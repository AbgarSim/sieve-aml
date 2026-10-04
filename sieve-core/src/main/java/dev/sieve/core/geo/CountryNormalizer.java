package dev.sieve.core.geo;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Resolves free-text country values from sanctions lists to ISO 3166-1 alpha-2 codes.
 *
 * <p>Sanctions lists publish countries in many shapes: ISO alpha-2 or alpha-3 codes, upper-case
 * English names ({@code "RUSSIA"}), official long forms ({@code "Iran (Islamic Republic of)"}),
 * inverted forms ({@code "Korea, North"}), demonyms ({@code "Syrian"}) and names in the publisher's
 * own language ({@code "Fédération de Russie"}). This class builds a lookup table from the JDK's
 * own country data in several languages plus a curated alias table, so it needs no external
 * dependency.
 *
 * <p>Instances are immutable and thread-safe. Use {@link #standard()} for the shared instance.
 */
public final class CountryNormalizer {

    /** User-assigned code for Kosovo, used by the EU, UN and most sanctions lists. */
    public static final String KOSOVO = "XK";

    private static final Locale[] NAME_LANGUAGES = {
        Locale.ENGLISH,
        Locale.FRENCH,
        Locale.GERMAN,
        Locale.of("es"),
        Locale.of("it"),
        Locale.of("nl"),
        Locale.of("pl"),
        Locale.of("tr"),
        Locale.of("ru"),
        Locale.of("uk"),
        Locale.of("lv"),
        Locale.of("ro"),
        Locale.of("ja"),
        Locale.of("ar"),
        Locale.of("he")
    };

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_ALNUM = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern PARENTHESES = Pattern.compile("\\([^)]*\\)");
    private static final Pattern PARENTHESIZED = Pattern.compile("\\(([^)]*)\\)");
    private static final Pattern POSSESSIVE = Pattern.compile("['\u2019\u02bc]s\\b");
    private static final Pattern ALTERNATIVES = Pattern.compile("\\s*/\\s*");
    private static final Pattern LIST_SEPARATORS = Pattern.compile(";|\\(\\d+\\)");
    private static final Set<String> FILLER_WORDS =
            Set.of(
                    "the",
                    "and",
                    "of",
                    "et",
                    "ve",
                    "ou",
                    "possibly",
                    "pretendument",
                    "presume",
                    "presumee");

    private final Map<String, String> lookup;
    private final Map<String, String> unorderedLookup;
    private final Set<String> codes;

    private CountryNormalizer() {
        Map<String, String> table = new HashMap<>(8192);
        Set<String> iso2 = new TreeSet<>();

        for (String code : Locale.getISOCountries()) {
            iso2.add(code);
            table.put(key(code), code);
            Locale country = Locale.of("", code);
            try {
                table.put(key(country.getISO3Country()), code);
            } catch (java.util.MissingResourceException ignored) {
                // No alpha-3 code known to the JDK for this alpha-2 code
            }
        }
        iso2.add(KOSOVO);
        table.put(key(KOSOVO), KOSOVO);
        table.put(key("XKX"), KOSOVO);

        // English names win over every other language, so they are written first
        for (Locale language : NAME_LANGUAGES) {
            for (String code : Locale.getISOCountries()) {
                String name = Locale.of("", code).getDisplayCountry(language);
                if (!name.isBlank() && !name.equalsIgnoreCase(code)) {
                    table.putIfAbsent(key(name), code);
                    table.putIfAbsent(key(stripParentheses(name)), code);
                }
            }
        }

        // Names the JDK gives in parentheses ("Myanmar (Birmanie)") are former or local names
        for (Locale language : NAME_LANGUAGES) {
            for (String code : Locale.getISOCountries()) {
                var inner = PARENTHESIZED.matcher(Locale.of("", code).getDisplayCountry(language));
                while (inner.find()) {
                    table.putIfAbsent(key(inner.group(1)), code);
                }
            }
        }

        // Curated aliases override anything derived above
        CountryAliases.ALIASES.forEach((alias, code) -> table.put(key(alias), code));

        table.remove("");
        this.lookup = Map.copyOf(table);
        this.unorderedLookup = unordered(table);
        this.codes = Set.copyOf(iso2);
    }

    /**
     * Returns the shared instance.
     *
     * @return the standard normalizer
     */
    public static CountryNormalizer standard() {
        return Holder.STANDARD;
    }

    /**
     * Resolves a raw country value to an ISO 3166-1 alpha-2 code.
     *
     * @param raw the value as published by a list, may be {@code null}
     * @return the upper-case alpha-2 code, or empty when the value is blank or unknown
     */
    public Optional<String> toIso2(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String direct = lookup.get(key(raw));
        if (direct != null) {
            return Optional.of(direct);
        }
        String withoutParentheses = lookup.get(key(stripParentheses(raw)));
        if (withoutParentheses != null) {
            return Optional.of(withoutParentheses);
        }
        // "Congo DR" for "DR Congo": the same words in another order
        String reordered = unorderedLookup.get(sortedTokens(key(raw)));
        if (reordered != null) {
            return Optional.of(reordered);
        }
        // "BIRMANIE/MYANMAR": two names for one country, accepted only when they agree
        String[] alternatives = ALTERNATIVES.split(raw.strip());
        if (alternatives.length > 1) {
            return agreeing(alternatives);
        }
        // "Moscow, Russia" or "Region: Gaza" style values: try the part after the last separator
        int separator = Math.max(raw.lastIndexOf(','), raw.lastIndexOf(':'));
        if (separator > 0 && separator < raw.length() - 1) {
            Optional<String> last = toIso2(raw.substring(separator + 1));
            if (last.isPresent()) {
                return last;
            }
            // "RF, CHECHEN REGION": the country comes first
            int first = raw.indexOf(',');
            if (first > 0) {
                return Optional.ofNullable(lookup.get(key(raw.substring(0, first))));
            }
        }
        return Optional.empty();
    }

    /**
     * Splits a value that may name several countries into one part per country.
     *
     * <p>Lists join countries with semicolons, number them ({@code "(1) Russia (2) Cyprus"}) or
     * separate them with a slash ({@code "Iraq/Syria"}). A slash is only split on when the whole
     * part does not resolve, so {@code "BIRMANIE/MYANMAR"} stays one value.
     *
     * @param raw the value as published by a list, may be {@code null}
     * @return the non-blank parts, in order; empty when the value is blank
     */
    public List<String> split(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        for (String part : LIST_SEPARATORS.split(raw)) {
            String trimmed = part.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (toIso2(trimmed).isPresent()) {
                parts.add(trimmed);
                continue;
            }
            for (String alternative : ALTERNATIVES.split(trimmed)) {
                if (!alternative.isBlank()) {
                    parts.add(alternative.strip());
                }
            }
        }
        return parts;
    }

    private Optional<String> agreeing(String[] alternatives) {
        String agreed = null;
        for (String alternative : alternatives) {
            Optional<String> code = toIso2(alternative);
            if (code.isEmpty() || (agreed != null && !agreed.equals(code.get()))) {
                return Optional.empty();
            }
            agreed = code.get();
        }
        return Optional.ofNullable(agreed);
    }

    /**
     * Returns the English name of a country code.
     *
     * @param iso2 an ISO 3166-1 alpha-2 code
     * @return the English display name, or the code itself when unknown
     */
    public String displayName(String iso2) {
        Objects.requireNonNull(iso2, "iso2 must not be null");
        if (KOSOVO.equals(iso2)) {
            return "Kosovo";
        }
        String name = Locale.of("", iso2).getDisplayCountry(Locale.ENGLISH);
        return name.isBlank() ? iso2 : name;
    }

    /**
     * Returns every code this normalizer can produce.
     *
     * @return the ISO 3166-1 alpha-2 codes known to the JDK plus {@link #KOSOVO}
     */
    public Set<String> codes() {
        return codes;
    }

    static String key(String value) {
        // "People's" and "Peoples" are the same word; the Turkish dotless i folds to i
        String folded = POSSESSIVE.matcher(value).replaceAll("s").replace('\u0131', 'i');
        folded = Normalizer.normalize(folded, Normalizer.Form.NFKD);
        folded = MARKS.matcher(folded).replaceAll("");
        folded = NON_ALNUM.matcher(folded.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
        StringBuilder sb = new StringBuilder(folded.length());
        String[] tokens = folded.split(" ");
        // A lone filler word is a code ("ET" for Ethiopia, "AND" for Andorra), so it stays
        boolean single = tokens.length == 1;
        for (String token : tokens) {
            if (token.isEmpty() || (!single && FILLER_WORDS.contains(token))) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(
                    switch (token) {
                        case "saint" -> "st";
                        case "sainte" -> "ste";
                        default -> token;
                    });
        }
        return sb.toString();
    }

    /** Keys by their sorted words; a sorted form shared by two countries is left out. */
    private static Map<String, String> unordered(Map<String, String> table) {
        Map<String, String> sorted = new HashMap<>(table.size() * 2);
        Set<String> ambiguous = new HashSet<>();
        table.forEach(
                (key, code) -> {
                    String words = sortedTokens(key);
                    String previous = sorted.putIfAbsent(words, code);
                    if (previous != null && !previous.equals(code)) {
                        ambiguous.add(words);
                    }
                });
        ambiguous.forEach(sorted::remove);
        return Map.copyOf(sorted);
    }

    private static String sortedTokens(String key) {
        String[] tokens = key.split(" ");
        Arrays.sort(tokens);
        return String.join(" ", tokens);
    }

    private static final class Holder {
        private static final CountryNormalizer STANDARD = new CountryNormalizer();
    }

    private static String stripParentheses(String value) {
        return PARENTHESES.matcher(value).replaceAll(" ");
    }
}
