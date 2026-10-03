package dev.sieve.core.model;

import java.time.LocalDate;
import java.util.Objects;

/**
 * A directed link from the entity that holds it to another entity.
 *
 * <p>For {@link RelationType#OWNERSHIP} and {@link RelationType#DIRECTORSHIP} the holder is the
 * owner or director and the target is the company. Family and associate links are symmetric, but
 * are stored on the entity whose source states them.
 *
 * @param type the kind of link
 * @param targetId the id of the linked entity, which may come from another list
 * @param role the link as the source words it (e.g. "spouse", "sole shareholder"), may be {@code
 *     null}
 * @param sharePercentage the ownership share from 0 to 100, {@code null} if unknown or not an
 *     ownership
 * @param startDate when the link began, may be {@code null}
 * @param endDate when the link ended, {@code null} if ongoing or unknown
 */
public record Relation(
        RelationType type,
        String targetId,
        String role,
        Double sharePercentage,
        LocalDate startDate,
        LocalDate endDate) {

    /**
     * Compact constructor with validation.
     *
     * @throws NullPointerException if {@code type} or {@code targetId} is {@code null}
     * @throws IllegalArgumentException if the share is outside 0 to 100 or the dates are reversed
     */
    public Relation {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(targetId, "targetId must not be null");
        if (targetId.isBlank()) {
            throw new IllegalArgumentException("targetId must not be blank");
        }
        if (sharePercentage != null && (sharePercentage < 0.0 || sharePercentage > 100.0)) {
            throw new IllegalArgumentException("sharePercentage must be between 0 and 100");
        }
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("endDate must not be before startDate");
        }
    }

    /**
     * Creates a link with only a type and a target.
     *
     * @param type the kind of link
     * @param targetId the id of the linked entity
     * @return the relation
     */
    public static Relation of(RelationType type, String targetId) {
        return new Relation(type, targetId, null, null, null, null);
    }
}
