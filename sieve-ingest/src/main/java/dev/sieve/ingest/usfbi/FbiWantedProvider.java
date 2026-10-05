package dev.sieve.ingest.usfbi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.EntityImage;
import dev.sieve.core.model.EntityLink;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.LinkKind;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.NameStrength;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.RiskTopic;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.SanctionsProgram;
import dev.sieve.core.model.ScriptType;
import dev.sieve.ingest.AbstractListProvider;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Fetches the people the U.S. Federal Bureau of Investigation lists as wanted, from its public
 * wanted-persons API.
 *
 * <p>The API pages through every FBI poster, including missing persons, victims, captured fugitives
 * and requests for information. Only the main subject of a poster that is still open ({@code
 * status} {@code na}) that names a suspect is kept, tagged {@link RiskTopic#WANTED}.
 * Missing-person, victim, kidnapping, unidentified-remains and information-request posters are
 * dropped. The first page is the main download; the rest are read while the request is built.
 *
 * @see <a href="https://www.fbi.gov/wanted/api">FBI Wanted API</a>
 */
public final class FbiWantedProvider extends AbstractListProvider {

    /** Photos kept per poster; posters rarely carry more than a few, and the first is the face. */
    private static final int MAX_IMAGES = 3;

    private static final String DEFAULT_URL = "https://api.fbi.gov/wanted/v1/list";
    static final int PAGE_SIZE = 50;
    private static final int MAX_PAGES = 200;

    /** Poster kinds that are about people who are not suspects. */
    private static final Set<String> NOT_WANTED_POSTERS =
            Set.of("missing", "victim", "information", "kidnapping", "ecap");

    /** Subject words that mark posters about victims, unknown people or open requests. */
    private static final List<String> NOT_WANTED_SUBJECTS =
            List.of(
                    "vicap",
                    "missing person",
                    "unidentified",
                    "seeking information",
                    "endangered child",
                    "john doe",
                    "case of the week");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter BIRTH_DATE =
            DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US);

    /** Pages after the first, downloaded while the request for the first page is built. */
    private volatile List<byte[]> laterPages = List.of();

    /** Creates a provider that reads the FBI's public API. */
    public FbiWantedProvider() {
        super(ListSource.US_FBI_WANTED, URI.create(DEFAULT_URL), "application/json");
    }

    /**
     * Creates a provider with a custom API URI and HTTP client (for testing).
     *
     * @param apiUri the list endpoint, without query parameters
     * @param httpClient the HTTP client to use for requests
     */
    public FbiWantedProvider(URI apiUri, HttpClient httpClient) {
        super(
                ListSource.US_FBI_WANTED,
                apiUri,
                "application/json",
                httpClient,
                Duration.ofSeconds(60));
    }

    @Override
    protected HttpRequest buildRequest(HttpClient client, HttpRequest.Builder builder)
            throws IOException, InterruptedException {
        List<byte[]> pages = new ArrayList<>();
        for (int page = 2; page <= MAX_PAGES; page++) {
            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(pageUri(page))
                            .timeout(Duration.ofSeconds(60))
                            .header("Accept", "application/json")
                            .header("User-Agent", "sieve-aml/1.0")
                            .GET()
                            .build();
            HttpResponse<byte[]> response =
                    client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IOException(
                        "FBI wanted page "
                                + page
                                + " download failed [status="
                                + response.statusCode()
                                + "]");
            }
            if (!MAPPER.readTree(response.body()).path("items").elements().hasNext()) {
                break;
            }
            pages.add(response.body());
        }
        laterPages = pages;
        return builder.uri(pageUri(1)).GET().build();
    }

    private URI pageUri(int page) {
        return URI.create(sourceUri() + "?pageSize=" + PAGE_SIZE + "&page=" + page);
    }

    @Override
    protected List<SanctionedEntity> parseResponse(byte[] responseBody)
            throws ListIngestionException {
        List<byte[]> pages = new ArrayList<>();
        pages.add(responseBody);
        pages.addAll(laterPages);
        return parse(pages);
    }

    /**
     * Parses API pages into the people still wanted.
     *
     * @param pages the raw JSON pages, in order
     * @return the wanted people, without duplicates
     * @throws ListIngestionException if a page is not the expected JSON
     */
    List<SanctionedEntity> parse(List<byte[]> pages) throws ListIngestionException {
        List<SanctionedEntity> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (byte[] page : pages) {
            JsonNode items;
            try {
                items = MAPPER.readTree(page).get("items");
            } catch (IOException e) {
                throw new ListIngestionException(
                        "Failed to parse FBI wanted JSON: " + e.getMessage(),
                        ListSource.US_FBI_WANTED,
                        e);
            }
            if (items == null || !items.isArray()) {
                throw new ListIngestionException(
                        "FBI wanted response has no 'items' array", ListSource.US_FBI_WANTED);
            }
            for (JsonNode item : items) {
                SanctionedEntity entity = parseItem(item);
                if (entity != null && seen.add(entity.id())) {
                    result.add(entity);
                }
            }
        }
        return result;
    }

    private static SanctionedEntity parseItem(JsonNode item) {
        String uid = text(item, "uid");
        String title = text(item, "title");
        if (uid == null || title == null || !isWanted(item)) {
            return null;
        }

        NameInfo primary =
                new NameInfo(
                        title,
                        null,
                        null,
                        null,
                        null,
                        NameType.PRIMARY,
                        NameStrength.STRONG,
                        ScriptType.LATIN);
        List<NameInfo> aliases = new ArrayList<>();
        for (JsonNode alias : item.path("aliases")) {
            String name = alias.asText().strip();
            boolean nickname = name.startsWith("\"") || name.startsWith("“");
            name = name.replaceAll("^[\"“]+|[\"”]+$", "").strip();
            if (!name.isEmpty()) {
                aliases.add(
                        new NameInfo(
                                name,
                                null,
                                null,
                                null,
                                null,
                                NameType.AKA,
                                nickname ? NameStrength.WEAK : NameStrength.STRONG,
                                ScriptType.LATIN));
            }
        }

        List<LocalDate> datesOfBirth = new ArrayList<>();
        for (JsonNode dob : item.path("dates_of_birth_used")) {
            try {
                datesOfBirth.add(LocalDate.parse(dob.asText().strip(), BIRTH_DATE));
            } catch (DateTimeParseException e) {
                // Entries such as "1975" or "Approximately 1980" carry no full date.
            }
        }

        List<String> nationalities = new ArrayList<>();
        String nationality = text(item, "nationality");
        if (nationality != null) {
            nationalities.add(nationality);
        }
        List<String> placesOfBirth = new ArrayList<>();
        String placeOfBirth = text(item, "place_of_birth");
        if (placeOfBirth != null) {
            placesOfBirth.add(placeOfBirth);
        }

        List<SanctionsProgram> programs = new ArrayList<>();
        for (JsonNode subject : item.path("subjects")) {
            String code = subject.asText().strip();
            if (!code.isEmpty()) {
                programs.add(new SanctionsProgram(code, null, ListSource.US_FBI_WANTED));
            }
        }

        StringJoiner remarks = new StringJoiner("\n");
        String charges = text(item, "description");
        if (charges != null) {
            remarks.add(charges);
        }
        String url = text(item, "url");

        return new SanctionedEntity(
                        "fbi-" + uid,
                        EntityType.INDIVIDUAL,
                        ListSource.US_FBI_WANTED,
                        primary,
                        aliases,
                        List.of(),
                        List.of(),
                        nationalities,
                        List.of(),
                        datesOfBirth,
                        placesOfBirth,
                        remarks.length() == 0 ? null : remarks.toString(),
                        programs,
                        instant(text(item, "publication")),
                        instant(text(item, "modified")),
                        Set.of(RiskTopic.WANTED),
                        List.of())
                .withImages(images(item, url))
                .withLinks(
                        url == null
                                ? List.of()
                                : List.of(
                                        new EntityLink(
                                                url,
                                                "FBI wanted poster",
                                                LinkKind.SOURCE_PAGE,
                                                null)));
    }

    /**
     * The poster's photos: the large version with its thumbnail, credited to the FBI. Posters
     * publish US government work, so no licence is stated beyond the poster's own terms.
     */
    static List<EntityImage> images(JsonNode item, String posterUrl) {
        List<EntityImage> images = new ArrayList<>();
        for (JsonNode image : item.path("images")) {
            String large = text(image, "large");
            String full = large != null ? large : text(image, "original");
            if (full != null && images.size() < MAX_IMAGES) {
                images.add(new EntityImage(full, text(image, "thumb"), posterUrl, "FBI", null));
            }
        }
        return images;
    }

    /** Keeps the main subject of an open poster that is about a suspect. */
    private static boolean isWanted(JsonNode item) {
        String person = text(item, "person_classification");
        if (person != null && !person.equalsIgnoreCase("Main")) {
            return false;
        }
        String status = text(item, "status");
        if (status != null && !status.equalsIgnoreCase("na")) {
            return false;
        }
        String poster = text(item, "poster_classification");
        if (poster != null && NOT_WANTED_POSTERS.contains(poster.toLowerCase(Locale.ROOT))) {
            return false;
        }
        String title = text(item, "title");
        if (title != null && title.strip().toUpperCase(Locale.ROOT).startsWith("UNKNOWN")) {
            return false;
        }
        for (JsonNode subject : item.path("subjects")) {
            String lower = subject.asText("").toLowerCase(Locale.ROOT);
            for (String word : NOT_WANTED_SUBJECTS) {
                if (lower.contains(word)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.isContainerNode()) {
            return null;
        }
        String s = value.asText().strip();
        return s.isEmpty() ? null : s;
    }

    private static Instant instant(String value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }
}
