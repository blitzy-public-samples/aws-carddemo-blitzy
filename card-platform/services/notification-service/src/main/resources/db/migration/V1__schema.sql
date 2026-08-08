-- Notification service, migration V1: the Data Definition Language (DDL) for the
-- private schema this service owns.
-- Flyway 12.4.0 applies this file to PostgreSQL 18.4 inside the schema named by
-- spring.flyway.schemas in src/main/resources/application.yml. Flyway creates that
-- schema, and every object name below stays unqualified.
-- Four tables follow: one read model taken from a COBOL (Common Business Oriented
-- Language) copybook, then three additive tables.

-- statement_transaction holds the card-keyed read model. Thirteen columns come from
-- TRNX-RECORD at app/cpy/COSTM01.CPY:L20-L36, and card_token supplies the key prefix.
-- The table carries no account column: the source read model is keyed by card, and an
-- alert is addressed to a cardholder rather than to an account.
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
-- The card half of that key carries a card token, not a card number of either form. The
-- source key holds a full Primary Account Number (PAN); no column in this schema holds one,
-- and no event this service consumes carries one. The card service owns the one table that
-- does, card.card_number. A masked card number cannot stand in for the token: twelve of its sixteen
-- characters are mask characters, so two cards sharing their last four digits mask to one
-- value and would collide on this key. PanMasker.cardToken in
-- card-platform/libs/cobol-compat derives the token instead, one value per card, and the
-- masked form travels beside it as display data that keys nothing.
CREATE TABLE statement_transaction (
    -- Card token: the hexadecimal rendering of an HmacSHA256 code over a labelled,
    -- versioned card number, 64 lower-case characters. It stands in for
    -- TRNX-CARD-NUM PIC X(16) at app/cpy/COSTM01.CPY:L22, which carried a full PAN in the
    -- source, and app/cpy/CVTRA05Y.cpy:L15 places that field at byte 263, the offset the
    -- sort step reads at app/jcl/CREASTMT.JCL:L53.
    -- The derivation is one-way and keyed under a deployment-supplied key, so no row here,
    -- and no response built from one, carries a value a reader could turn back into a card
    -- number, and no reader holding a token can recompute it over the sixteen-digit
    -- card-number space without that key.
    card_token           CHAR(64)      NOT NULL,
    transaction_id       CHAR(16)      NOT NULL,  -- TRNX-ID PIC X(16)
    -- Masked card number: twelve asterisks then the last four digits, sixteen characters
    -- in all, at the width TRNX-CARD-NUM PIC X(16) declares. It is display data alone.
    -- No key, index or ownership rule reads it, because it identifies no single card.
    masked_card_number   CHAR(16)      NOT NULL,
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
    -- history for one card is a range scan over this index: card_token fixes the prefix
    -- and transaction_id orders the entries, which is the order the sort at
    -- app/jcl/CREASTMT.JCL:L53 produces. Every read of this table names a limit, and
    -- NotificationRenderer.MAXIMUM_STATEMENT_ROWS fixes it, so no query returns a whole
    -- card history and no response carries an unbounded array.
    CONSTRAINT pk_statement_transaction PRIMARY KEY (card_token, transaction_id),
    CONSTRAINT ck_statement_transaction_category_digits
        CHECK (category_code ~ '^[0-9]{4}$'),
    CONSTRAINT ck_statement_transaction_merchant_digits
        CHECK (merchant_id ~ '^[0-9]{9}$'),
    -- The key half is a card token. This constraint is what stops a card number of either
    -- form reaching the key column: sixteen digits and twelve asterisks with four digits
    -- both fail 64 lower-case hexadecimal characters.
    CONSTRAINT ck_statement_transaction_card_token
        CHECK (card_token ~ '^[0-9a-f]{64}$'),
    -- The display column carries the masked form and never a full Primary Account Number:
    -- sixteen digits do not match twelve asterisks and four digits.
    CONSTRAINT ck_statement_transaction_masked_card_number
        CHECK (masked_card_number ~ '^\*{12}[0-9]{4}$')
);

-- The primary key serves the history read: card_token equality, then transaction_id
-- ascending, which is the order the endpoint returns. No separate index is needed for
-- that query, and masked_card_number carries none because no query reads it.
--
-- Retention. com.carddemo.notification.domain.RetentionSweep removes a statement row once
-- it passes carddemo.history.statement-retention-days from
-- src/main/resources/application.yml, and this index serves that delete.
-- processing_timestamp is fixed-width text whose lexical order matches chronological
-- order, so a range comparison against a 26-character cutoff is exact. The format is
-- shaped by app/cbl/CBTRN02C.cbl:L173-L174 and :L701, and the sweep renders its cutoff
-- through the same formatter that wrote every stored value.
CREATE INDEX ix_statement_transaction_processing_timestamp
    ON statement_transaction (processing_timestamp);

-- notification_log carries one row per delivery attempt. Additive: no COBOL program
-- records a delivery attempt.
-- card_token names the card the same way statement_transaction does, and
-- masked_card_number carries the same display value and identifies no single card.
-- channel names the rendered format. The application supplies id.
CREATE TABLE notification_log (
    id                 UUID                        NOT NULL,
    card_token         CHAR(64)                    NOT NULL,
    transaction_id     CHAR(16)                    NOT NULL,
    -- Display only, exactly as in statement_transaction.
    masked_card_number CHAR(16)                    NOT NULL,
    channel            VARCHAR(20)                 NOT NULL,
    attempted_at       TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_notification_log PRIMARY KEY (id),
    -- The same two shapes statement_transaction enforces, for the same reason.
    CONSTRAINT ck_notification_log_card_token
        CHECK (card_token ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_notification_log_card_number
        CHECK (masked_card_number ~ '^\*{12}[0-9]{4}$')
);

-- Reads the delivery history of one card, newest attempt first.
CREATE INDEX ix_notification_log_card_token
    ON notification_log (card_token, attempted_at DESC);


-- cardholder_context carries the cardholder fields one alert reports, one row per account.
-- ADDITIVE as a table: 5000-CREATE-STATEMENT at app/cbl/CBSTM03A.CBL:L458-L504 reads the
-- customer record directly, through the copybook app/cbl/CBSTM03A.CBL:L55 copies, because
-- one program held every dataset. This service owns no customer record, so it keeps the
-- ten fields that paragraph reads and nothing else.
-- The account service publishes them on CustomerContextChanged, whose contract is
-- schemas/customer-context-changed-v1.json in the event-contracts library, and
-- messaging/CustomerContextChangedConsumer applies one event to one row.
-- Each column maps one field of app/cpy/CVCUS01Y.cpy, the copy four programs bind to:
--   first_name     L6  CUST-FIRST-NAME PIC X(25)
--   middle_name    L7  CUST-MIDDLE-NAME PIC X(25)
--   last_name      L8  CUST-LAST-NAME PIC X(25)
--   address_line_1 L9  CUST-ADDR-LINE-1 PIC X(50)
--   address_line_2 L10 CUST-ADDR-LINE-2 PIC X(50)
--   address_line_3 L11 CUST-ADDR-LINE-3 PIC X(50)
--   state_code     L12 CUST-ADDR-STATE-CD PIC X(2)
--   country_code   L13 CUST-ADDR-COUNTRY-CD PIC X(3)
--   zip_code       L14 CUST-ADDR-ZIP PIC X(10)
--   fico_score     L22 CUST-FICO-CREDIT-SCORE PIC 9(3)
-- account_id is the key the event travels under, from XREF-ACCT-ID PIC 9(11) at
-- app/cpy/CVACT03Y.cpy:L7.
-- source_occurred_at holds the occurredAt of the event the row was built from. The upsert
-- compares it and applies nothing older, so a redelivered or reordered event cannot move
-- the row backwards. observed_at holds when this service applied the row.
CREATE TABLE cardholder_context (
    account_id         CHAR(11)                    NOT NULL,
    first_name         VARCHAR(25)                 NOT NULL,
    middle_name        VARCHAR(25)                 NOT NULL,
    last_name          VARCHAR(25)                 NOT NULL,
    address_line_1     VARCHAR(50)                 NOT NULL,
    address_line_2     VARCHAR(50)                 NOT NULL,
    address_line_3     VARCHAR(50)                 NOT NULL,
    state_code         VARCHAR(2)                  NOT NULL,
    country_code       VARCHAR(3)                  NOT NULL,
    zip_code           VARCHAR(10)                 NOT NULL,
    fico_score         CHAR(3)                     NOT NULL,
    source_occurred_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    observed_at        TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_cardholder_context PRIMARY KEY (account_id),
    CONSTRAINT ck_cardholder_context_account_digits
        CHECK (account_id ~ '^[0-9]{11}$'),
    -- CUST-FICO-CREDIT-SCORE PIC 9(3) is a display field, held as text at its declared
    -- width so a leading zero survives.
    CONSTRAINT ck_cardholder_context_fico_digits
        CHECK (fico_score ~ '^[0-9]{3}$')
);

-- The order a staleness report reads. RetentionSweep does not reach this table: a row lives
-- as long as the customer relationship, so observed_at serves an erasure request rather than
-- a window.
CREATE INDEX ix_cardholder_context_observed_at
    ON cardholder_context (observed_at);

-- processed_event carries one row per consumed event identifier. Additive: no COBOL
-- program detects a duplicate delivery.
-- A listener writes that row in the same database transaction as the read-model row
-- it guards. ix_processed_event_processed_at below serves the retention scan.
-- The primary key is the guard as well as the key: an insert that collides is how a consumer
-- learns the event was already handled, so the guard cannot be checked and then raced past.
-- The consumer inserts this row in the same local transaction as its side effects and
-- acknowledges the message only after that transaction commits, which is why a crash between
-- the two leaves no half-processed event.
-- The key this migration declares is the event identifier alone.
-- V3__processed_event_topic_key.sql widens it to (event_id, consumed_topic), because four
-- listener groups share this table and the producing services assign identifiers
-- independently.
CREATE TABLE processed_event (
    event_id     UUID                        NOT NULL,
    processed_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    -- Which topic the delivery arrived on. Nullable here; V3 fills every row and pins the
    -- column NOT NULL.
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
-- a whole dataset before it is redefined. Every table this schema owns states a rule.
--
-- Each COMMENT below reads as four fields followed by a sentence, so an operator can
-- read the policy out of the catalogue rather than out of a document:
--   retention=<window>      how long a row may stay, or the word relationship for a
--                           business record whose life is the customer relationship
--   purge_key=<column>      the column a purge job ranges over, or 'none'
--   personal_data=<value>   one of three values, not two:
--                             yes           the row carries a direct identifier of a person
--                                           (a name, an address, a government identifier)
--                             pseudonymous  the row carries no direct identifier, yet it
--                                           describes ONE person and another table in the
--                                           platform re-identifies them from a column here
--                             no            the row describes no person at all
--                           A card token, a masked card number and an account identifier
--                           are pseudonymous, not absent: the card service resolves a token
--                           to a card, and the account service resolves an account
--                           identifier to a named customer.
--   purge_op=<method>       the callable that performs the delete, or 'none'
-- Read them back with:
--   SELECT relname, obj_description(oid, 'pg_class') FROM pg_class
--    WHERE relkind = 'r' ORDER BY relname;
--
-- The windows below are the demo baseline this platform ships with. No requirement in
-- scope fixes a legal retention period, and card-platform/docs/suggested-next-tasks.md
-- carries the task of replacing them with the periods a deployment's jurisdiction
-- requires. com.carddemo.notification.domain.RetentionSweep is what applies them: it
-- ranges over the purge_key column named below, on the interval
-- carddemo.history.sweep-interval-ms sets. Each purge_op takes a row ceiling and the
-- sweep repeats it in a transaction per batch until the table is clear, so no one
-- statement locks a whole table and no backlog outlives the pass that found it. A window
-- stated here and in application.yml but applied nowhere would leave a reader taking
-- these tables for bounded when they were not, which is why the sweep ships with them.

-- The range RetentionSweep scans, and the order its batches take. The bounded delete
-- NotificationLogRepository.deleteRenderedBefore orders by this column before applying
-- its LIMIT, so this index serves both the range and the ordering. Without the ordering
-- successive batches could revisit the same rows and never converge. V5 renames the column
-- to rendered_at and the index with it, because the value was never a delivery attempt.
CREATE INDEX ix_notification_log_attempted_at ON notification_log (attempted_at);

-- The range RetentionSweep scans, and the order its batches take. The bounded delete
-- ProcessedEventRepository.deleteMarkersProcessedBefore orders by processed_at before
-- applying its LIMIT, for the same convergence reason.
CREATE INDEX ix_processed_event_processed_at ON processed_event (processed_at);

COMMENT ON TABLE statement_transaction IS
    'retention=400 days; purge_key=processing_timestamp; personal_data=no;
     purge_op=StatementTransactionRepository.deleteProcessedBefore. Token-keyed read model
     behind a cardholder alert, card number masked wherever it appears. The window is
     carddemo.history.statement-retention-days in src/main/resources/application.yml, 400
     days by default and overridable by STATEMENT_RETENTION_DAYS, and domain/RetentionSweep
     applies it. That setting is the authority: a window restated here as a second number
     would drift from the one the sweep reads, and this comment did drift, naming 24 months
     against a configured 400 days. It mirrors ledger rows this service does not own, so it
     expires on its own clock: processing_timestamp holds 26 characters shaped
     YYYY-MM-DD-HH.MM.SS.hh0000, which orders lexically, so a purge ranges over it as text.';

COMMENT ON TABLE notification_log IS
    'retention=90 days; purge_key=attempted_at; personal_data=pseudonymous;
     purge_op=NotificationLogRepository.deleteRenderedBefore. One delivery attempt, carrying
     the card token, the transaction identifier and the masked card number the alert showed.
     It carries no account identifier: this table names a card, and the account behind that
     card is resolved through the account service. The window is
     carddemo.history.log-retention-days in src/main/resources/application.yml, 90 days by
     default and overridable by NOTIFICATION_LOG_RETENTION_DAYS, and domain/RetentionSweep
     applies it. It exists to explain a delivery, and expires with the question.';

COMMENT ON TABLE cardholder_context IS
    'retention=relationship; purge_key=observed_at; personal_data=yes. Projection of the ten
     cardholder fields one alert reports, owned by the account service and applied here from
     CustomerContextChanged. A row lives as long as the customer relationship, so no window
     expires it: observed_at serves a purge of accounts the account service has erased.';

COMMENT ON TABLE processed_event IS
    'retention=7 days; purge_key=processed_at; personal_data=no. Duplicate-delivery marker.
     The window is carddemo.processed-event.marker-retention-hours in
     src/main/resources/application.yml, 168 hours by default and overridable by
     PROCESSED_EVENT_RETENTION_HOURS, and domain/RetentionSweep applies it. That setting is
     the authority: a window restated here as a second number would drift from the one the
     sweep reads. It has to cover redelivery, so it belongs at or above the retention of
     every topic this service consumes.';
