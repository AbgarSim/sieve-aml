package dev.sieve.server.controller;

import dev.sieve.core.index.EntityIndex;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.ingest.IngestionOrchestrator;
import dev.sieve.ingest.IngestionReport;
import dev.sieve.ingest.ListMetadata;
import dev.sieve.server.dto.EntityPageDto;
import dev.sieve.server.dto.ListStatusDto;
import dev.sieve.server.dto.ListsResponseDto;
import dev.sieve.server.dto.RefreshResponseDto;
import dev.sieve.server.mapper.ScreeningMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for managing sanctions lists and triggering ingestion.
 */
@RestController
@RequestMapping("/api/v1/lists")
@Tag(name = "Lists", description = "Sanctions list management and ingestion")
public class ListController {

    private static final Logger log = LoggerFactory.getLogger(ListController.class);

    private final EntityIndex entityIndex;
    private final IngestionOrchestrator orchestrator;
    private final ScreeningMapper mapper;

    /**
     * Creates a new list controller.
     *
     * @param entityIndex the entity index
     * @param orchestrator the ingestion orchestrator
     * @param mapper the DTO mapper
     */
    public ListController(
            EntityIndex entityIndex,
            IngestionOrchestrator orchestrator,
            ScreeningMapper mapper) {
        this.entityIndex = entityIndex;
        this.orchestrator = orchestrator;
        this.mapper = mapper;
    }

    /**
     * Returns the status of all known sanctions list sources.
     *
     * @return the lists response
     */
    @GetMapping
    @Operation(
            summary = "Get status of all sanctions lists",
            description = "Returns the current status, entity count, and last fetch time for each"
                    + " known sanctions list source.")
    @ApiResponse(responseCode = "200", description = "List statuses retrieved")
    public ResponseEntity<ListsResponseDto> getLists() {
        List<ListStatusDto> statuses = new ArrayList<>();

        for (ListSource source : ListSource.values()) {
            ListMetadata metadata = orchestrator.getMetadata(source);
            Collection<SanctionedEntity> entities = entityIndex.findBySource(source);
            int count = entities.size();
            String status = count > 0 ? "LOADED" : "EMPTY";

            statuses.add(
                    new ListStatusDto(
                            source.name(),
                            count,
                            metadata != null ? metadata.lastFetched() : null,
                            status));
        }

        return ResponseEntity.ok(new ListsResponseDto(statuses));
    }

    /**
     * Returns a paginated list of entities from a specific sanctions list source.
     *
     * @param source the list source identifier
     * @param page zero-based page number
     * @param size page size
     * @return the paginated entity list
     */
    @GetMapping("/{source}/entities")
    @Operation(
            summary = "Get entities from a specific list",
            description = "Returns a paginated list of sanctioned entities from the specified list"
                    + " source.")
    @ApiResponse(responseCode = "200", description = "Entities retrieved")
    @ApiResponse(responseCode = "400", description = "Invalid source identifier")
    public ResponseEntity<EntityPageDto> getEntities(
            @PathVariable
                    @Parameter(description = "List source identifier", example = "OFAC_SDN")
                    String source,
            @RequestParam(defaultValue = "0")
                    @Parameter(description = "Page number (zero-based)")
                    int page,
            @RequestParam(defaultValue = "20") @Parameter(description = "Page size") int size) {
        ListSource listSource = ListSource.fromString(source);
        Collection<SanctionedEntity> entities = entityIndex.findBySource(listSource);
        EntityPageDto pageDto = mapper.toEntityPage(entities, page, size);
        return ResponseEntity.ok(pageDto);
    }

    /**
     * Triggers a re-ingestion of all enabled sanctions lists.
     *
     * @return the ingestion report
     */
    @PostMapping("/refresh")
    @Operation(
            summary = "Refresh sanctions lists",
            description = "Triggers an immediate re-ingestion of all enabled sanctions list"
                    + " sources.")
    @ApiResponse(responseCode = "200", description = "Refresh completed")
    public ResponseEntity<RefreshResponseDto> refresh() {
        log.info("Manual list refresh triggered");
        IngestionReport report = orchestrator.ingest(entityIndex);
        RefreshResponseDto response = mapper.toRefreshResponse(report);
        log.info("Manual list refresh complete [entities={}]", report.totalEntitiesLoaded());
        return ResponseEntity.ok(response);
    }
}
