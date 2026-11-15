package dev.sieve.ingest.in;

import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.ListSource;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the document lists of the ministry's site: since its redesign in October 2026 every list
 * (the designated individuals, the unlawful associations, the "Banned Organisations" index) is a
 * table of rows, each with a title and a link to a PDF or to another page.
 *
 * <p>A row's title sits in a {@code documentRecordTitle} block and its link is the {@code viewBtn}
 * (PDF) or {@code viewInternalBtn} (page) anchor after it.
 */
final class MhaDocumentList {

    /** One row: its title, as text, and the absolute URI of its link. */
    record Row(String title, URI link) {}

    private static final Pattern TITLE =
            Pattern.compile("(?is)class=\"documentRecordTitle\"[^>]*>(.*?)</div>");
    private static final Pattern LINK =
            Pattern.compile("(?is)<a\\s[^>]*?href=\"([^\"]+)\"[^>]*class=\"view(?:Internal)?Btn\"");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");

    /** More pages than any list has; a list that seems longer is a layout change. */
    private static final int MAX_PAGES = 50;

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private MhaDocumentList() {}

    /**
     * Reads every row of a paged list, oldest entry first.
     *
     * @param client the HTTP client
     * @param list the list's address, without paging parameters
     * @param source the list being read, for errors
     * @return the rows of all pages, in list order
     * @throws ListIngestionException if a page fails or the list has no rows
     */
    static List<Row> all(HttpClient client, URI list, ListSource source)
            throws ListIngestionException, IOException, InterruptedException {
        List<Row> all = new ArrayList<>();
        for (int n = 0; n < MAX_PAGES; n++) {
            URI page = page(list, n);
            List<Row> rows =
                    rows(
                            new String(
                                    get(client, page, "text/html", source), StandardCharsets.UTF_8),
                            page);
            if (rows.isEmpty()) {
                break;
            }
            all.addAll(rows);
        }
        if (all.isEmpty()) {
            throw new ListIngestionException(
                    source.displayName()
                            + " list at "
                            + list
                            + " has no rows; the page layout may have changed",
                    source);
        }
        return all;
    }

    /**
     * Downloads one file, failing on any status but 200.
     *
     * @param client the HTTP client
     * @param uri what to download
     * @param accept the Accept header
     * @param source the list being read, for errors
     * @return the body
     * @throws ListIngestionException on a status other than 200
     */
    static byte[] get(HttpClient client, URI uri, String accept, ListSource source)
            throws ListIngestionException, IOException, InterruptedException {
        HttpRequest request =
                HttpRequest.newBuilder(uri)
                        .timeout(REQUEST_TIMEOUT)
                        .header("Accept", accept)
                        .header("User-Agent", "sieve-aml/1.0")
                        .GET()
                        .build();
        HttpResponse<byte[]> response =
                client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new ListIngestionException(
                    String.format(
                            "%s fetch failed [status=%d, uri=%s]",
                            source.displayName(), response.statusCode(), uri),
                    source);
        }
        return response.body();
    }

    /**
     * Reads the rows of one page of a list.
     *
     * @param html the page
     * @param page the page's URI, to resolve the links against
     * @return the rows in page order; a row without a link is left out
     */
    static List<Row> rows(String html, URI page) {
        record Title(int start, int end, String text) {}
        List<Title> titles = new ArrayList<>();
        Matcher title = TITLE.matcher(html);
        while (title.find()) {
            titles.add(new Title(title.start(), title.end(), text(title.group(1))));
        }
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < titles.size(); i++) {
            Title t = titles.get(i);
            int end = i + 1 < titles.size() ? titles.get(i + 1).start() : html.length();
            Matcher link = LINK.matcher(html).region(t.end(), end);
            if (link.find() && !t.text().isEmpty()) {
                rows.add(new Row(t.text(), page.resolve(encode(link.group(1)))));
            }
        }
        return rows;
    }

    /** The URI of page {@code n} (from 0) of a list, oldest entry first, twenty to a page. */
    static URI page(URI list, int n) {
        String base = list.toString().replaceAll("\\?.*$", "");
        return URI.create(base + "?sort_order=ASC&items_per_page=20&page=" + n);
    }

    static String text(String html) {
        String plain = TAG.matcher(html).replaceAll(" ");
        plain =
                plain.replace("&nbsp;", " ")
                        .replace("&amp;", "&")
                        .replace("&quot;", "\"")
                        .replace("&#039;", "'")
                        .replace("&#39;", "'")
                        .replace(' ', ' ');
        return plain.replaceAll("\\s+", " ").strip();
    }

    private static String encode(String href) {
        return href.replace("&amp;", "&").replace(" ", "%20");
    }
}
