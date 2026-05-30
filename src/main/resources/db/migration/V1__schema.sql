-- =============================================================================
-- V1__schema.sql
-- =============================================================================
-- Purpose:      Create the core database schema for the CardDemo modernized
--              Spring Boot application: 12 base tables + transaction_id_seq.
--              This is the foundational migration — all subsequent migrations
--              (V2-V5) depend on objects created here.
-- Source:      11 record-defining COBOL copybooks from app/cpy/:
--              CVACT01Y, CVACT02Y, CVACT03Y, CVCUS01Y, CVTRA01Y-CVTRA06Y, CSUSR01Y
-- Version:     CardDemo_v1.0-15-g27d6c6f-68 (Date: 2022-07-19)
-- Tech Spec:   §0.3.1 (Refactored Project Structure), §0.4.1.4 (file-by-file),
--              §6.2 (Database Design)
-- AAP Rules:   PR-13 (record-length fidelity — PIC clauses → column types),
--              PR-14 (COBOL typo EXPIRAION → SQL expiration English spelling),
--              PR-15 (composite key fidelity for TCATBAL, DISCGRP, TRANCATG),
--              PR-16 (BigDecimal NUMERIC(15,2) for money — never float/double),
--              PR-22 (version INTEGER NOT NULL DEFAULT 0 for @Version optimistic
--                     locking on accounts, cards, customers, transactions),
--              PR-25 (single monolith schema), PR-28 (Jakarta EE namespace —
--                     no impact on DDL but constrains entity-layer mapping)
-- Notes:       Spring Boot manages BATCH_* tables separately via
--              spring.batch.jdbc.initialize-schema=always. This script does NOT
--              create batch metadata tables.
-- =============================================================================


-- =============================================================================
-- Section 1: customers (from CVCUS01Y.cpy — 500 bytes)
-- =============================================================================
-- 500-byte CUSTOMER-RECORD. PII-rich (name, address, phone, SSN, DOB, FICO).
-- No FK dependencies — created first so card_xref can reference it later.
-- SSN preserved verbatim as VARCHAR(9) to retain leading zeros (PR-13);
-- outbound masking to ***-**-#### is applied at the DTO/mapper boundary (PR-20),
-- never in the schema.
-- =============================================================================
CREATE TABLE customers (
    cust_id                  BIGINT       NOT NULL PRIMARY KEY,
    first_name               VARCHAR(25)  NOT NULL,
    middle_name              VARCHAR(25),
    last_name                VARCHAR(25)  NOT NULL,
    addr_line_1              VARCHAR(50),
    addr_line_2              VARCHAR(50),
    addr_line_3              VARCHAR(50),
    addr_state_cd            CHAR(2),
    addr_country_cd          CHAR(3),
    addr_zip                 VARCHAR(10),
    phone_num_1              VARCHAR(15),
    phone_num_2              VARCHAR(15),
    ssn                      VARCHAR(9),
    govt_issued_id           VARCHAR(20),
    dob                      DATE,
    eft_account_id           VARCHAR(10),
    pri_card_holder_ind      CHAR(1),
    fico_credit_score        SMALLINT,
    version                  INTEGER      NOT NULL DEFAULT 0,
    created_date             TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_modified_date       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by               VARCHAR(50),
    last_modified_by         VARCHAR(50)
);
COMMENT ON TABLE customers IS 'Maps app/cpy/CVCUS01Y.cpy 500-byte CUSTOMER-RECORD (CardDemo_v1.0-15-g27d6c6f-68)';
COMMENT ON COLUMN customers.ssn IS 'PR-20: CUST-SSN PIC 9(09) preserved verbatim (leading zeros retained); masked at DTO boundary, never stored masked';
COMMENT ON COLUMN customers.version IS 'PR-22: Optimistic locking version for @Version JPA annotation';


-- =============================================================================
-- Section 2: accounts (from CVACT01Y.cpy — 300 bytes)
-- =============================================================================
-- 300-byte ACCOUNT-RECORD. No FK dependencies — created before cards/xref/
-- tran_cat_balances that reference it. All money fields are NUMERIC(15,2)
-- (PR-16). ACCT-EXPIRAION-DATE [COBOL typo] normalized to expiration_date
-- (PR-14). group_id is an intentionally UNenforced reference to
-- disclosure_groups.group_id because CBACT04C falls back to a 'DEFAULT' group
-- when an exact match is missing (PR-02).
-- =============================================================================
CREATE TABLE accounts (
    acct_id                  BIGINT         NOT NULL PRIMARY KEY,
    active_status            CHAR(1)        NOT NULL,
    curr_bal                 NUMERIC(15,2)  NOT NULL DEFAULT 0.00,
    credit_limit             NUMERIC(15,2)  NOT NULL DEFAULT 0.00,
    cash_credit_limit        NUMERIC(15,2)  NOT NULL DEFAULT 0.00,
    open_date                DATE,
    expiration_date          DATE,                        -- PR-14: COBOL EXPIRAION → SQL expiration
    reissue_date             DATE,
    curr_cyc_credit          NUMERIC(15,2)  NOT NULL DEFAULT 0.00,
    curr_cyc_debit           NUMERIC(15,2)  NOT NULL DEFAULT 0.00,
    addr_zip                 VARCHAR(10),
    group_id                 VARCHAR(10),
    version                  INTEGER        NOT NULL DEFAULT 0,
    created_date             TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_modified_date       TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by               VARCHAR(50),
    last_modified_by         VARCHAR(50)
);
COMMENT ON TABLE accounts IS 'Maps app/cpy/CVACT01Y.cpy 300-byte ACCOUNT-RECORD (CardDemo_v1.0-15-g27d6c6f-68). ACCT-EXPIRAION-DATE [COBOL typo] is normalized to expiration_date per PR-14.';
COMMENT ON COLUMN accounts.expiration_date IS 'PR-14: Original COBOL field ACCT-EXPIRAION-DATE [sic typo] normalized to correct English spelling';
COMMENT ON COLUMN accounts.version IS 'PR-22: Optimistic locking version for @Version JPA annotation';
COMMENT ON COLUMN accounts.group_id IS 'References disclosure_groups.group_id (not enforced FK due to DEFAULT fallback semantics per PR-02)';


-- =============================================================================
-- Section 3: cards (from CVACT02Y.cpy — 150 bytes)
-- =============================================================================
-- 150-byte CARD-RECORD. FK account_id → accounts.acct_id (CARD-ACCT-ID).
-- card_num is the natural PK (VARCHAR(16) preserves CARD-NUM PIC X(16)).
-- cvv_cd from CARD-CVV-CD PIC 9(03) → SMALLINT. CARD-EXPIRAION-DATE [COBOL typo]
-- normalized to expiration_date (PR-14). A secondary index on account_id
-- (replacing VSAM CARDDATA.AIX) is added in V2__indexes.sql.
-- =============================================================================
CREATE TABLE cards (
    card_num                 VARCHAR(16)  NOT NULL PRIMARY KEY,
    account_id               BIGINT       NOT NULL,
    cvv_cd                   SMALLINT     NOT NULL,
    embossed_name            VARCHAR(50),
    expiration_date          DATE,                          -- PR-14: COBOL EXPIRAION → SQL expiration
    active_status            CHAR(1)      NOT NULL,
    version                  INTEGER      NOT NULL DEFAULT 0,
    created_date             TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_modified_date       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by               VARCHAR(50),
    last_modified_by         VARCHAR(50),
    CONSTRAINT fk_cards_account FOREIGN KEY (account_id) REFERENCES accounts (acct_id)
);
COMMENT ON TABLE cards IS 'Maps app/cpy/CVACT02Y.cpy 150-byte CARD-RECORD (CardDemo_v1.0-15-g27d6c6f-68)';
COMMENT ON COLUMN cards.expiration_date IS 'PR-14: Original COBOL field CARD-EXPIRAION-DATE [sic typo] normalized to correct English spelling';
COMMENT ON COLUMN cards.version IS 'PR-22: Optimistic locking version for @Version JPA annotation';


-- =============================================================================
-- Section 4: card_xref (from CVACT03Y.cpy — 50 bytes per copybook, 36 bytes per data file)
-- =============================================================================
-- Junction record linking a card to its customer and account. Created AFTER
-- cards, customers, and accounts because it references all three. No audit or
-- version columns (immutable cross-reference). xref_cust_id PIC 9(09) and
-- xref_acct_id PIC 9(11) both map to BIGINT.
-- =============================================================================
CREATE TABLE card_xref (
    xref_card_num            VARCHAR(16)  NOT NULL PRIMARY KEY,
    xref_cust_id             BIGINT       NOT NULL,
    xref_acct_id             BIGINT       NOT NULL,
    CONSTRAINT fk_xref_card     FOREIGN KEY (xref_card_num) REFERENCES cards (card_num),
    CONSTRAINT fk_xref_customer FOREIGN KEY (xref_cust_id)  REFERENCES customers (cust_id),
    CONSTRAINT fk_xref_account  FOREIGN KEY (xref_acct_id)  REFERENCES accounts (acct_id)
);
COMMENT ON TABLE card_xref IS 'Maps app/cpy/CVACT03Y.cpy 50-byte CARD-XREF-RECORD (CardDemo_v1.0-15-g27d6c6f-68). NOTE: actual fixture file is 36 bytes (no FILLER) — that 14-byte FILLER is omitted from this schema.';


-- =============================================================================
-- Section 5: transactions (from CVTRA05Y.cpy — 350 bytes)
-- =============================================================================
-- 350-byte TRAN-RECORD. FK card_num → cards.card_num (TRAN-CARD-NUM).
-- amount from TRAN-AMT PIC S9(09)V99 → NUMERIC(15,2) (PR-16). orig_timestamp /
-- proc_timestamp hold DB2-format timestamps (yyyy-MM-dd-HH.mm.ss.SSS'0000')
-- formatted at the JPA/DTO boundary (PR-11). A secondary index on orig_timestamp
-- (replacing VSAM TRANSACT.AIX) is added in V2__indexes.sql.
-- =============================================================================
CREATE TABLE transactions (
    tran_id                  VARCHAR(16)    NOT NULL PRIMARY KEY,
    type_cd                  CHAR(2)        NOT NULL,
    cat_cd                   CHAR(4)        NOT NULL,
    source                   VARCHAR(10),
    description              VARCHAR(100),
    amount                   NUMERIC(15,2)  NOT NULL DEFAULT 0.00,
    merchant_id              BIGINT,
    merchant_name            VARCHAR(50),
    merchant_city            VARCHAR(50),
    merchant_zip             VARCHAR(10),
    card_num                 VARCHAR(16)    NOT NULL,
    orig_timestamp           TIMESTAMP,
    proc_timestamp           TIMESTAMP,
    version                  INTEGER        NOT NULL DEFAULT 0,
    created_date             TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_modified_date       TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by               VARCHAR(50),
    last_modified_by         VARCHAR(50),
    CONSTRAINT fk_transactions_card FOREIGN KEY (card_num) REFERENCES cards (card_num)
);
COMMENT ON TABLE transactions IS 'Maps app/cpy/CVTRA05Y.cpy 350-byte TRAN-RECORD (CardDemo_v1.0-15-g27d6c6f-68). orig_timestamp and proc_timestamp store DB2-format timestamps (yyyy-MM-dd-HH.mm.ss.SSS''0000'') per PR-11 — parsing/formatting handled at the JPA/DTO boundary.';
COMMENT ON COLUMN transactions.version IS 'PR-22: Optimistic locking version for @Version JPA annotation';


-- =============================================================================
-- Section 6: daily_transactions (from CVTRA06Y.cpy — 350 bytes, STAGING table)
-- =============================================================================
-- Staging mirror of the daily-transaction PS feed (DALYTRAN-RECORD). Loaded by
-- DailyTransactionReadJobConfig (CBTRN01C) and consumed by the POSTTRAN job
-- (CBTRN02C). Synthetic BIGSERIAL surrogate PK enables Spring Batch chunk
-- restart. Deliberately denormalized with NO FK constraints so orphaned /
-- invalid feed rows can be staged and then rejected (codes 100-103). All
-- business columns are nullable to tolerate malformed input. processed flag
-- supports incremental post-processing (AAP §0.6.6).
-- =============================================================================
CREATE TABLE daily_transactions (
    daily_tran_id            BIGSERIAL      NOT NULL PRIMARY KEY,
    tran_id                  VARCHAR(16)    NOT NULL,
    type_cd                  CHAR(2),
    cat_cd                   CHAR(4),
    source                   VARCHAR(10),
    description              VARCHAR(100),
    amount                   NUMERIC(15,2),
    merchant_id              BIGINT,
    merchant_name            VARCHAR(50),
    merchant_city            VARCHAR(50),
    merchant_zip             VARCHAR(10),
    card_num                 VARCHAR(16),
    orig_timestamp           TIMESTAMP,
    proc_timestamp           TIMESTAMP,
    processed                BOOLEAN        NOT NULL DEFAULT FALSE
);
COMMENT ON TABLE daily_transactions IS 'Staging table for CBTRN02C/POSTTRAN batch — maps app/cpy/CVTRA06Y.cpy 350-byte DALYTRAN-RECORD. Synthetic BIGSERIAL PK enables chunk-restart per Spring Batch. processed flag enables incremental post-processing.';


-- =============================================================================
-- Section 7: rejected_transactions (CVTRA06Y.cpy + DALYREJS layout — ~430 bytes equivalent)
-- =============================================================================
-- DALYREJS equivalent (350-byte transaction + 80-byte reject metadata). Written
-- by the POSTTRAN reject sink when the CBTRN02C validation chain fails. No FK
-- constraints (denormalized, mirrors the rejected PS file). validation_code and
-- rejection_reason capture the failing rule (AAP §0.6.6).
-- =============================================================================
CREATE TABLE rejected_transactions (
    rejected_id              BIGSERIAL      NOT NULL PRIMARY KEY,
    tran_id                  VARCHAR(16),
    type_cd                  CHAR(2),
    cat_cd                   CHAR(4),
    source                   VARCHAR(10),
    description              VARCHAR(100),
    amount                   NUMERIC(15,2),
    merchant_id              BIGINT,
    merchant_name            VARCHAR(50),
    merchant_city            VARCHAR(50),
    merchant_zip             VARCHAR(10),
    card_num                 VARCHAR(16),
    orig_timestamp           TIMESTAMP,
    proc_timestamp           TIMESTAMP,
    validation_code          INTEGER        NOT NULL,
    rejection_reason         VARCHAR(80)    NOT NULL,
    rejected_date            TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE rejected_transactions IS 'DALYREJS equivalent — records transactions rejected by CBTRN02C validation chain. validation_code values: 100 (INVALID CARD), 101 (account not found), 102 (OVERLIMIT), 103 (EXPIRED). Per AAP §0.6.6.';


-- =============================================================================
-- Section 8: tran_cat_balances (from CVTRA01Y.cpy — 50 bytes, COMPOSITE PK per PR-15)
-- =============================================================================
-- 50-byte TRAN-CAT-BAL-RECORD. PR-15: composite PK (account_id, type_cd, cat_cd)
-- mirrors the COBOL TRAN-CAT-KEY group field order. FK account_id →
-- accounts.acct_id. balance from TRAN-CAT-BAL PIC S9(09)V99 → NUMERIC(15,2)
-- (PR-16); maintained by the CBTRN02C TCATBAL upsert (PR-06) and the CBACT04C
-- interest run. JPA entity uses @EmbeddedId TransactionCategoryBalanceId.
-- =============================================================================
CREATE TABLE tran_cat_balances (
    account_id               BIGINT         NOT NULL,
    type_cd                  CHAR(2)        NOT NULL,
    cat_cd                   CHAR(4)        NOT NULL,
    balance                  NUMERIC(15,2)  NOT NULL DEFAULT 0.00,
    PRIMARY KEY (account_id, type_cd, cat_cd),
    CONSTRAINT fk_tcatbal_account FOREIGN KEY (account_id) REFERENCES accounts (acct_id)
);
COMMENT ON TABLE tran_cat_balances IS 'Maps app/cpy/CVTRA01Y.cpy 50-byte TRAN-CAT-BAL-RECORD (CardDemo_v1.0-15-g27d6c6f-68). PR-15: composite PK (account_id, type_cd, cat_cd) mirrors COBOL TRAN-CAT-KEY group order. JPA entity uses @EmbeddedId TransactionCategoryBalanceId.';


-- =============================================================================
-- Section 9: disclosure_groups (from CVTRA02Y.cpy — 50 bytes, COMPOSITE PK per PR-15)
-- =============================================================================
-- 50-byte DIS-GROUP-RECORD. PR-15: composite PK (group_id, type_cd, cat_cd)
-- mirrors the COBOL DIS-GROUP-KEY group field order. dis_int_rate from
-- DIS-INT-RATE PIC S9(04)V99 → NUMERIC(6,2) (PR-16). PR-02: V3 seed includes
-- DEFAULT group_id rows used as the InterestCalculationTasklet fallback. No FK
-- (group_id is referenced un-enforced from accounts). JPA entity uses
-- @EmbeddedId DisclosureGroupId.
-- =============================================================================
CREATE TABLE disclosure_groups (
    group_id                 VARCHAR(10)    NOT NULL,
    type_cd                  CHAR(2)        NOT NULL,
    cat_cd                   CHAR(4)        NOT NULL,
    dis_int_rate             NUMERIC(6,2)   NOT NULL DEFAULT 0.00,
    PRIMARY KEY (group_id, type_cd, cat_cd)
);
COMMENT ON TABLE disclosure_groups IS 'Maps app/cpy/CVTRA02Y.cpy 50-byte DIS-GROUP-RECORD (CardDemo_v1.0-15-g27d6c6f-68). PR-15: composite PK (group_id, type_cd, cat_cd) mirrors COBOL DIS-GROUP-KEY group order. PR-02: V3 seed includes DEFAULT group_id rows for InterestCalculationTasklet fallback. JPA entity uses @EmbeddedId DisclosureGroupId.';
COMMENT ON COLUMN disclosure_groups.dis_int_rate IS 'PR-16: DIS-INT-RATE PIC S9(04)V99 → NUMERIC(6,2); used by CBACT04C interest formula (TRAN-CAT-BAL * DIS-INT-RATE) / 1200';


-- =============================================================================
-- Section 10: transaction_types (from CVTRA03Y.cpy — 60 bytes)
-- =============================================================================
-- 60-byte TRAN-TYPE-RECORD. Simple PK tran_type (TRAN-TYPE PIC X(02) → CHAR(2)).
-- Immutable reference data — 7 rows seeded by V3. No audit/version columns.
-- =============================================================================
CREATE TABLE transaction_types (
    tran_type                CHAR(2)        NOT NULL PRIMARY KEY,
    type_desc                VARCHAR(50)    NOT NULL
);
COMMENT ON TABLE transaction_types IS 'Maps app/cpy/CVTRA03Y.cpy 60-byte TRAN-TYPE-RECORD (CardDemo_v1.0-15-g27d6c6f-68). Reference data — 7 rows seeded by V3.';


-- =============================================================================
-- Section 11: transaction_categories (from CVTRA04Y.cpy — 60 bytes, COMPOSITE PK per PR-15)
-- =============================================================================
-- 60-byte TRAN-CAT-RECORD. PR-15: composite PK (type_cd, cat_cd) mirrors the
-- COBOL TRAN-CAT-KEY group field order. Immutable reference data — 18 rows
-- seeded by V3. JPA entity uses @EmbeddedId TransactionCategoryId.
-- =============================================================================
CREATE TABLE transaction_categories (
    type_cd                  CHAR(2)        NOT NULL,
    cat_cd                   CHAR(4)        NOT NULL,
    category_desc            VARCHAR(50)    NOT NULL,
    PRIMARY KEY (type_cd, cat_cd)
);
COMMENT ON TABLE transaction_categories IS 'Maps app/cpy/CVTRA04Y.cpy 60-byte TRAN-CAT-RECORD (CardDemo_v1.0-15-g27d6c6f-68). PR-15: composite PK (type_cd, cat_cd). Reference data — 18 rows seeded by V3. JPA entity uses @EmbeddedId TransactionCategoryId.';


-- =============================================================================
-- Section 12: users (from CSUSR01Y.cpy — 80 bytes, BCrypt-extended sec_usr_pwd)
-- =============================================================================
-- 80-byte SEC-USER-DATA. PK sec_usr_id (SEC-USR-ID PIC X(08) → VARCHAR(8)).
-- PR-17: sec_usr_pwd widened from the original PIC X(08) plaintext to VARCHAR(60)
-- to store a BCrypt hash (NEVER plaintext). PR-19: sec_usr_type 'A'/'U' maps to
-- ROLE_ADMIN/ROLE_USER via CustomAuthorityMapper; enforced here by a CHECK
-- constraint. JPA entity User implements UserDetails.
-- =============================================================================
CREATE TABLE users (
    sec_usr_id               VARCHAR(8)     NOT NULL PRIMARY KEY,
    sec_usr_fname            VARCHAR(20)    NOT NULL,
    sec_usr_lname            VARCHAR(20)    NOT NULL,
    sec_usr_pwd              VARCHAR(60)    NOT NULL,           -- BCrypt hash (PR-17), NOT plaintext
    sec_usr_type             CHAR(1)        NOT NULL,           -- 'A' = ADMIN, 'U' = USER (PR-19)
    created_date             TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_modified_date       TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by               VARCHAR(50),
    last_modified_by         VARCHAR(50),
    CONSTRAINT chk_users_type CHECK (sec_usr_type IN ('A', 'U'))
);
COMMENT ON TABLE users IS 'Maps app/cpy/CSUSR01Y.cpy 80-byte SEC-USER-DATA (CardDemo_v1.0-15-g27d6c6f-68). PR-17: sec_usr_pwd stores 60-char BCrypt hash (NOT plaintext). PR-19: sec_usr_type maps to GrantedAuthority via CustomAuthorityMapper at runtime.';
COMMENT ON COLUMN users.sec_usr_pwd IS 'PR-17: BCrypt hash, exactly 60 chars starting with $2a$10$. NEVER store plaintext passwords.';
COMMENT ON COLUMN users.sec_usr_type IS 'PR-19: A→ROLE_ADMIN, U→ROLE_USER mapping (handled in CustomAuthorityMapper)';


-- =============================================================================
-- Section 13: transaction_id_seq (online transaction-ID suffix generator)
-- =============================================================================
-- Backs the 6-char suffix of the 16-char tran_id (parmDate(10) + suffix(6))
-- for ONLINE transactions (POST /api/transactions). Batch interest posting
-- (CBACT04C) uses a per-JobExecution AtomicLong instead. Per AAP §0.6.10 and
-- CBACT04C.cbl L473-L500.
-- =============================================================================
CREATE SEQUENCE transaction_id_seq
    START WITH 1
    INCREMENT BY 1
    NO CYCLE;
COMMENT ON SEQUENCE transaction_id_seq IS 'Generates suffix portion of 16-char tran_id (parmDate(10) + suffix(6) per AAP §0.6.10 and CBACT04C.cbl L473-L500). Used by TransactionIdGenerator for online transactions; batch uses per-JobExecution AtomicLong.';

