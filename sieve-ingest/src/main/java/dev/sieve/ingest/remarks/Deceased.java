package dev.sieve.ingest.remarks;

import java.util.regex.Pattern;

/**
 * Reads whether a list's free text says the person is dead.
 *
 * <p>Lists state a death in their comments rather than in a field: "Reportedly deceased",
 * "Confirmed to have died in 2011", "was killed in an air strike". The text is read sentence by
 * sentence, and a sentence counts only when it is about the person, not a relative ("father's name
 * is Ahmed (deceased)"). The verbs are kept narrow: "killed" counts only in the passive, so a
 * sentence about an attack the person carried out does not; "death" never counts, so a death
 * sentence does not.
 */
public final class Deceased {

    private static final Pattern SENTENCES = Pattern.compile("(?<=[.;!?])\\s+");
    private static final Pattern STATEMENT =
            Pattern.compile(
                    "(?i)\\bdeceased\\b"
                            + "|\\b(?:is|was|reportedly|confirmed|believed|presumed|declared|be)\\s+(?:to\\s+be\\s+)?dead\\b"
                            + "|\\b(?:has\\s+)?died\\b"
                            + "|\\bpassed\\s+away\\b"
                            + "|\\b(?:was|were|been|reportedly|confirmed)\\s+(?:reportedly\\s+)?killed\\b");
    private static final Pattern RELATIVE =
            Pattern.compile(
                    "(?i)\\b(?:father|mother|parent|brother|sister|son|daughter|wife|husband|spouse|"
                            + "uncle|aunt|cousin|grandfather|grandmother|nephew|niece|child|children)(?:'s|s)?\\b");

    private Deceased() {}

    /**
     * Whether the text says the person is dead.
     *
     * @param text a list's comments or remarks, may be {@code null}
     * @return {@link Boolean#TRUE} when a sentence about the person says so, otherwise {@code
     *     null}, since silence does not mean the person is alive
     */
    public static Boolean statedIn(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        for (String sentence : SENTENCES.split(text)) {
            if (STATEMENT.matcher(sentence).find() && !RELATIVE.matcher(sentence).find()) {
                return Boolean.TRUE;
            }
        }
        return null;
    }
}
