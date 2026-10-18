package dev.sieve.ingest.nl;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Reads the first sheet of an OpenDocument spreadsheet (.ods) into rows of text. An .ods file is a
 * zip whose {@code content.xml} holds the tables; a date cell gives its ISO date, any other cell
 * its paragraphs joined by line breaks.
 */
final class OdsSheet {

    private static final String TABLE_NS = "urn:oasis:names:tc:opendocument:xmlns:table:1.0";
    private static final String OFFICE_NS = "urn:oasis:names:tc:opendocument:xmlns:office:1.0";

    /** Cells repeated more often than this are trailing filler and are cut short. */
    private static final int MAX_REPEAT = 64;

    private OdsSheet() {}

    /**
     * Returns the rows of the first sheet, without the empty rows.
     *
     * @param ods the file
     * @return each row's cell values, empty strings for empty cells
     * @throws IOException if the file is not a readable spreadsheet
     */
    static List<List<String>> firstSheet(byte[] ods) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(ods))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (entry.getName().equals("content.xml")) {
                    return read(zip);
                }
            }
        }
        throw new IOException("No content.xml in the spreadsheet");
    }

    private static List<List<String>> read(InputStream content) throws IOException {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        List<List<String>> rows = new ArrayList<>();
        try {
            XMLStreamReader r = factory.createXMLStreamReader(content);
            List<String> row = null;
            StringBuilder cell = null;
            int repeat = 1;
            int depth = 0;
            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String name = r.getLocalName();
                    if (name.equals("table")) {
                        depth++;
                    } else if (depth == 1 && name.equals("table-row")) {
                        row = new ArrayList<>();
                    } else if (row != null
                            && (name.equals("table-cell") || name.equals("covered-table-cell"))) {
                        String repeated = r.getAttributeValue(TABLE_NS, "number-columns-repeated");
                        repeat =
                                repeated == null
                                        ? 1
                                        : Math.min(MAX_REPEAT, Integer.parseInt(repeated));
                        cell = new StringBuilder();
                        if ("date".equals(r.getAttributeValue(OFFICE_NS, "value-type"))) {
                            String date = r.getAttributeValue(OFFICE_NS, "date-value");
                            if (date != null) {
                                cell.append(date, 0, Math.min(10, date.length()));
                                repeat = -repeat;
                            }
                        }
                    } else if (cell != null
                            && repeat > 0
                            && name.equals("p")
                            && cell.length() > 0) {
                        cell.append('\n');
                    } else if (cell != null && repeat > 0 && name.equals("s")) {
                        cell.append(' ');
                    }
                } else if (event == XMLStreamConstants.CHARACTERS && cell != null && repeat > 0) {
                    cell.append(r.getText());
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String name = r.getLocalName();
                    if (name.equals("table")) {
                        if (--depth == 0) {
                            break;
                        }
                    } else if (cell != null
                            && (name.equals("table-cell") || name.equals("covered-table-cell"))) {
                        String value = cell.toString().strip();
                        for (int i = 0; i < Math.abs(repeat); i++) {
                            row.add(value);
                        }
                        cell = null;
                    } else if (row != null && name.equals("table-row")) {
                        if (row.stream().anyMatch(v -> !v.isEmpty())) {
                            rows.add(row);
                        }
                        row = null;
                    }
                }
            }
            r.close();
        } catch (XMLStreamException | NumberFormatException e) {
            throw new IOException("Unreadable spreadsheet: " + e.getMessage(), e);
        }
        return rows;
    }
}
