package dev.sieve.core.provenance;

import dev.sieve.core.model.Provenance;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.SourcedValue;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Records where and when each value of a freshly fetched entity was seen.
 *
 * <p>Every value gets the entity's list as its source and the fetch time as its last seen time. Its
 * first seen time is carried over from the previous version of the entity when that version held
 * the same value, so a passport number listed in 2019 keeps 2019 as first seen across refreshes; a
 * value seen for the first time gets the fetch time. A source URL or first seen time the provider
 * already set on a value is kept.
 */
public final class ProvenanceStamper {

    private ProvenanceStamper() {}

    /**
     * Stamps every value of an entity.
     *
     * @param current the entity as just fetched, must not be {@code null}
     * @param previous the entity as held before this fetch, or empty if it is new
     * @param sourceUrl the address the list was fetched from, may be {@code null}
     * @param seenAt when the list was fetched, must not be {@code null}
     * @return the entity with one provenance entry per value
     */
    public static SanctionedEntity stamp(
            SanctionedEntity current,
            Optional<SanctionedEntity> previous,
            String sourceUrl,
            Instant seenAt) {
        Objects.requireNonNull(current, "current must not be null");
        Objects.requireNonNull(previous, "previous must not be null");
        Objects.requireNonNull(seenAt, "seenAt must not be null");
        List<SourcedValue> stamped = new ArrayList<>();
        for (SourcedValue.Key key : SourcedValue.keysOf(current)) {
            Optional<Provenance> own = current.provenanceOf(key);
            Optional<Provenance> before = previous.flatMap(p -> p.provenanceOf(key));
            Instant firstSeen = seenAt;
            for (Optional<Provenance> known : List.of(own, before)) {
                if (known.isPresent() && known.get().firstSeen().isBefore(firstSeen)) {
                    firstSeen = known.get().firstSeen();
                }
            }
            String url = own.map(Provenance::sourceUrl).filter(u -> !u.isBlank()).orElse(sourceUrl);
            stamped.add(
                    new SourcedValue(
                            key.kind(),
                            key.value(),
                            new Provenance(current.listSource(), url, firstSeen, seenAt)));
        }
        return current.withProvenance(stamped);
    }

    /**
     * Returns when any value of the entity was first seen.
     *
     * @param entity the entity
     * @return the earliest first seen time, or empty if the entity has no provenance
     */
    public static Optional<Instant> firstSeen(SanctionedEntity entity) {
        return entity.provenance().stream()
                .map(v -> v.provenance().firstSeen())
                .min(Instant::compareTo);
    }

    /**
     * Returns when any value of the entity was last seen.
     *
     * @param entity the entity
     * @return the latest last seen time, or empty if the entity has no provenance
     */
    public static Optional<Instant> lastSeen(SanctionedEntity entity) {
        return entity.provenance().stream()
                .map(v -> v.provenance().lastSeen())
                .max(Instant::compareTo);
    }
}
