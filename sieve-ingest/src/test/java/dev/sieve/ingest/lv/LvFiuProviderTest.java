package dev.sieve.ingest.lv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.IdentifierType;
import dev.sieve.core.model.SanctionedEntity;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LvFiuProviderTest {

    private static final URI PAGE =
            URI.create("https://sankcijas.fid.gov.lv/lv/meklet-sankciju-sarakstos");

    @Test
    void shouldParsePersonWhenEntityIsANaturalPerson() throws Exception {
        SanctionedEntity person = parseSample().get(0);

        assertThat(person.id()).isEqualTo("lv-person-2");
        assertThat(person.entityType()).isEqualTo(EntityType.INDIVIDUAL);
        assertThat(person.primaryName().fullName()).isEqualTo("Ri Song-Hyok");
        assertThat(person.aliases()).extracting(a -> a.fullName()).containsExactly("Li, Cheng He");
        assertThat(person.datesOfBirth()).containsExactly(LocalDate.of(1965, 3, 19));
        assertThat(person.placesOfBirth())
                .containsExactly("Kaesong, Korejas Tautas Demokrātiskā Republika");
        assertThat(person.nationalities()).containsExactly("TW");
        assertThat(person.identifiers())
                .singleElement()
                .satisfies(
                        id -> {
                            assertThat(id.type()).isEqualTo(IdentifierType.PASSPORT);
                            assertThat(id.value()).isEqualTo("645120234");
                        });
        assertThat(person.programs())
                .singleElement()
                .satisfies(p -> assertThat(p.code()).isEqualTo("ZIEMEĻKOREJA"));
    }

    @Test
    void shouldParseAddressesWhenEntityIsALegalPerson() throws Exception {
        SanctionedEntity company = parseSample().get(1);

        assertThat(company.entityType()).isEqualTo(EntityType.ENTITY);
        assertThat(company.addresses()).hasSize(2);
        assertThat(company.addresses().get(1).country()).isEqualTo("RU");
        assertThat(company.addresses().get(1).city()).isEqualTo("Maskava");
    }

    @Test
    void shouldFailClearlyWhenServerReturnsAWebPage() {
        byte[] html = "<!DOCTYPE html><html><head><script async src=x>".getBytes();

        assertThatThrownBy(() -> new LvFiuProvider().parseResponse(html))
                .isInstanceOf(ListIngestionException.class)
                .hasMessageContaining("web page");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldPostDownloadFormWithTokenAndCookieWhenBuildingRequest() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> page = mock(HttpResponse.class);
        when(page.statusCode()).thenReturn(200);
        when(page.body())
                .thenReturn("<form><input type=\"hidden\" name=\"csrf\" value=\"abc-123\"></form>");
        when(page.headers())
                .thenReturn(
                        HttpHeaders.of(
                                Map.of("Set-Cookie", List.of("PHPSESSID=s1; path=/; HttpOnly")),
                                (k, v) -> true));
        doReturn(page).when(client).send(any(HttpRequest.class), any());

        HttpRequest request =
                new LvFiuProvider(PAGE, client)
                        .buildRequest(client, HttpRequest.newBuilder().uri(PAGE));

        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.uri())
                .isEqualTo(URI.create("https://sankcijas.fid.gov.lv/lejupieladet-sarakstu/lv"));
        assertThat(request.headers().firstValue("Cookie")).contains("PHPSESSID=s1");
        assertThat(request.headers().firstValue("Content-Type"))
                .contains("application/x-www-form-urlencoded");
    }

    @Test
    void shouldReadTokenWhenPageHasDownloadForm() {
        assertThat(LvFiuProvider.csrfToken("<input type=\"hidden\" name=\"csrf\" value=\"t-1\">"))
                .isEqualTo("t-1");
        assertThat(LvFiuProvider.csrfToken("<html></html>")).isNull();
    }

    private static List<SanctionedEntity> parseSample() throws Exception {
        try (InputStream in =
                LvFiuProviderTest.class.getResourceAsStream("/lv_fiu_test_sample.xml")) {
            return new LvFiuProvider().parseResponse(in.readAllBytes());
        }
    }
}
