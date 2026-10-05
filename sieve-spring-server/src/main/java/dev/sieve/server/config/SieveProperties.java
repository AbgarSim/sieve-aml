package dev.sieve.server.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
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
        @Valid @DefaultValue AdverseMediaProperties adverseMedia) {

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
     * Experimental adverse media: candidate news articles about a name, kept apart from list
     * screening. Off by default because it reads an external news index.
     *
     * @param enabled whether the adverse media endpoint is on
     * @param index {@code gkg} to keep a rolling window of GDELT's 15-minute news files and search
     *     it locally, or {@code doc-api} to ask GDELT's search API for each name (rate-limited to
     *     one request every five seconds and often refused from shared cloud addresses)
     * @param lookbackDays default search window when a request does not give one
     * @param maxArticles default number of articles when a request does not give one
     * @param backfillHours with {@code gkg}, how many hours of news files to read at startup
     * @param retentionHours with {@code gkg}, how long articles are kept
     * @param includeTranslated with {@code gkg}, whether to read GDELT's machine-translated
     *     non-English files too (about 12 MB per 15 minutes, on top of 5 MB in English)
     * @param nameThreshold with {@code gkg}, the least name similarity taken as the same name
     */
    public record AdverseMediaProperties(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("gkg") String index,
            @DefaultValue("90") @Min(1) @Max(90) int lookbackDays,
            @DefaultValue("25") @Min(1) @Max(250) int maxArticles,
            @DefaultValue("6") @Min(1) @Max(168) int backfillHours,
            @DefaultValue("72") @Min(1) @Max(720) int retentionHours,
            @DefaultValue("true") boolean includeTranslated,
            @DefaultValue("0.92") @DecimalMin("0.8") @DecimalMax("1.0") double nameThreshold) {}
}
