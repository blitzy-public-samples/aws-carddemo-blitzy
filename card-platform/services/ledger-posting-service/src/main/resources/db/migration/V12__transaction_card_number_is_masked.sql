-- Ledger posting service, migration V12.
-- Constrains transaction.card_number to the masked form every writer of the table already produces.
--
-- What this file declares. Column card_number holds twelve mask characters and the last four digits
-- of the card, and nothing else. entity/TransactionEntity has refused any other shape since V1
-- through its requireMasked check, and no statement anywhere in this repository inserts a row of this
-- table another way: there is no seed for it and no test writes to it in raw SQL. The constraint
-- below states in the catalogue what the model was already the only enforcement of.
--
-- A review of the delivered platform found this the one masked column with no database check behind
-- it. ck_rejected_transaction_masked_card_number covers the reject block of this same schema at V1,
-- ck_notification_log_card_number covers the notification read model, and
-- ck_authorization_decision_masked_card covers the decision row. This column was the gap.
--
-- The shape, and the wider one that does not belong here. Twelve asterisks then four digits, which is
-- what schemas/transaction-authorized-v2.json declares for maskedCardNumber and what
-- entity/TransactionEntity compiles as MASKED_CARD_NUMBER_PATTERN. The wider form that also admits
-- sixteen mask characters belongs to the two columns a refused or unreadable card reaches:
-- cobol.PanMasker answers a card it cannot read with sixteen mask characters, and a posted
-- transaction exists only for a card that was read into a cross-reference row. Every row of this
-- table therefore carries four digits, and admitting the wider form here would widen the check past
-- anything the table can hold.
--
-- TRAN-CARD-NUM PIC X(16) at app/cpy/CVTRA05Y.cpy:L15 holds the full Primary Account Number, and no
-- row of this table has ever carried one. The column keeps its VARCHAR(16) width and its name,
-- because the masked form occupies the same sixteen characters the copybook field does and the name
-- is what card-platform/docs/traceability-matrix.md maps that field to.
-- Design decisions: card-platform/docs/decision-log.md.
--
-- Idempotent in the sense Flyway needs: this file runs once. ALTER TABLE validates the constraint
-- against every row already present, and the model guarantees each one conforms, so the statement
-- succeeds on a fresh database and on one carrying posted transactions alike.

ALTER TABLE transaction
    ADD CONSTRAINT ck_transaction_card_number
    CHECK (card_number ~ '^\*{12}[0-9]{4}$');

COMMENT ON COLUMN transaction.card_number IS
    'Masked card number: twelve mask characters then the last four digits, held by
     ck_transaction_card_number. TRAN-CARD-NUM PIC X(16) at app/cpy/CVTRA05Y.cpy:L15 holds the full
     Primary Account Number (PAN), which no row of this table carries. entity/TransactionEntity
     refuses any other shape before a row reaches the database, and V12 added the catalogue check
     behind it.';
