package dev.sieve.ingest.ofac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.IdentifierType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameStrength;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.Relation;
import dev.sieve.core.model.RelationType;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.SanctionsProgram;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OfacNonSdnProviderTest {

    private OfacNonSdnProvider provider;

    @BeforeEach
    void setUp() {
        provider = new OfacNonSdnProvider(URI.create("https://localhost/test"));
    }

    @Test
    void shouldReturnOfacNonSdnSource() {
        assertThat(provider.source()).isEqualTo(ListSource.OFAC_NONSDN);
    }

    @Test
    void shouldKeepTheEntryUidAndNameWhenAliasesAddressesAndIdsFollow() throws Exception {
        List<SanctionedEntity> entities = parseSample();

        // three entries and the wallet of SMITH's digital currency address
        assertThat(entities)
                .extracting(SanctionedEntity::id)
                .containsExactly(
                        "ofac-nonsdn-9001",
                        "ofac-nonsdn-9010",
                        "ofac-nonsdn-9020",
                        "ofac-nonsdn-9020-wallet-1NonSdnWallet0000000000000000000000");

        SanctionedEntity holding = byId(entities, "ofac-nonsdn-9001");
        assertThat(holding.primaryName().fullName()).isEqualTo("ENERGY HOLDING PJSC");
        assertThat(holding.entityType()).isEqualTo(EntityType.ENTITY);
        assertThat(holding.aliases())
                .extracting(a -> a.fullName(), a -> a.nameType(), a -> a.strength())
                .containsExactly(
                        tuple("ENERGY HOLDING", NameType.AKA, NameStrength.STRONG),
                        tuple("EH OJSC", NameType.FKA, NameStrength.WEAK));
        assertThat(holding.addresses())
                .singleElement()
                .satisfies(
                        address -> {
                            assertThat(address.city()).isEqualTo("Moscow");
                            assertThat(address.country()).isEqualTo("Russia");
                        });
        assertThat(holding.identifiers())
                .extracting(i -> i.type(), i -> i.value())
                .containsExactly(tuple(IdentifierType.REGISTRATION_NUMBER, "1027700070518"));
        assertThat(holding.remarks()).startsWith("Executive Order 13662");
    }

    @Test
    void shouldParseIndividualDetails() throws Exception {
        SanctionedEntity smith = byId(parseSample(), "ofac-nonsdn-9020");

        assertThat(smith.entityType()).isEqualTo(EntityType.INDIVIDUAL);
        assertThat(smith.primaryName().fullName()).isEqualTo("SMITH, John");
        assertThat(smith.aliases()).extracting(a -> a.fullName()).containsExactly("SMYTHE, Jon");
        assertThat(smith.nationalities()).containsExactly("Russia");
        assertThat(smith.datesOfBirth()).containsExactly(LocalDate.of(1970, 1, 15));
    }

    @Test
    void shouldStampEverythingWithTheNonSdnSource() throws Exception {
        List<SanctionedEntity> entities = parseSample();

        assertThat(entities)
                .allSatisfy(e -> assertThat(e.listSource()).isEqualTo(ListSource.OFAC_NONSDN));
        assertThat(entities)
                .flatExtracting(SanctionedEntity::programs)
                .extracting(SanctionsProgram::source)
                .containsOnly(ListSource.OFAC_NONSDN);
        SanctionedEntity wallet =
                byId(entities, "ofac-nonsdn-9020-wallet-1NonSdnWallet0000000000000000000000");
        assertThat(wallet.entityType()).isEqualTo(EntityType.CRYPTO_WALLET);
        assertThat(wallet.relations())
                .containsExactly(
                        new Relation(
                                RelationType.LINKED,
                                "ofac-nonsdn-9020",
                                OfacXmlParser.WALLET_HOLDER_ROLE,
                                null,
                                null,
                                null));
    }

    @Test
    void shouldLinkEntriesNamedInRemarksByPrimaryNameOrAlias() throws Exception {
        List<SanctionedEntity> entities = parseSample();

        Relation linked =
                new Relation(
                        RelationType.LINKED,
                        "ofac-nonsdn-9001",
                        OfacLinks.LINKED_ROLE,
                        null,
                        null,
                        null);
        assertThat(byId(entities, "ofac-nonsdn-9010").relations()).containsExactly(linked);
        // SMITH names the holding by its alias, after the relation to his wallet
        assertThat(byId(entities, "ofac-nonsdn-9020").relations())
                .extracting(Relation::type, Relation::targetId)
                .containsExactly(
                        tuple(
                                RelationType.OWNERSHIP,
                                "ofac-nonsdn-9020-wallet-1NonSdnWallet0000000000000000000000"),
                        tuple(RelationType.LINKED, "ofac-nonsdn-9001"));
        assertThat(byId(entities, "ofac-nonsdn-9001").relations()).isEmpty();
    }

    private List<SanctionedEntity> parseSample() throws IOException, ListIngestionException {
        try (InputStream in = getClass().getResourceAsStream("/nonsdn_test_sample.xml")) {
            assertThat(in).isNotNull();
            return provider.parseResponse(in.readAllBytes());
        }
    }

    private static SanctionedEntity byId(List<SanctionedEntity> entities, String id) {
        return entities.stream()
                .filter(e -> e.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no entity " + id));
    }
}
