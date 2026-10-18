package dev.sieve.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CsvRowsTest {

    @Test
    void shouldHonourQuotesLineBreaksAndBom() {
        List<List<String>> rows =
                CsvRows.parse("﻿a,b\r\n\"x, y\",\"say \"\"hi\"\"\"\n\n\"two\nlines\",\n");

        assertThat(rows)
                .containsExactly(
                        List.of("a", "b"),
                        List.of("x, y", "say \"hi\""),
                        List.of("two\nlines", ""));
    }

    @Test
    void shouldReturnBlankFieldsAsNull() {
        List<String> row = List.of(" a ", " ", "");
        assertThat(CsvRows.field(row, 0)).isEqualTo("a");
        assertThat(CsvRows.field(row, 1)).isNull();
        assertThat(CsvRows.field(row, 5)).isNull();
    }
}
