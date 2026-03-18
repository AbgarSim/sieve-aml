package dev.sieve.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sieve.core.index.EntityIndex;
import dev.sieve.server.persistence.JpaEntityIndex;
import dev.sieve.server.persistence.SanctionedEntityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Configuration that activates PostgreSQL-backed persistence when {@code sieve.index.type=postgres}.
 *
 * <p>Re-imports the Spring Boot auto-configuration classes that are excluded by default in {@link
 * dev.sieve.server.SieveApplication}, and wires up the {@link JpaEntityIndex} as the {@link
 * EntityIndex} implementation.
 */
@Configuration
@ConditionalOnProperty(name = "sieve.index.type", havingValue = "postgres")
@ImportAutoConfiguration({
    DataSourceAutoConfiguration.class,
    HibernateJpaAutoConfiguration.class,
    JpaRepositoriesAutoConfiguration.class,
    TransactionAutoConfiguration.class,
    FlywayAutoConfiguration.class
})
@EnableJpaRepositories(basePackages = "dev.sieve.server.persistence")
public class PostgresConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PostgresConfiguration.class);

    /**
     * Creates a PostgreSQL-backed entity index.
     *
     * @param repository the JPA repository for sanctioned entity rows
     * @param objectMapper the Jackson object mapper for JSON serialization
     * @return the PostgreSQL-backed entity index
     */
    @Bean
    public EntityIndex entityIndex(
            SanctionedEntityRepository repository, ObjectMapper objectMapper) {
        log.info("Initializing PostgreSQL-backed entity index");
        return new JpaEntityIndex(repository, objectMapper);
    }
}
