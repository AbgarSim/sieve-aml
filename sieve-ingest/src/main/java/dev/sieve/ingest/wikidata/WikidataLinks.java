package dev.sieve.ingest.wikidata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sieve.core.model.EntityImage;
import dev.sieve.core.model.EntityLink;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.Identifier;
import dev.sieve.core.model.IdentifierType;
import dev.sieve.core.model.LinkKind;
import dev.sieve.core.model.SanctionedEntity;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adds a picture, a Wikipedia article and an official website to entities that Wikidata knows by
 * one of their identifiers.
 *
 * <p>An entity is linked to a Wikidata item only through an identifier both sides state exactly: a
 * Legal Entity Identifier (Wikidata property P1278), an IMO ship number (P458), a SWIFT/BIC code
 * (P2627) or an ISIN (P946). Names are never used, so a sanctioned person can never be shown with
 * someone else's photo. When an identifier belongs to several items, or an entity's identifiers
 * lead to different items, the entity is left alone.
 *
 * <p>From the item it takes the logo (P154) for an organisation or the image (P18) otherwise, the
 * English Wikipedia article and the official website (P856), and links the item itself. Pictures
 * come from Wikimedia Commons with the author and licence Commons states for them, which must be
 * shown with the picture; Sieve stores the addresses only.
 *
 * @see <a href="https://www.wikidata.org/wiki/Wikidata:SPARQL_query_service">Wikidata Query
 *     Service</a>
 */
public final class WikidataLinks {

    private static final Logger log = LoggerFactory.getLogger(WikidataLinks.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final URI DEFAULT_SPARQL = URI.create("https://query.wikidata.org/sparql");
    private static final URI DEFAULT_COMMONS =
            URI.create("https://commons.wikimedia.org/w/api.php");
    private static final String USER_AGENT =
            "sieve-aml/1.0 (https://github.com/AbgarSim/sieve-aml; sanctions and PEP screening)";

    /** Identifier values per query; exact-value lookups stay well under a second at this size. */
    static final int BATCH = 200;

    /** File names per Commons request, the API's limit. */
    static final int COMMONS_BATCH = 50;

    /**
     * Thumbnail width; Wikimedia serves a fixed set of widths from its cache and this is one of
     * them.
     */
    static final int THUMB_WIDTH = 250;

    private static final int CREDIT_MAX = 160;

    /** The identifier types Wikidata holds as exact values, with the property that holds each. */
    static final Map<IdentifierType, String> PROPERTIES =
            Map.of(
                    IdentifierType.LEI, "P1278",
                    IdentifierType.IMO_NUMBER, "P458",
                    IdentifierType.SWIFT_BIC, "P2627",
                    IdentifierType.ISIN, "P946");

    private final URI sparql;
    private final URI commons;
    private final HttpClient client;

    /** Creates the lookup against the public Wikidata and Commons services. */
    public WikidataLinks() {
        this(
                DEFAULT_SPARQL,
                DEFAULT_COMMONS,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(20))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build());
    }

    WikidataLinks(URI sparql, URI commons, HttpClient client) {
        this.sparql = Objects.requireNonNull(sparql, "sparql must not be null");
        this.commons = Objects.requireNonNull(commons, "commons must not be null");
        this.client = Objects.requireNonNull(client, "client must not be null");
    }

    /**
     * Returns the entities with what Wikidata adds to those it knows by identifier; the others are
     * returned unchanged, in the same order.
     *
     * @param entities the entities to look up
     * @return the entities, some with pictures and links added
     * @throws IOException if Wikidata or Commons cannot be reached
     * @throws InterruptedException if interrupted while waiting
     */
    public List<SanctionedEntity> addTo(List<SanctionedEntity> entities)
            throws IOException, InterruptedException {
        Map<String, Set<String>> valuesByProperty = new LinkedHashMap<>();
        for (SanctionedEntity entity : entities) {
            keys(entity)
                    .forEach(
                            k ->
                                    valuesByProperty
                                            .computeIfAbsent(
                                                    k.property(), p -> new LinkedHashSet<>())
                                            .add(k.value()));
        }
        Map<Key, Set<String>> itemsByKey = new HashMap<>();
        Map<String, Item> items = new HashMap<>();
        for (Map.Entry<String, Set<String>> property : valuesByProperty.entrySet()) {
            List<String> values = List.copyOf(property.getValue());
            for (int i = 0; i < values.size(); i += BATCH) {
                List<String> batch = values.subList(i, Math.min(i + BATCH, values.size()));
                for (JsonNode row : select(itemsQuery(property.getKey(), batch))) {
                    String id = itemId(value(row, "item"));
                    String v = value(row, "v");
                    if (id == null || v == null) {
                        continue;
                    }
                    itemsByKey
                            .computeIfAbsent(
                                    new Key(property.getKey(), v), k -> new LinkedHashSet<>())
                            .add(id);
                    items.computeIfAbsent(id, Item::new).add(row);
                }
            }
        }

        Map<SanctionedEntity, Item> matched = new IdentityHashMap<>();
        for (SanctionedEntity entity : entities) {
            Set<String> ids = new LinkedHashSet<>();
            boolean ambiguous = false;
            for (Key key : keys(entity)) {
                Set<String> found = itemsByKey.getOrDefault(key, Set.of());
                ambiguous |= found.size() > 1;
                ids.addAll(found);
            }
            if (!ambiguous && ids.size() == 1) {
                matched.put(entity, items.get(ids.iterator().next()));
            }
        }

        Map<String, EntityImage> pictures = pictures(matched);
        List<SanctionedEntity> result = new ArrayList<>(entities.size());
        int pictured = 0;
        for (SanctionedEntity entity : entities) {
            Item item = matched.get(entity);
            if (item == null) {
                result.add(entity);
                continue;
            }
            List<EntityImage> images = new ArrayList<>(entity.images());
            EntityImage picture = pictures.get(item.file(isOrganisation(entity)));
            if (picture != null) {
                images.add(picture);
                pictured++;
            }
            List<EntityLink> links = new ArrayList<>(entity.links());
            links.addAll(item.links());
            result.add(entity.withImages(images).withLinks(links));
        }
        log.info(
                "Wikidata links added [entities={}, withPicture={}, identifierValues={}]",
                matched.size(),
                pictured,
                valuesByProperty.values().stream().mapToInt(Set::size).sum());
        return result;
    }

    /** The identifiers of an entity that Wikidata holds, in the form Wikidata writes them. */
    static List<Key> keys(SanctionedEntity entity) {
        List<Key> keys = new ArrayList<>();
        for (Identifier id : entity.identifiers()) {
            String property = PROPERTIES.get(id.type());
            if (property == null) {
                continue;
            }
            String value = id.value().replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
            if (id.type() == IdentifierType.IMO_NUMBER) {
                value = value.replaceFirst("^IMO", "");
                if (!value.matches("\\d{7}")) {
                    continue;
                }
            }
            if (!value.isEmpty()) {
                keys.add(new Key(property, value));
            }
        }
        return keys;
    }

    private static boolean isOrganisation(SanctionedEntity entity) {
        return entity.entityType() != EntityType.INDIVIDUAL
                && entity.entityType() != EntityType.VESSEL
                && entity.entityType() != EntityType.AIRCRAFT;
    }

    static String itemsQuery(String property, List<String> values) {
        String quoted =
                values.stream()
                        .map(v -> "\"" + v.replace("\\", "").replace("\"", "") + "\"")
                        .collect(Collectors.joining(" "));
        return "SELECT ?v ?item ?image ?logo ?site ?article WHERE { VALUES ?v { "
                + quoted
                + " } ?item wdt:"
                + property
                + " ?v . OPTIONAL { ?item wdt:P18 ?image } OPTIONAL { ?item wdt:P154 ?logo }"
                + " OPTIONAL { ?item wdt:P856 ?site } OPTIONAL { ?article schema:about ?item ;"
                + " schema:isPartOf <https://en.wikipedia.org/> } }";
    }

    /** Looks up on Commons the picture each matched item offers, keyed by file name. */
    private Map<String, EntityImage> pictures(Map<SanctionedEntity, Item> matched)
            throws IOException, InterruptedException {
        Set<String> files = new LinkedHashSet<>();
        matched.forEach(
                (entity, item) -> {
                    String file = item.file(isOrganisation(entity));
                    if (file != null) {
                        files.add(file);
                    }
                });
        Map<String, EntityImage> pictures = new HashMap<>();
        List<String> names = List.copyOf(files);
        for (int i = 0; i < names.size(); i += COMMONS_BATCH) {
            List<String> batch = names.subList(i, Math.min(i + COMMONS_BATCH, names.size()));
            pictures.putAll(parsePictures(get(commonsQuery(batch))));
        }
        return pictures;
    }

    private URI commonsQuery(List<String> files) {
        String titles = files.stream().map(f -> "File:" + f).collect(Collectors.joining("|"));
        return URI.create(
                commons
                        + "?action=query&format=json&formatversion=2&prop=imageinfo"
                        + "&iiprop=url%7Cextmetadata&iiurlwidth="
                        + THUMB_WIDTH
                        + "&iiextmetadatafilter=LicenseShortName%7CArtist"
                        + "&titles="
                        + URLEncoder.encode(titles, StandardCharsets.UTF_8));
    }

    /** Reads a Commons image info response into pictures keyed by file name (no "File:"). */
    static Map<String, EntityImage> parsePictures(JsonNode response) {
        Map<String, String> renamed = new HashMap<>();
        for (JsonNode n : response.path("query").path("normalized")) {
            renamed.put(n.path("to").asText(), n.path("from").asText());
        }
        Map<String, EntityImage> pictures = new HashMap<>();
        for (JsonNode page : response.path("query").path("pages")) {
            JsonNode info = page.path("imageinfo").path(0);
            String url = withoutTracking(text(info, "url"));
            if (url == null) {
                continue;
            }
            JsonNode meta = info.path("extmetadata");
            String artist = plain(meta.path("Artist").path("value").asText(""));
            String licence = plain(meta.path("LicenseShortName").path("value").asText(""));
            String title = page.path("title").asText();
            String asked = renamed.getOrDefault(title, title);
            pictures.put(
                    asked.replaceFirst("^File:", ""),
                    new EntityImage(
                            url,
                            withoutTracking(text(info, "thumburl")),
                            text(info, "descriptionurl"),
                            artist.isEmpty() ? "Wikimedia Commons" : artist,
                            licence));
        }
        return pictures;
    }

    /** Text of an HTML snippet, as Commons writes authors: tags dropped, spaces folded. */
    static String plain(String html) {
        String text =
                html.replaceAll("<[^>]*>", " ")
                        .replace("&amp;", "&")
                        .replace("&quot;", "\"")
                        .replace("&#039;", "'")
                        .replace("&nbsp;", " ")
                        .replaceAll("\\s+", " ")
                        .strip();
        return text.length() > CREDIT_MAX ? text.substring(0, CREDIT_MAX - 1) + "…" : text;
    }

    private List<JsonNode> select(String query) throws IOException, InterruptedException {
        String form = "query=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
        HttpRequest request =
                HttpRequest.newBuilder(sparql)
                        .timeout(Duration.ofSeconds(60))
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "application/sparql-results+json")
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .build();
        List<JsonNode> rows = new ArrayList<>();
        send(request).path("results").path("bindings").forEach(rows::add);
        return rows;
    }

    private JsonNode get(URI uri) throws IOException, InterruptedException {
        return send(
                HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(60))
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "application/json")
                        .GET()
                        .build());
    }

    /** Sends a request, waiting as long as the service asks when throttled, a few times. */
    private JsonNode send(HttpRequest request) throws IOException, InterruptedException {
        for (int attempt = 1; ; attempt++) {
            HttpResponse<byte[]> response =
                    client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            if (status == 200) {
                return MAPPER.readTree(response.body());
            }
            if ((status != 429 && status < 500) || attempt >= 3) {
                throw new IOException(
                        "Wikidata lookup failed [status="
                                + status
                                + ", uri="
                                + request.uri()
                                + "]");
            }
            long wait =
                    Math.min(
                            response.headers().firstValueAsLong("Retry-After").orElse(5L * attempt),
                            60);
            Thread.sleep(Duration.ofSeconds(wait));
        }
    }

    private static String value(JsonNode row, String field) {
        return text(row.path(field), "value");
    }

    /** The address without the {@code utm_} parameters Commons appends to file addresses. */
    static String withoutTracking(String url) {
        if (url == null) {
            return null;
        }
        int query = url.indexOf('?');
        if (query < 0) {
            return url;
        }
        StringJoiner kept = new StringJoiner("&", "?", "").setEmptyValue("");
        for (String parameter : url.substring(query + 1).split("&")) {
            if (!parameter.startsWith("utm_")) {
                kept.add(parameter);
            }
        }
        return url.substring(0, query) + kept;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            return null;
        }
        return value.asText().strip();
    }

    /** {@code Q123} from an entity IRI such as {@code http://www.wikidata.org/entity/Q123}. */
    private static String itemId(String iri) {
        if (iri == null) {
            return null;
        }
        String id = iri.substring(iri.lastIndexOf('/') + 1);
        return id.matches("Q\\d+") ? id : null;
    }

    /** A Commons file name from its {@code Special:FilePath} IRI. */
    static String fileName(String iri) {
        if (iri == null) {
            return null;
        }
        String name = iri.substring(iri.lastIndexOf('/') + 1);
        return URLDecoder.decode(name.replace("+", "%2B"), StandardCharsets.UTF_8)
                .replace('_', ' ');
    }

    /** An identifier as Wikidata writes it, under the property that holds it. */
    record Key(String property, String value) {}

    /** What a Wikidata item offers: its first image, logo, website and English article. */
    private static final class Item {
        private final String id;
        private String image;
        private String logo;
        private String site;
        private String article;

        Item(String id) {
            this.id = id;
        }

        void add(JsonNode row) {
            image = image != null ? image : fileName(value(row, "image"));
            logo = logo != null ? logo : fileName(value(row, "logo"));
            site = site != null ? site : value(row, "site");
            article = article != null ? article : value(row, "article");
        }

        /** The logo for an organisation when there is one, else the image. */
        String file(boolean organisation) {
            return organisation && logo != null ? logo : image != null ? image : logo;
        }

        List<EntityLink> links() {
            List<EntityLink> links = new ArrayList<>();
            if (article != null) {
                String title =
                        URLDecoder.decode(
                                        article.substring(article.lastIndexOf('/') + 1)
                                                .replace("+", "%2B"),
                                        StandardCharsets.UTF_8)
                                .replace('_', ' ');
                links.add(
                        new EntityLink(
                                article, "Wikipedia: " + title, LinkKind.ENCYCLOPEDIA, null));
            }
            links.add(
                    new EntityLink(
                            "https://www.wikidata.org/wiki/" + id,
                            "Wikidata " + id,
                            LinkKind.ENCYCLOPEDIA,
                            null));
            if (site != null) {
                links.add(new EntityLink(site, "Official website", LinkKind.WEBSITE, null));
            }
            return links;
        }
    }
}
