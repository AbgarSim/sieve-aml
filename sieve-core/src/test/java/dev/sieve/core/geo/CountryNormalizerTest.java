package dev.sieve.core.geo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class CountryNormalizerTest {

    private final CountryNormalizer normalizer = CountryNormalizer.standard();

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "RU|RU",
                "ru|RU",
                "RUS|RU",
                "RUSSIA|RU",
                "Russian Federation|RU",
                "Fédération de Russie|RU",
                "Russland|RU",
                "Россия|RU",
                "Russian|RU",
                "Iran (Islamic Republic of)|IR",
                "Iran, Islamic Republic of|IR",
                "Korea, North|KP",
                "NORTH KOREA|KP",
                "Democratic People's Republic of Korea|KP",
                "Korea, Republic of|KR",
                "Syrian Arab Republic|SY",
                "Syrie|SY",
                "Democratic Republic of the Congo|CD",
                "Côte d'Ivoire|CI",
                "Cote d Ivoire|CI",
                "Bosnia and Herzegovina|BA",
                "Bosnia & Herzegovina|BA",
                "Saint Kitts and Nevis|KN",
                "The Bahamas|BS",
                "United Kingdom|GB",
                "UK|GB",
                "United States of America|US",
                "Türkiye|TR",
                "Kosovo|XK",
                "Myanmar (Burma)|MM",
                "Burma|MM",
                "Moscow, Russia|RU",
                "Crimea|UA",
                "Hong Kong|HK",
                "Palestinian|PS",
                "Virgin Islands, British|VG",
                "DPR Korea|KP",
                "Democratic Peoples Republic of Korea|KP",
                "Congo DR|CD",
                "BIRMANIE|MM",
                "BIRMANIE/MYANMAR|MM",
                "Region: Gaza|PS",
                "KIRGIZISTAN|KG",
                "KORE DEMOKRATİK HALK CUMHURİYETİ|KP",
                "RÉPUBLIQUE DÉMOCRATIQUE DU CONGO|CD",
                "TÜRKİYE CUMHURİYETİ|TR",
                "BİRLEŞİK KRALLIK|GB",
                "TRINIDAD ET TOBAGO|TT",
                "prétendument RUSSIE|RU",
                "RF, CHECHEN REGION|RU",
                "Region: Crimea|UA",
                "ET|ET",
                "AND|AD",
                "يمني|YE"
            })
    void shouldResolveKnownSpellingsWhenPublishedInDifferentForms(String raw, String expected) {
        assertThat(normalizer.toIso2(raw)).contains(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "Unknown", "Atlantis", "00", "Russia/Ukraine"})
    void shouldReturnEmptyWhenValueIsBlankOrUnknown(String raw) {
        assertThat(normalizer.toIso2(raw)).isEmpty();
    }

    @Test
    void shouldSplitIntoOnePartPerCountryWhenValueNamesSeveral() {
        assertThat(normalizer.split("(1) Russia. (2) Ukraine"))
                .containsExactly("Russia.", "Ukraine");
        assertThat(normalizer.split("RU; CY")).containsExactly("RU", "CY");
        assertThat(normalizer.split("Iraq/Syria")).containsExactly("Iraq", "Syria");
        assertThat(normalizer.split("BIRMANIE/MYANMAR")).containsExactly("BIRMANIE/MYANMAR");
        assertThat(normalizer.split("  ")).isEmpty();
    }

    @Test
    void shouldReturnEnglishDisplayNameWhenCodeIsKnown() {
        assertThat(normalizer.displayName("DE")).isEqualTo("Germany");
        assertThat(normalizer.displayName("XK")).isEqualTo("Kosovo");
    }

    @Test
    void shouldIncludeKosovoWhenListingCodes() {
        assertThat(normalizer.codes()).contains("XK", "US", "RU").hasSizeGreaterThan(240);
    }
}
