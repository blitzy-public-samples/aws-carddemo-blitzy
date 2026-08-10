-- Notification service, migration V6. Re-issues the cardholder_context table comment.
--
-- Statements: one COMMENT ON TABLE. No column, index, constraint or row is declared, altered or
-- removed, and the retention and purge_key tokens equivalence-tests/RetentionSweepContractTest reads
-- are re-issued unchanged.
--
-- The text each of the four replaces described an export or an erasure request reaching the row. No
-- endpoint, event, service or repository on this platform performs either, and
-- card-platform/docs/suggested-next-tasks.md carries the task and names every store it would have to
-- reach. Four migrations correct the same claim, one per schema that carried it: account-service V8,
-- authorization-service V11, card-service V4 and notification-service V6.
--
-- Rationale, alternatives considered and accepted risks: card-platform/docs/decision-log.md, under
-- "What a stored row is allowed to claim".

COMMENT ON TABLE cardholder_context IS
    'retention=relationship; purge_key=none; personal_data=yes. Projection of the ten
     cardholder fields one alert reports, owned by the account service and applied here from
     CustomerContextChanged. A row lives as long as the customer relationship and no window
     expires it. NO ERASURE OR EXPORT WORKFLOW EXISTS on this platform, here or upstream: this
     comment records the position rather than a promise, and an earlier version of it described
     observed_at as serving a purge of erased accounts, which was never implemented anywhere.
     observed_at is the ordering guard: messaging/CustomerContextChangedConsumer refuses an
     event older than the row it would overwrite, so a replay cannot move a projection
     backwards. card-platform/docs/suggested-next-tasks.md carries the erasure and export task
     and names every store it would have to reach.';
