package com.carddemo.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.sql.Timestamp;

/**
 * Converted from COBOL copybook: CVTRA02Y.cpy (DIS-GROUP-RECORD)
 * Original function: Disclosure group reference data with interest rates
 * Record length: 50 bytes
 * 
 * JPA entity representing disclosure group configuration for interest rates.
 * Defines interest rate settings per account group, transaction type, and 
 * transaction category combination. This is a reference table with no foreign 
 * key dependencies.
 * 
 * Conversion notes:
 * - COBOL DIS-GROUP-KEY (composite key of 3 fields) converted to @IdClass pattern
 * - PIC X(10) DIS-ACCT-GROUP-ID → String discAcctGroupId with @Column(length=10)
 * - PIC X(02) DIS-TRAN-TYPE-CD → String discTranTypeCd with @Column(length=2)
 * - PIC 9(04) DIS-TRAN-CAT-CD → Integer discTranCatCd
 * - PIC S9(04)V99 COMP-3 DIS-INT-RATE → BigDecimal discIntRate with precision=6, scale=2
 *   to preserve COBOL packed decimal precision per Section 0.7.2 requirement
 * - FILLER field (28 bytes) omitted from entity
 * - Added createdAt and updatedAt audit fields
 * - Added version field for JPA optimistic locking
 * 
 * Database table: disclosure_group
 * Primary key: Composite key (discAcctGroupId, discTranTypeCd, discTranCatCd)
 * 
 * @see DisclosureGroupId Composite primary key class
 */
@Data
@Entity
@Table(name = "disclosure_group")
@IdClass(DisclosureGroupId.class)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DisclosureGroup {

    /**
     * Account group identifier (part 1 of composite key).
     * Maps to COBOL: DIS-ACCT-GROUP-ID PIC X(10)
     * 
     * Identifies the account group for which this interest rate applies.
     * Maximum length: 10 characters
     */
    @Id
    @Column(name = "disc_acct_group_id", length = 10, nullable = false)
    private String discAcctGroupId;

    /**
     * Transaction type code (part 2 of composite key).
     * Maps to COBOL: DIS-TRAN-TYPE-CD PIC X(02)
     * 
     * Identifies the transaction type for which this interest rate applies.
     * Maximum length: 2 characters
     */
    @Id
    @Column(name = "disc_tran_type_cd", length = 2, nullable = false)
    private String discTranTypeCd;

    /**
     * Transaction category code (part 3 of composite key).
     * Maps to COBOL: DIS-TRAN-CAT-CD PIC 9(04)
     * 
     * Identifies the transaction category for which this interest rate applies.
     * Range: 0-9999 (4 digits)
     */
    @Id
    @Column(name = "disc_tran_cat_cd", nullable = false)
    private Integer discTranCatCd;

    /**
     * Interest rate for this account group/transaction type/category combination.
     * Maps to COBOL: DIS-INT-RATE PIC S9(04)V99 COMP-3
     * 
     * Stored as BigDecimal with precision 6 and scale 2 to maintain exact COBOL 
     * COMP-3 packed decimal precision. This ensures bit-identical financial 
     * calculations per Section 0.7.2 requirement for maintaining exact numeric 
     * precision and rounding behavior from mainframe.
     * 
     * Format: 9999.99 (signed, up to 4 digits before decimal, 2 digits after)
     * Example: 1250.50 represents 12.505% interest rate
     */
    @Column(name = "disc_int_rate", precision = 6, scale = 2, nullable = false)
    private BigDecimal discIntRate;

    /**
     * Timestamp when this disclosure group record was created.
     * Audit field for tracking record creation time.
     * 
     * Automatically set to current timestamp on record creation per 
     * Section 0.3.4 database schema design.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Timestamp createdAt;

    /**
     * Timestamp when this disclosure group record was last updated.
     * Audit field for tracking record modification time.
     * 
     * Automatically updated to current timestamp on each record update per 
     * Section 0.3.4 database schema design.
     */
    @Column(name = "updated_at", nullable = false)
    private Timestamp updatedAt;

    /**
     * Version number for optimistic locking.
     * 
     * JPA annotation for optimistic locking to prevent concurrent update conflicts 
     * on disclosure group records. Version field automatically incremented on each 
     * update, replicating COBOL VSAM RBA optimistic locking semantics from mainframe 
     * CICS transaction processing per Section 0.4.4 transformation rules.
     * 
     * This prevents lost updates when multiple users attempt to modify the same 
     * disclosure group record concurrently.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Integer version;
}

/**
 * Composite primary key class for DisclosureGroup entity.
 * 
 * Represents the three-part composite key from COBOL DIS-GROUP-KEY:
 * - DIS-ACCT-GROUP-ID (account group identifier)
 * - DIS-TRAN-TYPE-CD (transaction type code)
 * - DIS-TRAN-CAT-CD (transaction category code)
 * 
 * Implements Serializable as required by JPA specification for @IdClass pattern 
 * with multi-column keys. Enables the composite key to be serialized for caching, 
 * session storage, and distributed processing scenarios.
 * 
 * Must implement equals() and hashCode() for proper JPA entity management and 
 * collection operations.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
class DisclosureGroupId implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Account group identifier (part 1 of composite key).
     * Maps to COBOL: DIS-ACCT-GROUP-ID PIC X(10)
     */
    private String discAcctGroupId;

    /**
     * Transaction type code (part 2 of composite key).
     * Maps to COBOL: DIS-TRAN-TYPE-CD PIC X(02)
     */
    private String discTranTypeCd;

    /**
     * Transaction category code (part 3 of composite key).
     * Maps to COBOL: DIS-TRAN-CAT-CD PIC 9(04)
     */
    private Integer discTranCatCd;
}
