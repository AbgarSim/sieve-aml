package dev.sieve.cli.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sieve.core.geo.CountryNormalizer;
import dev.sieve.core.model.Address;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.Relation;
import dev.sieve.core.model.RelationType;
import dev.sieve.core.model.RiskTopic;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.VesselDetails;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class RelationGraphTest {

    private static final Instant NOW = Instant.parse("2026-10-05T03:00:00Z");
    private static final CountryNormalizer COUNTRIES = CountryNormalizer.standard();

    @Test
    void shouldLinkRecordsOfTheSameListAndUniqueIdsOfOtherLists() {
        SanctionedEntity person =
                entity("ofac-1", ListSource.OFAC_SDN, "Russia", null)
                        .withRelations(
                                List.of(
                                        new Relation(
                                                RelationType.ASSOCIATE,
                                                "ofac-2",
                                                "associate of",
                                                null,
                                                null,
                                                null)));
        SanctionedEntity associate = entity("ofac-2", ListSource.OFAC_SDN, null, "Dubai, UAE");
        SanctionedEntity company =
                entity("lei-9", ListSource.GLEIF_SANCTION_LINKED, null, "Cyprus")
                        .withRelations(
                                List.of(
                                        new Relation(
                                                RelationType.LINKED,
                                                "ofac-1",
                                                "IS_ULTIMATELY_CONSOLIDATED_BY",
                                                75.0,
                                                null,
                                                null)));
        List<SanctionedEntity> all = List.of(person, associate, company);

        RelationGraph graph = RelationGraph.of(all, all, COUNTRIES);

        assertThat(graph.edges())
                .extracting(RelationGraph.Edge::from, RelationGraph.Edge::to)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "GLEIF_SANCTION_LINKED/lei-9", "OFAC_SDN/ofac-1"),
                        org.assertj.core.groups.Tuple.tuple("OFAC_SDN/ofac-1", "OFAC_SDN/ofac-2"));
        Map<String, Object> json = graph.toJson();
        assertThat(json.get("home"))
                .isEqualTo(
                        Map.of(
                                "OFAC_SDN/ofac-1", "RU",
                                "OFAC_SDN/ofac-2", "AE",
                                "GLEIF_SANCTION_LINKED/lei-9", "CY"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = (List<Map<String, Object>>) json.get("edges");
        assertThat(edges.get(0)).containsEntry("r", "LINKED").containsEntry("p", 75.0);
        assertThat(edges.get(1)).containsEntry("r", "ASSOCIATE").containsEntry("l", "associate of");
    }

    @Test
    void shouldDropLinksToUnpublishedRecordsPositionsItselfAndUnknownOrAmbiguousIds() {
        SanctionedEntity pep = entity("wd-Q1", ListSource.WIKIDATA_PEP, "Germany", null);
        SanctionedEntity twinA = entity("x-1", ListSource.UK_HMT, null, null);
        SanctionedEntity twinB = entity("x-1", ListSource.CH_SECO, null, null);
        SanctionedEntity holder =
                entity("ofac-1", ListSource.OFAC_SDN, "Russia", null)
                        .withRelations(
                                List.of(
                                        Relation.of(RelationType.FAMILY, "wd-Q1"),
                                        Relation.of(RelationType.POSITION_HELD, "wd-Q11696"),
                                        Relation.of(RelationType.LINKED, "ofac-1"),
                                        Relation.of(RelationType.LINKED, "nowhere"),
                                        Relation.of(RelationType.LINKED, "x-1")));
        List<SanctionedEntity> all = List.of(pep, twinA, twinB, holder);
        List<SanctionedEntity> published =
                all.stream().filter(Predicate.not(e -> e == pep)).toList();

        RelationGraph graph = RelationGraph.of(all, published, COUNTRIES);

        assertThat(graph.edges()).isEmpty();
        @SuppressWarnings("unchecked")
        Map<String, Object> stats = (Map<String, Object>) graph.toJson().get("stats");
        assertThat(stats.get("dropped"))
                .isEqualTo(Map.of("unpublished", 1, "position", 1, "self", 1, "unresolved", 2));
    }

    @Test
    void shouldKeepTheTypedLinkWhenAListStatesAPlainOneToo() {
        SanctionedEntity owner =
                entity("ofac-1", ListSource.OFAC_SDN, null, "Iran")
                        .withRelations(
                                List.of(
                                        new Relation(
                                                RelationType.OWNERSHIP,
                                                "ofac-2",
                                                "owner",
                                                null,
                                                null,
                                                null)));
        SanctionedEntity vessel =
                entity("ofac-2", ListSource.OFAC_SDN, null, null)
                        .withVessel(new VesselDetails("Panama", null, null, null, null))
                        .withRelations(List.of(Relation.of(RelationType.LINKED, "ofac-1")));
        List<SanctionedEntity> all = List.of(owner, vessel);

        RelationGraph graph = RelationGraph.of(all, all, COUNTRIES);

        assertThat(graph.edges()).hasSize(1);
        assertThat(graph.edges().getFirst().relation().type()).isEqualTo(RelationType.OWNERSHIP);
        assertThat(graph.toJson().get("home"))
                .isEqualTo(Map.of("OFAC_SDN/ofac-1", "IR", "OFAC_SDN/ofac-2", "PA"));
    }

    @Test
    void shouldPlaceAnEntityAtItsNationalityBeforeItsAddress() {
        SanctionedEntity both = entity("1", ListSource.OFAC_SDN, "Russia", "Dubai, UAE");
        SanctionedEntity none = entity("2", ListSource.OFAC_SDN, null, null);

        assertThat(RelationGraph.homeCountry(both, COUNTRIES)).contains("RU");
        assertThat(RelationGraph.homeCountry(none, COUNTRIES)).isEmpty();
    }

    private static SanctionedEntity entity(
            String id, ListSource source, String nationality, String addressCountry) {
        return new SanctionedEntity(
                id,
                EntityType.INDIVIDUAL,
                source,
                new NameInfo("Name " + id, null, null, null, null, NameType.PRIMARY, null, null),
                List.of(),
                addressCountry == null
                        ? List.of()
                        : List.of(new Address(null, null, null, null, addressCountry, null)),
                List.of(),
                nationality == null ? List.of() : List.of(nationality),
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of(),
                null,
                NOW,
                Set.of(RiskTopic.SANCTION),
                List.of());
    }
}
