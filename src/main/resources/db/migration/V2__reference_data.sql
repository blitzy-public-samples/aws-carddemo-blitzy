-- =============================================================================
-- V2__reference_data.sql  --  CardDemo reference/lookup data (Flyway migration V2)
-- =============================================================================
--
-- PURPOSE
--   Populate the three STATIC reference/lookup tables created by V1__schema.sql
--   with their canonical seed rows:
--       * transaction_type      (7 rows)
--       * transaction_category  (18 rows)
--       * disclosure_group      (51 rows -- interest-rate families)
--   These code tables must exist before any transaction posting or interest
--   calculation can resolve its foreign keys, so they are shipped as a
--   deterministic, versioned Flyway migration rather than as bulk CSV seed data.
--
-- EXECUTION MODEL / ORDERING
--   * Flyway applies versioned migrations in order, so this file ALWAYS runs
--     AFTER V1__schema.sql; the three target tables are therefore guaranteed to
--     already exist when these INSERTs run.
--   * Insert order is transaction_type -> transaction_category -> disclosure_group
--     so that the transaction_category.type_cd -> transaction_type foreign key
--     (defined in V1) is satisfied. disclosure_group has no foreign keys.
--   * FLYWAY IMMUTABILITY: once released this file is never edited in place.
--
-- IDEMPOTENCY
--   Every INSERT ends with ON CONFLICT (<primary-key columns>) DO NOTHING. Flyway
--   already guarantees single ordered execution, but this makes re-application /
--   local re-loads harmless and satisfies the idempotency requirement.
--
-- SOURCE DATA (relocated legacy VSAM seed files -> read as the source of truth)
--   transaction_type      <- legacy/data/ASCII/trantype.txt  (copybook CVTRA03Y.cpy, RECLN 60)
--   transaction_category  <- legacy/data/ASCII/trancatg.txt  (copybook CVTRA04Y.cpy, RECLN 60)
--   disclosure_group      <- legacy/data/ASCII/discgrp.txt   (copybook CVTRA02Y.cpy, RECLN 50)
--
-- TYPE FIDELITY WITH V1 (AAP 0.8.1 / 0.9)
--   * type_cd   VARCHAR(2)   -> quoted 2-char string, leading zero PRESERVED ('01'..'07').
--   * cat_cd    INTEGER      -> plain integer (source 9(04) leading zeros dropped: 1,2,3,4,5).
--   * int_rate  DECIMAL(6,2) -> exact decimal literal (e.g. 15.00 / 25.00 / 0.00);
--                               no floating point -- feeds the parity-critical interest
--                               formula in CBACT04C (monthlyInterest = bal * rate / 1200).
--   * group_id  VARCHAR(10)  -> TRIMMED logical value (see below).
--
-- disclosure_group int_rate DECODE (traceability -- Explainability rule)
--   discgrp.txt field [16:22] is a 6-byte COBOL zoned-decimal S9(04)V99 whose LAST
--   byte is OVERPUNCHED (it encodes both the least-significant digit AND the sign):
--       positive  { A B C D E F G H I  -> digit 0 1 2 3 4 5 6 7 8 9
--       negative  } J K L M N O P Q R  -> digit 0 1 2 3 4 5 6 7 8 9
--   The 6 magnitude digits are then read as S9(04)V99 (implied decimal before the
--   last two digits). Examples: 00150{ -> 001500 -> 0015.00 -> 15.00;
--   00250{ -> 25.00; 00000{ -> 0.00. All 51 source rows are POSITIVE.
--
-- group_id TRIMMING CONVENTION (coordination note)
--   The source stores fixed 10-char space-padded group ids ('A000000000',
--   'DEFAULT   ', 'ZEROAPR   '); the TRIMMED logical value is inserted here
--   ('A000000000','DEFAULT','ZEROAPR'). The bulk account seed (db/seed) and the
--   DisclosureGroupRepository / interest-calc lookup MUST use this same trimmed
--   convention so account.group_id matches disclosure_group.group_id. This
--   convention is recorded in docs/decision-log.md.
--
-- SCOPE
--   This migration seeds ONLY the three reference tables above. Bulk business data
--   (customer, account, card, card_xref, transaction, user_security,
--   tran_cat_balance, daily_transaction) lives in src/main/resources/db/seed/*.csv
--   and is loaded by a separate mechanism; Flyway is NOT pointed at db/seed.
--
-- No hardcoded credentials or secrets appear in this file (reference INSERTs only).
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. transaction_type  (7 rows)  <- legacy/data/ASCII/trantype.txt
--    PIC layout: TRAN-TYPE X(02) | TRAN-TYPE-DESC X(50) | FILLER X(08)
-- -----------------------------------------------------------------------------
INSERT INTO transaction_type (type_cd, type_desc) VALUES
    ('01', 'Purchase'),
    ('02', 'Payment'),
    ('03', 'Credit'),
    ('04', 'Authorization'),
    ('05', 'Refund'),
    ('06', 'Reversal'),
    ('07', 'Adjustment')
ON CONFLICT (type_cd) DO NOTHING;


-- -----------------------------------------------------------------------------
-- 2. transaction_category  (18 rows)  <- legacy/data/ASCII/trancatg.txt
--    PIC layout: TRAN-TYPE-CD X(02) | TRAN-CAT-CD 9(04) | TRAN-CAT-TYPE-DESC X(50) | FILLER X(04)
--    cat_cd is inserted as a plain INTEGER (leading zeros dropped).
-- -----------------------------------------------------------------------------
INSERT INTO transaction_category (type_cd, cat_cd, cat_type_desc) VALUES
    ('01', 1, 'Regular Sales Draft'),
    ('01', 2, 'Regular Cash Advance'),
    ('01', 3, 'Convenience Check Debit'),
    ('01', 4, 'ATM Cash Advance'),
    ('01', 5, 'Interest Amount'),
    ('02', 1, 'Cash payment'),
    ('02', 2, 'Electronic payment'),
    ('02', 3, 'Check payment'),
    ('03', 1, 'Credit to Account'),
    ('03', 2, 'Credit to Purchase balance'),
    ('03', 3, 'Credit to Cash balance'),
    ('04', 1, 'Zero dollar authorization'),
    ('04', 2, 'Online purchase authorization'),
    ('04', 3, 'Travel booking authorization'),
    ('05', 1, 'Refund credit'),
    ('06', 1, 'Fraud reversal'),
    ('06', 2, 'Non-fraud reversal'),
    ('07', 1, 'Sales draft credit adjustment')
ON CONFLICT (type_cd, cat_cd) DO NOTHING;


-- -----------------------------------------------------------------------------
-- 3. disclosure_group  (51 rows)  <- legacy/data/ASCII/discgrp.txt
--    PIC layout: DIS-ACCT-GROUP-ID X(10) | DIS-TRAN-TYPE-CD X(02) |
--                DIS-TRAN-CAT-CD 9(04) | DIS-INT-RATE S9(04)V99 (overpunched) | FILLER X(28)
--    group_id inserted TRIMMED; int_rate decoded from the overpunched field (see header).
--    NOTE: (07,1) differs by family -- 15.00 for 'A000000000' but 0.00 for 'DEFAULT';
--          every 'ZEROAPR' row is 0.00.
-- -----------------------------------------------------------------------------
INSERT INTO disclosure_group (group_id, type_cd, cat_cd, int_rate) VALUES
    -- group_id 'A000000000'
    ('A000000000', '01', 1, 15.00),
    ('A000000000', '01', 2, 25.00),
    ('A000000000', '01', 3, 25.00),
    ('A000000000', '01', 4, 25.00),
    ('A000000000', '02', 1, 0.00),
    ('A000000000', '02', 2, 0.00),
    ('A000000000', '02', 3, 0.00),
    ('A000000000', '03', 1, 0.00),
    ('A000000000', '03', 2, 0.00),
    ('A000000000', '03', 3, 0.00),
    ('A000000000', '04', 1, 15.00),
    ('A000000000', '04', 2, 15.00),
    ('A000000000', '04', 3, 15.00),
    ('A000000000', '05', 1, 15.00),
    ('A000000000', '06', 1, 15.00),
    ('A000000000', '06', 2, 15.00),
    ('A000000000', '07', 1, 15.00),
    -- group_id 'DEFAULT'
    ('DEFAULT', '01', 1, 15.00),
    ('DEFAULT', '01', 2, 25.00),
    ('DEFAULT', '01', 3, 25.00),
    ('DEFAULT', '01', 4, 25.00),
    ('DEFAULT', '02', 1, 0.00),
    ('DEFAULT', '02', 2, 0.00),
    ('DEFAULT', '02', 3, 0.00),
    ('DEFAULT', '03', 1, 0.00),
    ('DEFAULT', '03', 2, 0.00),
    ('DEFAULT', '03', 3, 0.00),
    ('DEFAULT', '04', 1, 15.00),
    ('DEFAULT', '04', 2, 15.00),
    ('DEFAULT', '04', 3, 15.00),
    ('DEFAULT', '05', 1, 15.00),
    ('DEFAULT', '06', 1, 15.00),
    ('DEFAULT', '06', 2, 15.00),
    ('DEFAULT', '07', 1, 0.00),
    -- group_id 'ZEROAPR'
    ('ZEROAPR', '01', 1, 0.00),
    ('ZEROAPR', '01', 2, 0.00),
    ('ZEROAPR', '01', 3, 0.00),
    ('ZEROAPR', '01', 4, 0.00),
    ('ZEROAPR', '02', 1, 0.00),
    ('ZEROAPR', '02', 2, 0.00),
    ('ZEROAPR', '02', 3, 0.00),
    ('ZEROAPR', '03', 1, 0.00),
    ('ZEROAPR', '03', 2, 0.00),
    ('ZEROAPR', '03', 3, 0.00),
    ('ZEROAPR', '04', 1, 0.00),
    ('ZEROAPR', '04', 2, 0.00),
    ('ZEROAPR', '04', 3, 0.00),
    ('ZEROAPR', '05', 1, 0.00),
    ('ZEROAPR', '06', 1, 0.00),
    ('ZEROAPR', '06', 2, 0.00),
    ('ZEROAPR', '07', 1, 0.00)
ON CONFLICT (group_id, type_cd, cat_cd) DO NOTHING;
