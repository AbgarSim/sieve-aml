package dev.sieve.core.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VesselDetailsTest {

    @Test
    void shouldStripBlankStringsToNull() {
        VesselDetails details = new VesselDetails(" Panama ", "", "  ", 48_000, null);

        assertThat(details.flag()).isEqualTo("Panama");
        assertThat(details.type()).isNull();
        assertThat(details.callSign()).isNull();
        assertThat(details.tonnage()).isEqualTo(48_000);
        assertThat(details.grossRegisteredTonnage()).isNull();
        assertThat(details.isEmpty()).isFalse();
    }

    @Test
    void shouldReadTonnageFiguresAsListsWriteThem() {
        assertThat(VesselDetails.tons("52,000")).isEqualTo(52_000);
        assertThat(VesselDetails.tons("5.100 t")).isEqualTo(5_100);
        assertThat(VesselDetails.tons(" 48000 ")).isEqualTo(48_000);
        assertThat(VesselDetails.tons("n/a")).isNull();
        assertThat(VesselDetails.tons(null)).isNull();
        assertThat(VesselDetails.tons("1234567890")).as("too long to be a tonnage").isNull();
    }

    @Test
    void shouldGiveNothingWhenEveryFieldIsEmpty() {
        assertThat(VesselDetails.of(null, " ", "", null, null)).isNull();
        assertThat(VesselDetails.of(null, null, "3EXY9", null, null))
                .isEqualTo(new VesselDetails(null, null, "3EXY9", null, null));
    }
}
