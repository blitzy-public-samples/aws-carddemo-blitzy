package com.carddemo.service;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.carddemo.exception.ValidationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit test for {@link DateValidationService}.
 *
 * <p><strong>Purpose &mdash; date-validation parity.</strong> {@code DateValidationService} is the
 * {@code java.time} replacement for the legacy COBOL date utility {@code app/cbl/CSUTLDTC.cbl}
 * (a thin shim over the IBM Language&nbsp;Environment callable service {@code CEEDAYS}) and its
 * reusable procedure copybook {@code app/cpy/CSUTLDPY.cpy}. This test proves the migrated Java
 * service rejects exactly the dates the COBOL utility rejected and accepts the valid ones, thereby
 * preserving the AAP-mandated <strong>100% functional parity</strong> for date handling
 * (AAP &sect;0.4.1.3 per-program test requirement, &sect;0.7.1 preservation rules).</p>
 *
 * <p><strong>Source-of-truth parity facts</strong> (mined from {@code CSUTLDPY.cpy}):</p>
 * <ul>
 *   <li><em>Month range</em> &mdash; {@code EDIT-MONTH} enforces {@code WS-VALID-MONTH} (1&ndash;12);
 *       anything else yields "Month must be a number between 1 and 12.".</li>
 *   <li><em>Day range</em> &mdash; {@code EDIT-DAY} enforces 1&ndash;31, then {@code EDIT-DAY-MONTH-YEAR}
 *       rejects day&nbsp;31 in a 30-day month ("Cannot have 31 days in this month.") and day&nbsp;30 in
 *       February ("Cannot have 30 days in this month.").</li>
 *   <li><em>Leap year</em> &mdash; February&nbsp;29 is valid only when the year is divisible by 4, and,
 *       for century years (two-digit year {@code 00}), divisible by 400; otherwise
 *       "Not a leap year.Cannot have 29 days in this month.".</li>
 *   <li><em>Final cross-check</em> &mdash; {@code EDIT-DATE-LE} calls {@code CEEDAYS}; a severity of
 *       zero ({@code WS-SEVERITY-N = 0}) means "Date is valid", any non-zero severity means rejected.</li>
 * </ul>
 *
 * <p>{@link java.time.format.ResolverStyle#STRICT} resolution (configured in the production service
 * with the {@code uuuu-MM-dd} pattern) enforces every one of those rules automatically, so the Java
 * service reproduces the observable <em>outcome</em> &mdash; valid versus invalid &mdash; rather than
 * the numeric {@code CEEDAYS} feedback codes. Consistent with the agent prompt, this test therefore
 * asserts behavior (accept / reject) and never pins {@code CEEDAYS}/COBOL feedback tokens.</p>
 *
 * <p><strong>Error contract.</strong> Every invalid input must surface as the domain
 * {@link com.carddemo.exception.ValidationException} (translated to <strong>HTTP&nbsp;400</strong> by
 * the global exception handler), never as a raw {@link java.time.format.DateTimeParseException}. The
 * tests below assert the domain exception type explicitly.</p>
 *
 * <p><strong>Test character.</strong> This is a pure POJO unit test: no {@code @SpringBootTest}, no
 * {@code @ExtendWith}, no mocks, no Spring context, and no database. {@code DateValidationService}
 * has no collaborators, so it is instantiated directly. Because this test lives in the same package
 * ({@code com.carddemo.service}) as the class under test, no import of {@code DateValidationService}
 * is required; only {@link com.carddemo.exception.ValidationException} (a different package) is
 * imported.</p>
 *
 * <p><strong>PII suppression (AAP &sect;0.6.8, &sect;0.7.1).</strong> No personally identifiable
 * information appears in any test data and the message-hygiene tests assert that thrown messages do
 * not echo CVV, SSN, or password values.</p>
 *
 * @see DateValidationService
 * @see com.carddemo.exception.ValidationException
 */
class DateValidationServiceTest {

    /**
     * The class under test, constructed directly. {@code DateValidationService} is stateless and
     * thread-safe (its two {@code DateTimeFormatter}s are {@code private static final}), so a single
     * shared instance is sufficient for every test.
     */
    private final DateValidationService service = new DateValidationService();

    // =====================================================================================
    // validateAndParseDate(YYYY-MM-DD) — happy path
    // =====================================================================================

    /**
     * Valid {@code YYYY-MM-DD} inputs, including the leap-year boundary cases that {@code CEEDAYS}
     * and {@code CSUTLDPY}'s {@code EDIT-DAY-MONTH-YEAR} accepted.
     */
    @Nested
    @DisplayName("validateAndParseDate(YYYY-MM-DD) — valid dates")
    class ValidateAndParseDateValid {

        @Test
        @DisplayName("parses a normal calendar date")
        void parsesNormalDate() {
            assertThat(service.validateAndParseDate("2023-12-25", "date"))
                    .isEqualTo(LocalDate.of(2023, 12, 25));
        }

        @Test
        @DisplayName("accepts Feb 29 in a leap year divisible by 4 (2024)")
        void acceptsLeapDayDivisibleByFour() {
            assertThat(service.validateAndParseDate("2024-02-29", "date"))
                    .isEqualTo(LocalDate.of(2024, 2, 29));
        }

        @Test
        @DisplayName("accepts Feb 29 in a century leap year divisible by 400 (2000)")
        void acceptsLeapDayDivisibleByFourHundred() {
            assertThat(service.validateAndParseDate("2000-02-29", "date"))
                    .isEqualTo(LocalDate.of(2000, 2, 29));
        }

        @ParameterizedTest(name = "[{index}] \"{0}\" -> {1}-{2}-{3}")
        @CsvSource({
            "2023-12-25, 2023, 12, 25",
            "2024-02-29, 2024, 2, 29",
            "2000-02-29, 2000, 2, 29",
            "1999-12-31, 1999, 12, 31",
            "2023-01-31, 2023, 1, 31",
            "2023-04-30, 2023, 4, 30",
            "2023-06-15, 2023, 6, 15",
            "2023-02-28, 2023, 2, 28",
            "1900-02-28, 1900, 2, 28"
        })
        @DisplayName("parses a representative set of valid calendar dates")
        void parsesValidDates(String input, int year, int month, int day) {
            assertThat(service.validateAndParseDate(input, "date"))
                    .isEqualTo(LocalDate.of(year, month, day));
        }

        @Test
        @DisplayName("trims surrounding whitespace before parsing (production contract)")
        void trimsSurroundingWhitespace() {
            assertThat(service.validateAndParseDate("  2023-12-25  ", "date"))
                    .isEqualTo(LocalDate.of(2023, 12, 25));
        }
    }

    // =====================================================================================
    // validateAndParseDate(YYYY-MM-DD) — invalid path (must throw ValidationException -> 400)
    // =====================================================================================

    /**
     * Impossible or malformed {@code YYYY-MM-DD} inputs. Each must raise the domain
     * {@link ValidationException}, mirroring the non-zero {@code CEEDAYS} severity / failed
     * {@code CSUTLDPY} edits in the legacy flow.
     */
    @Nested
    @DisplayName("validateAndParseDate(YYYY-MM-DD) — invalid dates throw ValidationException")
    class ValidateAndParseDateInvalid {

        @ParameterizedTest(name = "[{index}] \"{0}\" is rejected")
        @ValueSource(strings = {
            "2023-02-29",   // non-leap February 29 (2023 not divisible by 4)
            "2021-02-29",   // non-leap February 29
            "1900-02-29",   // century year divisible by 100 but not 400 -> not a leap year
            "2023-13-01",   // month above 12 (EDIT-MONTH)
            "2023-00-10",   // month zero (EDIT-MONTH)
            "2023-04-31",   // April has 30 days (Cannot have 31 days in this month)
            "2023-06-31",   // June has 30 days
            "2023-09-31",   // September has 30 days
            "2023-11-31",   // November has 30 days
            "2023-01-32",   // day above 31 (EDIT-DAY)
            "2023-12-00",   // day zero (EDIT-DAY)
            "not-a-date",   // pure garbage
            "2023/12/25",   // wrong separator
            "20231225",     // missing separators
            "12/25/2023",   // wrong format entirely
            "abcd-ef-gh"    // non-numeric components
        })
        @DisplayName("rejects impossible or malformed dates")
        void rejectsInvalidDates(String input) {
            assertThatThrownBy(() -> service.validateAndParseDate(input, "date"))
                    .isInstanceOf(ValidationException.class);
        }

        @ParameterizedTest(name = "[{index}] missing input is rejected")
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t"})
        @DisplayName("rejects null, empty, and blank input")
        void rejectsMissingInput(String input) {
            assertThatThrownBy(() -> service.validateAndParseDate(input, "date"))
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        @DisplayName("throws the domain ValidationException, never the raw java.time exception")
        void doesNotLeakJavaTimeException() {
            assertThatThrownBy(() -> service.validateAndParseDate("2023-02-29", "date"))
                    .isInstanceOf(ValidationException.class)
                    .isNotInstanceOf(DateTimeException.class);
        }
    }

    // =====================================================================================
    // validateAndParseMonthYear(MM/YYYY)
    // =====================================================================================

    /**
     * The {@code MM/YYYY} period format used by the custom-report path ({@code CORPT00C}). STRICT
     * resolution keeps the month component within 1&ndash;12, matching {@code EDIT-MONTH}.
     */
    @Nested
    @DisplayName("validateAndParseMonthYear(MM/YYYY)")
    class ValidateAndParseMonthYearTests {

        @Test
        @DisplayName("parses a valid MM/YYYY period")
        void parsesValidMonthYear() {
            assertThat(service.validateAndParseMonthYear("12/2023", "period"))
                    .isEqualTo(YearMonth.of(2023, 12));
        }

        @ParameterizedTest(name = "[{index}] \"{0}\" -> {1}-{2}")
        @CsvSource({
            "12/2023, 2023, 12",
            "01/2000, 2000, 1",
            "06/1999, 1999, 6"
        })
        @DisplayName("parses representative valid MM/YYYY values")
        void parsesValidMonthYears(String input, int year, int month) {
            assertThat(service.validateAndParseMonthYear(input, "period"))
                    .isEqualTo(YearMonth.of(year, month));
        }

        @ParameterizedTest(name = "[{index}] \"{0}\" is rejected")
        @ValueSource(strings = {
            "13/2023",   // month above 12
            "00/2023",   // month zero
            "12-2023",   // wrong separator
            "2023/12",   // wrong order (leading component 20 is not a valid month)
            "foo",       // garbage
            "12/abcd"    // non-numeric year
        })
        @DisplayName("rejects invalid MM/YYYY values")
        void rejectsInvalidMonthYear(String input) {
            assertThatThrownBy(() -> service.validateAndParseMonthYear(input, "period"))
                    .isInstanceOf(ValidationException.class);
        }

        @ParameterizedTest(name = "[{index}] missing input is rejected")
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("rejects null, empty, and blank MM/YYYY input")
        void rejectsMissingMonthYear(String input) {
            assertThatThrownBy(() -> service.validateAndParseMonthYear(input, "period"))
                    .isInstanceOf(ValidationException.class);
        }
    }

    // =====================================================================================
    // validateMonthRange(int) — month range parity (EDIT-MONTH / WS-VALID-MONTH 1..12)
    // =====================================================================================

    /**
     * Standalone month-range checking, the direct analog of {@code CSUTLDPY}'s {@code EDIT-MONTH}
     * edit ("Month must be a number between 1 and 12.").
     */
    @Nested
    @DisplayName("validateMonthRange(int) — month must be 1..12")
    class ValidateMonthRangeTests {

        @ParameterizedTest(name = "month {0} is accepted")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12})
        @DisplayName("accepts every month in 1..12 without throwing")
        void acceptsValidMonths(int month) {
            assertThatCode(() -> service.validateMonthRange(month)).doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "month {0} is rejected")
        @ValueSource(ints = {0, 13, -1, 100, Integer.MIN_VALUE, Integer.MAX_VALUE})
        @DisplayName("rejects months outside 1..12 with ValidationException")
        void rejectsOutOfRangeMonths(int month) {
            assertThatThrownBy(() -> service.validateMonthRange(month))
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        @DisplayName("boundary months 1 and 12 do not throw")
        void boundaryMonthsDoNotThrow() {
            assertThatCode(() -> service.validateMonthRange(1)).doesNotThrowAnyException();
            assertThatCode(() -> service.validateMonthRange(12)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the just-outside boundaries 0 and 13 both throw")
        void justOutsideBoundariesThrow() {
            assertThatThrownBy(() -> service.validateMonthRange(0))
                    .isInstanceOf(ValidationException.class);
            assertThatThrownBy(() -> service.validateMonthRange(13))
                    .isInstanceOf(ValidationException.class);
        }
    }

    // =====================================================================================
    // isValidDate(String) — non-throwing boolean predicate
    // =====================================================================================

    /**
     * The boolean counterpart of {@link DateValidationService#validateAndParseDate}: it must report
     * validity without ever throwing, returning {@code false} for malformed, impossible, null, or
     * blank input.
     */
    @Nested
    @DisplayName("isValidDate(String) — boolean predicate, never throws")
    class IsValidDateTests {

        @ParameterizedTest(name = "isValidDate(\"{0}\") == true")
        @ValueSource(strings = {"2024-02-29", "2000-02-29", "2023-12-25", "1999-12-31", "2023-02-28"})
        @DisplayName("returns true for valid dates")
        void returnsTrueForValidDates(String input) {
            assertThat(service.isValidDate(input)).isTrue();
        }

        @ParameterizedTest(name = "isValidDate(\"{0}\") == false")
        @ValueSource(strings = {
            "2023-02-29", "1900-02-29", "2023-13-01", "2023-04-31",
            "garbage", "2023/12/25", "20231225"
        })
        @DisplayName("returns false for invalid dates")
        void returnsFalseForInvalidDates(String input) {
            assertThat(service.isValidDate(input)).isFalse();
        }

        @ParameterizedTest(name = "isValidDate(missing) == false")
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("returns false for null, empty, and blank input")
        void returnsFalseForMissingInput(String input) {
            assertThat(service.isValidDate(input)).isFalse();
        }

        @Test
        @DisplayName("never throws, even for malformed, impossible, or null input")
        void neverThrows() {
            assertThatCode(() -> service.isValidDate("garbage")).doesNotThrowAnyException();
            assertThatCode(() -> service.isValidDate("2023-02-29")).doesNotThrowAnyException();
            assertThatCode(() -> service.isValidDate(null)).doesNotThrowAnyException();
        }
    }

    // =====================================================================================
    // isLeapYear(int) — proleptic Gregorian leap-year rule (÷4, century ÷400)
    // =====================================================================================

    /**
     * Explicit leap-year predicate exposing the same divide-by-4 / divide-by-400 rule that
     * {@code CSUTLDPY}'s {@code EDIT-DAY-MONTH-YEAR} applied for February&nbsp;29.
     */
    @Nested
    @DisplayName("isLeapYear(int)")
    class IsLeapYearTests {

        @ParameterizedTest(name = "isLeapYear({0}) == {1}")
        @CsvSource({
            "2024, true",   // divisible by 4, not a century
            "2023, false",  // not divisible by 4
            "2021, false",  // not divisible by 4
            "1900, false",  // century divisible by 100 but not 400
            "2100, false",  // century divisible by 100 but not 400
            "2200, false",  // century divisible by 100 but not 400
            "2000, true",   // century divisible by 400
            "1600, true",   // century divisible by 400
            "2400, true"    // century divisible by 400
        })
        @DisplayName("applies the proleptic Gregorian leap-year rule")
        void appliesLeapYearRule(int year, boolean expected) {
            assertThat(service.isLeapYear(year)).isEqualTo(expected);
        }
    }

    // =====================================================================================
    // Error-contract & message hygiene (PII-free, informative) — agent prompt Phase 6 / 7
    // =====================================================================================

    /**
     * Verifies that thrown {@link ValidationException}s carry informative, PII-free messages and
     * wrap the underlying {@code java.time} failure as their cause for server-side diagnostics,
     * while never leaking that {@code java.time} exception type to the caller (AAP &sect;0.6.8).
     */
    @Nested
    @DisplayName("ValidationException message hygiene & error contract")
    class MessageHygiene {

        @Test
        @DisplayName("date error message is non-blank, names the field and the YYYY-MM-DD format, and is PII-free")
        void dateErrorMessageIsInformativeAndPiiFree() {
            String fieldLabel = "Transaction origination date";
            assertThatThrownBy(() -> service.validateAndParseDate("not-a-date", fieldLabel))
                    .isInstanceOf(ValidationException.class)
                    .satisfies(thrown -> {
                        String message = thrown.getMessage();
                        assertThat(message).isNotBlank();
                        assertThat(message).contains(fieldLabel);
                        assertThat(message).contains("YYYY-MM-DD");
                        // PII suppression (AAP §0.6.8): never echo CVV / SSN / password values.
                        assertThat(message.toLowerCase()).doesNotContain("password");
                        assertThat(message.toLowerCase()).doesNotContain("ssn");
                        assertThat(message.toLowerCase()).doesNotContain("cvv");
                    });
        }

        @Test
        @DisplayName("month/year error message names the field and the expected MM/YYYY format")
        void monthYearErrorMessageIsInformative() {
            String fieldLabel = "Report period";
            assertThatThrownBy(() -> service.validateAndParseMonthYear("13/2023", fieldLabel))
                    .isInstanceOf(ValidationException.class)
                    .satisfies(thrown -> {
                        String message = thrown.getMessage();
                        assertThat(message).isNotBlank();
                        assertThat(message).contains(fieldLabel);
                        assertThat(message).contains("MM/YYYY");
                    });
        }

        @Test
        @DisplayName("missing-input message is non-blank and names the field")
        void missingInputMessageIsInformative() {
            assertThatThrownBy(() -> service.validateAndParseDate(null, "Statement date"))
                    .isInstanceOf(ValidationException.class)
                    .satisfies(thrown -> assertThat(thrown.getMessage())
                            .isNotBlank()
                            .contains("Statement date"));
        }

        @Test
        @DisplayName("wraps the underlying java.time failure as the exception cause for diagnostics")
        void wrapsUnderlyingCause() {
            assertThatThrownBy(() -> service.validateAndParseDate("2023-02-29", "date"))
                    .isInstanceOf(ValidationException.class)
                    .hasCauseInstanceOf(DateTimeException.class);
        }

        @ParameterizedTest(name = "[{index}] null/empty/blank label falls back to \"Date\"")
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("falls back to a generic \"Date\" label when the supplied field label is null or blank")
        void usesGenericLabelWhenFieldLabelMissing(String fieldLabel) {
            // The production service normalizes a null/blank field label to the generic
            // "Date" so the diagnostic message is always meaningful and never NPEs.
            assertThatThrownBy(() -> service.validateAndParseDate("not-a-date", fieldLabel))
                    .isInstanceOf(ValidationException.class)
                    .satisfies(thrown -> assertThat(thrown.getMessage())
                            .isNotBlank()
                            .startsWith("Date"));
        }
    }
}
