package com.aws.carddemo.dto.report;

/**
 * Fixed literal report lines (column headings and separator rule) for the daily
 * transaction report.
 *
 * <p><strong>Origin: legacy/cpy/CVTRA07Y.cpy (TRANSACTION-HEADER-1,
 * TRANSACTION-HEADER-2).</strong> Both source groups are COBOL {@code 01}-level
 * structures composed entirely of {@code FILLER} items with literal
 * {@code VALUE} clauses, so they carry no variable runtime data. They are
 * therefore modeled here as compile-time {@code String} constants rather than
 * data-carrying DTOs. The transaction-report batch writer
 * ({@code batch/TransactionReportJobConfig}, derived from {@code CBTRN03C})
 * emits these constants verbatim as report lines.
 *
 * <p>The column headings in {@link #TRANSACTION_HEADER_1} are laid out to align,
 * byte-for-byte, with the detail-line columns produced by
 * {@code TransactionDetailReport}; the field widths and internal spacing defined
 * here are the printed report's byte columns and must not be altered.
 *
 * <p>This class is a stateless constants holder and cannot be instantiated.
 */
public final class TransactionReportHeaders {

    /**
     * TRANSACTION-HEADER-1: the column-heading line of the transaction report.
     *
     * <p>Exactly <strong>114</strong> bytes long, assembled from seven
     * left-justified, space-padded {@code FILLER} segments in the order and to
     * the field widths defined by {@code CVTRA07Y.cpy}: {@code X(17)}
     * "Transaction ID", {@code X(12)} "Account ID", {@code X(19)}
     * "Transaction Type", {@code X(35)} "Tran Category", {@code X(14)}
     * "Tran Source", {@code X(1)} a single space, and {@code X(16)}
     * "        Amount" (eight leading spaces so the amount column is
     * right-aligned). The trailing spaces produced by the final segment's
     * padding are part of the fixed layout and are preserved. The report writer
     * pads this 114-byte heading out to the 133-byte record width on output.
     */
    public static final String TRANSACTION_HEADER_1 =
            pad("Transaction ID", 17)
            + pad("Account ID", 12)
            + pad("Transaction Type", 19)
            + pad("Tran Category", 35)
            + pad("Tran Source", 14)
            + pad("", 1)
            + pad("        Amount", 16);

    /**
     * TRANSACTION-HEADER-2: the separator/rule line of the transaction report.
     *
     * <p>COBOL {@code PIC X(133) VALUE ALL '-'}: exactly <strong>133</strong>
     * dash characters. This equals the full transaction-report record width
     * ({@code FD-REPTFILE-REC PIC X(133)}).
     */
    public static final String TRANSACTION_HEADER_2 = "-".repeat(133);

    /**
     * Right-pads {@code value} with ASCII spaces to exactly {@code width}
     * characters, reproducing COBOL's left-justified
     * {@code FILLER PIC X(width) VALUE '...'} semantics.
     *
     * @param value the literal heading text; must not exceed {@code width}
     * @param width the fixed COBOL field width to pad to
     * @return {@code value} followed by {@code width - value.length()} spaces
     * @throws IllegalArgumentException if {@code value} is longer than {@code width}
     */
    private static String pad(String value, int width) {
        if (value.length() > width) {
            throw new IllegalArgumentException(
                    "Heading segment \"" + value + "\" (" + value.length()
                            + " chars) exceeds its fixed field width of " + width);
        }
        return value + " ".repeat(width - value.length());
    }

    /**
     * Prevents instantiation of this stateless constants holder.
     */
    private TransactionReportHeaders() {
        throw new AssertionError("No instances");
    }
}
