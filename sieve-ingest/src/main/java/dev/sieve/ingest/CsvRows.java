package dev.sieve.ingest;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads comma-separated (or otherwise delimited) text into rows of fields, honouring quotes and
 * doubled quotes.
 */
public final class CsvRows {

    private CsvRows() {}

    /**
     * Splits CSV text into rows. A quoted field may hold commas and line breaks; a leading byte
     * order mark is dropped, and blank lines are skipped.
     *
     * @param text the whole file
     * @return the rows, header included, each as its list of fields
     */
    public static List<List<String>> parse(String text) {
        return parse(text, ',');
    }

    /**
     * Splits delimited text into rows, as {@link #parse(String)} does, with another delimiter such
     * as a semicolon.
     *
     * @param text the whole file
     * @param delimiter the character between fields
     * @return the rows, header included, each as its list of fields
     */
    public static List<List<String>> parse(String text, char delimiter) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        int start = !text.isEmpty() && text.charAt(0) == '﻿' ? 1 : 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == delimiter) {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                endRow(rows, row, field);
                row = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        endRow(rows, row, field);
        return rows;
    }

    private static void endRow(List<List<String>> rows, List<String> row, StringBuilder field) {
        row.add(field.toString());
        field.setLength(0);
        if (row.size() > 1 || !row.get(0).isBlank()) {
            rows.add(row);
        }
    }

    /**
     * Returns a field stripped of surrounding space, or {@code null} when it is missing or blank.
     *
     * @param row the row
     * @param index the field's position
     * @return the field's text, or {@code null}
     */
    public static String field(List<String> row, int index) {
        if (index < 0 || index >= row.size()) {
            return null;
        }
        String value = row.get(index).strip();
        return value.isEmpty() ? null : value;
    }
}
