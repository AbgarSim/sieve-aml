package dev.sieve.ingest.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.NameStrength;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.SanctionsProgram;
import dev.sieve.ingest.in.InMhaOrgProvider.Schedule;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InMhaOrgProviderTest {

    private static final URI PAGE =
            URI.create(
                    "https://www.mha.gov.in/en/divisionofmha/counter-terrorism-and-counter-radicalization-division/Banned-Organizations");
    private static final URI TERRORIST_PDF =
            URI.create(
                    "https://www.mha.gov.in/sites/default/files/2026-09/List46TerroristOrganisations_16092026.pdf");
    private static final URI UNLAWFUL_PDF =
            URI.create(
                    "https://www.mha.gov.in/sites/default/files/2025-03/ListUNLAWFUL_17032025.pdf");

    private static final String PAGE_HTML =
            """
            <table><thead><tr><th>SR-No</th><th>Title</th><th>Download/Link</th></tr></thead><tbody>
            <tr><td>1 </td><td>TERRORIST ORGANISATIONS LISTED IN THE FIRST SCHEDULE OF THE UNLAWFUL ACTIVITIES (PREVENTION) ACT, 1967. </td>
            <td><span class="file"> <a href="/sites/default/files/2026-09/List46TerroristOrganisations_16092026.pdf" class="ext" target="_blank">Download (100.11 KB) </a> </span></td></tr>
            <tr><td>2 </td><td>UNLAWFUL ASSOCIATIONS UNDER SECTION 3 OF UNLAWFUL ACTIVITIES (PREVENTION) ACT, 1967 </td>
            <td><span class="file"> <a href="/sites/default/files/2025-03/ListUNLAWFUL_17032025.pdf" class="ext" target="_blank">Download (107.78 KB) </a> </span></td></tr>
            </tbody></table>
            """;

    @Test
    void shouldReturnSource() {
        assertThat(new InMhaOrgProvider().source()).isEqualTo(ListSource.IN_MHA_ORG);
    }

    @Test
    void shouldFindBothPdfLinksOnThePage() throws Exception {
        Map<Schedule, URI> links = InMhaOrgProvider.fileLinks(PAGE_HTML, PAGE);

        assertThat(links)
                .containsExactly(
                        Map.entry(Schedule.TERRORIST, TERRORIST_PDF),
                        Map.entry(Schedule.UNLAWFUL, UNLAWFUL_PDF));
    }

    @Test
    void shouldFailWhenAListHasNoLink() {
        String onlyTerrorists = PAGE_HTML.replace("UNLAWFUL ASSOCIATIONS", "SOMETHING ELSE");

        assertThatThrownBy(() -> InMhaOrgProvider.fileLinks(onlyTerrorists, PAGE))
                .isInstanceOf(ListIngestionException.class)
                .hasMessageContaining("unlawful associations");
    }

    @Test
    void shouldParseTheTerroristOrganisations() throws Exception {
        List<SanctionedEntity> entities =
                parse("in_mha_terrorist_organisations.pdf", Schedule.TERRORIST, TERRORIST_PDF);

        // 46 entries, minus the one that only points at the UN lists
        assertThat(entities).hasSize(45);
        assertThat(entities)
                .extracting(SanctionedEntity::id)
                .startsWith("in-mha-to-1", "in-mha-to-2")
                .endsWith("in-mha-to-46");
        assertThat(entities).extracting(SanctionedEntity::id).doesNotContain("in-mha-to-33");
        assertThat(entities)
                .allSatisfy(
                        e -> {
                            assertThat(e.entityType()).isEqualTo(EntityType.ENTITY);
                            assertThat(e.listSource()).isEqualTo(ListSource.IN_MHA_ORG);
                            assertThat(e.programs())
                                    .extracting(SanctionsProgram::code)
                                    .containsExactly("UAPA-1");
                            assertThat(e.primaryName().fullName()).doesNotEndWith(".");
                        });

        SanctionedEntity babbar = byId(entities, "in-mha-to-1");
        assertThat(babbar.primaryName().fullName()).isEqualTo("Babbar Khalsa International");
        assertThat(babbar.aliases()).isEmpty();
        assertThat(babbar.remarks())
                .isEqualTo(
                        "Entry 1 of the ministry's list of terrorist organisations (First Schedule): Babbar Khalsa International. Source: "
                                + TERRORIST_PDF);

        // names joined with slashes are aliases, the manifestations tail is dropped
        SanctionedEntity let = byId(entities, "in-mha-to-5");
        assertThat(let.primaryName().fullName()).isEqualTo("Lashkar-E-Taiba");
        assertThat(let.aliases())
                .extracting(NameInfo::fullName, NameInfo::strength)
                .containsExactly(
                        tuple("Pasban-E-Ahle Hadis", NameStrength.STRONG),
                        tuple("The Resistance Front", NameStrength.STRONG));

        // an acronym in an alias becomes a weak alias too, and a curly apostrophe is straightened
        SanctionedEntity jem = byId(entities, "in-mha-to-6");
        assertThat(jem.primaryName().fullName()).isEqualTo("Jaish-E-Mohammed");
        assertThat(jem.aliases())
                .extracting(NameInfo::fullName, NameInfo::strength)
                .containsExactly(
                        tuple("Tahreik-E-Furquan", NameStrength.STRONG),
                        tuple("People's Anti-Fascist-Front (PAFF)", NameStrength.STRONG),
                        tuple("PAFF", NameStrength.WEAK));

        // "or" separates alternative names as well
        assertThat(byId(entities, "in-mha-to-7").aliases())
                .extracting(NameInfo::fullName)
                .containsExactly("Harkat-ul-Ansar", "Harkat-ul-Jehad-E-Islami", "Ansar-Ul-Ummah");

        // the acronym stays in the primary name and becomes a weak alias
        SanctionedEntity ulfa = byId(entities, "in-mha-to-11");
        assertThat(ulfa.primaryName().fullName())
                .isEqualTo("United Liberation Front of Assam (ULFA)");
        assertThat(ulfa.aliases())
                .extracting(NameInfo::fullName, NameInfo::strength)
                .containsExactly(tuple("ULFA", NameStrength.WEAK));

        // a line broken in the PDF is joined back
        assertThat(byId(entities, "in-mha-to-12").primaryName().fullName())
                .isEqualTo("National Democratic Front of Bodoland (NDFB) in Assam");

        // "(Maoist)" is a faction, not an acronym; the tail without a comma is dropped too
        SanctionedEntity maoist = byId(entities, "in-mha-to-34");
        assertThat(maoist.primaryName().fullName()).isEqualTo("Communist Party of India (Maoist)");
        assertThat(maoist.aliases()).isEmpty();
        assertThat(byId(entities, "in-mha-to-24").primaryName().fullName())
                .isEqualTo("Communist Party of India (Marxizt-Leninst) – Peoples War");

        // a word hyphenated across lines is joined without a space
        SanctionedEntity jmb = byId(entities, "in-mha-to-42");
        assertThat(jmb.primaryName().fullName()).isEqualTo("Jamaat-ul-Mujahideen Bangladesh");
        assertThat(jmb.aliases())
                .extracting(NameInfo::fullName)
                .containsExactly("Jamaat-ul Mujahideen India", "Jamaat-ul-Mujahideen Hindustan");

        assertThat(byId(entities, "in-mha-to-38").aliases())
                .extracting(NameInfo::fullName)
                .contains(
                        "Daish",
                        "ISIS Wilayat Khorasan",
                        "Islamic State of Iraq and the Sham-Khorasan (ISISK)",
                        "ISKP",
                        "ISISK");
        assertThat(byId(entities, "in-mha-to-39").primaryName().fullName())
                .isEqualTo("National Socialist Council of Nagaland (Khaplang) (NSCN(K))");
        assertThat(byId(entities, "in-mha-to-46").primaryName().fullName())
                .isEqualTo("Shahzad Bhatti Network (SBN)");
    }

    @Test
    void shouldParseTheUnlawfulAssociations() throws Exception {
        List<SanctionedEntity> entities =
                parse("in_mha_unlawful_associations.pdf", Schedule.UNLAWFUL, UNLAWFUL_PDF);

        // 23 entries, of which the Meitei heading stands for seven organisations
        assertThat(entities).hasSize(29);
        assertThat(entities).extracting(SanctionedEntity::id).doesNotContain("in-mha-ua-4");
        assertThat(entities)
                .extracting(SanctionedEntity::id)
                .contains(
                        "in-mha-ua-1",
                        "in-mha-ua-4-1",
                        "in-mha-ua-4-7",
                        "in-mha-ua-5",
                        "in-mha-ua-23");
        assertThat(entities)
                .allSatisfy(
                        e ->
                                assertThat(e.programs())
                                        .extracting(SanctionsProgram::code)
                                        .containsExactly("UAPA-3"));

        // a grouped entry: one entity per item, the wing as an alias, the heading in the remarks
        SanctionedEntity pla = byId(entities, "in-mha-ua-4-1");
        assertThat(pla.primaryName().fullName()).isEqualTo("Peoples' Liberation Army (PLA)");
        assertThat(pla.aliases())
                .extracting(NameInfo::fullName, NameInfo::strength)
                .containsExactly(
                        tuple("Revolutionary People's Front (RPF)", NameStrength.STRONG),
                        tuple("PLA", NameStrength.WEAK),
                        tuple("RPF", NameStrength.WEAK));
        assertThat(pla.remarks())
                .startsWith(
                        "Entry 4 (i) of the ministry's list of unlawful associations (Section 3): Peoples' Liberation Army (PLA) and its political wing, the Revolutionary People's Front (RPF) (under \"Meitei Extremist Organizations\")");
        assertThat(byId(entities, "in-mha-ua-4-3").aliases())
                .extracting(NameInfo::fullName)
                .containsExactly("Red Army", "PREPAK");
        assertThat(byId(entities, "in-mha-ua-4-4").aliases())
                .extracting(NameInfo::fullName)
                .containsExactly("Red Army", "KCP");
        assertThat(byId(entities, "in-mha-ua-4-6").primaryName().fullName())
                .isEqualTo("Coordination Committee (CorCom)");

        // the fronts an entry enumerates become aliases; a one-word place stays with its name
        SanctionedEntity pfi = byId(entities, "in-mha-ua-13");
        assertThat(pfi.primaryName().fullName()).isEqualTo("Popular Front of India (PFI)");
        assertThat(pfi.aliases())
                .filteredOn(a -> a.strength() == NameStrength.STRONG)
                .extracting(NameInfo::fullName)
                .containsExactly(
                        "Rehab India Foundation (RIF)",
                        "Campus Front of India (CFI)",
                        "All India Imams Council (AIIC)",
                        "National Confederation of Human Rights Organization (NCHRO)",
                        "National Women's Front",
                        "Junior Front",
                        "Empower India Foundation",
                        "Rehab Foundation, Kerala");

        // factions listed after "namely" become aliases, the leader clause is dropped
        SanctionedEntity jkpl = byId(entities, "in-mha-ua-21");
        assertThat(jkpl.primaryName().fullName())
                .isEqualTo("Jammu and Kashmir Peoples League (JKPL)");
        assertThat(jkpl.aliases())
                .extracting(NameInfo::fullName)
                .containsExactly(
                        "JKPL (Mukhtar Ahmed Waza)",
                        "JKPL (Bashir Ahmad Tota)",
                        "JKPL (Ghulam Mohammad Khan @Sopori)",
                        "JKPL (Aziz Sheikh)",
                        "JKPL");

        // a bare acronym after a slash is a weak alias, not a name of its own
        SanctionedEntity mljk = byId(entities, "in-mha-ua-15");
        assertThat(mljk.primaryName().fullName())
                .isEqualTo("Muslim League Jammu Kashmir (Masarat Alam faction)");
        assertThat(mljk.aliases())
                .extracting(NameInfo::fullName, NameInfo::strength)
                .containsExactly(tuple("MLJK-MA", NameStrength.WEAK));

        assertThat(byId(entities, "in-mha-ua-10").primaryName().fullName())
                .isEqualTo("Jamaat-e-Islami (JeI), Jammu and Kashmir");
        assertThat(byId(entities, "in-mha-ua-8").primaryName().fullName())
                .isEqualTo("National Socialist Council of Nagaland (Khaplang) [NSCN (K)]");
        assertThat(byId(entities, "in-mha-ua-23").primaryName().fullName())
                .isEqualTo("Awami Action Committee (AAC)");
    }

    @Test
    void shouldFailOnTextWithoutEntries() {
        assertThatThrownBy(
                        () ->
                                InMhaOrgProvider.parse(
                                        "Nothing numbered here.",
                                        Schedule.TERRORIST,
                                        TERRORIST_PDF))
                .isInstanceOf(ListIngestionException.class)
                .hasMessageContaining("no numbered entries");
    }

    @Test
    void shouldReadNamesOutOfAnEntry() {
        assertThat(InMhaOrgProvider.names("Al Badr").primary()).isEqualTo("Al Badr");
        assertThat(
                        InMhaOrgProvider.names(
                                        "The Khalistan Liberation Force and all its manifestations.")
                                .primary())
                .isEqualTo("The Khalistan Liberation Force");
        assertThat(
                        InMhaOrgProvider.names(
                                        "Indian Mujahideen, all its formations and front organizations")
                                .primary())
                .isEqualTo("Indian Mujahideen");
        assertThat(InMhaOrgProvider.names("Coordination Committee (CorCom) and").aliases())
                .extracting(NameInfo::fullName)
                .containsExactly("CorCom");
        assertThat(
                        InMhaOrgProvider.names(
                                        "Kangleipak Communist Party (KCP) and its armed wing, also called the ‘Red Army’")
                                .aliases())
                .extracting(NameInfo::fullName)
                .containsExactly("Red Army", "KCP");
    }

    private static List<SanctionedEntity> parse(String resource, Schedule schedule, URI file)
            throws IOException, ListIngestionException {
        try (InputStream in = InMhaOrgProviderTest.class.getResourceAsStream("/" + resource)) {
            assertThat(in).as(resource).isNotNull();
            return InMhaOrgProvider.parse(
                    InMhaOrgProvider.extractText(in.readAllBytes()), schedule, file);
        }
    }

    private static SanctionedEntity byId(List<SanctionedEntity> entities, String id) {
        return entities.stream()
                .filter(e -> e.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no entity " + id));
    }
}
