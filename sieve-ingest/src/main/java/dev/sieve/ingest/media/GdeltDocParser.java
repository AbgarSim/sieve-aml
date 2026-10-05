package dev.sieve.ingest.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sieve.core.media.MediaArticle;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Reads the article list the GDELT DOC 2.0 API returns in {@code mode=artlist&format=json}.
 *
 * <p>The API answers {@code {"articles": [...]}} when it finds articles and {@code {}} when it
 * finds none. When it rejects a query (a phrase too short, too many requests) it answers with a
 * plain-text message instead of JSON, which this parser reports as {@link GdeltRejection}.
 */
final class GdeltDocParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter SEEN_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final int MAX_MESSAGE_LENGTH = 200;

    private GdeltDocParser() {}

    /**
     * Parses an API response body.
     *
     * @param body the response body
     * @param terms the adverse terms of the query, to mark the ones a headline contains
     * @return the articles, newest first, without duplicate URLs
     * @throws GdeltRejection if the body is the API's plain-text refusal rather than JSON
     */
    static List<MediaArticle> parse(String body, List<String> terms) {
        String trimmed = body.strip();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        if (!trimmed.startsWith("{")) {
            throw new GdeltRejection(shorten(trimmed));
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(trimmed);
        } catch (IOException e) {
            throw new GdeltRejection("Unreadable response: " + shorten(trimmed));
        }
        List<MediaArticle> articles = new ArrayList<>();
        List<String> seenUrls = new ArrayList<>();
        for (JsonNode node : root.path("articles")) {
            String url = node.path("url").asText("").strip();
            Optional<Instant> seenAt = seenDate(node.path("seendate").asText(""));
            if (url.isEmpty() || seenAt.isEmpty() || seenUrls.contains(url)) {
                continue;
            }
            seenUrls.add(url);
            String title = node.path("title").asText("").strip();
            String country = node.path("sourcecountry").asText("").strip();
            articles.add(
                    new MediaArticle(
                            url,
                            title,
                            node.path("domain").asText("").strip(),
                            node.path("language").asText("").strip(),
                            country.isEmpty() ? Optional.empty() : Optional.of(country),
                            seenAt.get(),
                            AdverseTerms.foundIn(title, terms),
                            Optional.empty()));
        }
        articles.sort(Comparator.comparing(MediaArticle::seenAt).reversed());
        return articles;
    }

    private static Optional<Instant> seenDate(String value) {
        try {
            return Optional.of(LocalDateTime.parse(value, SEEN_DATE).toInstant(ZoneOffset.UTC));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private static String shorten(String text) {
        String oneLine = text.replaceAll("\\s+", " ");
        return oneLine.length() <= MAX_MESSAGE_LENGTH
                ? oneLine
                : oneLine.substring(0, MAX_MESSAGE_LENGTH) + "...";
    }

    /** The API refused the query and said why in plain text. */
    static final class GdeltRejection extends RuntimeException {
        GdeltRejection(String message) {
            super(message);
        }
    }
}
