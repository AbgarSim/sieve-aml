package dev.sieve.ingest.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sieve.core.media.AdverseMediaQuery;
import dev.sieve.core.media.AdverseMediaReport;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class GdeltAdverseMediaSearchTest {

    private static final URI ENDPOINT = URI.create("https://gdelt.test/api/v2/doc/doc");
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-10-05T12:00:00Z"), ZoneOffset.UTC);
    private static final String ONE_ARTICLE =
            """
            {"articles": [{"url": "https://example.org/a", "title": "Trader convicted of fraud",
              "seendate": "20261001T120000Z", "domain": "example.org", "language": "English",
              "sourcecountry": "United Kingdom"}]}
            """;

    private final List<URI> requested = new ArrayList<>();

    private GdeltAdverseMediaSearch search(
            GdeltAdverseMediaSearch.Fetcher fetcher, Duration minInterval, Duration cacheTtl) {
        return new GdeltAdverseMediaSearch(
                ENDPOINT,
                uri -> {
                    requested.add(uri);
                    return fetcher.get(uri);
                },
                CLOCK,
                List.of("fraud", "money laundering"),
                minInterval,
                Duration.ZERO,
                cacheTtl);
    }

    @Test
    void shouldReturnArticlesWhenIndexAnswers() {
        AdverseMediaReport report =
                search(
                                uri ->
                                        new GdeltAdverseMediaSearch.Fetcher.Response(
                                                200, ONE_ARTICLE),
                                Duration.ZERO,
                                Duration.ZERO)
                        .search(AdverseMediaQuery.of("John Doe"));

        assertThat(report.status()).isEqualTo(AdverseMediaReport.Status.OK);
        assertThat(report.index()).isEqualTo("GDELT");
        assertThat(report.indexQuery()).isEqualTo("\"John Doe\" (fraud OR \"money laundering\")");
        assertThat(report.articles()).hasSize(1);
        assertThat(report.articles().get(0).adverseTerms()).containsExactly("fraud");
        assertThat(report.detail()).isEmpty();
    }

    @Test
    void shouldSendQueryWindowAndLimitWhenSearching() {
        search(
                        uri -> new GdeltAdverseMediaSearch.Fetcher.Response(200, "{}"),
                        Duration.ZERO,
                        Duration.ZERO)
                .search(new AdverseMediaQuery("John Doe", Duration.ofDays(400), 10));

        String query = URLDecoder.decode(requested.get(0).getRawQuery(), StandardCharsets.UTF_8);
        assertThat(query)
                .startsWith("query=\"John Doe\" (fraud OR \"money laundering\")")
                .contains("mode=artlist", "format=json", "maxrecords=10", "timespan=90d");
    }

    @Test
    void shouldReportUnavailableWhenIndexRefusesQuery() {
        AdverseMediaReport report =
                search(
                                uri ->
                                        new GdeltAdverseMediaSearch.Fetcher.Response(
                                                200, "The specified phrase is too short."),
                                Duration.ZERO,
                                Duration.ZERO)
                        .search(AdverseMediaQuery.of("Al"));

        assertThat(report.status()).isEqualTo(AdverseMediaReport.Status.UNAVAILABLE);
        assertThat(report.articles()).isEmpty();
        assertThat(report.detail()).hasValueSatisfying(d -> assertThat(d).contains("too short"));
    }

    @Test
    void shouldReportUnavailableWhenIndexAnswersTooManyRequests() {
        AdverseMediaReport report =
                search(
                                uri ->
                                        new GdeltAdverseMediaSearch.Fetcher.Response(
                                                429, "Please limit"),
                                Duration.ZERO,
                                Duration.ZERO)
                        .search(AdverseMediaQuery.of("John Doe"));

        assertThat(report.status()).isEqualTo(AdverseMediaReport.Status.UNAVAILABLE);
        assertThat(report.detail()).contains("GDELT answered HTTP 429");
    }

    @Test
    void shouldReportUnavailableWhenIndexIsUnreachable() {
        AdverseMediaReport report =
                search(
                                uri -> {
                                    throw new IOException("connect timed out");
                                },
                                Duration.ZERO,
                                Duration.ZERO)
                        .search(AdverseMediaQuery.of("John Doe"));

        assertThat(report.status()).isEqualTo(AdverseMediaReport.Status.UNAVAILABLE);
        assertThat(report.detail()).hasValueSatisfying(d -> assertThat(d).contains("timed out"));
    }

    @Test
    void shouldNotAskIndexWhenNextSlotIsLaterThanCallerWillWait() {
        GdeltAdverseMediaSearch search =
                search(
                        uri -> new GdeltAdverseMediaSearch.Fetcher.Response(200, "{}"),
                        Duration.ofSeconds(5),
                        Duration.ZERO);

        AdverseMediaReport first = search.search(AdverseMediaQuery.of("John Doe"));
        AdverseMediaReport second = search.search(AdverseMediaQuery.of("Jane Roe"));

        assertThat(first.status()).isEqualTo(AdverseMediaReport.Status.OK);
        assertThat(second.status()).isEqualTo(AdverseMediaReport.Status.UNAVAILABLE);
        assertThat(second.detail()).hasValueSatisfying(d -> assertThat(d).contains("Too many"));
        assertThat(requested).hasSize(1);
    }

    @Test
    void shouldReuseReportWhenSameQueryRepeatsWithinCacheTtl() {
        GdeltAdverseMediaSearch search =
                search(
                        uri -> new GdeltAdverseMediaSearch.Fetcher.Response(200, ONE_ARTICLE),
                        Duration.ofSeconds(5),
                        Duration.ofHours(1));

        AdverseMediaReport first = search.search(AdverseMediaQuery.of("John Doe"));
        AdverseMediaReport second = search.search(AdverseMediaQuery.of("John Doe"));

        assertThat(second).isSameAs(first);
        assertThat(requested).hasSize(1);
    }

    @Test
    void shouldStripQueryOperatorsWhenNameContainsThem() {
        assertThat(GdeltAdverseMediaSearch.indexQuery("John \"X\" (Doe)", List.of("fraud")))
                .isEqualTo("\"John X Doe\" (fraud)");
        assertThatThrownBy(() -> GdeltAdverseMediaSearch.indexQuery("\"()\"", List.of("fraud")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
