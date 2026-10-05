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
                ListSource.US_BIS_ENTITY,
                "U.S. Department of Commerce, Bureau of Industry and Security",
                "US",
                Format.JSON,
                "https://www.bis.gov/regulations/ear/744#supplement-4-744");
        put(
                map,
                ListSource.US_BIS_MEU,
                "U.S. Department of Commerce, Bureau of Industry and Security",
                "US",
                Format.JSON,
                "https://www.bis.gov/regulations/ear/744#supplement-7-744");
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
        put(
                map,
                ListSource.AR_REPET,
                "Ministry of Justice (RePET)",
                "AR",
                Format.JSON,
                "https://repet.jus.gob.ar/");
        put(
                map,
                ListSource.IN_MHA,
                "Ministry of Home Affairs",
                "IN",
                Format.HTML,
                "https://www.mha.gov.in/en/page/individual-terrorists-under-uapa");
        put(
                map,
                ListSource.IN_MHA_ORG,
                "Ministry of Home Affairs",
                "IN",
                Format.PDF,
                "https://www.mha.gov.in/en/divisionofmha/counter-terrorism-and-counter-radicalization-division/Banned-Organizations");
        put(
                map,
                ListSource.US_FBI_WANTED,
                "U.S. Federal Bureau of Investigation",
                "US",
                Format.JSON,
                "https://www.fbi.gov/wanted");
        put(
                map,
                ListSource.EU_MOST_WANTED,
                "Europol (ENFAST)",
                "EU",
                Format.HTML,
                "https://eumostwanted.eu/");
        put(
                map,
                ListSource.WB_DEBARRED,
                "World Bank Group",
                "WB",
                Format.JSON,
                "https://www.worldbank.org/en/projects-operations/procurement/debarred-firms");
        put(
                map,
                ListSource.WIKIDATA_PEP,
                "Wikidata",
                "WD",
                Format.JSON,
                "https://www.wikidata.org/");
        put(
                map,
                ListSource.GLEIF_STATE_OWNED,
                "Global Legal Entity Identifier Foundation",
                "LE",
                Format.JSON,
                "https://www.gleif.org/en/lei-data/gleif-golden-copy");
        put(
                map,
                ListSource.GLEIF_SANCTION_LINKED,
                "Global Legal Entity Identifier Foundation",
                "LE",
                Format.JSON,
                "https://www.gleif.org/en/lei-data/gleif-golden-copy");
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
                new SourceInfo(
                        source,
                        authority,
                        jurisdiction,
                        format,
                        URI.create(homepage),
                        description(source)));
    }

    /**
     * Describes what a list is and who is on it, for the dashboard's source pages and entity
     * profiles. The switch has no default, so a new list does not compile without a description.
     */
    private static String description(ListSource source) {
        return switch (source) {
            case OFAC_SDN ->
                    "The Specially Designated Nationals and Blocked Persons List of the U.S. "
                            + "Treasury. U.S. persons must block the property of those listed and may not deal "
                            + "with them; it covers country programs such as Russia, Iran and North Korea as "
                            + "well as terrorism, narcotics and cyber programs.";
            case OFAC_NONSDN ->
                    "OFAC's consolidated list of sanctions that are not full blocking: sectoral "
                            + "sanctions on Russian companies, the Chinese military-industrial companies list, "
                            + "foreign sanctions evaders and similar menu-based measures.";
            case US_TRADE_CSL ->
                    "The U.S. government's Consolidated Screening List, which merges the export "
                            + "restriction lists of the Departments of Commerce, State and the Treasury, such "
                            + "as the Entity List, the Denied Persons List and the Nonproliferation Sanctions "
                            + "list, into one file.";
            case US_BIS_ENTITY ->
                    "The Entity List of the U.S. Bureau of Industry and Security: foreign companies, "
                            + "research institutes and people that need a licence to receive items subject to "
                            + "U.S. export controls, usually over national security or weapons proliferation "
                            + "concerns.";
            case US_BIS_MEU ->
                    "The Military End-User List of the U.S. Bureau of Industry and Security: foreign "
                            + "parties judged to be military end users, for whom exports of listed items need a"
                            + " licence.";
            case EU_CONSOLIDATED ->
                    "The European Union's consolidated list of persons, groups and entities subject "
                            + "to financial sanctions, kept by the European Commission. Member states must "
                            + "freeze their funds and economic resources.";
            case EU_SANCTIONS_MAP ->
                    "Listings from the EU Sanctions Map that the consolidated financial sanctions "
                            + "list does not carry, such as people under an EU travel ban only.";
            case EU_TRAVEL_BANS ->
                    "The EU list of people banned from entering or transiting the territory of the "
                            + "member states, published with the EU financial sanctions files.";
            case EU_JOURNAL ->
                    "Sanctions designations as published in the Official Journal of the European "
                            + "Union, the legal acts that put a listing in force.";
            case UN_CONSOLIDATED ->
                    "The United Nations Security Council Consolidated List: every person and entity "
                            + "under a measure of a Security Council sanctions committee, such as the ISIL "
                            + "(Da'esh) and Al-Qaida, Taliban, DPRK and Libya regimes. All UN member states "
                            + "must apply it.";
            case UK_HMT ->
                    "The UK consolidated list of financial sanctions targets, kept by HM Treasury's "
                            + "Office of Financial Sanctions Implementation. It lists those subject to asset "
                            + "freezes under UK sanctions regulations, with the UK's statement of reasons.";
            case CA_CONSOLIDATED ->
                    "Canada's Consolidated Canadian Autonomous Sanctions List, kept by Global Affairs"
                            + " Canada, of people and entities listed under the Special Economic Measures Act "
                            + "and the Justice for Victims of Corrupt Foreign Officials Act.";
            case CH_SECO ->
                    "The Swiss sanctions list kept by the State Secretariat for Economic Affairs "
                            + "(SECO), covering the people, companies and organisations under Swiss sanctions "
                            + "ordinances, most of them adopted from EU and UN measures.";
            case AU_DFAT ->
                    "Australia's Consolidated List kept by the Department of Foreign Affairs and "
                            + "Trade: everyone subject to targeted financial sanctions or travel bans under "
                            + "Australian sanctions laws, UN and autonomous regimes alike.";
            case FR_TRESOR ->
                    "The French national register of asset freezes, kept by the Direction générale du"
                            + " Trésor. It lists everyone whose assets are frozen in France under national, EU "
                            + "and UN measures.";
            case BE_FOD ->
                    "The Belgian Federal Public Service Finance list of financial sanctions, "
                            + "including the national list of persons and entities linked to terrorism.";
            case NZ_RUSSIA ->
                    "New Zealand's sanctions register under the Russia Sanctions Act 2022, kept by "
                            + "the Ministry of Foreign Affairs and Trade: the people, companies and assets "
                            + "sanctioned over Russia's invasion of Ukraine.";
            case JP_MOF ->
                    "Japan's list of people and entities subject to asset freezes under the Foreign "
                            + "Exchange and Foreign Trade Act, published by the Ministry of Finance.";
            case TR_MASAK ->
                    "Türkiye's asset-freezing decisions published by the Financial Crimes "
                            + "Investigation Board (MASAK) under its law on preventing the financing of "
                            + "terrorism, including designations made at the request of other states and "
                            + "Türkiye's own.";
            case PL_MSWIA ->
                    "Poland's national sanctions list kept by the Ministry of the Interior and "
                            + "Administration, of people and companies supporting Russia's aggression against "
                            + "Ukraine.";
            case IL_WMD_TERROR ->
                    "Israel's lists of terrorist organisations, terror operatives and people involved"
                            + " in weapons of mass destruction proliferation, published by the National Bureau "
                            + "for Counter Terror Financing.";
            case MD_TERROR ->
                    "Moldova's list of people and entities involved in terrorist activity, kept by "
                            + "the Intelligence and Security Service.";
            case MC_FUND_FREEZING ->
                    "Monaco's national list of people and entities whose funds are frozen, which "
                            + "follows the EU designations and adds national ones.";
            case UA_NSDC ->
                    "Ukraine's sanctions register of the National Security and Defence Council: "
                            + "people and companies sanctioned by presidential decree, most of them over "
                            + "Russia's war against Ukraine.";
            case QA_NCTC ->
                    "Qatar's unified record of persons and entities designated by the National "
                            + "Counter Terrorism Committee, with UN and national targeted financial sanctions.";
            case ZA_FIC ->
                    "South Africa's targeted financial sanctions list published by the Financial "
                            + "Intelligence Centre, which gives effect to UN Security Council sanctions.";
            case LV_FIU ->
                    "Latvia's national sanctions list, kept by the Financial Intelligence Unit, of "
                            + "people and entities Latvia sanctions in its own right.";
            case AR_REPET ->
                    "Argentina's Public Registry of Persons and Entities linked to Terrorism (RePET),"
                            + " kept by the Ministry of Justice. It carries the UN Al-Qaida and Taliban "
                            + "designations and Argentina's own listings, including people wanted over the 1994"
                            + " AMIA bombing.";
            case IN_MHA ->
                    "The people India designates as terrorists under the Unlawful Activities "
                            + "(Prevention) Act, 1967, published by the Ministry of Home Affairs.";
            case IN_MHA_ORG ->
                    "The organisations India bans under the Unlawful Activities (Prevention) Act, "
                            + "1967: terrorist organisations in the Act's First Schedule and associations "
                            + "declared unlawful, published by the Ministry of Home Affairs.";
            case US_FBI_WANTED ->
                    "The FBI's wanted list: fugitives, people sought for questioning and missing "
                            + "persons the Bureau publishes notices for.";
            case EU_MOST_WANTED ->
                    "Europe's most wanted fugitives, published by the European Network of Fugitive "
                            + "Active Search Teams (ENFAST) with Europol: people wanted by EU member states for"
                            + " serious crimes.";
            case WB_DEBARRED ->
                    "Firms and individuals the World Bank Group has debarred, making them ineligible "
                            + "for World Bank-financed contracts because of fraud, corruption or other "
                            + "sanctionable practices.";
            case WIKIDATA_PEP ->
                    "Politically exposed persons drawn from Wikidata: people who hold or have held a "
                            + "prominent public function, such as heads of state, ministers, members of "
                            + "parliament, senior judges and central bank governors.";
            case GLEIF_STATE_OWNED ->
                    "Companies whose direct or ultimate parent in the Global LEI register is a "
                            + "government entity, together with those government owners, from the Global Legal "
                            + "Entity Identifier Foundation's daily data.";
            case GLEIF_SANCTION_LINKED ->
                    "Companies that are not listed themselves but whose accounts a sanctioned party "
                            + "consolidates, directly or through other companies, according to the Global LEI "
                            + "register's parent relationships.";
        };
    }
}
