package dev.sieve.ingest.enforcement;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A published enforcement action for a breach of sanctions: a civil penalty or settlement, a
 * finding of violation, a disclosure of a breach, or a criminal case.
 *
 * @param id stable id, the authority followed by the date and a slug of the party or title
 * @param authority the enforcing authority, e.g. {@code OFAC}
 * @param jurisdiction ISO 3166-1 alpha-2 code of the authority's country
 * @param kind what the authority did
 * @param date the date the action was published or decided
 * @param party the person or company the action concerns, when the source names one
 * @param amount the penalty or settlement, in whole units of {@code currency}
 * @param currency ISO 4217 code of the amount
 * @param violations the number of violations, penalties or settlements the source counts
 * @param programs the sanctions programs breached, as short labels (countries or themes)
 * @param targets ISO 3166-1 alpha-2 codes of the countries those programs target
 * @param summary one line on what happened, when the source gives one
 * @param url the authority's notice
 */
public record EnforcementAction(
        String id,
        String authority,
        String jurisdiction,
        Kind kind,
        LocalDate date,
        Optional<String> party,
        Optional<Long> amount,
        String currency,
        Optional<Integer> violations,
        List<String> programs,
        List<String> targets,
        Optional<String> summary,
        String url) {

    /** What the authority did. */
    public enum Kind {
        /** A civil monetary penalty, settlement or finding of violation. */
        CIVIL_PENALTY,
        /** A breach the authority made public without a penalty. */
        DISCLOSURE,
        /** A criminal charge, plea, conviction or sentence. */
        CRIMINAL_CASE
    }

    /** Validates and copies the lists. */
    public EnforcementAction {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(authority, "authority must not be null");
        Objects.requireNonNull(jurisdiction, "jurisdiction must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(date, "date must not be null");
        Objects.requireNonNull(party, "party must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        Objects.requireNonNull(violations, "violations must not be null");
        programs = List.copyOf(Objects.requireNonNull(programs, "programs must not be null"));
        targets = List.copyOf(Objects.requireNonNull(targets, "targets must not be null"));
        Objects.requireNonNull(summary, "summary must not be null");
        Objects.requireNonNull(url, "url must not be null");
    }

    /**
     * Returns a copy with the programs and targets read from the notice.
     *
     * @param found the programs found
     * @return the copy
     */
    public EnforcementAction withPrograms(SanctionsPrograms.Found found) {
        return new EnforcementAction(
                id,
                authority,
                jurisdiction,
                kind,
                date,
                party,
                amount,
                currency,
                violations,
                found.programs(),
                found.targets(),
                summary,
                url);
    }

    /**
     * Returns a copy with another amount.
     *
     * @param newAmount the amount
     * @return the copy
     */
    public EnforcementAction withAmount(Optional<Long> newAmount) {
        return new EnforcementAction(
                id,
                authority,
                jurisdiction,
                kind,
                date,
                party,
                newAmount,
                currency,
                violations,
                programs,
                targets,
                summary,
                url);
    }
}
