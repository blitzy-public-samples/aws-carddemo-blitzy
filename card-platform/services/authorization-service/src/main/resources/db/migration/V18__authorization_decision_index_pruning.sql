-- Authorization service, migration V18.
-- Withdraws the two authorization_decision indexes no read path reaches.
--
-- What this file declares. V5__authorization_decision.sql created three indexes for three questions the
-- table was expected to be asked: one account's recent decisions, one actor's recent decisions, and
-- rows past a retention horizon. Only the third question is ever asked. A performance review found
-- that the two repository reads naming the first two composites had no caller anywhere in the
-- service, and neither did a third bounded read over age alone. An index with no read path is not
-- free: every authorization inserts one decision row, and each surviving index turns that insert into
-- an insert plus an index maintenance write, plus the storage and the vacuum work behind it.
--
-- Withdrawn here, and retained. ix_authorization_decision_account_decided and
-- ix_authorization_decision_actor are dropped. ix_authorization_decision_decided_at stays, because
-- repository/AuthorizationDecisionRepository.deleteDecidedBefore reads it for both the subquery that
-- selects a batch and the delete that removes it. The primary key on transaction_id stays and is
-- untouched: it is what the inherited findById reads and what makes a repeated identifier a
-- constraint violation rather than a replaced decision.
--
-- An operator query. Nothing here forecloses one. The read this table is genuinely
-- expected to serve one day is a bounded operator query over an account's recent decisions, and
-- docs/suggested-next-tasks.md carries it as a task. Adding it means adding the endpoint, the bounded
-- read and the index that serves it in one change, so the index arrives with the query that reads it
-- rather than years ahead of it. IF EXISTS is used so a schema created before V5, or one an operator
-- has already pruned by hand, applies this file rather than failing on it.
--
-- NOTHING ELSE CHANGES. No column, constraint, default, comment or row is touched, and no data moves.
DROP INDEX IF EXISTS ix_authorization_decision_account_decided;

DROP INDEX IF EXISTS ix_authorization_decision_actor;
