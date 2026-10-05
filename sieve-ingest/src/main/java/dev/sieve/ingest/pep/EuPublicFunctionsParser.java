package dev.sieve.ingest.pep;

import dev.sieve.ingest.pep.OjBlocks.Block;
import dev.sieve.ingest.pep.OjBlocks.Cell;
import dev.sieve.ingest.pep.OjBlocks.Heading;
import dev.sieve.ingest.pep.OjBlocks.Lead;
import dev.sieve.ingest.pep.OjBlocks.Paragraph;
import dev.sieve.ingest.pep.OjBlocks.Row;
import dev.sieve.ingest.pep.OjBlocks.Tail;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Reads the European Commission's consolidated list of prominent public functions, which the
 * Official Journal publishes under Article 20a of Directive (EU) 2015/849: each member state's own
 * list of the functions whose holders are politically exposed persons, the international
 * organisations accredited on its territory, and the functions in the Union's institutions.
 *
 * <p>Every member state wrote its list in its own shape: bullet points that restate the directive's
 * categories with national specifics (Austria, Hungary), numbered tables with a category heading
 * over each group (Belgium, Luxembourg, Slovenia), flat lists of titles (Denmark, France,
 * Portugal), two-column tables pairing the directive's wording with the national office (Germany,
 * Croatia, Latvia), a decree in prose (Finland) and tables of international organisations and their
 * senior posts. The parser reads them all with one set of rules. A row is a heading when the rows
 * after it are shaped like its items (deeper in the table, or marked with a bullet or an empty
 * first cell where the heading has text), and the rows under a heading are functions in the
 * category the heading's words name (the words are listed in {@link #CATEGORY_PATTERNS}); a
 * function written without such a heading is filed under the category its own wording names, or
 * none. Under a heading about international organisations, the names are organisations and the
 * lines under them posts.
 *
 * <p>The result is deterministic for a given document, so the catalogue the module ships is this
 * parser's output and the test for the parser checks that they still agree.
 *
 * @see <a href="http://data.europa.eu/eli/C/2023/724/oj">OJ C/2023/724</a>
 */
public final class EuPublicFunctionsParser {

    /**
     * Where the Publications Office serves the list's English text: the CELEX number of OJ
     * C/2023/724, read with {@code Accept: application/xhtml+xml} and {@code Accept-Language: eng}.
     */
    public static final URI SOURCE =
            URI.create("https://publications.europa.eu/resource/celex/52023XC00724");

    private static final String EU_HEADING = "EUROPEAN UNION INSTITUTIONS AND BODIES";
    private static final Pattern COUNTRY_HEADING = Pattern.compile("[A-Z]{2}");
    private static final Pattern OJ_REFERENCE = Pattern.compile("C/\\d{4}/\\d+");
    private static final Pattern OJ_DATE = Pattern.compile("(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})");
    private static final Pattern FOOTNOTE = Pattern.compile("(?<!\\d)\\s*\\(\\d{1,2}\\)(?!\\()");
    private static final Pattern URL = Pattern.compile("(?i)^(https?://|www\\.)");
    private static final Pattern CODE_LIST = Pattern.compile("\\(\\d{4} \\d{4}\\)");
    private static final Pattern SECTION_REF =
            Pattern.compile("\\s*\\((Section|Art\\.?|Article)(?:[^()]|\\([^()]*\\))*\\)\\s*$");
    private static final Pattern SECTION_PREFIX =
            Pattern.compile("(?i)^(section|§)\\s?\\d+[a-z]?\\s+[–—-]\\s+");
    private static final Pattern SECTION_MARKER =
            Pattern.compile("(?i)^(section|§|art\\.?|article|chapter|point)\\s*\\d+[a-z]?\\.?$");
    private static final Pattern COLUMN_TITLE =
            Pattern.compile(";?\\s*INSTITUTION/AUTHORITY/ORGANISATION.*$");
    private static final Pattern INCLUDES = Pattern.compile("(?i)\\s*includes:?\\s*$");
    private static final Pattern QUOTED = Pattern.compile("^[‘'“\"](.*)[’'”\"]$");
    private static final Pattern FUNCTIONS_OF = Pattern.compile("(?i)^the functions? of\\s+");
    private static final Pattern DASH_SPLIT = Pattern.compile("\\s+[–—-]\\s+");
    private static final Pattern LEGAL_PREFIX =
            Pattern.compile(
                    "(?i)^(pursuant to|under|according to|in accordance with) (section|article|"
                            + "art\\.|§)\\s*\\S+( of (the )?[^()]*?)?\\s*\\((.*)\\)\\s*$");
    private static final Pattern PLURAL_HEADING =
            Pattern.compile(
                    "(?i)^(members|heads|judges|board members|elected members|leaders|ambassadors|"
                            + "directors|officers|senior officers|high-ranking)\\b");
    private static final Pattern FUNCTION_START =
            Pattern.compile(
                    "(?i)^(president|vice-president|co-president|director|executive|"
                            + "chair(person|man|woman)?|secretary|head|member|governor|"
                            + "representative|rector|commander|dean|deputy|vice|chief|managing|"
                            + "regional (director|representative|manager|co-?ordinator|head|"
                            + "advis[eo]r|chief|officer|delegate)|"
                            + "country (director|representative|manager|head)|"
                            + "special (representative|advis[eo]r|envoy|co-?ordinator|prosecutor|"
                            + "rapporteur)|senior|assistant|administrative|high commissioner|"
                            + "registrar|prosecutor|judges?|auditor|general(?! secretariat)|"
                            + "financial controller|co-?ordinator|sr\\.|supreme allied|national "
                            + "military|official|programme (manager|officer|director|"
                            + "co-?ordinator)|scientific|animal|acting|joint chair|undersecretary|"
                            + "under-secretary|board of|chef de|permanent (representative|"
                            + "observer(?! mission)|secretary)|ambassador|commissioner|treasurer|counsel\\b|inspector|"
                            + "ombudsman|first vice|liaison officer|resident representative|"
                            + "manager|officer|substitute|principal|legal advis[eo]r)\\b");
    private static final Pattern ADDRESS_LINE =
            Pattern.compile(
                    "(?i)^(\\d|[a-z]-\\d{4}|postal address|tel\\b|fax\\b|e-?mail\\b|\\S+@\\S+$)");
    private static final Pattern CONTACT_LINE =
            Pattern.compile(
                    "(?i)^(tel\\b|fax\\b|e-?mail\\b|\\S+@\\S+$|(https?://|www\\.)\\S+$|"
                            + "[\\w.-]+\\.(eu|int|org|com|net|lu|hr|gov)\\b\\S*$)");
    private static final Pattern PLACE_DATE =
            Pattern.compile("^\\p{Lu}\\p{L}+, \\d{1,2} \\p{L}+ \\d{4}$");
    private static final Pattern TITLE_HEADING =
            Pattern.compile(
                    "(?i)^(regulation|decree|law|act|decision|notification|legislative decree|"
                            + "government decree|ministerial|government gazette|decisions$|"
                            + "laying down|the speaker)\\b|^\\p{L}+ [–—-] notification");
    private static final Pattern APPOINTER =
            Pattern.compile("(?i)\\b(appoints|designates|nominates|appointed by)\\b");
    private static final Pattern ORGANISATION_LIST =
            Pattern.compile(
                    "(?i)\\b(heads|directors|leaders|members|representatives) of the following\\b|"
                            + "the following (accredited )?international organi");

    /**
     * How the lists word things that are not functions: legal preambles, definitions, notes on the
     * list itself.
     */
    private static final Pattern BOILERPLATE =
            Pattern.compile(
                    "(?i)^(the (list|present list|current list|legislation|national list|capacity|"
                            + "category|international organisations?|holders|senior officials|"
                            + "following|provisions|relevant|pay scale)\\b|"
                            + "any information|this\\b|that\\b|pursuant|in accordance|according to|"
                            + "please note|in this regard|"
                            + "politically exposed|natural persons who|family members|"
                            + "for the purposes|classification|by government|have decided|"
                            + "having regard|done at|list (of|indicating)|information provided|"
                            + "a .?prominent public function|no public function|there are no|"
                            + "not applicable|none\\b|n/a\\b|\\[?repealed|\\[rt i|as regards|remark|"
                            + "situation as on|includes:|name [–-] address|functions considered|"
                            + "to compile|articles? \\d|article\\b|presidential decree|joint decision|"
                            + "law \\d|law on\\b|recommendation|the need|the fact|where not elsewhere|"
                            + "published\\b|issn\\b|directive\\b|government gazette|companies marked|"
                            + "wholly state-owned enterprises \\[|"
                            + "[‘'“\"][^’'”\"]{1,80}[’'”\"] (is|means|refers)|"
                            + "[a-z ]{0,40}\\b(is considered|means|refers to)\\b|"
                            + "consist of:|consists of:|job title|\\*)|"
                            + "should be understood|are dismissed|(is|are|may( also)? be) headed by");

    /** How a list writes notes and definitions in between its entries. */
    private static final Pattern NOTE =
            Pattern.compile(
                    "(?i)^(please note|notes?\\b|n\\.?b\\.?\\b|remarks?\\b|comments?:|not applicable|"
                            + "none\\b|n/a\\b|no public function|there are no|\\[?repealed|\\[rt i|"
                            + "as regards|situation as on|includes:|name [–-] address|"
                            + "functions considered|companies marked|"
                            + "wholly state-owned enterprises \\[|\\*|"
                            + "the (list|present list|current list)\\b|this (list|table)\\b|"
                            + "for the purposes|see\\b|source:|any information|in this regard|"
                            + "articles? \\d|article\\b|law \\d|law on\\b|presidential decree|"
                            + "joint decision|legislative decree|ministerial decision|"
                            + "recommendation\\b|the need\\b|the fact\\b|this\\b|the national list|"
                            + "having regard|done at|published\\b|issn\\b|directive\\b|"
                            + "government gazette|regulation \\(|decision no|decree no|act no|"
                            + "family members|classification\\b|no (public )?function|"
                            + "the minister (for|of) [^:]+: \\p{Lu}|"
                            + "[‘'“\"][^’'”\"]{1,80}[’'”\"] (is|means|refers)|"
                            + "[a-z ]{0,40}\\b(is considered|means|refers to)\\b)|"
                            + "should be understood|are dismissed|(is|are|may( also)? be) headed by|"
                            + "has not been included");

    /**
     * The words a post is made of, wherever they stand: tells a post from a name in another tongue.
     */
    private static final Pattern FUNCTION_WORD =
            Pattern.compile(
                    "(?i)\\b(president|director|executive|chair\\w*|secretar(y|ies)|head|member|"
                            + "governor|representative|rector|commander|dean|deputy|vice|chief|"
                            + "manager|officer|registrar|prosecutor|judge|auditor|counsel|"
                            + "commissioner|treasurer|inspector|ombudsman|advis[eo]r|co-?ordinator|"
                            + "controller|official|expert|defender|cabinet|leadership|staff|"
                            + "undersecretary|under-secretary|general|observer|ambassador|envoy|"
                            + "delegate|attach[ée]|rapporteur|principal|substitute|professor|"
                            + "engineer|accountant|lawyer|specialist|assistant|associate)s?\\b");

    /** How a lead-in that defines a term rather than listing functions reads. */
    private static final Pattern DEFINITION =
            Pattern.compile(
                    "(?i)\\b(means|is considered|are considered|refers? to|is defined|are defined|"
                            + "shall mean|are organisations|is an organisation|is understood|"
                            + "are understood)\\b");

    private static final Pattern NOTE_LABEL =
            Pattern.compile("(?i)^(remarks?|notes?|n\\.?b\\.?|please note|comments?)\\b:?$");

    private static final Pattern TABLE_HEADER =
            Pattern.compile(
                    "(?i)^(job title|provision|application|legal category|description of function|"
                            + "prominent public (functions?|positions?)|"
                            + "(situation as on .{0,40} )?international organi[sz]ations?|"
                            + "leading and deputy leading position|politically exposed persons "
                            + "(pursuant|referred).*|politically exposed public positions.*|"
                            + "position \\(code\\).*|.*title of public function.*|law on .*|"
                            + "function according.*)$");

    private static final Pattern INTERNATIONAL = Pattern.compile("(?i)international organi");
    private static final Pattern FINNISH_ENTRY =
            Pattern.compile("(?i)consists? of the functions? of");
    private static final Pattern OTHER_HEADING = Pattern.compile("(?i)^other\\b");

    /**
     * The words each directive category is recognised by, in the English the lists are published
     * in. A text's category is the one whose words come first in it.
     */
    static final Map<PublicFunctionCategory, Pattern> CATEGORY_PATTERNS =
            Map.of(
                    PublicFunctionCategory.HEADS_OF_STATE_AND_GOVERNMENT,
                    pattern(
                            "heads? of (the )?state|heads? of (the )?government|"
                                    + "\\bminist(er|ers|re|ri)\\b|prime minister|"
                                    + "president of the republic|^(the )?president$|"
                                    + "\\bchancellor\\b(?! of justice)|"
                                    + "secretar(y|ies)[- ]general of the government|"
                                    + "secretar(y|ies) of state\\b(?! (firms|enterprises|compan))|"
                                    + "state secretar|\\bthe king\\b|"
                                    + "grand duke|members? of the (federal |provincial |regional |"
                                    + "national )?government|government members|taoiseach|"
                                    + "minister-president|president of the european council|"
                                    + "members? of the european commission"),
                    PublicFunctionCategory.LEGISLATORS,
                    pattern(
                            "parliament|legislat(ive|ure|ors?)\\b|\\bsenat(e|or|ors)\\b|"
                                    + "national assembly|national council\\b(?! (of|for) )|"
                                    + "house of representatives|chamber of deputies|"
                                    + "\\bdeputies\\b(?! to\\b)|"
                                    + "\\b(sejm|saeima|seimas|riigikogu|folketing|bundestag|"
                                    + "bundesrat|eduskunta|riksdag|sabor|dáil|seanad|oireachtas|"
                                    + "cortes|congreso|senado|assembleia|sobranie|országgyűlés|"
                                    + "nationalrat|staten-generaal|eerste kamer|tweede kamer|"
                                    + "vouli)\\b"),
                    PublicFunctionCategory.PARTY_GOVERNING_BODIES,
                    pattern(
                            "political part|\\bpart(y|ies)[’']? (leadership|board|executive|"
                                    + "governing|presidenc|management|supervis|leader)|"
                                    + "party leaders?|partidul|trades? union|\\bthe parties\\b|"
                                    + "employers[’']? association|political movement|"
                                    + "political group"),
                    PublicFunctionCategory.HIGH_COURTS,
                    pattern(
                            "supreme court|constitutional court|constitutional council|"
                                    + "constitutional tribunal|court of cassation|\\bcassation\\b|"
                                    + "high[- ]level judicial|judicial bod|judiciary|"
                                    + "\\bjudges?\\b(?! (of|at) (the )?(\\w+ )?courts? of "
                                    + "(audit|accounts))|"
                                    + "chief justice|\\bjustices\\b|justice of the supreme|"
                                    + "council of state|conseil d[’']?[eé]tat|state tribunal|"
                                    + "\\bhigh courts?\\b|court of appeal|appeals? (court|tribunal)|"
                                    + "\\bprosecutor|attorney[- ]general|state advocate|"
                                    + "\\btribunals?\\b(?! of auditors)|"
                                    + "\\bcourts?\\b(?! of (audit|accounts))|magistrate|"
                                    + "court of justice|advocates?-general"),
                    PublicFunctionCategory.AUDITORS_AND_CENTRAL_BANKS,
                    pattern(
                            "courts? of audit|court of accounts|"
                                    + "audit (office|authority|institution|chamber)\\b|"
                                    + "auditor[- ]general|auditing authorit|supreme audit|"
                                    + "national audit|state audit|rigsrevisionen|rechnungsh|"
                                    + "cour des comptes|central bank|national bank|\\bbank of\\b|"
                                    + "bundesbank|\\bbanque\\b|\\bbanca\\b|\\bbanka\\b|eesti pank|"
                                    + "monetary policy|banking council"),
                    PublicFunctionCategory.DIPLOMATS_AND_ARMED_FORCES,
                    pattern(
                            "ambassador|charg[ée]s? d[’']?\\s?affaires|armed forces|"
                                    + "defence forces|\\bmilitary\\b|general staff|"
                                    + "chiefs? of (the )?(defence )?staff|chief of defence|"
                                    + "\\badmiral|commodore|brigadier|lieutenant[- ]general|"
                                    + "major[- ]general|colonel|general officers?|rank of general|"
                                    + "generals? (and|or) admirals?|\\bdiplomatic\\b|"
                                    + "\\bconsul(s|ate|ates|ar|-general| general)?\\b|"
                                    + "heads? of (the )?(permanent |diplomatic )?missions?|"
                                    + "permanent missions? (to|of)|\\benvoys?\\b|defence attach|"
                                    + "plenipotentiary|\\bdelegations?\\b|"
                                    + "\\bcommanders?(-in-chief)? of the (armed|land|ground|air|"
                                    + "naval|navy|army|defence|special|joint|military|home guard|"
                                    + "national guard|\\w+ forces?)\\b|"
                                    + "\\barmy\\b|\\bnavy\\b|air force|national guard|"
                                    + "gendarmerie|\\barmed\\b"),
                    PublicFunctionCategory.STATE_OWNED_ENTERPRISES,
                    pattern(
                            "state[- ]owned|state owned|state[- ]controlled|publicly[- ]owned|"
                                    + "public (enterprise|undertaking|compan|sector entit|"
                                    + "institute|foundation|establishment|business sector|"
                                    + "corporation)|"
                                    + "enterprises? (under|in which)|capital compan|"
                                    + "majority[- ]owned|owned by the (state|government)|"
                                    + "state treasury|controlled directly or indirectly|"
                                    + "undertakings in which|in which the state|"
                                    + "government (holds|owns)|state[- ]?holding|state ownership|"
                                    + "state firms|undertakings that belong to the public sector"),
                    PublicFunctionCategory.INTERNATIONAL_ORGANISATIONS,
                    pattern(
                            "international(ly)? \\w*\\s?organi[sz]ation|international organi|"
                                    + "international (court|tribunal|institute|centre|center|"
                                    + "commission|committee|agency|bank|fund|monetary|labour|"
                                    + "maritime|civil aviation|atomic|olive|federation|"
                                    + "organization|anti-corruption|visegrad|investment)|"
                                    + "intergovernmental|"
                                    + "\\b(united nations|nato|otan|osce|oecd|unesco|unicef|unhcr|"
                                    + "undp|unido|unodc|unops|ebrd|imf|world bank|"
                                    + "council of europe|european space agency|european patent|"
                                    + "efta|european centre|european organisation|european bank|"
                                    + "european molecular|european southern|european spallation|"
                                    + "secretariat of|peacekeeping)\\b|accredited|"
                                    + "organi[sz]ations? (based|with (its|their) seat|represented|"
                                    + "accredited)|(?-i:Organi[sz]ation (for|of) )|"
                                    + "\\b(interpol|europol|eurocontrol)\\b|"
                                    + "international organisations? in"));

    /** Acronyms of international organisations, matched in capitals only. */
    private static final Pattern ACRONYMS =
            Pattern.compile(
                    "\\b(UN|NATO|OTAN|OSCE|OECD|UNESCO|UNICEF|UNHCR|UNDP|UNIDO|UNODC|UNOPS|WHO|IOM|"
                            + "EBRD|IMF|EFTA|ESA|ICRC|OPCW|IAEA|OPEC|FAO|ILO|WTO|CBSS|NDPHS|IDEA|"
                            + "UNFICYP|ICJ|ICC|ICTP|CERN|ICMPD|CTBTO|IACA|KAICIID|OFID|UNOV|EPO|"
                            + "ECMWF|IRMCT|IUSCT|NSPA|EYCB|IFRC|IOTA|REC|UNOCT|CIC|ICCROM|CIHEAM|"
                            + "ICGEB|IOC|UCCI|ICCAT|ATEI|CYTED|GWPO|WMU|ERIC|SELEC|UNEP|ICAO|EPPO|"
                            + "CCNR|ESO|EMBL|EUMETSAT|OCCAR|ICMP)\\b");

    private EuPublicFunctionsParser() {}

    private static Pattern pattern(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /**
     * Parses the list.
     *
     * @param xhtml the Publications Office's XHTML rendering of the Official Journal document
     * @return the catalogue it holds
     * @throws IOException when the document cannot be read or is not the list
     */
    public static PublicFunctionCatalog parse(InputStream xhtml) throws IOException {
        return new Interpreter(OjBlocks.read(xhtml)).run();
    }

    /**
     * Fetches the list from the Publications Office and parses it.
     *
     * @param client the HTTP client to use
     * @param source the document's address, normally {@link #SOURCE}
     * @return the catalogue
     * @throws IOException when the document cannot be fetched or read
     * @throws InterruptedException when interrupted while fetching
     */
    public static PublicFunctionCatalog fetch(HttpClient client, URI source)
            throws IOException, InterruptedException {
        HttpRequest request =
                HttpRequest.newBuilder(source)
                        .timeout(Duration.ofMinutes(2))
                        .header("Accept", "application/xhtml+xml")
                        .header("Accept-Language", "eng")
                        .header(
                                "User-Agent",
                                "sieve-aml/1.0 (https://github.com/AbgarSim/sieve-aml; sanctions"
                                        + " and PEP screening)")
                        .GET()
                        .build();
        HttpResponse<InputStream> response =
                client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException(
                    "Publications Office answered " + response.statusCode() + " for " + source);
        }
        try (InputStream body = response.body()) {
            return parse(body);
        }
    }

    /**
     * Regenerates the bundled catalogue: {@code <input> <output.json>}, where the input is a file
     * or an {@code http(s)} address (by default {@link #SOURCE}).
     *
     * @param args the input and the output path
     * @throws Exception when either cannot be read or written
     */
    public static void main(String[] args) throws Exception {
        String input = args.length > 0 ? args[0] : SOURCE.toString();
        Path output =
                Path.of(
                        args.length > 1
                                ? args[1]
                                : "sieve-ingest/src/main/resources/dev/sieve/ingest/pep/"
                                        + "eu-public-functions.json");
        PublicFunctionCatalog catalog;
        if (input.startsWith("http://") || input.startsWith("https://")) {
            catalog =
                    fetch(
                            HttpClient.newBuilder()
                                    .followRedirects(HttpClient.Redirect.NORMAL)
                                    .build(),
                            URI.create(input));
        } else {
            try (InputStream file = Files.newInputStream(Path.of(input));
                    InputStream in = input.endsWith(".gz") ? new GZIPInputStream(file) : file) {
                catalog = parse(in);
            }
        }
        try (OutputStream out = Files.newOutputStream(output)) {
            catalog.write(out);
        }
        System.out.println(
                "Wrote "
                        + catalog.size()
                        + " functions of "
                        + catalog.jurisdictions().size()
                        + " jurisdictions to "
                        + output);
    }

    /**
     * The kinds of item marker a list writes in a row's first cell, from headings down to items.
     */
    enum Marker {
        NONE,
        NUMBER,
        LETTER,
        DASH,
        STAR,
        EMPTY
    }

    /** What a row looks like, which tells sibling items from the heading over them. */
    record Shape(int depth, Marker marker, int textCells, boolean trailingEmpty) {}

    /** Returns the marker a cell is, or {@link Marker#NONE} when it holds text. */
    static Marker marker(String cell) {
        String text = FOOTNOTE.matcher(cell).replaceAll("").strip();
        if (text.isEmpty()) {
            return Marker.EMPTY;
        }
        if (text.matches("[—–\\-•·●]|\\[●\\]")) {
            return Marker.DASH;
        }
        if (text.matches("\\*{1,3}")) {
            return Marker.STAR;
        }
        if (text.matches("\\(?\\d{1,3}(\\.\\d{1,2})*[.)°]?\\)?")
                || SECTION_MARKER.matcher(text).matches()) {
            return Marker.NUMBER;
        }
        if (text.matches("\\(?([a-zA-Z]|[ivx]{1,4}|[IVX]{1,4})[.)°]\\)?|\\([a-zA-Z]\\)")) {
            return Marker.LETTER;
        }
        return Marker.NONE;
    }

    /** The category a text's wording names first, if any. */
    static Optional<PublicFunctionCategory> categoryOf(String text) {
        PublicFunctionCategory best = null;
        int bestIndex = Integer.MAX_VALUE;
        int bestLength = 0;
        for (PublicFunctionCategory category : PublicFunctionCategory.values()) {
            Matcher matcher = CATEGORY_PATTERNS.get(category).matcher(text);
            if (matcher.find()) {
                int length = matcher.end() - matcher.start();
                if (matcher.start() < bestIndex
                        || (matcher.start() == bestIndex && length > bestLength)) {
                    best = category;
                    bestIndex = matcher.start();
                    bestLength = length;
                }
            }
        }
        Matcher acronym = ACRONYMS.matcher(text);
        if (acronym.find() && acronym.start() < bestIndex) {
            best = PublicFunctionCategory.INTERNATIONAL_ORGANISATIONS;
        }
        return Optional.ofNullable(best);
    }

    /** How many category words a text has, over all categories. */
    static int hits(String text) {
        int hits = 0;
        for (Pattern pattern : CATEGORY_PATTERNS.values()) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                hits++;
            }
        }
        return hits;
    }

    private static boolean isBoilerplate(String text) {
        return BOILERPLATE.matcher(text).find();
    }

    private static boolean isNote(String text) {
        return NOTE.matcher(text.strip()).find();
    }

    private static boolean isFunctionStart(String text) {
        return FUNCTION_START.matcher(text.strip()).find();
    }

    /**
     * Whether a lead-in defines a term rather than introducing functions: it uses a defining verb
     * and does not itself start like a function ("members of the boards of ... which are considered
     * large enterprises:" introduces functions).
     */
    private static boolean isDefinition(String text) {
        if (!DEFINITION.matcher(text).find()) {
            return false;
        }
        Matcher word = FUNCTION_WORD.matcher(stripMarker(text).replaceFirst("(?i)^the\\s+", ""));
        return !(word.find() && word.start() == 0);
    }

    private static String stripMarker(String text) {
        return text.replaceFirst("^[—–\\-•·●]\\s*", "").strip();
    }

    /** Tidies a function or heading as a list wrote it: no list punctuation, no footnote marks. */
    static String clean(String text) {
        String cleaned = OjBlocks.normalize(stripMarker(text));
        cleaned = FOOTNOTE.matcher(cleaned).replaceAll("");
        cleaned = SECTION_REF.matcher(cleaned).replaceFirst("");
        cleaned = OjBlocks.normalize(cleaned);
        cleaned = cleaned.replaceAll("(\\s*[;,.:\\-–—*])+$", "").strip();
        Matcher quoted = QUOTED.matcher(cleaned);
        if (quoted.matches()) {
            cleaned = quoted.group(1).strip();
        }
        if (cleaned.startsWith("(") && cleaned.endsWith(")") && cleaned.indexOf('(', 1) < 0) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).strip();
        }
        return cleaned;
    }

    /** Cuts a parenthesis that never closes, as a lead-in's aside that runs into its colon does. */
    private static String cutUnclosed(String text) {
        int depth = 0;
        int open = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                if (depth++ == 0) {
                    open = i;
                }
            } else if (c == ')' && depth > 0) {
                depth--;
            }
        }
        return depth > 0 && open >= 0 ? text.substring(0, open) : text;
    }

    /** Tidies a heading: drops column titles, section references and legal lead-ins. */
    static String headingText(String text) {
        String cleaned = COLUMN_TITLE.matcher(text).replaceFirst("");
        cleaned = SECTION_PREFIX.matcher(cleaned).replaceFirst("");
        cleaned = INCLUDES.matcher(cleaned).replaceFirst("");
        Matcher legal = LEGAL_PREFIX.matcher(cleaned.strip());
        if (legal.matches()) {
            cleaned = legal.group(legal.groupCount());
        }
        return clean(cutUnclosed(cleaned));
    }

    private static final class Interpreter {
        private final List<Block> blocks;
        private String title;
        private String reference;
        private String eli;
        private LocalDate published;

        private String jurisdiction;

        /** The heading over the current part of a list and the category its words name. */
        private String sectionHeading;

        private PublicFunctionCategory sectionCategory;

        /** Whether the current section is about international organisations. */
        private boolean international;

        /** Whether the items under the current heading are organisations listed as functions. */
        private boolean organisationList;

        private String organisation;
        private boolean organisationOpen = true;
        private int orgTableDepth = -1;

        /** Whether the rows that follow spell out a definition rather than functions. */
        private boolean skipItems;

        /** The row shapes that have introduced items in the current group of rows, by depth. */
        private final List<Shape> headerShapes = new ArrayList<>();

        /** The headings open over the current row, innermost last. */
        private final Deque<Frame> frames = new ArrayDeque<>();

        private final Set<PublicFunction> functions = new LinkedHashSet<>();

        /**
         * A heading open over the current rows.
         *
         * @param nested whether a row's lead-in opened it, so that a row at the same depth closes
         *     it
         */
        private record Frame(
                int depth,
                int level,
                String heading,
                PublicFunctionCategory category,
                boolean nested) {}

        /** How the lines in a part about international organisations read. */
        private enum Mode {
            /**
             * Lines may name an organisation; a post may be followed by its organisation's name.
             */
            NAMES,
            /** A post may be followed by its organisation's name; other lines are noise. */
            PAIRS,
            /** Every line is a post. */
            FUNCTIONS
        }

        Interpreter(List<Block> blocks) {
            this.blocks = joinMarkerTables(blocks);
        }

        /**
         * Rejoins rows whose first cell the Journal rendered as a one-row table holding a marker
         * and a text (Germany's and Bulgaria's category rows): the text becomes the row's first
         * cell and the rest of the row follows it, so the row reads like any two-column row.
         */
        private static List<Block> joinMarkerTables(List<Block> blocks) {
            List<Block> out = new ArrayList<>(blocks.size());
            for (int i = 0; i < blocks.size(); i++) {
                Block block = blocks.get(i);
                if (block instanceof Lead lead
                        && textCells(lead.cells()).isEmpty()
                        && i + 2 < blocks.size()
                        && blocks.get(i + 1) instanceof Row inner
                        && isMarkerRow(inner, lead.depth() + 1)) {
                    Block after = blocks.get(i + 2);
                    List<Cell> rest = null;
                    if (after instanceof Tail tail && tail.depth() == lead.depth()) {
                        rest = tail.cells();
                    } else if (after instanceof Lead next
                            && next.depth() == lead.depth()
                            && next.cells().size() > lead.cells().size()) {
                        rest = next.cells().subList(lead.cells().size(), next.cells().size());
                    }
                    if (rest != null) {
                        List<Cell> cells = new ArrayList<>();
                        cells.add(new Cell(List.of(String.join(" ", textCells(inner.cells())))));
                        cells.addAll(rest);
                        out.add(new Row(lead.depth(), cells));
                        i += 2;
                        continue;
                    }
                }
                if (block instanceof Tail tail) {
                    if (!textCells(tail.cells()).isEmpty()) {
                        out.add(new Row(tail.depth(), tail.cells()));
                    }
                    continue;
                }
                out.add(block);
            }
            return out;
        }

        /**
         * Whether a row is a marker and one text at the given depth: a numbered or lettered item.
         */
        private static boolean isMarkerRow(Row row, int depth) {
            if (row.depth() != depth || row.cells().size() != 2) {
                return false;
            }
            Marker marker = marker(row.cells().get(0).text());
            return (marker == Marker.NUMBER || marker == Marker.LETTER)
                    && textCells(row.cells()).size() == 1;
        }

        PublicFunctionCatalog run() throws IOException {
            for (int i = 0; i < blocks.size(); i++) {
                Block block = blocks.get(i);
                if (block instanceof Heading h) {
                    heading(h.text());
                } else if (block instanceof Paragraph p) {
                    paragraph(p, i);
                } else if (block instanceof Lead l) {
                    lead(l, i);
                } else if (block instanceof Row r) {
                    row(r, i);
                }
            }
            if (jurisdictionCount() == 0 || reference == null) {
                throw new IOException("The document is not the Official Journal list of functions");
            }
            return new PublicFunctionCatalog(
                    title, reference, published, eli, new ArrayList<>(functions));
        }

        private int jurisdictionCount() {
            Set<String> seen = new LinkedHashSet<>();
            functions.forEach(f -> seen.add(f.jurisdiction()));
            return seen.size();
        }

        // ---- headings and paragraphs -------------------------------------------------------

        private void heading(String text) {
            String clean = OjBlocks.normalize(text);
            if (COUNTRY_HEADING.matcher(clean).matches()) {
                jurisdiction = "EL".equals(clean) ? "GR" : clean;
                section(null, null, false);
                return;
            }
            if (EU_HEADING.equals(clean)) {
                jurisdiction = "EU";
                section(null, null, false);
                return;
            }
            if (jurisdiction == null) {
                return;
            }
            PublicFunctionCategory named =
                    TITLE_HEADING.matcher(clean).find() ? null : categoryOf(clean).orElse(null);
            if (named == null && international && OTHER_HEADING.matcher(clean).find()) {
                named = PublicFunctionCategory.INTERNATIONAL_ORGANISATIONS;
            }
            section(headingText(clean), named, ORGANISATION_LIST.matcher(clean).find());
        }

        /** Opens a section: everything under a heading, until the next one. */
        private void section(String heading, PublicFunctionCategory category, boolean orgList) {
            sectionHeading = heading;
            sectionCategory = category;
            international = category == PublicFunctionCategory.INTERNATIONAL_ORGANISATIONS;
            organisationList = orgList;
            organisation = null;
            organisationOpen = true;
            orgTableDepth = -1;
            skipItems = false;
            headerShapes.clear();
            frames.clear();
        }

        private void paragraph(Paragraph p, int index) {
            String css = p.cssClass();
            String text = p.text();
            if (text.startsWith("ELI:")) {
                eli = text.substring(4).strip();
                return;
            }
            if (css.contains("oj-doc-ti")) {
                if (title == null && jurisdiction == null) {
                    title = text;
                }
                return;
            }
            if (css.contains("oj-note")
                    || css.contains("oj-signatory")
                    || css.contains("oj-hd")
                    || css.contains("oj-no-doc")
                    || css.contains("oj-ti-tbl")
                    || css.contains("oj-final")
                    || css.contains("oj-doc-end")
                    || jurisdiction == null) {
                return;
            }
            skipItems = false;
            boolean endsWithColon = text.endsWith(":");
            Matcher finnish = FINNISH_ENTRY.matcher(text);
            if (finnish.find() && !endsWithColon) {
                function(
                        text.substring(finnish.end()),
                        categoryOf(text).orElse(sectionCategory),
                        sectionHeading,
                        null);
                return;
            }
            if (international) {
                internationalParagraph(text, endsWithColon);
                return;
            }
            Block next = nextBlock(index);
            if (next instanceof Row || next instanceof Lead) {
                if (isBoilerplate(text) && !endsWithColon) {
                    return;
                }
                if (endsWithColon && isDefinition(text)) {
                    skipItems = true;
                    return;
                }
                PublicFunctionCategory named =
                        APPOINTER.matcher(text).find() ? null : categoryOf(text).orElse(null);
                section(headingText(text), named, ORGANISATION_LIST.matcher(text).find());
                return;
            }
            if (text.length() <= 160
                    && !endsWithColon
                    && !isBoilerplate(text)
                    && !URL.matcher(text).find()
                    && !ADDRESS_LINE.matcher(text).find()
                    && !PLACE_DATE.matcher(text).matches()
                    && !text.isEmpty()
                    && Character.isUpperCase(text.charAt(0))) {
                function(text, categoryOf(text).orElse(sectionCategory), sectionHeading, null);
            }
        }

        /** A paragraph in a section about international organisations: a name, a post or noise. */
        private void internationalParagraph(String text, boolean endsWithColon) {
            if (endsWithColon) {
                skipItems = isBoilerplate(text) || isDefinition(text);
                return;
            }
            if (PLURAL_HEADING.matcher(text).find() && hits(text) >= 2) {
                return;
            }
            if (CONTACT_LINE.matcher(text).find()) {
                organisationOpen = true;
                return;
            }
            if (ADDRESS_LINE.matcher(text).find() || isBoilerplate(text)) {
                return;
            }
            if (isFunctionStart(text)) {
                function(text, internationalCategory(), sectionHeading, organisation);
                return;
            }
            PublicFunctionCategory named = categoryOf(text).orElse(null);
            if (named != null
                    && named != PublicFunctionCategory.INTERNATIONAL_ORGANISATIONS
                    && named != PublicFunctionCategory.DIPLOMATS_AND_ARMED_FORCES
                    && !ACRONYMS.matcher(text).find()) {
                // a national body named after the international organisations: a new part
                section(headingText(text), named, false);
                return;
            }
            organisation = clean(text);
            organisationOpen = false;
        }

        // ---- table rows ---------------------------------------------------------------------

        private void lead(Lead lead, int index) {
            if (jurisdiction == null) {
                return;
            }
            List<String> cells = textCells(lead.cells());
            if (cells.isEmpty()) {
                return;
            }
            String joined = String.join(" ", cells);
            skipItems = joined.endsWith(":") && isDefinition(joined);
            if (skipItems) {
                return;
            }
            closeFrames(lead.depth(), 0);
            List<Cell> all = lead.cells();
            Shape shape =
                    new Shape(
                            lead.depth(),
                            Marker.NONE,
                            cells.size(),
                            all.size() >= 2 && all.get(all.size() - 1).text().isBlank());
            if (isInternational()) {
                List<String> named = cells.stream().filter(c -> !isBoilerplate(c)).toList();
                if (named.isEmpty()) {
                    return;
                }
                String first = named.get(0);
                if (first.endsWith(":") && hits(first) >= 2 && !isInternationalText(first)) {
                    frames.push(
                            new Frame(
                                    lead.depth(),
                                    0,
                                    headingText(first),
                                    categoryOf(first).orElse(null),
                                    true));
                    return;
                }
                organisation = clean(first);
                organisationOpen = false;
                for (Cell cell : all) {
                    String text = cell.text();
                    if (text.equals(first) || !named.contains(text)) {
                        continue;
                    }
                    for (String line : lines(cell)) {
                        if (isFunctionStart(line)) {
                            function(line, internationalCategory(), currentHeading(), organisation);
                        }
                    }
                }
                return;
            }
            String text = String.join(" ", cells);
            boolean endsWithColon = text.endsWith(":");
            if (isBoilerplate(text) && !endsWithColon) {
                return;
            }
            Matcher codes = CODE_LIST.matcher(text);
            int codeCount = 0;
            while (codes.find()) {
                codeCount++;
            }
            boolean header =
                    endsWithColon
                            || (!isBoilerplate(text)
                                    && codeCount < 2
                                    && (hits(text) >= 1
                                            || PLURAL_HEADING.matcher(text).find()
                                            || ORGANISATION_LIST.matcher(text).find()));
            if (!header) {
                function(text, currentCategory(), currentHeading(), null);
                return;
            }
            PublicFunctionCategory named =
                    APPOINTER.matcher(text).find() ? null : categoryOf(text).orElse(null);
            if (named == null && !frames.isEmpty()) {
                named = frames.peek().category();
            }
            if (!headerShapes.contains(shape)) {
                headerShapes.add(shape);
            }
            frames.push(new Frame(lead.depth(), 0, headingText(text), named, true));
            organisationList = ORGANISATION_LIST.matcher(text).find();
            if (named == PublicFunctionCategory.INTERNATIONAL_ORGANISATIONS) {
                organisation = null;
                organisationOpen = true;
            }
        }

        private void row(Row row, int index) {
            if (jurisdiction == null) {
                meta(row);
                return;
            }
            List<Cell> text = textCellList(row.cells());
            if (text.isEmpty() || skipItems) {
                return;
            }
            if (orgTableDepth >= 0 && row.depth() < orgTableDepth) {
                orgTableDepth = -1;
                organisation = null;
            }
            List<String> values = text.stream().map(Cell::text).toList();
            if (values.stream().anyMatch(v -> TABLE_HEADER.matcher(strip(v)).matches())) {
                if (values.stream().anyMatch(v -> INTERNATIONAL.matcher(v).find())) {
                    orgTableDepth = row.depth();
                    organisation = null;
                }
                return;
            }
            String first = values.get(0);
            boolean endsWithColon = first.endsWith(":");
            if (NOTE_LABEL.matcher(first).find() || (isNote(first) && !endsWithColon)) {
                return;
            }
            Shape shape = shape(row, text.size());
            if (text.size() == 1
                    && shape.marker() == Marker.NONE
                    && isBoilerplate(first)
                    && !endsWithColon) {
                return;
            }
            closeNested(row.depth());
            closeFrames(row.depth(), Integer.MAX_VALUE);
            if (text.size() >= 2) {
                twoColumnRow(row, text);
                return;
            }
            Row next = nextRow(index);
            boolean knownHeader = headerShapes.contains(shape);
            boolean childLike = childLike(shape, next);
            if (isInternational() && !organisationList) {
                boolean heading =
                        knownHeader
                                || (endsWithColon
                                        && hits(first) >= 2
                                        && !isInternationalText(first));
                if (!heading) {
                    internationalRow(text.get(0), shape);
                    return;
                }
            }
            boolean header = !organisationList && (childLike || knownHeader || endsWithColon);
            if (!header) {
                for (String line : lines(text.get(0))) {
                    function(line, currentCategory(), currentHeading(), null);
                }
                return;
            }
            int level = headerLevel(shape);
            // "Appointed by ..." groups entries under the heading before them rather than replacing
            // it
            boolean appointer = APPOINTER.matcher(first).find();
            closeFrames(row.depth(), appointer ? level + 1 : level);
            PublicFunctionCategory named = appointer ? null : categoryOf(first).orElse(null);
            if (named == null && (level > 0 || appointer) && !frames.isEmpty()) {
                named = frames.peek().category();
            }
            boolean childless = !childLike && !deeperHeader(next, row.depth(), level);
            if (childless
                    && (hits(first) >= 1
                            || PLURAL_HEADING.matcher(first).find()
                            || isFunctionStart(first))) {
                // a heading with nothing under it is a function in its own right
                function(first, named != null ? named : currentCategory(), currentHeading(), null);
            }
            frames.push(new Frame(row.depth(), level, headingText(first), named, false));
            organisationList = ORGANISATION_LIST.matcher(first).find();
            if (named == PublicFunctionCategory.INTERNATIONAL_ORGANISATIONS) {
                organisation = null;
                organisationOpen = true;
            }
        }

        /** A row with a heading-like first cell and functions in the cells after it. */
        private void twoColumnRow(Row row, List<Cell> text) {
            String first = text.get(0).text();
            if (orgTableDepth >= 0 && row.depth() == orgTableDepth) {
                List<String> organisations = lines(text.get(0));
                List<String> posts = lines(text.get(1));
                if (text.size() == 2
                        && organisations.size() > 1
                        && organisations.size() == posts.size()
                        && organisations.stream().allMatch(o -> o.contains(" "))) {
                    for (int i = 0; i < organisations.size(); i++) {
                        organisation = clean(organisations.get(i));
                        internationalLines(List.of(posts.get(i)), Mode.FUNCTIONS);
                    }
                    return;
                }
                organisation = clean(first);
                for (int c = 1; c < text.size(); c++) {
                    internationalLines(lines(text.get(c)), Mode.FUNCTIONS);
                }
                return;
            }
            PublicFunctionCategory named =
                    APPOINTER.matcher(first).find() ? null : categoryOf(first).orElse(null);
            int level = headerLevel(shape(row, text.size()));
            closeFrames(row.depth(), level);
            frames.push(new Frame(row.depth(), level, headingText(first), named, false));
            if (named == PublicFunctionCategory.INTERNATIONAL_ORGANISATIONS) {
                organisation = null;
                organisationOpen = true;
                for (int c = 1; c < text.size(); c++) {
                    List<String> lines = lines(text.get(c));
                    internationalLines(lines, alternating(lines) ? Mode.PAIRS : Mode.FUNCTIONS);
                }
                return;
            }
            for (int c = 1; c < text.size(); c++) {
                for (String line : lines(text.get(c))) {
                    function(line, currentCategory(), currentHeading(), null);
                }
            }
        }

        /** A single cell in a part about international organisations: an organisation or posts. */
        private void internationalRow(Cell cell, Shape shape) {
            Mode mode =
                    switch (shape.marker()) {
                        case NONE -> Mode.NAMES;
                        case EMPTY -> Mode.PAIRS;
                        default -> Mode.FUNCTIONS;
                    };
            internationalLines(lines(cell), mode);
        }

        /** Whether lines alternate between a post and a name: Germany's organisation column. */
        private static boolean alternating(List<String> lines) {
            if (lines.size() < 2 || lines.size() % 2 != 0) {
                return false;
            }
            for (int i = 0; i < lines.size(); i++) {
                if (isFunctionStart(lines.get(i)) != (i % 2 == 0)) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Reads lines about international organisations: {@code organisation – post} pairs, posts
         * under the organisation named before them, or an organisation's name.
         */
        private void internationalLines(List<String> lines, Mode mode) {
            PublicFunctionCategory category = internationalCategory();
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (isNote(line) || (mode != Mode.FUNCTIONS && isBoilerplate(line))) {
                    continue;
                }
                if (mode == Mode.FUNCTIONS && !FUNCTION_WORD.matcher(line).find()) {
                    // a parent organisation, or the same post in the country's own language
                    continue;
                }
                String[] parts = DASH_SPLIT.split(line);
                if (parts.length >= 2
                        && isFunctionStart(parts[parts.length - 1])
                        && !isFunctionStart(parts[0])) {
                    organisation = clean(parts[0]);
                    organisationOpen = false;
                    function(parts[parts.length - 1], category, currentHeading(), organisation);
                    organisationOpen = true;
                    continue;
                }
                if (CONTACT_LINE.matcher(line).find()) {
                    organisationOpen = true;
                    continue;
                }
                if (ADDRESS_LINE.matcher(line).find()) {
                    continue;
                }
                if (mode != Mode.FUNCTIONS && !isFunctionStart(line)) {
                    if (mode == Mode.NAMES && organisationOpen) {
                        organisation = clean(line);
                        organisationOpen = false;
                    }
                    continue;
                }
                String own = organisation;
                if (mode != Mode.FUNCTIONS
                        && i + 1 < lines.size()
                        && !isFunctionStart(lines.get(i + 1))
                        && !ADDRESS_LINE.matcher(lines.get(i + 1)).find()
                        && DASH_SPLIT.split(lines.get(i + 1)).length == 1
                        && !isBoilerplate(lines.get(i + 1))) {
                    own = clean(lines.get(i + 1));
                    i++;
                }
                List<String> posts = List.of(line);
                if (line.contains(", ")) {
                    String[] split = line.split(",\\s+");
                    if (split.length >= 2
                            && Arrays.stream(split)
                                    .allMatch(EuPublicFunctionsParser::isFunctionStart)) {
                        posts = List.of(split);
                    }
                }
                for (String post : posts) {
                    function(post, category, currentHeading(), own);
                }
                organisationOpen = true;
            }
        }

        // ---- shapes and headings ------------------------------------------------------------

        private boolean isInternational() {
            return international
                    || orgTableDepth >= 0
                    || currentCategory() == PublicFunctionCategory.INTERNATIONAL_ORGANISATIONS;
        }

        /** The category of a post in a part about international organisations. */
        private PublicFunctionCategory internationalCategory() {
            PublicFunctionCategory current = currentCategory();
            return current != null ? current : PublicFunctionCategory.INTERNATIONAL_ORGANISATIONS;
        }

        private static boolean isInternationalText(String text) {
            return categoryOf(text).orElse(null)
                    == PublicFunctionCategory.INTERNATIONAL_ORGANISATIONS;
        }

        private String currentHeading() {
            return frames.isEmpty() ? sectionHeading : frames.peek().heading();
        }

        private PublicFunctionCategory currentCategory() {
            return frames.isEmpty() ? sectionCategory : frames.peek().category();
        }

        /** Closes the headings at a greater depth, and those at this depth from a level down. */
        private void closeFrames(int depth, int level) {
            while (!frames.isEmpty()) {
                Frame top = frames.peek();
                if (top.depth() > depth || (top.depth() == depth && top.level() >= level)) {
                    frames.pop();
                } else {
                    break;
                }
            }
        }

        /** Closes the headings a lead-in opened at this depth: a row here is their sibling. */
        private void closeNested(int depth) {
            while (!frames.isEmpty() && frames.peek().depth() == depth && frames.peek().nested()) {
                frames.pop();
            }
        }

        /** The level of a heading shape: the order in which shapes of its depth became headings. */
        private int headerLevel(Shape shape) {
            if (!headerShapes.contains(shape)) {
                headerShapes.add(shape);
            }
            return levelOf(shape);
        }

        /** The level of a known heading shape, or -1. */
        private int levelOf(Shape shape) {
            int level = 0;
            for (Shape s : headerShapes) {
                if (s.equals(shape)) {
                    return level;
                }
                if (s.depth() == shape.depth()) {
                    level++;
                }
            }
            return -1;
        }

        /** Whether the next row is shaped like an item under a row of this shape. */
        private static boolean childLike(Shape shape, Row next) {
            if (next == null) {
                return false;
            }
            Shape other = shape(next, textCells(next.cells()).size());
            if (other.depth() != shape.depth()) {
                return other.depth() > shape.depth();
            }
            return other.marker().ordinal() > shape.marker().ordinal();
        }

        /**
         * Whether the next row is a known heading shape under a heading of this depth and level.
         */
        private boolean deeperHeader(Row next, int depth, int level) {
            if (next == null) {
                return false;
            }
            Shape other = shape(next, textCells(next.cells()).size());
            int otherLevel = levelOf(other);
            if (otherLevel < 0) {
                return false;
            }
            return other.depth() > depth || (other.depth() == depth && otherLevel > level);
        }

        private Row nextRow(int index) {
            for (int i = index + 1; i < blocks.size(); i++) {
                Block block = blocks.get(i);
                if (block instanceof Row r) {
                    if (!textCells(r.cells()).isEmpty()) {
                        return r;
                    }
                } else if (block instanceof Lead l) {
                    if (!textCells(l.cells()).isEmpty()) {
                        return null;
                    }
                } else {
                    return null;
                }
            }
            return null;
        }

        private Block nextBlock(int index) {
            for (int i = index + 1; i < blocks.size(); i++) {
                Block block = blocks.get(i);
                if (block instanceof Row r) {
                    if (!textCells(r.cells()).isEmpty()) {
                        return block;
                    }
                } else if (block instanceof Lead l) {
                    if (!textCells(l.cells()).isEmpty()) {
                        return block;
                    }
                } else {
                    return block;
                }
            }
            return null;
        }

        // ---- functions ----------------------------------------------------------------------

        private void function(
                String text,
                PublicFunctionCategory functionCategory,
                String functionHeading,
                String functionOrganisation) {
            String stripped = text.strip();
            if (URL.matcher(stripped).find() || isNote(stripped)) {
                return;
            }
            String base = FUNCTIONS_OF.matcher(stripped).replaceFirst("");
            Matcher legal = LEGAL_PREFIX.matcher(base);
            if (legal.matches()) {
                base = legal.group(legal.groupCount());
            }
            String cleaned = clean(base);
            if (cleaned.length() < 2 || isNote(cleaned)) {
                return;
            }
            List<String> items = List.of(cleaned);
            if (CODE_LIST.matcher(cleaned).find()) {
                // Bulgaria: entries with their occupation codes, several to a line
                items =
                        List.of(
                                cleaned.replaceAll("\\s*\\*{1,3}(?=[,;]|$)", "")
                                        .split("(?<=\\(\\d{4} \\d{4}\\))\\s*[,;]\\s*"));
            }
            for (String item : items) {
                String function = clean(item);
                if (function.length() < 2 || isNote(function)) {
                    continue;
                }
                PublicFunctionCategory itemCategory =
                        functionCategory != null
                                ? functionCategory
                                : categoryOf(function).orElse(null);
                functions.add(
                        new PublicFunction(
                                jurisdiction,
                                itemCategory,
                                functionHeading,
                                function,
                                functionOrganisation));
            }
        }

        private void meta(Row row) {
            for (Cell cell : row.cells()) {
                String value = cell.text();
                if (reference == null && OJ_REFERENCE.matcher(value).matches()) {
                    reference = value;
                }
                Matcher date = OJ_DATE.matcher(value);
                if (published == null && date.matches()) {
                    published =
                            LocalDate.parse(
                                    String.format(
                                            "%s-%02d-%02d",
                                            date.group(3),
                                            Integer.parseInt(date.group(2)),
                                            Integer.parseInt(date.group(1))),
                                    DateTimeFormatter.ISO_LOCAL_DATE);
                }
            }
        }

        private static Shape shape(Row row, int textCells) {
            List<Cell> cells = row.cells();
            Marker marker = cells.isEmpty() ? Marker.EMPTY : marker(cells.get(0).text());
            boolean trailingEmpty =
                    cells.size() >= 2 && cells.get(cells.size() - 1).text().isBlank();
            return new Shape(row.depth(), marker, textCells, trailingEmpty);
        }

        /** A cell's lines, with a line that continues the one before it (in brackets) rejoined. */
        private static List<String> lines(Cell cell) {
            List<String> out = new ArrayList<>();
            for (String line : cell.lines()) {
                if (!out.isEmpty() && (line.startsWith("(") || line.startsWith("and "))) {
                    out.set(out.size() - 1, out.get(out.size() - 1) + " " + line);
                } else {
                    out.add(line);
                }
            }
            return out;
        }

        /** The cells holding text: neither a list marker nor a link. */
        private static List<Cell> textCellList(List<Cell> cells) {
            List<Cell> text = new ArrayList<>();
            for (Cell cell : cells) {
                String value = cell.text();
                if (marker(value) == Marker.NONE && !URL.matcher(value).find()) {
                    text.add(cell);
                }
            }
            return text;
        }

        private static List<String> textCells(List<Cell> cells) {
            return textCellList(cells).stream().map(Cell::text).toList();
        }

        private static String strip(String value) {
            return FOOTNOTE.matcher(value).replaceAll("").strip().toLowerCase(Locale.ROOT);
        }
    }
}
