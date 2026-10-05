package dev.sieve.benchmark.eval;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** Controlled one-step changes to a name, as a screening query might misspell it. */
final class Perturbations {

    /** The change kinds, applied in turn by {@link EvaluationSet#spellingVariants}. */
    enum Change {
        /** One letter replaced by a random letter. */
        TYPO,
        /** Two neighbouring letters swapped. */
        SWAP_LETTERS,
        /** One letter dropped. */
        DROP_LETTER,
        /** One common romanisation difference, such as kh/h, ou/u, y/i, ph/f, sh/sch. */
        TRANSLITERATION,
        /** The words in reverse order ("Doe John" for "John Doe"). */
        REVERSE_ORDER,
        /** A middle word dropped from a name of three or more words. */
        DROP_MIDDLE_WORD
    }

    /** Pairs replaced in either direction; the first one found in the name is used. */
    private static final String[][] ROMANISATION = {
        {"kh", "h"}, {"ou", "u"}, {"ph", "f"}, {"sch", "sh"}, {"ck", "k"}, {"ya", "ia"},
        {"ye", "ie"}, {"yu", "iu"}, {"ee", "i"}, {"oo", "u"}, {"dj", "j"}, {"y", "i"},
        {"w", "v"}, {"ei", "ey"}, {"aa", "a"}, {"q", "k"}
    };

    private Perturbations() {}

    /**
     * Whether a name is written in Latin letters and has at least two words, so that every change
     * kind applies to it.
     */
    static boolean isLatinMultiWord(String name) {
        String[] words = name.trim().split("\\s+");
        if (words.length < 2) {
            return false;
        }
        return name.codePoints()
                .filter(Character::isLetter)
                .allMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.LATIN);
    }

    /**
     * Applies one change.
     *
     * @return the changed name, or {@code null} when the change does not apply to this name
     */
    static String apply(String name, Change change, Random random) {
        return switch (change) {
            case TYPO -> editLetter(name, random, 0);
            case SWAP_LETTERS -> editLetter(name, random, 1);
            case DROP_LETTER -> editLetter(name, random, 2);
            case TRANSLITERATION -> romanise(name);
            case REVERSE_ORDER -> reverse(name);
            case DROP_MIDDLE_WORD -> dropMiddle(name);
        };
    }

    /** Edits a letter at a random position of a word of four letters or more. */
    private static String editLetter(String name, Random random, int mode) {
        List<Integer> positions = new ArrayList<>();
        int start = 0;
        for (String word : name.split(" ", -1)) {
            if (word.length() >= 4 && word.chars().allMatch(Character::isLetter)) {
                int last = mode == 1 ? word.length() - 1 : word.length();
                for (int i = 0; i < last; i++) {
                    positions.add(start + i);
                }
            }
            start += word.length() + 1;
        }
        if (positions.isEmpty()) {
            return null;
        }
        int at = positions.get(random.nextInt(positions.size()));
        StringBuilder sb = new StringBuilder(name);
        switch (mode) {
            case 0 -> {
                char original = sb.charAt(at);
                char replacement;
                do {
                    replacement = (char) ('a' + random.nextInt(26));
                } while (Character.toLowerCase(original) == replacement);
                sb.setCharAt(
                        at,
                        Character.isUpperCase(original)
                                ? Character.toUpperCase(replacement)
                                : replacement);
            }
            case 1 -> {
                char a = sb.charAt(at);
                char b = sb.charAt(at + 1);
                if (Character.toLowerCase(a) == Character.toLowerCase(b)) {
                    return null;
                }
                sb.setCharAt(at, b);
                sb.setCharAt(at + 1, a);
            }
            default -> sb.deleteCharAt(at);
        }
        return sb.toString();
    }

    private static String romanise(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String[] pair : ROMANISATION) {
            for (int dir = 0; dir < 2; dir++) {
                String from = pair[dir];
                String to = pair[1 - dir];
                int at = lower.indexOf(from);
                if (at >= 0) {
                    String replaced = matchCase(name.substring(at, at + from.length()), to);
                    return name.substring(0, at) + replaced + name.substring(at + from.length());
                }
            }
        }
        return null;
    }

    private static String matchCase(String original, String replacement) {
        if (original.equals(original.toUpperCase(Locale.ROOT))) {
            return replacement.toUpperCase(Locale.ROOT);
        }
        if (Character.isUpperCase(original.charAt(0))) {
            return Character.toUpperCase(replacement.charAt(0)) + replacement.substring(1);
        }
        return replacement;
    }

    private static String reverse(String name) {
        List<String> words = new ArrayList<>(Arrays.asList(name.trim().split("\\s+")));
        if (words.size() < 2) {
            return null;
        }
        java.util.Collections.reverse(words);
        return String.join(" ", words);
    }

    private static String dropMiddle(String name) {
        String[] words = name.trim().split("\\s+");
        if (words.length < 3) {
            return null;
        }
        List<String> kept = new ArrayList<>(Arrays.asList(words));
        kept.remove(1);
        return String.join(" ", kept);
    }
}
