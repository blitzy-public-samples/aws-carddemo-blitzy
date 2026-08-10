-- Authorization service, migration V14. Restates catalogue comments that described an unresolved-card
-- decline as a published event.
--
-- Statements: one COMMENT ON COLUMN over outbox_event.aggregate_id, two COMMENT ON INDEX over the two
-- observed_at indexes, and one COMMENT ON TABLE over unresolved_card_attempt. No column, index,
-- constraint or row is declared, altered or removed.
--
-- The mechanics at the time. Reject code 0100 is assigned at app/cbl/CBTRN02C.cbl:L385, inside the
-- INVALID KEY branch of the cross-reference read at :L383, and the short-circuit at :L376-L378 stops
-- the account read, so no account identifier exists at that moment. The two observed_at indexes of
-- V1__schema.sql were described there as serving a freshness check; V12__replica_gap.sql replaced that
-- check, and these comments record what the indexes order now.
--
-- SUPERSEDED IN PART. V15__unresolved_decline_is_published.sql records the current position: that
-- outcome publishes a governed transaction-declined-v2 event keyed on its sixteen-character
-- transaction identifier, which ck_outbox_event_aggregate_id already admits beside the eleven-digit
-- account form (V4__outbox_transaction_key.sql). The comment below on unresolved_card_attempt states
-- that nothing is published for the outcome, and V15 restates it.
--
-- Rationale, alternatives considered and accepted risks: card-platform/docs/decision-log.md.

COMMENT ON COLUMN outbox_event.aggregate_id IS
    'The Kafka message key. Eleven decimal digits, the account identifier, from XREF-ACCT-ID
     PIC 9(11) at app/cpy/CVACT03Y.cpy:L7, on every row this service writes. The key travels as
     text, so a leading zero belongs to the value: account fifty renders as 00000000050.
     The CHECK also admits sixteen printable characters, which is TRAN-ID PIC X(16) at
     app/cpy/CVTRA05Y.cpy:L5, and no producer writes that form: an authorization that resolved no
     account is recorded in unresolved_card_attempt and authorization_decision and publishes nothing,
     because a key that is not an account would reach a topic every consumer partitions by account.
     The sixteen-character form is the retained contract of schemas/transaction-declined-v2.json,
     readable for as long as the topic holds a record written under it.';

-- The two observed_at indexes of V1__schema.sql are described there as serving a freshness check.
-- No decision compares observed_at against a window any more, for the reason above: an owner that
-- publishes only on a state change leaves an untouched row's stamp receding while the copy stays
-- correct, so the age of the row said nothing about the currency of the copy. Both indexes are kept
-- and both still earn their place, ordering a provenance report and bounding the scan a range read
-- performs. These two comments record what they are for now.

COMMENT ON INDEX ix_card_xref_observed_at IS
    'Orders card_xref by when this service last wrote each row, which is the provenance report an
     operator reads and the range a scan bounds. Not a currency check: replica currency is measured
     by consumer lag in messaging/KafkaReplicaSynchronization and by the replica_gap table.';

COMMENT ON INDEX ix_account_credit_snapshot_observed_at IS
    'Orders account_credit_snapshot by when this service last wrote each row, which is the provenance
     report an operator reads and the range a scan bounds. Not a currency check: replica currency is
     measured by consumer lag in messaging/KafkaReplicaSynchronization and by the replica_gap
     table.';

COMMENT ON TABLE unresolved_card_attempt IS
    'retention=carddemo.retention.decision-retention-days; purge_key=attempted_at;
     personal_data=pseudonymous. One authorization attempt whose card resolved to no account,
     which is reject code 0100 at app/cbl/CBTRN02C.cbl:L385. This row and the authorization_decision
     row beside it are the whole durable record of that outcome: nothing is published for it, because
     every event on this platform names an account and is keyed on one, and this outcome resolved
     none. The card number is masked to twelve mask characters and the last four digits, and no full
     Primary Account Number and no card verification value reaches any column here. There is no
     account identifier to hold: the short-circuit at app/cbl/CBTRN02C.cbl:L376-L378 stops the account
     read from running, so the row is pseudonymous through its masked card and its transaction
     identifier alone. It shares the decision horizon because it is the same kind of record, the
     attribution of one refusal.
     purge_op=UnresolvedCardAttemptRepository.deleteAttemptedBefore, called by
     domain/RetentionSweep.';
