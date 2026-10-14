package dev.sieve.ingest.enforcement;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SanctionsProgramsTest {

    @Test
    void shouldListProgramsInTheOrderTheNoticeNamesThem() {
        var found =
                SanctionsPrograms.find(
                        "475 apparent violations of OFAC sanctions programs targeting Russia, Belarus, Iran,"
                                + " Burma, Somalia, narcotics, and global terrorism.");
        assertThat(found.programs())
                .containsExactly(
                        "Russia", "Belarus", "Iran", "Burma", "Somalia", "Narcotics", "Terrorism");
        assertThat(found.targets()).containsExactly("RU", "BY", "IR", "MM", "SO");
    }

    @Test
    void shouldReadUkRegulationNames() {
        assertThat(
                        SanctionsPrograms.find(
                                        "Regulation 12 of the Russia (Sanctions) (EU Exit) Regulations 2019")
                                .targets())
                .containsExactly("RU");
        assertThat(
                        SanctionsPrograms.find(
                                        "The Ukraine (European Union Financial Sanctions) (No.2) Regulations 2014")
                                .targets())
                .containsExactly("RU");
        var ct = SanctionsPrograms.find("Counter-Terrorism (Sanctions) (EU Exit) Regulations 2019");
        assertThat(ct.programs()).containsExactly("Terrorism");
        assertThat(ct.targets()).isEmpty();
    }

    @Test
    void shouldTellSudanFromSouthSudan() {
        assertThat(SanctionsPrograms.find("South Sudan Sanctions Regulations").targets())
                .containsExactly("SS");
        assertThat(SanctionsPrograms.find("Sudanese Sanctions Regulations").targets())
                .containsExactly("SD");
    }

    @Test
    void shouldKeepOnlyTheFirstProgramWhenAsked() {
        var first =
                SanctionsPrograms.find(
                                "Cuba 2000 Unauthorized transactions with Cuban entity MRV Kosovo")
                        .first();
        assertThat(first.programs()).containsExactly("Cuba");
        assertThat(first.targets()).containsExactly("CU");
    }

    @Test
    void shouldFindNothingInUnrelatedText() {
        assertThat(
                        SanctionsPrograms.find(
                                        "A company headquartered in Charlotte, North Carolina")
                                .isEmpty())
                .isTrue();
    }
}
