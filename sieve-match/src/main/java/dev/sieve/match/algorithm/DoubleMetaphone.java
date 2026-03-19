package dev.sieve.match.algorithm;

/**
 * Pure-Java implementation of Lawrence Philips' Double Metaphone algorithm.
 *
 * <p>Double Metaphone produces one or two phonetic codes for a given string, enabling matching of
 * names that sound alike but are spelled differently — a critical capability for sanctions
 * screening where transliteration variants are common (e.g., "Muammar Gaddafi" vs "Moammar
 * Qadhafi").
 *
 * <p>Unlike the original Metaphone, Double Metaphone accounts for many non-English spellings and
 * produces both a primary and an alternate code, handling cases where a name has multiple plausible
 * pronunciations.
 *
 * <p>This implementation follows the algorithm as described by Lawrence Philips (2000) and is
 * derived from the public domain reference implementation.
 *
 * @see <a href="https://en.wikipedia.org/wiki/Metaphone#Double_Metaphone">Double Metaphone
 *     (Wikipedia)</a>
 */
public final class DoubleMetaphone {

    /** Maximum length of the generated metaphone code. */
    private static final int MAX_CODE_LENGTH = 4;

    private DoubleMetaphone() {
        throw new AssertionError("Utility class — do not instantiate");
    }

    /**
     * Computes the primary and alternate Double Metaphone codes for the given string.
     *
     * @param input the string to encode, may be {@code null} or empty
     * @return a {@link PhoneticCode} containing primary and alternate codes
     */
    public static PhoneticCode encode(String input) {
        if (input == null || input.isEmpty()) {
            return new PhoneticCode("", "");
        }

        String value = input.toUpperCase();
        // Pad for safe lookahead
        value = value + "     ";

        StringBuilder primary = new StringBuilder(MAX_CODE_LENGTH);
        StringBuilder alternate = new StringBuilder(MAX_CODE_LENGTH);

        int length = input.length();
        int last = length - 1;
        int current = 0;

        // Skip initial silent letters
        if (stringAt(value, 0, "GN", "KN", "PN", "AE", "WR")) {
            current++;
        }

        // Initial X -> S
        if (charAt(value, 0) == 'X') {
            addCode(primary, alternate, "S");
            current++;
        }

        while (primary.length() < MAX_CODE_LENGTH || alternate.length() < MAX_CODE_LENGTH) {
            if (current >= length) {
                break;
            }

            char ch = charAt(value, current);

            switch (ch) {
                case 'A', 'E', 'I', 'O', 'U' -> {
                    if (current == 0) {
                        addCode(primary, alternate, "A");
                    }
                    current++;
                }
                case 'B' -> {
                    addCode(primary, alternate, "P");
                    current += charAt(value, current + 1) == 'B' ? 2 : 1;
                }
                case 'C' -> current = handleC(value, primary, alternate, current, length);
                case 'D' -> current = handleD(value, primary, alternate, current);
                case 'F' -> {
                    addCode(primary, alternate, "F");
                    current += charAt(value, current + 1) == 'F' ? 2 : 1;
                }
                case 'G' -> current = handleG(value, primary, alternate, current, length, last);
                case 'H' -> current = handleH(value, primary, alternate, current);
                case 'J' -> current = handleJ(value, primary, alternate, current, length, last);
                case 'K' -> {
                    addCode(primary, alternate, "K");
                    current += charAt(value, current + 1) == 'K' ? 2 : 1;
                }
                case 'L' -> {
                    addCode(primary, alternate, "L");
                    current += charAt(value, current + 1) == 'L' ? 2 : 1;
                }
                case 'M' -> {
                    addCode(primary, alternate, "M");
                    current +=
                            (stringAt(value, current - 1, "UMB")
                                                    && (current + 1 >= last
                                                            || stringAt(value, current + 2, "ER")))
                                            || charAt(value, current + 1) == 'M'
                                    ? 2
                                    : 1;
                }
                case 'N' -> {
                    addCode(primary, alternate, "N");
                    current += charAt(value, current + 1) == 'N' ? 2 : 1;
                }
                case '\u00D1' -> { // Ñ
                    addCode(primary, alternate, "N");
                    current++;
                }
                case 'P' -> current = handleP(value, primary, alternate, current);
                case 'Q' -> {
                    addCode(primary, alternate, "K");
                    current += charAt(value, current + 1) == 'Q' ? 2 : 1;
                }
                case 'R' -> current = handleR(value, primary, alternate, current, length, last);
                case 'S' -> current = handleS(value, primary, alternate, current, length, last);
                case 'T' -> current = handleT(value, primary, alternate, current, length);
                case 'V' -> {
                    addCode(primary, alternate, "F");
                    current += charAt(value, current + 1) == 'V' ? 2 : 1;
                }
                case 'W' -> current = handleW(value, primary, alternate, current, length);
                case 'X' -> current = handleX(value, primary, alternate, current);
                case 'Z' -> current = handleZ(value, primary, alternate, current);
                default -> current++;
            }
        }

        String p =
                primary.length() > MAX_CODE_LENGTH
                        ? primary.substring(0, MAX_CODE_LENGTH)
                        : primary.toString();
        String a =
                alternate.length() > MAX_CODE_LENGTH
                        ? alternate.substring(0, MAX_CODE_LENGTH)
                        : alternate.toString();

        return new PhoneticCode(p, a.isEmpty() ? p : a);
    }

    /**
     * Returns {@code true} if the primary or alternate codes of two strings match.
     *
     * @param s1 first string
     * @param s2 second string
     * @return true if any phonetic code combination matches
     */
    public static boolean isMatch(String s1, String s2) {
        PhoneticCode c1 = encode(s1);
        PhoneticCode c2 = encode(s2);
        if (c1.primary().isEmpty() || c2.primary().isEmpty()) {
            return false;
        }
        return c1.primary().equals(c2.primary())
                || c1.primary().equals(c2.alternate())
                || c1.alternate().equals(c2.primary())
                || c1.alternate().equals(c2.alternate());
    }

    /**
     * Phonetic code result containing primary and alternate encodings.
     *
     * @param primary the primary phonetic code
     * @param alternate the alternate phonetic code (may equal primary if only one encoding exists)
     */
    public record PhoneticCode(String primary, String alternate) {}

    // ── Handler methods for each consonant group ─────────────────────

    private static int handleC(
            String value, StringBuilder primary, StringBuilder alternate, int current, int length) {
        // Various Germanic
        if (current > 1
                && !isVowel(charAt(value, current - 2))
                && stringAt(value, current - 1, "ACH")
                && charAt(value, current + 2) != 'I'
                && (charAt(value, current + 2) != 'E'
                        || stringAt(value, current - 2, "BACHER", "MACHER"))) {
            addCode(primary, alternate, "K");
            return current + 2;
        }

        // Initial Caesar
        if (current == 0 && stringAt(value, current, "CAESAR")) {
            addCode(primary, alternate, "S");
            return current + 2;
        }

        // Italian CH
        if (stringAt(value, current, "CHIA")) {
            addCode(primary, alternate, "K");
            return current + 2;
        }

        if (stringAt(value, current, "CH")) {
            // Germanic CH -> K
            if (current > 0 && stringAt(value, current, "CHAE")) {
                addCode(primary, "K");
                addCode(alternate, "X");
                return current + 2;
            }

            // Greek roots
            if (current == 0
                    && (stringAt(value, current + 1, "HARAC", "HARIS")
                            || stringAt(value, current + 1, "HOR", "HYM", "HIA", "HEM"))
                    && !stringAt(value, 0, "CHORE")) {
                addCode(primary, alternate, "K");
                return current + 2;
            }

            // Germanic/Greek
            if (stringAt(value, 0, "VAN ", "VON ")
                    || stringAt(value, 0, "SCH")
                    || stringAt(value, current - 2, "ORCHES", "ARCHIT", "ORCHID")
                    || stringAt(value, current + 2, "T", "S")
                    || ((current == 0 || stringAt(value, current - 1, "A", "O", "U", "E"))
                            && stringAt(
                                    value,
                                    current + 2,
                                    "L",
                                    "R",
                                    "N",
                                    "M",
                                    "B",
                                    "H",
                                    "F",
                                    "V",
                                    "W",
                                    " "))) {
                addCode(primary, alternate, "K");
            } else {
                if (current > 0) {
                    if (stringAt(value, 0, "MC")) {
                        addCode(primary, alternate, "K");
                    } else {
                        addCode(primary, "X");
                        addCode(alternate, "K");
                    }
                } else {
                    addCode(primary, alternate, "X");
                }
            }
            return current + 2;
        }

        if (stringAt(value, current, "CZ") && !stringAt(value, current - 2, "WICZ")) {
            addCode(primary, "S");
            addCode(alternate, "X");
            return current + 2;
        }

        if (stringAt(value, current + 1, "CIA")) {
            addCode(primary, alternate, "X");
            return current + 3;
        }

        if (stringAt(value, current, "CC") && !(current == 1 && charAt(value, 0) == 'M')) {
            if (stringAt(value, current + 2, "I", "E", "H")
                    && !stringAt(value, current + 2, "HU")) {
                if ((current == 1 && charAt(value, current - 1) == 'A')
                        || stringAt(value, current - 1, "UCCEE", "UCCES")) {
                    addCode(primary, alternate, "KS");
                } else {
                    addCode(primary, alternate, "X");
                }
                return current + 3;
            } else {
                addCode(primary, alternate, "K");
                return current + 2;
            }
        }

        if (stringAt(value, current, "CK", "CG", "CQ")) {
            addCode(primary, alternate, "K");
            return current + 2;
        }

        if (stringAt(value, current, "CI", "CE", "CY")) {
            if (stringAt(value, current, "CIO", "CIE", "CIA")) {
                addCode(primary, "S");
                addCode(alternate, "X");
            } else {
                addCode(primary, alternate, "S");
            }
            return current + 2;
        }

        addCode(primary, alternate, "K");
        if (stringAt(value, current + 1, " C", " Q", " G")) {
            return current + 3;
        }
        if (stringAt(value, current + 1, "C", "K", "Q")
                && !stringAt(value, current + 1, "CE", "CI")) {
            return current + 2;
        }
        return current + 1;
    }

    private static int handleD(
            String value, StringBuilder primary, StringBuilder alternate, int current) {
        if (stringAt(value, current, "DG")) {
            if (stringAt(value, current + 2, "I", "E", "Y")) {
                addCode(primary, alternate, "J");
                return current + 3;
            } else {
                addCode(primary, alternate, "TK");
                return current + 2;
            }
        }
        if (stringAt(value, current, "DT", "DD")) {
            addCode(primary, alternate, "T");
            return current + 2;
        }
        addCode(primary, alternate, "T");
        return current + 1;
    }

    private static int handleG(
            String value,
            StringBuilder primary,
            StringBuilder alternate,
            int current,
            int length,
            int last) {
        if (charAt(value, current + 1) == 'H') {
            return handleGH(value, primary, alternate, current, length, last);
        }

        if (charAt(value, current + 1) == 'N') {
            if (current == 1 && isVowel(charAt(value, 0)) && !isSlavoGermanic(value, length)) {
                addCode(primary, "KN");
                addCode(alternate, "N");
            } else if (!stringAt(value, current + 2, "EY")
                    && charAt(value, current + 1) != 'Y'
                    && !isSlavoGermanic(value, length)) {
                addCode(primary, "N");
                addCode(alternate, "KN");
            } else {
                addCode(primary, alternate, "KN");
            }
            return current + 2;
        }

        // Italian e.g. BIAGGI
        if (stringAt(value, current + 1, "LI") && !isSlavoGermanic(value, length)) {
            addCode(primary, "KL");
            addCode(alternate, "L");
            return current + 2;
        }

        // -GER-, -GY- at beginning
        if (current == 0
                && (charAt(value, current + 1) == 'Y'
                        || stringAt(
                                value,
                                current + 1,
                                "ES",
                                "EP",
                                "EB",
                                "EL",
                                "EY",
                                "IB",
                                "IL",
                                "IN",
                                "IE",
                                "EI",
                                "ER"))) {
            addCode(primary, "K");
            addCode(alternate, "J");
            return current + 2;
        }

        // -GER-, -GY-
        if ((stringAt(value, current + 1, "ER") || charAt(value, current + 1) == 'Y')
                && !stringAt(value, 0, "DANGER", "RANGER", "MANGER")
                && !stringAt(value, current - 1, "E", "I")
                && !stringAt(value, current - 1, "RGY", "OGY")) {
            addCode(primary, "K");
            addCode(alternate, "J");
            return current + 2;
        }

        // Italian
        if (stringAt(value, current + 1, "E", "I", "Y")
                || stringAt(value, current - 1, "AGGI", "OGGI")) {
            if (stringAt(value, 0, "VAN ", "VON ")
                    || stringAt(value, 0, "SCH")
                    || stringAt(value, current + 1, "ET")) {
                addCode(primary, alternate, "K");
            } else if (stringAt(value, current + 1, "IER")) {
                addCode(primary, alternate, "J");
            } else {
                addCode(primary, "J");
                addCode(alternate, "K");
            }
            return current + 2;
        }

        addCode(primary, alternate, "K");
        return charAt(value, current + 1) == 'G' ? current + 2 : current + 1;
    }

    private static int handleGH(
            String value,
            StringBuilder primary,
            StringBuilder alternate,
            int current,
            int length,
            int last) {
        if (current > 0 && !isVowel(charAt(value, current - 1))) {
            addCode(primary, alternate, "K");
            return current + 2;
        }

        if (current == 0) {
            if (charAt(value, current + 2) == 'I') {
                addCode(primary, alternate, "J");
            } else {
                addCode(primary, alternate, "K");
            }
            return current + 2;
        }

        if ((current > 1 && stringAt(value, current - 2, "B", "H", "D"))
                || (current > 2 && stringAt(value, current - 3, "B", "H", "D"))
                || (current > 3 && stringAt(value, current - 4, "B", "H"))) {
            return current + 2;
        }

        if (current > 2
                && charAt(value, current - 1) == 'U'
                && stringAt(value, current - 3, "C", "G", "L", "R", "T")) {
            addCode(primary, alternate, "F");
        } else if (current > 0 && charAt(value, current - 1) != 'I') {
            addCode(primary, alternate, "K");
        }
        return current + 2;
    }

    private static int handleH(
            String value, StringBuilder primary, StringBuilder alternate, int current) {
        if ((current == 0 || isVowel(charAt(value, current - 1)))
                && isVowel(charAt(value, current + 1))) {
            addCode(primary, alternate, "H");
            return current + 2;
        }
        return current + 1;
    }

    private static int handleJ(
            String value,
            StringBuilder primary,
            StringBuilder alternate,
            int current,
            int length,
            int last) {
        if (stringAt(value, current, "JOSE") || stringAt(value, 0, "SAN ")) {
            if ((current == 0 && charAt(value, current + 4) == ' ') || stringAt(value, 0, "SAN ")) {
                addCode(primary, alternate, "H");
            } else {
                addCode(primary, "J");
                addCode(alternate, "H");
            }
            return current + 1;
        }

        if (current == 0 && !stringAt(value, current, "JOSE")) {
            addCode(primary, "J");
            addCode(alternate, "A");
        } else if (isVowel(charAt(value, current - 1))
                && !isSlavoGermanic(value, length)
                && (charAt(value, current + 1) == 'A' || charAt(value, current + 1) == 'O')) {
            addCode(primary, "J");
            addCode(alternate, "H");
        } else if (current == last) {
            addCode(primary, "J");
        } else if (!stringAt(value, current + 1, "L", "T", "K", "S", "N", "M", "B", "Z")
                && !stringAt(value, current - 1, "S", "K", "L")) {
            addCode(primary, alternate, "J");
        }

        return charAt(value, current + 1) == 'J' ? current + 2 : current + 1;
    }

    private static int handleP(
            String value, StringBuilder primary, StringBuilder alternate, int current) {
        if (charAt(value, current + 1) == 'H') {
            addCode(primary, alternate, "F");
            return current + 2;
        }
        addCode(primary, alternate, "P");
        return stringAt(value, current + 1, "P", "B") ? current + 2 : current + 1;
    }

    private static int handleR(
            String value,
            StringBuilder primary,
            StringBuilder alternate,
            int current,
            int length,
            int last) {
        // French e.g. ROGIER -> no final R
        if (current == last
                && !isSlavoGermanic(value, length)
                && stringAt(value, current - 2, "IE")
                && !stringAt(value, current - 4, "ME", "MA")) {
            addCode(alternate, "R");
        } else {
            addCode(primary, alternate, "R");
        }
        return charAt(value, current + 1) == 'R' ? current + 2 : current + 1;
    }

    private static int handleS(
            String value,
            StringBuilder primary,
            StringBuilder alternate,
            int current,
            int length,
            int last) {
        // Special cases
        if (stringAt(value, current - 1, "ISL", "YSL")) {
            return current + 1;
        }

        if (current == 0 && stringAt(value, current, "SUGAR")) {
            addCode(primary, "X");
            addCode(alternate, "S");
            return current + 1;
        }

        if (stringAt(value, current, "SH")) {
            if (stringAt(value, current + 1, "HEIM", "HOEK", "HOLM", "HOLZ")) {
                addCode(primary, alternate, "S");
            } else {
                addCode(primary, alternate, "X");
            }
            return current + 2;
        }

        if (stringAt(value, current, "SIO", "SIA") || stringAt(value, current, "SIAN")) {
            if (!isSlavoGermanic(value, length)) {
                addCode(primary, "S");
                addCode(alternate, "X");
            } else {
                addCode(primary, alternate, "S");
            }
            return current + 3;
        }

        if ((current == 0 && stringAt(value, current + 1, "M", "N", "L", "W"))
                || stringAt(value, current + 1, "Z")) {
            addCode(primary, "S");
            addCode(alternate, "X");
            return stringAt(value, current + 1, "Z") ? current + 2 : current + 1;
        }

        if (stringAt(value, current, "SC")) {
            if (charAt(value, current + 2) == 'H') {
                // Schimmel, school etc
                if (stringAt(value, current + 3, "OO", "ER", "EN", "UY", "ED", "EM")) {
                    if (stringAt(value, current + 3, "ER", "EN")) {
                        addCode(primary, "X");
                        addCode(alternate, "SK");
                    } else {
                        addCode(primary, alternate, "SK");
                    }
                    return current + 3;
                }
                if (current == 0 && !isVowel(charAt(value, 3)) && charAt(value, 3) != 'W') {
                    addCode(primary, "X");
                    addCode(alternate, "S");
                } else {
                    addCode(primary, alternate, "X");
                }
                return current + 3;
            }
            if (stringAt(value, current + 2, "I", "E", "Y")) {
                addCode(primary, alternate, "S");
                return current + 3;
            }
            addCode(primary, alternate, "SK");
            return current + 3;
        }

        // French e.g. RESNAIS
        if (current == last && stringAt(value, current - 2, "AI", "OI")) {
            addCode(alternate, "S");
        } else {
            addCode(primary, alternate, "S");
        }

        return stringAt(value, current + 1, "S", "Z") ? current + 2 : current + 1;
    }

    private static int handleT(
            String value, StringBuilder primary, StringBuilder alternate, int current, int length) {
        if (stringAt(value, current, "TION") || stringAt(value, current, "TIA", "TCH")) {
            addCode(primary, alternate, "X");
            return current + 3;
        }

        if (stringAt(value, current, "TH") || stringAt(value, current, "TTH")) {
            if (stringAt(value, current + 2, "OM", "AM")
                    || stringAt(value, 0, "VAN ", "VON ")
                    || stringAt(value, 0, "SCH")) {
                addCode(primary, alternate, "T");
            } else {
                addCode(primary, "0"); // theta
                addCode(alternate, "T");
            }
            return current + 2;
        }

        addCode(primary, alternate, "T");
        return stringAt(value, current + 1, "T", "D") ? current + 2 : current + 1;
    }

    private static int handleW(
            String value, StringBuilder primary, StringBuilder alternate, int current, int length) {
        if (stringAt(value, current, "WR")) {
            addCode(primary, alternate, "R");
            return current + 2;
        }

        if (current == 0
                && (isVowel(charAt(value, current + 1)) || stringAt(value, current, "WH"))) {
            if (isVowel(charAt(value, current + 1))) {
                addCode(primary, "A");
                addCode(alternate, "F");
            } else {
                addCode(primary, alternate, "A");
            }
        }

        // Arnow, Filipowicz
        if ((current == length - 1 && isVowel(charAt(value, current - 1)))
                || stringAt(value, current - 1, "EWSKI", "EWSKY", "OWSKI", "OWSKY")
                || stringAt(value, 0, "SCH")) {
            addCode(alternate, "F");
            return current + 1;
        }

        if (stringAt(value, current, "WICZ", "WITZ")) {
            addCode(primary, "TS");
            addCode(alternate, "FX");
            return current + 4;
        }

        return current + 1;
    }

    private static int handleX(
            String value, StringBuilder primary, StringBuilder alternate, int current) {
        if (current == 0) {
            addCode(primary, alternate, "S");
            return current + 1;
        }

        // French e.g. BREAUX
        if (!(current == value.trim().length() - 1
                && (stringAt(value, current - 3, "IAU", "EAU")
                        || stringAt(value, current - 2, "AU", "OU")))) {
            addCode(primary, alternate, "KS");
        }

        return stringAt(value, current + 1, "C", "X") ? current + 2 : current + 1;
    }

    private static int handleZ(
            String value, StringBuilder primary, StringBuilder alternate, int current) {
        if (charAt(value, current + 1) == 'H') {
            addCode(primary, alternate, "J");
            return current + 2;
        }
        if (stringAt(value, current + 1, "ZO", "ZI", "ZA")
                || (isSlavoGermanic(value, value.trim().length())
                        && (current > 0 && charAt(value, current - 1) != 'T'))) {
            addCode(primary, "S");
            addCode(alternate, "TS");
        } else {
            addCode(primary, alternate, "S");
        }
        return charAt(value, current + 1) == 'Z' ? current + 2 : current + 1;
    }

    // ── Utility methods ──────────────────────────────────────────────

    private static void addCode(StringBuilder primary, StringBuilder alternate, String code) {
        primary.append(code);
        alternate.append(code);
    }

    private static void addCode(StringBuilder sb, String code) {
        sb.append(code);
    }

    private static char charAt(String s, int index) {
        if (index < 0 || index >= s.length()) {
            return '\0';
        }
        return s.charAt(index);
    }

    private static boolean stringAt(String s, int start, String... targets) {
        if (start < 0) return false;
        for (String target : targets) {
            int end = start + target.length();
            if (end <= s.length() && s.substring(start, end).equals(target)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isVowel(char ch) {
        return ch == 'A' || ch == 'E' || ch == 'I' || ch == 'O' || ch == 'U';
    }

    private static boolean isSlavoGermanic(String value, int length) {
        return value.contains("W")
                || value.contains("K")
                || value.contains("CZ")
                || value.contains("WITZ");
    }
}
