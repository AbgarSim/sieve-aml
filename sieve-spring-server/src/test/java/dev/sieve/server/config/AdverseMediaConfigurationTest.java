package dev.sieve.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sieve.core.media.AdverseMediaSearch;
import dev.sieve.ingest.media.GdeltAdverseMediaSearch;
import dev.sieve.match.media.NewsMentionIndex;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class AdverseMediaConfigurationTest {

    @Configuration
    @EnableConfigurationProperties(SieveProperties.class)
    static class PropertiesOnly {}

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(PropertiesOnly.class, AdverseMediaConfiguration.class)
                    .withPropertyValues(
                            "sieve.screening.default-threshold=0.8",
                            "sieve.screening.max-results=50",
                            "sieve.lists.ofac-sdn.enabled=false");

    @Test
    void shouldBeOffWithDefaultsWhenNotConfigured() {
        runner.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(AdverseMediaSearch.class);
                    SieveProperties.AdverseMediaProperties settings =
                            context.getBean(SieveProperties.class).adverseMedia();
                    assertThat(settings.enabled()).isFalse();
                    assertThat(settings.index()).isEqualTo("gkg");
                    assertThat(settings.lookbackDays()).isEqualTo(90);
                    assertThat(settings.maxArticles()).isEqualTo(25);
                    assertThat(settings.backfillHours()).isEqualTo(6);
                    assertThat(settings.retentionHours()).isEqualTo(72);
                    assertThat(settings.includeTranslated()).isTrue();
                    assertThat(settings.nameThreshold()).isEqualTo(0.92);
                });
    }

    @Test
    void shouldUseNewsFileIndexWhenEnabled() {
        runner.withPropertyValues("sieve.adverse-media.enabled=true")
                .run(
                        context ->
                                assertThat(context.getBean(AdverseMediaSearch.class))
                                        .isInstanceOf(NewsMentionIndex.class));
    }

    @Test
    void shouldUseSearchApiWhenAskedFor() {
        runner.withPropertyValues(
                        "sieve.adverse-media.enabled=true", "sieve.adverse-media.index=doc-api")
                .run(
                        context ->
                                assertThat(context.getBean(AdverseMediaSearch.class))
                                        .isInstanceOf(GdeltAdverseMediaSearch.class));
    }

    @Test
    void shouldFailToStartWhenIndexIsUnknown() {
        runner.withPropertyValues(
                        "sieve.adverse-media.enabled=true", "sieve.adverse-media.index=bing")
                .run(context -> assertThat(context).hasFailed());
    }
}
