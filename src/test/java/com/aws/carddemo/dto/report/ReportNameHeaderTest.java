package com.aws.carddemo.dto.report;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link ReportNameHeader}, the report title / date-range
 * header DTO for the daily transaction report.
 *
 * <p>Origin oracle: legacy/cpy/CVTRA07Y.cpy (REPORT-NAME-HEADER).
 *
 * <p>{@code ReportNameHeader} is a 1:1 translation of the {@code REPORT-NAME-HEADER}
 * {@code 01}-level group in copybook {@code CVTRA07Y} (a fixed-width, 115-byte
 * record). These tests pin the four fixed COBOL {@code VALUE} literals - modeled as
 * {@code public static final String} constants - to their exact values and byte
 * widths, and verify that the two runtime-variable 10-character date fields
 * ({@code REPT-START-DATE} / {@code REPT-END-DATE}) round-trip faithfully through
 * their JavaBean accessors. Pinning {@code DATE_HEADER} at 12 characters and
 * {@code DATE_SEPARATOR} at 4 characters guards the fixed-column byte layout that
 * the report writer and {@code FixedWidthRecordMapper} depend on.
 *
 * <p>This is a pure unit test: it exercises the DTO in complete isolation with no
 * application context, no database, and no container runtime, and runs under
 * Surefire in milliseconds.
 */
class ReportNameHeaderTest {

    /**
     * {@code REPT-SHORT-NAME PIC X(38) VALUE 'DALYREPT'}: the fixed short report
     * name literal is preserved exactly.
     */
    @Test
    void short_name_is_dalyrept() {
        assertThat(ReportNameHeader.SHORT_NAME).isEqualTo("DALYREPT");
    }

    /**
     * {@code REPT-LONG-NAME PIC X(41) VALUE 'Daily Transaction Report'}: the fixed
     * report title literal is preserved exactly.
     */
    @Test
    void long_name_is_daily_transaction_report() {
        assertThat(ReportNameHeader.LONG_NAME).isEqualTo("Daily Transaction Report");
    }

    /**
     * {@code REPT-DATE-HEADER PIC X(12) VALUE 'Date Range: '}: the label is exactly
     * 12 characters wide, including the single trailing space, so it already fills
     * the whole X(12) field (its length is 12, not 11).
     */
    @Test
    void date_header_is_twelve_chars() {
        assertThat(ReportNameHeader.DATE_HEADER).isEqualTo("Date Range: ").hasSize(12);
    }

    /**
     * {@code FILLER PIC X(04) VALUE ' to '}: the separator is exactly 4 characters
     * wide (leading space, {@code to}, trailing space), filling the whole X(04)
     * field.
     */
    @Test
    void date_separator_is_four_chars() {
        assertThat(ReportNameHeader.DATE_SEPARATOR).isEqualTo(" to ").hasSize(4);
    }

    /**
     * {@code REPT-START-DATE PIC X(10)} round-trips through the JavaBean accessor
     * pair on a no-arg-constructed instance. The value is an opaque 10-character
     * print string and is stored and returned verbatim.
     */
    @Test
    void start_date_round_trips_via_getter_setter() {
        ReportNameHeader header = new ReportNameHeader();
        header.setReptStartDate("2022-01-01");
        assertThat(header.getReptStartDate()).isEqualTo("2022-01-01");
    }

    /**
     * {@code REPT-END-DATE PIC X(10)} round-trips through the JavaBean accessor pair
     * on a no-arg-constructed instance. The value is an opaque 10-character print
     * string and is stored and returned verbatim.
     */
    @Test
    void end_date_round_trips_via_getter_setter() {
        ReportNameHeader header = new ReportNameHeader();
        header.setReptEndDate("2022-01-31");
        assertThat(header.getReptEndDate()).isEqualTo("2022-01-31");
    }

    /**
     * A freshly no-arg-constructed header leaves both date fields {@code null},
     * mirroring the COBOL {@code VALUE SPACES} default before the batch writer
     * populates the reporting window.
     */
    @Test
    void new_instance_has_null_dates_by_default() {
        ReportNameHeader header = new ReportNameHeader();
        assertThat(header.getReptStartDate()).isNull();
        assertThat(header.getReptEndDate()).isNull();
    }

    /**
     * The convenience constructor populates both date fields; each value is stored
     * as an opaque 10-character string and returned unchanged by its getter.
     */
    @Test
    void convenience_constructor_sets_both_dates() {
        ReportNameHeader header = new ReportNameHeader("2022-01-01", "2022-01-31");
        assertThat(header.getReptStartDate()).isEqualTo("2022-01-01");
        assertThat(header.getReptEndDate()).isEqualTo("2022-01-31");
    }
}
