package dev.sieve.ingest.media;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The GDELT GKG themes that make an article adverse, each with the plain words shown to analysts.
 *
 * <p>Chosen from the themes seen in a sample of the feed. Broad themes that also tag ordinary news
 * are left out on purpose: trials and criminal justice (they tag judges and lawyers), terror-group
 * names (GDELT tags mainstream parties with them), seizures (power is seized too) and
 * anti-corruption policy.
 */
final class AdverseThemes {

    private static final Map<String, String> THEMES =
            Map.ofEntries(
                    Map.entry("ECON_MONEYLAUNDERING", "money laundering"),
                    Map.entry("WB_2076_MONEY_LAUNDERING", "money laundering"),
                    Map.entry("TAX_FNCACT_LAUNDERER", "money laundering"),
                    Map.entry("CORRUPTION", "corruption"),
                    Map.entry("WB_2020_BRIBERY_FRAUD_AND_COLLUSION", "bribery or fraud"),
                    Map.entry("ELECTION_FRAUD", "fraud"),
                    Map.entry("ECON_IDENTITYTHEFT", "fraud"),
                    Map.entry("WB_2457_CYBER_CRIME", "cybercrime"),
                    Map.entry("SANCTIONS", "sanctions"),
                    Map.entry("TERROR", "terrorism"),
                    Map.entry("WB_2467_TERRORISM", "terrorism"),
                    Map.entry("ARREST", "arrest"),
                    Map.entry("SOC_GENERALCRIME", "crime"),
                    Map.entry("TAX_FNCACT_CRIMINAL", "crime"),
                    Map.entry("TAX_FNCACT_CRIMINALS", "crime"),
                    Map.entry("CRIME_COMMON_ROBBERY", "crime"),
                    Map.entry("ORGANIZED_CRIME", "organised crime"),
                    Map.entry("WB_2453_ORGANIZED_CRIME", "organised crime"),
                    Map.entry("WB_2455_CRIME_NETWORKS", "organised crime"),
                    Map.entry("CRIME_CARTELS", "organised crime"),
                    Map.entry("WB_2446_CARTELS", "organised crime"),
                    Map.entry("DRUG_TRADE", "drug trafficking"),
                    Map.entry("CRIME_ILLEGAL_DRUGS", "drug trafficking"),
                    Map.entry("HUMAN_TRAFFICKING", "human trafficking"),
                    Map.entry("WB_2458_HUMAN_TRAFFICKING", "human trafficking"),
                    Map.entry("SMUGGLING", "smuggling"),
                    Map.entry("TAX_FNCACT_SMUGGLER", "smuggling"),
                    Map.entry("BLACK_MARKET", "black market"),
                    Map.entry("WB_2506_ARMS_TRADE", "arms trade"),
                    Map.entry("MIL_SELF_IDENTIFIED_ARMS_DEAL", "arms trade"),
                    Map.entry("KIDNAP", "kidnapping"),
                    Map.entry("MARITIME_PIRACY", "piracy"),
                    Map.entry("WB_2510_WAR_CRIMES", "war crimes"),
                    Map.entry("WB_2513_CRIMES_AGAINST_HUMANITY", "war crimes"),
                    Map.entry("WB_2915_ENVIRONMENTAL_CRIME", "environmental crime"),
                    Map.entry("SCANDAL", "scandal"));

    private AdverseThemes() {}

    /**
     * Returns the plain words for the adverse themes among the given GKG themes, without
     * duplicates, in the order first met.
     *
     * @param themes GKG theme codes
     * @return the adverse terms, empty when the article is not adverse
     */
    static List<String> termsFor(Iterable<String> themes) {
        List<String> terms = new ArrayList<>();
        for (String theme : themes) {
            String term = THEMES.get(theme);
            if (term != null && !terms.contains(term)) {
                terms.add(term);
            }
        }
        return terms;
    }
}
