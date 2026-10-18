package dev.sieve.ingest.br;

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
import dev.sieve.ingest.AbstractListProvider;
import dev.sieve.ingest.CsvRows;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches Brazil's register of employers caught keeping workers in conditions analogous to slavery
 * (the Cadastro de Empregadores, known as the "lista suja"), published by the Ministry of Labour
 * and Employment as a semicolon-separated CSV in Latin-1.
 *
 * <p>Each row is an employer named after a labour inspection and a final administrative decision:
 * its name, CNPJ (a company) or CPF (a person), the establishment where the workers were found, the
 * number of workers, its business activity code (CNAE), the year of the inspection and the date it
 * was added to the register. Ids are {@code br-} followed by the CNPJ or CPF digits, with a suffix
 * when an employer is listed more than once. The CNPJ or CPF becomes a tax identifier. Names of
 * individual micro-entrepreneurs start with the first eight digits of their CNPJ; the name without
 * them is kept as an alias. Entries are tagged {@link RiskTopic#CRIME}.
 *
 * @see <a href="https://www.gov.br/trabalho-e-emprego/">Ministry of Labour and Employment</a>
 */
public final class BrSlaveLabourProvider extends AbstractListProvider {

    /** The CSV download of the register. */
    public static final String DEFAULT_URL =
            "https://www.gov.br/trabalho-e-emprego/pt-br/assuntos/inspecao-do-trabalho/"
                    + "areas-de-atuacao/cadastro_de_empregadores.csv";

    private static final Pattern CPF = Pattern.compile("^\\d{3}\\.\\d{3}\\.\\d{3}-\\d{2}$");
    private static final Pattern MEI_PREFIX = Pattern.compile("^\\d{2}\\.\\d{3}\\.\\d{3}\\s+(.+)$");
    private static final Pattern DATE = Pattern.compile("\\d{2}/\\d{2}/\\d{4}");
    private static final DateTimeFormatter BR_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final String REASON =
            "Employer found keeping workers in conditions analogous to slavery";
    private static final SanctionsProgram PROGRAM =
            new SanctionsProgram(
                    "Cadastro de Empregadores",
                    "Brazil's register of employers that kept workers in conditions analogous to slavery",
                    ListSource.BR_SLAVE_LABOUR);

    /** Creates a provider that reads the published CSV. */
    public BrSlaveLabourProvider() {
        super(ListSource.BR_SLAVE_LABOUR, URI.create(DEFAULT_URL), "text/csv");
    }

    /**
     * Creates a provider with a custom source URI and HTTP client (for testing).
     *
     * @param sourceUri the URI to fetch the CSV from
     * @param httpClient the HTTP client to use for requests
     */
    public BrSlaveLabourProvider(URI sourceUri, HttpClient httpClient) {
        super(
                ListSource.BR_SLAVE_LABOUR,
                sourceUri,
                "text/csv",
                httpClient,
                Duration.ofSeconds(60));
    }

    @Override
    protected List<SanctionedEntity> parseResponse(byte[] responseBody)
            throws ListIngestionException {
        List<List<String>> rows =
                CsvRows.parse(new String(responseBody, StandardCharsets.ISO_8859_1), ';');
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> columns = new HashMap<>();
        List<String> header = rows.get(0);
        for (int i = 0; i < header.size(); i++) {
            String label = header.get(i).strip().toLowerCase(Locale.ROOT);
            for (String key :
                    List.of(
                            "ano",
                            "uf",
                            "empregador",
                            "cnpj",
                            "estabelecimento",
                            "trabalhadores",
                            "cnae",
                            "decis",
                            "inclus")) {
                if (label.startsWith(key)) {
                    columns.putIfAbsent(key, i);
                }
            }
        }
        if (!columns.containsKey("empregador") || !columns.containsKey("cnpj")) {
            throw new ListIngestionException(
                    "Unexpected header in the Brazilian employers register: "
                            + String.join(";", header),
                    ListSource.BR_SLAVE_LABOUR);
        }

        List<SanctionedEntity> entities = new ArrayList<>();
        Map<String, Integer> repeats = new HashMap<>();
        Instant now = Instant.now();
        for (List<String> row : rows.subList(1, rows.size())) {
            String name = get(row, columns, "empregador");
            String taxId = get(row, columns, "cnpj");
            if (name == null) {
                continue;
            }
            boolean person = taxId != null && CPF.matcher(taxId).matches();
            String digits = taxId == null ? "" : taxId.replaceAll("\\D", "");
            String key = digits.isEmpty() ? name.toLowerCase(Locale.ROOT) : digits;
            int count = repeats.merge(key, 1, Integer::sum);
            String id =
                    "br-"
                            + (digits.isEmpty() ? Integer.toHexString(key.hashCode()) : digits)
                            + (count > 1 ? "-" + count : "");

            List<NameInfo> aliases = new ArrayList<>();
            Matcher mei = MEI_PREFIX.matcher(name);
            if (mei.matches()) {
                aliases.add(
                        new NameInfo(
                                mei.group(1).strip(),
                                null,
                                null,
                                null,
                                null,
                                NameType.AKA,
                                null,
                                null));
            }
            List<Identifier> identifiers =
                    taxId == null
                            ? List.of()
                            : List.of(
                                    new Identifier(
                                            IdentifierType.TAX_ID,
                                            taxId,
                                            "BR",
                                            person ? "CPF" : "CNPJ"));
            String place = get(row, columns, "estabelecimento");
            String state = get(row, columns, "uf");
            List<Address> addresses =
                    place == null && state == null
                            ? List.of()
                            : List.of(
                                    new Address(
                                            null,
                                            null,
                                            state,
                                            null,
                                            "BR",
                                            place == null ? state : place));

            LocalDate added = firstDate(get(row, columns, "inclus"));
            StringJoiner remarks = new StringJoiner("\n");
            String workers = get(row, columns, "trabalhadores");
            if (workers != null) {
                remarks.add("Workers found: " + workers);
            }
            String year = get(row, columns, "ano");
            if (year != null) {
                remarks.add("Year of the labour inspection: " + year);
            }
            String cnae = get(row, columns, "cnae");
            if (cnae != null) {
                remarks.add("Business activity (CNAE): " + cnae);
            }
            String decided = get(row, columns, "decis");
            if (decided != null) {
                remarks.add("Final administrative decision: " + decided);
            }
            String inclusion = get(row, columns, "inclus");
            if (inclusion != null) {
                remarks.add("On the register: " + inclusion);
            }

            entities.add(
                    new SanctionedEntity(
                                    id,
                                    person ? EntityType.INDIVIDUAL : EntityType.ENTITY,
                                    ListSource.BR_SLAVE_LABOUR,
                                    new NameInfo(
                                            name,
                                            null,
                                            null,
                                            null,
                                            null,
                                            NameType.PRIMARY,
                                            NameStrength.STRONG,
                                            null),
                                    aliases,
                                    addresses,
                                    identifiers,
                                    List.of(),
                                    List.of(),
                                    List.of(),
                                    List.of(),
                                    remarks.length() == 0 ? null : remarks.toString(),
                                    List.of(PROGRAM),
                                    added == null
                                            ? null
                                            : added.atStartOfDay(ZoneOffset.UTC).toInstant(),
                                    now,
                                    Set.of(RiskTopic.CRIME),
                                    List.of())
                            .withListingReasons(List.of(REASON)));
        }
        return entities;
    }

    private static LocalDate firstDate(String value) {
        if (value == null) {
            return null;
        }
        Matcher m = DATE.matcher(value);
        if (!m.find()) {
            return null;
        }
        try {
            return LocalDate.parse(m.group(), BR_DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String get(List<String> row, Map<String, Integer> columns, String key) {
        Integer index = columns.get(key);
        return index == null ? null : CsvRows.field(row, index);
    }
}
