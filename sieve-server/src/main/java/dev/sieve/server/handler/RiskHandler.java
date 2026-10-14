package dev.sieve.server.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sieve.core.index.EntityIndex;
import dev.sieve.core.match.MatchEngine;
import dev.sieve.risk.BehaviouralRiskScorer;
import dev.sieve.risk.Direction;
import dev.sieve.risk.MatchEngineScreener;
import dev.sieve.risk.RiskAssessment;
import dev.sieve.risk.RiskConfig;
import dev.sieve.risk.RiskSignal;
import dev.sieve.risk.Transaction;
import dev.sieve.server.ServerConfig;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles POST /api/v1/risk/assess: scores the behaviour of each subject in a batch of
 * transactions, screening every counterparty against the loaded lists.
 */
public final class RiskHandler {

    private static final Logger log = LoggerFactory.getLogger(RiskHandler.class);
    private static final String CONTENT_TYPE_JSON = "application/json";

    private final MatchEngine matchEngine;
    private final EntityIndex entityIndex;
    private final ObjectMapper objectMapper;
    private final ServerConfig config;

    public RiskHandler(
            MatchEngine matchEngine,
            EntityIndex entityIndex,
            ObjectMapper objectMapper,
            ServerConfig config) {
        this.matchEngine = matchEngine;
        this.entityIndex = entityIndex;
        this.objectMapper = objectMapper;
        this.config = config;
    }

    /** Assesses the request's transactions on a worker thread, off the event loop. */
    public void handle(RoutingContext ctx) {
        byte[] requestBody = ctx.body().buffer().getBytes();
        ctx.vertx()
                .executeBlocking(() -> assess(requestBody), false)
                .onSuccess(json -> writeJson(ctx, 200, json))
                .onFailure(
                        e -> {
                            if (e instanceof IllegalArgumentException
                                    || e instanceof ClassCastException
                                    || e instanceof NullPointerException
                                    || e instanceof IOException) {
                                writeError(ctx, 400, String.valueOf(e.getMessage()));
                            } else {
                                log.error("Risk assessment failed", e);
                                writeError(ctx, 500, String.valueOf(e.getMessage()));
                            }
                        });
    }

    private byte[] assess(byte[] requestBody) throws IOException {
        Map<String, Object> body = objectMapper.readValue(requestBody, Map.class);
        Object raw = body.get("transactions");
        if (!(raw instanceof List<?> rows) || rows.isEmpty()) {
            throw new IllegalArgumentException("transactions is required");
        }
        double threshold = config.defaultThreshold();
        if (body.get("threshold") instanceof Number number) {
            threshold = number.doubleValue();
        }
        List<Transaction> transactions = new ArrayList<>(rows.size());
        for (Object row : rows) {
            transactions.add(toTransaction((Map<?, ?>) row));
        }
        BehaviouralRiskScorer scorer =
                BehaviouralRiskScorer.withDefaultRules(
                        RiskConfig.defaults(),
                        new MatchEngineScreener(matchEngine, entityIndex, threshold));
        List<Map<String, Object>> assessments = new ArrayList<>();
        for (RiskAssessment assessment : scorer.assessAll(transactions)) {
            assessments.add(toMap(assessment));
        }
        Map<String, Object> response = new LinkedHashMap<>(3);
        response.put("subjects", assessments.size());
        response.put("assessments", assessments);
        response.put("assessedAt", Instant.now().toString());
        return objectMapper.writeValueAsBytes(response);
    }

    private static Transaction toTransaction(Map<?, ?> row) {
        String country = (String) row.get("counterpartyCountry");
        Object amount = row.get("amount");
        return new Transaction(
                String.valueOf(row.get("id")),
                (String) row.get("subjectId"),
                (String) row.get("counterparty"),
                country == null || country.isBlank() ? Optional.empty() : Optional.of(country),
                new BigDecimal(String.valueOf(amount)),
                (String) row.get("currency"),
                Instant.parse((String) row.get("timestamp")),
                Direction.fromString((String) row.get("direction")));
    }

    private static Map<String, Object> toMap(RiskAssessment assessment) {
        List<Map<String, Object>> signals = new ArrayList<>();
        for (RiskSignal signal : assessment.signals()) {
            Map<String, Object> map = new LinkedHashMap<>(3);
            map.put("rule", signal.rule());
            map.put("points", signal.points());
            map.put("detail", signal.detail());
            signals.add(map);
        }
        Map<String, Object> map = new LinkedHashMap<>(5);
        map.put("subjectId", assessment.subjectId());
        map.put("score", assessment.score());
        map.put("level", assessment.level().name());
        map.put("transactionCount", assessment.transactionCount());
        map.put("signals", signals);
        return map;
    }

    private static void writeJson(RoutingContext ctx, int statusCode, byte[] json) {
        ctx.response()
                .setStatusCode(statusCode)
                .putHeader("content-type", CONTENT_TYPE_JSON)
                .putHeader("content-length", String.valueOf(json.length))
                .end(Buffer.buffer(json));
    }

    private static void writeError(RoutingContext ctx, int statusCode, String message) {
        ctx.response()
                .setStatusCode(statusCode)
                .putHeader("content-type", CONTENT_TYPE_JSON)
                .end("{\"error\":\"" + message.replace("\"", "'") + "\"}");
    }
}
