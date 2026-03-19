package dev.sieve.server.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sieve.core.audit.ScreeningAuditEmitter;
import dev.sieve.core.audit.ScreeningAuditEvent;
import dev.sieve.core.index.EntityIndex;
import dev.sieve.core.match.MatchEngine;
import dev.sieve.core.match.MatchResult;
import dev.sieve.core.match.ScreeningRequest;
import dev.sieve.core.model.Address;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.SanctionsProgram;
import dev.sieve.server.ServerConfig;
import io.vertx.ext.web.RoutingContext;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Handles POST /api/v1/screen — the hot path, optimized for minimum latency. */
public final class ScreeningHandler {

    private static final Logger log = LoggerFactory.getLogger(ScreeningHandler.class);
    private static final String CONTENT_TYPE_JSON = "application/json";

    private final MatchEngine matchEngine;
    private final EntityIndex entityIndex;
    private final ObjectMapper objectMapper;
    private final ServerConfig config;
    private final ScreeningAuditEmitter auditEmitter;

    public ScreeningHandler(
            MatchEngine matchEngine,
            EntityIndex entityIndex,
            ObjectMapper objectMapper,
            ServerConfig config,
            ScreeningAuditEmitter auditEmitter) {
        this.matchEngine = matchEngine;
        this.entityIndex = entityIndex;
        this.objectMapper = objectMapper;
        this.config = config;
        this.auditEmitter = auditEmitter;
    }

    public void handle(RoutingContext ctx) {
        try {
            Map<String, Object> body =
                    objectMapper.readValue(ctx.body().buffer().getBytes(), Map.class);

            String name = (String) body.get("name");
            if (name == null || name.isBlank()) {
                ctx.response()
                        .setStatusCode(400)
                        .putHeader("content-type", CONTENT_TYPE_JSON)
                        .end("{\"error\":\"name is required\"}");
                return;
            }

            double threshold = config.defaultThreshold();
            if (body.containsKey("threshold")) {
                threshold = ((Number) body.get("threshold")).doubleValue();
            }

            Optional<EntityType> entityType = Optional.empty();
            if (body.containsKey("entityType") && body.get("entityType") != null) {
                entityType = Optional.of(EntityType.fromString((String) body.get("entityType")));
            }

            Optional<Set<ListSource>> sources = Optional.empty();
            if (body.containsKey("sources") && body.get("sources") != null) {
                @SuppressWarnings("unchecked")
                List<String> sourceList = (List<String>) body.get("sources");
                sources =
                        Optional.of(
                                sourceList.stream()
                                        .map(ListSource::fromString)
                                        .collect(Collectors.toSet()));
            }

            ScreeningRequest request = new ScreeningRequest(name, entityType, sources, threshold);
            long startNanos = System.nanoTime();
            List<MatchResult> results = matchEngine.screen(request, entityIndex);
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;

            // Build response directly — no intermediate DTO allocation
            int limit = Math.min(results.size(), config.maxResults());
            List<Map<String, Object>> resultMaps = new java.util.ArrayList<>(limit);
            for (int i = 0; i < limit; i++) {
                resultMaps.add(toMatchMap(results.get(i)));
            }

            Instant now = Instant.now();
            Map<String, Object> response = new HashMap<>(4);
            response.put("query", name);
            response.put("totalMatches", resultMaps.size());
            response.put("screenedAt", now);
            response.put("results", resultMaps);

            emitAuditEvent(name, threshold, results, now, durationMs);

            byte[] json = objectMapper.writeValueAsBytes(response);
            ctx.response()
                    .setStatusCode(200)
                    .putHeader("content-type", CONTENT_TYPE_JSON)
                    .putHeader("content-length", String.valueOf(json.length))
                    .end(io.vertx.core.buffer.Buffer.buffer(json));

        } catch (Exception e) {
            log.error("Screening request failed", e);
            ctx.response()
                    .setStatusCode(500)
                    .putHeader("content-type", CONTENT_TYPE_JSON)
                    .end("{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}");
        }
    }

    public void handleBatch(RoutingContext ctx) {
        try {
            Map<String, Object> body =
                    objectMapper.readValue(ctx.body().buffer().getBytes(), Map.class);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("requests");
            if (items == null || items.isEmpty()) {
                ctx.response()
                        .setStatusCode(400)
                        .putHeader("content-type", CONTENT_TYPE_JSON)
                        .end("{\"error\":\"requests array is required and must not be empty\"}");
                return;
            }

            int maxBatchSize = config.maxBatchSize();
            if (items.size() > maxBatchSize) {
                ctx.response()
                        .setStatusCode(400)
                        .putHeader("content-type", CONTENT_TYPE_JSON)
                        .end("{\"error\":\"batch size exceeds maximum of " + maxBatchSize + "\"}");
                return;
            }

            List<Map<String, Object>> batchResults = new java.util.ArrayList<>(items.size());
            for (Map<String, Object> item : items) {
                batchResults.add(screenSingle(item));
            }

            Map<String, Object> response = new HashMap<>(3);
            response.put("totalRequests", items.size());
            response.put("screenedAt", Instant.now());
            response.put("results", batchResults);

            byte[] json = objectMapper.writeValueAsBytes(response);
            ctx.response()
                    .setStatusCode(200)
                    .putHeader("content-type", CONTENT_TYPE_JSON)
                    .putHeader("content-length", String.valueOf(json.length))
                    .end(io.vertx.core.buffer.Buffer.buffer(json));

        } catch (Exception e) {
            log.error("Batch screening request failed", e);
            ctx.response()
                    .setStatusCode(500)
                    .putHeader("content-type", CONTENT_TYPE_JSON)
                    .end("{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> screenSingle(Map<String, Object> item) {
        String name = (String) item.get("name");
        if (name == null || name.isBlank()) {
            return Map.of("query", "", "error", "name is required");
        }

        double threshold = config.defaultThreshold();
        if (item.containsKey("threshold")) {
            threshold = ((Number) item.get("threshold")).doubleValue();
        }

        Optional<EntityType> entityType = Optional.empty();
        if (item.containsKey("entityType") && item.get("entityType") != null) {
            entityType = Optional.of(EntityType.fromString((String) item.get("entityType")));
        }

        Optional<Set<ListSource>> sources = Optional.empty();
        if (item.containsKey("sources") && item.get("sources") != null) {
            List<String> sourceList = (List<String>) item.get("sources");
            sources =
                    Optional.of(
                            sourceList.stream()
                                    .map(ListSource::fromString)
                                    .collect(Collectors.toSet()));
        }

        ScreeningRequest request = new ScreeningRequest(name, entityType, sources, threshold);
        List<MatchResult> results = matchEngine.screen(request, entityIndex);

        int limit = Math.min(results.size(), config.maxResults());
        List<Map<String, Object>> resultMaps = new java.util.ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            resultMaps.add(toMatchMap(results.get(i)));
        }

        Map<String, Object> singleResponse = new HashMap<>(3);
        singleResponse.put("query", name);
        singleResponse.put("totalMatches", resultMaps.size());
        singleResponse.put("results", resultMaps);
        return singleResponse;
    }

    private Map<String, Object> toMatchMap(MatchResult result) {
        SanctionedEntity entity = result.entity();
        Map<String, Object> entityMap = new HashMap<>(8);
        entityMap.put("id", entity.id());
        entityMap.put("entityType", entity.entityType().name());
        entityMap.put("listSource", entity.listSource().name());
        entityMap.put("primaryName", entity.primaryName().fullName());
        entityMap.put("aliases", entity.aliases().stream().map(NameInfo::fullName).toList());
        entityMap.put("addresses", entity.addresses().stream().map(this::toAddressMap).toList());
        entityMap.put("nationalities", entity.nationalities());
        entityMap.put("programs", entity.programs().stream().map(SanctionsProgram::code).toList());
        entityMap.put("remarks", entity.remarks());

        Map<String, Object> map = new HashMap<>(4);
        map.put("entity", entityMap);
        map.put("score", result.score());
        map.put("matchedField", result.matchedField());
        map.put("matchAlgorithm", result.matchAlgorithm());
        return map;
    }

    private Map<String, Object> toAddressMap(Address address) {
        Map<String, Object> map = new HashMap<>(6);
        map.put("street", address.street());
        map.put("city", address.city());
        map.put("stateOrProvince", address.stateOrProvince());
        map.put("postalCode", address.postalCode());
        map.put("country", address.country());
        map.put("fullAddress", address.fullAddress());
        return map;
    }

    private void emitAuditEvent(
            String name,
            double threshold,
            List<MatchResult> results,
            Instant screenedAt,
            long durationMs) {
        try {
            int auditLimit = Math.min(results.size(), config.maxResults());
            List<ScreeningAuditEvent.AuditMatchEntry> entries =
                    results.stream()
                            .limit(auditLimit)
                            .map(ScreeningAuditEvent.AuditMatchEntry::from)
                            .toList();

            List<String> algorithms =
                    results.stream().map(MatchResult::matchAlgorithm).distinct().toList();

            ScreeningAuditEvent.Outcome outcome =
                    results.isEmpty()
                            ? ScreeningAuditEvent.Outcome.NO_MATCH
                            : ScreeningAuditEvent.Outcome.MATCH;

            auditEmitter.emit(
                    new ScreeningAuditEvent(
                            UUID.randomUUID().toString(),
                            name,
                            threshold,
                            results.size(),
                            entries,
                            outcome,
                            screenedAt,
                            durationMs,
                            algorithms,
                            null));
        } catch (Exception e) {
            log.warn("Failed to emit audit event for query [name={}]", name, e);
        }
    }
}
