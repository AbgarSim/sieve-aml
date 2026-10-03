package dev.sieve.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.NameStrength;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.Relation;
import dev.sieve.core.model.RelationType;
import dev.sieve.core.model.RiskTopic;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.ScriptType;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The JSON column of {@link SanctionedEntityRow} must round-trip the full entity. */
class SanctionedEntityJsonTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void shouldRoundTripTopicsAndRelations() throws Exception {
        SanctionedEntity entity =
                new SanctionedEntity(
                        "un-1",
                        EntityType.INDIVIDUAL,
                        ListSource.UN_CONSOLIDATED,
                        name("DOE, John"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Set.of(RiskTopic.SANCTION, RiskTopic.PEP),
                        List.of(
                                new Relation(
                                        RelationType.OWNERSHIP,
                                        "eu-EU.1",
                                        "shareholder",
                                        60.0,
                                        LocalDate.of(2020, 5, 1),
                                        null)));

        SanctionedEntity read =
                mapper.readValue(mapper.writeValueAsString(entity), SanctionedEntity.class);

        assertThat(read).isEqualTo(entity);
    }

    @Test
    void shouldReadRowsWrittenBeforeTopicsExisted() throws Exception {
        String json =
                "{\"id\":\"ofac-sdn-1\",\"entityType\":\"INDIVIDUAL\",\"listSource\":\"OFAC_SDN\","
                        + "\"primaryName\":{\"fullName\":\"DOE, John\",\"nameType\":\"PRIMARY\"}}";

        SanctionedEntity read = mapper.readValue(json, SanctionedEntity.class);

        assertThat(read.topics()).isEmpty();
        assertThat(read.relations()).isEmpty();
    }

    private static NameInfo name(String fullName) {
        return new NameInfo(
                fullName,
                null,
                null,
                null,
                null,
                NameType.PRIMARY,
                NameStrength.STRONG,
                ScriptType.LATIN);
    }
}
