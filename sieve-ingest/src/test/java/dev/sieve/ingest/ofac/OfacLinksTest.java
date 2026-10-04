package dev.sieve.ingest.ofac;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.NameStrength;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.Relation;
import dev.sieve.core.model.RelationType;
import dev.sieve.core.model.RiskTopic;
import dev.sieve.core.model.SanctionedEntity;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OfacLinksTest {

    @Test
    void shouldReadLinkedNamesWithTheirOwnParenthesesAndSemicolons() {
        assertThat(
                        OfacLinks.linkedNames(
                                "Secondary sanctions risk. (Linked To: ISLAMIC REVOLUTIONARY GUARD CORPS"
                                        + " (IRGC)-QODS FORCE; Linked To: HIZBALLAH)."))
                .containsExactly(
                        "ISLAMIC REVOLUTIONARY GUARD CORPS (IRGC)-QODS FORCE", "HIZBALLAH");
        assertThat(OfacLinks.linkedNames("(Linked To: VEST SPECTRUM (S) PTE. LTD.)"))
                .containsExactly("VEST SPECTRUM (S) PTE. LTD.");
        assertThat(OfacLinks.linkedNames("Linked To: PETROLEOS DE VENEZUELA, S.A."))
                .containsExactly("PETROLEOS DE VENEZUELA, S.A.");
        assertThat(OfacLinks.linkedNames("Vessel Registration Identification IMO 1234567"))
                .isEmpty();
        assertThat(OfacLinks.linkedNames(null)).isEmpty();
    }

    @Test
    void shouldResolveNamesToTheOneEntryTheyNameAndLinkVesselOwners() {
        SanctionedEntity wallet =
                new SanctionedEntity(
                        "ofac-sdn-2-wallet-abc",
                        EntityType.CRYPTO_WALLET,
                        ListSource.OFAC_SDN,
                        name("abc"),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        "Digital currency address held by DOE, John (ofac-sdn-2)",
                        List.of(),
                        null,
                        null,
                        Set.of(RiskTopic.SANCTION),
                        List.of(
                                new Relation(
                                        RelationType.LINKED,
                                        "ofac-sdn-2",
                                        "holder",
                                        null,
                                        null,
                                        null)));
        Relation holds =
                new Relation(RelationType.OWNERSHIP, wallet.id(), "holder", null, null, null);
        List<SanctionedEntity> in =
                List.of(
                        entity(
                                "ofac-sdn-1",
                                EntityType.ENTITY,
                                "ACME HOLDINGS LTD",
                                "(Linked To: DOE, John)",
                                List.of(),
                                "ACME"),
                        entity(
                                "ofac-sdn-2",
                                EntityType.INDIVIDUAL,
                                "DOE, John",
                                "(Linked To: Acme Holdings, Ltd.; Linked To: ACME TWIN; Linked To: NOBODY)",
                                List.of(holds)),
                        wallet,
                        entity("ofac-sdn-3", EntityType.ENTITY, "ACME TWIN", null, List.of()),
                        entity("ofac-sdn-4", EntityType.ENTITY, "ACME TWIN", null, List.of()),
                        entity(
                                "ofac-sdn-5",
                                EntityType.VESSEL,
                                "OCEAN VOYAGER",
                                "(Linked To: ACME)",
                                List.of()));

        List<SanctionedEntity> out =
                OfacLinks.resolve(in, Map.of("ofac-sdn-5", "Acme Holdings Ltd"), "test");

        assertThat(out)
                .extracting(SanctionedEntity::id)
                .containsExactlyElementsOf(in.stream().map(SanctionedEntity::id).toList());
        // ACME is named by John and owns the vessel
        assertThat(out.get(0).relations())
                .containsExactly(
                        new Relation(
                                RelationType.LINKED, "ofac-sdn-2", "linked to", null, null, null),
                        new Relation(
                                RelationType.OWNERSHIP, "ofac-sdn-5", "owner", null, null, null));
        // John keeps his wallet; "Acme Holdings, Ltd." matches ACME's primary name ignoring
        // punctuation,
        // ACME TWIN names two entries and NOBODY none, so both stay text only
        assertThat(out.get(1).relations())
                .containsExactly(
                        holds,
                        new Relation(
                                RelationType.LINKED, "ofac-sdn-1", "linked to", null, null, null));
        assertThat(out.get(2)).isSameAs(wallet);
        assertThat(out.get(3).relations()).isEmpty();
        assertThat(out.get(4).relations()).isEmpty();
        // the vessel's remark names ACME by its alias; its vesselInfo owner adds the owner link
        assertThat(out.get(5).relations())
                .containsExactly(
                        new Relation(
                                RelationType.LINKED, "ofac-sdn-1", "linked to", null, null, null),
                        new Relation(RelationType.LINKED, "ofac-sdn-1", "owner", null, null, null));
    }

    private static SanctionedEntity entity(
            String id,
            EntityType type,
            String primaryName,
            String remarks,
            List<Relation> relations,
            String... aliases) {
        return new SanctionedEntity(
                id,
                type,
                ListSource.OFAC_SDN,
                name(primaryName),
                java.util.Arrays.stream(aliases).map(OfacLinksTest::name).toList(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                remarks,
                List.of(),
                null,
                null,
                Set.of(RiskTopic.SANCTION),
                relations);
    }

    private static NameInfo name(String name) {
        return new NameInfo(
                name, null, null, null, null, NameType.PRIMARY, NameStrength.STRONG, null);
    }
}
