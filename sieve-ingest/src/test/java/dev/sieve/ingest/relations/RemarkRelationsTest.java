package dev.sieve.ingest.relations;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.NameStrength;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.Relation;
import dev.sieve.core.model.RelationType;
import dev.sieve.core.model.SanctionedEntity;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class RemarkRelationsTest {

    private static final Pattern REFERENCE = Pattern.compile("\\b[A-Za-z]{2,3}[iIeE]\\.\\d{3}\\b");

    @Test
    void shouldLinkEntriesCitedByReferenceNumber() {
        List<SanctionedEntity> in =
                List.of(
                        entity(
                                "un-QDi.001",
                                EntityType.INDIVIDUAL,
                                "DOE, John",
                                "Member of Acme (QDe.001) and brother of Roe (QDi.002). Cites himself"
                                        + " (QDi.001) and a delisted party (QDe.099)."),
                        entity(
                                "un-QDe.001",
                                EntityType.ENTITY,
                                "Acme",
                                "Led by John Doe (qdi.001)."),
                        entity("un-QDi.002", EntityType.INDIVIDUAL, "ROE, Richard", null),
                        entity(
                                "un-1234",
                                EntityType.INDIVIDUAL,
                                "NOREF",
                                "Twice: (QDi.002) and (QDi.002)."));

        List<SanctionedEntity> out = RemarkRelations.byReference(in, REFERENCE, "un-", "test");

        assertThat(out)
                .extracting(SanctionedEntity::id)
                .containsExactly("un-QDi.001", "un-QDe.001", "un-QDi.002", "un-1234");
        assertThat(out.get(0).relations())
                .containsExactly(
                        new Relation(
                                RelationType.LINKED, "un-QDe.001", "member of", null, null, null),
                        new Relation(
                                RelationType.FAMILY, "un-QDi.002", "brother of", null, null, null));
        // a lower-case citation resolves; "led by" is no known phrase, so the link has no role
        assertThat(out.get(1).relations())
                .containsExactly(Relation.of(RelationType.LINKED, "un-QDi.001"));
        assertThat(out.get(2).relations()).isEmpty();
        // one relation per target, however often it is cited
        assertThat(out.get(3).relations())
                .containsExactly(Relation.of(RelationType.LINKED, "un-QDi.002"));
    }

    @Test
    void shouldLinkEntriesMentionedByNameAndPreferTheLongestName() {
        List<SanctionedEntity> in =
                List.of(
                        entity(
                                "eu-1",
                                EntityType.INDIVIDUAL,
                                "Sergei Petrovich Ivanov",
                                "Brother of Pyotr Ivanov; see also Pyotr Ivanov Holdings and the short name Li."),
                        entity(
                                "eu-2",
                                EntityType.INDIVIDUAL,
                                "Pyotr Ivanov",
                                "Owns Pyotr Ivanov Holdings.",
                                "Пётр Иванов"),
                        entity(
                                "eu-3",
                                EntityType.COMPANY,
                                "Pyotr Ivanov Holdings",
                                "Owned by Sergeï Petrovich Ivanov, associated with Acme Trading GmbH."),
                        entity("eu-4", EntityType.INDIVIDUAL, "Li", "Named Li."),
                        entity("eu-5", EntityType.COMPANY, "Acme Trading GmbH", null),
                        entity(
                                "eu-6",
                                EntityType.COMPANY,
                                "Acme Trading GmbH",
                                "Same name as another entry."));

        List<SanctionedEntity> out = RemarkRelations.byName(in, "test");

        // "Pyotr Ivanov Holdings" is one mention of the company, not also one of Pyotr Ivanov; the
        // phrase before the first mention does not carry past the semicolon
        assertThat(out.get(0).relations())
                .containsExactly(
                        new Relation(RelationType.FAMILY, "eu-2", "brother of", null, null, null),
                        Relation.of(RelationType.LINKED, "eu-3"));
        assertThat(out.get(1).relations())
                .containsExactly(Relation.of(RelationType.LINKED, "eu-3"));
        // accents do not matter; a name two entries share (Acme) is skipped
        assertThat(out.get(2).relations())
                .containsExactly(
                        new Relation(RelationType.LINKED, "eu-1", "owned by", null, null, null));
        // a short name is never a mention, and an entry's own name is not one either
        assertThat(out.get(3).relations()).isEmpty();
        assertThat(out.get(4).relations()).isEmpty();
        assertThat(out.get(5).relations()).isEmpty();
    }

    @Test
    void shouldNormalizeNamesForMatching() {
        assertThat(RemarkRelations.normalize("  PETRÓLEOS de Venezuela, S.A. "))
                .isEqualTo("petroleos de venezuela s a");
        assertThat(RemarkRelations.normalize("Пётр Иванов")).isEqualTo("петр иванов");
        RemarkRelations.Words words = RemarkRelations.words("Ab, cd (ef)");
        assertThat(words.words()).containsExactly("ab", "cd", "ef");
        assertThat(words.offsets()).containsExactly(0, 4, 8);
    }

    private static SanctionedEntity entity(
            String id, EntityType type, String name, String remarks, String... aliases) {
        return new SanctionedEntity(
                id,
                type,
                ListSource.UN_CONSOLIDATED,
                name(name, NameType.PRIMARY),
                Arrays.stream(aliases).map(a -> name(a, NameType.AKA)).toList(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                remarks,
                List.of(),
                null,
                null);
    }

    private static NameInfo name(String name, NameType type) {
        return new NameInfo(name, null, null, null, null, type, NameStrength.STRONG, null);
    }
}
