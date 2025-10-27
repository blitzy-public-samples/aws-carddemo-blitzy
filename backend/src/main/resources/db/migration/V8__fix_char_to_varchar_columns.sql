-- Migration: V8__fix_char_to_varchar_columns.sql
-- Purpose: Convert CHAR(1) columns to VARCHAR(1) for Hibernate schema validation compatibility
-- 
-- Issue: Hibernate schema validation expects VARCHAR for String mappings, but PostgreSQL
-- CHAR(1) columns (stored as bpchar internally) cause validation failures with error:
-- "Schema-validation: wrong column type encountered...found [bpchar], but expecting [VARCHAR]"
--
-- This migration alters the column types while preserving all data and constraints.
-- The functional behavior remains identical (single character storage), but resolves
-- the Hibernate/PostgreSQL type compatibility issue.
--
-- Affected tables and columns:
-- 1. account.acct_active_status (from COBOL ACCT-ACTIVE-STATUS PIC X(01))
-- 2. card.card_active_status (from COBOL CARD-ACTIVE-STATUS PIC X(01))
-- 3. user_security.user_type (from COBOL SEC-USR-TYPE PIC X(01))
--
-- Reference: Agent Action Plan Section 0.7.2 - COBOL PIC X(01) → Java String conversion

-- Alter account table: acct_active_status
ALTER TABLE account
ALTER COLUMN acct_active_status TYPE VARCHAR(1);

COMMENT ON COLUMN account.acct_active_status IS 'Account active status indicator. COBOL PIC X(01) ACCT-ACTIVE-STATUS. Valid values: Y (active), N (inactive), C (closed), S (suspended). Changed from CHAR(1) to VARCHAR(1) for Hibernate compatibility.';

-- Alter card table: card_active_status
ALTER TABLE card
ALTER COLUMN card_active_status TYPE VARCHAR(1);

COMMENT ON COLUMN card.card_active_status IS 'Card status indicator. COBOL PIC X(01) CARD-ACTIVE-STATUS. Valid values: A (active), I (inactive), S (stolen), L (lost), E (expired), C (closed). Changed from CHAR(1) to VARCHAR(1) for Hibernate compatibility.';

-- Alter user_security table: user_type
ALTER TABLE user_security
ALTER COLUMN user_type TYPE VARCHAR(1);

COMMENT ON COLUMN user_security.user_type IS 'User type indicator. COBOL PIC X(01) SEC-USR-TYPE. Valid values: A (admin), U (user), O (operator). Changed from CHAR(1) to VARCHAR(1) for Hibernate compatibility.';
