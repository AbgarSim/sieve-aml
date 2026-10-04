package dev.sieve.ingest.gleif;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.Address;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.Identifier;
import dev.sieve.core.model.IdentifierType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.NameStrength;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.Relation;
import dev.sieve.core.model.RelationType;
import dev.sieve.core.model.RiskTopic;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.SanctionsProgram;
import dev.sieve.ingest.AbstractListProvider;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Fetches state-owned companies from the Global Legal Entity Identifier Foundation (GLEIF): the
 * companies whose direct or ultimate accounting parent, in the LEI register's relationship records,
 * is a government entity, together with those government owners.
 *
 * <p>GLEIF publishes the register as daily golden copy files and through a JSON API. The fetch
 * takes three steps: it pages through the API for every legal entity GLEIF classes as a {@linkplain
 * #GOVERNMENT_CATEGORY resident government entity} (states, regions, cities, sovereign and public
 * pension funds, public universities), downloads the day's relationship file, a zipped CSV of every
 * link between LEIs, and keeps the active {@linkplain #CONSOLIDATION consolidation} links whose
 * parent is one of those entities, then asks the API for the companies at the other end, {@link
 * #PAGE_SIZE} at a time.
 *
 * <p>Every company becomes an entity tagged {@link RiskTopic#STATE_OWNED} with id {@code
 * lei-<LEI>}, its LEI, registration number and BICs as identifiers, its legal and headquarters
 * addresses, its other and transliterated names as aliases, and a remark naming each government
 * parent. Each government owner is an entity with the same tag, the program {@value #OWNER_PROGRAM}
 * and an {@link RelationType#OWNERSHIP} relation to every company it consolidates, so each relation
 * points at an entity in the list. Companies GLEIF marks inactive are left out, as are links to
 * companies the API no longer returns. Most state ownership in the register is reported as an
 * exception (a parent without an LEI) rather than as a link, so this is a first set of state-owned
 * companies, not a census.
 *
 * @see <a href="https://www.gleif.org/en/lei-data/gleif-golden-copy">GLEIF golden copy files</a>
 * @see <a href="https://www.gleif.org/en/lei-data/gleif-api">GLEIF API</a>
 */
public final class GleifStateOwnedProvider extends AbstractListProvider {

    private static final String DEFAULT_API = "https://api.gleif.org/api/v1/lei-records";
    private static final String DEFAULT_PUBLISHES =
            "https://goldencopy.gleif.org/api/v2/golden-copies/publishes?page=1&per_page=1";
    private static final String RECORD_PAGE = "https://search.gleif.org/#/record/";
    private static final String USER_AGENT =
            "sieve-aml/1.0 (https://github.com/AbgarSim/sieve-aml; sanctions and PEP screening)";

    /** GLEIF's entity category for public bodies, from states and cities to public funds. */
    static final String GOVERNMENT_CATEGORY = "RESIDENT_GOVERNMENT_ENTITY";

    /** The relationship types that make the parent an owner for accounting purposes. */
    static final Set<String> CONSOLIDATION =
            Set.of("IS_DIRECTLY_CONSOLIDATED_BY", "IS_ULTIMATELY_CONSOLIDATED_BY");

    /** Records per API page, the most the API returns. */
    static final int PAGE_SIZE = 200;

    static final String OWNER_PROGRAM = "Government owner";
    static final String COMPANY_PROGRAM = "State-owned company";

    private static final int ATTEMPTS = 3;
    private static final int MAX_PAGES = 500;
    private static final int PERIODS = 5;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final URI apiUri;
    private volatile Map<String, Record> records = Map.of();

    /** Creates a provider that reads the public GLEIF API and golden copy files. */
    public GleifStateOwnedProvider() {
        super(ListSource.GLEIF_STATE_OWNED, URI.create(DEFAULT_PUBLISHES), "application/json");
        this.apiUri = URI.create(DEFAULT_API);
    }

    /**
     * Creates a provider with custom endpoints and HTTP client (for testing).
     *
     * @param publishesUri the golden copy listing that names the day's relationship file
     * @param apiUri the LEI records API
     * @param httpClient the HTTP client to use for requests
     */
    public GleifStateOwnedProvider(URI publishesUri, URI apiUri, HttpClient httpClient) {
        super(
                ListSource.GLEIF_STATE_OWNED,
                publishesUri,
                "application/json",
                httpClient,
                Duration.ofSeconds(120));
        this.apiUri = apiUri;
    }

    /** One consolidation link from a company to its parent, as the relationship file states it. */
    record Link(
            String company,
            String parent,
            String type,
            LocalDate start,
            LocalDate end,
            Double share) {

        boolean ultimate() {
            return "IS_ULTIMATELY_CONSOLIDATED_BY".equals(type);
        }

        /** The parent's role towards the company. */
        String role() {
            return ultimate() ? "ultimate parent" : "direct parent";
        }
    }

    /** A legal entity's API record with the links it takes part in. */
    static final class Record {
        final String lei;
        final JsonNode attributes;
        final List<Link> parents = new ArrayList<>();
        final List<Link> children = new ArrayList<>();

        Record(String lei, JsonNode attributes) {
            this.lei = lei;
            this.attributes = attributes;
        }
    }

    @Override
    protected HttpRequest buildRequest(HttpClient client, HttpRequest.Builder builder)
            throws IOException, InterruptedException {
        Instant started = Instant.now();
        Map<String, JsonNode> owners = governmentEntities(client);
        if (owners.isEmpty()) {
            throw new IOException("GLEIF returned no government entities");
        }
        log.info(
                "GLEIF state-owned: found government entities [entities={}, seconds={}]",
                owners.size(),
                Duration.between(started, Instant.now()).toSeconds());

        List<Link> links = consolidationLinks(client, relationshipFileUri(client), owners.keySet());
        log.info(
                "GLEIF state-owned: read relationship file [links={}, seconds={}]",
                links.size(),
                Duration.between(started, Instant.now()).toSeconds());

        Set<String> unknown = new LinkedHashSet<>();
        for (Link link : links) {
            if (!owners.containsKey(link.company())) {
                unknown.add(link.company());
            }
        }
        Map<String, JsonNode> companies = leiRecords(client, unknown);
        Map<String, Record> found = new LinkedHashMap<>();
        for (Link link : links) {
            JsonNode company = companies.getOrDefault(link.company(), owners.get(link.company()));
            if (company == null) {
                continue;
            }
            found.computeIfAbsent(link.parent(), lei -> new Record(lei, owners.get(lei)))
                    .children
                    .add(link);
            found.computeIfAbsent(link.company(), lei -> new Record(lei, company))
                    .parents
                    .add(link);
        }
        log.info(
                "GLEIF state-owned: described companies [companies={}, owners={}, seconds={}]",
                companies.size(),
                found.size() - companies.size(),
                Duration.between(started, Instant.now()).toSeconds());
        records = found;
        return builder.GET().build();
    }

    /** Every legal entity in the {@link #GOVERNMENT_CATEGORY}, keyed by LEI. */
    private Map<String, JsonNode> governmentEntities(HttpClient client)
            throws IOException, InterruptedException {
        Map<String, JsonNode> entities = new LinkedHashMap<>();
        int lastPage = 1;
        for (int page = 1; page <= lastPage && page <= MAX_PAGES; page++) {
            JsonNode body =
                    json(
                            client,
                            apiUri
                                    + query(
                                            "filter[entity.category]",
                                            GOVERNMENT_CATEGORY,
                                            "page[size]",
                                            String.valueOf(PAGE_SIZE),
                                            "page[number]",
                                            String.valueOf(page)));
            for (JsonNode record : body.path("data")) {
                String lei = text(record.path("id"));
                if (lei != null) {
                    entities.put(lei, record.path("attributes"));
                }
            }
            lastPage = body.path("meta").path("pagination").path("lastPage").asInt(page);
        }
        return entities;
    }

    /** The day's relationship file, from the golden copy listing. */
    private URI relationshipFileUri(HttpClient client) throws IOException, InterruptedException {
        JsonNode listing = json(client, sourceUri().toString());
        String url =
                text(
                        listing.path("data")
                                .path(0)
                                .path("rr")
                                .path("full_file")
                                .path("csv")
                                .path("url"));
        if (url == null) {
            throw new IOException("GLEIF golden copy listing names no relationship file");
        }
        return URI.create(url);
    }

    /**
     * The active consolidation links in the relationship file whose parent is one of the given
     * entities. The file is a zipped CSV with a header; columns are found by name.
     */
    private List<Link> consolidationLinks(HttpClient client, URI file, Set<String> parents)
            throws IOException, InterruptedException {
        byte[] zip = bytes(client, file, "application/zip", Duration.ofSeconds(300));
        List<Link> links = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry = in.getNextEntry();
            if (entry == null) {
                throw new IOException("GLEIF relationship file is empty");
            }
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String header = reader.readLine();
            if (header == null) {
                throw new IOException("GLEIF relationship file has no header");
            }
            Map<String, Integer> columns = new LinkedHashMap<>();
            List<String> names = csvFields(header.replace("﻿", ""));
            for (int i = 0; i < names.size(); i++) {
                columns.put(names.get(i), i);
            }
            for (String required :
                    List.of(
                            "Relationship.StartNode.NodeID",
                            "Relationship.EndNode.NodeID",
                            "Relationship.RelationshipType",
                            "Relationship.RelationshipStatus")) {
                if (!columns.containsKey(required)) {
                    throw new IOException(
                            "GLEIF relationship file lacks column "
                                    + required
                                    + "; it may have changed");
                }
            }
            String line;
            while ((line = reader.readLine()) != null) {
                Link link = link(csvFields(line), columns, parents);
                if (link != null) {
                    links.add(link);
                }
            }
        }
        return links;
    }

    /** One row of the relationship file as a link, or {@code null} when it is not one to keep. */
    static Link link(List<String> row, Map<String, Integer> columns, Set<String> parents) {
        String type = field(row, columns, "Relationship.RelationshipType");
        String status = field(row, columns, "Relationship.RelationshipStatus");
        String company = field(row, columns, "Relationship.StartNode.NodeID");
        String parent = field(row, columns, "Relationship.EndNode.NodeID");
        if (type == null
                || !CONSOLIDATION.contains(type)
                || !"ACTIVE".equals(status)
                || company == null
                || parent == null
                || company.equals(parent)
                || !parents.contains(parent)) {
            return null;
        }
        LocalDate start = null;
        LocalDate end = null;
        Double share = null;
        for (int i = 1; i <= PERIODS; i++) {
            String kind = field(row, columns, "Relationship.Period." + i + ".periodType");
            if ("RELATIONSHIP_PERIOD".equals(kind)) {
                start = date(field(row, columns, "Relationship.Period." + i + ".startDate"));
                end = date(field(row, columns, "Relationship.Period." + i + ".endDate"));
            }
            String method =
                    field(row, columns, "Relationship.Quantifiers." + i + ".MeasurementMethod");
            String units =
                    field(row, columns, "Relationship.Quantifiers." + i + ".QuantifierUnits");
            if ("ACCOUNTING_CONSOLIDATION".equals(method) && "PERCENTAGE".equals(units)) {
                share =
                        percentage(
                                field(
                                        row,
                                        columns,
                                        "Relationship.Quantifiers." + i + ".QuantifierAmount"));
            }
        }
        if (start != null && end != null && end.isBefore(start)) {
            end = null;
        }
        return new Link(company, parent, type, start, end, share);
    }

    /** The API records of the given LEIs, keyed by LEI, fetched {@link #PAGE_SIZE} at a time. */
    private Map<String, JsonNode> leiRecords(HttpClient client, Collection<String> leis)
            throws IOException, InterruptedException {
        Map<String, JsonNode> records = new LinkedHashMap<>();
        List<String> ids = new ArrayList<>(leis);
        for (int i = 0; i < ids.size(); i += PAGE_SIZE) {
            List<String> batch = ids.subList(i, Math.min(i + PAGE_SIZE, ids.size()));
            JsonNode body =
                    json(
                            client,
                            apiUri
                                    + query(
                                            "filter[lei]",
                                            String.join(",", batch),
                                            "page[size]",
                                            String.valueOf(PAGE_SIZE)));
            for (JsonNode record : body.path("data")) {
                String lei = text(record.path("id"));
                if (lei != null) {
                    records.put(lei, record.path("attributes"));
                }
            }
        }
        return records;
    }

    @Override
    protected List<SanctionedEntity> parseResponse(byte[] responseBody)
            throws ListIngestionException {
        if (records.isEmpty()) {
            throw new ListIngestionException(
                    "GLEIF state-owned: no government-owned companies found",
                    ListSource.GLEIF_STATE_OWNED);
        }
        Set<String> kept = new LinkedHashSet<>();
        for (Record record : records.values()) {
            if (!record.parents.isEmpty() && name(record) != null && !inactive(record)) {
                kept.add(record.lei);
            }
        }
        for (Record record : records.values()) {
            if (name(record) != null
                    && record.children.stream().anyMatch(link -> kept.contains(link.company()))) {
                kept.add(record.lei);
            }
        }
        List<SanctionedEntity> entities = new ArrayList<>();
        for (Record record : records.values()) {
            if (kept.contains(record.lei)) {
                entities.add(toEntity(record, records, kept));
            }
        }
        return entities;
    }

    /**
     * Builds the entity for a record whose name is known. Links to companies that are not in the
     * list are left out, so every relation resolves.
     */
    static SanctionedEntity toEntity(Record record, Map<String, Record> all, Set<String> kept) {
        JsonNode entity = record.attributes.path("entity");
        String name = name(record);
        List<Link> parents =
                record.parents.stream().filter(l -> kept.contains(l.parent())).toList();
        List<Link> children =
                record.children.stream().filter(l -> kept.contains(l.company())).toList();

        List<NameInfo> aliases = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        seen.add(name.toLowerCase(Locale.ROOT));
        for (String field : List.of("otherNames", "transliteratedOtherNames")) {
            for (JsonNode other : entity.path(field)) {
                String alias = text(other.path("name"));
                if (alias != null && seen.add(alias.toLowerCase(Locale.ROOT))) {
                    NameType type =
                            "PREVIOUS_LEGAL_NAME".equals(text(other.path("type")))
                                    ? NameType.FKA
                                    : NameType.AKA;
                    aliases.add(
                            new NameInfo(
                                    alias,
                                    null,
                                    null,
                                    null,
                                    null,
                                    type,
                                    NameStrength.STRONG,
                                    null));
                }
            }
        }

        List<Address> addresses = new ArrayList<>();
        for (String field : List.of("legalAddress", "headquartersAddress")) {
            Address address = address(entity.path(field));
            if (address != null && !addresses.contains(address)) {
                addresses.add(address);
            }
        }

        String country = country(entity);
        List<Identifier> identifiers = new ArrayList<>();
        String registration = text(record.attributes.path("registration").path("status"));
        identifiers.add(
                new Identifier(
                        IdentifierType.LEI,
                        record.lei,
                        null,
                        registration == null ? null : "Registration " + registration));
        String registeredAs = text(entity.path("registeredAs"));
        if (registeredAs != null && !registeredAs.equalsIgnoreCase("n/a")) {
            String authority = text(entity.path("registeredAt").path("id"));
            identifiers.add(
                    new Identifier(
                            IdentifierType.REGISTRATION_NUMBER,
                            registeredAs,
                            country,
                            authority == null ? null : "Registration authority " + authority));
        }
        for (JsonNode bic : record.attributes.path("bic")) {
            String code = text(bic);
            if (code != null) {
                identifiers.add(new Identifier(IdentifierType.SWIFT_BIC, code, country, null));
            }
        }

        List<SanctionsProgram> programs = new ArrayList<>();
        StringJoiner remarks = new StringJoiner("\n");
        List<Relation> relations = new ArrayList<>();
        Instant listed = null;
        if (!children.isEmpty()) {
            programs.add(
                    new SanctionsProgram(
                            OWNER_PROGRAM,
                            "Government entity that consolidates companies",
                            ListSource.GLEIF_STATE_OWNED));
            long count = children.stream().map(Link::company).distinct().count();
            remarks.add(
                    "Government entity; "
                            + (count == 1 ? "1 company is" : count + " companies are")
                            + " consolidated into its accounts");
            for (Link link : children) {
                relations.add(
                        new Relation(
                                RelationType.OWNERSHIP,
                                "lei-" + link.company(),
                                link.role(),
                                link.share(),
                                link.start(),
                                link.end()));
            }
        }
        if (!parents.isEmpty()) {
            programs.add(
                    new SanctionsProgram(
                            COMPANY_PROGRAM,
                            "Company consolidated by a government entity",
                            ListSource.GLEIF_STATE_OWNED));
            for (Link link : parents) {
                StringBuilder line = new StringBuilder();
                line.append(link.ultimate() ? "Ultimately" : "Directly")
                        .append(" consolidated by ")
                        .append(name(all.get(link.parent())))
                        .append(" (")
                        .append(link.parent())
                        .append(")");
                if (link.share() != null) {
                    line.append(", share ").append(percentageText(link.share())).append("%");
                }
                if (link.start() != null) {
                    line.append(", since ").append(link.start());
                }
                remarks.add(line.toString());
                if (link.start() != null
                        && (listed == null
                                || link.start()
                                        .atStartOfDay(ZoneOffset.UTC)
                                        .toInstant()
                                        .isBefore(listed))) {
                    listed = link.start().atStartOfDay(ZoneOffset.UTC).toInstant();
                }
            }
        }
        if (registration != null) {
            remarks.add("LEI registration: " + registration);
        }
        remarks.add("Source: " + RECORD_PAGE + record.lei);

        return new SanctionedEntity(
                "lei-" + record.lei,
                EntityType.ENTITY,
                ListSource.GLEIF_STATE_OWNED,
                new NameInfo(
                        name, null, null, null, null, NameType.PRIMARY, NameStrength.STRONG, null),
                aliases,
                addresses,
                identifiers,
                country == null ? List.of() : List.of(country),
                List.of(),
                List.of(),
                List.of(),
                remarks.toString(),
                programs,
                listed,
                Instant.now(),
                Set.of(RiskTopic.STATE_OWNED),
                relations);
    }

    static String name(Record record) {
        return record == null
                ? null
                : text(record.attributes.path("entity").path("legalName").path("name"));
    }

    static boolean inactive(Record record) {
        return "INACTIVE".equals(text(record.attributes.path("entity").path("status")));
    }

    /**
     * The country of the legal jurisdiction ({@code US-DE} gives {@code US}), else of the address.
     */
    static String country(JsonNode entity) {
        String jurisdiction = text(entity.path("jurisdiction"));
        if (jurisdiction != null && jurisdiction.length() >= 2) {
            String code = jurisdiction.substring(0, 2).toUpperCase(Locale.ROOT);
            if (code.chars().allMatch(Character::isLetter)) {
                return code;
            }
        }
        return text(entity.path("legalAddress").path("country"));
    }

    static Address address(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        StringJoiner street = new StringJoiner(", ");
        for (JsonNode line : node.path("addressLines")) {
            String part = text(line);
            if (part != null) {
                street.add(part);
            }
        }
        String city = text(node.path("city"));
        String region = text(node.path("region"));
        String postal = text(node.path("postalCode"));
        String country = text(node.path("country"));
        String streetText = street.length() == 0 ? null : street.toString();
        if (streetText == null
                && city == null
                && region == null
                && postal == null
                && country == null) {
            return null;
        }
        StringJoiner full = new StringJoiner(", ");
        for (String part : new String[] {streetText, postal, city, region, country}) {
            if (part != null) {
                full.add(part);
            }
        }
        return new Address(streetText, city, region, postal, country, full.toString());
    }

    /** Splits one CSV line into fields, honouring quotes and doubled quotes. */
    static List<String> csvFields(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(c);
            }
        }
        fields.add(field.toString());
        return fields;
    }

    private static String field(List<String> row, Map<String, Integer> columns, String name) {
        Integer index = columns.get(name);
        if (index == null || index >= row.size()) {
            return null;
        }
        String value = row.get(index).strip();
        return value.isEmpty() ? null : value;
    }

    /** Runs a GET for JSON, retrying a dropped connection, a server error or being throttled. */
    private JsonNode json(HttpClient client, String url) throws IOException, InterruptedException {
        byte[] body =
                bytes(
                        client,
                        URI.create(url),
                        "application/vnd.api+json, application/json",
                        Duration.ofSeconds(90));
        return MAPPER.readTree(body);
    }

    private byte[] bytes(HttpClient client, URI uri, String accept, Duration timeout)
            throws IOException, InterruptedException {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(uri)
                        .timeout(timeout)
                        .header("Accept", accept)
                        .header("User-Agent", USER_AGENT)
                        .GET()
                        .build();
        for (int attempt = 1; ; attempt++) {
            HttpResponse<byte[]> response;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            } catch (IOException e) {
                if (attempt >= ATTEMPTS) {
                    throw e;
                }
                Thread.sleep(Duration.ofSeconds(2L * attempt));
                continue;
            }
            int status = response.statusCode();
            if (status == 200) {
                return response.body();
            }
            IOException failure =
                    new IOException(
                            "GLEIF request failed [status=" + status + ", uri=" + uri + "]");
            if ((status != 429 && status < 500) || attempt >= ATTEMPTS) {
                throw failure;
            }
            long wait =
                    Math.min(
                            response.headers().firstValueAsLong("Retry-After").orElse(2L * attempt),
                            60);
            Thread.sleep(Duration.ofSeconds(wait));
        }
    }

    /** A query string whose keys and values are URL-encoded, brackets included. */
    static String query(String... keysAndValues) {
        StringJoiner query = new StringJoiner("&", "?", "");
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            query.add(
                    URLEncoder.encode(keysAndValues[i], StandardCharsets.UTF_8)
                            + "="
                            + URLEncoder.encode(keysAndValues[i + 1], StandardCharsets.UTF_8));
        }
        return query.toString();
    }

    static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || node.isContainerNode()) {
            return null;
        }
        String text = node.asText().strip();
        return text.isEmpty() ? null : text;
    }

    static LocalDate date(String value) {
        if (value == null || value.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(value.substring(0, 10));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static Double percentage(String value) {
        if (value == null) {
            return null;
        }
        try {
            double share = Double.parseDouble(value);
            return share < 0 || share > 100 ? null : share;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String percentageText(double share) {
        return share == Math.rint(share) ? String.valueOf((long) share) : String.valueOf(share);
    }
}
