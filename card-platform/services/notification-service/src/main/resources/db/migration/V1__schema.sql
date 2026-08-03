-- Notification service, migration V1: the Data Definition Language (DDL) for the
-- private schema this service owns.
-- Flyway 12.4.0 applies this file to PostgreSQL 18.4 inside the schema named by
-- spring.flyway.schemas in src/main/resources/application.yml. Flyway creates that
-- schema, and every object name below stays unqualified.
-- Three tables follow: one read model taken from a COBOL (Common Business Oriented
-- Language) copybook, then two additive tables.

-- Field-by-field source mapping and every omitted field:
--   card-platform/docs/traceability-matrix.md
-- Design decisions:
--   card-platform/docs/decision-log.md


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
CREATE TABLE statement_transaction (
    -- card_number holds the masked form: twelve asterisks then the last four digits.
    -- TRNX-CARD-NUM PIC X(16) at app/cpy/COSTM01.CPY:L22 carries a full Primary
    -- Account Number (PAN), and no row here carries one.
    -- app/cpy/CVTRA05Y.cpy:L15 places the same field at byte 263, the offset the sort
    -- step reads at app/jcl/CREASTMT.JCL:L53.
    card_number          CHAR(16)      NOT NULL,
    transaction_id       CHAR(16)      NOT NULL,  -- TRNX-ID PIC X(16)
    type_code            CHAR(2)       NOT NULL,  -- TRNX-TYPE-CD PIC X(02)
    category_code        NUMERIC(4,0)  NOT NULL,  -- TRNX-CAT-CD PIC 9(04)
    source               CHAR(10)      NOT NULL,  -- TRNX-SOURCE PIC X(10)
    description          CHAR(100)     NOT NULL,  -- TRNX-DESC PIC X(100)
    amount               NUMERIC(11,2) NOT NULL,  -- TRNX-AMT PIC S9(09)V99
    merchant_id          NUMERIC(9,0)  NOT NULL,  -- TRNX-MERCHANT-ID PIC 9(09)
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
    CONSTRAINT pk_statement_transaction PRIMARY KEY (card_number, transaction_id)
);


-- notification_log carries one row per delivery attempt. Additive: no COBOL program
-- records a delivery attempt.
-- card_number holds the masked form, and channel names the rendered format.
-- The application supplies id.
CREATE TABLE notification_log (
    id             UUID                        NOT NULL,
    card_number    CHAR(16)                    NOT NULL,
    transaction_id CHAR(16)                    NOT NULL,
    channel        VARCHAR(20)                 NOT NULL,
    attempted_at   TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_notification_log PRIMARY KEY (id)
);


-- processed_event carries one row per consumed event identifier. Additive: no COBOL
-- program detects a duplicate delivery.
-- A listener writes that row in the same database transaction as the read-model row
-- it guards, and the primary key is the only access path.
CREATE TABLE processed_event (
    event_id     UUID                        NOT NULL,
    processed_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_processed_event PRIMARY KEY (event_id)
);
