package dev.sieve.risk;

import java.util.Optional;

/** Screens a counterparty's name against the lists, so dealings with listed parties count. */
@FunctionalInterface
public interface CounterpartyScreener {

    /**
     * Screens one name.
     *
     * @param name the counterparty's name
     * @return the best match at or above the screener's threshold, or empty
     */
    Optional<CounterpartyHit> screen(String name);

    /**
     * A screener that matches nothing, for assessing behaviour alone.
     *
     * @return the screener
     */
    static CounterpartyScreener none() {
        return name -> Optional.empty();
    }
}
