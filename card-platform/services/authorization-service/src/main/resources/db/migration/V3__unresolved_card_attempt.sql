-- Durable record of one authorization attempt whose card resolved to no account.
--
-- Reject code 0100 is assigned at app/cbl/CBTRN02C.cbl:L385 when the keyed read of the
-- cross-reference dataset at app/cbl/CBTRN02C.cbl:L383 takes its INVALID KEY branch. No account
-- exists at that point, and the short-circuit at app/cbl/CBTRN02C.cbl:L376-L378 stops the account
-- read from running, so none is read later either.
--
-- This row is the durable audit companion of schemas/transaction-declined-v2.json. That contract
-- keys the event on the allocated sixteen-character transaction identifier because this outcome
-- cannot supply an account identifier. The source records the same refusal in its own terms:
-- app/cbl/CBTRN02C.cbl:L446-L465 writes a 430-byte reject record, and the 80-byte trailer at
-- app/cbl/CBTRN02C.cbl:L180-L182 carries the reject code and its text.
--
-- masked_card_number holds twelve mask characters then the last four digits. The full Primary
-- Account Number reaches no column of this table, no log line and no response body. The card
-- verification value reaches nothing either: this service never reads the card record.

CREATE TABLE unresolved_card_attempt (
    transaction_id             VARCHAR(16)   NOT NULL,
    masked_card_number         VARCHAR(16)   NOT NULL,
    amount                     NUMERIC(11,2) NOT NULL,
    decline_reason_code        VARCHAR(4)    NOT NULL,
    decline_reason_description VARCHAR(76)   NOT NULL,
    attempted_at               TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    PRIMARY KEY (transaction_id)
);

-- An operator reads the most recent attempts first, so the index carries the timestamp.
CREATE INDEX idx_unresolved_card_attempt_attempted_at
    ON unresolved_card_attempt (attempted_at);
