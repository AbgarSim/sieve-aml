package dev.sieve.address;

import dev.sieve.core.model.Address;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * High-level address normalization service backed by libpostal.
 *
 * <p>Provides address parsing (free-text → structured components), expansion (abbreviation
 * normalization), and enrichment of existing {@link Address} records. When libpostal is not
 * available, all operations fall back to basic string normalization (trimming, whitespace
 * canonicalization).
 *
 * <p>This class is thread-safe after {@link #init()} completes.
 *
 * <p><b>Data model:</b> For best accuracy, use the
 * <a href="https://github.com/Senzing/libpostal-data">Senzing libpostal data model v1.2.0</a>
 * which improves parsing accuracy by ~4% on average across 89 countries compared to the original
 * 2016 model.
 */
public final class AddressNormalizer {

    private static final Logger log = LoggerFactory.getLogger(AddressNormalizer.class);

    private final String dataDir;
    private volatile boolean initialized = false;

    /**
     * Creates a normalizer that uses the system-default libpostal data directory.
     */
    public AddressNormalizer() {
        this(null);
    }

    /**
     * Creates a normalizer that uses the specified libpostal data directory.
     *
     * @param dataDir path to the libpostal data directory (containing the Senzing model), or
     *     {@code null} to use the default
     */
    public AddressNormalizer(String dataDir) {
        this.dataDir = dataDir;
    }

    /**
     * Initializes the underlying libpostal library. Must be called once before any other operation.
     *
     * <p>If libpostal is not available on the system, initialization will fail gracefully and the
     * normalizer will operate in fallback mode.
     */
    public synchronized void init() {
        if (initialized) return;

        boolean setupOk = LibPostal.setup(dataDir);
        boolean parserOk = LibPostal.setupParser(dataDir);

        if (setupOk && parserOk) {
            initialized = true;
            log.info(
                    "AddressNormalizer initialized with libpostal [dataDir={}]",
                    dataDir == null ? "default" : dataDir);
        } else {
            log.warn(
                    "AddressNormalizer: libpostal initialization failed [setup={}, parser={}]. "
                            + "Using fallback mode (basic string normalization only).",
                    setupOk,
                    parserOk);
        }
    }

    /**
     * Returns whether libpostal is fully initialized and available for address operations.
     *
     * @return {@code true} if libpostal is ready
     */
    public boolean isAvailable() {
        return initialized && LibPostal.isAvailable();
    }

    /**
     * Expands an address string into normalized forms suitable for comparison and indexing.
     *
     * <p>If libpostal is not available, returns a single-element list containing the stripped input.
     *
     * @param address the raw address string, must not be {@code null}
     * @return normalized expansions, never {@code null} or empty
     */
    public List<String> expand(String address) {
        Objects.requireNonNull(address, "address must not be null");
        if (!isAvailable()) {
            return List.of(address.strip());
        }
        String[] expansions = LibPostal.expandAddress(address);
        return List.of(expansions);
    }

    /**
     * Parses a free-text address into labeled structural components.
     *
     * <p>If libpostal is not available, returns a single {@code road} component with the raw input.
     *
     * @param address the raw address string, must not be {@code null}
     * @return the parsed address, never {@code null}
     */
    public ParsedAddress parse(String address) {
        Objects.requireNonNull(address, "address must not be null");
        if (!isAvailable()) {
            return new ParsedAddress(List.of(new AddressComponent("road", address.strip())));
        }
        List<AddressComponent> components = LibPostal.parseAddress(address);
        return new ParsedAddress(components);
    }

    /**
     * Normalizes an existing {@link Address} by parsing its {@code fullAddress} with libpostal and
     * filling in any missing structured fields.
     *
     * <p>Existing non-blank fields are preserved; only blank/null fields are populated from the
     * parsed result. If libpostal is not available, performs basic whitespace normalization.
     *
     * @param address the address to normalize, may be {@code null}
     * @return the normalized address, or {@code null} if the input was {@code null}
     */
    public Address normalize(Address address) {
        if (address == null) return null;

        if (!isAvailable()) {
            return normalizeBasic(address);
        }

        String raw = address.fullAddress();
        if (raw == null || raw.isBlank()) {
            raw = buildFullAddress(address);
        }

        if (raw == null || raw.isBlank()) {
            return address;
        }

        ParsedAddress parsed = parse(raw);

        return new Address(
                firstNonBlank(address.street(), parsed.road()),
                firstNonBlank(address.city(), parsed.city()),
                firstNonBlank(address.stateOrProvince(), parsed.state()),
                firstNonBlank(address.postalCode(), parsed.postcode()),
                firstNonBlank(address.country(), parsed.country()),
                raw);
    }

    /**
     * Shuts down the underlying libpostal library and releases native resources.
     */
    public synchronized void shutdown() {
        if (initialized) {
            LibPostal.teardownParser();
            LibPostal.teardown();
            initialized = false;
            log.info("AddressNormalizer shut down");
        }
    }

    // ---- Internal helpers ----

    private static Address normalizeBasic(Address address) {
        return new Address(
                strip(address.street()),
                strip(address.city()),
                strip(address.stateOrProvince()),
                strip(address.postalCode()),
                strip(address.country()),
                strip(address.fullAddress()));
    }

    private static String buildFullAddress(Address a) {
        StringBuilder sb = new StringBuilder();
        appendIfPresent(sb, a.street());
        appendIfPresent(sb, a.city());
        appendIfPresent(sb, a.stateOrProvince());
        appendIfPresent(sb, a.postalCode());
        appendIfPresent(sb, a.country());
        return sb.isEmpty() ? null : sb.toString();
    }

    private static void appendIfPresent(StringBuilder sb, String value) {
        if (value != null && !value.isBlank()) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(value.strip());
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v.strip();
        }
        return null;
    }

    private static String strip(String s) {
        return s == null ? null : s.strip();
    }
}
