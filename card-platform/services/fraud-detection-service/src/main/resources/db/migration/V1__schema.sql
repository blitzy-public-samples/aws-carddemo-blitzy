-- Fraud detection service, migration V1. Flyway applies the Data Definition
-- Language (DDL) below inside the schema named by spring.flyway.schemas in
-- src/main/resources/application.yml, and every object name here stays
-- unqualified.
--
-- No COBOL (Common Business Oriented Language) program in app/cbl/ scores risk.
-- Four tables follow: two carry columns derived from copybook Picture clauses,
-- two are additive infrastructure.
--
-- Field-by-field source mapping, omitted fields included:
--   card-platform/docs/traceability-matrix.md
-- Design decisions:
--   card-platform/docs/decision-log.md
-- Flagged source behaviour:
--   card-platform/docs/business-rule-flags.md


-- fraud_assessment: one row per assessed transaction.
CREATE TABLE fraud_assessment (
    -- DALYTRAN-ID PIC X(16) at app/cpy/CVTRA06Y.cpy:L5 and TRAN-ID PIC X(16) at
    -- app/cpy/CVTRA05Y.cpy:L5. Alphanumeric, not numeric.
    transaction_id  CHAR(16)                    NOT NULL,
    -- XREF-ACCT-ID PIC 9(11) at app/cpy/CVACT03Y.cpy:L7, width only. Eleven
    -- digits, leading zeros significant.
    account_id      CHAR(11)                    NOT NULL,
    -- Whole number from 0 to 100, no decimal place; net new; no COBOL ancestor.
    risk_score      INTEGER                     NOT NULL,
    -- True when at least one rule triggered; net new; no COBOL ancestor.
    flagged         BOOLEAN                     NOT NULL,
    -- Rule names in evaluation order, comma separated, empty when none
    -- triggered; net new; no COBOL ancestor.
    triggered_rules VARCHAR(64)                 NOT NULL,
    -- Assessment time; net new; no COBOL ancestor.
    assessed_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_fraud_assessment PRIMARY KEY (transaction_id),
    CONSTRAINT ck_fraud_assessment_risk_score CHECK (risk_score BETWEEN 0 AND 100)
);

-- Non-unique index on the account identifier. One account holds many rows.
CREATE INDEX ix_fraud_assessment_account ON fraud_assessment (account_id);


-- velocity_window: one row per account and window, holding the counters the
-- velocity rule reads.
CREATE TABLE velocity_window (
    -- XREF-ACCT-ID PIC 9(11) at app/cpy/CVACT03Y.cpy:L7, width only.
    account_id          CHAR(11)                    NOT NULL,
    -- Window lower bound, and part of the key; net new; no COBOL ancestor.
    window_start        TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    -- Authorizations counted in the window; net new; no COBOL ancestor.
    authorization_count INTEGER                     NOT NULL,
    -- TRAN-AMT PIC S9(09)V99 at app/cpy/CVTRA05Y.cpy:L10, precision and scale
    -- only. Signed: 50 of the 300 DALYTRAN-AMT values in
    -- app/data/ASCII/dailytran.txt are negative.
    total_amount        NUMERIC(11,2)               NOT NULL,
    -- Time of the last change to the row; net new; no COBOL ancestor.
    updated_at          TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    -- The composite key is the only access path, by point lookup or by range
    -- scan on the leading column.
    CONSTRAINT pk_velocity_window PRIMARY KEY (account_id, window_start)
);


-- outbox_event: ADDITIVE. One row per event this service publishes.
CREATE TABLE outbox_event (
    event_id     UUID                        NOT NULL,
    -- FraudFlagged and FraudCleared are the two values written.
    event_type   VARCHAR(32)                 NOT NULL,
    -- Account identifier, eleven characters, and the Kafka message key.
    aggregate_id CHAR(11)                    NOT NULL,
    -- One JavaScript Object Notation (JSON) document, envelope fields and
    -- payload fields at the same level.
    payload      TEXT                        NOT NULL,
    published    BOOLEAN                     NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    -- The one column in this migration that accepts NULL.
    published_at TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT pk_outbox_event PRIMARY KEY (event_id)
);

-- Non-unique index. The relay reads unpublished rows in arrival order.
CREATE INDEX ix_outbox_event_unpublished ON outbox_event (published, created_at);


-- processed_event: ADDITIVE. One row per event identifier a consumer has already
-- handled. The primary key is the only access path.
CREATE TABLE processed_event (
    event_id     UUID                        NOT NULL,
    processed_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_processed_event PRIMARY KEY (event_id)
);
