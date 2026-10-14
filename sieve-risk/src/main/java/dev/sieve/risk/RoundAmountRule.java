package dev.sieve.risk;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** Fires when a large share of the amounts are whole multiples of a round unit. */
final class RoundAmountRule implements RiskRule {

    static final String ID = "round-amounts";
    static final int POINTS = 10;

    private final BigDecimal unit;
    private final double share;
    private final int minCount;

    RoundAmountRule(BigDecimal unit, double share, int minCount) {
        this.unit = unit;
        this.share = share;
        this.minCount = minCount;
    }

    @Override
    public Optional<RiskSignal> evaluate(List<Transaction> transactions) {
        long round =
                transactions.stream()
                        .map(Transaction::amount)
                        .filter(a -> a.compareTo(unit) >= 0)
                        .filter(a -> a.remainder(unit).signum() == 0)
                        .count();
        if (round < minCount || round < share * transactions.size()) {
            return Optional.empty();
        }
        return Optional.of(
                new RiskSignal(
                        ID,
                        POINTS,
                        round
                                + " of "
                                + transactions.size()
                                + " amounts are multiples of "
                                + unit.stripTrailingZeros().toPlainString()));
    }
}
