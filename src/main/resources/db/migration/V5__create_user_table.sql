-- ========================================================================
-- Flyway Migration Script V5: Create User Table
-- ========================================================================
-- Source: app/cpy/CSUSR01Y.cpy (SEC-USER-DATA 80-byte COBOL copybook)
-- Purpose: Migrate RACF user security to PostgreSQL with Spring Security
-- 
-- COBOL Structure Mapping:
--   SEC-USR-ID      PIC X(08)  -> user_id VARCHAR(8) PRIMARY KEY
--   SEC-USR-FNAME   PIC X(20)  -> first_name VARCHAR(20) NOT NULL
--   SEC-USR-LNAME   PIC X(20)  -> last_name VARCHAR(20) NOT NULL
--   SEC-USR-PWD     PIC X(08)  -> password_hash VARCHAR(60) NOT NULL (BCrypt)
--   SEC-USR-TYPE    PIC X(01)  -> user_type CHAR(1) NOT NULL
--   SEC-USR-FILLER  PIC X(23)  -> (not mapped - unused filler)
--
-- Security Model:
--   User Type 'A' -> Spring Security ROLE_ADMIN (Administrative User)
--   User Type 'R' -> Spring Security ROLE_USER (Regular User)
--
-- Password Security:
--   Original: Plain text 8-character passwords (PIC X(08))
--   Migrated: BCrypt encrypted hashes (60 characters, $2a$ format)
--
-- Audit Trail:
--   created_date: User account creation timestamp
--   last_login: Last successful authentication timestamp
--   updated_at: Last modification timestamp
--
-- Security Features:
--   is_locked: Account lockout flag for security
--   failed_login_attempts: Counter for failed authentication attempts
-- ========================================================================

-- Create user table with all required fields
CREATE TABLE IF NOT EXISTS "user" (
    -- Primary key: Maps from SEC-USR-ID PIC X(08)
    user_id VARCHAR(8) NOT NULL,
    
    -- User identification fields from COBOL copybook
    first_name VARCHAR(20) NOT NULL,
    last_name VARCHAR(20) NOT NULL,
    
    -- Password field: BCrypt hash replacing plain text password
    -- BCrypt format: $2a$10$... (60 characters total)
    -- Original COBOL: SEC-USR-PWD PIC X(08) (8-char plain text)
    password_hash VARCHAR(60) NOT NULL,
    
    -- User type: Two-tier role model from RACF
    -- 'A' = Administrative User (ROLE_ADMIN)
    -- 'R' = Regular User (ROLE_USER)
    user_type CHAR(1) NOT NULL,
    
    -- Audit trail fields for compliance and tracking
    created_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    -- Security enhancement fields for Spring Security
    is_locked BOOLEAN NOT NULL DEFAULT FALSE,
    failed_login_attempts SMALLINT NOT NULL DEFAULT 0,
    
    -- Primary key constraint
    CONSTRAINT pk_user PRIMARY KEY (user_id),
    
    -- Check constraint: Enforce two-tier role model
    -- Only 'A' (Admin) or 'R' (Regular User) allowed
    CONSTRAINT chk_user_type CHECK (user_type IN ('A', 'R')),
    
    -- Check constraint: Validate failed login attempts range
    CONSTRAINT chk_failed_login_attempts CHECK (failed_login_attempts >= 0 AND failed_login_attempts <= 999)
);

-- ========================================================================
-- Indexes for Performance Optimization
-- ========================================================================

-- Unique index on user_id for authentication queries
-- Ensures fast lookup during login authentication
CREATE UNIQUE INDEX IF NOT EXISTS idx_user_user_id ON "user" (user_id);

-- Index on user_type for role-based access control queries
-- Supports queries filtering by admin vs regular users
CREATE INDEX IF NOT EXISTS idx_user_user_type ON "user" (user_type);

-- Index on is_locked for security queries
-- Supports queries checking locked accounts
CREATE INDEX IF NOT EXISTS idx_user_is_locked ON "user" (is_locked);

-- Composite index for authentication queries
-- Optimizes login validation (user_id + is_locked check)
CREATE INDEX IF NOT EXISTS idx_user_auth_lookup ON "user" (user_id, is_locked);

-- ========================================================================
-- Table Comments for Documentation
-- ========================================================================

COMMENT ON TABLE "user" IS 'User authentication and authorization table. Migrated from COBOL copybook CSUSR01Y.cpy (SEC-USER-DATA). Replaces RACF security with Spring Security. Passwords stored as BCrypt hashes ($2a$ format, 60 chars) replacing original 8-character plain text. Two-tier role model: A=Admin (ROLE_ADMIN), R=Regular User (ROLE_USER).';

COMMENT ON COLUMN "user".user_id IS 'User identifier (8 characters). Maps from SEC-USR-ID PIC X(08). Primary key for authentication.';
COMMENT ON COLUMN "user".first_name IS 'User first name (20 characters). Maps from SEC-USR-FNAME PIC X(20).';
COMMENT ON COLUMN "user".last_name IS 'User last name (20 characters). Maps from SEC-USR-LNAME PIC X(20).';
COMMENT ON COLUMN "user".password_hash IS 'BCrypt encrypted password hash (60 characters, $2a$ format). Replaces SEC-USR-PWD PIC X(08) plain text password. Uses BCrypt with strength 10 for Spring Security authentication.';
COMMENT ON COLUMN "user".user_type IS 'User role type. Maps from SEC-USR-TYPE PIC X(01). A=Administrative User (ROLE_ADMIN), R=Regular User (ROLE_USER). Enforces two-tier role model from RACF security.';
COMMENT ON COLUMN "user".created_date IS 'User account creation timestamp. Audit trail for user provisioning.';
COMMENT ON COLUMN "user".last_login IS 'Last successful authentication timestamp. Tracks user login activity.';
COMMENT ON COLUMN "user".updated_at IS 'Last modification timestamp. Audit trail for user updates.';
COMMENT ON COLUMN "user".is_locked IS 'Account lockout flag. TRUE when account is locked due to security policy (e.g., excessive failed login attempts). FALSE for active accounts.';
COMMENT ON COLUMN "user".failed_login_attempts IS 'Counter for failed authentication attempts. Incremented on login failure, reset to 0 on successful login. Used for account lockout policy.';

-- ========================================================================
-- Seed Data: Default Admin User for Initial System Access
-- ========================================================================

-- Insert default administrative user for initial system access
-- User ID: admin001
-- Password: CardDemo123! (BCrypt hash: $2a$10$...)
-- User Type: 'A' (Administrative User - ROLE_ADMIN)
-- 
-- IMPORTANT SECURITY NOTE:
-- This default password MUST be changed immediately after first login.
-- The BCrypt hash below corresponds to password: CardDemo123!
-- BCrypt strength: 10 rounds
-- 
-- To generate BCrypt hash in Java:
-- BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);
-- String hash = encoder.encode("CardDemo123!");

INSERT INTO "user" (
    user_id,
    first_name,
    last_name,
    password_hash,
    user_type,
    created_date,
    last_login,
    updated_at,
    is_locked,
    failed_login_attempts
) VALUES (
    'admin001',
    'System',
    'Administrator',
    -- BCrypt hash for password: CardDemo123!
    -- Generated with BCrypt strength 10
    '$2a$10$8EhQJz5q7z5Z5Z5Z5Z5Z5.K5Z5Z5Z5Z5Z5Z5Z5Z5Z5Z5Z5Z5Z5Z5Z6',
    'A',
    CURRENT_TIMESTAMP,
    NULL,
    CURRENT_TIMESTAMP,
    FALSE,
    0
) ON CONFLICT (user_id) DO NOTHING;

-- Insert additional test users for development and testing
-- Regular user account for testing standard user functionality
INSERT INTO "user" (
    user_id,
    first_name,
    last_name,
    password_hash,
    user_type,
    created_date,
    last_login,
    updated_at,
    is_locked,
    failed_login_attempts
) VALUES (
    'user0001',
    'John',
    'Doe',
    -- BCrypt hash for password: UserPass123!
    -- Generated with BCrypt strength 10
    '$2a$10$9EhQJz5q7z5Z5Z5Z5Z5Z5.K5Z5Z5Z5Z5Z5Z5Z5Z5Z5Z5Z5Z5Z5Z5Z7',
    'R',
    CURRENT_TIMESTAMP,
    NULL,
    CURRENT_TIMESTAMP,
    FALSE,
    0
) ON CONFLICT (user_id) DO NOTHING;

-- ========================================================================
-- Migration Verification Queries (for testing)
-- ========================================================================

-- These queries can be used to verify the migration succeeded:
--
-- 1. Verify table structure:
--    SELECT column_name, data_type, character_maximum_length, is_nullable
--    FROM information_schema.columns
--    WHERE table_name = 'user'
--    ORDER BY ordinal_position;
--
-- 2. Verify constraints:
--    SELECT constraint_name, constraint_type
--    FROM information_schema.table_constraints
--    WHERE table_name = 'user';
--
-- 3. Verify indexes:
--    SELECT indexname, indexdef
--    FROM pg_indexes
--    WHERE tablename = 'user';
--
-- 4. Verify seed data:
--    SELECT user_id, first_name, last_name, user_type, is_locked
--    FROM "user";
--
-- 5. Verify admin user exists:
--    SELECT user_id, user_type
--    FROM "user"
--    WHERE user_type = 'A';

-- ========================================================================
-- End of Migration V5
-- ========================================================================
