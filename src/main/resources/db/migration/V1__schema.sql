-- Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
--
-- Licensed under the Apache License, Version 2.0 (the "License").
-- You may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--    http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.
-- ============================================================================
--
-- ============================================================================
-- V1__schema.sql  -  AWS CardDemo modernization (Java 25 + Spring Boot 3.5.x)
-- ----------------------------------------------------------------------------
-- AUTHORITATIVE DATABASE SCHEMA.
--
-- Flyway is authoritative (ddl-auto=validate); types derived from
-- app/cpy/CV*.cpy + app/cpy/CSUSR01Y.cpy record layouts and
-- app/catlg/LISTCAT.txt key/index metadata; do NOT change types without
-- updating the matching JPA entity.
--
-- The Spring Boot application runs with spring.jpa.hibernate.ddl-auto=validate,
-- therefore Hibernate NEVER generates DDL. Every table, column, type, primary
-- key, and index is defined ONLY in this migration. Any drift between this file
-- and the JPA entities (domain/*) will make Hibernate `validate` FAIL at
-- startup. The inline `-- PIC ...` comments and the legend below are the
-- canonical specification the downstream domain/ and repository/ agents MUST
-- mirror exactly.
--
-- This schema guarantees 100% VSAM behavioral parity:
--   * exact decimal scale (numeric(p,s)) so there is zero rounding drift;
--   * char(n) fixed-width columns so VSAM ordering/equality are preserved;
--   * composite primary-key lengths matching the LISTCAT KEYLEN values;
--   * three NON-UNIQUE alternate indexes matching the VSAM AIX definitions.
--
-- ----------------------------------------------------------------------------
-- TYPE-MAPPING LEGEND  (COBOL PIC -> PostgreSQL type -> JPA Java type)
-- ----------------------------------------------------------------------------
--   S9(10)V99  -> numeric(12,2) -> BigDecimal (@Column precision=12, scale=2)
--   S9(09)V99  -> numeric(11,2) -> BigDecimal (precision=11, scale=2)
--   S9(04)V99  -> numeric(6,2)  -> BigDecimal (precision=6,  scale=2)
--   X(n) (text)               -> char(n)      -> String (fixed-length;
--                                                 @Column length=n /
--                                                 columnDefinition char(n))
--   9(n) GENUINE NUMERIC ID/QUANTITY (account/customer/merchant id, ssn, fico)
--                             -> numeric(n) (scale 0) -> Long/BigInteger
--   9(n) ENUMERATED CODE with mandatory leading zeros (category codes, CVV)
--        AND composite-key code parts
--                             -> char(n)      -> String
--
--   NOTE: Decimal columns are NEVER float/double/real/money. Code/type columns
--   that are COBOL X(02) (e.g. tran_type) are ALWAYS char(2). Account-id
--   columns are numeric(11) EVERYWHERE; type/category code columns are
--   char(2)/char(4) EVERYWHERE for cross-table consistency.
--
-- ----------------------------------------------------------------------------
-- CONVENTIONS
-- ----------------------------------------------------------------------------
--   * NULLABILITY: every column is declared NOT NULL. Legacy VSAM fixed-width
--     records always carry every field, so NOT NULL preserves that invariant.
--     The matching JPA entity should mirror this (@Column(nullable=false)).
--   * FILLER: COBOL trailing FILLER is positional padding with no business
--     meaning and is intentionally NOT modeled as a column. Fixed-width record
--     reconstruction for golden-file parity is handled by the file-I/O layer,
--     not the database. Each table documents its original RECLN and the omitted
--     trailing FILLER X(n) for byte-layout traceability.
--   * NO foreign keys: legacy VSAM enforced no referential integrity
--     (relationships are application-managed in COBOL); only primary keys and
--     the three alternate indexes are declared.
--   * NO Spring Batch metadata tables here: Spring Boot creates the BATCH_*
--     tables itself (spring.batch.jdbc.initialize-schema=always). This file
--     contains ONLY the 11 business tables + 3 indexes.
--   * Legacy misspelling "expiraion" (acct_expiraion_date, card_expiraion_date)
--     is the copybook field name and is preserved verbatim.
--   * PostgreSQL 16 dialect; plain CREATE statements (Flyway runs V1 once, so
--     no IF NOT EXISTS is required).
-- ============================================================================


-- ============================================================================
-- 1. customer   (source app/cpy/CVCUS01Y.cpy)
--    CVCUS01Y RECLN 500; business fields 332; trailing FILLER X(168) omitted
--    (no business meaning). Single primary key on cust_id.
-- ============================================================================
CREATE TABLE customer (
    cust_id                   numeric(9)   NOT NULL,  -- PIC 9(09)  genuine numeric id
    cust_first_name           char(25)     NOT NULL,  -- PIC X(25)
    cust_middle_name          char(25)     NOT NULL,  -- PIC X(25)
    cust_last_name            char(25)     NOT NULL,  -- PIC X(25)
    cust_addr_line_1          char(50)     NOT NULL,  -- PIC X(50)
    cust_addr_line_2          char(50)     NOT NULL,  -- PIC X(50)
    cust_addr_line_3          char(50)     NOT NULL,  -- PIC X(50)
    cust_addr_state_cd        char(2)      NOT NULL,  -- PIC X(02)
    cust_addr_country_cd      char(3)      NOT NULL,  -- PIC X(03)
    cust_addr_zip             char(10)     NOT NULL,  -- PIC X(10)
    cust_phone_num_1          char(15)     NOT NULL,  -- PIC X(15)
    cust_phone_num_2          char(15)     NOT NULL,  -- PIC X(15)
    cust_ssn                  numeric(9)   NOT NULL,  -- PIC 9(09)  genuine numeric (entity formats leading zeros for display)
    cust_govt_issued_id       char(20)     NOT NULL,  -- PIC X(20)
    cust_dob_yyyy_mm_dd       char(10)     NOT NULL,  -- PIC X(10)  date stored as text 'YYYY-MM-DD' (KEEP char(10), do NOT convert to date)
    cust_eft_account_id       char(10)     NOT NULL,  -- PIC X(10)
    cust_pri_card_holder_ind  char(1)      NOT NULL,  -- PIC X(01)
    cust_fico_credit_score    numeric(3)   NOT NULL,  -- PIC 9(03)  genuine numeric
    CONSTRAINT pk_customer PRIMARY KEY (cust_id)
);


-- ============================================================================
-- 2. account   (source app/cpy/CVACT01Y.cpy)
--    CVACT01Y RECLN 300; business fields 122; trailing FILLER X(178) omitted.
--    Single primary key on acct_id.
--    NOTE: the three date columns (open/expiraion/reissue) intentionally sit
--    BETWEEN the balance columns, preserving the exact copybook field order.
-- ============================================================================
CREATE TABLE account (
    acct_id                   numeric(11)    NOT NULL,  -- PIC 9(11)  genuine numeric id
    acct_active_status        char(1)        NOT NULL,  -- PIC X(01)
    acct_curr_bal             numeric(12,2)  NOT NULL,  -- PIC S9(10)V99
    acct_credit_limit         numeric(12,2)  NOT NULL,  -- PIC S9(10)V99
    acct_cash_credit_limit    numeric(12,2)  NOT NULL,  -- PIC S9(10)V99
    acct_open_date            char(10)       NOT NULL,  -- PIC X(10)
    acct_expiraion_date       char(10)       NOT NULL,  -- PIC X(10)  legacy "expiraion" spelling preserved
    acct_reissue_date         char(10)       NOT NULL,  -- PIC X(10)
    acct_curr_cyc_credit      numeric(12,2)  NOT NULL,  -- PIC S9(10)V99
    acct_curr_cyc_debit       numeric(12,2)  NOT NULL,  -- PIC S9(10)V99
    acct_addr_zip             char(10)       NOT NULL,  -- PIC X(10)
    acct_group_id             char(10)       NOT NULL,  -- PIC X(10)
    CONSTRAINT pk_account PRIMARY KEY (acct_id)
);


-- ============================================================================
-- 3. card   (source app/cpy/CVACT02Y.cpy)
--    CVACT02Y RECLN 150; business fields 91; trailing FILLER X(59) omitted.
--    Single primary key on card_num.
--    card_acct_id carries a NON-UNIQUE alternate index (see indexes section):
--    LISTCAT CARDDATA AIX KEYLEN 11, AXRKP 16, NONUNIQKEY.
-- ============================================================================
CREATE TABLE card (
    card_num                  char(16)     NOT NULL,  -- PIC X(16)
    card_acct_id              numeric(11)  NOT NULL,  -- PIC 9(11)  genuine numeric id (alt-indexed)
    card_cvv_cd               char(3)      NOT NULL,  -- PIC 9(03)  CVV: leading zeros mandatory, never arithmetic -> char
    card_embossed_name        char(50)     NOT NULL,  -- PIC X(50)
    card_expiraion_date       char(10)     NOT NULL,  -- PIC X(10)  legacy "expiraion" spelling preserved
    card_active_status        char(1)      NOT NULL,  -- PIC X(01)
    CONSTRAINT pk_card PRIMARY KEY (card_num)
);


-- ============================================================================
-- 4. card_xref   (source app/cpy/CVACT03Y.cpy)
--    CVACT03Y RECLN 50; business fields 36; trailing FILLER X(14) omitted.
--    Single primary key on xref_card_num.
--    xref_acct_id carries a NON-UNIQUE alternate index (see indexes section):
--    LISTCAT CARDXREF AIX KEYLEN 11, AXRKP 25, NONUNIQKEY.
-- ============================================================================
CREATE TABLE card_xref (
    xref_card_num             char(16)     NOT NULL,  -- PIC X(16)
    xref_cust_id              numeric(9)   NOT NULL,  -- PIC 9(09)  genuine numeric id
    xref_acct_id              numeric(11)  NOT NULL,  -- PIC 9(11)  genuine numeric id (alt-indexed)
    CONSTRAINT pk_card_xref PRIMARY KEY (xref_card_num)
);


-- ============================================================================
-- 5. transaction   (source app/cpy/CVTRA05Y.cpy)
--    CVTRA05Y RECLN 350; business fields 330; trailing FILLER X(20) omitted.
--    Single primary key on tran_id.
--    tran_proc_ts begins at byte offset 304 (matches LISTCAT TRANSACT AIX
--    AXRKP 304) and carries a NON-UNIQUE alternate index (see indexes
--    section): LISTCAT TRANSACT AIX KEYLEN 26, AXRKP 304, NONUNIQKEY.
--    NOTE: "transaction" is a valid unquoted table name in PostgreSQL 16; it
--    is kept to match the AAP/entity name Transaction.
-- ============================================================================
CREATE TABLE transaction (
    tran_id                   char(16)       NOT NULL,  -- PIC X(16)
    tran_type_cd              char(2)        NOT NULL,  -- PIC X(02)
    tran_cat_cd               char(4)        NOT NULL,  -- PIC 9(04)  category code -> char, leading zeros
    tran_source               char(10)       NOT NULL,  -- PIC X(10)
    tran_desc                 char(100)      NOT NULL,  -- PIC X(100)
    tran_amt                  numeric(11,2)  NOT NULL,  -- PIC S9(09)V99
    tran_merchant_id          numeric(9)     NOT NULL,  -- PIC 9(09)  genuine numeric id
    tran_merchant_name        char(50)       NOT NULL,  -- PIC X(50)
    tran_merchant_city        char(50)       NOT NULL,  -- PIC X(50)
    tran_merchant_zip         char(10)       NOT NULL,  -- PIC X(10)
    tran_card_num             char(16)       NOT NULL,  -- PIC X(16)
    tran_orig_ts              char(26)       NOT NULL,  -- PIC X(26)  timestamp text (KEEP char(26) for byte/ordering parity)
    tran_proc_ts              char(26)       NOT NULL,  -- PIC X(26)  timestamp text at byte offset 304 (alt-indexed); ISO-text ordering = chronological
    CONSTRAINT pk_transaction PRIMARY KEY (tran_id)
);


-- ============================================================================
-- 6. daily_transaction   (source app/cpy/CVTRA06Y.cpy)
--    CVTRA06Y RECLN 350; business fields 330; trailing FILLER X(20) omitted.
--    Structural mirror of `transaction` with the `dalytran_` prefix.
--    Single primary key on dalytran_id. There is NO alternate index here.
-- ============================================================================
CREATE TABLE daily_transaction (
    dalytran_id               char(16)       NOT NULL,  -- PIC X(16)
    dalytran_type_cd          char(2)        NOT NULL,  -- PIC X(02)
    dalytran_cat_cd           char(4)        NOT NULL,  -- PIC 9(04)  category code -> char, leading zeros
    dalytran_source           char(10)       NOT NULL,  -- PIC X(10)
    dalytran_desc             char(100)      NOT NULL,  -- PIC X(100)
    dalytran_amt              numeric(11,2)  NOT NULL,  -- PIC S9(09)V99
    dalytran_merchant_id      numeric(9)     NOT NULL,  -- PIC 9(09)  genuine numeric id
    dalytran_merchant_name    char(50)       NOT NULL,  -- PIC X(50)
    dalytran_merchant_city    char(50)       NOT NULL,  -- PIC X(50)
    dalytran_merchant_zip     char(10)       NOT NULL,  -- PIC X(10)
    dalytran_card_num         char(16)       NOT NULL,  -- PIC X(16)
    dalytran_orig_ts          char(26)       NOT NULL,  -- PIC X(26)  timestamp text
    dalytran_proc_ts          char(26)       NOT NULL,  -- PIC X(26)  timestamp text
    CONSTRAINT pk_daily_transaction PRIMARY KEY (dalytran_id)
);


-- ============================================================================
-- 7. tran_cat_balance   (source app/cpy/CVTRA01Y.cpy)
--    CVTRA01Y RECLN 50; business fields 28; trailing FILLER X(22) omitted.
--    COMPOSITE primary key (trancat_acct_id + trancat_type_cd + trancat_cd)
--    = 11 + 2 + 4 = KEYLEN 17 (matches the VSAM TCATBALF key length).
-- ============================================================================
CREATE TABLE tran_cat_balance (
    trancat_acct_id           numeric(11)    NOT NULL,  -- PIC 9(11)  account id -> numeric (consistent with account.acct_id)
    trancat_type_cd           char(2)        NOT NULL,  -- PIC X(02)
    trancat_cd                char(4)        NOT NULL,  -- PIC 9(04)  category code -> char, leading zeros
    tran_cat_bal              numeric(11,2)  NOT NULL,  -- PIC S9(09)V99
    CONSTRAINT pk_tran_cat_balance PRIMARY KEY (trancat_acct_id, trancat_type_cd, trancat_cd)
);


-- ============================================================================
-- 8. disclosure_group   (source app/cpy/CVTRA02Y.cpy)
--    CVTRA02Y RECLN 50; business fields 22; trailing FILLER X(28) omitted.
--    COMPOSITE primary key (dis_acct_group_id + dis_tran_type_cd
--    + dis_tran_cat_cd) = 10 + 2 + 4 = KEYLEN 16 (matches the VSAM DISCGRP
--    key length).
-- ============================================================================
CREATE TABLE disclosure_group (
    dis_acct_group_id         char(10)       NOT NULL,  -- PIC X(10)
    dis_tran_type_cd          char(2)        NOT NULL,  -- PIC X(02)
    dis_tran_cat_cd           char(4)        NOT NULL,  -- PIC 9(04)  category code -> char, leading zeros
    dis_int_rate              numeric(6,2)   NOT NULL,  -- PIC S9(04)V99
    CONSTRAINT pk_disclosure_group PRIMARY KEY (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd)
);


-- ============================================================================
-- 9. tran_type   (source app/cpy/CVTRA03Y.cpy)
--    CVTRA03Y RECLN 60; business fields 52; trailing FILLER X(08) omitted.
--    Single primary key on tran_type (KEYLEN 2).
-- ============================================================================
CREATE TABLE tran_type (
    tran_type                 char(2)      NOT NULL,  -- PIC X(02)
    tran_type_desc            char(50)     NOT NULL,  -- PIC X(50)
    CONSTRAINT pk_tran_type PRIMARY KEY (tran_type)
);


-- ============================================================================
-- 10. tran_category   (source app/cpy/CVTRA04Y.cpy)
--     CVTRA04Y RECLN 60; business fields 56; trailing FILLER X(04) omitted.
--     COMPOSITE primary key (tran_type_cd + tran_cat_cd) = 2 + 4 = KEYLEN 6.
-- ============================================================================
CREATE TABLE tran_category (
    tran_type_cd              char(2)      NOT NULL,  -- PIC X(02)
    tran_cat_cd               char(4)      NOT NULL,  -- PIC 9(04)  category code -> char, leading zeros
    tran_cat_type_desc        char(50)     NOT NULL,  -- PIC X(50)
    CONSTRAINT pk_tran_category PRIMARY KEY (tran_type_cd, tran_cat_cd)
);


-- ============================================================================
-- 11. user_security   (source app/cpy/CSUSR01Y.cpy)
--     CSUSR01Y RECLN 80; business fields 57; trailing FILLER X(23) omitted.
--     Single primary key on sec_usr_id (KEYLEN 8).
--     SECURITY HARDENING (AAP 0.6.6): the legacy clear-text PIC X(08) password
--     is REPLACED by a BCrypt hash; widened to varchar(60) (BCrypt hashes are
--     60 chars). varchar (not char) is used because a hash has no fixed-width
--     VSAM ordering semantics. This table is created EMPTY; the two seed users
--     ADMIN001/USER0001 are inserted by the bootstrap security seeder reading
--     CARDDEMO_ADMIN_PASSWORD / CARDDEMO_USER_PASSWORD from the environment --
--     NEVER hardcoded and NOT seeded by V2.
-- ============================================================================
CREATE TABLE user_security (
    sec_usr_id                char(8)      NOT NULL,  -- PIC X(08)
    sec_usr_fname             char(20)     NOT NULL,  -- PIC X(20)
    sec_usr_lname             char(20)     NOT NULL,  -- PIC X(20)
    sec_usr_pwd               varchar(60)  NOT NULL,  -- legacy PIC X(08) clear-text -> BCrypt hash (60 chars); externalized, never hardcoded
    sec_usr_type              char(1)      NOT NULL,  -- PIC X(01)  'A' admin / 'U' user
    CONSTRAINT pk_user_security PRIMARY KEY (sec_usr_id)
);


-- ============================================================================
-- ALTERNATE INDEXES (exactly 3, all NON-UNIQUE)
-- ----------------------------------------------------------------------------
-- These reproduce the VSAM alternate-index (AIX) definitions verified as
-- NONUNIQKEY in app/catlg/LISTCAT.txt. They are plain (non-unique) indexes --
-- NEVER UNIQUE -- so multiple cards may share an account id, multiple xref
-- rows may share an account id, and multiple transactions may share a
-- processing timestamp, exactly as the legacy VSAM AIXes allowed.
-- There is intentionally NO alternate index on daily_transaction.
-- ============================================================================
CREATE INDEX ix_card_acct_id        ON card (card_acct_id);          -- LISTCAT CARDDATA AIX KEYLEN 11, AXRKP 16,  NONUNIQKEY
CREATE INDEX ix_card_xref_acct_id   ON card_xref (xref_acct_id);     -- LISTCAT CARDXREF AIX KEYLEN 11, AXRKP 25,  NONUNIQKEY
CREATE INDEX ix_transaction_proc_ts ON transaction (tran_proc_ts);   -- LISTCAT TRANSACT AIX KEYLEN 26, AXRKP 304, NONUNIQKEY
