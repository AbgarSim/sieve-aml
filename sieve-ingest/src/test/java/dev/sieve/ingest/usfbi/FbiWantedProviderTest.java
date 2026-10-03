package dev.sieve.ingest.usfbi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameStrength;
import dev.sieve.core.model.RiskTopic;
import dev.sieve.core.model.SanctionedEntity;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FbiWantedProviderTest {

    @Test
    void shouldKeepOnlyOpenPostersAboutSuspects() throws Exception {
        List<SanctionedEntity> entities = parseSamples();

        assertThat(entities)
                .extracting(SanctionedEntity::id)
                .containsExactly("fbi-aaa111", "fbi-ddd444");
        assertThat(entities)
                .allSatisfy(
                        e -> {
                            assertThat(e.entityType()).isEqualTo(EntityType.INDIVIDUAL);
                            assertThat(e.listSource()).isEqualTo(ListSource.US_FBI_WANTED);
                            assertThat(e.topics()).containsExactly(RiskTopic.WANTED);
                        });
    }

    @Test
    void shouldParseNamesDatesAndChargesWhenPosterIsComplete() throws Exception {
        SanctionedEntity person = parseSamples().get(0);

        assertThat(person.primaryName().fullName()).isEqualTo("JOHN QUINCY EXAMPLE");
        assertThat(person.aliases())
                .extracting(a -> a.fullName(), a -> a.strength())
                .containsExactly(
                        tuple("The Example", NameStrength.WEAK),
                        tuple("Johnny Example", NameStrength.STRONG),
                        tuple("J. Q. Example", NameStrength.STRONG));
        assertThat(person.datesOfBirth()).containsExactly(LocalDate.of(1976, 5, 15));
        assertThat(person.nationalities()).containsExactly("Venezuelan");
        assertThat(person.placesOfBirth()).containsExactly("Venezuela");
        assertThat(person.programs())
                .extracting(p -> p.code())
                .containsExactly("Ten Most Wanted Fugitives");
        assertThat(person.remarks())
                .isEqualTo(
                        "Conspiracy to Commit Bank Fraud; Money Laundering\n"
                                + "Poster: https://www.fbi.gov/wanted/topten/john-quincy-example");
        assertThat(person.listedDate()).isEqualTo(Instant.parse("2026-02-17T11:44:00Z"));
        assertThat(person.lastUpdated()).isEqualTo(Instant.parse("2026-10-02T20:55:45Z"));
    }

    @Test
    void shouldTolerateNullFieldsWhenPosterIsSparse() throws Exception {
        SanctionedEntity person = parseSamples().get(1);

        assertThat(person.aliases()).isEmpty();
        assertThat(person.datesOfBirth()).isEmpty();
        assertThat(person.remarks()).isNull();
        assertThat(person.listedDate()).isNull();
    }

    @Test
    void shouldFailWhenResponseHasNoItems() {
        assertThatThrownBy(() -> new FbiWantedProvider().parse(List.of("{\"error\":1}".getBytes())))
                .isInstanceOf(ListIngestionException.class)
                .hasMessageContaining("items");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldReadEveryPageUntilOneIsEmptyWhenFetching() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<byte[]> page2 = response(resource("/fbi_wanted_page2.json"));
        HttpResponse<byte[]> page3 = response("{\"total\":5,\"items\":[]}".getBytes());
        HttpResponse<byte[]> page1 = response(resource("/fbi_wanted_page1.json"));
        ArgumentCaptor<HttpRequest> requests = ArgumentCaptor.forClass(HttpRequest.class);
        when(client.send(requests.capture(), any(HttpResponse.BodyHandler.class)))
                .thenReturn(page2, page3, page1);

        List<SanctionedEntity> result =
                new FbiWantedProvider(URI.create("https://api.fbi.gov/wanted/v1/list"), client)
                        .fetch();

        assertThat(result).hasSize(2);
        assertThat(requests.getAllValues())
                .extracting(r -> r.uri().getQuery())
                .containsExactly("pageSize=50&page=2", "pageSize=50&page=3", "pageSize=50&page=1");
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<byte[]> response(byte[] body) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(body);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (a, b) -> true));
        return response;
    }

    private static List<SanctionedEntity> parseSamples() throws Exception {
        return new FbiWantedProvider()
                .parse(
                        List.of(
                                resource("/fbi_wanted_page1.json"),
                                resource("/fbi_wanted_page2.json")));
    }

    private static byte[] resource(String path) throws Exception {
        try (InputStream in = FbiWantedProviderTest.class.getResourceAsStream(path)) {
            return in.readAllBytes();
        }
    }
}
