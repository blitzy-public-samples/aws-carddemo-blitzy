-- =============================================================================
-- V5__seed_master_data.sql
-- =============================================================================
-- Purpose:     Seed master business data tables with content from the original
--              CardDemo ASCII fixture files.
-- Source:      app/data/ASCII/custdata.txt (50 customers, 500 bytes/record)
--              app/data/ASCII/acctdata.txt (50 accounts, 300 bytes/record)
--              app/data/ASCII/carddata.txt (50 cards, 150 bytes/record)
--              app/data/ASCII/cardxref.txt (50 cross-references, 36 bytes/record)
--              app/data/ASCII/tcatbal.txt (50 records, 50 bytes/record)
-- Copybooks:   CVCUS01Y.cpy, CVACT01Y.cpy, CVACT02Y.cpy, CVACT03Y.cpy, CVTRA01Y.cpy
-- Version:     CardDemo_v1.0-15-g27d6c6f-68 (Date: 2022-07-19)
-- Tech Spec:   §0.4.1.4 file-by-file mapping
-- AAP Rules:   PR-13 (record-length fidelity), PR-14 (expiraion → expiration),
--              PR-15 (composite keys), PR-16 (BigDecimal NUMERIC(15,2)),
--              PR-22 (version=0 on accounts/cards/customers),
--              PR-27 (preserve source data verbatim).
-- Idempotent:  Every INSERT uses ON CONFLICT (<pk>) DO NOTHING so the script is
--              safe to re-run in test/reset scenarios. No DDL, no DELETE/TRUNCATE.
-- Note (TCATBAL row count — RESOLVED, authoritative):
--              This migration seeds exactly 50 transaction-category-balance rows
--              (one per account), matching the preserved source fixture
--              app/data/ASCII/tcatbal.txt, which contains 50 records. The figure of
--              "100" appearing in AAP §0.3.1/§0.4.1.4 prose is a documentation typo.
--              Per PR-27 the preserved app/ tree is the single source of truth, and
--              the DataInitializationJobConfig batch loader reads this same 50-record
--              fixture; seeding 50 rows here keeps the Flyway seed, the batch loader,
--              and the authoritative fixture in agreement. Seeding 100 would fabricate
--              50 rows that exist in no source — violating functional parity and the
--              no-feature-additions rule — so 50 is intentional and final. There is no
--              missing data.
-- =============================================================================

-- =============================================================================
-- Section 1: Customers (50 records from custdata.txt)
-- =============================================================================
INSERT INTO customers (cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2, addr_line_3, addr_state_cd, addr_country_cd, addr_zip, phone_num_1, phone_num_2, ssn, govt_issued_id, dob, eft_account_id, pri_card_holder_ind, fico_credit_score, version)
VALUES (1, 'Immanuel', 'Madeline', 'Kessler', '618 Deshaun Route', 'Apt. 802', 'Altenwerthshire', 'NC', 'USA', '12546', '(908)119-8310', '(373)693-8684', '020973888', '00000000000049368437', '1961-06-08'::DATE, '0053581756', 'Y', 274, 0)
ON CONFLICT (cust_id) DO NOTHING;
INSERT INTO customers (cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2, addr_line_3, addr_state_cd, addr_country_cd, addr_zip, phone_num_1, phone_num_2, ssn, govt_issued_id, dob, eft_account_id, pri_card_holder_ind, fico_credit_score, version)
VALUES (2, 'Enrico', 'April', 'Rosenbaum', '4917 Myrna Flats', 'Apt. 453', 'West Bernita', 'IN', 'USA', '22770', '(429)706-9510', '(744)950-5272', '587518382', '00000000000506210371', '1961-10-08'::DATE, '0069194009', 'Y', 268, 0)
ON CONFLICT (cust_id) DO NOTHING;
INSERT INTO customers (cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2, addr_line_3, addr_state_cd, addr_country_cd, addr_zip, phone_num_1, phone_num_2, ssn, govt_issued_id, dob, eft_account_id, pri_card_holder_ind, fico_credit_score, version)
VALUES (3, 'Larry', 'Cody', 'Homenick', '362 Esta Parks', 'Apt. 390', 'New Gladys', 'GA', 'USA', '19852-6716', '(950)396-9024', '(685)168-8826', '317460867', '00000000000052419303', '1987-11-30'::DATE, '0006465789', 'Y', 616, 0)
ON CONFLICT (cust_id) DO NOTHING;
INSERT INTO customers (cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2, addr_line_3, addr_state_cd, addr_country_cd, addr_zip, phone_num_1, phone_num_2, ssn, govt_issued_id, dob, eft_account_id, pri_card_holder_ind, fico_credit_score, version)
VALUES (4, 'Delbert', 'Kaia', 'Parisian', '638 Blanda Gateway', 'Apt. 076', 'Lake Virginie', 'MI', 'USA', '39035-0455', '(801)603-4121', '(156)074-6837', '660354258', '00000000000068579249', '1985-01-13'::DATE, '0040802739', 'Y', 776, 0)
ON CONFLICT (cust_id) DO NOTHING;
INSERT INTO customers (cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2, addr_line_3, addr_state_cd, addr_country_cd, addr_zip, phone_num_1, phone_num_2, ssn, govt_issued_id, dob, eft_account_id, pri_card_holder_ind, fico_credit_score, version)
VALUES (5, 'Treva', 'Manley', 'Schowalter', '5653 Legros Plaza', 'Apt. 968', 'Alvinaport', 'MI', 'USA', '02251-1698', '(978)775-4633', '(439)943-7644', '611264288', '00000000000639799754', '1971-09-29'::DATE, '0006365573', 'Y', 529, 0)
ON CONFLICT (cust_id) DO NOTHING;
INSERT INTO customers (cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2, addr_line_3, addr_state_cd, addr_country_cd, addr_zip, phone_num_1, phone_num_2, ssn, govt_issued_id, dob, eft_account_id, pri_card_holder_ind, fico_credit_score, version)
VALUES (6, 'Ignacio', 'Emery', 'Douglas', '3963 Yasmin Port', 'Suite 756', 'Port Josephstad', 'VI', 'USA', '46713-5148', '(277)743-4266', '(519)010-8739', '880329521', '00000000000975535496', '1994-11-29'::DATE, '0067163009', 'Y', 753, 0)
ON CONFLICT (cust_id) DO NOTHING;
INSERT INTO customers (cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2, addr_line_3, addr_state_cd, addr_country_cd, addr_zip, phone_num_1, phone_num_2, ssn, govt_issued_id, dob, eft_account_id, pri_card_holder_ind, fico_credit_score, version)
VALUES (7, 'Cooper', 'Dennis', 'Mayert', '6490 Zakary Locks', 'Apt. 765', 'Madieport', 'AL', 'USA', '34206-2974', '(698)282-4096', '(458)199-0016', '835138951', '00000000000959013170', '1977-05-06'::DATE, '0024571415', 'Y', 499, 0)
ON CONFLICT (cust_id) DO NOTHING;
INSERT INTO customers (cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2, addr_line_3, addr_state_cd, addr_country_cd, addr_zip, phone_num_1, phone_num_2, ssn, govt_issued_id, dob, eft_account_id, pri_card_holder_ind, fico_credit_score, version)
VALUES (8, 'Kelsie', 'Jordyn', 'Dicki', '0925 Welch Streets', 'Apt. 152', 'North Nanniestad', 'SC', 'USA', '27610', '(345)563-7159', '(443)197-1271', '295270759', '00000000000109746991', '1964-03-25'::DATE, '0033132723', 'Y', 51, 0)
ON CONFLICT (cust_id) DO NOTHING;
INSERT INTO customers (cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2, addr_line_3, addr_state_cd, addr_country_cd, addr_zip, phone_num_1, phone_num_2, ssn, govt_issued_id, dob, eft_account_id, pri_card_holder_ind, fico_credit_score, version)
VALUES (9, 'Melvin', 'Regan', 'Ondricka', '87893 Samson Flats', 'Apt. 135', 'New Braden', 'VI', 'USA', '21113', '(035)456-1404', '(412)440-3130', '842035847', '00000000000568299451', '1975-11-07'::DATE, '0039446039', 'Y', 699, 0)
ON CONFLICT (cust_id) DO NOTHING;
INSERT INTO customers (cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2, addr_line_3, addr_state_cd, addr_country_cd, addr_zip, phone_num_1, phone_num_2, ssn, govt_issued_id, dob, eft_account_id, pri_card_holder_ind, fico_credit_score, version)
VALUES (10, 'Maybell', 'Creola', 'Mann', '77933 Adah Dale', 'Suite 343', 'Andersonfurt', 'CT', 'USA', '44803-4279', '(614)594-2619', '(667)057-0235', '754755746', '00000000000212824755', '1980-06-11'::DATE, '0093803568', 'Y', 476, 0)
ON CONFLICT (cust_id) DO NOTHING;
INSERT INTO customers (cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2, addr_line_3, addr_state_cd, addr_country_cd, addr_zip, phone_num_1, phone_num_2, ssn, govt_issued_id, dob, eft_account_id, pri_card_holder_ind, fico_credit_score, version)
VALUES (11, 'Hayden', 'Ressie', 'Pfannerstill', '14895 Everette Ridges', 'Apt. 443', 'Julianneburgh', 'WA', 'USA', '24984', '(002)533-6980', '(553)586-7718', '493538586', '00000000000111190855', '1986-11-03'::DATE, '0002650577', 'Y', 209, 0)
ON CONFLICT (cust_id) DO NOTHING;
INSERT INTO customers (cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2, addr_line_3, addr_state_cd, addr_country_cd, addr_zip, phone_num_1, phone_num_2, ssn, govt_issued_id, dob, eft_account_id, pri_card_holder_ind, fico_credit_score, version)
VALUES (12, 'Maci', 'Alan', 'Robel', '80501 Isac Cliffs', 'Suite 623', 'Predovicton', 'MN', 'USA', '78861', '(584)045-5200', '(610)244-0407', '666114218', '00000000000902143351', '1984-02-18'::DATE, '0061317348', 'Y', 688, 0)
ON CONFLICT (cust_id) DO NOTHING;
INSERT INTO customers (cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2, addr_line_3, addr_state_cd, addr_country_cd, addr_zip, phone_num_1, phone_num_2, ssn, govt_issued_id, dob, eft_account_id, pri_card_holder_ind, fico_credit_score, version)
VALUES (13, 'Mariane', 'Oma', 'Fadel', '2689 Derick Mission', 'Suite 055', 'Bruenfurt', 'OR', 'USA', '02322', '(875)943-7287', '(075)550-6435', '757924569', '00000000000181377220', '1999-03-09'::DATE, '0044807431', 'Y', 53, 0)
ON CONFLICT (cust_id) DO NOTHING;
INSERT INTO customers (cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2, addr_line_3, addr_state_cd, addr_country_cd, addr_zip, phone_num_1, phone_num_2, ssn, govt_issued_id, dob, eft_account_id, pri_card_holder_ind, fico_credit_score, version)
VALUES (14, 'Chelsea', 'Ignacio', 'Marks', '747 Dino Lodge', 'Apt. 850', 'West Chase', 'RI', 'USA', '12914-8465', '(141)807-6571', '(284)088-9052', '655128548', '00000000000525955222', '1974-11-29'::DATE, '0048306401', 'Y', 243, 0)
ON CONFLICT (cust_id) DO NOTHING;
INSERT INTO customers (cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2, addr_line_3, addr_state_cd, addr_country_cd, addr_zip, phone_num_1, phone_num_2, ssn, govt_issued_id, dob, eft_account_id, pri_card_holder_ind, fico_credit_score, version)
VALUES (15, 'Aubree', 'Elliot', 'Hermann', '36365 Ledner Drives', 'Suite 882', 'Port Efrainland', 'DE', 'USA', '63205-7014', '(769)100-7971', '(366)310-2061', '033922034', '00000000000230369941', '1964-12-06'::DATE, '0000634612', 'Y', 681, 0)
ON CONFLICT (cust_id) DO NOTHING;
INSERT INTO customers (cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2, addr_line_3, addr_state_cd, addr_country_cd, addr_zip, phone_num_1, phone_num_2, ssn, govt_issued_id, dob, eft_account_id, pri_card_holder_ind, fico_credit_score, version)
VALUES (16, 'Carroll', 'Cicero', 'Bergstrom', '06988 Thiel Falls', 'Suite 148', 'Concepcionland', 'VT', 'USA', '84390', '(631)343-8667', '(938)648-3716', '649827971', '00000000000293265752', '1983-04-27'::DATE, '0012556599', 'Y', 326, 0)
ON CONFLICT (cust_id) DO NOTHING;
INSERT INTO customers (cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2, addr_line_3, addr_state_cd, addr_country_cd, addr_zip, phone_num_1, phone_num_2, ssn, govt_issued_id, dob, eft_account_id, pri_card_holder_ind, fico_credit_score, version)
VALUES (17, 'Sigrid', 'Angeline', 'Mann', '95666 Dare Isle', 'Suite 286', 'New Presley', 'FM', 'USA', '56181-0584', '(087)314-2070', '(541)003-6606', '303334693', '00000000000497606357', '1979-01-26'::DATE, '0052356071', 'Y', 54, 0)
ON CONFLICT (cust_id) DO NOTHING;
INSERT INTO customers (cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2, addr_line_3, addr_state_cd, addr_country_cd, addr_zip, phone_num_1, phone_num_2, ssn, govt_issued_id, dob, eft_account_id, pri_card_holder_ind, fico_credit_score, version)
VALUES (18, 'Emile', 'Jairo', 'White', '133 Bergnaum Square', 'Apt. 328', 'Hansenville', 'AP', 'USA', '96003-5867', '(303)654-3323', '(520)186-2176', '385849271', '00000000000088341821', '1987-03-25'::DATE, '0086459831', 'Y', 340, 0)
ON CONFLICT (cust_id) DO NOTHING;
INSERT INTO customers (cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2, addr_line_3, addr_state_cd, addr_country_cd, addr_zip, phone_num_1, phone_num_2, ssn, govt_issued_id, dob, eft_account_id, pri_card_holder_ind, fico_credit_score, version)
VALUES (19, 'Hadley', 'Sigrid', 'Hamill', '6273 Ondricka Meadows', 'Apt. 130', 'New Arturoshire', 'RI', 'USA', '48161', '(817)452-4986', '(724)901-6019', '439569907', '00000000000270176387', '1991-01-07'::DATE, '0036492057', 'Y', 259, 0)
ON CONFLICT (cust_id) DO NOTHING;
INSERT INTO customers (cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2, addr_line_3, addr_state_cd, addr_country_cd, addr_zip, phone_num_1, phone_num_2, ssn, govt_issued_id, dob, eft_account_id, pri_card_holder_ind, fico_credit_score, version)
VALUES (20, 'Carter', 'Oren', 'Veum', '5845 Allison Valleys', 'Suite 934', 'Mitchellmouth', 'MH', 'USA', '72362', '(618)994-0531', '(571)695-4136', '717778238', '00000000000342661293', '1996-04-14'::DATE, '0036749754', 'Y', 493, 0)
ON CONFLICT (cust_id) DO NOTHING;
INSERT INTO customers (cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2, addr_line_3, addr_state_cd, addr_country_cd, addr_zip, phone_num_1, phone_num_2, ssn, govt_issued_id, dob, eft_account_id, pri_card_holder_ind, fico_credit_score, version)
VALUES (21, 'Jerrold', 'Adolphus', 'Maggio', '401 Haylie Crest', 'Apt. 320', 'North Myrnaton', 'CA', 'USA', '72407', '(399)526-3254', '(326)193-1118', '336490822', '00000000000027656260', '1977-11-15'::DATE, '0011744660', 'Y', 163, 0)
ON CONFLICT (cust_id) DO NOTHING;
INSERT INTO customers (cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2, addr_line_3, addr_state_cd, addr_country_cd, addr_zip, phone_num_1, phone_num_2, ssn, govt_issued_id, dob, eft_account_id, pri_card_holder_ind, fico_credit_score, version)
VALUES (22, 'Allene', 'Icie', 'Brown', '4467 Donnie Crossroad', 'Apt. 437', 'Anabelton', 'MD', 'USA', '01993-9116', '(231)251-5792', '(494)652-0009', '292059024', '00000000000691159853', '1994-02-20'::DATE, '0024791470', 'Y', 597, 0)
ON CONFLICT (cust_id) DO NOTHING;
INSERT INTO customers (cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2, addr_line_3, addr_state_cd, addr_country_cd, addr_zip, phone_num_1, phone_num_2, ssn, govt_issued_id, dob, eft_account_id, pri_card_holder_ind, fico_credit_score, version)
VALUES (23, 'Johnson', 'Blanca', 'Ruecker', '2433 Jacobi Forks', 'Apt. 845', 'Hendersonbury', 'KS', 'USA', '78239-9466', '(981)873-1589', '(131)638-5974', '944154289', '00000000000268967122', '1998-12-07'::DATE, '0075158529', 'Y', 337, 0)
ON CONFLICT (cust_id) DO NOTHING;
INSERT INTO customers (cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2, addr_line_3, addr_state_cd, addr_country_cd, addr_zip, phone_num_1, phone_num_2, ssn, govt_issued_id, dob, eft_account_id, pri_card_holder_ind, fico_credit_score, version)
VALUES (24, 'Stefanie', 'Verla', 'Dickinson', '6367 Stracke River', 'Apt. 444', 'East Otho', 'KS', 'USA', '15414', '(617)348-9142', '(330)116-5634', '017590544', '00000000000439244633', '1996-01-24'::DATE, '0005459662', 'Y', 711, 0)
ON CONFLICT (cust_id) DO NOTHING;
INSERT INTO customers (cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2, addr_line_3, addr_state_cd, addr_country_cd, addr_zip, phone_num_1, phone_num_2, ssn, govt_issued_id, dob, eft_account_id, pri_card_holder_ind, fico_credit_score, version)
VALUES (25, 'Elliott', 'Fermin', 'Howell', '9524 McKenzie Lakes', 'Suite 245', 'West Alexa', 'NH', 'USA', '75721-7382', '(092)336-8599', '(311)969-1460', '788820436', '00000000000548223048', '1989-03-27'::DATE, '0032297533', 'Y', 355, 0)
ON CONFLICT (cust_id) DO NOTHING;
INSERT INTO customers (cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2, addr_line_3, addr_state_cd, addr_country_cd, addr_zip, phone_num_1, phone_num_2, ssn, govt_issued_id, dob, eft_account_id, pri_card_holder_ind, fico_credit_score, version)
VALUES (26, 'Marjory', 'Damien', 'Stracke', '30161 Bogan Canyon', 'Suite 916', 'Walshberg', 'IL', 'USA', '59945', '(584)772-2867', '(819)733-9809', '840478806', '00000000000947411626', '1990-03-17'::DATE, '0060808858', 'Y', 1, 0)
ON CONFLICT (cust_id) DO NOTHING;
INSERT INTO customers (cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2, addr_line_3, addr_state_cd, addr_country_cd, addr_zip, phone_num_1, phone_num_2, ssn, govt_issued_id, dob, eft_account_id, pri_card_holder_ind, fico_credit_score, version)
VALUES (27, 'Ward', 'Henri', 'Jones', '210 Amaya Turnpike', 'Suite 180', 'Port Dwight', 'GU', 'USA', '07923-8822', '(935)027-1145', '(103)537-5007', '980161210', '00000000000881558757', '1986-11-08'::DATE, '0050024139', 'Y', 78, 0)
ON CONFLICT (cust_id) DO NOTHING;
INSERT INTO customers (cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2, addr_line_3, addr_state_cd, addr_country_cd, addr_zip, phone_num_1, phone_num_2, ssn, govt_issued_id, dob, eft_account_id, pri_card_holder_ind, fico_credit_score, version)
VALUES (28, 'Hester', 'Vesta', 'Hane', '06816 Ursula Meadows', 'Suite 605', 'South Aurore', 'AS', 'USA', '77442-7954', '(122)357-7257', '(050)352-6579', '677986013', '00000000000514187796', '1991-06-05'::DATE, '0026946180', 'Y', 114, 0)
ON CONFLICT (cust_id) DO NOTHING;
INSERT INTO customers (cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2, addr_line_3, addr_state_cd, addr_country_cd, addr_zip, phone_num_1, phone_num_2, ssn, govt_issued_id, dob, eft_account_id, pri_card_holder_ind, fico_credit_score, version)
VALUES (29, 'Rickie', 'Otho', 'Daugherty', '676 Funk Curve', 'Apt. 375', 'Hayesstad', 'NH', 'USA', '01226', '(418)291-9023', '(795)634-7776', '015027332', '00000000000062745655', '1973-04-05'::DATE, '0067736493', 'Y', 552, 0)
ON CONFLICT (cust_id) DO NOTHING;
INSERT INTO customers (cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2, addr_line_3, addr_state_cd, addr_country_cd, addr_zip, phone_num_1, phone_num_2, ssn, govt_issued_id, dob, eft_account_id, pri_card_holder_ind, fico_credit_score, version)
VALUES (30, 'Layla', 'Dannie', 'Ullrich', '269 Eleazar Circle', 'Apt. 817', 'Kutchland', 'AK', 'USA', '64266', '(330)408-6966', '(413)347-7306', '866102152', '00000000000492021686', '1965-11-28'::DATE, '0050520060', 'Y', 133, 0)
ON CONFLICT (cust_id) DO NOTHING;
INSERT INTO customers (cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2, addr_line_3, addr_state_cd, addr_country_cd, addr_zip, phone_num_1, phone_num_2, ssn, govt_issued_id, dob, eft_account_id, pri_card_holder_ind, fico_credit_score, version)
VALUES (31, 'Lucious', 'Otto', 'O''Connell', '919 Swift Valleys', 'Suite 548', 'Hermanborough', 'MS', 'USA', '56133-5636', '(259)414-9625', '(118)946-9264', '357462348', '00000000000618310539', '1976-08-03'::DATE, '0092999757', 'Y', 58, 0)
ON CONFLICT (cust_id) DO NOTHING;
INSERT INTO customers (cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2, addr_line_3, addr_state_cd, addr_country_cd, addr_zip, phone_num_1, phone_num_2, ssn, govt_issued_id, dob, eft_account_id, pri_card_holder_ind, fico_credit_score, version)
VALUES (32, 'Stephany', 'Meda', 'Fisher', '63452 Kenny Streets', 'Apt. 116', 'Predovicburgh', 'AK', 'USA', '85943-7605', '(202)436-5156', '(246)296-3533', '146204208', '00000000000206200341', '1980-11-19'::DATE, '0035970593', 'Y', 221, 0)
ON CONFLICT (cust_id) DO NOTHING;
INSERT INTO customers (cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2, addr_line_3, addr_state_cd, addr_country_cd, addr_zip, phone_num_1, phone_num_2, ssn, govt_issued_id, dob, eft_account_id, pri_card_holder_ind, fico_credit_score, version)
VALUES (33, 'Bernice', 'Norbert', 'Herman', '877 Kassandra Ranch', 'Suite 956', 'Haleyport', 'AR', 'USA', '19113-4329', '(836)743-5487', '(640)208-1176', '144195105', '00000000000400605429', '1988-05-19'::DATE, '0065245171', 'Y', 469, 0)
ON CONFLICT (cust_id) DO NOTHING;
INSERT INTO customers (cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2, addr_line_3, addr_state_cd, addr_country_cd, addr_zip, phone_num_1, phone_num_2, ssn, govt_issued_id, dob, eft_account_id, pri_card_holder_ind, fico_credit_score, version)
VALUES (34, 'Faustino', 'Jess', 'Schmidt', '44132 Michel Square', 'Suite 007', 'South Margarettaburgh', 'ME', 'USA', '49544-2869', '(179)036-5135', '(986)905-0112', '548088300', '00000000000159882533', '1994-03-21'::DATE, '0067445089', 'Y', 104, 0)
ON CONFLICT (cust_id) DO NOTHING;
INSERT INTO customers (cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2, addr_line_3, addr_state_cd, addr_country_cd, addr_zip, phone_num_1, phone_num_2, ssn, govt_issued_id, dob, eft_account_id, pri_card_holder_ind, fico_credit_score, version)
VALUES (35, 'Angelica', 'Damaris', 'Dach', '396 Pearl Loop', 'Suite 383', 'Pfefferhaven', 'LA', 'USA', '46142', '(303)480-9098', '(637)710-7367', '220547115', '00000000000977144839', '1987-06-23'::DATE, '0047435332', 'Y', 793, 0)
ON CONFLICT (cust_id) DO NOTHING;
INSERT INTO customers (cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2, addr_line_3, addr_state_cd, addr_country_cd, addr_zip, phone_num_1, phone_num_2, ssn, govt_issued_id, dob, eft_account_id, pri_card_holder_ind, fico_credit_score, version)
VALUES (36, 'Toney', 'Emerald', 'Gerhold', '35943 Raleigh Harbor', 'Apt. 116', 'Lake Derekburgh', 'AL', 'USA', '10932-0480', '(034)271-9180', '(507)529-4523', '420360688', '00000000000942029210', '1991-03-31'::DATE, '0066461979', 'Y', 266, 0)
ON CONFLICT (cust_id) DO NOTHING;
INSERT INTO customers (cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2, addr_line_3, addr_state_cd, addr_country_cd, addr_zip, phone_num_1, phone_num_2, ssn, govt_issued_id, dob, eft_account_id, pri_card_holder_ind, fico_credit_score, version)
VALUES (37, 'Shany', 'Darby', 'Walker', '91196 Heaney Turnpike', 'Suite 814', 'Lubowitzberg', 'NV', 'USA', '11857-8177', '(052)759-5167', '(706)896-1282', '891897974', '00000000000524312632', '1984-12-09'::DATE, '0066111704', 'Y', 653, 0)
ON CONFLICT (cust_id) DO NOTHING;
INSERT INTO customers (cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2, addr_line_3, addr_state_cd, addr_country_cd, addr_zip, phone_num_1, phone_num_2, ssn, govt_issued_id, dob, eft_account_id, pri_card_holder_ind, fico_credit_score, version)
VALUES (38, 'Angela', 'Ceasar', 'Ankunding', '65482 Zoila Skyway', 'Apt. 054', 'East Malachi', 'VA', 'USA', '63928-0008', '(316)640-2650', '(148)111-1148', '764307306', '00000000000335562141', '1990-05-28'::DATE, '0018048939', 'Y', 446, 0)
ON CONFLICT (cust_id) DO NOTHING;
INSERT INTO customers (cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2, addr_line_3, addr_state_cd, addr_country_cd, addr_zip, phone_num_1, phone_num_2, ssn, govt_issued_id, dob, eft_account_id, pri_card_holder_ind, fico_credit_score, version)
VALUES (39, 'Aliyah', 'Horace', 'Berge', '5761 Pasquale Trail', 'Apt. 616', 'New Sabryna', 'IA', 'USA', '74267', '(089)096-3287', '(768)959-4733', '510793388', '00000000000553254403', '1972-08-26'::DATE, '0061869530', 'Y', 475, 0)
ON CONFLICT (cust_id) DO NOTHING;
INSERT INTO customers (cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2, addr_line_3, addr_state_cd, addr_country_cd, addr_zip, phone_num_1, phone_num_2, ssn, govt_issued_id, dob, eft_account_id, pri_card_holder_ind, fico_credit_score, version)
VALUES (40, 'Davon', 'Demond', 'Emmerich', '23499 Beer Views', 'Suite 816', 'Erniechester', 'TX', 'USA', '87156-8689', '(463)762-3017', '(419)414-2177', '054960660', '00000000000398353299', '1992-01-26'::DATE, '0087069976', 'Y', 284, 0)
ON CONFLICT (cust_id) DO NOTHING;
INSERT INTO customers (cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2, addr_line_3, addr_state_cd, addr_country_cd, addr_zip, phone_num_1, phone_num_2, ssn, govt_issued_id, dob, eft_account_id, pri_card_holder_ind, fico_credit_score, version)
VALUES (41, 'Lucinda', 'Kiana', 'Dach', '3220 Yolanda Corner', 'Suite 649', 'East Harmonystad', 'VT', 'USA', '72971-7481', '(284)052-5831', '(091)234-2144', '643942675', '00000000000919653442', '1967-02-20'::DATE, '0007315287', 'Y', 725, 0)
ON CONFLICT (cust_id) DO NOTHING;
INSERT INTO customers (cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2, addr_line_3, addr_state_cd, addr_country_cd, addr_zip, phone_num_1, phone_num_2, ssn, govt_issued_id, dob, eft_account_id, pri_card_holder_ind, fico_credit_score, version)
VALUES (42, 'Heather', 'Ericka', 'Nienow', '5523 Archibald Club', 'Apt. 358', 'Reillyland', 'FM', 'USA', '83589', '(640)954-4538', '(565)873-6897', '800455633', '00000000000997029966', '1964-11-03'::DATE, '0079262985', 'Y', 44, 0)
ON CONFLICT (cust_id) DO NOTHING;
INSERT INTO customers (cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2, addr_line_3, addr_state_cd, addr_country_cd, addr_zip, phone_num_1, phone_num_2, ssn, govt_issued_id, dob, eft_account_id, pri_card_holder_ind, fico_credit_score, version)
VALUES (43, 'Britney', 'Jermain', 'Waters', '97765 Bernhard Fort', 'Apt. 666', 'South Marisaview', 'OK', 'USA', '10050-7980', '(407)042-6952', '(438)659-6397', '262568593', '00000000000244555805', '1966-10-16'::DATE, '0053043599', 'Y', 558, 0)
ON CONFLICT (cust_id) DO NOTHING;
INSERT INTO customers (cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2, addr_line_3, addr_state_cd, addr_country_cd, addr_zip, phone_num_1, phone_num_2, ssn, govt_issued_id, dob, eft_account_id, pri_card_holder_ind, fico_credit_score, version)
VALUES (44, 'Irving', 'Kiera', 'Emard', '978 Fatima Stream', 'Apt. 110', 'Lake King', 'ID', 'USA', '05704-0501', '(703)484-5840', '(537)392-5569', '318104527', '00000000000934420974', '1984-04-04'::DATE, '0032076778', 'Y', 145, 0)
ON CONFLICT (cust_id) DO NOTHING;
INSERT INTO customers (cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2, addr_line_3, addr_state_cd, addr_country_cd, addr_zip, phone_num_1, phone_num_2, ssn, govt_issued_id, dob, eft_account_id, pri_card_holder_ind, fico_credit_score, version)
VALUES (45, 'Dixie', 'Norris', 'Beier', '441 Levi Prairie', 'Suite 749', 'Abbottshire', 'NV', 'USA', '09048', '(697)143-3221', '(499)287-7255', '352819961', '00000000000885743286', '2001-12-12'::DATE, '0027833000', 'Y', 629, 0)
ON CONFLICT (cust_id) DO NOTHING;
INSERT INTO customers (cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2, addr_line_3, addr_state_cd, addr_country_cd, addr_zip, phone_num_1, phone_num_2, ssn, govt_issued_id, dob, eft_account_id, pri_card_holder_ind, fico_credit_score, version)
VALUES (46, 'Cindy', 'Kira', 'Cremin', '494 Lang Avenue', 'Apt. 937', 'Alexandroview', 'PW', 'USA', '63082-4520', '(358)349-2574', '(077)525-9966', '656405528', '00000000000762699577', '1987-12-14'::DATE, '0017535749', 'Y', 514, 0)
ON CONFLICT (cust_id) DO NOTHING;
INSERT INTO customers (cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2, addr_line_3, addr_state_cd, addr_country_cd, addr_zip, phone_num_1, phone_num_2, ssn, govt_issued_id, dob, eft_account_id, pri_card_holder_ind, fico_credit_score, version)
VALUES (47, 'Rigoberto', 'Savanna', 'Hoeger', '00097 Gleichner Spur', 'Apt. 932', 'Port Aidanborough', 'GU', 'USA', '31329-6973', '(946)322-6160', '(973)443-8438', '029222192', '00000000000567601472', '1979-02-25'::DATE, '0022102472', 'Y', 722, 0)
ON CONFLICT (cust_id) DO NOTHING;
INSERT INTO customers (cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2, addr_line_3, addr_state_cd, addr_country_cd, addr_zip, phone_num_1, phone_num_2, ssn, govt_issued_id, dob, eft_account_id, pri_card_holder_ind, fico_credit_score, version)
VALUES (48, 'Lyric', 'Mackenzie', 'Pacocha', '453 Rosina Mountain', 'Apt. 011', 'Albertville', 'OR', 'USA', '83985-4937', '(950)497-1005', '(004)244-7955', '635734407', '00000000000265392832', '1986-08-17'::DATE, '0046317382', 'Y', 746, 0)
ON CONFLICT (cust_id) DO NOTHING;
INSERT INTO customers (cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2, addr_line_3, addr_state_cd, addr_country_cd, addr_zip, phone_num_1, phone_num_2, ssn, govt_issued_id, dob, eft_account_id, pri_card_holder_ind, fico_credit_score, version)
VALUES (49, 'Immanuel', 'Ellie', 'Bednar', '5423 Esther Locks', 'Apt. 142', 'Langoshstad', 'GA', 'USA', '12288-3495', '(843)095-2553', '(615)988-9038', '813044111', '00000000000424495981', '2000-01-05'::DATE, '0058726120', 'Y', 148, 0)
ON CONFLICT (cust_id) DO NOTHING;
INSERT INTO customers (cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2, addr_line_3, addr_state_cd, addr_country_cd, addr_zip, phone_num_1, phone_num_2, ssn, govt_issued_id, dob, eft_account_id, pri_card_holder_ind, fico_credit_score, version)
VALUES (50, 'Aniya', 'Alba', 'Von', '1588 Nienow Cape', 'Suite 187', 'New Aricchester', 'OR', 'USA', '04257', '(325)301-0827', '(493)985-9283', '931248469', '00000000000030387824', '1960-12-01'::DATE, '0074883577', 'Y', 623, 0)
ON CONFLICT (cust_id) DO NOTHING;

-- =============================================================================
-- Section 2: Accounts (50 records from acctdata.txt)
-- =============================================================================
-- ANOMALY NOTE: The source acctdata.txt fixture has positions 102-111
-- (ACCT-ADDR-ZIP slot per CVACT01Y.cpy) containing values that look like
-- group-IDs (e.g., "A000000000"), while positions 112-121 (ACCT-GROUP-ID slot)
-- are blank/empty. This is a known artifact of the original CardDemo seed data
-- generation. Values are preserved verbatim per PR-27 (no business transformation):
-- addr_zip receives the literal "A000000000"; group_id (blank) becomes NULL.
-- =============================================================================
INSERT INTO accounts (acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id, version)
VALUES (1, 'Y', 194.00, 2020.00, 1020.00, '2014-11-20'::DATE, '2025-05-20'::DATE, '2025-05-20'::DATE, 0.00, 0.00, 'A000000000', NULL, 0)
ON CONFLICT (acct_id) DO NOTHING;
INSERT INTO accounts (acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id, version)
VALUES (2, 'Y', 158.00, 6130.00, 5448.00, '2013-06-19'::DATE, '2024-08-11'::DATE, '2024-08-11'::DATE, 0.00, 0.00, 'A000000000', NULL, 0)
ON CONFLICT (acct_id) DO NOTHING;
INSERT INTO accounts (acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id, version)
VALUES (3, 'Y', 147.00, 4909.00, 538.00, '2013-08-23'::DATE, '2024-01-10'::DATE, '2024-01-10'::DATE, 0.00, 0.00, 'A000000000', NULL, 0)
ON CONFLICT (acct_id) DO NOTHING;
INSERT INTO accounts (acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id, version)
VALUES (4, 'Y', 40.00, 3503.00, 2789.00, '2012-11-17'::DATE, '2023-12-16'::DATE, '2023-12-16'::DATE, 0.00, 0.00, 'A000000000', NULL, 0)
ON CONFLICT (acct_id) DO NOTHING;
INSERT INTO accounts (acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id, version)
VALUES (5, 'Y', 345.00, 3819.00, 2430.00, '2012-10-03'::DATE, '2025-03-09'::DATE, '2025-03-09'::DATE, 0.00, 0.00, 'A000000000', NULL, 0)
ON CONFLICT (acct_id) DO NOTHING;
INSERT INTO accounts (acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id, version)
VALUES (6, 'Y', 218.00, 3584.00, 2948.00, '2017-12-23'::DATE, '2025-10-08'::DATE, '2025-10-08'::DATE, 0.00, 0.00, 'A000000000', NULL, 0)
ON CONFLICT (acct_id) DO NOTHING;
INSERT INTO accounts (acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id, version)
VALUES (7, 'Y', 193.00, 2065.00, 264.00, '2012-10-12'::DATE, '2024-12-13'::DATE, '2024-12-13'::DATE, 0.00, 0.00, 'A000000000', NULL, 0)
ON CONFLICT (acct_id) DO NOTHING;
INSERT INTO accounts (acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id, version)
VALUES (8, 'Y', 605.00, 6104.00, 1318.00, '2012-01-04'::DATE, '2024-05-20'::DATE, '2024-05-20'::DATE, 0.00, 0.00, 'A000000000', NULL, 0)
ON CONFLICT (acct_id) DO NOTHING;
INSERT INTO accounts (acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id, version)
VALUES (9, 'Y', 560.00, 8201.00, 2065.00, '2016-08-27'::DATE, '2024-12-27'::DATE, '2024-12-27'::DATE, 0.00, 0.00, 'A000000000', NULL, 0)
ON CONFLICT (acct_id) DO NOTHING;
INSERT INTO accounts (acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id, version)
VALUES (10, 'Y', 159.00, 5401.00, 4442.00, '2015-09-13'::DATE, '2023-01-27'::DATE, '2023-01-27'::DATE, 0.00, 0.00, 'A000000000', NULL, 0)
ON CONFLICT (acct_id) DO NOTHING;
INSERT INTO accounts (acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id, version)
VALUES (11, 'Y', 212.00, 4998.00, 3175.00, '2014-09-12'::DATE, '2025-03-12'::DATE, '2025-03-12'::DATE, 0.00, 0.00, 'A000000000', NULL, 0)
ON CONFLICT (acct_id) DO NOTHING;
INSERT INTO accounts (acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id, version)
VALUES (12, 'Y', 176.00, 4636.00, 388.00, '2009-06-17'::DATE, '2023-07-07'::DATE, '2023-07-07'::DATE, 0.00, 0.00, 'A000000000', NULL, 0)
ON CONFLICT (acct_id) DO NOTHING;
INSERT INTO accounts (acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id, version)
VALUES (13, 'Y', 41.00, 7542.00, 4922.00, '2017-10-01'::DATE, '2024-08-04'::DATE, '2024-08-04'::DATE, 0.00, 0.00, 'A000000000', NULL, 0)
ON CONFLICT (acct_id) DO NOTHING;
INSERT INTO accounts (acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id, version)
VALUES (14, 'Y', 15.00, 2254.00, 212.00, '2010-12-04'::DATE, '2025-12-11'::DATE, '2025-12-11'::DATE, 0.00, 0.00, 'A000000000', NULL, 0)
ON CONFLICT (acct_id) DO NOTHING;
INSERT INTO accounts (acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id, version)
VALUES (15, 'Y', 489.00, 8441.00, 3833.00, '2009-10-06'::DATE, '2025-06-09'::DATE, '2025-06-09'::DATE, 0.00, 0.00, 'A000000000', NULL, 0)
ON CONFLICT (acct_id) DO NOTHING;
INSERT INTO accounts (acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id, version)
VALUES (16, 'Y', 733.00, 8922.00, 2632.00, '2014-09-11'::DATE, '2024-01-25'::DATE, '2024-01-25'::DATE, 0.00, 0.00, 'A000000000', NULL, 0)
ON CONFLICT (acct_id) DO NOTHING;
INSERT INTO accounts (acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id, version)
VALUES (17, 'Y', 33.00, 568.00, 510.00, '2014-05-17'::DATE, '2025-03-01'::DATE, '2025-03-01'::DATE, 0.00, 0.00, 'A000000000', NULL, 0)
ON CONFLICT (acct_id) DO NOTHING;
INSERT INTO accounts (acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id, version)
VALUES (18, 'Y', 144.00, 2903.00, 1496.00, '2018-11-15'::DATE, '2023-09-10'::DATE, '2023-09-10'::DATE, 0.00, 0.00, 'A000000000', NULL, 0)
ON CONFLICT (acct_id) DO NOTHING;
INSERT INTO accounts (acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id, version)
VALUES (19, 'Y', 480.00, 6986.00, 3723.00, '2011-12-14'::DATE, '2025-07-23'::DATE, '2025-07-23'::DATE, 0.00, 0.00, 'A000000000', NULL, 0)
ON CONFLICT (acct_id) DO NOTHING;
INSERT INTO accounts (acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id, version)
VALUES (20, 'Y', 369.00, 3767.00, 1040.00, '2014-02-27'::DATE, '2024-03-13'::DATE, '2024-03-13'::DATE, 0.00, 0.00, 'A000000000', NULL, 0)
ON CONFLICT (acct_id) DO NOTHING;
INSERT INTO accounts (acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id, version)
VALUES (21, 'Y', 112.00, 1264.00, 180.00, '2011-10-19'::DATE, '2023-01-06'::DATE, '2023-01-06'::DATE, 0.00, 0.00, 'A000000000', NULL, 0)
ON CONFLICT (acct_id) DO NOTHING;
INSERT INTO accounts (acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id, version)
VALUES (22, 'Y', 55.00, 8599.00, 4712.00, '2016-11-21'::DATE, '2025-12-28'::DATE, '2025-12-28'::DATE, 0.00, 0.00, 'A000000000', NULL, 0)
ON CONFLICT (acct_id) DO NOTHING;
INSERT INTO accounts (acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id, version)
VALUES (23, 'Y', 104.00, 3377.00, 2904.00, '2012-03-15'::DATE, '2025-03-18'::DATE, '2025-03-18'::DATE, 0.00, 0.00, 'A000000000', NULL, 0)
ON CONFLICT (acct_id) DO NOTHING;
INSERT INTO accounts (acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id, version)
VALUES (24, 'Y', 400.00, 5174.00, 4129.00, '2015-08-08'::DATE, '2025-02-11'::DATE, '2025-02-11'::DATE, 0.00, 0.00, 'A000000000', NULL, 0)
ON CONFLICT (acct_id) DO NOTHING;
INSERT INTO accounts (acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id, version)
VALUES (25, 'Y', 61.00, 8194.00, 6582.00, '2012-10-26'::DATE, '2025-07-10'::DATE, '2025-07-10'::DATE, 0.00, 0.00, 'A000000000', NULL, 0)
ON CONFLICT (acct_id) DO NOTHING;
INSERT INTO accounts (acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id, version)
VALUES (26, 'Y', 46.00, 2181.00, 1375.00, '2009-04-20'::DATE, '2024-12-19'::DATE, '2024-12-19'::DATE, 0.00, 0.00, 'A000000000', NULL, 0)
ON CONFLICT (acct_id) DO NOTHING;
INSERT INTO accounts (acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id, version)
VALUES (27, 'Y', 284.00, 5572.00, 2075.00, '2012-09-30'::DATE, '2025-07-13'::DATE, '2025-07-13'::DATE, 0.00, 0.00, 'A000000000', NULL, 0)
ON CONFLICT (acct_id) DO NOTHING;
INSERT INTO accounts (acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id, version)
VALUES (28, 'Y', 68.00, 868.00, 547.00, '2015-05-20'::DATE, '2024-05-09'::DATE, '2024-05-09'::DATE, 0.00, 0.00, 'A000000000', NULL, 0)
ON CONFLICT (acct_id) DO NOTHING;
INSERT INTO accounts (acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id, version)
VALUES (29, 'Y', 339.00, 5511.00, 4361.00, '2015-11-03'::DATE, '2024-06-04'::DATE, '2024-06-04'::DATE, 0.00, 0.00, 'A000000000', NULL, 0)
ON CONFLICT (acct_id) DO NOTHING;
INSERT INTO accounts (acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id, version)
VALUES (30, 'Y', 2.00, 120.00, 93.00, '2011-08-26'::DATE, '2024-06-27'::DATE, '2024-06-27'::DATE, 0.00, 0.00, 'A000000000', NULL, 0)
ON CONFLICT (acct_id) DO NOTHING;
INSERT INTO accounts (acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id, version)
VALUES (31, 'Y', 31.00, 1140.00, 1077.00, '2017-02-25'::DATE, '2025-06-08'::DATE, '2025-06-08'::DATE, 0.00, 0.00, 'A000000000', NULL, 0)
ON CONFLICT (acct_id) DO NOTHING;
INSERT INTO accounts (acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id, version)
VALUES (32, 'Y', 30.00, 1175.00, 846.00, '2013-11-10'::DATE, '2025-05-19'::DATE, '2025-05-19'::DATE, 0.00, 0.00, 'A000000000', NULL, 0)
ON CONFLICT (acct_id) DO NOTHING;
INSERT INTO accounts (acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id, version)
VALUES (33, 'Y', 410.00, 6404.00, 951.00, '2012-10-11'::DATE, '2025-10-07'::DATE, '2025-10-07'::DATE, 0.00, 0.00, 'A000000000', NULL, 0)
ON CONFLICT (acct_id) DO NOTHING;
INSERT INTO accounts (acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id, version)
VALUES (34, 'Y', 253.00, 3642.00, 2770.00, '2009-05-10'::DATE, '2025-10-06'::DATE, '2025-10-06'::DATE, 0.00, 0.00, 'A000000000', NULL, 0)
ON CONFLICT (acct_id) DO NOTHING;
INSERT INTO accounts (acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id, version)
VALUES (35, 'Y', 166.00, 1947.00, 1525.00, '2018-02-02'::DATE, '2025-09-23'::DATE, '2025-09-23'::DATE, 0.00, 0.00, 'A000000000', NULL, 0)
ON CONFLICT (acct_id) DO NOTHING;
INSERT INTO accounts (acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id, version)
VALUES (36, 'Y', 110.00, 3328.00, 839.00, '2018-07-18'::DATE, '2024-12-23'::DATE, '2024-12-23'::DATE, 0.00, 0.00, 'A000000000', NULL, 0)
ON CONFLICT (acct_id) DO NOTHING;
INSERT INTO accounts (acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id, version)
VALUES (37, 'Y', 7.00, 446.00, 166.00, '2016-09-10'::DATE, '2023-10-24'::DATE, '2023-10-24'::DATE, 0.00, 0.00, 'A000000000', NULL, 0)
ON CONFLICT (acct_id) DO NOTHING;
INSERT INTO accounts (acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id, version)
VALUES (38, 'Y', 612.00, 6505.00, 3476.00, '2010-08-12'::DATE, '2023-07-23'::DATE, '2023-07-23'::DATE, 0.00, 0.00, 'A000000000', NULL, 0)
ON CONFLICT (acct_id) DO NOTHING;
INSERT INTO accounts (acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id, version)
VALUES (39, 'Y', 843.00, 9750.00, 6212.00, '2018-08-26'::DATE, '2025-09-08'::DATE, '2025-09-08'::DATE, 0.00, 0.00, 'A000000000', NULL, 0)
ON CONFLICT (acct_id) DO NOTHING;
INSERT INTO accounts (acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id, version)
VALUES (40, 'Y', 43.00, 5823.00, 1674.00, '2010-02-13'::DATE, '2023-10-27'::DATE, '2023-10-27'::DATE, 0.00, 0.00, 'A000000000', NULL, 0)
ON CONFLICT (acct_id) DO NOTHING;
INSERT INTO accounts (acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id, version)
VALUES (41, 'Y', 375.00, 6721.00, 3429.00, '2015-02-07'::DATE, '2023-04-24'::DATE, '2023-04-24'::DATE, 0.00, 0.00, 'A000000000', NULL, 0)
ON CONFLICT (acct_id) DO NOTHING;
INSERT INTO accounts (acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id, version)
VALUES (42, 'Y', 302.00, 6563.00, 5103.00, '2016-09-19'::DATE, '2025-09-19'::DATE, '2025-09-19'::DATE, 0.00, 0.00, 'A000000000', NULL, 0)
ON CONFLICT (acct_id) DO NOTHING;
INSERT INTO accounts (acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id, version)
VALUES (43, 'Y', 610.00, 6168.00, 1206.00, '2012-04-09'::DATE, '2025-08-29'::DATE, '2025-08-29'::DATE, 0.00, 0.00, 'A000000000', NULL, 0)
ON CONFLICT (acct_id) DO NOTHING;
INSERT INTO accounts (acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id, version)
VALUES (44, 'Y', 263.00, 6899.00, 4432.00, '2018-12-01'::DATE, '2024-01-17'::DATE, '2024-01-17'::DATE, 0.00, 0.00, 'A000000000', NULL, 0)
ON CONFLICT (acct_id) DO NOTHING;
INSERT INTO accounts (acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id, version)
VALUES (45, 'Y', 186.00, 2719.00, 688.00, '2010-12-31'::DATE, '2025-07-09'::DATE, '2025-07-09'::DATE, 0.00, 0.00, 'A000000000', NULL, 0)
ON CONFLICT (acct_id) DO NOTHING;
INSERT INTO accounts (acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id, version)
VALUES (46, 'Y', 396.00, 7007.00, 5438.00, '2013-09-06'::DATE, '2025-06-20'::DATE, '2025-06-20'::DATE, 0.00, 0.00, 'A000000000', NULL, 0)
ON CONFLICT (acct_id) DO NOTHING;
INSERT INTO accounts (acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id, version)
VALUES (47, 'Y', 32.00, 2338.00, 159.00, '2014-04-03'::DATE, '2025-08-23'::DATE, '2025-08-23'::DATE, 0.00, 0.00, 'A000000000', NULL, 0)
ON CONFLICT (acct_id) DO NOTHING;
INSERT INTO accounts (acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id, version)
VALUES (48, 'Y', 226.00, 2306.00, 612.00, '2017-03-18'::DATE, '2025-02-06'::DATE, '2025-02-06'::DATE, 0.00, 0.00, 'A000000000', NULL, 0)
ON CONFLICT (acct_id) DO NOTHING;
INSERT INTO accounts (acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id, version)
VALUES (49, 'Y', 100.00, 9048.00, 4807.00, '2019-04-06'::DATE, '2023-09-17'::DATE, '2023-09-17'::DATE, 0.00, 0.00, 'A000000000', NULL, 0)
ON CONFLICT (acct_id) DO NOTHING;
INSERT INTO accounts (acct_id, active_status, curr_bal, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id, version)
VALUES (50, 'Y', 492.00, 6169.00, 4587.00, '2011-04-22'::DATE, '2023-03-09'::DATE, '2023-03-09'::DATE, 0.00, 0.00, 'A000000000', NULL, 0)
ON CONFLICT (acct_id) DO NOTHING;

-- =============================================================================
-- Section 3: Cards (50 records from carddata.txt)
-- =============================================================================
INSERT INTO cards (card_num, account_id, cvv_cd, embossed_name, expiration_date, active_status, version)
VALUES ('0500024453765740', 50, 747, 'Aniya Von', '2023-03-09'::DATE, 'Y', 0)
ON CONFLICT (card_num) DO NOTHING;
INSERT INTO cards (card_num, account_id, cvv_cd, embossed_name, expiration_date, active_status, version)
VALUES ('0683586198171516', 27, 567, 'Ward Jones', '2025-07-13'::DATE, 'Y', 0)
ON CONFLICT (card_num) DO NOTHING;
INSERT INTO cards (card_num, account_id, cvv_cd, embossed_name, expiration_date, active_status, version)
VALUES ('0923877193247330', 2, 28, 'Enrico Rosenbaum', '2024-08-11'::DATE, 'Y', 0)
ON CONFLICT (card_num) DO NOTHING;
INSERT INTO cards (card_num, account_id, cvv_cd, embossed_name, expiration_date, active_status, version)
VALUES ('0927987108636232', 20, 3, 'Carter Veum', '2024-03-13'::DATE, 'Y', 0)
ON CONFLICT (card_num) DO NOTHING;
INSERT INTO cards (card_num, account_id, cvv_cd, embossed_name, expiration_date, active_status, version)
VALUES ('0982496213629795', 12, 75, 'Maci Robel', '2023-07-07'::DATE, 'Y', 0)
ON CONFLICT (card_num) DO NOTHING;
INSERT INTO cards (card_num, account_id, cvv_cd, embossed_name, expiration_date, active_status, version)
VALUES ('1014086565224350', 44, 640, 'Irving Emard', '2024-01-17'::DATE, 'Y', 0)
ON CONFLICT (card_num) DO NOTHING;
INSERT INTO cards (card_num, account_id, cvv_cd, embossed_name, expiration_date, active_status, version)
VALUES ('1142167692878931', 37, 625, 'Shany Walker', '2023-10-24'::DATE, 'Y', 0)
ON CONFLICT (card_num) DO NOTHING;
INSERT INTO cards (card_num, account_id, cvv_cd, embossed_name, expiration_date, active_status, version)
VALUES ('1561409106491600', 35, 31, 'Angelica Dach', '2025-09-23'::DATE, 'Y', 0)
ON CONFLICT (card_num) DO NOTHING;
INSERT INTO cards (card_num, account_id, cvv_cd, embossed_name, expiration_date, active_status, version)
VALUES ('2745303720002090', 39, 33, 'Aliyah Berge', '2025-09-08'::DATE, 'Y', 0)
ON CONFLICT (card_num) DO NOTHING;
INSERT INTO cards (card_num, account_id, cvv_cd, embossed_name, expiration_date, active_status, version)
VALUES ('2760836797107565', 24, 859, 'Stefanie Dickinson', '2025-02-11'::DATE, 'Y', 0)
ON CONFLICT (card_num) DO NOTHING;
INSERT INTO cards (card_num, account_id, cvv_cd, embossed_name, expiration_date, active_status, version)
VALUES ('2871968252812490', 6, 775, 'Ignacio Douglas', '2025-10-08'::DATE, 'Y', 0)
ON CONFLICT (card_num) DO NOTHING;
INSERT INTO cards (card_num, account_id, cvv_cd, embossed_name, expiration_date, active_status, version)
VALUES ('2940139362300449', 22, 876, 'Allene Brown', '2025-12-28'::DATE, 'Y', 0)
ON CONFLICT (card_num) DO NOTHING;
INSERT INTO cards (card_num, account_id, cvv_cd, embossed_name, expiration_date, active_status, version)
VALUES ('2988091353094312', 4, 795, 'Delbert Parisian', '2023-12-16'::DATE, 'Y', 0)
ON CONFLICT (card_num) DO NOTHING;
INSERT INTO cards (card_num, account_id, cvv_cd, embossed_name, expiration_date, active_status, version)
VALUES ('3260763612337560', 10, 342, 'Maybell Mann', '2023-01-27'::DATE, 'Y', 0)
ON CONFLICT (card_num) DO NOTHING;
INSERT INTO cards (card_num, account_id, cvv_cd, embossed_name, expiration_date, active_status, version)
VALUES ('3766281984155154', 41, 622, 'Lucinda Dach', '2023-04-24'::DATE, 'Y', 0)
ON CONFLICT (card_num) DO NOTHING;
INSERT INTO cards (card_num, account_id, cvv_cd, embossed_name, expiration_date, active_status, version)
VALUES ('3940246016141489', 19, 375, 'Hadley Hamill', '2025-07-23'::DATE, 'Y', 0)
ON CONFLICT (card_num) DO NOTHING;
INSERT INTO cards (card_num, account_id, cvv_cd, embossed_name, expiration_date, active_status, version)
VALUES ('3999169246375885', 3, 317, 'Larry Homenick', '2024-01-10'::DATE, 'Y', 0)
ON CONFLICT (card_num) DO NOTHING;
INSERT INTO cards (card_num, account_id, cvv_cd, embossed_name, expiration_date, active_status, version)
VALUES ('4011500891777367', 13, 390, 'Mariane Fadel', '2024-08-04'::DATE, 'Y', 0)
ON CONFLICT (card_num) DO NOTHING;
INSERT INTO cards (card_num, account_id, cvv_cd, embossed_name, expiration_date, active_status, version)
VALUES ('4385271476627819', 34, 709, 'Faustino Schmidt', '2025-10-06'::DATE, 'Y', 0)
ON CONFLICT (card_num) DO NOTHING;
INSERT INTO cards (card_num, account_id, cvv_cd, embossed_name, expiration_date, active_status, version)
VALUES ('4534784102713951', 36, 644, 'Toney Gerhold', '2024-12-23'::DATE, 'Y', 0)
ON CONFLICT (card_num) DO NOTHING;
INSERT INTO cards (card_num, account_id, cvv_cd, embossed_name, expiration_date, active_status, version)
VALUES ('4859452612877065', 7, 321, 'Cooper Mayert', '2024-12-13'::DATE, 'Y', 0)
ON CONFLICT (card_num) DO NOTHING;
INSERT INTO cards (card_num, account_id, cvv_cd, embossed_name, expiration_date, active_status, version)
VALUES ('5407099850479866', 21, 524, 'Jerrold Maggio', '2023-01-06'::DATE, 'Y', 0)
ON CONFLICT (card_num) DO NOTHING;
INSERT INTO cards (card_num, account_id, cvv_cd, embossed_name, expiration_date, active_status, version)
VALUES ('5656830544981216', 46, 196, 'Cindy Cremin', '2025-06-20'::DATE, 'Y', 0)
ON CONFLICT (card_num) DO NOTHING;
INSERT INTO cards (card_num, account_id, cvv_cd, embossed_name, expiration_date, active_status, version)
VALUES ('5671184478505844', 18, 137, 'Emile White', '2023-09-10'::DATE, 'Y', 0)
ON CONFLICT (card_num) DO NOTHING;
INSERT INTO cards (card_num, account_id, cvv_cd, embossed_name, expiration_date, active_status, version)
VALUES ('5787351228879339', 47, 67, 'Rigoberto Hoeger', '2025-08-23'::DATE, 'Y', 0)
ON CONFLICT (card_num) DO NOTHING;
INSERT INTO cards (card_num, account_id, cvv_cd, embossed_name, expiration_date, active_status, version)
VALUES ('5975117516616077', 42, 426, 'Heather Nienow', '2025-09-19'::DATE, 'Y', 0)
ON CONFLICT (card_num) DO NOTHING;
INSERT INTO cards (card_num, account_id, cvv_cd, embossed_name, expiration_date, active_status, version)
VALUES ('6009619150674526', 5, 21, 'Treva Schowalter', '2025-03-09'::DATE, 'Y', 0)
ON CONFLICT (card_num) DO NOTHING;
INSERT INTO cards (card_num, account_id, cvv_cd, embossed_name, expiration_date, active_status, version)
VALUES ('6349250331648509', 15, 735, 'Aubree Hermann', '2025-06-09'::DATE, 'Y', 0)
ON CONFLICT (card_num) DO NOTHING;
INSERT INTO cards (card_num, account_id, cvv_cd, embossed_name, expiration_date, active_status, version)
VALUES ('6503535181795992', 48, 413, 'Lyric Pacocha', '2025-02-06'::DATE, 'Y', 0)
ON CONFLICT (card_num) DO NOTHING;
INSERT INTO cards (card_num, account_id, cvv_cd, embossed_name, expiration_date, active_status, version)
VALUES ('6509230362553816', 30, 236, 'Layla Ullrich', '2024-06-27'::DATE, 'Y', 0)
ON CONFLICT (card_num) DO NOTHING;
INSERT INTO cards (card_num, account_id, cvv_cd, embossed_name, expiration_date, active_status, version)
VALUES ('6723000463207764', 28, 486, 'Hester Hane', '2024-05-09'::DATE, 'Y', 0)
ON CONFLICT (card_num) DO NOTHING;
INSERT INTO cards (card_num, account_id, cvv_cd, embossed_name, expiration_date, active_status, version)
VALUES ('6727055190616014', 16, 641, 'Carroll Bergstrom', '2024-01-25'::DATE, 'Y', 0)
ON CONFLICT (card_num) DO NOTHING;
INSERT INTO cards (card_num, account_id, cvv_cd, embossed_name, expiration_date, active_status, version)
VALUES ('6832676047698087', 33, 983, 'Bernice Herman', '2025-10-07'::DATE, 'Y', 0)
ON CONFLICT (card_num) DO NOTHING;
INSERT INTO cards (card_num, account_id, cvv_cd, embossed_name, expiration_date, active_status, version)
VALUES ('7026637615032277', 31, 920, 'Lucious O''Connell', '2025-06-08'::DATE, 'Y', 0)
ON CONFLICT (card_num) DO NOTHING;
INSERT INTO cards (card_num, account_id, cvv_cd, embossed_name, expiration_date, active_status, version)
VALUES ('7058267261837752', 43, 401, 'Britney Waters', '2025-08-29'::DATE, 'Y', 0)
ON CONFLICT (card_num) DO NOTHING;
INSERT INTO cards (card_num, account_id, cvv_cd, embossed_name, expiration_date, active_status, version)
VALUES ('7094142751055551', 32, 659, 'Stephany Fisher', '2025-05-19'::DATE, 'Y', 0)
ON CONFLICT (card_num) DO NOTHING;
INSERT INTO cards (card_num, account_id, cvv_cd, embossed_name, expiration_date, active_status, version)
VALUES ('7251508149188883', 29, 717, 'Rickie Daugherty', '2024-06-04'::DATE, 'Y', 0)
ON CONFLICT (card_num) DO NOTHING;
INSERT INTO cards (card_num, account_id, cvv_cd, embossed_name, expiration_date, active_status, version)
VALUES ('7379335634661142', 45, 134, 'Dixie Beier', '2025-07-09'::DATE, 'Y', 0)
ON CONFLICT (card_num) DO NOTHING;
INSERT INTO cards (card_num, account_id, cvv_cd, embossed_name, expiration_date, active_status, version)
VALUES ('7427684863423209', 11, 892, 'Hayden Pfannerstill', '2025-03-12'::DATE, 'Y', 0)
ON CONFLICT (card_num) DO NOTHING;
INSERT INTO cards (card_num, account_id, cvv_cd, embossed_name, expiration_date, active_status, version)
VALUES ('7443870988897530', 38, 708, 'Angela Ankunding', '2023-07-23'::DATE, 'Y', 0)
ON CONFLICT (card_num) DO NOTHING;
INSERT INTO cards (card_num, account_id, cvv_cd, embossed_name, expiration_date, active_status, version)
VALUES ('8040580410348680', 26, 971, 'Marjory Stracke', '2024-12-19'::DATE, 'Y', 0)
ON CONFLICT (card_num) DO NOTHING;
INSERT INTO cards (card_num, account_id, cvv_cd, embossed_name, expiration_date, active_status, version)
VALUES ('8112545834239735', 23, 440, 'Johnson Ruecker', '2025-03-18'::DATE, 'Y', 0)
ON CONFLICT (card_num) DO NOTHING;
INSERT INTO cards (card_num, account_id, cvv_cd, embossed_name, expiration_date, active_status, version)
VALUES ('8262593602473076', 49, 457, 'Immanuel Bednar', '2023-09-17'::DATE, 'Y', 0)
ON CONFLICT (card_num) DO NOTHING;
INSERT INTO cards (card_num, account_id, cvv_cd, embossed_name, expiration_date, active_status, version)
VALUES ('8517866958206008', 14, 955, 'Chelsea Marks', '2025-12-11'::DATE, 'Y', 0)
ON CONFLICT (card_num) DO NOTHING;
INSERT INTO cards (card_num, account_id, cvv_cd, embossed_name, expiration_date, active_status, version)
VALUES ('8931369351894783', 8, 230, 'Kelsie Dicki', '2024-05-20'::DATE, 'Y', 0)
ON CONFLICT (card_num) DO NOTHING;
INSERT INTO cards (card_num, account_id, cvv_cd, embossed_name, expiration_date, active_status, version)
VALUES ('9056297931664011', 25, 931, 'Elliott Howell', '2025-07-10'::DATE, 'Y', 0)
ON CONFLICT (card_num) DO NOTHING;
INSERT INTO cards (card_num, account_id, cvv_cd, embossed_name, expiration_date, active_status, version)
VALUES ('9349107475869214', 17, 218, 'Sigrid Mann', '2025-03-01'::DATE, 'Y', 0)
ON CONFLICT (card_num) DO NOTHING;
INSERT INTO cards (card_num, account_id, cvv_cd, embossed_name, expiration_date, active_status, version)
VALUES ('9501733721429893', 9, 725, 'Melvin Ondricka', '2024-12-27'::DATE, 'Y', 0)
ON CONFLICT (card_num) DO NOTHING;
INSERT INTO cards (card_num, account_id, cvv_cd, embossed_name, expiration_date, active_status, version)
VALUES ('9680294154603697', 1, 45, 'Immanuel Kessler', '2025-05-20'::DATE, 'Y', 0)
ON CONFLICT (card_num) DO NOTHING;
INSERT INTO cards (card_num, account_id, cvv_cd, embossed_name, expiration_date, active_status, version)
VALUES ('9805583408996588', 40, 908, 'Davon Emmerich', '2023-10-27'::DATE, 'Y', 0)
ON CONFLICT (card_num) DO NOTHING;

-- =============================================================================
-- Section 4: Card Cross-References (50 records from cardxref.txt)
-- =============================================================================
INSERT INTO card_xref (xref_card_num, xref_cust_id, xref_acct_id)
VALUES ('0500024453765740', 50, 50)
ON CONFLICT (xref_card_num) DO NOTHING;
INSERT INTO card_xref (xref_card_num, xref_cust_id, xref_acct_id)
VALUES ('0683586198171516', 27, 27)
ON CONFLICT (xref_card_num) DO NOTHING;
INSERT INTO card_xref (xref_card_num, xref_cust_id, xref_acct_id)
VALUES ('0923877193247330', 2, 2)
ON CONFLICT (xref_card_num) DO NOTHING;
INSERT INTO card_xref (xref_card_num, xref_cust_id, xref_acct_id)
VALUES ('0927987108636232', 20, 20)
ON CONFLICT (xref_card_num) DO NOTHING;
INSERT INTO card_xref (xref_card_num, xref_cust_id, xref_acct_id)
VALUES ('0982496213629795', 12, 12)
ON CONFLICT (xref_card_num) DO NOTHING;
INSERT INTO card_xref (xref_card_num, xref_cust_id, xref_acct_id)
VALUES ('1014086565224350', 44, 44)
ON CONFLICT (xref_card_num) DO NOTHING;
INSERT INTO card_xref (xref_card_num, xref_cust_id, xref_acct_id)
VALUES ('1142167692878931', 37, 37)
ON CONFLICT (xref_card_num) DO NOTHING;
INSERT INTO card_xref (xref_card_num, xref_cust_id, xref_acct_id)
VALUES ('1561409106491600', 35, 35)
ON CONFLICT (xref_card_num) DO NOTHING;
INSERT INTO card_xref (xref_card_num, xref_cust_id, xref_acct_id)
VALUES ('2745303720002090', 39, 39)
ON CONFLICT (xref_card_num) DO NOTHING;
INSERT INTO card_xref (xref_card_num, xref_cust_id, xref_acct_id)
VALUES ('2760836797107565', 24, 24)
ON CONFLICT (xref_card_num) DO NOTHING;
INSERT INTO card_xref (xref_card_num, xref_cust_id, xref_acct_id)
VALUES ('2871968252812490', 6, 6)
ON CONFLICT (xref_card_num) DO NOTHING;
INSERT INTO card_xref (xref_card_num, xref_cust_id, xref_acct_id)
VALUES ('2940139362300449', 22, 22)
ON CONFLICT (xref_card_num) DO NOTHING;
INSERT INTO card_xref (xref_card_num, xref_cust_id, xref_acct_id)
VALUES ('2988091353094312', 4, 4)
ON CONFLICT (xref_card_num) DO NOTHING;
INSERT INTO card_xref (xref_card_num, xref_cust_id, xref_acct_id)
VALUES ('3260763612337560', 10, 10)
ON CONFLICT (xref_card_num) DO NOTHING;
INSERT INTO card_xref (xref_card_num, xref_cust_id, xref_acct_id)
VALUES ('3766281984155154', 41, 41)
ON CONFLICT (xref_card_num) DO NOTHING;
INSERT INTO card_xref (xref_card_num, xref_cust_id, xref_acct_id)
VALUES ('3940246016141489', 19, 19)
ON CONFLICT (xref_card_num) DO NOTHING;
INSERT INTO card_xref (xref_card_num, xref_cust_id, xref_acct_id)
VALUES ('3999169246375885', 3, 3)
ON CONFLICT (xref_card_num) DO NOTHING;
INSERT INTO card_xref (xref_card_num, xref_cust_id, xref_acct_id)
VALUES ('4011500891777367', 13, 13)
ON CONFLICT (xref_card_num) DO NOTHING;
INSERT INTO card_xref (xref_card_num, xref_cust_id, xref_acct_id)
VALUES ('4385271476627819', 34, 34)
ON CONFLICT (xref_card_num) DO NOTHING;
INSERT INTO card_xref (xref_card_num, xref_cust_id, xref_acct_id)
VALUES ('4534784102713951', 36, 36)
ON CONFLICT (xref_card_num) DO NOTHING;
INSERT INTO card_xref (xref_card_num, xref_cust_id, xref_acct_id)
VALUES ('4859452612877065', 7, 7)
ON CONFLICT (xref_card_num) DO NOTHING;
INSERT INTO card_xref (xref_card_num, xref_cust_id, xref_acct_id)
VALUES ('5407099850479866', 21, 21)
ON CONFLICT (xref_card_num) DO NOTHING;
INSERT INTO card_xref (xref_card_num, xref_cust_id, xref_acct_id)
VALUES ('5656830544981216', 46, 46)
ON CONFLICT (xref_card_num) DO NOTHING;
INSERT INTO card_xref (xref_card_num, xref_cust_id, xref_acct_id)
VALUES ('5671184478505844', 18, 18)
ON CONFLICT (xref_card_num) DO NOTHING;
INSERT INTO card_xref (xref_card_num, xref_cust_id, xref_acct_id)
VALUES ('5787351228879339', 47, 47)
ON CONFLICT (xref_card_num) DO NOTHING;
INSERT INTO card_xref (xref_card_num, xref_cust_id, xref_acct_id)
VALUES ('5975117516616077', 42, 42)
ON CONFLICT (xref_card_num) DO NOTHING;
INSERT INTO card_xref (xref_card_num, xref_cust_id, xref_acct_id)
VALUES ('6009619150674526', 5, 5)
ON CONFLICT (xref_card_num) DO NOTHING;
INSERT INTO card_xref (xref_card_num, xref_cust_id, xref_acct_id)
VALUES ('6349250331648509', 15, 15)
ON CONFLICT (xref_card_num) DO NOTHING;
INSERT INTO card_xref (xref_card_num, xref_cust_id, xref_acct_id)
VALUES ('6503535181795992', 48, 48)
ON CONFLICT (xref_card_num) DO NOTHING;
INSERT INTO card_xref (xref_card_num, xref_cust_id, xref_acct_id)
VALUES ('6509230362553816', 30, 30)
ON CONFLICT (xref_card_num) DO NOTHING;
INSERT INTO card_xref (xref_card_num, xref_cust_id, xref_acct_id)
VALUES ('6723000463207764', 28, 28)
ON CONFLICT (xref_card_num) DO NOTHING;
INSERT INTO card_xref (xref_card_num, xref_cust_id, xref_acct_id)
VALUES ('6727055190616014', 16, 16)
ON CONFLICT (xref_card_num) DO NOTHING;
INSERT INTO card_xref (xref_card_num, xref_cust_id, xref_acct_id)
VALUES ('6832676047698087', 33, 33)
ON CONFLICT (xref_card_num) DO NOTHING;
INSERT INTO card_xref (xref_card_num, xref_cust_id, xref_acct_id)
VALUES ('7026637615032277', 31, 31)
ON CONFLICT (xref_card_num) DO NOTHING;
INSERT INTO card_xref (xref_card_num, xref_cust_id, xref_acct_id)
VALUES ('7058267261837752', 43, 43)
ON CONFLICT (xref_card_num) DO NOTHING;
INSERT INTO card_xref (xref_card_num, xref_cust_id, xref_acct_id)
VALUES ('7094142751055551', 32, 32)
ON CONFLICT (xref_card_num) DO NOTHING;
INSERT INTO card_xref (xref_card_num, xref_cust_id, xref_acct_id)
VALUES ('7251508149188883', 29, 29)
ON CONFLICT (xref_card_num) DO NOTHING;
INSERT INTO card_xref (xref_card_num, xref_cust_id, xref_acct_id)
VALUES ('7379335634661142', 45, 45)
ON CONFLICT (xref_card_num) DO NOTHING;
INSERT INTO card_xref (xref_card_num, xref_cust_id, xref_acct_id)
VALUES ('7427684863423209', 11, 11)
ON CONFLICT (xref_card_num) DO NOTHING;
INSERT INTO card_xref (xref_card_num, xref_cust_id, xref_acct_id)
VALUES ('7443870988897530', 38, 38)
ON CONFLICT (xref_card_num) DO NOTHING;
INSERT INTO card_xref (xref_card_num, xref_cust_id, xref_acct_id)
VALUES ('8040580410348680', 26, 26)
ON CONFLICT (xref_card_num) DO NOTHING;
INSERT INTO card_xref (xref_card_num, xref_cust_id, xref_acct_id)
VALUES ('8112545834239735', 23, 23)
ON CONFLICT (xref_card_num) DO NOTHING;
INSERT INTO card_xref (xref_card_num, xref_cust_id, xref_acct_id)
VALUES ('8262593602473076', 49, 49)
ON CONFLICT (xref_card_num) DO NOTHING;
INSERT INTO card_xref (xref_card_num, xref_cust_id, xref_acct_id)
VALUES ('8517866958206008', 14, 14)
ON CONFLICT (xref_card_num) DO NOTHING;
INSERT INTO card_xref (xref_card_num, xref_cust_id, xref_acct_id)
VALUES ('8931369351894783', 8, 8)
ON CONFLICT (xref_card_num) DO NOTHING;
INSERT INTO card_xref (xref_card_num, xref_cust_id, xref_acct_id)
VALUES ('9056297931664011', 25, 25)
ON CONFLICT (xref_card_num) DO NOTHING;
INSERT INTO card_xref (xref_card_num, xref_cust_id, xref_acct_id)
VALUES ('9349107475869214', 17, 17)
ON CONFLICT (xref_card_num) DO NOTHING;
INSERT INTO card_xref (xref_card_num, xref_cust_id, xref_acct_id)
VALUES ('9501733721429893', 9, 9)
ON CONFLICT (xref_card_num) DO NOTHING;
INSERT INTO card_xref (xref_card_num, xref_cust_id, xref_acct_id)
VALUES ('9680294154603697', 1, 1)
ON CONFLICT (xref_card_num) DO NOTHING;
INSERT INTO card_xref (xref_card_num, xref_cust_id, xref_acct_id)
VALUES ('9805583408996588', 40, 40)
ON CONFLICT (xref_card_num) DO NOTHING;

-- =============================================================================
-- Section 5: Transaction Category Balances (50 records from tcatbal.txt)
-- =============================================================================
INSERT INTO tran_cat_balances (account_id, type_cd, cat_cd, balance)
VALUES (1, '01', '0001', 0.00)
ON CONFLICT (account_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO tran_cat_balances (account_id, type_cd, cat_cd, balance)
VALUES (2, '01', '0001', 0.00)
ON CONFLICT (account_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO tran_cat_balances (account_id, type_cd, cat_cd, balance)
VALUES (3, '01', '0001', 0.00)
ON CONFLICT (account_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO tran_cat_balances (account_id, type_cd, cat_cd, balance)
VALUES (4, '01', '0001', 0.00)
ON CONFLICT (account_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO tran_cat_balances (account_id, type_cd, cat_cd, balance)
VALUES (5, '01', '0001', 0.00)
ON CONFLICT (account_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO tran_cat_balances (account_id, type_cd, cat_cd, balance)
VALUES (6, '01', '0001', 0.00)
ON CONFLICT (account_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO tran_cat_balances (account_id, type_cd, cat_cd, balance)
VALUES (7, '01', '0001', 0.00)
ON CONFLICT (account_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO tran_cat_balances (account_id, type_cd, cat_cd, balance)
VALUES (8, '01', '0001', 0.00)
ON CONFLICT (account_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO tran_cat_balances (account_id, type_cd, cat_cd, balance)
VALUES (9, '01', '0001', 0.00)
ON CONFLICT (account_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO tran_cat_balances (account_id, type_cd, cat_cd, balance)
VALUES (10, '01', '0001', 0.00)
ON CONFLICT (account_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO tran_cat_balances (account_id, type_cd, cat_cd, balance)
VALUES (11, '01', '0001', 0.00)
ON CONFLICT (account_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO tran_cat_balances (account_id, type_cd, cat_cd, balance)
VALUES (12, '01', '0001', 0.00)
ON CONFLICT (account_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO tran_cat_balances (account_id, type_cd, cat_cd, balance)
VALUES (13, '01', '0001', 0.00)
ON CONFLICT (account_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO tran_cat_balances (account_id, type_cd, cat_cd, balance)
VALUES (14, '01', '0001', 0.00)
ON CONFLICT (account_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO tran_cat_balances (account_id, type_cd, cat_cd, balance)
VALUES (15, '01', '0001', 0.00)
ON CONFLICT (account_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO tran_cat_balances (account_id, type_cd, cat_cd, balance)
VALUES (16, '01', '0001', 0.00)
ON CONFLICT (account_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO tran_cat_balances (account_id, type_cd, cat_cd, balance)
VALUES (17, '01', '0001', 0.00)
ON CONFLICT (account_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO tran_cat_balances (account_id, type_cd, cat_cd, balance)
VALUES (18, '01', '0001', 0.00)
ON CONFLICT (account_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO tran_cat_balances (account_id, type_cd, cat_cd, balance)
VALUES (19, '01', '0001', 0.00)
ON CONFLICT (account_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO tran_cat_balances (account_id, type_cd, cat_cd, balance)
VALUES (20, '01', '0001', 0.00)
ON CONFLICT (account_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO tran_cat_balances (account_id, type_cd, cat_cd, balance)
VALUES (21, '01', '0001', 0.00)
ON CONFLICT (account_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO tran_cat_balances (account_id, type_cd, cat_cd, balance)
VALUES (22, '01', '0001', 0.00)
ON CONFLICT (account_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO tran_cat_balances (account_id, type_cd, cat_cd, balance)
VALUES (23, '01', '0001', 0.00)
ON CONFLICT (account_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO tran_cat_balances (account_id, type_cd, cat_cd, balance)
VALUES (24, '01', '0001', 0.00)
ON CONFLICT (account_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO tran_cat_balances (account_id, type_cd, cat_cd, balance)
VALUES (25, '01', '0001', 0.00)
ON CONFLICT (account_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO tran_cat_balances (account_id, type_cd, cat_cd, balance)
VALUES (26, '01', '0001', 0.00)
ON CONFLICT (account_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO tran_cat_balances (account_id, type_cd, cat_cd, balance)
VALUES (27, '01', '0001', 0.00)
ON CONFLICT (account_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO tran_cat_balances (account_id, type_cd, cat_cd, balance)
VALUES (28, '01', '0001', 0.00)
ON CONFLICT (account_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO tran_cat_balances (account_id, type_cd, cat_cd, balance)
VALUES (29, '01', '0001', 0.00)
ON CONFLICT (account_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO tran_cat_balances (account_id, type_cd, cat_cd, balance)
VALUES (30, '01', '0001', 0.00)
ON CONFLICT (account_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO tran_cat_balances (account_id, type_cd, cat_cd, balance)
VALUES (31, '01', '0001', 0.00)
ON CONFLICT (account_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO tran_cat_balances (account_id, type_cd, cat_cd, balance)
VALUES (32, '01', '0001', 0.00)
ON CONFLICT (account_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO tran_cat_balances (account_id, type_cd, cat_cd, balance)
VALUES (33, '01', '0001', 0.00)
ON CONFLICT (account_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO tran_cat_balances (account_id, type_cd, cat_cd, balance)
VALUES (34, '01', '0001', 0.00)
ON CONFLICT (account_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO tran_cat_balances (account_id, type_cd, cat_cd, balance)
VALUES (35, '01', '0001', 0.00)
ON CONFLICT (account_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO tran_cat_balances (account_id, type_cd, cat_cd, balance)
VALUES (36, '01', '0001', 0.00)
ON CONFLICT (account_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO tran_cat_balances (account_id, type_cd, cat_cd, balance)
VALUES (37, '01', '0001', 0.00)
ON CONFLICT (account_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO tran_cat_balances (account_id, type_cd, cat_cd, balance)
VALUES (38, '01', '0001', 0.00)
ON CONFLICT (account_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO tran_cat_balances (account_id, type_cd, cat_cd, balance)
VALUES (39, '01', '0001', 0.00)
ON CONFLICT (account_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO tran_cat_balances (account_id, type_cd, cat_cd, balance)
VALUES (40, '01', '0001', 0.00)
ON CONFLICT (account_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO tran_cat_balances (account_id, type_cd, cat_cd, balance)
VALUES (41, '01', '0001', 0.00)
ON CONFLICT (account_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO tran_cat_balances (account_id, type_cd, cat_cd, balance)
VALUES (42, '01', '0001', 0.00)
ON CONFLICT (account_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO tran_cat_balances (account_id, type_cd, cat_cd, balance)
VALUES (43, '01', '0001', 0.00)
ON CONFLICT (account_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO tran_cat_balances (account_id, type_cd, cat_cd, balance)
VALUES (44, '01', '0001', 0.00)
ON CONFLICT (account_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO tran_cat_balances (account_id, type_cd, cat_cd, balance)
VALUES (45, '01', '0001', 0.00)
ON CONFLICT (account_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO tran_cat_balances (account_id, type_cd, cat_cd, balance)
VALUES (46, '01', '0001', 0.00)
ON CONFLICT (account_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO tran_cat_balances (account_id, type_cd, cat_cd, balance)
VALUES (47, '01', '0001', 0.00)
ON CONFLICT (account_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO tran_cat_balances (account_id, type_cd, cat_cd, balance)
VALUES (48, '01', '0001', 0.00)
ON CONFLICT (account_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO tran_cat_balances (account_id, type_cd, cat_cd, balance)
VALUES (49, '01', '0001', 0.00)
ON CONFLICT (account_id, type_cd, cat_cd) DO NOTHING;
INSERT INTO tran_cat_balances (account_id, type_cd, cat_cd, balance)
VALUES (50, '01', '0001', 0.00)
ON CONFLICT (account_id, type_cd, cat_cd) DO NOTHING;
