-- Authorization service, migration V13. Makes authorization_decision.event_id nullable, bounded by a
-- check constraint to the one outcome that can carry no event row.
--
-- Statements: two ALTER TABLE and one COMMENT ON COLUMN.
--
-- The mechanics. event_id was NOT NULL and described as the outbox row the decision published through.
-- The column becomes nullable and ck_authorization_decision_event ties the absence to exactly one
-- shape: a decision carrying no account identifier is the only decision that may carry no event, and
-- every other decision must carry one. Reject code 0100 at app/cbl/CBTRN02C.cbl:L385-L387 fires inside
-- the INVALID KEY branch of the cross-reference read, and the short-circuit at
-- app/cbl/CBTRN02C.cbl:L376-L378 stops the account read, so no account identifier exists at that
-- moment. The outcome is still a decision: reason 0100 and its verbatim text answer the caller,
-- unresolved_card_attempt keeps the attempt and this table keeps the decision.
--
-- SUPERSEDED IN PART. V15__unresolved_decline_is_published.sql records the current position: that
-- outcome now publishes a governed transaction-declined-v2 event keyed on its transaction identifier,
-- so a decision row for it carries an event identifier again. The nullable column and its constraint
-- stay, because the constraint still bounds which shape may omit one.
--
-- Design decisions: card-platform/docs/decision-log.md.

ALTER TABLE authorization_decision
    ALTER COLUMN event_id DROP NOT NULL;

-- What this constraint admitted while it stood: a decision that resolved an account named the one
-- event it published, a decision that resolved none named nothing, and no third combination reached
-- the table, so a missing event_id could not mean "we forgot".
-- V15__unresolved_decline_is_published.sql replaces it with event_id IS NOT NULL, because that second
-- outcome now publishes an event too.
ALTER TABLE authorization_decision
    ADD CONSTRAINT ck_authorization_decision_event CHECK (
        (account_id IS NOT NULL AND event_id IS NOT NULL)
        OR (account_id IS NULL AND event_id IS NULL));

COMMENT ON COLUMN authorization_decision.event_id IS
    'The outbox row this decision published through, so a decision and its event are traceable to one
     another in both directions. NULL for the one decided outcome that publishes no event: reject code
     0100 at app/cbl/CBTRN02C.cbl:L385-L387 resolves no account identifier, and every event contract on
     this platform requires one and is keyed on it.';
