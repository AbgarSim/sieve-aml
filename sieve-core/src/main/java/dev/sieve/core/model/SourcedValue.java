package dev.sieve.core.model;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The provenance of one value of an entity, such as one alias or one passport number.
 *
 * <p>The value is identified by its kind and a key: the text of a name, the ISO form of a date,
 * {@code PASSPORT:123} for an identifier and so on (see {@link ValueKind}). {@link #keysOf} lists
 * the keys of every value an entity holds.
 *
 * @param kind what kind of value this is
 * @param value the value's key
 * @param provenance where and when the value was seen
 */
public record SourcedValue(ValueKind kind, String value, Provenance provenance) {

    /**
     * Compact constructor with validation.
     *
     * @throws NullPointerException if any argument is {@code null}
     */
    public SourcedValue {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(value, "value must not be null");
        Objects.requireNonNull(provenance, "provenance must not be null");
    }

    /**
     * Returns whether this is the provenance of the given value.
     *
     * @param key the value's kind and key
     * @return {@code true} if kind and key are equal
     */
    public boolean describes(Key key) {
        return kind == key.kind() && value.equals(key.value());
    }

    /**
     * Returns this value's kind and key.
     *
     * @return the key
     */
    public Key key() {
        return new Key(kind, value);
    }

    /**
     * Identifies one value of an entity.
     *
     * @param kind what kind of value
     * @param value the value's key
     */
    public record Key(ValueKind kind, String value) {

        /**
         * Compact constructor with validation.
         *
         * @throws NullPointerException if either argument is {@code null}
         */
        public Key {
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(value, "value must not be null");
        }

        /** Key of a name. */
        public static Key of(NameInfo name) {
            return new Key(ValueKind.NAME, name.fullName());
        }

        /** Key of a date of birth. */
        public static Key of(LocalDate dateOfBirth) {
            return new Key(ValueKind.BIRTH_DATE, dateOfBirth.toString());
        }

        /** Key of an identifier. */
        public static Key of(Identifier identifier) {
            return new Key(
                    ValueKind.IDENTIFIER, identifier.type().name() + ":" + identifier.value());
        }

        /** Key of an address. */
        public static Key of(Address address) {
            return new Key(ValueKind.ADDRESS, text(address));
        }

        /** Key of a program. */
        public static Key of(SanctionsProgram program) {
            return new Key(ValueKind.PROGRAM, program.code());
        }

        /** Key of a topic. */
        public static Key of(RiskTopic topic) {
            return new Key(ValueKind.TOPIC, topic.code());
        }

        /** Key of a relation. */
        public static Key of(Relation relation) {
            return new Key(ValueKind.RELATION, relation.type().name() + ":" + relation.targetId());
        }
    }

    /**
     * Lists the keys of every value the entity holds, without duplicates, in a stable order: names,
     * dates and places of birth, nationalities, citizenships, addresses, identifiers, programs,
     * topics, relations.
     *
     * @param entity the entity, must not be {@code null}
     * @return the keys
     */
    public static List<Key> keysOf(SanctionedEntity entity) {
        Objects.requireNonNull(entity, "entity must not be null");
        Set<Key> keys = new LinkedHashSet<>();
        keys.add(Key.of(entity.primaryName()));
        entity.aliases().forEach(n -> keys.add(Key.of(n)));
        entity.datesOfBirth().forEach(d -> keys.add(Key.of(d)));
        entity.placesOfBirth().forEach(p -> keys.add(new Key(ValueKind.BIRTH_PLACE, p)));
        entity.nationalities().forEach(c -> keys.add(new Key(ValueKind.NATIONALITY, c)));
        entity.citizenships().forEach(c -> keys.add(new Key(ValueKind.CITIZENSHIP, c)));
        entity.addresses().forEach(a -> keys.add(Key.of(a)));
        entity.identifiers().forEach(i -> keys.add(Key.of(i)));
        entity.programs().forEach(p -> keys.add(Key.of(p)));
        entity.topics().forEach(t -> keys.add(Key.of(t)));
        entity.relations().forEach(r -> keys.add(Key.of(r)));
        return new ArrayList<>(keys);
    }

    private static String text(Address address) {
        if (address.fullAddress() != null && !address.fullAddress().isBlank()) {
            return address.fullAddress();
        }
        return String.join(
                ", ",
                Stream.of(
                                address.street(),
                                address.city(),
                                address.stateOrProvince(),
                                address.postalCode(),
                                address.country())
                        .filter(p -> p != null && !p.isBlank())
                        .toList());
    }
}
