-- =============================================================================
-- V1__schema.sql  --  CardDemo relational schema (Flyway versioned migration V1)
-- =============================================================================
--
-- PURPOSE
--   Authoritative DDL that creates the entire CardDemo relational schema on
--   PostgreSQL 16, replacing the legacy VSAM KSDS data store. Each of the 11
--   application tables is derived construct-by-construct from a COBOL copybook
--   record layout (legacy/cpy/*.cpy); every field name, length, and semantic is
--   preserved. The three VSAM alternate indexes are formalized as B-tree
--   indexes (see the LISTCAT reference legacy/catlg/LISTCAT.txt).
--
-- OWNERSHIP / EXECUTION MODEL
--   * This file is OWNED BY FLYWAY. It runs automatically at application startup
--     (spring.flyway.locations=classpath:db/migration, enabled=true,
--     baseline-on-migrate=true, validate-on-migrate=true) and against a fresh
--     Testcontainers PostgreSQL 16 during integration tests.
--   * Hibernate NEVER generates DDL: application.yml pins
--     spring.jpa.hibernate.ddl-auto=validate, so Hibernate only VALIDATES the
--     com.aws.carddemo.domain JPA entities against the tables created here.
--     The column names and types in THIS file are therefore the canonical
--     contract that every entity must match via @Table / @Column.
--   * FLYWAY IMMUTABILITY: once released this file is never edited in place.
--     Plain CREATE TABLE / CREATE INDEX are used (no IF NOT EXISTS) because
--     Flyway guarantees single, ordered execution and tracks applied state in
--     flyway_schema_history; fresh databases and Testcontainers start empty.
--
-- HARD RULES (non-negotiable -- AAP 0.8.1 / 0.9)
--   1. MONETARY / DECIMAL fields use DECIMAL(x,2) ONLY. Floating types
--      (float, double, real, money) are FORBIDDEN -- they would break bit-exact
--      parity with the Java BigDecimal (scale 2) Money value object.
--   2. NO hardcoded credentials or secrets anywhere in this script (no
--      CREATE ROLE ... PASSWORD, no connection strings). The datasource resolves
--      from environment variables in application.yml.
--   3. This script authors ONLY the 11 application tables (+ their PK/FK
--      constraints, indexes, and @Version columns). Spring Batch metadata tables
--      (BATCH_JOB_INSTANCE, BATCH_JOB_EXECUTION, BATCH_STEP_EXECUTION, ...) are
--      DELIBERATELY NOT created here -- Spring Batch auto-creates them because
--      application.yml sets spring.batch.jdbc.initialize-schema=always.
--   4. Identifiers are lowercase snake_case and are NEVER quoted (PostgreSQL
--      folds unquoted identifiers to lowercase).
--
-- COBOL PIC -> PostgreSQL TYPE MAPPING (AAP 0.4.3)
--   PIC 9(n) numeric identifier ......... BIGINT (uniform for all id columns so
--                                         FK column types match exactly)
--   PIC 9(nn) small numeric code ........ INTEGER (e.g. cat_cd 9(04))
--   PIC X(n) text ....................... VARCHAR(n) (exact copybook length)
--   PIC 9(n) leading-zero string ........ VARCHAR(n) (SSN, CVV -- sensitive)
--   PIC S9(i)V99 packed/zoned monetary .. DECIMAL(i+2, 2)
--   PIC X(10) date-as-text .............. VARCHAR(10) (legacy stores dates as
--                                         text, may hold spaces/placeholders;
--                                         NOT converted to DATE to keep parity)
--   PIC X(26) timestamp-as-text ......... VARCHAR(26) (YYYY-MM-DD-HH.MM.SS.ffffff)
--   FILLER .............................. padding only; never a column
--
-- TABLE -> SOURCE COPYBOOK PROVENANCE
--   customer .............. legacy/cpy/CVCUS01Y.cpy  (CUSTOMER-RECORD,   500B)
--   account ............... legacy/cpy/CVACT01Y.cpy  (ACCOUNT-RECORD,    300B)
--   card .................. legacy/cpy/CVACT02Y.cpy  (CARD-RECORD,       150B)
--   card_xref ............. legacy/cpy/CVACT03Y.cpy  (CARD-XREF-RECORD,   50B)
--   transaction ........... legacy/cpy/CVTRA05Y.cpy  (TRAN-RECORD,       350B)
--   daily_transaction ..... legacy/cpy/CVTRA06Y.cpy  (DALYTRAN-RECORD,   350B)
--   user_security ......... legacy/cpy/CSUSR01Y.cpy  (SEC-USER-DATA,      80B)
--   transaction_type ...... legacy/cpy/CVTRA03Y.cpy  (TRAN-TYPE-RECORD)
--   transaction_category .. legacy/cpy/CVTRA04Y.cpy  (TRAN-CAT-RECORD)
--   disclosure_group ...... legacy/cpy/CVTRA02Y.cpy  (DIS-GROUP-RECORD)
--   tran_cat_balance ...... legacy/cpy/CVTRA01Y.cpy  (TRAN-CAT-BAL-RECORD)
--
-- Tables are created parent-before-child so that inline FOREIGN KEY constraints
-- always reference an already-existing table.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. customer  <- legacy/cpy/CVCUS01Y.cpy (CUSTOMER-RECORD, RECLN 500)
--    Root of the customer relationship graph. cust_ssn / cust_govt_issued_id /
--    cust_dob are SENSITIVE (see COMMENTs at end of file).
-- -----------------------------------------------------------------------------
CREATE TABLE customer (
    cust_id                    BIGINT       NOT NULL,   -- CUST-ID                 PIC 9(09)
    cust_first_name            VARCHAR(25),              -- CUST-FIRST-NAME         PIC X(25)
    cust_middle_name           VARCHAR(25),              -- CUST-MIDDLE-NAME        PIC X(25)
    cust_last_name             VARCHAR(25),              -- CUST-LAST-NAME          PIC X(25)
    cust_addr_line_1           VARCHAR(50),              -- CUST-ADDR-LINE-1        PIC X(50)
    cust_addr_line_2           VARCHAR(50),              -- CUST-ADDR-LINE-2        PIC X(50)
    cust_addr_line_3           VARCHAR(50),              -- CUST-ADDR-LINE-3        PIC X(50)
    cust_addr_state_cd         VARCHAR(2),               -- CUST-ADDR-STATE-CD      PIC X(02)
    cust_addr_country_cd       VARCHAR(3),               -- CUST-ADDR-COUNTRY-CD    PIC X(03)
    cust_addr_zip              VARCHAR(10),              -- CUST-ADDR-ZIP           PIC X(10)
    cust_phone_num_1           VARCHAR(15),              -- CUST-PHONE-NUM-1        PIC X(15)
    cust_phone_num_2           VARCHAR(15),              -- CUST-PHONE-NUM-2        PIC X(15)
    cust_ssn                   VARCHAR(9),               -- CUST-SSN                PIC 9(09)  SENSITIVE (leading zeros preserved)
    cust_govt_issued_id        VARCHAR(20),              -- CUST-GOVT-ISSUED-ID     PIC X(20)  SENSITIVE
    cust_dob                   VARCHAR(10),              -- CUST-DOB-YYYY-MM-DD     PIC X(10)  SENSITIVE (date-as-text)
    cust_eft_account_id        VARCHAR(10),              -- CUST-EFT-ACCOUNT-ID     PIC X(10)
    cust_pri_card_holder_ind   VARCHAR(1),               -- CUST-PRI-CARD-HOLDER-IND PIC X(01)
    cust_fico_credit_score     INTEGER,                  -- CUST-FICO-CREDIT-SCORE  PIC 9(03)
    CONSTRAINT pk_customer PRIMARY KEY (cust_id)
);


-- -----------------------------------------------------------------------------
-- 2. transaction_type  <- legacy/cpy/CVTRA03Y.cpy (TRAN-TYPE-RECORD)
--    Reference table. Parent of transaction_category and transaction.
-- -----------------------------------------------------------------------------
CREATE TABLE transaction_type (
    type_cd     VARCHAR(2)    NOT NULL,   -- TRAN-TYPE       PIC X(02)
    type_desc   VARCHAR(50),              -- TRAN-TYPE-DESC  PIC X(50)
    CONSTRAINT pk_transaction_type PRIMARY KEY (type_cd)
);


-- -----------------------------------------------------------------------------
-- 3. transaction_category  <- legacy/cpy/CVTRA04Y.cpy (TRAN-CAT-RECORD)
--    Compound-key reference table (TRAN-CAT-KEY = type_cd + cat_cd).
-- -----------------------------------------------------------------------------
CREATE TABLE transaction_category (
    type_cd        VARCHAR(2)   NOT NULL,   -- TRAN-TYPE-CD        PIC X(02)  (composite PK part 1)
    cat_cd         INTEGER      NOT NULL,   -- TRAN-CAT-CD         PIC 9(04)  (composite PK part 2)
    cat_type_desc  VARCHAR(50),             -- TRAN-CAT-TYPE-DESC  PIC X(50)
    CONSTRAINT pk_transaction_category PRIMARY KEY (type_cd, cat_cd),
    CONSTRAINT fk_transaction_category_type FOREIGN KEY (type_cd)
        REFERENCES transaction_type (type_cd)
);


-- -----------------------------------------------------------------------------
-- 4. disclosure_group  <- legacy/cpy/CVTRA02Y.cpy (DIS-GROUP-RECORD)
--    Compound-key reference table (DIS-GROUP-KEY = group_id + type_cd + cat_cd).
--    int_rate is the ANNUAL interest rate (%) used by the interest-calc batch;
--    stored as DECIMAL(6,2) to preserve S9(04)V99 exactness.
-- -----------------------------------------------------------------------------
CREATE TABLE disclosure_group (
    group_id   VARCHAR(10)     NOT NULL,   -- DIS-ACCT-GROUP-ID  PIC X(10)     (composite PK part 1)
    type_cd    VARCHAR(2)      NOT NULL,   -- DIS-TRAN-TYPE-CD   PIC X(02)     (composite PK part 2)
    cat_cd     INTEGER         NOT NULL,   -- DIS-TRAN-CAT-CD    PIC 9(04)     (composite PK part 3)
    int_rate   DECIMAL(6,2),               -- DIS-INT-RATE       PIC S9(04)V99 (monetary/decimal)
    CONSTRAINT pk_disclosure_group PRIMARY KEY (group_id, type_cd, cat_cd)
);


-- -----------------------------------------------------------------------------
-- 5. account  <- legacy/cpy/CVACT01Y.cpy (ACCOUNT-RECORD, RECLN 300)
--    Five monetary columns are DECIMAL(12,2) from PIC S9(10)V99. group_id is
--    NOT a DB foreign key (see the account.group_id note at the end of file).
--    version supports JPA optimistic locking (READ-UPDATE-REWRITE parity).
-- -----------------------------------------------------------------------------
CREATE TABLE account (
    acct_id                BIGINT           NOT NULL,        -- ACCT-ID                PIC 9(11)
    acct_active_status     VARCHAR(1),                       -- ACCT-ACTIVE-STATUS     PIC X(01)
    curr_bal               DECIMAL(12,2)    DEFAULT 0.00,    -- ACCT-CURR-BAL          PIC S9(10)V99 (monetary)
    credit_limit           DECIMAL(12,2)    DEFAULT 0.00,    -- ACCT-CREDIT-LIMIT      PIC S9(10)V99 (monetary)
    cash_credit_limit      DECIMAL(12,2)    DEFAULT 0.00,    -- ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99 (monetary)
    acct_open_date         VARCHAR(10),                      -- ACCT-OPEN-DATE         PIC X(10)     (date-as-text)
    acct_expiration_date   VARCHAR(10),                      -- ACCT-EXPIRAION-DATE    PIC X(10)     (date-as-text; copybook misspells "EXPIRAION")
    acct_reissue_date      VARCHAR(10),                      -- ACCT-REISSUE-DATE      PIC X(10)     (date-as-text)
    curr_cyc_credit        DECIMAL(12,2)    DEFAULT 0.00,    -- ACCT-CURR-CYC-CREDIT   PIC S9(10)V99 (monetary)
    curr_cyc_debit         DECIMAL(12,2)    DEFAULT 0.00,    -- ACCT-CURR-CYC-DEBIT    PIC S9(10)V99 (monetary)
    acct_addr_zip          VARCHAR(10),                      -- ACCT-ADDR-ZIP          PIC X(10)
    group_id               VARCHAR(10),                      -- ACCT-GROUP-ID          PIC X(10)     (app-level RI; see note)
    version                BIGINT           NOT NULL DEFAULT 0,  -- JPA @Version optimistic lock
    CONSTRAINT pk_account PRIMARY KEY (acct_id)
);


-- -----------------------------------------------------------------------------
-- 6. card  <- legacy/cpy/CVACT02Y.cpy (CARD-RECORD, RECLN 150)
--    card_num (PAN) is the primary key. acct_id is a real FK to account and is
--    indexed (formalizes CARDDATA.VSAM.AIX). cvv is SENSITIVE (never logged).
--    version supports JPA optimistic locking.
-- -----------------------------------------------------------------------------
CREATE TABLE card (
    card_num               VARCHAR(16)   NOT NULL,          -- CARD-NUM             PIC X(16)
    acct_id                BIGINT        NOT NULL,          -- CARD-ACCT-ID         PIC 9(11)  (FK -> account; indexed)
    cvv                    VARCHAR(3),                      -- CARD-CVV-CD          PIC 9(03)  SENSITIVE (leading zeros preserved)
    card_embossed_name     VARCHAR(50),                     -- CARD-EMBOSSED-NAME   PIC X(50)
    card_expiration_date   VARCHAR(10),                     -- CARD-EXPIRAION-DATE  PIC X(10)  (date-as-text)
    card_active_status     VARCHAR(1),                      -- CARD-ACTIVE-STATUS   PIC X(01)
    version                BIGINT        NOT NULL DEFAULT 0,-- JPA @Version optimistic lock
    CONSTRAINT pk_card PRIMARY KEY (card_num),
    CONSTRAINT fk_card_account FOREIGN KEY (acct_id)
        REFERENCES account (acct_id)
);


-- -----------------------------------------------------------------------------
-- 7. card_xref  <- legacy/cpy/CVACT03Y.cpy (CARD-XREF-RECORD, RECLN 50)
--    Cross-reference joining a card to its customer and account. acct_id is
--    indexed (formalizes CARDXREF.VSAM.AIX -> "cross-reference to account").
-- -----------------------------------------------------------------------------
CREATE TABLE card_xref (
    xref_card_num   VARCHAR(16)   NOT NULL,   -- XREF-CARD-NUM  PIC X(16)
    cust_id         BIGINT        NOT NULL,   -- XREF-CUST-ID   PIC 9(09)  (FK -> customer)
    acct_id         BIGINT        NOT NULL,   -- XREF-ACCT-ID   PIC 9(11)  (FK -> account; indexed)
    CONSTRAINT pk_card_xref PRIMARY KEY (xref_card_num),
    CONSTRAINT fk_card_xref_customer FOREIGN KEY (cust_id)
        REFERENCES customer (cust_id),
    CONSTRAINT fk_card_xref_account FOREIGN KEY (acct_id)
        REFERENCES account (acct_id)
);


-- -----------------------------------------------------------------------------
-- 8. transaction  <- legacy/cpy/CVTRA05Y.cpy (TRAN-RECORD, RECLN 350)
--    Posted transactions. tran_amt is DECIMAL(11,2) from PIC S9(09)V99.
--    orig_ts is indexed (formalizes TRANSACT.VSAM.AIX -> chronological browse).
--    NOTE: type_cd participates in TWO foreign keys -- one to transaction_type
--    and one (with cat_cd) to transaction_category. This is valid.
--    "transaction" is a NON-RESERVED keyword in PostgreSQL and is a legal
--    unquoted table name.
-- -----------------------------------------------------------------------------
CREATE TABLE transaction (
    tran_id              VARCHAR(16)    NOT NULL,   -- TRAN-ID             PIC X(16)
    type_cd              VARCHAR(2)     NOT NULL,   -- TRAN-TYPE-CD        PIC X(02)     (FK -> transaction_type; composite FK part 1)
    cat_cd               INTEGER        NOT NULL,   -- TRAN-CAT-CD         PIC 9(04)     (composite FK part 2)
    tran_source          VARCHAR(10),               -- TRAN-SOURCE         PIC X(10)
    tran_desc            VARCHAR(100),              -- TRAN-DESC           PIC X(100)
    tran_amt             DECIMAL(11,2),             -- TRAN-AMT            PIC S9(09)V99 (monetary)
    tran_merchant_id     BIGINT,                    -- TRAN-MERCHANT-ID    PIC 9(09)
    tran_merchant_name   VARCHAR(50),               -- TRAN-MERCHANT-NAME  PIC X(50)
    tran_merchant_city   VARCHAR(50),               -- TRAN-MERCHANT-CITY  PIC X(50)
    tran_merchant_zip    VARCHAR(10),               -- TRAN-MERCHANT-ZIP   PIC X(10)
    card_num             VARCHAR(16)    NOT NULL,   -- TRAN-CARD-NUM       PIC X(16)     (FK -> card)
    orig_ts              VARCHAR(26),               -- TRAN-ORIG-TS        PIC X(26)     (timestamp-as-text; indexed)
    proc_ts              VARCHAR(26),               -- TRAN-PROC-TS        PIC X(26)     (timestamp-as-text)
    CONSTRAINT pk_transaction PRIMARY KEY (tran_id),
    CONSTRAINT fk_transaction_card FOREIGN KEY (card_num)
        REFERENCES card (card_num),
    CONSTRAINT fk_transaction_type FOREIGN KEY (type_cd)
        REFERENCES transaction_type (type_cd),
    CONSTRAINT fk_transaction_category FOREIGN KEY (type_cd, cat_cd)
        REFERENCES transaction_category (type_cd, cat_cd)
);


-- -----------------------------------------------------------------------------
-- 9. tran_cat_balance  <- legacy/cpy/CVTRA01Y.cpy (TRAN-CAT-BAL-RECORD)
--    Running per-account/type/category balance mutated by posting + interest
--    calc (READ-UPDATE-REWRITE). Compound PK (acct_id, type_cd, cat_cd).
--    bal is DECIMAL(11,2) from PIC S9(09)V99. version supports optimistic lock.
-- -----------------------------------------------------------------------------
CREATE TABLE tran_cat_balance (
    acct_id   BIGINT          NOT NULL,          -- TRANCAT-ACCT-ID  PIC 9(11)     (composite PK part 1)
    type_cd   VARCHAR(2)      NOT NULL,          -- TRANCAT-TYPE-CD  PIC X(02)     (composite PK part 2)
    cat_cd    INTEGER         NOT NULL,          -- TRANCAT-CD       PIC 9(04)     (composite PK part 3)
    bal       DECIMAL(11,2)   DEFAULT 0.00,      -- TRAN-CAT-BAL     PIC S9(09)V99 (monetary)
    version   BIGINT          NOT NULL DEFAULT 0,-- JPA @Version optimistic lock
    CONSTRAINT pk_tran_cat_balance PRIMARY KEY (acct_id, type_cd, cat_cd),
    CONSTRAINT fk_tcb_account FOREIGN KEY (acct_id)
        REFERENCES account (acct_id),
    CONSTRAINT fk_tcb_category FOREIGN KEY (type_cd, cat_cd)
        REFERENCES transaction_category (type_cd, cat_cd)
);


-- -----------------------------------------------------------------------------
-- 10. user_security  <- legacy/cpy/CSUSR01Y.cpy (SEC-USER-DATA, RECLN 80)
--     Application authentication store. sec_usr_type is the role: 'A'=Admin,
--     'U'=User. sec_usr_pwd is WIDENED from the legacy 8-char plaintext to
--     VARCHAR(255) to hold a BCrypt hash (documented security-hardening
--     improvement); it is SENSITIVE and must never be logged.
--     version supports JPA optimistic locking.
-- -----------------------------------------------------------------------------
CREATE TABLE user_security (
    sec_usr_id      VARCHAR(8)    NOT NULL,          -- SEC-USR-ID     PIC X(08)
    sec_usr_fname   VARCHAR(20),                     -- SEC-USR-FNAME  PIC X(20)
    sec_usr_lname   VARCHAR(20),                     -- SEC-USR-LNAME  PIC X(20)
    sec_usr_pwd     VARCHAR(255),                    -- SEC-USR-PWD    PIC X(08) WIDENED -> BCrypt hash; SENSITIVE
    sec_usr_type    VARCHAR(1),                      -- SEC-USR-TYPE   PIC X(01) ('A'=Admin / 'U'=User)
    version         BIGINT        NOT NULL DEFAULT 0,-- JPA @Version optimistic lock
    CONSTRAINT pk_user_security PRIMARY KEY (sec_usr_id)
);


-- -----------------------------------------------------------------------------
-- 11. daily_transaction  <- legacy/cpy/CVTRA06Y.cpy (DALYTRAN-RECORD, RECLN 350)
--     Posting-input STAGING table (mirrors the transaction layout). It has NO
--     foreign keys on purpose: staging rows may arrive before their parent
--     card/account/category rows are validated, which is exactly the batch
--     reject flow in CBTRN02C (reject codes 100/101/102/103). A surrogate
--     identity primary key lets the same business id (dalytran_id) be re-staged
--     across runs; the DailyTransaction entity declares
--     @GeneratedValue(strategy = IDENTITY) on this key.
-- -----------------------------------------------------------------------------
CREATE TABLE daily_transaction (
    id              BIGINT         GENERATED ALWAYS AS IDENTITY,  -- surrogate staging key
    dalytran_id     VARCHAR(16)    NOT NULL,   -- DALYTRAN-ID            PIC X(16)     (business id)
    type_cd         VARCHAR(2),                -- DALYTRAN-TYPE-CD       PIC X(02)
    cat_cd          INTEGER,                   -- DALYTRAN-CAT-CD        PIC 9(04)
    tran_source     VARCHAR(10),               -- DALYTRAN-SOURCE        PIC X(10)
    tran_desc       VARCHAR(100),              -- DALYTRAN-DESC          PIC X(100)
    tran_amt        DECIMAL(11,2),             -- DALYTRAN-AMT           PIC S9(09)V99 (monetary)
    merchant_id     BIGINT,                    -- DALYTRAN-MERCHANT-ID   PIC 9(09)
    merchant_name   VARCHAR(50),               -- DALYTRAN-MERCHANT-NAME PIC X(50)
    merchant_city   VARCHAR(50),               -- DALYTRAN-MERCHANT-CITY PIC X(50)
    merchant_zip    VARCHAR(10),               -- DALYTRAN-MERCHANT-ZIP  PIC X(10)
    card_num        VARCHAR(16),               -- DALYTRAN-CARD-NUM      PIC X(16)
    orig_ts         VARCHAR(26),               -- DALYTRAN-ORIG-TS       PIC X(26)     (timestamp-as-text)
    proc_ts         VARCHAR(26),               -- DALYTRAN-PROC-TS       PIC X(26)     (timestamp-as-text)
    CONSTRAINT pk_daily_transaction PRIMARY KEY (id)
);



-- =============================================================================
-- INDEXES
--   The first three formalize the VSAM alternate indexes (AAP 0.4.3), preserving
--   the browse patterns they supported. PostgreSQL auto-creates indexes for
--   primary keys and unique constraints but NOT for foreign-key columns, so the
--   FK-column indexes below are explicit and non-redundant.
-- =============================================================================

-- CARDDATA.VSAM.AIX (LISTCAT: KEYLEN=11, RKP=5, AXRKP=16 over CARD-ACCT-ID).
-- Supports "list all cards for an account".
CREATE INDEX idx_card_acct_id ON card (acct_id);

-- CARDXREF.VSAM.AIX (LISTCAT: AXRKP=25 over XREF-ACCT-ID).
-- Supports "cross-reference -> account".
CREATE INDEX idx_card_xref_acct_id ON card_xref (acct_id);

-- TRANSACT.VSAM.AIX over TRAN-ORIG-TS.
-- Supports chronological transaction retrieval / paging.
CREATE INDEX idx_transaction_orig_ts ON transaction (orig_ts);

-- Supporting index for account.group_id (see the app-level RI note below):
-- there is no DB FK on group_id, so this B-tree index backs disclosure-group
-- family lookups performed in application logic.
CREATE INDEX idx_account_group_id ON account (group_id);

-- Supporting index for staging lookups by business id during posting.
CREATE INDEX idx_daily_transaction_dalytran_id ON daily_transaction (dalytran_id);


-- =============================================================================
-- account.group_id -- INTENTIONAL ABSENCE OF A DATABASE FOREIGN KEY
--   AAP 0.4.3 lists a conceptual relationship "group_id -> disclosure_group",
--   but disclosure_group has a COMPOUND primary key (group_id, type_cd, cat_cd)
--   and group_id alone is NOT unique there. PostgreSQL cannot create a
--   single-column FK that references only the leading column of a compound key,
--   so a literal FOREIGN KEY (group_id) REFERENCES disclosure_group(group_id)
--   is impossible and would fail this migration. The relationship is therefore
--   preserved in APPLICATION LOGIC -- exactly as the COBOL original enforced
--   referential integrity in program code rather than in VSAM -- and backed by
--   idx_account_group_id above. This deviation is recorded in docs/decision-log.md.
--   (All OTHER foreign keys in this schema ARE real DB constraints and are a
--   documented improvement over the COBOL application-enforced RI.)
-- =============================================================================


-- =============================================================================
-- DOCUMENTATION COMMENTS
--   Table comments record copybook provenance; column comments flag the SENSITIVE
--   fields (never log or return in full) and the account.group_id nuance. These
--   support the traceability-matrix and decision-log (Explainability) rules.
-- =============================================================================

COMMENT ON TABLE customer             IS 'Customer master. Source copybook: legacy/cpy/CVCUS01Y.cpy (CUSTOMER-RECORD, 500B).';
COMMENT ON TABLE transaction_type     IS 'Reference: transaction types. Source copybook: legacy/cpy/CVTRA03Y.cpy (TRAN-TYPE-RECORD).';
COMMENT ON TABLE transaction_category IS 'Reference: transaction categories (compound key). Source copybook: legacy/cpy/CVTRA04Y.cpy (TRAN-CAT-RECORD).';
COMMENT ON TABLE disclosure_group     IS 'Reference: disclosure groups / interest rates (compound key). Source copybook: legacy/cpy/CVTRA02Y.cpy (DIS-GROUP-RECORD).';
COMMENT ON TABLE account              IS 'Account master. Source copybook: legacy/cpy/CVACT01Y.cpy (ACCOUNT-RECORD, 300B).';
COMMENT ON TABLE card                 IS 'Card master. Source copybook: legacy/cpy/CVACT02Y.cpy (CARD-RECORD, 150B).';
COMMENT ON TABLE card_xref            IS 'Card-to-customer/account cross-reference. Source copybook: legacy/cpy/CVACT03Y.cpy (CARD-XREF-RECORD, 50B).';
COMMENT ON TABLE transaction          IS 'Posted transactions. Source copybook: legacy/cpy/CVTRA05Y.cpy (TRAN-RECORD, 350B).';
COMMENT ON TABLE tran_cat_balance     IS 'Running per-account/type/category balance (compound key). Source copybook: legacy/cpy/CVTRA01Y.cpy (TRAN-CAT-BAL-RECORD).';
COMMENT ON TABLE user_security        IS 'Application authentication store. Source copybook: legacy/cpy/CSUSR01Y.cpy (SEC-USER-DATA, 80B).';
COMMENT ON TABLE daily_transaction    IS 'Posting-input staging (no FKs; surrogate identity PK). Source copybook: legacy/cpy/CVTRA06Y.cpy (DALYTRAN-RECORD, 350B).';

COMMENT ON COLUMN customer.cust_ssn            IS 'SENSITIVE: SSN (PIC 9(09) stored as text to preserve leading zeros). Never log.';
COMMENT ON COLUMN customer.cust_govt_issued_id IS 'SENSITIVE: government-issued id. Never log.';
COMMENT ON COLUMN customer.cust_dob            IS 'SENSITIVE: date of birth (YYYY-MM-DD as text). Never log.';
COMMENT ON COLUMN card.cvv                     IS 'SENSITIVE: card verification value (PIC 9(03) stored as text to preserve leading zeros). Never log or return in full.';
COMMENT ON COLUMN user_security.sec_usr_pwd    IS 'SENSITIVE: password hash (BCrypt). Widened from legacy PIC X(08) plaintext. Never log.';
COMMENT ON COLUMN account.group_id             IS 'Disclosure-group family key (leading column of disclosure_group compound PK). Referential integrity enforced in application logic as in the COBOL original -- intentionally NOT a DB foreign key; backed by idx_account_group_id. See docs/decision-log.md.';

