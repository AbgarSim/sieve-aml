package dev.sieve.ingest.ustrade;

import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.RiskTopic;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.ingest.AbstractListProvider;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

/**
 * Fetches and parses the U.S. Trade Consolidated Screening List (CSL) via api.trade.gov.
 *
 * <p>This single JSON API bundles ~12 US export-control and sanctions lists including SDN, Entity
 * List, DPL, MEU, UVL, AECA debarred, ISN, and more. Typically contains ~23,000+ entities.
 *
 * @see <a href="https://api.trade.gov/gateway/v2/consolidated_screening_list/search">US Trade CSL
 *     API</a>
 */
public final class UsTradeCslProvider extends AbstractListProvider {

    private static final CslParser PARSER =
            new CslParser(
                    ListSource.US_TRADE_CSL,
                    "csl-",
                    source -> true,
                    EntityType.INDIVIDUAL,
                    RiskTopic.SANCTION);

    static final String DEFAULT_URL =
            "https://data.trade.gov/downloadable_consolidated_screening_list/v1/consolidated.json";

    /** Creates a provider with the default US Trade CSL API URL. */
    public UsTradeCslProvider() {
        super(ListSource.US_TRADE_CSL, URI.create(DEFAULT_URL), "application/json");
    }

    /**
     * Creates a provider with a custom source URI.
     *
     * @param sourceUri the URI to fetch the CSL JSON from
     */
    public UsTradeCslProvider(URI sourceUri) {
        super(ListSource.US_TRADE_CSL, sourceUri, "application/json");
    }

    /**
     * Creates a provider with a custom source URI and HTTP client (for testing).
     *
     * @param sourceUri the URI to fetch the CSL JSON from
     * @param httpClient the HTTP client to use for requests
     */
    public UsTradeCslProvider(URI sourceUri, HttpClient httpClient) {
        super(
                ListSource.US_TRADE_CSL,
                sourceUri,
                "application/json",
                httpClient,
                Duration.ofSeconds(120));
    }

    @Override
    protected List<SanctionedEntity> parseResponse(byte[] responseBody)
            throws ListIngestionException {
        return PARSER.parse(responseBody);
    }
}
