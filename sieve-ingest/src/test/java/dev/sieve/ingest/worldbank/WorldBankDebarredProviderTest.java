package dev.sieve.ingest.worldbank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.RiskTopic;
import dev.sieve.core.model.SanctionedEntity;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WorldBankDebarredProviderTest {

    private static final URI PAGE = URI.create("https://www.worldbank.org/debarred-firms");
    private static final URI API = URI.create("https://apigwext.worldbank.org/firms");
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-10-03T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void shouldKeepCurrentDebarmentsOncePerSupplier() throws Exception {
        List<SanctionedEntity> entities = parseSample();

        assertThat(entities)
                .extracting(SanctionedEntity::id)
                .containsExactly("wb-306951", "wb-400100");
        assertThat(entities)
                .allSatisfy(
                        e -> {
                            assertThat(e.listSource()).isEqualTo(ListSource.WB_DEBARRED);
                            assertThat(e.topics()).containsExactly(RiskTopic.DEBARMENT);
                        });
        assertThat(entities)
                .extracting(SanctionedEntity::entityType)
                .containsExactly(EntityType.ENTITY, EntityType.INDIVIDUAL);
    }

    @Test
    void shouldMapAddressAcronymAndGrounds() throws Exception {
        SanctionedEntity firm = parseSample().get(0);

        assertThat(firm.aliases()).extracting(a -> a.fullName()).containsExactly("EGE");
        assertThat(firm.addresses())
                .singleElement()
                .satisfies(
                        a -> {
                            assertThat(a.street()).isEqualTo("LOT II A 119");
                            assertThat(a.city()).isEqualTo("ANTANANARIVO 101");
                            assertThat(a.country()).isEqualTo("Madagascar");
                        });
        assertThat(firm.programs())
                .singleElement()
                .satisfies(
                        p -> assertThat(p.code()).isEqualTo("Procurement Guidelines, 1.14(a)(ii)"));
        assertThat(firm.listedDate()).isEqualTo(Instant.parse("2014-05-28T00:00:00Z"));
        assertThat(firm.remarks())
                .isEqualTo("Ineligible from 2014-05-28, with no end date\nIneligibility: Ongoing");
    }

    @Test
    void shouldKeepAdditionalInformationWithoutFootnoteMarkers() throws Exception {
        SanctionedEntity person = parseSample().get(1);

        assertThat(person.remarks())
                .isEqualTo(
                        "Ineligible from 2024-01-15 to 2030-01-14\n"
                                + "Additional information: Formerly known as Test Consulting");
    }

    @Test
    void shouldMoveHonorificIntoTitleForIndividuals() throws Exception {
        SanctionedEntity person = parseSample().get(1);

        assertThat(person.primaryName().fullName()).isEqualTo("JOHN Q. TESTPERSON");
        assertThat(person.primaryName().title()).isEqualTo("MR.");
    }

    @Test
    void shouldFailWhenResponseHasNoRows() {
        assertThatThrownBy(() -> provider(null).parseResponse("{\"error\":1}".getBytes()))
                .isInstanceOf(ListIngestionException.class)
                .hasMessageContaining("ZPROCSUPP");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldSendTheKeyFromThePageWhenFetching() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<byte[]> page =
                response(
                        "<script>var prodtabApi = \"x\"; var propApiKey = \"page-key-123\";"
                                + " var qaApiKey = \"qa-key\";</script>");
        HttpResponse<byte[]> list = response(resource("/wb_debarred_sample.json"));
        ArgumentCaptor<HttpRequest> requests = ArgumentCaptor.forClass(HttpRequest.class);
        when(client.send(requests.capture(), any(HttpResponse.BodyHandler.class)))
                .thenReturn(page, list);

        List<SanctionedEntity> result = provider(client).fetch();

        assertThat(result).hasSize(2);
        assertThat(requests.getAllValues()).extracting(HttpRequest::uri).containsExactly(PAGE, API);
        assertThat(requests.getAllValues().get(1).headers().firstValue("apikey"))
                .contains("page-key-123");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldFailWhenPageHasNoKey() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<byte[]> page = response("<html>redesigned</html>");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(page);

        assertThatThrownBy(() -> provider(client).fetch())
                .isInstanceOf(ListIngestionException.class);
    }

    private static WorldBankDebarredProvider provider(HttpClient client) {
        return new WorldBankDebarredProvider(
                PAGE, API, client == null ? HttpClient.newHttpClient() : client, CLOCK);
    }

    private static HttpResponse<byte[]> response(String body) {
        return response(body.getBytes());
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<byte[]> response(byte[] body) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(body);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (a, b) -> true));
        return response;
    }

    private static List<SanctionedEntity> parseSample() throws Exception {
        return provider(null).parseResponse(resource("/wb_debarred_sample.json"));
    }

    private static byte[] resource(String path) throws Exception {
        try (InputStream in = WorldBankDebarredProviderTest.class.getResourceAsStream(path)) {
            return in.readAllBytes();
        }
    }
}
