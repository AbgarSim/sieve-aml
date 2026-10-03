package dev.sieve.core.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.NameStrength;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.ScriptType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemoryEntityIndexTest {

    private InMemoryEntityIndex index;

    @BeforeEach
    void setUp() {
        index = new InMemoryEntityIndex();
    }

    @Test
    void shouldStartEmpty() {
        assertThat(index.size()).isZero();
        assertThat(index.all()).isEmpty();
    }

    @Test
    void shouldAddSingleEntity() {
        SanctionedEntity entity = createEntity("1", EntityType.INDIVIDUAL, ListSource.OFAC_SDN);
        index.add(entity);

        assertThat(index.size()).isEqualTo(1);
        assertThat(index.findById("1")).isPresent();
    }

    @Test
    void shouldAddMultipleEntities() {
        List<SanctionedEntity> entities =
                List.of(
                        createEntity("1", EntityType.INDIVIDUAL, ListSource.OFAC_SDN),
                        createEntity("2", EntityType.ENTITY, ListSource.EU_CONSOLIDATED),
                        createEntity("3", EntityType.VESSEL, ListSource.OFAC_SDN));

        index.addAll(entities);

        assertThat(index.size()).isEqualTo(3);
    }

    @Test
    void shouldFindEntityById() {
        SanctionedEntity entity = createEntity("42", EntityType.INDIVIDUAL, ListSource.OFAC_SDN);
        index.add(entity);

        Optional<SanctionedEntity> found = index.findById("42");
        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo("42");
    }

    @Test
    void shouldReturnEmptyForMissingId() {
        assertThat(index.findById("nonexistent")).isEmpty();
    }

    @Test
    void shouldFindBySource() {
        index.addAll(
                List.of(
                        createEntity("1", EntityType.INDIVIDUAL, ListSource.OFAC_SDN),
                        createEntity("2", EntityType.ENTITY, ListSource.EU_CONSOLIDATED),
                        createEntity("3", EntityType.VESSEL, ListSource.OFAC_SDN)));

        Collection<SanctionedEntity> ofacEntities = index.findBySource(ListSource.OFAC_SDN);
        assertThat(ofacEntities).hasSize(2);

        Collection<SanctionedEntity> euEntities = index.findBySource(ListSource.EU_CONSOLIDATED);
        assertThat(euEntities).hasSize(1);

        Collection<SanctionedEntity> ukEntities = index.findBySource(ListSource.UK_HMT);
        assertThat(ukEntities).isEmpty();
    }

    @Test
    void shouldClearAllEntities() {
        index.addAll(
                List.of(
                        createEntity("1", EntityType.INDIVIDUAL, ListSource.OFAC_SDN),
                        createEntity("2", EntityType.ENTITY, ListSource.OFAC_SDN)));

        index.clear();

        assertThat(index.size()).isZero();
        assertThat(index.all()).isEmpty();
        assertThat(index.findBySource(ListSource.OFAC_SDN)).isEmpty();
    }

    @Test
    void shouldReturnCorrectStats() {
        index.addAll(
                List.of(
                        createEntity("1", EntityType.INDIVIDUAL, ListSource.OFAC_SDN),
                        createEntity("2", EntityType.INDIVIDUAL, ListSource.EU_CONSOLIDATED),
                        createEntity("3", EntityType.ENTITY, ListSource.OFAC_SDN)));

        IndexStats stats = index.stats();
        assertThat(stats.totalEntities()).isEqualTo(3);
        assertThat(stats.countBySource()).containsEntry(ListSource.OFAC_SDN, 2);
        assertThat(stats.countBySource()).containsEntry(ListSource.EU_CONSOLIDATED, 1);
        assertThat(stats.countByType()).containsEntry(EntityType.INDIVIDUAL, 2);
        assertThat(stats.countByType()).containsEntry(EntityType.ENTITY, 1);
        assertThat(stats.lastUpdated()).isNotNull();
    }

    @Test
    void shouldReturnUnmodifiableAllView() {
        index.add(createEntity("1", EntityType.INDIVIDUAL, ListSource.OFAC_SDN));
        Collection<SanctionedEntity> all = index.all();

        assertThatThrownBy(() -> all.add(createEntity("2", EntityType.ENTITY, ListSource.OFAC_SDN)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldThrowWhenAddingNull() {
        assertThatThrownBy(() -> index.add(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> index.addAll(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldHandleConcurrentAdds() throws InterruptedException {
        int threadCount = 10;
        int entitiesPerThread = 100;
        CountDownLatch latch = new CountDownLatch(threadCount);
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        for (int t = 0; t < threadCount; t++) {
            int threadId = t;
            Thread.ofVirtual()
                    .start(
                            () -> {
                                try {
                                    for (int i = 0; i < entitiesPerThread; i++) {
                                        String id = threadId + "-" + i;
                                        index.add(
                                                createEntity(
                                                        id,
                                                        EntityType.INDIVIDUAL,
                                                        ListSource.OFAC_SDN));
                                    }
                                } catch (Throwable e) {
                                    errors.add(e);
                                } finally {
                                    latch.countDown();
                                }
                            });
        }

        latch.await();
        assertThat(errors).isEmpty();
        assertThat(index.size()).isEqualTo(threadCount * entitiesPerThread);
    }

    @Test
    void shouldReplaceEntityWithSameId() {
        SanctionedEntity original = createEntity("1", EntityType.INDIVIDUAL, ListSource.OFAC_SDN);
        SanctionedEntity updated = createEntity("1", EntityType.ENTITY, ListSource.OFAC_SDN);

        index.add(original);
        index.add(updated);

        assertThat(index.size()).isEqualTo(1);
        assertThat(index.findById("1").get().entityType()).isEqualTo(EntityType.ENTITY);
    }

    @Test
    void shouldMoveIdToNewSourceWhenIdIsReusedAcrossSources() {
        index.add(createEntity("42", EntityType.INDIVIDUAL, ListSource.OFAC_SDN));
        index.add(createEntity("42", EntityType.INDIVIDUAL, ListSource.UN_CONSOLIDATED));

        assertThat(index.size()).isEqualTo(1);
        assertThat(index.findBySource(ListSource.OFAC_SDN)).isEmpty();
        assertThat(index.findBySource(ListSource.UN_CONSOLIDATED)).hasSize(1);
    }

    @Test
    void shouldRemoveMissingEntitiesWhenReplacingSource() {
        index.addAll(
                List.of(
                        createEntity("ofac-1", EntityType.INDIVIDUAL, ListSource.OFAC_SDN),
                        createEntity("ofac-2", EntityType.INDIVIDUAL, ListSource.OFAC_SDN),
                        createEntity("un-1", EntityType.INDIVIDUAL, ListSource.UN_CONSOLIDATED)));

        Set<String> removed =
                index.replaceSource(
                        ListSource.OFAC_SDN,
                        List.of(
                                createEntity("ofac-1", EntityType.INDIVIDUAL, ListSource.OFAC_SDN),
                                createEntity("ofac-3", EntityType.ENTITY, ListSource.OFAC_SDN)));

        assertThat(removed).containsExactly("ofac-2");
        assertThat(index.findById("ofac-2")).isEmpty();
        assertThat(index.findBySource(ListSource.OFAC_SDN))
                .extracting(SanctionedEntity::id)
                .containsExactlyInAnyOrder("ofac-1", "ofac-3");
        assertThat(index.findById("un-1")).isPresent();
    }

    @Test
    void shouldChangeVersionWhenContentChangesAtSameSize() {
        index.add(createEntity("ofac-1", EntityType.INDIVIDUAL, ListSource.OFAC_SDN));
        long before = index.version();

        index.replaceSource(
                ListSource.OFAC_SDN,
                List.of(createEntity("ofac-2", EntityType.INDIVIDUAL, ListSource.OFAC_SDN)));

        assertThat(index.size()).isEqualTo(1);
        assertThat(index.version()).isNotEqualTo(before);
    }

    @Test
    void shouldRejectEntityFromAnotherSourceWhenReplacingSource() {
        SanctionedEntity unEntity =
                createEntity("un-1", EntityType.INDIVIDUAL, ListSource.UN_CONSOLIDATED);

        assertThatThrownBy(() -> index.replaceSource(ListSource.OFAC_SDN, List.of(unEntity)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(index.size()).isZero();
    }

    private static SanctionedEntity createEntity(String id, EntityType type, ListSource source) {
        NameInfo name =
                new NameInfo(
                        "Test Entity " + id,
                        null,
                        null,
                        null,
                        null,
                        NameType.PRIMARY,
                        NameStrength.STRONG,
                        ScriptType.LATIN);
        return new SanctionedEntity(
                id,
                type,
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
    }
}
