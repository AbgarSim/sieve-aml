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

    private static final URI PAGE = URI.create("https://www.mha.gov.in/banned-organizations");
    private static final URI TERRORIST_PDF =
            URI.create(
                    "https://www.mha.gov.in/files/documents/List46TerroristOrganisationsWebsite.pdf");
    private static final URI UNLAWFUL_PAGE =
            URI.create(
                    "https://www.mha.gov.in/unlawful-associations-section3-unlawful-activities-prevention-act-1967");
    private static final URI UNLAWFUL_PDF =
            URI.create(
                    "https://www.mha.gov.in/sites/default/files/2025-03/ListUNLAWFUL_17032025.pdf");

    /** The page as the redesigned site has served it since October 2026, trimmed. */
    private static final String PAGE_HTML =
            """
            <div class="views-table customTable cols-2">
            <div class="mt-2 customTablebdr"> <div class="row align-items-lg-center">
            <div class="col-md-6 col-lg-8 mb-2 "> <div class="documentRecordTitle"> UNLAWFUL ASSOCIATIONS UNDER SECTION 3 OF UNLAWFUL ACTIVITIES (PREVENTION) ACT, 1967 </div> </div>
            <div class="col text-end"> <div class="viewButton"> <a href="/unlawful-associations-section3-unlawful-activities-prevention-act-1967" class="viewInternalBtn" target="_self" title="VISIT">VISIT</a> </div> </div>
            </div> </div>
            <div class="mt-2 customTablebdr"> <div class="row align-items-lg-center">
            <div class="col-md-6 col-lg-8 mb-2 "> <div class="documentRecordTitle"> TERRORIST ORGANISATIONS LISTED IN THE FIRST SCHEDULE OF THE UNLAWFUL ACTIVITIES (PREVENTION) ACT, 1967 </div> </div>
            <div class="col text-start"> <div class="fileSize">100.11 KB</div> </div>
            <div class="col text-end"> <div class="viewButton"> <a href="/files/documents/List46TerroristOrganisationsWebsite.pdf" class="viewBtn" target="_blank" title="Open PDF file in new window">VIEW</a> </div> </div>
            </div> </div>
            <div class="mt-2 customTablebdr"> <div class="row align-items-lg-center">
            <div class="col-md-6 col-lg-8 mb-2 "> <div class="documentRecordTitle"> INDIVIDUALS TERRORISTS LISTED IN THE FOURTH SCHEDULE OF THE UNLAWFUL ACTIVITIES (PREVENTION) ACT, 1967 </div> </div>
            <div class="col text-end"> <div class="viewButton"> <a href="/individuals-terrorists-fourth-schedule-unlawful-activities-prevention-act" class="viewInternalBtn" target="_self">VISIT</a> </div> </div>
            </div> </div>
            </div>
            """;

    /**
     * The 23 rows of the unlawful associations table, oldest first, as read on 15 November 2026.
     */
    private static final List<String> UNLAWFUL_TITLES =
            List.of(
                    "Student’s Islamic Movement of India (SIMI)",
                    "United Liberation Front of Asom (ULFA)",
                    "All Tripura Tiger Force (ATTF)",
                    "Meitei Extremist Organization of Manipur namely: PLA and its political wing RPF, UNLF and its armed wing MPA, PREPAK and its armed wing the red army, KCP, KYKL, CorCom, ASUK",
                    "National Liberation Front of Tripura (NLFT)",
                    "Hynniewtrep National Liberation Council (HNLC)",
                    "Liberation Tigers of Tamil Eelam.",
                    "National Socialist Council of Nagaland (Khaplang)",
                    "Islamic Research Foundation (IRF)",
                    "Jamaat-e- Islami (JeI), Jammu and Kashmir",
                    "Jammu and Kashmir Liberation Front (Mohd. Yasin Malik faction)",
                    "Sikhs for Justice (SFJ)",
                    "Popular Front of India (PFI) and its associates or affiliates or fronts",
                    "Jammu and Kashmir Democratic Freedom Party (JKDFP)",
                    "Muslim Conference Jammu and Kashmir (Bhat faction) (MCJK-B)",
                    "Muslim League Jammu Kashmir (Masarat Alam faction) / MLJK-MA",
                    "Tehreek-e-Hurriyat, Jammu and Kashmir (TeH)",
                    "Muslim Conference Jammu and Kashmir (Sumji faction) (MCJK-S)",
                    "Jammu Kashmir National Front (JKNF)",
                    "Four factions of Jammu and Kashmir Peoples League (JKPL), namely : JKPL (Mukhtar Ahmed Waza), JKPL (Bashir Ahmad Tota), JKPL (Ghulam Mohammad Khan @Sopori) and JKPL (Aziz Sheikh) led by Yaqoob Sheikh",
                    "Jammu and Kashmir Peoples Freedom League (JKPFL)",
                    "Jammu and Kashmir Ittihadul Muslimeen (JKIM)",
                    "Awami Action Committee (AAC)");

    @Test
    void shouldReturnSource() {
        assertThat(new InMhaOrgProvider().source()).isEqualTo(ListSource.IN_MHA_ORG);
    }

    @Test
    void shouldFindWhereBothListsAreOnThePage() throws Exception {
        Map<Schedule, URI> links = InMhaOrgProvider.fileLinks(PAGE_HTML, PAGE);

        assertThat(links)
                .containsExactly(
                        Map.entry(Schedule.TERRORIST, TERRORIST_PDF),
                        Map.entry(Schedule.UNLAWFUL, UNLAWFUL_PAGE));
    }

    @Test
    void shouldParseTheUnlawfulAssociationsTable() throws Exception {
        List<MhaDocumentList.Row> rows =
                UNLAWFUL_TITLES.stream()
                        .map(
                                t ->
                                        new MhaDocumentList.Row(
                                                t,
                                                URI.create(
                                                        "https://www.mha.gov.in/files/documents/x.pdf")))
                        .toList();

        List<SanctionedEntity> entities =
                InMhaOrgProvider.parse(
                        InMhaOrgProvider.numbered(rows), Schedule.UNLAWFUL, UNLAWFUL_PAGE);

        // 23 rows, of which the Meitei heading stands for seven organisations
        assertThat(entities).hasSize(29);
        assertThat(entities)
                .extracting(SanctionedEntity::id)
                .doesNotContain("in-mha-ua-4")
                .contains("in-mha-ua-1", "in-mha-ua-4-1", "in-mha-ua-4-7", "in-mha-ua-23");

        SanctionedEntity pla = byId(entities, "in-mha-ua-4-1");
        assertThat(pla.primaryName().fullName()).isEqualTo("PLA");
        assertThat(pla.aliases()).extracting(NameInfo::fullName).containsExactly("RPF");
        assertThat(pla.remarks()).contains("(under \"Meitei Extremist Organization of Manipur\")");
        assertThat(byId(entities, "in-mha-ua-4-3").aliases())
                .extracting(NameInfo::fullName)
                .containsExactly("red army");
        assertThat(byId(entities, "in-mha-ua-4-5").primaryName().fullName()).isEqualTo("KYKL");

        assertThat(byId(entities, "in-mha-ua-13").primaryName().fullName())
                .isEqualTo("Popular Front of India (PFI)");
        assertThat(byId(entities, "in-mha-ua-10").primaryName().fullName())
                .isEqualTo("Jamaat-e-Islami (JeI), Jammu and Kashmir");
        assertThat(byId(entities, "in-mha-ua-7").primaryName().fullName())
                .isEqualTo("Liberation Tigers of Tamil Eelam");
        assertThat(byId(entities, "in-mha-ua-20").primaryName().fullName())
                .isEqualTo("Jammu and Kashmir Peoples League (JKPL)");
        assertThat(byId(entities, "in-mha-ua-20").aliases())
                .extracting(NameInfo::fullName)
                .contains("JKPL (Mukhtar Ahmed Waza)", "JKPL (Aziz Sheikh)");
        assertThat(byId(entities, "in-mha-ua-23").primaryName().fullName())
                .isEqualTo("Awami Action Committee (AAC)");
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
