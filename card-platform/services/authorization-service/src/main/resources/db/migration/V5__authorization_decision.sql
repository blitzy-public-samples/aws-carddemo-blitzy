-- Durable record of every authorization decision this service takes.
--
-- ADDITIVE. The source stores no decision of its own. app/cbl/CBTRN02C.cbl:L424-L444 posts an
-- approved transaction into three files and app/cbl/CBTRN02C.cbl:L446-L465 writes a 430-byte reject
-- record for a declined one, so the two outcomes land in different places and neither carries the
-- decision as such. This table is the one place that answers what this service decided for one
-- transaction.
--
-- The row and the outbox row commit in one local transaction, so a published event always has a
-- decision behind it and a stored decision always has an event. Every file definition in
-- app/csd/CARDDEMO.CSD carries RECOVERY(NONE) and JOURNAL(NO), so that atomicity is ADDITIVE too.
--
-- transaction_id is the primary key, which reproduces the key of the transaction file:
-- app/jcl/TRANFILE.jcl:L41 declares KEYS(16 0) and app/cbl/CBTRN02C.cbl:L562-L579 writes one record
-- under it, where a duplicate key fails the status test at :L566. A repeated call carrying one
-- caller-supplied identifier therefore fails here rather than deciding the same transaction twice.
--
-- account_id is null for exactly one outcome. Reject code 0100 at app/cbl/CBTRN02C.cbl:L385-L387
-- follows a cross-reference read that resolved no account, and no account exists to record.
--
-- masked_card_number holds twelve mask characters then the last four digits, and card_token holds
-- the 64 lower-case hexadecimal characters of the domain-separated digest. The full Primary Account
-- Number reaches no column of this table. The card verification value reaches nothing at all: this
-- service never reads the card record.
--
-- unresolved_card_attempt, created by V3__unresolved_card_attempt.sql, holds the reject code 0100
-- outcome alone and is the reject-record analogue of app/cbl/CBTRN02C.cbl:L446-L465. This table
-- holds every outcome, so a 0100 decision appears in both.
--
-- actor is what makes the row an attribution record as well as a decision record. Without it a
-- decision could be read but not traced to the identity that asked for it.

CREATE TABLE authorization_decision (
    transaction_id             VARCHAR(16)   NOT NULL,
    -- The authenticated request identity, bounded to the width SEC-USR-ID PIC X(08) declares at
    -- app/cpy/CSUSR01Y.cpy:L18. A decision that commits is a decision that can be attributed, and
    -- one that rolls back leaves no attribution behind, because this column travels in the row the
    -- outbox row commits beside.
    actor                      VARCHAR(8)    NOT NULL,
    -- Eleven digits, from XREF-ACCT-ID PIC 9(11) at app/cpy/CVACT03Y.cpy:L7. Fixed-width character
    -- storage keeps a leading zero, which 50 of the 50 rows of app/data/ASCII/cardxref.txt carry.
    account_id                 CHAR(11),
    masked_card_number         VARCHAR(16)   NOT NULL,
    card_token                 CHAR(64)      NOT NULL,
    -- DALYTRAN-AMT PIC S9(09)V99 at app/cpy/CVTRA06Y.cpy:L10.
    amount                     NUMERIC(11,2) NOT NULL,
    approved                   BOOLEAN       NOT NULL,
    -- WS-VALIDATION-FAIL-REASON PIC 9(04) at app/cbl/CBTRN02C.cbl:L181 and
    -- WS-VALIDATION-FAIL-REASON-DESC PIC X(76) at :L182, the 80-byte trailer of the reject record.
    decline_reason_code        VARCHAR(4),
    decline_reason_description VARCHAR(76),
    decided_at                 TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    -- The outbox row this decision published through, so a decision and its event are traceable to
    -- one another in both directions.
    event_id                   UUID          NOT NULL,
    CONSTRAINT pk_authorization_decision PRIMARY KEY (transaction_id),
    -- An approval names no reject reason and a decline names both halves of one, so no third state
    -- reaches the table.
    CONSTRAINT ck_authorization_decision_outcome CHECK (
        (approved = TRUE
            AND decline_reason_code IS NULL
            AND decline_reason_description IS NULL)
        OR (approved = FALSE
            AND decline_reason_code IS NOT NULL
            AND decline_reason_description IS NOT NULL)),
    -- An approval always names the account the cross-reference resolved.
    CONSTRAINT ck_authorization_decision_approved_account CHECK (
        approved = FALSE OR account_id IS NOT NULL),
    CONSTRAINT ck_authorization_decision_actor CHECK (
        actor ~ '^[!-~]{1,8}$'),
    CONSTRAINT ck_authorization_decision_account_digits CHECK (
        account_id IS NULL OR account_id ~ '^[0-9]{11}$'),
    CONSTRAINT ck_authorization_decision_reason_digits CHECK (
        decline_reason_code IS NULL OR decline_reason_code ~ '^[0-9]{4}$'),
    CONSTRAINT ck_authorization_decision_card_token CHECK (
        card_token IS NULL OR card_token ~ '^[0-9a-f]{64}$'),
    -- Twelve mask characters then the last four digits, or sixteen mask characters when the
    -- request named no card number. PanMasker.maskCardNumber produces both forms.
    CONSTRAINT ck_authorization_decision_masked_card CHECK (
        masked_card_number ~ '^\*{12}[0-9]{4}$' OR masked_card_number ~ '^\*{16}$')
);

-- An operator reads one account's recent decisions, and a retention pass reads by age. The
-- composite serves the first and its leading column serves a lookup by account alone.
CREATE INDEX ix_authorization_decision_account_decided
    ON authorization_decision (account_id, decided_at DESC);

CREATE INDEX ix_authorization_decision_decided_at
    ON authorization_decision (decided_at);

-- An audit read asks what one operator decided, most recent first, which the composite serves.
CREATE INDEX ix_authorization_decision_actor
    ON authorization_decision (actor, decided_at DESC);

COMMENT ON TABLE authorization_decision IS
    'retention=relationship; purge_key=decided_at; personal_data=pseudonymous. One authorization
     decision and the request identity behind it. The card number is masked and the account
     identifier is a pseudonymous reference to a cardholder, so the row describes an identifiable
     person once it is joined with the account service. It is the attribution record for a financial
     decision, so it outlives the outbox row the decision published.';
