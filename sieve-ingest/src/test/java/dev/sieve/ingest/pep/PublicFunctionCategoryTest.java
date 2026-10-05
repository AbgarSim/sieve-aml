package dev.sieve.ingest.pep;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PublicFunctionCategoryTest {

    @Test
    void shouldNumberThePointsOfArticle3InOrder() {
        assertThat(PublicFunctionCategory.values())
                .extracting(PublicFunctionCategory::point)
                .containsExactly('a', 'b', 'c', 'd', 'e', 'f', 'g', 'h');
        assertThat(PublicFunctionCategory.values())
                .allSatisfy(category -> assertThat(category.description()).isNotBlank());
    }

    @Test
    void shouldCiteTheDirective() {
        assertThat(PublicFunctionCategory.STATE_OWNED_ENTERPRISES.citation())
                .isEqualTo("Directive (EU) 2015/849 Art. 3(9)(g)");
    }

    @Test
    void shouldFindACategoryByItsPointInEitherCase() {
        assertThat(PublicFunctionCategory.fromPoint("a"))
                .contains(PublicFunctionCategory.HEADS_OF_STATE_AND_GOVERNMENT);
        assertThat(PublicFunctionCategory.fromPoint("H"))
                .contains(PublicFunctionCategory.INTERNATIONAL_ORGANISATIONS);
        assertThat(PublicFunctionCategory.fromPoint(" d "))
                .contains(PublicFunctionCategory.HIGH_COURTS);
        assertThat(PublicFunctionCategory.fromPoint("x")).isEmpty();
        assertThat(PublicFunctionCategory.fromPoint("ab")).isEmpty();
        assertThat(PublicFunctionCategory.fromPoint(null)).isEmpty();
    }
}
