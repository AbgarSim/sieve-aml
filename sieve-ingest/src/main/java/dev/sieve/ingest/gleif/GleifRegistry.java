package dev.sieve.ingest.gleif;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sieve.core.model.Address;
import dev.sieve.core.model.Identifier;
import dev.sieve.core.model.IdentifierType;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.NameStrength;
import dev.sieve.core.model.NameType;
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
import java.time.LocalDate;
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
 * What the GLEIF providers share: the Global Legal Entity Identifier Foundation's LEI records API,
 * its daily relationship file from the golden copy, and the way an LEI record becomes names,
 * addresses and identifiers.
 *
 * @see <a href="https://www.gleif.org/en/lei-data/gleif-api">GLEIF API</a>
 * @see <a href="https://www.gleif.org/en/lei-data/gleif-golden-copy">GLEIF golden copy files</a>
 */
final class GleifRegistry {

    static final String DEFAULT_API = "https://api.gleif.org/api/v1/lei-records";
    static final String DEFAULT_PUBLISHES =
            "https://goldencopy.gleif.org/api/v2/golden-copies/publishes?page=1&per_page=1";
    static final String RECORD_PAGE = "https://search.gleif.org/#/record/";

    /** The relationship types that make the parent an owner for accounting purposes. */
    static final Set<String> CONSOLIDATION =
            Set.of("IS_DIRECTLY_CONSOLIDATED_BY", "IS_ULTIMATELY_CONSOLIDATED_BY");

    /** Records per API page, the most the API returns. */
    static final int PAGE_SIZE = 200;

    private static final String USER_AGENT =
            "sieve-aml/1.0 (https://github.com/AbgarSim/sieve-aml; sanctions and PEP screening)";
    private static final int ATTEMPTS = 3;
    private static final int MAX_PAGES = 500;
    private static final int PERIODS = 5;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient client;
    private final URI publishesUri;
    private final URI apiUri;

    /**
     * @param client the HTTP client to use
     * @param publishesUri the golden copy listing that names the day's files
     * @param apiUri the LEI records API
     */
    GleifRegistry(HttpClient client, URI publishesUri, URI apiUri) {
        this.client = client;
        this.publishesUri = publishesUri;
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

    /** What an LEI record says about the legal entity itself, for an entity of any list. */
    record Profile(
            String name,
            List<NameInfo> aliases,
            List<Address> addresses,
            List<Identifier> identifiers,
            String country,
            String registration) {}

    /** Every legal entity in the given GLEIF category, keyed by LEI. */
    Map<String, JsonNode> recordsInCategory(String category)
            throws IOException, InterruptedException {
        Map<String, JsonNode> records = new LinkedHashMap<>();
        int lastPage = 1;
        for (int page = 1; page <= lastPage && page <= MAX_PAGES; page++) {
            JsonNode body =
                    json(
                            apiUri
                                    + query(
                                            "filter[entity.category]",
                                            category,
                                            "page[size]",
                                            String.valueOf(PAGE_SIZE),
                                            "page[number]",
                                            String.valueOf(page)));
            for (JsonNode record : body.path("data")) {
                String lei = text(record.path("id"));
                if (lei != null) {
                    records.put(lei, record.path("attributes"));
                }
            }
            lastPage = body.path("meta").path("pagination").path("lastPage").asInt(page);
        }
        return records;
    }

    /** The API records of the given LEIs, keyed by LEI, fetched {@link #PAGE_SIZE} at a time. */
    Map<String, JsonNode> leiRecords(Collection<String> leis)
            throws IOException, InterruptedException {
        Map<String, JsonNode> records = new LinkedHashMap<>();
        List<String> ids = new ArrayList<>(leis);
        for (int i = 0; i < ids.size(); i += PAGE_SIZE) {
            List<String> batch = ids.subList(i, Math.min(i + PAGE_SIZE, ids.size()));
            JsonNode body =
                    json(
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

    /** The day's relationship file, the zipped CSV that the golden copy listing names. */
    byte[] relationshipFile() throws IOException, InterruptedException {
        JsonNode listing = json(publishesUri.toString());
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
        return bytes(URI.create(url), "application/zip", Duration.ofSeconds(300));
    }

    /**
     * The active consolidation links in a relationship file whose parent is one of the given
     * entities. The file is a zipped CSV with a header; columns are found by name.
     */
    static List<Link> consolidationLinks(byte[] zip, Set<String> parents) throws IOException {
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

    /**
     * The names, addresses and identifiers of a record: the legal name, other and transliterated
     * names as aliases (a previous legal name as a former name), the legal and headquarters
     * addresses, and the LEI, registration number and BICs as identifiers. {@code null} when the
     * record has no legal name.
     */
    static Profile profile(String lei, JsonNode attributes) {
        String name = name(attributes);
        if (name == null) {
            return null;
        }
        JsonNode entity = attributes.path("entity");
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
        String registration = text(attributes.path("registration").path("status"));
        identifiers.add(
                new Identifier(
                        IdentifierType.LEI,
                        lei,
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
        for (JsonNode bic : attributes.path("bic")) {
            String code = text(bic);
            if (code != null) {
                identifiers.add(new Identifier(IdentifierType.SWIFT_BIC, code, country, null));
            }
        }
        return new Profile(name, aliases, addresses, identifiers, country, registration);
    }

    /** The legal name of a record's entity, or {@code null}. */
    static String name(JsonNode attributes) {
        return attributes == null
                ? null
                : text(attributes.path("entity").path("legalName").path("name"));
    }

    /** Whether GLEIF marks the record's entity as no longer active. */
    static boolean inactive(JsonNode attributes) {
        return "INACTIVE".equals(text(attributes.path("entity").path("status")));
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
    private JsonNode json(String url) throws IOException, InterruptedException {
        byte[] body =
                bytes(
                        URI.create(url),
                        "application/vnd.api+json, application/json",
                        Duration.ofSeconds(90));
        return MAPPER.readTree(body);
    }

    private byte[] bytes(URI uri, String accept, Duration timeout)
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

    /** A share without a trailing {@code .0} when it is whole. */
    static String percentageText(double share) {
        return share == Math.rint(share) ? String.valueOf((long) share) : String.valueOf(share);
    }
}
