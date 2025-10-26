package com.carddemo.model.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

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
public class DisclosureGroupId implements Serializable {

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
