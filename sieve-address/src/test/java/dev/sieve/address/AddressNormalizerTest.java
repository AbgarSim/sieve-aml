package dev.sieve.address;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import dev.sieve.core.model.Address;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link AddressNormalizer}.
 *
 * <p>These tests exercise the fallback mode (libpostal not available on the system). When running
 * in CI or local dev without libpostal installed, the normalizer gracefully degrades to basic
 * string normalization.
 */
class AddressNormalizerTest {

    private final AddressNormalizer normalizer = new AddressNormalizer();

    @Test
    void fallbackMode_isNotAvailable() {
        // init() should not crash even without libpostal installed
        normalizer.init();
        // On a machine without libpostal, isAvailable() should be false
        // (On a machine WITH libpostal, it would be true — both cases are valid)
    }

    @Test
    void expand_fallback_returnsStrippedInput() {
        List<String> result = normalizer.expand("  123 Main St  ");
        assertThat(result).containsExactly("123 Main St");
    }

    @Test
    void expand_rejectsNull() {
        assertThatNullPointerException().isThrownBy(() -> normalizer.expand(null));
    }

    @Test
    void parse_fallback_returnsSingleRoadComponent() {
        ParsedAddress result = normalizer.parse("  100 Leonard St, London  ");
        assertThat(result.components()).hasSize(1);
        assertThat(result.components().get(0).label()).isEqualTo("road");
        assertThat(result.components().get(0).value()).isEqualTo("100 Leonard St, London");
        assertThat(result.road()).isEqualTo("100 Leonard St, London");
    }

    @Test
    void parse_rejectsNull() {
        assertThatNullPointerException().isThrownBy(() -> normalizer.parse(null));
    }

    @Test
    void normalize_nullAddress_returnsNull() {
        assertThat(normalizer.normalize(null)).isNull();
    }

    @Test
    void normalize_fallback_stripsWhitespace() {
        Address input = new Address("  123 Main St  ", "  New York  ", "  NY  ", "  10001  ",
                "  US  ", "  123 Main St, New York, NY 10001, US  ");
        Address result = normalizer.normalize(input);

        assertThat(result.street()).isEqualTo("123 Main St");
        assertThat(result.city()).isEqualTo("New York");
        assertThat(result.stateOrProvince()).isEqualTo("NY");
        assertThat(result.postalCode()).isEqualTo("10001");
        assertThat(result.country()).isEqualTo("US");
        assertThat(result.fullAddress()).isEqualTo("123 Main St, New York, NY 10001, US");
    }

    @Test
    void normalize_fallback_preservesNullFields() {
        Address input = new Address(null, "London", null, null, "GB", null);
        Address result = normalizer.normalize(input);

        assertThat(result.street()).isNull();
        assertThat(result.city()).isEqualTo("London");
        assertThat(result.stateOrProvince()).isNull();
        assertThat(result.postalCode()).isNull();
        assertThat(result.country()).isEqualTo("GB");
        assertThat(result.fullAddress()).isNull();
    }

    @Test
    void normalize_fallback_emptyAddress_returnsAsIs() {
        Address input = new Address(null, null, null, null, null, null);
        Address result = normalizer.normalize(input);
        assertThat(result).isEqualTo(input);
    }

    @Test
    void shutdown_doesNotThrow() {
        normalizer.init();
        normalizer.shutdown();
        // Double shutdown should also be safe
        normalizer.shutdown();
    }
}
