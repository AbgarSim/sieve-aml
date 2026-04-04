package dev.sieve.server.controller;

import dev.sieve.core.index.EntityIndex;
import dev.sieve.core.match.MatchEngine;
import dev.sieve.core.match.MatchResult;
import dev.sieve.core.match.ScreeningRequest;
import dev.sieve.server.config.SieveProperties;
import dev.sieve.server.dto.BatchScreeningRequestDto;
import dev.sieve.server.dto.BatchScreeningResponseDto;
import dev.sieve.server.dto.ScreeningRequestDto;
import dev.sieve.server.dto.ScreeningResponseDto;
import dev.sieve.server.mapper.ScreeningMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST controller for sanctions name screening. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Screening", description = "Name screening against sanctions lists")
public class ScreeningController {

    private static final Logger log = LoggerFactory.getLogger(ScreeningController.class);

    private final MatchEngine matchEngine;
    private final EntityIndex entityIndex;
    private final ScreeningMapper mapper;
    private final SieveProperties properties;

    /**
     * Creates a new screening controller.
     *
     * @param matchEngine the match engine to use for screening
     * @param entityIndex the entity index to screen against
     * @param mapper the DTO mapper
     * @param properties the Sieve configuration properties
     */
    public ScreeningController(
            MatchEngine matchEngine,
            EntityIndex entityIndex,
            ScreeningMapper mapper,
            SieveProperties properties) {
        this.matchEngine = matchEngine;
        this.entityIndex = entityIndex;
        this.mapper = mapper;
        this.properties = properties;
    }

    /**
     * Screens a name against the loaded sanctions lists.
     *
     * @param requestDto the screening request
     * @return the screening response with match results
     */
    @PostMapping("/screen")
    @Operation(
            summary = "Screen a name against sanctions lists",
            description =
                    "Screens the given name against all loaded sanctions lists using configured"
                            + " match engines. Returns matching entities sorted by score.")
    @ApiResponse(responseCode = "200", description = "Screening completed successfully")
    @ApiResponse(responseCode = "400", description = "Invalid request parameters")
    public ResponseEntity<ScreeningResponseDto> screen(
            @Valid @RequestBody ScreeningRequestDto requestDto) {
        log.debug(
                "Screening request [name={}, threshold={}]",
                requestDto.name(),
                requestDto.threshold());

        ScreeningRequest domainRequest =
                mapper.toDomain(requestDto, properties.screening().defaultThreshold());
        List<MatchResult> results = matchEngine.screen(domainRequest, entityIndex);

        ScreeningResponseDto response =
                mapper.toScreeningResponse(
                        requestDto.name(), results, properties.screening().maxResults());

        log.debug(
                "Screening complete [name={}, matches={}]",
                requestDto.name(),
                response.totalMatches());

        return ResponseEntity.ok(response);
    }

    /**
     * Screens multiple names against the loaded sanctions lists in a single request.
     *
     * @param batchDto the batch screening request containing multiple names
     * @return the batch screening response with results for each name
     */
    @PostMapping("/screen/batch")
    @Operation(
            summary = "Batch screen names against sanctions lists",
            description =
                    "Screens multiple names against all loaded sanctions lists in a single request."
                            + " Returns individual results for each name.")
    @ApiResponse(responseCode = "200", description = "Batch screening completed successfully")
    @ApiResponse(responseCode = "400", description = "Invalid request parameters")
    public ResponseEntity<BatchScreeningResponseDto> screenBatch(
            @Valid @RequestBody BatchScreeningRequestDto batchDto) {
        log.debug("Batch screening request [size={}]", batchDto.requests().size());

        List<ScreeningResponseDto> responses = new ArrayList<>(batchDto.requests().size());
        for (ScreeningRequestDto requestDto : batchDto.requests()) {
            ScreeningRequest domainRequest =
                    mapper.toDomain(requestDto, properties.screening().defaultThreshold());
            List<MatchResult> results = matchEngine.screen(domainRequest, entityIndex);
            responses.add(
                    mapper.toScreeningResponse(
                            requestDto.name(), results, properties.screening().maxResults()));
        }

        BatchScreeningResponseDto response =
                new BatchScreeningResponseDto(batchDto.requests().size(), Instant.now(), responses);

        log.debug(
                "Batch screening complete [requests={}, totalMatches={}]",
                response.totalRequests(),
                responses.stream().mapToInt(ScreeningResponseDto::totalMatches).sum());

        return ResponseEntity.ok(response);
    }
}
