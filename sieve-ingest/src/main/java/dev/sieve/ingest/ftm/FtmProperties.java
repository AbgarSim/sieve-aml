package dev.sieve.ingest.ftm;

import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.IdentifierType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Which FollowTheMoney properties each entity kind accepts, and which property holds each kind of
 * identifier. Writing a property a schema does not define makes FollowTheMoney tools reject the
 * whole entity, so the writer checks every property against this table.
 */
final class FtmProperties {

    private static final Set<String> THING =
            Set.of(
                    "name",
                    "alias",
                    "notes",
                    "country",
                    "topics",
                    "address",
                    "program",
                    "programId",
                    "createdAt",
                    "modifiedAt",
                    "sourceUrl");

    private static final Set<String> LEGAL_ENTITY =
            union(
                    THING,
                    Set.of(
                            "idNumber",
                            "taxNumber",
                            "registrationNumber",
                            "swiftBic",
                            "leiCode",
                            "jurisdiction"));

    private static final Map<EntityType, Set<String>> ACCEPTED = new EnumMap<>(EntityType.class);

    static {
        ACCEPTED.put(
                EntityType.INDIVIDUAL,
                union(
                        LEGAL_ENTITY,
                        Set.of(
                                "firstName",
                                "lastName",
                                "middleName",
                                "title",
                                "birthDate",
                                "birthPlace",
                                "nationality",
                                "citizenship",
                                "passportNumber",
                                "gender")));
        ACCEPTED.put(EntityType.ENTITY, LEGAL_ENTITY);
        ACCEPTED.put(EntityType.COMPANY, union(LEGAL_ENTITY, Set.of("imoNumber", "currency")));
        ACCEPTED.put(EntityType.ORGANIZATION, union(LEGAL_ENTITY, Set.of("imoNumber")));
        ACCEPTED.put(
                EntityType.VESSEL,
                union(THING, Set.of("imoNumber", "mmsi", "registrationNumber", "flag")));
        ACCEPTED.put(EntityType.AIRCRAFT, union(THING, Set.of("registrationNumber")));
        ACCEPTED.put(EntityType.CRYPTO_WALLET, union(THING, Set.of("publicKey", "currency")));
        ACCEPTED.put(
                EntityType.SECURITY,
                union(THING, Set.of("isin", "registrationNumber", "issuer", "currency")));
    }

    private static final Map<IdentifierType, String> IDENTIFIER_PROPERTY =
            new EnumMap<>(
                    Map.ofEntries(
                            Map.entry(IdentifierType.PASSPORT, "passportNumber"),
                            Map.entry(IdentifierType.NATIONAL_ID, "idNumber"),
                            Map.entry(IdentifierType.TAX_ID, "taxNumber"),
                            Map.entry(IdentifierType.IMO_NUMBER, "imoNumber"),
                            Map.entry(IdentifierType.MMSI, "mmsi"),
                            Map.entry(IdentifierType.REGISTRATION_NUMBER, "registrationNumber"),
                            Map.entry(IdentifierType.SWIFT_BIC, "swiftBic"),
                            Map.entry(IdentifierType.LEI, "leiCode"),
                            Map.entry(IdentifierType.BUSINESS_REGISTRATION, "registrationNumber"),
                            Map.entry(IdentifierType.ISIN, "isin"),
                            Map.entry(IdentifierType.CRYPTO_ADDRESS, "publicKey"),
                            Map.entry(IdentifierType.OTHER, "idNumber")));

    /** Properties tried, in order, when a kind lacks the identifier's own property. */
    private static final List<String> IDENTIFIER_FALLBACK =
            List.of("idNumber", "registrationNumber");

    private FtmProperties() {}

    static boolean accepts(EntityType type, String property) {
        return ACCEPTED.get(type).contains(property);
    }

    /**
     * Returns the property an identifier is written to on an entity of the given kind, or empty if
     * the kind has no property that can hold it.
     */
    static Optional<String> identifierProperty(EntityType type, IdentifierType identifier) {
        String own = IDENTIFIER_PROPERTY.get(identifier);
        if (accepts(type, own)) {
            return Optional.of(own);
        }
        return IDENTIFIER_FALLBACK.stream().filter(p -> accepts(type, p)).findFirst();
    }

    /** Returns the identifier type a property holds on an entity of the given kind, if any. */
    static Optional<IdentifierType> identifierType(EntityType type, String property) {
        return Optional.ofNullable(
                switch (property) {
                    case "passportNumber" -> IdentifierType.PASSPORT;
                    case "idNumber" -> IdentifierType.NATIONAL_ID;
                    case "taxNumber" -> IdentifierType.TAX_ID;
                    case "imoNumber" -> IdentifierType.IMO_NUMBER;
                    case "mmsi" -> IdentifierType.MMSI;
                    case "registrationNumber" ->
                            type.isLegalEntity()
                                    ? IdentifierType.BUSINESS_REGISTRATION
                                    : IdentifierType.REGISTRATION_NUMBER;
                    case "swiftBic" -> IdentifierType.SWIFT_BIC;
                    case "leiCode" -> IdentifierType.LEI;
                    case "isin" -> IdentifierType.ISIN;
                    case "publicKey" -> IdentifierType.CRYPTO_ADDRESS;
                    default -> null;
                });
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        java.util.HashSet<String> all = new java.util.HashSet<>(a);
        all.addAll(b);
        return Set.copyOf(all);
    }
}
