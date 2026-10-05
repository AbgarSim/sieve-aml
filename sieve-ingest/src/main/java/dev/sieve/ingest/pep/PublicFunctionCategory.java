package dev.sieve.ingest.pep;

import java.util.Locale;
import java.util.Optional;

/**
 * The categories of prominent public function in Article 3(9) of Directive (EU) 2015/849, the EU
 * anti-money-laundering directive, which defines a politically exposed person as someone entrusted
 * with one of them.
 */
public enum PublicFunctionCategory {
    /** Point (a). */
    HEADS_OF_STATE_AND_GOVERNMENT(
            'a',
            "heads of State, heads of government, ministers and deputy or assistant ministers"),
    /** Point (b). */
    LEGISLATORS('b', "members of parliament or of similar legislative bodies"),
    /** Point (c). */
    PARTY_GOVERNING_BODIES('c', "members of the governing bodies of political parties"),
    /** Point (d). */
    HIGH_COURTS(
            'd',
            "members of supreme courts, of constitutional courts or of other high-level judicial"
                    + " bodies"),
    /** Point (e). */
    AUDITORS_AND_CENTRAL_BANKS(
            'e', "members of courts of auditors or of the boards of central banks"),
    /** Point (f). */
    DIPLOMATS_AND_ARMED_FORCES(
            'f', "ambassadors, chargés d'affaires and high-ranking officers in the armed forces"),
    /** Point (g). */
    STATE_OWNED_ENTERPRISES(
            'g',
            "members of the administrative, management or supervisory bodies of State-owned"
                    + " enterprises"),
    /** Point (h). */
    INTERNATIONAL_ORGANISATIONS(
            'h',
            "directors, deputy directors and members of the board or equivalent function of an"
                    + " international organisation");

    private final char point;
    private final String description;

    PublicFunctionCategory(char point, String description) {
        this.point = point;
        this.description = description;
    }

    /** The point of Article 3(9) that defines the category: {@code a} to {@code h}. */
    public char point() {
        return point;
    }

    /** The directive's wording for the category. */
    public String description() {
        return description;
    }

    /** The category's citation, such as {@code Directive (EU) 2015/849 Art. 3(9)(a)}. */
    public String citation() {
        return "Directive (EU) 2015/849 Art. 3(9)(" + point + ")";
    }

    /**
     * Returns the category for a point letter, in either case, or empty when the letter is not one.
     *
     * @param point the point letter, {@code a} to {@code h}
     * @return the category
     */
    public static Optional<PublicFunctionCategory> fromPoint(String point) {
        if (point == null || point.strip().length() != 1) {
            return Optional.empty();
        }
        char letter = point.strip().toLowerCase(Locale.ROOT).charAt(0);
        for (PublicFunctionCategory category : values()) {
            if (category.point == letter) {
                return Optional.of(category);
            }
        }
        return Optional.empty();
    }
}
