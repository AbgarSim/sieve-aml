package dev.sieve.ingest.il;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.ingest.ListMetadata;
import dev.sieve.ingest.ListProvider;
import dev.sieve.ingest.PlaywrightSupport;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fetches Israel's lists of terrorist organisations and terror operatives from the National Bureau
 * for Counter Terror Financing (NBCTF).
 *
 * <p>In October 2026 the bureau moved from {@code nbctf.mod.gov.il} to {@code matal.mod.gov.il},
 * whose sanctions page offers each list as an Excel export: a form posted to {@code
 * /sanctions/export} with an anti-forgery token from the page. The site sits behind Imperva, so the
 * provider opens the page in a headless browser and posts the form from there, once per list, and
 * {@link IlNbctfParser} reads the workbooks.
 *
 * @see <a href="https://matal.mod.gov.il/en/sanctions/">NBCTF sanctions</a>
 */
public final class IlWmdTerrorProvider implements ListProvider {

    private static final Logger log = LoggerFactory.getLogger(IlWmdTerrorProvider.class);

    static final String SANCTIONS_PAGE = "https://matal.mod.gov.il/en/sanctions/";

    /** Posts the page's own export form for one list and returns the file, base64-encoded. */
    private static final String EXPORT_SCRIPT =
            """
            async (type) => {
              const fd = new FormData(document.getElementById('exportOrganizationPopup-form'));
              fd.set('SelectedTypes', type);
              fd.set('FileType', 'xlsx');
              fd.delete('ApplyAdvancedSearchFilter');
              const r = await fetch('/sanctions/export', {method: 'POST', body: fd, credentials: 'same-origin'});
              if (r.status !== 200) { return 'status:' + r.status; }
              const bytes = new Uint8Array(await r.arrayBuffer());
              let s = '';
              for (let i = 0; i < bytes.length; i += 0x8000) {
                s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
              }
              return btoa(s);
            }
            """;

    private volatile ListMetadata currentMetadata;

    public IlWmdTerrorProvider() {
        this.currentMetadata =
                new ListMetadata(
                        ListSource.IL_WMD_TERROR, null, null, null, URI.create(SANCTIONS_PAGE), 0);
    }

    @Override
    public ListSource source() {
        return ListSource.IL_WMD_TERROR;
    }

    @Override
    public ListMetadata metadata() {
        return currentMetadata;
    }

    @Override
    public List<SanctionedEntity> fetch() throws ListIngestionException {
        PlaywrightSupport.requireAvailable(ListSource.IL_WMD_TERROR);
        log.info("Fetching IL WMD/Terror lists via Playwright [uri={}]", SANCTIONS_PAGE);
        Instant start = Instant.now();

        try (Playwright pw = Playwright.create();
                Browser browser =
                        pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))) {
            Page page = browser.newPage();
            page.navigate(SANCTIONS_PAGE, new Page.NavigateOptions().setTimeout(90_000));
            page.waitForSelector(
                    "#exportOrganizationPopup-form",
                    new Page.WaitForSelectorOptions()
                            .setState(
                                    com.microsoft.playwright.options.WaitForSelectorState.ATTACHED)
                            .setTimeout(90_000));

            byte[] organisations = export(page, "organization");
            byte[] operatives = export(page, "operative");
            log.info(
                    "IL WMD/Terror exports downloaded [organisationsBytes={}, operativesBytes={}]",
                    organisations.length,
                    operatives.length);

            List<SanctionedEntity> entities = new ArrayList<>();
            entities.addAll(IlNbctfParser.organisations(organisations));
            entities.addAll(IlNbctfParser.operatives(operatives));
            if (entities.isEmpty()) {
                throw new ListIngestionException(
                        "IL NBCTF exports hold no designation in force; the export may have changed",
                        ListSource.IL_WMD_TERROR);
            }

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(organisations);
            digest.update(operatives);
            currentMetadata =
                    new ListMetadata(
                            ListSource.IL_WMD_TERROR,
                            Instant.now(),
                            null,
                            HexFormat.of().formatHex(digest.digest()),
                            URI.create(SANCTIONS_PAGE),
                            entities.size());
            log.info(
                    "IL WMD/Terror ingestion complete [entities={}, duration={}ms]",
                    entities.size(),
                    Duration.between(start, Instant.now()).toMillis());
            return entities;
        } catch (ListIngestionException e) {
            throw e;
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 algorithm not available", e);
        } catch (Exception e) {
            throw new ListIngestionException(
                    "Error during IL WMD/Terror ingestion: " + e.getMessage(),
                    ListSource.IL_WMD_TERROR,
                    e);
        }
    }

    @Override
    public boolean hasUpdates(ListMetadata previousMetadata) {
        return true;
    }

    private static byte[] export(Page page, String type) throws ListIngestionException {
        Object result = page.evaluate(EXPORT_SCRIPT, type);
        String text = String.valueOf(result);
        if (text.startsWith("status:")) {
            throw new ListIngestionException(
                    "IL NBCTF export of " + type + " failed [" + text + "]",
                    ListSource.IL_WMD_TERROR);
        }
        byte[] data = Base64.getDecoder().decode(text);
        if (data.length < 500) {
            throw new ListIngestionException(
                    "IL NBCTF export of " + type + " too small (" + data.length + " bytes)",
                    ListSource.IL_WMD_TERROR);
        }
        return data;
    }
}
