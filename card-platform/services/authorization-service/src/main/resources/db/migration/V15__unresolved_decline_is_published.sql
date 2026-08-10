-- Authorization service, migration V15.
-- Restores the one event that a decided outcome had stopped publishing, and moves the constraint
-- that had been shaped around its absence.
--
-- What this file declares. AAP transformation rule T4 requires one authorization call to produce one
-- event, written through the outbox in the transaction that recorded the decision. V13 and V14
-- narrowed that rule to admit one exception: reject code 0100 at app/cbl/CBTRN02C.cbl:L385-L387
-- resolves no account identifier, so the decline was recorded twice and published nowhere. A review
-- of the delivered platform found the exception itself to be the defect. The three consumers of the
-- authorized stream had no record that the call happened, so a declined attempt on an unknown card
-- was visible only to whoever read this service's own two tables, and the acceptance property the
-- user stated -- one call, one event -- did not hold for every call.
--
-- The publish path now. domain/AuthorizationService writes one event under
-- schemas/transaction-declined-v2.json, the contract libs/event-contracts already governs for a
-- decline that resolved no account. It carries no accountId, because none exists that this platform
-- established: the short-circuit at app/cbl/CBTRN02C.cbl:L376-L378 stops the account read from
-- running. It is keyed on the sixteen-character transaction identifier from TRAN-ID PIC X(16) at
-- app/cpy/CVTRA05Y.cpy:L5, which V4__outbox_transaction_key.sql widened aggregate_id to hold and
-- ck_outbox_event_aggregate_id still admits. That key is the one alternative naming no cardholder:
-- trusting an identifier the caller sent beside the card number would attribute one caller's
-- declined attempt to another caller's account, and minting one inside the real key space of
-- XREF-ACCT-ID PIC 9(11) would occupy a live account key. The key is drawn from the same sequence
-- one call draws once, so a retried publish of one decline lands on one partition.
--
-- The change here. ck_authorization_decision_event tied the absence of an event to the absence of
-- an account, which now describes no row this service writes and would refuse every row it does.
-- The constraint is replaced by the invariant that holds instead: every decision names the event it
-- published through.
--
-- The NOT VALID clause, and the nullable event_id. A database that applied V13 and then ran may hold
-- decision rows written under the old contract with a null event_id. Those rows are the audit record
-- of decisions that really did publish nothing, and no identifier can honestly be invented for them,
-- so they are neither deleted nor filled. NOT VALID is PostgreSQL's documented way to say exactly
-- that: the constraint is enforced on every insert and every update from this migration forward, and
-- rows already present are not re-checked. A NOT NULL column constraint offers no such scoping,
-- which is why the column keeps its nullability and the CHECK carries the rule.
--
-- Idempotent in the sense Flyway needs: this file runs once, and DROP ... IF EXISTS lets it run
-- against a database where V13's constraint was never created.

ALTER TABLE authorization_decision
    DROP CONSTRAINT IF EXISTS ck_authorization_decision_event;

-- One authorization call produces one event, and the decision row names it. A decision that
-- resolved an account names an account-keyed event; a decision that resolved none names a
-- transaction-keyed one. No decision names nothing.
ALTER TABLE authorization_decision
    ADD CONSTRAINT ck_authorization_decision_event CHECK (event_id IS NOT NULL) NOT VALID;

COMMENT ON COLUMN authorization_decision.event_id IS
    'The outbox row this decision published through, so a decision and its event are traceable to one
     another in both directions. Present on every decision this service records: an outcome that
     resolved an account publishes schemas/transaction-declined-v1.json or
     schemas/transaction-authorized-v2.json keyed on the account, and reject code 0100 at
     app/cbl/CBTRN02C.cbl:L385-L387 publishes schemas/transaction-declined-v2.json keyed on the
     transaction identifier. ck_authorization_decision_event holds the column to that, and is NOT
     VALID so rows written before V15 -- when that one outcome published nothing -- stay the record
     they are.';

COMMENT ON COLUMN outbox_event.aggregate_id IS
    'The Kafka message key, in one of the two forms ck_outbox_event_aggregate_id admits. Eleven
     decimal digits is the account identifier, from XREF-ACCT-ID PIC 9(11) at
     app/cpy/CVACT03Y.cpy:L7, and carries every event of an account on one partition in publish
     order. The key travels as text, so a leading zero belongs to the value: account fifty renders as
     00000000050. Sixteen printable characters is the transaction identifier, from TRAN-ID PIC X(16)
     at app/cpy/CVTRA05Y.cpy:L5, and one contract uses it: schemas/transaction-declined-v2.json, the
     decline whose card resolved no account, which has no account identifier to key on and would
     otherwise have to borrow a live one.';

COMMENT ON COLUMN account_credit_snapshot.pending_cycle_credit IS
    'Approved cycle credit this service has taken and the account service has not yet reported back.
     Width from ACCT-CURR-CYC-CREDIT PIC S9(10)V99 at app/cpy/CVACT01Y.cpy:L12: ten integer digits
     and two fractional ones. domain/CycleExposureReservation stores every sum at that width, the way
     a COBOL ADD with no ON SIZE ERROR phrase discards the digit that does not fit, so accumulation
     cannot exceed the column.';

COMMENT ON COLUMN account_credit_snapshot.pending_cycle_debit IS
    'Approved cycle debit this service has taken and the account service has not yet reported back.
     Width from ACCT-CURR-CYC-DEBIT PIC S9(10)V99 at app/cpy/CVACT01Y.cpy:L13, and the same store
     semantics as pending_cycle_credit. app/cbl/CBTRN02C.cbl:L548-L551 sends a negative amount here,
     so the figure runs negative and app/cbl/CBTRN02C.cbl:L404 subtracts it.';

COMMENT ON TABLE unresolved_card_attempt IS
    'retention=carddemo.retention.decision-retention-days; purge_key=attempted_at;
     personal_data=pseudonymous. One authorization attempt whose card resolved to no account,
     which is reject code 0100 at app/cbl/CBTRN02C.cbl:L385. This row, the authorization_decision
     row beside it and one outbox_event row are written in one transaction, so the durable record and
     the published event agree or neither exists. The published event is
     schemas/transaction-declined-v2.json, keyed on the transaction identifier because no account
     identifier exists at that moment. The card number is masked to twelve mask characters and the
     last four digits, and no full Primary Account Number and no card verification value reaches any
     column here. There is no account identifier to hold: the short-circuit at
     app/cbl/CBTRN02C.cbl:L376-L378 stops the account read from running, so the row is pseudonymous
     through its masked card and its transaction identifier alone. It shares the decision horizon
     because it is the same kind of record, the attribution of one refusal.
     purge_op=UnresolvedCardAttemptRepository.deleteAttemptedBefore, called by
     domain/RetentionSweep.';
