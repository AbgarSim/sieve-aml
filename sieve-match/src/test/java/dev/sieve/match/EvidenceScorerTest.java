package dev.sieve.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.sieve.core.index.EntityIndex;
import dev.sieve.core.index.InMemoryEntityIndex;
import dev.sieve.core.match.FieldMatch;
import dev.sieve.core.match.FieldMatch.Outcome;
import dev.sieve.core.match.MatchResult;
import dev.sieve.core.match.PartialDate;
import dev.sieve.core.match.ScreeningCriteria;
import dev.sieve.core.match.ScreeningRequest;
import dev.sieve.core.model.Address;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.Gender;
import dev.sieve.core.model.Identifier;
import dev.sieve.core.model.IdentifierType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.NameStrength;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.ScriptType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EvidenceScorerTest {

    private final EvidenceScorer scorer = new EvidenceScorer();

    @Test
    void shouldReturnSameResultWhenNoCriteria() {
        MatchResult name = nameMatch(person(), 0.9);
        assertThat(scorer.score(name, ScreeningCriteria.none())).isSameAs(name);
    }

    @Test
    void shouldRaiseScoreWhenDateOfBirthMatches() {
        MatchResult scored = scorer.score(nameMatch(person(), 0.9), dob("1970-05-12"));
        assertThat(scored.score()).isCloseTo(0.9 + 0.1 * EvidenceScorer.DOB_EXACT, within(1e-9));
        assertThat(scored.nameScore()).isEqualTo(0.9);
        assertThat(scored.evidence())
                .containsExactly(new FieldMatch("dateOfBirth", Outcome.MATCH, "1970-05-12"));
    }

    @Test
    void shouldTreatYearOnlyQueryAsMatchWhenYearAgrees() {
        MatchResult scored = scorer.score(nameMatch(person(), 0.9), dob("1970"));
        assertThat(scored.evidence().getFirst().outcome()).isEqualTo(Outcome.MATCH);
        assertThat(scored.score()).isCloseTo(0.9 + 0.1 * EvidenceScorer.DOB_YEAR, within(1e-9));
    }

    @Test
    void shouldTreatFirstOfJanuaryOnRecordAsYearOnly() {
        SanctionedEntity yearOnly = person(LocalDate.of(1970, 1, 1));
        MatchResult scored = scorer.score(nameMatch(yearOnly, 0.9), dob("1970-05-12"));
        assertThat(scored.evidence().getFirst().outcome()).isEqualTo(Outcome.MATCH);
    }

    @Test
    void shouldTreatOffByOneYearAndSwappedDayMonthAsPartial() {
        assertThat(outcome(dob("1971-05-12"))).isEqualTo(Outcome.PARTIAL);
        assertThat(outcome(dob("1970-12-05"))).isEqualTo(Outcome.PARTIAL);
        assertThat(outcome(dob("1970-05-13"))).isEqualTo(Outcome.PARTIAL);
    }

    @Test
    void shouldLowerScoreWhenDateOfBirthDisagrees() {
        MatchResult scored = scorer.score(nameMatch(person(), 0.95), dob("1985-02-03"));
        assertThat(scored.evidence().getFirst().outcome()).isEqualTo(Outcome.MISMATCH);
        assertThat(scored.score())
                .isCloseTo(0.95 * (1 - EvidenceScorer.DOB_MISMATCH), within(1e-9));
        assertThat(scored.score()).isLessThan(0.80);
    }

    @Test
    void shouldLeaveScoreUnchangedWhenRecordHasNoDateOfBirth() {
        MatchResult scored = scorer.score(nameMatch(person((LocalDate) null), 0.9), dob("1985"));
        assertThat(scored.score()).isEqualTo(0.9);
        assertThat(scored.evidence().getFirst().outcome()).isEqualTo(Outcome.NOT_ON_RECORD);
    }

    @Test
    void shouldMatchIdentifierIgnoringCaseSpacesAndPunctuation() {
        ScreeningCriteria criteria = criteria(c -> c.identifiers = List.of("ab-123 456"));
        MatchResult scored = scorer.score(nameMatch(person(), 0.8), criteria);
        assertThat(scored.evidence().getFirst())
                .isEqualTo(new FieldMatch("identifier", Outcome.MATCH, "Passport AB123456"));
        assertThat(scored.score()).isCloseTo(0.8 + 0.2 * EvidenceScorer.IDENTIFIER, within(1e-9));
    }

    @Test
    void shouldMatchCountryByNameOrCode() {
        assertThat(outcome(criteria(c -> c.country = "Russia"))).isEqualTo(Outcome.MATCH);
        assertThat(outcome(criteria(c -> c.country = "ru"))).isEqualTo(Outcome.MATCH);
        assertThat(outcome(criteria(c -> c.country = "Cyprus"))).isEqualTo(Outcome.MATCH);
        assertThat(outcome(criteria(c -> c.country = "France"))).isEqualTo(Outcome.MISMATCH);
    }

    @Test
    void shouldCompareAddressAndPlaceOfBirthByWords() {
        assertThat(outcome(criteria(c -> c.address = "Tverskaya 7, Moscow")))
                .isEqualTo(Outcome.MATCH);
        assertThat(outcome(criteria(c -> c.address = "Moskow"))).isEqualTo(Outcome.MATCH);
        assertThat(outcome(criteria(c -> c.address = "Moscow, Arbat"))).isEqualTo(Outcome.PARTIAL);
        assertThat(outcome(criteria(c -> c.address = "Paris"))).isEqualTo(Outcome.MISMATCH);
        assertThat(outcome(criteria(c -> c.placeOfBirth = "Leningrad"))).isEqualTo(Outcome.MATCH);
    }

    @Test
    void shouldLowerScoreWhenGenderDisagrees() {
        MatchResult scored =
                scorer.score(nameMatch(person(), 0.9), criteria(c -> c.gender = Gender.FEMALE));
        assertThat(scored.evidence().getFirst().outcome()).isEqualTo(Outcome.MISMATCH);
        assertThat(scored.score()).isLessThan(0.9);
    }

    @Test
    void shouldKeepScoreWithinZeroAndOne() {
        ScreeningCriteria all =
                criteria(
                        c -> {
                            c.dob = "1970-05-12";
                            c.identifiers = List.of("AB123456");
                            c.country = "RU";
                            c.address = "Moscow";
                            c.placeOfBirth = "Leningrad";
                            c.gender = Gender.MALE;
                        });
        MatchResult scored = scorer.score(nameMatch(person(), 1.0), all);
        assertThat(scored.score()).isEqualTo(1.0);
        assertThat(scored.evidence()).hasSize(6).allMatch(f -> f.outcome() == Outcome.MATCH);
    }

    @Test
    void shouldNeverRaiseCandidateFloorAboveThreshold() {
        assertThat(EvidenceScorer.candidateThreshold(dob("1970"), 0.8)).isLessThan(0.8);
        assertThat(EvidenceScorer.candidateThreshold(dob("1970"), 0.8))
                .isGreaterThanOrEqualTo(EvidenceScorer.MIN_NAME_SCORE);
        assertThat(EvidenceScorer.candidateThreshold(dob("1970"), 0.5)).isEqualTo(0.5);
    }

    @Test
    void shouldDropNameMatchesThatContradictTheDateOfBirthWhenScreening() {
        EntityIndex index = new InMemoryEntityIndex();
        index.add(person());
        CompositeMatchEngine engine =
                new CompositeMatchEngine(List.of(new ExactMatchEngine(), new FuzzyMatchEngine()));

        ScreeningRequest nameOnly = ScreeningRequest.of("Ivan Petrov", 0.8);
        ScreeningRequest wrongDob =
                new ScreeningRequest(
                        "Ivan Petrov", Optional.empty(), Optional.empty(), 0.8, dob("1990-01-02"));
        ScreeningRequest rightDob =
                new ScreeningRequest(
                        "Ivan Petrov", Optional.empty(), Optional.empty(), 0.8, dob("1970"));

        assertThat(engine.screen(nameOnly, index)).hasSize(1);
        assertThat(engine.screen(nameOnly, index).getFirst().evidence()).isEmpty();
        assertThat(engine.screen(wrongDob, index)).isEmpty();
        assertThat(engine.screen(rightDob, index))
                .singleElement()
                .satisfies(r -> assertThat(r.evidence()).hasSize(1));
    }

    @Test
    void shouldLiftNameMatchBelowThresholdWhenIdentifierMatches() {
        EntityIndex index = new InMemoryEntityIndex();
        index.add(person());
        CompositeMatchEngine engine =
                new CompositeMatchEngine(List.of(new ExactMatchEngine(), new FuzzyMatchEngine()));
        String name = "Iwan Petrowski";
        double nameScore = engine.screen(ScreeningRequest.of(name, 0.5), index).getFirst().score();
        assertThat(nameScore).isBetween(0.75, 0.9);

        ScreeningRequest withId =
                new ScreeningRequest(
                        name,
                        Optional.empty(),
                        Optional.empty(),
                        0.9,
                        criteria(c -> c.identifiers = List.of("AB123456")));
        assertThat(engine.screen(withId, index))
                .singleElement()
                .satisfies(r -> assertThat(r.score()).isGreaterThanOrEqualTo(0.9));
    }

    // ---- helpers ----

    private Outcome outcome(ScreeningCriteria criteria) {
        return scorer.score(nameMatch(person(), 0.9), criteria).evidence().getFirst().outcome();
    }

    private static MatchResult nameMatch(SanctionedEntity entity, double score) {
        return new MatchResult(entity, score, "primaryName", "TEST");
    }

    private static ScreeningCriteria dob(String date) {
        return criteria(c -> c.dob = date);
    }

    private static final class Fields {
        String dob;
        String placeOfBirth;
        String country;
        String address;
        List<String> identifiers = List.of();
        Gender gender;
    }

    private static ScreeningCriteria criteria(java.util.function.Consumer<Fields> set) {
        Fields f = new Fields();
        set.accept(f);
        return new ScreeningCriteria(
                Optional.ofNullable(f.dob).map(PartialDate::parse),
                Optional.ofNullable(f.placeOfBirth),
                Optional.ofNullable(f.country),
                Optional.ofNullable(f.address),
                f.identifiers,
                Optional.ofNullable(f.gender));
    }

    private static SanctionedEntity person() {
        return person(LocalDate.of(1970, 5, 12));
    }

    private static SanctionedEntity person(LocalDate dob) {
        NameInfo name =
                new NameInfo(
                        "Ivan Petrov",
                        null,
                        null,
                        null,
                        null,
                        NameType.PRIMARY,
                        NameStrength.STRONG,
                        ScriptType.LATIN);
        return new SanctionedEntity(
                "1",
                EntityType.INDIVIDUAL,
                ListSource.OFAC_SDN,
                name,
                null,
                List.of(new Address("Tverskaya 7", "Moscow", null, null, "Russia", null)),
                List.of(new Identifier(IdentifierType.PASSPORT, "AB123456", "RU", null)),
                List.of("Russian Federation"),
                List.of("Cyprus"),
                dob == null ? List.of() : List.of(dob),
                List.of("Leningrad, USSR"),
                Gender.MALE,
                null,
                null,
                null,
                null,
                null,
                null,
                Instant.now(),
                null,
                null,
                null);
    }
}
