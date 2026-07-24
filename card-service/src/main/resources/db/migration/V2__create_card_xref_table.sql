-- card-service Flyway V2: create the card_xref table (CXACAIX anchor).
-- Source record layout: app/cpy/CVACT03Y.cpy (CARD-XREF-RECORD, RECLN 50).
-- Column names/types match carddemo-common CardXref.java (@Table "card_xref")
-- so Hibernate ddl-auto=validate passes at card-service startup.
CREATE TABLE card_xref (
    xref_card_num VARCHAR(16)  PRIMARY KEY,   -- XREF-CARD-NUM PIC X(16)  (PAN; leading zeros)
    xref_cust_id  BIGINT       NOT NULL,       -- XREF-CUST-ID  PIC 9(09)  FK -> customers.cust_id
    xref_acct_id  BIGINT       NOT NULL,       -- XREF-ACCT-ID  PIC 9(11)  FK -> accounts.acct_id
    CONSTRAINT fk_card_xref_cust FOREIGN KEY (xref_cust_id) REFERENCES customers (cust_id),
    CONSTRAINT fk_card_xref_acct FOREIGN KEY (xref_acct_id) REFERENCES accounts (acct_id)
);

-- Secondary index reproducing VSAM CXACAIX (alternate index on account id),
-- backing the cross-reference-then-account lookup (findByXrefAcctId).
CREATE INDEX idx_card_xref_acct_id ON card_xref (xref_acct_id);
