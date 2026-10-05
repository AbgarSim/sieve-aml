package dev.sieve.server.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.sieve.server.config.SieveProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdverseMediaController.class)
class AdverseMediaDisabledTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private SieveProperties properties;

    @Test
    void shouldAnswerServiceUnavailableWhenAdverseMediaIsOff() throws Exception {
        mockMvc.perform(
                        post("/api/v1/adverse-media")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\": \"John Doe\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("Adverse Media Disabled"));
    }
}
