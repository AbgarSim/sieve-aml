package dev.sieve.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class EntityTypeTest {

    @ParameterizedTest
    @CsvSource({
        "INDIVIDUAL, Individual",
        "ENTITY, Entity",
        "VESSEL, Vessel",
        "AIRCRAFT, Aircraft",
        "COMPANY, Company",
        "ORGANIZATION, Organization",
        "CRYPTO_WALLET, Crypto wallet",
        "SECURITY, Security"
    })
    void shouldReturnCorrectDisplayName(String enumName, String expectedDisplay) {
        EntityType type = EntityType.valueOf(enumName);
        assertThat(type.displayName()).isEqualTo(expectedDisplay);
    }

    @ParameterizedTest
    @CsvSource({
        "INDIVIDUAL, INDIVIDUAL",
        "individual, INDIVIDUAL",
        "Individual, INDIVIDUAL",
        "ENTITY, ENTITY",
        "entity, ENTITY",
        "vessel, VESSEL",
        "Aircraft, AIRCRAFT",
        "Person, INDIVIDUAL",
        "Airplane, AIRCRAFT",
        "LegalEntity, ENTITY",
        "organisation, ORGANIZATION",
        "crypto_wallet, CRYPTO_WALLET",
        "Crypto wallet, CRYPTO_WALLET",
        "CryptoWallet, CRYPTO_WALLET"
    })
    void shouldResolveFromString(String input, String expectedName) {
        assertThat(EntityType.fromString(input)).isEqualTo(EntityType.valueOf(expectedName));
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "SHIP", "", "   "})
    void shouldThrowForUnknownValue(String value) {
        assertThatThrownBy(() -> EntityType.fromString(value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown EntityType");
    }

    @ParameterizedTest
    @NullSource
    void shouldThrowForNullValue(String value) {
        assertThatThrownBy(() -> EntityType.fromString(value))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldHaveEightValues() {
        assertThat(EntityType.values()).hasSize(8);
    }

    @ParameterizedTest
    @CsvSource({
        "ENTITY, COMPANY, true",
        "COMPANY, ENTITY, true",
        "ORGANIZATION, ENTITY, true",
        "COMPANY, ORGANIZATION, false",
        "INDIVIDUAL, ENTITY, false",
        "VESSEL, VESSEL, true",
        "CRYPTO_WALLET, ENTITY, false"
    })
    void shouldTreatGenericEntityAsCompatibleWithCompanyAndOrganization(
            EntityType a, EntityType b, boolean expected) {
        assertThat(a.isCompatibleWith(b)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
        "Person, INDIVIDUAL",
        "Company, COMPANY",
        "PublicBody, ORGANIZATION",
        "Thing, ENTITY",
        "Security, SECURITY"
    })
    void shouldResolveFromSchema(String schema, EntityType expected) {
        assertThat(EntityType.fromSchema(schema)).isEqualTo(expected);
    }

    @Test
    void shouldRejectSchemaThatIsNotAnEntity() {
        assertThatThrownBy(() -> EntityType.fromSchema("Ownership"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
