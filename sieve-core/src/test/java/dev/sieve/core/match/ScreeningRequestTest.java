package dev.sieve.core.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class ScreeningRequestTest {

    @Test
    void shouldCreateValidRequest() {
        ScreeningRequest request = ScreeningRequest.of("John Doe", 0.80);

        assertThat(request.name()).isEqualTo("John Doe");
        assertThat(request.threshold()).isEqualTo(0.80);
        assertThat(request.entityType()).isEmpty();
        assertThat(request.sources()).isEmpty();
    }

    @Test
    void shouldThrowWhenNameIsNull() {
        assertThatThrownBy(() -> ScreeningRequest.of(null, 0.80))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Name");
    }

    @Test
    void shouldThrowWhenNameIsBlank() {
        assertThatThrownBy(() -> ScreeningRequest.of("   ", 0.80))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void shouldThrowWhenThresholdIsNegative() {
        assertThatThrownBy(() -> ScreeningRequest.of("John Doe", -0.1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Threshold");
    }

    @Test
    void shouldThrowWhenThresholdExceedsOne() {
        assertThatThrownBy(() -> ScreeningRequest.of("John Doe", 1.1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Threshold");
    }

    @Test
    void shouldAcceptBoundaryThresholds() {
        ScreeningRequest atZero = ScreeningRequest.of("Test", 0.0);
        assertThat(atZero.threshold()).isEqualTo(0.0);

        ScreeningRequest atOne = ScreeningRequest.of("Test", 1.0);
        assertThat(atOne.threshold()).isEqualTo(1.0);
    }

    @Test
    void shouldThrowWhenEntityTypeOptionalIsNull() {
        assertThatThrownBy(() -> new ScreeningRequest("John Doe", null, Optional.empty(), 0.80))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("entityType");
    }

    @Test
    void shouldThrowWhenSourcesOptionalIsNull() {
        Optional<dev.sieve.core.model.EntityType> entityType = Optional.empty();
        assertThatThrownBy(() -> new ScreeningRequest("John Doe", entityType, null, 0.80))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("sources");
    }
}
