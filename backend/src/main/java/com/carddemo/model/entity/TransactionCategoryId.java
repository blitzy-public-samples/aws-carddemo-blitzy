package com.carddemo.model.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite primary key class for TransactionCategory entity.
 * 
 * Required by JPA specification for @IdClass pattern with multi-column keys.
 * Must implement Serializable and provide equals() and hashCode() methods
 * based on all key fields.
 * 
 * Represents the composite key structure from COBOL TRAN-CAT-KEY:
 * - TRAN-TYPE-CD PIC X(02)
 * - TRAN-CAT-CD PIC 9(04)
 * 
 * Original COBOL structure from CVTRA04Y.cpy copybook:
 * <pre>
 * 01  TRAN-CAT-RECORD.
 *     05  TRAN-CAT-KEY.
 *        10  TRAN-TYPE-CD         PIC X(02).
 *        10  TRAN-CAT-CD          PIC 9(04).
 *     05  TRAN-CAT-TYPE-DESC      PIC X(50).
 * </pre>
 * 
 * This class enables JPA to properly manage entities with composite primary keys
 * and replicate the VSAM KSDS composite key access pattern from the mainframe.
 * 
 * @see TransactionCategory JPA entity using this composite key
 * @author CardDemo Conversion Team
 * @version 1.0
 * @since 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TransactionCategoryId implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Transaction type code (part 1 of composite key).
     * Must match the field name and type in TransactionCategory entity.
     * 
     * COBOL: TRAN-TYPE-CD PIC X(02)
     * Examples: "01" = Purchase, "02" = Cash Advance, "03" = Payment, "04" = Fee
     */
    private String transTypeCd;

    /**
     * Transaction category code (part 2 of composite key).
     * Must match the field name and type in TransactionCategory entity.
     * 
     * COBOL: TRAN-CAT-CD PIC 9(04)
     * Examples: 5001 = Groceries, 5002 = Dining, 5003 = Gas/Fuel
     */
    private Integer tranCatCd;

    /**
     * Equals method for composite key comparison.
     * Required by JPA specification for proper entity identity management.
     * 
     * @param o Object to compare with
     * @return true if both transTypeCd and tranCatCd match
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TransactionCategoryId that = (TransactionCategoryId) o;
        return Objects.equals(transTypeCd, that.transTypeCd) &&
               Objects.equals(tranCatCd, that.tranCatCd);
    }

    /**
     * Hash code method for composite key.
     * Required by JPA specification for proper hash-based collection usage.
     * 
     * @return Hash code based on both key fields
     */
    @Override
    public int hashCode() {
        return Objects.hash(transTypeCd, tranCatCd);
    }
}
