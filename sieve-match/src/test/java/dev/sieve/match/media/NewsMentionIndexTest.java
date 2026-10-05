package dev.sieve.match.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sieve.core.media.AdverseMediaQuery;
import dev.sieve.core.media.AdverseMediaReport;
import dev.sieve.core.media.MediaArticle;
import dev.sieve.core.media.NewsMention;
import dev.sieve.core.media.NewsMentionFeed;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class NewsMentionIndexTest {

    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");

    private final List<NewsMention> published = new ArrayList<>();
    private final List<Instant[]> fetches = new ArrayList<>();
    private Instant now = NOW;

    private final Clock clock =
            new Clock() {
                @Override
                public ZoneId getZone() {
                    return ZoneOffset.UTC;
                }

                @Override
                public Clock withZone(ZoneId zone) {
                    return this;
                }

                @Override
                public Instant instant() {
                    return now;
                }
            };

    private final NewsMentionFeed feed =
            (from, to) -> {
                fetches.add(new Instant[] {from, to});
                List<NewsMention> batch = new ArrayList<>(published);
                published.clear();
                return new NewsMentionFeed.Batch(batch, to, 1, 0);
            };

    private NewsMentionIndex index() {
        return new NewsMentionIndex(
                feed, "GDELT GKG", Duration.ofHours(6), Duration.ofDays(3), 0.92, clock);
    }

    private static NewsMention mention(
            String url, Instant seenAt, List<String> persons, List<String> orgs) {
        MediaArticle article =
                new MediaArticle(
                        url,
                        "Title " + url,
                        "news.example",
                        "English",
                        Optional.empty(),
                        seenAt,
                        List.of("money laundering"),
                        Optional.empty());
        return new NewsMention(article, persons, orgs);
    }

    @Test
    void shouldReportUnavailableBeforeFirstRefresh() {
        AdverseMediaReport report = index().search(AdverseMediaQuery.of("John Doe"));

        assertThat(report.status()).isEqualTo(AdverseMediaReport.Status.UNAVAILABLE);
        assertThat(report.detail()).isPresent();
    }

    @Test
    void shouldFindArticlesWhenNameMatchesExactlyOrInAnotherOrder() {
        published.add(mention("u1", NOW.minusSeconds(60), List.of("John Doe"), List.of()));
        published.add(mention("u2", NOW.minusSeconds(30), List.of("Doe John"), List.of()));
        published.add(mention("u3", NOW.minusSeconds(10), List.of("Jane Roe"), List.of()));
        NewsMentionIndex index = index();
        index.refresh();

        AdverseMediaReport report = index.search(AdverseMediaQuery.of("JOHN DOE"));

        assertThat(report.status()).isEqualTo(AdverseMediaReport.Status.OK);
        assertThat(report.articles()).extracting(MediaArticle::url).containsExactly("u2", "u1");
        assertThat(report.articles().get(0).mentionedAs()).contains("Doe John");
    }

    @Test
    void shouldFindArticleWhenIndexCutTheLastLetterOfAName() {
        published.add(mention("u1", NOW.minusSeconds(60), List.of("Zeljko Cvijanovi"), List.of()));
        NewsMentionIndex index = index();
        index.refresh();

        assertThat(index.search(AdverseMediaQuery.of("Željko Cvijanović")).articles()).hasSize(1);
    }

    @Test
    void shouldNotFindArticleWhenOnlyFirstNameMatches() {
        published.add(mention("u1", NOW.minusSeconds(60), List.of("John Smith"), List.of()));
        published.add(mention("u2", NOW.minusSeconds(60), List.of("John"), List.of()));
        NewsMentionIndex index = index();
        index.refresh();

        assertThat(index.search(AdverseMediaQuery.of("John Doe")).articles()).isEmpty();
        assertThat(index.search(AdverseMediaQuery.of("John")).articles())
                .extracting(MediaArticle::url)
                .containsExactly("u2");
    }

    @Test
    void shouldNotFindArticleWhenOnlyFirstNameAndLetterPatternAgree() {
        published.add(mention("u1", NOW.minusSeconds(60), List.of("John Minto"), List.of()));
        published.add(mention("u2", NOW.minusSeconds(60), List.of("John Simte"), List.of()));
        published.add(mention("u3", NOW.minusSeconds(60), List.of("Smith"), List.of()));
        NewsMentionIndex index = index();
        index.refresh();

        assertThat(index.search(AdverseMediaQuery.of("John Smith")).articles()).isEmpty();
    }

    @Test
    void shouldFindArticleWhenSpellingDiffersSlightlyOrNameHasOneMoreWord() {
        published.add(mention("u1", NOW.minusSeconds(60), List.of("Nicholas Maduro"), List.of()));
        published.add(mention("u2", NOW.minusSeconds(30), List.of("Sam Bankman"), List.of()));
        NewsMentionIndex index = index();
        index.refresh();

        assertThat(index.search(AdverseMediaQuery.of("Nicolas Maduro")).articles()).hasSize(1);
        assertThat(index.search(AdverseMediaQuery.of("Sam Bankman-Fried")).articles())
                .extracting(MediaArticle::url)
                .containsExactly("u2");
    }

    @Test
    void shouldKeepNewestCopyWhenSitesSyndicateOneHeadline() {
        published.add(titled("u1", NOW.minusSeconds(90), "Banker held over fraud - Local News 8"));
        published.add(titled("u2", NOW.minusSeconds(60), "Banker held over fraud"));
        published.add(titled("u3", NOW.minusSeconds(30), "Another story"));
        NewsMentionIndex index = index();
        index.refresh();

        assertThat(index.search(AdverseMediaQuery.of("John Doe")).articles())
                .extracting(MediaArticle::url)
                .containsExactly("u3", "u2");
    }

    private static NewsMention titled(String url, Instant seenAt, String title) {
        MediaArticle article =
                new MediaArticle(
                        url,
                        title,
                        "news.example",
                        "English",
                        Optional.empty(),
                        seenAt,
                        List.of("fraud"),
                        Optional.empty());
        return new NewsMention(article, List.of("John Doe"), List.of());
    }

    @Test
    void shouldMatchOrganisationsWithoutTheirLegalForm() {
        published.add(mention("u1", NOW.minusSeconds(60), List.of(), List.of("Gazprombank JSC")));
        NewsMentionIndex index = index();
        index.refresh();

        assertThat(index.search(AdverseMediaQuery.of("Gazprombank")).articles()).hasSize(1);
    }

    @Test
    void shouldLeaveOutArticlesOlderThanTheLookback() {
        published.add(
                mention("old", NOW.minus(Duration.ofDays(2)), List.of("John Doe"), List.of()));
        published.add(mention("new", NOW.minusSeconds(60), List.of("John Doe"), List.of()));
        NewsMentionIndex index = index();
        index.refresh();

        AdverseMediaReport report =
                index.search(new AdverseMediaQuery("John Doe", Duration.ofDays(1), 10));

        assertThat(report.articles()).extracting(MediaArticle::url).containsExactly("new");
    }

    @Test
    void shouldReadOnlyNewFilesAndDropExpiredArticlesWhenRefreshedAgain() {
        published.add(mention("u1", NOW.minusSeconds(60), List.of("John Doe"), List.of()));
        NewsMentionIndex index = index();
        index.refresh();

        now = NOW.plus(Duration.ofDays(3));
        published.add(mention("u2", now.minusSeconds(60), List.of("John Doe"), List.of()));
        published.add(mention("u2", now.minusSeconds(60), List.of("John Doe"), List.of()));
        assertThat(index.refresh()).isEqualTo(1);

        assertThat(fetches.get(0)[0]).isEqualTo(NOW.minus(Duration.ofHours(6)));
        assertThat(fetches.get(1)[0]).isEqualTo(NOW);
        assertThat(index.size()).isEqualTo(1);
        assertThat(index.search(AdverseMediaQuery.of("John Doe")).articles())
                .extracting(MediaArticle::url)
                .containsExactly("u2");
    }

    @Test
    void shouldLimitArticlesToTheRequestedNumber() {
        for (int i = 0; i < 5; i++) {
            published.add(mention("u" + i, NOW.minusSeconds(i), List.of("John Doe"), List.of()));
        }
        NewsMentionIndex index = index();
        index.refresh();

        assertThat(
                        index.search(new AdverseMediaQuery("John Doe", Duration.ofDays(1), 2))
                                .articles())
                .extracting(MediaArticle::url)
                .containsExactly("u0", "u1");
    }

    @Test
    void shouldRejectThresholdOutsideRange() {
        assertThatThrownBy(
                        () ->
                                new NewsMentionIndex(
                                        feed,
                                        "x",
                                        Duration.ofHours(1),
                                        Duration.ofHours(1),
                                        0.5,
                                        clock))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
