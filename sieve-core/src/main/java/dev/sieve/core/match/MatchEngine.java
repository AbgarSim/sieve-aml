package dev.sieve.core.match;

import dev.sieve.core.index.EntityIndex;
import java.util.List;

/**
 * Service Provider Interface for sanctions screening match engines.
 *
 * <p>Implementations encapsulate a specific matching algorithm (e.g., exact match, fuzzy match,
 * phonetic match) and produce scored results against the entities in an {@link EntityIndex}.
 */
public interface MatchEngine {

    /**
     * Screens the given request against all applicable entities in the index.
     *
     * <p>Results are filtered by the request's threshold and optional entity type / source filters,
     * then returned in descending score order.
     *
     * @param request the screening request containing the name and filters
     * @param index the entity index to screen against
     * @return matching results sorted by score descending, never {@code null}
     */
    List<MatchResult> screen(ScreeningRequest request, EntityIndex index);
}
