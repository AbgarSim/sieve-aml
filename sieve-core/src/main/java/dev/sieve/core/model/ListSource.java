package dev.sieve.core.model;

import java.util.Objects;

/**
 * Identifies the origin sanctions list for a {@link SanctionedEntity}.
 *
 * <p>Each value corresponds to a publicly available sanctions list maintained by a government or
 * international body.
 */
public enum ListSource {

    /** U.S. Office of Foreign Assets Control — Specially Designated Nationals list. */
    OFAC_SDN("OFAC SDN"),

    /** U.S. Office of Foreign Assets Control — Non-SDN lists (consolidated). */
    OFAC_NONSDN("OFAC Non-SDN"),

    /** U.S. Trade Consolidated Screening List. */
    US_TRADE_CSL("US Trade CSL"),

    /** U.S. Bureau of Industry and Security Entity List. */
    US_BIS_ENTITY("US BIS Entity List"),

    /** U.S. Bureau of Industry and Security Military End-User List. */
    US_BIS_MEU("US BIS MEU"),

    /** European Union Consolidated List of sanctions targets. */
    EU_CONSOLIDATED("EU Consolidated"),

    /** EU Sanctions Map — additional designations not in the consolidated list. */
    EU_SANCTIONS_MAP("EU Sanctions Map"),

    /** EU Travel Bans list. */
    EU_TRAVEL_BANS("EU Travel Bans"),

    /** EU Official Journal designations. */
    EU_JOURNAL("EU Journal"),

    /** United Nations Security Council Consolidated List. */
    UN_CONSOLIDATED("UN Consolidated"),

    /** UK HM Treasury sanctions list. */
    UK_HMT("UK HMT"),

    /** Canada consolidated sanctions list (SEMA, FACFOA, Terrorists). */
    CA_CONSOLIDATED("Canada Consolidated"),

    /** Switzerland SECO sanctions list. */
    CH_SECO("CH SECO"),

    /** Australia DFAT consolidated sanctions list. */
    AU_DFAT("AU DFAT"),

    /** France Trésor — Direction Générale du Trésor sanctions list. */
    FR_TRESOR("FR Trésor"),

    /** Belgium FOD/SPF sanctions list. */
    BE_FOD("BE FOD"),

    /** New Zealand Russia sanctions list. */
    NZ_RUSSIA("NZ Russia"),

    /** Japan Ministry of Finance sanctions list. */
    JP_MOF("JP MoF"),

    /** Turkey MASAK (Financial Crimes Investigation Board) sanctions list. */
    TR_MASAK("TR MASAK"),

    /** Poland MSWiA (Ministry of Interior) sanctions list. */
    PL_MSWIA("PL MSWiA"),

    /** Israel WMD and Terror sanctions list. */
    IL_WMD_TERROR("IL WMD/Terror"),

    /** Moldova national terrorism sanctions list. */
    MD_TERROR("MD Terror"),

    /** Monaco fund freezing sanctions list. */
    MC_FUND_FREEZING("MC Fund Freezing"),

    /** Ukraine NSDC (National Security and Defence Council) sanctions list. */
    UA_NSDC("UA NSDC"),

    /** Qatar NCTC (National Counter Terrorism Committee) sanctions list. */
    QA_NCTC("QA NCTC"),

    /** South Africa FIC (Financial Intelligence Centre) targeted financial sanctions. */
    ZA_FIC("ZA FIC"),

    /** Latvia FIU (Finanšu izlūkošanas dienests) national sanctions. */
    LV_FIU("LV FIU"),

    /** Argentina RePET (Registro Público de Personas y Entidades vinculadas a actos de Terrorismo). */
    AR_REPET("AR RePET"),

    /** India Ministry of Home Affairs individual terrorists under UAPA. */
    IN_MHA("IN MHA");

    private final String displayName;

    ListSource(String displayName) {
        this.displayName = displayName;
    }

    /**
     * Returns the human-readable display name for this list source.
     *
     * @return the display name, never {@code null}
     */
    public String displayName() {
        return displayName;
    }

    /**
     * Resolves a {@link ListSource} from a case-insensitive string value.
     *
     * <p>Accepts the enum constant name (e.g., {@code "OFAC_SDN"}), the display name (e.g., {@code
     * "OFAC SDN"}), or a hyphenated form (e.g., {@code "ofac-sdn"}).
     *
     * @param value the string to parse, must not be {@code null}
     * @return the matching {@link ListSource}
     * @throws IllegalArgumentException if no matching source is found
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public static ListSource fromString(String value) {
        Objects.requireNonNull(value, "ListSource value must not be null");
        String normalized = value.strip().toUpperCase().replace('-', '_');
        for (ListSource source : values()) {
            if (source.name().equals(normalized)
                    || source.displayName.equalsIgnoreCase(value.strip())) {
                return source;
            }
        }
        throw new IllegalArgumentException("Unknown ListSource: " + value);
    }
}
