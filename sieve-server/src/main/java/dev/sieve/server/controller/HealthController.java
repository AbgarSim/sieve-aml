package dev.sieve.server.controller;

import dev.sieve.core.index.EntityIndex;
import dev.sieve.core.index.IndexStats;
import dev.sieve.server.dto.HealthResponseDto;
import dev.sieve.server.dto.IndexStatsDto;
import dev.sieve.server.mapper.ScreeningMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST controller for health and readiness checks. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Health", description = "Application health and status")
public class HealthController {

    private final EntityIndex entityIndex;
    private final ScreeningMapper mapper;

    /**
     * Creates a new health controller.
     *
     * @param entityIndex the entity index
     * @param mapper the DTO mapper
     */
    public HealthController(EntityIndex entityIndex, ScreeningMapper mapper) {
        this.entityIndex = entityIndex;
        this.mapper = mapper;
    }

    /**
     * Returns the application health status and index statistics.
     *
     * @return the health response
     */
    @GetMapping("/health")
    @Operation(
            summary = "Health check",
            description = "Returns the application status and index statistics.")
    @ApiResponse(responseCode = "200", description = "Application is healthy")
    public ResponseEntity<HealthResponseDto> health() {
        IndexStats stats = entityIndex.stats();
        IndexStatsDto statsDto = mapper.toIndexStatsDto(stats);
        return ResponseEntity.ok(new HealthResponseDto("UP", statsDto));
    }
}
