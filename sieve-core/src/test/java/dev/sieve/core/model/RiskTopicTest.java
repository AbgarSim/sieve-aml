package dev.sieve.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RiskTopicTest {

    @ParameterizedTest
    @ValueSource(strings = {"role.pep", "PEP", "pep", "Politically exposed person", " role.pep "})
    void shouldParsePepFromCodeNameOrDisplayName(String value) {
        assertThat(RiskTopic.fromString(value)).isEqualTo(RiskTopic.PEP);
    }

    @Test
    void shouldExposeStableCodes() {
        assertThat(RiskTopic.SANCTION.code()).isEqualTo("sanction");
        assertThat(RiskTopic.SANCTION_LINKED.code()).isEqualTo("sanction.linked");
        assertThat(RiskTopic.STATE_OWNED.code()).isEqualTo("gov.soe");
    }

    @Test
    void shouldThrowWhenTopicIsUnknown() {
        assertThatThrownBy(() -> RiskTopic.fromString("astrology"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
