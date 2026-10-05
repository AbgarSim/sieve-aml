package dev.sieve.core.model;

/**
 * What a list says about a vessel beyond its names and identifiers.
 *
 * <p>Blank strings are stored as {@code null}. The flag is kept as the list writes it (a country
 * name or code); callers normalise it like a nationality.
 *
 * @param flag the flag state, as the list writes it, may be {@code null}
 * @param type the vessel type, such as "Bulk Carrier", may be {@code null}
 * @param callSign the radio call sign, may be {@code null}
 * @param tonnage the tonnage the list gives without saying which measure, may be {@code null}
 * @param grossRegisteredTonnage the gross registered tonnage, may be {@code null}
 */
public record VesselDetails(
        String flag,
        String type,
        String callSign,
        Integer tonnage,
        Integer grossRegisteredTonnage) {

    public VesselDetails {
        flag = blankToNull(flag);
        type = blankToNull(type);
        callSign = blankToNull(callSign);
    }

    /**
     * Builds the details, or nothing when the list gives none of them.
     *
     * @return the details, or {@code null} when every field is empty
     */
    public static VesselDetails of(
            String flag,
            String type,
            String callSign,
            Integer tonnage,
            Integer grossRegisteredTonnage) {
        VesselDetails details =
                new VesselDetails(flag, type, callSign, tonnage, grossRegisteredTonnage);
        return details.isEmpty() ? null : details;
    }

    /**
     * Reads a tonnage figure as lists write it, with thousands separators or a unit: "52,000",
     * "5.100 t".
     *
     * @param text the list's value, may be {@code null}
     * @return the figure, or {@code null} when the text holds no number of a plausible size
     */
    public static Integer tons(String text) {
        if (text == null) {
            return null;
        }
        String digits = text.replaceAll("[^0-9]", "");
        if (digits.isEmpty() || digits.length() > 9) {
            return null;
        }
        return Integer.valueOf(digits);
    }

    /** Whether no field is set. */
    public boolean isEmpty() {
        return flag == null
                && type == null
                && callSign == null
                && tonnage == null
                && grossRegisteredTonnage == null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
