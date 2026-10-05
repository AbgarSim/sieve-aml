package dev.sieve.match;

import com.ibm.icu.text.Transliterator;
import java.lang.Character.UnicodeScript;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

/**
 * Rewrites a name in Latin letters without diacritics, so names written in different scripts or
 * with different accents produce the same matching key.
 *
 * <p>Each run of one script is romanised with the scheme that sanctions lists most often use for
 * it: BGN/PCGN for Cyrillic ("Щербаков" becomes "Shcherbakov"), UNGEGN for Greek, pinyin for
 * Chinese characters, and ICU's general rules for every other script. Chinese and Korean personal
 * names are written as one syllable followed by the rest joined, as lists write them ("习近平" becomes
 * "Xi Jinping"). Latin letters then lose their diacritics and ligatures ("Müller" becomes "Muller",
 * "Straße" becomes "Strasse").
 *
 * <p>Arabic and Hebrew are written without most vowels, so their romanisation ("محمد" becomes
 * "mhmd") matches the same name in the original script but not a vowelled Latin spelling.
 */
final class ScriptTransliterator {

    /** Longest run of Chinese or Korean syllables read as a personal name. */
    private static final int MAX_PERSONAL_NAME_SYLLABLES = 4;

    private ScriptTransliterator() {
        throw new AssertionError("Utility class — do not instantiate");
    }

    /**
     * Returns the name in Latin letters, without diacritics. Characters that are neither letters
     * nor digits are kept as they are, so the caller decides how punctuation splits words.
     *
     * @param name the name, not {@code null}
     * @return the romanised name
     */
    static String toLatin(String name) {
        String composed = Normalizer.normalize(name, Normalizer.Form.NFKC);
        if (isAscii(composed)) {
            return composed;
        }
        StringBuilder out = new StringBuilder(composed.length() + 8);
        int start = 0;
        UnicodeScript runScript = null;
        for (int i = 0; i < composed.length(); ) {
            int cp = composed.codePointAt(i);
            UnicodeScript script = scriptOf(cp);
            if (script != null && script != runScript) {
                if (runScript != null) {
                    appendRun(out, composed.substring(start, i), runScript);
                    start = i;
                }
                runScript = script;
            }
            i += Character.charCount(cp);
        }
        appendRun(out, composed.substring(start), runScript);
        return foldLatin(out);
    }

    /**
     * The script a character belongs to for romanisation, or {@code null} for a character that
     * joins whatever run it is in (spaces, punctuation, digits, combining marks).
     */
    private static UnicodeScript scriptOf(int cp) {
        UnicodeScript script = UnicodeScript.of(cp);
        return script == UnicodeScript.COMMON || script == UnicodeScript.INHERITED ? null : script;
    }

    private static void appendRun(StringBuilder out, String run, UnicodeScript script) {
        if (script == null || script == UnicodeScript.LATIN) {
            out.append(run);
            return;
        }
        switch (script) {
            case CYRILLIC -> out.append(Rules.romanise(Rules.CYRILLIC, run));
            case GREEK -> out.append(Rules.romanise(Rules.GREEK, run));
            case HAN -> appendSyllables(out, run, Rules.HAN);
            case HANGUL -> appendSyllables(out, run, Rules.HANGUL);
            default -> out.append(Rules.romanise(Rules.ANY, run));
        }
    }

    /**
     * Romanises Chinese characters or Korean syllables one by one. A word of two to four syllables
     * is read as a personal name: the family name, then the given name joined into one word.
     */
    private static void appendSyllables(StringBuilder out, String run, Transliterator rules) {
        List<String> word = new ArrayList<>();
        for (int i = 0; i < run.length(); ) {
            int cp = run.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetter(cp)) {
                word.add(Rules.romanise(rules, Character.toString(cp)).replace("-", "").strip());
            } else {
                appendWord(out, word);
                out.appendCodePoint(cp);
            }
        }
        appendWord(out, word);
    }

    private static void appendWord(StringBuilder out, List<String> syllables) {
        if (syllables.isEmpty()) {
            return;
        }
        boolean personalName =
                syllables.size() >= 2 && syllables.size() <= MAX_PERSONAL_NAME_SYLLABLES;
        out.append(' ');
        for (int i = 0; i < syllables.size(); i++) {
            if (i > 0 && (!personalName || i == 1)) {
                out.append(' ');
            }
            out.append(syllables.get(i));
        }
        out.append(' ');
        syllables.clear();
    }

    /**
     * Removes diacritics and spells out Latin letters that have no ASCII base letter ("ß", "æ",
     * "ø", "ł"). Letters of other scripts that could not be romanised are kept.
     */
    static String foldLatin(CharSequence text) {
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
        StringBuilder out = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length(); i++) {
            char c = decomposed.charAt(i);
            if (c < 0x80) {
                out.append(c);
                continue;
            }
            int type = Character.getType(c);
            if (type == Character.NON_SPACING_MARK
                    || type == Character.ENCLOSING_MARK
                    || type == Character.FORMAT) {
                continue;
            }
            String spelled = spellOut(c);
            out.append(spelled != null ? spelled : String.valueOf(c));
        }
        return out.toString();
    }

    private static String spellOut(char c) {
        return switch (c) {
            case 'ß' -> "ss";
            case 'ẞ' -> "SS";
            case 'æ' -> "ae";
            case 'Æ' -> "AE";
            case 'œ' -> "oe";
            case 'Œ' -> "OE";
            case 'ø' -> "o";
            case 'Ø' -> "O";
            case 'ł' -> "l";
            case 'Ł' -> "L";
            case 'đ', 'ð' -> "d";
            case 'Đ', 'Ð' -> "D";
            case 'þ' -> "th";
            case 'Þ' -> "TH";
            case 'ı' -> "i";
            case 'ħ' -> "h";
            case 'Ħ' -> "H";
            case 'ŀ' -> "l";
            case 'Ŀ' -> "L";
            case 'ĳ' -> "ij";
            case 'Ĳ' -> "IJ";
            case 'ŋ' -> "ng";
            case 'Ŋ' -> "NG";
            case 'ə', 'ǝ' -> "e";
            case 'Ə', 'Ǝ' -> "E";
            // Apostrophe-like letters and quotes, as romanisations write ayn, hamza and soft signs
            case 'ʹ', 'ʺ', 'ʻ', 'ʼ', 'ʽ', 'ʾ', 'ʿ', '‘', '’', '´', '′', '″' -> "'";
            default -> null;
        };
    }

    private static boolean isAscii(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) >= 0x80) {
                return false;
            }
        }
        return true;
    }

    /**
     * ICU transliterators, loaded on first use since most names are already ASCII. A transliterator
     * instance is not safe for concurrent use, so each call holds its lock.
     */
    private static final class Rules {
        static final Transliterator CYRILLIC =
                Transliterator.getInstance("Russian-Latin/BGN; Any-Latin");
        static final Transliterator GREEK =
                Transliterator.getInstance("Greek-Latin/UNGEGN; Any-Latin");
        static final Transliterator HAN = Transliterator.getInstance("Han-Latin");
        static final Transliterator HANGUL = Transliterator.getInstance("Hangul-Latin");
        static final Transliterator ANY = Transliterator.getInstance("Any-Latin");

        static String romanise(Transliterator rules, String text) {
            synchronized (rules) {
                return rules.transliterate(text);
            }
        }
    }
}
