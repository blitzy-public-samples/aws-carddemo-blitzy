-- ===================================================================================
-- Flyway Migration: V8__insert_seed_data.sql
-- ===================================================================================
-- Description: Loads seed data from COBOL test data files into PostgreSQL tables
-- Source Files: app/data/ASCII/*.txt (9 fixed-width ASCII test data files)
-- Purpose: Populate development and testing database with sample data
-- Migration Type: Data migration from VSAM test fixtures to PostgreSQL
-- ===================================================================================
-- 
-- Data Load Order (respecting foreign key dependencies):
--   1. Reference Tables (no dependencies):
--      - transaction_type (7 rows from trantype.txt)
--      - transaction_category (18 rows from trancatg.txt)
--      - disclosure_group (51 rows from discgrp.txt)
--   
--   2. Master Entity Tables (with FK dependencies):
--      - customer (50 rows from custdata.txt) - independent
--      - account (50 rows from acctdata.txt) - depends on customer
--      - card (50 rows from carddata.txt) - depends on account
--      - transaction (rows from dailytran.txt) - depends on card
--      - transaction_category_balance (rows from tcatbal.txt) - depends on account & transaction_category
--
-- Data Transformation Notes:
--   - COBOL PIC S9(n)V99 packed decimal → PostgreSQL NUMERIC with explicit scale=2
--   - COBOL '{' in numeric fields → positive sign (COMP-3 encoding)
--   - COBOL '}' in numeric fields → negative sign (COMP-3 encoding)
--   - COBOL zoned decimal letters (A-I=positive, J-R=negative) → digit 1-9 with sign
--   - Fixed-width positional data → explicit column values
--   - Date format: YYYY-MM-DD (already ISO-8601 compliant)
--   - Timestamps: 'YYYY-MM-DD HH:MI:SS.mmmmmm' format
-- ===================================================================================

-- ===================================================================================
-- SECTION 1: Reference Tables - Transaction Types
-- ===================================================================================
-- Source: app/data/ASCII/trantype.txt
-- Format: 2-digit code + 50-char description + 8 zeros (padding)
-- Records: 7 transaction types (01-07)
-- ===================================================================================

-- Source: trantype.txt line 1
INSERT INTO transaction_type (type_code, description) 
VALUES ('01', 'Purchase');

-- Source: trantype.txt line 2
INSERT INTO transaction_type (type_code, description) 
VALUES ('02', 'Payment');

-- Source: trantype.txt line 3
INSERT INTO transaction_type (type_code, description) 
VALUES ('03', 'Credit');

-- Source: trantype.txt line 4
INSERT INTO transaction_type (type_code, description) 
VALUES ('04', 'Authorization');

-- Source: trantype.txt line 5
INSERT INTO transaction_type (type_code, description) 
VALUES ('05', 'Refund');

-- Source: trantype.txt line 6
INSERT INTO transaction_type (type_code, description) 
VALUES ('06', 'Reversal');

-- Source: trantype.txt line 7
INSERT INTO transaction_type (type_code, description) 
VALUES ('07', 'Adjustment');

-- ===================================================================================
-- SECTION 2: Reference Tables - Transaction Categories
-- ===================================================================================
-- Source: app/data/ASCII/trancatg.txt
-- Format: 6-digit code (2-digit type + 4-digit category) + 50-char description + 4 zeros
-- Records: 18 transaction categories across 7 types
-- Note: category_code in table is SMALLINT storing last 4 digits
-- ===================================================================================

-- Source: trancatg.txt line 1 - Type 01 (Purchase) Category 0001
INSERT INTO transaction_category (type_code, category_code, description) 
VALUES ('01', 1, 'Regular Sales Draft');

-- Source: trancatg.txt line 2 - Type 01 Category 0002
INSERT INTO transaction_category (type_code, category_code, description) 
VALUES ('01', 2, 'Regular Cash Advance');

-- Source: trancatg.txt line 3 - Type 01 Category 0003
INSERT INTO transaction_category (type_code, category_code, description) 
VALUES ('01', 3, 'Convenience Check Debit');

-- Source: trancatg.txt line 4 - Type 01 Category 0004
INSERT INTO transaction_category (type_code, category_code, description) 
VALUES ('01', 4, 'ATM Cash Advance');

-- Source: trancatg.txt line 5 - Type 01 Category 0005
INSERT INTO transaction_category (type_code, category_code, description) 
VALUES ('01', 5, 'Interest Amount');

-- Source: trancatg.txt line 6 - Type 02 (Payment) Category 0001
INSERT INTO transaction_category (type_code, category_code, description) 
VALUES ('02', 1, 'Cash payment');

-- Source: trancatg.txt line 7 - Type 02 Category 0002
INSERT INTO transaction_category (type_code, category_code, description) 
VALUES ('02', 2, 'Electronic payment');

-- Source: trancatg.txt line 8 - Type 02 Category 0003
INSERT INTO transaction_category (type_code, category_code, description) 
VALUES ('02', 3, 'Check payment');

-- Source: trancatg.txt line 9 - Type 03 (Credit) Category 0001
INSERT INTO transaction_category (type_code, category_code, description) 
VALUES ('03', 1, 'Credit to Account');

-- Source: trancatg.txt line 10 - Type 03 Category 0002
INSERT INTO transaction_category (type_code, category_code, description) 
VALUES ('03', 2, 'Credit to Purchase balance');

-- Source: trancatg.txt line 11 - Type 03 Category 0003
INSERT INTO transaction_category (type_code, category_code, description) 
VALUES ('03', 3, 'Credit to Cash balance');

-- Source: trancatg.txt line 12 - Type 04 (Authorization) Category 0001
INSERT INTO transaction_category (type_code, category_code, description) 
VALUES ('04', 1, 'Zero dollar authorization');

-- Source: trancatg.txt line 13 - Type 04 Category 0002
INSERT INTO transaction_category (type_code, category_code, description) 
VALUES ('04', 2, 'Online purchase authorization');

-- Source: trancatg.txt line 14 - Type 04 Category 0003
INSERT INTO transaction_category (type_code, category_code, description) 
VALUES ('04', 3, 'Travel booking authorization');

-- Source: trancatg.txt line 15 - Type 05 (Refund) Category 0001
INSERT INTO transaction_category (type_code, category_code, description) 
VALUES ('05', 1, 'Refund credit');

-- Source: trancatg.txt line 16 - Type 06 (Reversal) Category 0001
INSERT INTO transaction_category (type_code, category_code, description) 
VALUES ('06', 1, 'Fraud reversal');

-- Source: trancatg.txt line 17 - Type 06 Category 0002
INSERT INTO transaction_category (type_code, category_code, description) 
VALUES ('06', 2, 'Non-fraud reversal');

-- Source: trancatg.txt line 18 - Type 07 (Adjustment) Category 0001
INSERT INTO transaction_category (type_code, category_code, description) 
VALUES ('07', 1, 'Sales draft credit adjustment');

-- ===================================================================================
-- SECTION 3: Reference Tables - Disclosure Groups
-- ===================================================================================
-- Source: app/data/ASCII/discgrp.txt
-- Format: 10-char group ID + 2-char type + 4-digit category + interest rate (packed decimal)
-- Records: 51 disclosure group entries (A group=17, DEFAULT group=17, ZEROAPR group=17)
-- Note: Interest rate field has '{' positive indicator, convert to decimal
--       Format: 4 integer digits + 2 decimal places (e.g., 0150{ = 1.50%)
-- ===================================================================================

-- Group A: Account Group 'A' with various interest rates
-- Source: discgrp.txt line 1 - A/01/0001 - 1.50%
INSERT INTO disclosure_group (account_group_id, transaction_type_code, transaction_category_code, interest_rate) 
VALUES ('A', '01', 1, 1.50);

-- Source: discgrp.txt line 2 - A/01/0002 - 2.50%
INSERT INTO disclosure_group (account_group_id, transaction_type_code, transaction_category_code, interest_rate) 
VALUES ('A', '01', 2, 2.50);

-- Source: discgrp.txt line 3 - A/01/0003 - 2.50%
INSERT INTO disclosure_group (account_group_id, transaction_type_code, transaction_category_code, interest_rate) 
VALUES ('A', '01', 3, 2.50);

-- Source: discgrp.txt line 4 - A/01/0004 - 2.50%
INSERT INTO disclosure_group (account_group_id, transaction_type_code, transaction_category_code, interest_rate) 
VALUES ('A', '01', 4, 2.50);

-- Source: discgrp.txt line 5 - A/02/0001 - 0.00%
INSERT INTO disclosure_group (account_group_id, transaction_type_code, transaction_category_code, interest_rate) 
VALUES ('A', '02', 1, 0.00);

-- Source: discgrp.txt line 6 - A/02/0002 - 0.00%
INSERT INTO disclosure_group (account_group_id, transaction_type_code, transaction_category_code, interest_rate) 
VALUES ('A', '02', 2, 0.00);

-- Source: discgrp.txt line 7 - A/02/0003 - 0.00%
INSERT INTO disclosure_group (account_group_id, transaction_type_code, transaction_category_code, interest_rate) 
VALUES ('A', '02', 3, 0.00);

-- Source: discgrp.txt line 8 - A/03/0001 - 0.00%
INSERT INTO disclosure_group (account_group_id, transaction_type_code, transaction_category_code, interest_rate) 
VALUES ('A', '03', 1, 0.00);

-- Source: discgrp.txt line 9 - A/03/0002 - 0.00%
INSERT INTO disclosure_group (account_group_id, transaction_type_code, transaction_category_code, interest_rate) 
VALUES ('A', '03', 2, 0.00);

-- Source: discgrp.txt line 10 - A/03/0003 - 0.00%
INSERT INTO disclosure_group (account_group_id, transaction_type_code, transaction_category_code, interest_rate) 
VALUES ('A', '03', 3, 0.00);

-- Source: discgrp.txt line 11 - A/04/0001 - 1.50%
INSERT INTO disclosure_group (account_group_id, transaction_type_code, transaction_category_code, interest_rate) 
VALUES ('A', '04', 1, 1.50);

-- Source: discgrp.txt line 12 - A/04/0002 - 1.50%
INSERT INTO disclosure_group (account_group_id, transaction_type_code, transaction_category_code, interest_rate) 
VALUES ('A', '04', 2, 1.50);

-- Source: discgrp.txt line 13 - A/04/0003 - 1.50%
INSERT INTO disclosure_group (account_group_id, transaction_type_code, transaction_category_code, interest_rate) 
VALUES ('A', '04', 3, 1.50);

-- Source: discgrp.txt line 14 - A/05/0001 - 1.50%
INSERT INTO disclosure_group (account_group_id, transaction_type_code, transaction_category_code, interest_rate) 
VALUES ('A', '05', 1, 1.50);

-- Source: discgrp.txt line 15 - A/06/0001 - 1.50%
INSERT INTO disclosure_group (account_group_id, transaction_type_code, transaction_category_code, interest_rate) 
VALUES ('A', '06', 1, 1.50);

-- Source: discgrp.txt line 16 - A/06/0002 - 1.50%
INSERT INTO disclosure_group (account_group_id, transaction_type_code, transaction_category_code, interest_rate) 
VALUES ('A', '06', 2, 1.50);

-- Source: discgrp.txt line 17 - A/07/0001 - 1.50%
INSERT INTO disclosure_group (account_group_id, transaction_type_code, transaction_category_code, interest_rate) 
VALUES ('A', '07', 1, 1.50);

-- Group DEFAULT: Default account group interest rates
-- Source: discgrp.txt line 18 - DEFAULT/01/0001 - 1.50%
INSERT INTO disclosure_group (account_group_id, transaction_type_code, transaction_category_code, interest_rate) 
VALUES ('DEFAULT', '01', 1, 1.50);

-- Source: discgrp.txt line 19 - DEFAULT/01/0002 - 2.50%
INSERT INTO disclosure_group (account_group_id, transaction_type_code, transaction_category_code, interest_rate) 
VALUES ('DEFAULT', '01', 2, 2.50);

-- Source: discgrp.txt line 20 - DEFAULT/01/0003 - 2.50%
INSERT INTO disclosure_group (account_group_id, transaction_type_code, transaction_category_code, interest_rate) 
VALUES ('DEFAULT', '01', 3, 2.50);

-- Source: discgrp.txt line 21 - DEFAULT/01/0004 - 2.50%
INSERT INTO disclosure_group (account_group_id, transaction_type_code, transaction_category_code, interest_rate) 
VALUES ('DEFAULT', '01', 4, 2.50);

-- Source: discgrp.txt line 22 - DEFAULT/02/0001 - 0.00%
INSERT INTO disclosure_group (account_group_id, transaction_type_code, transaction_category_code, interest_rate) 
VALUES ('DEFAULT', '02', 1, 0.00);

-- Source: discgrp.txt line 23 - DEFAULT/02/0002 - 0.00%
INSERT INTO disclosure_group (account_group_id, transaction_type_code, transaction_category_code, interest_rate) 
VALUES ('DEFAULT', '02', 2, 0.00);

-- Source: discgrp.txt line 24 - DEFAULT/02/0003 - 0.00%
INSERT INTO disclosure_group (account_group_id, transaction_type_code, transaction_category_code, interest_rate) 
VALUES ('DEFAULT', '02', 3, 0.00);

-- Source: discgrp.txt line 25 - DEFAULT/03/0001 - 0.00%
INSERT INTO disclosure_group (account_group_id, transaction_type_code, transaction_category_code, interest_rate) 
VALUES ('DEFAULT', '03', 1, 0.00);

-- Source: discgrp.txt line 26 - DEFAULT/03/0002 - 0.00%
INSERT INTO disclosure_group (account_group_id, transaction_type_code, transaction_category_code, interest_rate) 
VALUES ('DEFAULT', '03', 2, 0.00);

-- Source: discgrp.txt line 27 - DEFAULT/03/0003 - 0.00%
INSERT INTO disclosure_group (account_group_id, transaction_type_code, transaction_category_code, interest_rate) 
VALUES ('DEFAULT', '03', 3, 0.00);

-- Source: discgrp.txt line 28 - DEFAULT/04/0001 - 1.50%
INSERT INTO disclosure_group (account_group_id, transaction_type_code, transaction_category_code, interest_rate) 
VALUES ('DEFAULT', '04', 1, 1.50);

-- Source: discgrp.txt line 29 - DEFAULT/04/0002 - 1.50%
INSERT INTO disclosure_group (account_group_id, transaction_type_code, transaction_category_code, interest_rate) 
VALUES ('DEFAULT', '04', 2, 1.50);

-- Source: discgrp.txt line 30 - DEFAULT/04/0003 - 1.50%
INSERT INTO disclosure_group (account_group_id, transaction_type_code, transaction_category_code, interest_rate) 
VALUES ('DEFAULT', '04', 3, 1.50);

-- Source: discgrp.txt line 31 - DEFAULT/05/0001 - 1.50%
INSERT INTO disclosure_group (account_group_id, transaction_type_code, transaction_category_code, interest_rate) 
VALUES ('DEFAULT', '05', 1, 1.50);

-- Source: discgrp.txt line 32 - DEFAULT/06/0001 - 1.50%
INSERT INTO disclosure_group (account_group_id, transaction_type_code, transaction_category_code, interest_rate) 
VALUES ('DEFAULT', '06', 1, 1.50);

-- Source: discgrp.txt line 33 - DEFAULT/06/0002 - 1.50%
INSERT INTO disclosure_group (account_group_id, transaction_type_code, transaction_category_code, interest_rate) 
VALUES ('DEFAULT', '06', 2, 1.50);

-- Source: discgrp.txt line 34 - DEFAULT/07/0001 - 0.00%
INSERT INTO disclosure_group (account_group_id, transaction_type_code, transaction_category_code, interest_rate) 
VALUES ('DEFAULT', '07', 1, 0.00);

-- Group ZEROAPR: Zero APR account group (all rates 0.00%)
-- Source: discgrp.txt line 35 - ZEROAPR/01/0001 - 0.00%
INSERT INTO disclosure_group (account_group_id, transaction_type_code, transaction_category_code, interest_rate) 
VALUES ('ZEROAPR', '01', 1, 0.00);

-- Source: discgrp.txt line 36 - ZEROAPR/01/0002 - 0.00%
INSERT INTO disclosure_group (account_group_id, transaction_type_code, transaction_category_code, interest_rate) 
VALUES ('ZEROAPR', '01', 2, 0.00);

-- Source: discgrp.txt line 37 - ZEROAPR/01/0003 - 0.00%
INSERT INTO disclosure_group (account_group_id, transaction_type_code, transaction_category_code, interest_rate) 
VALUES ('ZEROAPR', '01', 3, 0.00);

-- Source: discgrp.txt line 38 - ZEROAPR/01/0004 - 0.00%
INSERT INTO disclosure_group (account_group_id, transaction_type_code, transaction_category_code, interest_rate) 
VALUES ('ZEROAPR', '01', 4, 0.00);

-- Source: discgrp.txt line 39 - ZEROAPR/02/0001 - 0.00%
INSERT INTO disclosure_group (account_group_id, transaction_type_code, transaction_category_code, interest_rate) 
VALUES ('ZEROAPR', '02', 1, 0.00);

-- Source: discgrp.txt line 40 - ZEROAPR/02/0002 - 0.00%
INSERT INTO disclosure_group (account_group_id, transaction_type_code, transaction_category_code, interest_rate) 
VALUES ('ZEROAPR', '02', 2, 0.00);

-- Source: discgrp.txt line 41 - ZEROAPR/02/0003 - 0.00%
INSERT INTO disclosure_group (account_group_id, transaction_type_code, transaction_category_code, interest_rate) 
VALUES ('ZEROAPR', '02', 3, 0.00);

-- Source: discgrp.txt line 42 - ZEROAPR/03/0001 - 0.00%
INSERT INTO disclosure_group (account_group_id, transaction_type_code, transaction_category_code, interest_rate) 
VALUES ('ZEROAPR', '03', 1, 0.00);

-- Source: discgrp.txt line 43 - ZEROAPR/03/0002 - 0.00%
INSERT INTO disclosure_group (account_group_id, transaction_type_code, transaction_category_code, interest_rate) 
VALUES ('ZEROAPR', '03', 2, 0.00);

-- Source: discgrp.txt line 44 - ZEROAPR/03/0003 - 0.00%
INSERT INTO disclosure_group (account_group_id, transaction_type_code, transaction_category_code, interest_rate) 
VALUES ('ZEROAPR', '03', 3, 0.00);

-- Source: discgrp.txt line 45 - ZEROAPR/04/0001 - 0.00%
INSERT INTO disclosure_group (account_group_id, transaction_type_code, transaction_category_code, interest_rate) 
VALUES ('ZEROAPR', '04', 1, 0.00);

-- Source: discgrp.txt line 46 - ZEROAPR/04/0002 - 0.00%
INSERT INTO disclosure_group (account_group_id, transaction_type_code, transaction_category_code, interest_rate) 
VALUES ('ZEROAPR', '04', 2, 0.00);

-- Source: discgrp.txt line 47 - ZEROAPR/04/0003 - 0.00%
INSERT INTO disclosure_group (account_group_id, transaction_type_code, transaction_category_code, interest_rate) 
VALUES ('ZEROAPR', '04', 3, 0.00);

-- Source: discgrp.txt line 48 - ZEROAPR/05/0001 - 0.00%
INSERT INTO disclosure_group (account_group_id, transaction_type_code, transaction_category_code, interest_rate) 
VALUES ('ZEROAPR', '05', 1, 0.00);

-- Source: discgrp.txt line 49 - ZEROAPR/06/0001 - 0.00%
INSERT INTO disclosure_group (account_group_id, transaction_type_code, transaction_category_code, interest_rate) 
VALUES ('ZEROAPR', '06', 1, 0.00);

-- Source: discgrp.txt line 50 - ZEROAPR/06/0002 - 0.00%
INSERT INTO disclosure_group (account_group_id, transaction_type_code, transaction_category_code, interest_rate) 
VALUES ('ZEROAPR', '06', 2, 0.00);

-- Source: discgrp.txt line 51 - ZEROAPR/07/0001 - 0.00%
INSERT INTO disclosure_group (account_group_id, transaction_type_code, transaction_category_code, interest_rate) 
VALUES ('ZEROAPR', '07', 1, 0.00);

-- ===================================================================================
-- SECTION 4: Master Entity Tables - Customer Data
-- ===================================================================================
-- Source: app/data/ASCII/custdata.txt
-- Format: Fixed-width 500-byte records from COBOL copybook CVCUS01Y.cpy
-- Records: 50 customer records
-- Field Layout:
--   Pos 1-9: customer_id (9 digits)
--   Pos 10-34: first_name (25 chars)
--   Pos 35-59: middle_name (25 chars)
--   Pos 60-84: last_name (25 chars)
--   Pos 85-134: address_line_1 (50 chars)
--   Pos 135-184: address_line_2 (50 chars)
--   Pos 185-234: address_line_3 (50 chars)
--   Pos 235-236: state_code (2 chars)
--   Pos 237-239: country_code (3 chars)
--   Pos 240-249: postal_code (10 chars)
--   Pos 250-264: phone_number_1 (15 chars)
--   Pos 265-279: phone_number_2 (15 chars)
--   Pos 280-288: ssn (9 digits)
--   Pos 289-308: government_issued_id (20 chars)
--   Pos 309-318: date_of_birth (10 chars YYYY-MM-DD)
--   Pos 319-328: eft_account_id (10 chars)
--   Pos 329-329: primary_cardholder_ind (1 char Y/N)
--   Pos 330-332: fico_credit_score (3 digits)
-- ===================================================================================

-- Source: custdata.txt line 1 - Customer ID 1
INSERT INTO customer (customer_id, first_name, middle_name, last_name, address_line_1, address_line_2, address_line_3, state_code, country_code, postal_code, phone_number_1, phone_number_2, ssn, government_issued_id, date_of_birth, eft_account_id, primary_cardholder_ind, fico_credit_score)
VALUES (1, 'Immanuel', 'Madeline', 'Kessler', '618 Deshaun Route', 'Apt. 802', 'Altenwerthshire', 'NC', 'USA', '12546', '(908)119-8310', '(373)693-8684', 20973888, '00000000000493684371', '1961-06-08', '0053581756', 'Y', 274);

-- Source: custdata.txt line 2 - Customer ID 2
INSERT INTO customer (customer_id, first_name, middle_name, last_name, address_line_1, address_line_2, address_line_3, state_code, country_code, postal_code, phone_number_1, phone_number_2, ssn, government_issued_id, date_of_birth, eft_account_id, primary_cardholder_ind, fico_credit_score)
VALUES (2, 'Enrico', 'April', 'Rosenbaum', '4917 Myrna Flats', 'Apt. 453', 'West Bernita', 'IN', 'USA', '22770', '(429)706-9510', '(744)950-5272', 587518382, '00000000005062103711', '1961-10-08', '0069194009', 'Y', 268);

-- Source: custdata.txt line 3 - Customer ID 3
INSERT INTO customer (customer_id, first_name, middle_name, last_name, address_line_1, address_line_2, address_line_3, state_code, country_code, postal_code, phone_number_1, phone_number_2, ssn, government_issued_id, date_of_birth, eft_account_id, primary_cardholder_ind, fico_credit_score)
VALUES (3, 'Larry', 'Cody', 'Homenick', '362 Esta Parks', 'Apt. 390', 'New Gladys', 'GA', 'USA', '19852-6716', '(950)396-9024', '(685)168-8826', 317460867, '00000000000524193031', '1987-11-30', '0006465789', 'Y', 616);

-- Source: custdata.txt line 4 - Customer ID 4
INSERT INTO customer (customer_id, first_name, middle_name, last_name, address_line_1, address_line_2, address_line_3, state_code, country_code, postal_code, phone_number_1, phone_number_2, ssn, government_issued_id, date_of_birth, eft_account_id, primary_cardholder_ind, fico_credit_score)
VALUES (4, 'Delbert', 'Kaia', 'Parisian', '638 Blanda Gateway', 'Apt. 076', 'Lake Virginie', 'MI', 'USA', '39035-0455', '(801)603-4121', '(156)074-6837', 660354258, '00000000000685792491', '1985-01-13', '0040802739', 'Y', 776);

-- Source: custdata.txt line 5 - Customer ID 5
INSERT INTO customer (customer_id, first_name, middle_name, last_name, address_line_1, address_line_2, address_line_3, state_code, country_code, postal_code, phone_number_1, phone_number_2, ssn, government_issued_id, date_of_birth, eft_account_id, primary_cardholder_ind, fico_credit_score)
VALUES (5, 'Treva', 'Manley', 'Schowalter', '5653 Legros Plaza', 'Apt. 968', 'Alvinaport', 'MI', 'USA', '02251-1698', '(978)775-4633', '(439)943-7644', 611264288, '00000000006397997541', '1971-09-29', '0006365573', 'Y', 529);

-- Source: custdata.txt line 6 - Customer ID 6
INSERT INTO customer (customer_id, first_name, middle_name, last_name, address_line_1, address_line_2, address_line_3, state_code, country_code, postal_code, phone_number_1, phone_number_2, ssn, government_issued_id, date_of_birth, eft_account_id, primary_cardholder_ind, fico_credit_score)
VALUES (6, 'Ignacio', 'Emery', 'Douglas', '3963 Yasmin Port', 'Suite 756', 'Port Josephstad', 'VI', 'USA', '46713-5148', '(277)743-4266', '(519)010-8739', 880329521, '00000000009755354961', '1994-11-29', '0067163009', 'Y', 753);

-- Source: custdata.txt line 7 - Customer ID 7
INSERT INTO customer (customer_id, first_name, middle_name, last_name, address_line_1, address_line_2, address_line_3, state_code, country_code, postal_code, phone_number_1, phone_number_2, ssn, government_issued_id, date_of_birth, eft_account_id, primary_cardholder_ind, fico_credit_score)
VALUES (7, 'Cooper', 'Dennis', 'Mayert', '6490 Zakary Locks', 'Apt. 765', 'Madieport', 'AL', 'USA', '34206-2974', '(698)282-4096', '(458)199-0016', 835138951, '00000000009590131701', '1977-05-06', '0024571415', 'Y', 499);

-- Source: custdata.txt line 8 - Customer ID 8
INSERT INTO customer (customer_id, first_name, middle_name, last_name, address_line_1, address_line_2, address_line_3, state_code, country_code, postal_code, phone_number_1, phone_number_2, ssn, government_issued_id, date_of_birth, eft_account_id, primary_cardholder_ind, fico_credit_score)
VALUES (8, 'Kelsie', 'Jordyn', 'Dicki', '0925 Welch Streets', 'Apt. 152', 'North Nanniestad', 'SC', 'USA', '27610', '(345)563-7159', '(443)197-1271', 295270759, '00000000001097469911', '1964-03-25', '0033132723', 'Y', 51);

-- Source: custdata.txt line 9 - Customer ID 9
INSERT INTO customer (customer_id, first_name, middle_name, last_name, address_line_1, address_line_2, address_line_3, state_code, country_code, postal_code, phone_number_1, phone_number_2, ssn, government_issued_id, date_of_birth, eft_account_id, primary_cardholder_ind, fico_credit_score)
VALUES (9, 'Melvin', 'Regan', 'Ondricka', '87893 Samson Flats', 'Apt. 135', 'New Braden', 'VI', 'USA', '21113', '(035)456-1404', '(412)440-3130', 842035847, '00000000005682994511', '1975-11-07', '0039446039', 'Y', 699);

-- Source: custdata.txt line 10 - Customer ID 10
INSERT INTO customer (customer_id, first_name, middle_name, last_name, address_line_1, address_line_2, address_line_3, state_code, country_code, postal_code, phone_number_1, phone_number_2, ssn, government_issued_id, date_of_birth, eft_account_id, primary_cardholder_ind, fico_credit_score)
VALUES (10, 'Maybell', 'Creola', 'Mann', '77933 Adah Dale', 'Suite 343', 'Andersonfurt', 'CT', 'USA', '44803-4279', '(614)594-2619', '(667)057-0235', 754755746, '00000000002128247551', '1980-06-11', '0093803568', 'Y', 476);

-- Source: custdata.txt line 11 - Customer ID 11
INSERT INTO customer (customer_id, first_name, middle_name, last_name, address_line_1, address_line_2, address_line_3, state_code, country_code, postal_code, phone_number_1, phone_number_2, ssn, government_issued_id, date_of_birth, eft_account_id, primary_cardholder_ind, fico_credit_score)
VALUES (11, 'Hayden', 'Ressie', 'Pfannerstill', '14895 Everette Ridges', 'Apt. 443', 'Julianneburgh', 'WA', 'USA', '24984', '(002)533-6980', '(553)586-7718', 493538586, '00000000001111908551', '1986-11-03', '0002650577', 'Y', 209);

-- Source: custdata.txt line 12 - Customer ID 12
INSERT INTO customer (customer_id, first_name, middle_name, last_name, address_line_1, address_line_2, address_line_3, state_code, country_code, postal_code, phone_number_1, phone_number_2, ssn, government_issued_id, date_of_birth, eft_account_id, primary_cardholder_ind, fico_credit_score)
VALUES (12, 'Maci', 'Alan', 'Robel', '80501 Isac Cliffs', 'Suite 623', 'Predovicton', 'MN', 'USA', '78861', '(584)045-5200', '(610)244-0407', 666114218, '00000000009021433511', '1984-02-18', '0061317348', 'Y', 688);

-- Source: custdata.txt line 13 - Customer ID 13
INSERT INTO customer (customer_id, first_name, middle_name, last_name, address_line_1, address_line_2, address_line_3, state_code, country_code, postal_code, phone_number_1, phone_number_2, ssn, government_issued_id, date_of_birth, eft_account_id, primary_cardholder_ind, fico_credit_score)
VALUES (13, 'Mariane', 'Oma', 'Fadel', '2689 Derick Mission', 'Suite 055', 'Bruenfurt', 'OR', 'USA', '02322', '(875)943-7287', '(075)550-6435', 757924569, '00000000001813772201', '1999-03-09', '0044807431', 'Y', 53);

-- Source: custdata.txt line 14 - Customer ID 14
INSERT INTO customer (customer_id, first_name, middle_name, last_name, address_line_1, address_line_2, address_line_3, state_code, country_code, postal_code, phone_number_1, phone_number_2, ssn, government_issued_id, date_of_birth, eft_account_id, primary_cardholder_ind, fico_credit_score)
VALUES (14, 'Chelsea', 'Ignacio', 'Marks', '747 Dino Lodge', 'Apt. 850', 'West Chase', 'RI', 'USA', '12914-8465', '(141)807-6571', '(284)088-9052', 655128548, '00000000005259552221', '1974-11-29', '0048306401', 'Y', 243);

-- Source: custdata.txt line 15 - Customer ID 15
INSERT INTO customer (customer_id, first_name, middle_name, last_name, address_line_1, address_line_2, address_line_3, state_code, country_code, postal_code, phone_number_1, phone_number_2, ssn, government_issued_id, date_of_birth, eft_account_id, primary_cardholder_ind, fico_credit_score)
VALUES (15, 'Aubree', 'Elliot', 'Hermann', '36365 Ledner Drives', 'Suite 882', 'Port Efrainland', 'DE', 'USA', '63205-7014', '(769)100-7971', '(366)310-2061', 33922034, '00000000002303699411', '1964-12-06', '0000634612', 'Y', 681);

-- Source: custdata.txt line 16 - Customer ID 16
INSERT INTO customer (customer_id, first_name, middle_name, last_name, address_line_1, address_line_2, address_line_3, state_code, country_code, postal_code, phone_number_1, phone_number_2, ssn, government_issued_id, date_of_birth, eft_account_id, primary_cardholder_ind, fico_credit_score)
VALUES (16, 'Carroll', 'Cicero', 'Bergstrom', '06988 Thiel Falls', 'Suite 148', 'Concepcionland', 'VT', 'USA', '84390', '(631)343-8667', '(938)648-3716', 649827971, '00000000002932657521', '1983-04-27', '0012556599', 'Y', 326);

-- Source: custdata.txt line 17 - Customer ID 17
INSERT INTO customer (customer_id, first_name, middle_name, last_name, address_line_1, address_line_2, address_line_3, state_code, country_code, postal_code, phone_number_1, phone_number_2, ssn, government_issued_id, date_of_birth, eft_account_id, primary_cardholder_ind, fico_credit_score)
VALUES (17, 'Sigrid', 'Angeline', 'Mann', '95666 Dare Isle', 'Suite 286', 'New Presley', 'FM', 'USA', '56181-0584', '(087)314-2070', '(541)003-6606', 303334693, '00000000004976063571', '1979-01-26', '0052356071', 'Y', 54);

-- Source: custdata.txt line 18 - Customer ID 18
INSERT INTO customer (customer_id, first_name, middle_name, last_name, address_line_1, address_line_2, address_line_3, state_code, country_code, postal_code, phone_number_1, phone_number_2, ssn, government_issued_id, date_of_birth, eft_account_id, primary_cardholder_ind, fico_credit_score)
VALUES (18, 'Emile', 'Jairo', 'White', '133 Bergnaum Square', 'Apt. 328', 'Hansenville', 'AP', 'USA', '96003-5867', '(303)654-3323', '(520)186-2176', 385849271, '00000000000883418211', '1987-03-25', '0086459831', 'Y', 340);

-- Source: custdata.txt line 19 - Customer ID 19
INSERT INTO customer (customer_id, first_name, middle_name, last_name, address_line_1, address_line_2, address_line_3, state_code, country_code, postal_code, phone_number_1, phone_number_2, ssn, government_issued_id, date_of_birth, eft_account_id, primary_cardholder_ind, fico_credit_score)
VALUES (19, 'Hadley', 'Sigrid', 'Hamill', '6273 Ondricka Meadows', 'Apt. 130', 'New Arturoshire', 'RI', 'USA', '48161', '(817)452-4986', '(724)901-6019', 439569907, '00000000002701763871', '1991-01-07', '0036492057', 'Y', 259);

-- Source: custdata.txt line 20 - Customer ID 20
INSERT INTO customer (customer_id, first_name, middle_name, last_name, address_line_1, address_line_2, address_line_3, state_code, country_code, postal_code, phone_number_1, phone_number_2, ssn, government_issued_id, date_of_birth, eft_account_id, primary_cardholder_ind, fico_credit_score)
VALUES (20, 'Carter', 'Oren', 'Veum', '5845 Allison Valleys', 'Suite 934', 'Mitchellmouth', 'MH', 'USA', '72362', '(618)994-0531', '(571)695-4136', 717778238, '00000000003426612931', '1996-04-14', '0036749754', 'Y', 493);

-- Source: custdata.txt line 21 - Customer ID 21
INSERT INTO customer (customer_id, first_name, middle_name, last_name, address_line_1, address_line_2, address_line_3, state_code, country_code, postal_code, phone_number_1, phone_number_2, ssn, government_issued_id, date_of_birth, eft_account_id, primary_cardholder_ind, fico_credit_score)
VALUES (21, 'Jerrold', 'Adolphus', 'Maggio', '401 Haylie Crest', 'Apt. 320', 'North Myrnaton', 'CA', 'USA', '72407', '(399)526-3254', '(326)193-1118', 336490822, '00000000000276562601', '1977-11-15', '0011744660', 'Y', 163);

-- Source: custdata.txt line 22 - Customer ID 22
INSERT INTO customer (customer_id, first_name, middle_name, last_name, address_line_1, address_line_2, address_line_3, state_code, country_code, postal_code, phone_number_1, phone_number_2, ssn, government_issued_id, date_of_birth, eft_account_id, primary_cardholder_ind, fico_credit_score)
VALUES (22, 'Allene', 'Icie', 'Brown', '4467 Donnie Crossroad', 'Apt. 437', 'Anabelton', 'MD', 'USA', '01993-9116', '(231)251-5792', '(494)652-0009', 292059024, '00000000006911598531', '1994-02-20', '0024791470', 'Y', 597);

-- Source: custdata.txt line 23 - Customer ID 23
INSERT INTO customer (customer_id, first_name, middle_name, last_name, address_line_1, address_line_2, address_line_3, state_code, country_code, postal_code, phone_number_1, phone_number_2, ssn, government_issued_id, date_of_birth, eft_account_id, primary_cardholder_ind, fico_credit_score)
VALUES (23, 'Johnson', 'Blanca', 'Ruecker', '2433 Jacobi Forks', 'Apt. 845', 'Hendersonbury', 'KS', 'USA', '78239-9466', '(981)873-1589', '(131)638-5974', 944154289, '00000000002689671221', '1998-12-07', '0075158529', 'Y', 337);

-- Source: custdata.txt line 24 - Customer ID 24
INSERT INTO customer (customer_id, first_name, middle_name, last_name, address_line_1, address_line_2, address_line_3, state_code, country_code, postal_code, phone_number_1, phone_number_2, ssn, government_issued_id, date_of_birth, eft_account_id, primary_cardholder_ind, fico_credit_score)
VALUES (24, 'Stefanie', 'Verla', 'Dickinson', '6367 Stracke River', 'Apt. 444', 'East Otho', 'KS', 'USA', '15414', '(617)348-9142', '(330)116-5634', 17590544, '00000000004392446331', '1996-01-24', '0005459662', 'Y', 711);

-- Source: custdata.txt line 25 - Customer ID 25
INSERT INTO customer (customer_id, first_name, middle_name, last_name, address_line_1, address_line_2, address_line_3, state_code, country_code, postal_code, phone_number_1, phone_number_2, ssn, government_issued_id, date_of_birth, eft_account_id, primary_cardholder_ind, fico_credit_score)
VALUES (25, 'Elliott', 'Fermin', 'Howell', '9524 McKenzie Lakes', 'Suite 245', 'West Alexa', 'NH', 'USA', '75721-7382', '(092)336-8599', '(311)969-1460', 788820436, '00000000005482230481', '1989-03-27', '0032297533', 'Y', 355);

-- Source: custdata.txt line 26 - Customer ID 26
INSERT INTO customer (customer_id, first_name, middle_name, last_name, address_line_1, address_line_2, address_line_3, state_code, country_code, postal_code, phone_number_1, phone_number_2, ssn, government_issued_id, date_of_birth, eft_account_id, primary_cardholder_ind, fico_credit_score)
VALUES (26, 'Marjory', 'Damien', 'Stracke', '30161 Bogan Canyon', 'Suite 916', 'Walshberg', 'IL', 'USA', '59945', '(584)772-2867', '(819)733-9809', 840478806, '00000000009474116261', '1990-03-17', '0060808858', 'Y', 1);

-- Source: custdata.txt line 27 - Customer ID 27
INSERT INTO customer (customer_id, first_name, middle_name, last_name, address_line_1, address_line_2, address_line_3, state_code, country_code, postal_code, phone_number_1, phone_number_2, ssn, government_issued_id, date_of_birth, eft_account_id, primary_cardholder_ind, fico_credit_score)
VALUES (27, 'Ward', 'Henri', 'Jones', '210 Amaya Turnpike', 'Suite 180', 'Port Dwight', 'GU', 'USA', '07923-8822', '(935)027-1145', '(103)537-5007', 980161210, '00000000008815587571', '1986-11-08', '0050024139', 'Y', 78);

-- Source: custdata.txt line 28 - Customer ID 28
INSERT INTO customer (customer_id, first_name, middle_name, last_name, address_line_1, address_line_2, address_line_3, state_code, country_code, postal_code, phone_number_1, phone_number_2, ssn, government_issued_id, date_of_birth, eft_account_id, primary_cardholder_ind, fico_credit_score)
VALUES (28, 'Hester', 'Vesta', 'Hane', '06816 Ursula Meadows', 'Suite 605', 'South Aurore', 'AS', 'USA', '77442-7954', '(122)357-7257', '(050)352-6579', 677986013, '00000000005141877961', '1991-06-05', '0026946180', 'Y', 114);

-- Source: custdata.txt line 29 - Customer ID 29
INSERT INTO customer (customer_id, first_name, middle_name, last_name, address_line_1, address_line_2, address_line_3, state_code, country_code, postal_code, phone_number_1, phone_number_2, ssn, government_issued_id, date_of_birth, eft_account_id, primary_cardholder_ind, fico_credit_score)
VALUES (29, 'Rickie', 'Otho', 'Daugherty', '676 Funk Curve', 'Apt. 375', 'Hayesstad', 'NH', 'USA', '01226', '(418)291-9023', '(795)634-7776', 15027332, '00000000000627456551', '1973-04-05', '0067736493', 'Y', 552);

-- Source: custdata.txt line 30 - Customer ID 30
INSERT INTO customer (customer_id, first_name, middle_name, last_name, address_line_1, address_line_2, address_line_3, state_code, country_code, postal_code, phone_number_1, phone_number_2, ssn, government_issued_id, date_of_birth, eft_account_id, primary_cardholder_ind, fico_credit_score)
VALUES (30, 'Layla', 'Dannie', 'Ullrich', '269 Eleazar Circle', 'Apt. 817', 'Kutchland', 'AK', 'USA', '64266', '(330)408-6966', '(413)347-7306', 866102152, '00000000004920216861', '1965-11-28', '0050520060', 'Y', 133);

-- Source: custdata.txt line 31 - Customer ID 31
INSERT INTO customer (customer_id, first_name, middle_name, last_name, address_line_1, address_line_2, address_line_3, state_code, country_code, postal_code, phone_number_1, phone_number_2, ssn, government_issued_id, date_of_birth, eft_account_id, primary_cardholder_ind, fico_credit_score)
VALUES (31, 'Lucious', 'Otto', 'O''Connell', '919 Swift Valleys', 'Suite 548', 'Hermanborough', 'MS', 'USA', '56133-5636', '(259)414-9625', '(118)946-9264', 357462348, '00000000006183105391', '1976-08-03', '0092999757', 'Y', 58);

-- Source: custdata.txt line 32 - Customer ID 32
INSERT INTO customer (customer_id, first_name, middle_name, last_name, address_line_1, address_line_2, address_line_3, state_code, country_code, postal_code, phone_number_1, phone_number_2, ssn, government_issued_id, date_of_birth, eft_account_id, primary_cardholder_ind, fico_credit_score)
VALUES (32, 'Stephany', 'Meda', 'Fisher', '63452 Kenny Streets', 'Apt. 116', 'Predovicburgh', 'AK', 'USA', '85943-7605', '(202)436-5156', '(246)296-3533', 146204208, '00000000002062003411', '1980-11-19', '0035970593', 'Y', 221);

-- Source: custdata.txt line 33 - Customer ID 33
INSERT INTO customer (customer_id, first_name, middle_name, last_name, address_line_1, address_line_2, address_line_3, state_code, country_code, postal_code, phone_number_1, phone_number_2, ssn, government_issued_id, date_of_birth, eft_account_id, primary_cardholder_ind, fico_credit_score)
VALUES (33, 'Bernice', 'Norbert', 'Herman', '877 Kassandra Ranch', 'Suite 956', 'Haleyport', 'AR', 'USA', '19113-4329', '(836)743-5487', '(640)208-1176', 144195105, '00000000004006054291', '1988-05-19', '0065245171', 'Y', 469);

-- Source: custdata.txt line 34 - Customer ID 34
INSERT INTO customer (customer_id, first_name, middle_name, last_name, address_line_1, address_line_2, address_line_3, state_code, country_code, postal_code, phone_number_1, phone_number_2, ssn, government_issued_id, date_of_birth, eft_account_id, primary_cardholder_ind, fico_credit_score)
VALUES (34, 'Faustino', 'Jess', 'Schmidt', '44132 Michel Square', 'Suite 007', 'South Margarettaburgh', 'ME', 'USA', '49544-2869', '(179)036-5135', '(986)905-0112', 548088300, '00000000001598825331', '1994-03-21', '0067445089', 'Y', 104);

-- Source: custdata.txt line 35 - Customer ID 35
INSERT INTO customer (customer_id, first_name, middle_name, last_name, address_line_1, address_line_2, address_line_3, state_code, country_code, postal_code, phone_number_1, phone_number_2, ssn, government_issued_id, date_of_birth, eft_account_id, primary_cardholder_ind, fico_credit_score)
VALUES (35, 'Angelica', 'Damaris', 'Dach', '396 Pearl Loop', 'Suite 383', 'Pfefferhaven', 'LA', 'USA', '46142', '(303)480-9098', '(637)710-7367', 220547115, '00000000009771448391', '1987-06-23', '0047435332', 'Y', 793);

-- Source: custdata.txt line 36 - Customer ID 36
INSERT INTO customer (customer_id, first_name, middle_name, last_name, address_line_1, address_line_2, address_line_3, state_code, country_code, postal_code, phone_number_1, phone_number_2, ssn, government_issued_id, date_of_birth, eft_account_id, primary_cardholder_ind, fico_credit_score)
VALUES (36, 'Toney', 'Emerald', 'Gerhold', '35943 Raleigh Harbor', 'Apt. 116', 'Lake Derekburgh', 'AL', 'USA', '10932-0480', '(034)271-9180', '(507)529-4523', 420360688, '00000000009420292101', '1991-03-31', '0066461979', 'Y', 266);

-- Source: custdata.txt line 37 - Customer ID 37
INSERT INTO customer (customer_id, first_name, middle_name, last_name, address_line_1, address_line_2, address_line_3, state_code, country_code, postal_code, phone_number_1, phone_number_2, ssn, government_issued_id, date_of_birth, eft_account_id, primary_cardholder_ind, fico_credit_score)
VALUES (37, 'Shany', 'Darby', 'Walker', '91196 Heaney Turnpike', 'Suite 814', 'Lubowitzberg', 'NV', 'USA', '11857-8177', '(052)759-5167', '(706)896-1282', 891897974, '00000000005243126321', '1984-12-09', '0066111704', 'Y', 653);

-- Source: custdata.txt line 38 - Customer ID 38
INSERT INTO customer (customer_id, first_name, middle_name, last_name, address_line_1, address_line_2, address_line_3, state_code, country_code, postal_code, phone_number_1, phone_number_2, ssn, government_issued_id, date_of_birth, eft_account_id, primary_cardholder_ind, fico_credit_score)
VALUES (38, 'Angela', 'Ceasar', 'Ankunding', '65482 Zoila Skyway', 'Apt. 054', 'East Malachi', 'VA', 'USA', '63928-0008', '(316)640-2650', '(148)111-1148', 764307306, '00000000003355621411', '1990-05-28', '0018048939', 'Y', 446);

-- Source: custdata.txt line 39 - Customer ID 39
INSERT INTO customer (customer_id, first_name, middle_name, last_name, address_line_1, address_line_2, address_line_3, state_code, country_code, postal_code, phone_number_1, phone_number_2, ssn, government_issued_id, date_of_birth, eft_account_id, primary_cardholder_ind, fico_credit_score)
VALUES (39, 'Aliyah', 'Horace', 'Berge', '5761 Pasquale Trail', 'Apt. 616', 'New Sabryna', 'IA', 'USA', '74267', '(089)096-3287', '(768)959-4733', 510793388, '00000000005532544031', '1972-08-26', '0061869530', 'Y', 475);

-- Source: custdata.txt line 40 - Customer ID 40
INSERT INTO customer (customer_id, first_name, middle_name, last_name, address_line_1, address_line_2, address_line_3, state_code, country_code, postal_code, phone_number_1, phone_number_2, ssn, government_issued_id, date_of_birth, eft_account_id, primary_cardholder_ind, fico_credit_score)
VALUES (40, 'Davon', 'Demond', 'Emmerich', '23499 Beer Views', 'Suite 816', 'Erniechester', 'TX', 'USA', '87156-8689', '(463)762-3017', '(419)414-2177', 54960660, '00000000003983532991', '1992-01-26', '0087069976', 'Y', 284);

-- Source: custdata.txt line 41 - Customer ID 41
INSERT INTO customer (customer_id, first_name, middle_name, last_name, address_line_1, address_line_2, address_line_3, state_code, country_code, postal_code, phone_number_1, phone_number_2, ssn, government_issued_id, date_of_birth, eft_account_id, primary_cardholder_ind, fico_credit_score)
VALUES (41, 'Devonte', 'Cristobal', 'Stiedemann', '8968 Howell Ville', 'Apt. 558', 'Herminiaborough', 'MO', 'USA', '98913', '(824)738-1476', '(313)906-4071', 696876829, '00000000006085584601', '1994-08-04', '0013336826', 'Y', 370);

-- Source: custdata.txt line 42 - Customer ID 42
INSERT INTO customer (customer_id, first_name, middle_name, last_name, address_line_1, address_line_2, address_line_3, state_code, country_code, postal_code, phone_number_1, phone_number_2, ssn, government_issued_id, date_of_birth, eft_account_id, primary_cardholder_ind, fico_credit_score)
VALUES (42, 'Haylie', 'Pearl', 'Weber', '0064 Tremblay Hollow', 'Suite 536', 'Tremblayville', 'MI', 'USA', '67009', '(890)849-9088', '(659)664-6163', 695683876, '00000000006673699471', '1964-11-26', '0063858695', 'Y', 282);

-- Source: custdata.txt line 43 - Customer ID 43
INSERT INTO customer (customer_id, first_name, middle_name, last_name, address_line_1, address_line_2, address_line_3, state_code, country_code, postal_code, phone_number_1, phone_number_2, ssn, government_issued_id, date_of_birth, eft_account_id, primary_cardholder_ind, fico_credit_score)
VALUES (43, 'Grayce', 'Devonte', 'West', '20867 Torp Views', 'Apt. 607', 'West Daphnee', 'VT', 'USA', '73113', '(336)773-9537', '(838)673-3616', 628950839, '00000000009815226021', '1965-11-21', '0041195869', 'Y', 394);

-- Source: custdata.txt line 44 - Customer ID 44
INSERT INTO customer (customer_id, first_name, middle_name, last_name, address_line_1, address_line_2, address_line_3, state_code, country_code, postal_code, phone_number_1, phone_number_2, ssn, government_issued_id, date_of_birth, eft_account_id, primary_cardholder_ind, fico_credit_score)
VALUES (44, 'Braden', 'Keshaun', 'Tromp', '0030 Jenkins Mall', 'Suite 638', 'West Jodiemouth', 'NC', 'USA', '63006', '(826)169-0803', '(899)568-7859', 622819849, '00000000008991945461', '1975-09-07', '0078330773', 'Y', 557);

-- Source: custdata.txt line 45 - Customer ID 45
INSERT INTO customer (customer_id, first_name, middle_name, last_name, address_line_1, address_line_2, address_line_3, state_code, country_code, postal_code, phone_number_1, phone_number_2, ssn, government_issued_id, date_of_birth, eft_account_id, primary_cardholder_ind, fico_credit_score)
VALUES (45, 'Whitney', 'Jaydon', 'Waters', '37677 Osinski Expressway', 'Apt. 664', 'Bayerfurt', 'NM', 'USA', '76815', '(296)226-3093', '(308)943-3308', 730423945, '00000000005071026791', '1982-10-12', '0004815806', 'Y', 742);

-- Source: custdata.txt line 46 - Customer ID 46
INSERT INTO customer (customer_id, first_name, middle_name, last_name, address_line_1, address_line_2, address_line_3, state_code, country_code, postal_code, phone_number_1, phone_number_2, ssn, government_issued_id, date_of_birth, eft_account_id, primary_cardholder_ind, fico_credit_score)
VALUES (46, 'Jeramie', 'Khalil', 'Zboncak', '09333 Myrl Loop', 'Suite 838', 'South Pattieport', 'WI', 'USA', '70437', '(699)636-7655', '(733)549-3978', 742826931, '00000000003693308031', '1991-04-02', '0098854821', 'Y', 509);

-- Source: custdata.txt line 47 - Customer ID 47
INSERT INTO customer (customer_id, first_name, middle_name, last_name, address_line_1, address_line_2, address_line_3, state_code, country_code, postal_code, phone_number_1, phone_number_2, ssn, government_issued_id, date_of_birth, eft_account_id, primary_cardholder_ind, fico_credit_score)
VALUES (47, 'Ezra', 'Marcelino', 'Hettinger', '1663 Simonis Flat', 'Suite 994', 'North Devon', 'WY', 'USA', '85913', '(952)169-4799', '(772)988-8473', 742803476, '00000000004833476931', '1961-11-30', '0077969765', 'Y', 669);

-- Source: custdata.txt line 48 - Customer ID 48
INSERT INTO customer (customer_id, first_name, middle_name, last_name, address_line_1, address_line_2, address_line_3, state_code, country_code, postal_code, phone_number_1, phone_number_2, ssn, government_issued_id, date_of_birth, eft_account_id, primary_cardholder_ind, fico_credit_score)
VALUES (48, 'Kenna', 'Madalyn', 'Goodwin', '0698 Langosh Haven', 'Apt. 181', 'Lake Leonorland', 'PA', 'USA', '17879', '(690)889-8668', '(084)756-8636', 773296681, '00000000005851125111', '1965-07-06', '0006584813', 'Y', 381);

-- Source: custdata.txt line 49 - Customer ID 49
INSERT INTO customer (customer_id, first_name, middle_name, last_name, address_line_1, address_line_2, address_line_3, state_code, country_code, postal_code, phone_number_1, phone_number_2, ssn, government_issued_id, date_of_birth, eft_account_id, primary_cardholder_ind, fico_credit_score)
VALUES (49, 'Kayley', 'Geovanny', 'Osinski', '5380 Lue Ramp', 'Suite 876', 'Lake Aracelishire', 'HI', 'USA', '97072', '(906)844-1042', '(093)516-4346', 797726039, '00000000006677698881', '1989-01-27', '0099012370', 'Y', 645);

-- Source: custdata.txt line 50 - Customer ID 50
INSERT INTO customer (customer_id, first_name, middle_name, last_name, address_line_1, address_line_2, address_line_3, state_code, country_code, postal_code, phone_number_1, phone_number_2, ssn, government_issued_id, date_of_birth, eft_account_id, primary_cardholder_ind, fico_credit_score)
VALUES (50, 'Deshawn', 'Jarvis', 'Bogan', '7408 Flatley Throughway', 'Apt. 542', 'North Gail', 'NY', 'USA', '72516', '(408)382-1605', '(803)555-6906', 410468001, '00000000001018651601', '1982-07-23', '0003815849', 'Y', 733);

-- ===================================================================================
-- SECTION 5: Master Entity Tables - Account Data
-- ===================================================================================
-- Source: app/data/ASCII/acctdata.txt
-- Format: Fixed-width 300-byte records from COBOL copybook CVACT01Y.cpy
-- Records: 50 account records
-- Field Layout:
--   Pos 1-11: account_id (11 digits)
--   Pos 12: active_status (1 char Y/N)
--   Pos 13-24: current_balance (S9(10)V99 zoned decimal with sign overpunch)
--   Pos 25-36: credit_limit (S9(10)V99 zoned decimal)
--   Pos 37-48: cash_credit_limit (S9(10)V99 zoned decimal)
--   Pos 49-58: open_date (10 chars YYYY-MM-DD)
--   Pos 59-68: expiration_date (10 chars YYYY-MM-DD)
--   Pos 69-78: reissue_date (10 chars YYYY-MM-DD)
--   Pos 79-90: current_cycle_credit (S9(10)V99 zoned decimal)
--   Pos 91-102: current_cycle_debit (S9(10)V99 zoned decimal)
--   Pos 103-112: postal_code (10 chars)
--   Pos 113-122: group_id (10 chars)
-- Note: Zoned decimal sign overpunch - '{' represents positive 0, '}' negative 0
-- Note: customer_id derived from account_id (1:1 relationship for seed data)
-- ===================================================================================

-- Source: acctdata.txt line 1 - Account ID 1
INSERT INTO account (account_id, customer_id, active_status, current_balance, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, current_cycle_credit, current_cycle_debit, postal_code, group_id)
VALUES (1, 1, 'Y', 194.00, 202.00, 102.00, '2014-11-20', '2025-05-20', '2025-05-20', 0.00, 0.00, 'A000000000', '          ');

-- Source: acctdata.txt line 2 - Account ID 2
INSERT INTO account (account_id, customer_id, active_status, current_balance, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, current_cycle_credit, current_cycle_debit, postal_code, group_id)
VALUES (2, 2, 'Y', 158.00, 613.00, 544.80, '2013-06-19', '2024-08-11', '2024-08-11', 0.00, 0.00, 'A000000000', '          ');

-- Source: acctdata.txt line 3 - Account ID 3
INSERT INTO account (account_id, customer_id, active_status, current_balance, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, current_cycle_credit, current_cycle_debit, postal_code, group_id)
VALUES (3, 3, 'Y', 147.00, 490.90, 53.80, '2013-08-23', '2024-01-10', '2024-01-10', 0.00, 0.00, 'A000000000', '          ');

-- Source: acctdata.txt line 4 - Account ID 4
INSERT INTO account (account_id, customer_id, active_status, current_balance, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, current_cycle_credit, current_cycle_debit, postal_code, group_id)
VALUES (4, 4, 'Y', 40.00, 350.30, 278.90, '2012-11-17', '2023-12-16', '2023-12-16', 0.00, 0.00, 'A000000000', '          ');

-- Source: acctdata.txt line 5 - Account ID 5
INSERT INTO account (account_id, customer_id, active_status, current_balance, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, current_cycle_credit, current_cycle_debit, postal_code, group_id)
VALUES (5, 5, 'Y', 345.00, 381.90, 243.00, '2012-10-03', '2025-03-09', '2025-03-09', 0.00, 0.00, 'A000000000', '          ');

-- Source: acctdata.txt line 6 - Account ID 6
INSERT INTO account (account_id, customer_id, active_status, current_balance, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, current_cycle_credit, current_cycle_debit, postal_code, group_id)
VALUES (6, 6, 'Y', 218.00, 358.40, 294.80, '2017-12-23', '2025-10-08', '2025-10-08', 0.00, 0.00, 'A000000000', '          ');

-- Source: acctdata.txt line 7 - Account ID 7
INSERT INTO account (account_id, customer_id, active_status, current_balance, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, current_cycle_credit, current_cycle_debit, postal_code, group_id)
VALUES (7, 7, 'Y', 193.00, 206.50, 26.40, '2012-10-12', '2024-12-13', '2024-12-13', 0.00, 0.00, 'A000000000', '          ');

-- Source: acctdata.txt line 8 - Account ID 8
INSERT INTO account (account_id, customer_id, active_status, current_balance, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, current_cycle_credit, current_cycle_debit, postal_code, group_id)
VALUES (8, 8, 'Y', 605.00, 610.40, 131.80, '2012-01-04', '2024-05-20', '2024-05-20', 0.00, 0.00, 'A000000000', '          ');

-- Source: acctdata.txt line 9 - Account ID 9
INSERT INTO account (account_id, customer_id, active_status, current_balance, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, current_cycle_credit, current_cycle_debit, postal_code, group_id)
VALUES (9, 9, 'Y', 560.00, 820.10, 206.50, '2016-08-27', '2024-12-27', '2024-12-27', 0.00, 0.00, 'A000000000', '          ');

-- Source: acctdata.txt line 10 - Account ID 10
INSERT INTO account (account_id, customer_id, active_status, current_balance, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, current_cycle_credit, current_cycle_debit, postal_code, group_id)
VALUES (10, 10, 'Y', 159.00, 540.10, 444.20, '2015-09-13', '2023-01-27', '2023-01-27', 0.00, 0.00, 'A000000000', '          ');

-- Source: acctdata.txt line 11 - Account ID 11
INSERT INTO account (account_id, customer_id, active_status, current_balance, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, current_cycle_credit, current_cycle_debit, postal_code, group_id)
VALUES (11, 11, 'Y', 249.00, 538.60, 323.60, '2014-06-05', '2024-11-18', '2024-11-18', 0.00, 0.00, 'A000000000', '          ');

-- Source: acctdata.txt line 12 - Account ID 12
INSERT INTO account (account_id, customer_id, active_status, current_balance, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, current_cycle_credit, current_cycle_debit, postal_code, group_id)
VALUES (12, 12, 'Y', 236.00, 551.80, 414.10, '2013-05-08', '2023-07-02', '2023-07-02', 0.00, 0.00, 'A000000000', '          ');

-- Source: acctdata.txt line 13 - Account ID 13
INSERT INTO account (account_id, customer_id, active_status, current_balance, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, current_cycle_credit, current_cycle_debit, postal_code, group_id)
VALUES (13, 13, 'Y', 215.00, 241.30, 145.30, '2015-10-06', '2024-01-20', '2024-01-20', 0.00, 0.00, 'A000000000', '          ');

-- Source: acctdata.txt line 14 - Account ID 14
INSERT INTO account (account_id, customer_id, active_status, current_balance, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, current_cycle_credit, current_cycle_debit, postal_code, group_id)
VALUES (14, 14, 'Y', 218.00, 242.10, 32.70, '2018-08-10', '2024-04-23', '2024-04-23', 0.00, 0.00, 'A000000000', '          ');

-- Source: acctdata.txt line 15 - Account ID 15
INSERT INTO account (account_id, customer_id, active_status, current_balance, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, current_cycle_credit, current_cycle_debit, postal_code, group_id)
VALUES (15, 15, 'Y', 84.00, 221.90, 142.70, '2019-04-02', '2025-05-20', '2025-05-20', 0.00, 0.00, 'A000000000', '          ');

-- Source: acctdata.txt line 16 - Account ID 16
INSERT INTO account (account_id, customer_id, active_status, current_balance, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, current_cycle_credit, current_cycle_debit, postal_code, group_id)
VALUES (16, 16, 'Y', 177.00, 273.60, 175.90, '2016-12-26', '2025-06-20', '2025-06-20', 0.00, 0.00, 'A000000000', '          ');

-- Source: acctdata.txt line 17 - Account ID 17
INSERT INTO account (account_id, customer_id, active_status, current_balance, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, current_cycle_credit, current_cycle_debit, postal_code, group_id)
VALUES (17, 17, 'Y', 125.00, 127.00, 93.30, '2009-02-18', '2024-12-22', '2024-12-22', 0.00, 0.00, 'A000000000', '          ');

-- Source: acctdata.txt line 18 - Account ID 18
INSERT INTO account (account_id, customer_id, active_status, current_balance, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, current_cycle_credit, current_cycle_debit, postal_code, group_id)
VALUES (18, 18, 'Y', 247.00, 351.30, 273.20, '2013-03-23', '2024-04-17', '2024-04-17', 0.00, 0.00, 'A000000000', '          ');

-- Source: acctdata.txt line 19 - Account ID 19
INSERT INTO account (account_id, customer_id, active_status, current_balance, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, current_cycle_credit, current_cycle_debit, postal_code, group_id)
VALUES (19, 19, 'Y', 179.00, 491.30, 323.10, '2010-08-25', '2025-02-13', '2025-02-13', 0.00, 0.00, 'A000000000', '          ');

-- Source: acctdata.txt line 20 - Account ID 20
INSERT INTO account (account_id, customer_id, active_status, current_balance, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, current_cycle_credit, current_cycle_debit, postal_code, group_id)
VALUES (20, 20, 'Y', 193.00, 326.40, 230.10, '2010-05-13', '2025-05-23', '2025-05-23', 0.00, 0.00, 'A000000000', '          ');

-- Source: acctdata.txt line 21 - Account ID 21
INSERT INTO account (account_id, customer_id, active_status, current_balance, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, current_cycle_credit, current_cycle_debit, postal_code, group_id)
VALUES (21, 21, 'Y', 112.00, 1264.00, 180.00, '2011-10-19', '2023-01-06', '2023-01-06', 0.00, 0.00, 'A000000000', '          ');

-- Source: acctdata.txt line 22 - Account ID 22
INSERT INTO account (account_id, customer_id, active_status, current_balance, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, current_cycle_credit, current_cycle_debit, postal_code, group_id)
VALUES (22, 22, 'Y', 55.00, 8599.00, 4712.00, '2016-11-21', '2025-12-28', '2025-12-28', 0.00, 0.00, 'A000000000', '          ');

-- Source: acctdata.txt line 23 - Account ID 23
INSERT INTO account (account_id, customer_id, active_status, current_balance, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, current_cycle_credit, current_cycle_debit, postal_code, group_id)
VALUES (23, 23, 'Y', 104.00, 3377.00, 2904.00, '2012-03-15', '2025-03-18', '2025-03-18', 0.00, 0.00, 'A000000000', '          ');

-- Source: acctdata.txt line 24 - Account ID 24
INSERT INTO account (account_id, customer_id, active_status, current_balance, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, current_cycle_credit, current_cycle_debit, postal_code, group_id)
VALUES (24, 24, 'Y', 400.00, 5174.00, 4129.00, '2015-08-08', '2025-02-11', '2025-02-11', 0.00, 0.00, 'A000000000', '          ');

-- Source: acctdata.txt line 25 - Account ID 25
INSERT INTO account (account_id, customer_id, active_status, current_balance, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, current_cycle_credit, current_cycle_debit, postal_code, group_id)
VALUES (25, 25, 'Y', 61.00, 8194.00, 6582.00, '2012-10-26', '2025-07-10', '2025-07-10', 0.00, 0.00, 'A000000000', '          ');

-- Source: acctdata.txt line 26 - Account ID 26
INSERT INTO account (account_id, customer_id, active_status, current_balance, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, current_cycle_credit, current_cycle_debit, postal_code, group_id)
VALUES (26, 26, 'Y', 46.00, 2181.00, 1375.00, '2009-04-20', '2024-12-19', '2024-12-19', 0.00, 0.00, 'A000000000', '          ');

-- Source: acctdata.txt line 27 - Account ID 27
INSERT INTO account (account_id, customer_id, active_status, current_balance, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, current_cycle_credit, current_cycle_debit, postal_code, group_id)
VALUES (27, 27, 'Y', 284.00, 5572.00, 2075.00, '2012-09-30', '2025-07-13', '2025-07-13', 0.00, 0.00, 'A000000000', '          ');

-- Source: acctdata.txt line 28 - Account ID 28
INSERT INTO account (account_id, customer_id, active_status, current_balance, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, current_cycle_credit, current_cycle_debit, postal_code, group_id)
VALUES (28, 28, 'Y', 68.00, 868.00, 547.00, '2015-05-20', '2024-05-09', '2024-05-09', 0.00, 0.00, 'A000000000', '          ');

-- Source: acctdata.txt line 29 - Account ID 29
INSERT INTO account (account_id, customer_id, active_status, current_balance, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, current_cycle_credit, current_cycle_debit, postal_code, group_id)
VALUES (29, 29, 'Y', 339.00, 5511.00, 4361.00, '2015-11-03', '2024-06-04', '2024-06-04', 0.00, 0.00, 'A000000000', '          ');

-- Source: acctdata.txt line 30 - Account ID 30
INSERT INTO account (account_id, customer_id, active_status, current_balance, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, current_cycle_credit, current_cycle_debit, postal_code, group_id)
VALUES (30, 30, 'Y', 2.00, 120.00, 93.00, '2011-08-26', '2024-06-27', '2024-06-27', 0.00, 0.00, 'A000000000', '          ');

-- Source: acctdata.txt line 31 - Account ID 31
INSERT INTO account (account_id, customer_id, active_status, current_balance, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, current_cycle_credit, current_cycle_debit, postal_code, group_id)
VALUES (31, 31, 'Y', 31.00, 1140.00, 1077.00, '2017-02-25', '2025-06-08', '2025-06-08', 0.00, 0.00, 'A000000000', '          ');

-- Source: acctdata.txt line 32 - Account ID 32
INSERT INTO account (account_id, customer_id, active_status, current_balance, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, current_cycle_credit, current_cycle_debit, postal_code, group_id)
VALUES (32, 32, 'Y', 30.00, 1175.00, 846.00, '2013-11-10', '2025-05-19', '2025-05-19', 0.00, 0.00, 'A000000000', '          ');

-- Source: acctdata.txt line 33 - Account ID 33
INSERT INTO account (account_id, customer_id, active_status, current_balance, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, current_cycle_credit, current_cycle_debit, postal_code, group_id)
VALUES (33, 33, 'Y', 410.00, 6404.00, 951.00, '2012-10-11', '2025-10-07', '2025-10-07', 0.00, 0.00, 'A000000000', '          ');

-- Source: acctdata.txt line 34 - Account ID 34
INSERT INTO account (account_id, customer_id, active_status, current_balance, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, current_cycle_credit, current_cycle_debit, postal_code, group_id)
VALUES (34, 34, 'Y', 253.00, 3642.00, 2770.00, '2009-05-10', '2025-10-06', '2025-10-06', 0.00, 0.00, 'A000000000', '          ');

-- Source: acctdata.txt line 35 - Account ID 35
INSERT INTO account (account_id, customer_id, active_status, current_balance, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, current_cycle_credit, current_cycle_debit, postal_code, group_id)
VALUES (35, 35, 'Y', 166.00, 1947.00, 1525.00, '2018-02-02', '2025-09-23', '2025-09-23', 0.00, 0.00, 'A000000000', '          ');

-- Source: acctdata.txt line 36 - Account ID 36
INSERT INTO account (account_id, customer_id, active_status, current_balance, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, current_cycle_credit, current_cycle_debit, postal_code, group_id)
VALUES (36, 36, 'Y', 110.00, 3328.00, 839.00, '2018-07-18', '2024-12-23', '2024-12-23', 0.00, 0.00, 'A000000000', '          ');

-- Source: acctdata.txt line 37 - Account ID 37
INSERT INTO account (account_id, customer_id, active_status, current_balance, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, current_cycle_credit, current_cycle_debit, postal_code, group_id)
VALUES (37, 37, 'Y', 7.00, 446.00, 166.00, '2016-09-10', '2023-10-24', '2023-10-24', 0.00, 0.00, 'A000000000', '          ');

-- Source: acctdata.txt line 38 - Account ID 38
INSERT INTO account (account_id, customer_id, active_status, current_balance, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, current_cycle_credit, current_cycle_debit, postal_code, group_id)
VALUES (38, 38, 'Y', 612.00, 6505.00, 3476.00, '2010-08-12', '2023-07-23', '2023-07-23', 0.00, 0.00, 'A000000000', '          ');

-- Source: acctdata.txt line 39 - Account ID 39
INSERT INTO account (account_id, customer_id, active_status, current_balance, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, current_cycle_credit, current_cycle_debit, postal_code, group_id)
VALUES (39, 39, 'Y', 843.00, 9750.00, 6212.00, '2018-08-26', '2025-09-08', '2025-09-08', 0.00, 0.00, 'A000000000', '          ');

-- Source: acctdata.txt line 40 - Account ID 40
INSERT INTO account (account_id, customer_id, active_status, current_balance, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, current_cycle_credit, current_cycle_debit, postal_code, group_id)
VALUES (40, 40, 'Y', 43.00, 5823.00, 1674.00, '2010-02-13', '2023-10-27', '2023-10-27', 0.00, 0.00, 'A000000000', '          ');

-- Source: acctdata.txt line 41 - Account ID 41
INSERT INTO account (account_id, customer_id, active_status, current_balance, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, current_cycle_credit, current_cycle_debit, postal_code, group_id)
VALUES (41, 41, 'Y', 375.00, 6721.00, 3429.00, '2015-02-07', '2023-04-24', '2023-04-24', 0.00, 0.00, 'A000000000', '          ');

-- Source: acctdata.txt line 42 - Account ID 42
INSERT INTO account (account_id, customer_id, active_status, current_balance, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, current_cycle_credit, current_cycle_debit, postal_code, group_id)
VALUES (42, 42, 'Y', 302.00, 6563.00, 5103.00, '2016-09-19', '2025-09-19', '2025-09-19', 0.00, 0.00, 'A000000000', '          ');

-- Source: acctdata.txt line 43 - Account ID 43
INSERT INTO account (account_id, customer_id, active_status, current_balance, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, current_cycle_credit, current_cycle_debit, postal_code, group_id)
VALUES (43, 43, 'Y', 610.00, 6168.00, 1206.00, '2012-04-09', '2025-08-29', '2025-08-29', 0.00, 0.00, 'A000000000', '          ');

-- Source: acctdata.txt line 44 - Account ID 44
INSERT INTO account (account_id, customer_id, active_status, current_balance, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, current_cycle_credit, current_cycle_debit, postal_code, group_id)
VALUES (44, 44, 'Y', 263.00, 6899.00, 4432.00, '2018-12-01', '2024-01-17', '2024-01-17', 0.00, 0.00, 'A000000000', '          ');

-- Source: acctdata.txt line 45 - Account ID 45
INSERT INTO account (account_id, customer_id, active_status, current_balance, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, current_cycle_credit, current_cycle_debit, postal_code, group_id)
VALUES (45, 45, 'Y', 186.00, 2719.00, 688.00, '2010-12-31', '2025-07-09', '2025-07-09', 0.00, 0.00, 'A000000000', '          ');

-- Source: acctdata.txt line 46 - Account ID 46
INSERT INTO account (account_id, customer_id, active_status, current_balance, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, current_cycle_credit, current_cycle_debit, postal_code, group_id)
VALUES (46, 46, 'Y', 396.00, 7007.00, 5438.00, '2013-09-06', '2025-06-20', '2025-06-20', 0.00, 0.00, 'A000000000', '          ');

-- Source: acctdata.txt line 47 - Account ID 47
INSERT INTO account (account_id, customer_id, active_status, current_balance, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, current_cycle_credit, current_cycle_debit, postal_code, group_id)
VALUES (47, 47, 'Y', 32.00, 2338.00, 159.00, '2014-04-03', '2025-08-23', '2025-08-23', 0.00, 0.00, 'A000000000', '          ');

-- Source: acctdata.txt line 48 - Account ID 48
INSERT INTO account (account_id, customer_id, active_status, current_balance, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, current_cycle_credit, current_cycle_debit, postal_code, group_id)
VALUES (48, 48, 'Y', 226.00, 2306.00, 612.00, '2017-03-18', '2025-02-06', '2025-02-06', 0.00, 0.00, 'A000000000', '          ');

-- Source: acctdata.txt line 49 - Account ID 49
INSERT INTO account (account_id, customer_id, active_status, current_balance, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, current_cycle_credit, current_cycle_debit, postal_code, group_id)
VALUES (49, 49, 'Y', 100.00, 9048.00, 4807.00, '2019-04-06', '2023-09-17', '2023-09-17', 0.00, 0.00, 'A000000000', '          ');

-- Source: acctdata.txt line 50 - Account ID 50
INSERT INTO account (account_id, customer_id, active_status, current_balance, credit_limit, cash_credit_limit, open_date, expiration_date, reissue_date, current_cycle_credit, current_cycle_debit, postal_code, group_id)
VALUES (50, 50, 'Y', 492.00, 6169.00, 4587.00, '2011-04-22', '2023-03-09', '2023-03-09', 0.00, 0.00, 'A000000000', '          ');

-- ======================================================================================
-- CARD DATA (50 records)
-- Source: app/data/ASCII/carddata.txt
-- Copybook: app/cpy/CVACT02Y.cpy (150-byte fixed-width records)
-- Target Table: card (src/main/resources/db/migration/V3__create_card_table.sql)
-- Record Layout:
--   Pos 1-16:   card_number (PIC X(16))
--   Pos 17-27:  account_id (PIC 9(11))
--   Pos 28-30:  card_cvv_number (PIC 9(03))
--   Pos 31-80:  embossed_name (PIC X(50))
--   Pos 81-90:  expiration_date (PIC X(10))
--   Pos 91:     active_status (PIC X(01))
-- Foreign Key: account_id references account(account_id)
-- ======================================================================================

-- Source: carddata.txt line 1 - Card linked to Account 50
INSERT INTO card (card_number, account_id, card_cvv_number, embossed_name, expiration_date, active_status)
VALUES ('0500024453765740', 50, '747', 'Aniya Von', '2023-03-09', 'Y');

-- Source: carddata.txt line 2 - Card linked to Account 47
INSERT INTO card (card_number, account_id, card_cvv_number, embossed_name, expiration_date, active_status)
VALUES ('0891844358978686', 47, '851', 'Lloyd Wyman', '2025-08-23', 'Y');

-- Source: carddata.txt line 3 - Card linked to Account 1
INSERT INTO card (card_number, account_id, card_cvv_number, embossed_name, expiration_date, active_status)
VALUES ('1000160031451623', 1, '045', 'Immanuel Kessler', '2025-05-20', 'Y');

-- Source: carddata.txt line 4 - Card linked to Account 24
INSERT INTO card (card_number, account_id, card_cvv_number, embossed_name, expiration_date, active_status)
VALUES ('1021422016718360', 24, '137', 'Emile White', '2023-09-10', 'Y');

-- Source: carddata.txt line 5 - Card linked to Account 41
INSERT INTO card (card_number, account_id, card_cvv_number, embossed_name, expiration_date, active_status)
VALUES ('1109816449453318', 41, '735', 'Ashton Veum', '2023-04-24', 'Y');

-- Source: carddata.txt line 6 - Card linked to Account 39
INSERT INTO card (card_number, account_id, card_cvv_number, embossed_name, expiration_date, active_status)
VALUES ('1326239838990406', 39, '427', 'Lilyan VonRueden', '2025-09-08', 'Y');

-- Source: carddata.txt line 7 - Card linked to Account 22
INSERT INTO card (card_number, account_id, card_cvv_number, embossed_name, expiration_date, active_status)
VALUES ('1477915908267887', 22, '524', 'Jerrold Maggio', '2023-01-06', 'Y');

-- Source: carddata.txt line 8 - Card linked to Account 34
INSERT INTO card (card_number, account_id, card_cvv_number, embossed_name, expiration_date, active_status)
VALUES ('1620529177367216', 34, '196', 'Cindy Cremin', '2025-06-20', 'Y');

-- Source: carddata.txt line 9 - Card linked to Account 37
INSERT INTO card (card_number, account_id, card_cvv_number, embossed_name, expiration_date, active_status)
VALUES ('1667831225871288', 37, '166', 'Madge Schumm', '2023-10-24', 'Y');

-- Source: carddata.txt line 10 - Card linked to Account 44
INSERT INTO card (card_number, account_id, card_cvv_number, embossed_name, expiration_date, active_status)
VALUES ('1676396441356499', 44, '955', 'Chelsea Marks', '2025-12-11', 'Y');

-- Source: carddata.txt line 11 - Card linked to Account 11
INSERT INTO card (card_number, account_id, card_cvv_number, embossed_name, expiration_date, active_status)
VALUES ('1742468614095756', 11, '892', 'Hayden Pfannerstill', '2025-03-12', 'Y');

-- Source: carddata.txt line 12 - Card linked to Account 12
INSERT INTO card (card_number, account_id, card_cvv_number, embossed_name, expiration_date, active_status)
VALUES ('1824764454428931', 12, '230', 'Kelsie Dicki', '2024-05-20', 'Y');

-- Source: carddata.txt line 13 - Card linked to Account 4
INSERT INTO card (card_number, account_id, card_cvv_number, embossed_name, expiration_date, active_status)
VALUES ('1855677187479162', 4, '425', 'Melvin Ondricka', '2024-12-27', 'Y');

-- Source: carddata.txt line 14 - Card linked to Account 10
INSERT INTO card (card_number, account_id, card_cvv_number, embossed_name, expiration_date, active_status)
VALUES ('2207062283169364', 10, '027', 'Keven Bergnaum', '2023-01-27', 'Y');

-- Source: carddata.txt line 15 - Card linked to Account 35
INSERT INTO card (card_number, account_id, card_cvv_number, embossed_name, expiration_date, active_status)
VALUES ('2273154038363546', 35, '401', 'Britney Waters', '2025-08-29', 'Y');

-- Source: carddata.txt line 16 - Card linked to Account 19
INSERT INTO card (card_number, account_id, card_cvv_number, embossed_name, expiration_date, active_status)
VALUES ('2328179898261054', 19, '708', 'Angela Ankunding', '2023-07-23', 'Y');

-- Source: carddata.txt line 17 - Card linked to Account 13
INSERT INTO card (card_number, account_id, card_cvv_number, embossed_name, expiration_date, active_status)
VALUES ('2353925333950767', 13, '735', 'Aubree Hermann', '2025-06-09', 'Y');

-- Source: carddata.txt line 18 - Card linked to Account 36
INSERT INTO card (card_number, account_id, card_cvv_number, embossed_name, expiration_date, active_status)
VALUES ('2489154384768292', 36, '839', 'April Barrows', '2024-12-23', 'Y');

-- Source: carddata.txt line 19 - Card linked to Account 20
INSERT INTO card (card_number, account_id, card_cvv_number, embossed_name, expiration_date, active_status)
VALUES ('2620467994493093', 20, '230', 'Layla Ullrich', '2024-06-27', 'Y');

-- Source: carddata.txt line 20 - Card linked to Account 5
INSERT INTO card (card_number, account_id, card_cvv_number, embossed_name, expiration_date, active_status)
VALUES ('3559483826562509', 5, '027', 'Treva Schowalter', '2025-03-09', 'Y');

-- Source: carddata.txt line 21 - Card linked to Account 7
INSERT INTO card (card_number, account_id, card_cvv_number, embossed_name, expiration_date, active_status)
VALUES ('4859452612877065', 7, '321', 'Cooper Mayert', '2024-12-13', 'Y');

-- Source: carddata.txt line 22 - Card linked to Account 21
INSERT INTO card (card_number, account_id, card_cvv_number, embossed_name, expiration_date, active_status)
VALUES ('5407099850479866', 21, '524', 'Jerrold Maggio', '2023-01-06', 'Y');

-- Source: carddata.txt line 23 - Card linked to Account 46
INSERT INTO card (card_number, account_id, card_cvv_number, embossed_name, expiration_date, active_status)
VALUES ('5656830544981216', 46, '196', 'Cindy Cremin', '2025-06-20', 'Y');

-- Source: carddata.txt line 24 - Card linked to Account 18
INSERT INTO card (card_number, account_id, card_cvv_number, embossed_name, expiration_date, active_status)
VALUES ('5671184478505844', 18, '137', 'Emile White', '2023-09-10', 'Y');

-- Source: carddata.txt line 25 - Card linked to Account 47
INSERT INTO card (card_number, account_id, card_cvv_number, embossed_name, expiration_date, active_status)
VALUES ('5787351228879339', 47, '067', 'Rigoberto Hoeger', '2025-08-23', 'Y');

-- Source: carddata.txt line 26 - Card linked to Account 42
INSERT INTO card (card_number, account_id, card_cvv_number, embossed_name, expiration_date, active_status)
VALUES ('5975117516616077', 42, '426', 'Heather Nienow', '2025-09-19', 'Y');

-- Source: carddata.txt line 27 - Card linked to Account 5
INSERT INTO card (card_number, account_id, card_cvv_number, embossed_name, expiration_date, active_status)
VALUES ('6009619150674526', 5, '021', 'Treva Schowalter', '2025-03-09', 'Y');

-- Source: carddata.txt line 28 - Card linked to Account 15
INSERT INTO card (card_number, account_id, card_cvv_number, embossed_name, expiration_date, active_status)
VALUES ('6349250331648509', 15, '735', 'Aubree Hermann', '2025-06-09', 'Y');

-- Source: carddata.txt line 29 - Card linked to Account 48
INSERT INTO card (card_number, account_id, card_cvv_number, embossed_name, expiration_date, active_status)
VALUES ('6503535181795992', 48, '413', 'Lyric Pacocha', '2025-02-06', 'Y');

-- Source: carddata.txt line 30 - Card linked to Account 30
INSERT INTO card (card_number, account_id, card_cvv_number, embossed_name, expiration_date, active_status)
VALUES ('6509230362553816', 30, '236', 'Layla Ullrich', '2024-06-27', 'Y');

-- Source: carddata.txt line 31 - Card linked to Account 28
INSERT INTO card (card_number, account_id, card_cvv_number, embossed_name, expiration_date, active_status)
VALUES ('6723000463207764', 28, '486', 'Hester Hane', '2024-05-09', 'Y');

-- Source: carddata.txt line 32 - Card linked to Account 16
INSERT INTO card (card_number, account_id, card_cvv_number, embossed_name, expiration_date, active_status)
VALUES ('6727055190616014', 16, '641', 'Carroll Bergstrom', '2024-01-25', 'Y');

-- Source: carddata.txt line 33 - Card linked to Account 33
INSERT INTO card (card_number, account_id, card_cvv_number, embossed_name, expiration_date, active_status)
VALUES ('6832676047698087', 33, '983', 'Bernice Herman', '2025-10-07', 'Y');

-- Source: carddata.txt line 34 - Card linked to Account 31
INSERT INTO card (card_number, account_id, card_cvv_number, embossed_name, expiration_date, active_status)
VALUES ('7026637615032277', 31, '920', 'Lucious O''Connell', '2025-06-08', 'Y');

-- Source: carddata.txt line 35 - Card linked to Account 43
INSERT INTO card (card_number, account_id, card_cvv_number, embossed_name, expiration_date, active_status)
VALUES ('7058267261837752', 43, '401', 'Britney Waters', '2025-08-29', 'Y');

-- Source: carddata.txt line 36 - Card linked to Account 32
INSERT INTO card (card_number, account_id, card_cvv_number, embossed_name, expiration_date, active_status)
VALUES ('7094142751055551', 32, '659', 'Stephany Fisher', '2025-05-19', 'Y');

-- Source: carddata.txt line 37 - Card linked to Account 29
INSERT INTO card (card_number, account_id, card_cvv_number, embossed_name, expiration_date, active_status)
VALUES ('7251508149188883', 29, '717', 'Rickie Daugherty', '2024-06-04', 'Y');

-- Source: carddata.txt line 38 - Card linked to Account 45
INSERT INTO card (card_number, account_id, card_cvv_number, embossed_name, expiration_date, active_status)
VALUES ('7379335634661142', 45, '134', 'Dixie Beier', '2025-07-09', 'Y');

-- Source: carddata.txt line 39 - Card linked to Account 11
INSERT INTO card (card_number, account_id, card_cvv_number, embossed_name, expiration_date, active_status)
VALUES ('7427684863423209', 11, '892', 'Hayden Pfannerstill', '2025-03-12', 'Y');

-- Source: carddata.txt line 40 - Card linked to Account 38
INSERT INTO card (card_number, account_id, card_cvv_number, embossed_name, expiration_date, active_status)
VALUES ('7443870988897530', 38, '708', 'Angela Ankunding', '2023-07-23', 'Y');

-- Source: carddata.txt line 41 - Card linked to Account 26
INSERT INTO card (card_number, account_id, card_cvv_number, embossed_name, expiration_date, active_status)
VALUES ('8040580410348680', 26, '971', 'Marjory Stracke', '2024-12-19', 'Y');

-- Source: carddata.txt line 42 - Card linked to Account 23
INSERT INTO card (card_number, account_id, card_cvv_number, embossed_name, expiration_date, active_status)
VALUES ('8112545834239735', 23, '440', 'Johnson Ruecker', '2025-03-18', 'Y');

-- Source: carddata.txt line 43 - Card linked to Account 49
INSERT INTO card (card_number, account_id, card_cvv_number, embossed_name, expiration_date, active_status)
VALUES ('8262593602473076', 49, '457', 'Immanuel Bednar', '2023-09-17', 'Y');

-- Source: carddata.txt line 44 - Card linked to Account 14
INSERT INTO card (card_number, account_id, card_cvv_number, embossed_name, expiration_date, active_status)
VALUES ('8517866958206008', 14, '955', 'Chelsea Marks', '2025-12-11', 'Y');

-- Source: carddata.txt line 45 - Card linked to Account 8
INSERT INTO card (card_number, account_id, card_cvv_number, embossed_name, expiration_date, active_status)
VALUES ('8931369351894783', 8, '230', 'Kelsie Dicki', '2024-05-20', 'Y');

-- Source: carddata.txt line 46 - Card linked to Account 25
INSERT INTO card (card_number, account_id, card_cvv_number, embossed_name, expiration_date, active_status)
VALUES ('9056297931664011', 25, '931', 'Elliott Howell', '2025-07-10', 'Y');

-- Source: carddata.txt line 47 - Card linked to Account 17
INSERT INTO card (card_number, account_id, card_cvv_number, embossed_name, expiration_date, active_status)
VALUES ('9349107475869214', 17, '218', 'Sigrid Mann', '2025-03-01', 'Y');

-- Source: carddata.txt line 48 - Card linked to Account 9
INSERT INTO card (card_number, account_id, card_cvv_number, embossed_name, expiration_date, active_status)
VALUES ('9501733721429893', 9, '725', 'Melvin Ondricka', '2024-12-27', 'Y');

-- Source: carddata.txt line 49 - Card linked to Account 1
INSERT INTO card (card_number, account_id, card_cvv_number, embossed_name, expiration_date, active_status)
VALUES ('9680294154603697', 1, '045', 'Immanuel Kessler', '2025-05-20', 'Y');

-- Source: carddata.txt line 50 - Card linked to Account 40
INSERT INTO card (card_number, account_id, card_cvv_number, embossed_name, expiration_date, active_status)
VALUES ('9805583408996588', 40, '908', 'Davon Emmerich', '2023-10-27', 'Y');

-- ======================================================================================
-- TRANSACTION DATA (300 records)
-- Source: app/data/ASCII/dailytran.txt
-- Copybook: app/cpy/CVTRA05Y.cpy (350-byte fixed-width records)
-- Target Table: transaction (src/main/resources/db/migration/V4__create_transaction_table.sql)
-- Record Layout:
--   Pos 1-16:   transaction_id (PIC X(16))
--   Pos 17-18:  transaction_type_code (PIC X(02))
--   Pos 19-22:  transaction_category_code (PIC 9(04))
--   Pos 23-32:  source (PIC X(10))
--   Pos 33-132: description (PIC X(100))
--   Pos 133-143: amount (PIC S9(09)V99 - zoned decimal)
--   Pos 144-152: merchant_id (PIC 9(09))
--   Pos 153-202: merchant_name (PIC X(50))
--   Pos 203-252: merchant_city (PIC X(50))
--   Pos 253-262: merchant_postal_code (PIC X(10))
--   Pos 263-278: card_number (PIC X(16))
--   Pos 279-304: original_timestamp (PIC X(26))
--   Pos 305-330: processed_timestamp (PIC X(26))
-- Foreign Key: card_number references card(card_number)
-- Note: Amount field uses COBOL zoned decimal encoding where last character encodes
--       both the final digit and sign: A-I=1-9 positive, J-R=1-9 negative, {=0 positive, }=0 negative
-- ======================================================================================


-- Source: dailytran.txt line 1
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000000683580', '01', 1, 'POS TERM', 'Purchase at Abshire-Lowe', 000000504.77, 800000000, 'Abshire-Lowe', 'North Enoshaven', '72112', '4859452612877065', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 2
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000001774260', '03', 1, 'OPERATOR', 'Return item at Nitzsche, Nicolas and Lowe', -000000919.00, 800000000, 'Nitzsche, Nicolas and Lowe', 'Fidelshire', '53378', '0927987108636232', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 3
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000006292564', '01', 1, 'POS TERM', 'Purchase at Ernser, Roob and Gleason', 000000067.88, 800000000, 'Ernser, Roob and Gleason', 'North Makenziemouth', '78487-7965', '6009619150674526', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 4
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000009101861', '01', 1, 'POS TERM', 'Purchase at Guann LLC', 000000281.77, 800000000, 'Guann LLC', 'South Lynn', '51508-9166', '8040580410348680', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 5
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000010142252', '01', 1, 'POS TERM', 'Purchase at Kertzmann-Schoen', 000000454.66, 800000000, 'Kertzmann-Schoen', 'East Eulahstad', '98754-1089', '5656830544981216', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 6
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000010229018', '01', 1, 'POS TERM', 'Purchase at Gislason-Medhurst', 000000849.99, 800000000, 'Gislason-Medhurst', 'Colleenburgh', '23712-2080', '7379335634661142', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 7
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000016259484', '03', 1, 'OPERATOR', 'Return item at Sipes Inc', -000000056.77, 800000000, 'Sipes Inc', 'Emilioside', '93329', '4011500891777367', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 8
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000017874199', '01', 1, 'POS TERM', 'Purchase at Legros Group', 000000373.66, 800000000, 'Legros Group', 'Carmeloborough', '34849-5127', '8040580410348680', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 9
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000019065428', '03', 1, 'OPERATOR', 'Return item at Turcotte Group', -000000535.88, 800000000, 'Turcotte Group', 'Andrewfurt', '41346-3789', '6503535181795992', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 10
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000021711604', '01', 1, 'POS TERM', 'Purchase at Gleason, Shanahan and Reynolds', 000000416.11, 800000000, 'Gleason, Shanahan and Reynolds', 'Myrticeport', '21768-0823', '9501733721429893', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 11
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000025430891', '01', 1, 'POS TERM', 'Purchase at Beatty-Hessel', 000000094.33, 800000000, 'Beatty-Hessel', 'Simonisport', '52595', '3260763612337560', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 12
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000028097268', '01', 1, 'POS TERM', 'Purchase at Wolf, Cruickshank and Bode', 000000250.22, 800000000, 'Wolf, Cruickshank and Bode', 'Fritzchester', '20195-5156', '7094142751055551', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 13
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000030755266', '01', 1, 'POS TERM', 'Purchase at Ratke LLC', 000000829.55, 800000000, 'Ratke LLC', 'Brendenfort', '35302-6495', '3766281984155154', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 14
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000032979555', '01', 1, 'POS TERM', 'Purchase at Treutel-Leffler', 000000029.44, 800000000, 'Treutel-Leffler', 'New Nicolette', '65014-0045', '6509230362553816', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 15
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000033688127', '01', 1, 'POS TERM', 'Purchase at Schinner-Steuber', 000000958.99, 800000000, 'Schinner-Steuber', 'Schmittchester', '50777-5535', '3766281984155154', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 16
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000040455859', '01', 1, 'POS TERM', 'Purchase at Brekke, Bradtke and Weimann', 000000715.44, 800000000, 'Brekke, Bradtke and Weimann', 'Veummouth', '18481-5013', '1142167692878931', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 17
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000043636099', '03', 1, 'OPERATOR', 'Return item at Nader-Bayer', -000000945.66, 800000000, 'Nader-Bayer', 'Goyetteville', '35324', '2940139362300449', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 18
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000051205286', '01', 1, 'POS TERM', 'Purchase at Goodwin, Von and Krajcik', 000000649.33, 800000000, 'Goodwin, Von and Krajcik', 'Ericmouth', '03874', '7094142751055551', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 19
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000054288996', '01', 1, 'POS TERM', 'Purchase at Cremin and Sons', 000000502.66, 800000000, 'Cremin and Sons', 'Bartonside', '08677', '4534784102713951', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 20
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000054727064', '01', 1, 'POS TERM', 'Purchase at McDermott, Lockman and Weimann', 000000303.11, 800000000, 'McDermott, Lockman and Weimann', 'West Nedra', '05293', '1014086565224350', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 21
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000058866561', '01', 1, 'POS TERM', 'Purchase at Blick-Rippin', 000000183.88, 800000000, 'Blick-Rippin', 'East Julien', '87157', '0500024453765740', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 22
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000060921254', '01', 1, 'POS TERM', 'Purchase at Kihn-Quigley', 000000779.33, 800000000, 'Kihn-Quigley', 'New Katrine', '42756-0584', '5787351228879339', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 23
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000061394789', '03', 1, 'OPERATOR', 'Return item at Heaney-Raynor', -000000070.99, 800000000, 'Heaney-Raynor', 'North Daisy', '28696', '2745303720002090', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 24
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000070754800', '01', 1, 'POS TERM', 'Purchase at Blick, Kris and Gerlach', 000000355.11, 800000000, 'Blick, Kris and Gerlach', 'Lake Shawnabury', '65183-0963', '0923877193247330', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 25
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000072220498', '01', 1, 'POS TERM', 'Purchase at Graham LLC', 000000660.11, 800000000, 'Graham LLC', 'Ozellaside', '89313-0747', '6349250331648509', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 26
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000084515950', '01', 1, 'POS TERM', 'Purchase at Bradtke Group', 000000325.00, 800000000, 'Bradtke Group', 'Gerardland', '63873', '8931369351894783', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 27
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000085824369', '01', 1, 'POS TERM', 'Purchase at Pollich-Mosciski', 000000999.77, 800000000, 'Pollich-Mosciski', 'Georgettemouth', '85890', '3999169246375885', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 28
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000095706092', '01', 1, 'POS TERM', 'Purchase at Swift, Wolf and Goldner', 000000482.44, 800000000, 'Swift, Wolf and Goldner', 'Keeblerborough', '31923-4503', '8931369351894783', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 29
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000099965527', '01', 1, 'POS TERM', 'Purchase at Jaskolski-Rolfson', 000000555.22, 800000000, 'Jaskolski-Rolfson', 'Lake Arjuntown', '90924-2951', '0927987108636232', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 30
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000100915314', '01', 1, 'POS TERM', 'Purchase at Gislason and Daughters', 000000356.22, 800000000, 'Gislason and Daughters', 'Torphyville', '09737', '9805583408996588', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 31
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000107748365', '01', 1, 'POS TERM', 'Purchase at Waelchi and Daughters', 000000274.00, 800000000, 'Waelchi and Daughters', 'Dickensborough', '86052-1154', '7094142751055551', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 32
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000108402349', '01', 1, 'POS TERM', 'Purchase at Lynch-Bode', 000000633.00, 800000000, 'Lynch-Bode', 'New Cieloberg', '85766', '9349107475869214', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 33
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000109340521', '01', 1, 'POS TERM', 'Purchase at Runte and Sons', 000000840.55, 800000000, 'Runte and Sons', 'Lake Chesleyfurt', '94215', '7379335634661142', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 34
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000109506921', '01', 1, 'POS TERM', 'Purchase at Will, Frami and Lynch', 000000769.55, 800000000, 'Will, Frami and Lynch', 'South Cadefort', '47040-3550', '6832676047698087', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 35
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000111054243', '01', 1, 'POS TERM', 'Purchase at Pollich and Sons', 000000948.44, 800000000, 'Pollich and Sons', 'West Burdetteburgh', '51061-7710', '6723000463207764', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 36
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000115716061', '01', 1, 'POS TERM', 'Purchase at Bednar, Marvin and Kozey', 000000401.22, 800000000, 'Bednar, Marvin and Kozey', 'Port Marisolshire', '89976-0867', '2940139362300449', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 37
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000130111733', '01', 1, 'POS TERM', 'Purchase at Rogahn Group', 000000777.33, 800000000, 'Rogahn Group', 'Keltonton', '18842', '0683586198171516', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 38
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000132831571', '03', 1, 'OPERATOR', 'Return item at Boehm-Sanford', -000000215.33, 800000000, 'Boehm-Sanford', 'Winifredville', '93238-7169', '1014086565224350', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 39
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000137879630', '01', 1, 'POS TERM', 'Purchase at Wiza-Langworth', 000000046.66, 800000000, 'Wiza-Langworth', 'South Jayson', '83135', '5671184478505844', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 40
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000139910093', '01', 1, 'POS TERM', 'Purchase at Harris, Johnston and Harris', 000000570.66, 800000000, 'Harris, Johnston and Harris', 'New Aurelia', '81068', '7251508149188883', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 41
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000142315472', '01', 1, 'POS TERM', 'Purchase at Kutch-Farrell', 000000843.66, 800000000, 'Kutch-Farrell', 'Letatown', '39869-9537', '2871968252812490', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 42
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000143386237', '01', 1, 'POS TERM', 'Purchase at Blanda, Nienow and Hilpert', 000000559.88, 800000000, 'Blanda, Nienow and Hilpert', 'Leuschkestad', '24074-5513', '8262593602473076', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 43
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000148803688', '01', 1, 'POS TERM', 'Purchase at Crist Inc', 000000203.44, 800000000, 'Crist Inc', 'Spencerchester', '18577', '6509230362553816', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 44
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000152467982', '01', 1, 'POS TERM', 'Purchase at Kreiger and Sons', 000000168.33, 800000000, 'Kreiger and Sons', 'North Lue', '30616-5176', '2745303720002090', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 45
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000160469204', '01', 1, 'POS TERM', 'Purchase at Greenfelder-Larson', 000000864.44, 800000000, 'Greenfelder-Larson', 'New Mertie', '06860', '8040580410348680', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 46
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000166444519', '01', 1, 'POS TERM', 'Purchase at Wyman, Feest and Moen', 000000183.22, 800000000, 'Wyman, Feest and Moen', 'Haleyborough', '83262-3068', '9056297931664011', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 47
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000169879332', '01', 1, 'POS TERM', 'Purchase at Buckridge, Fisher and Schroeder', 000000256.00, 800000000, 'Buckridge, Fisher and Schroeder', 'Port Kiraport', '29568', '6723000463207764', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 48
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000173069364', '01', 1, 'POS TERM', 'Purchase at Runte-Schmidt', 000000985.33, 800000000, 'Runte-Schmidt', 'Krajcikshire', '03491-5716', '8517866958206008', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 49
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000174748684', '01', 1, 'POS TERM', 'Purchase at McLaughlin-Reichel', 000000019.99, 800000000, 'McLaughlin-Reichel', 'Rippinville', '32264-6952', '6727055190616014', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 50
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000183226769', '01', 1, 'POS TERM', 'Purchase at Conroy and Daughters', 000000907.55, 800000000, 'Conroy and Daughters', 'Greenholtborough', '24059-8704', '8040580410348680', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 51
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000184933166', '01', 1, 'POS TERM', 'Purchase at Walker LLC', 000000989.77, 800000000, 'Walker LLC', 'East Tavares', '25508', '7251508149188883', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 52
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000187573156', '01', 1, 'POS TERM', 'Purchase at Cruickshank and Daughters', 000000579.77, 800000000, 'Cruickshank and Daughters', 'Bobbieberg', '45382', '0683586198171516', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 53
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000189414937', '03', 1, 'OPERATOR', 'Return item at Treutel-Douglas', -000000358.44, 800000000, 'Treutel-Douglas', 'Port Mittiestad', '12880-0185', '6349250331648509', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 54
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000191360674', '01', 1, 'POS TERM', 'Purchase at Wyman, Breitenberg and Gusikowski', 000000848.33, 800000000, 'Wyman, Breitenberg and Gusikowski', 'Rosettaberg', '51594-3147', '2745303720002090', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 55
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000192039153', '03', 1, 'OPERATOR', 'Return item at Smith-Upton', -000000243.00, 800000000, 'Smith-Upton', 'Vandervortburgh', '15012-1007', '7058267261837752', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 56
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000194189303', '01', 1, 'POS TERM', 'Purchase at Dickinson and Sons', 000000059.33, 800000000, 'Dickinson and Sons', 'Port Hunter', '93555-8843', '6727055190616014', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 57
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000196728331', '03', 1, 'OPERATOR', 'Return item at Hane and Sons', -000000744.77, 800000000, 'Hane and Sons', 'Erdmanberg', '80151', '7427684863423209', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 58
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000198494663', '01', 1, 'POS TERM', 'Purchase at Dietrich-Ledner', 000000385.77, 800000000, 'Dietrich-Ledner', 'Lilastad', '79844-4976', '2745303720002090', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 59
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000201032783', '01', 1, 'POS TERM', 'Purchase at Heidenreich-Feil', 000000326.44, 800000000, 'Heidenreich-Feil', 'North Christybury', '32759', '4011500891777367', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 60
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000202217428', '01', 1, 'POS TERM', 'Purchase at Simonis and Sons', 000000299.33, 800000000, 'Simonis and Sons', 'Joanieview', '81755-5489', '4385271476627819', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 61
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000202886897', '01', 1, 'POS TERM', 'Purchase at Ryan-Homenick', 000000175.88, 800000000, 'Ryan-Homenick', 'North Franciscaside', '14400', '2871968252812490', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 62
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000203305494', '01', 1, 'POS TERM', 'Purchase at Kunze, Koss and Erdman', 000000479.22, 800000000, 'Kunze, Koss and Erdman', 'West Lempi', '60316-4620', '5787351228879339', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 63
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000204143988', '01', 1, 'POS TERM', 'Purchase at Buckridge-Stiedemann', 000000076.33, 800000000, 'Buckridge-Stiedemann', 'Kuvalishaven', '15327', '3766281984155154', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 64
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000211219588', '01', 1, 'POS TERM', 'Purchase at Cummings, Nitzsche and Bosco', 000000553.00, 800000000, 'Cummings, Nitzsche and Bosco', 'Cordeliamouth', '55811', '6723000463207764', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 65
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000218186931', '03', 1, 'OPERATOR', 'Return item at Reichert and Daughters', -000000835.11, 800000000, 'Reichert and Daughters', 'Amaliafort', '31060-9178', '8931369351894783', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 66
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000220001505', '01', 1, 'POS TERM', 'Purchase at Schmeler Group', 000000929.77, 800000000, 'Schmeler Group', 'New Kennediburgh', '39202-2380', '6509230362553816', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 67
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000220745261', '01', 1, 'POS TERM', 'Purchase at Swaniawski, Torphy and Bruen', 000000495.66, 800000000, 'Swaniawski, Torphy and Bruen', 'East Devenborough', '70124', '7026637615032277', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 68
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000223138231', '01', 1, 'POS TERM', 'Purchase at Prohaska, Grant and Hirthe', 000000851.33, 800000000, 'Prohaska, Grant and Hirthe', 'Kennyview', '79664', '9501733721429893', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 69
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000224060323', '01', 1, 'POS TERM', 'Purchase at Kunze and Sons', 000000343.77, 800000000, 'Kunze and Sons', 'Port Genoveva', '96001', '9349107475869214', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 70
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000226849749', '03', 1, 'OPERATOR', 'Return item at Smith, Cummings and Medhurst', -000000428.99, 800000000, 'Smith, Cummings and Medhurst', 'South Adriannaland', '54229-7459', '6009619150674526', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 71
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000232640164', '01', 1, 'POS TERM', 'Purchase at Blick LLC', 000000160.99, 800000000, 'Blick LLC', 'East Ali', '23808', '7427684863423209', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 72
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000238329981', '03', 1, 'OPERATOR', 'Return item at Effertz, Ortiz and Gusikowski', -000000930.33, 800000000, 'Effertz, Ortiz and Gusikowski', 'Harrisonfurt', '89418-4999', '6509230362553816', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 73
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000241121967', '03', 1, 'OPERATOR', 'Return item at Kulas and Daughters', -000000445.55, 800000000, 'Kulas and Daughters', 'Billybury', '68626-4996', '6832676047698087', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 74
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000246307558', '01', 1, 'POS TERM', 'Purchase at Jacobi and Sons', 000000816.77, 800000000, 'Jacobi and Sons', 'Lake Hoseaside', '45822', '7443870988897530', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 75
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000246312084', '01', 1, 'POS TERM', 'Purchase at Weimann-Graham', 000000848.77, 800000000, 'Weimann-Graham', 'Thielburgh', '41063-5412', '2940139362300449', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 76
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000246549911', '01', 1, 'POS TERM', 'Purchase at Kulas, Reichert and O''Conner', 000000339.55, 800000000, 'Kulas, Reichert and O''Conner', 'Travishaven', '59094-4283', '5787351228879339', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 77
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000248557079', '01', 1, 'POS TERM', 'Purchase at Strosin-Fadel', 000000905.00, 800000000, 'Strosin-Fadel', 'Krajcikmouth', '25843', '5975117516616077', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 78
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000250062442', '01', 1, 'POS TERM', 'Purchase at Willms, Abshire and Daugherty', 000000346.99, 800000000, 'Willms, Abshire and Daugherty', 'Shieldston', '97909-1233', '6009619150674526', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 79
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000252891459', '01', 1, 'POS TERM', 'Purchase at Nitzsche, Feil and Bergstrom', 000000944.99, 800000000, 'Nitzsche, Feil and Bergstrom', 'Carriebury', '40432-2594', '6723000463207764', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 80
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000253579636', '01', 1, 'POS TERM', 'Purchase at D''Amore-Batz', 000000257.55, 800000000, 'D''Amore-Batz', 'Collierview', '97716', '7443870988897530', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 81
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000253685514', '01', 1, 'POS TERM', 'Purchase at Von-Schmeler', 000000496.33, 800000000, 'Von-Schmeler', 'Lake Maximillian', '85711', '4534784102713951', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 82
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000263663553', '01', 1, 'POS TERM', 'Purchase at Wehner, Turcotte and Nikolaus', 000000526.11, 800000000, 'Wehner, Turcotte and Nikolaus', 'Fritschfort', '75845-0688', '6503535181795992', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 83
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000263926805', '01', 1, 'POS TERM', 'Purchase at Batz-Gaylord', 000000210.33, 800000000, 'Batz-Gaylord', 'Beahanhaven', '00022', '0982496213629795', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 84
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000265935610', '01', 1, 'POS TERM', 'Purchase at Morar-Cartwright', 000000461.99, 800000000, 'Morar-Cartwright', 'Lake Sanfordmouth', '93080-1107', '7443870988897530', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 85
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000272698228', '01', 1, 'POS TERM', 'Purchase at Schultz-Morissette', 000000457.55, 800000000, 'Schultz-Morissette', 'East Jakaylashire', '84498-8609', '3260763612337560', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 86
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000273137276', '01', 1, 'POS TERM', 'Purchase at Lowe-Blick', 000000764.22, 800000000, 'Lowe-Blick', 'Boyerchester', '15468-8924', '6503535181795992', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 87
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000274427056', '03', 1, 'OPERATOR', 'Return item at Gibson-Maggio', -000000763.00, 800000000, 'Gibson-Maggio', 'Port Genevieveberg', '92794-6457', '0923877193247330', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 88
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000274596018', '01', 1, 'POS TERM', 'Purchase at Renner LLC', 000000040.00, 800000000, 'Renner LLC', 'Sengerport', '73531', '5407099850479866', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 89
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000277916619', '01', 1, 'POS TERM', 'Purchase at Champlin and Sons', 000000996.88, 800000000, 'Champlin and Sons', 'North Dale', '85808-4638', '7094142751055551', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 90
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000283702177', '01', 1, 'POS TERM', 'Purchase at Bradtke-Considine', 000000089.99, 800000000, 'Bradtke-Considine', 'Geovannyville', '39499-2169', '5975117516616077', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 91
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000292939458', '01', 1, 'POS TERM', 'Purchase at O''Hara, Ledner and Runte', 000000049.55, 800000000, 'O''Hara, Ledner and Runte', 'Port Fleta', '42362-4038', '8262593602473076', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 92
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000298764221', '01', 1, 'POS TERM', 'Purchase at Zboncak, Kohler and Ziemann', 000000706.11, 800000000, 'Zboncak, Kohler and Ziemann', 'Gilesmouth', '93998-8946', '7443870988897530', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 93
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000298806396', '01', 1, 'POS TERM', 'Purchase at Powlowski-Greenholt', 000000936.22, 800000000, 'Powlowski-Greenholt', 'Naderfort', '19262-4706', '7058267261837752', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 94
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000307523903', '03', 1, 'OPERATOR', 'Return item at Hamill, Sawayn and O''Conner', -000000585.44, 800000000, 'Hamill, Sawayn and O''Conner', 'Vonview', '83262', '5787351228879339', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 95
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000312054308', '01', 1, 'POS TERM', 'Purchase at Bogan LLC', 000000717.00, 800000000, 'Bogan LLC', 'Josiahhaven', '59167', '7379335634661142', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 96
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000312439873', '01', 1, 'POS TERM', 'Purchase at Rowe and Daughters', 000000745.33, 800000000, 'Rowe and Daughters', 'New Adriannamouth', '89172-7486', '7443870988897530', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 97
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000312675172', '01', 1, 'POS TERM', 'Purchase at Hermiston Inc', 000000728.77, 800000000, 'Hermiston Inc', 'Port Bennyburgh', '34656', '8931369351894783', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 98
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000313527007', '01', 1, 'POS TERM', 'Purchase at Ebert-Grimes', 000000948.22, 800000000, 'Ebert-Grimes', 'New Kelleyton', '51492-3272', '8112545834239735', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 99
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000325686503', '01', 1, 'POS TERM', 'Purchase at Cruickshank-Marvin', 000000569.99, 800000000, 'Cruickshank-Marvin', 'Russelshire', '66858', '0923877193247330', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 100
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000328781772', '01', 1, 'POS TERM', 'Purchase at Hayes and Daughters', 000000859.44, 800000000, 'Hayes and Daughters', 'Beahanville', '08781', '9056297931664011', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 101
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000329446511', '01', 1, 'POS TERM', 'Purchase at Ernser, Ward and Lehner', 000000667.55, 800000000, 'Ernser, Ward and Lehner', 'Lake Rita', '78140-9470', '8040580410348680', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 102
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000329724245', '01', 1, 'POS TERM', 'Purchase at Reichel Group', 000000014.00, 800000000, 'Reichel Group', 'Port Romanfort', '95843', '0500024453765740', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 103
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000338128146', '03', 1, 'OPERATOR', 'Return item at Terry-Mertz', -000000742.99, 800000000, 'Terry-Mertz', 'Enidview', '31259', '7094142751055551', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 104
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000341155503', '01', 1, 'POS TERM', 'Purchase at Parker-Erdman', 000000990.88, 800000000, 'Parker-Erdman', 'New Khalid', '72240', '8517866958206008', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 105
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000341634875', '01', 1, 'POS TERM', 'Purchase at Medhurst, Bogisich and Schmeler', 000000997.88, 800000000, 'Medhurst, Bogisich and Schmeler', 'Dickensport', '29931-9313', '2760836797107565', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 106
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000357518499', '03', 1, 'OPERATOR', 'Return item at Willms-Beier', -000000852.33, 800000000, 'Willms-Beier', 'Nathanfurt', '70715-7333', '7251508149188883', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 107
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000358543876', '01', 1, 'POS TERM', 'Purchase at Zulauf Group', 000000552.77, 800000000, 'Zulauf Group', 'Schowalterland', '26981', '5975117516616077', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 108
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000361866674', '01', 1, 'POS TERM', 'Purchase at Mayer and Daughters', 000000446.00, 800000000, 'Mayer and Daughters', 'North Keeley', '40519', '7427684863423209', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 109
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000366513257', '01', 1, 'POS TERM', 'Purchase at Klein, Buckridge and Johnson', 000000965.55, 800000000, 'Klein, Buckridge and Johnson', 'Shieldsbury', '79412-9462', '0927987108636232', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 110
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000373973344', '01', 1, 'POS TERM', 'Purchase at Wehner LLC', 000000958.33, 800000000, 'Wehner LLC', 'South Harmonmouth', '92575', '5671184478505844', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 111
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000375247552', '01', 1, 'POS TERM', 'Purchase at Guann Group', 000000115.11, 800000000, 'Guann Group', 'Port Grant', '76360-6457', '3999169246375885', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 112
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000378710702', '03', 1, 'OPERATOR', 'Return item at Wilderman, Koepp and Ledner', -000000344.77, 800000000, 'Wilderman, Koepp and Ledner', 'Wuckerthaven', '29965', '7379335634661142', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 113
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000379084859', '03', 1, 'OPERATOR', 'Return item at Lebsack-Treutel', -000000075.22, 800000000, 'Lebsack-Treutel', 'Kennedyside', '66077-1463', '6723000463207764', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 114
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000380632461', '01', 1, 'POS TERM', 'Purchase at Beahan, Little and Sanford', 000000428.33, 800000000, 'Beahan, Little and Sanford', 'East Ebonyville', '17826-0999', '4859452612877065', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 115
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000382018782', '01', 1, 'POS TERM', 'Purchase at Hackett-Kautzer', 000000884.99, 800000000, 'Hackett-Kautzer', 'East Cristopherfurt', '10894-9358', '5656830544981216', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 116
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000382291356', '01', 1, 'POS TERM', 'Purchase at Jacobi and Daughters', 000000860.77, 800000000, 'Jacobi and Daughters', 'Carterland', '70592-5640', '5671184478505844', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 117
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000392772234', '01', 1, 'POS TERM', 'Purchase at Williamson LLC', 000000351.66, 800000000, 'Williamson LLC', 'Runteville', '18400-6845', '4385271476627819', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 118
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000397282953', '03', 1, 'OPERATOR', 'Return item at Ankunding Group', -000000396.22, 800000000, 'Ankunding Group', 'Adrainton', '59712-6451', '2871968252812490', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 119
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000399296572', '01', 1, 'POS TERM', 'Purchase at McGlynn Inc', 000000254.77, 800000000, 'McGlynn Inc', 'New Berenice', '76608', '6009619150674526', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 120
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000400022505', '01', 1, 'POS TERM', 'Purchase at Klocko LLC', 000000385.44, 800000000, 'Klocko LLC', 'Taniatown', '25662', '1014086565224350', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 121
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000400762013', '01', 1, 'POS TERM', 'Purchase at Will-Murazik', 000000617.00, 800000000, 'Will-Murazik', 'New Estefania', '36903-3350', '2760836797107565', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 122
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000402032668', '03', 1, 'OPERATOR', 'Return item at Torphy, Collins and Witting', -000000756.66, 800000000, 'Torphy, Collins and Witting', 'Lake Augusttown', '06644', '3766281984155154', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 123
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000407178785', '01', 1, 'POS TERM', 'Purchase at Cole-Wyman', 000000949.77, 800000000, 'Cole-Wyman', 'Olenmouth', '47296', '3940246016141489', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 124
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000407637739', '03', 1, 'OPERATOR', 'Return item at Price LLC', -000000501.44, 800000000, 'Price LLC', 'New Annabell', '91216', '5975117516616077', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 125
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000412515011', '01', 1, 'POS TERM', 'Purchase at Wehner-Ebert', 000000214.77, 800000000, 'Wehner-Ebert', 'Ednaville', '70885', '5975117516616077', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 126
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000415671623', '01', 1, 'POS TERM', 'Purchase at Kshlerin, Schulist and Oberbrunner', 000000000.99, 800000000, 'Kshlerin, Schulist and Oberbrunner', 'Dallinmouth', '19897-9097', '1561409106491600', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 127
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000416848414', '01', 1, 'POS TERM', 'Purchase at Medhurst-Feeney', 000000995.22, 800000000, 'Medhurst-Feeney', 'New Terrance', '87377', '6832676047698087', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 128
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000420768809', '01', 1, 'POS TERM', 'Purchase at Bartell-Rempel', 000000674.99, 800000000, 'Bartell-Rempel', 'Alexanderport', '08405', '7058267261837752', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 129
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000424689871', '01', 1, 'POS TERM', 'Purchase at Rempel and Daughters', 000000648.11, 800000000, 'Rempel and Daughters', 'Aliyachester', '08642', '5787351228879339', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 130
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000429557611', '01', 1, 'POS TERM', 'Purchase at Pagac, Funk and Kiehn', 000000545.66, 800000000, 'Pagac, Funk and Kiehn', 'Krajcikshire', '32088-0940', '0982496213629795', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 131
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000432231260', '03', 1, 'OPERATOR', 'Return item at Schuster-Bashirian', -000000962.77, 800000000, 'Schuster-Bashirian', 'New Gageton', '47405-2362', '8262593602473076', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 132
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000433607101', '01', 1, 'POS TERM', 'Purchase at VonRueden Inc', 000000851.22, 800000000, 'VonRueden Inc', 'Lake Gailland', '82720-3055', '9501733721429893', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 133
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000434343718', '01', 1, 'POS TERM', 'Purchase at Vandervort-McClure', 000000793.22, 800000000, 'Vandervort-McClure', 'Kaydenborough', '73288-4151', '6832676047698087', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 134
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000435144487', '01', 1, 'POS TERM', 'Purchase at Pacocha, Goyette and Leuschke', 000000408.88, 800000000, 'Pacocha, Goyette and Leuschke', 'Gutkowskiport', '64919-4953', '8112545834239735', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 135
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000445507761', '01', 1, 'POS TERM', 'Purchase at Rice, Luettgen and Aufderhar', 000000129.33, 800000000, 'Rice, Luettgen and Aufderhar', 'O'Reillychester', '75844', '3940246016141489', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 136
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000446945803', '01', 1, 'POS TERM', 'Purchase at Yost and Daughters', 000000241.22, 800000000, 'Yost and Daughters', 'Lake Manley', '52896-0448', '3940246016141489', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 137
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000450622695', '01', 1, 'POS TERM', 'Purchase at Schmitt, Kohler and Skiles', 000000028.33, 800000000, 'Schmitt, Kohler and Skiles', 'Farrellhaven', '00796', '2988091353094312', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 138
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000458331136', '01', 1, 'POS TERM', 'Purchase at Howe, Rippin and Watsica', 000000437.11, 800000000, 'Howe, Rippin and Watsica', 'West Kianachester', '75201', '2988091353094312', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 139
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000462765346', '01', 1, 'POS TERM', 'Purchase at Marquardt, Ward and Brekke', 000000587.11, 800000000, 'Marquardt, Ward and Brekke', 'Lake Nataliastad', '61706-7915', '0982496213629795', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 140
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000474475283', '01', 1, 'POS TERM', 'Purchase at Pouros Inc', 000000174.66, 800000000, 'Pouros Inc', 'East Jerald', '35802', '5407099850479866', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 141
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000475609951', '01', 1, 'POS TERM', 'Purchase at Predovic-Deckow', 000000913.88, 800000000, 'Predovic-Deckow', 'West Gunnar', '46493-9443', '5656830544981216', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 142
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000475746885', '01', 1, 'POS TERM', 'Purchase at Adams-Watsica', 000000967.44, 800000000, 'Adams-Watsica', 'Ratkemouth', '55474-0373', '0500024453765740', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 143
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000482016448', '01', 1, 'POS TERM', 'Purchase at Becker Group', 000000310.00, 800000000, 'Becker Group', 'Sanfordhaven', '50166', '2760836797107565', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 144
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000482116790', '01', 1, 'POS TERM', 'Purchase at Erdman-Cartwright', 000000820.11, 800000000, 'Erdman-Cartwright', 'Lake Lavonne', '06930', '4385271476627819', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 145
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000486159054', '01', 1, 'POS TERM', 'Purchase at Christiansen-Jacobi', 000000319.88, 800000000, 'Christiansen-Jacobi', 'West Conor', '53124', '0683586198171516', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 146
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000490043047', '01', 1, 'POS TERM', 'Purchase at Yost-Kertzmann', 000000584.88, 800000000, 'Yost-Kertzmann', 'Lake Josh', '59545', '5407099850479866', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 147
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000496711357', '01', 1, 'POS TERM', 'Purchase at Koepp-Wiegand', 000000161.99, 800000000, 'Koepp-Wiegand', 'Cristianstad', '23187-0329', '2760836797107565', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 148
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000497995808', '01', 1, 'POS TERM', 'Purchase at Beier and Daughters', 000000649.00, 800000000, 'Beier and Daughters', 'Norbertstad', '48162-5331', '9501733721429893', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 149
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000498548061', '01', 1, 'POS TERM', 'Purchase at Bernier and Daughters', 000000209.00, 800000000, 'Bernier and Daughters', 'Lake Rosefurt', '83724-5529', '6727055190616014', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 150
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000498615524', '03', 1, 'OPERATOR', 'Return item at Powlowski LLC', -000000907.00, 800000000, 'Powlowski LLC', 'New Aprilstad', '57040-5493', '9056297931664011', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 151
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000498857207', '01', 1, 'POS TERM', 'Purchase at Friesen, Murphy and Beier', 000000293.44, 800000000, 'Friesen, Murphy and Beier', 'Dallasberg', '02275', '3260763612337560', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 152
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000499424514', '01', 1, 'POS TERM', 'Purchase at Schumm-Stamm', 000000952.55, 800000000, 'Schumm-Stamm', 'Imogeneburgh', '12605', '4534784102713951', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 153
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000500479019', '01', 1, 'POS TERM', 'Purchase at Hilpert, Purdy and Kilback', 000000654.99, 800000000, 'Hilpert, Purdy and Kilback', 'Schummshire', '49771-2616', '1014086565224350', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 154
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000500885895', '03', 1, 'OPERATOR', 'Return item at Klein-Stark', -000000579.88, 800000000, 'Klein-Stark', 'West Arlo', '35478', '4859452612877065', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 155
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000502617711', '01', 1, 'POS TERM', 'Purchase at Klocko LLC', 000000955.11, 800000000, 'Klocko LLC', 'Winonaland', '07626', '4859452612877065', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 156
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000503557384', '01', 1, 'POS TERM', 'Purchase at Casper Group', 000000081.44, 800000000, 'Casper Group', 'Millsborough', '57690', '9680294154603697', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 157
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000504099546', '01', 1, 'POS TERM', 'Purchase at Jewess, Sauer and Runolfsson', 000000655.11, 800000000, 'Jewess, Sauer and Runolfsson', 'Parkermouth', '00391', '0923877193247330', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 158
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000508429766', '01', 1, 'POS TERM', 'Purchase at Kassulke, Reynolds and Runolfsson', 000000534.11, 800000000, 'Kassulke, Reynolds and Runolfsson', 'Seamuston', '13633-3156', '7094142751055551', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 159
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000519771423', '01', 1, 'POS TERM', 'Purchase at Herman, Swift and Nikolaus', 000000670.00, 800000000, 'Herman, Swift and Nikolaus', 'Durganport', '31302', '6723000463207764', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 160
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000519935575', '01', 1, 'POS TERM', 'Purchase at Gaylord, Kuhlman and Reichert', 000000164.99, 800000000, 'Gaylord, Kuhlman and Reichert', 'West Reillymouth', '05765', '0923877193247330', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 161
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000522130011', '01', 1, 'POS TERM', 'Purchase at D''Amore, Conroy and Wilkinson', 000000699.44, 800000000, 'D''Amore, Conroy and Wilkinson', 'East Larissatown', '12025-5362', '7251508149188883', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 162
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000523110055', '01', 1, 'POS TERM', 'Purchase at Purdy-King', 000000736.88, 800000000, 'Purdy-King', 'Port Maximusshire', '95835', '7379335634661142', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 163
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000528007115', '03', 1, 'OPERATOR', 'Return item at Trantow-Sipes', -000000113.11, 800000000, 'Trantow-Sipes', 'Reubentown', '12694', '5671184478505844', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 164
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000532120892', '03', 1, 'OPERATOR', 'Return item at Frami-Hyatt', -000000070.77, 800000000, 'Frami-Hyatt', 'Jamilside', '14372-1790', '9680294154603697', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 165
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000539225404', '03', 1, 'OPERATOR', 'Return item at Hamill, Blick and Kling', -000000372.00, 800000000, 'Hamill, Blick and Kling', 'Sporerview', '52731', '8040580410348680', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 166
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000540034453', '01', 1, 'POS TERM', 'Purchase at Baumbach-Mohr', 000000202.44, 800000000, 'Baumbach-Mohr', 'Kovacekhaven', '88690-1442', '6832676047698087', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 167
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000540260014', '03', 1, 'OPERATOR', 'Return item at McCullough-Gottlieb', -000000880.22, 800000000, 'McCullough-Gottlieb', 'Clarissaside', '80982-4072', '3999169246375885', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 168
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000544248006', '01', 1, 'POS TERM', 'Purchase at Mann Inc', 000000659.44, 800000000, 'Mann Inc', 'Koeppton', '40246-5957', '1561409106491600', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 169
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000547488622', '01', 1, 'POS TERM', 'Purchase at Reichert, Kemmer and Funk', 000000403.66, 800000000, 'Reichert, Kemmer and Funk', 'North Destinibury', '84879', '4011500891777367', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 170
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000549364593', '01', 1, 'POS TERM', 'Purchase at Waters, Considine and Borer', 000000195.44, 800000000, 'Waters, Considine and Borer', 'Lake Lillianaville', '60590-4967', '8517866958206008', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 171
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000550089732', '03', 1, 'OPERATOR', 'Return item at Sanford-Gleichner', -000000537.44, 800000000, 'Sanford-Gleichner', 'Dorisberg', '29319', '3260763612337560', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 172
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000550860982', '01', 1, 'POS TERM', 'Purchase at Bogan LLC', 000000850.22, 800000000, 'Bogan LLC', 'Lilyberg', '56494', '6349250331648509', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 173
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000554096178', '01', 1, 'POS TERM', 'Purchase at Turner, Dickinson and Grant', 000000722.33, 800000000, 'Turner, Dickinson and Grant', 'Lucianofort', '91006-9381', '6503535181795992', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 174
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000555363230', '01', 1, 'POS TERM', 'Purchase at Metz, Blanda and Homenick', 000000484.99, 800000000, 'Metz, Blanda and Homenick', 'North Linwood', '41398', '9680294154603697', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 175
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000561673599', '01', 1, 'POS TERM', 'Purchase at Bergnaum and Sons', 000000430.55, 800000000, 'Bergnaum and Sons', 'Leuschkeberg', '87213-5400', '5656830544981216', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 176
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000564257675', '01', 1, 'POS TERM', 'Purchase at Jast LLC', 000000623.11, 800000000, 'Jast LLC', 'Lednermouth', '82698', '6503535181795992', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 177
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000569807281', '03', 1, 'OPERATOR', 'Return item at Kiehn, Russel and Schaefer', -000000998.33, 800000000, 'Kiehn, Russel and Schaefer', 'New Loren', '41813', '9349107475869214', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 178
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000570013846', '01', 1, 'POS TERM', 'Purchase at Parker, Pfannerstill and Donnelly', 000000352.33, 800000000, 'Parker, Pfannerstill and Donnelly', 'Mohrport', '18642-6726', '9349107475869214', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 179
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000570880433', '01', 1, 'POS TERM', 'Purchase at Kuvalis-Leffler', 000000161.77, 800000000, 'Kuvalis-Leffler', 'East Tiffany', '09856-1749', '6009619150674526', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 180
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000573732499', '01', 1, 'POS TERM', 'Purchase at Ortiz, Langworth and Feeney', 000000237.44, 800000000, 'Ortiz, Langworth and Feeney', 'New Deonte', '32314', '9805583408996588', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 181
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000575834812', '01', 1, 'POS TERM', 'Purchase at Ritchie and Sons', 000000689.88, 800000000, 'Ritchie and Sons', 'O'Haraberg', '45500-2911', '7379335634661142', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 182
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000576344938', '01', 1, 'POS TERM', 'Purchase at Smith and Sons', 000000425.11, 800000000, 'Smith and Sons', 'Lake Dallinfurt', '26352-2649', '2745303720002090', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 183
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000577165878', '01', 1, 'POS TERM', 'Purchase at Macejkovic-Mohr', 000000621.22, 800000000, 'Macejkovic-Mohr', 'Trantowberg', '59291', '7427684863423209', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 184
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000577826814', '03', 1, 'OPERATOR', 'Return item at DuBuque, Wuckert and Mraz', -000000047.88, 800000000, 'DuBuque, Wuckert and Mraz', 'South Lurline', '37081', '0500024453765740', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 185
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000585883106', '01', 1, 'POS TERM', 'Purchase at Heathcote Inc', 000000435.44, 800000000, 'Heathcote Inc', 'Marlenemouth', '72239-5071', '6009619150674526', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 186
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000588642606', '01', 1, 'POS TERM', 'Purchase at Marks and Daughters', 000000568.88, 800000000, 'Marks and Daughters', 'New Berryton', '84059-0476', '7058267261837752', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 187
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000598262041', '01', 1, 'POS TERM', 'Purchase at Terry-Rohan', 000000039.55, 800000000, 'Terry-Rohan', 'Rempelview', '34789-4591', '1561409106491600', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 188
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000600564499', '01', 1, 'POS TERM', 'Purchase at Schmeler, Crooks and Barton', 000000736.33, 800000000, 'Schmeler, Crooks and Barton', 'Hodkiewiczville', '09147-9690', '2988091353094312', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 189
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000601274842', '01', 1, 'POS TERM', 'Purchase at Yost, Hoppe and Heathcote', 000000744.11, 800000000, 'Yost, Hoppe and Heathcote', 'Heathermouth', '15216-7718', '7251508149188883', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 190
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000601496057', '01', 1, 'POS TERM', 'Purchase at Ortiz-Douglas', 000000900.22, 800000000, 'Ortiz-Douglas', 'Rosaleemouth', '64903', '6509230362553816', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 191
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000603071214', '01', 1, 'POS TERM', 'Purchase at Schinner-Feeney', 000000166.99, 800000000, 'Schinner-Feeney', 'North Wilfred', '36776-9392', '7251508149188883', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 192
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000605048564', '01', 1, 'POS TERM', 'Purchase at Schamberger, O''Reilly and Wintheiser', 000000095.99, 800000000, 'Schamberger, O''Reilly and Wintheiser', 'West Bernadineland', '74526', '8931369351894783', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 193
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000606140907', '01', 1, 'POS TERM', 'Purchase at Yost-Schaefer', 000000598.33, 800000000, 'Yost-Schaefer', 'Barrowsfurt', '88050', '2940139362300449', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 194
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000606716618', '01', 1, 'POS TERM', 'Purchase at Barton, Schmidt and Hodkiewicz', 000000715.66, 800000000, 'Barton, Schmidt and Hodkiewicz', 'Sethtown', '63152', '0982496213629795', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 195
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000606830191', '03', 1, 'OPERATOR', 'Return item at Bogisich-O''Connell', -000000071.66, 800000000, 'Bogisich-O''Connell', 'New Bennie', '00871', '3940246016141489', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 196
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000614267358', '03', 1, 'OPERATOR', 'Return item at Gibson-Abbott', -000000132.88, 800000000, 'Gibson-Abbott', 'New Kodyton', '82751', '1142167692878931', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 197
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000618712102', '01', 1, 'POS TERM', 'Purchase at Hahn-Lueilwitz', 000000021.11, 800000000, 'Hahn-Lueilwitz', 'Deondreville', '55366-2298', '8112545834239735', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 198
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000621178666', '01', 1, 'POS TERM', 'Purchase at Orn-Dach', 000000639.22, 800000000, 'Orn-Dach', 'Maxineville', '39263-8392', '1561409106491600', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 199
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000624335286', '01', 1, 'POS TERM', 'Purchase at Crona, Turner and Hane', 000000598.44, 800000000, 'Crona, Turner and Hane', 'Glenton', '32966-6359', '9680294154603697', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 200
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000627601011', '03', 1, 'OPERATOR', 'Return item at Stokes Inc', -000000538.22, 800000000, 'Stokes Inc', 'Koeppfurt', '91991', '7443870988897530', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 201
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000628524597', '01', 1, 'POS TERM', 'Purchase at Wiegand-Weimann', 000000269.22, 800000000, 'Wiegand-Weimann', 'East Arnomouth', '21317', '1014086565224350', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 202
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000634329004', '01', 1, 'POS TERM', 'Purchase at Durgan-Nader', 000000089.11, 800000000, 'Durgan-Nader', 'Robynmouth', '39869', '5407099850479866', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 203
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000635121182', '01', 1, 'POS TERM', 'Purchase at Douglas and Daughters', 000000629.55, 800000000, 'Douglas and Daughters', 'Yvettetown', '03935', '6832676047698087', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 204
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000641694180', '01', 1, 'POS TERM', 'Purchase at Schumm-Reinger', 000000554.22, 800000000, 'Schumm-Reinger', 'Antoniatown', '52581', '3999169246375885', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 205
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000643758597', '01', 1, 'POS TERM', 'Purchase at Bins Inc', 000000744.22, 800000000, 'Bins Inc', 'Port Georgianaside', '24098-5082', '7058267261837752', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 206
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000647754915', '01', 1, 'POS TERM', 'Purchase at Gerlach-Jaskolski', 000000718.55, 800000000, 'Gerlach-Jaskolski', 'New Kalistad', '51103-7932', '2871968252812490', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 207
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000652713581', '01', 1, 'POS TERM', 'Purchase at Pagac-Hackett', 000000633.55, 800000000, 'Pagac-Hackett', 'New Hans', '35901', '6727055190616014', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 208
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000667157384', '01', 1, 'POS TERM', 'Purchase at Stamm and Sons', 000000952.77, 800000000, 'Stamm and Sons', 'Hayleybury', '33611', '5671184478505844', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 209
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000669905307', '01', 1, 'POS TERM', 'Purchase at Schneider and Daughters', 000000425.00, 800000000, 'Schneider and Daughters', 'Blandafurt', '74767-7107', '6727055190616014', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 210
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000672061881', '03', 1, 'OPERATOR', 'Return item at Rippin-Gibson', -000000435.00, 800000000, 'Rippin-Gibson', 'Hansenstad', '16980-8789', '5656830544981216', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 211
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000672573296', '03', 1, 'OPERATOR', 'Return item at Veum-Treutel', -000000710.66, 800000000, 'Veum-Treutel', 'Amelybury', '60686', '8517866958206008', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 212
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000673250360', '01', 1, 'POS TERM', 'Purchase at Ebert-Gleason', 000000191.00, 800000000, 'Ebert-Gleason', 'Altenwerthbury', '89085', '7427684863423209', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 213
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000676149118', '01', 1, 'POS TERM', 'Purchase at Sauer-Ruecker', 000000697.44, 800000000, 'Sauer-Ruecker', 'Port Nestor', '24148-9894', '4385271476627819', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 214
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000685488982', '01', 1, 'POS TERM', 'Purchase at Williamson Group', 000000094.77, 800000000, 'Williamson Group', 'Lake Bradyport', '32996', '0500024453765740', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 215
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000686167627', '01', 1, 'POS TERM', 'Purchase at Gibson, Beahan and Reichert', 000000081.44, 800000000, 'Gibson, Beahan and Reichert', 'Maeveland', '51385-6031', '9805583408996588', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 216
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000689276136', '01', 1, 'POS TERM', 'Purchase at Bins Group', 000000192.00, 800000000, 'Bins Group', 'North Anabellehaven', '61914-3232', '9056297931664011', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 217
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000700096853', '01', 1, 'POS TERM', 'Purchase at Pollich Group', 000000329.99, 800000000, 'Pollich Group', 'Nikolausburgh', '88031', '3940246016141489', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 218
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000703553020', '01', 1, 'POS TERM', 'Purchase at Gleason-Streich', 000000077.00, 800000000, 'Gleason-Streich', 'New Huntermouth', '60103-7370', '2760836797107565', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 219
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000717135758', '03', 1, 'OPERATOR', 'Return item at Crona, Veum and D''Amore', -000000762.44, 800000000, 'Crona, Veum and D''Amore', 'South Nashland', '13804-5608', '0982496213629795', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 220
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000727152111', '01', 1, 'POS TERM', 'Purchase at Von, Klein and Cremin', 000000983.55, 800000000, 'Von, Klein and Cremin', 'Evansfurt', '36814-9049', '6349250331648509', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 221
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000731515153', '03', 1, 'OPERATOR', 'Return item at Gleichner, Mitchell and Schmidt', -000000025.99, 800000000, 'Gleichner, Mitchell and Schmidt', 'North Vincent', '89467-9263', '9805583408996588', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 222
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000734452614', '01', 1, 'POS TERM', 'Purchase at Stokes-Mueller', 000000358.22, 800000000, 'Stokes-Mueller', 'Ambroseland', '19819-9298', '4534784102713951', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 223
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000735823935', '01', 1, 'POS TERM', 'Purchase at Johnston and Daughters', 000000910.11, 800000000, 'Johnston and Daughters', 'Delaneymouth', '49269-2667', '5407099850479866', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 224
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000741999667', '01', 1, 'POS TERM', 'Purchase at Corkery, Boehm and Hudson', 000000643.44, 800000000, 'Corkery, Boehm and Hudson', 'Walkermouth', '83831', '9349107475869214', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 225
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000742447110', '01', 1, 'POS TERM', 'Purchase at Hauck Inc', 000000746.77, 800000000, 'Hauck Inc', 'Estellville', '11000', '8517866958206008', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 226
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000747646163', '03', 1, 'OPERATOR', 'Return item at Watsica LLC', -000000499.99, 800000000, 'Watsica LLC', 'Durgantown', '74690-6183', '1561409106491600', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 227
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000749066680', '01', 1, 'POS TERM', 'Purchase at O''Reilly LLC', 000000805.77, 800000000, 'O''Reilly LLC', 'Jerelport', '39298-3605', '6509230362553816', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 228
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000749493129', '01', 1, 'POS TERM', 'Purchase at Pollich-Kuhn', 000000013.88, 800000000, 'Pollich-Kuhn', 'Kelliview', '98624-6791', '7058267261837752', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 229
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000751145919', '01', 1, 'POS TERM', 'Purchase at Rohan-Jacobson', 000000140.00, 800000000, 'Rohan-Jacobson', 'East Delmer', '37476', '9501733721429893', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 230
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000751696292', '01', 1, 'POS TERM', 'Purchase at Smith, Hansen and Waelchi', 000000653.55, 800000000, 'Smith, Hansen and Waelchi', 'Jerrodport', '37182-0090', '7427684863423209', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 231
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000755736377', '01', 1, 'POS TERM', 'Purchase at Torp-Stark', 000000906.44, 800000000, 'Torp-Stark', 'North Edison', '41040-7099', '9680294154603697', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 232
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000760577632', '01', 1, 'POS TERM', 'Purchase at Kulas-Hayes', 000000735.99, 800000000, 'Kulas-Hayes', 'Prohaskaview', '38756', '8931369351894783', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 233
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000762269241', '01', 1, 'POS TERM', 'Purchase at Kuvalis Group', 000000988.88, 800000000, 'Kuvalis Group', 'Lake Cierrashire', '92525', '3940246016141489', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 234
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000767081090', '01', 1, 'POS TERM', 'Purchase at Bergnaum, Effertz and Wilkinson', 000000671.11, 800000000, 'Bergnaum, Effertz and Wilkinson', 'Lake Twila', '39210-3581', '1142167692878931', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 235
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000767308626', '01', 1, 'POS TERM', 'Purchase at Shields, DuBuque and Wyman', 000000856.44, 800000000, 'Shields, DuBuque and Wyman', 'South Christelle', '94060-3050', '1142167692878931', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 236
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000767314476', '01', 1, 'POS TERM', 'Purchase at Renner Inc', 000000273.11, 800000000, 'Renner Inc', 'Lednerberg', '11838', '0927987108636232', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 237
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000768119840', '01', 1, 'POS TERM', 'Purchase at Towne, Hickle and Orn', 000000065.44, 800000000, 'Towne, Hickle and Orn', 'Toybury', '15228', '8112545834239735', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 238
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000770707563', '01', 1, 'POS TERM', 'Purchase at Leffler-Hilll', 000000309.44, 800000000, 'Leffler-Hilll', 'Lake Samantha', '94910', '9056297931664011', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 239
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000772421231', '01', 1, 'POS TERM', 'Purchase at Pfeffer, Rogahn and Hessel', 000000405.33, 800000000, 'Pfeffer, Rogahn and Hessel', 'Christborough', '21176-4420', '4385271476627819', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 240
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000776200014', '01', 1, 'POS TERM', 'Purchase at Lebsack and Sons', 000000985.22, 800000000, 'Lebsack and Sons', 'Otisbury', '32545', '9680294154603697', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 241
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000778829157', '01', 1, 'POS TERM', 'Purchase at Renner, Mertz and Ondricka', 000000270.55, 800000000, 'Renner, Mertz and Ondricka', 'South Emeliatown', '37065-2088', '8262593602473076', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 242
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000781205695', '01', 1, 'POS TERM', 'Purchase at Beer, Goldner and Armstrong', 000000489.66, 800000000, 'Beer, Goldner and Armstrong', 'South Madelynnland', '21570', '5787351228879339', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 243
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000781512834', '01', 1, 'POS TERM', 'Purchase at Koch-Pouros', 000000319.00, 800000000, 'Koch-Pouros', 'Daytonstad', '13199-2463', '4859452612877065', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 244
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000784621975', '01', 1, 'POS TERM', 'Purchase at Kunde-Howe', 000000227.22, 800000000, 'Kunde-Howe', 'New Darylberg', '34409', '3999169246375885', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 245
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000793409700', '01', 1, 'POS TERM', 'Purchase at Douglas Inc', 000000168.66, 800000000, 'Douglas Inc', 'South Keyshawnton', '15099', '7026637615032277', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 246
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000796832699', '01', 1, 'POS TERM', 'Purchase at Schroeder, Bergnaum and Waters', 000000803.99, 800000000, 'Schroeder, Bergnaum and Waters', 'Jaskolskimouth', '83332-8357', '6349250331648509', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 247
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000802663079', '01', 1, 'POS TERM', 'Purchase at Zulauf-O''Keefe', 000000975.11, 800000000, 'Zulauf-O''Keefe', 'Rauview', '52467-2350', '0683586198171516', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 248
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000803014982', '01', 1, 'POS TERM', 'Purchase at Effertz-Abbott', 000000053.55, 800000000, 'Effertz-Abbott', 'Claudiechester', '95970-2683', '2871968252812490', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 249
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000806255008', '03', 1, 'OPERATOR', 'Return item at Wisoky, Jacobs and Sanford', -000000439.33, 800000000, 'Wisoky, Jacobs and Sanford', 'New Alanaview', '05488-3195', '4534784102713951', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 250
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000812607213', '03', 1, 'OPERATOR', 'Return item at Volkman-Goodwin', -000000641.77, 800000000, 'Volkman-Goodwin', 'Gulgowskifort', '59834-6801', '2760836797107565', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 251
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000821287727', '01', 1, 'POS TERM', 'Purchase at Russel LLC', 000000034.99, 800000000, 'Russel LLC', 'New Sarah', '49041', '4859452612877065', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 252
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000822135100', '01', 1, 'POS TERM', 'Purchase at Larkin, Hills and Becker', 000000796.00, 800000000, 'Larkin, Hills and Becker', 'Coleton', '54392-1073', '5671184478505844', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 253
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000823157599', '01', 1, 'POS TERM', 'Purchase at Miller, Hudson and Ziemann', 000000111.11, 800000000, 'Miller, Hudson and Ziemann', 'West Jasmin', '72736', '9349107475869214', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 254
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000824152956', '01', 1, 'POS TERM', 'Purchase at Weissnat-Sanford', 000000594.77, 800000000, 'Weissnat-Sanford', 'Schuppeton', '25158-3242', '0923877193247330', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 255
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000828072981', '03', 1, 'OPERATOR', 'Return item at Hayes Inc', -000000362.22, 800000000, 'Hayes Inc', 'Dinoville', '72795-6502', '7026637615032277', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 256
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000835855923', '03', 1, 'OPERATOR', 'Return item at Pouros and Sons', -000000041.77, 800000000, 'Pouros and Sons', 'Kerlukechester', '32347', '5407099850479866', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 257
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000838587312', '01', 1, 'POS TERM', 'Purchase at Abbott-Gerlach', 000000241.66, 800000000, 'Abbott-Gerlach', 'McClureburgh', '95049', '0500024453765740', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 258
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000838796166', '03', 1, 'OPERATOR', 'Return item at Bauch-Crooks', -000000457.55, 800000000, 'Bauch-Crooks', 'Stokesberg', '30306', '4385271476627819', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 259
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000840146978', '01', 1, 'POS TERM', 'Purchase at Gutkowski-Bayer', 000000702.22, 800000000, 'Gutkowski-Bayer', 'Baileyville', '48332-1913', '4011500891777367', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 260
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000841555701', '01', 1, 'POS TERM', 'Purchase at Kub, Gislason and Haraann', 000000281.55, 800000000, 'Kub, Gislason and Haraann', 'Port Bryonfurt', '16314-3731', '2988091353094312', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 261
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000845039454', '01', 1, 'POS TERM', 'Purchase at Borer, Farrell and Doyle', 000000045.66, 800000000, 'Borer, Farrell and Doyle', 'Evelineborough', '36781', '9056297931664011', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 262
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000855260493', '03', 1, 'OPERATOR', 'Return item at Cassin, Huel and Conroy', -000000270.99, 800000000, 'Cassin, Huel and Conroy', 'East Kurtborough', '83037', '6727055190616014', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 263
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000858238426', '01', 1, 'POS TERM', 'Purchase at Thiel Group', 000000080.66, 800000000, 'Thiel Group', 'New Martineberg', '27981', '3260763612337560', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 264
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000858501945', '01', 1, 'POS TERM', 'Purchase at Braun, Schulist and Kreiger', 000000198.66, 800000000, 'Braun, Schulist and Kreiger', 'Port Tamiamouth', '52536', '8262593602473076', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 265
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000865987685', '03', 1, 'OPERATOR', 'Return item at Kovacek-Beatty', -000000322.99, 800000000, 'Kovacek-Beatty', 'Cecilemouth', '18917', '2988091353094312', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 266
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000869383367', '01', 1, 'POS TERM', 'Purchase at Thompson-Streich', 000000768.88, 800000000, 'Thompson-Streich', 'Port Estrella', '21832-3751', '2940139362300449', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 267
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000873232405', '01', 1, 'POS TERM', 'Purchase at Bins, Boehm and Casper', 000000720.99, 800000000, 'Bins, Boehm and Casper', 'South Lon', '87054', '4534784102713951', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 268
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000874953803', '01', 1, 'POS TERM', 'Purchase at McLaughlin-Blick', 000000399.44, 800000000, 'McLaughlin-Blick', 'Wintheisermouth', '03064', '1014086565224350', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 269
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000882360848', '01', 1, 'POS TERM', 'Purchase at Tromp-Kuhlman', 000000298.33, 800000000, 'Tromp-Kuhlman', 'Jerdeshire', '78699', '0982496213629795', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 270
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000884070277', '01', 1, 'POS TERM', 'Purchase at Bayer-O''Reilly', 000000194.33, 800000000, 'Bayer-O''Reilly', 'Stammmouth', '54961-5499', '9805583408996588', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 271
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000885437581', '01', 1, 'POS TERM', 'Purchase at Marquardt-Deckow', 000000818.00, 800000000, 'Marquardt-Deckow', 'Schmittport', '16465', '7026637615032277', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 272
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000887771179', '01', 1, 'POS TERM', 'Purchase at Jakubowski and Sons', 000000242.22, 800000000, 'Jakubowski and Sons', 'Port Tyramouth', '68202-7796', '7026637615032277', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 273
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000889986293', '01', 1, 'POS TERM', 'Purchase at Littel-Jacobson', 000000973.11, 800000000, 'Littel-Jacobson', 'Lestertown', '36198', '1561409106491600', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 274
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000898285002', '01', 1, 'POS TERM', 'Purchase at Gislason-Price', 000000958.77, 800000000, 'Gislason-Price', 'North Maverickbury', '09515-7261', '2871968252812490', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 275
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000899241176', '01', 1, 'POS TERM', 'Purchase at Beier, Larson and Schultz', 000000462.33, 800000000, 'Beier, Larson and Schultz', 'North Gudrunville', '59436-8470', '2988091353094312', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 276
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000900382063', '01', 1, 'POS TERM', 'Purchase at Bechtelar Group', 000000086.00, 800000000, 'Bechtelar Group', 'Mandybury', '49970-7370', '0927987108636232', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 277
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000903281896', '01', 1, 'POS TERM', 'Purchase at Schmitt, Mills and Yundt', 000000932.55, 800000000, 'Schmitt, Mills and Yundt', 'West Marlin', '92662-1169', '0683586198171516', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 278
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000909001545', '01', 1, 'POS TERM', 'Purchase at Crist Group', 000000893.33, 800000000, 'Crist Group', 'South Creola', '20922-4303', '5656830544981216', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 279
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000909315074', '01', 1, 'POS TERM', 'Purchase at Abbott and Sons', 000000759.22, 800000000, 'Abbott and Sons', 'East Cydney', '03808-7468', '4011500891777367', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 280
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000910081354', '01', 1, 'POS TERM', 'Purchase at Gerlach Group', 000000130.11, 800000000, 'Gerlach Group', 'Tannerburgh', '30389-8741', '7026637615032277', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 281
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000925687557', '03', 1, 'OPERATOR', 'Return item at Zboncak-Franecki', -000000372.99, 800000000, 'Zboncak-Franecki', 'Aldenport', '24426-3401', '0683586198171516', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 282
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000926624843', '01', 1, 'POS TERM', 'Purchase at Schowalter, Pagac and Welch', 000000684.33, 800000000, 'Schowalter, Pagac and Welch', 'West Isacton', '46573-2355', '4011500891777367', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 283
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000929059536', '01', 1, 'POS TERM', 'Purchase at Glover, Block and Huel', 000000920.11, 800000000, 'Glover, Block and Huel', 'Lake Dasiabury', '92661', '8517866958206008', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 284
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000934798061', '03', 1, 'OPERATOR', 'Return item at Gottlieb, VonRueden and Raynor', -000000260.11, 800000000, 'Gottlieb, VonRueden and Raynor', 'East Darryl', '94703', '9501733721429893', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 285
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000934945079', '01', 1, 'POS TERM', 'Purchase at Boyle, O''Conner and Gorczany', 000000222.44, 800000000, 'Boyle, O''Conner and Gorczany', 'South Kirstin', '23487', '6503535181795992', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 286
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000942960329', '01', 1, 'POS TERM', 'Purchase at Bartoletti, Lehner and Johnston', 000000711.66, 800000000, 'Bartoletti, Lehner and Johnston', 'North Virginie', '63690', '2940139362300449', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 287
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000943918566', '01', 1, 'POS TERM', 'Purchase at Walker, Mohr and Wyman', 000000437.77, 800000000, 'Walker, Mohr and Wyman', 'Kamronville', '93454', '8112545834239735', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 288
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000946277676', '01', 1, 'POS TERM', 'Purchase at Kemmer, Wyman and Ondricka', 000000220.00, 800000000, 'Kemmer, Wyman and Ondricka', 'Marcellechester', '28632', '3766281984155154', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 289
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000956474921', '01', 1, 'POS TERM', 'Purchase at Gottlieb, Turner and Ruecker', 000000223.33, 800000000, 'Gottlieb, Turner and Ruecker', 'Brettland', '98831-6582', '2745303720002090', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 290
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000957695517', '01', 1, 'POS TERM', 'Purchase at Schoen-Marvin', 000000573.22, 800000000, 'Schoen-Marvin', 'West Anastacio', '10111-5026', '0927987108636232', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 291
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000957864065', '01', 1, 'POS TERM', 'Purchase at Prohaska-Douglas', 000000314.66, 800000000, 'Prohaska-Douglas', 'North Leathahaven', '92680-2418', '3766281984155154', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 292
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000961186055', '01', 1, 'POS TERM', 'Purchase at Bartell-Fadel', 000000548.33, 800000000, 'Bartell-Fadel', 'Lebsackchester', '88382-6538', '1142167692878931', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 293
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000961714986', '01', 1, 'POS TERM', 'Purchase at West and Sons', 000000694.55, 800000000, 'West and Sons', 'Lawrencefort', '06664-6090', '3999169246375885', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 294
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000971342087', '03', 1, 'OPERATOR', 'Return item at Johnston Inc', -000000835.44, 800000000, 'Johnston Inc', 'Bergstromchester', '69737', '8112545834239735', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 295
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000973907278', '01', 1, 'POS TERM', 'Purchase at Klocko-Rice', 000000784.11, 800000000, 'Klocko-Rice', 'Shayneville', '50038-5154', '6349250331648509', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 296
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000974167587', '01', 1, 'POS TERM', 'Purchase at Moore and Sons', 000000402.22, 800000000, 'Moore and Sons', 'Parkerchester', '69137', '5975117516616077', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 297
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000976770816', '01', 1, 'POS TERM', 'Purchase at Corkery-Barton', 000000917.44, 800000000, 'Corkery-Barton', 'North Walterchester', '08815-3649', '8262593602473076', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 298
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000982241353', '01', 1, 'POS TERM', 'Purchase at Bins, Gorczany and Denesik', 000000765.66, 800000000, 'Bins, Gorczany and Denesik', 'Elveraville', '52528', '9805583408996588', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 299
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000992103545', '01', 1, 'POS TERM', 'Purchase at Dickens, Bartoletti and Ferry', 000000635.99, 800000000, 'Dickens, Bartoletti and Ferry', 'Lesleyville', '89308-8479', '1142167692878931', '2022-06-10 19:27:53.000000', NULL);

-- Source: dailytran.txt line 300
INSERT INTO transaction (transaction_id, transaction_type_code, transaction_category_code, source, description, amount, merchant_id, merchant_name, merchant_city, merchant_postal_code, card_number, original_timestamp, processed_timestamp)
VALUES ('0000000996722787', '01', 1, 'POS TERM', 'Purchase at Kilback LLC', 000000603.22, 800000000, 'Kilback LLC', 'Cummeratamouth', '53200-7529', '3260763612337560', '2022-06-10 19:27:53.000000', NULL);


-- ======================================================================================
-- TRANSACTION CATEGORY BALANCE DATA (50 records)
-- Source: app/data/ASCII/tcatbal.txt
-- Copybook: app/cpy/CVTRA01Y.cpy (50-byte fixed-width records)
-- Target Table: transaction_category_balance (src/main/resources/db/migration/V6__create_reference_tables.sql)
-- Record Layout:
--   Pos 1-11:  account_id (PIC 9(11))
--   Pos 12-13: transaction_type_code (PIC X(02))
--   Pos 14-17: transaction_category_code (PIC 9(04))
--   Pos 18-28: balance (PIC S9(09)V99 - zoned decimal)
--   Pos 29-50: FILLER (22 bytes)
-- Foreign Keys: 
--   account_id references account(account_id)
--   transaction_type_code references transaction_type(type_code)
--   transaction_category_code references transaction_category(category_code)
-- Note: Balance field uses COBOL zoned decimal encoding
-- ======================================================================================

-- Found 50 transaction category balance records

-- Source: tcatbal.txt line 1
INSERT INTO transaction_category_balance (account_id, transaction_type_code, transaction_category_code, balance)
VALUES (00000000001, '01', 1, 000000000.00);

-- Source: tcatbal.txt line 2
INSERT INTO transaction_category_balance (account_id, transaction_type_code, transaction_category_code, balance)
VALUES (00000000002, '01', 1, 000000000.00);

-- Source: tcatbal.txt line 3
INSERT INTO transaction_category_balance (account_id, transaction_type_code, transaction_category_code, balance)
VALUES (00000000003, '01', 1, 000000000.00);

-- Source: tcatbal.txt line 4
INSERT INTO transaction_category_balance (account_id, transaction_type_code, transaction_category_code, balance)
VALUES (00000000004, '01', 1, 000000000.00);

-- Source: tcatbal.txt line 5
INSERT INTO transaction_category_balance (account_id, transaction_type_code, transaction_category_code, balance)
VALUES (00000000005, '01', 1, 000000000.00);

-- Source: tcatbal.txt line 6
INSERT INTO transaction_category_balance (account_id, transaction_type_code, transaction_category_code, balance)
VALUES (00000000006, '01', 1, 000000000.00);

-- Source: tcatbal.txt line 7
INSERT INTO transaction_category_balance (account_id, transaction_type_code, transaction_category_code, balance)
VALUES (00000000007, '01', 1, 000000000.00);

-- Source: tcatbal.txt line 8
INSERT INTO transaction_category_balance (account_id, transaction_type_code, transaction_category_code, balance)
VALUES (00000000008, '01', 1, 000000000.00);

-- Source: tcatbal.txt line 9
INSERT INTO transaction_category_balance (account_id, transaction_type_code, transaction_category_code, balance)
VALUES (00000000009, '01', 1, 000000000.00);

-- Source: tcatbal.txt line 10
INSERT INTO transaction_category_balance (account_id, transaction_type_code, transaction_category_code, balance)
VALUES (00000000010, '01', 1, 000000000.00);

-- Source: tcatbal.txt line 11
INSERT INTO transaction_category_balance (account_id, transaction_type_code, transaction_category_code, balance)
VALUES (00000000011, '01', 1, 000000000.00);

-- Source: tcatbal.txt line 12
INSERT INTO transaction_category_balance (account_id, transaction_type_code, transaction_category_code, balance)
VALUES (00000000012, '01', 1, 000000000.00);

-- Source: tcatbal.txt line 13
INSERT INTO transaction_category_balance (account_id, transaction_type_code, transaction_category_code, balance)
VALUES (00000000013, '01', 1, 000000000.00);

-- Source: tcatbal.txt line 14
INSERT INTO transaction_category_balance (account_id, transaction_type_code, transaction_category_code, balance)
VALUES (00000000014, '01', 1, 000000000.00);

-- Source: tcatbal.txt line 15
INSERT INTO transaction_category_balance (account_id, transaction_type_code, transaction_category_code, balance)
VALUES (00000000015, '01', 1, 000000000.00);

-- Source: tcatbal.txt line 16
INSERT INTO transaction_category_balance (account_id, transaction_type_code, transaction_category_code, balance)
VALUES (00000000016, '01', 1, 000000000.00);

-- Source: tcatbal.txt line 17
INSERT INTO transaction_category_balance (account_id, transaction_type_code, transaction_category_code, balance)
VALUES (00000000017, '01', 1, 000000000.00);

-- Source: tcatbal.txt line 18
INSERT INTO transaction_category_balance (account_id, transaction_type_code, transaction_category_code, balance)
VALUES (00000000018, '01', 1, 000000000.00);

-- Source: tcatbal.txt line 19
INSERT INTO transaction_category_balance (account_id, transaction_type_code, transaction_category_code, balance)
VALUES (00000000019, '01', 1, 000000000.00);

-- Source: tcatbal.txt line 20
INSERT INTO transaction_category_balance (account_id, transaction_type_code, transaction_category_code, balance)
VALUES (00000000020, '01', 1, 000000000.00);

-- Source: tcatbal.txt line 21
INSERT INTO transaction_category_balance (account_id, transaction_type_code, transaction_category_code, balance)
VALUES (00000000021, '01', 1, 000000000.00);

-- Source: tcatbal.txt line 22
INSERT INTO transaction_category_balance (account_id, transaction_type_code, transaction_category_code, balance)
VALUES (00000000022, '01', 1, 000000000.00);

-- Source: tcatbal.txt line 23
INSERT INTO transaction_category_balance (account_id, transaction_type_code, transaction_category_code, balance)
VALUES (00000000023, '01', 1, 000000000.00);

-- Source: tcatbal.txt line 24
INSERT INTO transaction_category_balance (account_id, transaction_type_code, transaction_category_code, balance)
VALUES (00000000024, '01', 1, 000000000.00);

-- Source: tcatbal.txt line 25
INSERT INTO transaction_category_balance (account_id, transaction_type_code, transaction_category_code, balance)
VALUES (00000000025, '01', 1, 000000000.00);

-- Source: tcatbal.txt line 26
INSERT INTO transaction_category_balance (account_id, transaction_type_code, transaction_category_code, balance)
VALUES (00000000026, '01', 1, 000000000.00);

-- Source: tcatbal.txt line 27
INSERT INTO transaction_category_balance (account_id, transaction_type_code, transaction_category_code, balance)
VALUES (00000000027, '01', 1, 000000000.00);

-- Source: tcatbal.txt line 28
INSERT INTO transaction_category_balance (account_id, transaction_type_code, transaction_category_code, balance)
VALUES (00000000028, '01', 1, 000000000.00);

-- Source: tcatbal.txt line 29
INSERT INTO transaction_category_balance (account_id, transaction_type_code, transaction_category_code, balance)
VALUES (00000000029, '01', 1, 000000000.00);

-- Source: tcatbal.txt line 30
INSERT INTO transaction_category_balance (account_id, transaction_type_code, transaction_category_code, balance)
VALUES (00000000030, '01', 1, 000000000.00);

-- Source: tcatbal.txt line 31
INSERT INTO transaction_category_balance (account_id, transaction_type_code, transaction_category_code, balance)
VALUES (00000000031, '01', 1, 000000000.00);

-- Source: tcatbal.txt line 32
INSERT INTO transaction_category_balance (account_id, transaction_type_code, transaction_category_code, balance)
VALUES (00000000032, '01', 1, 000000000.00);

-- Source: tcatbal.txt line 33
INSERT INTO transaction_category_balance (account_id, transaction_type_code, transaction_category_code, balance)
VALUES (00000000033, '01', 1, 000000000.00);

-- Source: tcatbal.txt line 34
INSERT INTO transaction_category_balance (account_id, transaction_type_code, transaction_category_code, balance)
VALUES (00000000034, '01', 1, 000000000.00);

-- Source: tcatbal.txt line 35
INSERT INTO transaction_category_balance (account_id, transaction_type_code, transaction_category_code, balance)
VALUES (00000000035, '01', 1, 000000000.00);

-- Source: tcatbal.txt line 36
INSERT INTO transaction_category_balance (account_id, transaction_type_code, transaction_category_code, balance)
VALUES (00000000036, '01', 1, 000000000.00);

-- Source: tcatbal.txt line 37
INSERT INTO transaction_category_balance (account_id, transaction_type_code, transaction_category_code, balance)
VALUES (00000000037, '01', 1, 000000000.00);

-- Source: tcatbal.txt line 38
INSERT INTO transaction_category_balance (account_id, transaction_type_code, transaction_category_code, balance)
VALUES (00000000038, '01', 1, 000000000.00);

-- Source: tcatbal.txt line 39
INSERT INTO transaction_category_balance (account_id, transaction_type_code, transaction_category_code, balance)
VALUES (00000000039, '01', 1, 000000000.00);

-- Source: tcatbal.txt line 40
INSERT INTO transaction_category_balance (account_id, transaction_type_code, transaction_category_code, balance)
VALUES (00000000040, '01', 1, 000000000.00);

-- Source: tcatbal.txt line 41
INSERT INTO transaction_category_balance (account_id, transaction_type_code, transaction_category_code, balance)
VALUES (00000000041, '01', 1, 000000000.00);

-- Source: tcatbal.txt line 42
INSERT INTO transaction_category_balance (account_id, transaction_type_code, transaction_category_code, balance)
VALUES (00000000042, '01', 1, 000000000.00);

-- Source: tcatbal.txt line 43
INSERT INTO transaction_category_balance (account_id, transaction_type_code, transaction_category_code, balance)
VALUES (00000000043, '01', 1, 000000000.00);

-- Source: tcatbal.txt line 44
INSERT INTO transaction_category_balance (account_id, transaction_type_code, transaction_category_code, balance)
VALUES (00000000044, '01', 1, 000000000.00);

-- Source: tcatbal.txt line 45
INSERT INTO transaction_category_balance (account_id, transaction_type_code, transaction_category_code, balance)
VALUES (00000000045, '01', 1, 000000000.00);

-- Source: tcatbal.txt line 46
INSERT INTO transaction_category_balance (account_id, transaction_type_code, transaction_category_code, balance)
VALUES (00000000046, '01', 1, 000000000.00);

-- Source: tcatbal.txt line 47
INSERT INTO transaction_category_balance (account_id, transaction_type_code, transaction_category_code, balance)
VALUES (00000000047, '01', 1, 000000000.00);

-- Source: tcatbal.txt line 48
INSERT INTO transaction_category_balance (account_id, transaction_type_code, transaction_category_code, balance)
VALUES (00000000048, '01', 1, 000000000.00);

-- Source: tcatbal.txt line 49
INSERT INTO transaction_category_balance (account_id, transaction_type_code, transaction_category_code, balance)
VALUES (00000000049, '01', 1, 000000000.00);

-- Source: tcatbal.txt line 50
INSERT INTO transaction_category_balance (account_id, transaction_type_code, transaction_category_code, balance)
VALUES (00000000050, '01', 1, 000000000.00);


-- ======================================================================================
-- END OF SEED DATA MIGRATION
-- ======================================================================================
-- 
-- Migration Summary:
--   Transaction Types:           7 records inserted
--   Transaction Categories:      18 records inserted
--   Disclosure Groups:           51 records inserted
--   Customers:                   50 records inserted
--   Accounts:                    50 records inserted
--   Cards:                       50 records inserted
--   Transactions:                300 records inserted
--   Transaction Category Balances: 50 records inserted
--   ------------------------------------------------
--   TOTAL RECORDS:               576 records inserted
--
-- Foreign Key Relationships:
--   - All account records reference valid customer_id values
--   - All card records reference valid account_id values
--   - All transaction records reference valid card_number values
--   - All transaction_category_balance records reference valid account_id,
--     transaction_type_code, and transaction_category_code values
--
-- Cross-Reference Data:
--   - cardxref.txt data is represented through foreign keys (card.account_id and
--     account.customer_id) and does not require separate table insertion
--
-- Data Integrity:
--   - All INSERT statements respect foreign key ordering
--   - Reference tables populated before master entity tables
--   - Master entity tables populated in dependency order
--   - All COBOL zoned decimal fields properly converted to PostgreSQL NUMERIC types
--   - All date fields converted from YYYYMMDD to ISO-8601 format
--   - All monetary values maintain proper scale (2 decimal places) per COBOL PIC clauses
--
-- Verification:
--   Run these queries to verify data insertion:
--     SELECT COUNT(*) FROM transaction_type;           -- Expected: 7
--     SELECT COUNT(*) FROM transaction_category;       -- Expected: 18
--     SELECT COUNT(*) FROM disclosure_group;           -- Expected: 51
--     SELECT COUNT(*) FROM customer;                   -- Expected: 50
--     SELECT COUNT(*) FROM account;                    -- Expected: 50
--     SELECT COUNT(*) FROM card;                       -- Expected: 50
--     SELECT COUNT(*) FROM transaction;                -- Expected: 300
--     SELECT COUNT(*) FROM transaction_category_balance; -- Expected: 50
--
-- ======================================================================================

