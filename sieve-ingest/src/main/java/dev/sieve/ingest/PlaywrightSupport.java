package dev.sieve.ingest;

import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.ListSource;

/**
 * Checks that the optional Playwright dependency is on the classpath.
 *
 * <p>Playwright is an optional dependency of {@code sieve-ingest} because its browser driver bundle
 * is about 200 MB and only the providers for sites that need a real browser (Israel NBCTF and
 * Turkey MASAK) use it. Applications that run those providers add {@code
 * com.microsoft.playwright:playwright} themselves.
 */
public final class PlaywrightSupport {

    private static final String PLAYWRIGHT_CLASS = "com.microsoft.playwright.Playwright";

    private PlaywrightSupport() {}

    /**
     * Returns whether Playwright can be loaded.
     *
     * @return {@code true} if the Playwright classes are on the classpath
     */
    public static boolean isAvailable() {
        try {
            Class.forName(PLAYWRIGHT_CLASS, false, PlaywrightSupport.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /**
     * Fails with a clear message when Playwright is missing.
     *
     * @param source the list that needs a browser to download
     * @throws ListIngestionException if Playwright is not on the classpath
     */
    public static void requireAvailable(ListSource source) throws ListIngestionException {
        if (!isAvailable()) {
            throw new ListIngestionException(
                    "The "
                            + source
                            + " list needs a headless browser: add the optional dependency"
                            + " com.microsoft.playwright:playwright to the classpath",
                    source);
        }
    }
}
