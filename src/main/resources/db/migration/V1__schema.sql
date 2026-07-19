-- =============================================================================
-- V1__schema.sql
--
-- AWS CardDemo business schema: the legacy VSAM KSDS data tier re-expressed as a
-- PostgreSQL 18.x relational schema. This is the first business-data Flyway
-- migration of the COBOL -> Java 25 / Spring Boot 3.5.16 migration (AAP 0.4.1).
--
-- Creates EXACTLY 10 tables, one per legacy VSAM key-sequenced data set (KSDS):
--   account, card, card_xref, transaction, transaction_category_balance,
--   disclosure_group, transaction_type, transaction_category, customer,
--   user_security.
--
-- ORDERING: Flyway applies migrations in version order. V0 (Spring Batch
-- metadata) runs first, then this V1 business schema, then V2 (reference/seed
-- data) and V3 (secondary indexes). This file is DDL only: NO seed data (V2),
-- NO secondary indexes (V3), NO foreign keys, NO Flyway callbacks/undo scripts.
--
-- AUTHORITATIVE STRATEGY (validated, never generated): Flyway is the single
-- source of truth for all DDL. Because spring.jpa.hibernate.ddl-auto=validate,
-- Hibernate NEVER creates or alters schema; it only verifies that the JPA
-- entity mappings under src/main/java/com/aws/carddemo/domain/** match the
-- tables created here. Every table name, column name, SQL type and primary key
-- below therefore mirrors those entities exactly.
--
-- TYPE MAPPING (AAP 0.6.2): PIC X(n) -> CHAR(n) (fixed-width, trailing-space
-- semantics preserved); PIC 9(n) -> NUMERIC(n); PIC S9(n)V99 -> NUMERIC(n+2,2)
-- (money is ALWAYS NUMERIC, never float/double/real); PIC X(10) date -> DATE;
-- PIC X(26) timestamp -> TIMESTAMP. FILLER bytes are layout padding and are NOT
-- persisted as columns. Relationships are modelled as plain scalar columns (no
-- FOREIGN KEY constraints), faithful to the loosely-coupled flat VSAM design.
--
-- COLLATION (AAP 0.6.6): every CHAR column carries COLLATE "C" so PostgreSQL
-- reproduces the legacy bytewise EBCDIC/VSAM key-browse and SORT order (e.g.
-- SORT FIELDS=(TRAN-ID,A)) deterministically. Rationale recorded in
-- docs/decision-log.md.
--
-- HIBERNATE ddl-auto=validate TYPE ALIGNMENT (cross-cutting coordination item):
-- Column SQL types are CHAR(n)/NUMERIC(n) per AAP 0.6.2; entity @Column mappings
-- must align their JDBC types (columnDefinition / @JdbcTypeCode) for
-- ddl-auto=validate. See docs/decision-log.md. (Integral keys already declare
-- @JdbcTypeCode(SqlTypes.NUMERIC); the CHAR alignment of String fields is owned
-- by the domain-entity layer, not resolved by changing types in this file.)
--
-- SOURCE LINEAGE (retained read-only under legacy/**): VSAM DEFINE CLUSTER jobs
-- legacy/jcl/{ACCTFILE,CARDFILE,CUSTFILE,XREFFILE,TRANFILE,TCATBALF,TRANCATG,
-- TRANTYPE,DISCGRP,DUSRSECJ}.jcl (-> primary key + record length), copybook
-- record layouts legacy/cpy/{CVACT01Y,CVACT02Y,CVACT03Y,CVCUS01Y,CUSTREC,
-- CVTRA05Y,CVTRA01Y,CVTRA02Y,CVTRA03Y,CVTRA04Y,CSUSR01Y}.cpy (-> columns/FILLER
-- positions), and legacy/catlg/LISTCAT.txt (-> base KEYLEN/RKP).
-- =============================================================================

-- ============ 1. account  (CVACT01Y / ACCTFILE, VSAM KEYLEN 11 RKP 0, record 300B) ============
CREATE TABLE account (
    acct_id                 NUMERIC(11) PRIMARY KEY,
    acct_active_status      CHAR(1)      COLLATE "C",
    acct_curr_bal           NUMERIC(12,2),
    acct_credit_limit       NUMERIC(12,2),
    acct_cash_credit_limit  NUMERIC(12,2),
    acct_open_date          DATE,
    acct_expiraion_date     DATE,          -- [sic] COBOL misspelling ACCT-EXPIRAION-DATE preserved
    acct_reissue_date       DATE,
    acct_curr_cyc_credit    NUMERIC(12,2),
    acct_curr_cyc_debit     NUMERIC(12,2),
    acct_addr_zip           CHAR(10)     COLLATE "C",
    acct_group_id           CHAR(10)     COLLATE "C"
);
-- FILLER X(178) omitted (300B total record).

-- ============ 2. card  (CVACT02Y / CARDFILE, KEYLEN 16, record 150B) ============
CREATE TABLE card (
    card_num             CHAR(16) COLLATE "C" PRIMARY KEY,
    card_acct_id         NUMERIC(11),
    card_cvv_cd          NUMERIC(3),
    card_embossed_name   CHAR(50) COLLATE "C",
    card_expiraion_date  DATE,               -- [sic] CARD-EXPIRAION-DATE preserved
    card_active_status   CHAR(1)  COLLATE "C"
);
-- FILLER X(59) omitted (150B).

-- ============ 3. card_xref  (CVACT03Y / XREFFILE, base KEYLEN 16, record 50B) ============
CREATE TABLE card_xref (
    xref_card_num  CHAR(16) COLLATE "C",
    xref_cust_id   NUMERIC(9),
    xref_acct_id   NUMERIC(11),
    PRIMARY KEY (xref_card_num, xref_cust_id, xref_acct_id)
);
-- FILLER X(14) omitted (copybook 50B; note the ASCII seed flat file is only 36B -- see V2).

-- ============ 4. transaction  (CVTRA05Y / TRANFILE, KEYLEN 16, record 350B) ============
-- 'transaction' is a NON-RESERVED keyword in PostgreSQL and is a valid UNQUOTED identifier.
CREATE TABLE transaction (
    tran_id             CHAR(16)  COLLATE "C" PRIMARY KEY,
    tran_type_cd        CHAR(2)   COLLATE "C",
    tran_cat_cd         NUMERIC(4),
    tran_source         CHAR(10)  COLLATE "C",
    tran_desc           CHAR(100) COLLATE "C",
    tran_amt            NUMERIC(11,2),
    tran_merchant_id    NUMERIC(9),
    tran_merchant_name  CHAR(50)  COLLATE "C",
    tran_merchant_city  CHAR(50)  COLLATE "C",
    tran_merchant_zip   CHAR(10)  COLLATE "C",
    card_num            CHAR(16)  COLLATE "C",   -- TRAN- prefix DROPPED (column is card_num, NOT tran_card_num)
    tran_orig_ts        TIMESTAMP,
    tran_proc_ts        TIMESTAMP
);
-- FILLER X(20) omitted (350B).

-- ============ 5. transaction_category_balance  (CVTRA01Y / TCATBALF, KEYLEN 17, record 50B) ============
CREATE TABLE transaction_category_balance (
    trancat_acct_id  NUMERIC(11),
    trancat_type_cd  CHAR(2) COLLATE "C",
    trancat_cd       NUMERIC(4),
    tran_cat_bal     NUMERIC(11,2),
    PRIMARY KEY (trancat_acct_id, trancat_type_cd, trancat_cd)
);
-- FILLER X(22) omitted (50B).

-- ============ 6. disclosure_group  (CVTRA02Y / DISCGRP, KEYLEN 16, record 50B) ============
CREATE TABLE disclosure_group (
    dis_acct_group_id  CHAR(10) COLLATE "C",
    dis_tran_type_cd   CHAR(2)  COLLATE "C",
    dis_tran_cat_cd    NUMERIC(4),
    dis_int_rate       NUMERIC(6,2),          -- S9(4)V99
    PRIMARY KEY (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd)
);
-- FILLER X(28) omitted (50B).

-- ============ 7. transaction_type  (CVTRA03Y / TRANTYPE, KEYLEN 2, record 60B) ============
CREATE TABLE transaction_type (
    tran_type       CHAR(2)  COLLATE "C" PRIMARY KEY,
    tran_type_desc  CHAR(50) COLLATE "C"
);
-- FILLER X(8) omitted (60B).

-- ============ 8. transaction_category  (CVTRA04Y / TRANCATG, KEYLEN 6, record 60B) ============
CREATE TABLE transaction_category (
    tran_type_cd        CHAR(2) COLLATE "C",
    tran_cat_cd         NUMERIC(4),
    tran_cat_type_desc  CHAR(50) COLLATE "C",
    PRIMARY KEY (tran_type_cd, tran_cat_cd)
);
-- FILLER X(4) omitted (60B).

-- ============ 9. customer  (CVCUS01Y consolidated w/ CUSTREC / CUSTFILE, KEYLEN 9, record 500B) ============
CREATE TABLE customer (
    cust_id                   NUMERIC(9) PRIMARY KEY,
    cust_first_name           CHAR(25) COLLATE "C",
    cust_middle_name          CHAR(25) COLLATE "C",
    cust_last_name            CHAR(25) COLLATE "C",
    cust_addr_line_1          CHAR(50) COLLATE "C",
    cust_addr_line_2          CHAR(50) COLLATE "C",
    cust_addr_line_3          CHAR(50) COLLATE "C",
    cust_addr_state_cd        CHAR(2)  COLLATE "C",
    cust_addr_country_cd      CHAR(3)  COLLATE "C",
    cust_addr_zip             CHAR(10) COLLATE "C",
    cust_phone_num_1          CHAR(15) COLLATE "C",
    cust_phone_num_2          CHAR(15) COLLATE "C",
    cust_ssn                  NUMERIC(9),
    cust_govt_issued_id       CHAR(20) COLLATE "C",
    cust_dob                  DATE,
    cust_eft_account_id       CHAR(10) COLLATE "C",
    cust_pri_card_holder_ind  CHAR(1)  COLLATE "C",
    cust_fico_credit_score    NUMERIC(3)
);
-- FILLER X(168) omitted (500B). DEFCUST.jcl 10-byte-key variant is NOT adopted; canonical PK is cust_id NUMERIC(9).

-- ============ 10. user_security  (CSUSR01Y / DUSRSECJ, KEYLEN 8, record 80B) ============
CREATE TABLE user_security (
    usr_id     CHAR(8)  COLLATE "C" PRIMARY KEY,   -- SEC- prefix DROPPED
    usr_fname  CHAR(20) COLLATE "C",
    usr_lname  CHAR(20) COLLATE "C",
    usr_pwd    CHAR(8)  COLLATE "C",               -- cleartext (parity 0.6.7 -- NO hashing)
    usr_type   CHAR(1)  COLLATE "C"                -- 'A'=admin, 'U'=user
);
-- SEC-USR-FILLER X(23) omitted (80B).
