package dev.sieve.core.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class GenderTest {

    @ParameterizedTest
    @CsvSource({
        "M, MALE",
        "Male, MALE",
        "male, MALE",
        "F, FEMALE",
        "Female, FEMALE",
        " female , FEMALE",
        "X, OTHER",
        "Other, OTHER"
    })
    void shouldReadTheWordsListsUse(String value, Gender expected) {
        assertThat(Gender.parse(value)).contains(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"Unknown", "n/a", "-", "   "})
    void shouldGiveNothingWhenTheListStatesNoGender(String value) {
        assertThat(Gender.parse(value)).isEmpty();
    }
}
