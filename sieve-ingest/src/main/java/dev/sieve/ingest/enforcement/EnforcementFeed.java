package dev.sieve.ingest.enforcement;

import dev.sieve.ingest.HttpClientFactory;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fetches published enforcement actions from OFAC, OFSI, FinCEN and the DOJ.
 *
 * <p>OFAC's chart does not say which programs were breached, so each notice PDF is read for them. A
 * notice is read once: actions already known with programs (from the previous night's file) keep
 * theirs, so a night only reads the notices published since.
 */
public final class EnforcementFeed {

    private static final Logger log = LoggerFactory.getLogger(EnforcementFeed.class);
    private static final int PARALLEL_DOWNLOADS = 4;
    private static final int MAX_DOJ_PAGES = 20;
    // OFAC notices name the programs in their first paragraphs; FinCEN consent orders run to
    // 30-100 pages and state the penalty and any sanctions findings well inside.
    private static final int OFAC_NOTICE_PAGES = 3;
    private static final int FINCEN_NOTICE_PAGES = 150;

    /** Fetches a URL's body. */
    @FunctionalInterface
    public interface Fetcher {
        /**
         * Fetches a URL.
         *
         * @param uri the address
         * @return the body
         * @throws IOException if the request fails or the status is not 200
         */
        byte[] get(URI uri) throws IOException;
    }

    /** One source's outcome. */
    public record SourceResult(
            String id,
            String name,
            String authority,
            String jurisdiction,
            String url,
            boolean loaded,
            int actions,
            long fetchMs,
            Optional<String> error) {}

    /**
     * Everything fetched.
     *
     * @param actions the actions, newest first
     * @param sources each source's outcome
     * @param unread ids of the actions whose notice could not be read in time; read next run
     */
    public record Result(
            List<EnforcementAction> actions, List<SourceResult> sources, Set<String> unread) {
        /** Copies the collections. */
        public Result {
            actions = List.copyOf(actions);
            sources = List.copyOf(sources);
            unread = Set.copyOf(unread);
        }
    }

    /** How long a run may spend reading notices; the rest wait for the next run. */
    static final Duration READ_BUDGET = Duration.ofMinutes(3);

    private final Fetcher fetcher;
    private final int currentYear;
    private final Duration readBudget;
    private final Set<String> unread = ConcurrentHashMap.newKeySet();
    private Set<String> previousUnread = Set.of();

    /**
     * Creates a feed over HTTP.
     *
     * @param currentYear the current year
     */
    public EnforcementFeed(int currentYear) {
        this(http(), currentYear);
    }

    /**
     * Creates a feed.
     *
     * @param fetcher fetches URLs
     * @param currentYear the current year
     */
    public EnforcementFeed(Fetcher fetcher, int currentYear) {
        this(fetcher, currentYear, READ_BUDGET);
    }

    EnforcementFeed(Fetcher fetcher, int currentYear, Duration readBudget) {
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher must not be null");
        this.currentYear = currentYear;
        this.readBudget = Objects.requireNonNull(readBudget, "readBudget must not be null");
    }

    /**
     * Fetches every source. A source that fails is reported and keeps its previous actions.
     *
     * @param previous the actions of the previous run, by id
     * @param previousUnread ids of the previous run's actions whose notice it did not read
     * @return the actions, newest first, and each source's outcome
     */
    public synchronized Result fetch(
            Map<String, EnforcementAction> previous, Set<String> previousUnread) {
        Objects.requireNonNull(previous, "previous must not be null");
        this.previousUnread = Set.copyOf(previousUnread);
        unread.clear();
        // The sources are slow government sites, so they are read side by side.
        List<Future<Loaded>> runs = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            runs.add(
                    pool.submit(
                            () ->
                                    run(
                                            "OFAC",
                                            "OFAC civil penalties",
                                            "US Treasury, Office of Foreign Assets Control",
                                            "US",
                                            OfacEnforcementParser.CURRENT.toString(),
                                            () -> ofac(previous),
                                            previous)));
            runs.add(
                    pool.submit(
                            () ->
                                    run(
                                            "OFSI",
                                            "OFSI penalties and disclosures",
                                            "HM Treasury, Office of Financial Sanctions"
                                                    + " Implementation",
                                            "GB",
                                            OfsiEnforcementParser.PAGE.toString(),
                                            () ->
                                                    OfsiEnforcementParser.parse(
                                                            text(OfsiEnforcementParser.COLLECTION)),
                                            previous)));
            runs.add(
                    pool.submit(
                            () ->
                                    run(
                                            "FINCEN",
                                            "FinCEN enforcement actions",
                                            "US Treasury, Financial Crimes Enforcement Network",
                                            "US",
                                            FincenEnforcementParser.PAGE.toString(),
                                            () -> fincen(previous),
                                            previous)));
            runs.add(
                    pool.submit(
                            () ->
                                    run(
                                            "DOJ",
                                            "DOJ criminal sanctions cases",
                                            "US Department of Justice",
                                            "US",
                                            "https://www.justice.gov/news",
                                            this::doj,
                                            previous)));
        }
        List<EnforcementAction> all = new ArrayList<>();
        List<SourceResult> sources = new ArrayList<>();
        for (Future<Loaded> f : runs) {
            Loaded l = f.resultNow();
            all.addAll(l.actions());
            sources.add(l.source());
        }
        all.sort(
                (a, b) -> {
                    int c = b.date().compareTo(a.date());
                    return c != 0 ? c : a.id().compareTo(b.id());
                });
        return new Result(all, sources, unread);
    }

    private interface Load {
        List<EnforcementAction> get() throws Exception;
    }

    private record Loaded(List<EnforcementAction> actions, SourceResult source) {}

    private static Loaded run(
            String id,
            String name,
            String authority,
            String cc,
            String url,
            Load load,
            Map<String, EnforcementAction> previous) {
        long t0 = System.nanoTime();
        try {
            List<EnforcementAction> got = load.get();
            if (got.isEmpty()) {
                throw new IOException("no actions found");
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            log.info(
                    "Enforcement source loaded [source={}, actions={}, ms={}]", id, got.size(), ms);
            return new Loaded(
                    got,
                    new SourceResult(
                            id, name, authority, cc, url, true, got.size(), ms, Optional.empty()));
        } catch (Exception e) {
            List<EnforcementAction> kept =
                    previous.values().stream().filter(a -> a.authority().equals(id)).toList();
            long ms = (System.nanoTime() - t0) / 1_000_000;
            log.warn(
                    "Enforcement source failed [source={}, kept={}, error={}]",
                    id,
                    kept.size(),
                    e.toString());
            return new Loaded(
                    kept,
                    new SourceResult(
                            id,
                            name,
                            authority,
                            cc,
                            url,
                            false,
                            kept.size(),
                            ms,
                            Optional.of(String.valueOf(e.getMessage()))));
        }
    }

    List<EnforcementAction> ofac(Map<String, EnforcementAction> previous) throws Exception {
        // The year pages are slow (seconds each), so a few are read at a time; newest first.
        Map<Integer, Future<List<EnforcementAction>>> pages = new LinkedHashMap<>();
        try (ExecutorService pool =
                Executors.newFixedThreadPool(PARALLEL_DOWNLOADS, Thread.ofVirtual().factory())) {
            for (int year = currentYear; year >= OfacEnforcementParser.FIRST_YEAR; year--) {
                URI page = OfacEnforcementParser.yearPage(year, currentYear);
                pages.put(year, pool.submit(() -> OfacEnforcementParser.parse(text(page), page)));
            }
        }
        Map<String, EnforcementAction> byId = new LinkedHashMap<>();
        int failed = 0;
        for (var e : pages.entrySet()) {
            try {
                for (EnforcementAction a : e.getValue().get()) {
                    byId.putIfAbsent(a.id(), a);
                }
            } catch (ExecutionException ex) {
                failed++;
                log.warn(
                        "OFAC year page failed [year={}, error={}]",
                        e.getKey(),
                        ex.getCause().toString());
                previous.values().stream()
                        .filter(a -> a.authority().equals("OFAC"))
                        .forEach(a -> byId.putIfAbsent(a.id(), a));
            }
        }
        if (failed > 3) {
            throw new IOException(failed + " OFAC year pages failed");
        }
        log.info("OFAC charts read [actions={}, failedYears={}]", byId.size(), failed);
        Map<String, Integer> perNotice = new HashMap<>();
        byId.values().forEach(a -> perNotice.merge(a.url(), 1, Integer::sum));
        return withNotices(
                new ArrayList<>(byId.values()),
                previous,
                OFAC_NOTICE_PAGES,
                (a, text) ->
                        a.withPrograms(
                                OfacEnforcementParser.programsFor(
                                        text, a.party().orElse(""), perNotice.get(a.url()) > 1)));
    }

    List<EnforcementAction> fincen(Map<String, EnforcementAction> previous) throws Exception {
        return withNotices(
                FincenEnforcementParser.parse(text(FincenEnforcementParser.PAGE)),
                previous,
                FINCEN_NOTICE_PAGES,
                (a, text) ->
                        a.withPrograms(FincenEnforcementParser.programsIn(text))
                                .withAmount(FincenEnforcementParser.penaltyIn(text)));
    }

    /**
     * Completes actions from their notices: a notice read on an earlier run is not read again (the
     * earlier action is kept as it was), and one that cannot be read in time is marked unread.
     */
    private List<EnforcementAction> withNotices(
            List<EnforcementAction> actions,
            Map<String, EnforcementAction> previous,
            int pages,
            BiFunction<EnforcementAction, String, EnforcementAction> complete)
            throws InterruptedException {
        Map<String, Optional<String>> texts = new HashMap<>();
        List<String> toRead =
                actions.stream()
                        .filter(a -> known(previous, a).isEmpty())
                        .map(EnforcementAction::url)
                        .distinct()
                        .toList();
        readAll(toRead, pages, texts);
        List<EnforcementAction> out = new ArrayList<>(actions.size());
        int reused = 0;
        for (EnforcementAction a : actions) {
            Optional<EnforcementAction> old = known(previous, a);
            if (old.isPresent()) {
                out.add(
                        a.withPrograms(
                                        new SanctionsPrograms.Found(
                                                old.get().programs(), old.get().targets()))
                                .withAmount(old.get().amount().or(a::amount)));
                reused++;
                continue;
            }
            Optional<String> text = texts.getOrDefault(a.url(), Optional.empty());
            if (text.isEmpty()) {
                unread.add(a.id());
                out.add(a);
            } else {
                out.add(complete.apply(a, text.get()));
            }
        }
        log.info(
                "Notices read [read={}, unread={}, reused={}]",
                texts.values().stream().filter(Optional::isPresent).count(),
                out.size() - reused - texts.values().stream().filter(Optional::isPresent).count(),
                reused);
        return out;
    }

    /** The previous run's action of the same id and notice, when its notice was read. */
    private Optional<EnforcementAction> known(
            Map<String, EnforcementAction> previous, EnforcementAction a) {
        EnforcementAction old = previous.get(a.id());
        return old != null && old.url().equals(a.url()) && !previousUnread.contains(a.id())
                ? Optional.of(old)
                : Optional.empty();
    }

    private void readAll(List<String> urls, int pages, Map<String, Optional<String>> into)
            throws InterruptedException {
        // A few at a time and in order, so that the newest notices are read first; whatever is
        // not read when the budget runs out waits for the next run.
        long deadline = System.nanoTime() + readBudget.toNanos();
        Map<String, Future<Optional<String>>> futures = new LinkedHashMap<>();
        ExecutorService pool =
                Executors.newFixedThreadPool(PARALLEL_DOWNLOADS, Thread.ofVirtual().factory());
        try {
            for (String url : urls) {
                futures.put(
                        url,
                        pool.submit(
                                () -> {
                                    if (System.nanoTime() > deadline) {
                                        return Optional.<String>empty();
                                    }
                                    try {
                                        return Optional.of(
                                                pdfText(fetcher.get(URI.create(url)), pages));
                                    } catch (IOException e) {
                                        log.warn(
                                                "Notice unreadable [url={}, error={}]",
                                                url,
                                                e.toString());
                                        return Optional.<String>empty();
                                    }
                                }));
            }
            long grace = Duration.ofSeconds(20).toNanos();
            for (var e : futures.entrySet()) {
                long wait = Math.max(0, deadline + grace - System.nanoTime());
                try {
                    into.put(e.getKey(), e.getValue().get(wait, TimeUnit.NANOSECONDS));
                } catch (ExecutionException | TimeoutException ex) {
                    e.getValue().cancel(true);
                    into.put(e.getKey(), Optional.empty());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** The text of a PDF's first pages. */
    static String pdfText(byte[] pdf, int pages) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setEndPage(pages);
            return stripper.getText(doc);
        }
    }

    List<EnforcementAction> doj() throws Exception {
        Map<String, EnforcementAction> byCase = new LinkedHashMap<>();
        for (int page = 0; page < MAX_DOJ_PAGES; page++) {
            String json = text(DojSanctionsParser.page(page));
            DojSanctionsParser.parse(json)
                    .forEach(a -> byCase.putIfAbsent(DojSanctionsParser.caseKey(a), a));
            if (DojSanctionsParser.results(json) < DojSanctionsParser.PAGE_SIZE) {
                break;
            }
            Thread.sleep(300); // the API asks for fewer than 4 requests a second
        }
        return new ArrayList<>(byCase.values());
    }

    private String text(URI uri) throws IOException {
        return new String(fetcher.get(uri), StandardCharsets.UTF_8);
    }

    private static Fetcher http() {
        HttpClient client = HttpClientFactory.createTrustAllClient(Duration.ofSeconds(30));
        return uri -> {
            HttpRequest req =
                    HttpRequest.newBuilder(uri)
                            .timeout(Duration.ofSeconds(30))
                            .header(
                                    "User-Agent",
                                    "sieve-aml (+https://github.com/AbgarSim/sieve-aml)")
                            .GET()
                            .build();
            IOException last = null;
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    HttpResponse<byte[]> r =
                            client.send(req, HttpResponse.BodyHandlers.ofByteArray());
                    if (r.statusCode() == 200) {
                        return r.body();
                    }
                    last = new IOException("HTTP " + r.statusCode() + " from " + uri);
                    if (r.statusCode() == 404) {
                        throw last;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", e);
                } catch (IOException e) {
                    if (e == last) {
                        throw e;
                    }
                    last = e;
                }
            }
            throw last;
        };
    }

    /** Identity map helper for callers holding a list. */
    public static Map<String, EnforcementAction> byId(List<EnforcementAction> actions) {
        return actions.stream()
                .collect(
                        java.util.stream.Collectors.toMap(
                                EnforcementAction::id,
                                Function.identity(),
                                (a, b) -> a,
                                LinkedHashMap::new));
    }
}
