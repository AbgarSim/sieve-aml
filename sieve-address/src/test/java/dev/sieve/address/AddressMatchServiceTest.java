package dev.sieve.address;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sieve.core.index.InMemoryEntityIndex;
import dev.sieve.core.model.*;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AddressMatchServiceTest {

    private AddressMatchService service;
    private InMemoryEntityIndex index;

    @BeforeEach
    void setUp() {
        // Use fallback mode (no libpostal native lib in test)
        AddressNormalizer normalizer = new AddressNormalizer();
        normalizer.init();
        service = new AddressMatchService(normalizer);
        index = new InMemoryEntityIndex();
    }

    @Test
    void shouldMatchEntityByCountryAndCity() {
        index.add(
                createEntityWithAddress(
                        "1",
                        "PUTIN, Vladimir",
                        new Address(
                                "Kremlin",
                                "Moscow",
                                null,
                                null,
                                "Russia",
                                "Kremlin, Moscow, Russia")));

        List<AddressMatchService.AddressMatchResult> results =
                service.screen("Moscow, Russia", index, 0.50, 10);

        assertThat(results).isNotEmpty();
        assertThat(results.getFirst().entity().id()).isEqualTo("1");
        assertThat(results.getFirst().score()).isGreaterThan(0.50);
    }

    @Test
    void shouldReturnEmptyForNoAddressMatch() {
        index.add(
                createEntityWithAddress(
                        "1",
                        "DOE, John",
                        new Address(
                                "123 Main St",
                                "London",
                                null,
                                null,
                                "UK",
                                "123 Main St, London, UK")));

        List<AddressMatchService.AddressMatchResult> results =
                service.screen("Tokyo, Japan", index, 0.50, 10);

        assertThat(results).isEmpty();
    }

    @Test
    void shouldSkipEntitiesWithNoAddresses() {
        index.add(createEntityNoAddress("1", "DOE, John"));

        List<AddressMatchService.AddressMatchResult> results =
                service.screen("London, UK", index, 0.50, 10);

        assertThat(results).isEmpty();
    }

    @Test
    void shouldRespectThreshold() {
        index.add(
                createEntityWithAddress(
                        "1",
                        "DOE, John",
                        new Address(
                                "123 Main St",
                                "London",
                                null,
                                null,
                                "UK",
                                "123 Main St, London, UK")));

        // Very high threshold — partial match (city only in query vs structured entity) shouldn't
        // pass
        List<AddressMatchService.AddressMatchResult> results =
                service.screen("London", index, 0.99, 10);

        assertThat(results).isEmpty();
    }

    @Test
    void shouldRespectMaxResults() {
        for (int i = 0; i < 5; i++) {
            index.add(
                    createEntityWithAddress(
                            String.valueOf(i),
                            "Entity " + i,
                            new Address(null, "London", null, null, "UK", "London, UK")));
        }

        List<AddressMatchService.AddressMatchResult> results =
                service.screen("London, UK", index, 0.50, 2);

        assertThat(results).hasSize(2);
    }

    @Test
    void shouldSortByScoreDescending() {
        index.add(
                createEntityWithAddress(
                        "1",
                        "Entity A",
                        new Address(
                                "123 Main St",
                                "London",
                                "England",
                                "SW1A",
                                "UK",
                                "123 Main St, London, England, SW1A, UK")));
        index.add(
                createEntityWithAddress(
                        "2",
                        "Entity B",
                        new Address(null, "London", null, null, "UK", "London, UK")));

        List<AddressMatchService.AddressMatchResult> results =
                service.screen("123 Main St, London, England, SW1A, UK", index, 0.30, 10);

        assertThat(results).hasSizeGreaterThanOrEqualTo(1);
        if (results.size() > 1) {
            assertThat(results.get(0).score()).isGreaterThanOrEqualTo(results.get(1).score());
        }
    }

    @Test
    void scoreComponentsShouldHandleAllNulls() {
        AddressMatchService.NormalizedAddress empty =
                new AddressMatchService.NormalizedAddress(null, null, null, null, null);
        assertThat(AddressMatchService.scoreComponents(empty, empty)).isEqualTo(0.0);
    }

    private static SanctionedEntity createEntityWithAddress(
            String id, String fullName, Address address) {
        return new SanctionedEntity(
                id,
                EntityType.INDIVIDUAL,
                ListSource.OFAC_SDN,
                new NameInfo(
                        fullName,
                        null,
                        null,
                        null,
                        null,
                        NameType.PRIMARY,
                        NameStrength.STRONG,
                        ScriptType.LATIN),
                List.of(),
                List.of(address),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of(),
                null,
                null);
    }

    private static SanctionedEntity createEntityNoAddress(String id, String fullName) {
        return new SanctionedEntity(
                id,
                EntityType.INDIVIDUAL,
                ListSource.OFAC_SDN,
                new NameInfo(
                        fullName,
                        null,
                        null,
                        null,
                        null,
                        NameType.PRIMARY,
                        NameStrength.STRONG,
                        ScriptType.LATIN),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of(),
                null,
                null);
    }
}
