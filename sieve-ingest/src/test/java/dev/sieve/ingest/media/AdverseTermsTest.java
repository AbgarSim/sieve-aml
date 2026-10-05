package dev.sieve.ingest.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class AdverseTermsTest {

    @Test
    void shouldFindTermsWhenTitleContainsThemAsWholeWords() {
        List<String> found =
                AdverseTerms.foundIn(
                        "Arms dealer CHARGED with Money-Laundering in New York",
                        AdverseTerms.DEFAULT);

        assertThat(found).containsExactly("money laundering", "charged");
    }

    @Test
    void shouldNotMatchWhenTermIsOnlyPartOfAWord() {
        assertThat(AdverseTerms.foundIn("Fraudulent claims denied", List.of("fraud"))).isEmpty();
    }

    @Test
    void shouldReturnNothingWhenTitleIsEmpty() {
        assertThat(AdverseTerms.foundIn("", AdverseTerms.DEFAULT)).isEmpty();
    }
}
