package dev.sieve.beam;

import dev.sieve.risk.BehaviouralRiskScorer;
import java.io.Serializable;

/**
 * Builds the scorer on each worker. The scorer itself holds an index and match engine that are not
 * serializable, so the pipeline ships this factory instead and calls it once per worker.
 */
@FunctionalInterface
public interface ScorerFactory extends Serializable {

    /**
     * Builds a scorer.
     *
     * @return the scorer
     */
    BehaviouralRiskScorer create();
}
