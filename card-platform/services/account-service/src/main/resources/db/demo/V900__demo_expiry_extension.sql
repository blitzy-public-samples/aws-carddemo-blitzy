-- Account service, DEMO-ONLY migration. Extends every seeded account expiry to 2099-12-31, the value
-- the authorization overlay writes to its replica.
--
-- Statements: one UPDATE over account. It touches the expiry alone: the balance, the credit limit, the
-- cash credit limit, both cycle accumulators, the open and reissue dates, the postal code, the group
-- identifier and the active status are left as the seed loaded them.
--
-- Where it applies. This file lives in db/demo and NOT in db/migration, so it runs only where
-- spring.flyway.locations names both locations: card-platform/docker-compose.yml and
-- card-platform/deploy/k8s/30-configmap.yaml name both, and nothing else does. Both locations are
-- named together with the authorization overlay or neither is, because AccountStateChanged carries the
-- expiry and the authorization listener applies it, so a posting on an account would otherwise write
-- this record's 2025 expiry over the extended replica and every later authorization on that account
-- would decline 0103 at app/cbl/CBTRN02C.cbl:L414-L420.
--
-- Rationale, alternatives considered and accepted risks, including why the overlay is a separate
-- location and why the value is 2099-12-31: card-platform/docs/decision-log.md.

UPDATE account
   SET expiration_date = '2099-12-31';

-- Assertion. The demo is worth nothing if a row was missed, and a silent partial update
-- would show as an unexplained decline during the demo itself.
DO $$
DECLARE
    unextended INTEGER;
BEGIN
    SELECT count(*) INTO unextended
      FROM account
     WHERE expiration_date <> '2099-12-31';

    IF unextended <> 0 THEN
        RAISE EXCEPTION
            'demo expiry extension left % of the account rows in the past',
            unextended;
    END IF;
END
$$;
