package dev.sieve.ingest.mc;

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
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fetches and parses the Monaco national fund-freezing list.
 *
 * <p>Mirrors EU consolidated designations plus national additions. Published as JSON.
 * Typically contains ~5,998 entities.
 *
 * @see <a href="https://geldefonds.gouv.mc/">Monaco Fund Freezing Registry</a>
 */
public final class McFundFreezingProvider extends AbstractListProvider {

    private static final String DEFAULT_URL =
            "https://geldefonds.gouv.mc/directdownload/sanctions.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter MC_DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public McFundFreezingProvider() {
        super(ListSource.MC_FUND_FREEZING, URI.create(DEFAULT_URL), "application/json");
    }

    public McFundFreezingProvider(URI sourceUri) {
        super(ListSource.MC_FUND_FREEZING, sourceUri, "application/json");
    }

    public McFundFreezingProvider(URI sourceUri, HttpClient httpClient) {
        super(ListSource.MC_FUND_FREEZING, sourceUri, "application/json", httpClient,
                Duration.ofSeconds(120));
    }

    @Override
    @SuppressWarnings("unchecked")
    protected List<SanctionedEntity> parseResponse(byte[] responseBody)
            throws ListIngestionException {
        try {
            List<Map<String, Object>> records =
                    MAPPER.readValue(responseBody, List.class);

            List<SanctionedEntity> entities = new ArrayList<>(records.size());
            for (Map<String, Object> record : records) {
                try {
                    SanctionedEntity entity = parseRecord(record);
                    if (entity != null) entities.add(entity);
                } catch (Exception e) {
                    log.debug("Skipping malformed MC record: {}", e.getMessage());
                }
            }
            return entities;
        } catch (ListIngestionException e) {
            throw e;
        } catch (Exception e) {
            throw new ListIngestionException(
                    "Failed to parse Monaco fund-freezing JSON: " + e.getMessage(),
                    ListSource.MC_FUND_FREEZING, e);
        }
    }

    @SuppressWarnings("unchecked")
    private SanctionedEntity parseRecord(Map<String, Object> record) {
        String state = strVal(record, "state");
        if ("withdrawal".equals(state)) return null;

        Object mesureIdObj = record.get("mesureId");
        String id = mesureIdObj != null ? mesureIdObj.toString() : null;

        String nature = strVal(record, "nature");
        EntityType entityType = EntityType.INDIVIDUAL;
        if (nature != null) {
            String nl = nature.toLowerCase();
            if (nl.contains("morale") || nl.contains("entit")) {
                entityType = EntityType.ENTITY;
            }
        }

        String familyName = strVal(record, "nom");
        if (familyName == null || familyName.isBlank()) return null;

        Map<String, Object> details = (Map<String, Object>) record.get("mesureDetails");
        String givenName = null;
        List<NameInfo> aliases = new ArrayList<>();
        List<String> nationalities = new ArrayList<>();
        List<LocalDate> datesOfBirth = new ArrayList<>();
        List<String> placesOfBirth = new ArrayList<>();
        List<SanctionsProgram> programs = new ArrayList<>();

        if (details != null) {
            givenName = strVal(details, "prenom");

            String aliasStr = strVal(details, "alias");
            if (aliasStr != null && !aliasStr.isBlank()) {
                for (String a : aliasStr.split(";")) {
                    String trimmed = a.strip();
                    if (!trimmed.isEmpty()) {
                        aliases.add(new NameInfo(
                                trimmed, null, null, null, null, NameType.AKA, null, null));
                    }
                }
            }

            String natStr = strVal(details, "nationalite");
            if (natStr != null && !natStr.isBlank()) {
                for (String n : natStr.split("[,/;]")) {
                    String trimmed = n.strip();
                    if (!trimmed.isEmpty()) nationalities.add(trimmed);
                }
            }

            String dobStr = strVal(details, "dateNaissance");
            if (dobStr != null) {
                LocalDate dob = parseDateSafe(dobStr);
                if (dob != null) datesOfBirth.add(dob);
            }

            String pob = strVal(details, "lieuNaissance");
            if (pob != null && !pob.isBlank()) placesOfBirth.add(pob.strip());

            String regime = strVal(details, "regimeSanction");
            if (regime != null && !regime.isBlank()) {
                programs.add(new SanctionsProgram(
                        regime.strip(), null, ListSource.MC_FUND_FREEZING));
            }
        }

        String fullName = givenName != null && !givenName.isBlank()
                ? givenName + " " + familyName : familyName;
        if (id == null) id = String.valueOf(fullName.hashCode());

        NameInfo primaryName = new NameInfo(
                fullName, givenName, familyName, null, null, NameType.PRIMARY, null, null);

        return new SanctionedEntity(
                "mc-" + id, entityType, ListSource.MC_FUND_FREEZING,
                primaryName, aliases, List.of(), List.of(),
                nationalities, List.of(), datesOfBirth, placesOfBirth,
                null, programs, null, Instant.now());
    }

    private static String strVal(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val == null) return null;
        String s = val.toString();
        return s.isBlank() ? null : s;
    }

    private static LocalDate parseDateSafe(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) return null;
        String cleaned = dateStr.strip().replaceAll(",\\s*$", "");
        try { return LocalDate.parse(cleaned, MC_DATE_FORMAT); }
        catch (DateTimeParseException e) {
            try { return LocalDate.parse(cleaned); }
            catch (DateTimeParseException e2) { return null; }
        }
    }
}
