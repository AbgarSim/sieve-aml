package dev.sieve.ingest.pep;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Reads the Publications Office's XHTML rendering of an Official Journal document into the flat
 * sequence of blocks a document is made of: group headings, paragraphs, and table rows. The Journal
 * renders every numbered or bulleted item as a two-cell table row, and nests tables for sub-items,
 * so most of a list is rows.
 */
final class OjBlocks {

    private OjBlocks() {}

    /** A block of the document, in document order. */
    sealed interface Block permits Heading, Paragraph, Row, Lead, Tail {}

    /** A group heading ({@code oj-ti-grseq-1}). */
    record Heading(String text) implements Block {}

    /** A paragraph outside any table, with its CSS class. */
    record Paragraph(String cssClass, String text) implements Block {}

    /** A table cell: its paragraphs, in order. */
    record Cell(List<String> lines) {
        Cell {
            lines = List.copyOf(lines);
        }

        /** The cell's text, paragraphs joined with a space. */
        String text() {
            return String.join(" ", lines);
        }
    }

    /**
     * A table row whose cells hold text only, at nesting {@code depth} (1 for a table at the top
     * level).
     */
    record Row(int depth, List<Cell> cells) implements Block {
        Row {
            cells = List.copyOf(cells);
        }
    }

    /**
     * What a row says before a table nested inside one of its cells: the cells completed so far and
     * the text of the cell the table sits in, which may all be empty. It leads the nested rows,
     * which follow it at a greater depth; the row's remainder follows them as a {@link Tail}.
     */
    record Lead(int depth, List<Cell> cells) implements Block {
        Lead {
            cells = List.copyOf(cells);
        }
    }

    /**
     * What a row says after a table nested inside one of its cells: the rest of that cell's text,
     * then the cells after it. It closes the row a {@link Lead} opened.
     */
    record Tail(int depth, List<Cell> cells) implements Block {
        Tail {
            cells = List.copyOf(cells);
        }
    }

    private static final class Table {
        final List<Cell> cells = new ArrayList<>();
        List<String> lines; // the open cell's paragraphs, null outside a cell
        StringBuilder line; // the open paragraph inside a cell, null outside one
        int nestedCell = -1; // index of the first cell of the open row holding a nested table
        boolean leadOpen; // whether the open cell has already led a nested table
    }

    /**
     * Reads a document's blocks.
     *
     * @param xhtml the document
     * @return its blocks in order
     * @throws IOException when the document is not well-formed XHTML
     */
    static List<Block> read(InputStream xhtml) throws IOException {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        List<Block> blocks = new ArrayList<>();
        Deque<Table> tables = new ArrayDeque<>();
        StringBuilder paragraph = null;
        String paragraphClass = null;
        try {
            XMLStreamReader reader = factory.createXMLStreamReader(xhtml);
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String tag = reader.getLocalName();
                    Table table = tables.peek();
                    switch (tag) {
                        case "table" -> {
                            if (table != null && table.lines != null) {
                                closeLine(table);
                                if (!table.leadOpen || !table.lines.isEmpty()) {
                                    List<Cell> cells = new ArrayList<>(table.cells);
                                    cells.add(new Cell(table.lines));
                                    blocks.add(new Lead(tables.size(), cells));
                                }
                                if (table.nestedCell < 0) {
                                    table.nestedCell = table.cells.size();
                                }
                                table.leadOpen = true;
                                table.lines = new ArrayList<>();
                            }
                            tables.push(new Table());
                        }
                        case "tr" -> {
                            if (table != null) {
                                table.cells.clear();
                                table.nestedCell = -1;
                            }
                        }
                        case "td", "th" -> {
                            if (table != null) {
                                table.lines = new ArrayList<>();
                                table.leadOpen = false;
                            }
                        }
                        case "p" -> {
                            if (table != null) {
                                if (table.lines != null) {
                                    closeLine(table);
                                    table.line = new StringBuilder();
                                }
                            } else {
                                paragraph = new StringBuilder();
                                paragraphClass = reader.getAttributeValue(null, "class");
                            }
                        }
                        case "br" -> {
                            if (table != null) {
                                closeLine(table);
                                if (table.lines != null) {
                                    table.line = new StringBuilder();
                                }
                            } else if (paragraph != null) {
                                paragraph.append(" / ");
                            }
                        }
                        default -> {}
                    }
                } else if (event == XMLStreamConstants.CHARACTERS
                        || event == XMLStreamConstants.CDATA) {
                    Table table = tables.peek();
                    if (table != null) {
                        if (table.lines != null) {
                            if (table.line == null) {
                                if (reader.getText().isBlank()) {
                                    continue;
                                }
                                table.line = new StringBuilder();
                            }
                            table.line.append(reader.getText());
                        }
                    } else if (paragraph != null) {
                        paragraph.append(reader.getText());
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String tag = reader.getLocalName();
                    Table table = tables.peek();
                    switch (tag) {
                        case "table" -> tables.pop();
                        case "td", "th" -> {
                            if (table != null && table.lines != null) {
                                closeLine(table);
                                table.cells.add(new Cell(table.lines));
                                table.lines = null;
                            }
                        }
                        case "tr" -> {
                            if (table != null && table.nestedCell < 0) {
                                blocks.add(new Row(tables.size(), table.cells));
                            } else if (table != null) {
                                blocks.add(
                                        new Tail(
                                                tables.size(),
                                                table.cells.subList(
                                                        table.nestedCell, table.cells.size())));
                            }
                            if (table != null) {
                                table.cells.clear();
                                table.nestedCell = -1;
                            }
                        }
                        case "p" -> {
                            if (table != null) {
                                closeLine(table);
                            } else if (paragraph != null) {
                                String text = normalize(paragraph.toString());
                                String css = paragraphClass == null ? "" : paragraphClass;
                                if (css.contains("oj-ti-grseq")) {
                                    blocks.add(new Heading(text));
                                } else if (!text.isEmpty()) {
                                    blocks.add(new Paragraph(css, text));
                                }
                                paragraph = null;
                                paragraphClass = null;
                            }
                        }
                        default -> {}
                    }
                }
            }
            reader.close();
        } catch (XMLStreamException e) {
            throw new IOException("Official Journal document is not well-formed XHTML", e);
        }
        return blocks;
    }

    private static void closeLine(Table table) {
        if (table.line != null) {
            String text = normalize(table.line.toString());
            if (!text.isEmpty() && table.lines != null) {
                table.lines.add(text);
            }
            table.line = null;
        }
    }

    static String normalize(String text) {
        return text.replace(' ', ' ').replaceAll("\\s+", " ").strip();
    }
}
