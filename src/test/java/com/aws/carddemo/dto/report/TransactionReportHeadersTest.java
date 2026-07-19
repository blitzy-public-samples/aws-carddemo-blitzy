package com.aws.carddemo.dto.report;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link TransactionReportHeaders}, pinning the exact byte
 * layout of the two fixed literal lines printed at the top of every page of the
 * daily transaction report.
 *
 * <p>Origin oracle: legacy/cpy/CVTRA07Y.cpy (TRANSACTION-HEADER-1,
 * TRANSACTION-HEADER-2). Both COBOL groups are composed entirely of
 * {@code FILLER} items with literal {@code VALUE} clauses and therefore carry no
 * variable runtime data; production models them as compile-time {@code String}
 * constants. These tests lock their widths and content so the printed report
 * columns and the 133-byte record width stay byte-faithful to the COBOL.
 *
 * <p>This is a pure unit test: no Spring context, no database, and no
 * Testcontainers. It runs under Surefire ({@code *Test}) in milliseconds. Only
 * the two public constants are asserted; the private constructor of the class
 * under test is intentionally not exercised.
 */
class TransactionReportHeadersTest {

    @Test
    void transaction_header_1_is_exactly_114_chars() {
        // TRANSACTION-HEADER-1 native group width = X(17)+X(12)+X(19)+X(35)
        // +X(14)+X(1)+X(16) = 114 bytes. This is intentionally NOT the 133-byte
        // record width; the report writer pads the heading to 133 on output.
        assertThat(TransactionReportHeaders.TRANSACTION_HEADER_1).hasSize(114);
    }

    @Test
    void transaction_header_1_starts_with_transaction_id() {
        assertThat(TransactionReportHeaders.TRANSACTION_HEADER_1)
                .startsWith("Transaction ID");
    }

    @Test
    void transaction_header_1_contains_expected_labels_in_order() {
        String header = TransactionReportHeaders.TRANSACTION_HEADER_1;

        assertThat(header).contains(
                "Account ID",
                "Transaction Type",
                "Tran Category",
                "Tran Source",
                "Amount");

        // The column labels must appear left-to-right in the COBOL FILLER
        // order. The first label's index is non-negative and every subsequent
        // label begins strictly further right, which also proves each label is
        // present (index >= 0).
        int transactionId = header.indexOf("Transaction ID");
        int accountId = header.indexOf("Account ID");
        int transactionType = header.indexOf("Transaction Type");
        int tranCategory = header.indexOf("Tran Category");
        int tranSource = header.indexOf("Tran Source");
        int amount = header.indexOf("Amount");

        assertThat(transactionId).isNotNegative();
        assertThat(accountId).isGreaterThan(transactionId);
        assertThat(transactionType).isGreaterThan(accountId);
        assertThat(tranCategory).isGreaterThan(transactionType);
        assertThat(tranSource).isGreaterThan(tranCategory);
        assertThat(amount).isGreaterThan(tranSource);
    }

    @Test
    void transaction_header_1_has_eight_spaces_before_amount() {
        // The final FILLER is X(16) VALUE '        Amount' (eight leading
        // spaces) so the amount column right-aligns over the detail line.
        assertThat(TransactionReportHeaders.TRANSACTION_HEADER_1)
                .contains("        Amount");
    }

    @Test
    void transaction_header_2_is_133_dashes() {
        assertThat(TransactionReportHeaders.TRANSACTION_HEADER_2)
                .hasSize(133)
                .isEqualTo("-".repeat(133));
    }

    @Test
    void transaction_header_2_contains_only_dash_characters() {
        assertThat(TransactionReportHeaders.TRANSACTION_HEADER_2
                .chars().allMatch(c -> c == '-')).isTrue();
    }

    @Test
    void transaction_header_2_matches_report_record_width() {
        // 133 equals the full FD-REPTFILE-REC PIC X(133) transaction-report
        // record width; the separator rule spans the entire printed line.
        assertThat(TransactionReportHeaders.TRANSACTION_HEADER_2).hasSize(133);
    }
}
