package dev.sieve.server;

/**
 * Immutable server configuration parsed from command-line arguments and environment variables.
 *
 * <p>Environment variables take precedence over defaults; CLI args take precedence over
 * environment.
 */
public record ServerConfig(
        int port,
        double defaultThreshold,
        int maxResults,
        boolean ofacEnabled,
        boolean euEnabled,
        boolean unEnabled,
        boolean ukEnabled) {

    private static final int DEFAULT_PORT = 8080;
    private static final double DEFAULT_THRESHOLD = 0.80;
    private static final int DEFAULT_MAX_RESULTS = 50;

    static ServerConfig fromArgs(String[] args) {
        int port = envInt("SIEVE_PORT", DEFAULT_PORT);
        double threshold = envDouble("SIEVE_THRESHOLD", DEFAULT_THRESHOLD);
        int maxResults = envInt("SIEVE_MAX_RESULTS", DEFAULT_MAX_RESULTS);
        boolean ofac = envBool("SIEVE_OFAC_ENABLED", true);
        boolean eu = envBool("SIEVE_EU_ENABLED", false);
        boolean un = envBool("SIEVE_UN_ENABLED", false);
        boolean uk = envBool("SIEVE_UK_ENABLED", false);

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port", "-p" -> port = Integer.parseInt(args[++i]);
                case "--threshold", "-t" -> threshold = Double.parseDouble(args[++i]);
                case "--max-results" -> maxResults = Integer.parseInt(args[++i]);
                case "--ofac" -> ofac = Boolean.parseBoolean(args[++i]);
                case "--eu" -> eu = Boolean.parseBoolean(args[++i]);
                case "--un" -> un = Boolean.parseBoolean(args[++i]);
                case "--uk" -> uk = Boolean.parseBoolean(args[++i]);
                default -> {
                    /* ignore unknown */
                }
            }
        }
        return new ServerConfig(port, threshold, maxResults, ofac, eu, un, uk);
    }

    private static int envInt(String key, int fallback) {
        String val = System.getenv(key);
        return val != null ? Integer.parseInt(val) : fallback;
    }

    private static double envDouble(String key, double fallback) {
        String val = System.getenv(key);
        return val != null ? Double.parseDouble(val) : fallback;
    }

    private static boolean envBool(String key, boolean fallback) {
        String val = System.getenv(key);
        return val != null ? Boolean.parseBoolean(val) : fallback;
    }
}
