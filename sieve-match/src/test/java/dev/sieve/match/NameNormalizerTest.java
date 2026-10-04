package dev.sieve.match;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sieve.core.model.EntityType;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class NameNormalizerTest {

    @Test
    void shouldReturnEmptyWhenNameIsNullOrBlank() {
        assertThat(NameNormalizer.normalize(null)).isEmpty();
        assertThat(NameNormalizer.normalize("   ")).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "  DOE,   John  | doe john",
                "Müller-García, José | muller garcia jose",
                "Øresund Straße Łódź | oresund strasse lodz",
                "İSTANBUL Ağır | istanbul agir",
                "Æthelred Œuvre | aethelred oeuvre",
                "O'Brien | obrien",
                "Ma’mun ʿAbd | mamun abd",
                "J.P. Morgan & Co. | j p morgan co",
                "«Ромашка» | romashka",
                "ｆｕｌｌ　ｗｉｄｔｈ | full width",
            })
    void shouldFoldAccentsAndPunctuationWhenNameIsLatin(String name, String expected) {
        assertThat(NameNormalizer.normalize(name)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "Владимир Владимирович Путин | vladimir vladimirovich putin",
                "Щербаков Юрий | shcherbakov yuriy",
                "Олександр Ющенко | oleksandr yushchenko",
                "Александр Лукашенко | aleksandr lukashenko",
                "Ελευθέριος Βενιζέλος | eleftherios venizelos",
                "习近平 | xi jinping",
                "李鹏 | li peng",
                "بشار الأسد | bshar alasd",
                "ქართული | kartuli",
            })
    void shouldRomaniseWhenNameIsInAnotherScript(String name, String expected) {
        assertThat(NameNormalizer.normalize(name)).isEqualTo(expected);
    }

    @Test
    void shouldRomaniseEachScriptWhenNameMixesScripts() {
        assertThat(NameNormalizer.normalize("Putin Путин")).isEqualTo("putin putin");
    }

    @Test
    void shouldKeepWordsSeparateWhenChineseRunIsLongerThanPersonalName() {
        assertThat(NameNormalizer.normalize("中国石油天然气集团"))
                .isEqualTo("zhong guo shi you tian ran qi ji tuan");
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "Rosneft PJSC | rosneft",
                "ООО «Ромашка» | romashka",
                "Limited Liability Company Alfa | alfa",
                "Siemens Aktiengesellschaft | siemens",
                "Bank Saderat PLC | bank saderat",
                "Huawei Technologies Co., Ltd. | huawei technologies",
                "PT Bank Negara Indonesia Tbk | bank negara indonesia",
                "Volkswagen GmbH & Co. KG | volkswagen",
                "Industrias S.A. de C.V. | industrias",
                "Kalashnikov Concern JSC | kalashnikov concern",
                "Company Limited | company",
                "LLC | llc",
                "Co-operative Bank | co operative bank",
            })
    void shouldStripLegalFormsWhenEntityIsOrganisation(String name, String expected) {
        assertThat(NameNormalizer.normalize(name, EntityType.COMPANY)).isEqualTo(expected);
    }

    @Test
    void shouldKeepLegalFormWordsWhenEntityIsPerson() {
        assertThat(NameNormalizer.normalize("Pat Ao", EntityType.INDIVIDUAL)).isEqualTo("pat ao");
    }

    @Test
    void shouldStripLegalFormsFromQueryUnlessItAsksForAnotherType() {
        assertThat(NameNormalizer.normalizeQuery("Alfa LLC", Optional.empty())).isEqualTo("alfa");
        assertThat(NameNormalizer.normalizeQuery("Alfa LLC", Optional.of(EntityType.ENTITY)))
                .isEqualTo("alfa");
        assertThat(NameNormalizer.normalizeQuery("Alfa LLC", Optional.of(EntityType.VESSEL)))
                .isEqualTo("alfa llc");
    }
}
