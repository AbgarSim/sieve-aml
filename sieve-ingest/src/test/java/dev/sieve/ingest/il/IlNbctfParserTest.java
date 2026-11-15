package dev.sieve.ingest.il;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.Identifier;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.SanctionedEntity;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class IlNbctfParserTest {

    /** The leading columns of the organisations export, as served in November 2026. */
    private static final String[] ORG_HEADER = {
        "Name he",
        "Name ar",
        "Name en",
        "Is Designated",
        "Temporary Designation Date",
        "Permanent Designation Date",
        "Status",
        "Cancellation Date",
        "Local Designator",
        "Designation Number",
        "Organization Name",
        "AlternativeNames he",
        "AlternativeNames ar",
        "AlternativeNames en",
        "Corporation ID",
        "Formation Location",
        "Justifications",
        "Linked Organizations"
    };

    private static final String[] OPERATIVE_HEADER = {
        "Name he",
        "Name ar",
        "Name en",
        "Is Designated",
        "Temporary Designation Date",
        "Permanent Designation Date",
        "Status",
        "Cancellation Date",
        "Local Designator",
        "Full Name",
        "Dates of Birth",
        "Identification Documents",
        "Justifications",
        "Related Organizations"
    };

    @Test
    void shouldParseOrganisationsInForce() throws Exception {
        byte[] xlsx =
                workbook(
                        ORG_HEADER,
                        new String[] {
                            "חברת סמיר חרזאללה",
                            "شركة سمير حرزالله",
                            "SAMIR HERZALLAH AND BROTHERS FOR MONEY EXCHANGE",
                            "True",
                            "2024-04-15",
                            "",
                            "Active",
                            "",
                            "Minister of Defense - Mr. Yoav  Gallant",
                            "408",
                            "SAMIR HERZALLAH AND BROTHERS FOR MONEY EXCHANGE",
                            "",
                            "",
                            "SAMEER HERZALLAH AND HIS BROTHERS\n-------------\nSAMEER HERZALLAH",
                            "563478999",
                            "Khan Younis, Gaza",
                            "Money exchange used by Hamas",
                            "Name: HAMAS\n"
                        },
                        new String[] {
                            "כך",
                            "",
                            "KACH",
                            "True",
                            "",
                            "1994-03-13",
                            "Cancelled",
                            "2013-01-21",
                            "",
                            "12",
                            "KACH",
                            "",
                            "",
                            "",
                            "",
                            "",
                            "",
                            ""
                        });

        List<SanctionedEntity> entities = IlNbctfParser.organisations(xlsx);

        assertThat(entities).hasSize(1);
        SanctionedEntity org = entities.get(0);
        assertThat(org.id()).isEqualTo("il-org-408");
        assertThat(org.entityType()).isEqualTo(EntityType.ENTITY);
        assertThat(org.primaryName().fullName())
                .isEqualTo("SAMIR HERZALLAH AND BROTHERS FOR MONEY EXCHANGE");
        assertThat(org.aliases())
                .extracting(NameInfo::fullName)
                .containsExactly(
                        "חברת סמיר חרזאללה",
                        "شركة سمير حرزالله",
                        "SAMEER HERZALLAH AND HIS BROTHERS",
                        "SAMEER HERZALLAH");
        assertThat(org.identifiers()).extracting(Identifier::value).containsExactly("563478999");
        assertThat(org.listedDate()).isEqualTo(Instant.parse("2024-04-15T00:00:00Z"));
        assertThat(org.remarks())
                .contains("Designation number 408")
                .contains("Formation Location: Khan Younis, Gaza")
                .contains("Linked Organizations: HAMAS")
                .contains("Money exchange used by Hamas");
    }

    @Test
    void shouldParseOperativesWithDatesOfBirthAndIdNumbers() throws Exception {
        String[] person = {
            "השאם יונס יחיא קפישה",
            "هشام يونس يحيى قفيشة",
            "Hisham Younis Yahia Qafishe",
            "True",
            "2022-08-17",
            "",
            "Active",
            "",
            "Minister of Defense - Mr.Benjamin Gantz",
            "Hisham Younis Yahia Qafishe",
            "1956-09-01",
            "Document Type: Unknown\nIdentification Number: Passport H161563 (Jordan)",
            "הוכרז",
            ""
        };
        String[] unknownDocument = person.clone();
        unknownDocument[2] = "MOHAMMED TUFAIL";
        unknownDocument[9] = "MOHAMMED TUFAIL";
        unknownDocument[10] = "9999-09-09";
        unknownDocument[11] = "Document Type: Unknown\nIdentification Number: Unknown";

        List<SanctionedEntity> entities =
                IlNbctfParser.operatives(workbook(OPERATIVE_HEADER, person, unknownDocument));

        assertThat(entities).hasSize(2);
        SanctionedEntity qafishe = entities.get(0);
        assertThat(qafishe.id()).startsWith("il-ind-").hasSize("il-ind-".length() + 12);
        assertThat(qafishe.entityType()).isEqualTo(EntityType.INDIVIDUAL);
        assertThat(qafishe.datesOfBirth()).containsExactly(LocalDate.of(1956, 9, 1));
        assertThat(qafishe.identifiers())
                .extracting(Identifier::value)
                .containsExactly("Passport H161563 (Jordan)");
        assertThat(qafishe.aliases()).hasSize(2);
        // a Hebrew justification is left out of the English remarks
        assertThat(qafishe.remarks())
                .isEqualTo("Designated by Minister of Defense - Mr.Benjamin Gantz");

        SanctionedEntity tufail = entities.get(1);
        assertThat(tufail.datesOfBirth()).isEmpty();
        assertThat(tufail.identifiers()).isEmpty();
        // the same row read again gets the same id
        assertThat(IlNbctfParser.operatives(workbook(OPERATIVE_HEADER, person)).get(0).id())
                .isEqualTo(qafishe.id());
    }

    @Test
    void shouldFailWhenTheExportLayoutChanged() throws Exception {
        byte[] xlsx = workbook(new String[] {"Something", "Else"}, new String[] {"a", "b"});

        assertThatThrownBy(() -> IlNbctfParser.organisations(xlsx))
                .isInstanceOf(ListIngestionException.class)
                .hasMessageContaining("layout");
    }

    private static byte[] workbook(String[] header, String[]... rows) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Data");
            write(sheet.createRow(0), header);
            for (int i = 0; i < rows.length; i++) {
                write(sheet.createRow(i + 1), rows[i]);
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static void write(Row row, String[] values) {
        for (int i = 0; i < values.length; i++) {
            row.createCell(i).setCellValue(values[i]);
        }
    }
}
