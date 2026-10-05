package dev.sieve.ingest.media;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The words that make an article about a name adverse: financial crime, corruption, terrorism,
 * sanctions and the steps of a criminal case.
 *
 * <p>The list is short on purpose. A news index matches each word anywhere in the text, so every
 * extra word adds articles that only mention it in passing.
 */
public final class AdverseTerms {

    /** The default terms, English only, as the news index matches them. */
    public static final List<String> DEFAULT =
            List.of(
                    "money laundering",
                    "fraud",
                    "bribery",
                    "corruption",
                    "embezzlement",
                    "sanctions",
                    "terrorism",
                    "terrorist financing",
                    "tax evasion",
                    "smuggling",
                    "trafficking",
                    "indicted",
                    "convicted",
                    "arrested",
                    "charged");

    private AdverseTerms() {}

    /**
     * Returns the terms that occur in a headline, ignoring case and matching whole words. A
     * multi-word term matches when its words appear next to each other.
     *
     * @param title the headline
     * @param terms the terms to look for
     * @return the matching terms in the order given
     */
    public static List<String> foundIn(String title, List<String> terms) {
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(terms, "terms must not be null");
        String padded = " " + words(title) + " ";
        List<String> found = new ArrayList<>();
        for (String term : terms) {
            if (padded.contains(" " + words(term) + " ")) {
                found.add(term);
            }
        }
        return found;
    }

    private static String words(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }
}
