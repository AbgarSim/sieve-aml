package dev.sieve.server.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for the Sieve sanctions screening platform.
 *
 * <p>Bound to the {@code sieve} prefix in {@code application.yml}.
 */
@ConfigurationProperties(prefix = "sieve")
@Validated
public record SieveProperties(
        @Valid @NotNull Map<String, ListProperties> lists,
        @Valid @NotNull ScreeningProperties screening,
        @Valid @NotNull IndexProperties index) {

    /**
     * Configuration for an individual sanctions list source.
     *
     * @param enabled whether this list is enabled for ingestion
     * @param url the URL to fetch the list from
     * @param refreshCron cron expression for scheduled refresh
     */
    public record ListProperties(boolean enabled, String url, String refreshCron) {}

    /**
     * Screening engine configuration.
     *
     * @param defaultThreshold default minimum match score (0.0–1.0)
     * @param maxResults maximum number of results to return per screening request
     */
    public record ScreeningProperties(
            @DecimalMin("0.0") @DecimalMax("1.0") double defaultThreshold,
            @Min(1) @Max(1000) int maxResults) {}

    /**
     * Index configuration.
     *
     * @param type the index implementation type (currently only "in-memory")
     */
    public record IndexProperties(@NotNull String type) {}
}
