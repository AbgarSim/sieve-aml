package dev.sieve.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.ingest.ar.ArRepetProvider;
import dev.sieve.ingest.au.AuDfatProvider;
import dev.sieve.ingest.be.BeFodProvider;
import dev.sieve.ingest.ca.CanadaConsolidatedProvider;
import dev.sieve.ingest.ch.ChSecoProvider;
import dev.sieve.ingest.eu.EuConsolidatedProvider;
import dev.sieve.ingest.eu.EuJournalProvider;
import dev.sieve.ingest.eu.EuSanctionsMapProvider;
import dev.sieve.ingest.eu.EuTravelBansProvider;
import dev.sieve.ingest.europol.EuMostWantedProvider;
import dev.sieve.ingest.fr.FrTresorProvider;
import dev.sieve.ingest.gleif.GleifSanctionLinkedProvider;
import dev.sieve.ingest.gleif.GleifStateOwnedProvider;
import dev.sieve.ingest.il.IlWmdTerrorProvider;
import dev.sieve.ingest.in.InMhaProvider;
import dev.sieve.ingest.jp.JpMofProvider;
import dev.sieve.ingest.lv.LvFiuProvider;
import dev.sieve.ingest.mc.McFundFreezingProvider;
import dev.sieve.ingest.md.MdTerrorProvider;
import dev.sieve.ingest.nz.NzRussiaProvider;
import dev.sieve.ingest.ofac.OfacNonSdnProvider;
import dev.sieve.ingest.ofac.OfacSdnProvider;
import dev.sieve.ingest.pl.PlMswiaProvider;
import dev.sieve.ingest.qa.QaNctcProvider;
import dev.sieve.ingest.tr.TrMasakProvider;
import dev.sieve.ingest.uk.UkHmtProvider;
import dev.sieve.ingest.un.UnConsolidatedProvider;
import dev.sieve.ingest.usfbi.FbiWantedProvider;
import dev.sieve.ingest.ustrade.BisEntityListProvider;
import dev.sieve.ingest.ustrade.BisMilitaryEndUserProvider;
import dev.sieve.ingest.ustrade.UsTradeCslProvider;
import dev.sieve.ingest.wikidata.WikidataPepProvider;
import dev.sieve.ingest.worldbank.WorldBankDebarredProvider;
import dev.sieve.ingest.za.ZaFicProvider;
import java.util.List;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Integration tests that verify real HTTP connections and parsing for each sanctions list provider.
 *
 * <p>These tests hit live government endpoints and require network access, so the build skips the
 * {@code integration} tag by default. Run them with:
 *
 * <pre>
 *   mvn test -pl sieve-ingest -am -Dgroups=integration -Dtest.excludedGroups=none
 * </pre>
 *
 * <p>Each test verifies:
 *
 * <ul>
 *   <li>HTTP connection succeeds (no timeouts, no TLS errors, correct status code)
 *   <li>Response body is non-empty and parseable
 *   <li>At least one entity is produced
 *   <li>Entities have valid primary names and correct list source
 * </ul>
 *
 * <p>Tests run in parallel since each provider fetches from an independent endpoint.
 */
@Tag("integration")
@Execution(ExecutionMode.CONCURRENT)
class ProviderIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ProviderIntegrationTest.class);

    // ── OFAC SDN ────────────────────────────────────────────────────────────

    @Test
    void ofacSdn_shouldFetchAndParseEntities() throws Exception {
        var provider = new OfacSdnProvider();
        List<SanctionedEntity> entities = provider.fetch();

        log.info("OFAC SDN: fetched {} entities", entities.size());
        assertThat(entities).isNotEmpty();
        assertCommonInvariants(entities, ListSource.OFAC_SDN);
        assertThat(entities.size()).isGreaterThan(100);
    }

    // ── OFAC Non-SDN ────────────────────────────────────────────────────────

    @Test
    void ofacNonSdn_shouldFetchAndParseEntities() throws Exception {
        var provider = new OfacNonSdnProvider();
        List<SanctionedEntity> entities = provider.fetch();

        log.info("OFAC Non-SDN: fetched {} entities", entities.size());
        assertThat(entities).isNotEmpty();
        assertCommonInvariants(entities, ListSource.OFAC_NONSDN);
    }

    // ── US Trade CSL ────────────────────────────────────────────────────────

    @Test
    void usTradeCsl_shouldFetchAndParseEntities() throws Exception {
        var provider = new UsTradeCslProvider();
        List<SanctionedEntity> entities = provider.fetch();

        log.info("US Trade CSL: fetched {} entities", entities.size());
        assertThat(entities).isNotEmpty();
        assertCommonInvariants(entities, ListSource.US_TRADE_CSL);
    }

    @Test
    void bisEntityList_shouldFetchAndParseEntities() throws Exception {
        var provider = new BisEntityListProvider();
        List<SanctionedEntity> entities = provider.fetch();

        log.info("US BIS Entity List: fetched {} entities", entities.size());
        assertThat(entities).isNotEmpty();
        assertCommonInvariants(entities, ListSource.US_BIS_ENTITY);
    }

    @Test
    void bisMilitaryEndUser_shouldFetchAndParseEntities() throws Exception {
        var provider = new BisMilitaryEndUserProvider();
        List<SanctionedEntity> entities = provider.fetch();

        log.info("US BIS MEU: fetched {} entities", entities.size());
        assertThat(entities).isNotEmpty();
        assertCommonInvariants(entities, ListSource.US_BIS_MEU);
    }

    // ── EU Consolidated ─────────────────────────────────────────────────────

    @Test
    void euConsolidated_shouldFetchAndParseEntities() throws Exception {
        var provider = new EuConsolidatedProvider();
        List<SanctionedEntity> entities = provider.fetch();

        log.info("EU Consolidated: fetched {} entities", entities.size());
        assertThat(entities).isNotEmpty();
        assertCommonInvariants(entities, ListSource.EU_CONSOLIDATED);
        assertThat(entities.size()).isGreaterThan(100);
    }

    // ── EU Sanctions Map ────────────────────────────────────────────────────

    @Test
    void euSanctionsMap_shouldFetchAndParseEntities() throws Exception {
        var provider = new EuSanctionsMapProvider();
        List<SanctionedEntity> entities = provider.fetch();

        log.info("EU Sanctions Map: fetched {} entities", entities.size());
        assertThat(entities).isNotEmpty();
        assertCommonInvariants(entities, ListSource.EU_SANCTIONS_MAP);
    }

    // ── EU Travel Bans ──────────────────────────────────────────────────────

    @Test
    void euTravelBans_shouldFetchAndParseEntities() throws Exception {
        var provider = new EuTravelBansProvider();
        List<SanctionedEntity> entities = provider.fetch();

        log.info("EU Travel Bans: fetched {} entities", entities.size());
        assertThat(entities).isNotEmpty();
        assertCommonInvariants(entities, ListSource.EU_TRAVEL_BANS);
    }

    // ── EU Journal ───────────────────────────────────────────────────────────

    @Test
    void euJournal_shouldFetchAndParseEntities() throws Exception {
        var provider = new EuJournalProvider();
        List<SanctionedEntity> entities = provider.fetch();

        log.info("EU Journal: fetched {} entities", entities.size());
        assertThat(entities).isNotEmpty();
        assertCommonInvariants(entities, ListSource.EU_JOURNAL);
    }

    // ── UN Consolidated ─────────────────────────────────────────────────────

    @Test
    void unConsolidated_shouldFetchAndParseEntities() throws Exception {
        var provider = new UnConsolidatedProvider();
        List<SanctionedEntity> entities = provider.fetch();

        log.info("UN Consolidated: fetched {} entities", entities.size());
        assertThat(entities).isNotEmpty();
        assertCommonInvariants(entities, ListSource.UN_CONSOLIDATED);
        assertThat(entities.size()).isGreaterThan(100);
    }

    // ── UK HMT ──────────────────────────────────────────────────────────────

    @Test
    void ukHmt_shouldFetchAndParseEntities() throws Exception {
        var provider = new UkHmtProvider();
        List<SanctionedEntity> entities = provider.fetch();

        log.info("UK HMT: fetched {} entities", entities.size());
        assertThat(entities).isNotEmpty();
        assertCommonInvariants(entities, ListSource.UK_HMT);
        assertThat(entities.size()).isGreaterThan(100);
    }

    // ── Canada Consolidated ─────────────────────────────────────────────────

    @Test
    void canadaConsolidated_shouldFetchAndParseEntities() throws Exception {
        var provider = new CanadaConsolidatedProvider();
        List<SanctionedEntity> entities = provider.fetch();

        log.info("Canada Consolidated: fetched {} entities", entities.size());
        assertThat(entities).isNotEmpty();
        assertCommonInvariants(entities, ListSource.CA_CONSOLIDATED);
    }

    // ── Switzerland SECO ────────────────────────────────────────────────────

    @Test
    void chSeco_shouldFetchAndParseEntities() throws Exception {
        var provider = new ChSecoProvider();
        List<SanctionedEntity> entities = provider.fetch();

        log.info("CH SECO: fetched {} entities", entities.size());
        assertThat(entities).isNotEmpty();
        assertCommonInvariants(entities, ListSource.CH_SECO);
    }

    // ── Australia DFAT ──────────────────────────────────────────────────────

    @Test
    void auDfat_shouldFetchAndParseEntities() throws Exception {
        var provider = new AuDfatProvider();
        List<SanctionedEntity> entities = provider.fetch();

        log.info("AU DFAT: fetched {} entities", entities.size());
        assertThat(entities).isNotEmpty();
        assertCommonInvariants(entities, ListSource.AU_DFAT);
    }

    // ── France Trésor ───────────────────────────────────────────────────────

    @Test
    void frTresor_shouldFetchAndParseEntities() throws Exception {
        var provider = new FrTresorProvider();
        List<SanctionedEntity> entities = provider.fetch();

        log.info("FR Trésor: fetched {} entities", entities.size());
        assertThat(entities).isNotEmpty();
        assertCommonInvariants(entities, ListSource.FR_TRESOR);
    }

    // ── Belgium FOD ─────────────────────────────────────────────────────────

    @Test
    void beFod_shouldFetchAndParseEntities() throws Exception {
        var provider = new BeFodProvider();
        List<SanctionedEntity> entities = provider.fetch();

        log.info("BE FOD: fetched {} entities", entities.size());
        assertThat(entities).isNotEmpty();
        assertCommonInvariants(entities, ListSource.BE_FOD);
    }

    // ── New Zealand Russia ──────────────────────────────────────────────────

    @Test
    void nzRussia_shouldFetchAndParseEntities() throws Exception {
        var provider = new NzRussiaProvider();
        List<SanctionedEntity> entities = provider.fetch();

        log.info("NZ Russia: fetched {} entities", entities.size());
        assertThat(entities).isNotEmpty();
        assertCommonInvariants(entities, ListSource.NZ_RUSSIA);
    }

    // ── Japan MoF ───────────────────────────────────────────────────────────

    @Test
    void jpMof_shouldFetchAndParseEntities() throws Exception {
        var provider = new JpMofProvider();
        List<SanctionedEntity> entities = provider.fetch();

        log.info("JP MoF: fetched {} entities", entities.size());
        assertThat(entities).isNotEmpty();
        assertCommonInvariants(entities, ListSource.JP_MOF);
    }

    // ── Turkey MASAK ────────────────────────────────────────────────────────

    @Test
    void trMasak_shouldFetchAndParseEntities() throws Exception {
        var provider = new TrMasakProvider();
        List<SanctionedEntity> entities = provider.fetch();

        log.info("TR MASAK: fetched {} entities", entities.size());
        assertThat(entities).isNotEmpty();
        assertCommonInvariants(entities, ListSource.TR_MASAK);
    }

    // ── Poland MSWiA ────────────────────────────────────────────────────────

    @Test
    void plMswia_shouldFetchAndParseEntities() throws Exception {
        var provider = new PlMswiaProvider();
        List<SanctionedEntity> entities = provider.fetch();

        log.info("PL MSWiA: fetched {} entities", entities.size());
        assertThat(entities).isNotEmpty();
        assertCommonInvariants(entities, ListSource.PL_MSWIA);
    }

    // ── Israel WMD/Terror ───────────────────────────────────────────────────

    @Test
    void ilWmdTerror_shouldFetchAndParseEntities() throws Exception {
        var provider = new IlWmdTerrorProvider();
        List<SanctionedEntity> entities = provider.fetch();

        log.info("IL WMD/Terror: fetched {} entities", entities.size());
        assertThat(entities).isNotEmpty();
        assertCommonInvariants(entities, ListSource.IL_WMD_TERROR);
    }

    // ── Moldova Terror ──────────────────────────────────────────────────────

    @Test
    void mdTerror_shouldFetchAndParseEntities() throws Exception {
        var provider = new MdTerrorProvider();
        List<SanctionedEntity> entities = provider.fetch();

        log.info("MD Terror: fetched {} entities", entities.size());
        assertThat(entities).isNotEmpty();
        assertCommonInvariants(entities, ListSource.MD_TERROR);
    }

    // ── Monaco Fund Freezing ────────────────────────────────────────────────

    @Test
    void mcFundFreezing_shouldFetchAndParseEntities() throws Exception {
        var provider = new McFundFreezingProvider();
        List<SanctionedEntity> entities = provider.fetch();

        log.info("MC Fund Freezing: fetched {} entities", entities.size());
        assertThat(entities).isNotEmpty();
        assertCommonInvariants(entities, ListSource.MC_FUND_FREEZING);
    }

    // ── Ukraine NSDC ────────────────────────────────────────────────────────

    @Test
    @Disabled("UA NSDC requires API key (SIEVE_NSDC_API_KEY) — excluded until key is obtained")
    void uaNsdc_shouldFetchAndParseEntities() throws Exception {
        var provider = new dev.sieve.ingest.ua.UaNsdcProvider();
        List<SanctionedEntity> entities = provider.fetch();

        log.info("UA NSDC: fetched {} entities", entities.size());
        assertThat(entities).isNotEmpty();
        assertCommonInvariants(entities, ListSource.UA_NSDC);
    }

    // ── Qatar NCTC ──────────────────────────────────────────────────────────

    @Test
    void qaNctc_shouldFetchAndParseEntities() throws Exception {
        var provider = new QaNctcProvider();
        List<SanctionedEntity> entities = provider.fetch();

        log.info("QA NCTC: fetched {} entities", entities.size());
        assertThat(entities).isNotEmpty();
        assertCommonInvariants(entities, ListSource.QA_NCTC);
        assertThat(entities.size()).isGreaterThan(100);
    }

    // ── South Africa FIC ────────────────────────────────────────────────────

    @Test
    void zaFic_shouldFetchAndParseEntities() throws Exception {
        var provider = new ZaFicProvider();
        List<SanctionedEntity> entities = provider.fetch();

        log.info("ZA FIC: fetched {} entities", entities.size());
        assertThat(entities).isNotEmpty();
        assertCommonInvariants(entities, ListSource.ZA_FIC);
    }

    // ── Latvia FIU ──────────────────────────────────────────────────────────

    @Test
    void lvFiu_shouldFetchAndParseEntities() throws Exception {
        var provider = new LvFiuProvider();
        List<SanctionedEntity> entities = provider.fetch();

        log.info("LV FIU: fetched {} entities", entities.size());
        assertThat(entities).isNotEmpty();
        assertCommonInvariants(entities, ListSource.LV_FIU);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Asserts invariants that should hold for every entity from every provider: correct list
     * source, non-null primary name with non-blank full name, non-null id.
     */
    private static void assertCommonInvariants(
            List<SanctionedEntity> entities, ListSource expectedSource) {
        for (SanctionedEntity entity : entities) {
            assertThat(entity.listSource())
                    .as("listSource for entity %s", entity.id())
                    .isEqualTo(expectedSource);
            assertThat(entity.id()).as("id must not be null").isNotNull();
            assertThat(entity.primaryName())
                    .as("primaryName for entity %s", entity.id())
                    .isNotNull();
            assertThat(entity.primaryName().fullName())
                    .as("fullName for entity %s", entity.id())
                    .isNotBlank();
        }
    }

    @Test
    void arRepet_shouldFetchAndParseEntities() throws Exception {
        var provider = new ArRepetProvider();
        List<SanctionedEntity> entities = provider.fetch();

        log.info("AR RePET: fetched {} entities", entities.size());
        assertThat(entities).isNotEmpty();
        assertCommonInvariants(entities, ListSource.AR_REPET);
    }

    @Test
    void inMha_shouldFetchAndParseEntities() throws Exception {
        var provider = new InMhaProvider();
        List<SanctionedEntity> entities = provider.fetch();

        log.info("IN MHA: fetched {} entities", entities.size());
        assertThat(entities).isNotEmpty();
        assertCommonInvariants(entities, ListSource.IN_MHA);
    }

    @Test
    void fbiWanted_shouldFetchAndParseEntities() throws Exception {
        var provider = new FbiWantedProvider();
        List<SanctionedEntity> entities = provider.fetch();

        log.info("US FBI Wanted: fetched {} entities", entities.size());
        assertThat(entities).isNotEmpty();
        assertCommonInvariants(entities, ListSource.US_FBI_WANTED);
    }

    @Test
    void euMostWanted_shouldFetchAndParseEntities() throws Exception {
        var provider = new EuMostWantedProvider();
        List<SanctionedEntity> entities = provider.fetch();

        log.info("EU Most Wanted: fetched {} entities", entities.size());
        assertThat(entities).isNotEmpty();
        assertCommonInvariants(entities, ListSource.EU_MOST_WANTED);
    }

    @Test
    void worldBankDebarred_shouldFetchAndParseEntities() throws Exception {
        var provider = new WorldBankDebarredProvider();
        List<SanctionedEntity> entities = provider.fetch();

        log.info("World Bank Debarred: fetched {} entities", entities.size());
        assertThat(entities).isNotEmpty();
        assertCommonInvariants(entities, ListSource.WB_DEBARRED);
    }

    @Test
    void wikidataPep_shouldFetchAndParseEntities() throws Exception {
        var provider = new WikidataPepProvider();
        List<SanctionedEntity> entities = provider.fetch();

        log.info("Wikidata PEPs: fetched {} entities", entities.size());
        assertThat(entities).isNotEmpty();
        assertCommonInvariants(entities, ListSource.WIKIDATA_PEP);
    }

    @Test
    void gleifStateOwned_shouldFetchAndParseEntities() throws Exception {
        var provider = new GleifStateOwnedProvider();
        List<SanctionedEntity> entities = provider.fetch();

        log.info("GLEIF state-owned: fetched {} entities", entities.size());
        assertThat(entities).isNotEmpty();
        assertCommonInvariants(entities, ListSource.GLEIF_STATE_OWNED);
    }

    @Test
    void gleifSanctionLinked_shouldFetchAndParseEntities() throws Exception {
        var provider = new GleifSanctionLinkedProvider();
        List<SanctionedEntity> entities = provider.fetch();

        log.info("GLEIF sanction-linked: fetched {} entities", entities.size());
        assertThat(entities).isNotEmpty();
        assertCommonInvariants(entities, ListSource.GLEIF_SANCTION_LINKED);
    }
}
