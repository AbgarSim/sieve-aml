package dev.sieve.ingest.gleif;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GleifRegistryTest {

    @Test
    void shouldKeepOnlyActiveConsolidationLinksToKnownParents() {
        Map<String, Integer> columns =
                Map.of(
                        "Relationship.StartNode.NodeID", 0,
                        "Relationship.EndNode.NodeID", 1,
                        "Relationship.RelationshipType", 2,
                        "Relationship.RelationshipStatus", 3,
                        "Relationship.Period.1.startDate", 4,
                        "Relationship.Period.1.endDate", 5,
                        "Relationship.Period.1.periodType", 6,
                        "Relationship.Quantifiers.1.MeasurementMethod", 7,
                        "Relationship.Quantifiers.1.QuantifierAmount", 8,
                        "Relationship.Quantifiers.1.QuantifierUnits", 9);
        Set<String> parents = Set.of("P");

        GleifRegistry.Link link =
                GleifRegistry.link(
                        List.of(
                                "C",
                                "P",
                                "IS_DIRECTLY_CONSOLIDATED_BY",
                                "ACTIVE",
                                "2020-02-02T00:00:00.000Z",
                                "",
                                "RELATIONSHIP_PERIOD",
                                "ACCOUNTING_CONSOLIDATION",
                                "51.5",
                                "PERCENTAGE"),
                        columns,
                        parents);

        assertThat(link)
                .isEqualTo(
                        new GleifRegistry.Link(
                                "C",
                                "P",
                                "IS_DIRECTLY_CONSOLIDATED_BY",
                                LocalDate.of(2020, 2, 2),
                                null,
                                51.5));
        assertThat(link.role()).isEqualTo("direct parent");
        // other parents, other relationship types, inactive links and self links are dropped
        assertThat(
                        GleifRegistry.link(
                                List.of("C", "X", "IS_DIRECTLY_CONSOLIDATED_BY", "ACTIVE"),
                                columns,
                                parents))
                .isNull();
        assertThat(
                        GleifRegistry.link(
                                List.of("C", "P", "IS_FUND-MANAGED_BY", "ACTIVE"),
                                columns,
                                parents))
                .isNull();
        assertThat(
                        GleifRegistry.link(
                                List.of("C", "P", "IS_ULTIMATELY_CONSOLIDATED_BY", "INACTIVE"),
                                columns,
                                parents))
                .isNull();
        assertThat(
                        GleifRegistry.link(
                                List.of("P", "P", "IS_ULTIMATELY_CONSOLIDATED_BY", "ACTIVE"),
                                columns,
                                parents))
                .isNull();
    }

    @Test
    void shouldSplitCsvFieldsWithQuotes() {
        assertThat(GleifRegistry.csvFields("\"a,b\",c,\"say \"\"hi\"\"\",,d"))
                .containsExactly("a,b", "c", "say \"hi\"", "", "d");
    }

    @Test
    void shouldEncodeBracketsInQueries() {
        assertThat(GleifRegistry.query("filter[lei]", "A,B", "page[size]", "200"))
                .isEqualTo("?filter%5Blei%5D=A%2CB&page%5Bsize%5D=200");
    }

    @Test
    void shouldTakeTheCountryFromTheJurisdictionFirst() throws Exception {
        assertThat(
                        GleifRegistry.country(
                                new com.fasterxml.jackson.databind.ObjectMapper()
                                        .readTree(
                                                "{\"jurisdiction\":\"US-DE\","
                                                        + "\"legalAddress\":{\"country\":\"GB\"}}")))
                .isEqualTo("US");
        assertThat(
                        GleifRegistry.country(
                                new com.fasterxml.jackson.databind.ObjectMapper()
                                        .readTree("{\"legalAddress\":{\"country\":\"GB\"}}")))
                .isEqualTo("GB");
    }
}
