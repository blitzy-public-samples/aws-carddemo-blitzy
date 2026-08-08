-- Restate two catalogue comments that describe an unresolved-card decline as a published event.
--
-- What changed above this migration. V4__outbox_transaction_key.sql widened outbox_event.aggregate_id
-- to VARCHAR(16) so that the one decline resolving no account could be published keyed on its
-- sixteen-character transaction identifier, and V3__unresolved_card_attempt.sql describes its table as
-- the audit companion of an event that travels. That is no longer what the service does, and the
-- reason is the promise the key itself carries.
--
-- Every event on this platform is keyed on the eleven-digit account identifier, so a consumer reads
-- one partition as one account's ordered history and a dead-letter diagnostic names the account it
-- concerns. Reject code 0100 is assigned at app/cbl/CBTRN02C.cbl:L385, inside the INVALID KEY branch
-- of the cross-reference read at app/cbl/CBTRN02C.cbl:L383, and the short-circuit at
-- app/cbl/CBTRN02C.cbl:L376-L378 stops the account read from running. No account identifier exists at
-- that moment, so the three ways to publish anyway are to trust an identifier the caller sent beside
-- the card number, to invent one inside the real key space of XREF-ACCT-ID PIC 9(11), or to put a key
-- that is not an account on a topic partitioned by account. Each breaks something a consumer relies
-- on, and the third breaks it for every consumer of that topic rather than for one record.
--
-- What the service does instead. domain/AuthorizationService records the attempt in
-- unresolved_card_attempt and the outcome in authorization_decision with a null event_id, which
-- V13__decision_without_event.sql permits and ck_authorization_decision_event bounds, and answers the
-- caller a decline carrying reject code 0100. Nothing is published. The source takes the same position
-- on its own synchronous path: app/cbl/COTRN02C.cbl:L620-L636 answers a card number the
-- cross-reference does not carry with a screen message and writes no reject record at all. The two
-- durable rows are this service's addition, standing where the batch path writes a reject record at
-- app/cbl/CBTRN02C.cbl:L446-L465.
--
-- Why the column stays sixteen characters wide, and why V4 is not edited. A migration that has run is
-- a fact about an existing database, and Flyway compares its checksum on every start, so rewriting the
-- text of V4 would refuse to start against any database that already applied it. The widened column
-- and its CHECK are also still needed: schemas/transaction-declined-v2.json is retained, because a
-- record published under it before this decision stays readable for as long as the topic retains it,
-- and outbox/OutboxWriter still accepts that form so a reinstated producer has somewhere to write.
-- This migration therefore corrects what the catalogue says and leaves the structure alone, exactly as
-- V10__declared_retention_matches_the_sweep.sql and V11__subject_request_posture.sql did before it.
--
-- Idempotent: COMMENT ON replaces whatever the column or table last carried.

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
