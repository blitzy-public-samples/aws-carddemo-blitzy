-- Authorization service, migration V23.
-- Points the cross-reference comment at the subject-request procedure that now exists.
--
-- Statements: one COMMENT ON TABLE. No table, column, index, constraint or row is declared, altered
-- or removed, and the retention and purge_key tokens equivalence-tests/RetentionSweepContractTest
-- reads are re-issued unchanged.
--
-- What changed, and what makes it a migration. V11 replaced a comment that promised an erasure route
-- with one stating the position: no route existed. A security review then asked for the route. What
-- it asked for is now delivered as a documented, executable procedure rather than as an
-- orchestration: card-platform/docs/data-model.md, under "Subject data: purpose, retention, export
-- and erasure", carries the jurisdiction-parameterised policy, the inventory of every store holding
-- subject data, the export statements, the erasure statements in the order they have to run, the
-- completion record a run leaves behind, and what a retained broker record and a backup mean for a
-- request. This comment names it, because a row that says a capability is absent while a procedure
-- exists is as stale as one that promised a capability nobody built.
--
-- What is still absent, stated so the comment cannot be read as more than it is. No endpoint,
-- event, command or scheduled task on this platform erases or exports a subject. A request is an
-- operator running the procedure, and the four schemas it reaches are four separate databases no
-- service may reach into. card-platform/docs/suggested-next-tasks.md carries the orchestration.
--
-- Rationale, alternatives considered and accepted risks: card-platform/docs/decision-log.md, under
-- "A subject request is a procedure before it is an orchestration".

COMMENT ON TABLE card_xref IS
    'retention=relationship; purge_key=none; personal_data=pseudonymous. Card-to-account cross-
     reference, the first hop of every authorization, keyed by card number from
     app/jcl/XREFFILE.jcl:L39-L47 with the account index from app/jcl/XREFFILE.jcl:L72-L77. A row
     lives as long as the card the card service holds, and no window expires it. NO AUTOMATED ERASURE
     OR EXPORT WORKFLOW EXISTS on this platform, and one documented procedure now does:
     card-platform/docs/data-model.md, under "Subject data: purpose, retention, export and erasure",
     names every store a request has to reach, this replica among them, and carries the statements
     that reach them in order. Nothing on this platform deletes a row of this table by itself: the
     card service deletes no card and no cross-reference row, and messaging/CardUpdatedConsumer
     applies an upsert with no delete branch. card-platform/docs/suggested-next-tasks.md carries the
     orchestration that would run the procedure without an operator.';
