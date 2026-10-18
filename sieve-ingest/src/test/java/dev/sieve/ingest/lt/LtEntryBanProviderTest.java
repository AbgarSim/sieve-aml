package dev.sieve.ingest.lt;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.Gender;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.SanctionedEntity;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class LtEntryBanProviderTest {

    @Test
    void shouldReadEveryPerson() throws Exception {
        List<SanctionedEntity> entities = parse();

        assertThat(entities)
                .extracting(e -> e.primaryName().fullName())
                .containsExactly("KHABIB SHARIPOV", "NATASHA KOROLEVA", "IVAN PETROV");
        assertThat(entities)
                .allSatisfy(
                        e -> {
                            assertThat(e.listSource()).isEqualTo(ListSource.LT_ENTRY_BAN);
                            assertThat(e.entityType()).isEqualTo(EntityType.INDIVIDUAL);
                            assertThat(e.id()).startsWith("lt-");
                        });
    }

    @Test
    void shouldMapMagnitskyBans() throws Exception {
        SanctionedEntity first = parse().get(0);

        assertThat(first.primaryName().givenName()).isEqualTo("KHABIB");
        assertThat(first.primaryName().familyName()).isEqualTo("SHARIPOV");
        assertThat(first.datesOfBirth()).containsExactly(LocalDate.of(1990, 6, 1));
        assertThat(first.gender()).isEqualTo(Gender.MALE);
        assertThat(first.nationalities()).containsExactly("RU");
        assertThat(first.programs().get(0).code()).isEqualTo("Aliens Law Art. 133(4)");
        assertThat(first.listingReasons())
                .containsExactly("UTPĮ 133 str. 4 d., galiojanti nuo 2018-01-01");
        assertThat(first.remarks()).isEqualTo("Banned from entering Lithuania until 2027-01-21");
    }

    @Test
    void shouldKeepOtherGroundsAsWritten() throws Exception {
        List<SanctionedEntity> entities = parse();
        SanctionedEntity second = entities.get(1);

        assertThat(second.gender()).isEqualTo(Gender.FEMALE);
        assertThat(second.nationalities()).containsExactly("RU", "RS");
        assertThat(second.programs().get(0).code())
                .isEqualTo("užsienietis gali kelti grėsmę valstybės saugumui");
        SanctionedEntity third = entities.get(2);
        assertThat(third.datesOfBirth()).isEmpty();
        assertThat(third.gender()).isNull();
        assertThat(third.programs().get(0).code()).isEqualTo("Entry ban");
    }

    private static List<SanctionedEntity> parse() throws Exception {
        try (InputStream in =
                LtEntryBanProviderTest.class.getResourceAsStream("/lt_entry_ban_sample.json")) {
            return new LtEntryBanProvider(
                            URI.create("http://localhost"), HttpClient.newHttpClient())
                    .parseResponse(in.readAllBytes());
        }
    }
}
