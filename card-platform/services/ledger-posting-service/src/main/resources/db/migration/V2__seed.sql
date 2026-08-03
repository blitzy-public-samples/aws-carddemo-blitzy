-- Ledger posting service, migration V2.
-- Seed rows for three of the eight tables that V1__schema.sql defines.
-- Flyway 12.4.0 applies it after V1, on PostgreSQL 18.4, inside the schema that
-- spring.flyway.schemas names. Every table name below stays unqualified.

-- Each block names its source fixture under app/data/ASCII/ and the COBOL (Common
-- Business Oriented Language) record layout that fixes its field positions.
-- The same block names the Job Control Language (JCL) member that loads that
-- fixture on z/OS through the dataset utility IDCAMS.

-- Rationale for these seed rows: card-platform/docs/decision-log.md.
-- Flagged source rules: card-platform/docs/business-rule-flags.md.
-- Field-by-field source mapping, the three omitted FILLER fields included:
-- card-platform/docs/traceability-matrix.md.

-- Five tables receive no row here. transaction and account_balance_projection take
-- their rows from consumed events. rejected_transaction, outbox_event and
-- processed_event take theirs from the posting path at runtime.
-- app/jcl/TRANFILE.jcl:L69-L70 loads the transaction master from the daily
-- transaction dataset AWS.M2.CARDDEMO.DALYTRAN.PS.INIT, through the REPRO step at
-- :L74. The source carries no separate transaction fixture.


-- 7 rows from app/data/ASCII/trantype.txt, 60 bytes per line.
-- Fields: TRAN-TYPE PIC X(02) at app/cpy/CVTRA03Y.cpy:L5, then TRAN-TYPE-DESC
-- PIC X(50) at :L6.
-- Key width from KEYS(2 0) at app/jcl/TRANTYPE.jcl:L40, record width from
-- RECORDSIZE(60 60) at :L41, load step REPRO INFILE(TRANTYPE) OUTFILE(TTYPVSAM)
-- at :L61.
-- Each description arrives padded to 50 characters and lands trimmed.
-- The trailing FILLER PIC X(08) at app/cpy/CVTRA03Y.cpy:L7 holds zero characters
-- and gets no column.
INSERT INTO transaction_type (type_code, type_description) VALUES
    ('01', 'Purchase'),
    ('02', 'Payment'),
    ('03', 'Credit'),
    ('04', 'Authorization'),
    ('05', 'Refund'),
    ('06', 'Reversal'),
    ('07', 'Adjustment');


-- 18 rows from app/data/ASCII/trancatg.txt, 60 bytes per line.
-- Fields: TRAN-TYPE-CD PIC X(02) at app/cpy/CVTRA04Y.cpy:L6, TRAN-CAT-CD PIC 9(04)
-- at :L7, then TRAN-CAT-TYPE-DESC PIC X(50) at :L8.
-- Composite key width from KEYS(6 0) at app/jcl/TRANCATG.jcl:L40, record width from
-- RECORDSIZE(60 60) at :L41, load step REPRO INFILE(TRANCATG) OUTFILE(TCATVSAM)
-- at :L61.
-- Each category code below carries the four fixture characters, leading zeros
-- included, and each description carries the fixture letter case.
-- The trailing FILLER PIC X(04) at app/cpy/CVTRA04Y.cpy:L9 holds zero characters
-- and gets no column.
INSERT INTO transaction_category (type_code, category_code, category_description) VALUES
    ('01', '0001', 'Regular Sales Draft'),
    ('01', '0002', 'Regular Cash Advance'),
    ('01', '0003', 'Convenience Check Debit'),
    ('01', '0004', 'ATM Cash Advance'),
    ('01', '0005', 'Interest Amount'),
    ('02', '0001', 'Cash payment'),
    ('02', '0002', 'Electronic payment'),
    ('02', '0003', 'Check payment'),
    ('03', '0001', 'Credit to Account'),
    ('03', '0002', 'Credit to Purchase balance'),
    ('03', '0003', 'Credit to Cash balance'),
    ('04', '0001', 'Zero dollar authorization'),
    ('04', '0002', 'Online purchase authorization'),
    ('04', '0003', 'Travel booking authorization'),
    ('05', '0001', 'Refund credit'),
    ('06', '0001', 'Fraud reversal'),
    ('06', '0002', 'Non-fraud reversal'),
    ('07', '0001', 'Sales draft credit adjustment');


-- 50 rows from app/data/ASCII/tcatbal.txt, 50 bytes per line, every balance decoding
-- to 0.00 from the trailing { overpunch.
-- Fields: TRANCAT-ACCT-ID PIC 9(11) at app/cpy/CVTRA01Y.cpy:L6, TRANCAT-TYPE-CD
-- PIC X(02) at :L7, TRANCAT-CD PIC 9(04) at :L8, then TRAN-CAT-BAL PIC S9(09)V99
-- at :L9.
-- Composite key width from KEYS(17 0) at app/jcl/TCATBALF.jcl:L40, record width from
-- RECORDSIZE(50 50) at :L41, load step REPRO INFILE(TCATBAL) OUTFILE(TCATBALV)
-- at :L61.
-- One row per account, account 00000000001 through account 00000000050, each
-- carrying type code 01 and category code 0001.
-- The trailing FILLER PIC X(22) at app/cpy/CVTRA01Y.cpy:L10 holds zero characters
-- and gets no column.
INSERT INTO transaction_category_balance
    (account_id, type_code, category_code, category_balance) VALUES
    ('00000000001', '01', '0001', 0.00),
    ('00000000002', '01', '0001', 0.00),
    ('00000000003', '01', '0001', 0.00),
    ('00000000004', '01', '0001', 0.00),
    ('00000000005', '01', '0001', 0.00),
    ('00000000006', '01', '0001', 0.00),
    ('00000000007', '01', '0001', 0.00),
    ('00000000008', '01', '0001', 0.00),
    ('00000000009', '01', '0001', 0.00),
    ('00000000010', '01', '0001', 0.00),
    ('00000000011', '01', '0001', 0.00),
    ('00000000012', '01', '0001', 0.00),
    ('00000000013', '01', '0001', 0.00),
    ('00000000014', '01', '0001', 0.00),
    ('00000000015', '01', '0001', 0.00),
    ('00000000016', '01', '0001', 0.00),
    ('00000000017', '01', '0001', 0.00),
    ('00000000018', '01', '0001', 0.00),
    ('00000000019', '01', '0001', 0.00),
    ('00000000020', '01', '0001', 0.00),
    ('00000000021', '01', '0001', 0.00),
    ('00000000022', '01', '0001', 0.00),
    ('00000000023', '01', '0001', 0.00),
    ('00000000024', '01', '0001', 0.00),
    ('00000000025', '01', '0001', 0.00),
    ('00000000026', '01', '0001', 0.00),
    ('00000000027', '01', '0001', 0.00),
    ('00000000028', '01', '0001', 0.00),
    ('00000000029', '01', '0001', 0.00),
    ('00000000030', '01', '0001', 0.00),
    ('00000000031', '01', '0001', 0.00),
    ('00000000032', '01', '0001', 0.00),
    ('00000000033', '01', '0001', 0.00),
    ('00000000034', '01', '0001', 0.00),
    ('00000000035', '01', '0001', 0.00),
    ('00000000036', '01', '0001', 0.00),
    ('00000000037', '01', '0001', 0.00),
    ('00000000038', '01', '0001', 0.00),
    ('00000000039', '01', '0001', 0.00),
    ('00000000040', '01', '0001', 0.00),
    ('00000000041', '01', '0001', 0.00),
    ('00000000042', '01', '0001', 0.00),
    ('00000000043', '01', '0001', 0.00),
    ('00000000044', '01', '0001', 0.00),
    ('00000000045', '01', '0001', 0.00),
    ('00000000046', '01', '0001', 0.00),
    ('00000000047', '01', '0001', 0.00),
    ('00000000048', '01', '0001', 0.00),
    ('00000000049', '01', '0001', 0.00),
    ('00000000050', '01', '0001', 0.00);
