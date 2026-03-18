package dev.sieve.server.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.sieve.core.index.EntityIndex;
import dev.sieve.core.match.MatchEngine;
import dev.sieve.core.match.MatchResult;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.NameStrength;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.ScriptType;
import dev.sieve.server.config.SieveProperties;
import dev.sieve.server.mapper.ScreeningMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ScreeningController.class)
@Import(ScreeningMapper.class)
class ScreeningControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private MatchEngine matchEngine;
    @MockBean private EntityIndex entityIndex;
    @MockBean private SieveProperties properties;

    @Test
    void shouldReturnMatchesWhenScreening() throws Exception {
        SanctionedEntity entity = createEntity("1", "DOE, John");
        MatchResult matchResult = new MatchResult(entity, 0.95, "primaryName", "JARO_WINKLER");

        when(matchEngine.screen(any(), any())).thenReturn(List.of(matchResult));
        when(properties.screening())
                .thenReturn(new SieveProperties.ScreeningProperties(0.80, 50));

        mockMvc.perform(
                        post("/api/v1/screen")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"name": "John Doe", "threshold": 0.75}
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value("John Doe"))
                .andExpect(jsonPath("$.results").isArray())
                .andExpect(jsonPath("$.results[0].score").value(0.95))
                .andExpect(jsonPath("$.results[0].entity.id").value("1"));
    }

    @Test
    void shouldReturnEmptyResultsWhenNoMatches() throws Exception {
        when(matchEngine.screen(any(), any())).thenReturn(List.of());
        when(properties.screening())
                .thenReturn(new SieveProperties.ScreeningProperties(0.80, 50));

        mockMvc.perform(
                        post("/api/v1/screen")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"name": "Nobody Matching", "threshold": 0.80}
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalMatches").value(0))
                .andExpect(jsonPath("$.results").isEmpty());
    }

    @Test
    void shouldReturn400WhenNameIsBlank() throws Exception {
        mockMvc.perform(
                        post("/api/v1/screen")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"name": "", "threshold": 0.80}
                                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturn400WhenNameIsMissing() throws Exception {
        mockMvc.perform(
                        post("/api/v1/screen")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"threshold": 0.80}
                                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturn400WhenThresholdExceedsBounds() throws Exception {
        mockMvc.perform(
                        post("/api/v1/screen")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"name": "John Doe", "threshold": 1.5}
                                        """))
                .andExpect(status().isBadRequest());
    }

    private static SanctionedEntity createEntity(String id, String name) {
        NameInfo primaryName =
                new NameInfo(
                        name, null, null, null, null, NameType.PRIMARY, NameStrength.STRONG,
                        ScriptType.LATIN);
        return new SanctionedEntity(
                id, EntityType.INDIVIDUAL, ListSource.OFAC_SDN, primaryName, null, null, null, null,
                null, null, null, null, null, null, Instant.now());
    }
}
