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
import dev.sieve.core.model.Identifier;
import dev.sieve.core.model.IdentifierType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.Relation;
import dev.sieve.core.model.RelationType;
import dev.sieve.core.model.RiskTopic;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.ingest.ListProvider;
import dev.sieve.ingest.eu.EuConsolidatedProvider;
import dev.sieve.ingest.ofac.OfacSdnProvider;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class GleifSanctionLinkedProviderTest {

    private static final URI PUBLISHES = URI.create("https://golden.test/publishes?page=1");
    private static final URI API = URI.create("https://api.test/lei-records");
    private static final URI SDN = URI.create("https://ofac.test/SDN.XML");
    private static final URI EU = URI.create("https://eu.test/fsf.xml");
    private static final String FILE = "https://files.test/rr-golden-copy.csv.zip";
    private static final String VTB = "253400V1H6ART1UQ0N98";
    private static final String GAZPROMBANK = "253400WSS48YWMBUA688";
    private static final String CORAL = "549300V3GQ2MCTQAIV68";
    private static final String LEASING = "5493000000000000L001";
    private static final String CAPITAL = "5493000000000000L002";
    private static final String FINANCE = "5493000000000000L003";
    private static final String TRADING = "5493000000000000L004";
    private static final String DISSOLVED = "5493000000000000L007";
    private static final String UNKNOWN = "5493000000000000L008";
    private static final String ORPHAN = "5493000000000000L009";

    @Test
    @SuppressWarnings("unchecked")
    void shouldFollowConsolidationLinksDownFromListedParties() throws Exception {
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(
                        invocation -> {
                            HttpRequest request = invocation.getArgument(0);
                            String url =
                                    URLDecoder.decode(
                                            request.uri().toString(), StandardCharsets.UTF_8);
                            if (url.equals(SDN.toString())) {
                                return response(200, resource("gleif_linked_sdn_sample.xml"));
                            }
                            if (url.equals(EU.toString())) {
                                return response(200, resource("gleif_linked_eu_sample.xml"));
                            }
                            if (url.startsWith(PUBLISHES.toString())) {
                                return response(
                                        200,
                                        ("{\"data\":[{\"publish_date\":\"2026-10-04 16:00:00\","
                                                        + "\"rr\":{\"full_file\":{\"csv\":{\"url\":\""
                                                        + FILE
                                                        + "\",\"record_count\":12}}}}]}")
                                                .getBytes(StandardCharsets.UTF_8));
                            }
                            if (url.equals(FILE)) {
                                assertThat(request.headers().firstValue("Accept"))
                                        .hasValue("application/zip");
                                return response(200, zip(resource("gleif_linked_rr_sample.csv")));
                            }
                            if (url.contains("filter[lei]=")) {
                                // every company found below a listed party, at any depth, in one
                                // call; the listed parties themselves are not asked for
                                assertThat(url)
                                        .contains(LEASING)
                                        .contains(CAPITAL)
                                        .contains(FINANCE)
                                        .contains(TRADING)
                                        .contains(DISSOLVED)
                                        .contains(UNKNOWN)
                                        .contains(ORPHAN)
                                        .contains("page[size]=200")
                                        .doesNotContain(VTB)
                                        .doesNotContain(GAZPROMBANK)
                                        .doesNotContain(CORAL);
                                return response(
                                        200, resource("gleif_linked_companies_sample.json"));
                            }
                            throw new AssertionError("unexpected request " + url);
                        });
        GleifSanctionLinkedProvider provider =
                new GleifSanctionLinkedProvider(
                        PUBLISHES,
                        API,
                        List.of(
                                new OfacSdnProvider(SDN, client),
                                new EuConsolidatedProvider(EU, client)),
                        client);

        List<SanctionedEntity> result = provider.fetch();

        // the companies below the OFAC-listed bank (one of them through another company) and the
        // one below the EU-listed party; out are the subsidiary that is listed by name, the listed
        // bank, the company GLEIF marks inactive, the one the API no longer knows, and the one
        // whose only parent is that inactive company
        assertThat(result)
                .extracting(SanctionedEntity::id)
                .containsExactly(
                        "lei-linked-" + LEASING, "lei-linked-" + TRADING, "lei-linked-" + FINANCE);

        SanctionedEntity leasing = result.get(0);
        assertThat(leasing.entityType()).isEqualTo(EntityType.ENTITY);
        assertThat(leasing.listSource()).isEqualTo(ListSource.GLEIF_SANCTION_LINKED);
        assertThat(leasing.primaryName().fullName()).isEqualTo("VTB Leasing (Europe) Limited");
        assertThat(leasing.topics()).containsExactly(RiskTopic.SANCTION_LINKED);
        assertThat(leasing.programs())
                .extracting(p -> p.code())
                .containsExactly("OFAC SDN 50% rule");
        assertThat(leasing.nationalities()).containsExactly("CY");
        assertThat(leasing.addresses()).hasSize(1);
        assertThat(leasing.identifiers())
                .extracting(Identifier::type, Identifier::value, Identifier::remarks)
                .containsExactly(
                        tuple(IdentifierType.LEI, LEASING, "Registration ISSUED"),
                        tuple(
                                IdentifierType.REGISTRATION_NUMBER,
                                "HE 123456",
                                "Registration authority RA000124"));
        // both links point at the listed bank's own record
        assertThat(leasing.relations())
                .containsExactly(
                        new Relation(
                                RelationType.LINKED,
                                "ofac-sdn-17013",
                                "ultimate parent",
                                100.0,
                                LocalDate.of(2015, 3, 1),
                                null),
                        new Relation(
                                RelationType.LINKED,
                                "ofac-sdn-17013",
                                "direct parent",
                                null,
                                null,
                                null));
        assertThat(leasing.remarks())
                .isEqualTo(
                        "Ultimately consolidated by VTB BANK PUBLIC JOINT STOCK COMPANY ("
                                + VTB
                                + "), listed on OFAC SDN under RUSSIA-EO14024, UKRAINE-EO13662,"
                                + " share 100%, since 2015-03-01\n"
                                + "Directly consolidated by VTB BANK PUBLIC JOINT STOCK COMPANY ("
                                + VTB
                                + "), listed on OFAC SDN under RUSSIA-EO14024, UKRAINE-EO13662\n"
                                + "LEI registration: ISSUED\n"
                                + "Source: https://search.gleif.org/#/record/"
                                + LEASING);
        assertThat(leasing.listedDate()).isEqualTo(Instant.parse("2015-03-01T00:00:00Z"));

        // two levels down: the relation points at the company in between, in this list
        SanctionedEntity finance = result.get(2);
        assertThat(finance.primaryName().fullName()).isEqualTo("VTB Leasing Finance B.V.");
        assertThat(finance.aliases())
                .extracting(a -> a.fullName())
                .containsExactly("VTB Leasing Finance");
        assertThat(finance.topics()).containsExactly(RiskTopic.SANCTION_LINKED);
        assertThat(finance.programs())
                .extracting(p -> p.code())
                .containsExactly("OFAC SDN 50% rule");
        assertThat(finance.relations())
                .containsExactly(
                        new Relation(
                                RelationType.LINKED,
                                "lei-linked-" + LEASING,
                                "direct parent",
                                null,
                                LocalDate.of(2018, 6, 30),
                                null));
        assertThat(finance.remarks())
                .startsWith(
                        "Directly consolidated by VTB Leasing (Europe) Limited ("
                                + LEASING
                                + "), itself majority-owned by a listed party, since 2018-06-30\n"
                                + "LEI registration: LAPSED\n");
        assertThat(finance.listedDate()).isEqualTo(Instant.parse("2018-06-30T00:00:00Z"));

        // below the EU-listed party: linked since that party was listed
        SanctionedEntity trading = result.get(1);
        assertThat(trading.primaryName().fullName()).isEqualTo("Coral Energy Trading DMCC");
        assertThat(trading.nationalities()).containsExactly("AE");
        assertThat(trading.programs())
                .extracting(p -> p.code())
                .containsExactly("EU Consolidated 50% rule");
        assertThat(trading.relations())
                .extracting(Relation::targetId, Relation::role)
                .containsExactly(tuple("eu-EU.1.77", "ultimate parent"));
        assertThat(trading.remarks())
                .startsWith(
                        "Ultimately consolidated by Coral Energy PTE Ltd ("
                                + CORAL
                                + "), listed on EU Consolidated");
        assertThat(trading.listedDate()).isEqualTo(Instant.parse("2022-04-08T00:00:00Z"));

        // the two lists, the listing (read again by the base provider), the file, one API call
        verify(client, times(6)).send(any(), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void shouldRecogniseLeisByFormatAndCheckDigits() {
        assertThat(lei(IdentifierType.LEI, VTB)).isEqualTo(VTB);
        // a loosely typed value with the right format and check digits counts, whatever its case
        assertThat(lei(IdentifierType.OTHER, " 549300lcj1ujxhybwi24 "))
                .isEqualTo("549300LCJ1UJXHYBWI24");
        // too short, wrong check digits, or no identifier at all
        assertThat(lei(IdentifierType.LEI, "851683897")).isNull();
        assertThat(lei(IdentifierType.LEI, "253400V1H6ART1UQ0N99")).isNull();
        assertThat(GleifSanctionLinkedProvider.lei(null)).isNull();
    }

    @Test
    void shouldCompareNamesWithoutPunctuationOrCase() {
        assertThat(GleifSanctionLinkedProvider.normalize("GPB-Financial Services, Ltd."))
                .isEqualTo("gpbfinancialservicesltd");
        assertThat(GleifSanctionLinkedProvider.normalize(null)).isEmpty();
    }

    @Test
    void shouldFailWhenAListCannotBeRead() throws Exception {
        ListProvider failing = mock(ListProvider.class);
        when(failing.source()).thenReturn(ListSource.OFAC_SDN);
        when(failing.fetch()).thenThrow(new ListIngestionException("boom", ListSource.OFAC_SDN));
        GleifSanctionLinkedProvider provider =
                new GleifSanctionLinkedProvider(
                        PUBLISHES, API, List.of(failing), mock(HttpClient.class));

        assertThatThrownBy(provider::fetch)
                .isInstanceOf(ListIngestionException.class)
                .hasMessageContaining("could not read OFAC SDN");
    }

    @Test
    void shouldFailWhenNothingWasFound() {
        GleifSanctionLinkedProvider provider =
                new GleifSanctionLinkedProvider(PUBLISHES, API, List.of(), mock(HttpClient.class));

        assertThatThrownBy(() -> provider.parseResponse(new byte[0]))
                .isInstanceOf(ListIngestionException.class)
                .hasMessageContaining("no companies");
        assertThat(provider.source()).isEqualTo(ListSource.GLEIF_SANCTION_LINKED);
    }

    private static String lei(IdentifierType type, String value) {
        return GleifSanctionLinkedProvider.lei(new Identifier(type, value, null, null));
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in =
                GleifSanctionLinkedProviderTest.class.getClassLoader().getResourceAsStream(name)) {
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

    @SuppressWarnings("unchecked")
    private static HttpResponse<byte[]> response(int status, byte[] body) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (a, b) -> true));
        return response;
    }
}
