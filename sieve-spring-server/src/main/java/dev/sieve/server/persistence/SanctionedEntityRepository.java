package dev.sieve.server.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for {@link SanctionedEntityRow}.
 *
 * <p>Provides standard CRUD operations plus custom aggregate queries used by {@link
 * JpaEntityIndex#stats()}.
 */
public interface SanctionedEntityRepository extends JpaRepository<SanctionedEntityRow, String> {

    List<SanctionedEntityRow> findByListSource(String listSource);

    /**
     * Returns the ids of all rows from one list source.
     *
     * @param listSource the {@code ListSource} name
     * @return the ids, never {@code null}
     */
    @Query("SELECT e.id FROM SanctionedEntityRow e WHERE e.listSource = :listSource")
    List<String> findIdsByListSource(@Param("listSource") String listSource);

    @Query("SELECT e.listSource, COUNT(e) FROM SanctionedEntityRow e GROUP BY e.listSource")
    List<Object[]> countByListSource();

    @Query("SELECT e.entityType, COUNT(e) FROM SanctionedEntityRow e GROUP BY e.entityType")
    List<Object[]> countByEntityType();
}
