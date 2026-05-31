-- =============================================================================
-- V4__seed_users.sql
-- =============================================================================
-- Purpose:      Seed the users table with 10 default user accounts. All initial
--              passwords are BCrypt-hashed (cost factor 10) — never stored or
--              transmitted in plaintext.
-- Source:      app/jcl/DUSRSECJ.jcl (REFERENCE) — defines 10 default users
--              app/cpy/CSUSR01Y.cpy (REFERENCE) — defines user record layout
-- Version:     CardDemo_v1.0-15-g27d6c6f-68 (Date: 2022-07-19)
-- Tech Spec:   §0.4.1.8, §6.4 (Security Architecture)
-- AAP Rules:   PR-17 (BCrypt for all passwords - MANDATORY),
--              PR-19 (role mapping: A→ROLE_ADMIN, U→ROLE_USER preserved verbatim
--              in sec_usr_type column; mapping to GrantedAuthority happens in
--              CustomAuthorityMapper at runtime)
-- =============================================================================
-- SECURITY CRITICAL: Each user's sec_usr_pwd column receives a DISTINCT 60-character
-- BCrypt hash of the literal password "PASSWORD". Distinct hashes are mandatory
-- because BCrypt embeds a unique 22-character random salt in every hash.
-- Authentication at runtime invokes BCryptPasswordEncoder.matches(rawPassword, hash)
-- which extracts the salt from the stored hash and re-hashes the input for comparison.
-- =============================================================================
-- HASH PROVENANCE: The 10 hashes below were pre-computed offline with Spring
-- Security's org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder at
-- strength 10 (the BCryptPasswordEncoder default) — the SAME encoder class used
-- at runtime by DaoAuthenticationProvider. Each was verified to be 60 chars, to
-- begin with "$2a$10$", to be distinct from all others, and to satisfy
-- encoder.matches("PASSWORD", hash) == true. Hashes are static literals because
-- Flyway runs against bare PostgreSQL with no pgcrypto extension; SQL-side
-- pgcrypto-based hash generation is therefore intentionally NOT used.
--
-- Data fidelity (PR-13): column lengths mirror CSUSR01Y.cpy PIC clauses —
--   sec_usr_id    SEC-USR-ID    PIC X(08) -> VARCHAR(8)
--   sec_usr_fname SEC-USR-FNAME PIC X(20) -> VARCHAR(20)
--   sec_usr_lname SEC-USR-LNAME PIC X(20) -> VARCHAR(20)
--   sec_usr_pwd   SEC-USR-PWD   PIC X(08) -> VARCHAR(60)  (widened for BCrypt hash)
--   sec_usr_type  SEC-USR-TYPE  PIC X(01) -> CHAR(1)
-- Names are taken verbatim (uppercase) from the DUSRSECJ.jcl in-stream REPRO
-- block with COBOL fixed-width trailing spaces stripped.
--
-- Idempotency: every INSERT uses ON CONFLICT (sec_usr_id) DO NOTHING so the
-- script is safe to re-run in test/reset scenarios. Audit columns
-- (created_date, last_modified_date, created_by, last_modified_by) are omitted
-- so V1's DEFAULT CURRENT_TIMESTAMP / NULL defaults apply.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- Administrator accounts (sec_usr_type = 'A' -> ROLE_ADMIN via CustomAuthorityMapper)
-- -----------------------------------------------------------------------------
INSERT INTO users (sec_usr_id, sec_usr_fname, sec_usr_lname, sec_usr_pwd, sec_usr_type)
VALUES ('ADMIN001', 'MARGARET', 'GOLD', '$2a$10$SyKwoKRTVKeoaWRTcCCBzOrdGyJdU3invj6JslsBurd60DDUf0LZC', 'A')
ON CONFLICT (sec_usr_id) DO NOTHING;

INSERT INTO users (sec_usr_id, sec_usr_fname, sec_usr_lname, sec_usr_pwd, sec_usr_type)
VALUES ('ADMIN002', 'RUSSELL', 'RUSSELL', '$2a$10$9tNtsuHBMi8GAtJeUcfOVu1WzDfzq/BReimmfgdyiC7T0ULm9MjRm', 'A')
ON CONFLICT (sec_usr_id) DO NOTHING;

INSERT INTO users (sec_usr_id, sec_usr_fname, sec_usr_lname, sec_usr_pwd, sec_usr_type)
VALUES ('ADMIN003', 'RAYMOND', 'WHITMORE', '$2a$10$F3Xh14EoJs81mJhzYKo/eOSklycONVE.73M/lHtvc5w.uDddvmN2a', 'A')
ON CONFLICT (sec_usr_id) DO NOTHING;

INSERT INTO users (sec_usr_id, sec_usr_fname, sec_usr_lname, sec_usr_pwd, sec_usr_type)
VALUES ('ADMIN004', 'EMMANUEL', 'CASGRAIN', '$2a$10$JjX9MGw9LkkCd7eWElITf.XfMAKkhiP3v6sMpgiU6XG7WlorsUOoC', 'A')
ON CONFLICT (sec_usr_id) DO NOTHING;

INSERT INTO users (sec_usr_id, sec_usr_fname, sec_usr_lname, sec_usr_pwd, sec_usr_type)
VALUES ('ADMIN005', 'GRANVILLE', 'LACHAPELLE', '$2a$10$L3lX2V4gDXhsz1D2OY2OAOoeyHOIGL.hX1Dgwd.x03hySMpC0k9ti', 'A')
ON CONFLICT (sec_usr_id) DO NOTHING;


-- -----------------------------------------------------------------------------
-- Regular user accounts (sec_usr_type = 'U' -> ROLE_USER via CustomAuthorityMapper)
-- -----------------------------------------------------------------------------
INSERT INTO users (sec_usr_id, sec_usr_fname, sec_usr_lname, sec_usr_pwd, sec_usr_type)
VALUES ('USER0001', 'LAWRENCE', 'THOMAS', '$2a$10$Q9NhHSk7e39JLcHe6iG0TO8sVgQNCSwatZwwekSuf3tEI0Ysk98Tm', 'U')
ON CONFLICT (sec_usr_id) DO NOTHING;

INSERT INTO users (sec_usr_id, sec_usr_fname, sec_usr_lname, sec_usr_pwd, sec_usr_type)
VALUES ('USER0002', 'AJITH', 'KUMAR', '$2a$10$Hv1orif2nSH6G341pCHWWe.z7eM62JUFO3VLNt0pQglMhILiLi.O6', 'U')
ON CONFLICT (sec_usr_id) DO NOTHING;

INSERT INTO users (sec_usr_id, sec_usr_fname, sec_usr_lname, sec_usr_pwd, sec_usr_type)
VALUES ('USER0003', 'LAURITZ', 'ALME', '$2a$10$eWYqqUN3B.pkK/Qj/rLC5u4LfcWJIRv2hYZcvYx/7joKLuisFC/8.', 'U')
ON CONFLICT (sec_usr_id) DO NOTHING;

INSERT INTO users (sec_usr_id, sec_usr_fname, sec_usr_lname, sec_usr_pwd, sec_usr_type)
VALUES ('USER0004', 'AVERARDO', 'MAZZI', '$2a$10$fu3OSftHz6Kdl04fY4FeBuUehmCSmJfdmZQrl6iNVx/Z0prDDxMVG', 'U')
ON CONFLICT (sec_usr_id) DO NOTHING;

INSERT INTO users (sec_usr_id, sec_usr_fname, sec_usr_lname, sec_usr_pwd, sec_usr_type)
VALUES ('USER0005', 'LEE', 'TING', '$2a$10$hFSDXe1rDGoMO3fLa9QHG.JxqzHvj0/Ev.OP57mOhv1EcD7rnoXpK', 'U')
ON CONFLICT (sec_usr_id) DO NOTHING;
