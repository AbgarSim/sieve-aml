package dev.sieve.cli.snapshot;

import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.ingest.wikidata.WikidataLinks;
import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adds what Wikidata knows about the published records (a picture, a Wikipedia article, a website)
 * before the snapshot is written. When Wikidata cannot be reached, the snapshot goes out without
 * it.
 */
public final class WikidataStep {

    private static final Logger log = LoggerFactory.getLogger(WikidataStep.class);

    private WikidataStep() {}

    /**
     * Returns the fetched lists with Wikidata's pictures and links added to their published
     * records; PEP and RCA records, which are never published, are left as they are.
     *
     * @param fetched the fetched lists
     * @param links the Wikidata lookup
     * @return the lists, unchanged when the lookup fails
     */
    public static List<FetchedSource> addTo(List<FetchedSource> fetched, WikidataLinks links)
            throws InterruptedException {
        List<SanctionedEntity> published = new ArrayList<>();
        for (FetchedSource source : fetched) {
            source.entities().stream().filter(SnapshotWriter::isPublic).forEach(published::add);
        }
        List<SanctionedEntity> added;
        try {
            added = links.addTo(published);
        } catch (IOException e) {
            log.warn("Wikidata links skipped [error={}]", e.getMessage());
            return fetched;
        }
        Map<SanctionedEntity, SanctionedEntity> replaced = new IdentityHashMap<>();
        for (int i = 0; i < published.size(); i++) {
            replaced.put(published.get(i), added.get(i));
        }
        List<FetchedSource> result = new ArrayList<>(fetched.size());
        for (FetchedSource source : fetched) {
            result.add(
                    new FetchedSource(
                            source.source(),
                            source.status(),
                            source.entities().stream()
                                    .map(e -> replaced.getOrDefault(e, e))
                                    .toList(),
                            source.metadata(),
                            source.duration(),
                            source.error()));
        }
        return result;
    }
}
