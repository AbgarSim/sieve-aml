package dev.sieve.ingest.relations;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.Relation;
import dev.sieve.core.model.RelationType;
import dev.sieve.ingest.relations.RelationPhrases.Phrase;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RelationPhrasesTest {

    @Test
    void shouldFindTheNearestPhraseBeforeAMention() {
        String text = "Member of the ADF (CDe.001) and brother of Qusay (IQi.002).";

        assertThat(RelationPhrases.before(text, text.indexOf("CDe")))
                .contains(new Phrase("member of", RelationType.LINKED));
        assertThat(RelationPhrases.before(text, text.indexOf("IQi")))
                .contains(new Phrase("brother of", RelationType.FAMILY));
    }

    @Test
    void shouldIgnoreAPhraseFromAnEarlierSentenceOrClause() {
        String sentence = "Son of a farmer. Lives in Kabul (TAi.001).";
        String clause = "Brother of Pyotr Ivanov; see also Pyotr Ivanov Holdings.";

        assertThat(RelationPhrases.before(sentence, sentence.indexOf("TAi"))).isEmpty();
        assertThat(RelationPhrases.before(clause, clause.indexOf("Pyotr Ivanov Holdings")))
                .isEmpty();
    }

    @Test
    void shouldMatchWholeWordsOnlyAndStayWithinTheWindow() {
        String grandson = "Grandson of the founder (QDi.001).";
        String far = "brother of " + "x".repeat(RelationPhrases.WINDOW) + " (QDi.001)";

        assertThat(RelationPhrases.before(grandson, grandson.indexOf("QDi"))).isEmpty();
        assertThat(RelationPhrases.before(far, far.indexOf("QDi"))).isEmpty();
    }

    @Test
    void shouldTypeTheRelationByThePhraseAndDemoteLeadershipOfAPerson() {
        Optional<Phrase> leader = RelationPhrases.before("leader of X (", 12);
        Optional<Phrase> owner = RelationPhrases.before("co-owner of Acme (", 17);
        Optional<Phrase> associate = RelationPhrases.before("associated with Acme (", 21);

        assertThat(RelationPhrases.relation(leader, "t", EntityType.ORGANIZATION))
                .isEqualTo(
                        new Relation(
                                RelationType.DIRECTORSHIP, "t", "leader of", null, null, null));
        assertThat(RelationPhrases.relation(leader, "t", EntityType.INDIVIDUAL))
                .isEqualTo(new Relation(RelationType.LINKED, "t", "leader of", null, null, null));
        assertThat(RelationPhrases.relation(owner, "t", EntityType.COMPANY).type())
                .isEqualTo(RelationType.OWNERSHIP);
        assertThat(RelationPhrases.relation(owner, "t", EntityType.INDIVIDUAL).type())
                .isEqualTo(RelationType.LINKED);
        assertThat(RelationPhrases.relation(associate, "t", EntityType.INDIVIDUAL))
                .isEqualTo(
                        new Relation(
                                RelationType.ASSOCIATE, "t", "associated with", null, null, null));
        assertThat(RelationPhrases.relation(Optional.empty(), "t", EntityType.ENTITY))
                .isEqualTo(Relation.of(RelationType.LINKED, "t"));
    }
}
