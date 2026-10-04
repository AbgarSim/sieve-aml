package dev.sieve.ingest.relations;

import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.Relation;
import dev.sieve.core.model.RelationType;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Reads how a list's free text words the link between an entry and a party the text mentions, such
 * as "brother of", "member of" or "owned by", and turns it into a relation.
 *
 * <p>The phrase nearest before the mention decides the relation's kind: kinship words give {@link
 * RelationType#FAMILY}, "associated with" gives {@link RelationType#ASSOCIATE}, leadership words
 * give {@link RelationType#DIRECTORSHIP} and "owner of" gives {@link RelationType#OWNERSHIP}, each
 * with the phrase as the relation's role. Every other phrase, and a mention with no phrase before
 * it, gives {@link RelationType#LINKED}, so "subsidiary of" and "owned by" (where the mentioned
 * party is the owner, not the entry) stay links with that role rather than ownership turned around.
 * A phrase counts only within {@value #WINDOW} characters of the mention and in the same sentence
 * or semicolon-separated clause.
 */
public final class RelationPhrases {

    /** How a list's text words a link, and the relation kind it implies. */
    public record Phrase(String words, RelationType type) {}

    /** How far before a mention a phrase is looked for. */
    static final int WINDOW = 120;

    private static final List<Phrase> PHRASES =
            List.of(
                    new Phrase("son of", RelationType.FAMILY),
                    new Phrase("daughter of", RelationType.FAMILY),
                    new Phrase("father of", RelationType.FAMILY),
                    new Phrase("mother of", RelationType.FAMILY),
                    new Phrase("brother of", RelationType.FAMILY),
                    new Phrase("sister of", RelationType.FAMILY),
                    new Phrase("wife of", RelationType.FAMILY),
                    new Phrase("husband of", RelationType.FAMILY),
                    new Phrase("spouse of", RelationType.FAMILY),
                    new Phrase("widow of", RelationType.FAMILY),
                    new Phrase("nephew of", RelationType.FAMILY),
                    new Phrase("niece of", RelationType.FAMILY),
                    new Phrase("uncle of", RelationType.FAMILY),
                    new Phrase("cousin of", RelationType.FAMILY),
                    new Phrase("son-in-law of", RelationType.FAMILY),
                    new Phrase("brother-in-law of", RelationType.FAMILY),
                    new Phrase("father-in-law of", RelationType.FAMILY),
                    new Phrase("married to", RelationType.FAMILY),
                    new Phrase("linked by marriage to", RelationType.FAMILY),
                    new Phrase("associated with", RelationType.ASSOCIATE),
                    new Phrase("associate of", RelationType.ASSOCIATE),
                    new Phrase("close associate", RelationType.ASSOCIATE),
                    new Phrase("leader of", RelationType.DIRECTORSHIP),
                    new Phrase("head of", RelationType.DIRECTORSHIP),
                    new Phrase("commander of", RelationType.DIRECTORSHIP),
                    new Phrase("director of", RelationType.DIRECTORSHIP),
                    new Phrase("chairman of", RelationType.DIRECTORSHIP),
                    new Phrase("ceo of", RelationType.DIRECTORSHIP),
                    new Phrase("founder of", RelationType.DIRECTORSHIP),
                    new Phrase("deputy of", RelationType.DIRECTORSHIP),
                    new Phrase("deputy to", RelationType.DIRECTORSHIP),
                    new Phrase("in charge of", RelationType.DIRECTORSHIP),
                    new Phrase("owner of", RelationType.OWNERSHIP),
                    new Phrase("co-owner of", RelationType.OWNERSHIP),
                    new Phrase("owned by", RelationType.LINKED),
                    new Phrase("subsidiary of", RelationType.LINKED),
                    new Phrase("controlled by", RelationType.LINKED),
                    new Phrase("member of", RelationType.LINKED),
                    new Phrase("financier of", RelationType.LINKED),
                    new Phrase("financier for", RelationType.LINKED),
                    new Phrase("representative of", RelationType.LINKED),
                    new Phrase("official of", RelationType.LINKED),
                    new Phrase("officer of", RelationType.LINKED),
                    new Phrase("works for", RelationType.LINKED),
                    new Phrase("employed by", RelationType.LINKED),
                    new Phrase("part of", RelationType.LINKED),
                    new Phrase("branch of", RelationType.LINKED),
                    new Phrase("front for", RelationType.LINKED),
                    new Phrase("successor to", RelationType.LINKED),
                    new Phrase("formerly known as", RelationType.LINKED),
                    new Phrase("affiliated with", RelationType.LINKED),
                    new Phrase("affiliated to", RelationType.LINKED),
                    new Phrase("linked to", RelationType.LINKED),
                    new Phrase("related to", RelationType.LINKED),
                    new Phrase("funded by", RelationType.LINKED),
                    new Phrase("supported by", RelationType.LINKED),
                    new Phrase("support to", RelationType.LINKED),
                    new Phrase("second in command to", RelationType.LINKED),
                    new Phrase("acting for", RelationType.LINKED),
                    new Phrase("on behalf of", RelationType.LINKED));

    private RelationPhrases() {}

    /**
     * Returns the phrase closest before a mention, within {@value #WINDOW} characters and the same
     * sentence, if the text has one.
     *
     * @param text the free text
     * @param mentionStart the index where the mentioned party's name or reference begins
     * @return the phrase, or empty when no phrase precedes the mention
     */
    public static Optional<Phrase> before(String text, int mentionStart) {
        int from = Math.max(0, mentionStart - WINDOW);
        String window = text.substring(from, mentionStart).toLowerCase(Locale.ROOT);
        Phrase best = null;
        int bestAt = -1;
        for (Phrase phrase : PHRASES) {
            int at = window.lastIndexOf(phrase.words());
            if (at > bestAt && wholeWords(window, at, phrase.words().length())) {
                best = phrase;
                bestAt = at;
            }
        }
        if (best == null) {
            return Optional.empty();
        }
        String between = window.substring(bestAt + best.words().length());
        if (between.contains(". ") || between.contains(";")) {
            return Optional.empty();
        }
        return Optional.of(best);
    }

    /**
     * Builds the relation a phrase implies from the entry whose text mentions a party to that
     * party. Leadership and ownership of a person make no sense, so for a mentioned individual
     * those kinds become {@link RelationType#LINKED} with the same role.
     *
     * @param phrase the phrase before the mention, if any
     * @param targetId the mentioned party's id
     * @param targetKind the mentioned party's kind
     * @return the relation
     */
    public static Relation relation(
            Optional<Phrase> phrase, String targetId, EntityType targetKind) {
        if (phrase.isEmpty()) {
            return Relation.of(RelationType.LINKED, targetId);
        }
        RelationType type = phrase.get().type();
        if ((type == RelationType.DIRECTORSHIP || type == RelationType.OWNERSHIP)
                && targetKind == EntityType.INDIVIDUAL) {
            type = RelationType.LINKED;
        }
        return new Relation(type, targetId, phrase.get().words(), null, null, null);
    }

    private static boolean wholeWords(String window, int at, int length) {
        boolean startOk = at == 0 || !Character.isLetter(window.charAt(at - 1));
        int end = at + length;
        boolean endOk = end >= window.length() || !Character.isLetter(window.charAt(end));
        return startOk && endOk;
    }
}
