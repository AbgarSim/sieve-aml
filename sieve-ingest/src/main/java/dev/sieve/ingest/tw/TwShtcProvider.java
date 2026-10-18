package dev.sieve.ingest.tw;

import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.Address;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.Identifier;
import dev.sieve.core.model.IdentifierType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.NameStrength;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.RiskTopic;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.SanctionsProgram;
import dev.sieve.core.model.ScriptType;
import dev.sieve.ingest.AbstractListProvider;
import dev.sieve.ingest.CsvRows;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches Taiwan's entity list for strategic high-technology commodities (SHTC), published by the
 * International Trade Administration of the Ministry of Economic Affairs.
 *
 * <p>Exporters of strategic goods need a permit to ship to anyone on the list. It brings together
 * the UN, US and other export-control and terrorism designations Taiwan follows. The download is a
 * UTF-8 CSV with six columns: item number, name, aliases, country codes, addresses and ID numbers;
 * several values in one cell are separated by semicolons.
 *
 * <p>Each entry's id is {@code tw-shtc-} followed by a hash of its name, because the item numbers
 * are row positions that shift when a row is removed; an entry whose name repeats gets a {@code
 * -2}, {@code -3} suffix in file order. Names in another script (written {@code 簡體中文：…} in the name
 * cell, or {@code (original script: …)} after an alias) become aliases in that script. A name
 * written {@code SURNAME, Given} or as single words separated by semicolons is read as a person;
 * every other entry is an organisation. Every entry is tagged {@link RiskTopic#EXPORT_CONTROL}.
 *
 * @see <a href="https://www.trade.gov.tw/">International Trade Administration</a>
 */
public final class TwShtcProvider extends AbstractListProvider {

    /** The CSV download of the SHTC entity list. */
    public static final String DEFAULT_URL =
            "https://publicinfo.trade.gov.tw/icp/Download.action?file=ZgPKOWuRLCD%2B8S4%2FZhAeuw%3D%3D";

    private static final int COL_NAME = 1;
    private static final int COL_ALIAS = 2;
    private static final int COL_COUNTRY = 3;
    private static final int COL_ADDRESS = 4;
    private static final int COL_ID = 5;

    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final Pattern ORIGINAL_SCRIPT =
            Pattern.compile("\\(original script:\\s*([^)]+)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LABELLED = Pattern.compile("^[^:：]*[：:]\\s*(.+)$");
    private static final Pattern PERSON = Pattern.compile("^[\\p{Lu}'’.\\- ]+, .*\\p{Ll}.*$");
    private static final Pattern ORGANISATION =
            Pattern.compile(
                    "\\b(LTD|LIMITED|CO|COMPANY|CORP|CORPORATION|INC|LLC|JSC|OOO|AO|PAO|GMBH|AG|SA"
                            + "|GROUP|BANK|INDUSTR\\w*|TRADING|ENTERPRISE\\w*|INSTITUTE|UNIVERSITY"
                            + "|FOUNDATION|ORGANI[SZ]ATION|FRONT|ARMY|PARTY|MOVEMENT)\\b",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern COUNTRY_CODE = Pattern.compile("^[A-Z]{2}$");

    private static final SanctionsProgram PROGRAM =
            new SanctionsProgram(
                    "SHTC", "Strategic high-tech commodities entity list", ListSource.TW_SHTC);

    /** Creates a provider that reads the published CSV download. */
    public TwShtcProvider() {
        super(ListSource.TW_SHTC, URI.create(DEFAULT_URL), "text/csv");
    }

    /**
     * Creates a provider with a custom source URI.
     *
     * @param sourceUri the URI to fetch the CSV file from
     */
    public TwShtcProvider(URI sourceUri) {
        super(ListSource.TW_SHTC, sourceUri, "text/csv");
    }

    /**
     * Creates a provider with a custom source URI and HTTP client (for testing).
     *
     * @param sourceUri the URI to fetch the CSV file from
     * @param httpClient the HTTP client to use for requests
     */
    public TwShtcProvider(URI sourceUri, HttpClient httpClient) {
        super(ListSource.TW_SHTC, sourceUri, "text/csv", httpClient, Duration.ofSeconds(120));
    }

    @Override
    protected List<SanctionedEntity> parseResponse(byte[] responseBody)
            throws ListIngestionException {
        List<List<String>> rows = CsvRows.parse(new String(responseBody, StandardCharsets.UTF_8));
        if (rows.isEmpty()) {
            return List.of();
        }
        List<String> header = rows.get(0);
        if (header.size() <= COL_ID
                || !header.get(COL_NAME).toLowerCase(Locale.ROOT).contains("name")) {
            throw new ListIngestionException(
                    "Unexpected SHTC entity list header: " + String.join(",", header),
                    ListSource.TW_SHTC);
        }
        List<SanctionedEntity> entities = new ArrayList<>();
        Map<String, Integer> occurrences = new HashMap<>();
        Instant now = Instant.now();
        for (List<String> row : rows.subList(1, rows.size())) {
            SanctionedEntity entity = toEntity(row, occurrences, now);
            if (entity != null) {
                entities.add(entity);
            }
        }
        return entities;
    }

    private SanctionedEntity toEntity(
            List<String> row, Map<String, Integer> occurrences, Instant now) {
        String nameCell = clean(CsvRows.field(row, COL_NAME));
        if (nameCell == null) {
            return null;
        }
        List<String> latin = new ArrayList<>();
        Set<NameInfo> aliases = new LinkedHashSet<>();
        for (String segment : split(nameCell)) {
            Matcher labelled = LABELLED.matcher(segment);
            if (labelled.matches() && script(labelled.group(1)) != ScriptType.LATIN) {
                aliases.add(alias(labelled.group(1).strip()));
            } else if (script(segment) != ScriptType.LATIN) {
                aliases.add(alias(segment));
            } else {
                latin.add(segment);
            }
        }
        if (latin.isEmpty()) {
            if (aliases.isEmpty()) {
                return null;
            }
            NameInfo first = aliases.iterator().next();
            aliases.remove(first);
            latin.add(first.fullName());
        }

        EntityType type = EntityType.ENTITY;
        String given = null;
        String family = null;
        String full = latin.get(0);
        List<String> otherNames = latin.subList(1, latin.size());
        if (latin.size() > 1 && latin.stream().noneMatch(s -> s.contains(" "))) {
            type = EntityType.INDIVIDUAL;
            full = String.join(" ", latin);
            otherNames = List.of();
        } else if (PERSON.matcher(full).matches() && !ORGANISATION.matcher(full).find()) {
            type = EntityType.INDIVIDUAL;
            int comma = full.indexOf(", ");
            family = full.substring(0, comma).strip();
            given = full.substring(comma + 2).strip();
        }
        NameInfo primary =
                new NameInfo(
                        full,
                        given,
                        family,
                        null,
                        null,
                        NameType.PRIMARY,
                        NameStrength.STRONG,
                        ScriptType.LATIN);
        for (String other : otherNames) {
            aliases.add(alias(other));
        }

        String aliasCell = clean(CsvRows.field(row, COL_ALIAS));
        if (aliasCell != null) {
            for (String segment : split(aliasCell)) {
                Matcher original = ORIGINAL_SCRIPT.matcher(segment);
                while (original.find()) {
                    aliases.add(alias(original.group(1).strip()));
                }
                String text = ORIGINAL_SCRIPT.matcher(segment).replaceAll("").strip();
                if (!text.isEmpty()) {
                    aliases.add(alias(text));
                }
            }
        }
        List<NameInfo> aliasList = new ArrayList<>();
        Set<String> seenNames = new LinkedHashSet<>();
        seenNames.add(full.toLowerCase(Locale.ROOT));
        for (NameInfo alias : aliases) {
            if (seenNames.add(alias.fullName().toLowerCase(Locale.ROOT))) {
                aliasList.add(alias);
            }
        }

        List<String> codes = new ArrayList<>();
        String codeCell = CsvRows.field(row, COL_COUNTRY);
        if (codeCell != null) {
            for (String code : codeCell.split(";")) {
                String c = code.strip().toUpperCase(Locale.ROOT);
                if (COUNTRY_CODE.matcher(c).matches()) {
                    codes.add(c);
                }
            }
        }
        List<String> distinctCodes = List.copyOf(new LinkedHashSet<>(codes));
        List<Address> addresses = new ArrayList<>();
        String addressCell = clean(CsvRows.field(row, COL_ADDRESS));
        List<String> lines = addressCell == null ? List.of() : split(addressCell);
        for (int i = 0; i < lines.size(); i++) {
            String country =
                    lines.size() == codes.size()
                            ? codes.get(i)
                            : distinctCodes.size() == 1 ? distinctCodes.get(0) : null;
            addresses.add(new Address(null, null, null, null, country, lines.get(i)));
        }
        if (lines.isEmpty()) {
            for (String code : distinctCodes) {
                addresses.add(new Address(null, null, null, null, code, null));
            }
        }

        List<Identifier> identifiers = new ArrayList<>();
        String idCell = clean(CsvRows.field(row, COL_ID));
        if (idCell != null) {
            for (String value : new LinkedHashSet<>(split(idCell))) {
                identifiers.add(
                        new Identifier(IdentifierType.OTHER, value, null, "Passport or ID number"));
            }
        }

        String key = full.toLowerCase(Locale.ROOT);
        int occurrence = occurrences.merge(key, 1, Integer::sum);
        String id = "tw-shtc-" + hash(key) + (occurrence > 1 ? "-" + occurrence : "");

        return new SanctionedEntity(
                id,
                type,
                ListSource.TW_SHTC,
                primary,
                aliasList,
                addresses,
                identifiers,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of(PROGRAM),
                null,
                now,
                Set.of(RiskTopic.EXPORT_CONTROL),
                List.of());
    }

    private static NameInfo alias(String name) {
        return new NameInfo(name, null, null, null, null, NameType.AKA, null, script(name));
    }

    private static List<String> split(String cell) {
        List<String> parts = new ArrayList<>();
        for (String part : cell.split(";")) {
            String p = part.strip();
            if (!p.isEmpty()) {
                parts.add(p);
            }
        }
        return parts;
    }

    /** Drops the stray HTML tags some cells carry and collapses white space. */
    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String text = TAG.matcher(value).replaceAll(" ").replaceAll("\\s+", " ").strip();
        return text.isEmpty() ? null : text;
    }

    /**
     * Tells which script a name is written in, from its letters.
     *
     * @param text the name
     * @return the script of its first non-Latin letter, or Latin when it has none
     */
    static ScriptType script(String text) {
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isLetter(cp)) {
                continue;
            }
            Character.UnicodeScript s = Character.UnicodeScript.of(cp);
            switch (s) {
                case LATIN:
                    continue;
                case CYRILLIC:
                    return ScriptType.CYRILLIC;
                case ARABIC:
                    return ScriptType.ARABIC;
                case HAN, HIRAGANA, KATAKANA, HANGUL:
                    return ScriptType.CJK;
                default:
                    return ScriptType.OTHER;
            }
        }
        return ScriptType.LATIN;
    }

    private static String hash(String key) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 not available", e);
        }
    }
}
