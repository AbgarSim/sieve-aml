package dev.sieve.cli.snapshot;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.sieve.core.geo.CountryNormalizer;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.SanctionsProgram;
import dev.sieve.core.stats.CountryStats;
import dev.sieve.core.stats.DatasetStats;
import dev.sieve.core.stats.SourceStats;
import dev.sieve.core.stats.StatsAggregator;
import dev.sieve.ingest.ListMetadata;
import dev.sieve.ingest.SourceCatalog;
import dev.sieve.ingest.SourceInfo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes a dashboard snapshot: aggregate JSON files, entity shards and a search index.
 *
 * <p>Layout of the output directory:
 *
 * <pre>
 *   overview.json             headline numbers, type mix, scripts, identifiers, top programs
 *   sources.json              one row per list: catalog facts, fetch outcome, aggregates
 *   countries.json            per-country counts for the map, plus unresolved country values
 *   search-index.json         names, aliases, type, source, countries and shard of every entity
 *   entities/SOURCE/N.json    full entity records, sorted by id, {@code shardSize} per file
 *   history.json              one row per day with totals per list; rows are kept across runs
 * </pre>
 *
 * <p>An entity's key is {@code SOURCE/id}, because raw ids repeat across lists.
 */
public final class SnapshotWriter {

    /** Format version of the files, bumped on incompatible changes. */
    public static final int FORMAT_VERSION = 1;

    private static final Logger log = LoggerFactory.getLogger(SnapshotWriter.class);

    private final ObjectMapper mapper;
    private final StatsAggregator aggregator;
    private final CountryNormalizer countries;
    private final int shardSize;
    private final Clock clock;

    /**
     * Creates a writer.
     *
     * @param countries resolves free-text country values to ISO codes
     * @param shardSize entity records per shard file
     * @param clock source of the snapshot time
     */
    public SnapshotWriter(CountryNormalizer countries, int shardSize, Clock clock) {
        if (shardSize < 1) {
            throw new IllegalArgumentException("shardSize must be positive");
        }
        this.countries = Objects.requireNonNull(countries, "countries must not be null");
        this.aggregator = new StatsAggregator(countries);
        this.shardSize = shardSize;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.mapper =
                new ObjectMapper()
                        .registerModule(new JavaTimeModule())
                        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                        .setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
    }

    /**
     * Writes a snapshot of the fetched lists.
     *
     * @param fetched one outcome per list
     * @param outDir directory to write into; created if missing
     * @param commit the source commit the snapshot was built from, if known
     * @return the aggregates written to {@code overview.json}
     * @throws IOException when a file cannot be written
     */
    public DatasetStats write(List<FetchedSource> fetched, Path outDir, Optional<String> commit)
            throws IOException {
        Objects.requireNonNull(fetched, "fetched must not be null");
        Objects.requireNonNull(outDir, "outDir must not be null");
        Files.createDirectories(outDir);
        Instant now = clock.instant();

        List<SanctionedEntity> all = fetched.stream().flatMap(f -> f.entities().stream()).toList();
        DatasetStats stats = aggregator.aggregate(all);

        writeJson(outDir.resolve("overview.json"), overview(stats, fetched, now, commit));
        writeJson(outDir.resolve("sources.json"), sources(stats, fetched, now));
        writeJson(outDir.resolve("countries.json"), countries(stats, now));
        writeJson(outDir.resolve("search-index.json"), writeEntities(fetched, outDir, now));
        writeHistory(outDir.resolve("history.json"), stats, now);

        log.info(
                "Snapshot written [dir={}, entities={}, countries={}]",
                outDir,
                stats.totalEntities(),
                stats.byCountry().size());
        return stats;
    }

    private Map<String, Object> overview(
            DatasetStats stats, List<FetchedSource> fetched, Instant now, Optional<String> commit) {
        long loaded =
                fetched.stream().filter(f -> f.status() == FetchedSource.Status.LOADED).count();
        long sumMs = fetched.stream().mapToLong(f -> f.duration().toMillis()).sum();
        long wallMs = fetched.stream().mapToLong(f -> f.duration().toMillis()).max().orElse(0);

        Map<String, Object> map = header(now);
        commit.ifPresent(c -> map.put("commit", c));
        map.put("totalEntities", stats.totalEntities());
        map.put("totalNames", stats.totalNames());
        map.put("sourcesTotal", ListSource.values().length);
        map.put("sourcesLoaded", loaded);
        map.put("countries", stats.byCountry().size());
        map.put("distinctPrograms", stats.distinctPrograms());
        map.put("byType", stats.byType());
        map.put("namesByScript", stats.namesByScript());
        map.put("identifiersByType", stats.identifiersByType());
        map.put("topPrograms", stats.topPrograms());
        map.put("ingest", Map.of("sumMs", sumMs, "longestMs", wallMs));
        return map;
    }

    private Map<String, Object> sources(
            DatasetStats stats, List<FetchedSource> fetched, Instant now) {
        Map<ListSource, FetchedSource> bySource = new EnumMap<>(ListSource.class);
        fetched.forEach(f -> bySource.put(f.source(), f));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (SourceInfo info : SourceCatalog.all()) {
            ListSource source = info.source();
            Optional<FetchedSource> outcome = Optional.ofNullable(bySource.get(source));
            Optional<ListMetadata> metadata = outcome.flatMap(FetchedSource::metadata);
            SourceStats sourceStats = stats.bySource().get(source);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("source", source.name());
            row.put("displayName", source.displayName());
            row.put("authority", info.authority());
            row.put("jurisdiction", info.jurisdiction());
            row.put("format", info.format());
            row.put("homepage", info.homepage());
            row.put(
                    "status",
                    outcome.map(FetchedSource::status).orElse(FetchedSource.Status.SKIPPED));
            outcome.flatMap(FetchedSource::error).ifPresent(e -> row.put("error", e));
            outcome.ifPresent(o -> row.put("fetchMs", o.duration().toMillis()));
            metadata.ifPresent(
                    m -> {
                        row.put("listUri", m.sourceUri());
                        row.put("lastFetched", m.lastFetched());
                        row.put("contentHash", m.contentHash());
                        row.put("etag", m.etag());
                    });
            if (sourceStats != null) {
                row.put("entities", sourceStats.entities());
                row.put("names", sourceStats.names());
                row.put("byType", sourceStats.byType());
                row.put("countries", sourceStats.countries());
                row.put("completeness", sourceStats.completeness());
                row.put("topPrograms", sourceStats.topPrograms());
            } else {
                row.put("entities", 0);
            }
            rows.add(row);
        }

        Map<String, Object> map = header(now);
        map.put("sources", rows);
        return map;
    }

    private Map<String, Object> countries(DatasetStats stats, Instant now) {
        Map<String, Object> byCountry = new TreeMap<>();
        stats.byCountry()
                .forEach((code, country) -> byCountry.put(code, countryRow(code, country)));

        Map<String, Object> map = header(now);
        map.put("countries", byCountry);
        map.put("unresolved", stats.unresolvedCountries());
        return map;
    }

    private Map<String, Object> countryRow(String code, CountryStats country) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", countries.displayName(code));
        row.put("entities", country.entities());
        row.put("byNationality", country.byNationality());
        row.put("byAddress", country.byAddress());
        row.put("bySource", country.bySource());
        row.put("byType", country.byType());
        return row;
    }

    private Map<String, Object> writeEntities(List<FetchedSource> fetched, Path outDir, Instant now)
            throws IOException {
        Path entitiesDir = outDir.resolve("entities");
        List<Map<String, Object>> index = new ArrayList<>();

        for (FetchedSource source : fetched) {
            if (source.entities().isEmpty()) {
                continue;
            }
            Path sourceDir = entitiesDir.resolve(source.source().name());
            Files.createDirectories(sourceDir);
            List<SanctionedEntity> sorted =
                    source.entities().stream()
                            .sorted(Comparator.comparing(SanctionedEntity::id))
                            .toList();
            for (int shard = 0; shard * shardSize < sorted.size(); shard++) {
                List<SanctionedEntity> page =
                        sorted.subList(
                                shard * shardSize,
                                Math.min((shard + 1) * shardSize, sorted.size()));
                writeJson(sourceDir.resolve(shard + ".json"), page);
                for (SanctionedEntity entity : page) {
                    index.add(indexEntry(entity, shard));
                }
            }
        }

        Map<String, Object> map = header(now);
        map.put("shardSize", shardSize);
        map.put("entries", index);
        return map;
    }

    private Map<String, Object> indexEntry(SanctionedEntity entity, int shard) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("k", key(entity));
        entry.put("n", entity.primaryName().fullName());
        entry.put(
                "a",
                entity.aliases().stream()
                        .map(NameInfo::fullName)
                        .filter(name -> !name.equals(entity.primaryName().fullName()))
                        .distinct()
                        .toList());
        entry.put("t", typeCode(entity.entityType()));
        entry.put("s", entity.listSource().name());
        entry.put("c", aggregator.countryCodes(entity));
        entry.put("p", entity.programs().stream().map(SanctionsProgram::code).distinct().toList());
        entry.put("f", shard);
        return entry;
    }

    private void writeHistory(Path file, DatasetStats stats, Instant now) throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (Files.exists(file)) {
            rows.addAll(mapper.readValue(file.toFile(), new TypeReference<>() {}));
        }
        String today = LocalDate.ofInstant(now, ZoneOffset.UTC).toString();
        rows.removeIf(row -> today.equals(row.get("date")));

        Map<String, Integer> bySource = new TreeMap<>();
        stats.bySource().forEach((source, s) -> bySource.put(source.name(), s.entities()));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("date", today);
        row.put("totalEntities", stats.totalEntities());
        row.put("totalNames", stats.totalNames());
        row.put("countries", stats.byCountry().size());
        row.put("bySource", bySource);
        rows.add(row);
        rows.sort(Comparator.comparing(r -> String.valueOf(r.get("date"))));

        writeJson(file, rows);
    }

    private Map<String, Object> header(Instant now) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("formatVersion", FORMAT_VERSION);
        map.put("generatedAt", now);
        return map;
    }

    private void writeJson(Path file, Object value) throws IOException {
        mapper.writeValue(file.toFile(), value);
    }

    /**
     * Returns the snapshot key of an entity, unique across lists.
     *
     * @param entity the entity
     * @return {@code SOURCE/id}
     */
    public static String key(SanctionedEntity entity) {
        return entity.listSource().name() + "/" + entity.id();
    }

    private static String typeCode(EntityType type) {
        return switch (type) {
            case INDIVIDUAL -> "I";
            case ENTITY -> "E";
            case VESSEL -> "V";
            case AIRCRAFT -> "A";
        };
    }
}
