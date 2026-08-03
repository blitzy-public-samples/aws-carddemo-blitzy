-- Authorization service, migration V2.
-- Seed rows for two of the four tables that V1__schema.sql defines.
-- Flyway 12.4.0 applies it after V1, on PostgreSQL 18.4, inside the schema that
-- spring.flyway.schemas names. Every table name below stays unqualified.

-- Each block names its source fixture under app/data/ASCII/ and the COBOL (Common
-- Business Oriented Language) record layout that fixes its field positions.
-- The same block names the Job Control Language (JCL) member that loads that
-- fixture on z/OS through the dataset utility IDCAMS.

-- Rationale for these seed rows: card-platform/docs/decision-log.md.
-- Flagged source rules: card-platform/docs/business-rule-flags.md.
-- Field-by-field source mapping, the omitted FILLER and account fields included:
-- card-platform/docs/traceability-matrix.md.

-- outbox_event and processed_event receive no row here. The authorization path
-- writes both at runtime: one outbox_event row per decision, one processed_event
-- row per event identifier already handled. The sequence transaction_id_seq keeps
-- the start value V1__schema.sql sets.


-- 50 rows from app/data/ASCII/cardxref.txt, 36 bytes per line, in fixture order
-- on the card number.
-- Fields: XREF-CARD-NUM PIC X(16) at app/cpy/CVACT03Y.cpy:L5, XREF-CUST-ID
-- PIC 9(09) at :L6, then XREF-ACCT-ID PIC 9(11) at :L7.
-- Key width from KEYS(16 0) at app/jcl/XREFFILE.jcl:L43, record width from
-- RECORDSIZE(50 50) at :L44, load step REPRO INFILE(XREFDATA) OUTFILE(XREFVSAM)
-- at :L64.
-- The trailing FILLER PIC X(14) at app/cpy/CVACT03Y.cpy:L8 holds no characters in
-- the fixture and gets no column.
-- Five card numbers open with a zero, and every card number lands as a quoted
-- 16-character value.
INSERT INTO card_xref (card_number, customer_id, account_id) VALUES
    ('0500024453765740', 50, 50),
    ('0683586198171516', 27, 27),
    ('0923877193247330', 2, 2),
    ('0927987108636232', 20, 20),
    ('0982496213629795', 12, 12),
    ('1014086565224350', 44, 44),
    ('1142167692878931', 37, 37),
    ('1561409106491600', 35, 35),
    ('2745303720002090', 39, 39),
    ('2760836797107565', 24, 24),
    ('2871968252812490', 6, 6),
    ('2940139362300449', 22, 22),
    ('2988091353094312', 4, 4),
    ('3260763612337560', 10, 10),
    ('3766281984155154', 41, 41),
    ('3940246016141489', 19, 19),
    ('3999169246375885', 3, 3),
    ('4011500891777367', 13, 13),
    ('4385271476627819', 34, 34),
    ('4534784102713951', 36, 36),
    ('4859452612877065', 7, 7),
    ('5407099850479866', 21, 21),
    ('5656830544981216', 46, 46),
    ('5671184478505844', 18, 18),
    ('5787351228879339', 47, 47),
    ('5975117516616077', 42, 42),
    ('6009619150674526', 5, 5),
    ('6349250331648509', 15, 15),
    ('6503535181795992', 48, 48),
    ('6509230362553816', 30, 30),
    ('6723000463207764', 28, 28),
    ('6727055190616014', 16, 16),
    ('6832676047698087', 33, 33),
    ('7026637615032277', 31, 31),
    ('7058267261837752', 43, 43),
    ('7094142751055551', 32, 32),
    ('7251508149188883', 29, 29),
    ('7379335634661142', 45, 45),
    ('7427684863423209', 11, 11),
    ('7443870988897530', 38, 38),
    ('8040580410348680', 26, 26),
    ('8112545834239735', 23, 23),
    ('8262593602473076', 49, 49),
    ('8517866958206008', 14, 14),
    ('8931369351894783', 8, 8),
    ('9056297931664011', 25, 25),
    ('9349107475869214', 17, 17),
    ('9501733721429893', 9, 9),
    ('9680294154603697', 1, 1),
    ('9805583408996588', 40, 40);


-- 50 rows from app/data/ASCII/acctdata.txt, 300 bytes per line, in ascending
-- account order.
-- Fields: ACCT-ID PIC 9(11) at app/cpy/CVACT01Y.cpy:L5, ACCT-CREDIT-LIMIT
-- PIC S9(10)V99 at :L8, ACCT-EXPIRAION-DATE PIC X(10) at :L11,
-- ACCT-CURR-CYC-CREDIT PIC S9(10)V99 at :L13, then ACCT-CURR-CYC-DEBIT
-- PIC S9(10)V99 at :L14.
-- Key width from KEYS(11 0) at app/jcl/ACCTFILE.jcl:L40, record width from
-- RECORDSIZE(300 300) at :L41, load step REPRO INFILE(ACCTDATA)
-- OUTFILE(ACCTVSAM) at :L61.
-- Eight of the 13 account fields get no column here, the trailing FILLER
-- PIC X(178) at app/cpy/CVACT01Y.cpy:L17 among them.

-- The fixture holds each signed amount in 12 characters. The last character
-- carries the final digit and the sign together, and all 250 decode to a positive
-- amount ending in .00.
-- Each expiry lands as a quoted 10-character value. app/cbl/CBTRN02C.cbl:L414
-- compares the field character by character against the first ten characters of a
-- transaction timestamp.
INSERT INTO account_credit_snapshot (
    account_id, credit_limit, account_expiration_date,
    current_cycle_credit, current_cycle_debit) VALUES
    (1, 2020.00, '2025-05-20', 0.00, 0.00),
    (2, 6130.00, '2024-08-11', 0.00, 0.00),
    (3, 4909.00, '2024-01-10', 0.00, 0.00),
    (4, 3503.00, '2023-12-16', 0.00, 0.00),
    (5, 3819.00, '2025-03-09', 0.00, 0.00),
    (6, 3584.00, '2025-10-08', 0.00, 0.00),
    (7, 2065.00, '2024-12-13', 0.00, 0.00),
    (8, 6104.00, '2024-05-20', 0.00, 0.00),
    (9, 8201.00, '2024-12-27', 0.00, 0.00),
    (10, 5401.00, '2023-01-27', 0.00, 0.00),
    (11, 4998.00, '2025-03-12', 0.00, 0.00),
    (12, 4636.00, '2023-07-07', 0.00, 0.00),
    (13, 7542.00, '2024-08-04', 0.00, 0.00),
    (14, 2254.00, '2025-12-11', 0.00, 0.00),
    (15, 8441.00, '2025-06-09', 0.00, 0.00),
    (16, 8922.00, '2024-01-25', 0.00, 0.00),
    (17, 568.00, '2025-03-01', 0.00, 0.00),
    (18, 2903.00, '2023-09-10', 0.00, 0.00),
    (19, 6986.00, '2025-07-23', 0.00, 0.00),
    (20, 3767.00, '2024-03-13', 0.00, 0.00),
    (21, 1264.00, '2023-01-06', 0.00, 0.00),
    (22, 8599.00, '2025-12-28', 0.00, 0.00),
    (23, 3377.00, '2025-03-18', 0.00, 0.00),
    (24, 5174.00, '2025-02-11', 0.00, 0.00),
    (25, 8194.00, '2025-07-10', 0.00, 0.00),
    (26, 2181.00, '2024-12-19', 0.00, 0.00),
    (27, 5572.00, '2025-07-13', 0.00, 0.00),
    (28, 868.00, '2024-05-09', 0.00, 0.00),
    (29, 5511.00, '2024-06-04', 0.00, 0.00),
    (30, 120.00, '2024-06-27', 0.00, 0.00),
    (31, 1140.00, '2025-06-08', 0.00, 0.00),
    (32, 1175.00, '2025-05-19', 0.00, 0.00),
    (33, 6404.00, '2025-10-07', 0.00, 0.00),
    (34, 3642.00, '2025-10-06', 0.00, 0.00),
    (35, 1947.00, '2025-09-23', 0.00, 0.00),
    (36, 3328.00, '2024-12-23', 0.00, 0.00),
    (37, 446.00, '2023-10-24', 0.00, 0.00),
    (38, 6505.00, '2023-07-23', 0.00, 0.00),
    (39, 9750.00, '2025-09-08', 0.00, 0.00),
    (40, 5823.00, '2023-10-27', 0.00, 0.00),
    (41, 6721.00, '2023-04-24', 0.00, 0.00),
    (42, 6563.00, '2025-09-19', 0.00, 0.00),
    (43, 6168.00, '2025-08-29', 0.00, 0.00),
    (44, 6899.00, '2024-01-17', 0.00, 0.00),
    (45, 2719.00, '2025-07-09', 0.00, 0.00),
    (46, 7007.00, '2025-06-20', 0.00, 0.00),
    (47, 2338.00, '2025-08-23', 0.00, 0.00),
    (48, 2306.00, '2025-02-06', 0.00, 0.00),
    (49, 9048.00, '2023-09-17', 0.00, 0.00),
    (50, 6169.00, '2023-03-09', 0.00, 0.00);
