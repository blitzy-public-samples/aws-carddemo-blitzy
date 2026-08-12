-- Authorization service schema, applied once by Flyway from
-- classpath:db/migration. Flyway creates the schema named by
-- spring.flyway.schemas in src/main/resources/application.yml, and every object
-- below is unqualified. V2__seed.sql loads the rows.

-- card_xref: card-to-account cross-reference. Fields from
-- app/cpy/CVACT03Y.cpy:L5-L7. The trailing FILLER PIC X(14) at L8 has no column
-- here. Primary key from KEYS(16 0) at app/jcl/XREFFILE.jcl:L43, sixteen bytes
-- at offset zero, alongside RECORDSIZE(50 50) at L44.
-- customer_id and account_id hold fixed-width digit strings, not numbers. XREF-CUST-ID
-- PIC 9(09) at app/cpy/CVACT03Y.cpy:L6 and XREF-ACCT-ID PIC 9(11) at :L7 are display
-- fields carrying their full declared width, leading zeros included. A numeric column
-- drops those zeros, and the value then stops round-tripping to the eleven characters the
-- alternate-index key occupies at KEYS(11,25) in app/jcl/XREFFILE.jcl:L74. The CHECK
-- constraints hold the width and the digit class each Picture clause declares.
-- The three freshness columns have no COBOL ancestor, because the source has no replica:
-- app/cbl/CBTRN02C.cbl:L382 reads the cross-reference dataset itself and cannot be stale.
-- source_event_id and source_occurred_at name the event that last wrote the row, so an
-- out-of-order delivery changes nothing, and observed_at is what a freshness check reads.
-- messaging/CardUpdatedConsumer refreshes observed_at on the rows of the account a
-- CardUpdated event names; it moves no mapping, because that event carries a masked card
-- number and this table is keyed by all sixteen characters. A row loaded by V2__seed.sql
-- and never touched by that listener holds NULL in both source_ columns.
CREATE TABLE card_xref (
    card_number  VARCHAR(16) NOT NULL,
    customer_id  CHAR(9)     NOT NULL,
    account_id   CHAR(11)    NOT NULL,
    -- The event that last wrote this row. NULL for a row loaded by V2__seed.sql, which is
    -- the initial load rather than an event.
    source_event_id    UUID,
    source_occurred_at TIMESTAMP(6) WITH TIME ZONE,
    -- When this row was last written, by seed or by event. Never NULL, so a freshness check
    -- always has a value to compare. The default exists for V2__seed.sql alone: the seed is
    -- the initial observation, so the moment the migration ran is the truthful answer, and
    -- every later writer sets the column explicitly.
    observed_at        TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (card_number),
    CONSTRAINT ck_card_xref_customer_id_digits
        CHECK (customer_id ~ '^[0-9]{9}$'),
    CONSTRAINT ck_card_xref_account_id_digits
        CHECK (account_id ~ '^[0-9]{11}$'),
    -- An event-written row carries both halves of its provenance or neither.
    CONSTRAINT ck_card_xref_source_pairing
        CHECK ((source_event_id IS NULL) = (source_occurred_at IS NULL))
);

-- A staleness sweep and the freshness check both read observed_at.
CREATE INDEX ix_card_xref_observed_at ON card_xref (observed_at);

-- The alternate index at app/jcl/XREFFILE.jcl:L72-L76 carries KEYS(11,25) and
-- NONUNIQUEKEY: eleven bytes at offset 25, where the account identifier starts.
-- app/cbl/COTRN02C.cbl:L208 reads through that path and :L222 reads the primary
-- key.
--
-- card_number is the second key column. Every read through this path filters on account_id and
-- takes the cross-references of that account in card-number order, which is the order
-- app/cbl/COCRDLIC.cbl:L1247 browses in. Both columns in the key answer that read from the index
-- alone, and the leading column still answers a lookup by account by itself.
CREATE INDEX idx_card_xref_account_id ON card_xref (account_id, card_number);

-- account_credit_snapshot: projection of the account record, read by the
-- decline rules. messaging/AccountStateChangedConsumer keeps the rows current from
-- account.state-changed, replacing every value column of the row the event names, and
-- V2__seed.sql loads the position each row starts from. Five of
-- the thirteen fields in app/cpy/CVACT01Y.cpy appear below, the trailing
-- FILLER PIC X(178) at L17 not among them. Primary key from KEYS(11 0) at
-- app/jcl/ACCTFILE.jcl:L40, eleven bytes at offset zero, alongside
-- RECORDSIZE(300 300) at L41.
CREATE TABLE account_credit_snapshot (
    -- ACCT-ID PIC 9(11) at app/cpy/CVACT01Y.cpy:L5, an eleven-character display field.
    -- Held as text for the same reason card_xref.account_id is: a numeric column drops
    -- the leading zeros that all 50 records of app/data/ASCII/acctdata.txt carry, and
    -- the value then no longer matches the eleven-character key at KEYS(11 0) in
    -- app/jcl/ACCTFILE.jcl:L40 or the aggregate_id this service publishes.
    account_id               CHAR(11)      NOT NULL,
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
    -- The three freshness columns have no COBOL ancestor. The source reads the
    -- account dataset directly at app/cbl/CBTRN02C.cbl:L396, so it has nothing to go stale.
    -- This table is a copy, and the credit-limit rule at app/cbl/CBTRN02C.cbl:L403-L407
    -- authorizes against its cycle accumulators, so a copy that has stopped being updated
    -- authorizes against a position that no longer exists. Recording when the row was last
    -- written is what makes refusing it possible.
    source_event_id          UUID,
    source_occurred_at       TIMESTAMP(6) WITH TIME ZONE,
    -- The default exists for V2__seed.sql alone, for the reason card_xref.observed_at gives.
    observed_at              TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (account_id),
    CONSTRAINT ck_account_credit_snapshot_account_id_digits
        CHECK (account_id ~ '^[0-9]{11}$'),
    CONSTRAINT ck_account_credit_snapshot_source_pairing
        CHECK ((source_event_id IS NULL) = (source_occurred_at IS NULL))
);

-- The freshness check and any staleness sweep both read observed_at.
CREATE INDEX ix_account_credit_snapshot_observed_at
    ON account_credit_snapshot (observed_at);


-- outbox_event: one row per event, written in the same local transaction as the
-- authorization decision. The relay claims a row, publishes it, then sets published.
--
-- payload is bounded at 8192 octets, the same ceiling
-- libs/event-contracts/src/main/java/com/carddemo/events/serde/EventWireBounds.java
-- applies on the wire. A document
-- this table would refuse could never have been published, and one the wire gate would
-- refuse can no longer be stored, so the two bounds cannot disagree.
--
-- The seven relay-state columns have no COBOL ancestor. The source runs
-- its three writes at app/cbl/CBTRN02C.cbl:L440-L442 under no condition and follows them
-- with no rollback, and every file definition in app/csd/CARDDEMO.CSD carries
-- RECOVERY(NONE) JOURNAL(NO), so there is nothing here to reproduce. Without a claim, two
-- relay instances read the same unpublished row and publish it twice; without an attempt
-- count and a next-attempt time, one undeliverable row is retried forever and blocks the
-- rows behind it.
CREATE TABLE outbox_event (
    -- Idempotency key. The same value travels in the EventEnvelope.eventId field of
    -- the payload, and every consumer records it in its own processed_event marker.
    event_id     UUID                        NOT NULL,
    -- TransactionAuthorized and TransactionDeclined are the two values written.
    event_type    VARCHAR(50)   NOT NULL,
    -- The Kafka message key, in one of two forms. Almost every event keys on the account
    -- identifier, eleven characters, from XREF-ACCT-ID PIC 9(11) at app/cpy/CVACT03Y.cpy:L7,
    -- which 50 of the 50 rows of app/data/ASCII/cardxref.txt carry with leading zeros. One
    -- contract keys on the sixteen-character transaction identifier, from DALYTRAN-ID PIC X(16)
    -- at app/cpy/CVTRA06Y.cpy:L5: reject code 0100 at app/cbl/CBTRN02C.cbl:L385-L387 follows the
    -- INVALID KEY branch of the cross-reference read at :L383, where no account identifier
    -- exists to key on, and libs/event-contracts declares that key in
    -- schemas/transaction-declined-v2.json. The column is variable-width because it holds two
    -- widths, and ck_outbox_event_aggregate_key below admits no third.
    aggregate_id VARCHAR(16)                 NOT NULL,
    -- One event payload as JavaScript Object Notation (JSON) text, envelope fields
    -- and payload fields at the same level. ck_outbox_event_payload_bytes below holds
    -- it to the same 8192-octet ceiling the wire applies, so the stored bound and the
    -- published bound are one bound.
    payload      TEXT                        NOT NULL,
    published     BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    -- PENDING, CLAIMED, PUBLISHED or ABANDONED. Terminal states are the last two.
    relay_state     VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    -- How many publish attempts this row has taken. The relay abandons a row at its own
    -- ceiling rather than at a constraint, so exceeding the ceiling is a decision here and
    -- not a failed statement.
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
    published_at    TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT pk_outbox_event PRIMARY KEY (event_id),
    CONSTRAINT ck_outbox_event_payload_bytes CHECK (octet_length(payload) <= 8192),
    -- Eleven decimal digits, or sixteen printable characters with no space. The same union
    -- constrains EventEnvelope.aggregateId, so the stored key and the published key are one key.
    CONSTRAINT ck_outbox_event_aggregate_key CHECK (
        aggregate_id ~ '^[0-9]{11}$' OR aggregate_id ~ '^[!-~]{16}$'),
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

-- Partial index over pending rows alone. The relay claims only unpublished rows, so a
-- published row carries no index entry and the index stays the size of the backlog
-- rather than the size of the table. The two key columns are the relay's ordering:
-- created_at first, then event_id to break a tie, so two relay instances agree on
-- which row comes next.
CREATE INDEX ix_outbox_event_pending
    ON outbox_event (created_at, event_id) WHERE published = FALSE;

-- Retention. A published row has done its work and stays only for diagnosis. This index
-- serves the purge that deletes published rows past the retention horizon.
CREATE INDEX ix_outbox_event_published_at
    ON outbox_event (published_at) WHERE published = TRUE;

-- The claim query filters on relay_state and orders by next_attempt_at, so both columns
-- are covered. This index is also the purge path for rows in a terminal state.
CREATE INDEX ix_outbox_event_claimable ON outbox_event (relay_state, next_attempt_at);

-- processed_event: one row per delivery a consumer of this service has already handled.
-- messaging/AccountStateChangedConsumer and messaging/CardUpdatedConsumer both write it.
-- The primary key is the guard as well as the key: an insert that collides is how a consumer
-- learns the delivery was already handled, so the guard cannot be checked and then raced past.
-- The key declared here is the event identifier alone;
-- V6__processed_event_topic_key.sql widens it to (event_id, consumed_topic), because an
-- event identifier is assigned per producing service and stops identifying a delivery once a
-- service reads two topics. The consumer inserts this row in the same local transaction as
-- its side effects, marker after effects, and acknowledges the message only after that
-- transaction commits, so a crash between the two leaves no half-processed event.
CREATE TABLE processed_event (
    event_id      UUID                        NOT NULL,
    processed_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    -- Which topic the delivery arrived on. NULL for a marker written before this column
    -- existed; every marker written since carries one.
    consumed_topic VARCHAR(128),
    PRIMARY KEY (event_id)
);

-- Retention. A marker matters only while a redelivery of its event is still possible.
-- This index serves the purge that deletes markers past the retention horizon.
CREATE INDEX ix_processed_event_processed_at ON processed_event (processed_at);


-- transaction_id_seq: allocates transaction identifiers. The two online capture
-- paths, app/cbl/COTRN02C.cbl:L444-L449 and app/cbl/COBIL00C.cbl:L212-L217,
-- browse the transaction file backwards from high values and add one. The start
-- value clears every identifier in app/data/ASCII/dailytran.txt, whose 300
-- records reach a maximum of 996722787.
CREATE SEQUENCE transaction_id_seq START WITH 1000000000 INCREMENT BY 1 NO CYCLE;


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
--   personal_data=<yes|pseudonymous|no>
--                           whether the row directly identifies a person, can be linked through
--                           a platform identifier, or carries no personal data
-- Read them back with:
--   SELECT relname, obj_description(oid, 'pg_class') FROM pg_class
--    WHERE relkind = 'r' ORDER BY relname;
--
-- The retention windows below are this platform's demo baseline; a deployment replaces
-- them with the periods its jurisdiction requires. domain/RetentionSweep is what applies them,
-- ranging over the purge_key columns named below on the interval
-- carddemo.retention.sweep-interval-ms sets, and the columns and indexes it needs are declared
-- above. Where a purge_key reads 'none' no sweep reaches the table and erasure there is an
-- operator action.

COMMENT ON TABLE card_xref IS
    'retention=relationship; purge_key=none; personal_data=pseudonymous. Card-to-account cross-
     reference, the first hop of every authorization. A row lives as long as the card it
     names, and card, customer and account identifiers link it to a cardholder. No route of
     this platform deletes a card, so erasure is an operator action against this table.';

COMMENT ON TABLE account_credit_snapshot IS
    'retention=relationship; purge_key=none; personal_data=pseudonymous. Credit projection
     the decline rules read. The account identifier and financial values link it to a
     cardholder. A row lives as long as the account, and account state-change events keep
     it current.';

COMMENT ON TABLE outbox_event IS
    'retention=7 days after published; purge_key=published_at; personal_data=pseudonymous.
     One decision event awaiting publication. Its payload carries account or transaction
     identity, a masked card number and financial values. A published row is spent; purge
     rows where published_at is older than 7 days. An unpublished row is work still owed
     and is never purged by age.';

COMMENT ON TABLE processed_event IS
    'retention=carddemo.processed-event.marker-retention-hours; purge_key=processed_at;
     personal_data=no. Duplicate-delivery marker.';
