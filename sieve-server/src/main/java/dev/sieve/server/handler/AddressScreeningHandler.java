package dev.sieve.server.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sieve.address.AddressMatchService;
import dev.sieve.address.AddressMatchService.AddressMatchResult;
import dev.sieve.core.index.EntityIndex;
import dev.sieve.core.model.Address;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.SanctionsProgram;
import dev.sieve.server.ServerConfig;
import io.vertx.ext.web.RoutingContext;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles POST /api/v1/screen/address — screens a free-text address against entity addresses.
 *
 * <p>Accepts NLP/free-form address input (e.g., "123 Main Street, London, United Kingdom"), parses
 * it using libpostal, and matches against all entity addresses in the index.
 */
public final class AddressScreeningHandler {

    private static final Logger log = LoggerFactory.getLogger(AddressScreeningHandler.class);
    private static final String CONTENT_TYPE_JSON = "application/json";

    private final AddressMatchService addressMatchService;
    private final EntityIndex entityIndex;
    private final ObjectMapper objectMapper;
    private final ServerConfig config;

    public AddressScreeningHandler(
            AddressMatchService addressMatchService,
            EntityIndex entityIndex,
            ObjectMapper objectMapper,
            ServerConfig config) {
        this.addressMatchService = addressMatchService;
        this.entityIndex = entityIndex;
        this.objectMapper = objectMapper;
        this.config = config;
    }

    public void handle(RoutingContext ctx) {
        try {
            Map<String, Object> body =
                    objectMapper.readValue(ctx.body().buffer().getBytes(), Map.class);

            String address = (String) body.get("address");
            if (address == null || address.isBlank()) {
                ctx.response()
                        .setStatusCode(400)
                        .putHeader("content-type", CONTENT_TYPE_JSON)
                        .end("{\"error\":\"address is required\"}");
                return;
            }

            double threshold = config.defaultThreshold();
            if (body.containsKey("threshold")) {
                threshold = ((Number) body.get("threshold")).doubleValue();
            }

            List<AddressMatchResult> results =
                    addressMatchService.screen(
                            address, entityIndex, threshold, config.maxResults());

            List<Map<String, Object>> resultMaps = new java.util.ArrayList<>(results.size());
            for (AddressMatchResult result : results) {
                resultMaps.add(toResultMap(result));
            }

            Map<String, Object> response = new HashMap<>(4);
            response.put("query", address);
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
            log.error("Address screening request failed", e);
            ctx.response()
                    .setStatusCode(500)
                    .putHeader("content-type", CONTENT_TYPE_JSON)
                    .end("{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}");
        }
    }

    private Map<String, Object> toResultMap(AddressMatchResult result) {
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

        Map<String, Object> map = new HashMap<>(4);
        map.put("entity", entityMap);
        map.put("score", result.score());
        map.put("matchedAddress", toAddressMap(result.matchedAddress()));
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
}
