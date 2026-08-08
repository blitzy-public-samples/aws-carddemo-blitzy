-- Authorization service, migration V13.
-- Lets the one decided outcome that publishes no event record that fact, instead of naming an
-- event row that was never published.
--
-- WHY THIS FILE EXISTS. A review found this service publishing a TransactionDeclined that declares no
-- accountId and is keyed on its sixteen-character transaction identifier. AAP 0.1.1 fixes the decline
-- payload at transaction identifier, account identifier and reject reason code, and AAP 0.3.1 makes
-- the account identifier the message key of every event on this platform. Reject code 0100 at
-- app/cbl/CBTRN02C.cbl:L385-L387 fires exactly where the keyed read of the cross-reference missed, so
-- no account identifier exists at that moment and no event on that contract can be built. The event
-- was therefore removed rather than reshaped, and domain/AuthorizationService now publishes nothing
-- for that outcome.
--
-- The source takes the same position on the synchronous path. READ-CCXREF-FILE at
-- app/cbl/COTRN02C.cbl:L620-L636 answers a card number the cross-reference does not carry with
-- 'Card Number NOT found...' and re-sends the screen: no reject record is written and nothing is
-- captured. The account branch at :L586-L596 already reaches this service as
-- AccountNotFoundInCrossReferenceException, which publishes nothing either.
--
-- WHAT STAYS. The outcome is still a decision, and it is still recorded twice. reason 0100 and its
-- verbatim text answer the caller with 422, unresolved_card_attempt keeps the attempt, and this table
-- keeps the decision and the identity behind it. AAP 0.4.1 requires that a keyed lookup miss be a
-- decline and not an exception, and it remains one.
--
-- WHAT CHANGES HERE. event_id was NOT NULL and described as 'the outbox row this decision published
-- through'. For this one outcome there is no such row, so the column becomes nullable and a check
-- constraint ties the absence to exactly that outcome: a decision with no account identifier is the
-- only decision that may carry no event, and every other decision must carry one. Recording a
-- generated identifier for an event nobody published would have kept the column NOT NULL and made
-- every row of it unverifiable.

ALTER TABLE authorization_decision
    ALTER COLUMN event_id DROP NOT NULL;

-- A decision that resolved an account published one event and names it. A decision that resolved
-- none published nothing and names nothing. No third combination reaches the table, so a missing
-- event_id cannot mean "we forgot".
ALTER TABLE authorization_decision
    ADD CONSTRAINT ck_authorization_decision_event CHECK (
        (account_id IS NOT NULL AND event_id IS NOT NULL)
        OR (account_id IS NULL AND event_id IS NULL));

COMMENT ON COLUMN authorization_decision.event_id IS
    'The outbox row this decision published through, so a decision and its event are traceable to one
     another in both directions. NULL for the one decided outcome that publishes no event: reject code
     0100 at app/cbl/CBTRN02C.cbl:L385-L387 resolves no account identifier, and every event contract on
     this platform requires one and is keyed on it.';
