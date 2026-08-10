-- Authorization service, DEMO-ONLY migration. Extends every replicated account expiry to 2099-12-31.
--
-- Statements: one UPDATE over account_credit_snapshot.
--
-- Where it applies. This file lives in db/demo and NOT in db/migration, so it runs only where
-- spring.flyway.locations names both locations. Two paths name both, and both are demo profiles:
-- card-platform/docker-compose.yml through AUTHORIZATION_FLYWAY_LOCATIONS, and
-- card-platform/deploy/k8s/30-configmap.yaml through the key of the same name, which
-- 40-authorization-service.yaml reads. Setting either to classpath:db/migration is the base-profile
-- opt-out, and it is set together with the account key or not at all. This service's own
-- application.yml defaults the property to classpath:db/migration alone, so mvn verify measures the
-- fixture rather than the overlay.
--
-- What the rule reads. Reason code 103 approves only while the account expiry is greater than or equal
-- to the first ten characters of the origin timestamp, at app/cbl/CBTRN02C.cbl:L414-L420, comparing raw
-- text. All fifty accounts of app/data/ASCII/acctdata.txt carry a 2025 expiry, the latest being
-- 2025-12-28, and all three hundred transactions of app/data/ASCII/dailytran.txt carry origin date
-- 2022-06-10, so the fixture passes the rule and a caller sending today's date does not.
--
-- No line of this file may carry a dollar sign followed by a brace. Flyway substitutes placeholders
-- across the whole text of a migration, comments included, and refuses to start on an unknown one.
--
-- Rationale, alternatives considered and accepted risks, including why the overlay is a separate
-- location and why the value is 2099-12-31: card-platform/docs/decision-log.md.

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
