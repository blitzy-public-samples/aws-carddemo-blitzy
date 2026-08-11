-- =============================================================================
-- V8__transactions_card_fk.sql
--
-- Adds the missing referential-integrity constraint between the transaction
-- master and the card master, completing the "10 tables + FK constraints"
-- schema AAP 0.4.5 specifies and the 100% referential integrity AAP 0.1.1
-- requires.
--
-- WHY: V1 created `transactions` with no foreign key at all and recorded the
-- reason as "tran_card_num is a LOGICAL reference only (no FK): the posting job
-- must be able to record a transaction whose card is unknown (CBTRN02C reject
-- code 100)". That reason does not hold. CBTRN02C posts a transaction ONLY when
-- validation passed:
--
--     IF WS-VALIDATION-FAIL-REASON = 0
--       PERFORM 2000-POST-TRANSACTION
--     ELSE
--       PERFORM 2500-WRITE-REJECT-REC   [app/cbl/CBTRN02C.cbl:L210-L215]
--
-- and reject code 100 ("INVALID CARD NUMBER FOUND") is precisely the branch that
-- writes the 430-byte record to DALYREJS INSTEAD of the transaction master
-- [app/cbl/CBTRN02C.cbl:L380-L385]. A row in `transactions` therefore always
-- names a card that resolved through the CXACAIX cross-reference, so the
-- constraint records an invariant the batch and online paths already maintain
-- rather than adding a new rule. Without it the invariant was enforced by
-- application code alone: a defect in any writer could silently orphan a
-- financial row from its card.
--
-- The unvalidated feed keeps its freedom: `daily_transactions` (the DALYTRAN
-- input the posting job reads, app/cpy/CVTRA06Y.cpy) deliberately carries NO
-- such constraint, because an unknown card number arriving on the feed is
-- exactly what reject code 100 exists to report.
--
-- ON DELETE / ON UPDATE are intentionally omitted (RESTRICT): a card that still
-- carries posted financial history must not be deletable, which is the same
-- protection the VSAM environment provided by convention.
--
-- The constraint is added only when absent so the set can be replayed or adopted
-- on a database that already carries it; `transactions` holds no orphan rows in
-- any seeded or migrated database (V3 seeds every transaction against a seeded
-- card), so the constraint validates without a data fix.
--
-- Source references: app/cpy/CVTRA05Y.cpy (TRAN-RECORD, TRAN-CARD-NUM PIC X(16)),
-- app/cpy/CVACT02Y.cpy (CARD-RECORD, CARD-NUM PIC X(16)),
-- app/cpy/CVACT03Y.cpy (CARD-XREF-RECORD), app/cbl/CBTRN02C.cbl:L210-L215,
-- L371-L397 (the ordered cross-reference then account lookup).
-- =============================================================================

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.table_constraints
        WHERE table_name = 'transactions'
          AND constraint_name = 'fk_transactions_card'
          AND constraint_type = 'FOREIGN KEY'
    ) THEN
        ALTER TABLE transactions
            ADD CONSTRAINT fk_transactions_card
            FOREIGN KEY (tran_card_num) REFERENCES cards (card_num);
    END IF;
END
$$;
