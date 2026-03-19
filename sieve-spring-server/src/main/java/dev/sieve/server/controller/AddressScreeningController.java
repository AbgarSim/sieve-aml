package dev.sieve.server.controller;

import dev.sieve.address.AddressMatchService;
import dev.sieve.address.AddressMatchService.AddressMatchResult;
import dev.sieve.core.index.EntityIndex;
import dev.sieve.server.config.SieveProperties;
import dev.sieve.server.dto.AddressDto;
import dev.sieve.server.dto.AddressMatchResultDto;
import dev.sieve.server.dto.AddressScreeningRequestDto;
import dev.sieve.server.dto.AddressScreeningResponseDto;
import dev.sieve.server.dto.EntityDto;
import dev.sieve.server.mapper.ScreeningMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST controller for address-based sanctions screening. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Address Screening", description = "Address screening against sanctions lists")
public class AddressScreeningController {

    private static final Logger log = LoggerFactory.getLogger(AddressScreeningController.class);

    private final AddressMatchService addressMatchService;
    private final EntityIndex entityIndex;
    private final ScreeningMapper mapper;
    private final SieveProperties properties;

    public AddressScreeningController(
            AddressMatchService addressMatchService,
            EntityIndex entityIndex,
            ScreeningMapper mapper,
            SieveProperties properties) {
        this.addressMatchService = addressMatchService;
        this.entityIndex = entityIndex;
        this.mapper = mapper;
        this.properties = properties;
    }

    /**
     * Screens a free-text address against entity addresses in the loaded sanctions lists.
     *
     * <p>The address is parsed using libpostal (NLP-capable) into structured components, then
     * matched against all entity addresses in the index.
     *
     * @param requestDto the address screening request
     * @return the screening response with matched entities
     */
    @PostMapping("/screen/address")
    @Operation(
            summary = "Screen an address against sanctions lists",
            description =
                    "Parses a free-text address using libpostal and matches it against all entity"
                            + " addresses in the loaded sanctions lists. Supports NLP-style input.")
    @ApiResponse(responseCode = "200", description = "Address screening completed successfully")
    @ApiResponse(responseCode = "400", description = "Invalid request parameters")
    public ResponseEntity<AddressScreeningResponseDto> screenAddress(
            @Valid @RequestBody AddressScreeningRequestDto requestDto) {
        log.debug(
                "Address screening request [address={}, threshold={}]",
                requestDto.address(),
                requestDto.threshold());

        double threshold =
                requestDto.threshold() != null
                        ? requestDto.threshold()
                        : properties.screening().defaultThreshold();

        List<AddressMatchResult> results =
                addressMatchService.screen(
                        requestDto.address(),
                        entityIndex,
                        threshold,
                        properties.screening().maxResults());

        List<AddressMatchResultDto> resultDtos = results.stream().map(this::toResultDto).toList();

        AddressScreeningResponseDto response =
                new AddressScreeningResponseDto(
                        requestDto.address(), resultDtos.size(), Instant.now(), resultDtos);

        log.debug(
                "Address screening complete [address={}, matches={}]",
                requestDto.address(),
                response.totalMatches());

        return ResponseEntity.ok(response);
    }

    private AddressMatchResultDto toResultDto(AddressMatchResult result) {
        EntityDto entityDto = mapper.toEntityDto(result.entity());
        AddressDto matchedAddress =
                new AddressDto(
                        result.matchedAddress().street(),
                        result.matchedAddress().city(),
                        result.matchedAddress().stateOrProvince(),
                        result.matchedAddress().postalCode(),
                        result.matchedAddress().country(),
                        result.matchedAddress().fullAddress());
        return new AddressMatchResultDto(entityDto, result.score(), matchedAddress);
    }
}
