package dev.sieve.address;

import dev.sieve.core.index.EntityIndex;
import dev.sieve.core.model.Address;
import dev.sieve.core.model.SanctionedEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service that matches a query address against addresses in the entity index.
 *
 * <p>Uses {@link AddressNormalizer} (backed by libpostal) to parse free-text/NLP address input into
 * structured components, then compares those components against entity addresses using weighted
 * component scoring.
 *
 * <p>Component weights:
 *
 * <ul>
 *   <li><b>country</b> — 0.30 (most discriminative at scale)
 *   <li><b>city</b> — 0.25
 *   <li><b>street</b> — 0.25
 *   <li><b>postalCode</b> — 0.10
 *   <li><b>stateOrProvince</b> — 0.10
 * </ul>
 *
 * <p>This class is thread-safe.
 */
public final class AddressMatchService {

    private static final Logger log = LoggerFactory.getLogger(AddressMatchService.class);

    private static final double W_COUNTRY = 0.30;
    private static final double W_CITY = 0.25;
    private static final double W_STREET = 0.25;
    private static final double W_POSTAL = 0.10;
    private static final double W_STATE = 0.10;

    private final AddressNormalizer normalizer;

    public AddressMatchService(AddressNormalizer normalizer) {
        this.normalizer = Objects.requireNonNull(normalizer);
    }

    /**
     * Screens a free-text address query against all entities in the index.
     *
     * <p>The query is first parsed into structured components via libpostal (or fallback), then
     * compared against every address on every entity. Entities with at least one address scoring
     * above the threshold are returned, sorted by descending score.
     *
     * @param query the free-text address to screen (e.g., "123 Main St, London, UK")
     * @param index the entity index to screen against
     * @param threshold minimum score (0.0–1.0)
     * @param maxResults maximum results to return
     * @return matching results sorted by score descending
     */
    public List<AddressMatchResult> screen(
            String query, EntityIndex index, double threshold, int maxResults) {
        Objects.requireNonNull(query, "query must not be null");
        Objects.requireNonNull(index, "index must not be null");

        ParsedAddress parsed = normalizer.parse(query);
        NormalizedAddress queryAddr = NormalizedAddress.from(parsed);

        log.debug(
                "Address screening [query={}, parsedCountry={}, parsedCity={}, parsedStreet={}]",
                query,
                queryAddr.country,
                queryAddr.city,
                queryAddr.street);

        List<AddressMatchResult> results = new ArrayList<>();

        for (SanctionedEntity entity : index.all()) {
            if (entity.addresses().isEmpty()) continue;

            double bestScore = 0.0;
            Address bestAddress = null;

            for (Address addr : entity.addresses()) {
                NormalizedAddress entityAddr = NormalizedAddress.from(addr);
                double score = scoreComponents(queryAddr, entityAddr);
                if (score > bestScore) {
                    bestScore = score;
                    bestAddress = addr;
                }
            }

            if (bestScore >= threshold && bestAddress != null) {
                results.add(new AddressMatchResult(entity, bestScore, bestAddress));
            }
        }

        results.sort(Comparator.comparingDouble(AddressMatchResult::score).reversed());

        log.debug(
                "Address screening complete [query={}, candidates={}, matches={}]",
                query,
                index.size(),
                results.size());

        if (results.size() > maxResults) {
            return results.subList(0, maxResults);
        }
        return results;
    }

    /**
     * Scores how well two normalized addresses match using weighted component comparison.
     *
     * <p>Each component is compared case-insensitively. Exact matches get full weight, containment
     * gets partial weight (0.6), no match gets 0. The total score is the sum of weighted component
     * scores, normalized to only count components present in the query.
     */
    static double scoreComponents(NormalizedAddress query, NormalizedAddress entity) {
        double totalWeight = 0.0;
        double earnedScore = 0.0;

        if (query.country != null) {
            totalWeight += W_COUNTRY;
            earnedScore += W_COUNTRY * compareComponent(query.country, entity.country);
        }

        if (query.city != null) {
            totalWeight += W_CITY;
            earnedScore += W_CITY * compareComponent(query.city, entity.city);
        }

        if (query.street != null) {
            totalWeight += W_STREET;
            earnedScore += W_STREET * compareComponent(query.street, entity.street);
        }

        if (query.postalCode != null) {
            totalWeight += W_POSTAL;
            earnedScore += W_POSTAL * compareComponent(query.postalCode, entity.postalCode);
        }

        if (query.state != null) {
            totalWeight += W_STATE;
            earnedScore += W_STATE * compareComponent(query.state, entity.state);
        }

        if (totalWeight <= 0.0) return 0.0;
        return earnedScore / totalWeight;
    }

    /**
     * Compares two address component values.
     *
     * @return 1.0 for exact match, 0.6 for containment, 0.0 for no match
     */
    private static double compareComponent(String query, String entity) {
        if (entity == null || entity.isEmpty()) return 0.0;
        if (query.equals(entity)) return 1.0;
        if (entity.contains(query) || query.contains(entity)) return 0.6;
        return 0.0;
    }

    /**
     * Result of an address screening match.
     *
     * @param entity the matched sanctioned entity
     * @param score the address match score (0.0–1.0)
     * @param matchedAddress the specific entity address that produced the best match
     */
    public record AddressMatchResult(
            SanctionedEntity entity, double score, Address matchedAddress) {
        public AddressMatchResult {
            Objects.requireNonNull(entity);
            Objects.requireNonNull(matchedAddress);
        }
    }

    /**
     * Internal normalized representation of an address for comparison. All fields are lowercased
     * and trimmed; null if absent.
     */
    record NormalizedAddress(
            String street, String city, String state, String postalCode, String country) {

        static NormalizedAddress from(ParsedAddress parsed) {
            return new NormalizedAddress(
                    normalize(parsed.road()),
                    normalize(parsed.city()),
                    normalize(parsed.state()),
                    normalize(parsed.postcode()),
                    normalize(parsed.country()));
        }

        static NormalizedAddress from(Address address) {
            return new NormalizedAddress(
                    normalize(address.street()),
                    normalize(address.city()),
                    normalize(address.stateOrProvince()),
                    normalize(address.postalCode()),
                    normalize(address.country()));
        }

        private static String normalize(String s) {
            if (s == null || s.isBlank()) return null;
            return s.strip().toLowerCase(Locale.ROOT);
        }
    }
}
