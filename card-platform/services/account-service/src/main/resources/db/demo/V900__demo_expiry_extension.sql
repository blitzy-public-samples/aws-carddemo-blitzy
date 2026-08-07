-- Account service, DEMO-ONLY migration. This file lives in db/demo and NOT in
-- db/migration, so it is applied only when spring.flyway.locations names both
-- locations. card-platform/docker-compose.yml and card-platform/deploy/k8s/30-configmap.yaml
-- name both; nothing else does.
--
-- WHY THIS FILE EXISTS
-- The authorization service holds a replica of this record, and its own
-- db/demo/V900__demo_expiry_extension.sql extends every replicated expiry to 2099-12-31 so
-- that a live demo carrying today's date as its origin timestamp is not declined by reason
-- code 103 at app/cbl/CBTRN02C.cbl:L414-L420. Until this file existed the overlay reached
-- the replica alone, and the record it replicates kept its 2025 expiry.
--
-- That asymmetry became reachable the moment this service began publishing a state change
-- for every posted transaction. AccountStateChanged carries the expiry, and the
-- authorization listener applies it, so the first posting on an account wrote a 2025 expiry
-- over the extended one and every later authorization on that account declined 0103. A
-- PUT /accounts/{id} that echoed the seeded expiry did the same thing before that, which is
-- how the asymmetry was found.
--
-- One record cannot carry a demo overlay in one copy and the fixture value in another.
-- Both locations are therefore named together or neither is.
--
-- WHY IT IS SEPARATE
-- V2__seed.sql reproduces app/data/ASCII/acctdata.txt exactly, value for value, and the
-- equivalence suite compares it against that fixture. Editing an expiry there would move
-- the oracle the suite measures against, which is the one thing this migration must not do.
-- A run that names only db/migration gets the fixture; a run that names both gets the
-- fixture plus this extension.
--
-- WHY 2099-12-31
-- The same value the authorization overlay writes, for the same reasons: it is
-- deterministic, needs no maintenance, and reads as synthetic, so a reader cannot mistake it
-- for a fixture value. The column holds ten characters of text and the rule compares it
-- lexically, so a four-digit year sorts correctly against any timestamp a caller sends.
--
-- WHAT IT DOES NOT TOUCH
-- The balance, the credit limit, the cash credit limit, both cycle accumulators, the open
-- and reissue dates, the postal code, the group identifier and the active status are all
-- left exactly as the seed loaded them.

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
