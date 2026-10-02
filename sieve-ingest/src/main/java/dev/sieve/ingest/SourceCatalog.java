package dev.sieve.ingest;

import dev.sieve.core.model.ListSource;
import dev.sieve.ingest.SourceInfo.Format;
import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Catalog of {@link SourceInfo} for every {@link ListSource}. */
public final class SourceCatalog {

    private static final Map<ListSource, SourceInfo> CATALOG = build();

    private SourceCatalog() {}

    /**
     * Returns the facts for one list.
     *
     * @param source the list
     * @return its catalog entry
     */
    public static SourceInfo info(ListSource source) {
        Objects.requireNonNull(source, "source must not be null");
        return CATALOG.get(source);
    }

    /**
     * Returns every catalog entry in {@link ListSource} order.
     *
     * @return all entries
     */
    public static Collection<SourceInfo> all() {
        return CATALOG.values();
    }

    private static Map<ListSource, SourceInfo> build() {
        Map<ListSource, SourceInfo> map = new EnumMap<>(ListSource.class);
        put(
                map,
                ListSource.OFAC_SDN,
                "U.S. Treasury, Office of Foreign Assets Control",
                "US",
                Format.XML,
                "https://ofac.treasury.gov/specially-designated-nationals-and-blocked-persons-list-sdn-human-readable-lists");
        put(
                map,
                ListSource.OFAC_NONSDN,
                "U.S. Treasury, Office of Foreign Assets Control",
                "US",
                Format.XML,
                "https://ofac.treasury.gov/consolidated-sanctions-list-non-sdn-lists");
        put(
                map,
                ListSource.US_TRADE_CSL,
                "U.S. International Trade Administration",
                "US",
                Format.JSON,
                "https://www.trade.gov/consolidated-screening-list");
        put(
                map,
                ListSource.EU_CONSOLIDATED,
                "European Commission",
                "EU",
                Format.XML,
                "https://data.europa.eu/data/datasets/consolidated-list-of-persons-groups-and-entities-subject-to-eu-financial-sanctions");
        put(
                map,
                ListSource.EU_SANCTIONS_MAP,
                "European Union, EU Sanctions Map",
                "EU",
                Format.JSON,
                "https://www.sanctionsmap.eu/");
        put(
                map,
                ListSource.EU_TRAVEL_BANS,
                "European Commission",
                "EU",
                Format.XML,
                "https://webgate.ec.europa.eu/fsd/fsf");
        put(
                map,
                ListSource.EU_JOURNAL,
                "Official Journal of the European Union",
                "EU",
                Format.XML,
                "https://eur-lex.europa.eu/");
        put(
                map,
                ListSource.UN_CONSOLIDATED,
                "United Nations Security Council",
                "UN",
                Format.XML,
                "https://www.un.org/securitycouncil/content/un-sc-consolidated-list");
        put(
                map,
                ListSource.UK_HMT,
                "HM Treasury, Office of Financial Sanctions Implementation",
                "GB",
                Format.XML,
                "https://www.gov.uk/government/publications/financial-sanctions-consolidated-list-of-targets");
        put(
                map,
                ListSource.CA_CONSOLIDATED,
                "Global Affairs Canada",
                "CA",
                Format.XML,
                "https://www.international.gc.ca/world-monde/international_relations-relations_internationales/sanctions/consolidated-consolide.aspx");
        put(
                map,
                ListSource.CH_SECO,
                "State Secretariat for Economic Affairs (SECO)",
                "CH",
                Format.XML,
                "https://www.seco.admin.ch/seco/en/home/Aussenwirtschaftspolitik_Wirtschaftliche_Zusammenarbeit/Wirtschaftsbeziehungen/Exportkontrollen-und-Sanktionen/Sanktionen-Embargos.html");
        put(
                map,
                ListSource.AU_DFAT,
                "Department of Foreign Affairs and Trade",
                "AU",
                Format.XLSX,
                "https://www.dfat.gov.au/international-relations/security/sanctions/consolidated-list");
        put(
                map,
                ListSource.FR_TRESOR,
                "Direction générale du Trésor",
                "FR",
                Format.JSON,
                "https://gels-avoirs.dgtresor.gouv.fr/");
        put(
                map,
                ListSource.BE_FOD,
                "FPS Finance (FOD Financiën)",
                "BE",
                Format.CSV,
                "https://financien.belgium.be/nl/thesaurie/financiele-sancties/terrorisme-en-terrorismefinanciering");
        put(
                map,
                ListSource.NZ_RUSSIA,
                "Ministry of Foreign Affairs and Trade",
                "NZ",
                Format.XLSX,
                "https://www.mfat.govt.nz/en/countries-and-regions/europe/ukraine/russian-invasion-of-ukraine/sanctions/");
        put(
                map,
                ListSource.JP_MOF,
                "Ministry of Finance",
                "JP",
                Format.XLSX,
                "https://www.mof.go.jp/policy/international_policy/gaitame_kawase/gaitame/economic_sanctions/list.html");
        put(
                map,
                ListSource.TR_MASAK,
                "Financial Crimes Investigation Board (MASAK)",
                "TR",
                Format.XLSX,
                "https://masak.hmb.gov.tr/");
        put(
                map,
                ListSource.PL_MSWIA,
                "Ministry of the Interior and Administration (MSWiA)",
                "PL",
                Format.HTML,
                "https://www.gov.pl/web/mswia/lista-osob-i-podmiotow-objetych-sankcjami");
        put(
                map,
                ListSource.IL_WMD_TERROR,
                "National Bureau for Counter Terror Financing (NBCTF)",
                "IL",
                Format.XLSX,
                "https://nbctf.mod.gov.il/en");
        put(
                map,
                ListSource.MD_TERROR,
                "Intelligence and Security Service (SIS)",
                "MD",
                Format.HTML,
                "https://antiteror.sis.md/lista-terorista-xls");
        put(
                map,
                ListSource.MC_FUND_FREEZING,
                "Government of Monaco",
                "MC",
                Format.JSON,
                "https://geldefonds.gouv.mc/");
        put(
                map,
                ListSource.UA_NSDC,
                "National Security and Defense Council (NSDC)",
                "UA",
                Format.JSON,
                "https://drs.nsdc.gov.ua/");
        put(
                map,
                ListSource.QA_NCTC,
                "National Counter Terrorism Committee (NCTC)",
                "QA",
                Format.JSON,
                "https://www.moci.gov.qa/en/about-the-ministry/anti-money-laundering-and-terrorism-financing/legal-framework/unified-record-of-persons-and-entities-designated-on-sanction-list/");
        put(
                map,
                ListSource.ZA_FIC,
                "Financial Intelligence Centre (FIC)",
                "ZA",
                Format.XML,
                "https://www.fic.gov.za/");
        put(
                map,
                ListSource.LV_FIU,
                "Financial Intelligence Unit (FID)",
                "LV",
                Format.XML,
                "https://sankcijas.fid.gov.lv");
        return Collections.unmodifiableMap(map);
    }

    private static void put(
            Map<ListSource, SourceInfo> map,
            ListSource source,
            String authority,
            String jurisdiction,
            Format format,
            String homepage) {
        map.put(
                source,
                new SourceInfo(source, authority, jurisdiction, format, URI.create(homepage)));
    }
}
