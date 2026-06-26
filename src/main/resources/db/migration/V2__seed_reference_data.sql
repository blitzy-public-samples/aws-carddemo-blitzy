-- Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
--
-- Licensed under the Apache License, Version 2.0 (the "License").
-- You may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--    http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.
-- ============================================================================
--
-- ============================================================================
-- V2__seed_reference_data.sql  -  AWS CardDemo modernization (Java 25 + Spring Boot 3.5.x)
-- ----------------------------------------------------------------------------
-- REFERENCE / LOOKUP SEED DATA.
--
-- Runs AFTER V1__schema.sql (which creates the tables). Seeds the four
-- reference tables the online + batch logic depends on:
--   tran_type (7)  ->  tran_category (18)  ->  disclosure_group (51)  ->  tran_cat_balance (50)
--
-- SOURCE FIXTURES (legacy fixed-width ASCII, retained read-only under /legacy):
--   app/data/ASCII/trantype.txt   -> tran_type
--   app/data/ASCII/trancatg.txt   -> tran_category
--   app/data/ASCII/discgrp.txt    -> disclosure_group
--   app/data/ASCII/tcatbal.txt    -> tran_cat_balance
--
-- COBOL ZONED-DECIMAL SIGN OVERPUNCH (decimal fidelity, AAP 0.6.1):
--   In the legacy fixtures the dis_int_rate and tran_cat_bal numeric fields
--   are stored as zoned decimals whose TRAILING byte encodes the arithmetic
--   sign together with the value of the final digit. Every sign byte present
--   in the current fixtures is the positive-zero form -- a positive number
--   whose last digit is 0. Each field is DECODED here to a plain numeric
--   literal carrying the implied 2-decimal scale: for example the rate digits
--   00150 plus a positive last-digit-0 sign become 001500 -> 15.00, and an
--   all-zero balance becomes 0.00. The raw zoned sign byte is NEVER persisted;
--   only fully decoded numeric literals appear in the INSERT statements below.
--
-- SEED USERS ARE NOT CREATED HERE (security hardening, AAP 0.6.6 / 0.7.2):
--   The two seed identities ADMIN001 (type 'A') and USER0001 (type 'U') are
--   NOT inserted by this migration. They are created at application startup
--   by the bootstrap security seeder (a CommandLineRunner under
--   src/main/java/com/aws/carddemo/config) which reads the externalized
--   admin / user credential environment variables (CARDDEMO_ADMIN_* /
--   CARDDEMO_USER_*) and BCrypt-encodes them in memory. This migration
--   therefore touches ONLY the four reference tables below; it never writes
--   to the security credential table and never embeds a clear-text secret or
--   a pre-computed hash literal.
--
-- CONVENTIONS:
--   * char(n) columns receive the trimmed literal value; PostgreSQL blank-pads
--     char(n) on storage and ignores trailing spaces in comparisons, so e.g.
--     'DEFAULT' stored in char(10) equals 'DEFAULT   ' -- fixed-width parity.
--   * numeric columns receive plain decimal literals at the declared scale.
--   * Flyway runs V2 exactly once, so plain INSERTs are used (no ON CONFLICT).
--   * PostgreSQL 16 dialect.
-- ============================================================================


-- ============================================================================
-- 1. tran_type   (source app/data/ASCII/trantype.txt)
--    7 transaction-type codes. char(2) code, char(50) description.
-- ============================================================================
INSERT INTO tran_type (tran_type, tran_type_desc) VALUES
    ('01', 'Purchase'),
    ('02', 'Payment'),
    ('03', 'Credit'),
    ('04', 'Authorization'),
    ('05', 'Refund'),
    ('06', 'Reversal'),
    ('07', 'Adjustment');


-- ============================================================================
-- 2. tran_category   (source app/data/ASCII/trancatg.txt)
--    18 (type_cd, cat_cd) categories. Note type 01 has 5 categories,
--    including 0005 'Interest Amount'. char(2) type, char(4) category code,
--    char(50) description.
-- ============================================================================
INSERT INTO tran_category (tran_type_cd, tran_cat_cd, tran_cat_type_desc) VALUES
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


-- ============================================================================
-- 3. disclosure_group   (source app/data/ASCII/discgrp.txt)
--    51 rows = 3 account-group blocks of 17 (A000000000, DEFAULT, ZEROAPR),
--    each covering the same 17 (type_cd, cat_cd) combinations.
--    dis_int_rate is decoded from the 6-byte zoned-decimal overpunch field.
--    Decoded rate distribution:  0.00 -> 30 rows,  15.00 -> 15 rows,  25.00 -> 6 rows.
--    NOTE the ONE intentional difference between blocks A000000000 and DEFAULT:
--    row (07,0001) is 15.00 in A000000000 but 0.00 in DEFAULT (verified from source).
-- ============================================================================
INSERT INTO disclosure_group (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd, dis_int_rate) VALUES
    -- block 'A000000000'
    ('A000000000', '01', '0001', 15.00),
    ('A000000000', '01', '0002', 25.00),
    ('A000000000', '01', '0003', 25.00),
    ('A000000000', '01', '0004', 25.00),
    ('A000000000', '02', '0001', 0.00),
    ('A000000000', '02', '0002', 0.00),
    ('A000000000', '02', '0003', 0.00),
    ('A000000000', '03', '0001', 0.00),
    ('A000000000', '03', '0002', 0.00),
    ('A000000000', '03', '0003', 0.00),
    ('A000000000', '04', '0001', 15.00),
    ('A000000000', '04', '0002', 15.00),
    ('A000000000', '04', '0003', 15.00),
    ('A000000000', '05', '0001', 15.00),
    ('A000000000', '06', '0001', 15.00),
    ('A000000000', '06', '0002', 15.00),
    ('A000000000', '07', '0001', 15.00),
    -- block 'DEFAULT'
    ('DEFAULT', '01', '0001', 15.00),
    ('DEFAULT', '01', '0002', 25.00),
    ('DEFAULT', '01', '0003', 25.00),
    ('DEFAULT', '01', '0004', 25.00),
    ('DEFAULT', '02', '0001', 0.00),
    ('DEFAULT', '02', '0002', 0.00),
    ('DEFAULT', '02', '0003', 0.00),
    ('DEFAULT', '03', '0001', 0.00),
    ('DEFAULT', '03', '0002', 0.00),
    ('DEFAULT', '03', '0003', 0.00),
    ('DEFAULT', '04', '0001', 15.00),
    ('DEFAULT', '04', '0002', 15.00),
    ('DEFAULT', '04', '0003', 15.00),
    ('DEFAULT', '05', '0001', 15.00),
    ('DEFAULT', '06', '0001', 15.00),
    ('DEFAULT', '06', '0002', 15.00),
    ('DEFAULT', '07', '0001', 0.00),
    -- block 'ZEROAPR'
    ('ZEROAPR', '01', '0001', 0.00),
    ('ZEROAPR', '01', '0002', 0.00),
    ('ZEROAPR', '01', '0003', 0.00),
    ('ZEROAPR', '01', '0004', 0.00),
    ('ZEROAPR', '02', '0001', 0.00),
    ('ZEROAPR', '02', '0002', 0.00),
    ('ZEROAPR', '02', '0003', 0.00),
    ('ZEROAPR', '03', '0001', 0.00),
    ('ZEROAPR', '03', '0002', 0.00),
    ('ZEROAPR', '03', '0003', 0.00),
    ('ZEROAPR', '04', '0001', 0.00),
    ('ZEROAPR', '04', '0002', 0.00),
    ('ZEROAPR', '04', '0003', 0.00),
    ('ZEROAPR', '05', '0001', 0.00),
    ('ZEROAPR', '06', '0001', 0.00),
    ('ZEROAPR', '06', '0002', 0.00),
    ('ZEROAPR', '07', '0001', 0.00);


-- ============================================================================
-- 4. tran_cat_balance   (source app/data/ASCII/tcatbal.txt)
--    50 rows, one per account id 1..50 (fixture stores zero-padded 9(11);
--    inserted as numeric). All rows: type_cd '01', cd '0001', tran_cat_bal
--    decoded from the 11-byte overpunch field = 0.00 for every row.
-- ============================================================================
INSERT INTO tran_cat_balance (trancat_acct_id, trancat_type_cd, trancat_cd, tran_cat_bal) VALUES
    (1, '01', '0001', 0.00),
    (2, '01', '0001', 0.00),
    (3, '01', '0001', 0.00),
    (4, '01', '0001', 0.00),
    (5, '01', '0001', 0.00),
    (6, '01', '0001', 0.00),
    (7, '01', '0001', 0.00),
    (8, '01', '0001', 0.00),
    (9, '01', '0001', 0.00),
    (10, '01', '0001', 0.00),
    (11, '01', '0001', 0.00),
    (12, '01', '0001', 0.00),
    (13, '01', '0001', 0.00),
    (14, '01', '0001', 0.00),
    (15, '01', '0001', 0.00),
    (16, '01', '0001', 0.00),
    (17, '01', '0001', 0.00),
    (18, '01', '0001', 0.00),
    (19, '01', '0001', 0.00),
    (20, '01', '0001', 0.00),
    (21, '01', '0001', 0.00),
    (22, '01', '0001', 0.00),
    (23, '01', '0001', 0.00),
    (24, '01', '0001', 0.00),
    (25, '01', '0001', 0.00),
    (26, '01', '0001', 0.00),
    (27, '01', '0001', 0.00),
    (28, '01', '0001', 0.00),
    (29, '01', '0001', 0.00),
    (30, '01', '0001', 0.00),
    (31, '01', '0001', 0.00),
    (32, '01', '0001', 0.00),
    (33, '01', '0001', 0.00),
    (34, '01', '0001', 0.00),
    (35, '01', '0001', 0.00),
    (36, '01', '0001', 0.00),
    (37, '01', '0001', 0.00),
    (38, '01', '0001', 0.00),
    (39, '01', '0001', 0.00),
    (40, '01', '0001', 0.00),
    (41, '01', '0001', 0.00),
    (42, '01', '0001', 0.00),
    (43, '01', '0001', 0.00),
    (44, '01', '0001', 0.00),
    (45, '01', '0001', 0.00),
    (46, '01', '0001', 0.00),
    (47, '01', '0001', 0.00),
    (48, '01', '0001', 0.00),
    (49, '01', '0001', 0.00),
    (50, '01', '0001', 0.00);

