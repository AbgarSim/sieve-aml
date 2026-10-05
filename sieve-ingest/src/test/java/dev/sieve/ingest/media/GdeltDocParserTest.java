package dev.sieve.ingest.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sieve.core.media.MediaArticle;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class GdeltDocParserTest {

    private static final String BODY =
            """
            {"articles": [
              {"url": "https://example.org/a", "url_mobile": "", "title": "Bout arrested again",
               "seendate": "20260901T101500Z", "socialimage": "", "domain": "example.org",
               "language": "English", "sourcecountry": "United States"},
              {"url": "https://example.com/b", "url_mobile": "", "title": "Sanctions widen",
               "seendate": "20260915T080000Z", "socialimage": "", "domain": "example.com",
               "language": "French", "sourcecountry": ""},
              {"url": "https://example.org/a", "title": "Duplicate", "seendate": "20260902T000000Z"},
              {"url": "https://example.net/c", "title": "No date", "seendate": "yesterday"}
            ]}
            """;

    @Test
    void shouldReadArticlesNewestFirstWhenBodyIsAnArticleList() {
        List<MediaArticle> articles = GdeltDocParser.parse(BODY, AdverseTerms.DEFAULT);

        assertThat(articles)
                .extracting(MediaArticle::url)
                .containsExactly("https://example.com/b", "https://example.org/a");
        MediaArticle first = articles.get(0);
        assertThat(first.seenAt()).isEqualTo(Instant.parse("2026-09-15T08:00:00Z"));
        assertThat(first.language()).isEqualTo("French");
        assertThat(first.sourceCountry()).isEmpty();
        assertThat(first.adverseTerms()).containsExactly("sanctions");
        assertThat(articles.get(1).sourceCountry()).contains("United States");
        assertThat(articles.get(1).adverseTerms()).containsExactly("arrested");
    }

    @Test
    void shouldReturnNoArticlesWhenIndexFoundNothing() {
        assertThat(GdeltDocParser.parse("{}", AdverseTerms.DEFAULT)).isEmpty();
        assertThat(GdeltDocParser.parse("  ", AdverseTerms.DEFAULT)).isEmpty();
    }

    @Test
    void shouldThrowRejectionWhenBodyIsPlainText() {
        assertThatThrownBy(
                        () ->
                                GdeltDocParser.parse(
                                        "Please limit requests to one every 5 seconds",
                                        AdverseTerms.DEFAULT))
                .isInstanceOf(GdeltDocParser.GdeltRejection.class)
                .hasMessageContaining("one every 5 seconds");
    }
}
