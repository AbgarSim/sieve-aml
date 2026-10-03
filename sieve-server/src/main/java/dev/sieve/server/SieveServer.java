package dev.sieve.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.sieve.address.AddressMatchService;
import dev.sieve.address.AddressNormalizer;
import dev.sieve.core.audit.ScreeningAuditEmitter;
import dev.sieve.core.index.EntityIndex;
import dev.sieve.core.index.InMemoryEntityIndex;
import dev.sieve.core.match.MatchEngine;
import dev.sieve.ingest.IngestionOrchestrator;
import dev.sieve.ingest.IngestionReport;
import dev.sieve.ingest.ListProvider;
import dev.sieve.ingest.ar.ArRepetProvider;
import dev.sieve.ingest.au.AuDfatProvider;
import dev.sieve.ingest.be.BeFodProvider;
import dev.sieve.ingest.ca.CanadaConsolidatedProvider;
import dev.sieve.ingest.ch.ChSecoProvider;
import dev.sieve.ingest.eu.EuConsolidatedProvider;
import dev.sieve.ingest.eu.EuJournalProvider;
import dev.sieve.ingest.eu.EuSanctionsMapProvider;
import dev.sieve.ingest.eu.EuTravelBansProvider;
import dev.sieve.ingest.fr.FrTresorProvider;
import dev.sieve.ingest.il.IlWmdTerrorProvider;
import dev.sieve.ingest.in.InMhaProvider;
import dev.sieve.ingest.jp.JpMofProvider;
import dev.sieve.ingest.lv.LvFiuProvider;
import dev.sieve.ingest.mc.McFundFreezingProvider;
import dev.sieve.ingest.md.MdTerrorProvider;
import dev.sieve.ingest.nz.NzRussiaProvider;
import dev.sieve.ingest.ofac.OfacNonSdnProvider;
import dev.sieve.ingest.ofac.OfacSdnProvider;
import dev.sieve.ingest.pl.PlMswiaProvider;
import dev.sieve.ingest.qa.QaNctcProvider;
import dev.sieve.ingest.tr.TrMasakProvider;
import dev.sieve.ingest.uk.UkHmtProvider;
import dev.sieve.ingest.un.UnConsolidatedProvider;
import dev.sieve.ingest.usfbi.FbiWantedProvider;
import dev.sieve.ingest.ustrade.BisEntityListProvider;
import dev.sieve.ingest.ustrade.BisMilitaryEndUserProvider;
import dev.sieve.ingest.ustrade.UsTradeCslProvider;
import dev.sieve.ingest.za.ZaFicProvider;
import dev.sieve.match.CompositeMatchEngine;
import dev.sieve.match.ExactMatchEngine;
import dev.sieve.match.FuzzyMatchEngine;
import dev.sieve.match.NgramIndex;
import dev.sieve.match.NormalizedNameCache;
import dev.sieve.match.PhoneticMatchEngine;
import dev.sieve.match.TokenMatchEngine;
import dev.sieve.server.handler.AddressScreeningHandler;
import dev.sieve.server.handler.HealthHandler;
import dev.sieve.server.handler.ListHandler;
import dev.sieve.server.handler.ScreeningHandler;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * High-performance Vert.x-based sanctions screening server.
 *
 * <p>Uses Netty event loops for I/O and direct method dispatch for request handling. No reflection,
 * no annotation processing, no component scanning — just raw speed.
 *
 * <p>Usage:
 *
 * <pre>
 *   java -jar sieve-server.jar                          # defaults: port 8080
 *   java -jar sieve-server.jar --port 9090              # custom port
 *   java -jar sieve-server.jar --threshold 0.85         # custom default threshold
 * </pre>
 */
public final class SieveServer {

    private static final Logger log = LoggerFactory.getLogger(SieveServer.class);

    private SieveServer() {}

    public static void main(String[] args) {
        ServerConfig config = ServerConfig.fromArgs(args);
        ObjectMapper objectMapper = createObjectMapper();

        // Build core components
        EntityIndex entityIndex = new InMemoryEntityIndex();
        MatchEngine matchEngine = createMatchEngine();
        List<ListProvider> providers = createProviders(config);
        IngestionOrchestrator orchestrator = new IngestionOrchestrator(providers);

        // Initial ingestion on a background thread
        Thread.ofVirtual()
                .name("startup-ingest")
                .start(
                        () -> {
                            log.info("Starting initial sanctions list ingestion...");
                            try {
                                IngestionReport report = orchestrator.ingest(entityIndex);
                                log.info(
                                        "Ingestion complete [entities={}, duration={}ms]",
                                        report.totalEntitiesLoaded(),
                                        report.totalDuration().toMillis());
                            } catch (Exception e) {
                                log.error("Startup ingestion failed", e);
                            }
                        });

        // Set up Vert.x with optimized options
        VertxOptions vertxOptions =
                new VertxOptions()
                        .setPreferNativeTransport(true)
                        .setEventLoopPoolSize(Runtime.getRuntime().availableProcessors());
        Vertx vertx = Vertx.vertx(vertxOptions);

        // Build router
        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create().setBodyLimit(1024 * (long) 1024));

        // Address normalizer (gracefully degrades if libpostal unavailable)
        AddressNormalizer addressNormalizer = new AddressNormalizer();
        addressNormalizer.init();
        AddressMatchService addressMatchService = new AddressMatchService(addressNormalizer);

        // Register handlers
        ScreeningAuditEmitter auditEmitter = ScreeningAuditEmitter.logging();
        ScreeningHandler screeningHandler =
                new ScreeningHandler(matchEngine, entityIndex, objectMapper, config, auditEmitter);
        AddressScreeningHandler addressScreeningHandler =
                new AddressScreeningHandler(addressMatchService, entityIndex, objectMapper, config);
        HealthHandler healthHandler = new HealthHandler(entityIndex, objectMapper);
        ListHandler listHandler = new ListHandler(entityIndex, orchestrator, objectMapper);

        router.post("/api/v1/screen").handler(screeningHandler::handle);
        router.post("/api/v1/screen/batch").handler(screeningHandler::handleBatch);
        router.post("/api/v1/screen/address").handler(addressScreeningHandler::handle);
        router.get("/api/v1/health").handler(healthHandler::handle);
        router.get("/api/v1/lists").handler(listHandler::handleGetLists);
        router.get("/api/v1/lists/:source/entities").handler(listHandler::handleGetEntities);
        router.post("/api/v1/lists/refresh").handler(ctx -> listHandler.handleRefresh(ctx, vertx));

        // Start server
        HttpServerOptions serverOptions =
                new HttpServerOptions()
                        .setTcpFastOpen(true)
                        .setTcpNoDelay(true)
                        .setTcpQuickAck(true)
                        .setReusePort(true)
                        .setIdleTimeout(60)
                        .setCompressionSupported(false);

        HttpServer server = vertx.createHttpServer(serverOptions);
        server.requestHandler(router)
                .listen(config.port())
                .onSuccess(
                        s ->
                                log.info(
                                        "Sieve server started on port {} [event-loops={}, native-transport={}]",
                                        s.actualPort(),
                                        vertxOptions.getEventLoopPoolSize(),
                                        vertx.isNativeTransportEnabled()))
                .onFailure(err -> log.error("Failed to start server", err));
    }

    static ObjectMapper createObjectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .setDefaultPropertyInclusion(
                        com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
    }

    private static MatchEngine createMatchEngine() {
        NormalizedNameCache nameCache = new NormalizedNameCache();
        NgramIndex ngramIndex = new NgramIndex();
        return new CompositeMatchEngine(
                List.of(
                        new ExactMatchEngine(nameCache, ngramIndex),
                        new FuzzyMatchEngine(nameCache, ngramIndex),
                        new PhoneticMatchEngine(nameCache, ngramIndex),
                        new TokenMatchEngine(nameCache, ngramIndex)));
    }

    private static List<ListProvider> createProviders(ServerConfig config) {
        List<ListProvider> providers = new ArrayList<>();

        if (config.ofacEnabled()) {
            URI uri =
                    URI.create(
                            "https://sanctionslistservice.ofac.treas.gov/api/PublicationPreview/exports/SDN.XML");
            providers.add(new OfacSdnProvider(uri));
            log.info("Registered OFAC SDN provider");
        }
        if (config.euEnabled()) {
            URI uri =
                    URI.create(
                            "https://webgate.ec.europa.eu/fsd/fsf/public/files/xmlFullSanctionsList_1_1/content?token=dG9rZW4tMjAxNw");
            providers.add(new EuConsolidatedProvider(uri));
            log.info("Registered EU Consolidated provider");
        }
        if (config.unEnabled()) {
            URI uri = URI.create("https://scsanctions.un.org/resources/xml/en/consolidated.xml");
            providers.add(new UnConsolidatedProvider(uri));
            log.info("Registered UN Consolidated provider");
        }
        if (config.ukEnabled()) {
            URI uri =
                    URI.create(
                            "https://ofsistorage.blob.core.windows.net/publishlive/2022format/ConList.xml");
            providers.add(new UkHmtProvider(uri));
            log.info("Registered UK HMT provider");
        }

        // Always-on providers (no feature flag needed)
        providers.add(new OfacNonSdnProvider());
        providers.add(new UsTradeCslProvider());
        providers.add(new BisEntityListProvider());
        providers.add(new BisMilitaryEndUserProvider());
        providers.add(new EuSanctionsMapProvider());
        providers.add(new EuTravelBansProvider());
        providers.add(new EuJournalProvider());
        providers.add(new CanadaConsolidatedProvider());
        providers.add(new ChSecoProvider());
        providers.add(new AuDfatProvider());
        providers.add(new FrTresorProvider());
        providers.add(new BeFodProvider());
        providers.add(new NzRussiaProvider());
        providers.add(new JpMofProvider());
        providers.add(new TrMasakProvider());
        providers.add(new PlMswiaProvider());
        providers.add(new IlWmdTerrorProvider());
        providers.add(new MdTerrorProvider());
        providers.add(new McFundFreezingProvider());
        providers.add(new QaNctcProvider());
        providers.add(new ZaFicProvider());
        providers.add(new LvFiuProvider());
        providers.add(new ArRepetProvider());
        providers.add(new InMhaProvider());
        providers.add(new FbiWantedProvider());
        log.info("Registered {} providers total", providers.size());

        return providers;
    }
}
