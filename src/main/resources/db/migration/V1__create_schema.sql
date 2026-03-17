-- ============================================================================
-- V1__create_schema.sql
-- Flyway Migration V1: PostgreSQL 16+ DDL for CardDemo Application
--
-- Translates 10 VSAM KSDS dataset definitions and associated reference data
-- structures from COBOL copybooks into relational PostgreSQL tables with
-- exact field-to-column mapping preserving field names, sizes, and data types.
--
-- Source: AWS CardDemo COBOL/CICS/VSAM mainframe application
-- Target: Java 25 + Spring Boot 3.5.x + PostgreSQL 16+
--
-- Mapping Rules:
--   PIC S9(n)V99 COMP-3  -> NUMERIC(n+2, 2)   (exact decimal scale)
--   PIC X(n)             -> VARCHAR(n)          (exact field width)
--   PIC 9(n) identifiers -> VARCHAR(n)          (preserve leading zeros)
--   FILLER               -> NOT mapped          (padding bytes omitted)
--
-- Table Creation Order (respects FK dependencies):
--   1. accounts            (no FK dependencies)
--   2. customers           (no FK dependencies)
--   3. cards               (FK to accounts)
--   4. card_xrefs          (FK to customers and accounts)
--   5. transactions        (no FK - app-layer validation)
--   6. daily_transactions  (no FK - batch staging)
--   7. user_security       (no FK)
--   8. transaction_type_refs   (no FK - reference data)
--   9. transaction_category_refs (no FK - reference data)
--  10. discount_groups     (no FK - reference data)
--  11. category_balances   (no FK - aggregation data)
-- ============================================================================

-- ============================================================================
-- TABLE 1: accounts
-- Source: CVACT01Y.cpy (ACCOUNT-RECORD), ACCTDATA VSAM KSDS
-- VSAM: KEYLEN=11, RKP=0, RECLN=300
-- ============================================================================
CREATE TABLE IF NOT EXISTS accounts (
    acct_id               VARCHAR(11)   NOT NULL,  -- PIC 9(11) - VSAM primary key
    acct_active_status    VARCHAR(1),              -- PIC X(01)
    acct_curr_bal         NUMERIC(12,2),           -- PIC S9(10)V99 - current balance
    acct_credit_limit     NUMERIC(12,2),           -- PIC S9(10)V99 - credit limit
    acct_cash_credit_limit NUMERIC(12,2),          -- PIC S9(10)V99 - cash credit limit
    acct_open_date        VARCHAR(10),             -- PIC X(10) - date string
    acct_expiration_date  VARCHAR(10),             -- PIC X(10) - COBOL src typo ACCT-EXPIRAION-DATE corrected
    acct_reissue_date     VARCHAR(10),             -- PIC X(10) - reissue date string
    acct_curr_cyc_credit  NUMERIC(12,2),           -- PIC S9(10)V99 - current cycle credit
    acct_curr_cyc_debit   NUMERIC(12,2),           -- PIC S9(10)V99 - current cycle debit
    acct_addr_zip         VARCHAR(10),             -- PIC X(10)
    acct_group_id         VARCHAR(10),             -- PIC X(10) - discount group reference
    version               INTEGER       DEFAULT 0, -- JPA @Version optimistic locking (CICS READ UPDATE -> REWRITE)
    CONSTRAINT pk_accounts PRIMARY KEY (acct_id)
);

COMMENT ON TABLE accounts IS 'Account master - migrated from ACCTDATA VSAM KSDS (CVACT01Y.cpy, RECLN 300)';

-- ============================================================================
-- TABLE 2: customers
-- Source: CVCUS01Y.cpy / CUSTREC.cpy (CUSTOMER-RECORD), CUSTDATA VSAM KSDS
-- VSAM: KEYLEN=9, RKP=0, RECLN=500
-- ============================================================================
CREATE TABLE IF NOT EXISTS customers (
    cust_id                   VARCHAR(9)   NOT NULL,  -- PIC 9(09) - VSAM primary key
    cust_first_name           VARCHAR(25),            -- PIC X(25)
    cust_middle_name          VARCHAR(25),            -- PIC X(25)
    cust_last_name            VARCHAR(25),            -- PIC X(25)
    cust_addr_line_1          VARCHAR(50),            -- PIC X(50)
    cust_addr_line_2          VARCHAR(50),            -- PIC X(50)
    cust_addr_line_3          VARCHAR(50),            -- PIC X(50)
    cust_addr_state_cd        VARCHAR(2),             -- PIC X(02)
    cust_addr_country_cd      VARCHAR(3),             -- PIC X(03)
    cust_addr_zip             VARCHAR(10),            -- PIC X(10)
    cust_phone_num_1          VARCHAR(15),            -- PIC X(15)
    cust_phone_num_2          VARCHAR(15),            -- PIC X(15)
    cust_ssn                  VARCHAR(9),             -- PIC 9(09) - PII: Social Security Number
    cust_govt_issued_id       VARCHAR(20),            -- PIC X(20) - PII: Government-issued ID
    cust_dob_yyyy_mm_dd       VARCHAR(10),            -- PIC X(10) - date of birth
    cust_eft_account_id       VARCHAR(10),            -- PIC X(10) - EFT account reference
    cust_pri_card_holder_ind  VARCHAR(1),             -- PIC X(01) - primary card holder indicator
    cust_fico_credit_score    INTEGER,                -- PIC 9(03) - FICO score 0-999
    CONSTRAINT pk_customers PRIMARY KEY (cust_id)
);

COMMENT ON TABLE customers IS 'Customer master - migrated from CUSTDATA VSAM KSDS (CVCUS01Y.cpy, RECLN 500)';
COMMENT ON COLUMN customers.cust_ssn IS 'PII: Social Security Number - requires secure handling';
COMMENT ON COLUMN customers.cust_govt_issued_id IS 'PII: Government-issued identification - requires secure handling';

-- ============================================================================
-- TABLE 3: cards
-- Source: CVACT02Y.cpy (CARD-RECORD), CARDDATA VSAM KSDS
-- VSAM: KEYLEN=16, RKP=0, RECLN=150
-- AIX: CARD-ACCT-ID (AXRKP=16, KEYLEN=11) - index in V2
-- ============================================================================
CREATE TABLE IF NOT EXISTS cards (
    card_num              VARCHAR(16)  NOT NULL,   -- PIC X(16) - VSAM primary key
    card_acct_id          VARCHAR(11)  NOT NULL,   -- PIC 9(11) - FK to accounts
    card_cvv_cd           VARCHAR(3),              -- PIC 9(03) - PII: Card Verification Value
    card_embossed_name    VARCHAR(50),             -- PIC X(50)
    card_expiration_date  VARCHAR(10),             -- PIC X(10) - COBOL src typo CARD-EXPIRAION-DATE corrected
    card_active_status    VARCHAR(1),              -- PIC X(01)
    version               INTEGER      DEFAULT 0,  -- JPA @Version optimistic locking (CICS READ UPDATE -> REWRITE)
    CONSTRAINT pk_cards PRIMARY KEY (card_num)
);

COMMENT ON TABLE cards IS 'Card master - migrated from CARDDATA VSAM KSDS (CVACT02Y.cpy, RECLN 150)';
COMMENT ON COLUMN cards.card_cvv_cd IS 'PII: Card Verification Value - requires secure handling';

-- ============================================================================
-- TABLE 4: card_xrefs
-- Source: CVACT03Y.cpy (CARD-XREF-RECORD), CARDXREF VSAM KSDS
-- VSAM: KEYLEN=16, RKP=0, RECLN=50
-- AIX: XREF-ACCT-ID (AXRKP=25, KEYLEN=11) - index in V2
-- ============================================================================
CREATE TABLE IF NOT EXISTS card_xrefs (
    xref_card_num         VARCHAR(16)  NOT NULL,   -- PIC X(16) - VSAM primary key
    xref_cust_id          VARCHAR(9)   NOT NULL,   -- PIC 9(09) - FK to customers
    xref_acct_id          VARCHAR(11)  NOT NULL,   -- PIC 9(11) - FK to accounts
    CONSTRAINT pk_card_xrefs PRIMARY KEY (xref_card_num)
);

COMMENT ON TABLE card_xrefs IS 'Card cross-reference junction - migrated from CARDXREF VSAM KSDS (CVACT03Y.cpy, RECLN 50)';

-- ============================================================================
-- TABLE 5: transactions
-- Source: CVTRA05Y.cpy (TRAN-RECORD), TRANSACT VSAM KSDS
-- VSAM: KEYLEN=16, RKP=0, RECLN=350
-- AIX: TRAN-ORIG-TS (AXRKP=304, KEYLEN=26) - index in V2
-- NOTE: No FK constraints - validation performed in application layer
--       (batch reject codes 100-103)
-- ============================================================================
CREATE TABLE IF NOT EXISTS transactions (
    tran_id               VARCHAR(16)  NOT NULL,   -- PIC X(16) - VSAM primary key
    tran_type_cd          VARCHAR(2),              -- PIC X(02) - transaction type code
    tran_cat_cd           INTEGER,                 -- PIC 9(04) - transaction category code
    tran_source           VARCHAR(10),             -- PIC X(10) - transaction source
    tran_desc             VARCHAR(100),            -- PIC X(100) - description
    tran_amt              NUMERIC(11,2),           -- PIC S9(09)V99 - transaction amount
    tran_merchant_id      VARCHAR(9),              -- PIC 9(09) - merchant identifier
    tran_merchant_name    VARCHAR(50),             -- PIC X(50) - merchant name
    tran_merchant_city    VARCHAR(50),             -- PIC X(50) - merchant city
    tran_merchant_zip     VARCHAR(10),             -- PIC X(10) - merchant zip code
    tran_card_num         VARCHAR(16),             -- PIC X(16) - card number reference
    tran_orig_ts          VARCHAR(26),             -- PIC X(26) - origination timestamp ISO-8601
    tran_proc_ts          VARCHAR(26),             -- PIC X(26) - processing timestamp ISO-8601
    CONSTRAINT pk_transactions PRIMARY KEY (tran_id)
);

COMMENT ON TABLE transactions IS 'Transaction master - migrated from TRANSACT VSAM KSDS (CVTRA05Y.cpy, RECLN 350). No FK constraints - app-layer validation via reject codes 100-103';

-- ============================================================================
-- TABLE 6: daily_transactions
-- Source: CVTRA06Y.cpy (DALYTRAN-RECORD), DALYTRAN VSAM Sequential
-- VSAM: Sequential file (not KSDS), RECLN=350
-- Batch staging table with auto-generated sequence PK
-- NOTE: No FK constraints - batch processing performance requirement
-- ============================================================================
CREATE TABLE IF NOT EXISTS daily_transactions (
    id                        BIGSERIAL    NOT NULL,   -- Auto-generated sequence for batch processing
    dalytran_id               VARCHAR(16),             -- PIC X(16) - daily transaction ID
    dalytran_type_cd          VARCHAR(2),              -- PIC X(02) - transaction type code
    dalytran_cat_cd           INTEGER,                 -- PIC 9(04) - transaction category code
    dalytran_source           VARCHAR(10),             -- PIC X(10) - transaction source
    dalytran_desc             VARCHAR(100),            -- PIC X(100) - description
    dalytran_amt              NUMERIC(11,2),           -- PIC S9(09)V99 - transaction amount
    dalytran_merchant_id      VARCHAR(9),              -- PIC 9(09) - merchant identifier
    dalytran_merchant_name    VARCHAR(50),             -- PIC X(50) - merchant name
    dalytran_merchant_city    VARCHAR(50),             -- PIC X(50) - merchant city
    dalytran_merchant_zip     VARCHAR(10),             -- PIC X(10) - merchant zip code
    dalytran_card_num         VARCHAR(16),             -- PIC X(16) - card number reference
    dalytran_orig_ts          VARCHAR(26),             -- PIC X(26) - origination timestamp ISO-8601
    dalytran_proc_ts          VARCHAR(26),             -- PIC X(26) - processing timestamp ISO-8601
    CONSTRAINT pk_daily_transactions PRIMARY KEY (id)
);

COMMENT ON TABLE daily_transactions IS 'Daily transaction staging - migrated from DALYTRAN VSAM Sequential (CVTRA06Y.cpy, RECLN 350). Batch input for posting job';

-- ============================================================================
-- TABLE 7: user_security
-- Source: CSUSR01Y.cpy (SEC-USER-DATA), USRSEC VSAM KSDS
-- VSAM: KEYLEN=8, RKP=0, RECLN=80
-- CRITICAL: sec_usr_pwd expanded from PIC X(08) to VARCHAR(72) for BCrypt hash
-- ============================================================================
CREATE TABLE IF NOT EXISTS user_security (
    sec_usr_id            VARCHAR(8)   NOT NULL,   -- PIC X(08) - VSAM primary key
    sec_usr_fname         VARCHAR(20),             -- PIC X(20) - first name
    sec_usr_lname         VARCHAR(20),             -- PIC X(20) - last name
    sec_usr_pwd           VARCHAR(72)  NOT NULL,   -- PIC X(08) EXPANDED to 72 for BCrypt hash
    sec_usr_type          VARCHAR(1),              -- PIC X(01) - 'A'=Admin, 'U'=User (88-level conditions)
    CONSTRAINT pk_user_security PRIMARY KEY (sec_usr_id)
);

COMMENT ON TABLE user_security IS 'User security - migrated from USRSEC VSAM KSDS (CSUSR01Y.cpy, RECLN 80). Password column expanded for BCrypt hashing';

-- ============================================================================
-- TABLE 8: transaction_type_refs
-- Source: CVTRA03Y.cpy (TRAN-TYPE-RECORD), TRANTYPE reference data
-- Record layout: RECLN=60 (2-byte code + 50-char description + 8-byte filler)
-- Reference data: 7 records (Purchase, Payment, Credit, Authorization,
--                 Refund, Reversal, Adjustment)
-- ============================================================================
CREATE TABLE IF NOT EXISTS transaction_type_refs (
    tran_type             VARCHAR(2)   NOT NULL,   -- PIC X(02) - type code primary key
    tran_type_desc        VARCHAR(50),             -- PIC X(50) - type description
    CONSTRAINT pk_transaction_type_refs PRIMARY KEY (tran_type)
);

COMMENT ON TABLE transaction_type_refs IS 'Transaction type reference - migrated from TRANTYPE (CVTRA03Y.cpy, RECLN 60). 7 reference records';

-- ============================================================================
-- TABLE 9: transaction_category_refs
-- Source: CVTRA04Y.cpy (TRAN-CAT-RECORD), TRANCATG reference data
-- Record layout: RECLN=60, Composite PK from COBOL group-level key TRAN-CAT-KEY
-- Reference data: 18 category records
-- ============================================================================
CREATE TABLE IF NOT EXISTS transaction_category_refs (
    tran_type_cd          VARCHAR(2)   NOT NULL,   -- PIC X(02) - part of composite key
    tran_cat_cd           INTEGER      NOT NULL,   -- PIC 9(04) - part of composite key
    tran_cat_type_desc    VARCHAR(50),             -- PIC X(50) - category description
    CONSTRAINT pk_transaction_category_refs PRIMARY KEY (tran_type_cd, tran_cat_cd)
);

COMMENT ON TABLE transaction_category_refs IS 'Transaction category reference - migrated from TRANCATG (CVTRA04Y.cpy, RECLN 60). 18 category records with composite PK';

-- ============================================================================
-- TABLE 10: discount_groups
-- Source: CVTRA02Y.cpy (DIS-GROUP-RECORD), DISCGRP reference data
-- Record layout: RECLN=50, Composite PK from COBOL group-level key DIS-GROUP-KEY
-- Reference data: 51 records (3 groups x 17 entries)
-- ============================================================================
CREATE TABLE IF NOT EXISTS discount_groups (
    dis_acct_group_id     VARCHAR(10)  NOT NULL,   -- PIC X(10) - account group ID
    dis_tran_type_cd      VARCHAR(2)   NOT NULL,   -- PIC X(02) - transaction type code
    dis_tran_cat_cd       INTEGER      NOT NULL,   -- PIC 9(04) - transaction category code
    dis_int_rate          NUMERIC(6,2),            -- PIC S9(04)V99 - interest rate
    CONSTRAINT pk_discount_groups PRIMARY KEY (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd)
);

COMMENT ON TABLE discount_groups IS 'Discount/disclosure group - migrated from DISCGRP (CVTRA02Y.cpy, RECLN 50). 51 records with composite PK';

-- ============================================================================
-- TABLE 11: category_balances
-- Source: CVTRA01Y.cpy (TRAN-CAT-BAL-RECORD), TCATBALF VSAM
-- Record layout: RECLN=50, Composite PK from COBOL group-level key TRAN-CAT-KEY
-- ============================================================================
CREATE TABLE IF NOT EXISTS category_balances (
    trancat_acct_id       VARCHAR(11)  NOT NULL,   -- PIC 9(11) - account ID
    trancat_type_cd       VARCHAR(2)   NOT NULL,   -- PIC X(02) - transaction type code
    trancat_cd            INTEGER      NOT NULL,   -- PIC 9(04) - transaction category code
    tran_cat_bal          NUMERIC(11,2),           -- PIC S9(09)V99 - category balance
    CONSTRAINT pk_category_balances PRIMARY KEY (trancat_acct_id, trancat_type_cd, trancat_cd)
);

COMMENT ON TABLE category_balances IS 'Transaction category balance - migrated from TCATBALF (CVTRA01Y.cpy, RECLN 50). Composite PK for category-level balance aggregation';

-- ============================================================================
-- FOREIGN KEY CONSTRAINTS
-- Derived from VSAM cross-reference patterns and COBOL COPY usage analysis.
--
-- IMPORTANT: FK constraints are NOT added on transactions or daily_transactions
-- to avoid batch processing performance issues. The COBOL programs perform
-- validation in application code using reject codes 100-103, not via database
-- constraints. This behavior is preserved in the Java migration.
-- ============================================================================

-- cards.card_acct_id -> accounts.acct_id (CARDDATA -> ACCTDATA relationship)
ALTER TABLE cards
    ADD CONSTRAINT fk_cards_acct_id
    FOREIGN KEY (card_acct_id) REFERENCES accounts (acct_id);

-- card_xrefs.xref_cust_id -> customers.cust_id (CARDXREF -> CUSTDATA relationship)
ALTER TABLE card_xrefs
    ADD CONSTRAINT fk_card_xrefs_cust_id
    FOREIGN KEY (xref_cust_id) REFERENCES customers (cust_id);

-- card_xrefs.xref_acct_id -> accounts.acct_id (CARDXREF -> ACCTDATA relationship)
ALTER TABLE card_xrefs
    ADD CONSTRAINT fk_card_xrefs_acct_id
    FOREIGN KEY (xref_acct_id) REFERENCES accounts (acct_id);
