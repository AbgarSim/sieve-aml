package dev.sieve.benchmark.eval;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * Given names, surnames and company words bundled in {@code eval/}, combined into customer names
 * that are not taken from any list.
 */
public final class NameSamples {

    private final List<String> givenNames;
    private final List<String> surnames;
    private final List<String> companyWords;
    private final List<String> legalForms;

    private NameSamples(
            List<String> givenNames,
            List<String> surnames,
            List<String> companyWords,
            List<String> legalForms) {
        this.givenNames = givenNames;
        this.surnames = surnames;
        this.companyWords = companyWords;
        this.legalForms = legalForms;
    }

    /** Loads the bundled lists. */
    public static NameSamples bundled() {
        List<String> company = read("eval/company-words.txt");
        int marker = company.indexOf("---");
        return new NameSamples(
                read("eval/given-names.txt"),
                read("eval/surnames.txt"),
                company.subList(0, marker),
                company.subList(marker + 1, company.size()));
    }

    /** A person: given name and surname, one time in five with a second given name. */
    String person(Random random) {
        String first = pick(givenNames, random);
        String last = pick(surnames, random);
        if (random.nextInt(5) == 0) {
            return first + " " + pick(givenNames, random) + " " + last;
        }
        return first + " " + last;
    }

    /** A company: two generic words and a legal form. */
    String company(Random random) {
        String a = pick(companyWords, random);
        String b;
        do {
            b = pick(companyWords, random);
        } while (b.equals(a));
        return a + " " + b + " " + pick(legalForms, random);
    }

    private static String pick(List<String> values, Random random) {
        return values.get(random.nextInt(values.size()));
    }

    private static List<String> read(String resource) {
        try (InputStream in =
                        Objects.requireNonNull(
                                NameSamples.class.getClassLoader().getResourceAsStream(resource),
                                "missing resource " + resource);
                BufferedReader reader =
                        new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            LinkedHashSet<String> values = new LinkedHashSet<>();
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    values.add(trimmed);
                }
            }
            return new ArrayList<>(values);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
