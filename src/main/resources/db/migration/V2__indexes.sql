-- =============================================================================
-- V2__indexes.sql
-- =============================================================================
-- Purpose:      Create secondary B-tree indexes replacing the original VSAM
--              Alternate Indexes (AIX). Adds operational indexes for query
--              performance (FK lookups, role-based queries).
-- Source:      app/catlg/LISTCAT.txt (REFERENCE — VSAM catalog with AIX positions)
--              app/csd/CARDDEMO.CSD   (REFERENCE — CICS resource definitions)
-- Version:     CardDemo_v1.0-15-g27d6c6f-68 (Date: 2022-07-19)
-- Tech Spec:   §0.6.2 (VSAM-to-JPA mapping), §0.6.13 (Secondary Index Implementation),
--              §6.2 (Database Design)
-- Note:        VSAM AIX rebuild jobs (TRANIDX.jcl: DELETE→DEFINE→BLDINDEX→DEFINE PATH)
--              are NO LONGER REQUIRED. PostgreSQL maintains B-tree indexes
--              automatically on every DML operation.
-- =============================================================================


-- =============================================================================
-- Section 1: AIX-Replacement Indexes (3 indexes)
-- Replace VSAM Alternate Indexes from app/catlg/LISTCAT.txt
-- =============================================================================
-- Each index below directly replaces a VSAM Alternate Index (AIX). The original
-- AIX datasets were NONUNIQKEY (non-unique alternate keys), so each PostgreSQL
-- equivalent is a plain (non-unique) B-tree index — never CREATE UNIQUE INDEX.
-- Index names match the @Index(name = ...) values declared on the JPA entities
-- so Hibernate schema validation (spring.jpa.hibernate.ddl-auto=validate) passes.
-- =============================================================================

-- Replaces AWS.M2.CARDDEMO.CARDDATA.VSAM.AIX (AXRKP=16, KEYLEN=11 on CARD-ACCT-ID).
-- Non-unique (NONUNIQKEY): one account may own many cards. Backs the account→cards
-- lookup (CardRepository.findByAccountId, CardController GET /api/accounts/{acctId}/cards),
-- replacing the legacy STARTBR DATASET('CARDAIX') browse. Matches Card.java
-- @Index(name = "idx_card_account_id", columnList = "account_id").
CREATE INDEX IF NOT EXISTS idx_card_account_id
    ON cards (account_id);

-- Replaces AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX (AXRKP=25, KEYLEN=11 on XREF-ACCT-ID).
-- Non-unique (NONUNIQKEY): one account may have many cross-reference rows. Backs the
-- cards-by-account-via-cross-reference lookup (CardXrefRepository.findByAccountId,
-- AccountService). Matches CardXref.java
-- @Index(name = "idx_xref_account_id", columnList = "xref_acct_id").
CREATE INDEX IF NOT EXISTS idx_xref_account_id
    ON card_xref (xref_acct_id);

-- Replaces AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX (AXRKP=304, KEYLEN=26 on TRAN-ORIG-TS).
-- Non-unique (NONUNIQKEY): many transactions may share an origination timestamp. Backs
-- date-range scans (TransactionRepository.findByOrigTimestampBetween) and the CBSTM03A
-- billing-cycle fetch in StatementGenerationJobConfig. The index name retains the
-- abbreviated "orig_ts" form while indexing the V1 column orig_timestamp. Matches
-- Transaction.java @Index(name = "idx_transaction_orig_ts", columnList = "orig_timestamp").
CREATE INDEX IF NOT EXISTS idx_transaction_orig_ts
    ON transactions (orig_timestamp);


-- =============================================================================
-- Section 2: Operational Indexes (4 indexes)
-- Added for query performance — not direct AIX replacements
-- =============================================================================
-- These indexes have no VSAM AIX ancestor; they support query patterns introduced
-- by the modernized Spring Data JPA repositories and the Spring Batch flows. All are
-- plain (non-unique) B-tree indexes on non-primary-key columns.
-- =============================================================================

-- Customer→Cards lookup: locate every cross-reference owned by a customer.
-- Backs CustomerController card enumeration and BillPaymentService payment routing
-- (xref_cust_id is a non-PK foreign-key column on card_xref).
CREATE INDEX IF NOT EXISTS idx_xref_cust_id
    ON card_xref (xref_cust_id);

-- Card→Transactions lookup: locate every transaction on a specific card.
-- Backs TransactionRepository.findByCardNumber, CardController
-- GET /api/cards/{cardNum}/transactions, and CBSTM03A statement generation.
CREATE INDEX IF NOT EXISTS idx_transaction_card_num
    ON transactions (card_num);

-- Daily-transaction batch processing: speeds CBTRN02C (TransactionPostingJob) chunk
-- reads by card_num for cross-reference lookups during the POSTTRAN batch flow.
CREATE INDEX IF NOT EXISTS idx_daily_tran_card_num
    ON daily_transactions (card_num);

-- Role-based queries: fast filtering by user role ('A' = ADMIN, 'U' = USER).
-- Backs UserController.listUsersByType and admin reporting. Low cardinality (two
-- distinct values) but improves admin-screen list performance for the demo user set.
CREATE INDEX IF NOT EXISTS idx_users_sec_usr_type
    ON users (sec_usr_type);
