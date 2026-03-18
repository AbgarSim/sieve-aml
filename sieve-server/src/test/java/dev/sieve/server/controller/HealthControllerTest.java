package dev.sieve.server.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.sieve.core.index.EntityIndex;
import dev.sieve.core.index.IndexStats;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.ListSource;
import dev.sieve.server.mapper.ScreeningMapper;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(HealthController.class)
@Import(ScreeningMapper.class)
class HealthControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private EntityIndex entityIndex;

    @Test
    void shouldReturnHealthStatus() throws Exception {
        IndexStats stats =
                new IndexStats(
                        100,
                        Map.of(ListSource.OFAC_SDN, 100),
                        Map.of(EntityType.INDIVIDUAL, 80, EntityType.ENTITY, 20),
                        Instant.now());
        when(entityIndex.stats()).thenReturn(stats);

        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.index.totalEntities").value(100))
                .andExpect(jsonPath("$.index.countBySource.OFAC_SDN").value(100));
    }

    @Test
    void shouldReturnHealthWhenIndexIsEmpty() throws Exception {
        IndexStats stats = new IndexStats(0, Map.of(), Map.of(), Instant.now());
        when(entityIndex.stats()).thenReturn(stats);

        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.index.totalEntities").value(0));
    }
}
