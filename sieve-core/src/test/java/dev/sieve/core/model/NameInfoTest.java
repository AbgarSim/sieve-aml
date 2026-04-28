package dev.sieve.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class NameInfoTest {

    @Test
    void shouldCreateValidNameInfo() {
        NameInfo name =
                new NameInfo(
                        "DOE, John",
                        "John",
                        "DOE",
                        null,
                        null,
                        NameType.PRIMARY,
                        NameStrength.STRONG,
                        ScriptType.LATIN);

        assertThat(name.fullName()).isEqualTo("DOE, John");
        assertThat(name.givenName()).isEqualTo("John");
        assertThat(name.familyName()).isEqualTo("DOE");
        assertThat(name.nameType()).isEqualTo(NameType.PRIMARY);
    }

    @Test
    void shouldThrowWhenFullNameIsNull() {
        assertThatThrownBy(
                        () ->
                                new NameInfo(
                                        null, null, null, null, null, NameType.PRIMARY, null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("fullName");
    }

    @Test
    void shouldThrowWhenNameTypeIsNull() {
        assertThatThrownBy(() -> new NameInfo("John Doe", null, null, null, null, null, null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("nameType");
    }

    @Test
    void shouldAllowNullOptionalFields() {
        NameInfo name = new NameInfo("John Doe", null, null, null, null, NameType.AKA, null, null);

        assertThat(name.givenName()).isNull();
        assertThat(name.familyName()).isNull();
        assertThat(name.middleName()).isNull();
        assertThat(name.title()).isNull();
        assertThat(name.strength()).isNull();
        assertThat(name.script()).isNull();
    }
}
