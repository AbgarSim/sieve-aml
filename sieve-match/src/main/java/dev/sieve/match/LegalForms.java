package dev.sieve.match;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Company legal forms ("LLC", "OOO", "GmbH", "Joint Stock Company"), removed from the start and end
 * of a normalized organisation name so that two companies do not match because both are an "LLC",
 * and "Rosneft PJSC" still matches "Rosneft".
 *
 * <p>Forms are written as {@link NameNormalizer} leaves them: lowercase ASCII words, so "ООО"
 * appears as its romanisation "ooo" and "S.p.A." as "s p a". A form is removed only when a word of
 * the name remains.
 */
final class LegalForms {

    /** Forms found before or after the name. */
    private static final String[] EITHER_END = {
        // English, and English renderings of foreign forms
        "llc",
        "l l c",
        "ltd",
        "limited",
        "inc",
        "incorporated",
        "corp",
        "corporation",
        "plc",
        "llp",
        "jsc",
        "pjsc",
        "ojsc",
        "cjsc",
        "co ltd",
        "company limited",
        "co limited",
        "limited company",
        "limited liability company",
        "limited liability partnership",
        "public limited company",
        "private limited",
        "private limited company",
        "joint stock company",
        "public joint stock company",
        "open joint stock company",
        "closed joint stock company",
        "non public joint stock company",
        "pvt ltd",
        "pte ltd",
        "pty ltd",
        "pty",
        "proprietary limited",
        "sdn bhd",
        "bhd",
        "fze",
        "fzco",
        "fzc",
        "fz llc",
        "wll",
        "w l l",
        "spc",
        "psc",
        "saog",
        "saoc",
        "ltda",
        // Russian, Ukrainian, Belarusian and Kazakh, romanised and spelled out
        "ooo",
        "oao",
        "zao",
        "pao",
        "ao",
        "nao",
        "tov",
        "pat",
        "prat",
        "too",
        "obshchestvo s ogranichennoy otvetstvennostyu",
        "aktsionernoye obshchestvo",
        "publichnoye aktsionernoye obshchestvo",
        "otkrytoye aktsionernoye obshchestvo",
        "zakrytoye aktsionernoye obshchestvo",
        "nepublichnoye aktsionernoye obshchestvo",
        "tovarishchestvo s ogranichennoy otvetstvennostyu",
        // German, Dutch, French, Spanish, Italian, Portuguese
        "gmbh",
        "mbh",
        "gmbh co kg",
        "kgaa",
        "ohg",
        "b v",
        "n v",
        "s a",
        "sas",
        "s a s",
        "sarl",
        "s a r l",
        "sasu",
        "eurl",
        "srl",
        "s r l",
        "spa",
        "s p a",
        "sa de cv",
        "s a de c v",
        "s de rl de cv",
        "s de r l de c v",
        "eireli",
        "lda",
        "aktiengesellschaft",
        "gesellschaft mit beschrankter haftung",
        "kommanditgesellschaft",
        "societe anonyme",
        "sociedad anonima",
        "societa per azioni",
        "sociedade anonima",
        "naamloze vennootschap",
        "besloten vennootschap",
        // Nordic, Baltic, Central and Eastern Europe, Turkey
        "asa",
        "as",
        "a s",
        "aps",
        "oyj",
        "ehf",
        "uab",
        "sia",
        "sp z o o",
        "sro",
        "s r o",
        "spol s r o",
        "kft",
        "zrt",
        "nyrt",
        "doo",
        "d o o",
        "ead",
        "eood",
        "anonim sirketi",
        "ltd sti",
        // Asia
        "pt",
        "tbk",
        "pcl",
        "public company limited",
        "kabushiki kaisha",
        "godo kaisha",
        "yugen kaisha",
    };

    /** Short forms that are also words or name parts, removed only after the name. */
    private static final String[] END_ONLY = {
        "co", "company", "lp", "ag", "kg", "ug", "bv", "nv", "sa", "sl", "slu", "sca", "scs",
        "snc", "ab", "oy", "hf", "ou", "dd", "ad", "kk", "cv", "c v", "nl", "cia", "sti",
    };

    private static final String[][] LEADING = tokenised(EITHER_END);
    private static final String[][] TRAILING = tokenised(concat(EITHER_END, END_ONLY));

    private LegalForms() {
        throw new AssertionError("Utility class — do not instantiate");
    }

    /**
     * Removes legal forms from the start and end of a normalized name.
     *
     * @param normalized a name normalized by {@link NameNormalizer}
     * @return the name without its legal forms, or the name itself if nothing else would remain
     */
    static String strip(String normalized) {
        if (normalized.isEmpty()) {
            return normalized;
        }
        String[] tokens = normalized.split(" ");
        int from = 0;
        int to = tokens.length;
        boolean changed = true;
        while (changed) {
            changed = false;
            int lead = longestMatch(tokens, from, to, LEADING, true);
            if (lead > 0) {
                from += lead;
                changed = true;
            }
            int trail = longestMatch(tokens, from, to, TRAILING, false);
            if (trail > 0) {
                to -= trail;
                changed = true;
            }
        }
        if (from == 0 && to == tokens.length) {
            return normalized;
        }
        return String.join(" ", Arrays.asList(tokens).subList(from, to));
    }

    /**
     * Number of tokens of the longest form at the start (or end) of {@code tokens[from, to)} that
     * leaves at least one token, or 0 if there is none.
     */
    private static int longestMatch(
            String[] tokens, int from, int to, String[][] forms, boolean atStart) {
        for (String[] form : forms) {
            if (form.length >= to - from) {
                continue;
            }
            int offset = atStart ? from : to - form.length;
            boolean matches = true;
            for (int i = 0; i < form.length && matches; i++) {
                matches = form[i].equals(tokens[offset + i]);
            }
            if (matches) {
                return form.length;
            }
        }
        return 0;
    }

    /** Distinct forms split into words, longest first so the longest form wins. */
    private static String[][] tokenised(String[] forms) {
        List<String[]> out = new ArrayList<>();
        Arrays.stream(forms)
                .distinct()
                .map(form -> form.split(" "))
                .sorted(Comparator.comparingInt((String[] form) -> form.length).reversed())
                .forEach(out::add);
        return out.toArray(String[][]::new);
    }

    private static String[] concat(String[] a, String[] b) {
        String[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
