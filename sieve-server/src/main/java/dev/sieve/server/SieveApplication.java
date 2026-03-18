package dev.sieve.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the Sieve sanctions screening REST API.
 *
 * <p>Boots the Spring context, initializes the entity index, and starts scheduled list refresh
 * tasks. Database auto-configuration is excluded by default (in-memory mode) and re-imported
 * conditionally by {@link dev.sieve.server.config.PostgresConfiguration} when {@code
 * sieve.index.type=postgres}.
 */
@SpringBootApplication(
        exclude = {
            DataSourceAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            JpaRepositoriesAutoConfiguration.class,
            FlywayAutoConfiguration.class
        })
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
