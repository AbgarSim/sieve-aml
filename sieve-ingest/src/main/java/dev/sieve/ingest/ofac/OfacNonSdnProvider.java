package dev.sieve.ingest.ofac;

import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.ingest.AbstractListProvider;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

/**
 * Fetches and parses the OFAC Consolidated non-SDN XML list.
 *
 * <p>Covers SSI, NS-CMIC, NS-MBS, CAPTA, and PLC lists. Uses the same XML schema as the SDN list
 * and the same parser, {@link OfacXmlParser}, so every entry carries its aliases, addresses,
 * identifiers, the crypto wallets of its digital currency addresses and the relations its "Linked
 * To:" remarks state (see {@link OfacLinks}). Entity ids start with {@code ofac-nonsdn-}. Typically
 * contains ~500 entities.
 *
 * @see <a
 *     href="https://sanctionslistservice.ofac.treas.gov/api/PublicationPreview/exports/CONSOLIDATED.XML">
 *     OFAC Consolidated non-SDN XML</a>
 */
public final class OfacNonSdnProvider extends AbstractListProvider {

    private static final String DEFAULT_URL =
            "https://sanctionslistservice.ofac.treas.gov/api/PublicationPreview/exports/CONSOLIDATED.XML";
    private static final OfacXmlParser PARSER =
            new OfacXmlParser(ListSource.OFAC_NONSDN, "ofac-nonsdn-", "OFAC non-SDN");

    public OfacNonSdnProvider() {
        super(ListSource.OFAC_NONSDN, URI.create(DEFAULT_URL), "application/xml");
    }

    public OfacNonSdnProvider(URI sourceUri) {
        super(ListSource.OFAC_NONSDN, sourceUri, "application/xml");
    }

    public OfacNonSdnProvider(URI sourceUri, HttpClient httpClient) {
        super(
                ListSource.OFAC_NONSDN,
                sourceUri,
                "application/xml",
                httpClient,
                Duration.ofSeconds(120));
    }

    @Override
    protected List<SanctionedEntity> parseResponse(byte[] responseBody)
            throws ListIngestionException {
        return PARSER.parse(responseBody);
    }
}
