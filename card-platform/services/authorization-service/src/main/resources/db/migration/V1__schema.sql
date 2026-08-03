-- Authorization service schema, applied once by Flyway from
-- classpath:db/migration. Flyway creates the schema named by
-- spring.flyway.schemas in src/main/resources/application.yml, and every object
-- below is unqualified. V2__seed.sql loads the rows.
--
-- Source-to-target field mapping, omitted fields included:
--   card-platform/docs/traceability-matrix.md
-- Design decisions:
--   card-platform/docs/decision-log.md
-- Flagged source behaviour:
--   card-platform/docs/business-rule-flags.md


-- card_xref: card-to-account cross-reference. Fields from
-- app/cpy/CVACT03Y.cpy:L5-L7. The trailing FILLER PIC X(14) at L8 has no column
-- here. Primary key from KEYS(16 0) at app/jcl/XREFFILE.jcl:L43, sixteen bytes
-- at offset zero, alongside RECORDSIZE(50 50) at L44.
CREATE TABLE card_xref (
    card_number  VARCHAR(16)   NOT NULL,
    customer_id  NUMERIC(9,0)  NOT NULL,
    account_id   NUMERIC(11,0) NOT NULL,
    PRIMARY KEY (card_number)
);

-- The alternate index at app/jcl/XREFFILE.jcl:L72-L76 carries KEYS(11,25) and
-- NONUNIQUEKEY: eleven bytes at offset 25, where the account identifier starts.
-- app/cbl/COTRN02C.cbl:L208 reads through that path and :L222 reads the primary
-- key.
CREATE INDEX idx_card_xref_account_id ON card_xref (account_id);


-- account_credit_snapshot: projection of the account record, read by the
-- decline rules. Account state-change events keep the rows current. Five of
-- the thirteen fields in app/cpy/CVACT01Y.cpy appear below, the trailing
-- FILLER PIC X(178) at L17 not among them. Primary key from KEYS(11 0) at
-- app/jcl/ACCTFILE.jcl:L40, eleven bytes at offset zero, alongside
-- RECORDSIZE(300 300) at L41.
CREATE TABLE account_credit_snapshot (
    -- ACCT-ID PIC 9(11) at app/cpy/CVACT01Y.cpy:L5
    account_id               NUMERIC(11,0) NOT NULL,
    -- ACCT-CREDIT-LIMIT PIC S9(10)V99 at app/cpy/CVACT01Y.cpy:L8
    credit_limit             NUMERIC(12,2) NOT NULL,
    -- ACCT-EXPIRAION-DATE PIC X(10) at app/cpy/CVACT01Y.cpy:L11, spelling
    -- corrected in the column name. app/cbl/CBTRN02C.cbl:L414 compares the
    -- field character by character against the first ten characters of a
    -- timestamp.
    account_expiration_date  VARCHAR(10)   NOT NULL,
    -- ACCT-CURR-CYC-CREDIT PIC S9(10)V99 at app/cpy/CVACT01Y.cpy:L13
    current_cycle_credit     NUMERIC(12,2) NOT NULL,
    -- ACCT-CURR-CYC-DEBIT PIC S9(10)V99 at app/cpy/CVACT01Y.cpy:L14. Signed and
    -- unconstrained. app/cbl/CBTRN02C.cbl:L551 adds a negative amount to this
    -- accumulator, and 50 of the 300 records in app/data/ASCII/dailytran.txt
    -- carry one.
    current_cycle_debit      NUMERIC(12,2) NOT NULL,
    PRIMARY KEY (account_id)
);


-- outbox_event: one row per event, written in the same local transaction as the
-- authorization decision. The relay publishes a row, then sets published.
CREATE TABLE outbox_event (
    event_id      UUID          NOT NULL,
    -- TransactionAuthorized and TransactionDeclined are the two values written.
    event_type    VARCHAR(50)   NOT NULL,
    -- Account identifier, eleven characters, and the Kafka message key.
    aggregate_id  VARCHAR(11)   NOT NULL,
    -- Event payload in JavaScript Object Notation (JSON).
    payload       VARCHAR(4000) NOT NULL,
    published     BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    PRIMARY KEY (event_id)
);

-- The relay reads unpublished rows in arrival order.
CREATE INDEX idx_outbox_event_unpublished ON outbox_event (published, created_at);


-- processed_event: one row per event identifier a consumer has already handled.
-- The primary key is the only access path.
CREATE TABLE processed_event (
    event_id      UUID                        NOT NULL,
    processed_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    PRIMARY KEY (event_id)
);


-- transaction_id_seq: allocates transaction identifiers. The two online capture
-- paths, app/cbl/COTRN02C.cbl:L444-L449 and app/cbl/COBIL00C.cbl:L212-L217,
-- browse the transaction file backwards from high values and add one. The start
-- value clears every identifier in app/data/ASCII/dailytran.txt, whose 300
-- records reach a maximum of 996722787.
CREATE SEQUENCE transaction_id_seq START WITH 1000000000 INCREMENT BY 1 NO CYCLE;
