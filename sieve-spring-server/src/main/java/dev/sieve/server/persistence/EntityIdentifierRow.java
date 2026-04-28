package dev.sieve.server.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** JPA entity for an identity document or reference number associated with a sanctioned entity. */
@Entity
@Table(name = "entity_identifier")
public class EntityIdentifierRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "entity_id", nullable = false)
    private SanctionedEntityRow entity;

    @Column(name = "type", nullable = false, length = 50)
    private String type;

    @Column(name = "value", nullable = false, length = 500)
    private String value;

    @Column(name = "issuing_country", length = 500)
    private String issuingCountry;

    @Column(name = "remarks", columnDefinition = "TEXT")
    private String remarks;

    protected EntityIdentifierRow() {}

    public EntityIdentifierRow(
            SanctionedEntityRow entity,
            String type,
            String value,
            String issuingCountry,
            String remarks) {
        this.entity = entity;
        this.type = type;
        this.value = value;
        this.issuingCountry = issuingCountry;
        this.remarks = remarks;
    }

    public Long getId() {
        return id;
    }

    public SanctionedEntityRow getEntity() {
        return entity;
    }

    public String getType() {
        return type;
    }

    public String getValue() {
        return value;
    }

    public String getIssuingCountry() {
        return issuingCountry;
    }

    public String getRemarks() {
        return remarks;
    }
}
