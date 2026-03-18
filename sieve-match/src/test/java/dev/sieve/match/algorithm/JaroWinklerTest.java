package dev.sieve.match.algorithm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class JaroWinklerTest {

    private static final double TOLERANCE = 0.001;

    @ParameterizedTest(name = "similarity(\"{0}\", \"{1}\") ≈ {2}")
    @CsvSource({
        "martha, marhta, 0.961",
        "DWAYNE, DUANE, 0.840",
        "DIXON, DICKSONX, 0.813",
    })
    void shouldMatchKnownTestVectors(String s1, String s2, double expectedScore) {
        double score = JaroWinkler.similarity(s1, s2);
        assertThat(score).isCloseTo(expectedScore, within(TOLERANCE));
    }

    @Test
    void shouldReturnOneForIdenticalStrings() {
        assertThat(JaroWinkler.similarity("hello", "hello")).isEqualTo(1.0);
    }

    @Test
    void shouldReturnOneForBothEmpty() {
        assertThat(JaroWinkler.similarity("", "")).isEqualTo(1.0);
    }

    @Test
    void shouldReturnZeroWhenOneIsEmpty() {
        assertThat(JaroWinkler.similarity("hello", "")).isEqualTo(0.0);
        assertThat(JaroWinkler.similarity("", "hello")).isEqualTo(0.0);
    }

    @Test
    void shouldReturnZeroForNullInputs() {
        assertThat(JaroWinkler.similarity(null, "hello")).isEqualTo(0.0);
        assertThat(JaroWinkler.similarity("hello", null)).isEqualTo(0.0);
        assertThat(JaroWinkler.similarity(null, null)).isEqualTo(0.0);
    }

    @Test
    void shouldReturnZeroForCompletelyDifferentStrings() {
        double score = JaroWinkler.similarity("abc", "xyz");
        assertThat(score).isEqualTo(0.0);
    }

    @Test
    void shouldHandleSingleCharacterStrings() {
        assertThat(JaroWinkler.similarity("a", "a")).isEqualTo(1.0);
        assertThat(JaroWinkler.similarity("a", "b")).isEqualTo(0.0);
    }

    @Test
    void shouldBeSymmetric() {
        double forward = JaroWinkler.similarity("martha", "marhta");
        double backward = JaroWinkler.similarity("marhta", "martha");
        assertThat(forward).isCloseTo(backward, within(0.0001));
    }

    @Test
    void shouldScoreHigherWithCommonPrefix() {
        double withPrefix = JaroWinkler.similarity("prefix_abc", "prefix_xyz");
        double withoutPrefix = JaroWinkler.similarity("abc_prefix", "xyz_prefix");
        assertThat(withPrefix).isGreaterThanOrEqualTo(withoutPrefix);
    }

    @Test
    void shouldHandleUnicodeCharacters() {
        assertThat(JaroWinkler.similarity("café", "café")).isEqualTo(1.0);
        double score = JaroWinkler.similarity("café", "cafe");
        assertThat(score).isGreaterThan(0.8);
    }

    @Test
    void shouldProduceScoreBetweenZeroAndOne() {
        double score = JaroWinkler.similarity("Robert", "Rupert");
        assertThat(score).isBetween(0.0, 1.0);
    }

    @Test
    void shouldHandleReversedNames() {
        double score = JaroWinkler.similarity("John Smith", "Smith John");
        assertThat(score).isGreaterThan(0.5);
        assertThat(score).isLessThan(1.0);
    }

    @Test
    void shouldProduceHighScoreForCloseNames() {
        double score = JaroWinkler.similarity("Jonathan", "Jonathon");
        assertThat(score).isGreaterThan(0.9);
    }

    @Test
    void jaroShouldReturnOneForIdentical() {
        assertThat(JaroWinkler.jaro("test", "test")).isEqualTo(1.0);
    }

    @Test
    void jaroShouldReturnOneForBothEmpty() {
        assertThat(JaroWinkler.jaro("", "")).isEqualTo(1.0);
    }
}
