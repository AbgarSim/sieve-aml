package dev.sieve.server.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sieve.core.index.EntityIndex;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.SanctionsProgram;
import dev.sieve.ingest.IngestionOrchestrator;
import dev.sieve.ingest.IngestionReport;
import dev.sieve.ingest.ListMetadata;
import io.vertx.core.Vertx;
import io.vertx.ext.web.RoutingContext;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Handles /api/v1/lists endpoints — list status, entity browsing, and refresh. */
public final class ListHandler {

    private static final Logger log = LoggerFactory.getLogger(ListHandler.class);
    private static final String CONTENT_TYPE_JSON = "application/json";

    private final EntityIndex entityIndex;
    private final IngestionOrchestrator orchestrator;
    private final ObjectMapper objectMapper;

    public ListHandler(
            EntityIndex entityIndex,
            IngestionOrchestrator orchestrator,
            ObjectMapper objectMapper) {
        this.entityIndex = entityIndex;
        this.orchestrator = orchestrator;
        this.objectMapper = objectMapper;
    }

    public void handleGetLists(RoutingContext ctx) {
        try {
            List<Map<String, Object>> statuses = new ArrayList<>();
            for (ListSource source : ListSource.values()) {
                ListMetadata metadata = orchestrator.getMetadata(source);
                Collection<SanctionedEntity> entities = entityIndex.findBySource(source);
                int count = entities.size();

                Map<String, Object> status = new HashMap<>(4);
                status.put("source", source.name());
                status.put("entityCount", count);
                status.put("lastFetched", metadata != null ? metadata.lastFetched() : null);
                status.put("status", count > 0 ? "LOADED" : "EMPTY");
                statuses.add(status);
            }

            Map<String, Object> response = Map.of("lists", statuses);
            writeJson(ctx, 200, response);
        } catch (Exception e) {
            log.error("Failed to get list statuses", e);
            writeError(ctx, 500, e.getMessage());
        }
    }

    public void handleGetEntities(RoutingContext ctx) {
        try {
            String sourceParam = ctx.pathParam("source");
            int page = intParam(ctx, "page", 0);
            int size = intParam(ctx, "size", 20);

            ListSource listSource = ListSource.fromString(sourceParam);
            Collection<SanctionedEntity> entities = entityIndex.findBySource(listSource);
            List<SanctionedEntity> entityList =
                    entities instanceof List<?>
                            ? (List<SanctionedEntity>) entities
                            : List.copyOf(entities);

            int totalElements = entityList.size();
            int totalPages = (int) Math.ceil((double) totalElements / size);
            int fromIndex = Math.min(page * size, totalElements);
            int toIndex = Math.min(fromIndex + size, totalElements);

            List<Map<String, Object>> pageDtos = new ArrayList<>(toIndex - fromIndex);
            for (int i = fromIndex; i < toIndex; i++) {
                pageDtos.add(toEntityMap(entityList.get(i)));
            }

            Map<String, Object> response = new HashMap<>(5);
            response.put("entities", pageDtos);
            response.put("page", page);
            response.put("size", size);
            response.put("totalElements", totalElements);
            response.put("totalPages", totalPages);

            writeJson(ctx, 200, response);
        } catch (IllegalArgumentException e) {
            writeError(ctx, 400, e.getMessage());
        } catch (Exception e) {
            log.error("Failed to get entities", e);
            writeError(ctx, 500, e.getMessage());
        }
    }

    public void handleRefresh(RoutingContext ctx, Vertx vertx) {
        // Run ingestion off the event loop
        vertx.executeBlocking(
                        () -> {
                            log.info("Manual list refresh triggered");
                            IngestionReport report = orchestrator.ingest(entityIndex);
                            log.info(
                                    "Manual list refresh complete [entities={}]",
                                    report.totalEntitiesLoaded());

                            Map<String, Object> results = new HashMap<>();
                            report.results()
                                    .forEach(
                                            (source, result) -> {
                                                Map<String, Object> r = new HashMap<>(4);
                                                r.put("status", result.status().name());
                                                r.put("entityCount", result.entityCount());
                                                r.put("durationMs", result.duration().toMillis());
                                                r.put("error", result.error().orElse(null));
                                                results.put(source.name(), r);
                                            });

                            Map<String, Object> response = new HashMap<>(3);
                            response.put("totalEntitiesLoaded", report.totalEntitiesLoaded());
                            response.put("totalDurationMs", report.totalDuration().toMillis());
                            response.put("results", results);
                            return response;
                        },
                        false)
                .onSuccess(response -> writeJson(ctx, 200, response))
                .onFailure(
                        err -> {
                            log.error("Refresh failed", err);
                            writeError(ctx, 500, err.getMessage());
                        });
    }

    private static Map<String, Object> toEntityMap(SanctionedEntity entity) {
        Map<String, Object> map = new HashMap<>(8);
        map.put("id", entity.id());
        map.put("entityType", entity.entityType().name());
        map.put("listSource", entity.listSource().name());
        map.put("primaryName", entity.primaryName().fullName());
        map.put("aliases", entity.aliases().stream().map(NameInfo::fullName).toList());
        map.put("nationalities", entity.nationalities());
        map.put("programs", entity.programs().stream().map(SanctionsProgram::code).toList());
        map.put("remarks", entity.remarks());
        map.put("lastUpdated", entity.lastUpdated());
        return map;
    }

    private void writeJson(RoutingContext ctx, int statusCode, Object body) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(body);
            ctx.response()
                    .setStatusCode(statusCode)
                    .putHeader("content-type", CONTENT_TYPE_JSON)
                    .putHeader("content-length", String.valueOf(json.length))
                    .end(io.vertx.core.buffer.Buffer.buffer(json));
        } catch (Exception e) {
            log.error("Failed to serialize response", e);
            ctx.response().setStatusCode(500).end();
        }
    }

    private static void writeError(RoutingContext ctx, int statusCode, String message) {
        ctx.response()
                .setStatusCode(statusCode)
                .putHeader("content-type", CONTENT_TYPE_JSON)
                .end(
                        "{\"error\":\""
                                + (message != null ? message.replace("\"", "'") : "unknown")
                                + "\"}");
    }

    private static int intParam(RoutingContext ctx, String name, int defaultValue) {
        String val = ctx.request().getParam(name);
        if (val == null) return defaultValue;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
