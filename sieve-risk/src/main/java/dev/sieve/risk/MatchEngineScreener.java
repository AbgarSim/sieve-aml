package dev.sieve.risk;

import dev.sieve.core.index.EntityIndex;
import dev.sieve.core.match.MatchEngine;
import dev.sieve.core.match.MatchResult;
import dev.sieve.core.match.ScreeningRequest;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Screens counterparties with a Sieve {@link MatchEngine} against an {@link EntityIndex}. */
public final class MatchEngineScreener implements CounterpartyScreener {

    private final MatchEngine engine;
    private final EntityIndex index;
    private final double threshold;

    /**
     * Creates a screener.
     *
     * @param engine the match engine
     * @param index the listed entities
     * @param threshold the lowest score that counts as a hit, from 0.0 to 1.0
     */
    public MatchEngineScreener(MatchEngine engine, EntityIndex index, double threshold) {
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
        this.index = Objects.requireNonNull(index, "index must not be null");
        if (threshold < 0.0 || threshold > 1.0) {
            throw new IllegalArgumentException("threshold must be between 0.0 and 1.0");
        }
        this.threshold = threshold;
    }

    @Override
    public Optional<CounterpartyHit> screen(String name) {
        if (name.isBlank()) {
            return Optional.empty();
        }
        List<MatchResult> results = engine.screen(ScreeningRequest.of(name, threshold), index);
        return results.stream()
                .sorted()
                .findFirst()
                .map(
                        best ->
                                new CounterpartyHit(
                                        name,
                                        best.entity().primaryName().fullName(),
                                        best.entity().listSource().name(),
                                        best.score()));
    }
}
