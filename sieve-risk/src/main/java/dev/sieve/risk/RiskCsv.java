package dev.sieve.risk;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Reads transactions from CSV and writes assessments to CSV.
 *
 * <p>A transaction row has the columns of {@link #TRANSACTION_HEADER}: the timestamp is ISO 8601
 * ({@code 2026-10-01T09:30:00Z}), the country may be empty, and the direction is {@code in} or
 * {@code out}. Fields holding commas are quoted with double quotes, as in {@code "DOE, John"}.
 */
public final class RiskCsv {

    /** The header of a transactions file. */
    public static final String TRANSACTION_HEADER =
            "id,subject_id,counterparty,counterparty_country,amount,currency,timestamp,direction";

    /** The header of an assessments file. */
    public static final String ASSESSMENT_HEADER =
            "subject_id,score,level,transactions,rules,details";

    private static final int COLUMNS = 8;

    private RiskCsv() {}

    /**
     * Reads every transaction row, skipping the header and blank lines.
     *
     * @param reader the CSV
     * @return the transactions in file order
     * @throws IOException if the reader fails
     * @throws IllegalArgumentException if a row is malformed, naming its line number
     */
    public static List<Transaction> readTransactions(Reader reader) throws IOException {
        List<Transaction> transactions = new ArrayList<>();
        BufferedReader lines = new BufferedReader(reader);
        String line;
        int number = 0;
        while ((line = lines.readLine()) != null) {
            number++;
            if (line.isBlank() || isHeader(line)) {
                continue;
            }
            try {
                transactions.add(parseTransaction(line));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Line " + number + ": " + e.getMessage(), e);
            }
        }
        return transactions;
    }

    /**
     * Tells whether a line is the transactions header.
     *
     * @param line a line of the file
     * @return {@code true} for the header
     */
    public static boolean isHeader(String line) {
        return line.trim().toLowerCase(Locale.ROOT).startsWith("id,subject_id");
    }

    /**
     * Parses one transaction row.
     *
     * @param line the row
     * @return the transaction
     * @throws IllegalArgumentException if the row does not have the expected columns and values
     */
    public static Transaction parseTransaction(String line) {
        List<String> fields = split(line);
        if (fields.size() != COLUMNS) {
            throw new IllegalArgumentException(
                    "expected " + COLUMNS + " columns, found " + fields.size());
        }
        String country = fields.get(3).trim();
        BigDecimal amount;
        try {
            amount = new BigDecimal(fields.get(4).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not an amount: " + fields.get(4), e);
        }
        Instant timestamp;
        try {
            timestamp = Instant.parse(fields.get(6).trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("not an ISO 8601 timestamp: " + fields.get(6), e);
        }
        return new Transaction(
                fields.get(0).trim(),
                fields.get(1).trim(),
                fields.get(2).trim(),
                country.isEmpty() ? Optional.empty() : Optional.of(country),
                amount,
                fields.get(5).trim(),
                timestamp,
                Direction.fromString(fields.get(7)));
    }

    /**
     * Formats an assessment as one CSV row under {@link #ASSESSMENT_HEADER}.
     *
     * @param assessment the assessment
     * @return the row
     */
    public static String formatAssessment(RiskAssessment assessment) {
        String rules =
                assessment.signals().stream()
                        .map(RiskSignal::rule)
                        .collect(Collectors.joining(";"));
        String details =
                assessment.signals().stream()
                        .map(s -> s.rule() + ": " + s.detail())
                        .collect(Collectors.joining("; "));
        return String.join(
                ",",
                quote(assessment.subjectId()),
                Integer.toString(assessment.score()),
                assessment.level().name(),
                Integer.toString(assessment.transactionCount()),
                quote(rules),
                quote(details));
    }

    static List<String> split(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(c);
            }
        }
        fields.add(field.toString());
        return fields;
    }

    private static String quote(String value) {
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
