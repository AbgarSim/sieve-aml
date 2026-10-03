package dev.sieve.ingest.ustrade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.RiskTopic;
import dev.sieve.core.model.SanctionedEntity;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CslSplitProvidersTest {

    @Test
    void shouldKeepOnlyEntityListEntriesWhenParsingForBisEntityList() throws Exception {
        List<SanctionedEntity> entities = new BisEntityListProvider().parseResponse(sample());

        assertThat(entities)
                .singleElement()
                .satisfies(
                        entity -> {
                            assertThat(entity.id())
                                    .isEqualTo(
                                            "bis-el-0f1e2d3c4b5a69788796a5b4c3d2e1f00f1e2d3c4b5a69788796a5b4");
                            assertThat(entity.listSource()).isEqualTo(ListSource.US_BIS_ENTITY);
                            assertThat(entity.entityType()).isEqualTo(EntityType.ENTITY);
                            assertThat(entity.primaryName().fullName())
                                    .isEqualTo("Example Electronics Trading Co., Ltd.");
                            assertThat(entity.addresses())
                                    .singleElement()
                                    .satisfies(a -> assertThat(a.country()).isEqualTo("PK"));
                            assertThat(entity.programs())
                                    .singleElement()
                                    .satisfies(
                                            p ->
                                                    assertThat(p.source())
                                                            .isEqualTo(ListSource.US_BIS_ENTITY));
                            assertThat(entity.topics()).containsExactly(RiskTopic.EXPORT_CONTROL);
                            assertThat(entity.listedDate())
                                    .isEqualTo(Instant.parse("2011-11-21T00:00:00Z"));
                        });
    }

    @Test
    void shouldKeepLicenceTermsInRemarksWhenEntryHasThem() throws Exception {
        SanctionedEntity entity = new BisEntityListProvider().parseResponse(sample()).get(0);

        assertThat(entity.remarks())
                .isEqualTo(
                        "License requirement: For all items subject to the EAR. (See §744.11 of"
                                + " the EAR).\n"
                                + "License policy: Presumption of denial.\n"
                                + "Federal Register notice: 76 FR 71867");
    }

    @Test
    void shouldKeepOnlyMilitaryEndUserEntriesWhenParsingForBisMeu() throws Exception {
        List<SanctionedEntity> entities = new BisMilitaryEndUserProvider().parseResponse(sample());

        assertThat(entities)
                .singleElement()
                .satisfies(
                        entity -> {
                            assertThat(entity.id()).startsWith("bis-meu-");
                            assertThat(entity.listSource()).isEqualTo(ListSource.US_BIS_MEU);
                            assertThat(entity.aliases())
                                    .extracting(a -> a.fullName())
                                    .containsExactly("Example Engine Institute");
                        });
    }

    @Test
    void shouldKeepEveryEntryWhenParsingTheWholeCsl() throws Exception {
        List<SanctionedEntity> entities = new UsTradeCslProvider().parseResponse(sample());

        assertThat(entities).hasSize(3);
        assertThat(entities).extracting(SanctionedEntity::id).allMatch(id -> id.startsWith("csl-"));
        assertThat(entities).allMatch(e -> e.listSource() == ListSource.US_TRADE_CSL);
        assertThat(entities).allMatch(e -> e.hasTopic(RiskTopic.SANCTION));
        assertThat(entities.get(2).programs()).extracting(p -> p.code()).contains("CUBA");
    }

    @Test
    void shouldNameTheSplitListWhenResponseHasNoResults() {
        byte[] body = "{\"total\":0}".getBytes();

        assertThatThrownBy(() -> new BisMilitaryEndUserProvider().parseResponse(body))
                .isInstanceOf(ListIngestionException.class)
                .hasMessageContaining("US BIS MEU");
    }

    private static byte[] sample() throws Exception {
        try (InputStream in =
                CslSplitProvidersTest.class.getResourceAsStream("/csl_test_sample.json")) {
            return in.readAllBytes();
        }
    }
}
