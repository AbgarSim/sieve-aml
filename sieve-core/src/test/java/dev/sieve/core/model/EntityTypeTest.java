package dev.sieve.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class EntityTypeTest {

    @ParameterizedTest
    @CsvSource({
        "INDIVIDUAL, Individual",
        "ENTITY, Entity",
        "VESSEL, Vessel",
        "AIRCRAFT, Aircraft"
    })
    void shouldReturnCorrectDisplayName(String enumName, String expectedDisplay) {
        EntityType type = EntityType.valueOf(enumName);
        assertThat(type.displayName()).isEqualTo(expectedDisplay);
    }

    @ParameterizedTest
    @CsvSource({
        "INDIVIDUAL, INDIVIDUAL",
        "individual, INDIVIDUAL",
        "Individual, INDIVIDUAL",
        "ENTITY, ENTITY",
        "entity, ENTITY",
        "vessel, VESSEL",
        "Aircraft, AIRCRAFT"
    })
    void shouldResolveFromString(String input, String expectedName) {
        assertThat(EntityType.fromString(input)).isEqualTo(EntityType.valueOf(expectedName));
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "SHIP", "", "   "})
    void shouldThrowForUnknownValue(String value) {
        assertThatThrownBy(() -> EntityType.fromString(value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown EntityType");
    }

    @ParameterizedTest
    @NullSource
    void shouldThrowForNullValue(String value) {
        assertThatThrownBy(() -> EntityType.fromString(value))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldHaveFourValues() {
        assertThat(EntityType.values()).hasSize(4);
    }
}
