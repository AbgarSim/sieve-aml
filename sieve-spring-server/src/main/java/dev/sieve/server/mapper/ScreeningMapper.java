package dev.sieve.server.mapper;

import dev.sieve.core.index.IndexStats;
import dev.sieve.core.match.MatchResult;
import dev.sieve.core.match.ScreeningRequest;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.SanctionsProgram;
import dev.sieve.ingest.IngestionReport;
import dev.sieve.ingest.ProviderResult;
import dev.sieve.server.dto.EntityDto;
import dev.sieve.server.dto.EntityPageDto;
import dev.sieve.server.dto.IndexStatsDto;
import dev.sieve.server.dto.MatchResultDto;
import dev.sieve.server.dto.ProviderResultDto;
import dev.sieve.server.dto.RefreshResponseDto;
import dev.sieve.server.dto.ScreeningRequestDto;
import dev.sieve.server.dto.ScreeningResponseDto;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Maps between domain objects and REST DTOs.
 *
 * <p>All mapping is manual (no MapStruct) for clarity and control.
 */
@Component
public class ScreeningMapper {

    /**
     * Converts a {@link ScreeningRequestDto} to a domain {@link ScreeningRequest}.
     *
     * @param dto the inbound request DTO
     * @param defaultThreshold the server-configured default threshold
     * @return the domain screening request
     */
    public ScreeningRequest toDomain(ScreeningRequestDto dto, double defaultThreshold) {
        Optional<EntityType> entityType =
                dto.entityType() != null
                        ? Optional.of(EntityType.fromString(dto.entityType()))
                        : Optional.empty();

        Optional<Set<ListSource>> sources =
                dto.sources() != null && !dto.sources().isEmpty()
                        ? Optional.of(
                                dto.sources().stream()
                                        .map(ListSource::fromString)
                                        .collect(Collectors.toSet()))
                        : Optional.empty();

        double threshold = dto.threshold() != null ? dto.threshold() : defaultThreshold;

        return new ScreeningRequest(dto.name(), entityType, sources, threshold);
    }

    /**
     * Converts a list of domain {@link MatchResult}s to a {@link ScreeningResponseDto}.
     *
     * @param query the original query name
     * @param results the match results
     * @param maxResults maximum number of results to include
     * @return the screening response DTO
     */
    public ScreeningResponseDto toScreeningResponse(
            String query, List<MatchResult> results, int maxResults) {
        List<MatchResultDto> resultDtos =
                results.stream().limit(maxResults).map(this::toMatchResultDto).toList();

        return new ScreeningResponseDto(query, resultDtos.size(), Instant.now(), resultDtos);
    }

    /**
     * Converts a domain {@link SanctionedEntity} to an {@link EntityDto}.
     *
     * @param entity the domain entity
     * @return the entity DTO
     */
    public EntityDto toEntityDto(SanctionedEntity entity) {
        List<String> aliases = entity.aliases().stream().map(NameInfo::fullName).toList();
        List<String> programs = entity.programs().stream().map(SanctionsProgram::code).toList();

        return new EntityDto(
                entity.id(),
                entity.entityType().name(),
                entity.listSource().name(),
                entity.primaryName().fullName(),
                aliases,
                entity.nationalities(),
                programs,
                entity.remarks(),
                entity.lastUpdated());
    }

    /**
     * Converts a collection of entities to a paginated {@link EntityPageDto}.
     *
     * @param entities all entities to paginate
     * @param page zero-based page number
     * @param size page size
     * @return the paginated entity DTO
     */
    public EntityPageDto toEntityPage(Collection<SanctionedEntity> entities, int page, int size) {
        List<SanctionedEntity> entityList =
                entities instanceof List<?>
                        ? (List<SanctionedEntity>) entities
                        : List.copyOf(entities);

        int totalElements = entityList.size();
        int totalPages = (int) Math.ceil((double) totalElements / size);
        int fromIndex = Math.min(page * size, totalElements);
        int toIndex = Math.min(fromIndex + size, totalElements);

        List<EntityDto> pageDtos =
                entityList.subList(fromIndex, toIndex).stream().map(this::toEntityDto).toList();

        return new EntityPageDto(pageDtos, page, size, totalElements, totalPages);
    }

    /**
     * Converts domain {@link IndexStats} to an {@link IndexStatsDto}.
     *
     * @param stats the domain index stats
     * @return the stats DTO
     */
    public IndexStatsDto toIndexStatsDto(IndexStats stats) {
        Map<String, Integer> bySource = new LinkedHashMap<>();
        stats.countBySource().forEach((k, v) -> bySource.put(k.name(), v));

        Map<String, Integer> byType = new LinkedHashMap<>();
        stats.countByType().forEach((k, v) -> byType.put(k.name(), v));

        return new IndexStatsDto(stats.totalEntities(), bySource, byType, stats.lastUpdated());
    }

    /**
     * Converts an {@link IngestionReport} to a {@link RefreshResponseDto}.
     *
     * @param report the domain ingestion report
     * @return the refresh response DTO
     */
    public RefreshResponseDto toRefreshResponse(IngestionReport report) {
        Map<String, ProviderResultDto> results = new LinkedHashMap<>();
        report.results()
                .forEach(
                        (source, result) ->
                                results.put(source.name(), toProviderResultDto(result)));

        return new RefreshResponseDto(
                report.totalEntitiesLoaded(), report.totalDuration().toMillis(), results);
    }

    private MatchResultDto toMatchResultDto(MatchResult result) {
        return new MatchResultDto(
                toEntityDto(result.entity()),
                result.score(),
                result.matchedField(),
                result.matchAlgorithm());
    }

    private ProviderResultDto toProviderResultDto(ProviderResult result) {
        return new ProviderResultDto(
                result.status().name(),
                result.entityCount(),
                result.duration().toMillis(),
                result.error().orElse(null));
    }
}
