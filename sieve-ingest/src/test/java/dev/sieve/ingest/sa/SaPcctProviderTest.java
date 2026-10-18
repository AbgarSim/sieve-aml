package dev.sieve.ingest.sa;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.Gender;
import dev.sieve.core.model.IdentifierType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.SanctionedEntity;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class SaPcctProviderTest {

    @Test
    void shouldReadAllThreeSheets() throws Exception {
        List<SanctionedEntity> entities = parse();

        assertThat(entities)
                .extracting(SanctionedEntity::entityType)
                .containsExactly(
                        EntityType.VESSEL,
                        EntityType.ORGANIZATION,
                        EntityType.INDIVIDUAL,
                        EntityType.INDIVIDUAL);
        assertThat(entities)
                .allSatisfy(
                        e -> {
                            assertThat(e.listSource()).isEqualTo(ListSource.SA_PCCT);
                            assertThat(e.id()).startsWith("sa-pcct-");
                        });
    }

    @Test
    void shouldReadVessels() throws Exception {
        SanctionedEntity vessel = parse().get(0);

        assertThat(vessel.primaryName().fullName()).isEqualTo("Yasmine");
        assertThat(vessel.identifiers())
                .singleElement()
                .satisfies(
                        i -> {
                            assertThat(i.type()).isEqualTo(IdentifierType.IMO_NUMBER);
                            assertThat(i.value()).isEqualTo("9105085");
                        });
        assertThat(vessel.vessel().flag()).isEqualTo("Iranian");
        assertThat(vessel.programs().get(0).code())
                .isEqualTo("Islamic Revolutionary Guards Corps (IRGC)");
        assertThat(vessel.listedDate()).isEqualTo(Instant.parse("2022-01-26T00:00:00Z"));
    }

    @Test
    void shouldReadEntities() throws Exception {
        SanctionedEntity entity = parse().get(1);

        assertThat(entity.primaryName().fullName()).isEqualTo("JOOD SARL");
        assertThat(entity.aliases()).extracting(NameInfo::fullName).containsExactly("JUD");
        assertThat(entity.identifiers())
                .extracting(i -> i.value())
                .containsExactly("2078233", "6007003");
        assertThat(entity.addresses())
                .extracting(a -> a.fullAddress())
                .containsExactly("Baabda, Lebanon");
        assertThat(entity.remarks())
                .contains(
                        "Owner: MANSOUR, ADEL (designation NO 201)", "Established Date: 2025/6/3");
    }

    @Test
    void shouldReadPeople() throws Exception {
        List<SanctionedEntity> entities = parse();
        SanctionedEntity harb = entities.get(2);

        assertThat(harb.primaryName().familyName()).isEqualTo("HARB");
        assertThat(harb.primaryName().givenName()).isEqualTo("KHALIL YUSIF");
        assertThat(harb.aliases())
                .extracting(NameInfo::fullName)
                .containsExactly("AHMAD, Sayyid", "HARB, Hajj Ya'taqad Khalil", "MUSTAFA, Abu");
        assertThat(harb.gender()).isEqualTo(Gender.MALE);
        assertThat(harb.datesOfBirth())
                .containsExactly(LocalDate.of(1958, 10, 9), LocalDate.of(1961, 1, 1));
        assertThat(harb.placesOfBirth()).containsExactly("Kfartebnit, Lebanon");
        assertThat(harb.nationalities()).containsExactly("Lebanon", "Iraq");
        assertThat(harb.identifiers())
                .singleElement()
                .satisfies(
                        i -> {
                            assertThat(i.type()).isEqualTo(IdentifierType.PASSPORT);
                            assertThat(i.value()).isEqualTo("RL1294089");
                            assertThat(i.issuingCountry()).isEqualTo("Lebanon");
                        });
    }

    @Test
    void shouldSkipEmptyRows() throws Exception {
        assertThat(parse()).hasSize(4);
    }

    @Test
    void shouldReadDates() {
        assertThat(SaPcctProvider.date("1989/9/30")).isEqualTo(LocalDate.of(1989, 9, 30));
        assertThat(SaPcctProvider.date("1945")).isEqualTo(LocalDate.of(1945, 1, 1));
        assertThat(SaPcctProvider.date("2022-01-26")).isEqualTo(LocalDate.of(2022, 1, 26));
        assertThat(SaPcctProvider.date("unknown")).isNull();
    }

    private static List<SanctionedEntity> parse() throws Exception {
        return new SaPcctProvider(URI.create("http://localhost"), HttpClient.newHttpClient())
                .parseResponse(workbook());
    }

    private static byte[] workbook() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            sheet(
                    wb,
                    "Vessels",
                    new String[] {
                        "No.",
                        "Vessel Name",
                        "Vessel Flag",
                        "Link of Terrorism Org.",
                        "IMO No.",
                        "Owner Name",
                        "Owner information",
                        "Additional info.",
                        "Designated Date"
                    },
                    new String[] {
                        "1",
                        "Yasmine",
                        "Iranian flag",
                        "Islamic Revolutionary Guards Corps (IRGC)",
                        "IMO 9105085",
                        "",
                        "",
                        "Additional name: EMMA",
                        "2022/01/26"
                    },
                    new String[] {"", "", "", "", "", "", "", "", ""});
            sheet(
                    wb,
                    "Entities ",
                    new String[] {
                        "No.",
                        "Entities Name",
                        "Aliases",
                        "Link of Terrorism Org.",
                        "Registration Number",
                        "Address",
                        "Owner Name",
                        "Owner information",
                        "Additional info.\n",
                        "Designated Date"
                    },
                    new String[] {
                        "109",
                        "JOOD SARL",
                        "JUD",
                        "Hizballah",
                        "2078233; 6007003",
                        "Baabda, Lebanon",
                        "MANSOUR, ADEL",
                        "designation NO 201",
                        "Established Date: 2025/6/3",
                        "2026/08/31"
                    });
            sheet(
                    wb,
                    "Individuals",
                    new String[] {
                        "No.",
                        "Full Name",
                        "Aliases",
                        "Gender\n",
                        "Link of Terrorism Org.",
                        "DOB",
                        "POB",
                        "COB",
                        "Nationality",
                        "Document Type",
                        "Document No.",
                        "Issuing Country",
                        "Address",
                        "Additional Information",
                        "Designated Date"
                    },
                    new String[] {
                        "1",
                        "HARB, KHALIL YUSIF",
                        "AHMAD, Sayyid ;HARB, Hajj Ya'taqad Khalil;MUSTAFA, Abu .",
                        "Male",
                        "Hizballah",
                        "1958/10/09;1961",
                        "Kfartebnit",
                        "Lebanon\t",
                        "Lebanon; Iraq",
                        "Passport",
                        "RL1294089",
                        "Lebanon",
                        "",
                        "",
                        "2015/05/27"
                    },
                    new String[] {
                        "2",
                        "MOHAMED NAJIB KARIM",
                        "",
                        "Male",
                        "Hizballah",
                        "1957",
                        "",
                        "",
                        "Lebanon",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "2015/11/26"
                    });
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static void sheet(XSSFWorkbook wb, String name, String[]... rows) {
        Sheet sheet = wb.createSheet(name);
        for (int r = 0; r < rows.length; r++) {
            Row row = sheet.createRow(r);
            for (int c = 0; c < rows[r].length; c++) {
                if (!rows[r][c].isEmpty()) {
                    row.createCell(c).setCellValue(rows[r][c]);
                }
            }
        }
    }
}
