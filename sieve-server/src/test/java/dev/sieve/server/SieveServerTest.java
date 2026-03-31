package dev.sieve.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sieve.core.index.EntityIndex;
import dev.sieve.core.index.InMemoryEntityIndex;
import dev.sieve.core.match.MatchEngine;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.NameStrength;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.ScriptType;
import dev.sieve.match.CompositeMatchEngine;
import dev.sieve.match.ExactMatchEngine;
import dev.sieve.match.FuzzyMatchEngine;
import dev.sieve.match.NgramIndex;
import dev.sieve.match.NormalizedNameCache;
import dev.sieve.server.handler.HealthHandler;
import dev.sieve.server.handler.ScreeningHandler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(VertxExtension.class)
class SieveServerTest {

    private static final int TEST_PORT = 0; // OS-assigned port
    private ObjectMapper objectMapper;
    private EntityIndex entityIndex;
    private int actualPort;

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext testContext) {
        objectMapper = SieveServer.createObjectMapper();
        entityIndex = new InMemoryEntityIndex();

        // Seed the index with a test entity
        entityIndex.add(createEntity("TEST-001", "PUTIN, Vladimir Vladimirovich"));

        NormalizedNameCache nameCache = new NormalizedNameCache();
        NgramIndex ngramIndex = new NgramIndex();
        MatchEngine matchEngine =
                new CompositeMatchEngine(
                        List.of(
                                new ExactMatchEngine(nameCache, ngramIndex),
                                new FuzzyMatchEngine(nameCache, ngramIndex)));

        ServerConfig config = new ServerConfig(0, 0.80, 50, 1000, false, false, false, false);

        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create().setBodyLimit(64 * 1024));

        ScreeningHandler screeningHandler =
                new ScreeningHandler(
                        matchEngine,
                        entityIndex,
                        objectMapper,
                        config,
                        dev.sieve.core.audit.ScreeningAuditEmitter.noop());
        HealthHandler healthHandler = new HealthHandler(entityIndex, objectMapper);

        router.post("/api/v1/screen").handler(screeningHandler::handle);
        router.get("/api/v1/health").handler(healthHandler::handle);

        vertx.createHttpServer()
                .requestHandler(router)
                .listen(TEST_PORT)
                .onSuccess(
                        server -> {
                            actualPort = server.actualPort();
                            testContext.completeNow();
                        })
                .onFailure(testContext::failNow);
    }

    @Test
    void healthEndpointReturnsUp(Vertx vertx, VertxTestContext testContext) throws Exception {
        HttpClient client = vertx.createHttpClient();
        client.request(HttpMethod.GET, actualPort, "localhost", "/api/v1/health")
                .compose(req -> req.send())
                .compose(HttpClientResponse::body)
                .onSuccess(
                        body -> {
                            testContext.verify(
                                    () -> {
                                        Map<String, Object> json =
                                                objectMapper.readValue(body.getBytes(), Map.class);
                                        assertThat(json.get("status")).isEqualTo("UP");
                                        assertThat(json).containsKey("index");
                                        testContext.completeNow();
                                    });
                        })
                .onFailure(testContext::failNow);

        assertThat(testContext.awaitCompletion(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void screenEndpointReturnsMatches(Vertx vertx, VertxTestContext testContext) throws Exception {
        HttpClient client = vertx.createHttpClient();
        String requestBody = "{\"name\":\"Vladimir Putin\",\"threshold\":0.50}";

        client.request(HttpMethod.POST, actualPort, "localhost", "/api/v1/screen")
                .compose(
                        req ->
                                req.putHeader("content-type", "application/json")
                                        .send(Buffer.buffer(requestBody)))
                .compose(HttpClientResponse::body)
                .onSuccess(
                        body -> {
                            testContext.verify(
                                    () -> {
                                        Map<String, Object> json =
                                                objectMapper.readValue(body.getBytes(), Map.class);
                                        assertThat(json.get("query")).isEqualTo("Vladimir Putin");
                                        assertThat((Integer) json.get("totalMatches"))
                                                .isGreaterThan(0);
                                        assertThat(json).containsKey("results");
                                        assertThat(json).containsKey("screenedAt");
                                        testContext.completeNow();
                                    });
                        })
                .onFailure(testContext::failNow);

        assertThat(testContext.awaitCompletion(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void screenEndpointReturns400WhenNameMissing(Vertx vertx, VertxTestContext testContext)
            throws Exception {
        HttpClient client = vertx.createHttpClient();
        String requestBody = "{\"threshold\":0.80}";

        client.request(HttpMethod.POST, actualPort, "localhost", "/api/v1/screen")
                .compose(
                        req ->
                                req.putHeader("content-type", "application/json")
                                        .send(Buffer.buffer(requestBody)))
                .onSuccess(
                        resp -> {
                            testContext.verify(
                                    () -> {
                                        assertThat(resp.statusCode()).isEqualTo(400);
                                        testContext.completeNow();
                                    });
                        })
                .onFailure(testContext::failNow);

        assertThat(testContext.awaitCompletion(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void screenEndpointReturnsEmptyForNoMatch(Vertx vertx, VertxTestContext testContext)
            throws Exception {
        HttpClient client = vertx.createHttpClient();
        String requestBody = "{\"name\":\"ZZZZZZZZZ XXXXXXXXX\",\"threshold\":0.95}";

        client.request(HttpMethod.POST, actualPort, "localhost", "/api/v1/screen")
                .compose(
                        req ->
                                req.putHeader("content-type", "application/json")
                                        .send(Buffer.buffer(requestBody)))
                .compose(HttpClientResponse::body)
                .onSuccess(
                        body -> {
                            testContext.verify(
                                    () -> {
                                        Map<String, Object> json =
                                                objectMapper.readValue(body.getBytes(), Map.class);
                                        assertThat((Integer) json.get("totalMatches")).isZero();
                                        testContext.completeNow();
                                    });
                        })
                .onFailure(testContext::failNow);

        assertThat(testContext.awaitCompletion(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void serverConfigParsesArgs() {
        ServerConfig config =
                ServerConfig.fromArgs(
                        new String[] {"--port", "9090", "--threshold", "0.90", "--eu", "true"});
        assertThat(config.port()).isEqualTo(9090);
        assertThat(config.defaultThreshold()).isEqualTo(0.90);
        assertThat(config.euEnabled()).isTrue();
        assertThat(config.ofacEnabled()).isTrue(); // default
    }

    @Test
    void serverConfigDefaultValues() {
        ServerConfig config = ServerConfig.fromArgs(new String[] {});
        assertThat(config.port()).isEqualTo(8080);
        assertThat(config.defaultThreshold()).isEqualTo(0.80);
        assertThat(config.maxResults()).isEqualTo(50);
    }

    private static SanctionedEntity createEntity(String id, String name) {
        NameInfo primaryName =
                new NameInfo(
                        name,
                        null,
                        null,
                        null,
                        null,
                        NameType.PRIMARY,
                        NameStrength.STRONG,
                        ScriptType.LATIN);
        return new SanctionedEntity(
                id,
                EntityType.INDIVIDUAL,
                ListSource.OFAC_SDN,
                primaryName,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Instant.now());
    }
}
