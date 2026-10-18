package dev.sieve.match;

import dev.sieve.core.geo.CountryNormalizer;
import dev.sieve.core.match.FieldMatch;
import dev.sieve.core.match.FieldMatch.Outcome;
import dev.sieve.core.match.MatchResult;
import dev.sieve.core.match.PartialDate;
import dev.sieve.core.match.ScreeningCriteria;
import dev.sieve.core.model.Address;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.Gender;
import dev.sieve.core.model.Identifier;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.match.algorithm.JaroWinkler;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Weighs facts beyond the name, such as date of birth, country and identifiers, into a name match
 * score.
 *
 * <p>Each fact given in the {@link ScreeningCriteria} is compared with the matched record. A fact
 * that agrees moves the score part of the way towards 1.0 ({@code s + (1 - s) * w}); one that
 * disagrees takes a share off it ({@code s * (1 - p)}); one the record does not state leaves it
 * unchanged. The score stays within 0 to 1, so thresholds keep their meaning.
 *
 * <p>Dates of birth: a list that gives only a birth year is stored as 1 January of that year, so a
 * record date of 1 January agrees with any date in that year, as does a query that gives only the
 * year. A date one year off, or with day and month swapped, counts as a partial agreement.
 *
 * <p>The weights are mirrored in the dashboard's client-side search ({@code
 * dashboard/src/data/evidence.ts}); change both together.
 */
public final class EvidenceScorer {

    /** Agreement weights: the share of the gap to 1.0 that a matching fact closes. */
    static final double DOB_EXACT = 0.40;

    static final double DOB_YEAR = 0.25;
    static final double DOB_NEAR = 0.10;
    static final double IDENTIFIER = 0.60;
    static final double COUNTRY = 0.15;
    static final double PLACE_OF_BIRTH = 0.15;
    static final double ADDRESS = 0.15;
    static final double ADDRESS_PARTIAL = 0.05;
    static final double GENDER = 0.05;

    /** Disagreement penalties: the share of the score that a contradicting fact removes. */
    static final double DOB_MISMATCH = 0.30;

    static final double IDENTIFIER_MISMATCH = 0.05;
    static final double COUNTRY_MISMATCH = 0.15;
    static final double PLACE_OF_BIRTH_MISMATCH = 0.05;
    static final double ADDRESS_MISMATCH = 0.05;
    static final double GENDER_MISMATCH = 0.25;

    /** The lowest name score that facts beyond the name can lift to a match. */
    static final double MIN_NAME_SCORE = 0.70;

    private static final double TOKEN_SIMILARITY = 0.90;

    private final CountryNormalizer countries;

    /** Creates a scorer with the standard country table. */
    public EvidenceScorer() {
        this(CountryNormalizer.standard());
    }

    /**
     * Creates a scorer with the given country table.
     *
     * @param countries resolves country names and codes to ISO codes
     */
    public EvidenceScorer(CountryNormalizer countries) {
        this.countries = Objects.requireNonNull(countries, "countries must not be null");
    }

    /**
     * The name score below which no combination of the given facts can reach {@code threshold}, so
     * that name candidates under it need not be scored. Never above {@code threshold} and never
     * below {@link #MIN_NAME_SCORE} (or {@code threshold}, if lower).
     *
     * @param criteria the facts to be weighed
     * @param threshold the requested threshold
     * @return the name score floor for candidates
     */
    public static double candidateThreshold(ScreeningCriteria criteria, double threshold) {
        double remaining = 1.0;
        if (criteria.dateOfBirth().isPresent()) {
            remaining *= 1 - DOB_EXACT;
        }
        if (!criteria.identifiers().isEmpty()) {
            remaining *= 1 - IDENTIFIER;
        }
        if (criteria.country().isPresent()) {
            remaining *= 1 - COUNTRY;
        }
        if (criteria.placeOfBirth().isPresent()) {
            remaining *= 1 - PLACE_OF_BIRTH;
        }
        if (criteria.address().isPresent()) {
            remaining *= 1 - ADDRESS;
        }
        if (criteria.gender().isPresent()) {
            remaining *= 1 - GENDER;
        }
        double floor = 1 - (1 - threshold) / remaining;
        return Math.min(threshold, Math.max(Math.min(MIN_NAME_SCORE, threshold), floor));
    }

    /**
     * Re-scores a name match with the given facts.
     *
     * @param result the name match
     * @param criteria the facts to weigh
     * @return the result with its adjusted score, the name score and the per-fact outcomes; the
     *     result itself when {@code criteria} is empty
     */
    public MatchResult score(MatchResult result, ScreeningCriteria criteria) {
        if (criteria.isEmpty()) {
            return result;
        }
        SanctionedEntity entity = result.entity();
        double nameScore = result.nameScore();
        double score = nameScore;
        List<FieldMatch> evidence = new ArrayList<>();

        if (criteria.dateOfBirth().isPresent()) {
            FieldMatch m = dateOfBirth(criteria.dateOfBirth().get(), entity.datesOfBirth());
            evidence.add(m);
            score =
                    switch (m.outcome()) {
                        case MATCH ->
                                raise(
                                        score,
                                        criteria.dateOfBirth().get().isComplete()
                                                        && !m.recordValue().endsWith("-01-01")
                                                ? DOB_EXACT
                                                : DOB_YEAR);
                        case PARTIAL -> raise(score, DOB_NEAR);
                        case MISMATCH -> lower(score, DOB_MISMATCH);
                        case NOT_ON_RECORD -> score;
                    };
        }
        if (!criteria.identifiers().isEmpty()) {
            FieldMatch m = identifiers(criteria.identifiers(), entity.identifiers());
            evidence.add(m);
            score = apply(score, m.outcome(), IDENTIFIER, IDENTIFIER, IDENTIFIER_MISMATCH);
        }
        if (criteria.country().isPresent()) {
            FieldMatch m = country(criteria.country().get(), entity);
            evidence.add(m);
            score = apply(score, m.outcome(), COUNTRY, COUNTRY, COUNTRY_MISMATCH);
        }
        if (criteria.placeOfBirth().isPresent()) {
            FieldMatch m =
                    text("placeOfBirth", criteria.placeOfBirth().get(), entity.placesOfBirth());
            evidence.add(m);
            score =
                    apply(
                            score,
                            m.outcome(),
                            PLACE_OF_BIRTH,
                            PLACE_OF_BIRTH / 2,
                            PLACE_OF_BIRTH_MISMATCH);
        }
        if (criteria.address().isPresent()) {
            List<String> addresses =
                    entity.addresses().stream().map(EvidenceScorer::addressText).toList();
            FieldMatch m = text("address", criteria.address().get(), addresses);
            evidence.add(m);
            score = apply(score, m.outcome(), ADDRESS, ADDRESS_PARTIAL, ADDRESS_MISMATCH);
        }
        if (criteria.gender().isPresent()) {
            FieldMatch m = gender(criteria.gender().get(), entity.gender());
            evidence.add(m);
            score = apply(score, m.outcome(), GENDER, GENDER, GENDER_MISMATCH);
        }

        double clamped = Math.max(0.0, Math.min(1.0, score));
        return new MatchResult(
                entity,
                clamped,
                result.matchedField(),
                result.matchAlgorithm(),
                nameScore,
                evidence);
    }

    private static double apply(
            double score, Outcome outcome, double match, double partial, double mismatch) {
        return switch (outcome) {
            case MATCH -> raise(score, match);
            case PARTIAL -> raise(score, partial);
            case MISMATCH -> lower(score, mismatch);
            case NOT_ON_RECORD -> score;
        };
    }

    private static double raise(double score, double weight) {
        return score + (1 - score) * weight;
    }

    private static double lower(double score, double penalty) {
        return score * (1 - penalty);
    }

    // ---- date of birth ----

    static FieldMatch dateOfBirth(PartialDate query, List<LocalDate> recordDates) {
        if (recordDates.isEmpty()) {
            return notOnRecord("dateOfBirth");
        }
        Outcome best = Outcome.MISMATCH;
        String bestValue =
                recordDates.stream().map(LocalDate::toString).collect(Collectors.joining(", "));
        for (LocalDate date : recordDates) {
            Outcome outcome = compareDate(query, date);
            if (outcome.ordinal() < best.ordinal()) {
                best = outcome;
                bestValue = date.toString();
            }
        }
        return new FieldMatch("dateOfBirth", best, bestValue);
    }

    private static Outcome compareDate(PartialDate query, LocalDate record) {
        boolean recordYearOnly = record.getMonthValue() == 1 && record.getDayOfMonth() == 1;
        if (query.year() == record.getYear()) {
            if (query.month().isEmpty() || recordYearOnly) {
                return Outcome.MATCH;
            }
            int month = query.month().get();
            if (month == record.getMonthValue()) {
                if (query.day().isEmpty() || query.day().get() == record.getDayOfMonth()) {
                    return Outcome.MATCH;
                }
                return Outcome.PARTIAL;
            }
            if (query.day().isPresent()
                    && query.day().get() == record.getMonthValue()
                    && month == record.getDayOfMonth()) {
                return Outcome.PARTIAL;
            }
            return Outcome.MISMATCH;
        }
        if (Math.abs(query.year() - record.getYear()) == 1) {
            return Outcome.PARTIAL;
        }
        return Outcome.MISMATCH;
    }

    // ---- identifiers ----

    static FieldMatch identifiers(List<String> query, List<Identifier> record) {
        if (record.isEmpty()) {
            return notOnRecord("identifier");
        }
        Set<String> wanted =
                query.stream()
                        .map(EvidenceScorer::identifierKey)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toSet());
        for (Identifier id : record) {
            if (wanted.contains(identifierKey(id.value()))) {
                return new FieldMatch(
                        "identifier", Outcome.MATCH, id.type().displayName() + " " + id.value());
            }
        }
        return new FieldMatch(
                "identifier",
                Outcome.MISMATCH,
                record.stream().map(Identifier::value).collect(Collectors.joining(", ")));
    }

    /**
     * An identifier value with case, spaces and punctuation removed, so that "AB-123 456" and
     * "ab123456" compare equal.
     *
     * @param value the identifier as written
     * @return the comparison key
     */
    public static String identifierKey(String value) {
        StringBuilder key = new StringBuilder(value.length());
        value.codePoints()
                .filter(Character::isLetterOrDigit)
                .map(Character::toUpperCase)
                .forEach(key::appendCodePoint);
        return key.toString();
    }

    // ---- country ----

    private FieldMatch country(String query, SanctionedEntity entity) {
        Set<String> wanted = codes(query);
        Set<String> onRecord = new LinkedHashSet<>();
        Stream.of(
                        entity.nationalities().stream(),
                        entity.citizenships().stream(),
                        entity.addresses().stream().map(Address::country))
                .flatMap(s -> s)
                .filter(Objects::nonNull)
                .forEach(raw -> onRecord.addAll(codes(raw)));
        if (onRecord.isEmpty()) {
            return notOnRecord("country");
        }
        for (String code : wanted) {
            if (onRecord.contains(code)) {
                return new FieldMatch("country", Outcome.MATCH, code);
            }
        }
        return new FieldMatch("country", Outcome.MISMATCH, String.join(", ", onRecord));
    }

    /** ISO codes of the countries a value names; an unrecognised part is kept upper-cased. */
    private Set<String> codes(String raw) {
        Set<String> codes = new LinkedHashSet<>();
        for (String part : countries.split(raw)) {
            codes.add(countries.toIso2(part).orElse(part.strip().toUpperCase(Locale.ROOT)));
        }
        return codes;
    }

    // ---- free text: place of birth, address ----

    /**
     * Compares free text by its words: every query word found in a record value is a match, some is
     * partial, none is a mismatch.
     */
    static FieldMatch text(String field, String query, List<String> recordValues) {
        List<String> values = recordValues.stream().filter(v -> v != null && !v.isBlank()).toList();
        if (values.isEmpty()) {
            return notOnRecord(field);
        }
        List<String> wanted = words(query);
        if (wanted.isEmpty()) {
            return notOnRecord(field);
        }
        Outcome best = Outcome.MISMATCH;
        String bestValue = String.join("; ", values);
        double bestShare = 0;
        for (String value : values) {
            List<String> have = words(value);
            long found = wanted.stream().filter(w -> containsWord(have, w)).count();
            double share = (double) found / wanted.size();
            if (share > bestShare) {
                bestShare = share;
                bestValue = value;
                best = share >= 0.999 ? Outcome.MATCH : Outcome.PARTIAL;
            }
        }
        return new FieldMatch(field, best, bestValue);
    }

    private static boolean containsWord(List<String> words, String word) {
        for (String w : words) {
            if (w.equals(word)
                    || (word.length() >= 4
                            && JaroWinkler.similarity(w, word) >= TOKEN_SIMILARITY)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> words(String text) {
        String normalized = NameNormalizer.normalizeUncached(text, EntityType.INDIVIDUAL);
        List<String> words = new ArrayList<>();
        for (String w : normalized.split("[^\\p{L}\\p{N}]+")) {
            if (w.length() >= 2) {
                words.add(w);
            }
        }
        return words;
    }

    /**
     * An address as one line of text: its full form when the list gives one, otherwise its parts
     * joined.
     *
     * @param address the address
     * @return the text, empty when the address has no parts
     */
    public static String addressText(Address address) {
        if (address.fullAddress() != null && !address.fullAddress().isBlank()) {
            return address.fullAddress();
        }
        return Stream.of(
                        address.street(),
                        address.city(),
                        address.stateOrProvince(),
                        address.postalCode(),
                        address.country())
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining(", "));
    }

    // ---- gender ----

    private static FieldMatch gender(Gender query, Gender record) {
        if (record == null) {
            return notOnRecord("gender");
        }
        return new FieldMatch(
                "gender",
                query == record ? Outcome.MATCH : Outcome.MISMATCH,
                record.name().toLowerCase(Locale.ROOT));
    }

    private static FieldMatch notOnRecord(String field) {
        return new FieldMatch(field, Outcome.NOT_ON_RECORD, "");
    }
}
