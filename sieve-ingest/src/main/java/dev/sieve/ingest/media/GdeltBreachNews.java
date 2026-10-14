package dev.sieve.ingest.media;

import dev.sieve.ingest.enforcement.SuspectedBreach;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds news of suspected sanctions breaches in the GDELT Global Knowledge Graph's English files:
 * web articles whose title names sanctions together with evasion, a violation or an enforcement
 * step (see {@link SuspectedBreach#reportsBreach}).
 */
public final class GdeltBreachNews {

    private static final Logger log = LoggerFactory.getLogger(GdeltBreachNews.class);
    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final Pattern LATEST_GKG = Pattern.compile("(\\d{14})\\.gkg\\.csv\\.zip");
    private static final int PARALLEL_DOWNLOADS = 4;

    private final URI base;
    private final GdeltGkgFeed.Downloader downloader;

    /** Reads GDELT's public files. */
    public GdeltBreachNews() {
        this(GdeltGkgFeed.DEFAULT_BASE, GdeltGkgFeed.httpDownloader());
    }

    GdeltBreachNews(URI base, GdeltGkgFeed.Downloader downloader) {
        this.base = Objects.requireNonNull(base, "base must not be null");
        this.downloader = Objects.requireNonNull(downloader, "downloader must not be null");
    }

    /**
     * Reads the files of a recent period.
     *
     * @param lookback how far back from the newest file
     * @return the articles, one per address
     */
    public List<SuspectedBreach> read(Duration lookback) {
        Optional<Instant> latest = latest();
        if (latest.isEmpty()) {
            return List.of();
        }
        List<Instant> slots =
                GdeltGkgFeed.slots(
                        latest.get().minus(lookback),
                        latest.get().plus(GdeltGkgFeed.FILE_INTERVAL),
                        latest.get());
        Map<String, SuspectedBreach> byUrl = new LinkedHashMap<>();
        int missing = 0;
        try (ExecutorService pool = Executors.newFixedThreadPool(PARALLEL_DOWNLOADS)) {
            List<Future<List<SuspectedBreach>>> files = new ArrayList<>();
            for (Instant slot : slots) {
                URI file =
                        base.resolve(
                                FILE_TIME.format(LocalDateTime.ofInstant(slot, ZoneOffset.UTC))
                                        + ".gkg.csv.zip");
                files.add(pool.submit(() -> readFile(file)));
            }
            for (Future<List<SuspectedBreach>> f : files) {
                try {
                    f.get().forEach(b -> byUrl.putIfAbsent(b.url(), b));
                } catch (ExecutionException e) {
                    missing++;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info(
                "GKG breach news read [files={}, missing={}, articles={}]",
                slots.size(),
                missing,
                byUrl.size());
        return new ArrayList<>(byUrl.values());
    }

    private List<SuspectedBreach> readFile(URI file) throws IOException, InterruptedException {
        try (ZipInputStream zip = new ZipInputStream(downloader.open(file))) {
            if (zip.getNextEntry() == null) {
                throw new IOException("Empty archive " + file);
            }
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(zip, StandardCharsets.UTF_8));
            List<SuspectedBreach> out = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                parseLine(line).ifPresent(out::add);
            }
            return out;
        }
    }

    static Optional<SuspectedBreach> parseLine(String line) {
        // Cheap test first: most lines never mention sanctions.
        if (!line.toLowerCase(java.util.Locale.ROOT).contains("sanction")) {
            return Optional.empty();
        }
        String[] f = line.split("\t", -1);
        if (f.length < 27 || !"1".equals(f[2]) || !f[4].startsWith("http")) {
            return Optional.empty();
        }
        String title = GdeltGkgParser.title(f[26]);
        if (title.isEmpty() || !SuspectedBreach.reportsBreach(title)) {
            return Optional.empty();
        }
        try {
            Instant seenAt = LocalDateTime.parse(f[1], FILE_TIME).toInstant(ZoneOffset.UTC);
            return Optional.of(SuspectedBreach.of(f[4], title, f[3], seenAt));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private Optional<Instant> latest() {
        try (InputStream in = downloader.open(base.resolve("lastupdate.txt"))) {
            Matcher m = LATEST_GKG.matcher(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            if (m.find()) {
                return Optional.of(
                        LocalDateTime.parse(m.group(1), FILE_TIME).toInstant(ZoneOffset.UTC));
            }
        } catch (IOException e) {
            log.warn("GKG lastupdate.txt unreadable [reason={}]", e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Optional.empty();
    }
}
