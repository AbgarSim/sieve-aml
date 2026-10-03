package dev.sieve.ingest.ar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.IdentifierType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameStrength;
import dev.sieve.core.model.NameType;
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

class ArRepetProviderTest {

    private static final URI BASE = URI.create("https://repet.jus.gob.ar/xml/");

    @Test
    void shouldParsePersonWhenListedByTheUn() throws Exception {
        SanctionedEntity person = parseSamples().get(0);

        assertThat(person.id()).isEqualTo("ar-person-6900001");
        assertThat(person.entityType()).isEqualTo(EntityType.INDIVIDUAL);
        assertThat(person.listSource()).isEqualTo(ListSource.AR_REPET);
        assertThat(person.topics()).containsExactly(RiskTopic.SANCTION);
        assertThat(person.primaryName().fullName()).isEqualTo("JOHN EXAMPLE");
        assertThat(person.aliases())
                .extracting(a -> a.fullName(), a -> a.strength())
                .containsExactly(
                        tuple("Jon Exampel", NameStrength.STRONG), tuple("JE", NameStrength.WEAK));
        assertThat(person.identifiers())
                .extracting(i -> i.type(), i -> i.value())
                .containsExactly(
                        tuple(IdentifierType.PASSPORT, "A0000001"),
                        tuple(IdentifierType.OTHER, "QDi.999"));
        assertThat(person.nationalities()).containsExactly("Libya");
        assertThat(person.placesOfBirth()).containsExactly("Doma, Libya");
        assertThat(person.addresses())
                .singleElement()
                .satisfies(a -> assertThat(a.country()).isEqualTo("Libya"));
        assertThat(person.programs())
                .singleElement()
                .satisfies(
                        p -> {
                            assertThat(p.code()).isEqualTo("Al-Qaida");
                            assertThat(p.name()).isEqualTo("UN List");
                        });
        assertThat(person.listedDate()).isEqualTo(Instant.parse("2016-02-29T00:00:00Z"));
        assertThat(person.lastUpdated()).isEqualTo(Instant.parse("2020-11-24T00:00:00Z"));
    }

    @Test
    void shouldParseNationalListingWhenArgentinaListedThePerson() throws Exception {
        SanctionedEntity person = parseSamples().get(1);

        assertThat(person.id()).isEqualTo("ar-person-85");
        assertThat(person.datesOfBirth()).containsExactly(LocalDate.of(1970, 7, 1));
        assertThat(person.programs())
                .singleElement()
                .satisfies(p -> assertThat(p.code()).isEqualTo("Notificación Roja de INTERPOL"));
        assertThat(person.topics()).containsExactlyInAnyOrder(RiskTopic.SANCTION, RiskTopic.WANTED);
        assertThat(person.listedDate()).isNull();
    }

    @Test
    void shouldSkipEntryWhenItIsDelisted() throws Exception {
        assertThat(parseSamples()).extracting(SanctionedEntity::id).doesNotContain("ar-person-86");
    }

    @Test
    void shouldParseEntitiesFromTheEntitiesFile() throws Exception {
        List<SanctionedEntity> all = parseSamples();
        SanctionedEntity entity = all.get(all.size() - 1);

        assertThat(all).hasSize(3);
        assertThat(entity.id()).isEqualTo("ar-entity-6900002");
        assertThat(entity.entityType()).isEqualTo(EntityType.ENTITY);
        assertThat(entity.primaryName().fullName()).isEqualTo("EXAMPLE BRIGADES (EB)");
        assertThat(entity.aliases())
                .extracting(a -> a.nameType())
                .containsExactly(NameType.AKA, NameType.FKA);
        assertThat(entity.addresses()).isEmpty();
    }

    @Test
    void shouldFailClearlyWhenFileIsNotAnArray() {
        byte[] html = "{\"error\":\"maintenance\"}".getBytes();

        assertThatThrownBy(() -> new ArRepetProvider().parse(html, "[]".getBytes()))
                .isInstanceOf(ListIngestionException.class)
                .hasMessageContaining("personas.json");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldDownloadBothFilesWhenFetching() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<byte[]> entities = response(resource("/ar_repet_entidades_sample.json"));
        HttpResponse<byte[]> persons = response(resource("/ar_repet_personas_sample.json"));
        ArgumentCaptor<HttpRequest> requests = ArgumentCaptor.forClass(HttpRequest.class);
        when(client.send(requests.capture(), any(HttpResponse.BodyHandler.class)))
                .thenReturn(entities, persons);

        List<SanctionedEntity> result = new ArRepetProvider(BASE, client).fetch();

        assertThat(result).hasSize(3);
        assertThat(requests.getAllValues())
                .extracting(r -> r.uri().toString())
                .containsExactly(
                        "https://repet.jus.gob.ar/xml/entidades.json",
                        "https://repet.jus.gob.ar/xml/personas.json");
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
        return new ArRepetProvider()
                .parse(
                        resource("/ar_repet_personas_sample.json"),
                        resource("/ar_repet_entidades_sample.json"));
    }

    private static byte[] resource(String path) throws Exception {
        try (InputStream in = ArRepetProviderTest.class.getResourceAsStream(path)) {
            return in.readAllBytes();
        }
    }
}
