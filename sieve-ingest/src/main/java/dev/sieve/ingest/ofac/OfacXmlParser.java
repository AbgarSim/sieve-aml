package dev.sieve.ingest.ofac;

import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.Address;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.Identifier;
import dev.sieve.core.model.IdentifierType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.NameStrength;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.Relation;
import dev.sieve.core.model.RelationType;
import dev.sieve.core.model.RiskTopic;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.SanctionsProgram;
import dev.sieve.core.model.ScriptType;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses the XML OFAC publishes for the SDN list and for the consolidated non-SDN list, which share
 * one schema, into the entities of the list the parser is created for.
 *
 * <p>Every {@code sdnEntry} gives an entity whose id is the list's prefix followed by the entry's
 * uid, with the entry's name, aliases, addresses, identifiers, nationalities, citizenships, dates
 * and places of birth, programs and remarks.
 *
 * <p>Every digital currency address an entry lists (OFAC's identifier types {@code Digital Currency
 * Address - XBT}, {@code Digital Currency Address - ETH} and so on) stays on the entry as a {@link
 * IdentifierType#CRYPTO_ADDRESS} identifier whose remarks hold the currency code, and also becomes
 * a {@link EntityType#CRYPTO_WALLET} entity of its own with id {@code
 * <prefix><uid>-wallet-<address>}: named by the address, with an identifier per currency the
 * address is listed under, the holder's programs and a remark naming the holder. The holder carries
 * an {@link RelationType#OWNERSHIP} relation to each of its wallets and each wallet a {@link
 * RelationType#LINKED} relation back, both with the role {@value #WALLET_HOLDER_ROLE}.
 *
 * <p>The links OFAC states in words become relations too (see {@link OfacLinks}): every "Linked
 * To:" name in an entry's remarks that is the primary name or an alias of exactly one other entry
 * gives a {@link RelationType#LINKED} relation to that entry, and the owner a vessel's {@code
 * vesselInfo} names gives an {@link RelationType#OWNERSHIP} relation from the owner to the vessel.
 */
final class OfacXmlParser {

    private static final Logger log = LoggerFactory.getLogger(OfacXmlParser.class);

    private static final DateTimeFormatter OFAC_DATE_FORMAT =
            DateTimeFormatter.ofPattern("MM/dd/yyyy");

    /** Start of OFAC's identifier types for digital currency addresses; the currency follows. */
    static final String DIGITAL_CURRENCY_TYPE = "digital currency address";

    /** Joins a holder's id and a digital currency address into the wallet entity's id. */
    static final String WALLET_ID_INFIX = "-wallet-";

    /** Role on the relations between a holder and its wallets, in both directions. */
    static final String WALLET_HOLDER_ROLE = "holder";

    private final ListSource source;
    private final String idPrefix;
    private final String label;

    /**
     * Creates a parser for one of the two lists.
     *
     * @param source the list the entities belong to
     * @param idPrefix what every entity id starts with, before the entry's uid, such as {@code
     *     ofac-sdn-}
     * @param label the list's name in log and error messages
     */
    OfacXmlParser(ListSource source, String idPrefix, String label) {
        this.source = Objects.requireNonNull(source, "source must not be null");
        this.idPrefix = Objects.requireNonNull(idPrefix, "idPrefix must not be null");
        this.label = Objects.requireNonNull(label, "label must not be null");
    }

    /**
     * Parses the list's XML with StAX streaming: every {@code sdnEntry} gives an entity, plus a
     * wallet entity per digital currency address it lists, and the links the list states in words
     * are resolved to relations afterwards (see {@link OfacLinks}).
     *
     * @param xmlContent the raw XML bytes
     * @return the entities, in list order, wallets right after their holder
     * @throws ListIngestionException if the XML is malformed or cannot be parsed
     */
    List<SanctionedEntity> parse(byte[] xmlContent) throws ListIngestionException {
        List<SanctionedEntity> entities = new ArrayList<>();
        Map<String, String> vesselOwners = new HashMap<>();
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);

        try (InputStream input = new ByteArrayInputStream(xmlContent)) {
            XMLStreamReader reader =
                    factory.createXMLStreamReader(input, StandardCharsets.UTF_8.name());

            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT
                        && "sdnEntry".equals(reader.getLocalName())) {
                    entities.addAll(parseSdnEntry(reader, vesselOwners));
                }
            }

            reader.close();
        } catch (XMLStreamException e) {
            throw new ListIngestionException(
                    "Failed to parse " + label + " XML: " + e.getMessage(), source, e);
        } catch (IOException e) {
            throw new ListIngestionException(
                    "IO error reading " + label + " XML: " + e.getMessage(), source, e);
        }

        return OfacLinks.resolve(entities, vesselOwners, label);
    }

    /**
     * Parses one {@code sdnEntry}: the entry itself followed by a wallet entity per digital
     * currency address it lists, or nothing when the entry has no uid or name. Only the entry's own
     * {@code uid}, {@code firstName} and {@code lastName} count; the ones inside its alias, address
     * and identifier elements belong to those. The owner a vessel's {@code vesselInfo} names is
     * recorded under the entry's id for {@link OfacLinks}.
     */
    private List<SanctionedEntity> parseSdnEntry(
            XMLStreamReader reader, Map<String, String> vesselOwners) throws XMLStreamException {
        String uid = null;
        String firstName = null;
        String lastName = null;
        String sdnType = null;
        String remarks = null;
        String title = null;
        String vesselOwner = null;
        List<SanctionsProgram> programs = new ArrayList<>();
        List<NameInfo> aliases = new ArrayList<>();
        List<Address> addresses = new ArrayList<>();
        List<Identifier> identifiers = new ArrayList<>();
        List<String> nationalities = new ArrayList<>();
        List<String> citizenships = new ArrayList<>();
        List<LocalDate> datesOfBirth = new ArrayList<>();
        List<String> placesOfBirth = new ArrayList<>();

        while (reader.hasNext()) {
            int event = reader.next();

            if (event == XMLStreamConstants.START_ELEMENT) {
                String elementName = reader.getLocalName();
                switch (elementName) {
                    case "uid" -> uid = readText(reader);
                    case "firstName" -> firstName = readText(reader);
                    case "lastName" -> lastName = readText(reader);
                    case "sdnType" -> sdnType = readText(reader);
                    case "remarks" -> remarks = readText(reader);
                    case "title" -> title = readText(reader);
                    case "programList" -> programs = parseProgramList(reader);
                    case "akaList" -> aliases = parseAkaList(reader);
                    case "addressList" -> addresses = parseAddressList(reader);
                    case "idList" -> identifiers = parseIdList(reader);
                    case "nationalityList" -> nationalities = parseNationalityList(reader);
                    case "citizenshipList" -> citizenships = parseCitizenshipList(reader);
                    case "dateOfBirthList" -> datesOfBirth = parseDateOfBirthList(reader);
                    case "placeOfBirthList" -> placesOfBirth = parsePlaceOfBirthList(reader);
                    case "vesselInfo" -> vesselOwner = parseVesselOwner(reader);
                    default -> {
                        /* skip unknown elements */
                    }
                }
            } else if (event == XMLStreamConstants.END_ELEMENT
                    && "sdnEntry".equals(reader.getLocalName())) {
                break;
            }
        }

        if (uid == null || lastName == null) {
            log.debug("{}: skipping an entry with no uid or lastName", label);
            return List.of();
        }

        String id = idPrefix + uid;
        if (vesselOwner != null) {
            vesselOwners.put(id, vesselOwner);
        }
        String fullName = buildFullName(firstName, lastName);
        EntityType entityType = mapSdnType(sdnType);
        NameInfo primaryName =
                new NameInfo(
                        fullName,
                        firstName,
                        lastName,
                        null,
                        title,
                        NameType.PRIMARY,
                        NameStrength.STRONG,
                        ScriptType.LATIN);

        Map<String, List<Identifier>> wallets = digitalCurrencyAddresses(identifiers);
        List<Relation> relations = new ArrayList<>();
        for (String address : wallets.keySet()) {
            relations.add(
                    new Relation(
                            RelationType.OWNERSHIP,
                            id + WALLET_ID_INFIX + address,
                            WALLET_HOLDER_ROLE,
                            null,
                            null,
                            null));
        }
        SanctionedEntity holder =
                new SanctionedEntity(
                        id,
                        entityType,
                        source,
                        primaryName,
                        aliases,
                        addresses,
                        identifiers,
                        nationalities,
                        citizenships,
                        datesOfBirth,
                        placesOfBirth,
                        remarks,
                        programs,
                        null,
                        Instant.now(),
                        Set.of(RiskTopic.SANCTION),
                        relations);

        List<SanctionedEntity> entities = new ArrayList<>();
        entities.add(holder);
        wallets.forEach((address, ids) -> entities.add(wallet(holder, address, ids)));
        return entities;
    }

    /**
     * Groups the digital currency addresses among an entry's identifiers by address, in list order.
     * OFAC lists an address once per currency it holds, so one address can carry several entries;
     * the few it repeats under the same currency are kept once.
     */
    static Map<String, List<Identifier>> digitalCurrencyAddresses(List<Identifier> identifiers) {
        Map<String, List<Identifier>> byAddress = new LinkedHashMap<>();
        for (Identifier identifier : identifiers) {
            if (identifier.type() == IdentifierType.CRYPTO_ADDRESS) {
                List<Identifier> ids =
                        byAddress.computeIfAbsent(identifier.value(), a -> new ArrayList<>());
                if (!ids.contains(identifier)) {
                    ids.add(identifier);
                }
            }
        }
        return byAddress;
    }

    /**
     * Builds the wallet entity for one of a holder's digital currency addresses: named by the
     * address, with the holder's identifiers for that address (one per currency), the holder's
     * programs, a remark naming the holder and a relation back to it.
     */
    static SanctionedEntity wallet(
            SanctionedEntity holder, String address, List<Identifier> identifiers) {
        StringJoiner currencies = new StringJoiner(", ", " (", ")");
        currencies.setEmptyValue("");
        for (Identifier identifier : identifiers) {
            if (identifier.remarks() != null) {
                currencies.add(identifier.remarks());
            }
        }
        String remarks =
                "Digital currency address"
                        + currencies
                        + " held by "
                        + holder.primaryName().fullName()
                        + " ("
                        + holder.id()
                        + ")";
        return new SanctionedEntity(
                holder.id() + WALLET_ID_INFIX + address,
                EntityType.CRYPTO_WALLET,
                holder.listSource(),
                new NameInfo(
                        address,
                        null,
                        null,
                        null,
                        null,
                        NameType.PRIMARY,
                        NameStrength.STRONG,
                        ScriptType.LATIN),
                List.of(),
                List.of(),
                identifiers,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                remarks,
                holder.programs(),
                null,
                holder.lastUpdated(),
                Set.of(RiskTopic.SANCTION),
                List.of(
                        new Relation(
                                RelationType.LINKED,
                                holder.id(),
                                WALLET_HOLDER_ROLE,
                                null,
                                null,
                                null)));
    }

    /**
     * Returns the currency code at the end of a digital currency identifier type, such as {@code
     * XBT} from {@code Digital Currency Address - XBT}, or {@code null} when there is none.
     */
    static String currency(String idType) {
        int dash = idType.lastIndexOf('-');
        if (dash < 0) {
            return null;
        }
        String code = idType.substring(dash + 1).strip();
        return code.isEmpty() ? null : code;
    }

    private List<SanctionsProgram> parseProgramList(XMLStreamReader reader)
            throws XMLStreamException {
        List<SanctionsProgram> programs = new ArrayList<>();
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT
                    && "program".equals(reader.getLocalName())) {
                String programCode = readText(reader);
                if (programCode != null && !programCode.isBlank()) {
                    programs.add(new SanctionsProgram(programCode.strip(), null, source));
                }
            } else if (event == XMLStreamConstants.END_ELEMENT
                    && "programList".equals(reader.getLocalName())) {
                break;
            }
        }
        return programs;
    }

    private List<NameInfo> parseAkaList(XMLStreamReader reader) throws XMLStreamException {
        List<NameInfo> aliases = new ArrayList<>();
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT && "aka".equals(reader.getLocalName())) {
                NameInfo alias = parseAka(reader);
                if (alias != null) {
                    aliases.add(alias);
                }
            } else if (event == XMLStreamConstants.END_ELEMENT
                    && "akaList".equals(reader.getLocalName())) {
                break;
            }
        }
        return aliases;
    }

    private NameInfo parseAka(XMLStreamReader reader) throws XMLStreamException {
        String type = null;
        String category = null;
        String firstName = null;
        String lastName = null;

        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String elementName = reader.getLocalName();
                switch (elementName) {
                    case "type" -> type = readText(reader);
                    case "category" -> category = readText(reader);
                    case "firstName" -> firstName = readText(reader);
                    case "lastName" -> lastName = readText(reader);
                    default -> {
                        /* skip */
                    }
                }
            } else if (event == XMLStreamConstants.END_ELEMENT
                    && "aka".equals(reader.getLocalName())) {
                break;
            }
        }

        if (lastName == null || lastName.isBlank()) {
            return null;
        }

        String fullName = buildFullName(firstName, lastName);
        NameType nameType = mapAkaType(type);
        NameStrength strength = mapAkaStrength(category);

        return new NameInfo(
                fullName, firstName, lastName, null, null, nameType, strength, ScriptType.LATIN);
    }

    private List<Address> parseAddressList(XMLStreamReader reader) throws XMLStreamException {
        List<Address> addresses = new ArrayList<>();
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT
                    && "address".equals(reader.getLocalName())) {
                Address address = parseAddress(reader);
                if (address != null) {
                    addresses.add(address);
                }
            } else if (event == XMLStreamConstants.END_ELEMENT
                    && "addressList".equals(reader.getLocalName())) {
                break;
            }
        }
        return addresses;
    }

    private Address parseAddress(XMLStreamReader reader) throws XMLStreamException {
        String address1 = null;
        String city = null;
        String stateOrProvince = null;
        String postalCode = null;
        String country = null;

        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String elementName = reader.getLocalName();
                switch (elementName) {
                    case "address1" -> address1 = readText(reader);
                    case "city" -> city = readText(reader);
                    case "stateOrProvince" -> stateOrProvince = readText(reader);
                    case "postalCode" -> postalCode = readText(reader);
                    case "country" -> country = readText(reader);
                    default -> {
                        /* skip */
                    }
                }
            } else if (event == XMLStreamConstants.END_ELEMENT
                    && "address".equals(reader.getLocalName())) {
                break;
            }
        }

        String fullAddress = buildFullAddress(address1, city, stateOrProvince, postalCode, country);
        return new Address(address1, city, stateOrProvince, postalCode, country, fullAddress);
    }

    /** Reads the owner named in a {@code vesselInfo} element, skipping its other fields for now. */
    private String parseVesselOwner(XMLStreamReader reader) throws XMLStreamException {
        String owner = null;
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT
                    && "vesselOwner".equals(reader.getLocalName())) {
                owner = readText(reader);
            } else if (event == XMLStreamConstants.END_ELEMENT
                    && "vesselInfo".equals(reader.getLocalName())) {
                break;
            }
        }
        return owner;
    }

    private List<Identifier> parseIdList(XMLStreamReader reader) throws XMLStreamException {
        List<Identifier> identifiers = new ArrayList<>();
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT && "id".equals(reader.getLocalName())) {
                Identifier identifier = parseId(reader);
                if (identifier != null) {
                    identifiers.add(identifier);
                }
            } else if (event == XMLStreamConstants.END_ELEMENT
                    && "idList".equals(reader.getLocalName())) {
                break;
            }
        }
        return identifiers;
    }

    private Identifier parseId(XMLStreamReader reader) throws XMLStreamException {
        String idType = null;
        String idNumber = null;
        String idCountry = null;

        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String elementName = reader.getLocalName();
                switch (elementName) {
                    case "idType" -> idType = readText(reader);
                    case "idNumber" -> idNumber = readText(reader);
                    case "idCountry" -> idCountry = readText(reader);
                    default -> {
                        /* skip */
                    }
                }
            } else if (event == XMLStreamConstants.END_ELEMENT
                    && "id".equals(reader.getLocalName())) {
                break;
            }
        }

        if (idNumber == null || idNumber.isBlank()) {
            return null;
        }

        IdentifierType type = mapIdType(idType);
        String remarks = type == IdentifierType.CRYPTO_ADDRESS ? currency(idType) : null;
        return new Identifier(type, idNumber.strip(), idCountry, remarks);
    }

    private List<String> parseNationalityList(XMLStreamReader reader) throws XMLStreamException {
        return parseSimpleItemList(reader, "nationalityList", "nationality", "country");
    }

    private List<String> parseCitizenshipList(XMLStreamReader reader) throws XMLStreamException {
        return parseSimpleItemList(reader, "citizenshipList", "citizenship", "country");
    }

    private List<String> parsePlaceOfBirthList(XMLStreamReader reader) throws XMLStreamException {
        return parseSimpleItemList(reader, "placeOfBirthList", "placeOfBirthItem", "placeOfBirth");
    }

    private List<String> parseSimpleItemList(
            XMLStreamReader reader, String listElement, String itemElement, String valueElement)
            throws XMLStreamException {
        List<String> values = new ArrayList<>();
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT
                    && itemElement.equals(reader.getLocalName())) {
                String value = parseSimpleItem(reader, itemElement, valueElement);
                if (value != null && !value.isBlank()) {
                    values.add(value.strip());
                }
            } else if (event == XMLStreamConstants.END_ELEMENT
                    && listElement.equals(reader.getLocalName())) {
                break;
            }
        }
        return values;
    }

    private String parseSimpleItem(XMLStreamReader reader, String itemElement, String valueElement)
            throws XMLStreamException {
        String value = null;
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT
                    && valueElement.equals(reader.getLocalName())) {
                value = readText(reader);
            } else if (event == XMLStreamConstants.END_ELEMENT
                    && itemElement.equals(reader.getLocalName())) {
                break;
            }
        }
        return value;
    }

    private List<LocalDate> parseDateOfBirthList(XMLStreamReader reader) throws XMLStreamException {
        List<LocalDate> dates = new ArrayList<>();
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT
                    && "dateOfBirthItem".equals(reader.getLocalName())) {
                LocalDate date = parseDateOfBirthItem(reader);
                if (date != null) {
                    dates.add(date);
                }
            } else if (event == XMLStreamConstants.END_ELEMENT
                    && "dateOfBirthList".equals(reader.getLocalName())) {
                break;
            }
        }
        return dates;
    }

    private LocalDate parseDateOfBirthItem(XMLStreamReader reader) throws XMLStreamException {
        String dateStr = null;
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT
                    && "dateOfBirth".equals(reader.getLocalName())) {
                dateStr = readText(reader);
            } else if (event == XMLStreamConstants.END_ELEMENT
                    && "dateOfBirthItem".equals(reader.getLocalName())) {
                break;
            }
        }
        return parseDateSafe(dateStr);
    }

    private String readText(XMLStreamReader reader) throws XMLStreamException {
        StringBuilder sb = new StringBuilder();
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                sb.append(reader.getText());
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                break;
            }
        }
        String text = sb.toString().strip();
        return text.isEmpty() ? null : text;
    }

    private static String buildFullName(String firstName, String lastName) {
        if (firstName == null || firstName.isBlank()) {
            return lastName;
        }
        return lastName + ", " + firstName;
    }

    private static String buildFullAddress(
            String street, String city, String state, String postalCode, String country) {
        StringBuilder sb = new StringBuilder();
        appendIfPresent(sb, street);
        appendIfPresent(sb, city);
        appendIfPresent(sb, state);
        appendIfPresent(sb, postalCode);
        appendIfPresent(sb, country);
        return sb.isEmpty() ? null : sb.toString();
    }

    private static void appendIfPresent(StringBuilder sb, String value) {
        if (value != null && !value.isBlank()) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(value.strip());
        }
    }

    private static EntityType mapSdnType(String sdnType) {
        if (sdnType == null) {
            return EntityType.INDIVIDUAL;
        }
        return switch (sdnType.strip().toLowerCase()) {
            case "individual" -> EntityType.INDIVIDUAL;
            case "entity" -> EntityType.ENTITY;
            case "vessel" -> EntityType.VESSEL;
            case "aircraft" -> EntityType.AIRCRAFT;
            default -> EntityType.ENTITY;
        };
    }

    private static NameType mapAkaType(String type) {
        if (type == null) {
            return NameType.AKA;
        }
        return switch (type.strip().toLowerCase()) {
            case "a.k.a.", "aka" -> NameType.AKA;
            case "f.k.a.", "fka" -> NameType.FKA;
            default -> NameType.AKA;
        };
    }

    private static NameStrength mapAkaStrength(String category) {
        if (category == null) {
            return NameStrength.WEAK;
        }
        return switch (category.strip().toLowerCase()) {
            case "strong" -> NameStrength.STRONG;
            case "weak" -> NameStrength.WEAK;
            default -> NameStrength.WEAK;
        };
    }

    @SuppressWarnings("PatternValidation") // identifier type mapping is best-effort
    private static IdentifierType mapIdType(String idType) {
        if (idType == null) {
            return IdentifierType.OTHER;
        }
        String normalized = idType.strip().toLowerCase();
        if (normalized.startsWith(DIGITAL_CURRENCY_TYPE)) {
            return IdentifierType.CRYPTO_ADDRESS;
        } else if (normalized.contains("passport")) {
            return IdentifierType.PASSPORT;
        } else if (normalized.contains("national") || normalized.contains("cedula")) {
            return IdentifierType.NATIONAL_ID;
        } else if (normalized.contains("tax") || normalized.contains("ssn")) {
            return IdentifierType.TAX_ID;
        } else if (normalized.contains("imo")) {
            return IdentifierType.IMO_NUMBER;
        } else if (normalized.contains("mmsi")) {
            return IdentifierType.MMSI;
        } else if (normalized.contains("swift") || normalized.contains("bic")) {
            return IdentifierType.SWIFT_BIC;
        } else if (normalized.contains("legal entity")) {
            return IdentifierType.LEI;
        } else if (normalized.contains("registration")) {
            return IdentifierType.REGISTRATION_NUMBER;
        }
        return IdentifierType.OTHER;
    }

    private static LocalDate parseDateSafe(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(dateStr.strip(), OFAC_DATE_FORMAT);
        } catch (DateTimeParseException e) {
            try {
                return LocalDate.parse(dateStr.strip());
            } catch (DateTimeParseException e2) {
                log.debug("Unable to parse date of birth [value={}]", dateStr);
                return null;
            }
        }
    }
}
