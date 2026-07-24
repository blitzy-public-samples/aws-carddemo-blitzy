-- card-service Flyway V1: create the cards table.
-- Source record layout: app/cpy/CVACT02Y.cpy (CARD-RECORD, RECLN 150).
-- Column names/types match carddemo-common Card.java (@Table "cards")
-- so Hibernate ddl-auto=validate passes at card-service startup.
CREATE TABLE cards (
    card_num            VARCHAR(16)  PRIMARY KEY,   -- CARD-NUM            PIC X(16)  (PAN; string preserves leading zeros)
    card_acct_id        BIGINT       NOT NULL,      -- CARD-ACCT-ID        PIC 9(11)  FK -> accounts.acct_id
    card_cvv_cd         VARCHAR(512),               -- CARD-CVV-CD         PIC 9(03)  (SENSITIVE; encrypted at rest via CryptoConverter)
    card_embossed_name  VARCHAR(50)  NOT NULL,      -- CARD-EMBOSSED-NAME  PIC X(50)
    card_expiraion_date VARCHAR(10)  NOT NULL,      -- CARD-EXPIRAION-DATE PIC X(10)  (source misspelling preserved)
    card_active_status  VARCHAR(1)   NOT NULL,      -- CARD-ACTIVE-STATUS  PIC X(01)
    CONSTRAINT fk_cards_account FOREIGN KEY (card_acct_id) REFERENCES accounts (acct_id)
);

-- Secondary index reproducing VSAM CARDAIX (alternate index on account id),
-- backing the card-service findByCardAcctId browse (card-list-by-account).
CREATE INDEX idx_cards_card_acct_id ON cards (card_acct_id);
