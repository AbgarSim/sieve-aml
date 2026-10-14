package dev.sieve.ingest.enforcement;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class EnforcementFeedTest {

    private static final String OFAC_PAGE =
            "<table><tbody><tr><td><a href=\"/media/1/download?inline\"><strong>10/13/2026</strong></a></td>"
                    + "<td>Bank of America, N.A.</td><td>1</td><td>11,662,930</td></tr></tbody></table>";

    /** Serves the same row on every OFAC chart and nothing else; notices fail. */
    private static final class Fake implements EnforcementFeed.Fetcher {
        final List<URI> asked = new CopyOnWriteArrayList<>();

        @Override
        public byte[] get(URI uri) throws IOException {
            asked.add(uri);
            if (uri.getPath().contains("enforcement-information")) {
                return OFAC_PAGE.getBytes(StandardCharsets.UTF_8);
            }
            throw new IOException("HTTP 404 from " + uri);
        }
    }

    @Test
    void shouldMarkAnUnreadableNoticeUnreadAndReportFailedSources() {
        Fake fake = new Fake();
        var result =
                new EnforcementFeed(fake, 2026, Duration.ofMinutes(1)).fetch(Map.of(), Set.of());
        assertThat(result.actions())
                .extracting(EnforcementAction::id)
                .containsExactly("OFAC-20261013-bank-of-america-n-a");
        assertThat(result.unread()).containsExactly("OFAC-20261013-bank-of-america-n-a");
        assertThat(result.sources())
                .extracting(EnforcementFeed.SourceResult::loaded)
                .containsExactly(true, false, false, false, false, false);
    }

    @Test
    void shouldReuseProgramsOfANoticeReadBefore() {
        Fake fake = new Fake();
        EnforcementFeed feed = new EnforcementFeed(fake, 2026, Duration.ofMinutes(1));
        EnforcementAction first = feed.fetch(Map.of(), Set.of()).actions().get(0);
        EnforcementAction read =
                first.withPrograms(SanctionsPrograms.find("programs targeting Russia"));
        fake.asked.clear();
        var again = feed.fetch(Map.of(read.id(), read), Set.of());
        assertThat(again.actions().get(0).targets()).containsExactly("RU");
        assertThat(again.unread()).isEmpty();
        assertThat(fake.asked).noneMatch(u -> u.getPath().startsWith("/media/"));
    }

    @Test
    void shouldKeepPreviousActionsOfAFailedSource() {
        Fake fake = new Fake();
        var old =
                new EnforcementAction(
                        "OFSI-20260126-x",
                        "OFSI",
                        "GB",
                        EnforcementAction.Kind.CIVIL_PENALTY,
                        java.time.LocalDate.of(2026, 1, 26),
                        java.util.Optional.of("X"),
                        java.util.Optional.of(1L),
                        "GBP",
                        java.util.Optional.empty(),
                        List.of("Russia"),
                        List.of("RU"),
                        java.util.Optional.empty(),
                        "https://x");
        var result =
                new EnforcementFeed(fake, 2026, Duration.ofMinutes(1))
                        .fetch(Map.of(old.id(), old), Set.of());
        assertThat(result.actions()).extracting(EnforcementAction::id).contains("OFSI-20260126-x");
    }
}
