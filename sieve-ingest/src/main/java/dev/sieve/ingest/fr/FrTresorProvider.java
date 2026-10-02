package dev.sieve.ingest.fr;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.Address;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.Identifier;
import dev.sieve.core.model.IdentifierType;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fetches and parses the French Trésor (DG) national asset-freeze list.
 *
 * <p>Published by the Direction Générale du Trésor as JSON via a public REST API. Contains national
 * and EU-derived designations. Typically contains ~5,900 entities.
 *
 * @see <a href="https://gels-avoirs.dgtresor.gouv.fr/">French Trésor Sanctions</a>
 */
public final class FrTresorProvider extends AbstractListProvider {

    private static final String DEFAULT_URL =
            "https://gels-avoirs.dgtresor.gouv.fr/ApiPublic/api/v1/publication/derniere-publication-fichier-json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public FrTresorProvider() {
        super(ListSource.FR_TRESOR, URI.create(DEFAULT_URL), "application/json");
    }

    public FrTresorProvider(URI sourceUri) {
        super(ListSource.FR_TRESOR, sourceUri, "application/json");
    }

    public FrTresorProvider(URI sourceUri, HttpClient httpClient) {
        super(
                ListSource.FR_TRESOR,
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
            Map<String, Object> root =
                    (Map<String, Object>) MAPPER.readValue(responseBody, Object.class);

            // Navigate: Publications.PublicationDetail[]
            Map<String, Object> publications = (Map<String, Object>) root.get("Publications");
            if (publications == null) {
                throw new ListIngestionException(
                        "Missing 'Publications' key in FR Trésor JSON", ListSource.FR_TRESOR);
            }
            List<Map<String, Object>> entries =
                    (List<Map<String, Object>>) publications.get("PublicationDetail");
            if (entries == null) {
                throw new ListIngestionException(
                        "Missing 'PublicationDetail' in FR Trésor JSON", ListSource.FR_TRESOR);
            }

            List<SanctionedEntity> entities = new ArrayList<>(entries.size());
            for (Map<String, Object> entry : entries) {
                SanctionedEntity entity = parseEntry(entry);
                if (entity != null) entities.add(entity);
            }
            return entities;

        } catch (ListIngestionException e) {
            throw e;
        } catch (Exception e) {
            throw new ListIngestionException(
                    "Failed to parse French Trésor JSON: " + e.getMessage(),
                    ListSource.FR_TRESOR,
                    e);
        }
    }

    @SuppressWarnings("unchecked")
    private SanctionedEntity parseEntry(Map<String, Object> entry) {
        String id = stringVal(entry, "IdRegistre");
        String familyName = stringVal(entry, "Nom");
        if (familyName == null) return null;

        String typeStr = stringVal(entry, "Nature");
        EntityType entityType =
                typeStr != null && typeStr.toLowerCase().contains("physique")
                        ? EntityType.INDIVIDUAL
                        : EntityType.ENTITY;

        // Extract typed fields from RegistreDetail[]
        String givenName = null;
        List<NameInfo> aliases = new ArrayList<>();
        List<String> nationalities = new ArrayList<>();
        List<LocalDate> datesOfBirth = new ArrayList<>();
        List<String> placesOfBirth = new ArrayList<>();
        List<Address> addresses = new ArrayList<>();
        List<Identifier> identifiers = new ArrayList<>();
        List<SanctionsProgram> programs = new ArrayList<>();

        Object detailObj = entry.get("RegistreDetail");
        if (detailObj instanceof List) {
            for (Object item : (List<Object>) detailObj) {
                if (!(item instanceof Map)) continue;
                Map<String, Object> detail = (Map<String, Object>) item;
                String typeChamp = stringVal(detail, "TypeChamp");
                if (typeChamp == null) continue;
                List<Map<String, Object>> valeurs = getValeurs(detail);

                switch (typeChamp) {
                    case "PRENOM" -> {
                        for (Map<String, Object> v : valeurs) {
                            String p = stringVal(v, "Prenom");
                            if (p != null && givenName == null) givenName = p;
                        }
                    }
                    case "ALIAS" -> {
                        for (Map<String, Object> v : valeurs) {
                            String a = stringVal(v, "Alias");
                            if (a != null && !a.isBlank()) {
                                aliases.add(
                                        new NameInfo(
                                                a,
                                                null,
                                                null,
                                                null,
                                                null,
                                                NameType.AKA,
                                                null,
                                                null));
                            }
                        }
                    }
                    case "NATIONALITE" -> {
                        for (Map<String, Object> v : valeurs) {
                            String nat = stringVal(v, "Pays");
                            if (nat == null) nat = stringVal(v, "Nationalite");
                            if (nat != null) nationalities.add(nat);
                        }
                    }
                    case "DATE_DE_NAISSANCE" -> {
                        for (Map<String, Object> v : valeurs) {
                            LocalDate dob =
                                    parseDmy(
                                            stringVal(v, "Jour"),
                                            stringVal(v, "Mois"),
                                            stringVal(v, "Annee"));
                            if (dob != null) datesOfBirth.add(dob);
                        }
                    }
                    case "LIEU_DE_NAISSANCE" -> {
                        for (Map<String, Object> v : valeurs) {
                            String lieu = stringVal(v, "Lieu");
                            if (lieu == null) lieu = stringVal(v, "LieuDeNaissance");
                            if (lieu != null) placesOfBirth.add(lieu);
                        }
                    }
                    case "ADRESSE" -> {
                        for (Map<String, Object> v : valeurs) {
                            String addr = stringVal(v, "Adresse");
                            if (addr != null) {
                                addresses.add(new Address(addr, null, null, null, null, addr));
                            }
                        }
                    }
                    case "PASSEPORT" -> {
                        for (Map<String, Object> v : valeurs) {
                            String num = stringVal(v, "Numero");
                            if (num == null) num = stringVal(v, "Passeport");
                            if (num != null) {
                                identifiers.add(
                                        new Identifier(IdentifierType.PASSPORT, num, null, null));
                            }
                        }
                    }
                    case "IDENTIFICATION" -> {
                        for (Map<String, Object> v : valeurs) {
                            String num = stringVal(v, "Numero");
                            if (num == null) num = stringVal(v, "Identification");
                            if (num != null) {
                                identifiers.add(
                                        new Identifier(
                                                IdentifierType.NATIONAL_ID, num, null, null));
                            }
                        }
                    }
                    case "FONDEMENT_JURIDIQUE" -> {
                        for (Map<String, Object> v : valeurs) {
                            String label = stringVal(v, "FondementJuridiqueLabel");
                            if (label != null) {
                                programs.add(
                                        new SanctionsProgram(label, null, ListSource.FR_TRESOR));
                            }
                        }
                    }
                    default -> {
                        /* skip other field types */
                    }
                }
            }
        }

        String fullName = givenName != null ? givenName + " " + familyName : familyName;
        if (id == null) id = String.valueOf(fullName.hashCode());

        NameInfo primaryName =
                new NameInfo(
                        fullName, givenName, familyName, null, null, NameType.PRIMARY, null, null);

        return new SanctionedEntity(
                "fr-" + id,
                entityType,
                ListSource.FR_TRESOR,
                primaryName,
                aliases,
                addresses,
                identifiers,
                nationalities,
                List.of(),
                datesOfBirth,
                placesOfBirth,
                null,
                programs,
                null,
                Instant.now());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> getValeurs(Map<String, Object> detail) {
        Object v = detail.get("Valeur");
        if (v instanceof List) return (List<Map<String, Object>>) v;
        return List.of();
    }

    private static String stringVal(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val == null) return null;
        String s = val.toString();
        return s.isBlank() ? null : s;
    }

    private static LocalDate parseDmy(String day, String month, String year) {
        if (year == null) return null;
        try {
            int y = Integer.parseInt(year.strip());
            int m = month != null ? Integer.parseInt(month.strip()) : 1;
            int d = day != null ? Integer.parseInt(day.strip()) : 1;
            return LocalDate.of(y, m, d);
        } catch (Exception e) {
            return null;
        }
    }
}
