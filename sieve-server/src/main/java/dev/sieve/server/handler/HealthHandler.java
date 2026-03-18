package dev.sieve.server.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sieve.core.index.EntityIndex;
import dev.sieve.core.index.IndexStats;
import io.vertx.ext.web.RoutingContext;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Handles GET /api/v1/health — lightweight health check with index stats. */
public final class HealthHandler {

    private static final Logger log = LoggerFactory.getLogger(HealthHandler.class);
    private static final String CONTENT_TYPE_JSON = "application/json";

    private final EntityIndex entityIndex;
    private final ObjectMapper objectMapper;

    public HealthHandler(EntityIndex entityIndex, ObjectMapper objectMapper) {
        this.entityIndex = entityIndex;
        this.objectMapper = objectMapper;
    }

    public void handle(RoutingContext ctx) {
        try {
            IndexStats stats = entityIndex.stats();

            Map<String, Integer> bySource = new LinkedHashMap<>();
            stats.countBySource().forEach((k, v) -> bySource.put(k.name(), v));

            Map<String, Integer> byType = new LinkedHashMap<>();
            stats.countByType().forEach((k, v) -> byType.put(k.name(), v));

            Map<String, Object> indexMap = new HashMap<>(4);
            indexMap.put("totalEntities", stats.totalEntities());
            indexMap.put("countBySource", bySource);
            indexMap.put("countByType", byType);
            indexMap.put("lastUpdated", stats.lastUpdated());

            Map<String, Object> response = new HashMap<>(2);
            response.put("status", "UP");
            response.put("index", indexMap);

            byte[] json = objectMapper.writeValueAsBytes(response);
            ctx.response()
                    .setStatusCode(200)
                    .putHeader("content-type", CONTENT_TYPE_JSON)
                    .putHeader("content-length", String.valueOf(json.length))
                    .end(io.vertx.core.buffer.Buffer.buffer(json));
        } catch (Exception e) {
            log.error("Health check failed", e);
            ctx.response()
                    .setStatusCode(500)
                    .putHeader("content-type", CONTENT_TYPE_JSON)
                    .end("{\"error\":\"" + e.getMessage() + "\"}");
        }
    }
}
