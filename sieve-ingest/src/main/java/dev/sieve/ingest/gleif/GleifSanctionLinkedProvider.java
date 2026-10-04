package dev.sieve.ingest.gleif;

import com.fasterxml.jackson.databind.JsonNode;
import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.Identifier;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.NameStrength;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.Relation;
import dev.sieve.core.model.RelationType;
import dev.sieve.core.model.RiskTopic;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.SanctionsProgram;
import dev.sieve.ingest.AbstractListProvider;
import dev.sieve.ingest.ListProvider;
import dev.sieve.ingest.eu.EuConsolidatedProvider;
import dev.sieve.ingest.gleif.GleifRegistry.Link;
import dev.sieve.ingest.gleif.GleifRegistry.Profile;
import dev.sieve.ingest.ofac.OfacSdnProvider;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Derives sanction-linked companies from the Global Legal Entity Identifier Foundation (GLEIF) LEI
 * register: the companies whose accounts a sanctioned party consolidates, directly or through other
 * companies, and which are not listed themselves.
 *
 * <p>OFAC blocks the property of any entity owned 50% or more by blocked persons (its "50 percent
 * rule"), and EU asset freezes reach the entities a listed party owns or controls, so such
 * companies are sanctioned in practice without appearing on a list. The fetch reads the LEIs the
 * {@linkplain OfacSdnProvider OFAC SDN} and {@linkplain EuConsolidatedProvider EU consolidated}
 * lists state for their entries (OFAC's "Legal Entity Number", and any identifier whose value has
 * the LEI format and check digits), downloads GLEIF's daily relationship file and follows its
 * active {@linkplain GleifRegistry#CONSOLIDATION consolidation} links down from those parties,
 * parent to child, up to {@value #MAX_DEPTH} levels. Consolidating a company's accounts means
 * controlling it under accounting standards, which stands in for majority ownership here; the
 * register states the share for some links. The companies found are then described through the
 * GLEIF API.
 *
 * <p>Each company becomes an entity tagged {@link RiskTopic#SANCTION_LINKED} with id {@code
 * lei-linked-<LEI>} (apart from the {@code lei-} ids of the state-owned list, as a company can be
 * in both), the names, addresses and identifiers of its LEI record, a program per list that names
 * an owner ({@code OFAC SDN 50% rule}), a remark per consolidation link naming the parent and its
 * list, and a {@link RelationType#LINKED} relation to the parent's record: the listed party's own
 * entity, such as {@code ofac-sdn-17013}, or the intermediate company in this list. A company is
 * left out when it is listed itself, by LEI or by name, when GLEIF marks it inactive, or when no
 * chain of kept companies leads from it to a listed party. Sectoral and other non-SDN programmes
 * are not followed, as the 50 percent rule applies only to some of them. The two lists are
 * downloaded again for this fetch, which takes about a minute.
 *
 * @see <a href="https://ofac.treasury.gov/faqs/401">OFAC FAQ 401, the 50 percent rule</a>
 * @see <a href="https://www.gleif.org/en/lei-data/gleif-golden-copy">GLEIF golden copy files</a>
 */
public final class GleifSanctionLinkedProvider extends AbstractListProvider {

    static final String ID_PREFIX = "lei-linked-";
    static final String PROGRAM_SUFFIX = " 50% rule";

    /** How many consolidation levels below a listed party are followed. */
    static final int MAX_DEPTH = 6;

    private static final Pattern LEI_FORMAT = Pattern.compile("[A-Z0-9]{18}[0-9]{2}");
    private static final Pattern NOT_LETTER_OR_DIGIT = Pattern.compile("[^\\p{L}\\p{N}]");

    private final URI apiUri;
    private final List<ListProvider> listings;
    private volatile Map<String, Party> parties = Map.of();
    private volatile Set<String> listedNames = Set.of();
    private volatile Map<String, Company> companies = Map.of();

    /** Creates a provider that reads the public OFAC SDN and EU lists, GLEIF API and files. */
    public GleifSanctionLinkedProvider() {
        super(
                ListSource.GLEIF_SANCTION_LINKED,
                URI.create(GleifRegistry.DEFAULT_PUBLISHES),
                "application/json");
        this.apiUri = URI.create(GleifRegistry.DEFAULT_API);
        this.listings = List.of(new OfacSdnProvider(), new EuConsolidatedProvider());
    }

    /**
     * Creates a provider with custom endpoints, lists and HTTP client (for testing).
     *
     * @param publishesUri the golden copy listing that names the day's relationship file
     * @param apiUri the LEI records API
     * @param listings the sanctions lists whose entries' LEIs are followed
     * @param httpClient the HTTP client to use for GLEIF requests
     */
    public GleifSanctionLinkedProvider(
            URI publishesUri, URI apiUri, List<ListProvider> listings, HttpClient httpClient) {
        super(
                ListSource.GLEIF_SANCTION_LINKED,
                publishesUri,
                "application/json",
                httpClient,
                Duration.ofSeconds(120));
        this.apiUri = apiUri;
        this.listings = List.copyOf(listings);
    }

    /** A listed party and the LEI its list states for it. */
    record Party(String lei, SanctionedEntity entity) {}

    /** A company found below a listed party, with the links to its parents. */
    static final class Company {
        final String lei;
        final List<Link> parents = new ArrayList<>();
        JsonNode attributes;

        Company(String lei) {
            this.lei = lei;
        }
    }

    @Override
    protected HttpRequest buildRequest(HttpClient client, HttpRequest.Builder builder)
            throws IOException, InterruptedException {
        Instant started = Instant.now();
        Map<String, Party> listed = new LinkedHashMap<>();
        Set<String> names = new HashSet<>();
        for (ListProvider listing : listings) {
            List<SanctionedEntity> entities;
            try {
                entities = listing.fetch();
            } catch (ListIngestionException e) {
                throw new IOException(
                        "GLEIF sanction-linked: could not read "
                                + listing.source().displayName()
                                + ": "
                                + e.getMessage(),
                        e);
            }
            int before = listed.size();
            for (SanctionedEntity entity : entities) {
                if (!legalEntity(entity.entityType())) {
                    continue;
                }
                addName(names, entity.primaryName());
                entity.aliases().forEach(alias -> addName(names, alias));
                for (Identifier identifier : entity.identifiers()) {
                    String lei = lei(identifier);
                    if (lei != null) {
                        listed.putIfAbsent(lei, new Party(lei, entity));
                    }
                }
            }
            log.info(
                    "GLEIF sanction-linked: read list [source={}, entities={}, leis={}]",
                    listing.source(),
                    entities.size(),
                    listed.size() - before);
        }
        if (listed.isEmpty()) {
            throw new IOException("GLEIF sanction-linked: no listed party states an LEI");
        }

        GleifRegistry registry = new GleifRegistry(client, sourceUri(), apiUri);
        byte[] file = registry.relationshipFile();
        Map<String, Company> found = new LinkedHashMap<>();
        Set<String> frontier = new LinkedHashSet<>(listed.keySet());
        for (int depth = 1; depth <= MAX_DEPTH && !frontier.isEmpty(); depth++) {
            Set<String> next = new LinkedHashSet<>();
            for (Link link : GleifRegistry.consolidationLinks(file, frontier)) {
                if (listed.containsKey(link.company())) {
                    continue; // listed itself, so already a sanctioned entity
                }
                Company company = found.get(link.company());
                if (company == null) {
                    company = new Company(link.company());
                    found.put(link.company(), company);
                    next.add(link.company());
                }
                company.parents.add(link);
            }
            frontier = next;
        }
        log.info(
                "GLEIF sanction-linked: followed consolidation links [parties={}, companies={}, seconds={}]",
                listed.size(),
                found.size(),
                Duration.between(started, Instant.now()).toSeconds());

        Map<String, JsonNode> records = registry.leiRecords(found.keySet());
        for (Company company : found.values()) {
            company.attributes = records.get(company.lei);
        }
        log.info(
                "GLEIF sanction-linked: described companies [described={}, seconds={}]",
                records.size(),
                Duration.between(started, Instant.now()).toSeconds());
        parties = listed;
        listedNames = names;
        companies = found;
        return builder.GET().build();
    }

    @Override
    protected List<SanctionedEntity> parseResponse(byte[] responseBody)
            throws ListIngestionException {
        if (companies.isEmpty()) {
            throw new ListIngestionException(
                    "GLEIF sanction-linked: no companies found under listed parties",
                    ListSource.GLEIF_SANCTION_LINKED);
        }
        Map<String, Profile> profiles = new LinkedHashMap<>();
        int listedByName = 0;
        for (Company company : companies.values()) {
            if (company.attributes == null || GleifRegistry.inactive(company.attributes)) {
                continue;
            }
            Profile profile = GleifRegistry.profile(company.lei, company.attributes);
            if (profile == null) {
                continue;
            }
            if (listedNames.contains(normalize(profile.name()))
                    || profile.aliases().stream()
                            .anyMatch(alias -> listedNames.contains(normalize(alias.fullName())))) {
                listedByName++;
                continue;
            }
            profiles.put(company.lei, profile);
        }
        if (listedByName > 0) {
            log.info(
                    "GLEIF sanction-linked: left out companies listed under their own name [companies={}]",
                    listedByName);
        }
        // a company stays when one of its parents is a listed party or a company that stays
        Set<String> kept = new LinkedHashSet<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String lei : profiles.keySet()) {
                if (!kept.contains(lei)
                        && companies.get(lei).parents.stream()
                                .anyMatch(
                                        link ->
                                                parties.containsKey(link.parent())
                                                        || kept.contains(link.parent()))) {
                    kept.add(lei);
                    changed = true;
                }
            }
        }
        List<SanctionedEntity> entities = new ArrayList<>();
        for (Map.Entry<String, Profile> entry : profiles.entrySet()) {
            if (kept.contains(entry.getKey())) {
                entities.add(toEntity(companies.get(entry.getKey()), entry.getValue(), kept));
            }
        }
        return entities;
    }

    private SanctionedEntity toEntity(Company company, Profile profile, Set<String> kept) {
        List<SanctionsProgram> programs = new ArrayList<>();
        for (ListSource list : lists(company.lei, kept, new HashSet<>())) {
            programs.add(
                    new SanctionsProgram(
                            list.displayName() + PROGRAM_SUFFIX,
                            "Majority-owned by a party on the " + list.displayName() + " list",
                            ListSource.GLEIF_SANCTION_LINKED));
        }
        StringJoiner remarks = new StringJoiner("\n");
        List<Relation> relations = new ArrayList<>();
        Instant listed = null;
        for (Link link : company.parents) {
            Party party = parties.get(link.parent());
            StringBuilder line =
                    new StringBuilder(link.ultimate() ? "Ultimately" : "Directly")
                            .append(" consolidated by ");
            Instant since =
                    link.start() == null
                            ? null
                            : link.start().atStartOfDay(ZoneOffset.UTC).toInstant();
            if (party != null) {
                SanctionedEntity owner = party.entity();
                line.append(owner.primaryName().fullName())
                        .append(" (")
                        .append(link.parent())
                        .append("), listed on ")
                        .append(owner.listSource().displayName());
                String codes =
                        owner.programs().stream()
                                .map(SanctionsProgram::code)
                                .collect(Collectors.joining(", "));
                if (!codes.isEmpty()) {
                    line.append(" under ").append(codes);
                }
                relations.add(
                        new Relation(
                                RelationType.LINKED,
                                owner.id(),
                                link.role(),
                                link.share(),
                                link.start(),
                                link.end()));
                Instant designated = owner.listedDate();
                if (designated != null && (since == null || designated.isAfter(since))) {
                    since = designated;
                }
            } else if (kept.contains(link.parent())) {
                line.append(GleifRegistry.name(companies.get(link.parent()).attributes))
                        .append(" (")
                        .append(link.parent())
                        .append("), itself majority-owned by a listed party");
                relations.add(
                        new Relation(
                                RelationType.LINKED,
                                ID_PREFIX + link.parent(),
                                link.role(),
                                link.share(),
                                link.start(),
                                link.end()));
            } else {
                continue;
            }
            if (link.share() != null) {
                line.append(", share ")
                        .append(GleifRegistry.percentageText(link.share()))
                        .append("%");
            }
            if (link.start() != null) {
                line.append(", since ").append(link.start());
            }
            remarks.add(line.toString());
            if (since != null && (listed == null || since.isBefore(listed))) {
                listed = since;
            }
        }
        if (profile.registration() != null) {
            remarks.add("LEI registration: " + profile.registration());
        }
        remarks.add("Source: " + GleifRegistry.RECORD_PAGE + company.lei);

        return new SanctionedEntity(
                ID_PREFIX + company.lei,
                EntityType.ENTITY,
                ListSource.GLEIF_SANCTION_LINKED,
                new NameInfo(
                        profile.name(),
                        null,
                        null,
                        null,
                        null,
                        NameType.PRIMARY,
                        NameStrength.STRONG,
                        null),
                profile.aliases(),
                profile.addresses(),
                profile.identifiers(),
                profile.country() == null ? List.of() : List.of(profile.country()),
                List.of(),
                List.of(),
                List.of(),
                remarks.toString(),
                programs,
                listed,
                Instant.now(),
                Set.of(RiskTopic.SANCTION_LINKED),
                relations);
    }

    /** The lists whose parties sit above a company, through any chain of kept companies. */
    private Set<ListSource> lists(String lei, Set<String> kept, Set<String> seen) {
        Set<ListSource> lists = EnumSet.noneOf(ListSource.class);
        if (!seen.add(lei)) {
            return lists;
        }
        for (Link link : companies.get(lei).parents) {
            Party party = parties.get(link.parent());
            if (party != null) {
                lists.add(party.entity().listSource());
            } else if (kept.contains(link.parent())) {
                lists.addAll(lists(link.parent(), kept, seen));
            }
        }
        return lists;
    }

    /** Whether a listed party of this kind can own companies: people, ships and planes cannot. */
    static boolean legalEntity(EntityType type) {
        return type == EntityType.ENTITY
                || type == EntityType.COMPANY
                || type == EntityType.ORGANIZATION;
    }

    private static void addName(Set<String> names, NameInfo name) {
        String normalized = normalize(name == null ? null : name.fullName());
        if (!normalized.isEmpty()) {
            names.add(normalized);
        }
    }

    /** Lower case, letters and digits only, so punctuation and spacing do not keep names apart. */
    static String normalize(String name) {
        return name == null
                ? ""
                : NOT_LETTER_OR_DIGIT.matcher(name.toLowerCase(Locale.ROOT)).replaceAll("");
    }

    /**
     * The identifier's value as an LEI when it has the LEI format and check digits, else {@code
     * null}. Lists type the LEI loosely, and some values they state are not LEIs at all.
     */
    static String lei(Identifier identifier) {
        if (identifier == null || identifier.value() == null) {
            return null;
        }
        String value = identifier.value().strip().toUpperCase(Locale.ROOT).replace(" ", "");
        return LEI_FORMAT.matcher(value).matches() && checkDigitsHold(value) ? value : null;
    }

    /** ISO 17442: with letters written as two digits, the number modulo 97 must be 1. */
    static boolean checkDigitsHold(String lei) {
        int remainder = 0;
        for (int i = 0; i < lei.length(); i++) {
            int digit = Character.digit(lei.charAt(i), 36);
            if (digit < 0) {
                return false;
            }
            if (digit >= 10) {
                remainder = (remainder * 10 + digit / 10) % 97;
                digit %= 10;
            }
            remainder = (remainder * 10 + digit) % 97;
        }
        return remainder == 1;
    }
}
