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
 * Fetches the U.S. Bureau of Industry and Security Military End-User List (EAR Supplement No. 7 to
 * Part 744).
 *
 * <p>BIS does not publish the Military End-User List as a machine-readable file of its own, so this
 * provider downloads the Consolidated Screening List and keeps the entries whose source is the
 * Military End-User List.
 *
 * @see <a href="https://www.bis.gov/regulations/ear/744#supplement-7-744">BIS Military End-User
 *     List</a>
 */
public final class BisMilitaryEndUserProvider extends AbstractListProvider {

    private static final CslParser PARSER =
            new CslParser(
                    ListSource.US_BIS_MEU,
                    "bis-meu-",
                    source -> source != null && source.contains("(MEU)"),
                    EntityType.ENTITY,
                    RiskTopic.EXPORT_CONTROL);

    /** Creates a provider that reads the default Consolidated Screening List download. */
    public BisMilitaryEndUserProvider() {
        this(URI.create(UsTradeCslProvider.DEFAULT_URL));
    }

    /**
     * Creates a provider with a custom source URI.
     *
     * @param sourceUri the URI to fetch the Consolidated Screening List JSON from
     */
    public BisMilitaryEndUserProvider(URI sourceUri) {
        super(ListSource.US_BIS_MEU, sourceUri, "application/json");
    }

    /**
     * Creates a provider with a custom source URI and HTTP client (for testing).
     *
     * @param sourceUri the URI to fetch the Consolidated Screening List JSON from
     * @param httpClient the HTTP client to use for requests
     */
    public BisMilitaryEndUserProvider(URI sourceUri, HttpClient httpClient) {
        super(
                ListSource.US_BIS_MEU,
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
