package dev.sieve.server.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sieve.ingest.pep.PublicFunction;
import dev.sieve.ingest.pep.PublicFunctionCatalog;
import dev.sieve.ingest.pep.PublicFunctionCategory;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles /api/v1/pep/functions: the EU list of prominent public functions, which says which
 * positions make their holder a politically exposed person, by jurisdiction.
 */
public final class PublicFunctionHandler {

    private static final Logger log = LoggerFactory.getLogger(PublicFunctionHandler.class);
    private static final String CONTENT_TYPE_JSON = "application/json";

    private final PublicFunctionCatalog catalog;
    private final ObjectMapper objectMapper;

    public PublicFunctionHandler(PublicFunctionCatalog catalog, ObjectMapper objectMapper) {
        this.catalog = catalog;
        this.objectMapper = objectMapper;
    }

    /** The Official Journal document the catalogue comes from and each jurisdiction's count. */
    public void handleGetSummary(RoutingContext ctx) {
        List<Map<String, Object>> jurisdictions = new ArrayList<>();
        for (String jurisdiction : catalog.jurisdictions()) {
            Map<String, Object> count = new LinkedHashMap<>(2);
            count.put("jurisdiction", jurisdiction);
            count.put("count", catalog.functions(jurisdiction).size());
            jurisdictions.add(count);
        }
        Map<String, Object> response = new LinkedHashMap<>(6);
        response.put("title", catalog.title());
        response.put("reference", catalog.reference());
        response.put(
                "published", catalog.published() == null ? null : catalog.published().toString());
        response.put("eli", catalog.eli());
        response.put("total", catalog.size());
        response.put("jurisdictions", jurisdictions);
        writeJson(ctx, 200, response);
    }

    /** One jurisdiction's functions, optionally only those of one directive category. */
    public void handleGetJurisdiction(RoutingContext ctx) {
        String code = ctx.pathParam("jurisdiction").toUpperCase(Locale.ROOT);
        if (!catalog.lists(code)) {
            writeError(ctx, 404, "No list of prominent public functions for " + code);
            return;
        }
        String category = ctx.request().getParam("category");
        PublicFunctionCategory filter = null;
        if (category != null && !category.isBlank()) {
            Optional<PublicFunctionCategory> wanted = PublicFunctionCategory.fromPoint(category);
            if (wanted.isEmpty()) {
                writeError(
                        ctx, 400, "Category must be a point of Article 3(9), a to h: " + category);
                return;
            }
            filter = wanted.get();
        }
        List<Map<String, Object>> functions = new ArrayList<>();
        for (PublicFunction function : catalog.functions(code)) {
            if (filter == null || function.category() == filter) {
                functions.add(toMap(function));
            }
        }
        Map<String, Object> response = new LinkedHashMap<>(4);
        response.put("jurisdiction", code);
        response.put("reference", catalog.reference());
        response.put("count", functions.size());
        response.put("functions", functions);
        writeJson(ctx, 200, response);
    }

    private static Map<String, Object> toMap(PublicFunction function) {
        Map<String, Object> map = new LinkedHashMap<>(4);
        if (function.category() != null) {
            map.put("category", String.valueOf(function.category().point()));
        }
        if (function.heading() != null) {
            map.put("heading", function.heading());
        }
        map.put("function", function.function());
        if (function.organisation() != null) {
            map.put("organisation", function.organisation());
        }
        return map;
    }

    private void writeJson(RoutingContext ctx, int statusCode, Object body) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(body);
            ctx.response()
                    .setStatusCode(statusCode)
                    .putHeader("content-type", CONTENT_TYPE_JSON)
                    .putHeader("content-length", String.valueOf(json.length))
                    .end(Buffer.buffer(json));
        } catch (Exception e) {
            log.error("Failed to serialize response", e);
            ctx.response().setStatusCode(500).end();
        }
    }

    private static void writeError(RoutingContext ctx, int statusCode, String message) {
        ctx.response()
                .setStatusCode(statusCode)
                .putHeader("content-type", CONTENT_TYPE_JSON)
                .end("{\"error\":\"" + message.replace("\"", "'") + "\"}");
    }
}
