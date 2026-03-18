package dev.sieve.server.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sieve.core.index.EntityIndex;
import dev.sieve.core.match.MatchEngine;
import dev.sieve.core.match.MatchResult;
import dev.sieve.core.match.ScreeningRequest;
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

    public ScreeningHandler(
            MatchEngine matchEngine,
            EntityIndex entityIndex,
            ObjectMapper objectMapper,
            ServerConfig config) {
        this.matchEngine = matchEngine;
        this.entityIndex = entityIndex;
        this.objectMapper = objectMapper;
        this.config = config;
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
            List<MatchResult> results = matchEngine.screen(request, entityIndex);

            // Build response directly — no intermediate DTO allocation
            int limit = Math.min(results.size(), config.maxResults());
            List<Map<String, Object>> resultMaps = new java.util.ArrayList<>(limit);
            for (int i = 0; i < limit; i++) {
                resultMaps.add(toMatchMap(results.get(i)));
            }

            Map<String, Object> response = new HashMap<>(4);
            response.put("query", name);
            response.put("totalMatches", resultMaps.size());
            response.put("screenedAt", Instant.now());
            response.put("results", resultMaps);

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

    private Map<String, Object> toMatchMap(MatchResult result) {
        SanctionedEntity entity = result.entity();
        Map<String, Object> entityMap = new HashMap<>(8);
        entityMap.put("id", entity.id());
        entityMap.put("entityType", entity.entityType().name());
        entityMap.put("listSource", entity.listSource().name());
        entityMap.put("primaryName", entity.primaryName().fullName());
        entityMap.put("aliases", entity.aliases().stream().map(NameInfo::fullName).toList());
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
}
