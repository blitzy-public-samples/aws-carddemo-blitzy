-- Authorization service, DEMO-ONLY migration. This file lives in db/demo and NOT in
-- db/migration, so it is applied only when spring.flyway.locations names both
-- locations. Two deployment paths name both, and both are demo profiles:
-- card-platform/docker-compose.yml through AUTHORIZATION_FLYWAY_LOCATIONS, and
-- card-platform/deploy/k8s/30-configmap.yaml through the key of the same name, which
-- 40-authorization-service.yaml reads. Setting that key to classpath:db/migration on either
-- path is the base-profile opt-out, and it is set together with the account key or not at
-- all. Nothing else names both: this service's own application.yml defaults the property to
-- classpath:db/migration alone, so mvn verify measures the fixture rather than the overlay.
--
-- No line of this file may carry a dollar sign followed by a brace. Flyway substitutes
-- placeholders across the whole text of a migration, comments included, and answers an
-- unknown one by refusing to start. An earlier revision of the sentence above quoted the
-- property with its default in that form and stopped every service that applies this overlay.
--
-- WHY THIS FILE EXISTS
-- Reason code 103 approves only while the account expiry is greater than or equal to
-- the first ten characters of the origin timestamp, at
-- app/cbl/CBTRN02C.cbl:L414-L420. The comparison is on raw text, and the origin
-- timestamp arrives on the request.
-- Every one of the 50 accounts in app/data/ASCII/acctdata.txt carries an expiry in
-- 2025, the latest being 2025-12-28. Every one of the 300 transactions in
-- app/data/ASCII/dailytran.txt carries the origin date 2022-06-10. Against the fixture
-- the rule therefore passes, and the equivalence suite exercises it correctly.
--
-- A live demo is different. A caller sending the current date as the origin timestamp
-- sends a value later than every seeded expiry. Reason 103 then declines every account,
-- and no TransactionAuthorized event is ever published. The fan-out has
-- nothing to demonstrate.
--
-- WHY IT IS SEPARATE
-- V2__seed.sql reproduces the fixture exactly, value for value, and the equivalence
-- suite compares it against app/data/ASCII/acctdata.txt. Editing an expiry there would
-- change the oracle the suite measures against, which is the one thing this migration
-- must not do. Keeping the change in its own location leaves V1 and V2 untouched. A run
-- that names only db/migration gets the fixture, and a run that names both gets the
-- fixture plus this demo extension.
--
-- WHY 2099-12-31
-- The value is deterministic, needs no maintenance, and reads as synthetic. A
-- reader cannot mistake it for a fixture value, which is the point. The column holds
-- ten characters of text and compares lexically, so a four-digit year sorts correctly
-- against any timestamp a caller sends.
--

UPDATE account_credit_snapshot
   SET account_expiration_date = '2099-12-31';

-- Assertion. The demo is worth nothing if a row was missed, and a silent partial update
-- would show as an unexplained decline during the demo itself.
DO $$
DECLARE
    unextended INTEGER;
BEGIN
    SELECT count(*) INTO unextended
      FROM account_credit_snapshot
     WHERE account_expiration_date <> '2099-12-31';

    IF unextended <> 0 THEN
        RAISE EXCEPTION
            'demo expiry extension left % of the account_credit_snapshot rows in the past',
            unextended;
    END IF;
END
$$;
