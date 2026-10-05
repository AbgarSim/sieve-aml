package dev.sieve.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sieve.core.model.ListSource;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class SourceCatalogTest {

    @Test
    void shouldDescribeEverySourceWhenCatalogIsBuilt() {
        assertThat(SourceCatalog.all())
                .extracting(SourceInfo::source)
                .containsExactly(ListSource.values());
    }

    @Test
    void shouldUseIsoOrBlocJurisdictionWhenDescribingSource() {
        assertThat(SourceCatalog.all())
                .allSatisfy(info -> assertThat(info.jurisdiction()).matches("[A-Z]{2}"));
        assertThat(SourceCatalog.info(ListSource.UK_HMT).jurisdiction()).isEqualTo("GB");
        assertThat(SourceCatalog.info(ListSource.UN_CONSOLIDATED).jurisdiction()).isEqualTo("UN");
    }

    @Test
    void shouldGiveEverySourceADescriptionWhenCatalogIsBuilt() {
        assertThat(SourceCatalog.all())
                .allSatisfy(
                        info ->
                                assertThat(info.description())
                                        .isNotBlank()
                                        .endsWith(".")
                                        .hasSizeLessThan(400));
    }

    @Test
    void shouldCreateOneProviderPerSourceWhenUsingDefaults() {
        assertThat(ProviderRegistry.defaults())
                .extracting(ListProvider::source)
                .containsExactlyElementsOf(Arrays.asList(ListSource.values()));
    }
}
