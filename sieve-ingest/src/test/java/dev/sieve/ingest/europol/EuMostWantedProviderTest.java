package dev.sieve.ingest.europol;

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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EuMostWantedProviderTest {

    @Test
    void shouldKeepOnlyWantedPostersOncePerNode() throws Exception {
        List<SanctionedEntity> entities = parseSample();

        assertThat(entities)
                .extracting(SanctionedEntity::id)
                .containsExactly("eu-mw-2102", "eu-mw-2062");
        assertThat(entities)
                .allSatisfy(
                        e -> {
                            assertThat(e.entityType()).isEqualTo(EntityType.INDIVIDUAL);
                            assertThat(e.listSource()).isEqualTo(ListSource.EU_MOST_WANTED);
                            assertThat(e.topics()).containsExactly(RiskTopic.WANTED);
                        });
    }

    @Test
    void shouldPutGivenNameFirstWhenTitleIsFamilyCommaGiven() throws Exception {
        SanctionedEntity first = parseSample().get(0);
        SanctionedEntity second = parseSample().get(1);

        assertThat(first.primaryName().fullName()).isEqualTo("Jörgen EXAMPLE");
        assertThat(first.primaryName().givenName()).isEqualTo("Jörgen");
        assertThat(first.primaryName().familyName()).isEqualTo("EXAMPLE");
        assertThat(second.primaryName().fullName()).isEqualTo("Anna Maria O'TESTER");
    }

    @Test
    void shouldTurnBracketedNicknameIntoWeakAlias() throws Exception {
        SanctionedEntity person = parseSample().get(1);

        assertThat(person.primaryName().givenName()).isEqualTo("Anna Maria");
        assertThat(person.aliases())
                .extracting(a -> a.fullName(), a -> a.strength())
                .containsExactly(tuple("Annie O'TESTER", NameStrength.WEAK));
    }

    @Test
    void shouldReadBirthDateNationalityAndPublicationFromPosterPage() throws Exception {
        EuMostWantedProvider.Details details =
                EuMostWantedProvider.parseDetails(
                        new String(
                                resource("/eu_most_wanted_poster.html"), StandardCharsets.UTF_8));

        assertThat(details.datesOfBirth()).containsExactly(LocalDate.of(1970, 3, 24));
        assertThat(details.nationalities()).containsExactly("Swedish", "Spanish");
        assertThat(details.published()).isEqualTo(Instant.parse("2026-09-25T00:00:00Z"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldAddPosterDetailsAndSkipPostersThatFailWhenFetching() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<byte[]> list = response(200, resource("/eu_most_wanted_sample.html"));
        HttpResponse<byte[]> poster = response(200, resource("/eu_most_wanted_poster.html"));
        HttpResponse<byte[]> missing = response(404, new byte[0]);
        HttpResponse<byte[]> download = response(200, resource("/eu_most_wanted_sample.html"));
        ArgumentCaptor<HttpRequest> requests = ArgumentCaptor.forClass(HttpRequest.class);
        when(client.send(requests.capture(), any(HttpResponse.BodyHandler.class)))
                .thenReturn(list, poster, missing, download);

        List<SanctionedEntity> result =
                new EuMostWantedProvider(URI.create("https://eumostwanted.eu/"), client).fetch();

        assertThat(requests.getAllValues())
                .extracting(r -> r.uri().toString())
                .containsExactly(
                        "https://eumostwanted.eu/",
                        "https://eumostwanted.eu/example-jorgen",
                        "https://eumostwanted.eu/otester-anna-maria",
                        "https://eumostwanted.eu/");
        assertThat(result.get(0).datesOfBirth()).containsExactly(LocalDate.of(1970, 3, 24));
        assertThat(result.get(0).nationalities()).containsExactly("Swedish", "Spanish");
        assertThat(result.get(0).listedDate()).isEqualTo(Instant.parse("2026-09-25T00:00:00Z"));
        assertThat(result.get(1).datesOfBirth()).isEmpty();
        assertThat(result.get(1).listedDate()).isNull();
    }

    @Test
    void shouldKeepCrimeAsProgramAndCaseDetailsInRemarks() throws Exception {
        SanctionedEntity person = parseSample().get(0);

        assertThat(person.programs())
                .singleElement()
                .satisfies(
                        p ->
                                assertThat(p.code())
                                        .isEqualTo(
                                                "Illicit trafficking in narcotic drugs and"
                                                        + " psychotropic substances"));
        assertThat(person.remarks())
                .isEqualTo(
                        "Wanted by: Sweden (SE)\n"
                                + "State of case: Sentenced to 4 years 11 months of prison\n"
                                + "Probable locations: Spain\n"
                                + "Poster: https://eumostwanted.eu/example-jorgen");
    }

    @Test
    void shouldFailWhenPageHasNoRows() {
        byte[] html = "<html><body><p>Maintenance</p></body></html>".getBytes();

        assertThatThrownBy(() -> new EuMostWantedProvider().parseResponse(html))
                .isInstanceOf(ListIngestionException.class)
                .hasMessageContaining("layout");
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<byte[]> response(int status, byte[] body) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (a, b) -> true));
        return response;
    }

    private static List<SanctionedEntity> parseSample() throws Exception {
        return new EuMostWantedProvider().parseResponse(resource("/eu_most_wanted_sample.html"));
    }

    private static byte[] resource(String path) throws Exception {
        try (InputStream in = EuMostWantedProviderTest.class.getResourceAsStream(path)) {
            return in.readAllBytes();
        }
    }
}
