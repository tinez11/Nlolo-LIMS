-- The individual person record: the facts an underwriter needs about a human being.
--
-- Until now party.party held display_name, date_of_birth, contact details and a KYC
-- status, and nothing else. That is not only thin against the requirement -- it left
-- the platform's own pricing path asking for data it refuses to store:
--
--   product.base_rate_table  UNIQUE (product_version_id, age_from, sex, smoker_status)
--   PremiumQuoteRequest      requires sex, smokerStatus, occupationClass
--
-- The rating engine's primary key is (age, sex, smoker_status). Age comes from
-- date_of_birth; the other two were asserted per quote and then discarded, so nothing
-- recorded the risk facts a premium was priced on. product.api.Sex and
-- product.api.SmokerStatus both say exactly this in their own Javadoc. These columns
-- are that missing source.
--
-- Every column is NULLABLE on purpose. Parties registered before this migration have
-- none of these facts, and a NOT NULL column with a backfilled default would invent
-- them about real people.
--
-- See docs/superpowers/specs/2026-09-02-build1-individual-person-record-design.md.

ALTER TABLE party.party
    -- Rating dimensions. No DEFAULT, deliberately: a default of UNKNOWN would apply to
    -- corporates and groups as well, and would make "nobody asked" indistinguishable
    -- from "the applicant declined to say".
    ADD COLUMN sex              VARCHAR(10),
    ADD COLUMN smoker_status    VARCHAR(20),

    -- Identity document. INDIVIDUAL only, mirroring how date_of_birth is already
    -- documented on this table.
    ADD COLUMN id_type          VARCHAR(20),
    ADD COLUMN id_number        VARCHAR(50),

    -- occupation is what the applicant said they do. occupation_class is the rating
    -- band an underwriter assigns, matching product.rating_table's OCCUPATION_CLASS
    -- factor. They are separate because the platform does not own a canonical
    -- occupation list -- refdata holds nine numeric placeholder code sets and backs no
    -- dropdown anywhere -- so the declared text is recorded as given and the class is a
    -- judgement someone makes.
    ADD COLUMN occupation       VARCHAR(120),
    ADD COLUMN occupation_class VARCHAR(30),
    ADD COLUMN employer_name    VARCHAR(255),

    -- ISO 3166-1 alpha-2.
    ADD COLUMN nationality      CHAR(2),

    -- One address per party, as columns. A party with several addresses (residential,
    -- postal, employer) is a real future need and a separate table when it arrives;
    -- five nullable columns now beat a premature join. Shaped for Tanzanian addressing.
    ADD COLUMN address_line     VARCHAR(255),
    ADD COLUMN ward             VARCHAR(100),
    ADD COLUMN district         VARCHAR(100),
    ADD COLUMN region           VARCHAR(100),
    ADD COLUMN postal_code      VARCHAR(20);

ALTER TABLE party.party
    ADD CONSTRAINT party_sex_valid
        CHECK (sex IS NULL OR sex IN ('FEMALE','MALE')),
    ADD CONSTRAINT party_smoker_status_valid
        CHECK (smoker_status IS NULL OR smoker_status IN ('SMOKER','NON_SMOKER','UNKNOWN')),
    ADD CONSTRAINT party_id_type_valid
        CHECK (id_type IS NULL OR id_type IN ('NATIONAL_ID','PASSPORT','DRIVING_LICENCE','VOTER_ID')),
    ADD CONSTRAINT party_nationality_iso2
        CHECK (nationality IS NULL OR nationality ~ '^[A-Z]{2}$'),
    -- Both or neither. A number with no type cannot be read; a type with no number is
    -- noise.
    ADD CONSTRAINT party_identity_document_complete
        CHECK ((id_type IS NULL AND id_number IS NULL)
            OR (id_type IS NOT NULL AND id_number IS NOT NULL));

-- One identity document registers one person per tenant.
--
-- This mirrors ux_party_corporate_regno, and it matters more than that one does: a KYC
-- register in which the same national ID can be registered twice cannot do the job it
-- exists for. Partial, so the (many) rows with no document recorded do not collide with
-- each other.
CREATE UNIQUE INDEX ux_party_individual_identity
    ON party.party (tenant_id, id_type, id_number)
    WHERE id_number IS NOT NULL;

-- Underwriting and pricing read sex + smoker status together with the date of birth;
-- this keeps a quote's party lookup off a sequential scan once the register grows.
CREATE INDEX idx_party_rating_facts
    ON party.party (tenant_id, date_of_birth, sex, smoker_status)
    WHERE party_type = 'INDIVIDUAL';
