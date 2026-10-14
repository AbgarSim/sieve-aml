package dev.sieve.risk;

/** The band a behavioural risk score falls in. */
public enum RiskLevel {
    /** Below 30: nothing that calls for review. */
    LOW,
    /** 30 to 59: worth an analyst's look. */
    MEDIUM,
    /** 60 and above: review before the relationship continues. */
    HIGH;

    /**
     * Returns the band of a score.
     *
     * @param score a score from 0 to 100
     * @return the band
     */
    public static RiskLevel of(int score) {
        if (score >= 60) {
            return HIGH;
        }
        return score >= 30 ? MEDIUM : LOW;
    }
}
