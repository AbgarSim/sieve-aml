package dev.sieve.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sieve.server.persistence.PersistentEntityIndex;
import dev.sieve.server.persistence.PostgresEntityStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wires PostgreSQL as the system of record and the in-memory screening index built from it.
 *
 * <p>The server needs a database: the data source comes from {@code spring.datasource.*} and Flyway
 * creates the schema on startup.
 */
@Configuration
public class PostgresConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PostgresConfiguration.class);

    /**
     * Creates the store that reads and writes entities in PostgreSQL.
     *
     * @param jdbc access to the database
     * @param transactions runs each list refresh in one transaction
     * @param objectMapper writes the JSON column
     * @return the store
     */
    @Bean
    public PostgresEntityStore postgresEntityStore(
            JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper objectMapper) {
        return new PostgresEntityStore(jdbc, transactions, objectMapper);
    }

    /**
     * Creates the screening index. It starts empty; {@link
     * dev.sieve.server.schedule.StartupIngestionInitializer} loads it from the database.
     *
     * @param store the system of record
     * @return the index
     */
    @Bean
    public PersistentEntityIndex entityIndex(PostgresEntityStore store) {
        log.info("Using PostgreSQL as the system of record");
        return new PersistentEntityIndex(store);
    }
}
