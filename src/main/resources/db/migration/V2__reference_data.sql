-- =============================================================================
-- V2__reference_data.sql
--
-- AWS CardDemo reference / seed data. Second business Flyway migration; runs
-- immediately after V1__schema.sql (which creates the tables) and before the
-- Hibernate ddl-auto=validate check at startup (AAP 0.4.1, 0.7.1).
--
-- Seeds 8 master/reference tables decoded from the legacy fixed-width ASCII
-- fixtures plus user_security from the inline IEBGENER data, giving a clean
-- local machine a fully-populated database (local-validation / Onboarding).
-- This file is fully self-contained: every value below is a literal decoded
-- from the fixtures at build/author time; NO files are read at runtime.
--
-- DECODING NOTES (parity, AAP 0.6.1 / 0.6.2 / 0.6.6):
--  * Signed money PIC S9(n)V99 packed as zoned-decimal with a trailing OVERPUNCH
--    sign byte are decoded to exact NUMERIC literals (no rounding, no float).
--  * Unsigned 9(n) id/ssn fields drop insignificant leading zeros and are stored
--    as the NUMERIC value (e.g. cust_ssn 020973888 -> 20973888).
--  * PIC X(n) text keeps its exact characters with trailing spaces trimmed;
--    PostgreSQL re-pads CHAR(n). A blank field is '' (space-fill parity), NOT NULL.
--  * ISO dates yyyy-MM-dd -> DATE literals.
--
-- SEED INTEGRITY (review finding #33): each statement is a PLAIN INSERT with NO
-- "ON CONFLICT ... DO NOTHING" masking, so a duplicate primary key or any other
-- seed error fails the migration LOUDLY instead of being silently swallowed. This
-- is safe because Flyway applies each versioned migration exactly once (tracked in
-- the flyway_schema_history table); the seed rows below are authored duplicate-free
-- on their primary keys (verified), and the test harness always migrates against a
-- freshly cleaned database (flyway.clean + migrate per test).
--
-- NOT SEEDED HERE: the 'transaction' table starts EMPTY (populated at runtime by
-- the posting batch job CBTRN02C -> PostTransactionJobConfig). The DALYTRAN feed
-- legacy/data/ASCII/dailytran.txt is a FlatFileItemReader input / test fixture,
-- not a master-table seed, and is intentionally not loaded here (AAP 0.6.4).
--
-- SOURCE LINEAGE (retained read-only under legacy/**):
--   legacy/data/ASCII/{acctdata,carddata,cardxref,custdata,discgrp,tcatbal,
--   trancatg,trantype}.txt  and inline users in legacy/jcl/DUSRSECJ.jcl.
--   Cross-referenced by docs/traceability-matrix.md (seed -> V2).
-- =============================================================================

-- ===== account (50 rows) =====
INSERT INTO account (acct_id, acct_active_status, acct_curr_bal, acct_credit_limit, acct_cash_credit_limit, acct_open_date, acct_expiraion_date, acct_reissue_date, acct_curr_cyc_credit, acct_curr_cyc_debit, acct_addr_zip, acct_group_id) VALUES
    (1, 'Y', 194.00, 2020.00, 1020.00, DATE '2014-11-20', DATE '2025-05-20', DATE '2025-05-20', 0.00, 0.00, 'A000000000', ''),
    (2, 'Y', 158.00, 6130.00, 5448.00, DATE '2013-06-19', DATE '2024-08-11', DATE '2024-08-11', 0.00, 0.00, 'A000000000', ''),
    (3, 'Y', 147.00, 4909.00, 538.00, DATE '2013-08-23', DATE '2024-01-10', DATE '2024-01-10', 0.00, 0.00, 'A000000000', ''),
    (4, 'Y', 40.00, 3503.00, 2789.00, DATE '2012-11-17', DATE '2023-12-16', DATE '2023-12-16', 0.00, 0.00, 'A000000000', ''),
    (5, 'Y', 345.00, 3819.00, 2430.00, DATE '2012-10-03', DATE '2025-03-09', DATE '2025-03-09', 0.00, 0.00, 'A000000000', ''),
    (6, 'Y', 218.00, 3584.00, 2948.00, DATE '2017-12-23', DATE '2025-10-08', DATE '2025-10-08', 0.00, 0.00, 'A000000000', ''),
    (7, 'Y', 193.00, 2065.00, 264.00, DATE '2012-10-12', DATE '2024-12-13', DATE '2024-12-13', 0.00, 0.00, 'A000000000', ''),
    (8, 'Y', 605.00, 6104.00, 1318.00, DATE '2012-01-04', DATE '2024-05-20', DATE '2024-05-20', 0.00, 0.00, 'A000000000', ''),
    (9, 'Y', 560.00, 8201.00, 2065.00, DATE '2016-08-27', DATE '2024-12-27', DATE '2024-12-27', 0.00, 0.00, 'A000000000', ''),
    (10, 'Y', 159.00, 5401.00, 4442.00, DATE '2015-09-13', DATE '2023-01-27', DATE '2023-01-27', 0.00, 0.00, 'A000000000', ''),
    (11, 'Y', 212.00, 4998.00, 3175.00, DATE '2014-09-12', DATE '2025-03-12', DATE '2025-03-12', 0.00, 0.00, 'A000000000', ''),
    (12, 'Y', 176.00, 4636.00, 388.00, DATE '2009-06-17', DATE '2023-07-07', DATE '2023-07-07', 0.00, 0.00, 'A000000000', ''),
    (13, 'Y', 41.00, 7542.00, 4922.00, DATE '2017-10-01', DATE '2024-08-04', DATE '2024-08-04', 0.00, 0.00, 'A000000000', ''),
    (14, 'Y', 15.00, 2254.00, 212.00, DATE '2010-12-04', DATE '2025-12-11', DATE '2025-12-11', 0.00, 0.00, 'A000000000', ''),
    (15, 'Y', 489.00, 8441.00, 3833.00, DATE '2009-10-06', DATE '2025-06-09', DATE '2025-06-09', 0.00, 0.00, 'A000000000', ''),
    (16, 'Y', 733.00, 8922.00, 2632.00, DATE '2014-09-11', DATE '2024-01-25', DATE '2024-01-25', 0.00, 0.00, 'A000000000', ''),
    (17, 'Y', 33.00, 568.00, 510.00, DATE '2014-05-17', DATE '2025-03-01', DATE '2025-03-01', 0.00, 0.00, 'A000000000', ''),
    (18, 'Y', 144.00, 2903.00, 1496.00, DATE '2018-11-15', DATE '2023-09-10', DATE '2023-09-10', 0.00, 0.00, 'A000000000', ''),
    (19, 'Y', 480.00, 6986.00, 3723.00, DATE '2011-12-14', DATE '2025-07-23', DATE '2025-07-23', 0.00, 0.00, 'A000000000', ''),
    (20, 'Y', 369.00, 3767.00, 1040.00, DATE '2014-02-27', DATE '2024-03-13', DATE '2024-03-13', 0.00, 0.00, 'A000000000', ''),
    (21, 'Y', 112.00, 1264.00, 180.00, DATE '2011-10-19', DATE '2023-01-06', DATE '2023-01-06', 0.00, 0.00, 'A000000000', ''),
    (22, 'Y', 55.00, 8599.00, 4712.00, DATE '2016-11-21', DATE '2025-12-28', DATE '2025-12-28', 0.00, 0.00, 'A000000000', ''),
    (23, 'Y', 104.00, 3377.00, 2904.00, DATE '2012-03-15', DATE '2025-03-18', DATE '2025-03-18', 0.00, 0.00, 'A000000000', ''),
    (24, 'Y', 400.00, 5174.00, 4129.00, DATE '2015-08-08', DATE '2025-02-11', DATE '2025-02-11', 0.00, 0.00, 'A000000000', ''),
    (25, 'Y', 61.00, 8194.00, 6582.00, DATE '2012-10-26', DATE '2025-07-10', DATE '2025-07-10', 0.00, 0.00, 'A000000000', ''),
    (26, 'Y', 46.00, 2181.00, 1375.00, DATE '2009-04-20', DATE '2024-12-19', DATE '2024-12-19', 0.00, 0.00, 'A000000000', ''),
    (27, 'Y', 284.00, 5572.00, 2075.00, DATE '2012-09-30', DATE '2025-07-13', DATE '2025-07-13', 0.00, 0.00, 'A000000000', ''),
    (28, 'Y', 68.00, 868.00, 547.00, DATE '2015-05-20', DATE '2024-05-09', DATE '2024-05-09', 0.00, 0.00, 'A000000000', ''),
    (29, 'Y', 339.00, 5511.00, 4361.00, DATE '2015-11-03', DATE '2024-06-04', DATE '2024-06-04', 0.00, 0.00, 'A000000000', ''),
    (30, 'Y', 2.00, 120.00, 93.00, DATE '2011-08-26', DATE '2024-06-27', DATE '2024-06-27', 0.00, 0.00, 'A000000000', ''),
    (31, 'Y', 31.00, 1140.00, 1077.00, DATE '2017-02-25', DATE '2025-06-08', DATE '2025-06-08', 0.00, 0.00, 'A000000000', ''),
    (32, 'Y', 30.00, 1175.00, 846.00, DATE '2013-11-10', DATE '2025-05-19', DATE '2025-05-19', 0.00, 0.00, 'A000000000', ''),
    (33, 'Y', 410.00, 6404.00, 951.00, DATE '2012-10-11', DATE '2025-10-07', DATE '2025-10-07', 0.00, 0.00, 'A000000000', ''),
    (34, 'Y', 253.00, 3642.00, 2770.00, DATE '2009-05-10', DATE '2025-10-06', DATE '2025-10-06', 0.00, 0.00, 'A000000000', ''),
    (35, 'Y', 166.00, 1947.00, 1525.00, DATE '2018-02-02', DATE '2025-09-23', DATE '2025-09-23', 0.00, 0.00, 'A000000000', ''),
    (36, 'Y', 110.00, 3328.00, 839.00, DATE '2018-07-18', DATE '2024-12-23', DATE '2024-12-23', 0.00, 0.00, 'A000000000', ''),
    (37, 'Y', 7.00, 446.00, 166.00, DATE '2016-09-10', DATE '2023-10-24', DATE '2023-10-24', 0.00, 0.00, 'A000000000', ''),
    (38, 'Y', 612.00, 6505.00, 3476.00, DATE '2010-08-12', DATE '2023-07-23', DATE '2023-07-23', 0.00, 0.00, 'A000000000', ''),
    (39, 'Y', 843.00, 9750.00, 6212.00, DATE '2018-08-26', DATE '2025-09-08', DATE '2025-09-08', 0.00, 0.00, 'A000000000', ''),
    (40, 'Y', 43.00, 5823.00, 1674.00, DATE '2010-02-13', DATE '2023-10-27', DATE '2023-10-27', 0.00, 0.00, 'A000000000', ''),
    (41, 'Y', 375.00, 6721.00, 3429.00, DATE '2015-02-07', DATE '2023-04-24', DATE '2023-04-24', 0.00, 0.00, 'A000000000', ''),
    (42, 'Y', 302.00, 6563.00, 5103.00, DATE '2016-09-19', DATE '2025-09-19', DATE '2025-09-19', 0.00, 0.00, 'A000000000', ''),
    (43, 'Y', 610.00, 6168.00, 1206.00, DATE '2012-04-09', DATE '2025-08-29', DATE '2025-08-29', 0.00, 0.00, 'A000000000', ''),
    (44, 'Y', 263.00, 6899.00, 4432.00, DATE '2018-12-01', DATE '2024-01-17', DATE '2024-01-17', 0.00, 0.00, 'A000000000', ''),
    (45, 'Y', 186.00, 2719.00, 688.00, DATE '2010-12-31', DATE '2025-07-09', DATE '2025-07-09', 0.00, 0.00, 'A000000000', ''),
    (46, 'Y', 396.00, 7007.00, 5438.00, DATE '2013-09-06', DATE '2025-06-20', DATE '2025-06-20', 0.00, 0.00, 'A000000000', ''),
    (47, 'Y', 32.00, 2338.00, 159.00, DATE '2014-04-03', DATE '2025-08-23', DATE '2025-08-23', 0.00, 0.00, 'A000000000', ''),
    (48, 'Y', 226.00, 2306.00, 612.00, DATE '2017-03-18', DATE '2025-02-06', DATE '2025-02-06', 0.00, 0.00, 'A000000000', ''),
    (49, 'Y', 100.00, 9048.00, 4807.00, DATE '2019-04-06', DATE '2023-09-17', DATE '2023-09-17', 0.00, 0.00, 'A000000000', ''),
    (50, 'Y', 492.00, 6169.00, 4587.00, DATE '2011-04-22', DATE '2023-03-09', DATE '2023-03-09', 0.00, 0.00, 'A000000000', '');

-- ===== card (50 rows) =====
INSERT INTO card (card_num, card_acct_id, card_cvv_cd, card_embossed_name, card_expiraion_date, card_active_status) VALUES
    ('0500024453765740', 50, 747, 'Aniya Von', DATE '2023-03-09', 'Y'),
    ('0683586198171516', 27, 567, 'Ward Jones', DATE '2025-07-13', 'Y'),
    ('0923877193247330', 2, 28, 'Enrico Rosenbaum', DATE '2024-08-11', 'Y'),
    ('0927987108636232', 20, 3, 'Carter Veum', DATE '2024-03-13', 'Y'),
    ('0982496213629795', 12, 75, 'Maci Robel', DATE '2023-07-07', 'Y'),
    ('1014086565224350', 44, 640, 'Irving Emard', DATE '2024-01-17', 'Y'),
    ('1142167692878931', 37, 625, 'Shany Walker', DATE '2023-10-24', 'Y'),
    ('1561409106491600', 35, 31, 'Angelica Dach', DATE '2025-09-23', 'Y'),
    ('2745303720002090', 39, 33, 'Aliyah Berge', DATE '2025-09-08', 'Y'),
    ('2760836797107565', 24, 859, 'Stefanie Dickinson', DATE '2025-02-11', 'Y'),
    ('2871968252812490', 6, 775, 'Ignacio Douglas', DATE '2025-10-08', 'Y'),
    ('2940139362300449', 22, 876, 'Allene Brown', DATE '2025-12-28', 'Y'),
    ('2988091353094312', 4, 795, 'Delbert Parisian', DATE '2023-12-16', 'Y'),
    ('3260763612337560', 10, 342, 'Maybell Mann', DATE '2023-01-27', 'Y'),
    ('3766281984155154', 41, 622, 'Lucinda Dach', DATE '2023-04-24', 'Y'),
    ('3940246016141489', 19, 375, 'Hadley Hamill', DATE '2025-07-23', 'Y'),
    ('3999169246375885', 3, 317, 'Larry Homenick', DATE '2024-01-10', 'Y'),
    ('4011500891777367', 13, 390, 'Mariane Fadel', DATE '2024-08-04', 'Y'),
    ('4385271476627819', 34, 709, 'Faustino Schmidt', DATE '2025-10-06', 'Y'),
    ('4534784102713951', 36, 644, 'Toney Gerhold', DATE '2024-12-23', 'Y'),
    ('4859452612877065', 7, 321, 'Cooper Mayert', DATE '2024-12-13', 'Y'),
    ('5407099850479866', 21, 524, 'Jerrold Maggio', DATE '2023-01-06', 'Y'),
    ('5656830544981216', 46, 196, 'Cindy Cremin', DATE '2025-06-20', 'Y'),
    ('5671184478505844', 18, 137, 'Emile White', DATE '2023-09-10', 'Y'),
    ('5787351228879339', 47, 67, 'Rigoberto Hoeger', DATE '2025-08-23', 'Y'),
    ('5975117516616077', 42, 426, 'Heather Nienow', DATE '2025-09-19', 'Y'),
    ('6009619150674526', 5, 21, 'Treva Schowalter', DATE '2025-03-09', 'Y'),
    ('6349250331648509', 15, 735, 'Aubree Hermann', DATE '2025-06-09', 'Y'),
    ('6503535181795992', 48, 413, 'Lyric Pacocha', DATE '2025-02-06', 'Y'),
    ('6509230362553816', 30, 236, 'Layla Ullrich', DATE '2024-06-27', 'Y'),
    ('6723000463207764', 28, 486, 'Hester Hane', DATE '2024-05-09', 'Y'),
    ('6727055190616014', 16, 641, 'Carroll Bergstrom', DATE '2024-01-25', 'Y'),
    ('6832676047698087', 33, 983, 'Bernice Herman', DATE '2025-10-07', 'Y'),
    ('7026637615032277', 31, 920, 'Lucious O''Connell', DATE '2025-06-08', 'Y'),
    ('7058267261837752', 43, 401, 'Britney Waters', DATE '2025-08-29', 'Y'),
    ('7094142751055551', 32, 659, 'Stephany Fisher', DATE '2025-05-19', 'Y'),
    ('7251508149188883', 29, 717, 'Rickie Daugherty', DATE '2024-06-04', 'Y'),
    ('7379335634661142', 45, 134, 'Dixie Beier', DATE '2025-07-09', 'Y'),
    ('7427684863423209', 11, 892, 'Hayden Pfannerstill', DATE '2025-03-12', 'Y'),
    ('7443870988897530', 38, 708, 'Angela Ankunding', DATE '2023-07-23', 'Y'),
    ('8040580410348680', 26, 971, 'Marjory Stracke', DATE '2024-12-19', 'Y'),
    ('8112545834239735', 23, 440, 'Johnson Ruecker', DATE '2025-03-18', 'Y'),
    ('8262593602473076', 49, 457, 'Immanuel Bednar', DATE '2023-09-17', 'Y'),
    ('8517866958206008', 14, 955, 'Chelsea Marks', DATE '2025-12-11', 'Y'),
    ('8931369351894783', 8, 230, 'Kelsie Dicki', DATE '2024-05-20', 'Y'),
    ('9056297931664011', 25, 931, 'Elliott Howell', DATE '2025-07-10', 'Y'),
    ('9349107475869214', 17, 218, 'Sigrid Mann', DATE '2025-03-01', 'Y'),
    ('9501733721429893', 9, 725, 'Melvin Ondricka', DATE '2024-12-27', 'Y'),
    ('9680294154603697', 1, 45, 'Immanuel Kessler', DATE '2025-05-20', 'Y'),
    ('9805583408996588', 40, 908, 'Davon Emmerich', DATE '2023-10-27', 'Y');

-- ===== card_xref (50 rows) =====
INSERT INTO card_xref (xref_card_num, xref_cust_id, xref_acct_id) VALUES
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

-- ===== customer (50 rows) =====
INSERT INTO customer (cust_id, cust_first_name, cust_middle_name, cust_last_name, cust_addr_line_1, cust_addr_line_2, cust_addr_line_3, cust_addr_state_cd, cust_addr_country_cd, cust_addr_zip, cust_phone_num_1, cust_phone_num_2, cust_ssn, cust_govt_issued_id, cust_dob, cust_eft_account_id, cust_pri_card_holder_ind, cust_fico_credit_score) VALUES
    (1, 'Immanuel', 'Madeline', 'Kessler', '618 Deshaun Route', 'Apt. 802', 'Altenwerthshire', 'NC', 'USA', '12546', '(908)119-8310', '(373)693-8684', 20973888, '00000000000049368437', DATE '1961-06-08', '0053581756', 'Y', 274),
    (2, 'Enrico', 'April', 'Rosenbaum', '4917 Myrna Flats', 'Apt. 453', 'West Bernita', 'IN', 'USA', '22770', '(429)706-9510', '(744)950-5272', 587518382, '00000000000506210371', DATE '1961-10-08', '0069194009', 'Y', 268),
    (3, 'Larry', 'Cody', 'Homenick', '362 Esta Parks', 'Apt. 390', 'New Gladys', 'GA', 'USA', '19852-6716', '(950)396-9024', '(685)168-8826', 317460867, '00000000000052419303', DATE '1987-11-30', '0006465789', 'Y', 616),
    (4, 'Delbert', 'Kaia', 'Parisian', '638 Blanda Gateway', 'Apt. 076', 'Lake Virginie', 'MI', 'USA', '39035-0455', '(801)603-4121', '(156)074-6837', 660354258, '00000000000068579249', DATE '1985-01-13', '0040802739', 'Y', 776),
    (5, 'Treva', 'Manley', 'Schowalter', '5653 Legros Plaza', 'Apt. 968', 'Alvinaport', 'MI', 'USA', '02251-1698', '(978)775-4633', '(439)943-7644', 611264288, '00000000000639799754', DATE '1971-09-29', '0006365573', 'Y', 529),
    (6, 'Ignacio', 'Emery', 'Douglas', '3963 Yasmin Port', 'Suite 756', 'Port Josephstad', 'VI', 'USA', '46713-5148', '(277)743-4266', '(519)010-8739', 880329521, '00000000000975535496', DATE '1994-11-29', '0067163009', 'Y', 753),
    (7, 'Cooper', 'Dennis', 'Mayert', '6490 Zakary Locks', 'Apt. 765', 'Madieport', 'AL', 'USA', '34206-2974', '(698)282-4096', '(458)199-0016', 835138951, '00000000000959013170', DATE '1977-05-06', '0024571415', 'Y', 499),
    (8, 'Kelsie', 'Jordyn', 'Dicki', '0925 Welch Streets', 'Apt. 152', 'North Nanniestad', 'SC', 'USA', '27610', '(345)563-7159', '(443)197-1271', 295270759, '00000000000109746991', DATE '1964-03-25', '0033132723', 'Y', 51),
    (9, 'Melvin', 'Regan', 'Ondricka', '87893 Samson Flats', 'Apt. 135', 'New Braden', 'VI', 'USA', '21113', '(035)456-1404', '(412)440-3130', 842035847, '00000000000568299451', DATE '1975-11-07', '0039446039', 'Y', 699),
    (10, 'Maybell', 'Creola', 'Mann', '77933 Adah Dale', 'Suite 343', 'Andersonfurt', 'CT', 'USA', '44803-4279', '(614)594-2619', '(667)057-0235', 754755746, '00000000000212824755', DATE '1980-06-11', '0093803568', 'Y', 476),
    (11, 'Hayden', 'Ressie', 'Pfannerstill', '14895 Everette Ridges', 'Apt. 443', 'Julianneburgh', 'WA', 'USA', '24984', '(002)533-6980', '(553)586-7718', 493538586, '00000000000111190855', DATE '1986-11-03', '0002650577', 'Y', 209),
    (12, 'Maci', 'Alan', 'Robel', '80501 Isac Cliffs', 'Suite 623', 'Predovicton', 'MN', 'USA', '78861', '(584)045-5200', '(610)244-0407', 666114218, '00000000000902143351', DATE '1984-02-18', '0061317348', 'Y', 688),
    (13, 'Mariane', 'Oma', 'Fadel', '2689 Derick Mission', 'Suite 055', 'Bruenfurt', 'OR', 'USA', '02322', '(875)943-7287', '(075)550-6435', 757924569, '00000000000181377220', DATE '1999-03-09', '0044807431', 'Y', 53),
    (14, 'Chelsea', 'Ignacio', 'Marks', '747 Dino Lodge', 'Apt. 850', 'West Chase', 'RI', 'USA', '12914-8465', '(141)807-6571', '(284)088-9052', 655128548, '00000000000525955222', DATE '1974-11-29', '0048306401', 'Y', 243),
    (15, 'Aubree', 'Elliot', 'Hermann', '36365 Ledner Drives', 'Suite 882', 'Port Efrainland', 'DE', 'USA', '63205-7014', '(769)100-7971', '(366)310-2061', 33922034, '00000000000230369941', DATE '1964-12-06', '0000634612', 'Y', 681),
    (16, 'Carroll', 'Cicero', 'Bergstrom', '06988 Thiel Falls', 'Suite 148', 'Concepcionland', 'VT', 'USA', '84390', '(631)343-8667', '(938)648-3716', 649827971, '00000000000293265752', DATE '1983-04-27', '0012556599', 'Y', 326),
    (17, 'Sigrid', 'Angeline', 'Mann', '95666 Dare Isle', 'Suite 286', 'New Presley', 'FM', 'USA', '56181-0584', '(087)314-2070', '(541)003-6606', 303334693, '00000000000497606357', DATE '1979-01-26', '0052356071', 'Y', 54),
    (18, 'Emile', 'Jairo', 'White', '133 Bergnaum Square', 'Apt. 328', 'Hansenville', 'AP', 'USA', '96003-5867', '(303)654-3323', '(520)186-2176', 385849271, '00000000000088341821', DATE '1987-03-25', '0086459831', 'Y', 340),
    (19, 'Hadley', 'Sigrid', 'Hamill', '6273 Ondricka Meadows', 'Apt. 130', 'New Arturoshire', 'RI', 'USA', '48161', '(817)452-4986', '(724)901-6019', 439569907, '00000000000270176387', DATE '1991-01-07', '0036492057', 'Y', 259),
    (20, 'Carter', 'Oren', 'Veum', '5845 Allison Valleys', 'Suite 934', 'Mitchellmouth', 'MH', 'USA', '72362', '(618)994-0531', '(571)695-4136', 717778238, '00000000000342661293', DATE '1996-04-14', '0036749754', 'Y', 493),
    (21, 'Jerrold', 'Adolphus', 'Maggio', '401 Haylie Crest', 'Apt. 320', 'North Myrnaton', 'CA', 'USA', '72407', '(399)526-3254', '(326)193-1118', 336490822, '00000000000027656260', DATE '1977-11-15', '0011744660', 'Y', 163),
    (22, 'Allene', 'Icie', 'Brown', '4467 Donnie Crossroad', 'Apt. 437', 'Anabelton', 'MD', 'USA', '01993-9116', '(231)251-5792', '(494)652-0009', 292059024, '00000000000691159853', DATE '1994-02-20', '0024791470', 'Y', 597),
    (23, 'Johnson', 'Blanca', 'Ruecker', '2433 Jacobi Forks', 'Apt. 845', 'Hendersonbury', 'KS', 'USA', '78239-9466', '(981)873-1589', '(131)638-5974', 944154289, '00000000000268967122', DATE '1998-12-07', '0075158529', 'Y', 337),
    (24, 'Stefanie', 'Verla', 'Dickinson', '6367 Stracke River', 'Apt. 444', 'East Otho', 'KS', 'USA', '15414', '(617)348-9142', '(330)116-5634', 17590544, '00000000000439244633', DATE '1996-01-24', '0005459662', 'Y', 711),
    (25, 'Elliott', 'Fermin', 'Howell', '9524 McKenzie Lakes', 'Suite 245', 'West Alexa', 'NH', 'USA', '75721-7382', '(092)336-8599', '(311)969-1460', 788820436, '00000000000548223048', DATE '1989-03-27', '0032297533', 'Y', 355),
    (26, 'Marjory', 'Damien', 'Stracke', '30161 Bogan Canyon', 'Suite 916', 'Walshberg', 'IL', 'USA', '59945', '(584)772-2867', '(819)733-9809', 840478806, '00000000000947411626', DATE '1990-03-17', '0060808858', 'Y', 1),
    (27, 'Ward', 'Henri', 'Jones', '210 Amaya Turnpike', 'Suite 180', 'Port Dwight', 'GU', 'USA', '07923-8822', '(935)027-1145', '(103)537-5007', 980161210, '00000000000881558757', DATE '1986-11-08', '0050024139', 'Y', 78),
    (28, 'Hester', 'Vesta', 'Hane', '06816 Ursula Meadows', 'Suite 605', 'South Aurore', 'AS', 'USA', '77442-7954', '(122)357-7257', '(050)352-6579', 677986013, '00000000000514187796', DATE '1991-06-05', '0026946180', 'Y', 114),
    (29, 'Rickie', 'Otho', 'Daugherty', '676 Funk Curve', 'Apt. 375', 'Hayesstad', 'NH', 'USA', '01226', '(418)291-9023', '(795)634-7776', 15027332, '00000000000062745655', DATE '1973-04-05', '0067736493', 'Y', 552),
    (30, 'Layla', 'Dannie', 'Ullrich', '269 Eleazar Circle', 'Apt. 817', 'Kutchland', 'AK', 'USA', '64266', '(330)408-6966', '(413)347-7306', 866102152, '00000000000492021686', DATE '1965-11-28', '0050520060', 'Y', 133),
    (31, 'Lucious', 'Otto', 'O''Connell', '919 Swift Valleys', 'Suite 548', 'Hermanborough', 'MS', 'USA', '56133-5636', '(259)414-9625', '(118)946-9264', 357462348, '00000000000618310539', DATE '1976-08-03', '0092999757', 'Y', 58),
    (32, 'Stephany', 'Meda', 'Fisher', '63452 Kenny Streets', 'Apt. 116', 'Predovicburgh', 'AK', 'USA', '85943-7605', '(202)436-5156', '(246)296-3533', 146204208, '00000000000206200341', DATE '1980-11-19', '0035970593', 'Y', 221),
    (33, 'Bernice', 'Norbert', 'Herman', '877 Kassandra Ranch', 'Suite 956', 'Haleyport', 'AR', 'USA', '19113-4329', '(836)743-5487', '(640)208-1176', 144195105, '00000000000400605429', DATE '1988-05-19', '0065245171', 'Y', 469),
    (34, 'Faustino', 'Jess', 'Schmidt', '44132 Michel Square', 'Suite 007', 'South Margarettaburgh', 'ME', 'USA', '49544-2869', '(179)036-5135', '(986)905-0112', 548088300, '00000000000159882533', DATE '1994-03-21', '0067445089', 'Y', 104),
    (35, 'Angelica', 'Damaris', 'Dach', '396 Pearl Loop', 'Suite 383', 'Pfefferhaven', 'LA', 'USA', '46142', '(303)480-9098', '(637)710-7367', 220547115, '00000000000977144839', DATE '1987-06-23', '0047435332', 'Y', 793),
    (36, 'Toney', 'Emerald', 'Gerhold', '35943 Raleigh Harbor', 'Apt. 116', 'Lake Derekburgh', 'AL', 'USA', '10932-0480', '(034)271-9180', '(507)529-4523', 420360688, '00000000000942029210', DATE '1991-03-31', '0066461979', 'Y', 266),
    (37, 'Shany', 'Darby', 'Walker', '91196 Heaney Turnpike', 'Suite 814', 'Lubowitzberg', 'NV', 'USA', '11857-8177', '(052)759-5167', '(706)896-1282', 891897974, '00000000000524312632', DATE '1984-12-09', '0066111704', 'Y', 653),
    (38, 'Angela', 'Ceasar', 'Ankunding', '65482 Zoila Skyway', 'Apt. 054', 'East Malachi', 'VA', 'USA', '63928-0008', '(316)640-2650', '(148)111-1148', 764307306, '00000000000335562141', DATE '1990-05-28', '0018048939', 'Y', 446),
    (39, 'Aliyah', 'Horace', 'Berge', '5761 Pasquale Trail', 'Apt. 616', 'New Sabryna', 'IA', 'USA', '74267', '(089)096-3287', '(768)959-4733', 510793388, '00000000000553254403', DATE '1972-08-26', '0061869530', 'Y', 475),
    (40, 'Davon', 'Demond', 'Emmerich', '23499 Beer Views', 'Suite 816', 'Erniechester', 'TX', 'USA', '87156-8689', '(463)762-3017', '(419)414-2177', 54960660, '00000000000398353299', DATE '1992-01-26', '0087069976', 'Y', 284),
    (41, 'Lucinda', 'Kiana', 'Dach', '3220 Yolanda Corner', 'Suite 649', 'East Harmonystad', 'VT', 'USA', '72971-7481', '(284)052-5831', '(091)234-2144', 643942675, '00000000000919653442', DATE '1967-02-20', '0007315287', 'Y', 725),
    (42, 'Heather', 'Ericka', 'Nienow', '5523 Archibald Club', 'Apt. 358', 'Reillyland', 'FM', 'USA', '83589', '(640)954-4538', '(565)873-6897', 800455633, '00000000000997029966', DATE '1964-11-03', '0079262985', 'Y', 44),
    (43, 'Britney', 'Jermain', 'Waters', '97765 Bernhard Fort', 'Apt. 666', 'South Marisaview', 'OK', 'USA', '10050-7980', '(407)042-6952', '(438)659-6397', 262568593, '00000000000244555805', DATE '1966-10-16', '0053043599', 'Y', 558),
    (44, 'Irving', 'Kiera', 'Emard', '978 Fatima Stream', 'Apt. 110', 'Lake King', 'ID', 'USA', '05704-0501', '(703)484-5840', '(537)392-5569', 318104527, '00000000000934420974', DATE '1984-04-04', '0032076778', 'Y', 145),
    (45, 'Dixie', 'Norris', 'Beier', '441 Levi Prairie', 'Suite 749', 'Abbottshire', 'NV', 'USA', '09048', '(697)143-3221', '(499)287-7255', 352819961, '00000000000885743286', DATE '2001-12-12', '0027833000', 'Y', 629),
    (46, 'Cindy', 'Kira', 'Cremin', '494 Lang Avenue', 'Apt. 937', 'Alexandroview', 'PW', 'USA', '63082-4520', '(358)349-2574', '(077)525-9966', 656405528, '00000000000762699577', DATE '1987-12-14', '0017535749', 'Y', 514),
    (47, 'Rigoberto', 'Savanna', 'Hoeger', '00097 Gleichner Spur', 'Apt. 932', 'Port Aidanborough', 'GU', 'USA', '31329-6973', '(946)322-6160', '(973)443-8438', 29222192, '00000000000567601472', DATE '1979-02-25', '0022102472', 'Y', 722),
    (48, 'Lyric', 'Mackenzie', 'Pacocha', '453 Rosina Mountain', 'Apt. 011', 'Albertville', 'OR', 'USA', '83985-4937', '(950)497-1005', '(004)244-7955', 635734407, '00000000000265392832', DATE '1986-08-17', '0046317382', 'Y', 746),
    (49, 'Immanuel', 'Ellie', 'Bednar', '5423 Esther Locks', 'Apt. 142', 'Langoshstad', 'GA', 'USA', '12288-3495', '(843)095-2553', '(615)988-9038', 813044111, '00000000000424495981', DATE '2000-01-05', '0058726120', 'Y', 148),
    (50, 'Aniya', 'Alba', 'Von', '1588 Nienow Cape', 'Suite 187', 'New Aricchester', 'OR', 'USA', '04257', '(325)301-0827', '(493)985-9283', 931248469, '00000000000030387824', DATE '1960-12-01', '0074883577', 'Y', 623);

-- ===== disclosure_group (51 rows) =====
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
    ('ZEROAPR', '07', 1, 0.00);

-- ===== transaction_category_balance (50 rows) =====
INSERT INTO transaction_category_balance (trancat_acct_id, trancat_type_cd, trancat_cd, tran_cat_bal) VALUES
    (1, '01', 1, 0.00),
    (2, '01', 1, 0.00),
    (3, '01', 1, 0.00),
    (4, '01', 1, 0.00),
    (5, '01', 1, 0.00),
    (6, '01', 1, 0.00),
    (7, '01', 1, 0.00),
    (8, '01', 1, 0.00),
    (9, '01', 1, 0.00),
    (10, '01', 1, 0.00),
    (11, '01', 1, 0.00),
    (12, '01', 1, 0.00),
    (13, '01', 1, 0.00),
    (14, '01', 1, 0.00),
    (15, '01', 1, 0.00),
    (16, '01', 1, 0.00),
    (17, '01', 1, 0.00),
    (18, '01', 1, 0.00),
    (19, '01', 1, 0.00),
    (20, '01', 1, 0.00),
    (21, '01', 1, 0.00),
    (22, '01', 1, 0.00),
    (23, '01', 1, 0.00),
    (24, '01', 1, 0.00),
    (25, '01', 1, 0.00),
    (26, '01', 1, 0.00),
    (27, '01', 1, 0.00),
    (28, '01', 1, 0.00),
    (29, '01', 1, 0.00),
    (30, '01', 1, 0.00),
    (31, '01', 1, 0.00),
    (32, '01', 1, 0.00),
    (33, '01', 1, 0.00),
    (34, '01', 1, 0.00),
    (35, '01', 1, 0.00),
    (36, '01', 1, 0.00),
    (37, '01', 1, 0.00),
    (38, '01', 1, 0.00),
    (39, '01', 1, 0.00),
    (40, '01', 1, 0.00),
    (41, '01', 1, 0.00),
    (42, '01', 1, 0.00),
    (43, '01', 1, 0.00),
    (44, '01', 1, 0.00),
    (45, '01', 1, 0.00),
    (46, '01', 1, 0.00),
    (47, '01', 1, 0.00),
    (48, '01', 1, 0.00),
    (49, '01', 1, 0.00),
    (50, '01', 1, 0.00);

-- ===== transaction_category (18 rows) =====
INSERT INTO transaction_category (tran_type_cd, tran_cat_cd, tran_cat_type_desc) VALUES
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
    ('07', 1, 'Sales draft credit adjustment');

-- ===== transaction_type (7 rows) =====
INSERT INTO transaction_type (tran_type, tran_type_desc) VALUES
    ('01', 'Purchase'),
    ('02', 'Payment'),
    ('03', 'Credit'),
    ('04', 'Authorization'),
    ('05', 'Refund'),
    ('06', 'Reversal'),
    ('07', 'Adjustment');

-- ===== user_security (10 rows) =====
-- SECURITY (review finding #5, AAP 0.7.1 "no hardcoded credentials"): the seed
-- principals' password is NOT a committed literal. It is supplied at migration
-- time by the Flyway placeholder ${carddemo_seed_password}, which application.yml
-- binds to the CARDDEMO_SEED_PASSWORD environment variable with NO committed
-- default (fail-closed, exactly like SPRING_DATASOURCE_PASSWORD). A clean checkout
-- therefore contains no reusable secret; the value is provisioned on the host
-- (see docs/onboarding.md). The cleartext COMPARISON behavior of the COBOL signon
-- (COSGN00C READ-USER-SEC-FILE) is still preserved for parity (AAP 0.6.7) by
-- CardDemoAuthenticationProvider; only the known, reusable committed credential is
-- removed. The placeholder must resolve to an all-uppercase value of at most 8
-- characters (usr_pwd is CHAR(8); the provider uppercases the entered password
-- before the fixed-width comparison).
INSERT INTO user_security (usr_id, usr_fname, usr_lname, usr_pwd, usr_type) VALUES
    ('ADMIN001', 'MARGARET', 'GOLD', '${carddemo_seed_password}', 'A'),
    ('ADMIN002', 'RUSSELL', 'RUSSELL', '${carddemo_seed_password}', 'A'),
    ('ADMIN003', 'RAYMOND', 'WHITMORE', '${carddemo_seed_password}', 'A'),
    ('ADMIN004', 'EMMANUEL', 'CASGRAIN', '${carddemo_seed_password}', 'A'),
    ('ADMIN005', 'GRANVILLE', 'LACHAPELLE', '${carddemo_seed_password}', 'A'),
    ('USER0001', 'LAWRENCE', 'THOMAS', '${carddemo_seed_password}', 'U'),
    ('USER0002', 'AJITH', 'KUMAR', '${carddemo_seed_password}', 'U'),
    ('USER0003', 'LAURITZ', 'ALME', '${carddemo_seed_password}', 'U'),
    ('USER0004', 'AVERARDO', 'MAZZI', '${carddemo_seed_password}', 'U'),
    ('USER0005', 'LEE', 'TING', '${carddemo_seed_password}', 'U');
