package dev.sieve.ingest.relations;

import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.Relation;
import dev.sieve.core.model.RelationType;
import dev.sieve.core.model.SanctionedEntity;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adds to a list's entries the relations their free text states about other entries of the same
 * list, found either through the reference numbers the text cites or through the names it mentions.
 *
 * <p>Each mention becomes a relation from the entry whose text it is to the entry it points at,
 * typed and labelled by the {@linkplain RelationPhrases phrase} before it. Mentions of the entry
 * itself, of references the list no longer carries and of names several entries share are left out;
 * a name counts as a mention only when it has at least {@value #MIN_NAME_WORDS} words and {@value
 * #MIN_NAME_LENGTH} characters once normalized, and the longest name at a position wins, so a
 * person's full name is not also read as a mention of someone who shares its first words.
 */
public final class RemarkRelations {

    private static final Logger log = LoggerFactory.getLogger(RemarkRelations.class);

    /** Shortest normalized name, in characters, that counts as a mention. */
    static final int MIN_NAME_LENGTH = 8;

    /** Shortest normalized name, in words, that counts as a mention. */
    static final int MIN_NAME_WORDS = 2;

    /** Longest name, in words, looked for in a text. */
    static final int MAX_NAME_WORDS = 12;

    private RemarkRelations() {}

    /** A mention of another entry at an offset into an entity's remarks. */
    private record Mention(int offset, String targetId) {}

    /** What one entity's remarks mention, and how many references or names could not be used. */
    private record Scan(List<Mention> mentions, int unresolved, int ambiguous) {}

    /** The words of a text, lower-cased and without accents or punctuation, with their offsets. */
    record Words(List<String> words, List<Integer> offsets) {}

    /**
     * Adds the relations the entries' remarks state through reference numbers, such as the UN
     * list's {@code (QDe.004)}.
     *
     * @param entities every entry of the list
     * @param reference the pattern of a reference number, matched case-insensitively against the
     *     part of each entry's id after the prefix
     * @param idPrefix the prefix the list's ids put before the reference number
     * @param label the list's name for the log
     * @return the entries, in the same order, with their relations
     */
    public static List<SanctionedEntity> byReference(
            List<SanctionedEntity> entities, Pattern reference, String idPrefix, String label) {
        Map<String, String> ids = new HashMap<>();
        for (SanctionedEntity entity : entities) {
            if (entity.id().startsWith(idPrefix)) {
                ids.put(
                        entity.id().substring(idPrefix.length()).toLowerCase(Locale.ROOT),
                        entity.id());
            }
        }
        return resolve(
                entities,
                label,
                entity -> {
                    List<Mention> mentions = new ArrayList<>();
                    int unresolved = 0;
                    if (entity.remarks() != null) {
                        Matcher matcher = reference.matcher(entity.remarks());
                        while (matcher.find()) {
                            String id = ids.get(matcher.group().toLowerCase(Locale.ROOT));
                            if (id == null) {
                                unresolved++;
                            } else if (!id.equals(entity.id())) {
                                mentions.add(new Mention(matcher.start(), id));
                            }
                        }
                    }
                    return new Scan(mentions, unresolved, 0);
                });
    }

    /**
     * Adds the relations the entries' remarks state by naming other entries, by their primary name
     * or an alias.
     *
     * @param entities every entry of the list
     * @param label the list's name for the log
     * @return the entries, in the same order, with their relations
     */
    public static List<SanctionedEntity> byName(List<SanctionedEntity> entities, String label) {
        return byName(entities, label, name -> List.of(name.fullName()));
    }

    /**
     * Adds the relations the entries' remarks state by naming other entries, by any of the
     * spellings given for their primary name or aliases.
     *
     * @param entities every entry of the list
     * @param label the list's name for the log
     * @param spellings the ways the remarks may write a name, such as {@link #givenNameFirst}
     * @return the entries, in the same order, with their relations
     */
    public static List<SanctionedEntity> byName(
            List<SanctionedEntity> entities,
            String label,
            Function<NameInfo, List<String>> spellings) {
        Map<String, Set<String>> names = new HashMap<>();
        int longest = MIN_NAME_WORDS;
        for (SanctionedEntity entity : entities) {
            List<NameInfo> all = new ArrayList<>(entity.aliases());
            all.add(entity.primaryName());
            for (NameInfo name : all) {
                if (name == null || name.fullName() == null) {
                    continue;
                }
                for (String spelling : spellings.apply(name)) {
                    Words words = words(spelling);
                    String key = String.join(" ", words.words());
                    if (words.words().size() >= MIN_NAME_WORDS
                            && key.length() >= MIN_NAME_LENGTH) {
                        names.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(entity.id());
                        longest = Math.max(longest, words.words().size());
                    }
                }
            }
        }
        int maxWords = Math.min(longest, MAX_NAME_WORDS);
        return resolve(
                entities,
                label,
                entity -> {
                    List<Mention> mentions = new ArrayList<>();
                    int ambiguous = 0;
                    if (entity.remarks() == null) {
                        return new Scan(mentions, 0, 0);
                    }
                    Words text = words(entity.remarks());
                    List<String> words = text.words();
                    int i = 0;
                    while (i < words.size()) {
                        int taken = 0;
                        for (int n = Math.min(maxWords, words.size() - i);
                                n >= MIN_NAME_WORDS;
                                n--) {
                            Set<String> ids = names.get(String.join(" ", words.subList(i, i + n)));
                            if (ids == null) {
                                continue;
                            }
                            if (ids.size() > 1) {
                                ambiguous++;
                            } else if (!ids.contains(entity.id())) {
                                mentions.add(
                                        new Mention(text.offsets().get(i), ids.iterator().next()));
                            }
                            taken = n;
                            break;
                        }
                        i += Math.max(taken, 1);
                    }
                    return new Scan(mentions, 0, ambiguous);
                });
    }

    /**
     * Normalizes a name the way mentions are matched: lower case, no accents, letters and digits
     * only, single spaces between words.
     *
     * @param text the name
     * @return the normalized name, possibly empty
     */
    public static String normalize(String text) {
        return String.join(" ", words(text).words());
    }

    /** Splits a text into normalized words, remembering where each starts in the text. */
    static Words words(String text) {
        List<String> words = new ArrayList<>();
        List<Integer> offsets = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        int start = -1;
        for (int i = 0; i < text.length(); i++) {
            String folded =
                    Normalizer.normalize(String.valueOf(text.charAt(i)), Normalizer.Form.NFD)
                            .replaceAll("\\p{M}+", "")
                            .toLowerCase(Locale.ROOT);
            for (int j = 0; j < folded.length(); j++) {
                char c = folded.charAt(j);
                if (Character.isLetterOrDigit(c)) {
                    if (word.isEmpty()) {
                        start = i;
                    }
                    word.append(c);
                } else if (!word.isEmpty()) {
                    words.add(word.toString());
                    offsets.add(start);
                    word.setLength(0);
                }
            }
        }
        if (!word.isEmpty()) {
            words.add(word.toString());
            offsets.add(start);
        }
        return new Words(words, offsets);
    }

    private static List<SanctionedEntity> resolve(
            List<SanctionedEntity> entities,
            String label,
            Function<SanctionedEntity, Scan> scanner) {
        Map<String, SanctionedEntity> byId = new HashMap<>();
        for (SanctionedEntity entity : entities) {
            byId.put(entity.id(), entity);
        }
        Map<String, List<Relation>> added = new HashMap<>();
        int holders = 0;
        int mentions = 0;
        int unresolved = 0;
        int ambiguous = 0;
        for (SanctionedEntity entity : entities) {
            Scan scan = scanner.apply(entity);
            unresolved += scan.unresolved();
            ambiguous += scan.ambiguous();
            if (scan.mentions().isEmpty()) {
                continue;
            }
            holders++;
            List<Relation> relations = added.computeIfAbsent(entity.id(), id -> new ArrayList<>());
            for (Mention mention : scan.mentions()) {
                mentions++;
                SanctionedEntity target = byId.get(mention.targetId());
                Relation relation =
                        RelationPhrases.relation(
                                RelationPhrases.before(entity.remarks(), mention.offset()),
                                target.id(),
                                target.entityType());
                add(relations, relation);
            }
        }
        Map<RelationType, Integer> byType = new EnumMap<>(RelationType.class);
        for (List<Relation> list : added.values()) {
            for (Relation relation : list) {
                byType.merge(relation.type(), 1, Integer::sum);
            }
        }
        log.info(
                "{}: relations from remarks [entries={}, mentions={}, relations={}, byType={}, unresolved={}, ambiguous={}]",
                label,
                holders,
                mentions,
                byType.values().stream().mapToInt(Integer::intValue).sum(),
                byType,
                unresolved,
                ambiguous);
        List<SanctionedEntity> result = new ArrayList<>(entities.size());
        for (SanctionedEntity entity : entities) {
            List<Relation> more = added.get(entity.id());
            if (more == null || more.isEmpty()) {
                result.add(entity);
            } else {
                List<Relation> all = new ArrayList<>(entity.relations());
                all.addAll(more);
                result.add(entity.withRelations(all));
            }
        }
        return result;
    }

    /**
     * Adds a relation unless the list holds one to the same target already; a relation with a role
     * replaces one to the same target without.
     */
    private static void add(List<Relation> relations, Relation relation) {
        for (int i = 0; i < relations.size(); i++) {
            Relation existing = relations.get(i);
            if (existing.targetId().equals(relation.targetId())) {
                if (existing.role() == null && relation.role() != null) {
                    relations.set(i, relation);
                }
                return;
            }
        }
        relations.add(relation);
    }

    /**
     * Returns a name as the list writes it and, when the list writes the family name first (as in
     * "SMITH, John Edward"), also given names first ("John Edward SMITH"), the order free text
     * uses.
     *
     * @param name the name
     * @return the full name, then the given-name-first spelling when it differs
     */
    public static List<String> givenNameFirst(NameInfo name) {
        if (name.givenName() == null || name.familyName() == null) {
            return List.of(name.fullName());
        }
        StringBuilder natural = new StringBuilder(name.givenName().strip());
        if (name.middleName() != null && !name.middleName().isBlank()) {
            natural.append(' ').append(name.middleName().strip());
        }
        natural.append(' ').append(name.familyName().strip());
        return natural.toString().equals(name.fullName())
                ? List.of(name.fullName())
                : List.of(name.fullName(), natural.toString());
    }
}
