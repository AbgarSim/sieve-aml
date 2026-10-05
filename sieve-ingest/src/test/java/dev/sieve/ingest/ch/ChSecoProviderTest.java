package dev.sieve.ingest.ch;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sieve.core.model.Relation;
import dev.sieve.core.model.RelationType;
import dev.sieve.core.model.SanctionedEntity;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChSecoProviderTest {

    private static final String XML =
            """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <swiss-sanctions-list list-type="whole-list" date="2026-09-28">
              <target ssid="5300">
                <individual>
                  <identity ssid="5301" main="true">
                    <name ssid="5302" name-type="primary-name">
                      <name-part order="1" name-part-type="whole-name"><value>Vladimir Peftiev</value></name-part>
                    </name>
                  </identity>
                </individual>
              </target>
              <target ssid="5817">
                <entity>
                  <identity ssid="5818" main="true">
                    <name ssid="5819" name-type="primary-name">
                      <name-part order="1" name-part-type="whole-name"><value>LLC Delovaya Set</value></name-part>
                    </name>
                  </identity>
                  <justification ssid="23591">Entity controlled by Vladimir Peftiev.</justification>
                  <relation ssid="6010" target-id="5300" relation-type="related-to"></relation>
                </entity>
                <modification modification-type="amended">
                  <removed><relation ssid="6009" target-id="9702" relation-type="related-to"></relation></removed>
                </modification>
              </target>
            </swiss-sanctions-list>
            """;

    @Test
    void shouldLinkTargetsByTheirRelationElementsOutsideTheModificationHistory() {
        List<SanctionedEntity> entities =
                new ChSecoProvider(URI.create("https://localhost/test"))
                        .parseResponse(XML.strip().getBytes(StandardCharsets.UTF_8));

        assertThat(entities).extracting(SanctionedEntity::id).containsExactly("ch-5300", "ch-5817");
        assertThat(entities.get(0).relations()).isEmpty();
        assertThat(entities.get(1).relations())
                .containsExactly(
                        new Relation(
                                RelationType.LINKED,
                                "ch-5300",
                                ChSecoProvider.RELATED_ROLE,
                                null,
                                null,
                                null));
    }
}
