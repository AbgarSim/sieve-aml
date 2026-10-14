package dev.sieve.risk;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Fires when money that comes in goes straight out again: the outgoing amounts within the window
 * after an incoming one add up to most of it.
 */
final class PassThroughRule implements RiskRule {

    static final String ID = "pass-through";
    static final int POINTS = 25;

    private final Duration window;
    private final BigDecimal share;

    PassThroughRule(Duration window, double share) {
        this.window = window;
        this.share = BigDecimal.valueOf(share);
    }

    @Override
    public Optional<RiskSignal> evaluate(List<Transaction> transactions) {
        int passedThrough = 0;
        for (int i = 0; i < transactions.size(); i++) {
            Transaction in = transactions.get(i);
            if (in.direction() != Direction.IN) {
                continue;
            }
            Instant until = in.timestamp().plus(window);
            BigDecimal out = BigDecimal.ZERO;
            for (int j = i + 1; j < transactions.size(); j++) {
                Transaction next = transactions.get(j);
                if (next.timestamp().isAfter(until)) {
                    break;
                }
                if (next.direction() == Direction.OUT) {
                    out = out.add(next.amount());
                }
            }
            if (out.compareTo(in.amount().multiply(share)) >= 0) {
                passedThrough++;
            }
        }
        if (passedThrough == 0) {
            return Optional.empty();
        }
        return Optional.of(
                new RiskSignal(
                        ID,
                        POINTS,
                        passedThrough
                                + " incoming amount(s) sent on within "
                                + Windows.describe(window)));
    }
}
