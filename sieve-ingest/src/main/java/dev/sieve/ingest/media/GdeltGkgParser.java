package dev.sieve.ingest.media;

import dev.sieve.core.media.MediaArticle;
import dev.sieve.core.media.NewsMention;
import java.io.BufferedReader;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads GDELT Global Knowledge Graph 2.1 files (tab-separated, one article per line) and keeps the
 * adverse articles that name at least one person or organisation.
 *
 * <p>Columns used: 1 date, 2 source collection (1 is the web), 3 site, 4 URL, 8 themes with
 * offsets, 12 persons with offsets, 14 organisations with offsets, 25 translation info (source
 * language of machine-translated articles) and 26 extras (holds the page title).
 */
final class GdeltGkgParser {

    private static final int COLUMNS = 27;
    private static final String WEB_COLLECTION = "1";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final Pattern PAGE_TITLE = Pattern.compile("<PAGE_TITLE>(.*?)</PAGE_TITLE>");
    private static final Pattern NUMERIC_ENTITY =
            Pattern.compile("&#(?:[xX]([0-9a-fA-F]{1,6})|([0-9]{1,7}));");
    private static final Pattern SOURCE_LANGUAGE = Pattern.compile("srclc:([a-z]{3})");
    private static final Map<String, String> LANGUAGES = languagesByIso3();

    private GdeltGkgParser() {}

    /**
     * Parses one GKG file.
     *
     * @param reader the decompressed file
     * @return the adverse articles that name someone
     * @throws IOException if reading fails
     */
    static List<NewsMention> parse(BufferedReader reader) throws IOException {
        List<NewsMention> mentions = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null) {
            parseLine(line).ifPresent(mentions::add);
        }
        return mentions;
    }

    static Optional<NewsMention> parseLine(String line) {
        String[] f = line.split("\t", -1);
        if (f.length < COLUMNS || !WEB_COLLECTION.equals(f[2]) || !f[4].startsWith("http")) {
            return Optional.empty();
        }
        List<String> terms = AdverseThemes.termsFor(names(f[8]));
        if (terms.isEmpty()) {
            return Optional.empty();
        }
        List<String> persons = List.copyOf(names(f[12]));
        List<String> organisations = List.copyOf(names(f[14]));
        if (persons.isEmpty() && organisations.isEmpty()) {
            return Optional.empty();
        }
        Instant seenAt;
        try {
            seenAt = LocalDateTime.parse(f[1], DATE).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
        MediaArticle article =
                new MediaArticle(
                        f[4],
                        title(f[26]),
                        f[3],
                        language(f[25]),
                        Optional.empty(),
                        seenAt,
                        terms,
                        Optional.empty());
        return Optional.of(new NewsMention(article, persons, organisations));
    }

    /** Reads a {@code value,offset;value,offset} field, keeping each value once. */
    private static Set<String> names(String field) {
        Set<String> values = new LinkedHashSet<>();
        for (String entry : field.split(";")) {
            int comma = entry.lastIndexOf(',');
            String value = (comma >= 0 ? entry.substring(0, comma) : entry).strip();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return values;
    }

    private static String title(String extras) {
        Matcher m = PAGE_TITLE.matcher(extras);
        if (!m.find()) {
            return "";
        }
        Matcher entity = NUMERIC_ENTITY.matcher(m.group(1));
        StringBuilder decoded = new StringBuilder();
        while (entity.find()) {
            int codePoint =
                    entity.group(1) != null
                            ? Integer.parseInt(entity.group(1), 16)
                            : Integer.parseInt(entity.group(2));
            String replacement =
                    Character.isValidCodePoint(codePoint)
                            ? Character.toString(codePoint)
                            : entity.group();
            entity.appendReplacement(decoded, Matcher.quoteReplacement(replacement));
        }
        entity.appendTail(decoded);
        return decoded.toString()
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .strip();
    }

    private static String language(String translationInfo) {
        Matcher m = SOURCE_LANGUAGE.matcher(translationInfo);
        if (!m.find()) {
            return "English";
        }
        return LANGUAGES.getOrDefault(m.group(1), m.group(1));
    }

    private static Map<String, String> languagesByIso3() {
        Map<String, String> byIso3 = new HashMap<>();
        for (String code : Locale.getISOLanguages()) {
            Locale locale = Locale.of(code);
            try {
                byIso3.put(locale.getISO3Language(), locale.getDisplayLanguage(Locale.ENGLISH));
            } catch (java.util.MissingResourceException e) {
                // no three-letter code for this language
            }
        }
        return Map.copyOf(byIso3);
    }
}
