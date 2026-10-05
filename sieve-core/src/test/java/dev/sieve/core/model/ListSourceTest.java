package dev.sieve.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class ListSourceTest {

    @ParameterizedTest
    @CsvSource({
        "OFAC_SDN, OFAC SDN",
        "OFAC_NONSDN, OFAC Non-SDN",
        "US_TRADE_CSL, US Trade CSL",
        "US_BIS_ENTITY, US BIS Entity List",
        "US_BIS_MEU, US BIS MEU",
        "EU_CONSOLIDATED, EU Consolidated",
        "EU_SANCTIONS_MAP, EU Sanctions Map",
        "EU_TRAVEL_BANS, EU Travel Bans",
        "EU_JOURNAL, EU Journal",
        "UN_CONSOLIDATED, UN Consolidated",
        "UK_HMT, UK HMT",
        "CA_CONSOLIDATED, Canada Consolidated",
        "CH_SECO, CH SECO",
        "AU_DFAT, AU DFAT",
        "FR_TRESOR, FR Trésor",
        "BE_FOD, BE FOD",
        "NZ_RUSSIA, NZ Russia",
        "JP_MOF, JP MoF",
        "TR_MASAK, TR MASAK",
        "PL_MSWIA, PL MSWiA",
        "IL_WMD_TERROR, IL WMD/Terror",
        "MD_TERROR, MD Terror",
        "MC_FUND_FREEZING, MC Fund Freezing",
        "UA_NSDC, UA NSDC",
        "QA_NCTC, QA NCTC",
        "ZA_FIC, ZA FIC",
        "LV_FIU, LV FIU",
        "AR_REPET, AR RePET",
        "IN_MHA, IN MHA",
        "IN_MHA_ORG, IN MHA Organisations",
        "US_FBI_WANTED, US FBI Wanted",
        "EU_MOST_WANTED, EU Most Wanted",
        "WB_DEBARRED, World Bank Debarred",
        "WIKIDATA_PEP, Wikidata PEPs",
        "GLEIF_STATE_OWNED, GLEIF State-Owned",
        "GLEIF_SANCTION_LINKED, GLEIF Sanction-Linked"
    })
    void shouldReturnCorrectDisplayName(String enumName, String expectedDisplay) {
        ListSource source = ListSource.valueOf(enumName);
        assertThat(source.displayName()).isEqualTo(expectedDisplay);
    }

    @ParameterizedTest
    @CsvSource({
        "OFAC_SDN, OFAC_SDN",
        "ofac_sdn, OFAC_SDN",
        "ofac-sdn, OFAC_SDN",
        "OFAC SDN, OFAC_SDN",
        "EU_CONSOLIDATED, EU_CONSOLIDATED",
        "eu-consolidated, EU_CONSOLIDATED",
        "UK_HMT, UK_HMT",
        "uk-hmt, UK_HMT"
    })
    void shouldResolveFromString(String input, String expectedName) {
        assertThat(ListSource.fromString(input)).isEqualTo(ListSource.valueOf(expectedName));
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "OFAC", ""})
    void shouldThrowForUnknownValue(String value) {
        assertThatThrownBy(() -> ListSource.fromString(value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown ListSource");
    }

    @ParameterizedTest
    @NullSource
    void shouldThrowForNullValue(String value) {
        assertThatThrownBy(() -> ListSource.fromString(value))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldHaveExpectedValues() {
        assertThat(ListSource.values()).hasSize(36);
    }
}
