-- Authorization service, migration V11. Re-issues the card_xref table comment.
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
-- Design decisions: card-platform/docs/decision-log.md, under
-- "What a stored row is allowed to claim".

COMMENT ON TABLE card_xref IS
    'retention=relationship; purge_key=none; personal_data=pseudonymous. Card-to-account cross-
     reference, the first hop of every authorization, keyed by card number from
     app/jcl/XREFFILE.jcl:L39-L47 with the account index from app/jcl/XREFFILE.jcl:L72-L77. A row
     lives as long as the card the card service holds, and no window expires it. NO ERASURE OR
     EXPORT WORKFLOW EXISTS on this platform: this comment records the position rather than a
     promise, and an earlier version of it said erasure followed the card service deleting the card,
     which was true of neither service. The card service deletes no card and no cross-reference row,
     and messaging/CardUpdatedConsumer applies an upsert with no delete branch.
     card-platform/docs/suggested-next-tasks.md carries the erasure and export task and names every
     store it would have to reach, this replica among them.';
