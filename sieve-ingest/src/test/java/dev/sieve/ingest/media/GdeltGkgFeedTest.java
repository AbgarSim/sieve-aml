package dev.sieve.ingest.media;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sieve.core.media.NewsMentionFeed;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class GdeltGkgFeedTest {

    private static final URI BASE = URI.create("http://gdelt.test/gdeltv2/");

    private final Map<String, byte[]> files = new HashMap<>();
    private final List<String> requested = new ArrayList<>();

    private GdeltGkgFeed feed(boolean translated) {
        return new GdeltGkgFeed(
                BASE,
                translated,
                uri -> {
                    String name = BASE.relativize(uri).toString();
                    synchronized (requested) {
                        requested.add(name);
                    }
                    byte[] body = files.get(name);
                    if (body == null) {
                        throw new FileNotFoundException(name);
                    }
                    return new ByteArrayInputStream(body);
                });
    }

    private void publish(String stamp, String kind, String url) throws IOException {
        String line =
                GdeltGkgParserTest.line(
                        stamp, "1", url, "CORRUPTION,1", "John Doe,1", "", "", "Title");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(stamp + "." + kind + ".csv"));
            zip.write(line.getBytes(StandardCharsets.UTF_8));
        }
        files.put(stamp + "." + kind + ".csv.zip", out.toByteArray());
    }

    private void latest(String stamp) {
        files.put(
                "lastupdate.txt",
                ("1 abc http://gdelt.test/gdeltv2/"
                                + stamp
                                + ".export.CSV.zip\n"
                                + "2 def http://gdelt.test/gdeltv2/"
                                + stamp
                                + ".gkg.csv.zip\n")
                        .getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void shouldReadEveryFileFromStartToLatestWhenAllArePublished() throws IOException {
        latest("20261005123000");
        publish("20261005120000", "gkg", "https://a.example/1");
        publish("20261005121500", "gkg", "https://a.example/2");
        publish("20261005123000", "gkg", "https://a.example/3");

        NewsMentionFeed.Batch batch =
                feed(false)
                        .fetch(
                                Instant.parse("2026-10-05T11:55:00Z"),
                                Instant.parse("2026-10-05T12:40:00Z"));

        assertThat(batch.mentions()).hasSize(3);
        assertThat(batch.filesRead()).isEqualTo(3);
        assertThat(batch.filesMissing()).isZero();
        assertThat(batch.coveredUntil()).isEqualTo(Instant.parse("2026-10-05T12:45:00Z"));
    }

    @Test
    void shouldReadNewestSlotAgainWhenItsTranslatedFileIsNotPublishedYet() throws IOException {
        latest("20261005121500");
        publish("20261005120000", "gkg", "https://a.example/1");
        publish("20261005120000", "translation.gkg", "https://b.example/1");
        publish("20261005121500", "gkg", "https://a.example/2");

        NewsMentionFeed.Batch batch =
                feed(true)
                        .fetch(
                                Instant.parse("2026-10-05T12:00:00Z"),
                                Instant.parse("2026-10-05T12:20:00Z"));

        assertThat(batch.mentions()).hasSize(3);
        assertThat(batch.filesMissing()).isEqualTo(1);
        assertThat(batch.coveredUntil()).isEqualTo(Instant.parse("2026-10-05T12:15:00Z"));
    }

    @Test
    void shouldSkipMissingFilesInTheMiddleOfTheWindow() throws IOException {
        latest("20261005123000");
        publish("20261005120000", "gkg", "https://a.example/1");
        publish("20261005123000", "gkg", "https://a.example/3");

        NewsMentionFeed.Batch batch =
                feed(false)
                        .fetch(
                                Instant.parse("2026-10-05T12:00:00Z"),
                                Instant.parse("2026-10-05T12:40:00Z"));

        assertThat(batch.mentions()).hasSize(2);
        assertThat(batch.filesMissing()).isEqualTo(1);
        assertThat(batch.coveredUntil()).isEqualTo(Instant.parse("2026-10-05T12:45:00Z"));
    }

    @Test
    void shouldReturnEmptyBatchWhenLatestFileIsUnknown() {
        Instant from = Instant.parse("2026-10-05T12:00:00Z");

        NewsMentionFeed.Batch batch = feed(false).fetch(from, from.plusSeconds(3600));

        assertThat(batch.mentions()).isEmpty();
        assertThat(batch.filesRead()).isZero();
        assertThat(batch.coveredUntil()).isEqualTo(from);
    }

    @Test
    void shouldRoundStartUpToTheNextFileTime() {
        List<Instant> slots =
                GdeltGkgFeed.slots(
                        Instant.parse("2026-10-05T12:01:00Z"),
                        Instant.parse("2026-10-05T13:00:00Z"),
                        Instant.parse("2026-10-05T12:30:00Z"));

        assertThat(slots)
                .containsExactly(
                        Instant.parse("2026-10-05T12:15:00Z"),
                        Instant.parse("2026-10-05T12:30:00Z"));
    }
}
