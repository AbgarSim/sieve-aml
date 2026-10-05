package dev.sieve.match;

import dev.sieve.core.model.EntityType;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns a name into the key that match engines and cross-list matching compare.
 *
 * <p>The key is lowercase ASCII words separated by single spaces: names in other scripts are
 * romanised and accents removed ({@link ScriptTransliterator}), apostrophes are dropped ("O'Brien"
 * becomes "obrien") and any other punctuation separates words ("Al-Assad" becomes "al assad"). For
 * organisations, legal forms such as "LLC" or "GmbH" are also removed ({@link LegalForms}). Only
 * the key changes: names are stored and shown as published.
 *
 * <p>Keys are cached, since the same names are normalized again on every index rebuild.
 */
public final class NameNormalizer {

    private static final int MAX_CACHE_SIZE = 200_000;
    private static final ConcurrentHashMap<String, String> CACHE = new ConcurrentHashMap<>();

    private NameNormalizer() {
        throw new AssertionError("Utility class — do not instantiate");
    }

    /**
     * Normalizes a name for matching, keeping any legal form.
     *
     * @param name the name to normalize, may be {@code null}
     * @return the matching key, or an empty string if the input is {@code null} or blank
     */
    public static String normalize(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String cached = CACHE.get(name);
        if (cached != null) {
            return cached;
        }
        String normalized = compute(name);
        if (CACHE.size() < MAX_CACHE_SIZE) {
            CACHE.put(name, normalized);
        }
        return normalized;
    }

    /**
     * Normalizes a name like {@link #normalize(String)} without adding the key to the cache. Meant
     * for large streams of one-off names, such as the names in news articles, which would otherwise
     * fill the cache that list names rely on.
     *
     * <p>An organisation's name also loses its legal forms, as in {@link #normalize(String,
     * EntityType)}.
     *
     * @param name the name to normalize, may be {@code null}
     * @param type the type of whatever the name belongs to, not {@code null}
     * @return the matching key, or an empty string if the input is {@code null} or blank
     */
    public static String normalizeUncached(String name, EntityType type) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String cached = CACHE.get(name);
        String normalized = cached != null ? cached : compute(name);
        return type.isLegalEntity() ? LegalForms.strip(normalized) : normalized;
    }

    /**
     * Normalizes the name of a listed entity: an organisation's name also loses its legal forms.
     *
     * @param name the name to normalize, may be {@code null}
     * @param type the entity's type, not {@code null}
     * @return the matching key, or an empty string if the input is {@code null} or blank
     */
    public static String normalize(String name, EntityType type) {
        String normalized = normalize(name);
        return type.isLegalEntity() ? LegalForms.strip(normalized) : normalized;
    }

    /**
     * Normalizes a screening query. Legal forms are removed unless the query is for a person or
     * another type that has none, so "Rosneft PJSC" finds "Rosneft".
     *
     * @param name the queried name, may be {@code null}
     * @param type the entity type the query asks for, if any
     * @return the matching key, or an empty string if the input is {@code null} or blank
     */
    public static String normalizeQuery(String name, Optional<EntityType> type) {
        String normalized = normalize(name);
        return type.isEmpty() || type.get().isLegalEntity()
                ? LegalForms.strip(normalized)
                : normalized;
    }

    private static String compute(String name) {
        String latin = ScriptTransliterator.toLatin(name).toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(latin.length());
        boolean pendingSpace = false;
        for (int i = 0; i < latin.length(); ) {
            int cp = latin.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\'' || cp == '`' || cp == '"') {
                // "O'Brien", "Ma'mun", romanised ayn and soft signs: part of the word
                continue;
            }
            if (Character.isLetterOrDigit(cp)) {
                if (pendingSpace && !out.isEmpty()) {
                    out.append(' ');
                }
                pendingSpace = false;
                out.appendCodePoint(cp);
            } else {
                pendingSpace = true;
            }
        }
        return out.toString();
    }
}
