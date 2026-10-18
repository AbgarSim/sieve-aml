package dev.sieve.ingest.nl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.SanctionedEntity;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class NlTerrorismProviderTest {

    private static final String ROWS =
            row(
                            cell(
                                            "Persons and entities whose assets have been frozen (updated 11 September 2026).")
                                    + "<table:table-cell table:number-columns-repeated=\"6\"/>")
                    + "<table:table-row table:number-rows-repeated=\"1\"><table:table-cell table:number-columns-repeated=\"7\"/></table:table-row>"
                    + row(
                            cell("Surname")
                                    + cell("First name(s)")
                                    + cell("Alias")
                                    + cell("Date of Birth(DD-MM-JJJJ)")
                                    + cell("Place of Birth")
                                    + cell("Date of ministerial decision (DD/MM/JJJJ)")
                                    + cell("Link official notification"))
                    + row(
                            cell("Abdellaoui")
                                    + cell("Kawthar")
                                    + "<table:table-cell/>"
                                    + date("1998-10-10")
                                    + cell("Lichtenvoorde")
                                    + date("2016-12-07")
                                    + cell("Stcrt 68135"))
                    + row(
                            cell("Akhnikh")
                                    + cell("Ismail")
                                    + cell("Abu Ibrahim")
                                    + cell("22-10-1982")
                                    + cell("Amsterdam")
                                    + cell("11/09/2014")
                                    + cell("Stcrt 2014, 26134"));

    @Test
    void shouldReadPeopleBelowTheHeader() throws Exception {
        List<SanctionedEntity> entities = parse(ods(ROWS));

        assertThat(entities).hasSize(2);
        SanctionedEntity first = entities.get(0);
        assertThat(first.listSource()).isEqualTo(ListSource.NL_TERRORISM);
        assertThat(first.entityType()).isEqualTo(EntityType.INDIVIDUAL);
        assertThat(first.id()).startsWith("nl-");
        assertThat(first.primaryName().fullName()).isEqualTo("Kawthar Abdellaoui");
        assertThat(first.primaryName().familyName()).isEqualTo("Abdellaoui");
        assertThat(first.datesOfBirth()).containsExactly(LocalDate.of(1998, 10, 10));
        assertThat(first.placesOfBirth()).containsExactly("Lichtenvoorde");
        assertThat(first.listedDate()).isEqualTo(Instant.parse("2016-12-07T00:00:00Z"));
        assertThat(first.remarks()).isEqualTo("Official notification: Stcrt 68135");
    }

    @Test
    void shouldReadTextDatesAndAliases() throws Exception {
        SanctionedEntity second = parse(ods(ROWS)).get(1);

        assertThat(second.datesOfBirth()).containsExactly(LocalDate.of(1982, 10, 22));
        assertThat(second.listedDate()).isEqualTo(Instant.parse("2014-09-11T00:00:00Z"));
        assertThat(second.aliases()).extracting(a -> a.fullName()).containsExactly("Abu Ibrahim");
    }

    @Test
    void shouldGiveTheSameIdsOnEveryRead() throws Exception {
        assertThat(parse(ods(ROWS)).get(0).id()).isEqualTo(parse(ods(ROWS)).get(0).id());
    }

    @Test
    void shouldFailWithoutAHeaderRow() {
        assertThatThrownBy(() -> parse(ods(row(cell("Nothing here")))))
                .isInstanceOf(ListIngestionException.class);
    }

    private static List<SanctionedEntity> parse(byte[] ods) throws Exception {
        return new NlTerrorismProvider(URI.create("http://localhost"), HttpClient.newHttpClient())
                .parseResponse(ods);
    }

    private static String row(String cells) {
        return "<table:table-row>" + cells + "</table:table-row>";
    }

    private static String cell(String text) {
        return "<table:table-cell office:value-type=\"string\"><text:p>"
                + text
                + "</text:p></table:table-cell>";
    }

    private static String date(String iso) {
        return "<table:table-cell office:value-type=\"date\" office:date-value=\""
                + iso
                + "T00:00:00\"><text:p>whatever</text:p></table:table-cell>";
    }

    private static byte[] ods(String rows) throws Exception {
        String content =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<office:document-content xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\""
                        + " xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\""
                        + " xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\">"
                        + "<office:body><office:spreadsheet><table:table table:name=\"Sheet1\">"
                        + rows
                        + "</table:table><table:table table:name=\"Sheet2\">"
                        + row(cell("Surname"))
                        + row(cell("Other") + cell("Sheet"))
                        + "</table:table></office:spreadsheet></office:body></office:document-content>";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("mimetype"));
            zip.write(
                    "application/vnd.oasis.opendocument.spreadsheet"
                            .getBytes(StandardCharsets.UTF_8));
            zip.putNextEntry(new ZipEntry("content.xml"));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }
}
