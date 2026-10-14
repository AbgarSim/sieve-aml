package dev.sieve.risk;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * The thresholds the behavioural rules use. Amounts are compared in each transaction's own
 * currency, so a subject's transactions are expected to share one currency.
 *
 * @param velocityCount how many transactions inside {@code velocityWindow} count as unusual
 * @param velocityWindow the window for the velocity rule
 * @param reportingThreshold the amount at which a transaction must be reported, such as the US
 *     10,000 currency transaction report threshold
 * @param structuringBand the share of the reporting threshold above which an amount counts as just
 *     under it, such as 0.9 for 9,000 to 9,999.99
 * @param structuringCount how many just-under amounts inside {@code structuringWindow} count as
 *     structuring
 * @param structuringWindow the window for the structuring rule
 * @param roundUnit the unit a round amount is a multiple of
 * @param roundShare the share of round amounts that counts as unusual
 * @param roundMinCount the fewest round amounts the round amount rule needs
 * @param highRiskCountries ISO 3166-1 alpha-2 codes of high-risk jurisdictions
 * @param passThroughWindow how soon after money comes in it must go out to count as passing through
 * @param passThroughShare the share of an incoming amount that must go out again
 */
public record RiskConfig(
        int velocityCount,
        Duration velocityWindow,
        BigDecimal reportingThreshold,
        double structuringBand,
        int structuringCount,
        Duration structuringWindow,
        BigDecimal roundUnit,
        double roundShare,
        int roundMinCount,
        Set<String> highRiskCountries,
        Duration passThroughWindow,
        double passThroughShare) {

    /**
     * The jurisdictions FATF names as subject to a call for action (North Korea, Iran and Myanmar
     * at the time of writing).
     */
    public static final Set<String> FATF_CALL_FOR_ACTION = Set.of("KP", "IR", "MM");

    /**
     * Compact constructor with validation and a defensive copy.
     *
     * @throws NullPointerException if any object parameter is {@code null}
     * @throws IllegalArgumentException if a count is below 1 or a share is outside (0, 1]
     */
    public RiskConfig {
        Objects.requireNonNull(velocityWindow, "velocityWindow must not be null");
        Objects.requireNonNull(reportingThreshold, "reportingThreshold must not be null");
        Objects.requireNonNull(structuringWindow, "structuringWindow must not be null");
        Objects.requireNonNull(roundUnit, "roundUnit must not be null");
        Objects.requireNonNull(passThroughWindow, "passThroughWindow must not be null");
        highRiskCountries =
                Set.copyOf(
                        Objects.requireNonNull(
                                highRiskCountries, "highRiskCountries must not be null"));
        if (velocityCount < 1 || structuringCount < 1 || roundMinCount < 1) {
            throw new IllegalArgumentException("counts must be at least 1");
        }
        requireShare(structuringBand, "structuringBand");
        requireShare(roundShare, "roundShare");
        requireShare(passThroughShare, "passThroughShare");
    }

    /**
     * The defaults: 10 transactions in 24 hours, 3 amounts from 9,000 to just under 10,000 in 7
     * days, half or more of at least 3 amounts in whole thousands, FATF call-for-action countries,
     * and 90% of an incoming amount sent on within 48 hours.
     *
     * @return the default configuration
     */
    public static RiskConfig defaults() {
        return new RiskConfig(
                10,
                Duration.ofHours(24),
                new BigDecimal("10000"),
                0.9,
                3,
                Duration.ofDays(7),
                new BigDecimal("1000"),
                0.5,
                3,
                FATF_CALL_FOR_ACTION,
                Duration.ofHours(48),
                0.9);
    }

    private static void requireShare(double share, String name) {
        if (share <= 0.0 || share > 1.0) {
            throw new IllegalArgumentException(name + " must be in (0, 1]");
        }
    }
}
