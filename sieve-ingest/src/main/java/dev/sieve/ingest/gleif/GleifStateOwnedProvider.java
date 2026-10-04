package dev.sieve.ingest.gleif;

import com.fasterxml.jackson.databind.JsonNode;
import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.EntityType;
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
import dev.sieve.ingest.gleif.GleifRegistry.Link;
import dev.sieve.ingest.gleif.GleifRegistry.Profile;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Fetches state-owned companies from the Global Legal Entity Identifier Foundation (GLEIF): the
 * companies whose direct or ultimate accounting parent, in the LEI register's relationship records,
 * is a government entity, together with those government owners.
 *
 * <p>GLEIF publishes the register as daily golden copy files and through a JSON API. The fetch
 * takes three steps: it pages through the API for every legal entity GLEIF classes as a {@linkplain
 * #GOVERNMENT_CATEGORY resident government entity} (states, regions, cities, sovereign and public
 * pension funds, public universities), downloads the day's relationship file, a zipped CSV of every
 * link between LEIs, and keeps the active {@linkplain GleifRegistry#CONSOLIDATION consolidation}
 * links whose parent is one of those entities, then asks the API for the companies at the other
 * end, {@link GleifRegistry#PAGE_SIZE} at a time.
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

    /** GLEIF's entity category for public bodies, from states and cities to public funds. */
    static final String GOVERNMENT_CATEGORY = "RESIDENT_GOVERNMENT_ENTITY";

    static final String OWNER_PROGRAM = "Government owner";
    static final String COMPANY_PROGRAM = "State-owned company";

    private final URI apiUri;
    private volatile Map<String, Record> records = Map.of();

    /** Creates a provider that reads the public GLEIF API and golden copy files. */
    public GleifStateOwnedProvider() {
        super(
                ListSource.GLEIF_STATE_OWNED,
                URI.create(GleifRegistry.DEFAULT_PUBLISHES),
                "application/json");
        this.apiUri = URI.create(GleifRegistry.DEFAULT_API);
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
        GleifRegistry registry = new GleifRegistry(client, sourceUri(), apiUri);
        Map<String, JsonNode> owners = registry.recordsInCategory(GOVERNMENT_CATEGORY);
        if (owners.isEmpty()) {
            throw new IOException("GLEIF returned no government entities");
        }
        log.info(
                "GLEIF state-owned: found government entities [entities={}, seconds={}]",
                owners.size(),
                Duration.between(started, Instant.now()).toSeconds());

        List<Link> links =
                GleifRegistry.consolidationLinks(registry.relationshipFile(), owners.keySet());
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
        Map<String, JsonNode> companies = registry.leiRecords(unknown);
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
            if (!record.parents.isEmpty()
                    && GleifRegistry.name(record.attributes) != null
                    && !GleifRegistry.inactive(record.attributes)) {
                kept.add(record.lei);
            }
        }
        for (Record record : records.values()) {
            if (GleifRegistry.name(record.attributes) != null
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
        Profile profile = GleifRegistry.profile(record.lei, record.attributes);
        List<Link> parents =
                record.parents.stream().filter(l -> kept.contains(l.parent())).toList();
        List<Link> children =
                record.children.stream().filter(l -> kept.contains(l.company())).toList();

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
                        .append(GleifRegistry.name(all.get(link.parent()).attributes))
                        .append(" (")
                        .append(link.parent())
                        .append(")");
                if (link.share() != null) {
                    line.append(", share ")
                            .append(GleifRegistry.percentageText(link.share()))
                            .append("%");
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
        if (profile.registration() != null) {
            remarks.add("LEI registration: " + profile.registration());
        }
        remarks.add("Source: " + GleifRegistry.RECORD_PAGE + record.lei);

        return new SanctionedEntity(
                "lei-" + record.lei,
                EntityType.ENTITY,
                ListSource.GLEIF_STATE_OWNED,
                new NameInfo(
                        profile.name(),
                        null,
                        null,
                        null,
                        null,
                        NameType.PRIMARY,
                        NameStrength.STRONG,
                        null),
                profile.aliases(),
                profile.addresses(),
                profile.identifiers(),
                profile.country() == null ? List.of() : List.of(profile.country()),
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
}
