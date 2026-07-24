-- V4__create_transaction_id_sequence.sql
-- Transaction-id generator. Owned by transaction-service (schema owner of the
-- transactions table created in V1). Backs TransactionRepository.getNextTransactionId()
-- ("SELECT nextval('transaction_id_seq')"), which supplies the 16-digit zero-padded
-- TRAN-ID for the online add flow (COTRN02C) and the bill-pay posting flow (COBIL00C).
-- Replaces the legacy browse-last-then-increment probe (AAP 0.6.5) with a database
-- sequence while preserving the observable 16-digit id format; the sequence is aligned
-- to the current maximum so generated ids continue after the last existing row,
-- reproducing the COBOL "last id + 1" baseline.
CREATE SEQUENCE IF NOT EXISTS transaction_id_seq
    AS BIGINT
    START WITH 1
    INCREMENT BY 1
    MINVALUE 1
    NO MAXVALUE
    CACHE 1;

-- Advance the sequence past any rows already present when this migration runs so the
-- next generated id does not collide with a pre-seeded transaction. When the table is
-- empty the next value is 1 (first transaction -> 0000000000000001).
SELECT setval(
           'transaction_id_seq',
           GREATEST(COALESCE((SELECT MAX(CAST(tran_id AS BIGINT)) FROM transactions), 0) + 1, 1),
           false
       );
