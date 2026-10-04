package dev.sieve.core.provenance;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.Identifier;
import dev.sieve.core.model.IdentifierType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.NameStrength;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.Provenance;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.ScriptType;
import dev.sieve.core.model.SourcedValue;
import dev.sieve.core.model.SourcedValue.Key;
import dev.sieve.core.model.ValueKind;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProvenanceStamperTest {

    private static final Instant FIRST = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant SECOND = Instant.parse("2026-02-01T00:00:00Z");
    private static final String URL = "https://example.org/list.xml";

    @Test
    void shouldStampEveryValueOfANewEntityWithTheFetchTime() {
        SanctionedEntity stamped =
                ProvenanceStamper.stamp(entity("P1", "Ivan Petrov"), Optional.empty(), URL, FIRST);

        assertThat(stamped.provenance())
                .extracting(SourcedValue::kind)
                .containsExactly(
                        ValueKind.NAME,
                        ValueKind.NAME,
                        ValueKind.BIRTH_DATE,
                        ValueKind.IDENTIFIER,
                        ValueKind.TOPIC);
        assertThat(stamped.provenanceOf(Key.of(passport("P1"))))
                .contains(new Provenance(ListSource.OFAC_SDN, URL, FIRST, FIRST));
    }

    @Test
    void shouldKeepFirstSeenOfValuesTheEntityAlreadyHad() {
        SanctionedEntity before =
                ProvenanceStamper.stamp(entity("P1", "Ivan Petrov"), Optional.empty(), URL, FIRST);

        SanctionedEntity after =
                ProvenanceStamper.stamp(
                        entity("P2", "Ivan Petrov"), Optional.of(before), URL, SECOND);

        assertThat(after.provenanceOf(Key.of(passport("P1")))).isEmpty();
        assertThat(after.provenanceOf(Key.of(passport("P2"))).orElseThrow().firstSeen())
                .isEqualTo(SECOND);
        Provenance alias = after.provenanceOf(new Key(ValueKind.NAME, "Ivan Petrov")).orElseThrow();
        assertThat(alias.firstSeen()).isEqualTo(FIRST);
        assertThat(alias.lastSeen()).isEqualTo(SECOND);
        assertThat(ProvenanceStamper.firstSeen(after)).contains(FIRST);
        assertThat(ProvenanceStamper.lastSeen(after)).contains(SECOND);
    }

    @Test
    void shouldKeepSourceUrlAndEarlierFirstSeenSetByTheProvider() {
        Instant earlier = Instant.parse("2020-05-01T00:00:00Z");
        SanctionedEntity fetched = entity("P1", "Ivan Petrov");
        fetched =
                fetched.withProvenance(
                        List.of(
                                new SourcedValue(
                                        ValueKind.NAME,
                                        "PETROV, Ivan",
                                        new Provenance(
                                                ListSource.OFAC_SDN,
                                                "https://example.org/item/1",
                                                earlier,
                                                earlier))));

        SanctionedEntity stamped = ProvenanceStamper.stamp(fetched, Optional.empty(), URL, FIRST);

        assertThat(stamped.provenanceOf(new Key(ValueKind.NAME, "PETROV, Ivan")))
                .contains(
                        new Provenance(
                                ListSource.OFAC_SDN, "https://example.org/item/1", earlier, FIRST));
    }

    private static Identifier passport(String number) {
        return new Identifier(IdentifierType.PASSPORT, number, null, null);
    }

    private static SanctionedEntity entity(String passport, String alias) {
        return new SanctionedEntity(
                "ofac-sdn-1",
                EntityType.INDIVIDUAL,
                ListSource.OFAC_SDN,
                name("PETROV, Ivan", NameType.PRIMARY),
                List.of(name(alias, NameType.AKA)),
                List.of(),
                List.of(passport(passport)),
                List.of(),
                List.of(),
                List.of(LocalDate.of(1970, 5, 1)),
                List.of(),
                null,
                List.of(),
                null,
                null);
    }

    private static NameInfo name(String fullName, NameType type) {
        return new NameInfo(
                fullName, null, null, null, null, type, NameStrength.STRONG, ScriptType.LATIN);
    }
}
