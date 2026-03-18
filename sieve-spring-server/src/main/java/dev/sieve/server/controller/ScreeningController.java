package dev.sieve.server.controller;

import dev.sieve.core.index.EntityIndex;
import dev.sieve.core.match.MatchEngine;
import dev.sieve.core.match.MatchResult;
import dev.sieve.core.match.ScreeningRequest;
import dev.sieve.server.config.SieveProperties;
import dev.sieve.server.dto.ScreeningRequestDto;
import dev.sieve.server.dto.ScreeningResponseDto;
import dev.sieve.server.mapper.ScreeningMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
}
