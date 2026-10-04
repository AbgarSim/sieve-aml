package dev.sieve.ingest.gleif;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.IdentifierType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.Relation;
import dev.sieve.core.model.RelationType;
import dev.sieve.core.model.RiskTopic;
import dev.sieve.core.model.SanctionedEntity;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class GleifStateOwnedProviderTest {

    private static final URI PUBLISHES = URI.create("https://golden.test/publishes?page=1");
    private static final URI API = URI.create("https://api.test/lei-records");
    private static final String FILE = "https://files.test/rr-golden-copy.csv.zip";
    private static final String GOV1 = "5493000000000000GOV1";
    private static final String C1 = "5493000000000000C001";

    @Test
    @SuppressWarnings("unchecked")
    void shouldLinkStateOwnedCompaniesToTheirGovernmentOwners() throws Exception {
        HttpClient client = mock(HttpClient.class);
        AtomicBoolean failed = new AtomicBoolean();
        when(client.send(any(), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(
                        invocation -> {
                            HttpRequest request = invocation.getArgument(0);
                            String url =
                                    URLDecoder.decode(
                                            request.uri().toString(), StandardCharsets.UTF_8);
                            if (url.startsWith(PUBLISHES.toString())) {
                                return response(
                                        200,
                                        ("{\"data\":[{\"publish_date\":\"2026-10-04 16:00:00\","
                                                        + "\"rr\":{\"full_file\":{\"csv\":{\"url\":\""
                                                        + FILE
                                                        + "\",\"record_count\":8}}}}]}")
                                                .getBytes(StandardCharsets.UTF_8));
                            }
                            // the government page fails once with a server error, then answers
                            if (url.contains(
                                    "filter[entity.category]=RESIDENT_GOVERNMENT_ENTITY")) {
                                assertThat(url)
                                        .contains("page[size]=200")
                                        .contains("page[number]=1");
                                if (failed.compareAndSet(false, true)) {
                                    return response(503, new byte[0], Map.of("Retry-After", "0"));
                                }
                                return response(200, resource("gleif_government_sample.json"));
                            }
                            if (url.equals(FILE)) {
                                assertThat(request.headers().firstValue("Accept"))
                                        .hasValue("application/zip");
                                return response(200, zip(resource("gleif_rr_sample.csv")));
                            }
                            if (url.contains("filter[lei]=")) {
                                // companies that are not government entities themselves
                                assertThat(url)
                                        .contains(
                                                "filter[lei]="
                                                        + C1
                                                        + ",5493000000000000C005"
                                                        + ",5493000000000000C006"
                                                        + ",5493000000000000C007&page[size]=200")
                                        .doesNotContain(GOV1);
                                return response(200, resource("gleif_companies_sample.json"));
                            }
                            throw new AssertionError("unexpected request " + url);
                        });
        GleifStateOwnedProvider provider = new GleifStateOwnedProvider(PUBLISHES, API, client);

        List<SanctionedEntity> result = provider.fetch();

        // the owner and its one active company; the inactive company, the fund it manages, the
        // company the API no longer knows and the owner left with no company are all out
        assertThat(result)
                .extracting(SanctionedEntity::id)
                .containsExactly("lei-" + GOV1, "lei-" + C1);

        SanctionedEntity owner = result.get(0);
        assertThat(owner.entityType()).isEqualTo(EntityType.ENTITY);
        assertThat(owner.primaryName().fullName()).isEqualTo("République d'Exemple");
        assertThat(owner.aliases())
                .extracting(a -> a.fullName())
                .containsExactly("Republic of Example");
        assertThat(owner.topics()).containsExactly(RiskTopic.STATE_OWNED);
        assertThat(owner.programs())
                .extracting(p -> p.code())
                .containsExactly(GleifStateOwnedProvider.OWNER_PROGRAM);
        assertThat(owner.relations())
                .containsExactly(
                        new Relation(
                                RelationType.OWNERSHIP,
                                "lei-" + C1,
                                "ultimate parent",
                                100.0,
                                LocalDate.of(2015, 3, 1),
                                null),
                        new Relation(
                                RelationType.OWNERSHIP,
                                "lei-" + C1,
                                "direct parent",
                                null,
                                LocalDate.of(2015, 3, 1),
                                null));
        assertThat(owner.addresses()).hasSize(1);
        assertThat(owner.identifiers())
                .extracting(i -> i.type(), i -> i.value())
                .containsExactly(tuple(IdentifierType.LEI, GOV1));
        assertThat(owner.remarks())
                .contains("Government entity; 1 company is consolidated into its accounts")
                .contains("Source: https://search.gleif.org/#/record/" + GOV1);
        assertThat(owner.nationalities()).containsExactly("FR");
        assertThat(owner.listedDate()).isNull();

        SanctionedEntity company = result.get(1);
        assertThat(company.primaryName().fullName()).isEqualTo("Exemple Énergie SA");
        assertThat(company.aliases())
                .extracting(a -> a.fullName(), a -> a.nameType())
                .containsExactly(
                        tuple("Énergie d'Exemple SA", NameType.FKA),
                        tuple("Exemple Energie SA", NameType.AKA));
        assertThat(company.addresses())
                .extracting(a -> a.city(), a -> a.street(), a -> a.fullAddress())
                .containsExactly(
                        tuple(
                                "Paris",
                                "1 place de l'Énergie",
                                "1 place de l'Énergie, 75001, Paris, FR-75, FR"),
                        tuple(
                                "Lyon",
                                "10 quai Exemple, Bâtiment B",
                                "10 quai Exemple, Bâtiment B, 69002, Lyon, FR-69, FR"));
        assertThat(company.identifiers())
                .extracting(
                        i -> i.type(), i -> i.value(), i -> i.issuingCountry(), i -> i.remarks())
                .containsExactly(
                        tuple(IdentifierType.LEI, C1, null, "Registration LAPSED"),
                        tuple(
                                IdentifierType.REGISTRATION_NUMBER,
                                "123 456 789",
                                "FR",
                                "Registration authority RA000189"),
                        tuple(IdentifierType.SWIFT_BIC, "EXEMFRPPXXX", "FR", null));
        assertThat(company.nationalities()).containsExactly("FR");
        assertThat(company.topics()).containsExactly(RiskTopic.STATE_OWNED);
        assertThat(company.programs())
                .extracting(p -> p.code(), p -> p.name())
                .containsExactly(
                        tuple(
                                GleifStateOwnedProvider.COMPANY_PROGRAM,
                                "Company consolidated by a government entity"));
        assertThat(company.relations()).isEmpty();
        assertThat(company.remarks())
                .isEqualTo(
                        "Ultimately consolidated by République d'Exemple ("
                                + GOV1
                                + "), share 100%, since 2015-03-01\n"
                                + "Directly consolidated by République d'Exemple ("
                                + GOV1
                                + "), since 2015-03-01\n"
                                + "LEI registration: LAPSED\n"
                                + "Source: https://search.gleif.org/#/record/"
                                + C1);
        assertThat(company.listedDate()).isEqualTo(Instant.parse("2015-03-01T00:00:00Z"));
        // the listing, the government page (twice), the file, the companies, and the request
        // whose response is parsed
        verify(client, times(6)).send(any(), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void shouldKeepOnlyActiveConsolidationLinksToKnownParents() {
        Map<String, Integer> columns =
                Map.of(
                        "Relationship.StartNode.NodeID", 0,
                        "Relationship.EndNode.NodeID", 1,
                        "Relationship.RelationshipType", 2,
                        "Relationship.RelationshipStatus", 3,
                        "Relationship.Period.1.startDate", 4,
                        "Relationship.Period.1.endDate", 5,
                        "Relationship.Period.1.periodType", 6,
                        "Relationship.Quantifiers.1.MeasurementMethod", 7,
                        "Relationship.Quantifiers.1.QuantifierAmount", 8,
                        "Relationship.Quantifiers.1.QuantifierUnits", 9);
        Set<String> parents = Set.of("P");

        GleifStateOwnedProvider.Link link =
                GleifStateOwnedProvider.link(
                        List.of(
                                "C",
                                "P",
                                "IS_DIRECTLY_CONSOLIDATED_BY",
                                "ACTIVE",
                                "2020-02-02T00:00:00.000Z",
                                "",
                                "RELATIONSHIP_PERIOD",
                                "ACCOUNTING_CONSOLIDATION",
                                "51.5",
                                "PERCENTAGE"),
                        columns,
                        parents);

        assertThat(link)
                .isEqualTo(
                        new GleifStateOwnedProvider.Link(
                                "C",
                                "P",
                                "IS_DIRECTLY_CONSOLIDATED_BY",
                                LocalDate.of(2020, 2, 2),
                                null,
                                51.5));
        assertThat(link.role()).isEqualTo("direct parent");
        // other parents, other relationship types, inactive links and self links are dropped
        assertThat(
                        GleifStateOwnedProvider.link(
                                List.of("C", "X", "IS_DIRECTLY_CONSOLIDATED_BY", "ACTIVE"),
                                columns,
                                parents))
                .isNull();
        assertThat(
                        GleifStateOwnedProvider.link(
                                List.of("C", "P", "IS_FUND-MANAGED_BY", "ACTIVE"),
                                columns,
                                parents))
                .isNull();
        assertThat(
                        GleifStateOwnedProvider.link(
                                List.of("C", "P", "IS_ULTIMATELY_CONSOLIDATED_BY", "INACTIVE"),
                                columns,
                                parents))
                .isNull();
        assertThat(
                        GleifStateOwnedProvider.link(
                                List.of("P", "P", "IS_ULTIMATELY_CONSOLIDATED_BY", "ACTIVE"),
                                columns,
                                parents))
                .isNull();
    }

    @Test
    void shouldSplitCsvFieldsWithQuotes() {
        assertThat(GleifStateOwnedProvider.csvFields("\"a,b\",c,\"say \"\"hi\"\"\",,d"))
                .containsExactly("a,b", "c", "say \"hi\"", "", "d");
    }

    @Test
    void shouldEncodeBracketsInQueries() {
        assertThat(GleifStateOwnedProvider.query("filter[lei]", "A,B", "page[size]", "200"))
                .isEqualTo("?filter%5Blei%5D=A%2CB&page%5Bsize%5D=200");
    }

    @Test
    void shouldTakeTheCountryFromTheJurisdictionFirst() throws Exception {
        assertThat(
                        GleifStateOwnedProvider.country(
                                new com.fasterxml.jackson.databind.ObjectMapper()
                                        .readTree(
                                                "{\"jurisdiction\":\"US-DE\","
                                                        + "\"legalAddress\":{\"country\":\"GB\"}}")))
                .isEqualTo("US");
        assertThat(
                        GleifStateOwnedProvider.country(
                                new com.fasterxml.jackson.databind.ObjectMapper()
                                        .readTree("{\"legalAddress\":{\"country\":\"GB\"}}")))
                .isEqualTo("GB");
    }

    @Test
    void shouldFailWhenNothingWasFound() {
        GleifStateOwnedProvider provider =
                new GleifStateOwnedProvider(PUBLISHES, API, mock(HttpClient.class));

        assertThatThrownBy(() -> provider.parseResponse(new byte[0]))
                .isInstanceOf(ListIngestionException.class)
                .hasMessageContaining("no government-owned companies");
        assertThat(provider.source()).isEqualTo(ListSource.GLEIF_STATE_OWNED);
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in =
                GleifStateOwnedProviderTest.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IOException("missing test resource " + name);
            }
            return in.readAllBytes();
        }
    }

    private static byte[] zip(byte[] csv) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("rr-golden-copy.csv"));
            zip.write(csv);
            zip.closeEntry();
        }
        return bytes.toByteArray();
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
        Map<String, List<String>> map = new java.util.HashMap<>();
        headers.forEach((k, v) -> map.put(k, List.of(v)));
        when(response.headers()).thenReturn(HttpHeaders.of(map, (a, b) -> true));
        return response;
    }
}
