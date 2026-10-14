package dev.sieve.ingest.enforcement;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small helpers for the HTML the enforcement pages use. */
final class Html {

    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern ENTITY = Pattern.compile("&(#x?[0-9a-fA-F]+|[a-z]+);");
    private static final Pattern SPACE = Pattern.compile("\\s+");

    private Html() {}

    /** The text of an HTML fragment: tags removed, entities decoded, whitespace collapsed. */
    static String text(String html) {
        String s = TAG.matcher(html).replaceAll(" ");
        Matcher m = ENTITY.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(decode(m.group(1))));
        }
        m.appendTail(out);
        return SPACE.matcher(out.toString().replace(' ', ' ')).replaceAll(" ").strip();
    }

    private static String decode(String e) {
        if (e.startsWith("#x") || e.startsWith("#X")) {
            return new String(Character.toChars(Integer.parseInt(e.substring(2), 16)));
        }
        if (e.startsWith("#")) {
            return new String(Character.toChars(Integer.parseInt(e.substring(1))));
        }
        return switch (e) {
            case "amp" -> "&";
            case "lt" -> "<";
            case "gt" -> ">";
            case "quot" -> "\"";
            case "apos" -> "'";
            case "nbsp" -> " ";
            case "pound" -> "£";
            default -> "&" + e + ";";
        };
    }

    /** A lower-case ASCII slug of a name, at most 40 characters. */
    static String slug(String name) {
        String s =
                Normalizer.normalize(name, Normalizer.Form.NFD)
                        .replaceAll("\\p{M}", "")
                        .toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9]+", "-")
                        .replaceAll("(^-|-$)", "");
        if (s.length() > 40) {
            s = s.substring(0, 40).replaceAll("-[^-]*$", "");
        }
        return s.isEmpty() ? "x" : s;
    }

    /** The digits of an amount such as "11,662,930" or "£30,000.00", rounded down. */
    static java.util.Optional<Long> amount(String s) {
        Matcher m = Pattern.compile("(\\d[\\d,]*)(\\.\\d+)?").matcher(s);
        if (!m.find()) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(Long.parseLong(m.group(1).replace(",", "")));
        } catch (NumberFormatException e) {
            return java.util.Optional.empty();
        }
    }
}
