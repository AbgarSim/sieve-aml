package dev.sieve.ingest.enforcement;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Finds the sanctions programs a notice says were breached, by the names authorities give them:
 * "the Iranian Transactions and Sanctions Regulations", "programs targeting Russia, Belarus, Iran",
 * "the Russia (Sanctions) (EU Exit) Regulations 2019", "violating U.S. sanctions against Iran".
 *
 * <p>A country program maps to the country it targets. Thematic programs (terrorism, narcotics,
 * proliferation, cyber, human rights) have no country. "Ukraine-related" programs target Russia and
 * the occupied regions, so they count as Russia; the old Kosovo and Yugoslavia programs count as
 * Serbia.
 */
public final class SanctionsPrograms {

    /** The programs found in a text: labels in the order first met, and their target countries. */
    public record Found(List<String> programs, List<String> targets) {

        /** Copies the lists. */
        public Found {
            programs = List.copyOf(programs);
            targets = List.copyOf(targets);
        }

        /** No program found. */
        public static final Found NONE = new Found(List.of(), List.of());

        /** Only the program met first, or none. */
        public Found first() {
            if (programs.isEmpty()) {
                return NONE;
            }
            String label = programs.get(0);
            List<String> cc =
                    PROGRAMS.stream()
                            .filter(p -> p.label().equals(label))
                            .flatMap(p -> p.country().stream())
                            .toList();
            return new Found(List.of(label), cc);
        }

        /** Whether nothing was found. */
        public boolean isEmpty() {
            return programs.isEmpty();
        }
    }

    private record Program(String label, Optional<String> country, Pattern pattern) {}

    private static final List<Program> PROGRAMS = new ArrayList<>();

    private static void country(String cc, String label, String regex) {
        PROGRAMS.add(
                new Program(
                        label, Optional.of(cc), Pattern.compile(regex, Pattern.CASE_INSENSITIVE)));
    }

    private static void theme(String label, String regex) {
        PROGRAMS.add(
                new Program(
                        label, Optional.empty(), Pattern.compile(regex, Pattern.CASE_INSENSITIVE)));
    }

    static {
        country(
                "RU",
                "Russia",
                "\\bRussia(n|'s)?\\b|Ukraine[- ]?/?[- ]?(Russia|related)|\\bUkraine \\(European Union|\\bCrimea");
        country("IR", "Iran", "\\bIran(ian|'s)?\\b");
        country("CU", "Cuba", "\\bCuba(n|'s)?\\b");
        country("SY", "Syria", "\\bSyria(n|'s)?\\b");
        country(
                "KP",
                "North Korea",
                "North Korea(n|'s)?|\\bDPRK\\b|Democratic People.s Republic of Korea");
        country("VE", "Venezuela", "\\bVenezuela(n|'s)?\\b");
        country("SS", "South Sudan", "\\bSouth Sudan(ese)?\\b");
        country("SD", "Sudan", "(?<!South )\\bSudan(ese|'s)?\\b");
        country("MM", "Burma", "\\bBurm(a|ese)\\b|\\bMyanmar\\b");
        country("BY", "Belarus", "\\bBelarus(ian)?\\b");
        country("IQ", "Iraq", "\\bIraq(i)?\\b");
        country("LY", "Libya", "\\bLibya(n)?\\b");
        country("ZW", "Zimbabwe", "\\bZimbabwe(an)?\\b");
        country("SO", "Somalia", "\\bSomali(a|an)?\\b");
        country("YE", "Yemen", "\\bYemen(i)?\\b");
        country("AF", "Afghanistan", "\\bAfghanistan\\b|\\bTaliban\\b");
        country("RS", "Serbia", "\\bYugoslavia(n)?\\b|\\bKosovo\\b|\\bSerbia\\b|Western Balkans");
        country("CI", "Côte d'Ivoire", "C[oô]te d.Ivoire|Ivory Coast");
        country("LR", "Liberia", "\\bLiberia(n)?\\b");
        country("CF", "Central African Republic", "Central African Republic");
        country("CD", "DR Congo", "Democratic Republic of (the )?Congo");
        country("NI", "Nicaragua", "\\bNicaragua(n)?\\b");
        country("ML", "Mali", "\\bMali(an)?\\b");
        country("LB", "Lebanon", "\\bLebanon\\b");
        theme("Terrorism", "terroris[mt]|\\bISIL\\b|Da.esh|Al-Qa.?ida|Hizballah|Hezbollah");
        theme("Narcotics", "narcotic|kingpin|drug traffick");
        theme("Proliferation", "weapons of mass destruction|non-?proliferation|\\bWMD\\b");
        theme("Cyber", "\\bcyber");
        theme("Human rights", "Magnitsky|human rights");
        theme("Transnational crime", "transnational criminal");
    }

    private SanctionsPrograms() {}

    /**
     * Finds the programs named in a text.
     *
     * @param text the text, e.g. the first page of a notice
     * @return the programs found, possibly none
     */
    public static Found find(String text) {
        Objects.requireNonNull(text, "text must not be null");
        Set<String> labels = new LinkedHashSet<>();
        Set<String> targets = new LinkedHashSet<>();
        record Hit(int at, Program program) {}
        List<Hit> hits = new ArrayList<>();
        for (Program p : PROGRAMS) {
            var m = p.pattern().matcher(text);
            if (m.find()) {
                hits.add(new Hit(m.start(), p));
            }
        }
        hits.sort((a, b) -> Integer.compare(a.at(), b.at()));
        for (Hit h : hits) {
            labels.add(h.program().label());
            h.program().country().ifPresent(targets::add);
        }
        return new Found(new ArrayList<>(labels), new ArrayList<>(targets));
    }
}
