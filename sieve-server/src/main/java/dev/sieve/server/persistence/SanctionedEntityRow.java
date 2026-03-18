package dev.sieve.server.persistence;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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

    // -- Searchable columns for individuals / organisations --

    @Column(name = "given_name", length = 500)
    private String givenName;

    @Column(name = "family_name", length = 500)
    private String familyName;

    @Column(name = "middle_name", length = 500)
    private String middleName;

    @Column(name = "title", length = 500)
    private String title;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(name = "place_of_birth", length = 500)
    private String placeOfBirth;

    @Column(name = "nationality", length = 500)
    private String nationality;

    @Column(name = "citizenship", length = 500)
    private String citizenship;

    // -- Child collections --

    @OneToMany(mappedBy = "entity", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<EntityAliasRow> aliases = new ArrayList<>();

    @OneToMany(mappedBy = "entity", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<EntityAddressRow> addresses = new ArrayList<>();

    @OneToMany(mappedBy = "entity", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<EntityIdentifierRow> identifiers = new ArrayList<>();

    @OneToMany(mappedBy = "entity", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<EntityProgramRow> programs = new ArrayList<>();

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

    public String getGivenName() { return givenName; }
    public void setGivenName(String givenName) { this.givenName = givenName; }

    public String getFamilyName() { return familyName; }
    public void setFamilyName(String familyName) { this.familyName = familyName; }

    public String getMiddleName() { return middleName; }
    public void setMiddleName(String middleName) { this.middleName = middleName; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public void setDateOfBirth(LocalDate dateOfBirth) { this.dateOfBirth = dateOfBirth; }

    public String getPlaceOfBirth() { return placeOfBirth; }
    public void setPlaceOfBirth(String placeOfBirth) { this.placeOfBirth = placeOfBirth; }

    public String getNationality() { return nationality; }
    public void setNationality(String nationality) { this.nationality = nationality; }

    public String getCitizenship() { return citizenship; }
    public void setCitizenship(String citizenship) { this.citizenship = citizenship; }

    public List<EntityAliasRow> getAliases() { return aliases; }
    public List<EntityAddressRow> getAddresses() { return addresses; }
    public List<EntityIdentifierRow> getIdentifiers() { return identifiers; }
    public List<EntityProgramRow> getPrograms() { return programs; }
}
