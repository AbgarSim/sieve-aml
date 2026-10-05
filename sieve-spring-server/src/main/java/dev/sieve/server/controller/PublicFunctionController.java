package dev.sieve.server.controller;

import dev.sieve.ingest.pep.PublicFunction;
import dev.sieve.ingest.pep.PublicFunctionCatalog;
import dev.sieve.ingest.pep.PublicFunctionCategory;
import dev.sieve.server.dto.JurisdictionFunctionCountDto;
import dev.sieve.server.dto.JurisdictionFunctionsDto;
import dev.sieve.server.dto.PublicFunctionDto;
import dev.sieve.server.dto.PublicFunctionsSummaryDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST controller serving the EU list of prominent public functions: the functions each member
 * state and the Union list under Article 20a of Directive (EU) 2015/849, which say which positions
 * make their holder a politically exposed person.
 */
@RestController
@RequestMapping("/api/v1/pep/functions")
@Tag(
        name = "PEP functions",
        description = "The EU list of prominent public functions, by jurisdiction")
public class PublicFunctionController {

    private final PublicFunctionCatalog catalog;

    /**
     * Creates a new controller.
     *
     * @param catalog the catalogue of prominent public functions
     */
    public PublicFunctionController(PublicFunctionCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * Summarises the catalogue: the Official Journal document it comes from and how many functions
     * each jurisdiction lists.
     *
     * @return the summary
     */
    @GetMapping
    @Operation(
            summary = "Summarise the EU list of prominent public functions",
            description =
                    "Returns the Official Journal document the catalogue is read from and the"
                            + " number of functions each member state and the Union list.")
    @ApiResponse(responseCode = "200", description = "Summary retrieved")
    public ResponseEntity<PublicFunctionsSummaryDto> getSummary() {
        List<JurisdictionFunctionCountDto> jurisdictions =
                catalog.jurisdictions().stream()
                        .map(
                                jurisdiction ->
                                        new JurisdictionFunctionCountDto(
                                                jurisdiction,
                                                catalog.functions(jurisdiction).size()))
                        .toList();
        return ResponseEntity.ok(
                new PublicFunctionsSummaryDto(
                        catalog.title(),
                        catalog.reference(),
                        catalog.published(),
                        catalog.eli(),
                        catalog.size(),
                        jurisdictions));
    }

    /**
     * Returns the functions one jurisdiction lists.
     *
     * @param jurisdiction the ISO 3166-1 alpha-2 code of a member state, or {@code EU}
     * @param category a point of Article 3(9), {@code a} to {@code h}, to return only its functions
     * @return the functions
     */
    @GetMapping("/{jurisdiction}")
    @Operation(
            summary = "List a jurisdiction's prominent public functions",
            description =
                    "Returns the functions one member state, or the Union (EU), lists, each with"
                            + " the directive category it is filed under, the heading it appears"
                            + " under and, for an international organisation's post, the"
                            + " organisation.")
    @ApiResponse(responseCode = "200", description = "Functions retrieved")
    @ApiResponse(responseCode = "400", description = "Invalid category")
    @ApiResponse(responseCode = "404", description = "The jurisdiction has no list")
    public ResponseEntity<JurisdictionFunctionsDto> getFunctions(
            @PathVariable
                    @Parameter(
                            description = "ISO 3166-1 alpha-2 code of a member state, or EU",
                            example = "DE")
                    String jurisdiction,
            @RequestParam(required = false)
                    @Parameter(
                            description =
                                    "Point of Article 3(9) of Directive (EU) 2015/849, a to h",
                            example = "a")
                    String category) {
        String code = jurisdiction.toUpperCase(Locale.ROOT);
        if (!catalog.lists(code)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "No list of prominent public functions for " + code);
        }
        PublicFunctionCategory filter = null;
        if (category != null && !category.isBlank()) {
            filter =
                    PublicFunctionCategory.fromPoint(category)
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "Category must be a point of Article 3(9),"
                                                            + " a to h: "
                                                            + category));
        }
        PublicFunctionCategory wanted = filter;
        List<PublicFunctionDto> functions =
                catalog.functions(code).stream()
                        .filter(function -> wanted == null || function.category() == wanted)
                        .map(PublicFunctionController::toDto)
                        .toList();
        return ResponseEntity.ok(
                new JurisdictionFunctionsDto(
                        code, catalog.reference(), functions.size(), functions));
    }

    private static PublicFunctionDto toDto(PublicFunction function) {
        return new PublicFunctionDto(
                function.category() == null ? null : String.valueOf(function.category().point()),
                function.heading(),
                function.function(),
                function.organisation());
    }
}
