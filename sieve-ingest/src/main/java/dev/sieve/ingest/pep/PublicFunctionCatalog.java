package dev.sieve.ingest.pep;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The prominent public functions the EU and its member states list under Article 20a of Directive
 * (EU) 2015/849, by jurisdiction: a reference table, not a list of people. The module ships the
 * catalogue read from OJ C/2023/724 as a resource; {@link #bundled()} loads it, and {@link
 * EuPublicFunctionsParser} regenerates it from the Official Journal.
 *
 * <p>The catalogue tells, for a politically exposed person's office, which directive category it
 * falls under and how the person's own state lists the function, which {@link #reason} words for
 * the person's record.
 */
public final class PublicFunctionCatalog {

    /** The bundled catalogue's classpath resource. */
    public static final String RESOURCE = "/dev/sieve/ingest/pep/eu-public-functions.json";

    private static final ObjectMapper MAPPER =
            new ObjectMapper()
                    .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                    .enable(SerializationFeature.INDENT_OUTPUT);

    private static final Set<String> STOPWORDS =
            Set.of(
                    "of", "the", "for", "and", "in", "a", "an", "to", "at", "or", "de", "du", "des",
                    "der", "di", "la", "le", "les", "del", "da", "do", "das", "dos", "van", "von",
                    "all", "its");
    private static final Pattern BRACKETS = Pattern.compile("[(\\[][^)\\]]*[)\\]]");
    private static final Pattern NON_LETTERS = Pattern.compile("[^\\p{L}\\p{N}]+");

    private final String title;
    private final String reference;
    private final LocalDate published;
    private final String eli;
    private final Map<String, List<PublicFunction>> byJurisdiction;
    private final List<PublicFunction> all;

    /**
     * Creates a catalogue.
     *
     * @param title the Official Journal document's title
     * @param reference the Official Journal number, such as {@code C/2023/724}
     * @param published the publication date
     * @param eli the document's European Legislation Identifier
     * @param functions the functions
     */
    public PublicFunctionCatalog(
            String title,
            String reference,
            LocalDate published,
            String eli,
            List<PublicFunction> functions) {
        this.title = title;
        this.reference = Objects.requireNonNull(reference, "reference must not be null");
        this.published = published;
        this.eli = eli;
        Map<String, List<PublicFunction>> grouped = new LinkedHashMap<>();
        for (PublicFunction function : functions) {
            grouped.computeIfAbsent(function.jurisdiction(), j -> new ArrayList<>()).add(function);
        }
        grouped.replaceAll((j, list) -> Collections.unmodifiableList(list));
        this.byJurisdiction = Collections.unmodifiableMap(grouped);
        this.all = List.copyOf(functions);
    }

    private static final class Bundled {
        static final PublicFunctionCatalog CATALOG = load();

        private static PublicFunctionCatalog load() {
            try (InputStream in = PublicFunctionCatalog.class.getResourceAsStream(RESOURCE)) {
                if (in == null) {
                    throw new IllegalStateException("Missing resource " + RESOURCE);
                }
                return read(in);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read " + RESOURCE, e);
            }
        }
    }

    /**
     * Returns the catalogue the module ships, read once.
     *
     * @return the bundled catalogue
     */
    public static PublicFunctionCatalog bundled() {
        return Bundled.CATALOG;
    }

    /**
     * Reads a catalogue written by {@link #write}.
     *
     * @param json the catalogue
     * @return the catalogue
     * @throws IOException when it cannot be read
     */
    public static PublicFunctionCatalog read(InputStream json) throws IOException {
        Document document = MAPPER.readValue(json, Document.class);
        List<PublicFunction> functions = new ArrayList<>();
        for (Entry entry : document.functions()) {
            functions.add(
                    new PublicFunction(
                            entry.jurisdiction(),
                            PublicFunctionCategory.fromPoint(entry.category()).orElse(null),
                            entry.heading(),
                            entry.function(),
                            entry.organisation()));
        }
        return new PublicFunctionCatalog(
                document.title(),
                document.reference(),
                document.published() == null ? null : LocalDate.parse(document.published()),
                document.eli(),
                functions);
    }

    /**
     * Writes the catalogue as JSON.
     *
     * @param out where to write
     * @throws IOException when it cannot be written
     */
    public void write(OutputStream out) throws IOException {
        List<Entry> entries = new ArrayList<>();
        for (PublicFunction function : all) {
            entries.add(
                    new Entry(
                            function.jurisdiction(),
                            function.category() == null
                                    ? null
                                    : String.valueOf(function.category().point()),
                            function.heading(),
                            function.function(),
                            function.organisation()));
        }
        MAPPER.writeValue(
                out,
                new Document(
                        title,
                        reference,
                        published == null ? null : published.toString(),
                        eli,
                        entries));
    }

    private record Document(
            String title, String reference, String published, String eli, List<Entry> functions) {}

    private record Entry(
            String jurisdiction,
            String category,
            String heading,
            String function,
            String organisation) {}

    /** The Official Journal document's title. */
    public String title() {
        return title;
    }

    /** The Official Journal number, such as {@code C/2023/724}. */
    public String reference() {
        return reference;
    }

    /** The publication date, or {@code null} when unknown. */
    public LocalDate published() {
        return published;
    }

    /** The document's European Legislation Identifier, or {@code null} when unknown. */
    public String eli() {
        return eli;
    }

    /** The jurisdictions with a list: ISO 3166-1 alpha-2 codes and {@code EU}, in list order. */
    public Set<String> jurisdictions() {
        return byJurisdiction.keySet();
    }

    /** Whether a jurisdiction has a list. */
    public boolean lists(String jurisdiction) {
        return jurisdiction != null
                && byJurisdiction.containsKey(jurisdiction.toUpperCase(Locale.ROOT));
    }

    /**
     * A jurisdiction's functions, in list order.
     *
     * @param jurisdiction the ISO 3166-1 alpha-2 code, or {@code EU}
     * @return its functions, empty when it has no list
     */
    public List<PublicFunction> functions(String jurisdiction) {
        if (jurisdiction == null) {
            return List.of();
        }
        return byJurisdiction.getOrDefault(jurisdiction.toUpperCase(Locale.ROOT), List.of());
    }

    /** Every function, in list order. */
    public List<PublicFunction> all() {
        return all;
    }

    /** The number of functions. */
    public int size() {
        return all.size();
    }

    /**
     * Finds how a jurisdiction lists an office, by the words of the office's name: the list entry
     * whose words are all in the office's name, or whose words include all of the name's, in the
     * same category when the list says which.
     *
     * @param jurisdiction the ISO 3166-1 alpha-2 code
     * @param category the office's directive category, or {@code null} to search every category
     * @param office the office's name, such as {@code Federal Chancellor of Germany}
     * @return the entry, when one matches
     */
    public Optional<PublicFunction> find(
            String jurisdiction, PublicFunctionCategory category, String office) {
        Set<String> officeWords = words(office);
        if (officeWords.size() < 2) {
            return Optional.empty();
        }
        PublicFunction best = null;
        int bestWords = 0;
        for (PublicFunction function : functions(jurisdiction)) {
            if (category != null
                    && function.category() != null
                    && function.category() != category) {
                continue;
            }
            Set<String> words = words(function.function());
            if (words.size() < 2) {
                continue;
            }
            boolean matches = officeWords.containsAll(words) || words.containsAll(officeWords);
            if (matches && words.size() > bestWords) {
                best = function;
                bestWords = words.size();
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Words a person's record with: the directive category of an office and, when the office's
     * state is in the catalogue, the state's own entry for it.
     *
     * @param category the office's directive category
     * @param jurisdiction the ISO 3166-1 alpha-2 code of the office's state
     * @param office the office's name
     * @return the reason
     */
    public String reason(PublicFunctionCategory category, String jurisdiction, String office) {
        Objects.requireNonNull(category, "category must not be null");
        StringBuilder reason =
                new StringBuilder("Prominent public function under ")
                        .append(category.citation())
                        .append(" (")
                        .append(category.description())
                        .append(")");
        if (lists(jurisdiction)) {
            reason.append("; on the ")
                    .append(jurisdiction.toUpperCase(Locale.ROOT))
                    .append(" list in OJ ")
                    .append(reference);
            find(jurisdiction, category, office)
                    .ifPresent(function -> reason.append(": ").append(function.function()));
        }
        return reason.toString();
    }

    /** The significant words of a name, lower-cased, unaccented and crudely singular. */
    static Set<String> words(String text) {
        if (text == null) {
            return Set.of();
        }
        String plain = BRACKETS.matcher(text).replaceAll(" ");
        plain = Normalizer.normalize(plain, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        Set<String> words = new LinkedHashSet<>();
        for (String word : NON_LETTERS.split(plain.toLowerCase(Locale.ROOT))) {
            if (word.length() < 2 || STOPWORDS.contains(word)) {
                continue;
            }
            if (word.length() > 3 && word.endsWith("s") && !word.endsWith("ss")) {
                word = word.substring(0, word.length() - 1);
            }
            words.add(word);
        }
        return words;
    }
}
