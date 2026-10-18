package dev.sieve.ingest.tw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.RiskTopic;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.ScriptType;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class TwShtcProviderTest {

    @Test
    void shouldReadEveryRow() throws Exception {
        List<SanctionedEntity> entities = parseSample();

        assertThat(entities).hasSize(9);
        assertThat(entities)
                .allSatisfy(
                        e -> {
                            assertThat(e.listSource()).isEqualTo(ListSource.TW_SHTC);
                            assertThat(e.topics()).containsExactly(RiskTopic.EXPORT_CONTROL);
                            assertThat(e.programs().get(0).code()).isEqualTo("SHTC");
                            assertThat(e.id()).startsWith("tw-shtc-");
                        });
        assertThat(entities.get(0).primaryName().fullName()).isEqualTo("ABU SAYYAF GROUP");
        assertThat(entities.get(0).entityType()).isEqualTo(EntityType.ENTITY);
        assertThat(entities.get(0).aliases())
                .extracting(NameInfo::fullName)
                .containsExactly("AL HARAKAT AL ISLAMIYYA");
    }

    @Test
    void shouldReadPeopleWrittenSurnameFirst() throws Exception {
        SanctionedEntity omar = parseSample().get(1);

        assertThat(omar.entityType()).isEqualTo(EntityType.INDIVIDUAL);
        assertThat(omar.primaryName().familyName()).isEqualTo("OMAR");
        assertThat(omar.primaryName().givenName()).isEqualTo("Mohammed");
        assertThat(omar.addresses())
                .singleElement()
                .satisfies(
                        a -> {
                            assertThat(a.country()).isEqualTo("AF");
                            assertThat(a.fullAddress()).isEqualTo("Afghanistan");
                        });
    }

    @Test
    void shouldTurnOtherScriptsIntoAliases() throws Exception {
        List<SanctionedEntity> entities = parseSample();

        SanctionedEntity li = entities.get(2);
        assertThat(li.entityType()).isEqualTo(EntityType.INDIVIDUAL);
        assertThat(li.primaryName().fullName()).isEqualTo("LI, Hong Ri");
        assertThat(li.aliases())
                .extracting(NameInfo::fullName, NameInfo::script)
                .contains(
                        org.assertj.core.groups.Tuple.tuple("李红日", ScriptType.CJK),
                        org.assertj.core.groups.Tuple.tuple("RI, Hong-il", ScriptType.LATIN));

        SanctionedEntity rustam = entities.get(4);
        assertThat(rustam.entityType()).isEqualTo(EntityType.INDIVIDUAL);
        assertThat(rustam.primaryName().fullName()).isEqualTo("RUSTAM MAGOMEDOVICH ASELDEROV");
        assertThat(rustam.aliases())
                .extracting(NameInfo::fullName, NameInfo::script)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Абу Мухаммад", ScriptType.CYRILLIC),
                        org.assertj.core.groups.Tuple.tuple("Abu Muhammad", ScriptType.LATIN));
    }

    @Test
    void shouldPairAddressesWithCountryCodes() throws Exception {
        SanctionedEntity dalian = parseSample().get(3);

        assertThat(dalian.entityType()).isEqualTo(EntityType.ENTITY);
        assertThat(dalian.primaryName().fullName())
                .isEqualTo("DALIAN GLOBAL UNITY SHIPPING CO., LTD.");
        assertThat(dalian.addresses())
                .extracting(a -> a.country() + ":" + a.fullAddress())
                .containsExactly("CN:Dalian China", "KP:Pyongyang Korea North");
        assertThat(dalian.aliases()).extracting(NameInfo::fullName).contains("大连宁联船务有限公司");
    }

    @Test
    void shouldReadIdNumbersAndDropStrayMarkup() throws Exception {
        SanctionedEntity mujahid = parseSample().get(5);

        assertThat(mujahid.identifiers())
                .extracting(i -> i.value())
                .containsExactly("35404-1577309-9", "26961341469");
        assertThat(mujahid.aliases())
                .extracting(NameInfo::fullName)
                .containsExactly("AZIZ, Mohammad Yahya");
        assertThat(mujahid.addresses()).extracting(a -> a.country()).containsExactly("PK");
    }

    @Test
    void shouldGiveRepeatedNamesTheirOwnIds() throws Exception {
        List<SanctionedEntity> entities = parseSample();

        assertThat(entities.get(6).id()).isNotEqualTo(entities.get(7).id());
        assertThat(entities.get(7).id()).isEqualTo(entities.get(6).id() + "-2");
    }

    @Test
    void shouldKeepCompaniesWithACommaAsOrganisations() throws Exception {
        SanctionedEntity plant = parseSample().get(8);

        assertThat(plant.entityType()).isEqualTo(EntityType.ENTITY);
        assertThat(plant.aliases())
                .extracting(NameInfo::fullName)
                .containsExactly("Zelenodolsk Plant Named After A.M. Gorky, JSC");
    }

    @Test
    void shouldRejectAnUnexpectedHeader() {
        TwShtcProvider provider = new TwShtcProvider(URI.create("http://localhost"));
        assertThatThrownBy(
                        () -> provider.parseResponse("a,b\n1,2\n".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ListIngestionException.class);
    }

    private static List<SanctionedEntity> parseSample() throws Exception {
        try (InputStream in =
                TwShtcProviderTest.class.getResourceAsStream("/tw_shtc_test_sample.csv")) {
            return new TwShtcProvider(URI.create("http://localhost"))
                    .parseResponse(in.readAllBytes());
        }
    }
}
