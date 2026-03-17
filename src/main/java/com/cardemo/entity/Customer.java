/*
 * Customer.java — JPA Entity mapping VSAM CUSTDATA (500-byte KSDS record)
 *
 * Source: app/cpy/CVCUS01Y.cpy and app/cpy/CUSTREC.cpy (CUSTOMER-RECORD)
 * VSAM Dataset: AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS
 * Record Length: 500 bytes
 * Primary Key: CUST-ID PIC 9(09) — 9-character customer identifier
 *
 * COBOL Record Layout (CVCUS01Y.cpy):
 *   01  CUSTOMER-RECORD.
 *       05  CUST-ID                           PIC 9(09).       -> custId (String, 9 chars)
 *       05  CUST-FIRST-NAME                   PIC X(25).       -> firstName (String, 25 chars)
 *       05  CUST-MIDDLE-NAME                  PIC X(25).       -> middleName (String, 25 chars)
 *       05  CUST-LAST-NAME                    PIC X(25).       -> lastName (String, 25 chars)
 *       05  CUST-ADDR-LINE-1                  PIC X(50).       -> addrLine1 (String, 50 chars)
 *       05  CUST-ADDR-LINE-2                  PIC X(50).       -> addrLine2 (String, 50 chars)
 *       05  CUST-ADDR-LINE-3                  PIC X(50).       -> addrLine3 (String, 50 chars)
 *       05  CUST-ADDR-STATE-CD                PIC X(02).       -> addrStateCode (String, 2 chars)
 *       05  CUST-ADDR-COUNTRY-CD              PIC X(03).       -> addrCountryCode (String, 3 chars)
 *       05  CUST-ADDR-ZIP                     PIC X(10).       -> addrZip (String, 10 chars)
 *       05  CUST-PHONE-NUM-1                  PIC X(15).       -> phoneNum1 (String, 15 chars)
 *       05  CUST-PHONE-NUM-2                  PIC X(15).       -> phoneNum2 (String, 15 chars)
 *       05  CUST-SSN                          PIC 9(09).       -> ssn (String, 9 chars) [PII]
 *       05  CUST-GOVT-ISSUED-ID               PIC X(20).       -> govtIssuedId (String, 20 chars) [PII]
 *       05  CUST-DOB-YYYY-MM-DD               PIC X(10).       -> dateOfBirth (String, 10 chars)
 *       05  CUST-EFT-ACCOUNT-ID               PIC X(10).       -> eftAccountId (String, 10 chars)
 *       05  CUST-PRI-CARD-HOLDER-IND          PIC X(01).       -> priCardHolderInd (String, 1 char)
 *       05  CUST-FICO-CREDIT-SCORE            PIC 9(03).       -> ficoCreditScore (Integer)
 *       05  FILLER                            PIC X(168).      -> not mapped
 *   Total: 9+25+25+25+50+50+50+2+3+10+15+15+9+20+10+10+1+3+168 = 500 bytes
 *
 * Migration Notes:
 * - No monetary COMP-3 fields in customer record; all fields are String-typed
 * - CUST-ID and CUST-SSN are PIC 9(n) (numeric display) but stored as String
 *   to preserve leading zeros (e.g., "000000001", "020973888")
 * - CUST-FICO-CREDIT-SCORE PIC 9(03) stored as String for leading-zero preservation
 * - PII FIELDS: ssn and govtIssuedId are sensitive — excluded from toString()
 * - Both CVCUS01Y.cpy and CUSTREC.cpy define the same record; only difference is
 *   the DOB field name (CUST-DOB-YYYY-MM-DD vs CUST-DOB-YYYYMMDD); Java uses dateOfBirth
 * - FILLER (168 bytes) is not mapped — it is padding to reach 500-byte record length
 *
 * Ver: CardDemo_v1.0 — Migrated from COBOL to Java 25 + Spring Boot 3.5.x
 */
package com.cardemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;

/**
 * JPA entity representing a customer master record.
 *
 * <p>Maps the VSAM CUSTDATA KSDS dataset (500-byte records) defined
 * in COBOL copybooks CVCUS01Y.cpy and CUSTREC.cpy. Each record contains
 * customer demographic data, contact information, and financial identifiers
 * for the CardDemo credit card management system.</p>
 *
 * <p>This entity contains PII (Personally Identifiable Information) fields:
 * {@code ssn} (Social Security Number) and {@code govtIssuedId} (government-issued ID).
 * These fields are deliberately excluded from {@link #toString()} output
 * and must never be written to log files or exposed in API responses without
 * appropriate masking.</p>
 *
 * <p>All fields are String-typed. The customer record has no monetary COMP-3
 * fields, so BigDecimal is not needed. Numeric PIC 9(n) fields (CUST-ID,
 * CUST-SSN, CUST-FICO-CREDIT-SCORE) use String to preserve leading zeros.</p>
 */
@Entity
@Table(name = "customers")
public class Customer {

    // =========================================================================
    // Primary Key — CUST-ID PIC 9(09)
    // =========================================================================

    /**
     * Customer identifier (9-character numeric string).
     * Maps to COBOL CUST-ID PIC 9(09). Stored as String to preserve
     * leading zeros (e.g., "000000001").
     */
    @Id
    @Column(name = "cust_id", length = 9, nullable = false)
    private String custId;

    // =========================================================================
    // Name Fields — PIC X(25) each
    // =========================================================================

    /**
     * Customer first name.
     * Maps to COBOL CUST-FIRST-NAME PIC X(25).
     */
    @Column(name = "cust_first_name", length = 25)
    private String firstName;

    /**
     * Customer middle name.
     * Maps to COBOL CUST-MIDDLE-NAME PIC X(25).
     */
    @Column(name = "cust_middle_name", length = 25)
    private String middleName;

    /**
     * Customer last name.
     * Maps to COBOL CUST-LAST-NAME PIC X(25).
     */
    @Column(name = "cust_last_name", length = 25)
    private String lastName;

    // =========================================================================
    // Address Fields — PIC X(50) for lines, PIC X(02/03/10) for codes
    // =========================================================================

    /**
     * Address line 1 (street address).
     * Maps to COBOL CUST-ADDR-LINE-1 PIC X(50).
     */
    @Column(name = "cust_addr_line_1", length = 50)
    private String addrLine1;

    /**
     * Address line 2 (apartment, suite, etc.).
     * Maps to COBOL CUST-ADDR-LINE-2 PIC X(50).
     */
    @Column(name = "cust_addr_line_2", length = 50)
    private String addrLine2;

    /**
     * Address line 3 (city or additional info).
     * Maps to COBOL CUST-ADDR-LINE-3 PIC X(50).
     */
    @Column(name = "cust_addr_line_3", length = 50)
    private String addrLine3;

    /**
     * State code (2-letter US state abbreviation).
     * Maps to COBOL CUST-ADDR-STATE-CD PIC X(02).
     */
    @Column(name = "cust_addr_state_cd", length = 2)
    private String addrStateCode;

    /**
     * Country code (3-letter country code, e.g., "USA").
     * Maps to COBOL CUST-ADDR-COUNTRY-CD PIC X(03).
     */
    @Column(name = "cust_addr_country_cd", length = 3)
    private String addrCountryCode;

    /**
     * Address ZIP/postal code.
     * Maps to COBOL CUST-ADDR-ZIP PIC X(10).
     * Supports both 5-digit ("12546") and ZIP+4 ("19852-6716") formats.
     */
    @Column(name = "cust_addr_zip", length = 10)
    private String addrZip;

    // =========================================================================
    // Phone Fields — PIC X(15) each
    // =========================================================================

    /**
     * Primary phone number.
     * Maps to COBOL CUST-PHONE-NUM-1 PIC X(15).
     * Format typically "(NNN)NNN-NNNN" from seed data.
     */
    @Column(name = "cust_phone_num_1", length = 15)
    private String phoneNum1;

    /**
     * Secondary phone number.
     * Maps to COBOL CUST-PHONE-NUM-2 PIC X(15).
     * Format typically "(NNN)NNN-NNNN" from seed data.
     */
    @Column(name = "cust_phone_num_2", length = 15)
    private String phoneNum2;

    // =========================================================================
    // PII Fields — SENSITIVE: Do not log or expose without masking
    // =========================================================================

    /**
     * Social Security Number (9-digit numeric string).
     * Maps to COBOL CUST-SSN PIC 9(09). Stored as String to preserve
     * leading zeros (e.g., "020973888").
     *
     * <p><strong>PII: Do not log or expose.</strong> This field contains
     * sensitive personally identifiable information and is deliberately
     * excluded from {@link #toString()} output.</p>
     */
    @Column(name = "cust_ssn", length = 9)
    private String ssn; // PII: Do not log or expose

    /**
     * Government-issued identification number.
     * Maps to COBOL CUST-GOVT-ISSUED-ID PIC X(20).
     *
     * <p><strong>PII: Do not log or expose.</strong> This field contains
     * sensitive personally identifiable information and is deliberately
     * excluded from {@link #toString()} output.</p>
     */
    @Column(name = "cust_govt_issued_id", length = 20)
    private String govtIssuedId; // PII: Do not log or expose

    // =========================================================================
    // Date and Financial Identifier Fields
    // =========================================================================

    /**
     * Date of birth in YYYY-MM-DD format.
     * Maps to COBOL CUST-DOB-YYYY-MM-DD PIC X(10) (CVCUS01Y.cpy) /
     * CUST-DOB-YYYYMMDD PIC X(10) (CUSTREC.cpy).
     * Both copybooks define the same 10-character field at the same position.
     */
    @Column(name = "cust_dob_yyyy_mm_dd", length = 10)
    private String dateOfBirth;

    /**
     * Electronic Funds Transfer account identifier.
     * Maps to COBOL CUST-EFT-ACCOUNT-ID PIC X(10).
     */
    @Column(name = "cust_eft_account_id", length = 10)
    private String eftAccountId;

    // =========================================================================
    // Card Holder and Credit Score Fields
    // =========================================================================

    /**
     * Primary card holder indicator (single character).
     * Maps to COBOL CUST-PRI-CARD-HOLDER-IND PIC X(01).
     * Typical values: 'Y' = primary card holder, 'N' = not primary.
     */
    @Column(name = "cust_pri_card_holder_ind", length = 1)
    private String priCardHolderInd;

    /**
     * FICO credit score (integer value).
     * Maps to COBOL CUST-FICO-CREDIT-SCORE PIC 9(03).
     * Stored as Integer matching the PostgreSQL INTEGER column type.
     * Valid FICO range is typically 300-850.
     */
    @Column(name = "cust_fico_credit_score")
    private Integer ficoCreditScore;

    // =========================================================================
    // Constructors
    // =========================================================================

    /**
     * Default no-argument constructor required by JPA.
     * Protected access prevents direct instantiation outside of JPA/subclasses.
     */
    protected Customer() {
        // Required by JPA specification
    }

    /**
     * Parameterized constructor for creating Customer instances with all 17 business fields.
     *
     * @param custId            customer identifier (9 chars, PK)
     * @param firstName         customer first name (up to 25 chars)
     * @param middleName        customer middle name (up to 25 chars)
     * @param lastName          customer last name (up to 25 chars)
     * @param addrLine1         address line 1 (up to 50 chars)
     * @param addrLine2         address line 2 (up to 50 chars)
     * @param addrLine3         address line 3 (up to 50 chars)
     * @param addrStateCode     state code (2 chars)
     * @param addrCountryCode   country code (3 chars)
     * @param addrZip           ZIP/postal code (up to 10 chars)
     * @param phoneNum1         primary phone number (up to 15 chars)
     * @param phoneNum2         secondary phone number (up to 15 chars)
     * @param ssn               Social Security Number (9 chars) [PII]
     * @param govtIssuedId      government-issued ID (up to 20 chars) [PII]
     * @param dateOfBirth       date of birth in YYYY-MM-DD format (10 chars)
     * @param eftAccountId      EFT account identifier (up to 10 chars)
     * @param priCardHolderInd  primary card holder indicator (1 char)
     * @param ficoCreditScore   FICO credit score (integer value)
     */
    public Customer(String custId, String firstName, String middleName,
                    String lastName, String addrLine1, String addrLine2,
                    String addrLine3, String addrStateCode, String addrCountryCode,
                    String addrZip, String phoneNum1, String phoneNum2,
                    String ssn, String govtIssuedId, String dateOfBirth,
                    String eftAccountId, String priCardHolderInd,
                    Integer ficoCreditScore) {
        this.custId = custId;
        this.firstName = firstName;
        this.middleName = middleName;
        this.lastName = lastName;
        this.addrLine1 = addrLine1;
        this.addrLine2 = addrLine2;
        this.addrLine3 = addrLine3;
        this.addrStateCode = addrStateCode;
        this.addrCountryCode = addrCountryCode;
        this.addrZip = addrZip;
        this.phoneNum1 = phoneNum1;
        this.phoneNum2 = phoneNum2;
        this.ssn = ssn;
        this.govtIssuedId = govtIssuedId;
        this.dateOfBirth = dateOfBirth;
        this.eftAccountId = eftAccountId;
        this.priCardHolderInd = priCardHolderInd;
        this.ficoCreditScore = ficoCreditScore;
    }

    // =========================================================================
    // Getters and Setters
    // =========================================================================

    /**
     * Returns the customer identifier.
     * @return 9-character customer ID string (CUST-ID)
     */
    public String getCustId() {
        return custId;
    }

    /**
     * Sets the customer identifier.
     * @param custId 9-character customer ID string
     */
    public void setCustId(String custId) {
        this.custId = custId;
    }

    /**
     * Returns the customer first name.
     * @return first name string (up to 25 characters)
     */
    public String getFirstName() {
        return firstName;
    }

    /**
     * Sets the customer first name.
     * @param firstName first name string (up to 25 characters)
     */
    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    /**
     * Returns the customer middle name.
     * @return middle name string (up to 25 characters)
     */
    public String getMiddleName() {
        return middleName;
    }

    /**
     * Sets the customer middle name.
     * @param middleName middle name string (up to 25 characters)
     */
    public void setMiddleName(String middleName) {
        this.middleName = middleName;
    }

    /**
     * Returns the customer last name.
     * @return last name string (up to 25 characters)
     */
    public String getLastName() {
        return lastName;
    }

    /**
     * Sets the customer last name.
     * @param lastName last name string (up to 25 characters)
     */
    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    /**
     * Returns address line 1.
     * @return address line 1 string (up to 50 characters)
     */
    public String getAddrLine1() {
        return addrLine1;
    }

    /**
     * Sets address line 1.
     * @param addrLine1 address line 1 string (up to 50 characters)
     */
    public void setAddrLine1(String addrLine1) {
        this.addrLine1 = addrLine1;
    }

    /**
     * Returns address line 2.
     * @return address line 2 string (up to 50 characters)
     */
    public String getAddrLine2() {
        return addrLine2;
    }

    /**
     * Sets address line 2.
     * @param addrLine2 address line 2 string (up to 50 characters)
     */
    public void setAddrLine2(String addrLine2) {
        this.addrLine2 = addrLine2;
    }

    /**
     * Returns address line 3.
     * @return address line 3 string (up to 50 characters)
     */
    public String getAddrLine3() {
        return addrLine3;
    }

    /**
     * Sets address line 3.
     * @param addrLine3 address line 3 string (up to 50 characters)
     */
    public void setAddrLine3(String addrLine3) {
        this.addrLine3 = addrLine3;
    }

    /**
     * Returns the state code.
     * @return 2-character state code string
     */
    public String getAddrStateCode() {
        return addrStateCode;
    }

    /**
     * Sets the state code.
     * @param addrStateCode 2-character state code string
     */
    public void setAddrStateCode(String addrStateCode) {
        this.addrStateCode = addrStateCode;
    }

    /**
     * Returns the country code.
     * @return 3-character country code string
     */
    public String getAddrCountryCode() {
        return addrCountryCode;
    }

    /**
     * Sets the country code.
     * @param addrCountryCode 3-character country code string
     */
    public void setAddrCountryCode(String addrCountryCode) {
        this.addrCountryCode = addrCountryCode;
    }

    /**
     * Returns the address ZIP/postal code.
     * @return ZIP code string (up to 10 characters)
     */
    public String getAddrZip() {
        return addrZip;
    }

    /**
     * Sets the address ZIP/postal code.
     * @param addrZip ZIP code string (up to 10 characters)
     */
    public void setAddrZip(String addrZip) {
        this.addrZip = addrZip;
    }

    /**
     * Returns the primary phone number.
     * @return primary phone number string (up to 15 characters)
     */
    public String getPhoneNum1() {
        return phoneNum1;
    }

    /**
     * Sets the primary phone number.
     * @param phoneNum1 primary phone number string (up to 15 characters)
     */
    public void setPhoneNum1(String phoneNum1) {
        this.phoneNum1 = phoneNum1;
    }

    /**
     * Returns the secondary phone number.
     * @return secondary phone number string (up to 15 characters)
     */
    public String getPhoneNum2() {
        return phoneNum2;
    }

    /**
     * Sets the secondary phone number.
     * @param phoneNum2 secondary phone number string (up to 15 characters)
     */
    public void setPhoneNum2(String phoneNum2) {
        this.phoneNum2 = phoneNum2;
    }

    /**
     * Returns the Social Security Number.
     * <p><strong>PII: Handle with care — do not log this value.</strong></p>
     * @return 9-character SSN string
     */
    public String getSsn() {
        return ssn;
    }

    /**
     * Sets the Social Security Number.
     * <p><strong>PII: Handle with care — do not log this value.</strong></p>
     * @param ssn 9-character SSN string
     */
    public void setSsn(String ssn) {
        this.ssn = ssn;
    }

    /**
     * Returns the government-issued identification number.
     * <p><strong>PII: Handle with care — do not log this value.</strong></p>
     * @return government-issued ID string (up to 20 characters)
     */
    public String getGovtIssuedId() {
        return govtIssuedId;
    }

    /**
     * Sets the government-issued identification number.
     * <p><strong>PII: Handle with care — do not log this value.</strong></p>
     * @param govtIssuedId government-issued ID string (up to 20 characters)
     */
    public void setGovtIssuedId(String govtIssuedId) {
        this.govtIssuedId = govtIssuedId;
    }

    /**
     * Returns the date of birth in YYYY-MM-DD format.
     * @return date of birth string (10 characters)
     */
    public String getDateOfBirth() {
        return dateOfBirth;
    }

    /**
     * Sets the date of birth in YYYY-MM-DD format.
     * @param dateOfBirth date of birth string (10 characters)
     */
    public void setDateOfBirth(String dateOfBirth) {
        this.dateOfBirth = dateOfBirth;
    }

    /**
     * Returns the EFT (Electronic Funds Transfer) account identifier.
     * @return EFT account ID string (up to 10 characters)
     */
    public String getEftAccountId() {
        return eftAccountId;
    }

    /**
     * Sets the EFT (Electronic Funds Transfer) account identifier.
     * @param eftAccountId EFT account ID string (up to 10 characters)
     */
    public void setEftAccountId(String eftAccountId) {
        this.eftAccountId = eftAccountId;
    }

    /**
     * Returns the primary card holder indicator.
     * @return single-character indicator ('Y' or 'N')
     */
    public String getPriCardHolderInd() {
        return priCardHolderInd;
    }

    /**
     * Sets the primary card holder indicator.
     * @param priCardHolderInd single-character indicator ('Y' or 'N')
     */
    public void setPriCardHolderInd(String priCardHolderInd) {
        this.priCardHolderInd = priCardHolderInd;
    }

    /**
     * Returns the FICO credit score.
     * @return integer value representing the FICO score
     */
    public Integer getFicoCreditScore() {
        return ficoCreditScore;
    }

    /**
     * Sets the FICO credit score.
     * @param ficoCreditScore integer value representing the FICO score
     */
    public void setFicoCreditScore(Integer ficoCreditScore) {
        this.ficoCreditScore = ficoCreditScore;
    }

    // =========================================================================
    // equals() and hashCode() — Based on custId (VSAM KSDS primary key)
    // =========================================================================

    /**
     * Compares this Customer with another object for equality based on the
     * primary key ({@code custId}). Follows JPA entity best practices where
     * equality is determined by the natural/business key rather than object identity.
     *
     * @param o the object to compare with
     * @return true if both objects represent the same customer (same custId)
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Customer customer = (Customer) o;
        return Objects.equals(custId, customer.custId);
    }

    /**
     * Returns a hash code based on the primary key ({@code custId}).
     * Consistent with {@link #equals(Object)} — two Customer objects
     * with the same custId will have the same hash code.
     *
     * @return hash code derived from custId
     */
    @Override
    public int hashCode() {
        return Objects.hash(custId);
    }

    // =========================================================================
    // toString() — EXCLUDES PII fields (ssn, govtIssuedId) for security
    // =========================================================================

    /**
     * Returns a string representation of this Customer entity.
     *
     * <p><strong>SECURITY NOTE:</strong> PII fields ({@code ssn} and
     * {@code govtIssuedId}) are deliberately excluded from this output
     * to prevent accidental exposure in log files, error messages,
     * or debugging output.</p>
     *
     * @return string representation with non-sensitive fields only
     */
    @Override
    public String toString() {
        return "Customer{"
                + "custId='" + custId + '\''
                + ", firstName='" + firstName + '\''
                + ", middleName='" + middleName + '\''
                + ", lastName='" + lastName + '\''
                + ", addrLine1='" + addrLine1 + '\''
                + ", addrLine2='" + addrLine2 + '\''
                + ", addrLine3='" + addrLine3 + '\''
                + ", addrStateCode='" + addrStateCode + '\''
                + ", addrCountryCode='" + addrCountryCode + '\''
                + ", addrZip='" + addrZip + '\''
                + ", phoneNum1='" + phoneNum1 + '\''
                + ", phoneNum2='" + phoneNum2 + '\''
                + ", dateOfBirth='" + dateOfBirth + '\''
                + ", eftAccountId='" + eftAccountId + '\''
                + ", priCardHolderInd='" + priCardHolderInd + '\''
                + ", ficoCreditScore=" + ficoCreditScore
                + '}';
    }
}
