package dev.sieve.ingest.uk;

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
 * Stub provider for the UK HM Treasury sanctions list.
 *
 * <p>TODO: Implement full fetch and parse logic for the UK HMT list.
 *
 * @see <a
 *     href="https://ofsistorage.blob.core.windows.net/publishlive/2022format/ConList.xml">UK HMT
 *     Consolidated XML</a>
 */
public final class UkHmtProvider implements ListProvider {

    private static final Logger log = LoggerFactory.getLogger(UkHmtProvider.class);

    // Source URL: https://ofsistorage.blob.core.windows.net/publishlive/2022format/ConList.xml
    private static final URI SOURCE_URI =
            URI.create(
                    "https://ofsistorage.blob.core.windows.net/publishlive/2022format/ConList.xml");

    private final ListMetadata currentMetadata =
            new ListMetadata(ListSource.UK_HMT, null, null, null, SOURCE_URI, 0);

    @Override
    public ListSource source() {
        return ListSource.UK_HMT;
    }

    @Override
    public ListMetadata metadata() {
        return currentMetadata;
    }

    @Override
    public List<SanctionedEntity> fetch() throws ListIngestionException {
        // TODO: Implement UK HMT list parsing
        log.warn("UK HMT provider is not yet implemented — returning empty list");
        return List.of();
    }

    @Override
    public boolean hasUpdates(ListMetadata previousMetadata) {
        return false;
    }
}
