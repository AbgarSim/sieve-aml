-- Enable trigram extension for fuzzy name search
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE sanctioned_entity (
    id              VARCHAR(255)  NOT NULL,
    entity_type     VARCHAR(50)   NOT NULL,
    list_source     VARCHAR(50)   NOT NULL,
    primary_name    VARCHAR(1000) NOT NULL,
    remarks         TEXT,
    listed_date     TIMESTAMPTZ,
    last_updated    TIMESTAMPTZ,
    data            JSONB         NOT NULL,
    CONSTRAINT pk_sanctioned_entity PRIMARY KEY (id)
);

CREATE INDEX idx_se_list_source ON sanctioned_entity (list_source);
CREATE INDEX idx_se_entity_type ON sanctioned_entity (entity_type);
CREATE INDEX idx_se_primary_name_trgm ON sanctioned_entity USING gin (primary_name gin_trgm_ops);
