package dev.sieve.server.config;

import dev.sieve.core.index.EntityIndex;
import dev.sieve.core.index.InMemoryEntityIndex;
import dev.sieve.core.match.MatchEngine;
import dev.sieve.ingest.IngestionOrchestrator;
import dev.sieve.ingest.ListProvider;
import dev.sieve.ingest.ofac.OfacSdnProvider;
import dev.sieve.match.CompositeMatchEngine;
import dev.sieve.match.ExactMatchEngine;
import dev.sieve.match.FuzzyMatchEngine;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Central Spring configuration for the Sieve server.
 *
 * <p>Wires up the entity index, list providers, match engines, and ingestion orchestrator based on
 * the {@link SieveProperties} configuration.
 */
@Configuration
@EnableConfigurationProperties(SieveProperties.class)
public class SieveConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SieveConfiguration.class);

    /**
     * Creates the entity index bean.
     *
     * @return the in-memory entity index
     */
    @Bean
    public EntityIndex entityIndex() {
        log.info("Initializing in-memory entity index");
        return new InMemoryEntityIndex();
    }

    /**
     * Creates and registers all enabled list providers.
     *
     * @param properties the Sieve configuration properties
     * @return the list of enabled providers
     */
    @Bean
    public List<ListProvider> listProviders(SieveProperties properties) {
        List<ListProvider> providers = new ArrayList<>();

        SieveProperties.ListProperties ofacConfig = properties.lists().get("ofac-sdn");
        if (ofacConfig != null && ofacConfig.enabled()) {
            URI uri =
                    ofacConfig.url() != null
                            ? URI.create(ofacConfig.url())
                            : URI.create(
                                    "https://sanctionslistservice.ofac.treas.gov/api/PublicationPreview/exports/SDN.XML");
            providers.add(new OfacSdnProvider(uri));
            log.info("Registered OFAC SDN provider [url={}]", uri);
        }

        // TODO: Register EU, UN, UK providers when implemented

        if (providers.isEmpty()) {
            log.warn("No list providers are enabled — the index will remain empty");
        }

        return providers;
    }

    /**
     * Creates the composite match engine combining exact and fuzzy matching.
     *
     * @return the composite match engine
     */
    @Bean
    public MatchEngine matchEngine() {
        return new CompositeMatchEngine(List.of(new ExactMatchEngine(), new FuzzyMatchEngine()));
    }

    /**
     * Creates the ingestion orchestrator from the registered providers.
     *
     * @param providers the list of enabled providers
     * @return the ingestion orchestrator
     */
    @Bean
    public IngestionOrchestrator ingestionOrchestrator(List<ListProvider> providers) {
        if (providers.isEmpty()) {
            log.warn("No providers registered — ingestion orchestrator will have no sources");
            return new IngestionOrchestrator(
                    List.of(new dev.sieve.ingest.eu.EuConsolidatedProvider()));
        }
        return new IngestionOrchestrator(providers);
    }
}
