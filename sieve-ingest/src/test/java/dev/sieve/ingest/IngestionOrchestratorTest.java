package dev.sieve.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.sieve.core.ListIngestionException;
import dev.sieve.core.index.EntityIndex;
import dev.sieve.core.index.InMemoryEntityIndex;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.NameStrength;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.ScriptType;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class IngestionOrchestratorTest {

    @Test
    void shouldIngestFromAllProviders() {
        ListProvider provider1 = mockProvider(ListSource.OFAC_SDN, 3);
        ListProvider provider2 = mockProvider(ListSource.EU_CONSOLIDATED, 2);

        IngestionOrchestrator orchestrator =
                new IngestionOrchestrator(List.of(provider1, provider2));
        EntityIndex index = new InMemoryEntityIndex();

        IngestionReport report = orchestrator.ingest(index);

        assertThat(report.totalEntitiesLoaded()).isEqualTo(5);
        assertThat(report.results()).hasSize(2);
        assertThat(report.results().get(ListSource.OFAC_SDN).status())
                .isEqualTo(ProviderResult.Status.SUCCESS);
        assertThat(report.results().get(ListSource.EU_CONSOLIDATED).status())
                .isEqualTo(ProviderResult.Status.SUCCESS);
        assertThat(index.size()).isEqualTo(5);
    }

    @Test
    void shouldIngestSelectiveSources() {
        ListProvider provider1 = mockProvider(ListSource.OFAC_SDN, 3);
        ListProvider provider2 = mockProvider(ListSource.EU_CONSOLIDATED, 2);

        IngestionOrchestrator orchestrator =
                new IngestionOrchestrator(List.of(provider1, provider2));
        EntityIndex index = new InMemoryEntityIndex();

        IngestionReport report = orchestrator.ingest(index, Set.of(ListSource.OFAC_SDN));

        assertThat(report.totalEntitiesLoaded()).isEqualTo(3);
        assertThat(report.results().get(ListSource.OFAC_SDN).status())
                .isEqualTo(ProviderResult.Status.SUCCESS);
        assertThat(report.results().get(ListSource.EU_CONSOLIDATED).status())
                .isEqualTo(ProviderResult.Status.SKIPPED);
    }

    @Test
    void shouldHandleProviderFailure() {
        ListProvider failingProvider = mock(ListProvider.class);
        when(failingProvider.source()).thenReturn(ListSource.OFAC_SDN);
        when(failingProvider.fetch())
                .thenThrow(new ListIngestionException("Network error", ListSource.OFAC_SDN));

        IngestionOrchestrator orchestrator = new IngestionOrchestrator(List.of(failingProvider));
        EntityIndex index = new InMemoryEntityIndex();

        IngestionReport report = orchestrator.ingest(index);

        assertThat(report.totalEntitiesLoaded()).isZero();
        assertThat(report.results().get(ListSource.OFAC_SDN).status())
                .isEqualTo(ProviderResult.Status.FAILED);
        assertThat(report.results().get(ListSource.OFAC_SDN).error()).isPresent();
    }

    @Test
    void shouldThrowForEmptyProviders() {
        assertThatThrownBy(() -> new IngestionOrchestrator(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("At least one");
    }

    @Test
    void shouldThrowForNullProviders() {
        assertThatThrownBy(() -> new IngestionOrchestrator(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldReportDuration() {
        ListProvider provider = mockProvider(ListSource.OFAC_SDN, 1);
        IngestionOrchestrator orchestrator = new IngestionOrchestrator(List.of(provider));
        EntityIndex index = new InMemoryEntityIndex();

        IngestionReport report = orchestrator.ingest(index);

        assertThat(report.totalDuration()).isNotNull();
        assertThat(report.totalDuration().toMillis()).isGreaterThanOrEqualTo(0);
    }

    private ListProvider mockProvider(ListSource source, int entityCount) {
        ListProvider provider = mock(ListProvider.class);
        when(provider.source()).thenReturn(source);

        List<SanctionedEntity> entities =
                java.util.stream.IntStream.rangeClosed(1, entityCount)
                        .mapToObj(
                                i -> {
                                    NameInfo name =
                                            new NameInfo(
                                                    "Entity " + source.name() + "-" + i,
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    NameType.PRIMARY,
                                                    NameStrength.STRONG,
                                                    ScriptType.LATIN);
                                    return new SanctionedEntity(
                                            source.name() + "-" + i,
                                            EntityType.INDIVIDUAL,
                                            source,
                                            name,
                                            null,
                                            null,
                                            null,
                                            null,
                                            null,
                                            null,
                                            null,
                                            null,
                                            null,
                                            null,
                                            Instant.now());
                                })
                        .toList();

        when(provider.fetch()).thenReturn(entities);
        when(provider.metadata())
                .thenReturn(
                        new ListMetadata(
                                source,
                                Instant.now(),
                                null,
                                null,
                                java.net.URI.create("https://test/" + source.name()),
                                entityCount));

        return provider;
    }
}
