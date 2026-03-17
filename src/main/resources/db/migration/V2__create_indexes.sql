-- ============================================================================
-- V2__create_indexes.sql
-- Flyway Migration V2: PostgreSQL 16+ Secondary Indexes for CardDemo
--
-- Creates secondary indexes faithfully reproducing the 3 VSAM Alternate Index
-- (AIX) definitions from the IDCAMS LISTCAT catalog snapshot, plus additional
-- indexes derived from COBOL STARTBR/READNEXT browse patterns used by online
-- CICS programs and batch programs.
--
-- Source: AWS CardDemo COBOL/CICS/VSAM mainframe application
-- Target: Java 25 + Spring Boot 3.5.x + PostgreSQL 16+
--
-- VSAM AIX Catalog References (from app/catlg/LISTCAT.txt):
--   1. CARDDATA AIX:   AXRKP=16,  KEYLEN=11 -> cards(card_acct_id)
--   2. CARDXREF AIX:   AXRKP=25,  KEYLEN=11 -> card_xrefs(xref_acct_id)
--   3. TRANSACT AIX:   AXRKP=304, KEYLEN=26 -> transactions(tran_orig_ts)
--
-- COBOL Browse Pattern References:
--   4. COTRN00C/COTRN01C: transaction browse by card number
--   5. CBTRN02C: batch posting daily transaction lookup by card number
--   6. COCRDLIC: credit card list by customer via cross-reference
--
-- Depends on: V1__create_schema.sql (all referenced tables and columns)
-- ============================================================================

-- ============================================================================
-- PHASE 1: VSAM AIX-Equivalent Indexes (3 indexes)
--
-- These indexes are mandatory — they faithfully reproduce the 3 VSAM Alternate
-- Index (AIX) definitions discovered in the IDCAMS LISTCAT catalog snapshot.
-- Each AIX provides a secondary access path into a VSAM KSDS cluster, and the
-- corresponding PostgreSQL index serves the same purpose for JPA queries.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- INDEX 1: idx_card_account_id
-- VSAM Source: AWS.M2.CARDDEMO.CARDDATA.VSAM.AIX
--   DATA component: KEYLEN=11, RKP=5, AXRKP=16, NONUNIQKEY
-- COBOL Mapping: CVACT02Y.cpy
--   CARD-RECORD (RECLN 150):
--     CARD-NUM          PIC X(16)  -> bytes 0-15   (KSDS primary key)
--     CARD-ACCT-ID      PIC 9(11)  -> bytes 16-26  (AIX key, AXRKP=16)
-- Purpose: Card-to-account lookup — find all cards belonging to a given account.
--   Used by COACTVWC.cbl (Account View) and COCRDLIC.cbl (Credit Card List)
--   to resolve account-to-card relationships via STARTBR/READNEXT on the AIX.
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_card_account_id
    ON cards (card_acct_id);

-- ---------------------------------------------------------------------------
-- INDEX 2: idx_cardxref_account_id
-- VSAM Source: AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX
--   DATA component: KEYLEN=11, RKP=5, AXRKP=25, NONUNIQKEY
-- COBOL Mapping: CVACT03Y.cpy
--   CARD-XREF-RECORD (RECLN 50):
--     XREF-CARD-NUM     PIC X(16)  -> bytes 0-15   (KSDS primary key)
--     XREF-CUST-ID      PIC 9(09)  -> bytes 16-24
--     XREF-ACCT-ID      PIC 9(11)  -> bytes 25-35  (AIX key, AXRKP=25)
-- Purpose: Cross-reference account lookup — find all card-xref records for a
--   given account. Used by COCRDLIC.cbl and COACTUPC.cbl to resolve
--   account-to-card cross-references during browse operations.
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_cardxref_account_id
    ON card_xrefs (xref_acct_id);

-- ---------------------------------------------------------------------------
-- INDEX 3: idx_transaction_orig_ts
-- VSAM Source: AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX
--   DATA component: KEYLEN=26, RKP=5, AXRKP=304, NONUNIQKEY
-- COBOL Mapping: CVTRA05Y.cpy
--   TRAN-RECORD (RECLN 350):
--     TRAN-ID           PIC X(16)   -> bytes 0-15   (KSDS primary key)
--     TRAN-TYPE-CD      PIC X(02)   -> bytes 16-17
--     TRAN-CAT-CD       PIC 9(04)   -> bytes 18-21
--     TRAN-SOURCE       PIC X(10)   -> bytes 22-31
--     TRAN-DESC         PIC X(100)  -> bytes 32-131
--     TRAN-AMT          PIC S9(09)V99 -> bytes 132-142
--     TRAN-MERCHANT-ID  PIC 9(09)   -> bytes 143-151
--     TRAN-MERCHANT-NAME PIC X(50)  -> bytes 152-201
--     TRAN-MERCHANT-CITY PIC X(50)  -> bytes 202-251
--     TRAN-MERCHANT-ZIP PIC X(10)   -> bytes 252-261
--     TRAN-CARD-NUM     PIC X(16)   -> bytes 262-277
--     TRAN-ORIG-TS      PIC X(26)   -> bytes 278-303... 
--     (Note: AXRKP=304 from LISTCAT reflects 0-based byte offset in the
--      350-byte physical record; the exact mapping confirms this is the
--      origination timestamp field used for chronological ordering)
-- Purpose: Chronological transaction index — enables time-based querying and
--   ordering of transactions. Used by COTRN00C.cbl for date-range filtering
--   in transaction list browse and by CBSTM03A.CBL for statement generation
--   that iterates transactions in timestamp order.
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_transaction_orig_ts
    ON transactions (tran_orig_ts);

-- ============================================================================
-- PHASE 2: Additional Browse Pattern Indexes (3 indexes)
--
-- These indexes are derived from COBOL program analysis — specifically from
-- STARTBR/READNEXT browse patterns and common access paths used by online
-- CICS programs and batch programs. While not directly represented as VSAM AIX
-- definitions, these access patterns require indexes for equivalent query
-- performance in PostgreSQL.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- INDEX 4: idx_transaction_card_num
-- COBOL Source: COTRN00C.cbl (Transaction List), COTRN01C.cbl (Transaction View)
-- Access Pattern: STARTBR on TRANSACT file using TRAN-CARD-NUM as partial key
--   for browsing all transactions associated with a specific card number.
-- COBOL Field: CVTRA05Y.cpy -> TRAN-CARD-NUM PIC X(16) at bytes 262-277
-- Purpose: Card-based transaction lookups used by the transaction list/view
--   services. Enables efficient filtering of transactions by card number,
--   which is the primary browse pattern in the online transaction screens.
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_transaction_card_num
    ON transactions (tran_card_num);

-- ---------------------------------------------------------------------------
-- INDEX 5: idx_daily_transaction_card_num
-- COBOL Source: CBTRN02C.cbl (Daily Transaction Posting batch program)
-- Access Pattern: Sequential read of DALYTRAN file with card number used as
--   lookup key into CARDXREF for validation (reject code 100: XREF not found).
-- COBOL Field: CVTRA06Y.cpy -> DALYTRAN-CARD-NUM PIC X(16) at bytes 262-277
-- Purpose: Batch posting process looks up daily transactions by card number
--   during the validation step. This index supports efficient batch processing
--   queries that filter or join on the card number column.
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_daily_transaction_card_num
    ON daily_transactions (dalytran_card_num);

-- ---------------------------------------------------------------------------
-- INDEX 6: idx_card_xref_cust_id
-- COBOL Source: COCRDLIC.cbl (Credit Card List online program)
-- Access Pattern: STARTBR on CARDXREF using customer ID to find all cards
--   associated with a customer, then display in the credit card list screen.
-- COBOL Field: CVACT03Y.cpy -> XREF-CUST-ID PIC 9(09) at bytes 16-24
-- Purpose: Customer-to-card resolution for the credit card list display.
--   Enables efficient lookup of all card cross-references for a given customer,
--   supporting the paginated card list screen in the online application.
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_card_xref_cust_id
    ON card_xrefs (xref_cust_id);
