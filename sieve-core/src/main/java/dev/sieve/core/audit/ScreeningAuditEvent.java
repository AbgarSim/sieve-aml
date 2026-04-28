package dev.sieve.core.audit;

import dev.sieve.core.match.MatchResult;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Immutable audit event emitted after every screening decision.
 *
 * <p>Captures the full context of a screening operation: who was screened, against which list
 * version, what matches were found, and how they were dispositioned. This is the core domain event
 * that downstream consumers (database writers, message queues, log aggregators) use to build an
 * immutable compliance audit trail.
 *
 * <p>In AML contexts, regulators require that every screening decision be traceable — including "no
 * match" outcomes. This record satisfies that requirement.
 *
 * @param eventId unique identifier for this audit event
 * @param screenedName the name that was screened
 * @param threshold the match threshold used for this screening
 * @param matchCount number of matches found at or above the threshold
 * @param topMatches the top N match results (capped at a configurable limit)
 * @param outcome the screening outcome (MATCH, NO_MATCH, ERROR)
 * @param screenedAt timestamp when the screening was performed
 * @param durationMs time taken to perform the screening in milliseconds
 * @param engineAlgorithms the match algorithms that were invoked
 * @param listVersionId identifier of the sanctions list version used, may be {@code null}
 */
public record ScreeningAuditEvent(
        String eventId,
        String screenedName,
        double threshold,
        int matchCount,
        List<AuditMatchEntry> topMatches,
        Outcome outcome,
        Instant screenedAt,
        long durationMs,
        List<String> engineAlgorithms,
        String listVersionId) {

    public ScreeningAuditEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(screenedName, "screenedName must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(screenedAt, "screenedAt must not be null");
        topMatches = topMatches == null ? List.of() : List.copyOf(topMatches);
        engineAlgorithms = engineAlgorithms == null ? List.of() : List.copyOf(engineAlgorithms);
    }

    /** The outcome of a screening operation. */
    public enum Outcome {
        /** At least one match was found above the threshold. */
        MATCH,
        /** No matches were found above the threshold. */
        NO_MATCH,
        /** The screening operation failed with an error. */
        ERROR
    }

    /**
     * A single match entry within an audit event, containing only the fields needed for audit.
     *
     * @param entityId the matched entity's ID
     * @param entityName the matched entity's primary name
     * @param listSource the sanctions list the entity belongs to
     * @param score the match score
     * @param matchedField the field that produced the match
     * @param matchAlgorithm the algorithm that produced the match
     */
    public record AuditMatchEntry(
            String entityId,
            String entityName,
            String listSource,
            double score,
            String matchedField,
            String matchAlgorithm) {

        public AuditMatchEntry {
            Objects.requireNonNull(entityId, "entityId must not be null");
            Objects.requireNonNull(entityName, "entityName must not be null");
            Objects.requireNonNull(listSource, "listSource must not be null");
        }

        /** Creates an audit entry from a domain {@link MatchResult}. */
        public static AuditMatchEntry from(MatchResult result) {
            return new AuditMatchEntry(
                    result.entity().id(),
                    result.entity().primaryName().fullName(),
                    result.entity().listSource().name(),
                    result.score(),
                    result.matchedField(),
                    result.matchAlgorithm());
        }
    }
}
