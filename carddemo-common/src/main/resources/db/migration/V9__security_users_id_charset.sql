-- =============================================================================
-- V9__security_users_id_charset.sql
--
-- Restricts security_users.sec_usr_id to the character set the legacy key could
-- actually hold: upper-case letters and digits, one to eight of them.
--
-- WHY: SEC-USR-ID is PIC X(08) on a VSAM KSDS keyed by an EBCDIC 3270 terminal,
-- whose UCTRAN attribute folded input to upper case before COSGN00C ever saw it
-- (app/cbl/COSGN00C.cbl:L132). A user id outside A-Z / 0-9 was therefore NOT
-- REPRESENTABLE on the mainframe. The migrated table carried no such restriction,
-- so an administrator could create the id U+0410 CYRILLIC CAPITAL A + 'DMIN001',
-- which stores as d090444d494e303031, has length 8, passes chk_sec_usr_type, signs
-- on, and is granted ROLE_ADMIN. Rendered in any log line, Grafana panel or report
-- it is indistinguishable from the legitimate ADMIN001 (CWE-1007), so one
-- administrator compromise became a durable, review-resistant backdoor and the
-- audit trail lost non-repudiation: every one of the audit records keys on the
-- user id alone.
--
-- The application enforces the same rule twice more, on purpose: the add-user
-- request DTO refuses the value with HTTP 400 before it reaches the column, and
-- UserIdNormalizer applies Unicode NFKC canonicalization plus upper-casing so the
-- stored form is always the canonical one. This constraint is the backstop for any
-- writer that does not pass through those layers - a psql session, a data load, or
-- a future service.
--
-- UPPER CASE is required rather than merely allowed: UserIdNormalizer upper-cases
-- every id before it is persisted or looked up, so a lower-case row could only be
-- reached by a writer that bypassed the application, and such a row could never be
-- signed on to. The constraint therefore also pins the invariant that made
-- UserIdNormalizer necessary.
--
-- Source references: app/cpy/CSUSR01Y.cpy (SEC-USR-ID PIC X(08)),
-- app/cbl/COSGN00C.cbl:L132 (FUNCTION UPPER-CASE on the entered id),
-- app/cbl/COUSR01C.cbl (the add-user screen this guards).
-- =============================================================================

ALTER TABLE security_users
    DROP CONSTRAINT IF EXISTS chk_sec_usr_id_charset;

ALTER TABLE security_users
    ADD CONSTRAINT chk_sec_usr_id_charset CHECK (sec_usr_id ~ '^[A-Z0-9]{1,8}$');
