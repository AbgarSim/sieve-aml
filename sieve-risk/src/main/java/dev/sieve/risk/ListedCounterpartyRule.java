package dev.sieve.risk;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Fires when a counterparty's name matches a listed entity. */
final class ListedCounterpartyRule implements RiskRule {

    static final String ID = "listed-counterparty";
    static final int POINTS = 60;

    private final CounterpartyScreener screener;

    ListedCounterpartyRule(CounterpartyScreener screener) {
        this.screener = screener;
    }

    @Override
    public Optional<RiskSignal> evaluate(List<Transaction> transactions) {
        Set<String> names = new LinkedHashSet<>();
        for (Transaction t : transactions) {
            names.add(t.counterparty().trim());
        }
        List<String> hits = new ArrayList<>();
        for (String name : names) {
            screener.screen(name)
                    .ifPresent(
                            hit ->
                                    hits.add(
                                            String.format(
                                                    "%s matches %s on %s (%.2f)",
                                                    hit.counterparty(),
                                                    hit.matchedName(),
                                                    hit.source(),
                                                    hit.score())));
        }
        if (hits.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new RiskSignal(ID, POINTS, String.join("; ", hits)));
    }
}
