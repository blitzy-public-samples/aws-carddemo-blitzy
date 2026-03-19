package com.cardemo.common.enums;

/**
 * Enum representing the 18 transaction category codes used throughout the CardDemo application.
 *
 * <p>Faithfully translated from the fixed-width reference data file {@code app/data/ASCII/trancatg.txt}.
 * Each record in the source file is 60 characters wide:
 * <ul>
 *   <li>Positions 0–5: 6-digit category code (2-digit type prefix + 4-digit category sequence)</li>
 *   <li>Positions 6–55: description (right-padded with spaces)</li>
 *   <li>Positions 56–59: 4-digit filler ("0000")</li>
 * </ul>
 *
 * <p>The 6-digit code structure enables hierarchical categorization: the first 2 digits correspond
 * to a {@code TransactionType} code (01–07), and the last 4 digits identify the specific category
 * within that type.
 *
 * <p>COBOL lineage: TRANCATG reference data — 18 category records used by entity classes,
 * service layers, and batch processors for transaction categorization.
 */
public enum TransactionCategory {

    /** Type 01 (Purchase) — Regular Sales Draft. Code: 010001. */
    REGULAR_SALES_DRAFT("010001", "Regular Sales Draft"),

    /** Type 01 (Purchase) — Regular Cash Advance. Code: 010002. */
    REGULAR_CASH_ADVANCE("010002", "Regular Cash Advance"),

    /** Type 01 (Purchase) — Convenience Check Debit. Code: 010003. */
    CONVENIENCE_CHECK_DEBIT("010003", "Convenience Check Debit"),

    /** Type 01 (Purchase) — ATM Cash Advance. Code: 010004. */
    ATM_CASH_ADVANCE("010004", "ATM Cash Advance"),

    /** Type 01 (Purchase) — Interest Amount. Code: 010005. */
    INTEREST_AMOUNT("010005", "Interest Amount"),

    /** Type 02 (Payment) — Cash payment. Code: 020001. */
    CASH_PAYMENT("020001", "Cash payment"),

    /** Type 02 (Payment) — Electronic payment. Code: 020002. */
    ELECTRONIC_PAYMENT("020002", "Electronic payment"),

    /** Type 02 (Payment) — Check payment. Code: 020003. */
    CHECK_PAYMENT("020003", "Check payment"),

    /** Type 03 (Credit) — Credit to Account. Code: 030001. */
    CREDIT_TO_ACCOUNT("030001", "Credit to Account"),

    /** Type 03 (Credit) — Credit to Purchase balance. Code: 030002. */
    CREDIT_TO_PURCHASE_BALANCE("030002", "Credit to Purchase balance"),

    /** Type 03 (Credit) — Credit to Cash balance. Code: 030003. */
    CREDIT_TO_CASH_BALANCE("030003", "Credit to Cash balance"),

    /** Type 04 (Authorization) — Zero dollar authorization. Code: 040001. */
    ZERO_DOLLAR_AUTHORIZATION("040001", "Zero dollar authorization"),

    /** Type 04 (Authorization) — Online purchase authorization. Code: 040002. */
    ONLINE_PURCHASE_AUTHORIZATION("040002", "Online purchase authorization"),

    /** Type 04 (Authorization) — Travel booking authorization. Code: 040003. */
    TRAVEL_BOOKING_AUTHORIZATION("040003", "Travel booking authorization"),

    /** Type 05 (Refund) — Refund credit. Code: 050001. */
    REFUND_CREDIT("050001", "Refund credit"),

    /** Type 06 (Reversal) — Fraud reversal. Code: 060001. */
    FRAUD_REVERSAL("060001", "Fraud reversal"),

    /** Type 06 (Reversal) — Non-fraud reversal. Code: 060002. */
    NON_FRAUD_REVERSAL("060002", "Non-fraud reversal"),

    /** Type 07 (Adjustment) — Sales draft credit adjustment. Code: 070001. */
    SALES_DRAFT_CREDIT_ADJUSTMENT("070001", "Sales draft credit adjustment");

    /**
     * The 6-character category code. The first 2 digits correspond to the parent
     * {@code TransactionType} code, and the last 4 digits identify the specific
     * category within that type.
     */
    private final String code;

    /**
     * The human-readable description of this transaction category, preserving the
     * exact capitalization from the COBOL source data file.
     */
    private final String description;

    /**
     * Constructs a transaction category enum constant.
     *
     * @param code        the 6-character category code (type prefix + sequence)
     * @param description the human-readable description from the source data
     */
    TransactionCategory(String code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * Returns the 6-character category code.
     *
     * <p>The first 2 digits match the parent {@code TransactionType} code (01–07),
     * and the last 4 digits identify the specific category sequence within that type.
     *
     * @return the 6-character code string, never {@code null}
     */
    public String getCode() {
        return code;
    }

    /**
     * Returns the human-readable description of this transaction category.
     *
     * <p>Descriptions preserve the exact capitalization from the original COBOL
     * source data file ({@code trancatg.txt}).
     *
     * @return the description string, never {@code null}
     */
    public String getDescription() {
        return description;
    }

    /**
     * Looks up a {@code TransactionCategory} by its 6-character code.
     *
     * @param code the 6-character category code to look up (e.g., "010001")
     * @return the matching {@code TransactionCategory} constant
     * @throws IllegalArgumentException if no constant matches the given code
     */
    public static TransactionCategory fromCode(String code) {
        for (TransactionCategory category : values()) {
            if (category.code.equals(code)) {
                return category;
            }
        }
        throw new IllegalArgumentException("Unknown transaction category code: " + code);
    }

    /**
     * Returns a string representation of this transaction category in the format
     * {@code "code: description"} (e.g., {@code "010001: Regular Sales Draft"}).
     *
     * @return formatted string with code and description
     */
    @Override
    public String toString() {
        return code + ": " + description;
    }
}
