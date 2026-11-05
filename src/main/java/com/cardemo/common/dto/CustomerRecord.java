package com.cardemo.common.dto;

import jakarta.validation.constraints.Size;
import java.util.Objects;

/**
 * Customer record DTO — translated from CVCUS01Y.cpy (CUSTOMER-RECORD, RECLN 500).
 *
 * <p>Faithfully maps all 18 COBOL 05-level fields from the CUSTOMER-RECORD
 * copybook. The FILLER field (PIC X(168)) at byte positions 333–500 is not
 * mapped as it carries no business data.</p>
 *
 * <p>Also references CUSTREC.cpy which contains the same structure with a
 * minor field name variation: {@code CUST-DOB-YYYYMMDD} versus
 * {@code CUST-DOB-YYYY-MM-DD}. This DTO uses the CVCUS01Y.cpy naming
 * as the primary convention.</p>
 *
 * <p><strong>Contains PII fields:</strong> {@code custSsn} and
 * {@code custGovtIssuedId} are personally identifiable information and
 * are masked in {@link #toString()} output.</p>
 *
 * <pre>
 * COBOL Source: app/cpy/CVCUS01Y.cpy
 * Record Length: 500 bytes
 * Primary Key: CUST-ID PIC 9(09) — 9-digit customer identifier
 * </pre>
 *
 * @see com.cardemo.entity.Customer
 */
public class CustomerRecord {

    // ---------------------------------------------------------------
    // Fields — exact 1:1 mapping from CVCUS01Y.cpy 05-level items
    // ---------------------------------------------------------------

    /** CUST-ID — PIC 9(09). 9-digit customer identifier (primary key). */
    @Size(max = 9)
    private String custId;

    /** CUST-FIRST-NAME — PIC X(25). Customer first name. */
    @Size(max = 25)
    private String custFirstName;

    /** CUST-MIDDLE-NAME — PIC X(25). Customer middle name. */
    @Size(max = 25)
    private String custMiddleName;

    /** CUST-LAST-NAME — PIC X(25). Customer last name. */
    @Size(max = 25)
    private String custLastName;

    /** CUST-ADDR-LINE-1 — PIC X(50). Address line 1. */
    @Size(max = 50)
    private String custAddrLine1;

    /** CUST-ADDR-LINE-2 — PIC X(50). Address line 2. */
    @Size(max = 50)
    private String custAddrLine2;

    /** CUST-ADDR-LINE-3 — PIC X(50). Address line 3. */
    @Size(max = 50)
    private String custAddrLine3;

    /** CUST-ADDR-STATE-CD — PIC X(02). Two-character state code. */
    @Size(max = 2)
    private String custAddrStateCd;

    /** CUST-ADDR-COUNTRY-CD — PIC X(03). Three-character country code. */
    @Size(max = 3)
    private String custAddrCountryCd;

    /** CUST-ADDR-ZIP — PIC X(10). Postal/ZIP code. */
    @Size(max = 10)
    private String custAddrZip;

    /** CUST-PHONE-NUM-1 — PIC X(15). Primary phone number. */
    @Size(max = 15)
    private String custPhoneNum1;

    /** CUST-PHONE-NUM-2 — PIC X(15). Secondary phone number. */
    @Size(max = 15)
    private String custPhoneNum2;

    /**
     * CUST-SSN — PIC 9(09). Social Security Number.
     *
     * <p><strong>PII FIELD — SENSITIVE.</strong> This field contains a
     * personally identifiable Social Security Number. It is masked in
     * {@link #toString()} and must be handled with appropriate security
     * controls (encryption at rest, restricted access, audit logging).</p>
     */
    @Size(max = 9)
    private String custSsn;

    /**
     * CUST-GOVT-ISSUED-ID — PIC X(20). Government-issued identification number.
     *
     * <p><strong>PII FIELD — SENSITIVE.</strong> This field contains a
     * government-issued identification number (e.g., driver's license,
     * passport). It is masked in {@link #toString()} and must be handled
     * with appropriate security controls.</p>
     */
    @Size(max = 20)
    private String custGovtIssuedId;

    /**
     * CUST-DOB-YYYY-MM-DD — PIC X(10). Date of birth in YYYY-MM-DD format.
     *
     * <p>CUSTREC.cpy uses the alternate name CUST-DOB-YYYYMMDD for this
     * same field. Both map to this single Java field.</p>
     */
    @Size(max = 10)
    private String custDobYyyyMmDd;

    /** CUST-EFT-ACCOUNT-ID — PIC X(10). Electronic Funds Transfer account identifier. */
    @Size(max = 10)
    private String custEftAccountId;

    /** CUST-PRI-CARD-HOLDER-IND — PIC X(01). Primary cardholder indicator ('Y'/'N'). */
    @Size(max = 1)
    private String custPriCardHolderInd;

    /** CUST-FICO-CREDIT-SCORE — PIC 9(03). Three-digit FICO credit score. */
    @Size(max = 3)
    private String custFicoCreditScore;

    // FILLER — PIC X(168) at byte positions 333–500 is intentionally not mapped.

    // ---------------------------------------------------------------
    // Constructors
    // ---------------------------------------------------------------

    /**
     * Default no-argument constructor.
     */
    public CustomerRecord() {
        // Default constructor required for frameworks and serialization
    }

    /**
     * All-arguments constructor initializing every field of the customer record.
     *
     * @param custId               9-digit customer identifier (PK)
     * @param custFirstName        customer first name (max 25)
     * @param custMiddleName       customer middle name (max 25)
     * @param custLastName         customer last name (max 25)
     * @param custAddrLine1        address line 1 (max 50)
     * @param custAddrLine2        address line 2 (max 50)
     * @param custAddrLine3        address line 3 (max 50)
     * @param custAddrStateCd      two-character state code
     * @param custAddrCountryCd    three-character country code
     * @param custAddrZip          postal/ZIP code (max 10)
     * @param custPhoneNum1        primary phone number (max 15)
     * @param custPhoneNum2        secondary phone number (max 15)
     * @param custSsn              SSN — PII field (max 9)
     * @param custGovtIssuedId     government-issued ID — PII field (max 20)
     * @param custDobYyyyMmDd      date of birth YYYY-MM-DD (max 10)
     * @param custEftAccountId     EFT account identifier (max 10)
     * @param custPriCardHolderInd primary cardholder indicator (max 1)
     * @param custFicoCreditScore  FICO credit score (max 3)
     */
    public CustomerRecord(String custId,
                          String custFirstName,
                          String custMiddleName,
                          String custLastName,
                          String custAddrLine1,
                          String custAddrLine2,
                          String custAddrLine3,
                          String custAddrStateCd,
                          String custAddrCountryCd,
                          String custAddrZip,
                          String custPhoneNum1,
                          String custPhoneNum2,
                          String custSsn,
                          String custGovtIssuedId,
                          String custDobYyyyMmDd,
                          String custEftAccountId,
                          String custPriCardHolderInd,
                          String custFicoCreditScore) {
        this.custId = custId;
        this.custFirstName = custFirstName;
        this.custMiddleName = custMiddleName;
        this.custLastName = custLastName;
        this.custAddrLine1 = custAddrLine1;
        this.custAddrLine2 = custAddrLine2;
        this.custAddrLine3 = custAddrLine3;
        this.custAddrStateCd = custAddrStateCd;
        this.custAddrCountryCd = custAddrCountryCd;
        this.custAddrZip = custAddrZip;
        this.custPhoneNum1 = custPhoneNum1;
        this.custPhoneNum2 = custPhoneNum2;
        this.custSsn = custSsn;
        this.custGovtIssuedId = custGovtIssuedId;
        this.custDobYyyyMmDd = custDobYyyyMmDd;
        this.custEftAccountId = custEftAccountId;
        this.custPriCardHolderInd = custPriCardHolderInd;
        this.custFicoCreditScore = custFicoCreditScore;
    }

    // ---------------------------------------------------------------
    // Getters and Setters
    // ---------------------------------------------------------------

    /** Returns the 9-digit customer identifier (CUST-ID). */
    public String getCustId() {
        return custId;
    }

    /** Sets the 9-digit customer identifier (CUST-ID). */
    public void setCustId(String custId) {
        this.custId = custId;
    }

    /** Returns the customer first name (CUST-FIRST-NAME). */
    public String getCustFirstName() {
        return custFirstName;
    }

    /** Sets the customer first name (CUST-FIRST-NAME). */
    public void setCustFirstName(String custFirstName) {
        this.custFirstName = custFirstName;
    }

    /** Returns the customer middle name (CUST-MIDDLE-NAME). */
    public String getCustMiddleName() {
        return custMiddleName;
    }

    /** Sets the customer middle name (CUST-MIDDLE-NAME). */
    public void setCustMiddleName(String custMiddleName) {
        this.custMiddleName = custMiddleName;
    }

    /** Returns the customer last name (CUST-LAST-NAME). */
    public String getCustLastName() {
        return custLastName;
    }

    /** Sets the customer last name (CUST-LAST-NAME). */
    public void setCustLastName(String custLastName) {
        this.custLastName = custLastName;
    }

    /** Returns address line 1 (CUST-ADDR-LINE-1). */
    public String getCustAddrLine1() {
        return custAddrLine1;
    }

    /** Sets address line 1 (CUST-ADDR-LINE-1). */
    public void setCustAddrLine1(String custAddrLine1) {
        this.custAddrLine1 = custAddrLine1;
    }

    /** Returns address line 2 (CUST-ADDR-LINE-2). */
    public String getCustAddrLine2() {
        return custAddrLine2;
    }

    /** Sets address line 2 (CUST-ADDR-LINE-2). */
    public void setCustAddrLine2(String custAddrLine2) {
        this.custAddrLine2 = custAddrLine2;
    }

    /** Returns address line 3 (CUST-ADDR-LINE-3). */
    public String getCustAddrLine3() {
        return custAddrLine3;
    }

    /** Sets address line 3 (CUST-ADDR-LINE-3). */
    public void setCustAddrLine3(String custAddrLine3) {
        this.custAddrLine3 = custAddrLine3;
    }

    /** Returns the two-character state code (CUST-ADDR-STATE-CD). */
    public String getCustAddrStateCd() {
        return custAddrStateCd;
    }

    /** Sets the two-character state code (CUST-ADDR-STATE-CD). */
    public void setCustAddrStateCd(String custAddrStateCd) {
        this.custAddrStateCd = custAddrStateCd;
    }

    /** Returns the three-character country code (CUST-ADDR-COUNTRY-CD). */
    public String getCustAddrCountryCd() {
        return custAddrCountryCd;
    }

    /** Sets the three-character country code (CUST-ADDR-COUNTRY-CD). */
    public void setCustAddrCountryCd(String custAddrCountryCd) {
        this.custAddrCountryCd = custAddrCountryCd;
    }

    /** Returns the postal/ZIP code (CUST-ADDR-ZIP). */
    public String getCustAddrZip() {
        return custAddrZip;
    }

    /** Sets the postal/ZIP code (CUST-ADDR-ZIP). */
    public void setCustAddrZip(String custAddrZip) {
        this.custAddrZip = custAddrZip;
    }

    /** Returns the primary phone number (CUST-PHONE-NUM-1). */
    public String getCustPhoneNum1() {
        return custPhoneNum1;
    }

    /** Sets the primary phone number (CUST-PHONE-NUM-1). */
    public void setCustPhoneNum1(String custPhoneNum1) {
        this.custPhoneNum1 = custPhoneNum1;
    }

    /** Returns the secondary phone number (CUST-PHONE-NUM-2). */
    public String getCustPhoneNum2() {
        return custPhoneNum2;
    }

    /** Sets the secondary phone number (CUST-PHONE-NUM-2). */
    public void setCustPhoneNum2(String custPhoneNum2) {
        this.custPhoneNum2 = custPhoneNum2;
    }

    /**
     * Returns the Social Security Number (CUST-SSN).
     *
     * <p><strong>PII — handle with care.</strong></p>
     */
    public String getCustSsn() {
        return custSsn;
    }

    /**
     * Sets the Social Security Number (CUST-SSN).
     *
     * <p><strong>PII — handle with care.</strong></p>
     */
    public void setCustSsn(String custSsn) {
        this.custSsn = custSsn;
    }

    /**
     * Returns the government-issued identification number (CUST-GOVT-ISSUED-ID).
     *
     * <p><strong>PII — handle with care.</strong></p>
     */
    public String getCustGovtIssuedId() {
        return custGovtIssuedId;
    }

    /**
     * Sets the government-issued identification number (CUST-GOVT-ISSUED-ID).
     *
     * <p><strong>PII — handle with care.</strong></p>
     */
    public void setCustGovtIssuedId(String custGovtIssuedId) {
        this.custGovtIssuedId = custGovtIssuedId;
    }

    /** Returns the date of birth in YYYY-MM-DD format (CUST-DOB-YYYY-MM-DD). */
    public String getCustDobYyyyMmDd() {
        return custDobYyyyMmDd;
    }

    /** Sets the date of birth in YYYY-MM-DD format (CUST-DOB-YYYY-MM-DD). */
    public void setCustDobYyyyMmDd(String custDobYyyyMmDd) {
        this.custDobYyyyMmDd = custDobYyyyMmDd;
    }

    /** Returns the EFT account identifier (CUST-EFT-ACCOUNT-ID). */
    public String getCustEftAccountId() {
        return custEftAccountId;
    }

    /** Sets the EFT account identifier (CUST-EFT-ACCOUNT-ID). */
    public void setCustEftAccountId(String custEftAccountId) {
        this.custEftAccountId = custEftAccountId;
    }

    /** Returns the primary cardholder indicator (CUST-PRI-CARD-HOLDER-IND). */
    public String getCustPriCardHolderInd() {
        return custPriCardHolderInd;
    }

    /** Sets the primary cardholder indicator (CUST-PRI-CARD-HOLDER-IND). */
    public void setCustPriCardHolderInd(String custPriCardHolderInd) {
        this.custPriCardHolderInd = custPriCardHolderInd;
    }

    /** Returns the FICO credit score (CUST-FICO-CREDIT-SCORE). */
    public String getCustFicoCreditScore() {
        return custFicoCreditScore;
    }

    /** Sets the FICO credit score (CUST-FICO-CREDIT-SCORE). */
    public void setCustFicoCreditScore(String custFicoCreditScore) {
        this.custFicoCreditScore = custFicoCreditScore;
    }

    // ---------------------------------------------------------------
    // toString — PII fields are masked
    // ---------------------------------------------------------------

    /**
     * Returns a string representation of this customer record.
     *
     * <p><strong>PII Protection:</strong> The {@code custSsn} and
     * {@code custGovtIssuedId} fields are masked as {@code [REDACTED]}
     * to prevent accidental exposure of sensitive data in logs,
     * error messages, or debug output.</p>
     *
     * @return formatted string with PII fields redacted
     */
    @Override
    public String toString() {
        return "CustomerRecord{"
                + "custId='" + custId + '\''
                + ", custFirstName='" + custFirstName + '\''
                + ", custMiddleName='" + custMiddleName + '\''
                + ", custLastName='" + custLastName + '\''
                + ", custAddrLine1='" + custAddrLine1 + '\''
                + ", custAddrLine2='" + custAddrLine2 + '\''
                + ", custAddrLine3='" + custAddrLine3 + '\''
                + ", custAddrStateCd='" + custAddrStateCd + '\''
                + ", custAddrCountryCd='" + custAddrCountryCd + '\''
                + ", custAddrZip='" + custAddrZip + '\''
                + ", custPhoneNum1='" + custPhoneNum1 + '\''
                + ", custPhoneNum2='" + custPhoneNum2 + '\''
                + ", custSsn='[REDACTED]'"
                + ", custGovtIssuedId='[REDACTED]'"
                + ", custDobYyyyMmDd='" + custDobYyyyMmDd + '\''
                + ", custEftAccountId='" + custEftAccountId + '\''
                + ", custPriCardHolderInd='" + custPriCardHolderInd + '\''
                + ", custFicoCreditScore='" + custFicoCreditScore + '\''
                + '}';
    }

    // ---------------------------------------------------------------
    // equals / hashCode — identity based on custId (COBOL PK CUST-ID)
    // ---------------------------------------------------------------

    /**
     * Compares this customer record with another object for equality.
     *
     * <p>Identity is determined solely by {@code custId}, which corresponds
     * to the COBOL primary key {@code CUST-ID PIC 9(09)}.</p>
     *
     * @param obj the object to compare with
     * @return {@code true} if the other object is a {@code CustomerRecord}
     *         with the same {@code custId}
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof CustomerRecord other)) {
            return false;
        }
        return Objects.equals(custId, other.custId);
    }

    /**
     * Returns a hash code based on {@code custId} (COBOL PK CUST-ID).
     *
     * @return hash code derived from the customer identifier
     */
    @Override
    public int hashCode() {
        return Objects.hash(custId);
    }
}
