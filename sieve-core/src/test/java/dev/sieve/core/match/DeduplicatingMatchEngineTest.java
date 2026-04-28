package dev.sieve.core.match;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sieve.core.dedup.CanonicalEntity;
import dev.sieve.core.dedup.DeduplicationIndex;
import dev.sieve.core.dedup.DeduplicationResult;
import dev.sieve.core.index.EntityIndex;
import dev.sieve.core.index.InMemoryEntityIndex;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.SanctionedEntity;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DeduplicatingMatchEngineTest {

    @Test
    void shouldCollapseDuplicateResultsByCanonicalId() {
        SanctionedEntity ofac = entity("ofac-1", "John DOE", ListSource.OFAC_SDN);
        SanctionedEntity eu = entity("eu-1", "John DOE", ListSource.EU_CONSOLIDATED);
        SanctionedEntity unrelated = entity("eu-2", "Jane SMITH", ListSource.EU_CONSOLIDATED);

        EntityIndex index = new InMemoryEntityIndex();
        index.addAll(List.of(ofac, eu, unrelated));

        // Build dedup result: ofac-1 and eu-1 are the same canonical entity
        CanonicalEntity canonical = CanonicalEntity.merge("canonical-0", List.of(ofac, eu));
        CanonicalEntity singletonJane = CanonicalEntity.singleton(unrelated);

        Map<String, CanonicalEntity> canonicals = new HashMap<>();
        canonicals.put("canonical-0", canonical);
        canonicals.put(singletonJane.canonicalId(), singletonJane);

        Map<String, String> mapping = new HashMap<>();
        mapping.put("ofac-1", "canonical-0");
        mapping.put("eu-1", "canonical-0");
        mapping.put("eu-2", singletonJane.canonicalId());

        DeduplicationResult dedupResult = new DeduplicationResult(
                canonicals, mapping, 3, 2, 1, Duration.ofMillis(10));

        DeduplicationIndex dedupIndex = new DeduplicationIndex();
        dedupIndex.apply(dedupResult);

        // A fake match engine that returns all entities with score 0.95
        MatchEngine fakeEngine = (request, idx) -> idx.all().stream()
                .map(e -> new MatchResult(e, 0.95, "primaryName", "FAKE"))
                .toList();

        DeduplicatingMatchEngine engine = new DeduplicatingMatchEngine(fakeEngine, dedupIndex);
        ScreeningRequest request = ScreeningRequest.of("John DOE", 0.5);

        List<MatchResult> results = engine.screen(request, index);

        // Should collapse ofac-1 and eu-1 into one result, keep Jane separate
        assertThat(results).hasSize(2);
    }

    @Test
    void shouldKeepHighestScorePerCanonicalGroup() {
        SanctionedEntity ofac = entity("ofac-1", "John DOE", ListSource.OFAC_SDN);
        SanctionedEntity eu = entity("eu-1", "John Doe", ListSource.EU_CONSOLIDATED);

        EntityIndex index = new InMemoryEntityIndex();
        index.addAll(List.of(ofac, eu));

        CanonicalEntity canonical = CanonicalEntity.merge("canonical-0", List.of(ofac, eu));
        Map<String, String> mapping = Map.of("ofac-1", "canonical-0", "eu-1", "canonical-0");

        DeduplicationResult dedupResult = new DeduplicationResult(
                Map.of("canonical-0", canonical), mapping, 2, 1, 1, Duration.ofMillis(5));

        DeduplicationIndex dedupIndex = new DeduplicationIndex();
        dedupIndex.apply(dedupResult);

        // Fake engine returns different scores for the two entities
        MatchEngine fakeEngine = (request, idx) -> List.of(
                new MatchResult(ofac, 0.85, "primaryName", "FAKE"),
                new MatchResult(eu, 0.92, "primaryName", "FAKE"));

        DeduplicatingMatchEngine engine = new DeduplicatingMatchEngine(fakeEngine, dedupIndex);
        List<MatchResult> results = engine.screen(ScreeningRequest.of("test", 0.5), index);

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().score()).isEqualTo(0.92);
        assertThat(results.getFirst().entity().id()).isEqualTo("eu-1");
    }

    @Test
    void shouldPassThroughWhenDedupNotPopulated() {
        SanctionedEntity ofac = entity("ofac-1", "John DOE", ListSource.OFAC_SDN);
        SanctionedEntity eu = entity("eu-1", "John DOE", ListSource.EU_CONSOLIDATED);

        EntityIndex index = new InMemoryEntityIndex();
        index.addAll(List.of(ofac, eu));

        DeduplicationIndex dedupIndex = new DeduplicationIndex(); // not populated

        MatchEngine fakeEngine = (request, idx) -> idx.all().stream()
                .map(e -> new MatchResult(e, 0.95, "primaryName", "FAKE"))
                .toList();

        DeduplicatingMatchEngine engine = new DeduplicatingMatchEngine(fakeEngine, dedupIndex);
        List<MatchResult> results = engine.screen(ScreeningRequest.of("test", 0.5), index);

        // No dedup applied — both results pass through
        assertThat(results).hasSize(2);
    }

    private static SanctionedEntity entity(String id, String fullName, ListSource source) {
        return new SanctionedEntity(
                id, EntityType.INDIVIDUAL, source,
                new NameInfo(fullName, null, null, null, null, NameType.PRIMARY, null, null),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), null, List.of(), null, Instant.now());
    }
}
