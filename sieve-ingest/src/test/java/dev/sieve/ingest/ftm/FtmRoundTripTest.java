package dev.sieve.ingest.ftm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sieve.core.model.Address;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.Identifier;
import dev.sieve.core.model.IdentifierType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.NameStrength;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.Relation;
import dev.sieve.core.model.RelationType;
import dev.sieve.core.model.RiskTopic;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.SanctionsProgram;
import dev.sieve.core.model.ScriptType;
import dev.sieve.core.provenance.ProvenanceStamper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class FtmRoundTripTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FtmWriter writer = new FtmWriter();
    private final FtmReader reader = new FtmReader();

    @Test
    void shouldWritePersonWithSchemaPropertiesAndSanction() throws IOException {
        List<JsonNode> nodes = lines(writer.writeToString(List.of(person())));

        JsonNode person = bySchema(nodes).get("Person");
        assertThat(person.get("id").asText()).isEqualTo("ofac-sdn-1");
        assertThat(person.get("datasets").get(0).asText()).isEqualTo("ofac_sdn");
        JsonNode props = person.get("properties");
        assertThat(texts(props, "name")).containsExactly("PETROV, Ivan");
        assertThat(texts(props, "alias")).containsExactly("Ivan Petrov");
        assertThat(texts(props, "birthDate")).containsExactly("1970-05-01");
        assertThat(texts(props, "nationality")).containsExactly("ru");
        assertThat(texts(props, "passportNumber")).containsExactly("P123");
        assertThat(texts(props, "topics")).containsExactlyInAnyOrder("sanction", "role.pep");
        assertThat(texts(props, "programId")).containsExactly("RUSSIA-EO14024");

        JsonNode sanction = bySchema(nodes).get("Sanction");
        assertThat(texts(sanction.get("properties"), "entity")).containsExactly("ofac-sdn-1");
        assertThat(texts(sanction.get("properties"), "authority")).containsExactly("OFAC SDN");
        assertThat(texts(sanction.get("properties"), "listingDate")).containsExactly("2022-04-06");
    }

    @Test
    void shouldWriteRelationsAsLinksAndPositionsOnce() throws IOException {
        SanctionedEntity a = person();
        SanctionedEntity b = withId(person(), "ofac-sdn-2");

        List<JsonNode> nodes = lines(writer.writeToString(List.of(a, b)));

        JsonNode ownership = bySchema(nodes).get("Ownership");
        assertThat(texts(ownership.get("properties"), "owner")).containsExactly("ofac-sdn-1");
        assertThat(texts(ownership.get("properties"), "asset")).containsExactly("ofac-sdn-9");
        assertThat(texts(ownership.get("properties"), "percentage")).containsExactly("51.0");
        JsonNode occupancy = bySchema(nodes).get("Occupancy");
        assertThat(texts(occupancy.get("properties"), "post")).containsExactly("wd-Q1");
        assertThat(nodes.stream().filter(n -> n.get("schema").asText().equals("Position")))
                .hasSize(1);
    }

    @Test
    void shouldLeaveOutPropertiesTheSchemaDoesNotDefine() throws IOException {
        SanctionedEntity vessel =
                new SanctionedEntity(
                        "ofac-sdn-5",
                        EntityType.VESSEL,
                        ListSource.OFAC_SDN,
                        name("SEA STAR"),
                        List.of(),
                        List.of(),
                        List.of(
                                new Identifier(IdentifierType.IMO_NUMBER, "9123456", null, null),
                                new Identifier(IdentifierType.PASSPORT, "X1", null, null)),
                        List.of(),
                        List.of(),
                        List.of(LocalDate.of(2000, 1, 1)),
                        List.of(),
                        null,
                        List.of(),
                        null,
                        null);

        JsonNode props = lines(writer.writeToString(List.of(vessel))).getFirst().get("properties");

        assertThat(texts(props, "imoNumber")).containsExactly("9123456");
        assertThat(props.has("passportNumber")).isFalse();
        assertThat(props.has("birthDate")).isFalse();
        assertThat(props.has("idNumber")).isFalse();
    }

    @Test
    void shouldReadBackWhatItWrote() throws IOException {
        SanctionedEntity original = person();
        String json = writer.writeToString(List.of(original));

        List<SanctionedEntity> read = read(json);

        assertThat(read).hasSize(1);
        SanctionedEntity back = read.getFirst();
        assertThat(back.id()).isEqualTo(original.id());
        assertThat(back.entityType()).isEqualTo(EntityType.INDIVIDUAL);
        assertThat(back.primaryName().fullName()).isEqualTo("PETROV, Ivan");
        assertThat(back.primaryName().givenName()).isEqualTo("Ivan");
        assertThat(back.aliases()).extracting(NameInfo::fullName).containsExactly("Ivan Petrov");
        assertThat(back.datesOfBirth()).containsExactly(LocalDate.of(1970, 5, 1));
        assertThat(back.nationalities()).containsExactly("RU");
        assertThat(back.identifiers())
                .containsExactly(new Identifier(IdentifierType.PASSPORT, "P123", null, null));
        assertThat(back.topics()).containsExactlyInAnyOrder(RiskTopic.SANCTION, RiskTopic.PEP);
        assertThat(back.programs())
                .extracting(SanctionsProgram::code)
                .containsExactly("RUSSIA-EO14024");
        assertThat(back.relations()).containsExactlyInAnyOrderElementsOf(original.relations());
        assertThat(back.listedDate()).isEqualTo(Instant.parse("2022-04-06T00:00:00Z"));
    }

    @Test
    void shouldReadForeignEntitiesWithPartialDatesAndSkipUnknownSchemas() throws IOException {
        String json =
                """
                {"id":"x-1","schema":"Company","properties":{"name":["Acme LLC"],"jurisdiction":["cy"],"registrationNumber":["HE123"],"topics":["sanction.linked","unknown.topic"]}}
                {"id":"x-2","schema":"Person","properties":{"name":["Jane Roe"],"birthDate":["1961"]}}
                {"id":"x-3","schema":"Address","properties":{"full":["1 Main St"]}}
                {"id":"x-4","schema":"Ownership","properties":{"owner":["x-2"],"asset":["x-1"],"percentage":["75"]}}
                {"id":"x-5","schema":"CryptoWallet","properties":{"publicKey":["bc1qxyz"],"name":["bc1qxyz"],"currency":["XBT"]}}
                """;

        Map<String, SanctionedEntity> byId =
                read(json).stream()
                        .collect(Collectors.toMap(SanctionedEntity::id, Function.identity()));

        assertThat(byId).containsOnlyKeys("x-1", "x-2", "x-5");
        assertThat(byId.get("x-1").entityType()).isEqualTo(EntityType.COMPANY);
        assertThat(byId.get("x-1").nationalities()).containsExactly("CY");
        assertThat(byId.get("x-1").identifiers())
                .containsExactly(
                        new Identifier(IdentifierType.BUSINESS_REGISTRATION, "HE123", null, null));
        assertThat(byId.get("x-1").topics()).containsExactly(RiskTopic.SANCTION_LINKED);
        assertThat(byId.get("x-2").datesOfBirth()).containsExactly(LocalDate.of(1961, 1, 1));
        assertThat(byId.get("x-2").relations())
                .containsExactly(
                        new Relation(RelationType.OWNERSHIP, "x-1", null, 75.0, null, null));
        assertThat(byId.get("x-5").entityType()).isEqualTo(EntityType.CRYPTO_WALLET);
        assertThat(byId.get("x-5").identifiers())
                .extracting(Identifier::type)
                .containsExactly(IdentifierType.CRYPTO_ADDRESS);
    }

    @Test
    void shouldCarryFirstAndLastSeenThroughTheFormat() throws IOException {
        Instant seen = Instant.parse("2026-10-04T12:00:00Z");
        SanctionedEntity stamped =
                ProvenanceStamper.stamp(person(), java.util.Optional.empty(), null, seen);

        String json = writer.writeToString(List.of(stamped));
        JsonNode node = lines(json).getFirst();
        SanctionedEntity back = read(json).getFirst();

        assertThat(node.get("first_seen").asText()).isEqualTo("2026-10-04T12:00:00Z");
        assertThat(node.get("last_seen").asText()).isEqualTo("2026-10-04T12:00:00Z");
        assertThat(ProvenanceStamper.firstSeen(back)).contains(seen);
        assertThat(back.provenance()).isNotEmpty();
    }

    private List<SanctionedEntity> read(String json) throws IOException {
        return reader.read(
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)),
                ListSource.OFAC_SDN);
    }

    private static SanctionedEntity person() {
        NameInfo primary =
                new NameInfo(
                        "PETROV, Ivan",
                        "Ivan",
                        "PETROV",
                        null,
                        null,
                        NameType.PRIMARY,
                        NameStrength.STRONG,
                        ScriptType.LATIN);
        return new SanctionedEntity(
                "ofac-sdn-1",
                EntityType.INDIVIDUAL,
                ListSource.OFAC_SDN,
                primary,
                List.of(name("Ivan Petrov")),
                List.of(new Address("1 Tverskaya", "Moscow", null, null, "Russia", null)),
                List.of(new Identifier(IdentifierType.PASSPORT, "P123", "RU", null)),
                List.of("RU"),
                List.of(),
                List.of(LocalDate.of(1970, 5, 1)),
                List.of(),
                null,
                List.of(new SanctionsProgram("RUSSIA-EO14024", "Russia", ListSource.OFAC_SDN)),
                Instant.parse("2022-04-06T10:00:00Z"),
                null,
                Set.of(RiskTopic.SANCTION, RiskTopic.PEP),
                List.of(
                        new Relation(RelationType.OWNERSHIP, "ofac-sdn-9", null, 51.0, null, null),
                        new Relation(
                                RelationType.POSITION_HELD,
                                "wd-Q1",
                                "Minister of Energy",
                                null,
                                LocalDate.of(2012, 5, 21),
                                null)));
    }

    private static SanctionedEntity withId(SanctionedEntity e, String id) {
        return new SanctionedEntity(
                id,
                e.entityType(),
                e.listSource(),
                e.primaryName(),
                e.aliases(),
                e.addresses(),
                e.identifiers(),
                e.nationalities(),
                e.citizenships(),
                e.datesOfBirth(),
                e.placesOfBirth(),
                e.remarks(),
                e.programs(),
                e.listedDate(),
                e.lastUpdated(),
                e.topics(),
                e.relations());
    }

    private static NameInfo name(String fullName) {
        return new NameInfo(
                fullName,
                null,
                null,
                null,
                null,
                NameType.AKA,
                NameStrength.STRONG,
                ScriptType.LATIN);
    }

    private static List<JsonNode> lines(String json) throws IOException {
        List<JsonNode> nodes = new ArrayList<>();
        for (String line : json.split("\n")) {
            nodes.add(MAPPER.readTree(line));
        }
        return nodes;
    }

    private static Map<String, JsonNode> bySchema(List<JsonNode> nodes) {
        return nodes.stream()
                .collect(Collectors.toMap(n -> n.get("schema").asText(), n -> n, (a, b) -> a));
    }

    private static List<String> texts(JsonNode props, String property) {
        List<String> out = new ArrayList<>();
        props.path(property).forEach(v -> out.add(v.asText()));
        return out;
    }
}
