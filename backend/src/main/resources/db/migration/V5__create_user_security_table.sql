-- ******************************************************************
-- Copyright Amazon.com, Inc. or its affiliates.
-- All Rights Reserved.
--
-- Licensed under the Apache License, Version 2.0 (the "License").
-- You may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--    http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing,
-- software distributed under the License is distributed on an
-- "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
-- either express or implied. See the License for the specific
-- language governing permissions and limitations under the License
-- ******************************************************************

-- Flyway Migration V5: Create user_security table
-- Source: USRSEC VSAM KSDS (KEYLEN=8, MAXLRECL=80, REC-TOTAL=10)
-- Copybook: CSUSR01Y.cpy (SEC-USER-DATA structure)
-- Purpose: User security and authentication for Spring Security UserDetailsService
-- Migration Type: COBOL VSAM to PostgreSQL relational table

-- ******************************************************************
-- TABLE: user_security
-- ******************************************************************
-- Transformation from USRSEC VSAM KSDS to PostgreSQL table
-- Implements Spring Security UserDetailsService interface requirements
-- Replaces file-based authentication with JWT token-based authentication
-- ******************************************************************

CREATE TABLE user_security (
  -- ****************************************************************
  -- PRIMARY KEY: user_id
  -- Source: SEC-USR-ID PIC X(08) from CSUSR01Y.cpy
  -- VSAM KEYLEN=8 (primary key field)
  -- ****************************************************************
  user_id VARCHAR(8) NOT NULL,
  
  -- ****************************************************************
  -- AUTHENTICATION FIELD: username
  -- Duplicate of user_id for Spring Security authentication lookups
  -- Unique constraint ensures one-to-one mapping with user_id
  -- ****************************************************************
  username VARCHAR(8) NOT NULL,
  
  -- ****************************************************************
  -- USER PROFILE FIELDS from CSUSR01Y.cpy
  -- ****************************************************************
  
  -- Source: SEC-USR-FNAME PIC X(20)
  -- User first name (up to 20 characters)
  first_name VARCHAR(20) NOT NULL,
  
  -- Source: SEC-USR-LNAME PIC X(20)
  -- User last name (up to 20 characters)
  last_name VARCHAR(20) NOT NULL,
  
  -- ****************************************************************
  -- PASSWORD FIELD: password_hash
  -- Source: SEC-USR-PWD PIC X(08) (plaintext in COBOL)
  -- CRITICAL TRANSFORMATION: Plaintext → BCrypt hash
  -- BCrypt strength: 12 (per Section 0.5 Security Configuration)
  -- Hash length: 60 characters (BCrypt output), with buffer for future algorithms
  -- ****************************************************************
  password_hash VARCHAR(100) NOT NULL,
  
  -- ****************************************************************
  -- USER ROLE FIELD: user_type
  -- Source: SEC-USR-TYPE PIC X(01) from CSUSR01Y.cpy
  -- Two-tier role model per Section 0.1 Core Refactoring Goals:
  --   'R' = Regular User → Maps to ROLE_USER in Spring Security
  --   'A' = Administrative User → Maps to ROLE_ADMIN in Spring Security
  -- ****************************************************************
  user_type VARCHAR(1) NOT NULL DEFAULT 'R',
  
  -- ****************************************************************
  -- SPRING SECURITY UserDetailsService REQUIRED FIELDS
  -- These fields are NOT in COBOL CSUSR01Y.cpy but required for
  -- Spring Security UserDetailsService interface implementation
  -- ****************************************************************
  
  -- User account is enabled and can authenticate
  enabled BOOLEAN NOT NULL DEFAULT true,
  
  -- Account has not expired (for account lifecycle management)
  account_non_expired BOOLEAN NOT NULL DEFAULT true,
  
  -- Credentials (password) have not expired (for password rotation policies)
  credentials_non_expired BOOLEAN NOT NULL DEFAULT true,
  
  -- Account is not locked (for failed login attempt protection)
  -- Automatically set to false after failed_login_attempts >= 5
  account_non_locked BOOLEAN NOT NULL DEFAULT true,
  
  -- ****************************************************************
  -- AUDIT AND SECURITY TRACKING FIELDS
  -- Not in original COBOL copybook, added for security best practices
  -- and audit trail validation per Section 0.9 Special Instructions
  -- ****************************************************************
  
  -- Timestamp when user record was created
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  
  -- Timestamp when user record was last updated (auto-updated by trigger)
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  
  -- Timestamp of user's last successful login
  last_login_at TIMESTAMP,
  
  -- Counter for consecutive failed login attempts
  -- Account automatically locked when this reaches 5 (via trigger)
  failed_login_attempts INTEGER NOT NULL DEFAULT 0,
  
  -- ****************************************************************
  -- PRIMARY KEY CONSTRAINT
  -- Maps to VSAM KSDS primary key on SEC-USR-ID (KEYLEN=8)
  -- ****************************************************************
  CONSTRAINT pk_user_security PRIMARY KEY (user_id),
  
  -- ****************************************************************
  -- UNIQUE CONSTRAINTS
  -- Username must be unique for Spring Security authentication
  -- ****************************************************************
  CONSTRAINT uk_user_username UNIQUE (username),
  
  -- ****************************************************************
  -- CHECK CONSTRAINTS
  -- Validate user_type matches two-tier role model: R or A only
  -- ****************************************************************
  CONSTRAINT chk_user_type CHECK (user_type IN ('R', 'A')),
  
  -- Validate failed_login_attempts is non-negative
  CONSTRAINT chk_failed_login_attempts CHECK (failed_login_attempts >= 0)
);

-- ******************************************************************
-- TABLE COMMENTS - Documentation for database schema
-- ******************************************************************

COMMENT ON TABLE user_security IS 
'User security and authentication records migrated from USRSEC VSAM KSDS file. Original specs: KEYLEN=8, MAXLRECL=80, 10 records. Based on CSUSR01Y.cpy copybook structure (SEC-USER-DATA). Implements Spring Security UserDetailsService interface for JWT token-based authentication, replacing RACF file-based authentication per Section 0.1 Core Refactoring Goals.';

COMMENT ON COLUMN user_security.user_id IS 
'User ID (8 characters), from SEC-USR-ID PIC X(08) in CSUSR01Y.cpy. Primary key matching VSAM KSDS KEYLEN=8.';

COMMENT ON COLUMN user_security.username IS 
'Username for Spring Security authentication, duplicates user_id. Unique constraint ensures one-to-one mapping. Used by CustomUserDetailsService for loadUserByUsername() method.';

COMMENT ON COLUMN user_security.first_name IS 
'User first name (up to 20 characters), from SEC-USR-FNAME PIC X(20) in CSUSR01Y.cpy.';

COMMENT ON COLUMN user_security.last_name IS 
'User last name (up to 20 characters), from SEC-USR-LNAME PIC X(20) in CSUSR01Y.cpy.';

COMMENT ON COLUMN user_security.password_hash IS 
'BCrypt password hash (strength 12) replacing plaintext SEC-USR-PWD PIC X(08) from CSUSR01Y.cpy. Hash generated using Spring Security BCryptPasswordEncoder. Supports up to 100 characters for future algorithm compatibility.';

COMMENT ON COLUMN user_security.user_type IS 
'User type from SEC-USR-TYPE PIC X(01) in CSUSR01Y.cpy. Two-tier role model: R=Regular User (maps to ROLE_USER), A=Administrative User (maps to ROLE_ADMIN). Used by Spring Security for role-based authorization via @PreAuthorize annotations.';

COMMENT ON COLUMN user_security.enabled IS 
'Spring Security UserDetailsService field. Indicates if user account is enabled. When false, authentication attempts are rejected.';

COMMENT ON COLUMN user_security.account_non_expired IS 
'Spring Security UserDetailsService field. Indicates if user account has not expired. Used for account lifecycle management.';

COMMENT ON COLUMN user_security.credentials_non_expired IS 
'Spring Security UserDetailsService field. Indicates if user credentials (password) have not expired. Used for password rotation policies.';

COMMENT ON COLUMN user_security.account_non_locked IS 
'Spring Security UserDetailsService field. Indicates if user account is not locked. Automatically set to false by trigger when failed_login_attempts reaches 5. Prevents brute-force password attacks.';

COMMENT ON COLUMN user_security.created_at IS 
'Timestamp when user record was created. Audit field for security tracking and compliance, not present in original COBOL copybook.';

COMMENT ON COLUMN user_security.updated_at IS 
'Timestamp when user record was last updated. Automatically maintained by update_user_timestamp() trigger. Audit field for security tracking.';

COMMENT ON COLUMN user_security.last_login_at IS 
'Timestamp of user''s last successful login. Updated by application after successful authentication. Used for inactive account monitoring.';

COMMENT ON COLUMN user_security.failed_login_attempts IS 
'Counter for consecutive failed login attempts. Reset to 0 on successful login. Triggers account lock when reaching 5 attempts via check_failed_login_attempts() trigger. Security feature not present in original COBOL implementation.';

-- ******************************************************************
-- INDEXES
-- ******************************************************************
-- Primary index on user_id automatically created by PRIMARY KEY constraint
-- Unique index on username automatically created by UNIQUE constraint
-- Additional indexes for query optimization
-- ******************************************************************

-- Index on user_type for role-based filtering queries
-- Used by admin functions to list users by role (ROLE_USER vs ROLE_ADMIN)
CREATE INDEX idx_user_type ON user_security(user_type);

-- Composite index on enabled and account_non_locked for active user queries
-- Optimizes queries filtering for enabled, non-locked accounts during authentication
CREATE INDEX idx_user_active_status ON user_security(enabled, account_non_locked) 
WHERE enabled = true AND account_non_locked = true;

-- Index on last_login_at for inactive user reports
-- Used by administrative functions to identify dormant accounts
CREATE INDEX idx_user_last_login ON user_security(last_login_at);

-- ******************************************************************
-- TRIGGER FUNCTION: update_user_timestamp()
-- ******************************************************************
-- Automatically updates updated_at column on any UPDATE operation
-- Ensures audit trail accuracy without application code dependencies
-- ******************************************************************

CREATE OR REPLACE FUNCTION update_user_timestamp()
RETURNS TRIGGER AS $$
BEGIN
  NEW.updated_at = CURRENT_TIMESTAMP;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION update_user_timestamp() IS 
'Trigger function to automatically update updated_at timestamp on user_security table modifications. Maintains accurate audit trail for security compliance.';

-- ******************************************************************
-- TRIGGER: trg_user_updated_at
-- ******************************************************************
-- Fires before UPDATE to maintain updated_at timestamp
-- ******************************************************************

CREATE TRIGGER trg_user_updated_at
  BEFORE UPDATE ON user_security
  FOR EACH ROW
  EXECUTE FUNCTION update_user_timestamp();

COMMENT ON TRIGGER trg_user_updated_at ON user_security IS 
'Automatically updates updated_at column before any row update. Ensures accurate audit trail timestamps.';

-- ******************************************************************
-- TRIGGER FUNCTION: check_failed_login_attempts()
-- ******************************************************************
-- Automatically locks account when failed login attempts reach threshold
-- Implements brute-force attack protection not present in COBOL system
-- Threshold: 5 failed attempts (configurable business rule)
-- ******************************************************************

CREATE OR REPLACE FUNCTION check_failed_login_attempts()
RETURNS TRIGGER AS $$
BEGIN
  -- Lock account if failed login attempts reach or exceed 5
  -- This implements security best practice for brute-force protection
  IF NEW.failed_login_attempts >= 5 THEN
    NEW.account_non_locked = false;
    
    -- Log the account lock event for security audit
    -- Note: In production, this should integrate with centralized logging
    RAISE NOTICE 'User account locked due to failed login attempts: user_id=%', NEW.user_id;
  END IF;
  
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION check_failed_login_attempts() IS 
'Trigger function to automatically lock user account when failed_login_attempts reaches 5. Implements brute-force attack protection. Account can be unlocked by admin setting account_non_locked=true and resetting failed_login_attempts=0.';

-- ******************************************************************
-- TRIGGER: trg_check_failed_logins
-- ******************************************************************
-- Fires before UPDATE of failed_login_attempts column
-- Prevents brute-force password attacks by locking accounts
-- ******************************************************************

CREATE TRIGGER trg_check_failed_logins
  BEFORE UPDATE OF failed_login_attempts ON user_security
  FOR EACH ROW
  EXECUTE FUNCTION check_failed_login_attempts();

COMMENT ON TRIGGER trg_check_failed_logins ON user_security IS 
'Automatically locks account (sets account_non_locked=false) when failed_login_attempts reaches 5. Implements security best practice for brute-force attack prevention.';

-- ******************************************************************
-- MIGRATION VALIDATION QUERIES
-- ******************************************************************
-- These queries can be used to validate the migration from VSAM to PostgreSQL
-- Expected record count: 10 records (REC-TOTAL=10 from USRSEC VSAM)
-- ******************************************************************

-- Query to verify table structure matches COBOL copybook
-- SELECT 
--   column_name, 
--   data_type, 
--   character_maximum_length,
--   is_nullable,
--   column_default
-- FROM information_schema.columns
-- WHERE table_name = 'user_security'
-- ORDER BY ordinal_position;

-- Query to verify record count after migration
-- SELECT COUNT(*) as record_count FROM user_security;
-- Expected result: 10 (matching VSAM REC-TOTAL)

-- Query to verify user type distribution (two-tier role model)
-- SELECT 
--   user_type,
--   CASE user_type
--     WHEN 'R' THEN 'ROLE_USER (Regular User)'
--     WHEN 'A' THEN 'ROLE_ADMIN (Administrative User)'
--   END as spring_security_role,
--   COUNT(*) as count
-- FROM user_security
-- GROUP BY user_type
-- ORDER BY user_type;

-- Query to verify password hash format (BCrypt validation)
-- SELECT 
--   user_id,
--   LENGTH(password_hash) as hash_length,
--   SUBSTRING(password_hash, 1, 4) as bcrypt_prefix
-- FROM user_security;
-- Expected: hash_length = 60, bcrypt_prefix = '$2a$' or '$2b$'

-- ******************************************************************
-- DATA INTEGRITY RULES
-- ******************************************************************
-- 1. user_id must be exactly 8 characters (matching VSAM KEYLEN=8)
-- 2. username must equal user_id (enforced by application, not database)
-- 3. password_hash must be valid BCrypt hash starting with $2a$ or $2b$
-- 4. user_type must be 'R' or 'A' only (enforced by CHECK constraint)
-- 5. failed_login_attempts resets to 0 on successful login (application logic)
-- 6. last_login_at updated on successful authentication (application logic)
-- 7. Account unlock requires admin intervention: UPDATE user_security 
--    SET account_non_locked = true, failed_login_attempts = 0
-- ******************************************************************

-- ******************************************************************
-- SPRING SECURITY INTEGRATION NOTES
-- ******************************************************************
-- 1. CustomUserDetailsService.loadUserByUsername() queries by username
-- 2. User authorities derived from user_type:
--    - user_type='R' → GrantedAuthority("ROLE_USER")
--    - user_type='A' → GrantedAuthority("ROLE_ADMIN")
-- 3. UserDetails fields mapped directly:
--    - enabled → isEnabled()
--    - account_non_expired → isAccountNonExpired()
--    - credentials_non_expired → isCredentialsNonExpired()
--    - account_non_locked → isAccountNonLocked()
-- 4. Password validation via BCryptPasswordEncoder.matches()
-- 5. JWT token contains user_id, username, and roles
-- ******************************************************************

-- ******************************************************************
-- TRANSFORMATION SUMMARY
-- ******************************************************************
-- COBOL VSAM (USRSEC KSDS) → PostgreSQL (user_security table)
--
-- Field Mappings:
--   SEC-USR-ID PIC X(08)    → user_id VARCHAR(8) PRIMARY KEY
--   SEC-USR-FNAME PIC X(20) → first_name VARCHAR(20)
--   SEC-USR-LNAME PIC X(20) → last_name VARCHAR(20)
--   SEC-USR-PWD PIC X(08)   → password_hash VARCHAR(100) [BCrypt]
--   SEC-USR-TYPE PIC X(01)  → user_type VARCHAR(1) ['R'|'A']
--   SEC-USR-FILLER PIC X(23) → [NOT MIGRATED - unused padding]
--
-- Added Fields (Spring Security + Audit):
--   username, enabled, account_non_expired, credentials_non_expired,
--   account_non_locked, created_at, updated_at, last_login_at,
--   failed_login_attempts
--
-- Key Transformations:
--   1. Plaintext password → BCrypt hash (strength 12)
--   2. File-based auth → Spring Security UserDetailsService
--   3. User type codes → Spring Security roles (ROLE_USER, ROLE_ADMIN)
--   4. No audit trail → Comprehensive audit timestamps
--   5. No account locking → Automatic lock after 5 failed attempts
--
-- Performance Characteristics:
--   - Primary key index on user_id (B-tree) matches VSAM key access
--   - Unique index on username for authentication lookups
--   - Composite index on active status for fast enabled user queries
--   - Expected query performance: <10ms for single user lookup
--
-- Security Enhancements:
--   - BCrypt password hashing (strength 12) vs plaintext
--   - Automatic account locking after failed login attempts
--   - JWT token-based authentication vs session-based
--   - Comprehensive audit trail for compliance
--   - Role-based access control via Spring Security
-- ******************************************************************

-- End of migration V5__create_user_security_table.sql
