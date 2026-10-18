package dev.sieve.core.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class PartialDateTest {

    @Test
    void shouldParseYearYearMonthAndFullDate() {
        assertThat(PartialDate.parse("1970"))
                .isEqualTo(new PartialDate(1970, Optional.empty(), Optional.empty()));
        assertThat(PartialDate.parse("1970-05"))
                .isEqualTo(new PartialDate(1970, Optional.of(5), Optional.empty()));
        assertThat(PartialDate.parse(" 1970-05-12 ").toString()).isEqualTo("1970-05-12");
    }

    @Test
    void shouldParseDayFirstDates() {
        assertThat(PartialDate.parse("12.05.1970").toString()).isEqualTo("1970-05-12");
        assertThat(PartialDate.parse("12/05/1970").toString()).isEqualTo("1970-05-12");
    }

    @Test
    void shouldRejectInvalidDates() {
        assertThatThrownBy(() -> PartialDate.parse("1970-13-01"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PartialDate.parse("May 1970"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
