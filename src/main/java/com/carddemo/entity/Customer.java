package com.carddemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * JPA Entity representing Customer master data.
 * 
 * <p>This entity corresponds to the COBOL CUSTOMER-RECORD copybook structure
 * defined in CVCUS01Y.cpy with a 500-byte record layout. It represents customer
 * information stored in the VSAM KSDS CUSTDAT file, now migrated to PostgreSQL
 * customer table.</p>
 * 
 * <p>Key Transformations from COBOL:</p>
 * <ul>
 *   <li>CUST-ID (PIC 9(09)) → Long customerId (Primary Key)</li>
 *   <li>CUST-FIRST-NAME (PIC X(25)) → String firstName</li>
 *   <li>CUST-MIDDLE-NAME (PIC X(25)) → String middleName</li>
 *   <li>CUST-LAST-NAME (PIC X(25)) → String lastName</li>
 *   <li>CUST-ADDR-LINE-1/2/3 (PIC X(50)) → String addressLine1/2/3</li>
 *   <li>CUST-ADDR-STATE-CD (PIC X(02)) → String addressStateCode</li>
 *   <li>CUST-ADDR-COUNTRY-CD (PIC X(03)) → String addressCountryCode</li>
 *   <li>CUST-ADDR-ZIP (PIC X(10)) → String addressZip</li>
 *   <li>CUST-PHONE-NUM-1/2 (PIC X(15)) → String phoneNumber1/2</li>
 *   <li>CUST-SSN (PIC 9(09)) → String ssn (for security and leading zeros)</li>
 *   <li>CUST-GOVT-ISSUED-ID (PIC X(20)) → String governmentIssuedId</li>
 *   <li>CUST-DOB-YYYY-MM-DD (PIC X(10)) → LocalDate dateOfBirth</li>
 *   <li>CUST-EFT-ACCOUNT-ID (PIC X(10)) → String eftAccountId</li>
 *   <li>CUST-PRI-CARD-HOLDER-IND (PIC X(01)) → String primaryCardHolderIndicator</li>
 *   <li>CUST-FICO-CREDIT-SCORE (PIC 9(03)) → Integer ficoScore</li>
 * </ul>
 * 
 * <p>The entity includes optimistic locking via the @Version annotation to handle
 * concurrent access patterns that replicate VSAM record locking behavior.</p>
 * 
 * <p>Lombok annotations are used to reduce boilerplate:</p>
 * <ul>
 *   <li>@Data - generates getters, setters, equals, hashCode, toString</li>
 *   <li>@NoArgsConstructor - generates no-args constructor for JPA</li>
 *   <li>@AllArgsConstructor - generates constructor with all fields</li>
 *   <li>@Builder - generates builder pattern for object construction</li>
 * </ul>
 * 
 * @see <a href="Section 0.4">Agent Action Plan - Source File app/cpy/CVCUS01Y.cpy</a>
 * @see <a href="Section 0.6">File-by-File Transformation Plan</a>
 * @see <a href="Section 0.10">Special Instructions - Data Precision Requirements</a>
 */
@Entity
@Table(name = "customer")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Customer implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Customer ID - Primary Key
     * 
     * <p>Maps from COBOL CUST-ID field (PIC 9(09)).</p>
     * <p>9-digit numeric identifier uniquely identifying each customer.</p>
     */
    @Id
    @Column(name = "customer_id", nullable = false, precision = 9)
    private Long customerId;

    /**
     * Customer First Name
     * 
     * <p>Maps from COBOL CUST-FIRST-NAME field (PIC X(25)).</p>
     * <p>Customer's legal first name, maximum 25 characters.</p>
     */
    @Column(name = "first_name", length = 25)
    private String firstName;

    /**
     * Customer Middle Name
     * 
     * <p>Maps from COBOL CUST-MIDDLE-NAME field (PIC X(25)).</p>
     * <p>Customer's middle name or initial, maximum 25 characters.</p>
     */
    @Column(name = "middle_name", length = 25)
    private String middleName;

    /**
     * Customer Last Name
     * 
     * <p>Maps from COBOL CUST-LAST-NAME field (PIC X(25)).</p>
     * <p>Customer's legal last name, maximum 25 characters.</p>
     */
    @Column(name = "last_name", length = 25)
    private String lastName;

    /**
     * Address Line 1
     * 
     * <p>Maps from COBOL CUST-ADDR-LINE-1 field (PIC X(50)).</p>
     * <p>Primary address line (street number and name), maximum 50 characters.</p>
     */
    @Column(name = "address_line_1", length = 50)
    private String addressLine1;

    /**
     * Address Line 2
     * 
     * <p>Maps from COBOL CUST-ADDR-LINE-2 field (PIC X(50)).</p>
     * <p>Secondary address line (apartment, suite, etc.), maximum 50 characters.</p>
     */
    @Column(name = "address_line_2", length = 50)
    private String addressLine2;

    /**
     * Address Line 3
     * 
     * <p>Maps from COBOL CUST-ADDR-LINE-3 field (PIC X(50)).</p>
     * <p>Tertiary address line (city, additional info), maximum 50 characters.</p>
     */
    @Column(name = "address_line_3", length = 50)
    private String addressLine3;

    /**
     * State Code
     * 
     * <p>Maps from COBOL CUST-ADDR-STATE-CD field (PIC X(02)).</p>
     * <p>Two-character US state code (e.g., "CA", "NY", "TX").</p>
     */
    @Column(name = "address_state_code", length = 2)
    private String addressStateCode;

    /**
     * Country Code
     * 
     * <p>Maps from COBOL CUST-ADDR-COUNTRY-CD field (PIC X(03)).</p>
     * <p>Three-character ISO country code (e.g., "USA", "CAN", "MEX").</p>
     */
    @Column(name = "address_country_code", length = 3)
    private String addressCountryCode;

    /**
     * ZIP Code
     * 
     * <p>Maps from COBOL CUST-ADDR-ZIP field (PIC X(10)).</p>
     * <p>Postal/ZIP code, maximum 10 characters (supports ZIP+4 format).</p>
     */
    @Column(name = "address_zip", length = 10)
    private String addressZip;

    /**
     * Primary Phone Number
     * 
     * <p>Maps from COBOL CUST-PHONE-NUM-1 field (PIC X(15)).</p>
     * <p>Customer's primary contact phone number, maximum 15 characters.</p>
     */
    @Column(name = "phone_number_1", length = 15)
    private String phoneNumber1;

    /**
     * Secondary Phone Number
     * 
     * <p>Maps from COBOL CUST-PHONE-NUM-2 field (PIC X(15)).</p>
     * <p>Customer's alternate contact phone number, maximum 15 characters.</p>
     */
    @Column(name = "phone_number_2", length = 15)
    private String phoneNumber2;

    /**
     * Social Security Number
     * 
     * <p>Maps from COBOL CUST-SSN field (PIC 9(09)).</p>
     * <p>Customer's SSN stored as String to preserve leading zeros and for security.
     * Should be encrypted at rest in production systems. Format: 9 digits.</p>
     * 
     * <p><strong>Security Note:</strong> This field contains sensitive PII and must be
     * handled according to regulatory requirements (PCI DSS, SOX).</p>
     */
    @Column(name = "ssn", length = 9)
    private String ssn;

    /**
     * Government Issued ID
     * 
     * <p>Maps from COBOL CUST-GOVT-ISSUED-ID field (PIC X(20)).</p>
     * <p>Alternative government-issued identification number (driver's license,
     * passport, etc.), maximum 20 characters.</p>
     */
    @Column(name = "government_issued_id", length = 20)
    private String governmentIssuedId;

    /**
     * Date of Birth
     * 
     * <p>Maps from COBOL CUST-DOB-YYYY-MM-DD field (PIC X(10)).</p>
     * <p>Customer's date of birth in ISO 8601 format (YYYY-MM-DD).
     * Converted from COBOL character format to Java LocalDate for proper
     * date arithmetic and validation per Section 0.10 Requirement 8.</p>
     * 
     * <p>Used for age verification, regulatory compliance, and credit decisioning.</p>
     */
    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    /**
     * EFT Account ID
     * 
     * <p>Maps from COBOL CUST-EFT-ACCOUNT-ID field (PIC X(10)).</p>
     * <p>Electronic Funds Transfer account identifier for payment processing,
     * maximum 10 characters.</p>
     */
    @Column(name = "eft_account_id", length = 10)
    private String eftAccountId;

    /**
     * Primary Card Holder Indicator
     * 
     * <p>Maps from COBOL CUST-PRI-CARD-HOLDER-IND field (PIC X(01)).</p>
     * <p>Single character flag indicating if this customer is the primary
     * card holder. Typical values: 'Y' = Yes (primary), 'N' = No (secondary).</p>
     */
    @Column(name = "primary_card_holder_indicator", length = 1)
    private String primaryCardHolderIndicator;

    /**
     * FICO Credit Score
     * 
     * <p>Maps from COBOL CUST-FICO-CREDIT-SCORE field (PIC 9(03)).</p>
     * <p>Customer's FICO credit score (300-850 range). Used for credit
     * decisioning, limit calculations, and risk assessment.</p>
     * 
     * <p>Valid range: 300-850 (standard FICO score range).</p>
     */
    @Column(name = "fico_score", precision = 3)
    private Integer ficoScore;

    /**
     * Version field for optimistic locking
     * 
     * <p>Enables JPA optimistic locking to handle concurrent updates.
     * Automatically incremented by JPA on each update operation.</p>
     * 
     * <p>Replicates VSAM record locking behavior in PostgreSQL using
     * version-based concurrency control per Section 0.10 Requirement 10.</p>
     */
    @Version
    @Column(name = "version")
    private Long version;
}
