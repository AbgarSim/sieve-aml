package dev.sieve.ingest.wikidata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.NameStrength;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.RiskTopic;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.SanctionsProgram;
import dev.sieve.ingest.AbstractListProvider;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

/**
 * Fetches politically exposed persons from Wikidata: living people who hold, or recently held, a
 * national public office.
 *
 * <p>Offices are the positions whose jurisdiction ({@code P1001}) is a sovereign state and that are
 * a kind of head of state, head of government, minister, central bank governor, chief of defence or
 * commander-in-chief (tier 1), or of member of parliament, judge, deputy minister, ambassador,
 * attorney general or party leader (tier 2). The heads of government and state of each country's
 * first-level regions, such as state governors and regional premiers, are tier 2 as well. Holders
 * come from "position held" ({@code P39}) statements with their start and end dates. A holder is
 * kept while in office, or for {@link #YEARS_AFTER_OFFICE} years after leaving it; open-ended terms
 * that began more than {@link #STALE_TERM_YEARS} years ago are treated as unrecorded ends and left
 * out.
 *
 * <p>The query service limits each query to a minute and each client to a few queries at once, so
 * the work is split into small queries that run {@link #PARALLEL_QUERIES} at a time: one query
 * lists the countries, one query per {@linkplain #EXTRA_CLASSES smaller class} finds its offices
 * everywhere and one finds the {@linkplain #REGIONAL_HEADS_QUERY regional heads}, then each country
 * gets one query for its other offices and queries for their holders, and the people found are
 * described in batches. A query that keeps failing is skipped and logged rather than failing the
 * whole list; a throttled one waits as long as the service asks. Every entity is tagged {@link
 * RiskTopic#PEP} with id {@code wd-<item id>}.
 *
 * @see <a href="https://www.wikidata.org/wiki/Wikidata:SPARQL_query_service">Wikidata Query
 *     Service</a>
 */
public final class WikidataPepProvider extends AbstractListProvider {

    private static final String DEFAULT_ENDPOINT = "https://query.wikidata.org/sparql";
    private static final String USER_AGENT =
            "sieve-aml/1.0 (https://github.com/AbgarSim/sieve-aml; sanctions and PEP screening)";

    static final int YEARS_AFTER_OFFICE = 5;
    static final int STALE_TERM_YEARS = 40;
    static final int PERSON_BATCH = 400;
    static final int OFFICE_BATCH = 300;

    /** Queries in flight at once; the query service allows a client five. */
    static final int PARALLEL_QUERIES = 4;

    private static final int ATTEMPTS = 3;
    private static final int THROTTLE_WAITS = 10;

    /** Office classes and the PEP tier their holders get. */
    static final Map<String, Integer> OFFICE_CLASSES =
            Map.of(
                    "Q48352", 1, // head of state
                    "Q2285706", 1, // head of government
                    "Q83307", 1, // minister
                    "Q486839", 2, // member of parliament
                    "Q16533", 2); // judge

    /**
     * Smaller office classes and their tiers, in query order. Each is looked up once for every
     * country, which is much faster than adding them to the per-country query.
     */
    static final Map<String, Integer> EXTRA_CLASSES = extraClasses();

    private static Map<String, Integer> extraClasses() {
        Map<String, Integer> classes = new LinkedHashMap<>();
        classes.put("Q107363151", 1); // central bank governor
        classes.put("Q5097014", 1); // chief of defence
        classes.put("Q380782", 1); // commander-in-chief
        classes.put("Q26204040", 2); // deputy minister
        classes.put("Q121998", 2); // ambassador
        classes.put("Q1501926", 2); // attorney general
        classes.put("Q1553195", 2); // party leader
        return Collections.unmodifiableMap(classes);
    }

    /** The tier of the heads of a country's first-level regions. */
    static final int REGIONAL_TIER = 2;

    /**
     * The offices held by the head of government ({@code P1313}) or head of state ({@code P1906})
     * of each first-level region ({@code P150}) of a country: state governors, regional premiers
     * and the like. Only an office whose own jurisdiction is that region counts, which leaves out
     * the generic classes, such as "governor", that some regions name instead of an office of their
     * own.
     */
    static final String REGIONAL_HEADS_QUERY =
            "SELECT DISTINCT ?office ?country WHERE { ?country wdt:P31 wd:Q3624078 ."
                    + " ?country wdt:P150 ?region . ?region wdt:P1313|wdt:P1906 ?office ."
                    + " ?office wdt:P1001 ?region . }";

    static final String COUNTRIES_QUERY =
            "SELECT ?country ?iso WHERE { ?country wdt:P31 wd:Q3624078 ."
                    + " OPTIONAL { ?country wdt:P297 ?iso } }";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Clock clock;
    private volatile Map<String, Person> people = Map.of();

    /** Creates a provider that reads from the public Wikidata Query Service. */
    public WikidataPepProvider() {
        super(
                ListSource.WIKIDATA_PEP,
                URI.create(DEFAULT_ENDPOINT),
                "application/sparql-results+json");
        this.clock = Clock.systemUTC();
    }

    /**
     * Creates a provider with a custom endpoint, HTTP client and clock (for testing).
     *
     * @param endpoint the SPARQL endpoint
     * @param httpClient the HTTP client to use for requests
     * @param clock the clock that decides which terms are recent
     */
    public WikidataPepProvider(URI endpoint, HttpClient httpClient, Clock clock) {
        super(
                ListSource.WIKIDATA_PEP,
                endpoint,
                "application/sparql-results+json",
                httpClient,
                Duration.ofSeconds(90));
        this.clock = clock;
    }

    /** An office in one country. */
    record Office(String id, String label, String country, int tier) {}

    /** A query that finds offices in every country at once, and the tier its offices get. */
    record OfficeQuery(String what, String sparql, int tier) {}

    /** One holder's term in an office. */
    record Term(Office office, LocalDate start, LocalDate end) {}

    /** A person and everything known about them. */
    static final class Person {
        final String id;
        final List<Term> terms = new ArrayList<>();
        String name;
        final Set<String> aliases = new LinkedHashSet<>();
        final Set<LocalDate> datesOfBirth = new LinkedHashSet<>();
        final Set<String> citizenships = new LinkedHashSet<>();

        Person(String id) {
            this.id = id;
        }
    }

    @Override
    protected HttpRequest buildRequest(HttpClient client, HttpRequest.Builder builder)
            throws IOException, InterruptedException {
        LocalDate today = LocalDate.now(clock);
        Instant started = Instant.now();
        Map<String, String> countries = new LinkedHashMap<>();
        for (JsonNode row : select(client, COUNTRIES_QUERY)) {
            String iso = value(row, "iso");
            if (iso != null) {
                countries.putIfAbsent(id(value(row, "country")), iso);
            }
        }
        if (countries.isEmpty()) {
            throw new IOException("Wikidata returned no countries");
        }

        Map<String, Person> found = new LinkedHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(PARALLEL_QUERIES);
        try {
            Map<String, Map<String, Office>> extras = extraOffices(client, pool, countries);

            Deque<String> isos = new ArrayDeque<>(countries.values());
            Deque<Future<Map<String, List<Term>>>> pending = new ArrayDeque<>();
            countries.forEach(
                    (countryId, iso) ->
                            pending.add(
                                    pool.submit(
                                            () ->
                                                    holders(
                                                            client,
                                                            countryId,
                                                            iso,
                                                            extras.getOrDefault(
                                                                    countryId, Map.of()),
                                                            today))));
            while (!pending.isEmpty()) {
                Map<String, List<Term>> terms = await(pending.poll(), "country " + isos.poll());
                if (terms != null) {
                    terms.forEach(
                            (person, held) ->
                                    found.computeIfAbsent(person, Person::new).terms.addAll(held));
                }
            }
            log.info(
                    "Wikidata PEPs: found office holders [countries={}, people={}, seconds={}]",
                    countries.size(),
                    found.size(),
                    Duration.between(started, Instant.now()).toSeconds());

            List<String> ids = new ArrayList<>(found.keySet());
            Deque<Future<List<JsonNode>>> batches = new ArrayDeque<>();
            for (int i = 0; i < ids.size(); i += PERSON_BATCH) {
                List<String> batch = ids.subList(i, Math.min(i + PERSON_BATCH, ids.size()));
                batches.add(pool.submit(() -> select(client, peopleQuery(batch))));
            }
            while (!batches.isEmpty()) {
                List<JsonNode> rows = await(batches.poll(), "a batch of people");
                if (rows != null) {
                    rows.forEach(row -> describe(found, row));
                }
            }
        } finally {
            // every result has been awaited by now; this only cancels what is left after a failure
            pool.shutdownNow();
        }
        log.info(
                "Wikidata PEPs: described people [people={}, seconds={}]",
                found.size(),
                Duration.between(started, Instant.now()).toSeconds());
        people = found;
        return post(builder, COUNTRIES_QUERY);
    }

    /**
     * Waits for a query's result. A query that failed is skipped and logged, with {@code what} it
     * was for, and yields {@code null}.
     */
    private <T> T await(Future<T> future, String what) throws InterruptedException {
        try {
            return future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException || cause instanceof InterruptedException) {
                log.warn("Wikidata PEPs: skipped {} [error={}]", what, cause.getMessage());
                return null;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(cause);
        }
    }

    /** Keeps an office, or its higher tier when it is already known. */
    static void keep(Map<String, Office> offices, Office office) {
        Office existing = offices.get(office.id());
        if (existing == null || office.tier() < existing.tier()) {
            offices.put(office.id(), office);
        }
    }

    /**
     * The queries that find offices in every country at once: the extra classes and regional heads.
     */
    static List<OfficeQuery> officeQueries() {
        List<OfficeQuery> queries = new ArrayList<>();
        EXTRA_CLASSES.forEach(
                (officeClass, tier) ->
                        queries.add(
                                new OfficeQuery(
                                        "office class " + officeClass,
                                        extraOfficesQuery(officeClass),
                                        tier)));
        queries.add(new OfficeQuery("regional heads", REGIONAL_HEADS_QUERY, REGIONAL_TIER));
        return queries;
    }

    /**
     * Finds the offices of the {@link #officeQueries()} in every country, keyed by country item id.
     * A query that fails is skipped and logged.
     */
    private Map<String, Map<String, Office>> extraOffices(
            HttpClient client, ExecutorService pool, Map<String, String> countries)
            throws InterruptedException {
        Map<OfficeQuery, Future<List<JsonNode>>> queries = new LinkedHashMap<>();
        for (OfficeQuery query : officeQueries()) {
            queries.put(query, pool.submit(() -> select(client, query.sparql())));
        }
        Map<String, String> officeCountry = new LinkedHashMap<>();
        Map<String, Integer> officeTier = new LinkedHashMap<>();
        for (Map.Entry<OfficeQuery, Future<List<JsonNode>>> query : queries.entrySet()) {
            List<JsonNode> rows = await(query.getValue(), query.getKey().what());
            if (rows == null) {
                continue;
            }
            int tier = query.getKey().tier();
            for (JsonNode row : rows) {
                String office = id(value(row, "office"));
                String country = id(value(row, "country"));
                if (office != null && countries.containsKey(country)) {
                    officeCountry.putIfAbsent(office, country);
                    officeTier.merge(office, tier, Math::min);
                }
            }
        }

        List<String> ids = new ArrayList<>(officeCountry.keySet());
        Deque<Future<List<JsonNode>>> batches = new ArrayDeque<>();
        for (int i = 0; i < ids.size(); i += PERSON_BATCH) {
            List<String> batch = ids.subList(i, Math.min(i + PERSON_BATCH, ids.size()));
            batches.add(pool.submit(() -> select(client, labelsQuery(batch))));
        }
        Map<String, String> labels = new LinkedHashMap<>();
        while (!batches.isEmpty()) {
            List<JsonNode> rows = await(batches.poll(), "office labels");
            if (rows != null) {
                rows.forEach(
                        row -> labels.putIfAbsent(id(value(row, "office")), value(row, "label")));
            }
        }

        Map<String, Map<String, Office>> offices = new LinkedHashMap<>();
        officeCountry.forEach(
                (office, country) ->
                        offices.computeIfAbsent(country, c -> new LinkedHashMap<>())
                                .put(
                                        office,
                                        new Office(
                                                office,
                                                labels.getOrDefault(office, office),
                                                countries.get(country),
                                                officeTier.get(office))));
        return offices;
    }

    static String extraOfficesQuery(String officeClass) {
        return "SELECT DISTINCT ?office ?country WHERE { ?office wdt:P279* wd:"
                + officeClass
                + " . ?office wdt:P1001 ?country . hint:Prior hint:runLast true ."
                + " ?country wdt:P31 wd:Q3624078 . }";
    }

    static String labelsQuery(List<String> offices) {
        String values = offices.stream().map(o -> "wd:" + o).collect(Collectors.joining(" "));
        return "SELECT ?office ?label WHERE { VALUES ?office { "
                + values
                + " } ?office rdfs:label ?label FILTER(LANG(?label) = \"en\") }";
    }

    /**
     * One country's offices, from the extra classes already known and the {@link #OFFICE_CLASSES},
     * and the terms of their holders, keyed by person.
     */
    private Map<String, List<Term>> holders(
            HttpClient client,
            String countryId,
            String iso,
            Map<String, Office> extras,
            LocalDate today)
            throws IOException, InterruptedException {
        Map<String, Office> byId = new LinkedHashMap<>();
        extras.values().forEach(o -> keep(byId, o));
        offices(client, countryId, iso).forEach(o -> keep(byId, o));
        List<Office> offices = new ArrayList<>(byId.values());
        Map<String, List<Term>> terms = new LinkedHashMap<>();
        for (int i = 0; i < offices.size(); i += OFFICE_BATCH) {
            List<Office> batch = offices.subList(i, Math.min(i + OFFICE_BATCH, offices.size()));
            for (JsonNode row : select(client, holdersQuery(batch, today))) {
                addTerm(terms, row, batch, today);
            }
        }
        return terms;
    }

    private List<Office> offices(HttpClient client, String countryId, String iso)
            throws IOException, InterruptedException {
        Map<String, Office> offices = new LinkedHashMap<>();
        for (JsonNode row : select(client, officesQuery(countryId))) {
            String office = id(value(row, "office"));
            Integer tier = OFFICE_CLASSES.get(id(value(row, "class")));
            String label = value(row, "label");
            if (office != null && tier != null) {
                keep(offices, new Office(office, label == null ? office : label, iso, tier));
            }
        }
        return new ArrayList<>(offices.values());
    }

    /** The query for one country's offices of the {@link #OFFICE_CLASSES}. */
    static String officesQuery(String countryId) {
        String classes =
                OFFICE_CLASSES.keySet().stream()
                        .sorted()
                        .map(c -> "wd:" + c)
                        .collect(Collectors.joining(" "));
        return "SELECT DISTINCT ?office ?class ?label WHERE { VALUES ?class { "
                + classes
                + " } ?office wdt:P1001 wd:"
                + countryId
                + " . ?office wdt:P279* ?class ."
                + " OPTIONAL { ?office rdfs:label ?label FILTER(LANG(?label) = \"en\") } }";
    }

    static String holdersQuery(List<Office> offices, LocalDate today) {
        String values = offices.stream().map(o -> "wd:" + o.id()).collect(Collectors.joining(" "));
        return "SELECT ?person ?office ?start ?end WHERE { VALUES ?office { "
                + values
                + " } ?person p:P39 ?held . ?held ps:P39 ?office . ?person wdt:P31 wd:Q5 ."
                + " OPTIONAL { ?held pq:P580 ?start } OPTIONAL { ?held pq:P582 ?end }"
                + " FILTER NOT EXISTS { ?person wdt:P570 ?died }"
                + " FILTER(!BOUND(?end) || ?end >= \""
                + today.minusYears(YEARS_AFTER_OFFICE)
                + "T00:00:00Z\"^^xsd:dateTime) }";
    }

    static String peopleQuery(List<String> ids) {
        String values = ids.stream().map(i -> "wd:" + i).collect(Collectors.joining(" "));
        return "SELECT ?person ?name ?mul ?alias ?born ?citizenship WHERE { VALUES ?person { "
                + values
                + " } OPTIONAL { ?person rdfs:label ?name FILTER(LANG(?name) = \"en\") }"
                + " OPTIONAL { ?person rdfs:label ?mul FILTER(LANG(?mul) = \"mul\") }"
                + " OPTIONAL { ?person skos:altLabel ?alias FILTER(LANG(?alias) = \"en\") }"
                + " OPTIONAL { ?person wdt:P569 ?born }"
                + " OPTIONAL { ?person wdt:P27 ?country . ?country wdt:P297 ?citizenship } }";
    }

    static void addTerm(
            Map<String, List<Term>> terms, JsonNode row, List<Office> offices, LocalDate today) {
        String person = id(value(row, "person"));
        String officeId = id(value(row, "office"));
        Office office =
                offices.stream().filter(o -> o.id().equals(officeId)).findFirst().orElse(null);
        if (person == null || office == null) {
            return;
        }
        LocalDate start = date(value(row, "start"));
        LocalDate end = date(value(row, "end"));
        if (end != null && end.isBefore(today.minusYears(YEARS_AFTER_OFFICE))) {
            return;
        }
        if (end == null && start != null && start.isBefore(today.minusYears(STALE_TERM_YEARS))) {
            return;
        }
        terms.computeIfAbsent(person, p -> new ArrayList<>()).add(new Term(office, start, end));
    }

    static void describe(Map<String, Person> people, JsonNode row) {
        Person person = people.get(id(value(row, "person")));
        if (person == null) {
            return;
        }
        String name = value(row, "name");
        String mul = value(row, "mul");
        if (name != null) {
            person.name = name;
        } else if (person.name == null && mul != null) {
            person.name = mul;
        }
        String alias = value(row, "alias");
        if (alias != null) {
            person.aliases.add(alias);
        }
        LocalDate born = date(value(row, "born"));
        if (born != null) {
            person.datesOfBirth.add(born);
        }
        String citizenship = value(row, "citizenship");
        if (citizenship != null) {
            person.citizenships.add(citizenship);
        }
    }

    @Override
    protected List<SanctionedEntity> parseResponse(byte[] responseBody)
            throws ListIngestionException {
        if (people.isEmpty()) {
            throw new ListIngestionException(
                    "Wikidata PEPs: no office holders found", ListSource.WIKIDATA_PEP);
        }
        List<SanctionedEntity> entities = new ArrayList<>();
        for (Person person : people.values()) {
            SanctionedEntity entity = toEntity(person);
            if (entity != null) {
                entities.add(entity);
            }
        }
        return entities;
    }

    static SanctionedEntity toEntity(Person person) {
        if (person.name == null || person.name.isBlank() || person.terms.isEmpty()) {
            return null;
        }
        NameInfo primary =
                new NameInfo(
                        person.name,
                        null,
                        null,
                        null,
                        null,
                        NameType.PRIMARY,
                        NameStrength.STRONG,
                        null);
        List<NameInfo> aliases = new ArrayList<>();
        for (String alias : person.aliases) {
            if (!alias.equalsIgnoreCase(person.name)) {
                NameStrength strength =
                        alias.strip().contains(" ") ? NameStrength.STRONG : NameStrength.WEAK;
                aliases.add(
                        new NameInfo(alias, null, null, null, null, NameType.AKA, strength, null));
            }
        }

        Map<String, SanctionsProgram> programs = new LinkedHashMap<>();
        StringJoiner remarks = new StringJoiner("\n");
        Set<String> countries = new LinkedHashSet<>(person.citizenships);
        for (Term term : person.terms) {
            Office office = term.office();
            programs.putIfAbsent(
                    office.id(),
                    new SanctionsProgram(
                            office.label(), "PEP tier " + office.tier(), ListSource.WIKIDATA_PEP));
            remarks.add(
                    office.label()
                            + " ("
                            + office.country()
                            + ", tier "
                            + office.tier()
                            + "): "
                            + (term.start() == null ? "start unknown" : term.start())
                            + " to "
                            + (term.end() == null ? "present" : term.end()));
            if (person.citizenships.isEmpty()) {
                countries.add(office.country());
            }
        }
        remarks.add("Source: https://www.wikidata.org/wiki/" + person.id);

        return new SanctionedEntity(
                "wd-" + person.id,
                EntityType.INDIVIDUAL,
                ListSource.WIKIDATA_PEP,
                primary,
                aliases,
                List.of(),
                List.of(),
                new ArrayList<>(countries),
                List.of(),
                new ArrayList<>(person.datesOfBirth),
                List.of(),
                remarks.toString(),
                new ArrayList<>(programs.values()),
                null,
                Instant.now(),
                Set.of(RiskTopic.PEP),
                List.of());
    }

    /**
     * Runs a query and returns its result rows. A server error is retried a few times and being
     * throttled waits as long as the service asks, up to {@link #THROTTLE_WAITS} times; any other
     * failure is thrown at once.
     */
    private List<JsonNode> select(HttpClient client, String query)
            throws IOException, InterruptedException {
        HttpRequest request =
                post(
                        HttpRequest.newBuilder()
                                .uri(sourceUri())
                                .timeout(Duration.ofSeconds(90))
                                .header("Accept", "application/sparql-results+json"),
                        query);
        int failures = 0;
        int waits = 0;
        while (true) {
            HttpResponse<byte[]> response =
                    client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            if (status == 200) {
                List<JsonNode> rows = new ArrayList<>();
                MAPPER.readTree(response.body())
                        .path("results")
                        .path("bindings")
                        .forEach(rows::add);
                return rows;
            }
            IOException failure = new IOException("Wikidata query failed [status=" + status + "]");
            if (status == 429) {
                if (++waits > THROTTLE_WAITS) {
                    throw failure;
                }
            } else if (status < 500 || ++failures >= ATTEMPTS) {
                throw failure;
            }
            long waitSeconds =
                    Math.min(
                            response.headers()
                                    .firstValueAsLong("Retry-After")
                                    .orElse(5L * (failures + waits)),
                            60);
            if (status == 429) {
                log.info(
                        "Wikidata PEPs: throttled by the query service [waitSeconds={}]",
                        waitSeconds);
            }
            Thread.sleep(Duration.ofSeconds(waitSeconds));
        }
    }

    private static HttpRequest post(HttpRequest.Builder builder, String query) {
        String form = "query=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
        return builder.setHeader("User-Agent", USER_AGENT)
                .setHeader("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
    }

    static String value(JsonNode row, String field) {
        JsonNode value = row.path(field).path("value");
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText().strip();
        return text.isEmpty() ? null : text;
    }

    /** Returns the item id ({@code Q123}) at the end of an entity URI. */
    static String id(String uri) {
        if (uri == null) {
            return null;
        }
        int slash = uri.lastIndexOf('/');
        return slash < 0 ? uri : uri.substring(slash + 1);
    }

    static LocalDate date(String value) {
        if (value == null || value.length() < 10 || value.startsWith("-")) {
            return null;
        }
        try {
            return LocalDate.parse(value.substring(0, 10));
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
