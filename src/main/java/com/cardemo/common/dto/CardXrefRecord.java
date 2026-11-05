package com.cardemo.common.dto;

import jakarta.validation.constraints.Size;
import java.util.Objects;

/**
 * Card cross-reference record DTO — translated from CVACT03Y.cpy (CARD-XREF-RECORD, RECLN 50).
 *
 * <p>Represents the card-to-customer-to-account cross-reference (junction) record
 * used by the CARDXREF VSAM dataset. This record links a 16-character card number
 * to its owning 9-digit customer ID and 11-digit account ID.</p>
 *
 * <h3>COBOL Source Mapping (CVACT03Y.cpy):</h3>
 * <pre>
 *   01 CARD-XREF-RECORD.
 *       05 XREF-CARD-NUM   PIC X(16).   → xrefCardNum  (PK)
 *       05 XREF-CUST-ID    PIC 9(09).   → xrefCustId
 *       05 XREF-ACCT-ID    PIC 9(11).   → xrefAcctId   (AIX equivalent)
 *       05 FILLER           PIC X(14).   → not mapped
 * </pre>
 *
 * <p>Total COBOL record length: 50 bytes (16 + 9 + 11 + 14 FILLER).</p>
 * <p>Identity is based on {@code xrefCardNum} (the VSAM primary key XREF-CARD-NUM).</p>
 */
public class CardXrefRecord {

    /**
     * Card number — maps to COBOL field XREF-CARD-NUM PIC X(16).
     * This is the primary key of the CARDXREF VSAM dataset.
     */
    @Size(max = 16)
    private String xrefCardNum;

    /**
     * Customer ID — maps to COBOL field XREF-CUST-ID PIC 9(09).
     * Links this card cross-reference to a customer record in CUSTDATA.
     */
    @Size(max = 9)
    private String xrefCustId;

    /**
     * Account ID — maps to COBOL field XREF-ACCT-ID PIC 9(11).
     * Links this card cross-reference to an account record in ACCTDATA.
     * This field serves as the alternate index (AIX) equivalent for account-based lookups.
     */
    @Size(max = 11)
    private String xrefAcctId;

    /**
     * Default no-argument constructor.
     * Required for frameworks (JPA, Jackson, Spring) that instantiate via reflection.
     */
    public CardXrefRecord() {
    }

    /**
     * All-arguments constructor for creating a fully populated card cross-reference record.
     *
     * @param xrefCardNum the 16-character card number (XREF-CARD-NUM)
     * @param xrefCustId  the 9-digit customer ID (XREF-CUST-ID)
     * @param xrefAcctId  the 11-digit account ID (XREF-ACCT-ID)
     */
    public CardXrefRecord(String xrefCardNum, String xrefCustId, String xrefAcctId) {
        this.xrefCardNum = xrefCardNum;
        this.xrefCustId = xrefCustId;
        this.xrefAcctId = xrefAcctId;
    }

    /**
     * Returns the card number (XREF-CARD-NUM).
     *
     * @return the 16-character card number, or {@code null} if not set
     */
    public String getXrefCardNum() {
        return xrefCardNum;
    }

    /**
     * Sets the card number (XREF-CARD-NUM).
     *
     * @param xrefCardNum the 16-character card number
     */
    public void setXrefCardNum(String xrefCardNum) {
        this.xrefCardNum = xrefCardNum;
    }

    /**
     * Returns the customer ID (XREF-CUST-ID).
     *
     * @return the 9-digit customer ID, or {@code null} if not set
     */
    public String getXrefCustId() {
        return xrefCustId;
    }

    /**
     * Sets the customer ID (XREF-CUST-ID).
     *
     * @param xrefCustId the 9-digit customer ID
     */
    public void setXrefCustId(String xrefCustId) {
        this.xrefCustId = xrefCustId;
    }

    /**
     * Returns the account ID (XREF-ACCT-ID).
     *
     * @return the 11-digit account ID, or {@code null} if not set
     */
    public String getXrefAcctId() {
        return xrefAcctId;
    }

    /**
     * Sets the account ID (XREF-ACCT-ID).
     *
     * @param xrefAcctId the 11-digit account ID
     */
    public void setXrefAcctId(String xrefAcctId) {
        this.xrefAcctId = xrefAcctId;
    }

    /**
     * Returns a string representation of this card cross-reference record,
     * including all three mapped fields.
     *
     * @return a descriptive string containing xrefCardNum, xrefCustId, and xrefAcctId
     */
    @Override
    public String toString() {
        return "CardXrefRecord{"
                + "xrefCardNum='" + xrefCardNum + '\''
                + ", xrefCustId='" + xrefCustId + '\''
                + ", xrefAcctId='" + xrefAcctId + '\''
                + '}';
    }

    /**
     * Compares this card cross-reference record with another object for equality.
     * Identity is based solely on {@code xrefCardNum} (the VSAM primary key XREF-CARD-NUM).
     *
     * @param o the object to compare with
     * @return {@code true} if the other object is a {@code CardXrefRecord}
     *         with the same {@code xrefCardNum}
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CardXrefRecord other)) {
            return false;
        }
        return Objects.equals(xrefCardNum, other.xrefCardNum);
    }

    /**
     * Returns a hash code based on {@code xrefCardNum} (the VSAM primary key).
     *
     * @return the hash code derived from the card number
     */
    @Override
    public int hashCode() {
        return Objects.hash(xrefCardNum);
    }
}
