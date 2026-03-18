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

/**
 * JPA entity for a sanctions program associated with a sanctioned entity.
 */
@Entity
@Table(name = "entity_program")
public class EntityProgramRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "entity_id", nullable = false)
    private SanctionedEntityRow entity;

    @Column(name = "code", nullable = false, length = 200)
    private String code;

    @Column(name = "name", length = 500)
    private String name;

    protected EntityProgramRow() {}

    public EntityProgramRow(SanctionedEntityRow entity, String code, String name) {
        this.entity = entity;
        this.code = code;
        this.name = name;
    }

    public Long getId() { return id; }
    public SanctionedEntityRow getEntity() { return entity; }
    public String getCode() { return code; }
    public String getName() { return name; }
}
