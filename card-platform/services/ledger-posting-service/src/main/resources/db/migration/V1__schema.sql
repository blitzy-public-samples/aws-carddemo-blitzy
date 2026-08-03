-- Ledger posting service, migration V1.
-- Data Definition Language (DDL) for the private database this service owns.
-- Flyway 12.4.0 applies it to PostgreSQL 18.4 inside the schema named by
-- spring.flyway.schemas. Every object name below stays unqualified.

-- Eight tables carry the posting state. Their fields come from the COBOL (Common
-- Business Oriented Language) copybooks and the batch posting program cited per table.
-- Key widths come from the dataset definitions in the Job Control Language (JCL)
-- members cited beside them, written for the z/OS utility IDCAMS.

-- Rationale for this schema: card-platform/docs/decision-log.md.
-- Flagged source rules: card-platform/docs/business-rule-flags.md.
-- Field-by-field source mapping and every omitted field:
-- card-platform/docs/traceability-matrix.md.


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
-- rejected_transaction_data holds those 350 bytes whole and undecomposed.
-- The DALYREJS dataset at app/jcl/POSTTRAN.jcl:L34-L38 is sequential and carries no
-- KEYS parameter. The application supplies id.
CREATE TABLE rejected_transaction (
    id                        UUID         NOT NULL,
    -- DALYTRAN-ID PIC X(16) at app/cpy/CVTRA06Y.cpy:L5
    transaction_id            VARCHAR(16)  NOT NULL,
    -- WS-VALIDATION-FAIL-REASON PIC 9(04) at app/cbl/CBTRN02C.cbl:L181
    reject_reason_code        VARCHAR(4)   NOT NULL,
    -- WS-VALIDATION-FAIL-REASON-DESC PIC X(76) at app/cbl/CBTRN02C.cbl:L182
    reject_reason_description VARCHAR(76)  NOT NULL,
    -- REJECT-TRAN-DATA PIC X(350) at app/cbl/CBTRN02C.cbl:L177
    rejected_transaction_data CHAR(350)    NOT NULL,
    rejected_at               TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_rejected_transaction PRIMARY KEY (id)
);


-- Transactional outbox. Additive: no COBOL program carries an equivalent record.
-- payload holds one event document, its schema version included.
-- aggregate_id is the account identifier and the Kafka message key.
CREATE TABLE outbox_event (
    event_id     UUID          NOT NULL,
    event_type   VARCHAR(50)   NOT NULL,
    aggregate_id VARCHAR(11)   NOT NULL,
    payload      VARCHAR(4000) NOT NULL,
    published    BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_outbox_event PRIMARY KEY (event_id)
);


-- Processed event marker. Additive: no COBOL program detects a duplicate delivery.
-- One row records one consumed event identifier, written with the side effects of that
-- event. The primary key is the only access path.
CREATE TABLE processed_event (
    event_id     UUID NOT NULL,
    processed_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_processed_event PRIMARY KEY (event_id)
);


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

-- The access path the outbox relay polls for unpublished rows in age order.
CREATE INDEX idx_outbox_event_unpublished
    ON outbox_event (published, created_at);
