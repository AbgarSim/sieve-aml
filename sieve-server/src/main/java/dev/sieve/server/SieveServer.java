package dev.sieve.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.sieve.core.index.EntityIndex;
import dev.sieve.core.index.InMemoryEntityIndex;
import dev.sieve.core.match.MatchEngine;
import dev.sieve.ingest.IngestionOrchestrator;
import dev.sieve.ingest.IngestionReport;
import dev.sieve.ingest.ListProvider;
import dev.sieve.ingest.eu.EuConsolidatedProvider;
import dev.sieve.ingest.ofac.OfacSdnProvider;
import dev.sieve.ingest.uk.UkHmtProvider;
import dev.sieve.ingest.un.UnConsolidatedProvider;
import dev.sieve.match.CompositeMatchEngine;
import dev.sieve.match.ExactMatchEngine;
import dev.sieve.match.FuzzyMatchEngine;
import dev.sieve.match.NgramIndex;
import dev.sieve.match.NormalizedNameCache;
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
        router.route().handler(BodyHandler.create().setBodyLimit(64 * 1024));

        // Register handlers
        ScreeningHandler screeningHandler =
                new ScreeningHandler(matchEngine, entityIndex, objectMapper, config);
        HealthHandler healthHandler = new HealthHandler(entityIndex, objectMapper);
        ListHandler listHandler = new ListHandler(entityIndex, orchestrator, objectMapper);

        router.post("/api/v1/screen").handler(screeningHandler::handle);
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
                        new FuzzyMatchEngine(nameCache, ngramIndex)));
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

        if (providers.isEmpty()) {
            log.warn("No providers enabled — using EU Consolidated as default");
            providers.add(new EuConsolidatedProvider());
        }
        return providers;
    }
}
