-- Authorization service, migration V19.
-- Gives the one decline that had no account subject the account subject it always had available, and
-- narrows the message key column to the one form this service now writes.
--
-- What this file declares. Reject code 0100 at app/cbl/CBTRN02C.cbl:L385-L387 is assigned in the
-- INVALID KEY limb of the cross-reference read, before app/cbl/CBTRN02C.cbl:L394 moves XREF-ACCT-ID
-- into the account key. V14 read that ordering as meaning the decline has no account at all and
-- published nothing; V15 read it as meaning the decline has no account to key on and published a
-- second contract keyed on the transaction identifier. A review of the delivered platform found both
-- readings wrong in the same place. The ordering settles which read resolved an identifier, not which
-- subject the decision applies to. A synchronous caller declares its own subject: COTRN02C takes an
-- account identifier or a card number at app/cbl/COTRN02C.cbl:L196-L209, and its card branch refuses
-- a card the cross-reference does not hold with 'Card Number NOT found...' at
-- app/cbl/COTRN02C.cbl:L625-L626 rather than deciding anything.
--
-- The behaviour now, stated once. domain/AuthorizationService resolves the subject from the
-- cross-reference row where the card resolves one, and from the caller's validated eleven-digit
-- account identifier where it does not. A call that establishes neither is refused before a decision
-- with that same source text, so it allocates no identifier, records no decision and publishes no
-- event. Every decided decline therefore carries an eleven-digit account, publishes
-- schemas/transaction-declined-v3.json keyed on it, and reaches the ledger with the nine descriptive
-- values app/cbl/CBTRN02C.cbl:L446-L465 copies into REJECT-TRAN-DATA, so the 430-byte reject record
-- exists for reject code 0100 exactly as it does for 0101, 0102 and 0103.
--
-- What changes here. unresolved_card_attempt gains account_id, so the durable audit row names the
-- same subject its event does. outbox_event.aggregate_id keeps both key forms and its comment stops
-- claiming that a live contract uses the second one.
--
-- The division of the key rule between this file and the writer. Every row written from here forward
-- carries the eleven-digit account key, and outbox/OutboxWriter refuses anything else before a row is
-- built. ck_outbox_event_aggregate_id still admits the sixteen-character form, because a PostgreSQL
-- CHECK marked NOT VALID skips the rows present when it is added and is still enforced on every UPDATE
-- of them, and the relay writes a column on every row it claims, retries or abandons. The alternatives
-- weighed and the risk accepted: card-platform/docs/decision-log.md.
--
-- The nullable column and its NOT VALID clause. A database that ran under V14 or V15 holds
-- unresolved_card_attempt rows whose decision resolved no subject. No account identifier can honestly
-- be invented for them, so they are neither deleted nor filled: the column stays nullable and the
-- CHECK carries NOT VALID, which is PostgreSQL's documented way to hold every insert to the rule from
-- this migration forward while leaving the rows already present unre-checked.
--
-- Records already on a topic stay readable. libs/event-contracts keeps
-- schemas/transaction-declined-v2.json on the classpath and its posture in
-- contracts/released-contracts.json is RETAINED, so the deserializer still selects it by the version
-- a record declares, and EventEnvelope.AGGREGATE_KEY_PATTERN still admits the transaction key form on
-- the read side. What no producer path may do any more is write either.
--
-- Idempotent in the sense Flyway needs: this file runs once, and DROP ... IF EXISTS lets it run against
-- a database at any of the states V14 and V15 could leave.

ALTER TABLE unresolved_card_attempt
    ADD COLUMN account_id VARCHAR(11);

-- The subject an attempt was decided against, at the width of XREF-ACCT-ID PIC 9(11). Absent only on
-- a row written before this service resolved a subject for reject code 0100.
ALTER TABLE unresolved_card_attempt
    DROP CONSTRAINT IF EXISTS ck_unresolved_card_attempt_account_id;

ALTER TABLE unresolved_card_attempt
    ADD CONSTRAINT ck_unresolved_card_attempt_account_id
    CHECK (account_id IS NULL OR account_id ~ '^[0-9]{11}$') NOT VALID;

CREATE INDEX idx_unresolved_card_attempt_account_id
    ON unresolved_card_attempt (account_id);

COMMENT ON COLUMN outbox_event.aggregate_id IS
    'The Kafka message key. One form reaches a row written from V19 forward: eleven decimal digits, the
     account identifier of XREF-ACCT-ID PIC 9(11) at app/cpy/CVACT03Y.cpy:L7. It carries every event of
     an account on one partition in publish order, which is the ordering the ledger applies to a
     balance. The key travels as text, so a leading zero belongs to the value: account fifty renders as
     00000000050. outbox/OutboxWriter refuses any other form before a row is built.
     ck_outbox_event_aggregate_id still admits the sixteen-character transaction identifier of
     TRAN-ID PIC X(16) at app/cpy/CVTRA05Y.cpy:L5, because rows written before V19 -- when a decline
     whose card resolved no account was keyed that way -- carry it, and the relay writes a column on
     every row it claims, retries or abandons. A CHECK narrowed to the account form would refuse those
     updates and strand real events, so the column stays open and the writer holds the rule.';

COMMENT ON COLUMN unresolved_card_attempt.account_id IS
    'The account this attempt was decided against, at the width of XREF-ACCT-ID PIC 9(11) at
     app/cpy/CVACT03Y.cpy:L7. The cross-reference read resolved none, so this is the account the caller
     declared, and it is the subject the published decline names in accountId and keys on. Absent only
     on a row written before V19, when the decision resolved no subject at all;
     ck_unresolved_card_attempt_account_id is NOT VALID for that reason.';

COMMENT ON TABLE unresolved_card_attempt IS
    'retention=carddemo.retention.decision-retention-days; purge_key=attempted_at;
     personal_data=pseudonymous. One authorization attempt whose card resolved to no cross-reference
     row, which is reject code 0100 at app/cbl/CBTRN02C.cbl:L385. This row, the authorization_decision
     row beside it and one outbox_event row are written in one transaction, so the durable record and
     the published event agree or neither exists. The published event is
     schemas/transaction-declined-v3.json, keyed on account_id, carrying the nine descriptive values
     app/cbl/CBTRN02C.cbl:L446-L465 copies into REJECT-TRAN-DATA so the ledger can write the reject
     record this refusal earned. The card number is masked to twelve mask characters and the last four
     digits, and no full Primary Account Number and no card verification value reaches any column here.
     What this table adds over authorization_decision is the fact that the card reached this service
     and no cross-reference row held it. It shares the decision horizon because it is the same kind of
     record, the attribution of one refusal.
     purge_op=UnresolvedCardAttemptRepository.deleteAttemptedBefore, called by
     domain/RetentionSweep.';

COMMENT ON COLUMN authorization_decision.event_id IS
    'The outbox row this decision published through, so a decision and its event are traceable to one
     another in both directions. Present on every decision this service records: an approval publishes
     schemas/transaction-authorized-v2.json and every decline publishes
     schemas/transaction-declined-v3.json, each keyed on the eleven-digit account the decision applies
     to. ck_authorization_decision_event holds the column to that, and is NOT VALID so rows written
     before V15 -- when one outcome published nothing -- stay the record they are.';
