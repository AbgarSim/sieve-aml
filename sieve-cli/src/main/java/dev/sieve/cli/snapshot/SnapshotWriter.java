package dev.sieve.cli.snapshot;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.sieve.core.dedup.CanonicalEntity;
import dev.sieve.core.dedup.DeduplicationResult;
import dev.sieve.core.dedup.EntityDeduplicator;
import dev.sieve.core.geo.CountryNormalizer;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.RiskTopic;
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
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
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
 *
 * <p>One person or company is often listed by several authorities. The published records are
 * matched across lists by name, identifiers and date of birth (see {@link EntityDeduplicator}), and
 * every index entry of an entity found on more than one list carries the group's id in {@code g}:
 * the key of the group's first record in list order. {@code overview.json} counts the distinct
 * entities in {@code dedup}, and each list's row in {@code sources.json} says in {@code
 * onOtherLists} how many of its records another list also carries.
 *
 * <p>Politically exposed persons and their relatives or close associates are never written as
 * records or index entries: the public dashboard shows how many there are, not who they are. They
 * are counted in their list's row of {@code sources.json} (whose {@code published} field says how
 * many of a list's records were written), in the history and in the overview's {@code byTopic}, but
 * stay out of the headline totals, the country map and the top programs, which describe the
 * sanctions-style lists.
 */
public final class SnapshotWriter {

    /** Format version of the files, bumped on incompatible changes. */
    public static final int FORMAT_VERSION = 1;

    private static final Logger log = LoggerFactory.getLogger(SnapshotWriter.class);

    private final ObjectMapper mapper;
    private final StatsAggregator aggregator;
    private final CountryNormalizer countries;
    private final EntityDeduplicator deduplicator;
    private final int shardSize;
    private final Clock clock;

    /**
     * Creates a writer.
     *
     * @param countries resolves free-text country values to ISO codes
     * @param deduplicator finds the records that are one entity on several lists
     * @param shardSize entity records per shard file
     * @param clock source of the snapshot time
     */
    public SnapshotWriter(
            CountryNormalizer countries,
            EntityDeduplicator deduplicator,
            int shardSize,
            Clock clock) {
        if (shardSize < 1) {
            throw new IllegalArgumentException("shardSize must be positive");
        }
        this.countries = Objects.requireNonNull(countries, "countries must not be null");
        this.deduplicator = Objects.requireNonNull(deduplicator, "deduplicator must not be null");
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
        List<SanctionedEntity> published = all.stream().filter(SnapshotWriter::isPublic).toList();
        DatasetStats everything = aggregator.aggregate(all);
        DatasetStats stats = aggregator.aggregate(published);
        Overlap overlap = Overlap.of(deduplicator.deduplicate(published));

        writeJson(
                outDir.resolve("overview.json"),
                overview(stats, byTopic(all), overlap, fetched, now, commit));
        writeJson(outDir.resolve("sources.json"), sources(everything, overlap, fetched, now));
        writeJson(outDir.resolve("countries.json"), countries(stats, now));
        writeJson(
                outDir.resolve("search-index.json"), writeEntities(fetched, overlap, outDir, now));
        writeHistory(outDir.resolve("history.json"), stats, everything, overlap, now);

        log.info(
                "Snapshot written [dir={}, entities={}, distinct={}, countries={}]",
                outDir,
                stats.totalEntities(),
                overlap.distinctEntities(),
                stats.byCountry().size());
        return stats;
    }

    private Map<String, Object> overview(
            DatasetStats stats,
            Map<String, Integer> byTopic,
            Overlap overlap,
            List<FetchedSource> fetched,
            Instant now,
            Optional<String> commit) {
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
        map.put("byTopic", byTopic);
        map.put("namesByScript", stats.namesByScript());
        map.put("identifiersByType", stats.identifiersByType());
        map.put("topPrograms", stats.topPrograms());
        map.put("ingest", Map.of("sumMs", sumMs, "longestMs", wallMs));
        Map<String, Object> dedup = new LinkedHashMap<>();
        dedup.put("distinctEntities", overlap.distinctEntities());
        dedup.put("onSeveralLists", overlap.groups());
        dedup.put("ms", overlap.result().duration().toMillis());
        map.put("dedup", dedup);
        return map;
    }

    private Map<String, Object> sources(
            DatasetStats stats, Overlap overlap, List<FetchedSource> fetched, Instant now) {
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
                List<SanctionedEntity> entities =
                        outcome.map(FetchedSource::entities).orElse(List.of());
                row.put("entities", sourceStats.entities());
                row.put("published", entities.stream().filter(SnapshotWriter::isPublic).count());
                row.put("onOtherLists", overlap.onOtherLists().getOrDefault(source, 0));
                row.put("names", sourceStats.names());
                row.put("byType", sourceStats.byType());
                row.put("byTopic", byTopic(entities));
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

    private Map<String, Object> writeEntities(
            List<FetchedSource> fetched, Overlap overlap, Path outDir, Instant now)
            throws IOException {
        Path entitiesDir = outDir.resolve("entities");
        List<Map<String, Object>> index = new ArrayList<>();

        for (FetchedSource source : fetched) {
            List<SanctionedEntity> sorted =
                    source.entities().stream()
                            .filter(SnapshotWriter::isPublic)
                            .sorted(Comparator.comparing(SanctionedEntity::id))
                            .toList();
            if (sorted.isEmpty()) {
                continue;
            }
            Path sourceDir = entitiesDir.resolve(source.source().name());
            Files.createDirectories(sourceDir);
            for (int shard = 0; shard * shardSize < sorted.size(); shard++) {
                List<SanctionedEntity> page =
                        sorted.subList(
                                shard * shardSize,
                                Math.min((shard + 1) * shardSize, sorted.size()));
                writeJson(sourceDir.resolve(shard + ".json"), page);
                for (SanctionedEntity entity : page) {
                    index.add(indexEntry(entity, overlap, shard));
                }
            }
        }

        Map<String, Object> map = header(now);
        map.put("shardSize", shardSize);
        map.put("entries", index);
        return map;
    }

    /** Whether an entity's record may be published; PEP and RCA records are counted only. */
    static boolean isPublic(SanctionedEntity entity) {
        return !entity.topics().contains(RiskTopic.PEP) && !entity.topics().contains(RiskTopic.RCA);
    }

    /** Entities per risk topic, in topic order; an entity with several topics counts for each. */
    static Map<String, Integer> byTopic(Collection<SanctionedEntity> entities) {
        Map<RiskTopic, Integer> counts = new EnumMap<>(RiskTopic.class);
        for (SanctionedEntity entity : entities) {
            for (RiskTopic topic : entity.topics()) {
                counts.merge(topic, 1, Integer::sum);
            }
        }
        Map<String, Integer> byTopic = new LinkedHashMap<>();
        counts.forEach((topic, n) -> byTopic.put(topic.name(), n));
        return byTopic;
    }

    private Map<String, Object> indexEntry(SanctionedEntity entity, Overlap overlap, int shard) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("k", key(entity));
        entry.put("g", overlap.groupOf().get(key(entity)));
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

    private void writeHistory(
            Path file, DatasetStats stats, DatasetStats everything, Overlap overlap, Instant now)
            throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (Files.exists(file)) {
            rows.addAll(mapper.readValue(file.toFile(), new TypeReference<>() {}));
        }
        String today = LocalDate.ofInstant(now, ZoneOffset.UTC).toString();
        rows.removeIf(row -> today.equals(row.get("date")));

        Map<String, Integer> bySource = new TreeMap<>();
        everything.bySource().forEach((source, s) -> bySource.put(source.name(), s.entities()));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("date", today);
        row.put("totalEntities", stats.totalEntities());
        row.put("distinctEntities", overlap.distinctEntities());
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

    /**
     * The records that are one entity on several lists, from one deduplication of the published
     * records.
     *
     * @param result the deduplication
     * @param groupOf group id by record key, for the records of groups with more than one member
     * @param onOtherLists per list, how many of its records another list also carries
     */
    private record Overlap(
            DeduplicationResult result,
            Map<String, String> groupOf,
            Map<ListSource, Integer> onOtherLists) {

        /** Lists in {@link ListSource} order, then ids, so a group's id is the same every night. */
        private static final Comparator<SanctionedEntity> LEAD_FIRST =
                Comparator.comparing(SanctionedEntity::listSource)
                        .thenComparing(SanctionedEntity::id);

        static Overlap of(DeduplicationResult result) {
            Map<String, String> groupOf = new HashMap<>();
            Map<ListSource, Integer> onOtherLists = new EnumMap<>(ListSource.class);
            for (CanonicalEntity canonical : result.canonicalEntities().values()) {
                if (canonical.sourceCount() < 2) {
                    continue;
                }
                List<SanctionedEntity> members =
                        canonical.sourceEntities().values().stream()
                                .flatMap(List::stream)
                                .sorted(LEAD_FIRST)
                                .toList();
                String group = key(members.getFirst());
                for (SanctionedEntity member : members) {
                    groupOf.put(key(member), group);
                    onOtherLists.merge(member.listSource(), 1, Integer::sum);
                }
            }
            return new Overlap(result, Map.copyOf(groupOf), Map.copyOf(onOtherLists));
        }

        int distinctEntities() {
            return result.totalCanonicalEntities();
        }

        /** Entities found on more than one list. */
        int groups() {
            return result.mergedGroups();
        }
    }

    private static String typeCode(EntityType type) {
        return switch (type) {
            case INDIVIDUAL -> "I";
            case ENTITY -> "E";
            case VESSEL -> "V";
            case AIRCRAFT -> "A";
            case COMPANY -> "C";
            case ORGANIZATION -> "O";
            case CRYPTO_WALLET -> "W";
            case SECURITY -> "S";
        };
    }
}
