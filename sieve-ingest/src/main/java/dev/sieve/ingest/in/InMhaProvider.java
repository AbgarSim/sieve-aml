package dev.sieve.ingest.in;

import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.NameStrength;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.SanctionsProgram;
import dev.sieve.core.model.ScriptType;
import dev.sieve.ingest.AbstractListProvider;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches the individual terrorists that India's Ministry of Home Affairs designates under the
 * Fourth Schedule of the Unlawful Activities (Prevention) Act, 1967 (UAPA).
 *
 * <p>The ministry publishes the list only as a web page: one numbered paragraph per person, the
 * name and its aliases joined by {@code @}, linking to the gazette notification. Ids follow the
 * list's serial numbers ({@code in-mha-12}), which the ministry has kept stable as it appends new
 * designations.
 *
 * @see <a href="https://www.mha.gov.in/en/page/individual-terrorists-under-uapa">Individual
 *     terrorists under UAPA</a>
 */
public final class InMhaProvider extends AbstractListProvider {

    private static final String DEFAULT_URL =
            "https://www.mha.gov.in/en/page/individual-terrorists-under-uapa";

    private static final SanctionsProgram PROGRAM =
            new SanctionsProgram(
                    "UAPA-4",
                    "Unlawful Activities (Prevention) Act, 1967, Fourth Schedule",
                    ListSource.IN_MHA);

    private static final Pattern PARAGRAPH = Pattern.compile("(?is)<p[^>]*>(.*?)</p>");
    private static final Pattern ENTRY = Pattern.compile("^(\\d{1,4})\\s*\\.\\s*(.+)$");
    private static final Pattern LINK = Pattern.compile("(?i)href=\"([^\"]+)\"");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");

    /** Creates a provider that reads the list from the ministry's site. */
    public InMhaProvider() {
        super(ListSource.IN_MHA, URI.create(DEFAULT_URL), "text/html");
    }

    /**
     * Creates a provider with a custom page URI and HTTP client (for testing).
     *
     * @param pageUri the list page
     * @param httpClient the HTTP client to use for requests
     */
    public InMhaProvider(URI pageUri, HttpClient httpClient) {
        super(ListSource.IN_MHA, pageUri, "text/html", httpClient, Duration.ofSeconds(60));
    }

    @Override
    protected List<SanctionedEntity> parseResponse(byte[] responseBody)
            throws ListIngestionException {
        String html = new String(responseBody, StandardCharsets.UTF_8);
        List<SanctionedEntity> entities = new ArrayList<>();
        Matcher paragraphs = PARAGRAPH.matcher(html);
        while (paragraphs.find()) {
            SanctionedEntity entity = parseParagraph(paragraphs.group(1));
            if (entity != null) {
                entities.add(entity);
            }
        }
        if (entities.isEmpty()) {
            throw new ListIngestionException(
                    "India MHA page has no numbered entries; the page layout may have changed",
                    ListSource.IN_MHA);
        }
        return entities;
    }

    private SanctionedEntity parseParagraph(String paragraph) {
        Matcher entry = ENTRY.matcher(text(paragraph));
        if (!entry.matches()) {
            return null;
        }
        String serial = entry.group(1);
        List<String> names = new ArrayList<>();
        for (String part : entry.group(2).split("@")) {
            String name = clean(part);
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        if (names.isEmpty()) {
            return null;
        }

        NameInfo primary =
                new NameInfo(
                        names.get(0),
                        null,
                        null,
                        null,
                        null,
                        NameType.PRIMARY,
                        NameStrength.STRONG,
                        ScriptType.LATIN);
        List<NameInfo> aliases = new ArrayList<>();
        for (String alias : names.subList(1, names.size())) {
            // One-word aliases such as "Rode" or "Doctor" are nicknames, too common to match on.
            NameStrength strength = alias.contains(" ") ? NameStrength.STRONG : NameStrength.WEAK;
            aliases.add(
                    new NameInfo(
                            alias,
                            null,
                            null,
                            null,
                            null,
                            NameType.AKA,
                            strength,
                            ScriptType.LATIN));
        }

        Matcher link = LINK.matcher(paragraph);
        String remarks =
                link.find() ? "Notification: " + sourceUri().resolve(encode(link.group(1))) : null;

        return new SanctionedEntity(
                "in-mha-" + serial,
                EntityType.INDIVIDUAL,
                ListSource.IN_MHA,
                primary,
                aliases,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                remarks,
                List.of(PROGRAM),
                null,
                Instant.now());
    }

    private static String text(String html) {
        String plain = TAG.matcher(html).replaceAll(" ");
        plain =
                plain.replace("&nbsp;", " ")
                        .replace("&amp;", "&")
                        .replace("&quot;", "\"")
                        .replace("&#39;", "'")
                        .replace(' ', ' ');
        return plain.replaceAll("\\s+", " ").strip();
    }

    /** Trims a name and drops the closing period and stray quotes the page puts after it. */
    private static String clean(String name) {
        return name.strip().replaceAll("[\\s.,;\"'“”]+$", "").strip();
    }

    private static String encode(String href) {
        return href.replace(" ", "%20");
    }
}
