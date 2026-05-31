package com.carddemo.service;

import com.carddemo.util.DateConversionUtil;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link DateConversionService} — the Java replacement for the
 * COBOL {@code app/cbl/CSUTLDTC.cbl} program, which wrapped the IBM Language
 * Environment {@code CEEDAYS} callable service to validate a date string against
 * a format mask and return a severity code plus a 15-character result message in
 * {@code LS-RESULT} [app/cbl/CSUTLDTC.cbl L88-L149].
 *
 * <h2>What is verified</h2>
 * <ul>
 *   <li><b>Format inter-conversion</b> — {@code CCYYMMDD} &harr; {@code MM/DD/YYYY}
 *       and {@code CCYYMMDD} &harr; ISO {@code yyyy-MM-dd}, mirroring the
 *       {@code WS-CURDATE} family of fields in {@code app/cpy/CSDAT01Y.cpy}.</li>
 *   <li><b>DB2 timestamp (PR-11)</b> — the 26-character DB2 external timestamp
 *       {@code yyyy-MM-dd-HH.mm.ss.SS'0000'} preserved from {@code CBACT04C}
 *       {@code DB2-FORMAT-TS}; the trailing {@code 0000} is a literal and the
 *       sub-second field is a 2-digit centisecond ({@code DB2-MIL PIC 9(02)}).</li>
 *   <li><b>Date validation</b> — the {@code CSUTLDTC}/{@code CEEDAYS}
 *       severity-and-message contract: severity {@code "0000"} means valid, while
 *       month/day/era/numeric failures map to the exact result strings preserved
 *       from the {@code EVALUATE} dispatch [app/cbl/CSUTLDTC.cbl L128-L149].</li>
 *   <li><b>Edge cases</b> — leap-year February 29, end-of-February in a non-leap
 *       year, and year/century boundaries.</li>
 * </ul>
 *
 * <h2>API note (defensive adaptation)</h2>
 * <p>These tests exercise the <em>actual</em> public contract of
 * {@link DateConversionService} and its delegate {@link DateConversionUtil}:
 * {@code parseCcyymmdd}/{@code formatAsMmDdYyyy}/{@code formatAsYyyyMmDd},
 * {@code convertToDb2Timestamp}, and {@code validateDate} (returning the
 * {@link DateConversionService.DateValidationResult} record), plus the static
 * converters {@code isoToCcyymmdd}/{@code ccyymmddToIso}/{@code mmDdYyyyToIso}
 * and {@code fromDb2Timestamp} on the utility. COBOL-style picture masks (e.g.
 * {@code "YYYYMMDD"}) are routed through {@code validateDate}, which translates
 * the mask and applies {@link java.time.format.ResolverStyle#STRICT} parsing so
 * that out-of-range calendar fields are rejected rather than silently
 * normalised.
 *
 * <p>The class is annotated with {@link ExtendWith}({@code MockitoExtension.class})
 * to satisfy the project's test-harness convention; no collaborators are mocked
 * because date conversion is pure, side-effect-free logic (PR-29: the service is
 * constructed directly via its Lombok-generated no-args constructor).
 *
 * @see DateConversionService
 * @see com.carddemo.util.DateConversionUtil
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DateConversionService - replaces COBOL CSUTLDTC date conversion utility")
class DateConversionServiceTest {

    /**
     * The COBOL-style picture mask used by {@code CSUTLDTC} callers for the
     * compact 8-digit date form. {@link DateConversionService#validateDate}
     * translates {@code YYYY}&rarr;{@code uuuu} and {@code DD}&rarr;{@code dd}
     * before applying a {@code STRICT} resolver.
     */
    private static final String COBOL_DATE_MASK = "YYYYMMDD";

    /** Class under test; reconstructed before each test for isolation. */
    private DateConversionService service;

    @BeforeEach
    void setUp() {
        // DateConversionService is annotated @RequiredArgsConstructor over zero
        // final fields, so Lombok emits a public no-args constructor. The service
        // is stateless and delegates to the static DateConversionUtil helper.
        service = new DateConversionService();
    }

    // =====================================================================
    // Group 1 — CCYYMMDD <-> MM/DD/YYYY conversion
    // =====================================================================
    @Nested
    @DisplayName("CCYYMMDD <-> MM/DD/YYYY conversion")
    class CcyymmddToMmddyyyyConversion {

        @Test
        @DisplayName("Should convert CCYYMMDD 20220719 to MM/DD/YYYY 07/19/2022")
        void shouldConvertCcyymmddToMmddyyyy() {
            // when: parse the compact COBOL date then render the US display form
            String result = service.formatAsMmDdYyyy(service.parseCcyymmdd("20220719"));

            // then
            assertThat(result).isEqualTo("07/19/2022");
        }

        @Test
        @DisplayName("Should convert MM/DD/YYYY 07/19/2022 to CCYYMMDD 20220719")
        void shouldConvertMmddyyyyToCcyymmdd() {
            // when: MM/DD/YYYY -> ISO -> CCYYMMDD via the static utility converters
            String result = DateConversionUtil.isoToCcyymmdd(
                    DateConversionUtil.mmDdYyyyToIso("07/19/2022"));

            // then
            assertThat(result).isEqualTo("20220719");
        }

        @Test
        @DisplayName("Should handle New Year's Day 20220101 -> 01/01/2022")
        void shouldHandleNewYearDayInCcyymmddConversion() {
            assertThat(service.formatAsMmDdYyyy(service.parseCcyymmdd("20220101")))
                    .isEqualTo("01/01/2022");
        }

        @Test
        @DisplayName("Should handle New Year's Eve 20221231 -> 12/31/2022")
        void shouldHandleNewYearEveInCcyymmddConversion() {
            assertThat(service.formatAsMmDdYyyy(service.parseCcyymmdd("20221231")))
                    .isEqualTo("12/31/2022");
        }

        @Test
        @DisplayName("Should reject malformed CCYYMMDD input (hyphenated or non-numeric)")
        void shouldRejectInvalidCcyymmddFormat() {
            // A hyphenated ISO string cannot be parsed by the compact yyyyMMdd mask.
            assertThatThrownBy(() -> service.parseCcyymmdd("2022-07-19"))
                    .isInstanceOf(DateTimeParseException.class);
            // Neither can an alphabetic string (mirrors CSUTLDTC FC-NON-NUMERIC-DATA).
            assertThatThrownBy(() -> service.parseCcyymmdd("abcd1234"))
                    .isInstanceOf(DateTimeParseException.class);
        }
    }

    // =====================================================================
    // Group 2 — CCYYMMDD <-> DB2 timestamp (PR-11)
    // =====================================================================
    @Nested
    @DisplayName("CCYYMMDD <-> DB2 timestamp (PR-11 preservation)")
    class Db2TimestampConversion {

        @Test
        @DisplayName("Should format DB2 timestamp as exactly 26 characters ending in literal 0000")
        void shouldFormatDb2TimestampWith26Characters() {
            // given: the PR-11 canonical instant (CSUTLDTC version footer date/time)
            LocalDateTime dt = LocalDateTime.of(2022, 7, 19, 23, 12, 33, 456_000_000);

            // when
            String result = service.convertToDb2Timestamp(dt);

            // then: 26-char DB2 external form whose final field is the literal
            // '0000' (CBACT04C DB2-REST PIC X(04)).
            assertThat(result).hasSize(26);
            assertThat(result).endsWith("0000");
        }

        @Test
        @DisplayName("Should format DB2 timestamp preserving 2-digit centiseconds (yyyy-MM-dd-HH.mm.ss.SS0000)")
        void shouldFormatDb2TimestampPreservingMilliseconds() {
            // given: a 456 ms sub-second component
            LocalDateTime dt = LocalDateTime.of(2022, 7, 19, 23, 12, 33, 456_000_000);

            // when
            String result = service.convertToDb2Timestamp(dt);

            // then: the COBOL DB2-MIL field is 2 digits (centiseconds), so 456 ms
            // truncates to "45" before the literal "0000" is appended (PR-11).
            assertThat(result).isEqualTo("2022-07-19-23.12.33.450000");
        }

        @Test
        @DisplayName("Should parse a DB2 timestamp string back to LocalDateTime (stable roundtrip)")
        void shouldParseDb2TimestampBack() {
            // given: an instant whose sub-second component already fits 2 digits
            // (450 ms), making the format->parse->format roundtrip exact.
            LocalDateTime original = LocalDateTime.of(2022, 7, 19, 23, 12, 33, 450_000_000);
            String formatted = service.convertToDb2Timestamp(original);

            // when
            LocalDateTime parsed = DateConversionUtil.fromDb2Timestamp(formatted);

            // then
            assertThat(parsed).isEqualTo(original);
            assertThat(service.convertToDb2Timestamp(parsed)).isEqualTo(formatted);
        }

        @Test
        @DisplayName("Should throw on a malformed DB2 timestamp string")
        void shouldFailOnMalformedDb2Timestamp() {
            assertThatThrownBy(() -> DateConversionUtil.fromDb2Timestamp("2022/07/19 23:12:33"))
                    .isInstanceOf(DateTimeParseException.class);
        }
    }

    // =====================================================================
    // Group 3 — ISO date <-> CCYYMMDD conversion
    // =====================================================================
    @Nested
    @DisplayName("ISO date <-> CCYYMMDD conversion")
    class IsoDateConversion {

        @Test
        @DisplayName("Should convert ISO 2022-07-19 to CCYYMMDD 20220719")
        void shouldConvertIsoDateToCcyymmdd() {
            assertThat(DateConversionUtil.isoToCcyymmdd("2022-07-19")).isEqualTo("20220719");
        }

        @Test
        @DisplayName("Should convert CCYYMMDD 20220719 to ISO 2022-07-19")
        void shouldConvertCcyymmddToIsoDate() {
            assertThat(DateConversionUtil.ccyymmddToIso("20220719")).isEqualTo("2022-07-19");
        }
    }

    // =====================================================================
    // Group 4 — Date validation (mirrors CSUTLDTC / CEEDAYS severity + message)
    // =====================================================================
    @Nested
    @DisplayName("Date validation")
    class DateValidation {

        @Test
        @DisplayName("Should validate a well-formed CCYYMMDD date (severity 0000 / 'Date is valid')")
        void shouldValidateValidCcyymmddDate() {
            // when
            DateConversionService.DateValidationResult result =
                    service.validateDate("20220719", COBOL_DATE_MASK);

            // then: CEEDAYS FC-INVALID-DATE -> severity '0000', "Date is valid"
            assertThat(result.valid()).isTrue();
            assertThat(result.severityCode()).isEqualTo("0000");
            assertThat(result.resultMessage()).isEqualTo("Date is valid");
        }

        @Test
        @DisplayName("Should reject month 13 (CSUTLDTC FC-INVALID-MONTH -> 'Invalid month')")
        void shouldValidateInvalidMonth() {
            DateConversionService.DateValidationResult result =
                    service.validateDate("20221319", COBOL_DATE_MASK);

            assertThat(result.valid()).isFalse();
            assertThat(result.resultMessage()).isEqualTo("Invalid month");
        }

        @Test
        @DisplayName("Should reject Feb 32 (CSUTLDTC FC-BAD-DATE-VALUE -> 'Datevalue error')")
        void shouldValidateInvalidDay() {
            DateConversionService.DateValidationResult result =
                    service.validateDate("20220232", COBOL_DATE_MASK);

            assertThat(result.valid()).isFalse();
            assertThat(result.resultMessage()).isEqualTo("Datevalue error");
        }

        @Test
        @DisplayName("Should reject non-numeric input (CSUTLDTC FC-NON-NUMERIC-DATA -> 'Nonnumeric data')")
        void shouldValidateNonNumericInput() {
            DateConversionService.DateValidationResult result =
                    service.validateDate("ABCDEFGH", COBOL_DATE_MASK);

            assertThat(result.valid()).isFalse();
            assertThat(result.resultMessage()).isEqualTo("Nonnumeric data");
        }

        @Test
        @DisplayName("Should reject empty input (CSUTLDTC FC-INSUFFICIENT-DATA -> severity 0010 / 'Insufficient')")
        void shouldValidateEmptyDate() {
            DateConversionService.DateValidationResult result =
                    service.validateDate("", COBOL_DATE_MASK);

            assertThat(result.valid()).isFalse();
            assertThat(result.severityCode()).isEqualTo("0010");
            assertThat(result.resultMessage()).isEqualTo("Insufficient");
        }
    }

    // =====================================================================
    // Group 5 — Edge cases: leap year, end-of-month, year boundary
    // =====================================================================
    @Nested
    @DisplayName("Edge cases - leap year, end-of-month")
    class EdgeCases {

        @Test
        @DisplayName("Should accept Feb 29 in a leap year (2020-02-29)")
        void shouldHandleLeapYear() {
            assertThat(service.validateDate("20200229", COBOL_DATE_MASK).valid()).isTrue();
        }

        @Test
        @DisplayName("Should reject Feb 29 in a non-leap year (2021-02-29)")
        void shouldRejectFeb29InNonLeapYear() {
            DateConversionService.DateValidationResult result =
                    service.validateDate("20210229", COBOL_DATE_MASK);

            assertThat(result.valid()).isFalse();
            assertThat(result.resultMessage()).isEqualTo("Datevalue error");
        }

        @Test
        @DisplayName("Should accept Feb 28 in a non-leap year (2022-02-28)")
        void shouldHandleEndOfFebruary() {
            assertThat(service.validateDate("20220228", COBOL_DATE_MASK).valid()).isTrue();
        }

        @Test
        @DisplayName("Should convert at a year boundary: 19991231 -> 12/31/1999")
        void shouldHandleYearBoundary() {
            // Exercises the LocalDate-typed service methods across a year/century
            // transition: parse the compact form, then render both display forms.
            LocalDate parsed = service.parseCcyymmdd("19991231");

            assertThat(service.formatAsMmDdYyyy(parsed)).isEqualTo("12/31/1999");
            assertThat(service.formatAsYyyyMmDd(parsed)).isEqualTo("1999-12-31");
        }
    }
}
