-- Ledger posting service, migration V1.
-- Data Definition Language (DDL) for the private database this service owns.
-- Flyway 12.4.0 applies it to PostgreSQL 18.4 inside the schema named by
-- spring.flyway.schemas. Every object name below stays unqualified.

-- Eight tables carry the posting state. Their fields come from the COBOL (Common
-- Business Oriented Language) copybooks and the batch posting program cited per table.
-- Key widths come from the dataset definitions in the Job Control Language (JCL)
-- members cited beside them, written for the z/OS utility IDCAMS.

-- Posted transaction, from TRAN-RECORD at app/cpy/CVTRA05Y.cpy:L4-L17, RECLN = 350.
-- Primary key width from KEYS(16 0) at app/jcl/TRANFILE.jcl:L53, record width from
-- RECORDSIZE(350 350) at :L54.
-- Both timestamp columns keep the 26-character layout of their source fields.
-- app/cbl/CBTRN02C.cbl:L173-L174 and :L701 shape processed_timestamp as hundredths
-- of a second followed by four zero characters.
-- The trailing FILLER PIC X(20) at app/cpy/CVTRA05Y.cpy:L18 gets no column.
CREATE TABLE transaction (
    transaction_id      VARCHAR(16)   NOT NULL,  -- TRAN-ID PIC X(16)
    type_code           CHAR(2)       NOT NULL,  -- TRAN-TYPE-CD PIC X(02)
    category_code       VARCHAR(4)    NOT NULL,  -- TRAN-CAT-CD PIC 9(04)
    source              VARCHAR(10)   NOT NULL,  -- TRAN-SOURCE PIC X(10)
    description         VARCHAR(100)  NOT NULL,  -- TRAN-DESC PIC X(100)
    amount              NUMERIC(11,2) NOT NULL,  -- TRAN-AMT PIC S9(09)V99
    merchant_id         VARCHAR(9)    NOT NULL,  -- TRAN-MERCHANT-ID PIC 9(09)
    merchant_name       VARCHAR(50)   NOT NULL,  -- TRAN-MERCHANT-NAME PIC X(50)
    merchant_city       VARCHAR(50)   NOT NULL,  -- TRAN-MERCHANT-CITY PIC X(50)
    merchant_zip        VARCHAR(10)   NOT NULL,  -- TRAN-MERCHANT-ZIP PIC X(10)
    -- Masked form: twelve asterisks and the last four digits. TRAN-CARD-NUM
    -- PIC X(16) at app/cpy/CVTRA05Y.cpy:L15 holds the full Primary Account
    -- Number (PAN), which no row in this table carries.
    card_number         VARCHAR(16)   NOT NULL,
    origin_timestamp    CHAR(26)      NOT NULL,  -- TRAN-ORIG-TS PIC X(26)
    processed_timestamp CHAR(26)      NOT NULL,  -- TRAN-PROC-TS PIC X(26)
    CONSTRAINT pk_transaction PRIMARY KEY (transaction_id)
);

-- Transaction category balance, from TRAN-CAT-BAL-RECORD at app/cpy/CVTRA01Y.cpy:L4-L9,
-- RECLN = 50.
-- The three key columns are the TRAN-CAT-KEY group at app/cpy/CVTRA01Y.cpy:L5-L8, whose
-- 17-byte width matches KEYS(17 0) at app/jcl/TCATBALF.jcl:L40.
-- app/cbl/CBTRN02C.cbl:L467-L542 reads that key, then creates or updates the row.
-- The trailing FILLER PIC X(22) at app/cpy/CVTRA01Y.cpy:L10 gets no column.
CREATE TABLE transaction_category_balance (
    account_id       VARCHAR(11)   NOT NULL,  -- TRANCAT-ACCT-ID PIC 9(11)
    type_code        CHAR(2)       NOT NULL,  -- TRANCAT-TYPE-CD PIC X(02)
    category_code    VARCHAR(4)    NOT NULL,  -- TRANCAT-CD PIC 9(04)
    category_balance NUMERIC(11,2) NOT NULL,  -- TRAN-CAT-BAL PIC S9(09)V99
    CONSTRAINT pk_transaction_category_balance
        PRIMARY KEY (account_id, type_code, category_code)
);

-- Account balance projection: the four ACCOUNT-RECORD fields this service reads and
-- writes, from app/cpy/CVACT01Y.cpy, RECLN 300.
-- Primary key width from KEYS(11 0) at app/jcl/ACCTFILE.jcl:L40, record width from
-- RECORDSIZE(300 300) at :L41.
-- app/cbl/CBTRN02C.cbl:L547-L551 adds a non-negative amount to the cycle credit
-- accumulator and a negative amount to the cycle debit accumulator.
-- The account service owns the whole 300-byte record. Its remaining fields and the
-- trailing FILLER PIC X(178) at app/cpy/CVACT01Y.cpy:L17 get no column here.
CREATE TABLE account_balance_projection (
    account_id      VARCHAR(11)   NOT NULL,  -- ACCT-ID PIC 9(11) at :L5
    current_balance NUMERIC(12,2) NOT NULL,  -- ACCT-CURR-BAL PIC S9(10)V99 at :L7
    cycle_credit    NUMERIC(12,2) NOT NULL,  -- ACCT-CURR-CYC-CREDIT PIC S9(10)V99 at :L13
    cycle_debit     NUMERIC(12,2) NOT NULL,  -- ACCT-CURR-CYC-DEBIT PIC S9(10)V99 at :L14
    CONSTRAINT pk_account_balance_projection PRIMARY KEY (account_id)
);

-- Transaction type lookup, from TRAN-TYPE-RECORD at app/cpy/CVTRA03Y.cpy:L4-L6,
-- RECLN = 60.
-- Primary key width from KEYS(2 0) at app/jcl/TRANTYPE.jcl:L40, record width from
-- RECORDSIZE(60 60) at :L41.
-- V2__seed.sql loads the seven rows. No Jakarta Persistence API (JPA) entity maps
-- this table.
-- The trailing FILLER PIC X(08) at app/cpy/CVTRA03Y.cpy:L7 gets no column.
CREATE TABLE transaction_type (
    type_code        CHAR(2)     NOT NULL,  -- TRAN-TYPE PIC X(02)
    type_description VARCHAR(50) NOT NULL,  -- TRAN-TYPE-DESC PIC X(50)
    CONSTRAINT pk_transaction_type PRIMARY KEY (type_code)
);

-- Transaction category lookup, from TRAN-CAT-RECORD at app/cpy/CVTRA04Y.cpy:L4-L8,
-- RECLN = 60.
-- The two key columns are the TRAN-CAT-KEY group at app/cpy/CVTRA04Y.cpy:L5-L7, whose
-- 6-byte width matches KEYS(6 0) at app/jcl/TRANCATG.jcl:L40. That group name repeats
-- at app/cpy/CVTRA01Y.cpy:L5 over a wider 17-byte key.
-- V2__seed.sql loads the eighteen rows, and no JPA entity maps this table.
-- The trailing FILLER PIC X(04) at app/cpy/CVTRA04Y.cpy:L9 gets no column.
CREATE TABLE transaction_category (
    type_code            CHAR(2)     NOT NULL,  -- TRAN-TYPE-CD PIC X(02)
    category_code        VARCHAR(4)  NOT NULL,  -- TRAN-CAT-CD PIC 9(04)
    category_description VARCHAR(50) NOT NULL,  -- TRAN-CAT-TYPE-DESC PIC X(50)
    CONSTRAINT pk_transaction_category PRIMARY KEY (type_code, category_code)
);

-- Rejected transaction, from REJECT-RECORD at app/cbl/CBTRN02C.cbl:L176-L178: a
-- 350-byte record plus an 80-byte trailer, 430 bytes in all. The same total appears as
-- DCB=(RECFM=F,LRECL=430,BLKSIZE=0) at app/jcl/POSTTRAN.jcl:L36.
-- The DALYREJS dataset at app/jcl/POSTTRAN.jcl:L34-L38 is sequential and carries no
-- KEYS parameter. The application supplies id.
--
-- The table keeps the 350 bytes whole. REJECT-TRAN-DATA PIC X(350) at
-- app/cbl/CBTRN02C.cbl:L177 is a copy of the daily transaction record, and L447 moves
-- the whole arriving record into it without reshaping a field. rejected_transaction_data
-- holds those 350 characters in app/cpy/CVTRA06Y.cpy:L5-L18 field order, trailing spaces
-- included, so a reader slices any field out at its copybook offset. The card number at
-- app/cpy/CVTRA06Y.cpy:L15 arrives masked: authorization publishes the masked form and
-- the unmasked Primary Account Number never enters this service.
-- ck_rejected_transaction_masked_card_number holds that boundary at the column: the
-- sixteen characters at one-based offsets 263 through 278 of the block carry no digit in
-- their first twelve positions.
-- transaction_id, reject_reason_code and reject_reason_description repeat values the
-- block and the trailer already carry, and carry them as queryable columns.
CREATE TABLE rejected_transaction (
    id                        UUID         NOT NULL,
    -- DALYTRAN-ID PIC X(16) at app/cpy/CVTRA06Y.cpy:L5
    transaction_id            VARCHAR(16)  NOT NULL,
    -- WS-VALIDATION-FAIL-REASON PIC 9(04) at app/cbl/CBTRN02C.cbl:L181
    reject_reason_code        VARCHAR(4)   NOT NULL,
    -- WS-VALIDATION-FAIL-REASON-DESC PIC X(76) at app/cbl/CBTRN02C.cbl:L182
    reject_reason_description VARCHAR(76)  NOT NULL,
    -- REJECT-TRAN-DATA PIC X(350) at app/cbl/CBTRN02C.cbl:L177, the arriving
    -- DALYTRAN-RECORD of app/cpy/CVTRA06Y.cpy:L4-L18 verbatim
    rejected_transaction_data CHAR(350)    NOT NULL,
    rejected_at               TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_rejected_transaction PRIMARY KEY (id),
    -- Offsets 263 through 278 of the block are DALYTRAN-CARD-NUM PIC X(16) at
    -- app/cpy/CVTRA06Y.cpy:L15, after the 262 characters of the ten fields ahead of it.
    -- com.carddemo.cobol.PanMasker returns twelve asterisks then the last four digits,
    -- and twelve asterisks then four more for a card number it cannot read. A refusal is
    -- exactly where the second form turns up. Either way the first twelve positions hold
    -- no digit, so no unmasked card number fits the block.
    CONSTRAINT ck_rejected_transaction_masked_card_number
        CHECK (SUBSTRING(rejected_transaction_data FROM 263 FOR 16)
               ~ '^\*{12}([0-9]{4}|\*{4})$')
);

-- Transactional outbox. Additive: no COBOL program carries an equivalent record.
-- payload holds one event document, its schema version included.
-- aggregate_id is the account identifier and the Kafka message key.
-- payload is bounded at 8192 octets, the ceiling
-- libs/event-contracts/.../serde/EventWireBounds.java applies on the wire, so the stored
-- bound and the published bound are one bound. The seven relay-state columns have no
-- COBOL ancestor: without a claim, two relay instances read the same unpublished
-- row and publish it twice, and without an attempt count and a next-attempt time one
-- undeliverable row is retried forever and blocks the rows behind it.
CREATE TABLE outbox_event (
    event_id     UUID          NOT NULL,
    event_type   VARCHAR(50)   NOT NULL,
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
    published    BOOLEAN       NOT NULL DEFAULT FALSE,
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
    published_at    TIMESTAMP(6) WITH TIME ZONE,
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

-- Partial index over pending rows alone. The relay claims only unpublished rows, so a
-- published row carries no index entry and the index stays the size of the backlog
-- rather than the size of the table. The two key columns are the relay's ordering:
-- created_at first, then event_id to break a tie, so two relay instances agree on
-- which row comes next.
CREATE INDEX ix_outbox_event_pending
    ON outbox_event (created_at, event_id) WHERE published = FALSE;

-- Processed event marker. Additive: no COBOL program detects a duplicate delivery.
-- One row records one consumed event identifier, written with the side effects of that
-- event. ix_processed_event_processed_at below serves the retention scan.
-- The primary key is the guard as well as the key: an insert that collides is how a consumer
-- learns the event was already handled, so the guard cannot be checked and then raced past.
-- The consumer inserts this row in the same local transaction as its side effects and
-- acknowledges the message only after that transaction commits, which is why a crash between
-- the two leaves no half-processed event.
CREATE TABLE processed_event (
    event_id     UUID                        NOT NULL,
    processed_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    -- Which topic the delivery arrived on. NULL for a marker written before this column
    -- existed; every marker written since carries one.
    consumed_topic VARCHAR(128),
    CONSTRAINT pk_processed_event PRIMARY KEY (event_id)
);

-- Retention. A marker matters only while a redelivery of its event is still possible.
-- This index serves the purge that deletes markers past the retention horizon.
CREATE INDEX ix_processed_event_processed_at ON processed_event (processed_at);

-- Three secondary indexes follow, and none of them is unique.

-- Matches the Virtual Storage Access Method (VSAM) alternate index defined with
-- KEYS(26 304) and NONUNIQUEKEY at app/jcl/TRANIDX.jcl:L27-L28, repeated at
-- app/jcl/TRANFILE.jcl:L84-L85. Offset 304 is the width of the twelve columns
-- preceding processed_timestamp, and many transactions share one value.
CREATE INDEX idx_transaction_processed_timestamp
    ON transaction (processed_timestamp);

-- One transaction identifier can reach the reject table more than once across
-- redeliveries.
CREATE INDEX idx_rejected_transaction_transaction_id
    ON rejected_transaction (transaction_id);

-- The claim query filters on relay_state and orders by next_attempt_at, so both columns
-- are covered. This index is also the purge path for rows in a terminal state.
CREATE INDEX ix_outbox_event_claimable
    ON outbox_event (relay_state, next_attempt_at);

-- Retention. A published row has done its work and stays only for diagnosis. This index
-- serves the purge that deletes published rows past the retention horizon.
CREATE INDEX ix_outbox_event_published_at
    ON outbox_event (published_at) WHERE published = TRUE;

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
CREATE INDEX ix_rejected_transaction_rejected_at ON rejected_transaction (rejected_at);

COMMENT ON TABLE transaction IS
    'retention=relationship; purge_key=none; personal_data=pseudonymous. Posted transaction,
     including amount, merchant detail, masked card and transaction identifier. Those values
     can resolve to a named customer through the owning services. A financial record is not
     purged on a timer.';

COMMENT ON TABLE transaction_category_balance IS
    'retention=relationship; purge_key=none; personal_data=pseudonymous. Per-account category
     balance. account_id links the financial aggregate to a named customer through the account
     service. Derived from transaction rows and as durable as they are.';

COMMENT ON TABLE account_balance_projection IS
    'retention=relationship; purge_key=none; personal_data=pseudonymous. Per-account balance
     and billing-cycle projection. account_id links it to a named customer.';

COMMENT ON TABLE transaction_type IS
    'retention=reference; purge_key=none; personal_data=no. Seeded lookup from
     app/data/ASCII/trantype.txt, 7 rows. No retention rule applies to reference data.';

COMMENT ON TABLE transaction_category IS
    'retention=reference; purge_key=none; personal_data=no. Seeded lookup from
     app/data/ASCII/trancatg.txt, 18 rows.';

COMMENT ON TABLE rejected_transaction IS
    'retention=90 days; purge_key=rejected_at; personal_data=pseudonymous. Diagnostic record
     of one refusal, carrying the refused 350-character transaction record verbatim with its
     card number masked. Amount, merchant and transaction identifier sit inside that block and
     can be linked to a customer. Purge rows whose rejected_at is older than 90 days.';

COMMENT ON TABLE outbox_event IS
    'retention=7 days after published; purge_key=published_at; personal_data=pseudonymous. One
     posted or declined event awaiting publication, keyed by account and carrying financial
     payload data. Purge rows where published_at is older than 7 days.';

COMMENT ON TABLE processed_event IS
    'retention=carddemo.processed-event.marker-retention-hours; purge_key=processed_at;
     personal_data=no. Duplicate-delivery marker.';
