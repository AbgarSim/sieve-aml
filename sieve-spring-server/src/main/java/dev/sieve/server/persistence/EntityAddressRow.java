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

/** JPA entity for an address associated with a sanctioned entity. */
@Entity
@Table(name = "entity_address")
public class EntityAddressRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "entity_id", nullable = false)
    private SanctionedEntityRow entity;

    @Column(name = "street", length = 500)
    private String street;

    @Column(name = "city", length = 500)
    private String city;

    @Column(name = "state_or_province", length = 500)
    private String stateOrProvince;

    @Column(name = "postal_code", length = 100)
    private String postalCode;

    @Column(name = "country", length = 500)
    private String country;

    @Column(name = "full_address", columnDefinition = "TEXT")
    private String fullAddress;

    protected EntityAddressRow() {}

    public EntityAddressRow(
            SanctionedEntityRow entity,
            String street,
            String city,
            String stateOrProvince,
            String postalCode,
            String country,
            String fullAddress) {
        this.entity = entity;
        this.street = street;
        this.city = city;
        this.stateOrProvince = stateOrProvince;
        this.postalCode = postalCode;
        this.country = country;
        this.fullAddress = fullAddress;
    }

    public Long getId() {
        return id;
    }

    public SanctionedEntityRow getEntity() {
        return entity;
    }

    public String getStreet() {
        return street;
    }

    public String getCity() {
        return city;
    }

    public String getStateOrProvince() {
        return stateOrProvince;
    }

    public String getPostalCode() {
        return postalCode;
    }

    public String getCountry() {
        return country;
    }

    public String getFullAddress() {
        return fullAddress;
    }
}
