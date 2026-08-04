-- Notification service, migration V1: the Data Definition Language (DDL) for the
-- private schema this service owns.
-- Flyway 12.4.0 applies this file to PostgreSQL 18.4 inside the schema named by
-- spring.flyway.schemas in src/main/resources/application.yml. Flyway creates that
-- schema, and every object name below stays unqualified.
-- Three tables follow: one read model taken from a COBOL (Common Business Oriented
-- Language) copybook, then two additive tables.

-- statement_transaction holds the card-keyed read model, and every column comes from
-- TRNX-RECORD at app/cpy/COSTM01.CPY:L20-L36.
-- Record width 350 comes from the RECORDSIZE(350 350) parameter at
-- app/jcl/CREASTMT.JCL:L32, a Job Control Language (JCL) member.
-- The composite primary key comes from the KEYS(32 0) parameter at
-- app/jcl/CREASTMT.JCL:L30, a 32-byte key at offset zero spanning the TRNX-KEY group
-- at app/cpy/COSTM01.CPY:L21.
-- That group holds TRNX-CARD-NUM then TRNX-ID, 16 bytes each, and
-- app/jcl/CREASTMT.JCL:L42 names the same key: CREATE COPY OF TRANSACT FILE WITH
-- CARD NUMBER AND TRAN ID AS KEY.
-- The trailing FILLER PIC X(20) at app/cpy/COSTM01.CPY:L36 gets no column.
--
-- The card half of that key carries the masked card number, not a full one. The source
-- key holds a full Primary Account Number (PAN), which this platform never stores or
-- transports: every event carries the masked form alone, and the check constraint below
-- is what stops a full one reaching this table.
CREATE TABLE statement_transaction (
    -- Masked card number: twelve asterisks then the last four digits, sixteen characters
    -- in all. TRNX-CARD-NUM PIC X(16) at app/cpy/COSTM01.CPY:L22 carries a full PAN in
    -- the source, and no row here carries one.
    -- app/cpy/CVTRA05Y.cpy:L15 places the same field at byte 263, the offset the sort
    -- step reads at app/jcl/CREASTMT.JCL:L53.
    card_number          CHAR(16)      NOT NULL,
    transaction_id       CHAR(16)      NOT NULL,  -- TRNX-ID PIC X(16)
    type_code            CHAR(2)       NOT NULL,  -- TRNX-TYPE-CD PIC X(02)
    -- TRNX-CAT-CD PIC 9(04) and TRNX-MERCHANT-ID PIC 9(09) are display fields, not
    -- magnitudes. Both are held as text at their declared widths, matching the ledger
    -- service that publishes them: transaction.category_code and transaction.merchant_id
    -- there are VARCHAR(4) and VARCHAR(9), and the 18 category codes of
    -- app/data/ASCII/trancatg.txt read 0001 through 0016. A numeric column would store
    -- 1 for 0001 and 49936 for 000049936, so a row here would stop comparing equal to
    -- the ledger row it was built from.
    category_code        CHAR(4)       NOT NULL,  -- TRNX-CAT-CD PIC 9(04)
    source               CHAR(10)      NOT NULL,  -- TRNX-SOURCE PIC X(10)
    description          CHAR(100)     NOT NULL,  -- TRNX-DESC PIC X(100)
    amount               NUMERIC(11,2) NOT NULL,  -- TRNX-AMT PIC S9(09)V99
    merchant_id          CHAR(9)       NOT NULL,  -- TRNX-MERCHANT-ID PIC 9(09)
    merchant_name        CHAR(50)      NOT NULL,  -- TRNX-MERCHANT-NAME PIC X(50)
    merchant_city        CHAR(50)      NOT NULL,  -- TRNX-MERCHANT-CITY PIC X(50)
    merchant_zip         CHAR(10)      NOT NULL,  -- TRNX-MERCHANT-ZIP PIC X(10)
    -- Both timestamp columns hold 26 characters of text.
    -- origin_timestamp separates date and time with a space, then carries colons and
    -- six fractional digits.
    -- processing_timestamp separates them with a dash, then carries dots, two
    -- hundredths digits and four zero characters, shaped by
    -- app/cbl/CBTRN02C.cbl:L173-L174 and :L701.
    origin_timestamp     CHAR(26)      NOT NULL,  -- TRNX-ORIG-TS PIC X(26)
    processing_timestamp CHAR(26)      NOT NULL,  -- TRNX-PROC-TS PIC X(26)
    -- The primary key is also the access path of the one read route. A page of the alert
    -- history for one card is a range scan over this index: card_number fixes the prefix
    -- and transaction_id orders the entries, which is the order the sort at
    -- app/jcl/CREASTMT.JCL:L53 produces. NotificationRenderer.MAXIMUM_STATEMENT_ROWS
    -- bounds the rows one response carries, so no query here returns a whole card
    -- history and no response carries an unbounded array.
    CONSTRAINT pk_statement_transaction PRIMARY KEY (card_number, transaction_id),
    CONSTRAINT ck_statement_transaction_category_digits
        CHECK (category_code ~ '^[0-9]{4}$'),
    CONSTRAINT ck_statement_transaction_merchant_digits
        CHECK (merchant_id ~ '^[0-9]{9}$'),
    -- The key half is masked. This constraint is what stops a full Primary Account Number
    -- reaching this table: sixteen digits do not match twelve asterisks and four digits.
    CONSTRAINT ck_statement_transaction_card_number
        CHECK (card_number ~ '^\*{12}[0-9]{4}$')
);

-- The primary key serves the history read: card_number equality, then transaction_id
-- ascending, which is the order the endpoint returns. No separate index is needed for
-- that query.
--
-- Retention. Nothing removes a statement row, so the table grows by one row per consumed
-- event for the life of the service. carddemo.history.statement-retention-days in
-- src/main/resources/application.yml supplies the horizon, and this index serves the
-- delete. processing_timestamp is fixed-width text whose lexical order matches
-- chronological order, so a range comparison against a 26-character cutoff is exact.
-- The format is shaped by app/cbl/CBTRN02C.cbl:L173-L174 and :L701.
CREATE INDEX ix_statement_transaction_processing_timestamp
    ON statement_transaction (processing_timestamp);


-- notification_log carries one row per delivery attempt. Additive: no COBOL program
-- records a delivery attempt.
-- card_number identifies the card the same way statement_transaction does, in the same
-- masked form. channel names the rendered format. The application supplies id.
CREATE TABLE notification_log (
    id                 UUID                        NOT NULL,
    card_number        CHAR(16)                    NOT NULL,
    transaction_id     CHAR(16)                    NOT NULL,
    channel            VARCHAR(20)                 NOT NULL,
    attempted_at       TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_notification_log PRIMARY KEY (id),
    -- The same shape statement_transaction enforces, for the same reason.
    CONSTRAINT ck_notification_log_card_number
        CHECK (card_number ~ '^\*{12}[0-9]{4}$')
);

-- Reads the delivery history of one card, newest attempt first.
CREATE INDEX ix_notification_log_card_number
    ON notification_log (card_number, attempted_at DESC);

-- processed_event carries one row per consumed event identifier. Additive: no COBOL
-- program detects a duplicate delivery.
-- A listener writes that row in the same database transaction as the read-model row
-- it guards, and the primary key is the only access path.
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


-- ============================================================================
-- Retention and erasure
-- ============================================================================
-- No source dataset carries a retention rule. app/csd/CARDDEMO.CSD defines eight files
-- with RECOVERY(NONE) and JOURNAL(NO) and no expiry, the Job Control Language members
-- under app/jcl/ define datasets without an EXPDT or RETPD parameter, and no program
-- under app/cbl/ deletes a record: the only DELETE in the repository is IDCAMS deleting
-- a whole dataset before it is redefined. A table that only ever grows is a table whose
-- oldest row is as exposed as its newest, so this platform states a rule for every table
-- it owns.
--
-- Each COMMENT below reads as four fields followed by a sentence, so an operator can
-- read the policy out of the catalogue rather than out of a document:
--   retention=<window>      how long a row may stay, or the word relationship for a business
--                           record whose life is the customer relationship
--   purge_key=<column>      the column a purge job ranges over, or 'none'
--   personal_data=<yes|no>  whether the row describes an identifiable person
-- Read them back with:
--   SELECT relname, obj_description(oid, 'pg_class') FROM pg_class
--    WHERE relkind = 'r' ORDER BY relname;
--
-- The windows below are the demo baseline this platform ships with. No requirement in
-- scope fixes a legal retention period, and card-platform/docs/suggested-next-tasks.md (planned)
-- carries the task of replacing them with the periods a deployment's jurisdiction
-- requires. The purge job itself is out of scope for the same reason: nothing in the
-- Agent Action Plan schedules one, and a job that deletes financial records is not
-- something to add without an owner. The columns and indexes it needs are here.

-- The range a purge job scans.
CREATE INDEX ix_notification_log_attempted_at ON notification_log (attempted_at);

-- The range a purge job scans.
CREATE INDEX ix_processed_event_processed_at ON processed_event (processed_at);

COMMENT ON TABLE statement_transaction IS
    'retention=24 months; purge_key=processing_timestamp; personal_data=no. Card-keyed read
     model behind a cardholder alert, card number already masked. It mirrors ledger rows this
     service does not own, so it expires on its own clock: processing_timestamp holds 26
     characters shaped YYYY-MM-DD-HH.MM.SS.hh0000, which orders lexically, so a purge ranges
     over it as text.';

COMMENT ON TABLE notification_log IS
    'retention=90 days; purge_key=attempted_at; personal_data=no. One delivery attempt. It
     exists to explain a delivery, and expires with the question: purge rows whose
     attempted_at is older than 90 days.';

COMMENT ON TABLE processed_event IS
    'retention=30 days; purge_key=processed_at; personal_data=no. Duplicate-delivery marker,
     kept longer than broker topic retention.';
