package dev.sieve.match.algorithm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DoubleMetaphoneTest {

    @Test
    void shouldReturnEmptyCodesForNullInput() {
        DoubleMetaphone.PhoneticCode code = DoubleMetaphone.encode(null);
        assertThat(code.primary()).isEmpty();
        assertThat(code.alternate()).isEmpty();
    }

    @Test
    void shouldReturnEmptyCodesForEmptyInput() {
        DoubleMetaphone.PhoneticCode code = DoubleMetaphone.encode("");
        assertThat(code.primary()).isEmpty();
        assertThat(code.alternate()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "Smith, SM0",
        "Schmidt, XMT",
        "John, JN",
        "Jon, JN",
        "Robert, RPRT",
    })
    void shouldEncodeCommonNames(String input, String expectedPrimary) {
        DoubleMetaphone.PhoneticCode code = DoubleMetaphone.encode(input);
        assertThat(code.primary()).isEqualTo(expectedPrimary);
    }

    @ParameterizedTest
    @CsvSource({
        "Gaddafi, Qadhafi",
        "Smith, Schmidt",
        "John, Jon",
        "Catherine, Katherine",
        "Phillip, Philip",
    })
    void shouldMatchPhoneticVariants(String name1, String name2) {
        assertThat(DoubleMetaphone.isMatch(name1, name2))
                .as("'%s' should phonetically match '%s'", name1, name2)
                .isTrue();
    }

    @ParameterizedTest
    @CsvSource({
        "Smith, Jones",
        "Robert, Mary",
        "John, Mary",
    })
    void shouldNotMatchDifferentNames(String name1, String name2) {
        assertThat(DoubleMetaphone.isMatch(name1, name2))
                .as("'%s' should NOT phonetically match '%s'", name1, name2)
                .isFalse();
    }

    @Test
    void shouldProducePrimaryAndAlternateCodes() {
        DoubleMetaphone.PhoneticCode code = DoubleMetaphone.encode("Michael");
        assertThat(code.primary()).isNotEmpty();
        assertThat(code.alternate()).isNotEmpty();
    }

    @Test
    void shouldHandleSingleCharacterInput() {
        DoubleMetaphone.PhoneticCode code = DoubleMetaphone.encode("A");
        assertThat(code.primary()).isEqualTo("A");
    }

    @Test
    void shouldNotMatchNullInputs() {
        assertThat(DoubleMetaphone.isMatch(null, "test")).isFalse();
        assertThat(DoubleMetaphone.isMatch("test", null)).isFalse();
        assertThat(DoubleMetaphone.isMatch(null, null)).isFalse();
    }
}
