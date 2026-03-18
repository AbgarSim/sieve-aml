package dev.sieve.server.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.sieve.core.index.EntityIndex;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.NameStrength;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.ScriptType;
import dev.sieve.ingest.IngestionOrchestrator;
import dev.sieve.ingest.IngestionReport;
import dev.sieve.ingest.ProviderResult;
import dev.sieve.server.mapper.ScreeningMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ListController.class)
@Import(ScreeningMapper.class)
class ListControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private EntityIndex entityIndex;
    @MockBean private IngestionOrchestrator orchestrator;

    @Test
    void shouldReturnListStatuses() throws Exception {
        when(entityIndex.findBySource(ListSource.OFAC_SDN)).thenReturn(List.of(createEntity("1")));
        when(entityIndex.findBySource(ListSource.EU_CONSOLIDATED)).thenReturn(List.of());
        when(entityIndex.findBySource(ListSource.UN_CONSOLIDATED)).thenReturn(List.of());
        when(entityIndex.findBySource(ListSource.UK_HMT)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/lists"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lists").isArray())
                .andExpect(jsonPath("$.lists[0].source").value("OFAC_SDN"))
                .andExpect(jsonPath("$.lists[0].status").value("LOADED"));
    }

    @Test
    void shouldReturnPaginatedEntities() throws Exception {
        List<SanctionedEntity> entities =
                List.of(createEntity("1"), createEntity("2"), createEntity("3"));

        when(entityIndex.findBySource(ListSource.OFAC_SDN)).thenReturn(entities);

        mockMvc.perform(get("/api/v1/lists/OFAC_SDN/entities?page=0&size=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entities").isArray())
                .andExpect(jsonPath("$.entities.length()").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void shouldTriggerRefresh() throws Exception {
        IngestionReport report =
                new IngestionReport(
                        Map.of(
                                ListSource.OFAC_SDN,
                                ProviderResult.success(
                                        ListSource.OFAC_SDN, 100, Duration.ofMillis(500))),
                        100,
                        Duration.ofMillis(500));

        when(orchestrator.ingest(any())).thenReturn(report);

        mockMvc.perform(post("/api/v1/lists/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEntitiesLoaded").value(100))
                .andExpect(jsonPath("$.results.OFAC_SDN.status").value("SUCCESS"));
    }

    @Test
    void shouldReturn400ForInvalidSource() throws Exception {
        mockMvc.perform(get("/api/v1/lists/INVALID_SOURCE/entities"))
                .andExpect(status().isBadRequest());
    }

    private static SanctionedEntity createEntity(String id) {
        NameInfo name =
                new NameInfo(
                        "Test " + id,
                        null,
                        null,
                        null,
                        null,
                        NameType.PRIMARY,
                        NameStrength.STRONG,
                        ScriptType.LATIN);
        return new SanctionedEntity(
                id,
                EntityType.INDIVIDUAL,
                ListSource.OFAC_SDN,
                name,
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
                Instant.now());
    }
}
