package com.carddemo.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.sql.Timestamp;
import java.time.LocalDate;

/**
 * Customer JPA Entity - Converted from COBOL copybook CVCUS01Y.cpy
 * 
 * Represents customer master data from CUSTOMER-RECORD (500-byte fixed COBOL record).
 * Stores customer personal information including name, address, contact details, SSN,
 * government ID, date of birth, and FICO credit score.
 * 
 * Original COBOL Structure: CUSTOMER-RECORD with RECLN 500
 * Target Database Table: customer
 * 
 * Conversion Notes:
 * - COBOL PIC 9(09) CUST-ID → Long custId (primary key)
 * - COBOL PIC X(25) name fields → String with length 25
 * - COBOL PIC X(50) address fields → String with length 50
 * - COBOL PIC 9(09) CUST-SSN → String with length 9 (preserves leading zeros)
 * - COBOL PIC X(10) CUST-DOB-YYYY-MM-DD → LocalDate (YYYY-MM-DD format)
 * - COBOL PIC 9(03) CUST-FICO-CREDIT-SCORE → Integer
 * - CUST-EFT-ACCOUNT-ID and CUST-PRI-CARD-HOLDER-IND omitted per database schema
 * - FILLER field omitted (not needed in JPA entity)
 * - Added audit fields: createdAt, updatedAt
 * - Added version field for JPA optimistic locking
 * 
 * Security Note: Contains PII data requiring masking/tokenization per security requirements
 * Referenced by: CardAccountXref entity
 * 
 * Indexes:
 * - idx_customer_ssn on custSsn for SSN lookups
 * - idx_customer_name on (custLastName, custFirstName) for name searches
 */
@Entity
@Table(name = "customer", indexes = {
    @Index(name = "idx_customer_ssn", columnList = "cust_ssn"),
    @Index(name = "idx_customer_name", columnList = "cust_last_name, cust_first_name")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Customer {

    /**
     * Customer ID - Primary Key
     * COBOL: CUST-ID PIC 9(09)
     * Database: cust_id BIGINT PRIMARY KEY
     */
    @Id
    @Column(name = "cust_id", nullable = false)
    private Long custId;

    /**
     * Customer First Name
     * COBOL: CUST-FIRST-NAME PIC X(25)
     * Database: cust_first_name VARCHAR(25) NOT NULL
     */
    @Column(name = "cust_first_name", length = 25, nullable = false)
    private String custFirstName;

    /**
     * Customer Middle Name
     * COBOL: CUST-MIDDLE-NAME PIC X(25)
     * Database: cust_middle_name VARCHAR(25)
     */
    @Column(name = "cust_middle_name", length = 25)
    private String custMiddleName;

    /**
     * Customer Last Name
     * COBOL: CUST-LAST-NAME PIC X(25)
     * Database: cust_last_name VARCHAR(25) NOT NULL
     */
    @Column(name = "cust_last_name", length = 25, nullable = false)
    private String custLastName;

    /**
     * Customer Address Line 1
     * COBOL: CUST-ADDR-LINE-1 PIC X(50)
     * Database: cust_addr_line_1 VARCHAR(50)
     */
    @Column(name = "cust_addr_line_1", length = 50)
    private String custAddrLine1;

    /**
     * Customer Address Line 2
     * COBOL: CUST-ADDR-LINE-2 PIC X(50)
     * Database: cust_addr_line_2 VARCHAR(50)
     */
    @Column(name = "cust_addr_line_2", length = 50)
    private String custAddrLine2;

    /**
     * Customer Address Line 3
     * COBOL: CUST-ADDR-LINE-3 PIC X(50)
     * Database: cust_addr_line_3 VARCHAR(50)
     */
    @Column(name = "cust_addr_line_3", length = 50)
    private String custAddrLine3;

    /**
     * Customer Address State Code (2-letter state abbreviation)
     * COBOL: CUST-ADDR-STATE-CD PIC X(02)
     * Database: cust_addr_state_cd VARCHAR(2)
     */
    @Column(name = "cust_addr_state_cd", length = 2)
    private String custAddrStateCd;

    /**
     * Customer Address Country Code (3-letter country code)
     * COBOL: CUST-ADDR-COUNTRY-CD PIC X(03)
     * Database: cust_addr_country_cd VARCHAR(3)
     */
    @Column(name = "cust_addr_country_cd", length = 3)
    private String custAddrCountryCd;

    /**
     * Customer Address ZIP/Postal Code
     * COBOL: CUST-ADDR-ZIP PIC X(10)
     * Database: cust_addr_zip VARCHAR(10)
     */
    @Column(name = "cust_addr_zip", length = 10)
    private String custAddrZip;

    /**
     * Customer Primary Phone Number
     * COBOL: CUST-PHONE-NUM-1 PIC X(15)
     * Database: cust_phone_num_1 VARCHAR(15)
     */
    @Column(name = "cust_phone_num_1", length = 15)
    private String custPhoneNum1;

    /**
     * Customer Secondary Phone Number
     * COBOL: CUST-PHONE-NUM-2 PIC X(15)
     * Database: cust_phone_num_2 VARCHAR(15)
     */
    @Column(name = "cust_phone_num_2", length = 15)
    private String custPhoneNum2;

    /**
     * Customer Social Security Number (SSN)
     * COBOL: CUST-SSN PIC 9(09)
     * Database: cust_ssn VARCHAR(9)
     * 
     * Note: Stored as String to preserve leading zeros (e.g., "001234567")
     * PII data - requires masking/tokenization per security requirements
     * Indexed via idx_customer_ssn for SSN lookups
     */
    @Column(name = "cust_ssn", length = 9)
    private String custSsn;

    /**
     * Customer Government Issued ID (e.g., Driver's License, Passport)
     * COBOL: CUST-GOVT-ISSUED-ID PIC X(20)
     * Database: cust_govt_issued_id VARCHAR(20)
     * 
     * PII data - requires masking/tokenization per security requirements
     */
    @Column(name = "cust_govt_issued_id", length = 20)
    private String custGovtIssuedId;

    /**
     * Customer Date of Birth
     * COBOL: CUST-DOB-YYYY-MM-DD PIC X(10) in YYYY-MM-DD format
     * Database: cust_dob_yyyy_mm_dd DATE
     * 
     * Converted from COBOL string date format (YYYY-MM-DD) to Java LocalDate
     * Maintains COBOL date format compatibility while enabling date arithmetic
     * PII data - requires careful handling per privacy regulations
     */
    @Column(name = "cust_dob_yyyy_mm_dd")
    private LocalDate custDobYyyyMmDd;

    /**
     * Customer FICO Credit Score (300-850 range)
     * COBOL: CUST-FICO-CREDIT-SCORE PIC 9(03)
     * Database: cust_fico_credit_score INTEGER
     * 
     * Used for credit limit determination and risk assessment
     * Typical range: 300 (poor) to 850 (excellent)
     */
    @Column(name = "cust_fico_credit_score")
    private Integer custFicoCreditScore;

    /**
     * Record Creation Timestamp
     * Database: created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
     * 
     * Automatically set when customer record is first inserted
     * Replaces COBOL CURRENT-DATE function with JPA automatic timestamp
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Timestamp createdAt;

    /**
     * Record Last Update Timestamp
     * Database: updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
     * 
     * Automatically updated when customer record is modified
     * Tracks last modification time for audit trail
     */
    @Column(name = "updated_at", nullable = false)
    private Timestamp updatedAt;

    /**
     * Version field for JPA Optimistic Locking
     * Database: version INTEGER DEFAULT 0
     * 
     * Replaces COBOL VSAM RBA (Relative Byte Address) check mechanism
     * Prevents lost updates in concurrent modification scenarios
     * Automatically incremented by JPA on each update
     */
    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    /**
     * Lifecycle callback to set timestamps before persist
     */
    @jakarta.persistence.PrePersist
    protected void onCreate() {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        this.createdAt = now;
        this.updatedAt = now;
        if (this.version == null) {
            this.version = 0;
        }
    }

    /**
     * Lifecycle callback to update timestamp before update
     */
    @jakarta.persistence.PreUpdate
    protected void onUpdate() {
        this.updatedAt = new Timestamp(System.currentTimeMillis());
    }
}
