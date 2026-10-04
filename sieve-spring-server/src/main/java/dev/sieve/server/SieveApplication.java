package dev.sieve.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the Sieve sanctions screening REST API.
 *
 * <p>Boots the Spring context, connects to PostgreSQL (the system of record, schema managed by
 * Flyway), builds the in-memory screening index from it and starts scheduled list refresh tasks.
 */
@SpringBootApplication
@EnableScheduling
public class SieveApplication {
    /**
     * Starts the Sieve server.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(SieveApplication.class, args);
    }
}
