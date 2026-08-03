-- =============================================================================
-- CardDemo reference data (AAP 0.4.5 "V2__seed_reference_data.sql").
--
-- Loads the three VSAM reference files that the online and batch flows look up.
-- Every value is decoded from the human-readable ASCII fixtures in app/data/ASCII
-- (the EBCDIC binaries are out of scope) against the record layouts in
-- app/cpy/CVTRA03Y.cpy, app/cpy/CVTRA04Y.cpy and app/cpy/CVTRA02Y.cpy:
--   * tran_type        7 rows  <- trantype.txt
--   * tran_category   18 rows  <- trancatg.txt
--   * disclosure_group 51 rows <- discgrp.txt
--
-- DIS-INT-RATE is PIC S9(04)V99 held with a COBOL trailing-overpunch sign in the
-- fixture ('{' = +0 ... 'I' = +9); the decoded value is written at the exact
-- NUMERIC(6,2) scale (AAP 0.6.1) so CBACT04C's
-- (TRAN-CAT-BAL * DIS-INT-RATE) / 1200 is reproduced byte-for-byte.
-- Fixed-width key and description fields are trimmed of their COBOL blank padding,
-- so the padded fixture group ids 'DEFAULT   ' and 'ZEROAPR   ' are stored as
-- 'DEFAULT' and 'ZEROAPR'. 'DEFAULT' is the group id CBACT04C substitutes when an
-- account's own disclosure group is missing (file status 23 fallback), so the
-- trimmed form is what InterestCalculationService looks up.
--
-- ON CONFLICT DO NOTHING keeps the seed replayable: re-running the migration set
-- against a populated database is a no-op rather than a duplicate-key failure.
-- =============================================================================

-- Transaction types (CVTRA03Y TRAN-TYPE-RECORD; FILLER X(08) not persisted).
INSERT INTO tran_type (tran_type, tran_type_desc) VALUES
    ('01', 'Purchase'),
    ('02', 'Payment'),
    ('03', 'Credit'),
    ('04', 'Authorization'),
    ('05', 'Refund'),
    ('06', 'Reversal'),
    ('07', 'Adjustment')
ON CONFLICT DO NOTHING;

-- Transaction categories (CVTRA04Y TRAN-CAT-RECORD; FILLER X(04) not persisted).
INSERT INTO tran_category (tran_type_cd, tran_cat_cd, tran_cat_type_desc) VALUES
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
ON CONFLICT DO NOTHING;

-- Disclosure groups / interest rates (CVTRA02Y DIS-GROUP-RECORD; FILLER X(28) not persisted).
INSERT INTO disclosure_group (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate) VALUES
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
ON CONFLICT DO NOTHING;
