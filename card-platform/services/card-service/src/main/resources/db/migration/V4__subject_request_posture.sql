-- Card service, migration V4. Re-issues the card_xref table comment.
--
-- Statements: one COMMENT ON TABLE. No column, index, constraint or row is declared, altered or
-- removed, and the retention and purge_key tokens equivalence-tests/RetentionSweepContractTest reads
-- are re-issued unchanged. The card table's own comment is untouched: it speaks about dropping the
-- card_verification_value column, which is a schema change rather than a data-subject workflow.
--
-- V5__xref_reconciliation_and_status_domain.sql supersedes the first sentence of the comment below,
-- which said the replica was kept current by the events this service publishes.
--
-- The text each of the four replaces described an export or an erasure request reaching the row. No
-- endpoint, event, service or repository on this platform performs either, and
-- card-platform/docs/suggested-next-tasks.md carries the task and names every store it would have to
-- reach. Four migrations correct the same claim, one per schema that carried it: account-service V8,
-- authorization-service V11, this file and notification-service V6.
--
-- Design decisions: card-platform/docs/decision-log.md, under
-- "What a stored row is allowed to claim".

COMMENT ON TABLE card_xref IS
    'retention=relationship; purge_key=none; personal_data=pseudonymous. Card-to-account cross-
     reference replica, kept current by the events this service publishes when a card changes. Its
     card, account and customer identifiers link the row to one person, and a row lives as long as
     the card it names. NO ERASURE OR EXPORT WORKFLOW EXISTS on this platform: this comment records
     the position rather than a promise, and an earlier version of it said an erasure request has to
     reach this row, which described an obligation nothing discharges. This service deletes no
     cross-reference row; domain/RetentionSweep purges published outbox rows and processed-event
     markers and nothing else. card-platform/docs/suggested-next-tasks.md carries the erasure and
     export task and names every store it would have to reach, this replica among them.';
