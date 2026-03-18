package dev.sieve.ingest.eu;

import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.ingest.ListMetadata;
import dev.sieve.ingest.ListProvider;
import java.net.URI;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stub provider for the EU Consolidated sanctions list.
 *
 * <p>TODO: Implement full fetch and parse logic for the EU Consolidated list.
 *
 * @see <a
 *     href="https://webgate.ec.europa.eu/fsd/fsf/public/files/xmlFullSanctionsList_1_1/content?token=dG9rZW4tMjAxNw">EU
 *     Consolidated XML</a>
 */
public final class EuConsolidatedProvider implements ListProvider {

    private static final Logger log = LoggerFactory.getLogger(EuConsolidatedProvider.class);

    // Source URL: https://webgate.ec.europa.eu/fsd/fsf/public/files/xmlFullSanctionsList_1_1/content?token=dG9rZW4tMjAxNw
    private static final URI SOURCE_URI =
            URI.create(
                    "https://webgate.ec.europa.eu/fsd/fsf/public/files/xmlFullSanctionsList_1_1/content?token=dG9rZW4tMjAxNw");

    private final ListMetadata currentMetadata =
            new ListMetadata(ListSource.EU_CONSOLIDATED, null, null, null, SOURCE_URI, 0);

    @Override
    public ListSource source() {
        return ListSource.EU_CONSOLIDATED;
    }

    @Override
    public ListMetadata metadata() {
        return currentMetadata;
    }

    @Override
    public List<SanctionedEntity> fetch() throws ListIngestionException {
        // TODO: Implement EU Consolidated list parsing
        log.warn("EU Consolidated provider is not yet implemented — returning empty list");
        return List.of();
    }

    @Override
    public boolean hasUpdates(ListMetadata previousMetadata) {
        return false;
    }
}
