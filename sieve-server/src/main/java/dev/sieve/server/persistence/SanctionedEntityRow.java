package dev.sieve.server.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA entity representing a sanctioned entity row in PostgreSQL.
 *
 * <p>Queryable columns ({@code entityType}, {@code listSource}, {@code primaryName}) are stored as
 * plain columns for efficient indexing and filtering. The complete domain object is serialized as
 * JSON in the {@code data} column.
 */
@Entity
@Table(name = "sanctioned_entity")
public class SanctionedEntityRow {

    @Id
    @Column(name = "id", length = 255)
    private String id;

    @Column(name = "entity_type", nullable = false, length = 50)
    private String entityType;

    @Column(name = "list_source", nullable = false, length = 50)
    private String listSource;

    @Column(name = "primary_name", nullable = false, length = 1000)
    private String primaryName;

    @Column(name = "remarks", columnDefinition = "TEXT")
    private String remarks;

    @Column(name = "listed_date")
    private Instant listedDate;

    @Column(name = "last_updated")
    private Instant lastUpdated;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data", nullable = false)
    private String data;

    /** Default constructor required by JPA. */
    protected SanctionedEntityRow() {}

    public SanctionedEntityRow(
            String id,
            String entityType,
            String listSource,
            String primaryName,
            String remarks,
            Instant listedDate,
            Instant lastUpdated,
            String data) {
        this.id = id;
        this.entityType = entityType;
        this.listSource = listSource;
        this.primaryName = primaryName;
        this.remarks = remarks;
        this.listedDate = listedDate;
        this.lastUpdated = lastUpdated;
        this.data = data;
    }

    public String getId() {
        return id;
    }

    public String getEntityType() {
        return entityType;
    }

    public String getListSource() {
        return listSource;
    }

    public String getPrimaryName() {
        return primaryName;
    }

    public String getRemarks() {
        return remarks;
    }

    public Instant getListedDate() {
        return listedDate;
    }

    public Instant getLastUpdated() {
        return lastUpdated;
    }

    public String getData() {
        return data;
    }
}
