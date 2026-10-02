package dev.sieve.ingest.md;

import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.SanctionsProgram;
import dev.sieve.ingest.AbstractListProvider;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches and parses the Moldovan national terrorism sanctions list.
 *
 * <p>Published by the Moldova Intelligence and Security Service (SIS) as an HTML table. Columns:
 * name, date of birth, terrorist sanctions, proliferation sanctions. Typically contains ~700+
 * entities.
 *
 * @see <a href="https://antiteror.sis.md/lista-terorista-xls">Moldova SIS Terror List</a>
 */
public final class MdTerrorProvider extends AbstractListProvider {

    private static final String DEFAULT_URL = "https://antiteror.sis.md/lista-terorista-xls";
    private static final Pattern ROW_PATTERN =
            Pattern.compile(
                    "<tr[^>]*>\\s*<td>(.*?)</td>\\s*<td>(.*?)</td>\\s*<td>(.*?)</td>\\s*<td>(.*?)</td>",
                    Pattern.DOTALL);
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final DateTimeFormatter MD_DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy");

    public MdTerrorProvider() {
        super(ListSource.MD_TERROR, URI.create(DEFAULT_URL), "*/*");
    }

    public MdTerrorProvider(URI sourceUri) {
        super(ListSource.MD_TERROR, sourceUri, "*/*");
    }

    public MdTerrorProvider(URI sourceUri, HttpClient httpClient) {
        super(ListSource.MD_TERROR, sourceUri, "*/*", httpClient, Duration.ofSeconds(120));
    }

    @Override
    protected List<SanctionedEntity> parseResponse(byte[] responseBody)
            throws ListIngestionException {
        String html = new String(responseBody, StandardCharsets.UTF_8);
        List<SanctionedEntity> entities = new ArrayList<>();
        Matcher matcher = ROW_PATTERN.matcher(html);
        int idx = 0;

        while (matcher.find()) {
            String rawName = stripHtml(matcher.group(1)).strip();
            String rawDob = stripHtml(matcher.group(2)).strip();
            String terrorProgram = stripHtml(matcher.group(3)).strip();
            String prolifProgram = stripHtml(matcher.group(4)).strip();

            if (rawName.isEmpty()) continue;

            // Parse aliases from "name alias other_name" pattern
            String name;
            List<NameInfo> aliases = new ArrayList<>();
            String[] parts = rawName.split("(?i)\\balias\\b");
            name =
                    parts[0].strip()
                            .replaceAll("^[\\]\\), ]+", "")
                            .replaceAll("[\\[\\(\\., ]+$", "");
            for (int i = 1; i < parts.length; i++) {
                String a =
                        parts[i].strip()
                                .replaceAll("^[\\]\\), ]+", "")
                                .replaceAll("[\\[\\(\\., ]+$", "");
                if (!a.isEmpty() && !a.equals(name)) {
                    aliases.add(new NameInfo(a, null, null, null, null, NameType.AKA, null, null));
                }
            }

            if (name.isEmpty()) continue;

            NameInfo primaryName =
                    new NameInfo(name, null, null, null, null, NameType.PRIMARY, null, null);

            List<LocalDate> datesOfBirth = new ArrayList<>();
            if (!rawDob.isEmpty()) {
                // DOB field may contain multiple dates separated by ;
                for (String dobPart : rawDob.split(";")) {
                    LocalDate dob = parseDateSafe(dobPart.strip());
                    if (dob != null) datesOfBirth.add(dob);
                }
            }

            List<SanctionsProgram> programs = new ArrayList<>();
            if (!terrorProgram.isEmpty()) {
                programs.add(new SanctionsProgram(terrorProgram, null, ListSource.MD_TERROR));
            }
            if (!prolifProgram.isEmpty()) {
                programs.add(new SanctionsProgram(prolifProgram, null, ListSource.MD_TERROR));
            }
            if (programs.isEmpty()) {
                programs.add(
                        new SanctionsProgram(
                                "MD Terror", "Moldova Terrorism", ListSource.MD_TERROR));
            }

            entities.add(
                    new SanctionedEntity(
                            "md-" + idx,
                            EntityType.INDIVIDUAL,
                            ListSource.MD_TERROR,
                            primaryName,
                            aliases,
                            List.of(),
                            List.of(),
                            List.of(),
                            List.of(),
                            datesOfBirth,
                            List.of(),
                            null,
                            programs,
                            null,
                            Instant.now()));
            idx++;
        }
        return entities;
    }

    private static String stripHtml(String s) {
        return HTML_TAG.matcher(s)
                .replaceAll("")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#039;", "'")
                .replace("&nbsp;", " ")
                .strip();
    }

    private static LocalDate parseDateSafe(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) return null;
        String cleaned = dateStr.strip().replaceAll("[;,]+$", "").strip();
        if (cleaned.isEmpty()) return null;
        try {
            return LocalDate.parse(cleaned, MD_DATE_FORMAT);
        } catch (DateTimeParseException e) {
            try {
                return LocalDate.parse(cleaned);
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }
}
