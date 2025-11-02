package com.carddemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * JPA Entity representing the Customer master data table.
 * 
 * <p>This entity is transformed from COBOL copybook CVCUS01Y.cpy (CUSTOMER-RECORD)
 * which defines a 500-byte record structure for customer information stored in the
 * VSAM CUSTDAT KSDS file. This is the root entity in the CardDemo data model with
 * no parent foreign key dependencies.</p>
 * 
 * <p><strong>Data Security Notice:</strong> This entity contains Personally Identifiable
 * Information (PII) including Social Security Number (SSN) and Date of Birth (DOB).
 * These fields require:</p>
 * <ul>
 *   <li>Encryption at rest in the database</li>
 *   <li>Masking in application logs and toString() output</li>
 *   <li>Audit trail logging for all access and modifications</li>
 *   <li>Compliance with GDPR, PCI-DSS, and regulatory requirements</li>
 * </ul>
 * 
 * <p><strong>Entity Relationships:</strong></p>
 * <ul>
 *   <li>Parent entity for Account (one-to-many relationship)</li>
 *   <li>9-digit customer ID serves as primary key</li>
 * </ul>
 * 
 * <p><strong>COBOL Source:</strong> app/cpy/CVCUS01Y.cpy</p>
 * <p><strong>VSAM File:</strong> CUSTDAT KSDS (Key-Sequenced Dataset)</p>
 * <p><strong>Record Length:</strong> 500 bytes</p>
 * 
 * @see <a href="Section 0.6">File-by-File Transformation Plan</a>
 * @see <a href="Section 0.3">COBOL to Java Type Conversion Rules</a>
 */
@Entity
@Table(name = "customer")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Customer implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Customer unique identifier (9-digit numeric).
     * Maps to COBOL field: CUST-ID PIC 9(09)
     * Primary key for Customer entity.
     */
    @Id
    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    /**
     * Customer first name (up to 25 characters).
     * Maps to COBOL field: CUST-FIRST-NAME PIC X(25)
     */
    @Column(name = "first_name", length = 25, nullable = false)
    private String firstName;

    /**
     * Customer middle name (up to 25 characters).
     * Maps to COBOL field: CUST-MIDDLE-NAME PIC X(25)
     */
    @Column(name = "middle_name", length = 25)
    private String middleName;

    /**
     * Customer last name (up to 25 characters).
     * Maps to COBOL field: CUST-LAST-NAME PIC X(25)
     */
    @Column(name = "last_name", length = 25, nullable = false)
    private String lastName;

    /**
     * Customer address line 1 (up to 50 characters).
     * Maps to COBOL field: CUST-ADDR-LINE-1 PIC X(50)
     */
    @Column(name = "address_line_1", length = 50)
    private String addressLine1;

    /**
     * Customer address line 2 (up to 50 characters).
     * Maps to COBOL field: CUST-ADDR-LINE-2 PIC X(50)
     */
    @Column(name = "address_line_2", length = 50)
    private String addressLine2;

    /**
     * Customer address line 3 (up to 50 characters).
     * Maps to COBOL field: CUST-ADDR-LINE-3 PIC X(50)
     */
    @Column(name = "address_line_3", length = 50)
    private String addressLine3;

    /**
     * Customer address state code (2 characters).
     * Maps to COBOL field: CUST-ADDR-STATE-CD PIC X(02)
     */
    @Column(name = "state_code", length = 2)
    private String stateCode;

    /**
     * Customer address country code (3 characters).
     * Maps to COBOL field: CUST-ADDR-COUNTRY-CD PIC X(03)
     */
    @Column(name = "country_code", length = 3)
    private String countryCode;

    /**
     * Customer address ZIP code (up to 10 characters).
     * Maps to COBOL field: CUST-ADDR-ZIP PIC X(10)
     */
    @Column(name = "zip_code", length = 10)
    private String zipCode;

    /**
     * Customer primary phone number (up to 15 characters).
     * Maps to COBOL field: CUST-PHONE-NUM-1 PIC X(15)
     */
    @Column(name = "phone_number_1", length = 15)
    private String phoneNumber1;

    /**
     * Customer secondary phone number (up to 15 characters).
     * Maps to COBOL field: CUST-PHONE-NUM-2 PIC X(15)
     */
    @Column(name = "phone_number_2", length = 15)
    private String phoneNumber2;

    /**
     * Customer Social Security Number (9 digits).
     * Maps to COBOL field: CUST-SSN PIC 9(09)
     * 
     * <p><strong>WARNING: Contains PII (Personally Identifiable Information)</strong></p>
     * <p>This field must be:</p>
     * <ul>
     *   <li>Encrypted at rest in the database</li>
     *   <li>Masked in all log outputs (use "***-**-XXXX" format)</li>
     *   <li>Audited for all access and modifications</li>
     *   <li>Protected under PCI-DSS and GDPR regulations</li>
     * </ul>
     */
    @Column(name = "ssn", length = 9)
    private String ssn;

    /**
     * Customer government-issued ID (up to 20 characters).
     * Maps to COBOL field: CUST-GOVT-ISSUED-ID PIC X(20)
     * Examples: Driver's license, passport number, national ID
     */
    @Column(name = "government_issued_id", length = 20)
    private String governmentIssuedId;

    /**
     * Customer date of birth.
     * Maps to COBOL field: CUST-DOB-YYYY-MM-DD PIC X(10)
     * 
     * <p><strong>WARNING: Contains PII (Personally Identifiable Information)</strong></p>
     * <p>Original COBOL format: YYYY-MM-DD string</p>
     * <p>Converted to LocalDate for type-safe date handling with ISO-8601 formatting.</p>
     * <p>This field must be masked in logs and audit trails per data security requirements.</p>
     */
    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    /**
     * Customer Electronic Funds Transfer (EFT) account ID (up to 10 characters).
     * Maps to COBOL field: CUST-EFT-ACCOUNT-ID PIC X(10)
     */
    @Column(name = "eft_account_id", length = 10)
    private String eftAccountId;

    /**
     * Primary card holder indicator (1 character).
     * Maps to COBOL field: CUST-PRI-CARD-HOLDER-IND PIC X(01)
     * Typical values: 'Y' (Yes), 'N' (No)
     */
    @Column(name = "primary_card_holder_indicator", length = 1)
    private String primaryCardHolderIndicator;

    /**
     * Customer FICO credit score (3-digit integer, range 300-850).
     * Maps to COBOL field: CUST-FICO-CREDIT-SCORE PIC 9(03)
     */
    @Column(name = "fico_credit_score")
    private Integer ficoCreditScore;

    /**
     * Custom toString implementation that masks PII fields (SSN and Date of Birth)
     * to prevent accidental exposure in logs, debugging output, or error messages.
     * 
     * <p>Masking patterns:</p>
     * <ul>
     *   <li>SSN: Displays as "***-**-XXXX" (only last 4 digits visible)</li>
     *   <li>Date of Birth: Displays as "XXXX-XX-XX" (completely masked)</li>
     * </ul>
     * 
     * @return String representation of Customer with PII fields masked
     */
    @Override
    public String toString() {
        return "Customer{" +
                "customerId=" + customerId +
                ", firstName='" + firstName + '\'' +
                ", middleName='" + middleName + '\'' +
                ", lastName='" + lastName + '\'' +
                ", addressLine1='" + addressLine1 + '\'' +
                ", addressLine2='" + addressLine2 + '\'' +
                ", addressLine3='" + addressLine3 + '\'' +
                ", stateCode='" + stateCode + '\'' +
                ", countryCode='" + countryCode + '\'' +
                ", zipCode='" + zipCode + '\'' +
                ", phoneNumber1='" + phoneNumber1 + '\'' +
                ", phoneNumber2='" + phoneNumber2 + '\'' +
                ", ssn='" + maskSsn(ssn) + '\'' +
                ", governmentIssuedId='" + governmentIssuedId + '\'' +
                ", dateOfBirth=" + maskDateOfBirth(dateOfBirth) +
                ", eftAccountId='" + eftAccountId + '\'' +
                ", primaryCardHolderIndicator='" + primaryCardHolderIndicator + '\'' +
                ", ficoCreditScore=" + ficoCreditScore +
                '}';
    }

    /**
     * Masks SSN to protect PII in logs and output.
     * Shows only the last 4 digits in format: ***-**-XXXX
     * 
     * @param ssn The Social Security Number to mask
     * @return Masked SSN string, or "***-**-****" if null/invalid
     */
    private String maskSsn(String ssn) {
        if (ssn == null || ssn.length() != 9) {
            return "***-**-****";
        }
        return "***-**-" + ssn.substring(5);
    }

    /**
     * Masks date of birth to protect PII in logs and output.
     * Returns completely masked date: XXXX-XX-XX
     * 
     * @param dob The date of birth to mask
     * @return Masked date string "XXXX-XX-XX", or null if dob is null
     */
    private String maskDateOfBirth(LocalDate dob) {
        if (dob == null) {
            return null;
        }
        return "XXXX-XX-XX";
    }
}
