-- Ledger posting service, migration V2.
-- Seed rows for four of the eight tables that V1__schema.sql defines.
-- Flyway 12.4.0 applies it after V1, on PostgreSQL 18.4, inside the schema that
-- spring.flyway.schemas names. Every table name below stays unqualified.

-- Each block names its source fixture under app/data/ASCII/ and the COBOL (Common
-- Business Oriented Language) record layout that fixes its field positions.
-- The same block names the Job Control Language (JCL) member that loads that
-- fixture on z/OS through the dataset utility IDCAMS.

-- Four tables receive no row here. transaction takes its rows from consumed events,
-- and rejected_transaction, outbox_event and processed_event take theirs from the
-- posting path at runtime.
-- account_balance_projection is seeded below. Every one of its business columns is
-- NOT NULL and the posting path reads a row before it updates one, so an account with
-- no row has no balance to add a transaction amount to. The batch program never meets
-- that state: reject reason 101 at app/cbl/CBTRN02C.cbl:L397-L399 declines a
-- transaction whose account is absent, so 2800-UPDATE-ACCOUNT-REC at :L545-L560
-- always reads a record that exists.
-- 2800-UPDATE-ACCOUNT-REC creates nothing. It adds the amount, issues REWRITE, and on
-- INVALID KEY at :L555-L558 moves 109 and 'ACCOUNT RECORD NOT FOUND' into the failure
-- fields. Contrast 2700-UPDATE-TCATBAL at :L473-L499, which sets a create flag on
-- INVALID KEY and branches to 2700-A-CREATE-TCATBAL-REC. The account record has no such
-- branch, so seeding these rows reproduces the source and a create-on-miss would not.
-- A projection left empty would make the first posting of every account meet no row, and
-- neither answer available at that point is the one the source gives: the consumer would
-- either decline a transaction the batch job posts, or open the balance at zero and lose the
-- opening balance the account record carries.
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

-- 50 rows from app/data/ASCII/acctdata.txt, 300 bytes per line, in ascending account
-- order. One projection row per account, which is the state 2800-UPDATE-ACCOUNT-REC at
-- app/cbl/CBTRN02C.cbl:L545-L560 assumes when it reads the account record, adds the
-- transaction amount to the balance and adds the same amount to one of the two cycle
-- accumulators.
-- Fields: ACCT-ID PIC 9(11) at app/cpy/CVACT01Y.cpy:L5, ACCT-CURR-BAL PIC S9(10)V99 at
-- :L7, ACCT-CURR-CYC-CREDIT PIC S9(10)V99 at :L13, then ACCT-CURR-CYC-DEBIT
-- PIC S9(10)V99 at :L14.
-- Key width from KEYS(11 0) at app/jcl/ACCTFILE.jcl:L40, record width from
-- RECORDSIZE(300 300) at :L41, load step REPRO INFILE(ACCTDATA) OUTFILE(ACCTVSAM)
-- at :L61.
-- The account service owns the account record and seeds the same three values from the
-- same fixture. This table is a projection private to the ledger service, and the two
-- services hold one value per account at load time.
-- Both cycle accumulators read 0.00 in all 50 fixture records. The account service
-- exposes a cycle-close operation that returns them to that value, reproducing
-- app/cbl/CBACT04C.cbl:L353-L354.
INSERT INTO account_balance_projection
    (account_id, current_balance, cycle_credit, cycle_debit) VALUES
    ('00000000001', 194.00, 0.00, 0.00),
    ('00000000002', 158.00, 0.00, 0.00),
    ('00000000003', 147.00, 0.00, 0.00),
    ('00000000004', 40.00, 0.00, 0.00),
    ('00000000005', 345.00, 0.00, 0.00),
    ('00000000006', 218.00, 0.00, 0.00),
    ('00000000007', 193.00, 0.00, 0.00),
    ('00000000008', 605.00, 0.00, 0.00),
    ('00000000009', 560.00, 0.00, 0.00),
    ('00000000010', 159.00, 0.00, 0.00),
    ('00000000011', 212.00, 0.00, 0.00),
    ('00000000012', 176.00, 0.00, 0.00),
    ('00000000013', 41.00, 0.00, 0.00),
    ('00000000014', 15.00, 0.00, 0.00),
    ('00000000015', 489.00, 0.00, 0.00),
    ('00000000016', 733.00, 0.00, 0.00),
    ('00000000017', 33.00, 0.00, 0.00),
    ('00000000018', 144.00, 0.00, 0.00),
    ('00000000019', 480.00, 0.00, 0.00),
    ('00000000020', 369.00, 0.00, 0.00),
    ('00000000021', 112.00, 0.00, 0.00),
    ('00000000022', 55.00, 0.00, 0.00),
    ('00000000023', 104.00, 0.00, 0.00),
    ('00000000024', 400.00, 0.00, 0.00),
    ('00000000025', 61.00, 0.00, 0.00),
    ('00000000026', 46.00, 0.00, 0.00),
    ('00000000027', 284.00, 0.00, 0.00),
    ('00000000028', 68.00, 0.00, 0.00),
    ('00000000029', 339.00, 0.00, 0.00),
    ('00000000030', 2.00, 0.00, 0.00),
    ('00000000031', 31.00, 0.00, 0.00),
    ('00000000032', 30.00, 0.00, 0.00),
    ('00000000033', 410.00, 0.00, 0.00),
    ('00000000034', 253.00, 0.00, 0.00),
    ('00000000035', 166.00, 0.00, 0.00),
    ('00000000036', 110.00, 0.00, 0.00),
    ('00000000037', 7.00, 0.00, 0.00),
    ('00000000038', 612.00, 0.00, 0.00),
    ('00000000039', 843.00, 0.00, 0.00),
    ('00000000040', 43.00, 0.00, 0.00),
    ('00000000041', 375.00, 0.00, 0.00),
    ('00000000042', 302.00, 0.00, 0.00),
    ('00000000043', 610.00, 0.00, 0.00),
    ('00000000044', 263.00, 0.00, 0.00),
    ('00000000045', 186.00, 0.00, 0.00),
    ('00000000046', 396.00, 0.00, 0.00),
    ('00000000047', 32.00, 0.00, 0.00),
    ('00000000048', 226.00, 0.00, 0.00),
    ('00000000049', 100.00, 0.00, 0.00),
    ('00000000050', 492.00, 0.00, 0.00);

-- Deployment assertion. Every account carrying a category balance must carry a
-- projection row, and the fixture load must place all 50. A seed that drifts stops the
-- migration here. The alternative is a failure at the first authorized transaction for
-- an account with no row, which surfaces as a posting error far from its cause.
DO $$
DECLARE
    projection_rows INTEGER;
    accounts_without_projection INTEGER;
BEGIN
    SELECT count(*) INTO projection_rows FROM account_balance_projection;
    IF projection_rows <> 50 THEN
        RAISE EXCEPTION 'account_balance_projection holds % rows and the 50 accounts of '
            'app/data/ASCII/acctdata.txt each need one', projection_rows;
    END IF;

    SELECT count(*) INTO accounts_without_projection
        FROM (SELECT DISTINCT account_id FROM transaction_category_balance) balances
        WHERE NOT EXISTS (SELECT 1 FROM account_balance_projection projection
                          WHERE projection.account_id = balances.account_id);
    IF accounts_without_projection <> 0 THEN
        RAISE EXCEPTION '% accounts carry a category balance and no projection row',
            accounts_without_projection;
    END IF;
END
$$;
