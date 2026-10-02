package dev.sieve.ingest.qa;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.SanctionsProgram;
import dev.sieve.ingest.AbstractListProvider;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fetches and parses the Qatar NCTC (National Counter Terrorism Committee) sanctions list.
 *
 * <p>Published by Qatar's National Counter Terrorism Committee as JSON. Contains both UN-mandated
 * and national sanctions designations (Targeted Financial Sanctions). Typically contains ~700
 * entities (persons and organizations).
 *
 * @see <a
 *     href="https://www.moci.gov.qa/en/about-the-ministry/anti-money-laundering-and-terrorism-financing/legal-framework/unified-record-of-persons-and-entities-designated-on-sanction-list/">
 *     Qatar NCTC Sanctions</a>
 */
public final class QaNctcProvider extends AbstractListProvider {

    private static final String DEFAULT_URL =
            "https://portal.moi.gov.qa/wps/portal/NCTC/sanctionlist/unifiedsanctionlist/!ut/p/z1/jY_BDoIwAEM_aXUbIMdByLa4iZgRcBeyk1mi6MH4_RL16qS3Jq9tSjwZiZ_DM57DI97mcFn8yeeTLimnilPT1lygY5V2lllImZHhDWTKNJLvsJeFq9C1VLn8qCiwIX5NHj8ksC6fAHy6fiD-MyGsBuUwbVMvDaVyqmbA9lB8gdTFfyP3a9-PiPoFJNS7hg!!/dz/d5/L3dDZyEvUUZRSS9ZTlEh/p0/IZ7_I9242H42LOC4A0Q3BITM3M0G85=CZ6_I9242H42LOC4A0Q3BITM3M0GG5=NJgetSanctionList=/?lang=en&name=&qid=&passport=&listType=";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public QaNctcProvider() {
        super(ListSource.QA_NCTC, URI.create(DEFAULT_URL), "application/json");
    }

    public QaNctcProvider(URI sourceUri) {
        super(ListSource.QA_NCTC, sourceUri, "application/json");
    }

    public QaNctcProvider(URI sourceUri, HttpClient httpClient) {
        super(
                ListSource.QA_NCTC,
                sourceUri,
                "application/json",
                httpClient,
                Duration.ofSeconds(120));
    }

    @Override
    @SuppressWarnings("unchecked")
    protected List<SanctionedEntity> parseResponse(byte[] responseBody)
            throws ListIngestionException {
        try {
            Map<String, Object> root = MAPPER.readValue(responseBody, Map.class);
            List<Map<String, Object>> items = (List<Map<String, Object>>) root.get("content");
            if (items == null) {
                throw new ListIngestionException(
                        "QA NCTC JSON missing 'content' field", ListSource.QA_NCTC);
            }

            List<SanctionedEntity> entities = new ArrayList<>(items.size());
            for (Map<String, Object> item : items) {
                SanctionedEntity entity = parseItem(item);
                if (entity != null) entities.add(entity);
            }
            return entities;

        } catch (ListIngestionException e) {
            throw e;
        } catch (Exception e) {
            throw new ListIngestionException(
                    "Failed to parse QA NCTC JSON: " + e.getMessage(), ListSource.QA_NCTC, e);
        }
    }

    private SanctionedEntity parseItem(Map<String, Object> item) {
        String dataId = stringVal(item, "dataId");
        String typ = stringVal(item, "typ");
        String fullNameEn = stringVal(item, "fullNameEn");
        if (fullNameEn == null || fullNameEn.isBlank()) return null;

        // typ "1" = Person, "2" = Organization
        EntityType entityType = "1".equals(typ) ? EntityType.INDIVIDUAL : EntityType.ENTITY;

        // Build the primary name from name parts
        String firstName = stringVal(item, "firstNameEN");
        String secondName = stringVal(item, "secondNameEN");
        String thirdName = stringVal(item, "thirdNameEN");
        String fourthName = stringVal(item, "fourthNameEN");

        NameInfo primaryName =
                new NameInfo(fullNameEn, firstName, null, null, null, NameType.PRIMARY, null, null);

        // Arabic name as alias
        List<NameInfo> aliases = new ArrayList<>();
        String fullNameAr = stringVal(item, "fullNameAr");
        if (fullNameAr != null && !fullNameAr.isBlank() && !fullNameAr.equals(fullNameEn)) {
            aliases.add(new NameInfo(fullNameAr, null, null, null, null, NameType.AKA, null, null));
        }

        // Parse comma/semicolon-separated aliases
        String aliasStr = stringVal(item, "aliases");
        if (aliasStr != null && !aliasStr.isBlank()) {
            for (String a : aliasStr.split("[;]")) {
                String trimmed = a.strip();
                if (!trimmed.isEmpty() && !trimmed.equals(fullNameEn)) {
                    aliases.add(
                            new NameInfo(
                                    trimmed, null, null, null, null, NameType.AKA, null, null));
                }
            }
        }

        // Date of birth (for persons)
        List<LocalDate> datesOfBirth = new ArrayList<>();
        List<String> nationalities = new ArrayList<>();
        List<String> identifierNotes = new ArrayList<>();

        if (entityType == EntityType.INDIVIDUAL) {
            String dobFormat = stringVal(item, "dobFormat");
            if (dobFormat != null) {
                LocalDate dob = parseDateSafe(dobFormat);
                if (dob != null) datesOfBirth.add(dob);
            }
            String nationality = stringVal(item, "nationality");
            if (nationality != null && !nationality.isBlank()) {
                nationalities.add(nationality.strip());
            }
        }

        // Sanctions program
        List<SanctionsProgram> programs = new ArrayList<>();
        Object sanctionsDto = item.get("sanctionsDTO");
        if (sanctionsDto instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> sanctions = (Map<String, Object>) sanctionsDto;
            String programEn = stringVal(sanctions, "sanctionRegimeEn");
            if (programEn != null && !programEn.isBlank()) {
                programs.add(
                        new SanctionsProgram(
                                programEn.strip(), programEn.strip(), ListSource.QA_NCTC));
            }
        }
        if (programs.isEmpty()) {
            programs.add(
                    new SanctionsProgram("QA NCTC", "Qatar NCTC Sanctions", ListSource.QA_NCTC));
        }

        // Listed date
        Instant listedDate = null;
        String listedOn = stringVal(item, "listedOn");
        if (listedOn != null) {
            LocalDate ld = parseDateSafe(listedOn);
            if (ld != null) {
                listedDate = ld.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
            }
        }

        String id = dataId != null ? dataId : String.valueOf(fullNameEn.hashCode());

        return new SanctionedEntity(
                "qa-" + id,
                entityType,
                ListSource.QA_NCTC,
                primaryName,
                aliases,
                List.of(),
                List.of(),
                nationalities,
                List.of(),
                datesOfBirth,
                List.of(),
                null,
                programs,
                listedDate,
                Instant.now());
    }

    private static String stringVal(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val == null) return null;
        String s = val.toString();
        return s.isBlank() ? null : s;
    }

    private static LocalDate parseDateSafe(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) return null;
        String cleaned = dateStr.strip();
        // Try ISO format first
        try {
            return LocalDate.parse(cleaned);
        } catch (DateTimeParseException e) {
            /* try next */
        }
        // Try dd/MM/yyyy
        try {
            return LocalDate.parse(
                    cleaned, java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        } catch (DateTimeParseException e) {
            /* try next */
        }
        // Try dd-MM-yyyy
        try {
            return LocalDate.parse(
                    cleaned, java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy"));
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
