package dev.sieve.ingest.enforcement;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A news article that reports a suspected sanctions breach: evasion, a violation, smuggling to a
 * sanctioned country. Unverified: it is what the article says, not what an authority found.
 *
 * @param url the article
 * @param title its title
 * @param domain the site
 * @param seenAt when the news index saw it
 * @param programs the sanctions programs its title names
 * @param targets the countries those programs target
 */
public record SuspectedBreach(
        String url,
        String title,
        String domain,
        Instant seenAt,
        List<String> programs,
        List<String> targets) {

    private static final Pattern SANCTIONS = Pattern.compile("(?i)\\bsanction");
    private static final Pattern BREACH =
            Pattern.compile(
                    "(?i)\\b(evad|evasion|evasive|dodg|bust|violat|breach|circumvent|smuggl|shadow fleet|dark fleet|skirt|flout|loophole|front compan|charged|indicted|fined|penalt|seiz|arrest|guilty|probe|investigat)");

    /** Validates and copies. */
    public SuspectedBreach {
        Objects.requireNonNull(url, "url must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(domain, "domain must not be null");
        Objects.requireNonNull(seenAt, "seenAt must not be null");
        programs = List.copyOf(programs);
        targets = List.copyOf(targets);
    }

    /**
     * Whether a title reports a breach of sanctions: it names sanctions and an evasion, violation
     * or enforcement step.
     *
     * @param title the article's title
     * @return whether it does
     */
    public static boolean reportsBreach(String title) {
        return SANCTIONS.matcher(title).find() && BREACH.matcher(title).find();
    }

    /**
     * An article from its title, with the programs the title names.
     *
     * @param url the article
     * @param title its title
     * @param domain the site
     * @param seenAt when it was seen
     * @return the article
     */
    public static SuspectedBreach of(String url, String title, String domain, Instant seenAt) {
        SanctionsPrograms.Found found = SanctionsPrograms.find(title);
        return new SuspectedBreach(url, title, domain, seenAt, found.programs(), found.targets());
    }
}
