package dev.sieve.ingest.enforcement;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class SuspectedBreachTest {

    @Test
    void shouldKeepTitlesAboutBreakingSanctions() {
        assertThat(SuspectedBreach.reportsBreach("Shadow fleet tanker evades Russia oil sanctions"))
                .isTrue();
        assertThat(
                        SuspectedBreach.reportsBreach(
                                "Businessman charged with violating Iran sanctions"))
                .isTrue();
        assertThat(SuspectedBreach.reportsBreach("EU agrees new sanctions package")).isFalse();
        assertThat(SuspectedBreach.reportsBreach("Police arrest burglary suspect")).isFalse();
    }

    @Test
    void shouldPlaceAnArticleByTheProgramItsTitleNames() {
        var b =
                SuspectedBreach.of(
                        "https://x/a",
                        "Firm fined for breaching North Korea sanctions",
                        "x",
                        Instant.EPOCH);
        assertThat(b.targets()).containsExactly("KP");
    }
}
