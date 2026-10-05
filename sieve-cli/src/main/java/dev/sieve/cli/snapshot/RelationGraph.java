package dev.sieve.cli.snapshot;

import dev.sieve.core.geo.CountryNormalizer;
import dev.sieve.core.model.Address;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.Relation;
import dev.sieve.core.model.RelationType;
import dev.sieve.core.model.SanctionedEntity;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The links between published records, as the dashboard's association graph draws them.
 *
 * <p>Each {@link Relation} a list states becomes an edge from the record that holds it to the
 * record it names, keyed by {@link SnapshotWriter#key snapshot key}. A target id is looked up in
 * the holder's own list first and then among every other list's records, where it must be unique.
 * Links to records that are never published (politically exposed persons and their relatives),
 * links to positions rather than entities, links a record has to itself and targets no list
 * carries are left out and counted in {@code dropped}. When a list states both a plain link and a
 * typed one between the same two records, as OFAC does for a vessel and its owner, only the typed
 * one is kept.
 *
 * <p>Every record at either end of an edge gets a home country in {@code home}, so the graph can
 * place it on the map: its first nationality or citizenship, else the country of its first address,
 * else a vessel's flag. Records with no resolvable country are left out of {@code home}.
 */
final class RelationGraph {

    /** An edge: from the holder to the target, with the relation's details. */
    record Edge(String from, String to, Relation relation) {}

    private final List<Edge> edges;
    private final Map<String, String> home;
    private final Map<String, Integer> dropped;

    private RelationGraph(List<Edge> edges, Map<String, String> home, Map<String, Integer> dropped) {
        this.edges = edges;
        this.home = home;
        this.dropped = dropped;
    }

    /**
     * Builds the graph of the published records.
     *
     * @param all every fetched record, published or not, so links to unpublished ones can be told
     *     apart from links to records no list carries
     * @param published the records the snapshot writes
     * @param countries resolves country values to ISO codes
     * @return the graph
     */
    static RelationGraph of(
            Collection<SanctionedEntity> all,
            Collection<SanctionedEntity> published,
            CountryNormalizer countries) {
        Objects.requireNonNull(countries, "countries must not be null");
        Map<ListSource, Map<String, SanctionedEntity>> bySource = new EnumMap<>(ListSource.class);
        Map<String, List<SanctionedEntity>> byId = new HashMap<>();
        for (SanctionedEntity entity : all) {
            bySource.computeIfAbsent(entity.listSource(), s -> new HashMap<>())
                    .put(entity.id(), entity);
            byId.computeIfAbsent(entity.id(), id -> new ArrayList<>(1)).add(entity);
        }
        Set<String> publishedKeys = new HashSet<>();
        published.forEach(e -> publishedKeys.add(SnapshotWriter.key(e)));

        Map<String, Integer> dropped = new TreeMap<>();
        Map<String, Map<String, Edge>> byPair = new HashMap<>();
        List<SanctionedEntity> holders =
                published.stream()
                        .filter(e -> !e.relations().isEmpty())
                        .sorted(
                                Comparator.comparing(SanctionedEntity::listSource)
                                        .thenComparing(SanctionedEntity::id))
                        .toList();
        for (SanctionedEntity holder : holders) {
            String from = SnapshotWriter.key(holder);
            for (Relation relation : holder.relations()) {
                if (relation.type() == RelationType.POSITION_HELD) {
                    dropped.merge("position", 1, Integer::sum);
                    continue;
                }
                Optional<SanctionedEntity> target =
                        resolve(holder.listSource(), relation.targetId(), bySource, byId);
                if (target.isEmpty()) {
                    dropped.merge("unresolved", 1, Integer::sum);
                    continue;
                }
                String to = SnapshotWriter.key(target.get());
                if (to.equals(from)) {
                    dropped.merge("self", 1, Integer::sum);
                } else if (!publishedKeys.contains(to)) {
                    dropped.merge("unpublished", 1, Integer::sum);
                } else {
                    String pair = from.compareTo(to) < 0 ? from + "|" + to : to + "|" + from;
                    byPair.computeIfAbsent(pair, p -> new LinkedHashMap<>())
                            .putIfAbsent(
                                    from + ">" + to + ">" + relation.type(),
                                    new Edge(from, to, relation));
                }
            }
        }

        List<Edge> edges = new ArrayList<>();
        for (Map<String, Edge> pair : byPair.values()) {
            boolean typed =
                    pair.values().stream()
                            .anyMatch(e -> e.relation().type() != RelationType.LINKED);
            for (Edge edge : pair.values()) {
                if (typed && edge.relation().type() == RelationType.LINKED) {
                    dropped.merge("duplicate", 1, Integer::sum);
                } else {
                    edges.add(edge);
                }
            }
        }
        edges.sort(Comparator.comparing(Edge::from).thenComparing(Edge::to));

        Map<String, SanctionedEntity> byKey = new HashMap<>();
        published.forEach(e -> byKey.put(SnapshotWriter.key(e), e));
        Map<String, String> home = new TreeMap<>();
        for (Edge edge : edges) {
            for (String key : List.of(edge.from(), edge.to())) {
                if (!home.containsKey(key)) {
                    homeCountry(byKey.get(key), countries).ifPresent(cc -> home.put(key, cc));
                }
            }
        }
        return new RelationGraph(List.copyOf(edges), home, dropped);
    }

    private static Optional<SanctionedEntity> resolve(
            ListSource holder,
            String targetId,
            Map<ListSource, Map<String, SanctionedEntity>> bySource,
            Map<String, List<SanctionedEntity>> byId) {
        SanctionedEntity own = bySource.getOrDefault(holder, Map.of()).get(targetId);
        if (own != null) {
            return Optional.of(own);
        }
        List<SanctionedEntity> elsewhere = byId.getOrDefault(targetId, List.of());
        return elsewhere.size() == 1 ? Optional.of(elsewhere.getFirst()) : Optional.empty();
    }

    /**
     * Returns the country an entity is placed at on the graph's map.
     *
     * @param entity the entity
     * @param countries resolves country values to ISO codes
     * @return its first nationality or citizenship, else its first address country, else its flag
     */
    static Optional<String> homeCountry(SanctionedEntity entity, CountryNormalizer countries) {
        List<String> values = new ArrayList<>(entity.nationalities());
        values.addAll(entity.citizenships());
        entity.addresses().stream().map(Address::country).forEach(values::add);
        if (entity.vessel() != null) {
            values.add(entity.vessel().flag());
        }
        for (String value : values) {
            if (value == null) {
                continue;
            }
            for (String part : countries.split(value)) {
                Optional<String> code = countries.toIso2(part);
                if (code.isPresent()) {
                    return code;
                }
            }
        }
        return Optional.empty();
    }

    List<Edge> edges() {
        return edges;
    }

    /**
     * Returns the content of {@code relations.json}, without its header.
     *
     * @return edges, home countries and counts
     */
    Map<String, Object> toJson() {
        List<Map<String, Object>> rows = new ArrayList<>(edges.size());
        Map<String, Integer> byType = new TreeMap<>();
        Set<String> linked = new HashSet<>();
        for (Edge edge : edges) {
            Relation r = edge.relation();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("f", edge.from());
            row.put("t", edge.to());
            row.put("r", r.type().name());
            row.put("l", r.role());
            row.put("p", r.sharePercentage());
            row.put("s", r.startDate());
            row.put("e", r.endDate());
            rows.add(row);
            byType.merge(r.type().name(), 1, Integer::sum);
            linked.add(edge.from());
            linked.add(edge.to());
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("edges", edges.size());
        stats.put("entities", linked.size());
        stats.put("byType", byType);
        stats.put("dropped", dropped);

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("stats", stats);
        map.put("edges", rows);
        map.put("home", home);
        return map;
    }
}
