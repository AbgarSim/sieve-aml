package dev.sieve.ingest.media;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sieve.core.media.MediaArticle;
import dev.sieve.core.media.NewsMention;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class GdeltGkgParserTest {

    /** Builds a 27-column GKG line with the given fields and empty others. */
    static String line(
            String date,
            String collection,
            String url,
            String themes,
            String persons,
            String orgs,
            String translation,
            String title) {
        String[] f = new String[27];
        java.util.Arrays.fill(f, "");
        f[0] = date + "-1";
        f[1] = date;
        f[2] = collection;
        f[3] = url.replaceAll("https?://([^/]+).*", "$1");
        f[4] = url;
        f[7] = "";
        f[8] = themes;
        f[12] = persons;
        f[14] = orgs;
        f[25] = translation;
        f[26] = "<PAGE_TITLE>" + title + "</PAGE_TITLE>";
        return String.join("\t", f);
    }

    @Test
    void shouldKeepAdverseArticlesThatNameSomeone() throws IOException {
        String text =
                String.join(
                        "\n",
                        line(
                                "20261005120000",
                                "1",
                                "https://news.example/a",
                                "TAX_FNCACT_PRESIDENT,10;ECON_MONEYLAUNDERING,40;ARREST,90;ARREST,120",
                                "John Doe,12;John Doe,300;Jane Roe,400",
                                "Acme Bank,50",
                                "",
                                "Banker held over money laundering &amp; fraud"),
                        line(
                                "20261005120000",
                                "1",
                                "https://news.example/b",
                                "TAX_FNCACT_PRESIDENT,10;WB_1014_CRIMINAL_JUSTICE,20",
                                "John Doe,12",
                                "",
                                "",
                                "Not adverse"),
                        line(
                                "20261005120000",
                                "1",
                                "https://news.example/c",
                                "CORRUPTION,10",
                                "",
                                "",
                                "",
                                "Adverse but names nobody"),
                        line(
                                "20261005120000",
                                "2",
                                "Some Citation",
                                "CORRUPTION,10",
                                "John Doe,1",
                                "",
                                "",
                                "Not from the web"),
                        line(
                                "20261005121500",
                                "1",
                                "https://nachrichten.example/d",
                                "WB_2020_BRIBERY_FRAUD_AND_COLLUSION,5",
                                "Hans Muster,7",
                                "",
                                "srclc:deu;eng:GT-DEU 1.0",
                                "Bestechung &#x2013; Gericht &#252;berpr&#xFC;ft"),
                        "short\tline");

        List<NewsMention> mentions =
                GdeltGkgParser.parse(new BufferedReader(new StringReader(text)));

        assertThat(mentions).hasSize(2);
        NewsMention first = mentions.get(0);
        MediaArticle article = first.article();
        assertThat(article.url()).isEqualTo("https://news.example/a");
        assertThat(article.title()).isEqualTo("Banker held over money laundering & fraud");
        assertThat(article.domain()).isEqualTo("news.example");
        assertThat(article.language()).isEqualTo("English");
        assertThat(article.seenAt()).isEqualTo(Instant.parse("2026-10-05T12:00:00Z"));
        assertThat(article.adverseTerms()).containsExactly("money laundering", "arrest");
        assertThat(first.persons()).containsExactly("John Doe", "Jane Roe");
        assertThat(first.organisations()).containsExactly("Acme Bank");
        assertThat(mentions.get(1).article().language()).isEqualTo("German");
        assertThat(mentions.get(1).article().title()).isEqualTo("Bestechung – Gericht überprüft");
        assertThat(mentions.get(1).article().adverseTerms()).containsExactly("bribery or fraud");
    }
}
