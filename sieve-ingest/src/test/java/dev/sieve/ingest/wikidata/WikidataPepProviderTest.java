package dev.sieve.ingest.wikidata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameStrength;
import dev.sieve.core.model.RiskTopic;
import dev.sieve.core.model.SanctionedEntity;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class WikidataPepProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 3);
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-10-03T00:00:00Z"), ZoneOffset.UTC);
    private static final String WD = "http://www.wikidata.org/entity/";

    private static final WikidataPepProvider.Office CHANCELLOR =
            new WikidataPepProvider.Office("Q4970706", "Federal Chancellor of Germany", "DE", 1);
    private static final WikidataPepProvider.Office MDB =
            new WikidataPepProvider.Office("Q1939555", "member of the Bundestag", "DE", 2);

    @Test
    void shouldKeepTermsThatAreCurrentOrEndedRecently() throws Exception {
        Map<String, List<WikidataPepProvider.Term>> terms = new LinkedHashMap<>();
        List<WikidataPepProvider.Office> offices = List.of(CHANCELLOR, MDB);

        WikidataPepProvider.addTerm(
                terms, holder("Q567", "Q4970706", "2005-11-22", "2021-12-08"), offices, TODAY);
        WikidataPepProvider.addTerm(
                terms, holder("Q1", "Q1939555", "2009-10-27", "2017-10-24"), offices, TODAY);
        WikidataPepProvider.addTerm(
                terms, holder("Q2", "Q1939555", "1969-10-20", null), offices, TODAY);
        WikidataPepProvider.addTerm(
                terms, holder("Q3", "Q1939555", "2021-10-26", null), offices, TODAY);
        WikidataPepProvider.addTerm(
                terms, holder("Q4", "Q999", "2021-10-26", null), offices, TODAY);

        assertThat(terms).containsOnlyKeys("Q567", "Q3");
        assertThat(terms.get("Q567"))
                .singleElement()
                .satisfies(
                        t -> {
                            assertThat(t.office()).isEqualTo(CHANCELLOR);
                            assertThat(t.end()).isEqualTo(LocalDate.of(2021, 12, 8));
                        });
    }

    @Test
    void shouldPreferEnglishNameAndFallBackToMultilingualLabel() throws Exception {
        Map<String, WikidataPepProvider.Person> people = new LinkedHashMap<>();
        people.put("Q567", new WikidataPepProvider.Person("Q567"));
        people.put("Q3", new WikidataPepProvider.Person("Q3"));

        WikidataPepProvider.describe(
                people,
                row(
                        Map.of(
                                "person", WD + "Q567",
                                "mul", "Angela Dorothea Merkel",
                                "name", "Angela Merkel",
                                "alias", "Merkel",
                                "born", "1954-07-17T00:00:00Z",
                                "citizenship", "DE")));
        WikidataPepProvider.describe(
                people, row(Map.of("person", WD + "Q3", "mul", "Erika Mustermann")));
        WikidataPepProvider.describe(people, row(Map.of("person", WD + "Q99", "name", "Nobody")));

        assertThat(people.get("Q567").name).isEqualTo("Angela Merkel");
        assertThat(people.get("Q567").aliases).containsExactly("Merkel");
        assertThat(people.get("Q567").datesOfBirth).containsExactly(LocalDate.of(1954, 7, 17));
        assertThat(people.get("Q567").citizenships).containsExactly("DE");
        assertThat(people.get("Q3").name).isEqualTo("Erika Mustermann");
        assertThat(people).doesNotContainKey("Q99");
    }

    @Test
    void shouldBuildPepEntityWithOfficesTiersAndTerms() {
        WikidataPepProvider.Person person = new WikidataPepProvider.Person("Q567");
        person.name = "Angela Merkel";
        person.aliases.add("Merkel");
        person.aliases.add("Angela Dorothea Merkel");
        person.aliases.add("angela merkel");
        person.datesOfBirth.add(LocalDate.of(1954, 7, 17));
        person.terms.add(
                new WikidataPepProvider.Term(
                        CHANCELLOR, LocalDate.of(2005, 11, 22), LocalDate.of(2021, 12, 8)));
        person.terms.add(new WikidataPepProvider.Term(MDB, null, null));

        SanctionedEntity entity = WikidataPepProvider.toEntity(person);

        assertThat(entity.id()).isEqualTo("wd-Q567");
        assertThat(entity.entityType()).isEqualTo(EntityType.INDIVIDUAL);
        assertThat(entity.listSource()).isEqualTo(ListSource.WIKIDATA_PEP);
        assertThat(entity.topics()).containsExactly(RiskTopic.PEP);
        assertThat(entity.primaryName().fullName()).isEqualTo("Angela Merkel");
        assertThat(entity.aliases())
                .extracting(a -> a.fullName(), a -> a.strength())
                .containsExactly(
                        tuple("Merkel", NameStrength.WEAK),
                        tuple("Angela Dorothea Merkel", NameStrength.STRONG));
        assertThat(entity.nationalities()).containsExactly("DE");
        assertThat(entity.datesOfBirth()).containsExactly(LocalDate.of(1954, 7, 17));
        assertThat(entity.programs())
                .extracting(p -> p.code(), p -> p.name())
                .containsExactly(
                        tuple("Federal Chancellor of Germany", "PEP tier 1"),
                        tuple("member of the Bundestag", "PEP tier 2"));
        assertThat(entity.remarks())
                .isEqualTo(
                        "Federal Chancellor of Germany (DE, tier 1): 2005-11-22 to 2021-12-08\n"
                                + "member of the Bundestag (DE, tier 2): start unknown to present\n"
                                + "Source: https://www.wikidata.org/wiki/Q567");
    }

    @Test
    void shouldSkipPeopleWithoutAName() {
        WikidataPepProvider.Person person = new WikidataPepProvider.Person("Q3");
        person.terms.add(new WikidataPepProvider.Term(MDB, null, null));

        assertThat(WikidataPepProvider.toEntity(person)).isNull();
    }

    @Test
    void shouldRankCentralBankersAndMilitaryChiefsAsTierOne() {
        assertThat(WikidataPepProvider.EXTRA_CLASSES)
                .containsEntry("Q107363151", 1)
                .containsEntry("Q5097014", 1)
                .containsEntry("Q121998", 2)
                .containsEntry("Q26204040", 2);
    }

    @Test
    void shouldKeepTheHigherTierWhenAnOfficeIsFoundTwice() {
        Map<String, WikidataPepProvider.Office> offices = new LinkedHashMap<>();
        WikidataPepProvider.keep(offices, MDB);
        WikidataPepProvider.keep(
                offices, new WikidataPepProvider.Office("Q1939555", "member", "DE", 1));
        WikidataPepProvider.keep(offices, MDB);

        assertThat(offices.get("Q1939555").tier()).isEqualTo(1);
    }

    @Test
    void shouldReadDatesAndIdsLeniently() {
        assertThat(WikidataPepProvider.date("1954-07-17T00:00:00Z"))
                .isEqualTo(LocalDate.of(1954, 7, 17));
        assertThat(WikidataPepProvider.date("1954-00-00T00:00:00Z")).isNull();
        assertThat(WikidataPepProvider.date("-0044-03-15T00:00:00Z")).isNull();
        assertThat(WikidataPepProvider.date(null)).isNull();
        assertThat(WikidataPepProvider.id(WD + "Q567")).isEqualTo("Q567");
        assertThat(WikidataPepProvider.id(null)).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldQueryEachCountryAndSkipQueriesThatFail() throws Exception {
        HttpClient client = mock(HttpClient.class);
        AtomicBoolean throttled = new AtomicBoolean();
        when(client.send(any(), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(
                        invocation -> {
                            String query = query(invocation.getArgument(0));
                            if (query.startsWith("SELECT ?country ?iso")) {
                                // the first query is throttled once, then answered
                                if (throttled.compareAndSet(false, true)) {
                                    return response(429, new byte[0], Map.of("Retry-After", "0"));
                                }
                                return response(
                                        200,
                                        bindings(
                                                Map.of("country", WD + "Q183", "iso", "DE"),
                                                Map.of("country", WD + "Q30", "iso", "US"),
                                                Map.of("country", WD + "Q99999")));
                            }
                            // central bank governors everywhere; chiefs of defence fail
                            if (query.contains("wdt:P279* wd:Q107363151")) {
                                return response(
                                        200,
                                        bindings(
                                                Map.of(
                                                        "office", WD + "Q1200",
                                                        "country", WD + "Q183"),
                                                Map.of(
                                                        "office", WD + "Q1300",
                                                        "country", WD + "Q99999")));
                            }
                            if (query.contains("wdt:P279* wd:Q5097014")) {
                                return response(400, "timeout".getBytes());
                            }
                            if (query.startsWith("SELECT DISTINCT ?office ?country")) {
                                return response(200, bindings());
                            }
                            if (query.startsWith("SELECT ?office ?label")) {
                                return response(
                                        200,
                                        bindings(
                                                Map.of(
                                                        "office",
                                                        WD + "Q1200",
                                                        "label",
                                                        "President of the Deutsche Bundesbank")));
                            }
                            // Germany's other offices; the United States fails and is skipped
                            if (query.contains("?office wdt:P1001 wd:Q183")) {
                                return response(
                                        200,
                                        bindings(
                                                Map.of(
                                                        "office", WD + "Q4970706",
                                                        "class", WD + "Q48352",
                                                        "label", "Federal Chancellor of Germany"),
                                                Map.of(
                                                        "office", WD + "Q4970706",
                                                        "class", WD + "Q2285706",
                                                        "label", "Federal Chancellor of Germany")));
                            }
                            if (query.contains("?office wdt:P1001 wd:Q30")) {
                                return response(400, "bad query".getBytes());
                            }
                            if (query.contains("?person p:P39 ?held")) {
                                assertThat(query)
                                        .contains("VALUES ?office { wd:Q1200 wd:Q4970706 }");
                                return response(
                                        200,
                                        bindings(
                                                Map.of(
                                                        "person",
                                                        WD + "Q567",
                                                        "office",
                                                        WD + "Q4970706",
                                                        "start",
                                                        "2005-11-22T00:00:00Z",
                                                        "end",
                                                        "2021-12-08T00:00:00Z"),
                                                Map.of(
                                                        "person", WD + "Q600",
                                                        "office", WD + "Q1200",
                                                        "start", "2022-01-01T00:00:00Z")));
                            }
                            if (query.contains("VALUES ?person { wd:Q567 wd:Q600 }")) {
                                return response(
                                        200,
                                        bindings(
                                                Map.of(
                                                        "person", WD + "Q567",
                                                        "name", "Angela Merkel",
                                                        "citizenship", "DE"),
                                                Map.of(
                                                        "person",
                                                        WD + "Q600",
                                                        "name",
                                                        "Joachim Nagel")));
                            }
                            throw new AssertionError("unexpected query: " + query);
                        });

        List<SanctionedEntity> result =
                new WikidataPepProvider(URI.create("https://query.example/sparql"), client, CLOCK)
                        .fetch();

        assertThat(result).extracting(SanctionedEntity::id).containsExactly("wd-Q567", "wd-Q600");
        assertThat(result.get(1).programs())
                .singleElement()
                .satisfies(
                        p -> {
                            assertThat(p.code()).isEqualTo("President of the Deutsche Bundesbank");
                            assertThat(p.name()).isEqualTo("PEP tier 1");
                        });
        assertThat(result.get(1).nationalities()).containsExactly("DE");
        // countries (twice), seven extra classes, labels, two countries' offices, Germany's
        // holders, the people, and the request whose response is parsed
        verify(client, times(15)).send(any(), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void shouldFailWhenNoHoldersWereFound() {
        assertThatThrownBy(() -> new WikidataPepProvider().parseResponse("{}".getBytes()))
                .isInstanceOf(ListIngestionException.class)
                .hasMessageContaining("no office holders");
    }

    @Test
    void shouldAskForACountrysOfficesInAStableOrder() {
        assertThat(WikidataPepProvider.officesQuery("Q183"))
                .contains("VALUES ?class { wd:Q16533 wd:Q2285706 wd:Q48352 wd:Q486839 wd:Q83307 }")
                .contains("?office wdt:P1001 wd:Q183 . ?office wdt:P279* ?class .");
    }

    @Test
    void shouldLimitHoldersQueryToRecentTermsOfLivingPeople() {
        String query = WikidataPepProvider.holdersQuery(List.of(CHANCELLOR, MDB), TODAY);

        assertThat(query)
                .contains("VALUES ?office { wd:Q4970706 wd:Q1939555 }")
                .contains("FILTER NOT EXISTS { ?person wdt:P570 ?died }")
                .contains("\"2021-10-03T00:00:00Z\"^^xsd:dateTime");
    }

    /** The SPARQL query a request carries in its form body. */
    private static String query(HttpRequest request) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        request.bodyPublisher()
                .orElseThrow()
                .subscribe(
                        new Flow.Subscriber<ByteBuffer>() {
                            @Override
                            public void onSubscribe(Flow.Subscription subscription) {
                                subscription.request(Long.MAX_VALUE);
                            }

                            @Override
                            public void onNext(ByteBuffer item) {
                                byte[] bytes = new byte[item.remaining()];
                                item.get(bytes);
                                body.writeBytes(bytes);
                            }

                            @Override
                            public void onError(Throwable throwable) {}

                            @Override
                            public void onComplete() {}
                        });
        String form = body.toString(StandardCharsets.UTF_8);
        assertThat(form).startsWith("query=");
        return URLDecoder.decode(form.substring("query=".length()), StandardCharsets.UTF_8);
    }

    private static JsonNode holder(String person, String office, String start, String end) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("person", WD + person);
        fields.put("office", WD + office);
        if (start != null) {
            fields.put("start", start + "T00:00:00Z");
        }
        if (end != null) {
            fields.put("end", end + "T00:00:00Z");
        }
        return row(fields);
    }

    private static JsonNode row(Map<String, String> fields) {
        var node = MAPPER.createObjectNode();
        fields.forEach((k, v) -> node.putObject(k).put("value", v));
        return node;
    }

    @SafeVarargs
    private static byte[] bindings(Map<String, String>... rows) throws Exception {
        var root = MAPPER.createObjectNode();
        var array = root.putObject("results").putArray("bindings");
        for (Map<String, String> fields : rows) {
            array.add(row(fields));
        }
        return MAPPER.writeValueAsBytes(root);
    }

    private static HttpResponse<byte[]> response(int status, byte[] body) {
        return response(status, body, Map.of());
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<byte[]> response(
            int status, byte[] body, Map<String, String> headers) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        Map<String, List<String>> values = new LinkedHashMap<>();
        headers.forEach((k, v) -> values.put(k, List.of(v)));
        when(response.headers()).thenReturn(HttpHeaders.of(values, (a, b) -> true));
        return response;
    }
}
