package dev.sieve.ingest.un;

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
 * Stub provider for the UN Security Council Consolidated sanctions list.
 *
 * <p>TODO: Implement full fetch and parse logic for the UN Consolidated list.
 *
 * @see <a
 *     href="https://scsanctions.un.org/resources/xml/en/consolidated.xml">UN
 *     Consolidated XML</a>
 */
public final class UnConsolidatedProvider implements ListProvider {

    private static final Logger log = LoggerFactory.getLogger(UnConsolidatedProvider.class);

    // Source URL: https://scsanctions.un.org/resources/xml/en/consolidated.xml
    private static final URI SOURCE_URI =
            URI.create("https://scsanctions.un.org/resources/xml/en/consolidated.xml");

    private final ListMetadata currentMetadata =
            new ListMetadata(ListSource.UN_CONSOLIDATED, null, null, null, SOURCE_URI, 0);

    @Override
    public ListSource source() {
        return ListSource.UN_CONSOLIDATED;
    }

    @Override
    public ListMetadata metadata() {
        return currentMetadata;
    }

    @Override
    public List<SanctionedEntity> fetch() throws ListIngestionException {
        // TODO: Implement UN Consolidated list parsing
        log.warn("UN Consolidated provider is not yet implemented — returning empty list");
        return List.of();
    }

    @Override
    public boolean hasUpdates(ListMetadata previousMetadata) {
        return false;
    }
}
