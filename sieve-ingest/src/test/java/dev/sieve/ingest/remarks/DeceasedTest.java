package dev.sieve.ingest.remarks;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class DeceasedTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Reportedly deceased.",
                "Review pursuant to Security Council resolution 1822 (2008) was concluded on 21 Jun. 2010. Confirmed to have died in April 2011.",
                "Reportedly killed in an air strike in 2015.",
                "Believed to be dead since 2009",
                "He was killed in Syria in 2017 (reportedly).",
                "DOB 1965; Deceased",
                "Passed away in 2020 according to press reports."
            })
    void shouldReadAStatementAboutThePerson(String text) {
        assertThat(Deceased.statedIn(text)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
            strings = {
                "Father's name is Mohammed (deceased).",
                "Mother's name: Fatima, deceased.",
                "Responsible for an attack that killed 35 people in 2008.",
                "Sentenced to death in absentia in 2012.",
                "Leader of a death squad.",
                "Brother of QUSAY (IQi.002), who died in 2003.",
                "Member of ADF (CDe.001)."
            })
    void shouldStaySilentWhenNothingSaysThePersonIsDead(String text) {
        assertThat(Deceased.statedIn(text)).isNull();
    }
}
