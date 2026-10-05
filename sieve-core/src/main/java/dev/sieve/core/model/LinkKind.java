package dev.sieve.core.model;

/** What a page linked from an entity is. */
public enum LinkKind {
    /** The entity's own page on the list that names it, such as a wanted poster. */
    SOURCE_PAGE,
    /**
     * A legal act that listed the entity or changed its listing, such as an Official Journal act.
     */
    LEGAL_ACT,
    /** An encyclopedia article about the entity. */
    ENCYCLOPEDIA,
    /** The entity's own website. */
    WEBSITE,
    /** A news article that mentions the entity's name. */
    NEWS
}
