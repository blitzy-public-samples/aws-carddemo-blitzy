-- Widen the outbox message key so that the one decline carrying no account identifier can be
-- published like every other decision.
--
-- The gap this closes. Reject code 0100 is assigned at app/cbl/CBTRN02C.cbl:L385 when the keyed
-- read of the cross-reference dataset at app/cbl/CBTRN02C.cbl:L383 takes its INVALID KEY branch,
-- and the short-circuit at app/cbl/CBTRN02C.cbl:L376-L378 stops the account read from running. No
-- account identifier exists at that moment and none is read later, so the decision has nothing to
-- put in an eleven-character key column. Until this migration the decision was recorded in
-- unresolved_card_attempt and no event left the service, which broke the one guarantee this
-- platform makes about the authorization call: one call produces one event.
--
-- The contract that carries it already exists. schemas/transaction-declined-v2.json declares no
-- accountId and keys the event on the sixteen characters of TRAN-ID PIC X(16) at
-- app/cpy/CVTRA05Y.cpy:L5, so the producer neither trusts an identifier a caller supplied nor
-- invents one inside the real account key space. This migration makes the storage match that
-- contract.
--
-- Two key forms, and exactly two. The CHECK below admits eleven decimal digits, which is
-- XREF-ACCT-ID PIC 9(11) at app/cpy/CVACT03Y.cpy:L7, or sixteen printable characters, which is
-- TRAN-ID PIC X(16). Nothing else reaches the column, so a malformed key is refused by the
-- database rather than published to a partition no consumer expects.
--
-- The column is VARCHAR rather than CHAR. A column holding two widths cannot pad, because padding a
-- sixteen-character transaction identifier to a fixed width would change the message key, so the
-- account form is stored at its own eleven characters and the eleven-digit CHECK is what keeps a
-- leading zero from being dropped: '00000000050' passes and '50' does not.
-- card-platform/docs/decision-log.md carries the alternatives.
--
-- Ordering and partitioning are unaffected for the account form. Every event of one account still
-- carries that account's key, so the events of one account stay on one partition and in publish
-- order. A transaction-keyed decline names one transaction and has no ordering relationship to any
-- other event, which is why giving it its own key space costs nothing.

ALTER TABLE outbox_event
    ALTER COLUMN aggregate_id TYPE VARCHAR(16);

-- Existing rows were stored as CHAR(11) and carry no padding to strip: the column held exactly
-- eleven characters and every value in it is an eleven-digit account identifier. The statement
-- below is a no-op on well-formed data and repairs any row a manual insert padded.
UPDATE outbox_event
SET aggregate_id = rtrim(aggregate_id)
WHERE aggregate_id <> rtrim(aggregate_id);

ALTER TABLE outbox_event
    ADD CONSTRAINT ck_outbox_event_aggregate_id
        CHECK (aggregate_id ~ '^[0-9]{11}$' OR aggregate_id ~ '^[!-~]{16}$');

COMMENT ON COLUMN outbox_event.aggregate_id IS
    'The Kafka message key. Eleven decimal digits for an account-keyed event, from XREF-ACCT-ID
     PIC 9(11) at app/cpy/CVACT03Y.cpy:L7, or sixteen printable characters for the one decline that
     resolved no account, from TRAN-ID PIC X(16) at app/cpy/CVTRA05Y.cpy:L5. The key travels as
     text, so a leading zero belongs to the value: account fifty renders as 00000000050.';
