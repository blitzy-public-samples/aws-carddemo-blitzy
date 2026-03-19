package com.cardemo.common.dto;

import jakarta.validation.constraints.Size;
import java.util.Objects;

/**
 * Card record DTO — translated from CVACT02Y.cpy (CARD-RECORD, RECLN 150).
 *
 * <p>Maps the COBOL copybook {@code CVACT02Y.cpy} CARD-RECORD 01-level group
 * (150-byte fixed-width record) used by the CARDDATA VSAM KSDS dataset into a
 * Java POJO. Each 05-level field is preserved with its original naming
 * convention (camelCase equivalent of the COBOL hyphenated name) and size
 * constraint matching the PIC clause specification.</p>
 *
 * <h3>COBOL Field Layout (150 bytes total):</h3>
 * <pre>
 *   05 CARD-NUM              PIC X(16)   → cardNum
 *   05 CARD-ACCT-ID          PIC 9(11)   → cardAcctId
 *   05 CARD-CVV-CD           PIC 9(03)   → cardCvvCd   [PII — masked in toString]
 *   05 CARD-EMBOSSED-NAME    PIC X(50)   → cardEmbossedName
 *   05 CARD-EXPIRAION-DATE   PIC X(10)   → cardExpiraionDate  [typo preserved]
 *   05 CARD-ACTIVE-STATUS    PIC X(01)   → cardActiveStatus
 *   05 FILLER                PIC X(59)   → (not mapped — padding)
 * </pre>
 *
 * <p>Identity is determined by {@code cardNum} (the COBOL primary key
 * CARD-NUM PIC X(16)), which drives {@link #equals(Object)} and
 * {@link #hashCode()}.</p>
 *
 * <p><strong>PII Notice:</strong> The {@code cardCvvCd} field contains
 * sensitive card verification data and is masked in {@link #toString()}
 * output to prevent inadvertent exposure in logs or diagnostics.</p>
 *
 * @see com.cardemo.entity.Card
 */
public class CardRecord {

    // ---------------------------------------------------------------
    // Fields — exact mapping from CVACT02Y.cpy 05-level items
    // ---------------------------------------------------------------

    /**
     * Card number — CARD-NUM PIC X(16). Primary key of the CARDDATA dataset.
     */
    @Size(max = 16)
    private String cardNum;

    /**
     * Associated account identifier — CARD-ACCT-ID PIC 9(11).
     * Links to the ACCTDATA dataset via VSAM alternate index.
     */
    @Size(max = 11)
    private String cardAcctId;

    /**
     * Card verification value — CARD-CVV-CD PIC 9(03).
     * <p><strong>PII / Sensitive Data:</strong> This field contains the card
     * CVV code and must be handled securely. It is masked in
     * {@link #toString()} to prevent exposure in logs.</p>
     */
    @Size(max = 3)
    private String cardCvvCd;

    /**
     * Embossed cardholder name — CARD-EMBOSSED-NAME PIC X(50).
     */
    @Size(max = 50)
    private String cardEmbossedName;

    /**
     * Card expiration date — CARD-EXPIRAION-DATE PIC X(10).
     * <p>Note: The field name preserves the original COBOL typo
     * ("Expiraion" instead of "Expiration") for traceability.</p>
     */
    @Size(max = 10)
    private String cardExpiraionDate;

    /**
     * Card active status flag — CARD-ACTIVE-STATUS PIC X(01).
     * Typical values: 'Y' (active), 'N' (inactive).
     */
    @Size(max = 1)
    private String cardActiveStatus;

    // ---------------------------------------------------------------
    // Constructors
    // ---------------------------------------------------------------

    /**
     * Default no-argument constructor required by frameworks (JPA, Jackson).
     */
    public CardRecord() {
        // intentionally empty — framework requirement
    }

    /**
     * All-arguments constructor for programmatic construction.
     *
     * @param cardNum           card number (max 16 chars)
     * @param cardAcctId        account identifier (max 11 chars)
     * @param cardCvvCd         card verification value (max 3 chars, PII)
     * @param cardEmbossedName  embossed cardholder name (max 50 chars)
     * @param cardExpiraionDate expiration date (max 10 chars)
     * @param cardActiveStatus  active status flag (max 1 char)
     */
    public CardRecord(String cardNum,
                      String cardAcctId,
                      String cardCvvCd,
                      String cardEmbossedName,
                      String cardExpiraionDate,
                      String cardActiveStatus) {
        this.cardNum = cardNum;
        this.cardAcctId = cardAcctId;
        this.cardCvvCd = cardCvvCd;
        this.cardEmbossedName = cardEmbossedName;
        this.cardExpiraionDate = cardExpiraionDate;
        this.cardActiveStatus = cardActiveStatus;
    }

    // ---------------------------------------------------------------
    // Getters and Setters
    // ---------------------------------------------------------------

    /**
     * Returns the card number (CARD-NUM).
     *
     * @return card number, up to 16 characters
     */
    public String getCardNum() {
        return cardNum;
    }

    /**
     * Sets the card number (CARD-NUM).
     *
     * @param cardNum card number, up to 16 characters
     */
    public void setCardNum(String cardNum) {
        this.cardNum = cardNum;
    }

    /**
     * Returns the associated account identifier (CARD-ACCT-ID).
     *
     * @return account identifier, up to 11 characters
     */
    public String getCardAcctId() {
        return cardAcctId;
    }

    /**
     * Sets the associated account identifier (CARD-ACCT-ID).
     *
     * @param cardAcctId account identifier, up to 11 characters
     */
    public void setCardAcctId(String cardAcctId) {
        this.cardAcctId = cardAcctId;
    }

    /**
     * Returns the card verification value (CARD-CVV-CD).
     * <p><strong>PII / Sensitive Data:</strong> Callers must ensure this
     * value is not written to logs or diagnostics in cleartext.</p>
     *
     * @return CVV code, up to 3 characters
     */
    public String getCardCvvCd() {
        return cardCvvCd;
    }

    /**
     * Sets the card verification value (CARD-CVV-CD).
     *
     * @param cardCvvCd CVV code, up to 3 characters (PII)
     */
    public void setCardCvvCd(String cardCvvCd) {
        this.cardCvvCd = cardCvvCd;
    }

    /**
     * Returns the embossed cardholder name (CARD-EMBOSSED-NAME).
     *
     * @return embossed name, up to 50 characters
     */
    public String getCardEmbossedName() {
        return cardEmbossedName;
    }

    /**
     * Sets the embossed cardholder name (CARD-EMBOSSED-NAME).
     *
     * @param cardEmbossedName embossed name, up to 50 characters
     */
    public void setCardEmbossedName(String cardEmbossedName) {
        this.cardEmbossedName = cardEmbossedName;
    }

    /**
     * Returns the card expiration date (CARD-EXPIRAION-DATE).
     * <p>Note: Field name preserves the original COBOL typo.</p>
     *
     * @return expiration date string, up to 10 characters
     */
    public String getCardExpiraionDate() {
        return cardExpiraionDate;
    }

    /**
     * Sets the card expiration date (CARD-EXPIRAION-DATE).
     * <p>Note: Field name preserves the original COBOL typo.</p>
     *
     * @param cardExpiraionDate expiration date string, up to 10 characters
     */
    public void setCardExpiraionDate(String cardExpiraionDate) {
        this.cardExpiraionDate = cardExpiraionDate;
    }

    /**
     * Returns the card active status flag (CARD-ACTIVE-STATUS).
     *
     * @return active status, single character ('Y' / 'N')
     */
    public String getCardActiveStatus() {
        return cardActiveStatus;
    }

    /**
     * Sets the card active status flag (CARD-ACTIVE-STATUS).
     *
     * @param cardActiveStatus active status, single character ('Y' / 'N')
     */
    public void setCardActiveStatus(String cardActiveStatus) {
        this.cardActiveStatus = cardActiveStatus;
    }

    // ---------------------------------------------------------------
    // toString — PII-safe (CVV masked)
    // ---------------------------------------------------------------

    /**
     * Returns a string representation of this card record.
     * <p>The {@code cardCvvCd} field is masked as {@code "***"} to
     * prevent PII leakage in logs and diagnostic output.</p>
     *
     * @return human-readable representation with CVV masked
     */
    @Override
    public String toString() {
        return "CardRecord{"
                + "cardNum='" + cardNum + '\''
                + ", cardAcctId='" + cardAcctId + '\''
                + ", cardCvvCd='***'"
                + ", cardEmbossedName='" + cardEmbossedName + '\''
                + ", cardExpiraionDate='" + cardExpiraionDate + '\''
                + ", cardActiveStatus='" + cardActiveStatus + '\''
                + '}';
    }

    // ---------------------------------------------------------------
    // equals / hashCode — identity based on cardNum (COBOL PK)
    // ---------------------------------------------------------------

    /**
     * Determines equality based on the card number (CARD-NUM), which is
     * the primary key of the CARDDATA VSAM dataset.
     *
     * @param o the object to compare with
     * @return {@code true} if both objects have the same {@code cardNum}
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CardRecord other)) {
            return false;
        }
        return Objects.equals(cardNum, other.cardNum);
    }

    /**
     * Computes a hash code based on the card number (CARD-NUM).
     *
     * @return hash code derived from {@code cardNum}
     */
    @Override
    public int hashCode() {
        return Objects.hash(cardNum);
    }
}
