package dev.sieve.benchmark.eval;

import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.Identifier;
import dev.sieve.core.model.IdentifierType;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.match.NameNormalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds labelled screening queries from loaded list data.
 *
 * <p>Three kinds of query, each with a label known without human review:
 *
 * <ul>
 *   <li>{@link Kind#SAME_ENTITY_OTHER_LIST}: the primary name of a record on one list, which must
 *       find a record on another list that states the same strong identifier (passport, national
 *       id, IMO, LEI, BIC, tax or registration number). The two records are the same party as
 *       written by two publishers, so this measures recall on real spelling differences.
 *   <li>{@link Kind#SPELLING_VARIANT}: a listed name with one controlled change (a typo, two
 *       letters swapped, a letter dropped, a transliteration variant, word order reversed, a middle
 *       name dropped), which must find the record it came from.
 *   <li>{@link Kind#UNLISTED_CUSTOMER}: a generated customer name (common given name and surname,
 *       or a generic company name) that no list was asked for. Any hit is an alert an analyst would
 *       have to clear, so the share of these queries that raise one is the false-positive rate of
 *       name-only screening.
 * </ul>
 */
public final class EvaluationSet {

    /** Query kinds; see the class comment. */
    public enum Kind {
        SAME_ENTITY_OTHER_LIST,
        SPELLING_VARIANT,
        UNLISTED_CUSTOMER
    }

    /**
     * One labelled query.
     *
     * @param name the name to screen
     * @param kind how the label was obtained
     * @param expectedId the record the query must find, empty for unlisted customers
     * @param sourceId the record the query was built from, empty for unlisted customers
     * @param differentSpelling for same-entity queries, whether the expected record carries no name
     *     equal to the query after normalisation (the cases a plain exact match cannot find)
     * @param note what was done to build the query, for the sample printed with the report
     */
    public record Query(
            String name,
            Kind kind,
            String expectedId,
            String sourceId,
            boolean differentSpelling,
            String note) {

        public Query {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(expectedId, "expectedId must not be null");
            Objects.requireNonNull(sourceId, "sourceId must not be null");
            Objects.requireNonNull(note, "note must not be null");
        }
    }

    /** Identifier types that name one party; generic and crypto values are left out. */
    private static final Set<IdentifierType> STRONG =
            EnumSet.of(
                    IdentifierType.PASSPORT,
                    IdentifierType.NATIONAL_ID,
                    IdentifierType.IMO_NUMBER,
                    IdentifierType.LEI,
                    IdentifierType.SWIFT_BIC,
                    IdentifierType.TAX_ID,
                    IdentifierType.REGISTRATION_NUMBER,
                    IdentifierType.BUSINESS_REGISTRATION);

    /** A value held by more records than this is a placeholder or a shared number, not an id. */
    private static final int MAX_GROUP = 15;

    private static final int MIN_VALUE_LENGTH = 6;

    private EvaluationSet() {}

    /**
     * Pairs of records on different lists that state the same strong identifier.
     *
     * @param entities every loaded record
     * @param max the most queries to return, sampled with {@code random}
     * @param random the seeded source of randomness
     * @return one query per sampled ordered pair
     */
    public static List<Query> sameEntityOtherList(
            Collection<SanctionedEntity> entities, int max, Random random) {
        Map<String, List<Holder>> byValue = new HashMap<>();
        for (SanctionedEntity e : entities) {
            if (e.entityType() == EntityType.CRYPTO_WALLET || !hasLetters(e.primaryName())) {
                continue;
            }
            for (Identifier id : e.identifiers()) {
                if (!STRONG.contains(id.type())) {
                    continue;
                }
                String value = id.value().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
                if (value.length() < MIN_VALUE_LENGTH || value.chars().distinct().count() < 3) {
                    continue;
                }
                byValue.computeIfAbsent(id.type() + ":" + value, k -> new ArrayList<>())
                        .add(new Holder(e, id));
            }
        }

        // Directed pairs, each counted once even when the two records share several identifiers
        Map<String, Query> pairs = new HashMap<>();
        byValue.keySet().stream()
                .sorted()
                .forEach(
                        key -> {
                            List<Holder> group = byValue.get(key);
                            if (group.size() < 2 || group.size() > MAX_GROUP) {
                                return;
                            }
                            for (Holder a : group) {
                                for (Holder b : group) {
                                    if (a.entity().listSource() == b.entity().listSource()
                                            || isPerson(a.entity()) != isPerson(b.entity())
                                            || countriesDisagree(a.identifier(), b.identifier())) {
                                        continue;
                                    }
                                    String pairKey = a.entity().id() + "->" + b.entity().id();
                                    pairs.computeIfAbsent(
                                            pairKey,
                                            k ->
                                                    new Query(
                                                            a.entity().primaryName().fullName(),
                                                            Kind.SAME_ENTITY_OTHER_LIST,
                                                            b.entity().id(),
                                                            a.entity().id(),
                                                            !sharesName(a.entity(), b.entity()),
                                                            key));
                                }
                            }
                        });
        List<Query> all = new ArrayList<>(pairs.values());
        all.sort(Comparator.comparing(q -> q.sourceId() + "->" + q.expectedId()));
        return sample(all, max, random);
    }

    /**
     * Listed names with one controlled change each, cycling through the change kinds.
     *
     * @param entities every loaded record
     * @param max the number of queries to build
     * @param random the seeded source of randomness
     * @return the queries, at most {@code max}
     */
    public static List<Query> spellingVariants(
            Collection<SanctionedEntity> entities, int max, Random random) {
        List<SanctionedEntity> eligible =
                entities.stream()
                        .filter(e -> e.entityType() != EntityType.CRYPTO_WALLET)
                        .filter(e -> e.entityType() != EntityType.SECURITY)
                        .filter(e -> Perturbations.isLatinMultiWord(e.primaryName().fullName()))
                        .sorted(Comparator.comparing(SanctionedEntity::id))
                        .toList();
        List<Query> queries = new ArrayList<>();
        if (eligible.isEmpty()) {
            return queries;
        }
        Perturbations.Change[] changes = Perturbations.Change.values();
        int attempts = 0;
        while (queries.size() < max && attempts < max * 20) {
            attempts++;
            SanctionedEntity e = eligible.get(random.nextInt(eligible.size()));
            // Cycle by attempt so a change that does not apply to a name moves on to the next kind
            Perturbations.Change change = changes[attempts % changes.length];
            String original = e.primaryName().fullName();
            String varied = Perturbations.apply(original, change, random);
            if (varied == null || varied.equalsIgnoreCase(original)) {
                continue;
            }
            queries.add(
                    new Query(
                            varied,
                            Kind.SPELLING_VARIANT,
                            e.id(),
                            e.id(),
                            true,
                            change + ": " + original));
        }
        return queries;
    }

    /**
     * Generated customer names that no list was asked for.
     *
     * @param names the given names, surnames and company words to combine
     * @param max the number of queries to build
     * @param random the seeded source of randomness
     * @return the queries
     */
    public static List<Query> unlistedCustomers(NameSamples names, int max, Random random) {
        List<Query> queries = new ArrayList<>(max);
        Set<String> seen = new java.util.HashSet<>();
        int attempts = 0;
        while (queries.size() < max && attempts < max * 20) {
            attempts++;
            boolean company = random.nextInt(5) == 0;
            String name = company ? names.company(random) : names.person(random);
            if (seen.add(name)) {
                queries.add(
                        new Query(
                                name,
                                Kind.UNLISTED_CUSTOMER,
                                "",
                                "",
                                false,
                                company ? "company" : "person"));
            }
        }
        return queries;
    }

    private static List<Query> sample(List<Query> all, int max, Random random) {
        if (all.size() <= max) {
            return all;
        }
        List<Query> copy = new ArrayList<>(all);
        java.util.Collections.shuffle(copy, random);
        return new ArrayList<>(copy.subList(0, max));
    }

    private static boolean isPerson(SanctionedEntity e) {
        return e.entityType() == EntityType.INDIVIDUAL;
    }

    private static boolean countriesDisagree(Identifier a, Identifier b) {
        String ca = a.issuingCountry();
        String cb = b.issuingCountry();
        return ca != null
                && cb != null
                && !ca.isBlank()
                && !cb.isBlank()
                && !ca.trim().equalsIgnoreCase(cb.trim());
    }

    private static boolean sharesName(SanctionedEntity a, SanctionedEntity b) {
        String query = NameNormalizer.normalize(a.primaryName().fullName(), a.entityType());
        return allNames(b).stream()
                .map(n -> NameNormalizer.normalize(n, b.entityType()))
                .anyMatch(query::equals);
    }

    private static List<String> allNames(SanctionedEntity e) {
        List<String> names = new ArrayList<>();
        names.add(e.primaryName().fullName());
        names.addAll(e.aliases().stream().map(NameInfo::fullName).collect(Collectors.toList()));
        return names;
    }

    private static boolean hasLetters(NameInfo name) {
        return name.fullName().codePoints().anyMatch(Character::isLetter);
    }

    private record Holder(SanctionedEntity entity, Identifier identifier) {}
}
