-- =============================================================================
-- V3__seed_reference_data.sql
-- =============================================================================
-- Purpose:      Seed reference data tables (transaction_types, transaction_categories,
--              disclosure_groups) with content from the original CardDemo ASCII
--              fixtures. The disclosure_groups table includes the critical DEFAULT
--              group_id entries that support the InterestCalculationTasklet's
--              fallback logic (PR-02).
-- Source:      app/data/ASCII/trantype.txt   (7 records, 60 bytes each)
--              app/data/ASCII/trancatg.txt   (18 records, 60 bytes each)
--              app/data/ASCII/discgrp.txt    (51 records, 50 bytes each)
-- Copybooks:   CVTRA02Y.cpy, CVTRA03Y.cpy, CVTRA04Y.cpy
-- Version:     CardDemo_v1.0-15-g27d6c6f-68 (Date: 2022-07-19)
-- Tech Spec:   §0.4.1.4 file-by-file mapping
-- AAP Rules:   PR-02 (DISCGRP DEFAULT fallback PRESERVED — DEFAULT rows critical for
--                     InterestCalculationTasklet),
--              PR-13 (record-length fidelity), PR-15 (composite keys),
--              PR-16 (BigDecimal NUMERIC(15,2) — also for NUMERIC(6,2) interest rate)
-- =============================================================================


-- =============================================================================
-- Section 1: transaction_types (7 records from trantype.txt)
-- =============================================================================
-- CVTRA03Y.cpy: TRAN-TYPE X(02) + TRAN-TYPE-DESC X(50) + FILLER X(08).
-- Simple primary key tran_type (CHAR(2)). PR-13: lengths mirror PIC clauses.
-- =============================================================================
INSERT INTO transaction_types (tran_type, type_desc) VALUES ('01', 'Purchase') ON CONFLICT (tran_type) DO NOTHING;
INSERT INTO transaction_types (tran_type, type_desc) VALUES ('02', 'Payment') ON CONFLICT (tran_type) DO NOTHING;
INSERT INTO transaction_types (tran_type, type_desc) VALUES ('03', 'Credit') ON CONFLICT (tran_type) DO NOTHING;
INSERT INTO transaction_types (tran_type, type_desc) VALUES ('04', 'Authorization') ON CONFLICT (tran_type) DO NOTHING;
INSERT INTO transaction_types (tran_type, type_desc) VALUES ('05', 'Refund') ON CONFLICT (tran_type) DO NOTHING;
INSERT INTO transaction_types (tran_type, type_desc) VALUES ('06', 'Reversal') ON CONFLICT (tran_type) DO NOTHING;
INSERT INTO transaction_types (tran_type, type_desc) VALUES ('07', 'Adjustment') ON CONFLICT (tran_type) DO NOTHING;

-- =============================================================================
-- Section 2: transaction_categories (18 records from trancatg.txt)
-- =============================================================================
-- CVTRA04Y.cpy: TRAN-TYPE-CD X(02) + TRAN-CAT-CD 9(04) + TRAN-CAT-TYPE-DESC X(50)
-- + FILLER X(04). PR-15: composite primary key (type_cd, cat_cd). cat_cd is
-- CHAR(4) to preserve leading zeros (e.g. '0001'). Referential domain for
-- tran_cat_balances (type_cd, cat_cd) seeded by V5.
-- =============================================================================
INSERT INTO transaction_categories (type_cd, cat_cd, category_desc) VALUES ('01', '0001', 'Regular Sales Draft') ON CONFLICT (type_cd, cat_cd) DO NOTHING;
INSERT INTO transaction_categories (type_cd, cat_cd, category_desc) VALUES ('01', '0002', 'Regular Cash Advance') ON CONFLICT (type_cd, cat_cd) DO NOTHING;
INSERT INTO transaction_categories (type_cd, cat_cd, category_desc) VALUES ('01', '0003', 'Convenience Check Debit') ON CONFLICT (type_cd, cat_cd) DO NOTHING;
INSERT INTO transaction_categories (type_cd, cat_cd, category_desc) VALUES ('01', '0004', 'ATM Cash Advance') ON CONFLICT (type_cd, cat_cd) DO NOTHING;
INSERT INTO transaction_categories (type_cd, cat_cd, category_desc) VALUES ('01', '0005', 'Interest Amount') ON CONFLICT (type_cd, cat_cd) DO NOTHING;
INSERT INTO transaction_categories (type_cd, cat_cd, category_desc) VALUES ('02', '0001', 'Cash payment') ON CONFLICT (type_cd, cat_cd) DO NOTHING;
INSERT INTO transaction_categories (type_cd, cat_cd, category_desc) VALUES ('02', '0002', 'Electronic payment') ON CONFLICT (type_cd, cat_cd) DO NOTHING;
INSERT INTO transaction_categories (type_cd, cat_cd, category_desc) VALUES ('02', '0003', 'Check payment') ON CONFLICT (type_cd, cat_cd) DO NOTHING;
INSERT INTO transaction_categories (type_cd, cat_cd, category_desc) VALUES ('03', '0001', 'Credit to Account') ON CONFLICT (type_cd, cat_cd) DO NOTHING;
INSERT INTO transaction_categories (type_cd, cat_cd, category_desc) VALUES ('03', '0002', 'Credit to Purchase balance') ON CONFLICT (type_cd, cat_cd) DO NOTHING;
INSERT INTO transaction_categories (type_cd, cat_cd, category_desc) VALUES ('03', '0003', 'Credit to Cash balance') ON CONFLICT (type_cd, cat_cd) DO NOTHING;
INSERT INTO transaction_categories (type_cd, cat_cd, category_desc) VALUES ('04', '0001', 'Zero dollar authorization') ON CONFLICT (type_cd, cat_cd) DO NOTHING;
INSERT INTO transaction_categories (type_cd, cat_cd, category_desc) VALUES ('04', '0002', 'Online purchase authorization') ON CONFLICT (type_cd, cat_cd) DO NOTHING;
INSERT INTO transaction_categories (type_cd, cat_cd, category_desc) VALUES ('04', '0003', 'Travel booking authorization') ON CONFLICT (type_cd, cat_cd) DO NOTHING;
INSERT INTO transaction_categories (type_cd, cat_cd, category_desc) VALUES ('05', '0001', 'Refund credit') ON CONFLICT (type_cd, cat_cd) DO NOTHING;
INSERT INTO transaction_categories (type_cd, cat_cd, category_desc) VALUES ('06', '0001', 'Fraud reversal') ON CONFLICT (type_cd, cat_cd) DO NOTHING;
INSERT INTO transaction_categories (type_cd, cat_cd, category_desc) VALUES ('06', '0002', 'Non-fraud reversal') ON CONFLICT (type_cd, cat_cd) DO NOTHING;
INSERT INTO transaction_categories (type_cd, cat_cd, category_desc) VALUES ('07', '0001', 'Sales draft credit adjustment') ON CONFLICT (type_cd, cat_cd) DO NOTHING;

-- =============================================================================
-- Section 3: disclosure_groups (51 records from discgrp.txt)
-- =============================================================================
-- CVTRA02Y.cpy: DIS-ACCT-GROUP-ID X(10) + DIS-TRAN-TYPE-CD X(02)
-- + DIS-TRAN-CAT-CD 9(04) + DIS-INT-RATE S9(04)V99 (overpunched) + FILLER X(28).
-- PR-15: composite primary key (group_id, type_cd, cat_cd).
-- PR-16: dis_int_rate stored as NUMERIC(6,2) literal with scale 2.
-- ---------------------------------------------------------------------------
-- CRITICAL PR-02: This table MUST include rows with group_id = 'DEFAULT'.
-- The InterestCalculationTasklet (CBACT04C.cbl L415-L440, AAP §0.6.11) retries
-- failed interest-rate lookups with group_id = 'DEFAULT' when the account-
-- specific group_id does not match. Missing DEFAULT rows would cause
-- DiscloseGroupNotFoundException at runtime.
-- =============================================================================

-- Block A: account-specific group 'A000000000' (17 rows)
INSERT INTO disclosure_groups (group_id, type_cd, cat_cd, dis_int_rate) VALUES ('A000000000', '01', '0001', 15.00) ON CONFLICT (group_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO disclosure_groups (group_id, type_cd, cat_cd, dis_int_rate) VALUES ('A000000000', '01', '0002', 25.00) ON CONFLICT (group_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO disclosure_groups (group_id, type_cd, cat_cd, dis_int_rate) VALUES ('A000000000', '01', '0003', 25.00) ON CONFLICT (group_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO disclosure_groups (group_id, type_cd, cat_cd, dis_int_rate) VALUES ('A000000000', '01', '0004', 25.00) ON CONFLICT (group_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO disclosure_groups (group_id, type_cd, cat_cd, dis_int_rate) VALUES ('A000000000', '02', '0001', 0.00) ON CONFLICT (group_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO disclosure_groups (group_id, type_cd, cat_cd, dis_int_rate) VALUES ('A000000000', '02', '0002', 0.00) ON CONFLICT (group_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO disclosure_groups (group_id, type_cd, cat_cd, dis_int_rate) VALUES ('A000000000', '02', '0003', 0.00) ON CONFLICT (group_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO disclosure_groups (group_id, type_cd, cat_cd, dis_int_rate) VALUES ('A000000000', '03', '0001', 0.00) ON CONFLICT (group_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO disclosure_groups (group_id, type_cd, cat_cd, dis_int_rate) VALUES ('A000000000', '03', '0002', 0.00) ON CONFLICT (group_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO disclosure_groups (group_id, type_cd, cat_cd, dis_int_rate) VALUES ('A000000000', '03', '0003', 0.00) ON CONFLICT (group_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO disclosure_groups (group_id, type_cd, cat_cd, dis_int_rate) VALUES ('A000000000', '04', '0001', 15.00) ON CONFLICT (group_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO disclosure_groups (group_id, type_cd, cat_cd, dis_int_rate) VALUES ('A000000000', '04', '0002', 15.00) ON CONFLICT (group_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO disclosure_groups (group_id, type_cd, cat_cd, dis_int_rate) VALUES ('A000000000', '04', '0003', 15.00) ON CONFLICT (group_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO disclosure_groups (group_id, type_cd, cat_cd, dis_int_rate) VALUES ('A000000000', '05', '0001', 15.00) ON CONFLICT (group_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO disclosure_groups (group_id, type_cd, cat_cd, dis_int_rate) VALUES ('A000000000', '06', '0001', 15.00) ON CONFLICT (group_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO disclosure_groups (group_id, type_cd, cat_cd, dis_int_rate) VALUES ('A000000000', '06', '0002', 15.00) ON CONFLICT (group_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO disclosure_groups (group_id, type_cd, cat_cd, dis_int_rate) VALUES ('A000000000', '07', '0001', 15.00) ON CONFLICT (group_id, type_cd, cat_cd) DO NOTHING;

-- Block B: DEFAULT group (17 rows) — CRITICAL FOR PR-02 (interest-rate fallback)
INSERT INTO disclosure_groups (group_id, type_cd, cat_cd, dis_int_rate) VALUES ('DEFAULT', '01', '0001', 15.00) ON CONFLICT (group_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO disclosure_groups (group_id, type_cd, cat_cd, dis_int_rate) VALUES ('DEFAULT', '01', '0002', 25.00) ON CONFLICT (group_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO disclosure_groups (group_id, type_cd, cat_cd, dis_int_rate) VALUES ('DEFAULT', '01', '0003', 25.00) ON CONFLICT (group_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO disclosure_groups (group_id, type_cd, cat_cd, dis_int_rate) VALUES ('DEFAULT', '01', '0004', 25.00) ON CONFLICT (group_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO disclosure_groups (group_id, type_cd, cat_cd, dis_int_rate) VALUES ('DEFAULT', '02', '0001', 0.00) ON CONFLICT (group_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO disclosure_groups (group_id, type_cd, cat_cd, dis_int_rate) VALUES ('DEFAULT', '02', '0002', 0.00) ON CONFLICT (group_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO disclosure_groups (group_id, type_cd, cat_cd, dis_int_rate) VALUES ('DEFAULT', '02', '0003', 0.00) ON CONFLICT (group_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO disclosure_groups (group_id, type_cd, cat_cd, dis_int_rate) VALUES ('DEFAULT', '03', '0001', 0.00) ON CONFLICT (group_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO disclosure_groups (group_id, type_cd, cat_cd, dis_int_rate) VALUES ('DEFAULT', '03', '0002', 0.00) ON CONFLICT (group_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO disclosure_groups (group_id, type_cd, cat_cd, dis_int_rate) VALUES ('DEFAULT', '03', '0003', 0.00) ON CONFLICT (group_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO disclosure_groups (group_id, type_cd, cat_cd, dis_int_rate) VALUES ('DEFAULT', '04', '0001', 15.00) ON CONFLICT (group_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO disclosure_groups (group_id, type_cd, cat_cd, dis_int_rate) VALUES ('DEFAULT', '04', '0002', 15.00) ON CONFLICT (group_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO disclosure_groups (group_id, type_cd, cat_cd, dis_int_rate) VALUES ('DEFAULT', '04', '0003', 15.00) ON CONFLICT (group_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO disclosure_groups (group_id, type_cd, cat_cd, dis_int_rate) VALUES ('DEFAULT', '05', '0001', 15.00) ON CONFLICT (group_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO disclosure_groups (group_id, type_cd, cat_cd, dis_int_rate) VALUES ('DEFAULT', '06', '0001', 15.00) ON CONFLICT (group_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO disclosure_groups (group_id, type_cd, cat_cd, dis_int_rate) VALUES ('DEFAULT', '06', '0002', 15.00) ON CONFLICT (group_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO disclosure_groups (group_id, type_cd, cat_cd, dis_int_rate) VALUES ('DEFAULT', '07', '0001', 0.00) ON CONFLICT (group_id, type_cd, cat_cd) DO NOTHING;

-- Block C: ZEROAPR group (17 rows) — all dis_int_rate = 0.00
INSERT INTO disclosure_groups (group_id, type_cd, cat_cd, dis_int_rate) VALUES ('ZEROAPR', '01', '0001', 0.00) ON CONFLICT (group_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO disclosure_groups (group_id, type_cd, cat_cd, dis_int_rate) VALUES ('ZEROAPR', '01', '0002', 0.00) ON CONFLICT (group_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO disclosure_groups (group_id, type_cd, cat_cd, dis_int_rate) VALUES ('ZEROAPR', '01', '0003', 0.00) ON CONFLICT (group_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO disclosure_groups (group_id, type_cd, cat_cd, dis_int_rate) VALUES ('ZEROAPR', '01', '0004', 0.00) ON CONFLICT (group_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO disclosure_groups (group_id, type_cd, cat_cd, dis_int_rate) VALUES ('ZEROAPR', '02', '0001', 0.00) ON CONFLICT (group_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO disclosure_groups (group_id, type_cd, cat_cd, dis_int_rate) VALUES ('ZEROAPR', '02', '0002', 0.00) ON CONFLICT (group_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO disclosure_groups (group_id, type_cd, cat_cd, dis_int_rate) VALUES ('ZEROAPR', '02', '0003', 0.00) ON CONFLICT (group_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO disclosure_groups (group_id, type_cd, cat_cd, dis_int_rate) VALUES ('ZEROAPR', '03', '0001', 0.00) ON CONFLICT (group_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO disclosure_groups (group_id, type_cd, cat_cd, dis_int_rate) VALUES ('ZEROAPR', '03', '0002', 0.00) ON CONFLICT (group_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO disclosure_groups (group_id, type_cd, cat_cd, dis_int_rate) VALUES ('ZEROAPR', '03', '0003', 0.00) ON CONFLICT (group_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO disclosure_groups (group_id, type_cd, cat_cd, dis_int_rate) VALUES ('ZEROAPR', '04', '0001', 0.00) ON CONFLICT (group_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO disclosure_groups (group_id, type_cd, cat_cd, dis_int_rate) VALUES ('ZEROAPR', '04', '0002', 0.00) ON CONFLICT (group_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO disclosure_groups (group_id, type_cd, cat_cd, dis_int_rate) VALUES ('ZEROAPR', '04', '0003', 0.00) ON CONFLICT (group_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO disclosure_groups (group_id, type_cd, cat_cd, dis_int_rate) VALUES ('ZEROAPR', '05', '0001', 0.00) ON CONFLICT (group_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO disclosure_groups (group_id, type_cd, cat_cd, dis_int_rate) VALUES ('ZEROAPR', '06', '0001', 0.00) ON CONFLICT (group_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO disclosure_groups (group_id, type_cd, cat_cd, dis_int_rate) VALUES ('ZEROAPR', '06', '0002', 0.00) ON CONFLICT (group_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO disclosure_groups (group_id, type_cd, cat_cd, dis_int_rate) VALUES ('ZEROAPR', '07', '0001', 0.00) ON CONFLICT (group_id, type_cd, cat_cd) DO NOTHING;
