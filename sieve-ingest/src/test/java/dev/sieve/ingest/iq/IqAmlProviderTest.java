package dev.sieve.ingest.iq;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.ScriptType;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class IqAmlProviderTest {

    @Test
    void shouldReadEveryPageAndLeaveOutRemovedRows() throws Exception {
        List<SanctionedEntity> entities = parse();

        assertThat(entities)
                .extracting(SanctionedEntity::id)
                .containsExactly(
                        "iq-08df284b-5fdc-407d-845d-9320825576a6",
                        "iq-08df1e19-f8a0-40f3-8c10-1a7390fd1424",
                        "iq-11111111-2222-3333-4444-555555555555");
        assertThat(entities)
                .allSatisfy(e -> assertThat(e.listSource()).isEqualTo(ListSource.IQ_AML));
        assertThat(entities.get(2).entityType()).isEqualTo(EntityType.ORGANIZATION);
    }

    @Test
    void shouldMapPersonDetails() throws Exception {
        SanctionedEntity person = parse().get(1);

        assertThat(person.entityType()).isEqualTo(EntityType.INDIVIDUAL);
        assertThat(person.primaryName().script()).isEqualTo(ScriptType.ARABIC);
        assertThat(person.aliases())
                .extracting(NameInfo::fullName)
                .containsExactly("ابو علي", "ابو احمد");
        assertThat(person.datesOfBirth()).containsExactly(LocalDate.of(1993, 1, 1));
        assertThat(person.remarks())
                .contains(
                        "Listed by decision 54 of 2026",
                        "Born in 1993",
                        "Mother's name: اسراء ناصر خليفة");
        assertThat(person.listedDate()).isEqualTo(Instant.parse("2026-09-29T14:08:20.416006Z"));
    }

    @Test
    void shouldLeaveTheBirthDateEmptyWithoutAYear() throws Exception {
        assertThat(parse().get(0).datesOfBirth()).isEmpty();
    }

    private static List<SanctionedEntity> parse() throws Exception {
        return new IqAmlProvider(URI.create("http://localhost/"), HttpClient.newHttpClient())
                .parse(List.of(resource("/iq_aml_page1.json"), resource("/iq_aml_page2.json")));
    }

    private static byte[] resource(String path) throws Exception {
        try (InputStream in = IqAmlProviderTest.class.getResourceAsStream(path)) {
            return in.readAllBytes();
        }
    }
}
