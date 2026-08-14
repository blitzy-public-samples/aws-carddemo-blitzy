-- Gives one account's assessment page a total order, and withdraws the two indexes that only
-- carried part of it.
--
-- api/FraudAssessmentController reads one account's assessments newest first, and it reaches a
-- page by naming a position: the assessment time and transaction identifier of the last row it
-- served. The predicate that follows that position is a range, so the tenth page and the
-- ten-thousandth cost the same. Counting rows from the start of the account's history instead
-- would read every row before the page and return none of them.
--
-- The order needs both columns. assessed_at is not unique: one consumer batch assesses several
-- transactions and the rows can carry the same microsecond, so ordering by time alone leaves an
-- equal-time group in whatever order the plan produces. A page boundary inside such a group then
-- repeats one row and skips another. transaction_id is the primary key, so adding it makes the
-- order total and the boundary exact.

-- account_id selects the account, assessed_at DESC and transaction_id DESC carry the order and the
-- tiebreaker, so the page is read straight from the index. Both directions are declared because the
-- read walks descending on both columns.
CREATE INDEX ix_fraud_assessment_account_cursor
    ON fraud_assessment (account_id, assessed_at DESC, transaction_id DESC);

-- Both withdrawn indexes are leading prefixes of the index above, so it answers every query either
-- of them answered. No read remains that they serve: the only other reads of this table are by
-- primary key (api/FraudAssessmentController.assessmentOfTransaction) and by assessed_at alone
-- (domain/RetentionSweep, which ix_fraud_assessment_assessed_at still serves).
--
-- Keeping them would cost writes for nothing. This table takes one insert per authorized
-- transaction, on a path the authorization response never waits for but which every transaction
-- reaches, and each index is a separate write on every one of those inserts.
--
-- IF EXISTS so the statement is safe on a database where an earlier hand-run repair already removed
-- one of them.
DROP INDEX IF EXISTS ix_fraud_assessment_account;
DROP INDEX IF EXISTS ix_fraud_assessment_account_assessed_at;
