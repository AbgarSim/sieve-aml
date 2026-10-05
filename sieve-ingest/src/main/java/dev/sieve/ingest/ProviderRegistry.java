package dev.sieve.ingest;

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
import dev.sieve.ingest.in.InMhaOrgProvider;
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
import dev.sieve.ingest.ua.UaNsdcProvider;
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

/** Creates one provider per {@link dev.sieve.core.model.ListSource} with default settings. */
public final class ProviderRegistry {

    private ProviderRegistry() {}

    /**
     * Creates every provider with its default URL and HTTP client.
     *
     * <p>The UA NSDC provider reads its API key from {@code SIEVE_NSDC_API_KEY} and fails at fetch
     * time when the key is missing.
     *
     * @return one provider per list source, in {@link dev.sieve.core.model.ListSource} order
     */
    public static List<ListProvider> defaults() {
        return List.of(
                new OfacSdnProvider(),
                new OfacNonSdnProvider(),
                new UsTradeCslProvider(),
                new BisEntityListProvider(),
                new BisMilitaryEndUserProvider(),
                new EuConsolidatedProvider(),
                new EuSanctionsMapProvider(),
                new EuTravelBansProvider(),
                new EuJournalProvider(),
                new UnConsolidatedProvider(),
                new UkHmtProvider(),
                new CanadaConsolidatedProvider(),
                new ChSecoProvider(),
                new AuDfatProvider(),
                new FrTresorProvider(),
                new BeFodProvider(),
                new NzRussiaProvider(),
                new JpMofProvider(),
                new TrMasakProvider(),
                new PlMswiaProvider(),
                new IlWmdTerrorProvider(),
                new MdTerrorProvider(),
                new McFundFreezingProvider(),
                new UaNsdcProvider(),
                new QaNctcProvider(),
                new ZaFicProvider(),
                new LvFiuProvider(),
                new ArRepetProvider(),
                new InMhaProvider(),
                new InMhaOrgProvider(),
                new FbiWantedProvider(),
                new EuMostWantedProvider(),
                new WorldBankDebarredProvider(),
                new WikidataPepProvider(),
                new GleifStateOwnedProvider(),
                new GleifSanctionLinkedProvider());
    }
}
