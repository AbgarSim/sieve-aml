package dev.sieve.ingest.wikidata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.sieve.core.model.EntityImage;
import dev.sieve.core.model.EntityLink;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.Identifier;
import dev.sieve.core.model.IdentifierType;
import dev.sieve.core.model.LinkKind;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.NameStrength;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.ScriptType;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WikidataLinksTest {

    private static final String WD = "http://www.wikidata.org/entity/";
    private static final String FILE = "http://commons.wikimedia.org/wiki/Special:FilePath/";

    private static final String LEI_ROWS =
            """
            {"results":{"bindings":[
              {"v":{"value":"5299001ABCDEF0000001"},"item":{"value":"%sQ1"},
               "logo":{"value":"%sAcme%%20Logo.svg"},"image":{"value":"%sAcme_HQ.jpg"},
               "site":{"value":"https://acme.example"},
               "article":{"value":"https://en.wikipedia.org/wiki/Acme_Corporation"}},
              {"v":{"value":"5299001ABCDEF0000002"},"item":{"value":"%sQ2"}},
              {"v":{"value":"5299001ABCDEF0000002"},"item":{"value":"%sQ3"}}
            ]}}
            """
                    .formatted(WD, FILE, FILE, WD, WD);

    private static final String IMO_ROWS =
            """
            {"results":{"bindings":[
              {"v":{"value":"9123456"},"item":{"value":"%sQ4"},"image":{"value":"%sShip.jpg"}}
            ]}}
            """
                    .formatted(WD, FILE);

    private static final String COMMONS =
            """
            {"query":{
              "normalized":[{"from":"File:Acme Logo.svg","to":"File:Acme Logo.svg"}],
              "pages":[
                {"title":"File:Acme Logo.svg","imageinfo":[{
                  "url":"https://upload.wikimedia.org/a/ab/Acme_Logo.svg",
                  "thumburl":"https://upload.wikimedia.org/thumb/a/ab/Acme_Logo.svg/250px-Acme_Logo.svg.png",
                  "descriptionurl":"https://commons.wikimedia.org/wiki/File:Acme_Logo.svg",
                  "extmetadata":{"LicenseShortName":{"value":"Public domain"},"Artist":{"value":""}}}]},
                {"title":"File:Ship.jpg","imageinfo":[{
                  "url":"https://upload.wikimedia.org/s/sh/Ship.jpg",
                  "thumburl":"https://upload.wikimedia.org/thumb/s/sh/Ship.jpg/250px-Ship.jpg",
                  "descriptionurl":"https://commons.wikimedia.org/wiki/File:Ship.jpg",
                  "extmetadata":{"LicenseShortName":{"value":"CC BY-SA 4.0"},
                    "Artist":{"value":"<a href=\\"//commons.wikimedia.org/wiki/User:Jane\\">Jane Doe</a>"}}}]}
              ]}}
            """;

    @Test
    void shouldAddPictureArticleAndWebsiteWhenOneItemHasTheIdentifier() throws Exception {
        HttpClient client = client(LEI_ROWS, IMO_ROWS, COMMONS);
        SanctionedEntity acme =
                entity("1", EntityType.COMPANY, IdentifierType.LEI, "5299001abcdef0000001 ");
        SanctionedEntity ship =
                entity("2", EntityType.VESSEL, IdentifierType.IMO_NUMBER, "IMO 9123456");

        List<SanctionedEntity> result = links(client).addTo(List.of(acme, ship));

        assertThat(result.get(0).images())
                .containsExactly(
                        new EntityImage(
                                "https://upload.wikimedia.org/a/ab/Acme_Logo.svg",
                                "https://upload.wikimedia.org/thumb/a/ab/Acme_Logo.svg/250px-Acme_Logo.svg.png",
                                "https://commons.wikimedia.org/wiki/File:Acme_Logo.svg",
                                "Wikimedia Commons",
                                "Public domain"));
        assertThat(result.get(0).links())
                .containsExactly(
                        new EntityLink(
                                "https://en.wikipedia.org/wiki/Acme_Corporation",
                                "Wikipedia: Acme Corporation",
                                LinkKind.ENCYCLOPEDIA,
                                null),
                        new EntityLink(
                                "https://www.wikidata.org/wiki/Q1",
                                "Wikidata Q1",
                                LinkKind.ENCYCLOPEDIA,
                                null),
                        new EntityLink(
                                "https://acme.example",
                                "Official website",
                                LinkKind.WEBSITE,
                                null));
        assertThat(result.get(1).images())
                .extracting(EntityImage::credit, EntityImage::licence)
                .containsExactly(tuple("Jane Doe", "CC BY-SA 4.0"));
    }

    @Test
    void shouldLeaveAnEntityAloneWhenItsIdentifierBelongsToSeveralItems() throws Exception {
        HttpClient client = client(LEI_ROWS, COMMONS);
        SanctionedEntity twin =
                entity("3", EntityType.COMPANY, IdentifierType.LEI, "5299001ABCDEF0000002");

        List<SanctionedEntity> result = links(client).addTo(List.of(twin));

        assertThat(result).containsExactly(twin);
    }

    @Test
    void shouldNotAskWikidataWhenNoEntityHasAnIdentifierItHolds() throws Exception {
        HttpClient client = mock(HttpClient.class);
        SanctionedEntity person =
                entity("4", EntityType.INDIVIDUAL, IdentifierType.PASSPORT, "AB123456");

        assertThat(links(client).addTo(List.of(person))).containsExactly(person);
        verifyNoInteractions(client);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldAskForEveryIdentifierValueOnceInOneQuery() throws Exception {
        HttpClient client = client(LEI_ROWS, COMMONS);
        SanctionedEntity a =
                entity("5", EntityType.COMPANY, IdentifierType.LEI, "5299001ABCDEF0000001");
        SanctionedEntity b =
                entity("6", EntityType.COMPANY, IdentifierType.LEI, "5299001ABCDEF0000001");

        links(client).addTo(List.of(a, b));

        ArgumentCaptor<HttpRequest> requests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client, times(2)).send(requests.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(requests.getAllValues().get(0).uri().getHost()).isEqualTo("query.example");
        assertThat(WikidataLinks.itemsQuery("P1278", List.of("5299001ABCDEF0000001")))
                .contains("VALUES ?v { \"5299001ABCDEF0000001\" }")
                .contains("wdt:P1278 ?v");
    }

    @Test
    void shouldKeepOnlySevenDigitImoNumbers() {
        assertThat(
                        WikidataLinks.keys(
                                entity("7", EntityType.VESSEL, IdentifierType.IMO_NUMBER, "12345")))
                .isEmpty();
        assertThat(
                        WikidataLinks.keys(
                                entity(
                                        "8",
                                        EntityType.VESSEL,
                                        IdentifierType.IMO_NUMBER,
                                        "imo9123456")))
                .containsExactly(new WikidataLinks.Key("P458", "9123456"));
    }

    @Test
    void shouldReadAuthorsAsPlainText() {
        assertThat(WikidataLinks.plain("<span>Photo by <b>J. Smith</b> &amp; team</span>"))
                .isEqualTo("Photo by J. Smith & team");
        assertThat(
                        WikidataLinks.withoutTracking(
                                "https://upload.wikimedia.org/a/Logo.svg?utm_source=commons.wikimedia.org&utm_campaign=imageinfo&utm_content=original"))
                .isEqualTo("https://upload.wikimedia.org/a/Logo.svg");
        assertThat(WikidataLinks.withoutTracking("https://x.example/a.jpg?w=1&utm_source=c"))
                .isEqualTo("https://x.example/a.jpg?w=1");
        assertThat(WikidataLinks.fileName(FILE + "Acme%20Logo%2B.svg")).isEqualTo("Acme Logo+.svg");
    }

    private static WikidataLinks links(HttpClient client) {
        return new WikidataLinks(
                URI.create("https://query.example/sparql"),
                URI.create("https://commons.example/w/api.php"),
                client);
    }

    @SuppressWarnings("unchecked")
    private static HttpClient client(String... bodies) throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<byte[]> first = response(bodies[0]);
        HttpResponse<byte[]>[] rest = new HttpResponse[bodies.length - 1];
        for (int i = 1; i < bodies.length; i++) {
            rest[i - 1] = response(bodies[i]);
        }
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(first, rest);
        return client;
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<byte[]> response(String body) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (a, b) -> true));
        return response;
    }

    private static SanctionedEntity entity(
            String id, EntityType type, IdentifierType idType, String value) {
        return new SanctionedEntity(
                id,
                type,
                ListSource.GLEIF_STATE_OWNED,
                new NameInfo(
                        "Name " + id,
                        null,
                        null,
                        null,
                        null,
                        NameType.PRIMARY,
                        NameStrength.STRONG,
                        ScriptType.LATIN),
                List.of(),
                List.of(),
                List.of(new Identifier(idType, value, null, null)),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of(),
                null,
                null);
    }
}
