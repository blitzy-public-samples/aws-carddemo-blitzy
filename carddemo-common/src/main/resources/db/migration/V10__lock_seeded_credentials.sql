-- =============================================================================
-- V10__lock_seeded_credentials.sql
--
-- Removes the shared default credential from the ten seeded USRSEC users.
--
-- WHY: V3 seeds ADMIN001-ADMIN005 and USER0001-USER0005 from the legacy USRSEC
-- list (app/jcl/DUSRSECJ.jcl), and every one of them carried a BCrypt hash of the
-- single string 'PASSWORD' - the legacy fixture credential, which is also written
-- down in README-target.md. On a freshly built stack `ADMIN001 / PASSWORD`
-- therefore authenticated and was granted ROLE_ADMIN. A known, shared, documented
-- administrator credential that is active by default is not something a delivery
-- candidate may ship (CWE-1392, CWE-798): the migration set is applied
-- automatically on first boot, so every deployment of this repository - not only
-- the demo laptop - came up with the same administrator password.
--
-- WHAT THIS DOES: every seeded row keeps its identity, name and role (the legacy
-- USRSEC list is reference data the online user screens read and page through, so
-- deleting the rows would remove behaviour) but its stored credential becomes the
-- LOCKED sentinel below. The sentinel is deliberately NOT a syntactically valid
-- BCrypt hash, so `BCryptPasswordEncoder.matches()` short-circuits to false for
-- every input rather than comparing anything: there is no password, not even an
-- unknown one, that signs these accounts on.
--
-- HOW A USABLE CREDENTIAL IS OBTAINED: auth-service's SeedCredentialProvisioner
-- replaces the sentinel at start-up when, and only when,
-- `carddemo.security.seed-credentials.enabled=true` AND a password is injected
-- through CARDDEMO_SEED_USER_PASSWORD. Both are off/absent by default; the local
-- docker-compose environment sets them from the gitignored .env, which is what
-- keeps the demo usable locally without shipping a credential in the repository.
-- Enabling the flag without supplying a password fails start-up rather than
-- leaving the accounts silently locked.
--
-- IDEMPOTENT AND NON-DESTRUCTIVE: the UPDATE is restricted to rows that still
-- carry the V3 hash, so a deployment whose credentials have already been
-- provisioned or rotated is untouched, and re-running the migration set (Flyway
-- validates checksums, it does not re-apply) cannot revoke a live credential.
--
-- Source references: app/cpy/CSUSR01Y.cpy (SEC-USER-DATA, SEC-USR-PWD PIC X(08)),
-- app/cbl/COSGN00C.cbl:L223 (the plaintext compare this replaced), AAP 0.6.7.
-- =============================================================================

UPDATE security_users
   SET sec_usr_pwd = '{bcrypt}$2a$10$locked-seeded-credential-not-provisioned!'
 WHERE sec_usr_pwd = '{bcrypt}$2a$10$ucIRth.iIafhA4MgE1RXZ.0whYamgfRIpJebWmPswpnxmLKA/peYm';

COMMENT ON COLUMN security_users.sec_usr_pwd IS
    'Encoded credential with a {id} algorithm prefix (DelegatingPasswordEncoder). '
    'The sentinel {bcrypt}$2a$10$locked-seeded-credential-not-provisioned! marks a '
    'seeded account with NO usable credential: it is not a valid BCrypt hash, so '
    'every match attempt fails. auth-service SeedCredentialProvisioner replaces it '
    'only when carddemo.security.seed-credentials.enabled=true and '
    'CARDDEMO_SEED_USER_PASSWORD is supplied.';
