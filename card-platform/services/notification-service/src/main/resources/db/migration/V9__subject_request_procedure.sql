-- Notification service, migration V9.
-- Points the two subject-data comments of this schema at the procedure that now exists, and corrects
-- the classification of the token-keyed read model.
--
-- Statements: two COMMENT ON TABLE. No table, column, index, constraint or row is declared, altered
-- or removed, and the retention and purge_key tokens equivalence-tests/RetentionSweepContractTest
-- reads are re-issued unchanged.
--
-- What changed, and what makes it a migration. V6 replaced a comment that promised an erasure route with
-- one stating the position: no route existed. A security review then asked for the route. What it
-- asked for is now delivered as a documented, executable procedure rather than as an orchestration:
-- card-platform/docs/data-model.md, under "Subject data: purpose, retention, export and erasure",
-- carries the jurisdiction-parameterised policy, the inventory of every store holding subject data,
-- the export statements, the erasure statements in the order they have to run, the completion record
-- a run leaves behind, and what a retained broker record and a backup mean for a request.
--
-- THE SECOND CHANGE IS A CLASSIFICATION, AND IT IS SUBSTANTIVE. statement_transaction read
-- personal_data=no. The table holds no card number and no name, which is what that answer described,
-- but every row is keyed by a card token and carries a masked card number, and a card token names one
-- card for as long as the key it was taken under stands. That is what pseudonymous means everywhere
-- else in this platform: card_xref carries identifiers alone and reads pseudonymous. A subject
-- request has to reach this table, and a reader taking it for anonymous would leave the rows behind.
-- The classification is corrected here rather than in V1, because V1 has run.
--
-- What is still absent. No endpoint, event, command or scheduled task erases or exports a subject. A
-- request is an operator running the procedure, and the four schemas it reaches are four separate
-- databases no service may reach into. card-platform/docs/suggested-next-tasks.md carries the
-- orchestration.
--
-- Rationale, alternatives considered and accepted risks: card-platform/docs/decision-log.md, under
-- "A subject request is a procedure before it is an orchestration".

COMMENT ON TABLE cardholder_context IS
    'retention=relationship; purge_key=none; personal_data=yes. Projection of the ten cardholder
     fields one alert reports, owned by the account service and applied here from
     CustomerContextChanged. A row lives as long as the customer relationship and no window expires
     it. NO AUTOMATED ERASURE OR EXPORT WORKFLOW EXISTS on this platform, here or upstream, and one
     documented procedure now does: card-platform/docs/data-model.md, under "Subject data: purpose,
     retention, export and erasure", names every store a request has to reach, this projection among
     them, and carries the statements that reach them in order. observed_at is the ordering guard:
     messaging/CustomerContextChangedConsumer refuses an event older than the row it would overwrite,
     so a replay cannot move a projection backwards, and an erasure has to be applied after the
     upstream row is cleared or the next event would rebuild it.
     card-platform/docs/suggested-next-tasks.md carries the orchestration.';

COMMENT ON TABLE statement_transaction IS
    'retention=400 days; purge_key=processing_timestamp; personal_data=pseudonymous;
     purge_op=StatementTransactionRepository.deleteProcessedBefore. Token-keyed read model behind a
     cardholder alert, card number masked wherever it appears. It holds no card number and no name,
     and it is pseudonymous rather than anonymous: every row is keyed by a card token, which names one
     card for as long as the key it was taken under stands, so a subject request has to reach this
     table. The window is carddemo.history.statement-retention-days in
     src/main/resources/application.yml, 400 days by default and overridable by
     STATEMENT_RETENTION_DAYS, and domain/RetentionSweep applies it. That setting is the authority: a
     window restated here as a second number would drift from the one the sweep reads. It mirrors
     ledger rows this service does not own, so it expires on its own clock: processing_timestamp holds
     26 characters shaped YYYY-MM-DD-HH.MM.SS.hh0000, which orders lexically, so a purge ranges over
     it as text.';
