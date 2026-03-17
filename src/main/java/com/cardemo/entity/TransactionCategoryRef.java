package com.cardemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.Objects;

/**
 * JPA entity representing a transaction category reference record.
 *
 * <p>Migrated from the VSAM TRANCATG reference dataset containing 18 transaction
 * category records parsed from {@code app/data/ASCII/trancatg.txt}. Each record
 * maps a 4-byte category code (within a 6-digit composite that includes a 2-byte
 * transaction type prefix) to a human-readable description.</p>
 *
 * <h3>Source Data Format (trancatg.txt, 18 records):</h3>
 * <pre>
 * Positions [0:2]   = 2-digit type code prefix (e.g., "01" through "07")
 * Positions [2:6]   = 4-digit category code   (e.g., "0001", "0002")
 * Positions [6:56]  = 50-char description      (right-padded with spaces)
 * Positions [56:60] = filler "0000"
 * </pre>
 *
 * <p>The 6-digit composite code in the source data is decomposed into two fields:
 * {@code typeCode} (2-char prefix linking to {@code TransactionTypeRef}) and
 * {@code categoryCode} (4-char category identifier used as the primary key).
 * The full 6-digit code can be reconstructed as {@code typeCode + categoryCode}.</p>
 *
 * <h3>Reference Data Categories (18 records across 7 types):</h3>
 * <ul>
 *   <li>Type 01 (Purchase): 5 categories — Sales Draft, Cash Advance, Check Debit, ATM, Interest</li>
 *   <li>Type 02 (Payment): 3 categories — Cash, Electronic, Check</li>
 *   <li>Type 03 (Credit): 3 categories — Account Credit, Purchase Balance, Cash Balance</li>
 *   <li>Type 04 (Authorization): 3 categories — Zero Dollar, Online Purchase, Travel Booking</li>
 *   <li>Type 05 (Refund): 1 category — Refund Credit</li>
 *   <li>Type 06 (Reversal): 2 categories — Fraud Reversal, Non-Fraud Reversal</li>
 *   <li>Type 07 (Adjustment): 1 category — Sales Draft Credit Adjustment</li>
 * </ul>
 *
 * <p>Per the AAP: "4-byte category code PK". The entity stores the type code
 * separately for referential grouping with the {@code TransactionTypeRef} entity.</p>
 *
 * @see <a href="app/data/ASCII/trancatg.txt">trancatg.txt — 18 transaction category records</a>
 */
@Entity
@Table(name = "transaction_category_ref")
public class TransactionCategoryRef {

    /**
     * Transaction category code — primary key.
     * Derived from positions [2:6] of each trancatg.txt record.
     * This is a 4-character code (e.g., "0001", "0002") that identifies
     * the specific transaction category within its parent type group.
     */
    @Id
    @Column(name = "category_code", length = 4, nullable = false)
    private String categoryCode;

    /**
     * Transaction type code prefix.
     * Derived from positions [0:2] of each trancatg.txt record.
     * A 2-character code (e.g., "01" through "07") that links this category
     * to a parent transaction type in the {@code TransactionTypeRef} entity.
     * Together with {@code categoryCode}, forms the full 6-digit composite
     * identifier from the source data.
     */
    @Column(name = "type_code", length = 2, nullable = false)
    private String typeCode;

    /**
     * Human-readable description of the transaction category.
     * Derived from positions [6:56] of each trancatg.txt record (trimmed).
     * Examples: "Regular Sales Draft", "Cash payment", "Fraud reversal".
     */
    @Column(name = "category_description", length = 50, nullable = false)
    private String categoryDescription;

    /**
     * Default no-argument constructor required by JPA.
     * Protected access to discourage direct instantiation outside of
     * the persistence framework.
     */
    protected TransactionCategoryRef() {
        // Required by JPA specification
    }

    /**
     * Constructs a new TransactionCategoryRef with all fields populated.
     *
     * @param typeCode            the 2-character type code prefix (positions [0:2])
     * @param categoryCode        the 4-character category code PK (positions [2:6])
     * @param categoryDescription the trimmed category description (positions [6:56])
     */
    public TransactionCategoryRef(String typeCode, String categoryCode,
                                  String categoryDescription) {
        this.typeCode = typeCode;
        this.categoryCode = categoryCode;
        this.categoryDescription = categoryDescription;
    }

    /**
     * Returns the transaction category code (primary key).
     *
     * @return the 4-character category code (e.g., "0001")
     */
    public String getCategoryCode() {
        return categoryCode;
    }

    /**
     * Sets the transaction category code (primary key).
     *
     * @param categoryCode the 4-character category code
     */
    public void setCategoryCode(String categoryCode) {
        this.categoryCode = categoryCode;
    }

    /**
     * Returns the transaction type code prefix.
     *
     * @return the 2-character type code (e.g., "01" through "07")
     */
    public String getTypeCode() {
        return typeCode;
    }

    /**
     * Sets the transaction type code prefix.
     *
     * @param typeCode the 2-character type code
     */
    public void setTypeCode(String typeCode) {
        this.typeCode = typeCode;
    }

    /**
     * Returns the human-readable description of the transaction category.
     *
     * @return the category description (e.g., "Regular Sales Draft")
     */
    public String getCategoryDescription() {
        return categoryDescription;
    }

    /**
     * Sets the human-readable description of the transaction category.
     *
     * @param categoryDescription the category description
     */
    public void setCategoryDescription(String categoryDescription) {
        this.categoryDescription = categoryDescription;
    }

    /**
     * Compares this TransactionCategoryRef with another object for equality.
     * Equality is determined solely by the primary key field {@code categoryCode},
     * consistent with JPA entity identity semantics and the VSAM reference
     * dataset primary key definition ("4-byte category code PK").
     *
     * @param o the object to compare with
     * @return {@code true} if the other object is a TransactionCategoryRef
     *         with the same category code
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TransactionCategoryRef other)) {
            return false;
        }
        return Objects.equals(categoryCode, other.categoryCode);
    }

    /**
     * Returns the hash code based on the primary key field {@code categoryCode}.
     *
     * @return hash code computed from the category code
     */
    @Override
    public int hashCode() {
        return Objects.hash(categoryCode);
    }

    /**
     * Returns a string representation of this TransactionCategoryRef record,
     * including all mapped fields from the original TRANCATG reference dataset.
     *
     * @return string representation with type code, category code, and description
     */
    @Override
    public String toString() {
        return "TransactionCategoryRef{"
                + "categoryCode='" + categoryCode + '\''
                + ", typeCode='" + typeCode + '\''
                + ", categoryDescription='" + categoryDescription + '\''
                + '}';
    }
}
