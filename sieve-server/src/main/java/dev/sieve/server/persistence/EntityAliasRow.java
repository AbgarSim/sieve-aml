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
 * JPA entity for an alias name associated with a sanctioned entity.
 */
@Entity
@Table(name = "entity_alias")
public class EntityAliasRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "entity_id", nullable = false)
    private SanctionedEntityRow entity;

    @Column(name = "full_name", nullable = false, length = 1000)
    private String fullName;

    @Column(name = "given_name", length = 500)
    private String givenName;

    @Column(name = "family_name", length = 500)
    private String familyName;

    @Column(name = "middle_name", length = 500)
    private String middleName;

    @Column(name = "title", length = 500)
    private String title;

    @Column(name = "name_type", nullable = false, length = 50)
    private String nameType;

    @Column(name = "name_strength", length = 50)
    private String nameStrength;

    @Column(name = "script", length = 50)
    private String script;

    protected EntityAliasRow() {}

    public EntityAliasRow(
            SanctionedEntityRow entity,
            String fullName,
            String givenName,
            String familyName,
            String middleName,
            String title,
            String nameType,
            String nameStrength,
            String script) {
        this.entity = entity;
        this.fullName = fullName;
        this.givenName = givenName;
        this.familyName = familyName;
        this.middleName = middleName;
        this.title = title;
        this.nameType = nameType;
        this.nameStrength = nameStrength;
        this.script = script;
    }

    public Long getId() { return id; }
    public SanctionedEntityRow getEntity() { return entity; }
    public String getFullName() { return fullName; }
    public String getGivenName() { return givenName; }
    public String getFamilyName() { return familyName; }
    public String getMiddleName() { return middleName; }
    public String getTitle() { return title; }
    public String getNameType() { return nameType; }
    public String getNameStrength() { return nameStrength; }
    public String getScript() { return script; }
}
