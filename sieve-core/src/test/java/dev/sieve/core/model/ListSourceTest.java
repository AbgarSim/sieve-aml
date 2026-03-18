package dev.sieve.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class ListSourceTest {

    @ParameterizedTest
    @CsvSource({
        "OFAC_SDN, OFAC SDN",
        "EU_CONSOLIDATED, EU Consolidated",
        "UN_CONSOLIDATED, UN Consolidated",
        "UK_HMT, UK HMT"
    })
    void shouldReturnCorrectDisplayName(String enumName, String expectedDisplay) {
        ListSource source = ListSource.valueOf(enumName);
        assertThat(source.displayName()).isEqualTo(expectedDisplay);
    }

    @ParameterizedTest
    @CsvSource({
        "OFAC_SDN, OFAC_SDN",
        "ofac_sdn, OFAC_SDN",
        "ofac-sdn, OFAC_SDN",
        "OFAC SDN, OFAC_SDN",
        "EU_CONSOLIDATED, EU_CONSOLIDATED",
        "eu-consolidated, EU_CONSOLIDATED",
        "UK_HMT, UK_HMT",
        "uk-hmt, UK_HMT"
    })
    void shouldResolveFromString(String input, String expectedName) {
        assertThat(ListSource.fromString(input)).isEqualTo(ListSource.valueOf(expectedName));
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "OFAC", ""})
    void shouldThrowForUnknownValue(String value) {
        assertThatThrownBy(() -> ListSource.fromString(value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown ListSource");
    }

    @ParameterizedTest
    @NullSource
    void shouldThrowForNullValue(String value) {
        assertThatThrownBy(() -> ListSource.fromString(value))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldHaveFourValues() {
        assertThat(ListSource.values()).hasSize(4);
    }
}
