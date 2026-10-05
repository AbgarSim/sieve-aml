package dev.sieve.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.sieve.core.index.EntityIndex;
import dev.sieve.core.match.MatchEngine;
import dev.sieve.core.media.AdverseMediaQuery;
import dev.sieve.core.media.AdverseMediaReport;
import dev.sieve.core.media.AdverseMediaSearch;
import dev.sieve.core.media.MediaArticle;
import dev.sieve.server.config.SieveProperties;
import dev.sieve.server.dto.AdverseMediaResponseDto;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdverseMediaController.class)
class AdverseMediaControllerTest {

    private static final SieveProperties.AdverseMediaProperties SETTINGS =
            new SieveProperties.AdverseMediaProperties(true, "gkg", 90, 25, 6, 72, true, 0.92);

    @Autowired private MockMvc mockMvc;

    @MockBean private AdverseMediaSearch search;
    @MockBean private SieveProperties properties;
    @MockBean private MatchEngine matchEngine;
    @MockBean private EntityIndex entityIndex;

    @Test
    void shouldReturnCandidateArticlesWithNoticeWhenEnabled() throws Exception {
        MediaArticle article =
                new MediaArticle(
                        "https://news.example/a",
                        "Banker held",
                        "news.example",
                        "English",
                        Optional.empty(),
                        Instant.parse("2026-10-05T12:00:00Z"),
                        List.of("money laundering"),
                        Optional.of("John Doe"));
        when(properties.adverseMedia()).thenReturn(SETTINGS);
        when(search.search(any()))
                .thenReturn(
                        new AdverseMediaReport(
                                "John Doe",
                                "GDELT GKG",
                                "names like \"john doe\"",
                                AdverseMediaReport.Status.OK,
                                List.of(article),
                                Instant.parse("2026-10-05T12:05:00Z"),
                                Optional.empty()));

        mockMvc.perform(
                        post("/api/v1/adverse-media")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\": \"John Doe\", \"lookbackDays\": 7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.totalArticles").value(1))
                .andExpect(jsonPath("$.articles[0].url").value("https://news.example/a"))
                .andExpect(jsonPath("$.articles[0].mentionedAs").value("John Doe"))
                .andExpect(jsonPath("$.articles[0].adverseTerms[0]").value("money laundering"))
                .andExpect(jsonPath("$.notice").value(AdverseMediaResponseDto.NOTICE));

        ArgumentCaptor<AdverseMediaQuery> query = ArgumentCaptor.forClass(AdverseMediaQuery.class);
        verify(search).search(query.capture());
        assertThat(query.getValue().lookback()).isEqualTo(Duration.ofDays(7));
        assertThat(query.getValue().maxArticles()).isEqualTo(25);
        verifyNoInteractions(matchEngine, entityIndex);
    }

    @Test
    void shouldRejectBlankName() throws Exception {
        mockMvc.perform(
                        post("/api/v1/adverse-media")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\": \" \"}"))
                .andExpect(status().isBadRequest());
    }
}
