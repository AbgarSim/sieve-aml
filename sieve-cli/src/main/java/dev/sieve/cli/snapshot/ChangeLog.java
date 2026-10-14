package dev.sieve.cli.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sieve.core.model.ListSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes {@code changes.json}: for each of the last {@value #NIGHTS} nights, the records that
 * appeared on a list, left it or changed, for the dashboard's "What changed tonight" view.
 *
 * <p>A record is new when its key was not in the previous night's {@code seen.json}, removed when
 * it was there, its list loaded tonight and the key is no longer published, and changed when its
 * content digest differs from the previous night's. A list that did not load tonight contributes
 * nothing, so a failed fetch never reads as a mass delisting. Counts are exact; the records listed
 * under {@code records} are capped at {@value #LISTED} per kind, in list order and then by name.
 *
 * <p>Format: {@code {formatVersion, generatedAt, nights: [{date, added, removed, changed, bySource:
 * {LIST: [added, removed, changed]}, records: {added: [{k, n, t, c, p}], removed: [...], changed:
 * [...]}}]}}, oldest night first; {@code n} is the name, {@code t} the type code of the search
 * index, {@code c} the countries and {@code p} the program codes. The Dashboard workflow restores
 * the previous file from the published site, like {@code seen.json}. A second run on the same day
 * sees only what changed since the first, because {@code seen.json} was restored from it, so its
 * changes are added to that day's night rather than replacing it. {@code digestVersion} names the
 * {@link SnapshotWriter#DIGEST_VERSION record digest} the nights were counted with; nights counted
 * with an older one are dropped, because their changed counts are not comparable.
 */
final class ChangeLog {

    /** Nights kept in the file. */
    static final int NIGHTS = 30;

    /** Records listed per kind and night; the counts stay exact. */
    static final int LISTED = 200;

    private static final Logger log = LoggerFactory.getLogger(ChangeLog.class);

    /**
     * A record that appeared, left or changed.
     *
     * @param key the record's snapshot key, {@code SOURCE/id}
     * @param name the record's primary name, {@code null} when the previous file did not keep it
     * @param type the type code of the search index, {@code null} when not kept
     * @param countries the record's countries as ISO codes, empty when unknown
     * @param programs the record's program codes, empty when unknown
     */
    record Entry(
            String key, String name, String type, List<String> countries, List<String> programs) {

        Entry {
            Objects.requireNonNull(key, "key must not be null");
            countries = countries == null ? List.of() : List.copyOf(countries);
            programs = programs == null ? List.of() : List.copyOf(programs);
        }

        String source() {
            int slash = key.indexOf('/');
            return slash < 0 ? key : key.substring(0, slash);
        }
    }

    private ChangeLog() {}

    /**
     * Adds tonight's changes to the file, replacing a night of the same date, and drops nights
     * older than the last {@value #NIGHTS}.
     *
     * @param mapper writes the JSON
     * @param file the change log, read if it exists
     * @param now the snapshot time
     * @param added records first seen tonight
     * @param removed records of loaded lists seen before and no longer published
     * @param changed records seen before whose content changed tonight
     * @throws IOException when the file cannot be written
     */
    static void write(
            ObjectMapper mapper,
            Path file,
            Instant now,
            List<Entry> added,
            List<Entry> removed,
            List<Entry> changed)
            throws IOException {
        List<JsonNode> nights = new ArrayList<>();
        if (Files.exists(file)) {
            try {
                JsonNode root = mapper.readTree(file.toFile());
                int digestVersion = root.path("digestVersion").asInt(1);
                if (digestVersion != SnapshotWriter.DIGEST_VERSION) {
                    log.info(
                            "Dropping nights recorded with an older digest [file={},"
                                    + " digestVersion={}]",
                            file,
                            digestVersion);
                } else {
                    for (JsonNode night : root.path("nights")) {
                        if (night.hasNonNull("date")) {
                            nights.add(night);
                        }
                    }
                }
            } catch (IOException e) {
                log.warn("Ignoring unreadable change log [file={}]", file, e);
            }
        }
        String today = LocalDate.ofInstant(now, ZoneOffset.UTC).toString();
        Map<String, Object> night =
                nights.stream()
                        .filter(n -> today.equals(n.path("date").asText()))
                        .findFirst()
                        .map(earlier -> merged(earlier, today, added, removed, changed))
                        .orElseGet(() -> night(today, added, removed, changed));
        nights.removeIf(n -> today.equals(n.path("date").asText()));
        // Through bytes rather than valueToTree, which needs a newer jackson-core than the
        // classpath has
        nights.add(mapper.readTree(mapper.writeValueAsBytes(night)));
        nights.sort(Comparator.comparing(n -> n.path("date").asText()));
        while (nights.size() > NIGHTS) {
            nights.removeFirst();
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("formatVersion", SnapshotWriter.FORMAT_VERSION);
        out.put("digestVersion", SnapshotWriter.DIGEST_VERSION);
        out.put("generatedAt", now);
        out.put("nights", nights);
        mapper.writeValue(file.toFile(), out);
        log.info(
                "Change log written [file={}, nights={}, added={}, removed={}, changed={}]",
                file,
                nights.size(),
                added.size(),
                removed.size(),
                changed.size());
    }

    private static Map<String, Object> night(
            String date, List<Entry> added, List<Entry> removed, List<Entry> changed) {
        Map<String, List<Integer>> bySource = new TreeMap<>();
        count(bySource, added, 0);
        count(bySource, removed, 1);
        count(bySource, changed, 2);

        Map<String, Object> records = new LinkedHashMap<>();
        records.put("added", listed(added));
        records.put("removed", listed(removed));
        records.put("changed", listed(changed));

        Map<String, Object> night = new LinkedHashMap<>();
        night.put("date", date);
        night.put("added", added.size());
        night.put("removed", removed.size());
        night.put("changed", changed.size());
        night.put("bySource", bySource);
        night.put("records", records);
        return night;
    }

    /**
     * Tonight's changes added to an earlier run of the same day: a record that appeared in the
     * earlier run and left in this one was never there at the day's end, and a changed record that
     * is new today is just new. When the earlier run listed every record, the counts come from the
     * merged lists; otherwise they are the two runs' counts added up.
     */
    private static Map<String, Object> merged(
            JsonNode earlier,
            String today,
            List<Entry> added,
            List<Entry> removed,
            List<Entry> changed) {
        Map<String, Entry> a = byKey(entries(earlier, "added"));
        Map<String, Entry> r = byKey(entries(earlier, "removed"));
        Map<String, Entry> c = byKey(entries(earlier, "changed"));
        boolean exact =
                a.size() == earlier.path("added").asInt()
                        && r.size() == earlier.path("removed").asInt()
                        && c.size() == earlier.path("changed").asInt();
        added.forEach(e -> a.put(e.key(), e));
        removed.forEach(e -> r.put(e.key(), e));
        changed.forEach(e -> c.put(e.key(), e));
        for (String key : new ArrayList<>(a.keySet())) {
            if (r.containsKey(key)) {
                a.remove(key);
                r.remove(key);
            }
        }
        c.keySet().removeAll(a.keySet());

        Map<String, Object> night =
                night(
                        today,
                        new ArrayList<>(a.values()),
                        new ArrayList<>(r.values()),
                        new ArrayList<>(c.values()));
        if (!exact) {
            night.put("added", earlier.path("added").asInt() + added.size());
            night.put("removed", earlier.path("removed").asInt() + removed.size());
            night.put("changed", earlier.path("changed").asInt() + changed.size());
            Map<String, List<Integer>> bySource = new TreeMap<>();
            earlier.path("bySource")
                    .fields()
                    .forEachRemaining(
                            f -> {
                                List<Integer> counts = new ArrayList<>(List.of(0, 0, 0));
                                for (int i = 0; i < 3 && i < f.getValue().size(); i++) {
                                    counts.set(i, f.getValue().get(i).asInt());
                                }
                                bySource.put(f.getKey(), counts);
                            });
            count(bySource, added, 0);
            count(bySource, removed, 1);
            count(bySource, changed, 2);
            night.put("bySource", bySource);
        }
        return night;
    }

    private static Map<String, Entry> byKey(List<Entry> entries) {
        Map<String, Entry> byKey = new LinkedHashMap<>();
        entries.forEach(e -> byKey.put(e.key(), e));
        return byKey;
    }

    /** The records a night lists under one kind, read back from the file. */
    private static List<Entry> entries(JsonNode night, String kind) {
        List<Entry> entries = new ArrayList<>();
        for (JsonNode row : night.path("records").path(kind)) {
            if (!row.hasNonNull("k")) {
                continue;
            }
            entries.add(
                    new Entry(
                            row.get("k").asText(),
                            row.hasNonNull("n") ? row.get("n").asText() : null,
                            row.hasNonNull("t") ? row.get("t").asText() : null,
                            texts(row.path("c")),
                            texts(row.path("p"))));
        }
        return entries;
    }

    private static List<String> texts(JsonNode array) {
        List<String> texts = new ArrayList<>();
        array.forEach(v -> texts.add(v.asText()));
        return texts;
    }

    private static void count(Map<String, List<Integer>> bySource, List<Entry> entries, int at) {
        for (Entry entry : entries) {
            List<Integer> counts =
                    bySource.computeIfAbsent(
                            entry.source(), s -> new ArrayList<>(List.of(0, 0, 0)));
            counts.set(at, counts.get(at) + 1);
        }
    }

    /** The first {@value #LISTED} entries in list order and then by name, as JSON rows. */
    private static List<Map<String, Object>> listed(List<Entry> entries) {
        List<Map<String, Object>> rows = new ArrayList<>();
        entries.stream()
                .sorted(
                        Comparator.comparingInt((Entry e) -> listOrder(e.source()))
                                .thenComparing(e -> e.name() == null ? "" : e.name())
                                .thenComparing(Entry::key))
                .limit(LISTED)
                .forEach(
                        e -> {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("k", e.key());
                            if (e.name() != null) {
                                row.put("n", e.name());
                            }
                            if (e.type() != null) {
                                row.put("t", e.type());
                            }
                            if (!e.countries().isEmpty()) {
                                row.put("c", e.countries());
                            }
                            if (!e.programs().isEmpty()) {
                                row.put("p", e.programs());
                            }
                            rows.add(row);
                        });
        return rows;
    }

    /** A list's position in {@link ListSource} order; a list the enum no longer has sorts last. */
    private static int listOrder(String source) {
        try {
            return ListSource.valueOf(source).ordinal();
        } catch (IllegalArgumentException e) {
            return Integer.MAX_VALUE;
        }
    }
}
