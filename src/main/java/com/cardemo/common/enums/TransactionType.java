package com.cardemo.common.enums;

/**
 * Enum representing the 7 transaction types defined in the CardDemo application.
 *
 * <p>Faithfully translated from the fixed-width reference data file
 * {@code app/data/ASCII/trantype.txt}. Each record in the source file is 60 characters:
 * positions [0:2] = 2-digit type code, positions [2:52] = description (space-padded right),
 * positions [52:60] = 8-digit filler ("00000000").</p>
 *
 * <p>The 2-digit type code serves as the prefix for {@code TransactionCategory}'s 6-digit
 * code (e.g., TransactionType "01" maps to TransactionCategory "010001" through "010005"),
 * enabling hierarchical categorization used throughout the application.</p>
 *
 * <p>COBOL source mapping: TRANTYPE reference data (7 records, 2-byte type code primary key).</p>
 */
public enum TransactionType {

    /** Type code 01 — Purchase transaction. */
    PURCHASE("01", "Purchase"),

    /** Type code 02 — Payment transaction. */
    PAYMENT("02", "Payment"),

    /** Type code 03 — Credit transaction. */
    CREDIT("03", "Credit"),

    /** Type code 04 — Authorization transaction. */
    AUTHORIZATION("04", "Authorization"),

    /** Type code 05 — Refund transaction. */
    REFUND("05", "Refund"),

    /** Type code 06 — Reversal transaction. */
    REVERSAL("06", "Reversal"),

    /** Type code 07 — Adjustment transaction. */
    ADJUSTMENT("07", "Adjustment");

    /** The 2-character type code ("01" through "07"), zero-padded. */
    private final String code;

    /** Human-readable description preserving original capitalization from source data. */
    private final String description;

    /**
     * Constructs a TransactionType enum constant.
     *
     * @param code        the 2-character type code (e.g., "01")
     * @param description the human-readable description (e.g., "Purchase")
     */
    TransactionType(String code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * Returns the 2-character transaction type code.
     *
     * @return the type code string, always exactly 2 characters, zero-padded
     */
    public String getCode() {
        return code;
    }

    /**
     * Returns the human-readable description of this transaction type.
     *
     * @return the description string with original capitalization from source data
     */
    public String getDescription() {
        return description;
    }

    /**
     * Looks up a {@code TransactionType} by its 2-character code.
     *
     * <p>This is the primary factory method for obtaining a TransactionType from
     * a stored or transmitted code value. The lookup is exact-match on the
     * {@code code} field using {@link String#equals(Object)}.</p>
     *
     * @param code the 2-character type code to look up (e.g., "01", "07")
     * @return the matching {@code TransactionType} enum constant
     * @throws IllegalArgumentException if no constant matches the given code
     */
    public static TransactionType fromCode(String code) {
        for (TransactionType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown transaction type code: " + code);
    }

    /**
     * Returns a string representation of this transaction type in the format
     * {@code "code: description"} (e.g., {@code "01: Purchase"}).
     *
     * @return formatted string combining the code and description
     */
    @Override
    public String toString() {
        return code + ": " + description;
    }
}
