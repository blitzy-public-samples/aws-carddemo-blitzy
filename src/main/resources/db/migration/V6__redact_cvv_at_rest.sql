-- =============================================================================
-- V6__redact_cvv_at_rest.sql  --  CVV-at-rest redaction (PCI-DSS; D22-revised)
-- =============================================================================
--
-- PURPOSE
--   Formalize, at the schema level, that the card verification value (CVV) is
--   NEVER stored at rest as a real value. The card.cvv column is RETAINED so the
--   150-byte CARD-RECORD layout shape (legacy/cpy/CVACT02Y.cpy CARD-CVV-CD
--   PIC 9(03)) and its copybook-to-column traceability are preserved, but it now
--   holds ONLY a fixed, non-reversible masked placeholder ('***'). Any real value
--   that a prior seed/migration may have written is scrubbed here.
--
-- WHY (Refine-PR #4 / decision-log D22-revised)
--   PCI-DSS Requirement 3.2 prohibits retaining the sensitive card-verification
--   value (CVV/CVV2/CVC2/CID) after authorization -- even when the demonstration
--   values are synthetic. Behavioral parity requires NOTHING of the CVV in this
--   migration: it is never mapped to any request/response DTO, never read by any
--   service, controller or batch job, is excluded from Card.toString(), and no
--   test asserts a CVV persistence round-trip. The real CARD-CVV-CD is therefore
--   unnecessary at rest, so it is represented as a non-reversible placeholder
--   instead of the original value. This is a CORRECTION to existing scoped work
--   (the original D22 hardened CVV handling in transit/logs but still modeled the
--   real value at rest); it is NOT a new feature and adds no capability.
--
-- DEFENSE IN DEPTH (three consistent layers)
--   1. Repository hygiene -- src/main/resources/db/seed/card.csv ships '***' for
--      every row's cvv column (no synthetic CVVs live in the repo).
--   2. Ingestion boundary -- LocalSeedDataLoader.redactAtRest() forces card.cvv to
--      the '***' placeholder on every insert, regardless of CSV content, so the
--      seed path can never persist a real CVV even if the CSV regresses.
--   3. Schema level (THIS migration) -- scrub any non-placeholder value already in
--      the table and re-document the column so the authoritative PCI posture lives
--      with the schema.
--   The deployed database's own controls (encryption at rest, access) for the
--   production data cutover remain owned by the H-1 / H-5 path-to-production tasks.
--
-- NOTE ON THE ABSENCE OF A CHECK CONSTRAINT
--   A CHECK (cvv IS NULL OR cvv = '***') would hard-enforce the invariant but would
--   also reject the synthetic CVV that the repository integration tests persist
--   directly via the JPA entity (CardRepositoryTest builds cards with a literal
--   value to exercise unrelated browse/round-trip paths and never asserts the CVV).
--   The invariant is instead enforced on the only production ingestion path (the
--   seed loader) and documented here; the ephemeral Testcontainers test database is
--   not a data-at-rest surface. This trade-off is recorded in docs/decision-log.md
--   (D22-revised) so the choice is explicit rather than implicit.
--
-- WHY A NEW MIGRATION (NOT AN EDIT TO V1)
--   V1__schema.sql is an already-applied, immutable Flyway migration (Flyway runs
--   with validate-on-migrate=true; editing a recorded checksum fails validation on
--   every existing database). Schema evolution is delivered as a new, higher-
--   versioned migration -- the same pattern as V3/V4/V5.
--
-- EXECUTION MODEL
--   * OWNED BY FLYWAY. Runs automatically at startup and against a fresh
--     Testcontainers PostgreSQL 16 during integration tests. Runs BEFORE the
--     @Profile("local") seed loader, so on a fresh database the UPDATE matches zero
--     rows and the loader then inserts the '***' placeholder; on a database that
--     already holds seeded rows the loader skips the non-empty table and this
--     UPDATE performs the scrub.
--   * Hibernate NEVER generates DDL (ddl-auto=validate); the retained VARCHAR(3)
--     card.cvv column keeps the com.aws.carddemo.domain.Card.cvv mapping valid.
--
-- IDEMPOTENCE / SAFETY
--   The UPDATE is naturally idempotent (re-running matches zero rows once every
--   value is the placeholder) and changes no business field of any copybook; the
--   COMMENT is declarative and safe to re-apply.
-- =============================================================================

-- 1) Scrub any real card verification value already persisted, replacing it with
--    the fixed non-reversible masked placeholder. Idempotent: matches only rows
--    that still hold a non-placeholder value.
UPDATE card
   SET cvv = '***'
 WHERE cvv IS NOT NULL
   AND cvv <> '***';

-- 2) Re-document the column so the PCI-DSS at-rest posture is authoritative in the
--    schema (supersedes the original V1 "SENSITIVE ... never log" comment).
COMMENT ON COLUMN card.cvv IS
    'CARD-CVV-CD (PIC 9(03), CVACT02Y.cpy). PCI-DSS (decision-log D22-revised): the real card '
    'verification value is NEVER stored at rest. Column retained for CARD-RECORD layout-shape '
    'traceability but holds only a fixed non-reversible masked placeholder (''***''); it is never '
    'read, logged, mapped to a DTO, or returned. Real CVV is redacted at the ingestion boundary '
    '(LocalSeedDataLoader) and scrubbed here.';
