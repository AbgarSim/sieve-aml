package dev.sieve.server.config;

import dev.sieve.core.media.AdverseMediaSearch;
import dev.sieve.ingest.media.GdeltAdverseMediaSearch;
import dev.sieve.ingest.media.GdeltGkgFeed;
import dev.sieve.match.media.NewsMentionIndex;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

/**
 * Wires experimental adverse media when {@code sieve.adverse-media.enabled=true}.
 *
 * <p>Nothing here touches the entity index or the match engine: the search is only reachable
 * through the adverse media endpoint.
 */
@Configuration
@ConditionalOnProperty(name = "sieve.adverse-media.enabled", havingValue = "true")
public class AdverseMediaConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AdverseMediaConfiguration.class);

    /** GDELT publishes a news file every 15 minutes. */
    private static final long REFRESH_MINUTES = 15;

    /**
     * Creates the adverse media search the configuration asks for.
     *
     * @param properties the Sieve configuration properties
     * @return the search
     */
    @Bean
    public AdverseMediaSearch adverseMediaSearch(SieveProperties properties) {
        SieveProperties.AdverseMediaProperties settings = properties.adverseMedia();
        String index = settings.index().strip().toLowerCase(Locale.ROOT);
        switch (index) {
            case "doc-api" -> {
                log.info("Adverse media on, asking the GDELT search API per name");
                return new GdeltAdverseMediaSearch();
            }
            case "gkg" -> {
                log.info(
                        "Adverse media on, reading GDELT news files [backfillHours={},"
                                + " retentionHours={}, translated={}]",
                        settings.backfillHours(),
                        settings.retentionHours(),
                        settings.includeTranslated());
                return new NewsMentionIndex(
                        new GdeltGkgFeed(settings.includeTranslated()),
                        "GDELT GKG",
                        Duration.ofHours(settings.backfillHours()),
                        Duration.ofHours(settings.retentionHours()),
                        settings.nameThreshold(),
                        Clock.systemUTC());
            }
            default ->
                    throw new IllegalArgumentException(
                            "sieve.adverse-media.index must be gkg or doc-api, not " + index);
        }
    }

    /**
     * Creates the refresher that keeps a news-file index current.
     *
     * @param search the adverse media search
     * @return the refresher
     */
    @Bean
    public NewsFileRefresher newsFileRefresher(AdverseMediaSearch search) {
        return new NewsFileRefresher(search);
    }

    /**
     * Reads new GDELT news files every 15 minutes on a thread of its own, so a slow download never
     * delays list refreshes or startup. Does nothing for the per-name search API.
     */
    public static final class NewsFileRefresher implements DisposableBean {

        private final AdverseMediaSearch search;
        private final ScheduledExecutorService executor =
                Executors.newSingleThreadScheduledExecutor(
                        Thread.ofPlatform().name("adverse-media-refresh").daemon().factory());

        NewsFileRefresher(AdverseMediaSearch search) {
            this.search = search;
        }

        /** Starts refreshing once the application is up. */
        @EventListener(ApplicationReadyEvent.class)
        public void start() {
            if (search instanceof NewsMentionIndex index) {
                executor.scheduleWithFixedDelay(
                        () -> refresh(index), 0, REFRESH_MINUTES, TimeUnit.MINUTES);
            }
        }

        private static void refresh(NewsMentionIndex index) {
            try {
                index.refresh();
            } catch (RuntimeException e) {
                log.error("Adverse media refresh failed", e);
            }
        }

        @Override
        public void destroy() {
            executor.shutdownNow();
        }
    }
}
