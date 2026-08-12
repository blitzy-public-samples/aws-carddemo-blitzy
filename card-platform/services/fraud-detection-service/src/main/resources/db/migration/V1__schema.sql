-- Fraud detection service, migration V1. Flyway applies the Data Definition
-- Language (DDL) below inside the schema named by spring.flyway.schemas in
-- src/main/resources/application.yml, and every object name here stays
-- unqualified.
--
-- No COBOL (Common Business Oriented Language) program in app/cbl/ scores risk.
-- Four tables follow: two carry columns derived from copybook Picture clauses,
-- two are additive infrastructure.

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
    -- True when the score reaches the configured threshold; net new; no COBOL ancestor.
    flagged         BOOLEAN                     NOT NULL,
    -- Rule names in evaluation order as a JSON array, [] when none contributed;
    -- net new; no COBOL ancestor. A JSON array carries a comma inside a quoted
    -- string and never between two values, so an identifier holding one
    -- survives a round trip. All three names span 49 of the 64 characters.
    triggered_rules VARCHAR(64)                 NOT NULL,
    -- Assessment time; net new; no COBOL ancestor.
    assessed_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_fraud_assessment PRIMARY KEY (transaction_id),
    -- Bounds of riskScore in libs/event-contracts fraud-flagged-v1.json.
    CONSTRAINT ck_fraud_assessment_risk_score CHECK (risk_score BETWEEN 0 AND 100),
    -- The account identifier keeps leading zeros, so CHAR(11) holds digits only.
    CONSTRAINT ck_fraud_assessment_account_id CHECK (account_id ~ '^[0-9]{11}$'),
    -- A JSON array of the identifiers the enum of fraud-flagged-v1.json permits,
    -- and nothing else. [] is the cleared verdict.
    CONSTRAINT ck_fraud_assessment_triggered_rules CHECK (
        triggered_rules ~ ('^\[("(VELOCITY|AMOUNT_ANOMALY|MERCHANT_CATEGORY)"'
                        || '(,"(VELOCITY|AMOUNT_ANOMALY|MERCHANT_CATEGORY)")*)?\]$')),
    -- A cleared row may retain rules whose combined score stayed below the configured threshold,
    -- but a flagged row must name at least one rule that contributed to that score.
    CONSTRAINT ck_fraud_assessment_verdict CHECK (
        flagged = FALSE OR triggered_rules <> '[]'),
    -- The regular expression above constrains each value but cannot stop one known value from
    -- appearing twice. Count the distinct known members and require that count to equal the array
    -- length, preserving the one-rule-one-contribution contract.
    CONSTRAINT ck_fraud_assessment_unique_rules CHECK (
        jsonb_array_length(triggered_rules::jsonb) =
            (CASE WHEN triggered_rules::jsonb @> '["VELOCITY"]'::jsonb THEN 1 ELSE 0 END)
          + (CASE WHEN triggered_rules::jsonb @> '["AMOUNT_ANOMALY"]'::jsonb
                  THEN 1 ELSE 0 END)
          + (CASE WHEN triggered_rules::jsonb @> '["MERCHANT_CATEGORY"]'::jsonb
                  THEN 1 ELSE 0 END))
);
-- The verdict is threshold-based. A rule may contribute points without the summed score reaching
-- that threshold, so a cleared row may still name contributing rules.

-- Non-unique index on the account identifier. One account holds many rows.
CREATE INDEX ix_fraud_assessment_account ON fraud_assessment (account_id);

-- The read of api/FraudAssessmentController: one account's assessments, newest first, at
-- most one page of them. Leading column account_id selects the account and trailing column
-- assessed_at DESC supports the order, so the planner can read the page from the index rather
-- than sorting the account's whole history.
CREATE INDEX ix_fraud_assessment_account_assessed_at
    ON fraud_assessment (account_id, assessed_at DESC);

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
    -- only. The fraud window stores absolute magnitude, so a refund raises rather than lowers the
    -- total. This is net-new fraud behaviour and does not change ledger arithmetic.
    total_amount        NUMERIC(11,2)               NOT NULL,
    -- Time of the last change to the row; net new; no COBOL ancestor.
    updated_at          TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    -- The composite key is the only access path, by point lookup or by range
    -- scan on the leading column.
    CONSTRAINT pk_velocity_window PRIMARY KEY (account_id, window_start),
    CONSTRAINT ck_velocity_window_total_nonnegative CHECK (total_amount >= 0)
);

-- outbox_event: No COBOL ancestor. One row per event this service publishes.
-- payload is bounded at 8192 octets, the ceiling
-- libs/event-contracts/src/main/java/com/carddemo/events/serde/EventWireBounds.java applies on
-- the wire, so the stored bound and the published bound are one bound. The seven relay-state
-- columns are ADDITIVE. A skip-locked row lock keeps two relay instances from publishing one
-- row concurrently, and the state columns hold bounded retry and recovery metadata.
CREATE TABLE outbox_event (
    -- Idempotency key. The same value travels in the EventEnvelope.eventId field of
    -- the payload, and every consumer records it in its own processed_event marker.
    event_id     UUID                        NOT NULL,
    -- FraudFlagged and FraudCleared are the two values written.
    event_type   VARCHAR(50)                 NOT NULL,
    -- Account identifier, eleven characters, and the Kafka message key. Fixed-width
    -- character storage keeps a leading zero, which XREF-ACCT-ID PIC 9(11) at
    -- app/cpy/CVACT03Y.cpy:L7 carries in 50 of the 50 rows of
    -- app/data/ASCII/cardxref.txt.
    aggregate_id CHAR(11)                    NOT NULL,
    -- One event payload as JavaScript Object Notation (JSON) text, envelope fields
    -- and payload fields at the same level. ck_outbox_event_payload_bytes below holds
    -- it to the same 8192-octet ceiling the wire applies, so the stored bound and the
    -- published bound are one bound.
    payload      TEXT                        NOT NULL,
    published    BOOLEAN                     NOT NULL DEFAULT FALSE,
    -- Arrival order, and the leading column the relay orders its batch on.
    created_at   TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    -- PENDING, CLAIMED, PUBLISHED or ABANDONED. Terminal states are the last two.
    relay_state     VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    -- How many publish attempts this row has taken. The relay abandons a row at its own
    -- ceiling rather than at a constraint, so exceeding the ceiling is a decision and not
    -- a failed statement.
    attempt_count   INTEGER     NOT NULL DEFAULT 0,
    -- When the relay may next attempt this row. A new row is due as soon as it is written.
    next_attempt_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    last_attempt_at TIMESTAMP(6) WITH TIME ZONE,
    -- A short, redacted reason. Never the payload and never an event value: the column is
    -- bounded so that a stack trace cannot be stored here by accident.
    last_error      VARCHAR(500),
    -- Which relay instance holds the claim, and since when. Both or neither.
    claimed_by      VARCHAR(64),
    claimed_at      TIMESTAMP(6) WITH TIME ZONE,
    published_at TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT pk_outbox_event PRIMARY KEY (event_id),
    CONSTRAINT ck_outbox_event_payload_bytes CHECK (octet_length(payload) <= 8192),
    -- The flag and the timestamp move together. A row is either unpublished with no
    -- timestamp or published with one, and no third state reaches the table.
    CONSTRAINT ck_outbox_event_publication CHECK (
        (published = FALSE AND published_at IS NULL)
        OR (published = TRUE AND published_at IS NOT NULL)),
    CONSTRAINT ck_outbox_event_relay_state
        CHECK (relay_state IN ('PENDING', 'CLAIMED', 'PUBLISHED', 'ABANDONED')),
    CONSTRAINT ck_outbox_event_attempt_count CHECK (attempt_count >= 0),
    -- A claim has both halves or neither, so a half-written claim cannot strand a row.
    CONSTRAINT ck_outbox_event_claim_pairing
        CHECK ((claimed_by IS NULL) = (claimed_at IS NULL)),
    -- The boolean and the state cannot drift apart, whichever one a reader trusts.
    CONSTRAINT ck_outbox_event_published_agrees
        CHECK (published = (relay_state = 'PUBLISHED')),
    CONSTRAINT ck_outbox_event_published_at
        CHECK ((published_at IS NOT NULL) = (relay_state = 'PUBLISHED'))
);

-- Non-unique index. The relay reads unpublished rows in arrival order.
-- Partial index over pending rows alone. The relay claims only unpublished rows, so a
-- published row carries no index entry and the index stays the size of the backlog
-- rather than the size of the table. The two key columns are the relay's ordering:
-- created_at first, then event_id to break a tie, so two relay instances agree on
-- which row comes next.
CREATE INDEX ix_outbox_event_pending
    ON outbox_event (created_at, event_id) WHERE published = FALSE;
-- Relay-state diagnostics and future retry recovery use this order. The active claim query uses
-- the pending partial index above and a pessimistic write lock with skip-locked semantics.
CREATE INDEX ix_outbox_event_claimable ON outbox_event (relay_state, next_attempt_at);

-- Retention. A published row has done its work and stays only for diagnosis. This index
-- serves the purge that deletes published rows past the retention horizon.
CREATE INDEX ix_outbox_event_published_at
    ON outbox_event (published_at) WHERE published = TRUE;
-- processed_event: ADDITIVE. One row per delivery a consumer has already handled.
-- TransactionAuthorizedConsumer is the one writer. The primary key is the guard as well as the
-- key: an insert that collides is how a consumer learns the delivery was already handled, so the
-- guard cannot be checked and then raced past. The key declared here is the event identifier
-- alone; V4__processed_event_topic_key.sql widens it to (event_id, consumed_topic). The consumer
-- inserts this row in the same local transaction as its side effects and acknowledges the message
-- only after that transaction commits, so a crash between the two leaves no half-processed event.
CREATE TABLE processed_event (
    event_id     UUID                        NOT NULL,
    processed_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    -- Which topic the delivery arrived on. NULL for a marker written before this column
    -- existed; every marker written since carries one.
    consumed_topic VARCHAR(128),
    CONSTRAINT pk_processed_event PRIMARY KEY (event_id)
);

-- ============================================================================
-- Retention and erasure
-- ============================================================================
-- No source dataset carries a retention rule. app/csd/CARDDEMO.CSD defines eight files
-- with RECOVERY(NONE) and JOURNAL(NO) and no expiry, the Job Control Language members
-- under app/jcl/ define datasets without an EXPDT or RETPD parameter, and no migrated
-- financial posting path deletes a record. app/cbl/COUSR03C.cbl:307 executes EXEC CICS
-- DELETE against the security file, which the AAP places out of scope; every other DELETE
-- in the repository is IDCAMS removing a whole dataset before it is redefined. A table that
-- only ever grows is a table whose
-- oldest row is as exposed as its newest, so this platform states a rule for every table
-- it owns.
--
-- Each COMMENT below reads as four fields followed by a sentence, so an operator can
-- read the policy out of the catalogue rather than out of a document:
--   retention=<window>      how long a row may stay, or the word relationship for a
--                           business record whose life is the customer relationship
--   purge_key=<column>      the column a purge job ranges over, or 'none'
--   personal_data=<pseudonymous|no> whether the row can be linked to a person
-- Read them back with:
--   SELECT relname, obj_description(oid, 'pg_class') FROM pg_class
--    WHERE relkind = 'r' ORDER BY relname;
--
-- The windows below are the demo baseline this platform ships with. No requirement in
-- scope fixes a legal retention period, so a deployment replaces them with the periods
-- its own jurisdiction requires. domain/RetentionSweep is what applies them: it ranges over
-- the purge_key column named below on the interval carddemo.retention.sweep-interval-ms sets,
-- in a transaction per batch, so no one statement locks a whole table. A window stated here
-- and applied nowhere would leave a reader taking these tables for bounded when they were not.
-- Where a purge_key reads 'none' the row's life is the customer relationship and no sweep
-- reaches it, so erasure there is an operator action.

-- The range a purge job scans.
CREATE INDEX ix_fraud_assessment_assessed_at ON fraud_assessment (assessed_at);

-- The range a purge job scans.
CREATE INDEX ix_velocity_window_start ON velocity_window (window_start);

-- The range a purge job scans.
CREATE INDEX ix_processed_event_processed_at ON processed_event (processed_at);

COMMENT ON TABLE fraud_assessment IS
    'retention=90 days; purge_key=assessed_at; personal_data=pseudonymous. One risk verdict
     per transaction. account_id and transaction_id can resolve to a named customer through
     the owning services, so this row is not anonymous. Purge rows whose assessed_at is older
     than 90 days.';

COMMENT ON TABLE velocity_window IS
    'retention=7 days; purge_key=window_start; personal_data=pseudonymous. Rolling
     per-account spending magnitude and authorization count. account_id can resolve to a named
     customer, so purge rows whose window_start is older than 7 days.';

COMMENT ON TABLE outbox_event IS
    'retention=7 days after published; purge_key=published_at; personal_data=pseudonymous. One
     assessment event awaiting publication, keyed by account and carrying a risk payload.
     Purge rows where published_at is older than 7 days.';

COMMENT ON TABLE processed_event IS
    'retention=carddemo.processed-event.marker-retention-hours; purge_key=processed_at;
     personal_data=no. Duplicate-delivery marker.';
