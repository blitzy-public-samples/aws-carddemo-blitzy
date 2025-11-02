-- ================================================================
-- User Security Data Load Script
-- ================================================================
-- Source: AWS.M2.CARDDEMO.USRSEC.PS (EBCDIC CP037)
-- Copybook: CSUSR01Y.cpy (80-byte fixed-width records)
-- Total Records: 10
-- SECURITY CRITICAL: All passwords BCrypt hashed (strength 12)
--
-- Field Mapping:
--   SEC-USR-ID (PIC X(08))    → user_id VARCHAR(8) PRIMARY KEY
--   SEC-USR-FNAME (PIC X(20)) → first_name VARCHAR(20)
--   SEC-USR-LNAME (PIC X(20)) → last_name VARCHAR(20)
--   SEC-USR-PWD (PIC X(08))   → password_hash VARCHAR(100) [BCrypt]
--   SEC-USR-TYPE (PIC X(01))  → user_type VARCHAR(1)
--
-- User Type Mapping to Spring Security Roles:
--   'A' → ROLE_ADMIN (Administrative User)
--   'U' → ROLE_USER  (Regular User)
--   'R' → ROLE_USER  (Regular User - alternate code)
--
-- Spring Security UserDetailsService Fields:
--   enabled              → true  (account enabled)
--   account_non_expired  → true  (account not expired)
--   credentials_non_expired → true (credentials not expired)
--   account_non_locked   → true  (account not locked)
-- ================================================================

-- Clear existing user security data
TRUNCATE TABLE user_security CASCADE;

-- Insert user security records with BCrypt hashed passwords
INSERT INTO user_security (
  user_id,
  username,
  first_name,
  last_name,
  password_hash,
  user_type,
  enabled,
  account_non_expired,
  credentials_non_expired,
  account_non_locked
) VALUES
  ('ADMIN001', 'ADMIN001', 'MARGARET', 'GOLD', '$2b$12$JG0IP.BMkp7cT0JTbSmU8eokf.m7/bwKwdluuKXBgNNFJM1q0wkoO', 'A', true, true, true, true),  -- ROLE_ADMIN
  ('ADMIN002', 'ADMIN002', 'RUSSELL', 'RUSSELL', '$2b$12$pR9FIx4OZprbrDLN9xICS.ALXdfXkweHXiAuXhpU8sSPAwivwXaV.', 'A', true, true, true, true),  -- ROLE_ADMIN
  ('ADMIN003', 'ADMIN003', 'RAYMOND', 'WHITMORE', '$2b$12$T436TP67b8kck9ZJNTwcWuL6i8NwLfrc3NRIc0VMhHgncWZf5qr3y', 'A', true, true, true, true),  -- ROLE_ADMIN
  ('ADMIN004', 'ADMIN004', 'EMMANUEL', 'CASGRAIN', '$2b$12$oyqmUIA65wjdfc2pJFOPp.zfgrtygcIsVMZGHdiOnIW6SEmTBQncO', 'A', true, true, true, true),  -- ROLE_ADMIN
  ('ADMIN005', 'ADMIN005', 'GRANVILLE', 'LACHAPELLE', '$2b$12$mu7D1nnpyzZDhn.6P3o23uAB9rZa7KrAhGC4y6eFpYADK9lQ/2yWi', 'A', true, true, true, true),  -- ROLE_ADMIN
  ('USER0001', 'USER0001', 'LAWRENCE', 'THOMAS', '$2b$12$AQUSiMfWupq5LWgfoOK1wuKbe83xvsAHARBd4vlaPGa9JyiliVTt.', 'U', true, true, true, true),  -- ROLE_USER
  ('USER0002', 'USER0002', 'AJITH', 'KUMAR', '$2b$12$Vv6l6bl7Za5mS3S/.6360.1hGub04VljWuHj8pGiIBnHXIrYuFj7e', 'U', true, true, true, true),  -- ROLE_USER
  ('USER0003', 'USER0003', 'LAURITZ', 'ALME', '$2b$12$Q0qFS8WHdwJVMjFmpxTyk.3hLXDeU5omZOvA.awKvPtVSoWyOIive', 'U', true, true, true, true),  -- ROLE_USER
  ('USER0004', 'USER0004', 'AVERARDO', 'MAZZI', '$2b$12$gy9YrtSXsO49d2UaoL4z2.wUD2NTlOYBK8lmHMH/NOrDoL1FKLCPq', 'U', true, true, true, true),  -- ROLE_USER
  ('USER0005', 'USER0005', 'LEE', 'TING', '$2b$12$a2FUliCGT4DoFo9eFUpao.nhqvLwLRn2T0UtQ.zdUxaE9Gr7z20AG', 'U', true, true, true, true);  -- ROLE_USER

-- ================================================================
-- Validation Queries
-- ================================================================

-- Verify record count (Expected: 10)
SELECT COUNT(*) AS total_users FROM user_security;

-- Verify BCrypt hash format and length (Expected: ~60 characters)
SELECT 
  user_id,
  user_type,
  LENGTH(password_hash) AS hash_length,
  SUBSTRING(password_hash, 1, 4) AS hash_prefix
FROM user_security
ORDER BY user_id;

-- Verify user type distribution
SELECT 
  user_type,
  COUNT(*) AS count,
  CASE 
    WHEN user_type = 'A' THEN 'ROLE_ADMIN'
    WHEN user_type IN ('U', 'R') THEN 'ROLE_USER'
    ELSE 'UNKNOWN'
  END AS spring_security_role
FROM user_security
GROUP BY user_type
ORDER BY user_type;

-- Verify all accounts are enabled
SELECT 
  enabled,
  account_non_expired,
  credentials_non_expired,
  account_non_locked,
  COUNT(*) AS count
FROM user_security
GROUP BY enabled, account_non_expired, credentials_non_expired, account_non_locked;

-- List all users with their roles
SELECT 
  user_id,
  username,
  first_name || ' ' || last_name AS full_name,
  CASE 
    WHEN user_type = 'A' THEN 'ROLE_ADMIN'
    WHEN user_type IN ('U', 'R') THEN 'ROLE_USER'
    ELSE 'UNKNOWN'
  END AS role,
  enabled
FROM user_security
ORDER BY user_type DESC, user_id;
