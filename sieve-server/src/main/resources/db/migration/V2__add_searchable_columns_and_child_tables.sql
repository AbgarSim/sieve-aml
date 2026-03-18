-- ============================================================================
-- V2: Add precise searchable columns to sanctioned_entity and create child
--     tables for aliases, addresses, identifiers, and programs.
--
-- The JSONB 'data' column is retained for full domain-object reconstruction.
-- The new columns and child tables enable direct SQL search and filtering.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. Add individual / organisation searchable columns to the main table
-- ---------------------------------------------------------------------------
ALTER TABLE sanctioned_entity
    ADD COLUMN given_name       VARCHAR(500),
    ADD COLUMN family_name      VARCHAR(500),
    ADD COLUMN middle_name      VARCHAR(500),
    ADD COLUMN title            VARCHAR(500),
    ADD COLUMN date_of_birth    DATE,
    ADD COLUMN place_of_birth   VARCHAR(500),
    ADD COLUMN nationality      VARCHAR(500),
    ADD COLUMN citizenship      VARCHAR(500);

CREATE INDEX idx_se_given_name_trgm  ON sanctioned_entity USING gin (given_name  gin_trgm_ops);
CREATE INDEX idx_se_family_name_trgm ON sanctioned_entity USING gin (family_name gin_trgm_ops);
CREATE INDEX idx_se_date_of_birth    ON sanctioned_entity (date_of_birth);
CREATE INDEX idx_se_nationality      ON sanctioned_entity (nationality);

-- ---------------------------------------------------------------------------
-- 2. Entity aliases (one-to-many)
-- ---------------------------------------------------------------------------
CREATE TABLE entity_alias (
    id              BIGSERIAL     NOT NULL,
    entity_id       VARCHAR(255)  NOT NULL,
    full_name       VARCHAR(1000) NOT NULL,
    given_name      VARCHAR(500),
    family_name     VARCHAR(500),
    middle_name     VARCHAR(500),
    title           VARCHAR(500),
    name_type       VARCHAR(50)   NOT NULL,
    name_strength   VARCHAR(50),
    script          VARCHAR(50),
    CONSTRAINT pk_entity_alias PRIMARY KEY (id),
    CONSTRAINT fk_alias_entity FOREIGN KEY (entity_id)
        REFERENCES sanctioned_entity (id) ON DELETE CASCADE
);

CREATE INDEX idx_alias_entity_id      ON entity_alias (entity_id);
CREATE INDEX idx_alias_full_name_trgm ON entity_alias USING gin (full_name gin_trgm_ops);

-- ---------------------------------------------------------------------------
-- 3. Entity addresses (one-to-many)
-- ---------------------------------------------------------------------------
CREATE TABLE entity_address (
    id                BIGSERIAL    NOT NULL,
    entity_id         VARCHAR(255) NOT NULL,
    street            VARCHAR(500),
    city              VARCHAR(500),
    state_or_province VARCHAR(500),
    postal_code       VARCHAR(100),
    country           VARCHAR(500),
    full_address      TEXT,
    CONSTRAINT pk_entity_address PRIMARY KEY (id),
    CONSTRAINT fk_address_entity FOREIGN KEY (entity_id)
        REFERENCES sanctioned_entity (id) ON DELETE CASCADE
);

CREATE INDEX idx_address_entity_id ON entity_address (entity_id);
CREATE INDEX idx_address_country   ON entity_address (country);

-- ---------------------------------------------------------------------------
-- 4. Entity identifiers (one-to-many)
-- ---------------------------------------------------------------------------
CREATE TABLE entity_identifier (
    id              BIGSERIAL    NOT NULL,
    entity_id       VARCHAR(255) NOT NULL,
    type            VARCHAR(50)  NOT NULL,
    value           VARCHAR(500) NOT NULL,
    issuing_country VARCHAR(500),
    remarks         TEXT,
    CONSTRAINT pk_entity_identifier PRIMARY KEY (id),
    CONSTRAINT fk_identifier_entity FOREIGN KEY (entity_id)
        REFERENCES sanctioned_entity (id) ON DELETE CASCADE
);

CREATE INDEX idx_identifier_entity_id ON entity_identifier (entity_id);
CREATE INDEX idx_identifier_value     ON entity_identifier (value);
CREATE INDEX idx_identifier_type      ON entity_identifier (type);

-- ---------------------------------------------------------------------------
-- 5. Entity sanctions programs (one-to-many)
-- ---------------------------------------------------------------------------
CREATE TABLE entity_program (
    id        BIGSERIAL    NOT NULL,
    entity_id VARCHAR(255) NOT NULL,
    code      VARCHAR(200) NOT NULL,
    name      VARCHAR(500),
    CONSTRAINT pk_entity_program PRIMARY KEY (id),
    CONSTRAINT fk_program_entity FOREIGN KEY (entity_id)
        REFERENCES sanctioned_entity (id) ON DELETE CASCADE
);

CREATE INDEX idx_program_entity_id ON entity_program (entity_id);
CREATE INDEX idx_program_code      ON entity_program (code);

-- ---------------------------------------------------------------------------
-- 6. Back-fill new columns from existing JSONB data
-- ---------------------------------------------------------------------------
UPDATE sanctioned_entity SET
    given_name     = LEFT(data->'primaryName'->>'givenName', 500),
    family_name    = LEFT(data->'primaryName'->>'familyName', 500),
    middle_name    = LEFT(data->'primaryName'->>'middleName', 500),
    title          = LEFT(data->'primaryName'->>'title', 500),
    date_of_birth  = CASE
                       WHEN jsonb_array_length(COALESCE(data->'datesOfBirth', '[]'::jsonb)) > 0
                       THEN (data->'datesOfBirth'->>0)::date
                       ELSE NULL
                     END,
    place_of_birth = LEFT(data->'placesOfBirth'->>0, 500),
    nationality    = LEFT(data->'nationalities'->>0, 500),
    citizenship    = LEFT(data->'citizenships'->>0, 500);

-- Back-fill aliases
INSERT INTO entity_alias (entity_id, full_name, given_name, family_name, middle_name, title, name_type, name_strength, script)
SELECT
    se.id,
    LEFT(a->>'fullName', 1000),
    LEFT(a->>'givenName', 500),
    LEFT(a->>'familyName', 500),
    LEFT(a->>'middleName', 500),
    LEFT(a->>'title', 500),
    LEFT(COALESCE(a->>'nameType', 'PRIMARY'), 50),
    LEFT(a->>'strength', 50),
    LEFT(a->>'script', 50)
FROM sanctioned_entity se,
     jsonb_array_elements(COALESCE(se.data->'aliases', '[]'::jsonb)) AS a;

-- Back-fill addresses
INSERT INTO entity_address (entity_id, street, city, state_or_province, postal_code, country, full_address)
SELECT
    se.id,
    LEFT(a->>'street', 500),
    LEFT(a->>'city', 500),
    LEFT(a->>'stateOrProvince', 500),
    LEFT(a->>'postalCode', 100),
    LEFT(a->>'country', 500),
    a->>'fullAddress'
FROM sanctioned_entity se,
     jsonb_array_elements(COALESCE(se.data->'addresses', '[]'::jsonb)) AS a;

-- Back-fill identifiers
INSERT INTO entity_identifier (entity_id, type, value, issuing_country, remarks)
SELECT
    se.id,
    LEFT(i->>'type', 50),
    LEFT(i->>'value', 500),
    LEFT(i->>'issuingCountry', 500),
    i->>'remarks'
FROM sanctioned_entity se,
     jsonb_array_elements(COALESCE(se.data->'identifiers', '[]'::jsonb)) AS i;

-- Back-fill programs
INSERT INTO entity_program (entity_id, code, name)
SELECT
    se.id,
    LEFT(p->>'code', 200),
    LEFT(p->>'name', 500)
FROM sanctioned_entity se,
     jsonb_array_elements(COALESCE(se.data->'programs', '[]'::jsonb)) AS p;
