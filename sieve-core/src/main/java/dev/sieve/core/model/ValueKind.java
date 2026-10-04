package dev.sieve.core.model;

/** The kinds of value on an entity whose provenance is recorded (see {@link SourcedValue}). */
public enum ValueKind {

    /** The primary name or an alias, keyed by its full name. */
    NAME,

    /** A date of birth, keyed by its ISO date. */
    BIRTH_DATE,

    /** A place of birth. */
    BIRTH_PLACE,

    /** A nationality. */
    NATIONALITY,

    /** A citizenship. */
    CITIZENSHIP,

    /** An address, keyed by its full text. */
    ADDRESS,

    /** An identifier, keyed by {@code TYPE:value}. */
    IDENTIFIER,

    /** A sanctions program, keyed by its code. */
    PROGRAM,

    /** A risk topic, keyed by its code. */
    TOPIC,

    /** A relation, keyed by {@code TYPE:targetId}. */
    RELATION
}
