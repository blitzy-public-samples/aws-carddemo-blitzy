package com.cardemo.common.dto;

import jakarta.validation.constraints.Size;
import java.util.Objects;

/**
 * Transaction type record DTO — translated from CVTRA03Y.cpy (TRAN-TYPE-RECORD, RECLN 60).
 *
 * <p>Faithfully maps the COBOL copybook {@code app/cpy/CVTRA03Y.cpy} structure:
 * <pre>
 * 01  TRAN-TYPE-RECORD.
 *     05  TRAN-TYPE          PIC X(02).   → tranType   (2-char code)
 *     05  TRAN-TYPE-DESC     PIC X(50).   → tranTypeDesc (description)
 *     05  FILLER             PIC X(08).   → not mapped
 * </pre>
 *
 * <p>The FILLER field (8 bytes of padding to reach RECLN 60) is intentionally
 * omitted from the Java representation — it carried no business semantics in
 * the original COBOL record layout.
 *
 * <p>Identity is based on {@code tranType} (the 2-character transaction type
 * code), which serves as the primary key in the original VSAM dataset.
 *
 * @see com.cardemo.entity.TransactionTypeRef
 */
public class TransactionListItem {

    /**
     * 2-character transaction type code.
     * Maps to COBOL field {@code TRAN-TYPE PIC X(02)}.
     */
    @Size(max = 2)
    private String tranType;

    /**
     * Human-readable description of the transaction type.
     * Maps to COBOL field {@code TRAN-TYPE-DESC PIC X(50)}.
     */
    @Size(max = 50)
    private String tranTypeDesc;

    /**
     * Default no-arg constructor required for frameworks (JPA, Jackson, Spring).
     */
    public TransactionListItem() {
        // No-arg constructor for framework compatibility
    }

    /**
     * All-args constructor for programmatic construction.
     *
     * @param tranType     2-character transaction type code (max length 2)
     * @param tranTypeDesc human-readable type description (max length 50)
     */
    public TransactionListItem(String tranType, String tranTypeDesc) {
        this.tranType = tranType;
        this.tranTypeDesc = tranTypeDesc;
    }

    /**
     * Returns the 2-character transaction type code.
     *
     * @return transaction type code, or {@code null} if not set
     */
    public String getTranType() {
        return tranType;
    }

    /**
     * Sets the 2-character transaction type code.
     *
     * @param tranType transaction type code (max length 2)
     */
    public void setTranType(String tranType) {
        this.tranType = tranType;
    }

    /**
     * Returns the human-readable description of the transaction type.
     *
     * @return transaction type description, or {@code null} if not set
     */
    public String getTranTypeDesc() {
        return tranTypeDesc;
    }

    /**
     * Sets the human-readable description of the transaction type.
     *
     * @param tranTypeDesc transaction type description (max length 50)
     */
    public void setTranTypeDesc(String tranTypeDesc) {
        this.tranTypeDesc = tranTypeDesc;
    }

    /**
     * Returns a string representation including both fields.
     *
     * @return formatted string with tranType and tranTypeDesc
     */
    @Override
    public String toString() {
        return "TransactionListItem{" +
                "tranType='" + tranType + '\'' +
                ", tranTypeDesc='" + tranTypeDesc + '\'' +
                '}';
    }

    /**
     * Compares for equality based on {@code tranType} (the primary key).
     * Two {@code TransactionListItem} instances are equal if and only if
     * their {@code tranType} fields are equal.
     *
     * @param o the object to compare with
     * @return {@code true} if the other object is a {@code TransactionListItem}
     *         with the same {@code tranType}
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TransactionListItem that)) {
            return false;
        }
        return Objects.equals(tranType, that.tranType);
    }

    /**
     * Computes hash code based on {@code tranType} (the primary key).
     *
     * @return hash code derived from {@code tranType}
     */
    @Override
    public int hashCode() {
        return Objects.hash(tranType);
    }
}
