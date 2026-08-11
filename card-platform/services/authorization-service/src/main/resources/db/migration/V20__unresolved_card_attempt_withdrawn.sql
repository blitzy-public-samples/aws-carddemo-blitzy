-- Authorization service, migration V20.
-- Withdraws unresolved_card_attempt, because the outcome it recorded is no longer decided.
--
-- What a security review found. V19 gave reject code 0100 the account the caller declared as its
-- subject, and that value is the one thing on the request no stored row of this platform ties to the
-- card presented. A caller holding a broad role -- the acquirer identity this route exists for, or an
-- administrator -- could therefore present an unknown sixteen-digit card together with any
-- eleven-digit account and have this service write an unresolved_card_attempt row, an
-- authorization_decision row and a declined event, all keyed on an account it had merely named. The
-- audit trail of that account then held a refusal that account had nothing to do with, and the Kafka
-- partition of that account carried the record.
--
-- What the source does with the same case. The feed record the batch path validates carries no account
-- at all: app/cpy/CVTRA06Y.cpy declares twelve fields and none of them is an account identifier, which
-- is why app/cbl/CBTRN02C.cbl:L394 has to move XREF-ACCT-ID out of the resolved cross-reference row
-- before app/cbl/CBTRN02C.cbl:L446-L465 can write the 430-byte reject record against an account. When
-- that read misses, the batch has a file to write the reject into and no account to attribute it to.
-- The synchronous ancestor answers differently and more plainly: the NOTFND limb of READ-CCXREF-FILE at
-- app/cbl/COTRN02C.cbl:L625-L626 moves 'Card Number NOT found...' into the message field and re-sends
-- the screen, capturing nothing and deciding nothing.
--
-- The behaviour now, stated once. domain/AuthorizationService resolves the subject of a decision from
-- the cross-reference row and from nowhere else. A card that resolves no row refuses the call with that
-- same source text, for every caller, after the entitlement check and before the identifier is drawn,
-- so the call allocates no sequence value, records no decision, writes no audit row and publishes no
-- event. An account named on the request selects the card on the account branch of
-- app/cbl/COTRN02C.cbl:L196-L209 and takes no further part.
--
-- The table is dropped rather than left in place empty. Every column of it described an outcome that
-- no longer exists, and its account_id column held the value the review objected to. The observation it
-- carried -- that a card reached this service and no cross-reference row held it -- is kept as a metric
-- instead: config/ObservabilityConfig.UNRESOLVED_CARD_STAGE counts each refusal on
-- carddemo.authorization.failures, so a caller probing card numbers shows up as a rising count that
-- holds no cardholder value at all. Alternatives weighed: card-platform/docs/decision-log.md.
--
-- Rows already present are dropped with the table. They record decisions taken under V14, V15 and V19,
-- and the authorization_decision row of each of those calls stays: the decision is the record of what
-- this service answered, and it is not being rewritten. What is removed is the second copy of that
-- refusal, whose account column carried a value the platform could not vouch for.
--
-- Records already on a topic stay readable. libs/event-contracts keeps
-- schemas/transaction-declined-v2.json and -v3.json on the classpath, their postures in
-- contracts/released-contracts.json are unchanged, and EventEnvelope.AGGREGATE_KEY_PATTERN still admits
-- both key forms on the read side. A consumer meeting a reason-0100 record published before this
-- migration still reads it. What no producer path writes any more is a reason-0100 decline of any
-- version.
--
-- Alternatives weighed and the risk accepted: card-platform/docs/decision-log.md.
--
-- Idempotent in the sense Flyway needs: this file runs once, and IF EXISTS lets it run against a
-- database at any of the states V3, V14, V15 and V19 could leave.

DROP INDEX IF EXISTS idx_unresolved_card_attempt_account_id;

DROP INDEX IF EXISTS idx_unresolved_card_attempt_attempted_at;

DROP TABLE IF EXISTS unresolved_card_attempt;

COMMENT ON COLUMN outbox_event.aggregate_id IS
    'The Kafka message key, and one form reaches a row this service writes: eleven decimal digits, the
     account identifier of XREF-ACCT-ID PIC 9(11) at app/cpy/CVACT03Y.cpy:L7. It carries every event of
     an account on one partition in publish order, which is the ordering the ledger applies to a
     balance. The key travels as text, so a leading zero belongs to the value: account fifty renders as
     00000000050. outbox/OutboxWriter refuses any other form before a row is built, and from V20 the
     account is always one a cross-reference row named rather than one a caller declared.
     ck_outbox_event_aggregate_id still admits the sixteen-character transaction identifier of
     TRAN-ID PIC X(16) at app/cpy/CVTRA05Y.cpy:L5, because rows written before V19 -- when a decline
     whose card resolved no account was keyed that way -- carry it, and the relay writes a column on
     every row it claims, retries or abandons. A CHECK narrowed to the account form would refuse those
     updates and strand real events, so the column stays open and the writer holds the rule.';

COMMENT ON TABLE authorization_decision IS
    'retention=carddemo.retention.decision-retention-days; purge_key=decided_at;
     personal_data=pseudonymous. One decision this service answered with, and the identity that asked
     for it. Every row names the eleven-digit account the cross-reference resolved, because from V20 a
     card that resolves no row is refused rather than decided: there is no decision whose subject was
     taken from the request body. An approval publishes schemas/transaction-authorized-v2.json and a
     decline publishes schemas/transaction-declined-v3.json, each keyed on that account, and event_id
     ties the row to the outbox row it published through. The card number is masked to twelve mask
     characters and the last four digits, and no full Primary Account Number and no card verification
     value reaches any column here.
     purge_op=AuthorizationDecisionRepository.deleteDecidedBefore, called by domain/RetentionSweep.';
