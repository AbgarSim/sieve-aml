package dev.sieve.server.controller;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.sieve.ingest.pep.PublicFunction;
import dev.sieve.ingest.pep.PublicFunctionCatalog;
import dev.sieve.ingest.pep.PublicFunctionCategory;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PublicFunctionController.class)
@Import(PublicFunctionControllerTest.CatalogConfiguration.class)
class PublicFunctionControllerTest {

    @TestConfiguration
    static class CatalogConfiguration {
        @Bean
        PublicFunctionCatalog publicFunctionCatalog() {
            return new PublicFunctionCatalog(
                    "Prominent public functions",
                    "C/2023/724",
                    LocalDate.of(2023, 11, 10),
                    "http://data.europa.eu/eli/C/2023/724/oj",
                    List.of(
                            new PublicFunction(
                                    "DE",
                                    PublicFunctionCategory.HEADS_OF_STATE_AND_GOVERNMENT,
                                    "Head of Government",
                                    "Federal Chancellor (Bundeskanzler)",
                                    null),
                            new PublicFunction(
                                    "DE",
                                    PublicFunctionCategory.LEGISLATORS,
                                    "Members of Parliament",
                                    "Member of the German Bundestag",
                                    null),
                            new PublicFunction(
                                    "PL",
                                    PublicFunctionCategory.INTERNATIONAL_ORGANISATIONS,
                                    "International organisations",
                                    "Regional Director",
                                    "European Bank for Reconstruction and Development Warsaw"
                                            + " Resident Office")));
        }
    }

    @Autowired private MockMvc mockMvc;

    @Test
    void shouldSummariseTheCatalogue() throws Exception {
        mockMvc.perform(get("/api/v1/pep/functions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Prominent public functions"))
                .andExpect(jsonPath("$.reference").value("C/2023/724"))
                .andExpect(jsonPath("$.published").value("2023-11-10"))
                .andExpect(jsonPath("$.eli").value("http://data.europa.eu/eli/C/2023/724/oj"))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.jurisdictions[0].jurisdiction").value("DE"))
                .andExpect(jsonPath("$.jurisdictions[0].count").value(2))
                .andExpect(jsonPath("$.jurisdictions[1].jurisdiction").value("PL"))
                .andExpect(jsonPath("$.jurisdictions[1].count").value(1));
    }

    @Test
    void shouldListAJurisdictionsFunctionsWhateverTheCaseOfItsCode() throws Exception {
        mockMvc.perform(get("/api/v1/pep/functions/de"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jurisdiction").value("DE"))
                .andExpect(jsonPath("$.reference").value("C/2023/724"))
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.functions[0].category").value("a"))
                .andExpect(jsonPath("$.functions[0].heading").value("Head of Government"))
                .andExpect(
                        jsonPath("$.functions[0].function")
                                .value("Federal Chancellor (Bundeskanzler)"))
                .andExpect(jsonPath("$.functions[0].organisation").doesNotExist())
                .andExpect(jsonPath("$.functions[1].category").value("b"));
    }

    @Test
    void shouldFilterAJurisdictionsFunctionsByCategory() throws Exception {
        mockMvc.perform(get("/api/v1/pep/functions/DE").param("category", "b"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(
                        jsonPath("$.functions[0].function")
                                .value("Member of the German Bundestag"));
    }

    @Test
    void shouldNameTheOrganisationOfAnInternationalFunction() throws Exception {
        mockMvc.perform(get("/api/v1/pep/functions/PL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.functions[0].category").value("h"))
                .andExpect(jsonPath("$.functions[0].function").value("Regional Director"))
                .andExpect(
                        jsonPath("$.functions[0].organisation")
                                .value(
                                        "European Bank for Reconstruction and Development Warsaw"
                                                + " Resident Office"));
    }

    @Test
    void shouldReturnNotFoundForAJurisdictionWithoutAList() throws Exception {
        mockMvc.perform(get("/api/v1/pep/functions/FR"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.detail").value(containsString("FR")));
    }

    @Test
    void shouldRejectAnUnknownCategory() throws Exception {
        mockMvc.perform(get("/api/v1/pep/functions/DE").param("category", "z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.detail").value(containsString("z")));
    }
}
