-- Card service schema, applied once by Flyway from classpath:db/migration. Flyway
-- creates the schema named by spring.flyway.schemas in
-- src/main/resources/application.yml, and every object below is unqualified.
-- V2__seed.sql loads the rows.
--
-- Source-to-target field mapping, omitted fields included:
--   card-platform/docs/traceability-matrix.md
-- Design decisions:
--   card-platform/docs/decision-log.md
-- Flagged source behaviour:
--   card-platform/docs/business-rule-flags.md


-- card: the 150-byte card record. Fields from app/cpy/CVACT02Y.cpy:L5-L10, and the
-- trailing FILLER PIC X(59) at L11 has no column here. Primary key from KEYS(16 0)
-- at app/jcl/CARDFILE.jcl:L54, sixteen bytes at offset zero, alongside
-- RECORDSIZE(150 150) at L55.
CREATE TABLE card (
    card_number             CHAR(16)      NOT NULL,
    account_id              NUMERIC(11,0) NOT NULL,
    -- CARD-CVV-CD PIC 9(03) holds the card verification value. The column keeps it,
    -- and no event, log line or response payload carries it.
    card_verification_value NUMERIC(3,0)  NOT NULL,
    embossed_name           CHAR(50)      NOT NULL,
    -- CARD-EXPIRAION-DATE PIC X(10), spelling corrected in the column name. The ten
    -- characters read YYYY-MM-DD, and app/cbl/COCRDUPC.cbl:L117-L121 splits them into
    -- a four-character year, a two-character month and a two-character day.
    expiration_date         DATE          NOT NULL,
    -- app/jcl/POSTTRAN.jcl allocates no card dataset, and posting reads no status.
    active_status           CHAR(1)       NOT NULL,
    CONSTRAINT pk_card PRIMARY KEY (card_number)
);

-- The alternate index at app/jcl/CARDFILE.jcl:L85-L87 carries KEYS(11 16) and
-- NONUNIQUEKEY: eleven bytes at offset 16, where the account identifier starts, and a
-- repeated value is allowed.
CREATE INDEX idx_card_account_id ON card (account_id);


-- card_xref: card-to-account cross-reference, a replica private to this service.
-- Fields from app/cpy/CVACT03Y.cpy:L5-L7, and the trailing FILLER PIC X(14) at L8 has
-- no column here. Primary key from KEYS(16 0) at app/jcl/XREFFILE.jcl:L43, alongside
-- RECORDSIZE(50 50) at L44.
CREATE TABLE card_xref (
    card_number CHAR(16)      NOT NULL,
    customer_id NUMERIC(9,0)  NOT NULL,
    account_id  NUMERIC(11,0) NOT NULL,
    CONSTRAINT pk_card_xref PRIMARY KEY (card_number)
);

-- The alternate index at app/jcl/XREFFILE.jcl:L74-L75 carries KEYS(11,25) and
-- NONUNIQUEKEY: eleven bytes at offset 25, where the account identifier starts.
CREATE INDEX idx_card_xref_account_id ON card_xref (account_id);


-- outbox_event: one row per event, written in the same local transaction as the card
-- update. app/csd/CARDDEMO.CSD:L7 and :L9 set JOURNAL(NO) and RECOVERY(NONE), and all
-- eight of its file definitions carry both. app/cbl/CORPT00C.cbl:L517 writes the one
-- asynchronous handoff in the source, a transient data queue record. The relay
-- publishes a row, then sets published.
CREATE TABLE outbox_event (
    event_id     UUID          NOT NULL,
    event_type   VARCHAR(50)   NOT NULL,
    -- Account identifier, eleven characters, and the Kafka message key. Text keeps a
    -- leading zero.
    aggregate_id VARCHAR(11)   NOT NULL,
    -- One event payload as JavaScript Object Notation (JSON) text.
    payload      VARCHAR(4000) NOT NULL,
    published    BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_outbox_event PRIMARY KEY (event_id)
);

-- The relay reads unpublished rows in arrival order.
CREATE INDEX idx_outbox_event_unpublished ON outbox_event (published, created_at);


-- processed_event: one row per event identifier a consumer has already handled. The
-- primary key is the only access path.
CREATE TABLE processed_event (
    event_id     UUID                        NOT NULL,
    processed_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_processed_event PRIMARY KEY (event_id)
);
