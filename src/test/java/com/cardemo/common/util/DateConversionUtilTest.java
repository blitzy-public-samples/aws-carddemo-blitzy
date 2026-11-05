package com.cardemo.common.util;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comprehensive JUnit 5 unit tests for {@link DateConversionUtil}.
 *
 * <p>Validates the faithful translation of COBOL date handling from:
 * <ul>
 *   <li>CSUTLDTC.cbl — CEEDAYS API wrapper with 9 feedback code categories</li>
 *   <li>CSDAT01Y.cpy — Date/time working storage (WS-DATE-TIME structure)</li>
 *   <li>CSUTLDWY.cpy — 88-level validation conditions (century, month, day, leap year)</li>
 *   <li>CSUTLDPY.cpy — Date edit procedures with leap year calculation</li>
 * </ul>
 *
 * <p>Testing conventions:
 * <ul>
 *   <li>Pure unit tests — NO Spring context required</li>
 *   <li>AssertJ {@code assertThat()} for all fluent assertions</li>
 *   <li>Static method calls — utility class, no DI</li>
 *   <li>Parameterized tests for boundary/parity coverage</li>
 * </ul>
 */
@DisplayName("DateConversionUtil Tests")
class DateConversionUtilTest {

    // ========================================================================
    // Phase 2: CCYYMMDD → MM/DD/YYYY Conversion Tests
    // ========================================================================

    @Nested
    @DisplayName("CCYYMMDD to Display Conversion")
    class CcyymmddToDisplayConversionTest {

        @ParameterizedTest(name = "CCYYMMDD {0} → Display {1}")
        @DisplayName("Standard CCYYMMDD to MM/DD/YYYY conversions")
        @CsvSource({
                "20231215, 12/15/2023",
                "20240101, 01/01/2024",
                "20241231, 12/31/2024",
                "19990515, 05/15/1999",
                "20000229, 02/29/2000",
                "19000101, 01/01/1900",
                "20000101, 01/01/2000"
        })
        void shouldConvertCcyymmddToDisplay(String ccyymmdd, String expectedDisplay) {
            String result = DateConversionUtil.convertCcyymmddToDisplay(ccyymmdd);
            assertThat(result).isEqualTo(expectedDisplay);
        }

        @Test
        @DisplayName("Round-trip CCYYMMDD → Display → CCYYMMDD preserves original value")
        void shouldRoundTripCcyymmddThroughDisplay() {
            String original = "20231215";
            String display = DateConversionUtil.convertCcyymmddToDisplay(original);
            String roundTrip = DateConversionUtil.convertDisplayToCcyymmdd(display);
            assertThat(roundTrip).isEqualTo(original);
        }

        @Test
        @DisplayName("Start of century boundary 19000101")
        void shouldConvertCenturyStart() {
            String result = DateConversionUtil.convertCcyymmddToDisplay("19000101");
            assertThat(result).isEqualTo("01/01/1900");
        }

        @Test
        @DisplayName("Y2K date 20000101")
        void shouldConvertY2kDate() {
            String result = DateConversionUtil.convertCcyymmddToDisplay("20000101");
            assertThat(result).isEqualTo("01/01/2000");
        }

        @Test
        @DisplayName("Leap year century boundary 20000229")
        void shouldConvertLeapYearCenturyDate() {
            String result = DateConversionUtil.convertCcyymmddToDisplay("20000229");
            assertThat(result).isEqualTo("02/29/2000");
        }
    }

    // ========================================================================
    // Phase 2: MM/DD/YYYY → CCYYMMDD Conversion Tests
    // ========================================================================

    @Nested
    @DisplayName("Display to CCYYMMDD Conversion")
    class DisplayToCcyymmddConversionTest {

        @ParameterizedTest(name = "Display {0} → CCYYMMDD {1}")
        @DisplayName("Standard MM/DD/YYYY to CCYYMMDD conversions")
        @CsvSource({
                "12/15/2023, 20231215",
                "01/01/2024, 20240101",
                "02/29/2000, 20000229",
                "05/15/1999, 19990515",
                "12/31/2024, 20241231",
                "01/01/1900, 19000101"
        })
        void shouldConvertDisplayToCcyymmdd(String display, String expectedCcyymmdd) {
            String result = DateConversionUtil.convertDisplayToCcyymmdd(display);
            assertThat(result).isEqualTo(expectedCcyymmdd);
        }

        @Test
        @DisplayName("Round-trip Display → CCYYMMDD → Display preserves original value")
        void shouldRoundTripDisplayThroughCcyymmdd() {
            String original = "12/15/2023";
            String ccyymmdd = DateConversionUtil.convertDisplayToCcyymmdd(original);
            String roundTrip = DateConversionUtil.convertCcyymmddToDisplay(ccyymmdd);
            assertThat(roundTrip).isEqualTo(original);
        }

        @Test
        @DisplayName("Invalid display date returns null")
        void shouldReturnNullForInvalidDisplayDate() {
            String result = DateConversionUtil.convertDisplayToCcyymmdd("13/01/2023");
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Malformed display date returns null")
        void shouldReturnNullForMalformedDisplayDate() {
            String result = DateConversionUtil.convertDisplayToCcyymmdd("not-a-date");
            assertThat(result).isNull();
        }
    }

    // ========================================================================
    // Phase 3: ISO-8601 Timestamp Conversion Tests
    // ========================================================================

    @Nested
    @DisplayName("ISO-8601 Timestamp Conversion")
    class Iso8601TimestampConversionTest {

        @Test
        @DisplayName("convertToIso8601 produces 26-character timestamp")
        void shouldProduceTwentySixCharTimestamp() {
            String result = DateConversionUtil.convertToIso8601("20231215");
            assertThat(result).isNotNull();
            assertThat(result).hasSize(26);
        }

        @Test
        @DisplayName("convertToIso8601 starts with formatted date and midnight time")
        void shouldStartWithDateAndMidnight() {
            String result = DateConversionUtil.convertToIso8601("20231215");
            assertThat(result).isNotNull();
            assertThat(result).startsWith("2023-12-15");
            assertThat(result).contains("00.00.00.000000");
        }

        @Test
        @DisplayName("convertToIso8601 for leap year date 20240229")
        void shouldConvertLeapYearDateToIso() {
            String result = DateConversionUtil.convertToIso8601("20240229");
            assertThat(result).isNotNull();
            assertThat(result).startsWith("2024-02-29");
        }

        @Test
        @DisplayName("convertFromIso8601 extracts CCYYMMDD from AAP format timestamp")
        void shouldExtractCcyymmddFromAapFormat() {
            String result = DateConversionUtil.convertFromIso8601("2023-12-15-10.30.45.123456");
            assertThat(result).isEqualTo("20231215");
        }

        @Test
        @DisplayName("convertFromIso8601 extracts CCYYMMDD from midnight timestamp")
        void shouldExtractCcyymmddFromMidnightTimestamp() {
            String result = DateConversionUtil.convertFromIso8601("2024-01-01-00.00.00.000000");
            assertThat(result).isEqualTo("20240101");
        }

        @Test
        @DisplayName("ISO-8601 output matches 26-character format pattern")
        void shouldMatchTwentySixCharFormatPattern() {
            String result = DateConversionUtil.convertToIso8601("20231215");
            assertThat(result).isNotNull();
            assertThat(result).hasSize(26);
            // Pattern: YYYY-MM-DD-HH.MM.SS.mmmmmm
            assertThat(result).matches("\\d{4}-\\d{2}-\\d{2}-\\d{2}\\.\\d{2}\\.\\d{2}\\.\\d{6}");
        }

        @Test
        @DisplayName("Round-trip CCYYMMDD → ISO-8601 → CCYYMMDD preserves date")
        void shouldRoundTripThroughIso8601() {
            String original = "20231215";
            String iso = DateConversionUtil.convertToIso8601(original);
            String roundTrip = DateConversionUtil.convertFromIso8601(iso);
            assertThat(roundTrip).isEqualTo(original);
        }

        @Test
        @DisplayName("convertFromIso8601 handles COBOL WS-TIMESTAMP format with space and colons")
        void shouldHandleCobolTimestampFormat() {
            String result = DateConversionUtil.convertFromIso8601("2023-12-15 10:30:45.123456");
            assertThat(result).isEqualTo("20231215");
        }

        @Test
        @DisplayName("convertToIso8601 returns null for invalid CCYYMMDD")
        void shouldReturnNullForInvalidCcyymmddIso() {
            String result = DateConversionUtil.convertToIso8601("99991301");
            assertThat(result).isNull();
        }
    }

    // ========================================================================
    // Phase 4: Date Validation - CEEDAYS Equivalent Tests
    // ========================================================================

    @Nested
    @DisplayName("Date Validation - CEEDAYS Equivalent")
    class DateValidationCeedaysEquivalentTest {

        @Test
        @DisplayName("FC-INVALID-DATE: valid date returns severity 0 and 'Date is valid'")
        void shouldReturnValidForGoodDate() {
            DateConversionUtil.DateValidationResult result =
                    DateConversionUtil.validateDate("20231215", "YYYYMMDD");
            assertThat(result.severity()).isEqualTo(0);
            assertThat(result.resultText()).contains("Date is valid");
            assertThat(result.valid()).isTrue();
            assertThat(result.messageCode()).isEqualTo(0);
        }

        @Test
        @DisplayName("FC-INSUFFICIENT-DATA: too-short input returns 'Insufficient'")
        void shouldReturnInsufficientForShortInput() {
            DateConversionUtil.DateValidationResult result =
                    DateConversionUtil.validateDate("2023", "YYYYMMDD");
            assertThat(result.resultText()).contains("Insufficient");
            assertThat(result.valid()).isFalse();
            assertThat(result.severity()).isEqualTo(3);
        }

        @Test
        @DisplayName("FC-INSUFFICIENT-DATA: empty string returns 'Insufficient'")
        void shouldReturnInsufficientForEmptyString() {
            DateConversionUtil.DateValidationResult result =
                    DateConversionUtil.validateDate("", "YYYYMMDD");
            assertThat(result.resultText()).contains("Insufficient");
            assertThat(result.valid()).isFalse();
        }

        @Test
        @DisplayName("FC-BAD-DATE-VALUE: day 32 returns 'Datevalue error'")
        void shouldReturnBadDateValueForDay32() {
            DateConversionUtil.DateValidationResult result =
                    DateConversionUtil.validateDate("20231232", "YYYYMMDD");
            assertThat(result.resultText()).contains("Datevalue error");
            assertThat(result.valid()).isFalse();
            assertThat(result.severity()).isEqualTo(3);
        }

        @Test
        @DisplayName("FC-BAD-DATE-VALUE: Feb 31 returns 'Datevalue error'")
        void shouldReturnBadDateValueForFeb31() {
            DateConversionUtil.DateValidationResult result =
                    DateConversionUtil.validateDate("20230231", "YYYYMMDD");
            assertThat(result.resultText()).contains("Datevalue error");
            assertThat(result.valid()).isFalse();
        }

        @Test
        @DisplayName("FC-INVALID-ERA: century 18 returns 'Invalid Era'")
        void shouldReturnInvalidEraForCentury18() {
            DateConversionUtil.DateValidationResult result =
                    DateConversionUtil.validateDate("18001215", "YYYYMMDD");
            assertThat(result.resultText()).contains("Invalid Era");
            assertThat(result.valid()).isFalse();
            assertThat(result.severity()).isEqualTo(3);
        }

        @Test
        @DisplayName("FC-INVALID-ERA: century 21 returns 'Invalid Era'")
        void shouldReturnInvalidEraForCentury21() {
            DateConversionUtil.DateValidationResult result =
                    DateConversionUtil.validateDate("21001215", "YYYYMMDD");
            assertThat(result.resultText()).contains("Invalid Era");
            assertThat(result.valid()).isFalse();
        }

        @Test
        @DisplayName("FC-INVALID-MONTH: month 13 returns 'Invalid month'")
        void shouldReturnInvalidMonthForMonth13() {
            DateConversionUtil.DateValidationResult result =
                    DateConversionUtil.validateDate("20231315", "YYYYMMDD");
            assertThat(result.resultText()).contains("Invalid month");
            assertThat(result.valid()).isFalse();
            assertThat(result.severity()).isEqualTo(3);
        }

        @Test
        @DisplayName("FC-INVALID-MONTH: month 00 returns 'Invalid month'")
        void shouldReturnInvalidMonthForMonth00() {
            DateConversionUtil.DateValidationResult result =
                    DateConversionUtil.validateDate("20230015", "YYYYMMDD");
            assertThat(result.resultText()).contains("Invalid month");
            assertThat(result.valid()).isFalse();
        }

        @Test
        @DisplayName("FC-NON-NUMERIC-DATA: letters in date returns 'Nonnumeric data'")
        void shouldReturnNonNumericForLettersInDate() {
            DateConversionUtil.DateValidationResult result =
                    DateConversionUtil.validateDate("2023AB15", "YYYYMMDD");
            assertThat(result.resultText()).contains("Nonnumeric data");
            assertThat(result.valid()).isFalse();
            assertThat(result.severity()).isEqualTo(3);
        }

        @Test
        @DisplayName("FC-NON-NUMERIC-DATA: all letters returns 'Nonnumeric data'")
        void shouldReturnNonNumericForAllLetters() {
            DateConversionUtil.DateValidationResult result =
                    DateConversionUtil.validateDate("ABCDEFGH", "YYYYMMDD");
            assertThat(result.resultText()).contains("Nonnumeric data");
            assertThat(result.valid()).isFalse();
        }

        @Test
        @DisplayName("FC-YEAR-IN-ERA-ZERO: year 0000 returns 'YearInEra is 0'")
        void shouldReturnYearInEraZeroForYear0000() {
            DateConversionUtil.DateValidationResult result =
                    DateConversionUtil.validateDate("00001215", "YYYYMMDD");
            assertThat(result.resultText()).contains("YearInEra is 0");
            assertThat(result.valid()).isFalse();
            assertThat(result.severity()).isEqualTo(3);
        }

        @Test
        @DisplayName("FC-BAD-PIC-STRING: unrecognized format mask returns 'Bad Pic String'")
        void shouldReturnBadPicStringForBadFormat() {
            DateConversionUtil.DateValidationResult result =
                    DateConversionUtil.validateDate("20231215", "ZZZZZZZZ");
            assertThat(result.resultText()).contains("Bad Pic String");
            assertThat(result.valid()).isFalse();
            assertThat(result.severity()).isEqualTo(3);
        }

        @ParameterizedTest(name = "Valid date {0} should pass CEEDAYS validation")
        @DisplayName("Multiple valid dates pass validation")
        @ValueSource(strings = {"19000101", "20000229", "20241231", "19991231"})
        void shouldValidateMultipleValidDates(String date) {
            DateConversionUtil.DateValidationResult result =
                    DateConversionUtil.validateDate(date, "YYYYMMDD");
            assertThat(result.valid()).isTrue();
            assertThat(result.severity()).isEqualTo(0);
            assertThat(result.resultText()).contains("Date is valid");
        }

        @Test
        @DisplayName("Validation result contains tested date")
        void shouldContainTestedDate() {
            DateConversionUtil.DateValidationResult result =
                    DateConversionUtil.validateDate("20231215", "YYYYMMDD");
            assertThat(result.testedDate()).isEqualTo("20231215");
        }

        @Test
        @DisplayName("Validation result contains format used")
        void shouldContainFormatUsed() {
            DateConversionUtil.DateValidationResult result =
                    DateConversionUtil.validateDate("20231215", "YYYYMMDD");
            assertThat(result.formatUsed()).isEqualTo("YYYYMMDD");
        }

        @Test
        @DisplayName("Null format defaults to YYYYMMDD")
        void shouldDefaultFormatToYyyymmdd() {
            DateConversionUtil.DateValidationResult result =
                    DateConversionUtil.validateDate("20231215", null);
            assertThat(result.valid()).isTrue();
            assertThat(result.formatUsed()).isEqualTo("YYYYMMDD");
        }
    }

    // ========================================================================
    // Phase 5: CCYYMMDD Validation Tests (88-level conditions)
    // ========================================================================

    @Nested
    @DisplayName("CCYYMMDD Validation - isValidCcyymmdd")
    class CcyymmddValidationTest {

        // --- Valid dates ---

        @ParameterizedTest(name = "Valid CCYYMMDD date: {0}")
        @DisplayName("Known valid dates should return true")
        @ValueSource(strings = {
                "20231215", "19991231", "20000101",
                "20240229", "19000101", "20001231"
        })
        void shouldAcceptValidDates(String date) {
            assertThat(DateConversionUtil.isValidCcyymmdd(date)).isTrue();
        }

        // --- Century validation (only 19 and 20) ---

        @Test
        @DisplayName("Century 20 is valid")
        void shouldAcceptCentury20() {
            assertThat(DateConversionUtil.isValidCcyymmdd("20231215")).isTrue();
        }

        @Test
        @DisplayName("Century 19 is valid")
        void shouldAcceptCentury19() {
            assertThat(DateConversionUtil.isValidCcyymmdd("19991231")).isTrue();
        }

        @Test
        @DisplayName("Century 18 is INVALID")
        void shouldRejectCentury18() {
            assertThat(DateConversionUtil.isValidCcyymmdd("18001215")).isFalse();
        }

        @Test
        @DisplayName("Century 21 is INVALID")
        void shouldRejectCentury21() {
            assertThat(DateConversionUtil.isValidCcyymmdd("21001215")).isFalse();
        }

        @Test
        @DisplayName("Century 00 is INVALID")
        void shouldRejectCentury00() {
            assertThat(DateConversionUtil.isValidCcyymmdd("00011215")).isFalse();
        }

        // --- Month validation (1-12) ---

        @Test
        @DisplayName("Month 00 is INVALID")
        void shouldRejectMonth00() {
            assertThat(DateConversionUtil.isValidCcyymmdd("20230015")).isFalse();
        }

        @Test
        @DisplayName("Month 13 is INVALID")
        void shouldRejectMonth13() {
            assertThat(DateConversionUtil.isValidCcyymmdd("20231315")).isFalse();
        }

        @Test
        @DisplayName("Month 01 (January) is valid")
        void shouldAcceptMonth01() {
            assertThat(DateConversionUtil.isValidCcyymmdd("20230115")).isTrue();
        }

        @Test
        @DisplayName("Month 12 (December) is valid")
        void shouldAcceptMonth12() {
            assertThat(DateConversionUtil.isValidCcyymmdd("20231215")).isTrue();
        }

        // --- 31-day months ---

        @ParameterizedTest(name = "31-day month date {0} should be valid")
        @DisplayName("Day 31 is valid for 31-day months (Jan, Mar, May, Jul, Aug, Oct, Dec)")
        @ValueSource(strings = {
                "20230131", "20230331", "20230531",
                "20230731", "20230831", "20231031", "20231231"
        })
        void shouldAcceptDay31In31DayMonths(String date) {
            assertThat(DateConversionUtil.isValidCcyymmdd(date)).isTrue();
        }

        // --- 30-day months ---

        @Test
        @DisplayName("April 31 is INVALID (30-day month)")
        void shouldRejectApril31() {
            assertThat(DateConversionUtil.isValidCcyymmdd("20230431")).isFalse();
        }

        @Test
        @DisplayName("June 31 is INVALID (30-day month)")
        void shouldRejectJune31() {
            assertThat(DateConversionUtil.isValidCcyymmdd("20230631")).isFalse();
        }

        @Test
        @DisplayName("September 30 is valid")
        void shouldAcceptSep30() {
            assertThat(DateConversionUtil.isValidCcyymmdd("20230930")).isTrue();
        }

        @Test
        @DisplayName("November 30 is valid")
        void shouldAcceptNov30() {
            assertThat(DateConversionUtil.isValidCcyymmdd("20231130")).isTrue();
        }

        // --- February special ---

        @Test
        @DisplayName("Feb 28 in non-leap year is valid")
        void shouldAcceptFeb28NonLeap() {
            assertThat(DateConversionUtil.isValidCcyymmdd("20230228")).isTrue();
        }

        @Test
        @DisplayName("Feb 29 in non-leap year 2023 is INVALID")
        void shouldRejectFeb29NonLeap() {
            assertThat(DateConversionUtil.isValidCcyymmdd("20230229")).isFalse();
        }

        @Test
        @DisplayName("Feb 29 in leap year 2024 is valid (divisible by 4)")
        void shouldAcceptFeb29LeapYear() {
            assertThat(DateConversionUtil.isValidCcyymmdd("20240229")).isTrue();
        }

        @Test
        @DisplayName("Feb 29 in 1900 is INVALID (century year NOT divisible by 400)")
        void shouldRejectFeb29In1900() {
            assertThat(DateConversionUtil.isValidCcyymmdd("19000229")).isFalse();
        }

        @Test
        @DisplayName("Feb 29 in 2000 is valid (century year divisible by 400)")
        void shouldAcceptFeb29In2000() {
            assertThat(DateConversionUtil.isValidCcyymmdd("20000229")).isTrue();
        }

        @Test
        @DisplayName("Feb 30 is NEVER valid")
        void shouldRejectFeb30() {
            assertThat(DateConversionUtil.isValidCcyymmdd("20230230")).isFalse();
        }

        @Test
        @DisplayName("Feb 30 even in leap year is INVALID")
        void shouldRejectFeb30InLeapYear() {
            assertThat(DateConversionUtil.isValidCcyymmdd("20240230")).isFalse();
        }

        // --- Day 0 ---

        @Test
        @DisplayName("Day 00 is INVALID")
        void shouldRejectDay00() {
            assertThat(DateConversionUtil.isValidCcyymmdd("20230100")).isFalse();
        }

        // --- Non-numeric input ---

        @Test
        @DisplayName("Input with letters is INVALID")
        void shouldRejectLettersInInput() {
            assertThat(DateConversionUtil.isValidCcyymmdd("2023AB15")).isFalse();
        }

        @Test
        @DisplayName("All-letter input is INVALID")
        void shouldRejectAllLetters() {
            assertThat(DateConversionUtil.isValidCcyymmdd("ABCDEFGH")).isFalse();
        }

        // --- Wrong length ---

        @Test
        @DisplayName("Too-short input (6 chars) is INVALID")
        void shouldRejectTooShort() {
            assertThat(DateConversionUtil.isValidCcyymmdd("202312")).isFalse();
        }

        @Test
        @DisplayName("Too-long input (9 chars) is INVALID")
        void shouldRejectTooLong() {
            assertThat(DateConversionUtil.isValidCcyymmdd("202312150")).isFalse();
        }
    }

    // ========================================================================
    // Phase 6: Null and Empty Input Handling Tests
    // ========================================================================

    @Nested
    @DisplayName("Null and Empty Input Handling")
    class NullAndEmptyInputHandlingTest {

        // --- convertCcyymmddToDisplay ---

        @Test
        @DisplayName("convertCcyymmddToDisplay(null) returns null")
        void shouldHandleNullCcyymmddToDisplay() {
            assertThat(DateConversionUtil.convertCcyymmddToDisplay(null)).isNull();
        }

        @Test
        @DisplayName("convertCcyymmddToDisplay('') returns null")
        void shouldHandleEmptyCcyymmddToDisplay() {
            assertThat(DateConversionUtil.convertCcyymmddToDisplay("")).isNull();
        }

        @Test
        @DisplayName("convertCcyymmddToDisplay(8 spaces) returns null")
        void shouldHandleBlankCcyymmddToDisplay() {
            assertThat(DateConversionUtil.convertCcyymmddToDisplay("        ")).isNull();
        }

        // --- convertDisplayToCcyymmdd ---

        @Test
        @DisplayName("convertDisplayToCcyymmdd(null) returns null")
        void shouldHandleNullDisplayToCcyymmdd() {
            assertThat(DateConversionUtil.convertDisplayToCcyymmdd(null)).isNull();
        }

        @Test
        @DisplayName("convertDisplayToCcyymmdd('') returns null")
        void shouldHandleEmptyDisplayToCcyymmdd() {
            assertThat(DateConversionUtil.convertDisplayToCcyymmdd("")).isNull();
        }

        // --- convertToIso8601 ---

        @Test
        @DisplayName("convertToIso8601(null) returns null")
        void shouldHandleNullToIso() {
            assertThat(DateConversionUtil.convertToIso8601(null)).isNull();
        }

        @Test
        @DisplayName("convertToIso8601('') returns null")
        void shouldHandleEmptyToIso() {
            assertThat(DateConversionUtil.convertToIso8601("")).isNull();
        }

        // --- convertFromIso8601 ---

        @Test
        @DisplayName("convertFromIso8601(null) returns null")
        void shouldHandleNullFromIso() {
            assertThat(DateConversionUtil.convertFromIso8601(null)).isNull();
        }

        @Test
        @DisplayName("convertFromIso8601('') returns null")
        void shouldHandleEmptyFromIso() {
            assertThat(DateConversionUtil.convertFromIso8601("")).isNull();
        }

        // --- isValidCcyymmdd ---

        @Test
        @DisplayName("isValidCcyymmdd(null) returns false")
        void shouldReturnFalseForNullIsValid() {
            assertThat(DateConversionUtil.isValidCcyymmdd(null)).isFalse();
        }

        @Test
        @DisplayName("isValidCcyymmdd('') returns false")
        void shouldReturnFalseForEmptyIsValid() {
            assertThat(DateConversionUtil.isValidCcyymmdd("")).isFalse();
        }

        @Test
        @DisplayName("isValidCcyymmdd(8 spaces) returns false")
        void shouldReturnFalseForBlankIsValid() {
            assertThat(DateConversionUtil.isValidCcyymmdd("        ")).isFalse();
        }

        // --- validateDate ---

        @Test
        @DisplayName("validateDate(null, 'YYYYMMDD') returns graceful result")
        void shouldHandleNullValidateDate() {
            DateConversionUtil.DateValidationResult result =
                    DateConversionUtil.validateDate(null, "YYYYMMDD");
            assertThat(result).isNotNull();
            assertThat(result.valid()).isFalse();
            assertThat(result.resultText()).contains("Insufficient");
        }

        @Test
        @DisplayName("validateDate('', 'YYYYMMDD') returns 'Insufficient'")
        void shouldHandleEmptyValidateDate() {
            DateConversionUtil.DateValidationResult result =
                    DateConversionUtil.validateDate("", "YYYYMMDD");
            assertThat(result).isNotNull();
            assertThat(result.valid()).isFalse();
            assertThat(result.resultText()).contains("Insufficient");
        }

        @Test
        @DisplayName("validateDate(8 spaces, 'YYYYMMDD') handles blanks gracefully")
        void shouldHandleBlankValidateDate() {
            DateConversionUtil.DateValidationResult result =
                    DateConversionUtil.validateDate("        ", "YYYYMMDD");
            assertThat(result).isNotNull();
            assertThat(result.valid()).isFalse();
        }

        // --- NullAndEmptySource parameterized test ---

        @ParameterizedTest(name = "isValidCcyymmdd({0}) returns false")
        @DisplayName("isValidCcyymmdd with null and empty source")
        @NullAndEmptySource
        void shouldReturnFalseForNullAndEmptyIsValid(String input) {
            assertThat(DateConversionUtil.isValidCcyymmdd(input)).isFalse();
        }

        @ParameterizedTest(name = "convertCcyymmddToDisplay({0}) returns null")
        @DisplayName("convertCcyymmddToDisplay with null and empty source")
        @NullAndEmptySource
        void shouldReturnNullForNullAndEmptyConvertToDisplay(String input) {
            assertThat(DateConversionUtil.convertCcyymmddToDisplay(input)).isNull();
        }
    }

    // ========================================================================
    // Phase 7: Current Date/Time Method Tests
    // ========================================================================

    @Nested
    @DisplayName("Current Date/Time Methods")
    class CurrentDateTimeMethodsTest {

        @Test
        @DisplayName("getCurrentTimestamp returns non-null 26-character ISO timestamp")
        void shouldReturnValidCurrentTimestamp() {
            String result = DateConversionUtil.getCurrentTimestamp();
            assertThat(result).isNotNull();
            assertThat(result).hasSize(26);
        }

        @Test
        @DisplayName("getCurrentTimestamp matches ISO-8601 pattern")
        void shouldMatchTimestampPattern() {
            String result = DateConversionUtil.getCurrentTimestamp();
            assertThat(result).matches("\\d{4}-\\d{2}-\\d{2}-\\d{2}\\.\\d{2}\\.\\d{2}\\.\\d{6}");
        }

        @Test
        @DisplayName("getCurrentTimestamp year matches current year")
        void shouldHaveCurrentYearInTimestamp() {
            String result = DateConversionUtil.getCurrentTimestamp();
            int currentYear = LocalDate.now().getYear();
            String yearStr = result.substring(0, 4);
            assertThat(Integer.parseInt(yearStr)).isEqualTo(currentYear);
        }

        @Test
        @DisplayName("getCurrentDateCcyymmdd returns non-null 8-character numeric string")
        void shouldReturnValidCurrentDateCcyymmdd() {
            String result = DateConversionUtil.getCurrentDateCcyymmdd();
            assertThat(result).isNotNull();
            assertThat(result).hasSize(8);
            assertThat(result).matches("\\d{8}");
        }

        @Test
        @DisplayName("getCurrentDateCcyymmdd starts with current century 20")
        void shouldStartWithCurrentCentury() {
            String result = DateConversionUtil.getCurrentDateCcyymmdd();
            assertThat(result).startsWith("20");
        }

        @Test
        @DisplayName("getCurrentDateCcyymmdd contains current year")
        void shouldContainCurrentYearInCcyymmdd() {
            String result = DateConversionUtil.getCurrentDateCcyymmdd();
            int currentYear = LocalDate.now().getYear();
            assertThat(result).startsWith(String.valueOf(currentYear));
        }

        @Test
        @DisplayName("getCurrentDateDisplay returns non-null 10-character formatted string")
        void shouldReturnValidCurrentDateDisplay() {
            String result = DateConversionUtil.getCurrentDateDisplay();
            assertThat(result).isNotNull();
            assertThat(result).hasSize(10);
        }

        @Test
        @DisplayName("getCurrentDateDisplay matches MM/DD/YYYY pattern")
        void shouldMatchDisplayPattern() {
            String result = DateConversionUtil.getCurrentDateDisplay();
            assertThat(result).matches("\\d{2}/\\d{2}/\\d{4}");
        }

        @Test
        @DisplayName("getCurrentDateDisplay has '/' separators at correct positions")
        void shouldHaveSlashSeparatorsAtCorrectPositions() {
            String result = DateConversionUtil.getCurrentDateDisplay();
            assertThat(result.charAt(2)).isEqualTo('/');
            assertThat(result.charAt(5)).isEqualTo('/');
        }

        @Test
        @DisplayName("getCurrentDateDisplay year matches current year")
        void shouldHaveCurrentYearInDisplay() {
            String result = DateConversionUtil.getCurrentDateDisplay();
            int currentYear = LocalDate.now().getYear();
            String yearStr = result.substring(6, 10);
            assertThat(Integer.parseInt(yearStr)).isEqualTo(currentYear);
        }
    }

    // ========================================================================
    // Phase 8: Edge Cases and Boundaries
    // ========================================================================

    @Nested
    @DisplayName("Edge Cases and Boundaries")
    class EdgeCasesAndBoundariesTest {

        // --- Leap year boundary tests ---

        @Test
        @DisplayName("2024 is a leap year (divisible by 4)")
        void shouldIdentify2024AsLeapYear() {
            assertThat(DateConversionUtil.isValidCcyymmdd("20240229")).isTrue();
        }

        @Test
        @DisplayName("2023 is NOT a leap year")
        void shouldIdentify2023AsNonLeapYear() {
            assertThat(DateConversionUtil.isValidCcyymmdd("20230229")).isFalse();
        }

        @Test
        @DisplayName("2000 is a leap year (century divisible by 400)")
        void shouldIdentify2000AsLeapYear() {
            assertThat(DateConversionUtil.isValidCcyymmdd("20000229")).isTrue();
        }

        @Test
        @DisplayName("1900 is NOT a leap year (century NOT divisible by 400)")
        void shouldIdentify1900AsNonLeapYear() {
            assertThat(DateConversionUtil.isValidCcyymmdd("19000229")).isFalse();
        }

        // --- Month boundary tests ---

        @ParameterizedTest(name = "Month boundary: {0} should be valid")
        @DisplayName("Maximum valid day for each month")
        @CsvSource({
                "20230131",
                "20230228",
                "20230331",
                "20230430",
                "20230531",
                "20230630",
                "20230731",
                "20230831",
                "20230930",
                "20231031",
                "20231130",
                "20231231"
        })
        void shouldAcceptMaxDayForEachMonth(String date) {
            assertThat(DateConversionUtil.isValidCcyymmdd(date)).isTrue();
        }

        @Test
        @DisplayName("Feb 29 in leap year 2024 is valid (month boundary)")
        void shouldAcceptFeb29InLeapYearBoundary() {
            assertThat(DateConversionUtil.isValidCcyymmdd("20240229")).isTrue();
        }

        // --- First and last valid dates ---

        @Test
        @DisplayName("First valid date 19000101 is accepted")
        void shouldAcceptFirstValidDate() {
            assertThat(DateConversionUtil.isValidCcyymmdd("19000101")).isTrue();
        }

        @Test
        @DisplayName("Last valid date 20991231 is accepted")
        void shouldAcceptLastValidDate() {
            assertThat(DateConversionUtil.isValidCcyymmdd("20991231")).isTrue();
        }

        // --- Century boundary ---

        @Test
        @DisplayName("Century boundary: 19991231 is valid")
        void shouldAcceptEndOfCentury19() {
            assertThat(DateConversionUtil.isValidCcyymmdd("19991231")).isTrue();
        }

        @Test
        @DisplayName("Century boundary: 20000101 is valid")
        void shouldAcceptStartOfCentury20() {
            assertThat(DateConversionUtil.isValidCcyymmdd("20000101")).isTrue();
        }

        // --- Conversion edge cases ---

        @Test
        @DisplayName("convertCcyymmddToDisplay returns null for invalid date 20230229")
        void shouldReturnNullForInvalidFebDateConversion() {
            String result = DateConversionUtil.convertCcyymmddToDisplay("20230229");
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("convertToIso8601 returns null for blank input")
        void shouldReturnNullForBlankIsoConversion() {
            String result = DateConversionUtil.convertToIso8601("        ");
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("convertFromIso8601 returns null for malformed timestamp")
        void shouldReturnNullForMalformedTimestamp() {
            String result = DateConversionUtil.convertFromIso8601("not-a-timestamp-at-all!!!");
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("validateDate with MM/DD/YYYY format validates correctly")
        void shouldValidateWithDisplayFormat() {
            DateConversionUtil.DateValidationResult result =
                    DateConversionUtil.validateDate("12/15/2023", "MM/DD/YYYY");
            assertThat(result.valid()).isTrue();
            assertThat(result.resultText()).contains("Date is valid");
        }

        @Test
        @DisplayName("Conversion consistency: CCYYMMDD → Display → CCYYMMDD for all valid months")
        void shouldRoundTripAllValidMonths() {
            String[] dates = {
                    "20230115", "20230215", "20230315", "20230415",
                    "20230515", "20230615", "20230715", "20230815",
                    "20230915", "20231015", "20231115", "20231215"
            };
            for (String ccyymmdd : dates) {
                String display = DateConversionUtil.convertCcyymmddToDisplay(ccyymmdd);
                assertThat(display).isNotNull();
                String roundTrip = DateConversionUtil.convertDisplayToCcyymmdd(display);
                assertThat(roundTrip).isEqualTo(ccyymmdd);
            }
        }
    }
}
