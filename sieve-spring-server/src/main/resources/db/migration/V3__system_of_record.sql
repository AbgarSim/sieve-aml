-- ============================================================================
-- V3: PostgreSQL becomes the system of record.
--
-- Every value's provenance is kept in the JSONB 'data' column; first_seen and
-- last_seen summarise it per entity so it can be queried. Entities a list drops
-- are copied to entity_removal before they are deleted.
-- ============================================================================

ALTER TABLE sanctioned_entity
    ADD COLUMN first_seen TIMESTAMPTZ,
    ADD COLUMN last_seen  TIMESTAMPTZ;

CREATE INDEX idx_se_first_seen ON sanctioned_entity (first_seen);

CREATE TABLE entity_removal (
    id          BIGSERIAL    NOT NULL,
    entity_id   VARCHAR(255) NOT NULL,
    list_source VARCHAR(50)  NOT NULL,
    removed_at  TIMESTAMPTZ  NOT NULL,
    data        JSONB        NOT NULL,
    CONSTRAINT pk_entity_removal PRIMARY KEY (id)
);

CREATE INDEX idx_removal_entity_id ON entity_removal (entity_id);
CREATE INDEX idx_removal_source    ON entity_removal (list_source, removed_at);
