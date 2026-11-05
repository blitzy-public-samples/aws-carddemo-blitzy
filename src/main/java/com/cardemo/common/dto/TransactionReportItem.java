package com.cardemo.common.dto;

import jakarta.validation.constraints.Size;
import java.util.Objects;

/**
 * Transaction category record DTO — translated from CVTRA04Y.cpy (TRAN-CAT-RECORD, RECLN 60).
 *
 * <p>Represents a transaction category reference record used for categorizing transactions.
 * The COBOL copybook defines a 60-byte fixed-width record with a composite key consisting
 * of the transaction type code ({@code TRAN-TYPE-CD}, PIC X(02)) and the transaction
 * category code ({@code TRAN-CAT-CD}, PIC 9(04)). The trailing 4-byte FILLER is not mapped.</p>
 *
 * <pre>
 * COBOL source (CVTRA04Y.cpy):
 *   01  TRAN-CAT-RECORD.
 *       05  TRAN-CAT-KEY.
 *          10  TRAN-TYPE-CD              PIC X(02).
 *          10  TRAN-CAT-CD              PIC 9(04).
 *       05  TRAN-CAT-TYPE-DESC          PIC X(50).
 *       05  FILLER                      PIC X(04).
 * </pre>
 *
 * @see com.cardemo.entity.TransactionCategoryRef
 */
public class TransactionReportItem {

    /**
     * Transaction type code — maps to TRAN-TYPE-CD PIC X(02).
     * Part of the composite key (tranTypeCd + tranCatCd).
     */
    @Size(max = 2)
    private String tranTypeCd;

    /**
     * Transaction category code — maps to TRAN-CAT-CD PIC 9(04).
     * Part of the composite key (tranTypeCd + tranCatCd).
     * Stored as an int since the COBOL definition is PIC 9(04) (unsigned numeric display, 4 digits).
     */
    private int tranCatCd;

    /**
     * Transaction category type description — maps to TRAN-CAT-TYPE-DESC PIC X(50).
     * Human-readable label describing the transaction category.
     */
    @Size(max = 50)
    private String tranCatTypeDesc;

    /**
     * Default no-arg constructor required for frameworks (JPA, Jackson, Spring Batch).
     */
    public TransactionReportItem() {
        // Default constructor — all fields initialized to Java defaults
    }

    /**
     * All-args constructor for programmatic construction.
     *
     * @param tranTypeCd     transaction type code (max 2 characters)
     * @param tranCatCd      transaction category code (4-digit numeric)
     * @param tranCatTypeDesc transaction category type description (max 50 characters)
     */
    public TransactionReportItem(String tranTypeCd, int tranCatCd, String tranCatTypeDesc) {
        this.tranTypeCd = tranTypeCd;
        this.tranCatCd = tranCatCd;
        this.tranCatTypeDesc = tranCatTypeDesc;
    }

    /**
     * Returns the transaction type code (TRAN-TYPE-CD, PIC X(02)).
     *
     * @return transaction type code, up to 2 characters
     */
    public String getTranTypeCd() {
        return tranTypeCd;
    }

    /**
     * Sets the transaction type code (TRAN-TYPE-CD, PIC X(02)).
     *
     * @param tranTypeCd transaction type code, up to 2 characters
     */
    public void setTranTypeCd(String tranTypeCd) {
        this.tranTypeCd = tranTypeCd;
    }

    /**
     * Returns the transaction category code (TRAN-CAT-CD, PIC 9(04)).
     *
     * @return transaction category code as a 4-digit integer
     */
    public int getTranCatCd() {
        return tranCatCd;
    }

    /**
     * Sets the transaction category code (TRAN-CAT-CD, PIC 9(04)).
     *
     * @param tranCatCd transaction category code as a 4-digit integer
     */
    public void setTranCatCd(int tranCatCd) {
        this.tranCatCd = tranCatCd;
    }

    /**
     * Returns the transaction category type description (TRAN-CAT-TYPE-DESC, PIC X(50)).
     *
     * @return category description, up to 50 characters
     */
    public String getTranCatTypeDesc() {
        return tranCatTypeDesc;
    }

    /**
     * Sets the transaction category type description (TRAN-CAT-TYPE-DESC, PIC X(50)).
     *
     * @param tranCatTypeDesc category description, up to 50 characters
     */
    public void setTranCatTypeDesc(String tranCatTypeDesc) {
        this.tranCatTypeDesc = tranCatTypeDesc;
    }

    /**
     * Returns a string representation containing all three fields for debugging and logging.
     *
     * @return formatted string with tranTypeCd, tranCatCd, and tranCatTypeDesc
     */
    @Override
    public String toString() {
        return "TransactionReportItem{"
                + "tranTypeCd='" + tranTypeCd + '\''
                + ", tranCatCd=" + tranCatCd
                + ", tranCatTypeDesc='" + tranCatTypeDesc + '\''
                + '}';
    }

    /**
     * Compares this item with another using the composite key (tranTypeCd + tranCatCd),
     * matching the COBOL TRAN-CAT-KEY group structure.
     *
     * @param o the reference object with which to compare
     * @return {@code true} if both objects have the same composite key
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TransactionReportItem that)) {
            return false;
        }
        return tranCatCd == that.tranCatCd
                && Objects.equals(tranTypeCd, that.tranTypeCd);
    }

    /**
     * Returns a hash code based on the composite key (tranTypeCd + tranCatCd),
     * consistent with {@link #equals(Object)}.
     *
     * @return hash code derived from the composite key fields
     */
    @Override
    public int hashCode() {
        return Objects.hash(tranTypeCd, tranCatCd);
    }
}
