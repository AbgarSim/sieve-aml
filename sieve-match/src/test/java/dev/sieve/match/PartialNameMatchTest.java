package dev.sieve.match;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PartialNameMatchTest {

    @Test
    void shouldCountTokensSeparatedByWhitespaceCommasAndHyphens() {
        assertThat(PartialNameMatch.tokenCount("putin, vladimir vladimirovich")).isEqualTo(3);
        assertThat(PartialNameMatch.tokenCount("kim jong-un")).isEqualTo(3);
        assertThat(PartialNameMatch.tokenCount("hamas")).isEqualTo(1);
    }

    @Test
    void shouldCountZeroTokensWhenNameIsEmptyOrNull() {
        assertThat(PartialNameMatch.tokenCount("")).isZero();
        assertThat(PartialNameMatch.tokenCount(" , ")).isZero();
        assertThat(PartialNameMatch.tokenCount(null)).isZero();
    }

    @Test
    void shouldTreatSingleTokenQueryAgainstLongerNameAsLoneToken() {
        assertThat(PartialNameMatch.isLoneToken(1, "vladimir putin")).isTrue();
        assertThat(PartialNameMatch.isLoneToken(1, "hamas")).isFalse();
        assertThat(PartialNameMatch.isLoneToken(2, "putin, vladimir vladimirovich")).isFalse();
    }

    @Test
    void shouldDiscountScoreByFactor() {
        assertThat(PartialNameMatch.discount(1.0)).isEqualTo(PartialNameMatch.FACTOR);
    }
}
