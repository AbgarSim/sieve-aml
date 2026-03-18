package dev.sieve.address;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Low-level JNI binding to the <a href="https://github.com/openvenues/libpostal">libpostal</a> C
 * library.
 *
 * <p>This class loads the {@code sieve_postal} native library and exposes libpostal's address
 * expansion and parsing functions to Java. All native operations are thread-safe once initialized.
 *
 * <p>Typical lifecycle:
 *
 * <pre>{@code
 * LibPostal.setup(dataDir);
 * LibPostal.setupParser(dataDir);
 * // ... use expandAddress / parseAddress ...
 * LibPostal.teardownParser();
 * LibPostal.teardown();
 * }</pre>
 *
 * <p>If the native library is not available (e.g., libpostal is not installed), all setup methods
 * return {@code false} and {@link #isAvailable()} returns {@code false}. Callers should check
 * availability before invoking expand/parse operations.
 */
public final class LibPostal {

    private static final Logger log = LoggerFactory.getLogger(LibPostal.class);

    private static volatile boolean nativeLoaded = false;
    private static volatile boolean setupDone = false;
    private static volatile boolean parserSetupDone = false;

    static {
        try {
            System.loadLibrary("sieve_postal");
            nativeLoaded = true;
            log.info("libpostal JNI library loaded successfully");
        } catch (UnsatisfiedLinkError e) {
            log.warn(
                    "Failed to load libpostal JNI library: {}. "
                            + "Address normalization will use fallback mode.",
                    e.getMessage());
        }
    }

    private LibPostal() {}

    /**
     * Returns {@code true} if the native library was loaded from the system library path.
     *
     * @return whether the JNI shared library is loaded
     */
    public static boolean isNativeLoaded() {
        return nativeLoaded;
    }

    /**
     * Returns {@code true} if libpostal is fully initialized and ready for address operations.
     *
     * @return whether both the core library and parser are initialized
     */
    public static boolean isAvailable() {
        return nativeLoaded && setupDone && parserSetupDone;
    }

    // ---- Setup / teardown ----

    /**
     * Initializes the libpostal core library using the default data directory.
     *
     * @return {@code true} if initialization succeeded
     */
    public static synchronized boolean setup() {
        if (!nativeLoaded) return false;
        if (setupDone) return true;
        setupDone = nativeSetup();
        return setupDone;
    }

    /**
     * Initializes the libpostal core library using the given data directory.
     *
     * @param dataDir path to the libpostal data directory, or {@code null} for the default
     * @return {@code true} if initialization succeeded
     */
    public static synchronized boolean setup(String dataDir) {
        if (!nativeLoaded) return false;
        if (setupDone) return true;
        setupDone = (dataDir == null) ? nativeSetup() : nativeSetupDataDir(dataDir);
        return setupDone;
    }

    /**
     * Initializes the libpostal address parser using the default data directory.
     *
     * @return {@code true} if initialization succeeded
     */
    public static synchronized boolean setupParser() {
        if (!nativeLoaded) return false;
        if (parserSetupDone) return true;
        parserSetupDone = nativeSetupParser();
        return parserSetupDone;
    }

    /**
     * Initializes the libpostal address parser using the given data directory.
     *
     * @param dataDir path to the libpostal data directory, or {@code null} for the default
     * @return {@code true} if initialization succeeded
     */
    public static synchronized boolean setupParser(String dataDir) {
        if (!nativeLoaded) return false;
        if (parserSetupDone) return true;
        parserSetupDone =
                (dataDir == null) ? nativeSetupParser() : nativeSetupParserDataDir(dataDir);
        return parserSetupDone;
    }

    /** Tears down the libpostal core library. */
    public static synchronized void teardown() {
        if (nativeLoaded && setupDone) {
            nativeTeardown();
            setupDone = false;
        }
    }

    /** Tears down the libpostal address parser. */
    public static synchronized void teardownParser() {
        if (nativeLoaded && parserSetupDone) {
            nativeTeardownParser();
            parserSetupDone = false;
        }
    }

    // ---- Core operations ----

    /**
     * Expands an address string into normalized forms suitable for comparison and indexing.
     *
     * <p>For example, {@code "123 Main St"} might expand to {@code ["123 main street"]}.
     *
     * @param address the raw address string
     * @return an array of normalized address expansions
     * @throws IllegalStateException if libpostal is not initialized
     */
    public static String[] expandAddress(String address) {
        if (!setupDone) {
            throw new IllegalStateException("libpostal not initialized — call setup() first");
        }
        return nativeExpandAddress(address);
    }

    /**
     * Parses a free-text address into labeled components.
     *
     * @param address the raw address string
     * @return the parsed address components
     * @throws IllegalStateException if the parser is not initialized
     */
    public static List<AddressComponent> parseAddress(String address) {
        if (!parserSetupDone) {
            throw new IllegalStateException(
                    "libpostal parser not initialized — call setupParser() first");
        }
        String[][] raw = nativeParseAddress(address);
        List<AddressComponent> components = new ArrayList<>(raw.length);
        for (String[] pair : raw) {
            components.add(new AddressComponent(pair[0], pair[1]));
        }
        return components;
    }

    // ---- Native method declarations ----

    private static native boolean nativeSetup();

    private static native boolean nativeSetupDataDir(String dataDir);

    private static native boolean nativeSetupParser();

    private static native boolean nativeSetupParserDataDir(String dataDir);

    private static native String[] nativeExpandAddress(String address);

    private static native String[][] nativeParseAddress(String address);

    private static native void nativeTeardown();

    private static native void nativeTeardownParser();
}
