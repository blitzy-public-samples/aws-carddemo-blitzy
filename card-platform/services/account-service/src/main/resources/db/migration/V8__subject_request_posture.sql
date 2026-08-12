-- Account service, migration V8. Re-issues the customer.social_security_number comment.
--
-- Statements: one COMMENT ON COLUMN. No column, index, constraint or row is declared, altered or
-- removed, and the retention and purge_key tokens equivalence-tests/RetentionSweepContractTest reads
-- are re-issued unchanged.
--
-- The text each of the four replaces described an export or an erasure request reaching the row. No
-- endpoint, event, service or repository on this platform performs either, and
-- card-platform/docs/suggested-next-tasks.md carries the task and names every store it would have to
-- reach. Four migrations correct the same claim, one per schema that carried it: account-service V8,
-- authorization-service V11, card-service V4 and notification-service V6.
--
-- Design decisions: card-platform/docs/decision-log.md, under
-- "What a stored row is allowed to claim".

COMMENT ON COLUMN customer.social_security_number IS
    'personal_data=yes. Held because app/cpy/CVCUS01Y.cpy:L17 declares it and the edit at
     app/cbl/COACTUPC.cbl:L2431-L2491 reads it. It reaches no event, no log line and no response
     body. NO ERASURE OR EXPORT WORKFLOW EXISTS on this platform: an erasure would clear this value
     with the rest of the customer row, and nothing here performs one today.
     card-platform/docs/suggested-next-tasks.md carries the task.';
