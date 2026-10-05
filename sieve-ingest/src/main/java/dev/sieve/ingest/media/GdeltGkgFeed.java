package dev.sieve.ingest.media;

import dev.sieve.core.media.NewsMention;
import dev.sieve.core.media.NewsMentionFeed;
import dev.sieve.ingest.HttpClientFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
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
 * Reads adverse articles from the GDELT Global Knowledge Graph, which GDELT publishes every 15
 * minutes as a zipped file of the online news it processed, in English and machine-translated from
 * other languages.
 *
 * <p>Each file is about 5 MB (English) or 12 MB (translated) zipped. Files are fetched a few at a
 * time and parsed as they stream in; only adverse articles that name someone are kept.
 */
public final class GdeltGkgFeed implements NewsMentionFeed {

    /** Where GDELT publishes the files. */
    public static final URI DEFAULT_BASE = URI.create("http://data.gdeltproject.org/gdeltv2/");

    static final Duration FILE_INTERVAL = Duration.ofMinutes(15);

    private static final Logger log = LoggerFactory.getLogger(GdeltGkgFeed.class);
    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final Pattern LATEST_GKG = Pattern.compile("(\\d{14})\\.gkg\\.csv\\.zip");
    private static final int PARALLEL_DOWNLOADS = 4;

    private final URI base;
    private final boolean includeTranslated;
    private final Downloader downloader;

    /**
     * Creates a feed reading GDELT's public files.
     *
     * @param includeTranslated whether to read the machine-translated non-English files too
     */
    public GdeltGkgFeed(boolean includeTranslated) {
        this(DEFAULT_BASE, includeTranslated, httpDownloader());
    }

    GdeltGkgFeed(URI base, boolean includeTranslated, Downloader downloader) {
        this.base = Objects.requireNonNull(base, "base must not be null");
        this.includeTranslated = includeTranslated;
        this.downloader = Objects.requireNonNull(downloader, "downloader must not be null");
    }

    @Override
    public Batch fetch(Instant from, Instant to) {
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        Optional<Instant> latest = latestFile();
        if (latest.isEmpty()) {
            return new Batch(List.of(), from, 0, 0);
        }
        List<Instant> slots = slots(from, to, latest.get());
        if (slots.isEmpty()) {
            return new Batch(List.of(), from, 0, 0);
        }

        List<URI> files = new ArrayList<>();
        for (Instant slot : slots) {
            String stamp = FILE_TIME.format(LocalDateTime.ofInstant(slot, ZoneOffset.UTC));
            files.add(base.resolve(stamp + ".gkg.csv.zip"));
            if (includeTranslated) {
                files.add(base.resolve(stamp + ".translation.gkg.csv.zip"));
            }
        }

        long started = System.nanoTime();
        List<NewsMention> mentions = new ArrayList<>();
        int read = 0;
        int missing = 0;
        boolean lastSlotMissing = false;
        try (ExecutorService pool = Executors.newFixedThreadPool(PARALLEL_DOWNLOADS)) {
            List<Future<List<NewsMention>>> results = new ArrayList<>();
            for (URI file : files) {
                results.add(pool.submit(() -> readFile(file)));
            }
            for (int i = 0; i < results.size(); i++) {
                try {
                    mentions.addAll(results.get(i).get());
                    read++;
                } catch (ExecutionException e) {
                    missing++;
                    lastSlotMissing |= i >= files.size() - filesPerSlot();
                    log.debug(
                            "GKG file skipped [file={}, reason={}]",
                            files.get(i),
                            e.getCause().toString());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // A file of the newest slot may not be published yet; read that slot again next time.
        Instant lastSlot = slots.get(slots.size() - 1);
        Instant coveredUntil = lastSlotMissing ? lastSlot : lastSlot.plus(FILE_INTERVAL);
        log.info(
                "GKG files read [from={}, until={}, files={}, missing={}, adverseArticles={},"
                        + " ms={}]",
                slots.get(0),
                coveredUntil,
                read,
                missing,
                mentions.size(),
                (System.nanoTime() - started) / 1_000_000);
        return new Batch(mentions, coveredUntil, read, missing);
    }

    private int filesPerSlot() {
        return includeTranslated ? 2 : 1;
    }

    /**
     * Returns the 15-minute file times from {@code from} (rounded up) to before {@code to}, no
     * later than the latest published file.
     */
    static List<Instant> slots(Instant from, Instant to, Instant latest) {
        Instant first = from.truncatedTo(ChronoUnit.HOURS);
        while (first.isBefore(from)) {
            first = first.plus(FILE_INTERVAL);
        }
        List<Instant> slots = new ArrayList<>();
        for (Instant slot = first;
                slot.isBefore(to) && !slot.isAfter(latest);
                slot = slot.plus(FILE_INTERVAL)) {
            slots.add(slot);
        }
        return slots;
    }

    private Optional<Instant> latestFile() {
        try (InputStream in = downloader.open(base.resolve("lastupdate.txt"))) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Matcher m = LATEST_GKG.matcher(text);
            if (m.find()) {
                return Optional.of(
                        LocalDateTime.parse(m.group(1), FILE_TIME).toInstant(ZoneOffset.UTC));
            }
            log.warn("GKG latest file not found in lastupdate.txt");
        } catch (IOException e) {
            log.warn("GKG lastupdate.txt unreadable [reason={}]", e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Optional.empty();
    }

    private List<NewsMention> readFile(URI file) throws IOException, InterruptedException {
        try (ZipInputStream zip = new ZipInputStream(downloader.open(file))) {
            if (zip.getNextEntry() == null) {
                throw new IOException("Empty archive " + file);
            }
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(zip, StandardCharsets.UTF_8));
            return GdeltGkgParser.parse(reader);
        }
    }

    private static Downloader httpDownloader() {
        HttpClient client = HttpClientFactory.createTrustAllClient(Duration.ofSeconds(15));
        return uri -> {
            HttpRequest request =
                    HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(120)).GET().build();
            HttpResponse<InputStream> response =
                    client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                response.body().close();
                throw new IOException("HTTP " + response.statusCode() + " for " + uri);
            }
            return response.body();
        };
    }

    /** Opens a URL for reading; replaced in tests. */
    @FunctionalInterface
    interface Downloader {
        InputStream open(URI uri) throws IOException, InterruptedException;
    }
}
