package dev.sieve.core.geo;

import java.text.Normalizer;
import java.util.HashMap;
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
    private static final Set<String> FILLER_WORDS = Set.of("the", "and", "of");

    private final Map<String, String> lookup;
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

        // Curated aliases override anything derived above
        CountryAliases.ALIASES.forEach((alias, code) -> table.put(key(alias), code));

        table.remove("");
        this.lookup = Map.copyOf(table);
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
        // "Moscow, Russia" or "Region: Crimea" style values: try the last comma-separated part
        int comma = raw.lastIndexOf(',');
        if (comma > 0 && comma < raw.length() - 1) {
            return Optional.ofNullable(lookup.get(key(raw.substring(comma + 1))));
        }
        return Optional.empty();
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
        String folded = Normalizer.normalize(value, Normalizer.Form.NFKD);
        folded = MARKS.matcher(folded).replaceAll("");
        folded = NON_ALNUM.matcher(folded.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
        StringBuilder sb = new StringBuilder(folded.length());
        for (String token : folded.split(" ")) {
            if (token.isEmpty() || FILLER_WORDS.contains(token)) {
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

    private static final class Holder {
        private static final CountryNormalizer STANDARD = new CountryNormalizer();
    }

    private static String stripParentheses(String value) {
        return PARENTHESES.matcher(value).replaceAll(" ");
    }
}
