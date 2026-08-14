-- Fraud detection service, migration V6.
-- Rebuilds ix_fraud_assessment_account_assessed_at with the primary key as its trailing column, so
-- the index carries the whole order the paged read of api/FraudAssessmentController now applies.
--
-- The read is FraudAssessmentRepository.findByAccountIdOrderByAssessedAtDescTransactionIdDesc, which
-- orders by account_id, then assessed_at descending, then transaction_id descending. V1__schema.sql
-- created this index over the first two columns alone, matching the two-column order that finder
-- carried then. transaction_id is the primary key of fraud_assessment, declared in V1__schema.sql:
-- it is unique and never updated, so appending it makes the order total and each row falls on
-- exactly one page.
--
-- assessed_at is stamped by this service, and two assessments of one account hold the same value
-- whenever two authorizations arrive inside the same microsecond, which one burst on one account
-- produces. Design decisions for the column choice: card-platform/docs/decision-log.md.
--
-- ADDITIVE. No dataset definition under app/jcl/ declares an alternate index over a risk score, and
-- no program under app/cbl/ scores risk, so this index has no source ancestor to reproduce.
--
-- An index is rebuilt rather than added beside the old one. Two indexes leading on the same two
-- columns would both be maintained on every insert and only one would ever be read. The name is kept
-- so the entity mapping of entity/FraudAssessmentEntity names one index and not two.

DROP INDEX ix_fraud_assessment_account_assessed_at;

CREATE INDEX ix_fraud_assessment_account_assessed_at
    ON fraud_assessment (account_id, assessed_at DESC, transaction_id DESC);
