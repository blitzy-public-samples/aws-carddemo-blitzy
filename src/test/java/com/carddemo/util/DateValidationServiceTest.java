package com.carddemo.util;

import com.carddemo.exception.ValidationException;
import com.carddemo.service.DateValidationService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit test for {@link com.carddemo.service.DateValidationService}.
 *
 * <p><strong>Purpose &mdash; COBOL parity pinning.</strong> {@code DateValidationService} is the
 * {@code java.time} replacement for the legacy mainframe date utility {@code app/cbl/CSUTLDTC.cbl}
 * and its reusable edit copybooks {@code app/cpy/CSUTLDPY.cpy} / {@code app/cpy/CSUTLDWY.cpy}. The
 * legacy code delegated to the Language&nbsp;Environment service {@code CEEDAYS} and treated any
 * non-zero severity as a validation failure, while the inline copybook edits enforced the calendar
 * rules directly. This test pins the migrated behavior so it stays faithful to the AAP's mandated
 * <strong>100% functional parity</strong> (AAP &sect;0.1.1, &sect;0.7.1) and contributes to the
 * &ge;80% JaCoCo coverage gate enforced by {@code mvn verify} (AAP &sect;0.7.2).</p>
 *
 * <p><strong>Legacy rules encoded</strong> (source-of-truth: {@code CSUTLDTC.cbl} +
 * {@code CSUTLDPY.cpy} + {@code CSUTLDWY.cpy}):</p>
 * <ul>
 *   <li>Month must be {@code 1-12} ({@code WS-VALID-MONTH}, {@code CSUTLDWY.cpy} L19-20;
 *       {@code CSUTLDPY.cpy} L110-124).</li>
 *   <li>Day must be {@code 1-31} ({@code WS-VALID-DAY}, {@code CSUTLDWY.cpy} L28-29).</li>
 *   <li>31 days only in months 1,3,5,7,8,10,12 ({@code WS-31-DAY-MONTH}, {@code CSUTLDWY.cpy}
 *       L21-23).</li>
 *   <li>February can never have 30 days; February&nbsp;29 only in a leap year &mdash; century years
 *       divide by 400, others by 4 ({@code CSUTLDPY.cpy} L228-272) &rArr; 2024 valid, 2000 valid,
 *       1900 invalid, 2023 invalid.</li>
 *   <li>Supported external formats are <strong>YYYY-MM-DD</strong> and <strong>MM/YYYY</strong>
 *       only (AAP &sect;0.4.1.3).</li>
 *   <li>Invalid dates surface as {@link com.carddemo.exception.ValidationException}
 *       (&rarr; HTTP&nbsp;400 via the global exception handler).</li>
 * </ul>
 *
 * <p><strong>Test character.</strong> This is a plain POJO unit test &mdash; no
 * {@code @SpringBootTest}, no {@code @ExtendWith}, no mocks, no Spring context, no database, and no
 * file I/O. {@code DateValidationService} is {@code java.time}-only with no injected collaborators,
 * so it is instantiated directly via {@code new DateValidationService()}. The class under test
 * lives in {@code com.carddemo.service} while this test lives in {@code com.carddemo.util} (per AAP
 * &sect;0.3.1), hence the explicit cross-package imports.</p>
 *
 * <p>No personally identifiable information (PII) appears in any test data (AAP &sect;0.6.8).</p>
 *
 * @see com.carddemo.service.DateValidationService
 * @see com.carddemo.exception.ValidationException
 */
@DisplayName("DateValidationService — COBOL CSUTLDTC parity")
public class DateValidationServiceTest {

    /** System under test; a fresh instance is created before each test for isolation. */
    private DateValidationService service;

    @BeforeEach
    void setUp() {
        // DateValidationService has no collaborators and a default no-arg constructor.
        service = new DateValidationService();
    }

    // =====================================================================================
    // validateAndParseDate(String, String) — YYYY-MM-DD
    // =====================================================================================

    @Nested
    @DisplayName("validateAndParseDate (YYYY-MM-DD)")
    class YyyyMmDdValidation {

        @Test
        @DisplayName("accepts an ordinary valid date and returns the exact LocalDate")
        void acceptsOrdinaryDate() {
            assertThat(service.validateAndParseDate("2023-06-15", "Date"))
                    .isEqualTo(LocalDate.of(2023, 6, 15));
        }

        @Test
        @DisplayName("accepts Feb 29 in leap year 2024 (÷4 rule) — CSUTLDPY leap check")
        void acceptsLeapDayDivisibleBy4() {
            assertThat(service.validateAndParseDate("2024-02-29", "Date"))
                    .isEqualTo(LocalDate.of(2024, 2, 29));
        }

        @Test
        @DisplayName("accepts Feb 29 in century leap year 2000 (÷400 rule) — CSUTLDPY leap check")
        void acceptsLeapDayDivisibleBy400() {
            assertThat(service.validateAndParseDate("2000-02-29", "Date"))
                    .isEqualTo(LocalDate.of(2000, 2, 29));
        }

        @Test
        @DisplayName("accepts the last day of a 31-day month (2023-01-31)")
        void acceptsLastDayOf31DayMonth() {
            assertThat(service.validateAndParseDate("2023-01-31", "Date"))
                    .isEqualTo(LocalDate.of(2023, 1, 31));
        }

        @Test
        @DisplayName("accepts Feb 28 in a non-leap year (2023-02-28)")
        void acceptsFeb28InNonLeapYear() {
            assertThat(service.validateAndParseDate("2023-02-28", "Date"))
                    .isEqualTo(LocalDate.of(2023, 2, 28));
        }

        @ParameterizedTest(name = "[{index}] \"{0}\" round-trips to the same ISO string")
        @ValueSource(strings = {"2023-06-15", "2024-02-29", "2000-02-29", "2023-12-31"})
        @DisplayName("valid dates parse to a non-null LocalDate that round-trips to the input")
        void roundTripsValidDates(String input) {
            LocalDate result = service.validateAndParseDate(input, "Date");
            assertThat(result).isNotNull();
            // LocalDate.toString() emits the canonical zero-padded YYYY-MM-DD form.
            assertThat(result.toString()).isEqualTo(input);
        }

        @ParameterizedTest(name = "[{index}] \"{0}\" is rejected")
        @ValueSource(strings = {
                "2023-02-29", // non-leap Feb 29 (2023 not ÷4) — CSUTLDPY "cannot have 29 days"
                "1900-02-29", // century non-leap Feb 29 (1900 ÷100 but not ÷400)
                "2023-13-01", // month above range (>12) — WS-VALID-MONTH
                "2023-00-10", // month zero (<1) — WS-VALID-MONTH
                "2023-04-31", // 31 days in a 30-day month (April) — WS-31-DAY-MONTH
                "2023-02-30", // February can never have 30 days — CSUTLDPY
                "2023-06-00", // day zero (<1) — WS-VALID-DAY
                "2023-01-32"  // day above range (>31) — WS-VALID-DAY
        })
        @DisplayName("rejects impossible calendar dates with ValidationException")
        void rejectsImpossibleDates(String input) {
            assertThatThrownBy(() -> service.validateAndParseDate(input, "Date"))
                    .isInstanceOf(ValidationException.class);
        }

        @ParameterizedTest(name = "[{index}] \"{0}\" is rejected")
        @ValueSource(strings = {
                "06/15/2023", // wrong format (MM/DD/YYYY)
                "2023-6-15",  // non-zero-padded month — STRICT requires 2 digits
                "2023/06/15", // wrong separator
                "15-06-2023", // wrong field order
                "not-a-date", // pure garbage
                ""            // blank — guarded before parsing
        })
        @DisplayName("rejects malformed / wrong-format strings with ValidationException")
        void rejectsMalformedDates(String input) {
            assertThatThrownBy(() -> service.validateAndParseDate(input, "Date"))
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        @DisplayName("rejects null input with ValidationException (null guard, not NPE)")
        void rejectsNullDate() {
            // ValueSource cannot supply a true null, so the null case is asserted explicitly.
            assertThatThrownBy(() -> service.validateAndParseDate(null, "Date"))
                    .isInstanceOf(ValidationException.class);
        }
    }

    // =====================================================================================
    // validateAndParseMonthYear(String, String) — MM/YYYY
    // =====================================================================================

    @Nested
    @DisplayName("validateAndParseMonthYear (MM/YYYY)")
    class MmYyyyValidation {

        @Test
        @DisplayName("accepts a valid MM/YYYY value and returns the exact YearMonth")
        void acceptsValidMonthYear() {
            assertThat(service.validateAndParseMonthYear("12/2023", "Expiry"))
                    .isEqualTo(YearMonth.of(2023, 12));
        }

        @Test
        @DisplayName("accepts the lower month boundary (01/2024)")
        void acceptsLowerBoundaryMonth() {
            assertThat(service.validateAndParseMonthYear("01/2024", "Expiry"))
                    .isEqualTo(YearMonth.of(2024, 1));
        }

        @ParameterizedTest(name = "[{index}] \"{0}\" is rejected")
        @ValueSource(strings = {
                "13/2023", // month above range (>12)
                "00/2023", // month zero (<1)
                "2023/12", // wrong field order
                "12-2023", // wrong separator
                "1/2023",  // non-zero-padded month — STRICT requires 2 digits
                ""         // blank — guarded before parsing
        })
        @DisplayName("rejects out-of-range or malformed MM/YYYY values with ValidationException")
        void rejectsInvalidMonthYear(String input) {
            assertThatThrownBy(() -> service.validateAndParseMonthYear(input, "Expiry"))
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        @DisplayName("rejects null input with ValidationException (null guard)")
        void rejectsNullMonthYear() {
            assertThatThrownBy(() -> service.validateAndParseMonthYear(null, "Expiry"))
                    .isInstanceOf(ValidationException.class);
        }
    }

    // =====================================================================================
    // validateMonthRange(int) — WS-VALID-MONTH (1 THROUGH 12)
    // =====================================================================================

    @Nested
    @DisplayName("validateMonthRange(int)")
    class MonthRange {

        @ParameterizedTest(name = "month {0} is accepted")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12})
        @DisplayName("accepts every valid month 1–12 without throwing — WS-VALID-MONTH")
        void acceptsEveryValidMonth(int month) {
            assertThatCode(() -> service.validateMonthRange(month))
                    .doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "month {0} is rejected")
        @ValueSource(ints = {0, 13, -1, 99})
        @DisplayName("rejects months outside 1–12 (lower, upper, negative) with ValidationException")
        void rejectsOutOfRangeMonths(int month) {
            assertThatThrownBy(() -> service.validateMonthRange(month))
                    .isInstanceOf(ValidationException.class);
        }
    }

    // =====================================================================================
    // isValidDate(String) — non-throwing boolean convenience check
    // =====================================================================================

    @Nested
    @DisplayName("isValidDate(String) — non-throwing convenience check")
    class IsValidDate {

        @ParameterizedTest(name = "\"{0}\" is valid")
        @ValueSource(strings = {"2024-02-29", "2023-06-15", "2000-02-29", "2023-12-31"})
        @DisplayName("returns true for valid YYYY-MM-DD dates")
        void returnsTrueForValidDates(String input) {
            assertThat(service.isValidDate(input)).isTrue();
        }

        @ParameterizedTest(name = "\"{0}\" is invalid")
        @ValueSource(strings = {"2023-02-29", "2023-13-01", "2023-04-31", "bad", "06/15/2023", ""})
        @DisplayName("returns false for invalid / malformed input (does not throw)")
        void returnsFalseForInvalidDates(String input) {
            assertThat(service.isValidDate(input)).isFalse();
        }

        @Test
        @DisplayName("returns false for null/garbage/blank and NEVER throws (convenience contract)")
        void neverThrowsAndReturnsFalse() {
            // The defining property of this method versus validateAndParseDate is that it must
            // never propagate an exception — it reports invalidity via a false return instead.
            assertThatCode(() -> {
                assertThat(service.isValidDate(null)).isFalse();
                assertThat(service.isValidDate("garbage")).isFalse();
                assertThat(service.isValidDate("")).isFalse();
            }).doesNotThrowAnyException();
        }
    }

    // =====================================================================================
    // isLeapYear(int) — proleptic-Gregorian leap rule (CSUTLDPY L243-272)
    // =====================================================================================

    @Nested
    @DisplayName("isLeapYear(int) — Gregorian rule (÷4, century ÷400)")
    class LeapYear {

        @Test
        @DisplayName("2024 is a leap year (÷4) — CSUTLDPY leap check")
        void year2024IsLeap() {
            assertThat(service.isLeapYear(2024)).isTrue();
        }

        @Test
        @DisplayName("2000 is a leap year (century ÷400) — CSUTLDPY leap check")
        void year2000IsLeap() {
            assertThat(service.isLeapYear(2000)).isTrue();
        }

        @Test
        @DisplayName("1900 is NOT a leap year (÷100 but not ÷400) — CSUTLDPY leap check")
        void year1900IsNotLeap() {
            assertThat(service.isLeapYear(1900)).isFalse();
        }

        @Test
        @DisplayName("2023 is NOT a leap year (not ÷4) — CSUTLDPY leap check")
        void year2023IsNotLeap() {
            assertThat(service.isLeapYear(2023)).isFalse();
        }
    }

    // =====================================================================================
    // DateFormat enum — the two supported external formats (AAP §0.4.1.3)
    // =====================================================================================

    @Nested
    @DisplayName("DateFormat enum — supported external formats")
    class DateFormatEnum {

        @Test
        @DisplayName("exposes exactly the two supported formats")
        void exposesExactlyTwoFormats() {
            assertThat(DateValidationService.DateFormat.values()).hasSize(2);
        }

        @Test
        @DisplayName("ISO_DATE carries the YYYY-MM-DD legacy mask and a non-blank parse pattern")
        void isoDateFormatMetadata() {
            DateValidationService.DateFormat fmt = DateValidationService.DateFormat.ISO_DATE;
            assertThat(fmt.getLegacyMask()).isEqualTo("YYYY-MM-DD");
            assertThat(fmt.getPattern()).isNotBlank();
        }

        @Test
        @DisplayName("MONTH_YEAR carries the MM/YYYY legacy mask and a non-blank parse pattern")
        void monthYearFormatMetadata() {
            DateValidationService.DateFormat fmt = DateValidationService.DateFormat.MONTH_YEAR;
            assertThat(fmt.getLegacyMask()).isEqualTo("MM/YYYY");
            assertThat(fmt.getPattern()).isNotBlank();
        }
    }
}
