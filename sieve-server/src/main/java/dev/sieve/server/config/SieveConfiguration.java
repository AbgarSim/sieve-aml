package dev.sieve.server.config;

import dev.sieve.address.AddressNormalizer;
import dev.sieve.core.index.EntityIndex;
import dev.sieve.core.index.InMemoryEntityIndex;
import dev.sieve.core.match.MatchEngine;
import dev.sieve.ingest.IngestionOrchestrator;
import dev.sieve.ingest.ListProvider;
import dev.sieve.ingest.eu.EuConsolidatedProvider;
import dev.sieve.ingest.ofac.OfacSdnProvider;
import dev.sieve.ingest.uk.UkHmtProvider;
import dev.sieve.ingest.un.UnConsolidatedProvider;
import dev.sieve.match.CompositeMatchEngine;
import dev.sieve.match.ExactMatchEngine;
import dev.sieve.match.FuzzyMatchEngine;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
     * Creates the in-memory entity index bean.
     *
     * <p>Only active when {@code sieve.index.type=in-memory} (or not set). When {@code
     * sieve.index.type=postgres}, the bean is provided by {@link PostgresConfiguration}.
     *
     * @return the in-memory entity index
     */
    @Bean
    @ConditionalOnProperty(name = "sieve.index.type", havingValue = "in-memory", matchIfMissing = true)
    public EntityIndex entityIndex() {
        log.info("Initializing in-memory entity index");
        return new InMemoryEntityIndex();
    }

    /**
     * Creates the libpostal-backed address normalizer.
     *
     * <p>Only active when {@code sieve.address.libpostal-enabled=true}. Uses the Senzing libpostal
     * data model v1.2.0 for improved parsing accuracy when available.
     *
     * @param dataDir the libpostal data directory path
     * @return the address normalizer
     */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(name = "sieve.address.libpostal-enabled", havingValue = "true")
    public AddressNormalizer addressNormalizer(
            @Value("${sieve.address.libpostal-data-dir:#{null}}") String dataDir) {
        AddressNormalizer normalizer = new AddressNormalizer(dataDir);
        normalizer.init();
        return normalizer;
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

        SieveProperties.ListProperties ukHmtConfig = properties.lists().get("uk-hmt");
        if (ukHmtConfig != null && ukHmtConfig.enabled()) {
            URI ukUri =
                    ukHmtConfig.url() != null
                            ? URI.create(ukHmtConfig.url())
                            : URI.create(
                                    "https://ofsistorage.blob.core.windows.net/publishlive/2022format/ConList.xml");
            providers.add(new UkHmtProvider(ukUri));
            log.info("Registered UK HMT provider [url={}]", ukUri);
        }

        SieveProperties.ListProperties euConfig = properties.lists().get("eu-consolidated");
        if (euConfig != null && euConfig.enabled()) {
            URI euUri =
                    euConfig.url() != null
                            ? URI.create(euConfig.url())
                            : URI.create(
                                    "https://webgate.ec.europa.eu/fsd/fsf/public/files/xmlFullSanctionsList_1_1/content?token=dG9rZW4tMjAxNw");
            providers.add(new EuConsolidatedProvider(euUri));
            log.info("Registered EU Consolidated provider [url={}]", euUri);
        }

        SieveProperties.ListProperties unConfig = properties.lists().get("un-consolidated");
        if (unConfig != null && unConfig.enabled()) {
            URI unUri =
                    unConfig.url() != null
                            ? URI.create(unConfig.url())
                            : URI.create(
                                    "https://scsanctions.un.org/resources/xml/en/consolidated.xml");
            providers.add(new UnConsolidatedProvider(unUri));
            log.info("Registered UN Consolidated provider [url={}]", unUri);
        }

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
