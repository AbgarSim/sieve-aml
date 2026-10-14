package dev.sieve.risk;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** Fires when a counterparty is in a high-risk jurisdiction. */
final class HighRiskJurisdictionRule implements RiskRule {

    static final String ID = "high-risk-jurisdiction";
    static final int POINTS = 30;

    private final Set<String> countries;

    HighRiskJurisdictionRule(Set<String> countries) {
        this.countries = countries;
    }

    @Override
    public Optional<RiskSignal> evaluate(List<Transaction> transactions) {
        Set<String> seen = new TreeSet<>();
        int count = 0;
        for (Transaction t : transactions) {
            Optional<String> country =
                    t.counterpartyCountry().map(c -> c.trim().toUpperCase(Locale.ROOT));
            if (country.isPresent() && countries.contains(country.get())) {
                seen.add(country.get());
                count++;
            }
        }
        if (count == 0) {
            return Optional.empty();
        }
        return Optional.of(
                new RiskSignal(
                        ID,
                        POINTS,
                        count
                                + " transaction(s) with counterparties in "
                                + String.join(", ", seen)));
    }
}
