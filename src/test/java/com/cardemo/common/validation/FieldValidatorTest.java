package com.cardemo.common.validation;

import com.cardemo.common.util.DateConversionUtil;
import com.cardemo.common.validation.FieldValidator.FieldValidationFlags;
import com.cardemo.common.validation.FieldValidator.ValidationResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for FieldValidator — translated from CSUTLDPY.cpy validation paragraphs.
 * Each test maps to a specific COBOL paragraph per the traceability matrix.
 *
 * <p>Organized into 15 {@link Nested} inner classes corresponding to:
 * <ol>
 *   <li>EDIT-DATE-CCYYMMDD (full orchestration)</li>
 *   <li>EDIT-YEAR-CCYY (lines 25-90)</li>
 *   <li>EDIT-MONTH (lines 91-147)</li>
 *   <li>EDIT-DAY (lines 150-207)</li>
 *   <li>EDIT-DAY-MONTH-YEAR (lines 209-282)</li>
 *   <li>EDIT-DATE-LE (lines 284-331)</li>
 *   <li>EDIT-DATE-OF-BIRTH (lines 341-372)</li>
 *   <li>IS-NUMERIC utility</li>
 *   <li>IS-BLANK-OR-NULL utility</li>
 *   <li>VALIDATE-NUMERIC-FIELD</li>
 *   <li>VALIDATE-REQUIRED-FIELD</li>
 *   <li>VALIDATE-FIELD-LENGTH</li>
 *   <li>Error message exact string verification</li>
 *   <li>Date boundary parameterized tests</li>
 *   <li>FieldValidationFlags unit tests</li>
 * </ol>
 *
 * @see FieldValidator
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FieldValidator — CSUTLDPY.cpy validation parity tests")
class FieldValidatorTest {

    /** Maps COBOL WS-EDIT-VARIABLE-NAME for test methods. */
    private static final String VARIABLE_NAME = "TestField";

    /** CCYYMMDD formatter for dynamic date generation in date-of-birth tests. */
    private static final DateTimeFormatter CCYYMMDD_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private FieldValidator validator;

    @BeforeEach
    void setUp() {
        validator = new FieldValidator();
    }

    // ========================================================================
    // 1. EditDateCcyymmddTests — CSUTLDPY.cpy lines 18-20
    // ========================================================================

    @Nested
    @DisplayName("EDIT-DATE-CCYYMMDD — full orchestration (lines 18-20)")
    class EditDateCcyymmddTests {

        @Test
        @DisplayName("Valid date 20231215 passes all checks")
        void validDatePassesAllChecks() {
            try (MockedStatic<DateConversionUtil> mocked = Mockito.mockStatic(DateConversionUtil.class)) {
                mocked.when(() -> DateConversionUtil.validateDate("20231215", "YYYYMMDD"))
                        .thenReturn(new DateConversionUtil.DateValidationResult(
                                0, 0, "VALID", "20231215", "YYYYMMDD", true));

                ValidationResult result = validator.editDateCcyymmdd(VARIABLE_NAME, "20231215");

                assertThat(result.hasError()).isFalse();
                assertThat(result.getFlags()).isNotNull();
                assertThat(result.getFlags().isDateValid()).isTrue();
            }
        }

        @Test
        @DisplayName("Valid date January 31st passes")
        void validDateJanuary31st() {
            try (MockedStatic<DateConversionUtil> mocked = Mockito.mockStatic(DateConversionUtil.class)) {
                mocked.when(() -> DateConversionUtil.validateDate("20230131", "YYYYMMDD"))
                        .thenReturn(new DateConversionUtil.DateValidationResult(
                                0, 0, "VALID", "20230131", "YYYYMMDD", true));

                ValidationResult result = validator.editDateCcyymmdd(VARIABLE_NAME, "20230131");

                assertThat(result.hasError()).isFalse();
            }
        }

        @Test
        @DisplayName("Valid date February 28th non-leap year passes")
        void validDateFebruary28th() {
            try (MockedStatic<DateConversionUtil> mocked = Mockito.mockStatic(DateConversionUtil.class)) {
                mocked.when(() -> DateConversionUtil.validateDate("20230228", "YYYYMMDD"))
                        .thenReturn(new DateConversionUtil.DateValidationResult(
                                0, 0, "VALID", "20230228", "YYYYMMDD", true));

                ValidationResult result = validator.editDateCcyymmdd(VARIABLE_NAME, "20230228");

                assertThat(result.hasError()).isFalse();
            }
        }

        @Test
        @DisplayName("Valid date February 29th leap year 2024 passes")
        void validDateLeapYear() {
            try (MockedStatic<DateConversionUtil> mocked = Mockito.mockStatic(DateConversionUtil.class)) {
                mocked.when(() -> DateConversionUtil.validateDate("20240229", "YYYYMMDD"))
                        .thenReturn(new DateConversionUtil.DateValidationResult(
                                0, 0, "VALID", "20240229", "YYYYMMDD", true));

                ValidationResult result = validator.editDateCcyymmdd(VARIABLE_NAME, "20240229");

                assertThat(result.hasError()).isFalse();
            }
        }

        @Test
        @DisplayName("Valid date February 29th century leap year 2000 passes")
        void validDateCenturyLeapYear2000() {
            try (MockedStatic<DateConversionUtil> mocked = Mockito.mockStatic(DateConversionUtil.class)) {
                mocked.when(() -> DateConversionUtil.validateDate("20000229", "YYYYMMDD"))
                        .thenReturn(new DateConversionUtil.DateValidationResult(
                                0, 0, "VALID", "20000229", "YYYYMMDD", true));

                ValidationResult result = validator.editDateCcyymmdd(VARIABLE_NAME, "20000229");

                assertThat(result.hasError()).isFalse();
            }
        }

        @Test
        @DisplayName("Valid date December 31st 1999 (century 19) passes")
        void validDateLastCentury() {
            try (MockedStatic<DateConversionUtil> mocked = Mockito.mockStatic(DateConversionUtil.class)) {
                mocked.when(() -> DateConversionUtil.validateDate("19991231", "YYYYMMDD"))
                        .thenReturn(new DateConversionUtil.DateValidationResult(
                                0, 0, "VALID", "19991231", "YYYYMMDD", true));

                ValidationResult result = validator.editDateCcyymmdd(VARIABLE_NAME, "19991231");

                assertThat(result.hasError()).isFalse();
            }
        }

        @ParameterizedTest(name = "Valid date {0} for month max day passes all checks")
        @CsvSource({
                "20230131", "20230228", "20230331", "20230430",
                "20230531", "20230630", "20230731", "20230831",
                "20230930", "20231031", "20231130", "20231231"
        })
        @DisplayName("All 12 months' valid maximum day dates pass")
        void validDatesAllMonths(String date) {
            try (MockedStatic<DateConversionUtil> mocked = Mockito.mockStatic(DateConversionUtil.class)) {
                mocked.when(() -> DateConversionUtil.validateDate(date, "YYYYMMDD"))
                        .thenReturn(new DateConversionUtil.DateValidationResult(
                                0, 0, "VALID", date, "YYYYMMDD", true));

                ValidationResult result = validator.editDateCcyymmdd(VARIABLE_NAME, date);

                assertThat(result.hasError()).isFalse();
            }
        }

        @Test
        @DisplayName("Blank date (8 spaces) fails at year check")
        void blankDateFailsAtYearCheck() {
            ValidationResult result = validator.editDateCcyymmdd(VARIABLE_NAME, "        ");

            assertThat(result.hasError()).isTrue();
            assertThat(result.getReturnMessage()).isEqualTo("TestField : Year must be supplied.");
        }

        @Test
        @DisplayName("Null date fails at year check")
        void nullDateFailsAtYearCheck() {
            ValidationResult result = validator.editDateCcyymmdd(VARIABLE_NAME, null);

            assertThat(result.hasError()).isTrue();
            assertThat(result.getReturnMessage()).isEqualTo("TestField : Year must be supplied.");
        }
    }

    // ========================================================================
    // 2. EditYearCcyyTests — CSUTLDPY.cpy lines 25-90
    // ========================================================================

    @Nested
    @DisplayName("EDIT-YEAR-CCYY — year validation (lines 25-90)")
    class EditYearCcyyTests {

        @Test
        @DisplayName("Blank year (4 spaces) sets BLANK flag — line 30-42")
        void yearBlankSetsBlankFlag() {
            ValidationResult result = validator.editYearCcyy(VARIABLE_NAME, "    ");

            assertThat(result.hasError()).isTrue();
            assertThat(result.getFlags()).isNotNull();
            assertThat(result.getFlags().getYearFlag()).isEqualTo('B');
            assertThat(result.getReturnMessage()).isEqualTo("TestField : Year must be supplied.");
        }

        @Test
        @DisplayName("Null year sets BLANK flag")
        void yearNullSetsBlankFlag() {
            ValidationResult result = validator.editYearCcyy(VARIABLE_NAME, null);

            assertThat(result.hasError()).isTrue();
            assertThat(result.getFlags()).isNotNull();
            assertThat(result.getFlags().getYearFlag()).isEqualTo('B');
            assertThat(result.getReturnMessage()).isEqualTo("TestField : Year must be supplied.");
        }

        @Test
        @DisplayName("Empty string year sets BLANK flag")
        void yearEmptyStringSetsBlankFlag() {
            ValidationResult result = validator.editYearCcyy(VARIABLE_NAME, "");

            assertThat(result.hasError()).isTrue();
            assertThat(result.getFlags()).isNotNull();
            assertThat(result.getFlags().getYearFlag()).isEqualTo('B');
            assertThat(result.getReturnMessage()).isEqualTo("TestField : Year must be supplied.");
        }

        @Test
        @DisplayName("Non-numeric year ABCD sets NOT-OK flag — line 48-58")
        void yearNotNumericSetsNotOkFlag() {
            ValidationResult result = validator.editYearCcyy(VARIABLE_NAME, "ABCD");

            assertThat(result.hasError()).isTrue();
            assertThat(result.getFlags()).isNotNull();
            assertThat(result.getFlags().getYearFlag()).isEqualTo('0');
            assertThat(result.getReturnMessage()).isEqualTo("TestField must be 4 digit number.");
        }

        @Test
        @DisplayName("Mixed alphanumeric year 20A3 sets NOT-OK flag")
        void yearMixedAlphaNumericSetsNotOkFlag() {
            ValidationResult result = validator.editYearCcyy(VARIABLE_NAME, "20A3");

            assertThat(result.hasError()).isTrue();
            assertThat(result.getFlags()).isNotNull();
            assertThat(result.getFlags().getYearFlag()).isEqualTo('0');
            assertThat(result.getReturnMessage()).isEqualTo("TestField must be 4 digit number.");
        }

        @Test
        @DisplayName("Century 21 (2100) sets NOT-OK flag — line 70-84")
        void centuryNotValidSetsNotOkFlag_2100() {
            ValidationResult result = validator.editYearCcyy(VARIABLE_NAME, "2100");

            assertThat(result.hasError()).isTrue();
            assertThat(result.getFlags()).isNotNull();
            assertThat(result.getFlags().getYearFlag()).isEqualTo('0');
            assertThat(result.getReturnMessage()).isEqualTo("TestField : Century is not valid.");
        }

        @Test
        @DisplayName("Century 18 (1800) sets NOT-OK flag")
        void centuryNotValidSetsNotOkFlag_1800() {
            ValidationResult result = validator.editYearCcyy(VARIABLE_NAME, "1800");

            assertThat(result.hasError()).isTrue();
            assertThat(result.getFlags()).isNotNull();
            assertThat(result.getFlags().getYearFlag()).isEqualTo('0');
            assertThat(result.getReturnMessage()).isEqualTo("TestField : Century is not valid.");
        }

        @Test
        @DisplayName("Century 00 (0099) sets NOT-OK flag")
        void centuryNotValidSetsNotOkFlag_0000() {
            ValidationResult result = validator.editYearCcyy(VARIABLE_NAME, "0099");

            assertThat(result.hasError()).isTrue();
            assertThat(result.getFlags()).isNotNull();
            assertThat(result.getFlags().getYearFlag()).isEqualTo('0');
            assertThat(result.getReturnMessage()).isEqualTo("TestField : Century is not valid.");
        }

        @ParameterizedTest(name = "Year {0} with valid century passes")
        @CsvSource({"1900", "1901", "1950", "1999", "2000", "2001", "2023", "2099"})
        @DisplayName("Valid centuries 19 and 20 accepted")
        void validCenturies(String ccyy) {
            ValidationResult result = validator.editYearCcyy(VARIABLE_NAME, ccyy);

            assertThat(result.hasError()).isFalse();
            assertThat(result.getFlags()).isNotNull();
            assertThat(result.getFlags().getYearFlag()).isEqualTo('\0');
            assertThat(result.getFlags().isYearValid()).isTrue();
        }
    }

    // ========================================================================
    // 3. EditMonthTests — CSUTLDPY.cpy lines 91-147
    // ========================================================================

    @Nested
    @DisplayName("EDIT-MONTH — month validation (lines 91-147)")
    class EditMonthTests {

        @Test
        @DisplayName("Blank month (2 spaces) sets BLANK flag — line 94-105")
        void monthBlankSetsBlankFlag() {
            ValidationResult result = validator.editMonth(VARIABLE_NAME, "  ");

            assertThat(result.hasError()).isTrue();
            assertThat(result.getFlags()).isNotNull();
            assertThat(result.getFlags().getMonthFlag()).isEqualTo('B');
            assertThat(result.getReturnMessage()).isEqualTo("TestField : Month must be supplied.");
        }

        @Test
        @DisplayName("Null month sets BLANK flag")
        void monthNullSetsBlankFlag() {
            ValidationResult result = validator.editMonth(VARIABLE_NAME, null);

            assertThat(result.hasError()).isTrue();
            assertThat(result.getFlags()).isNotNull();
            assertThat(result.getFlags().getMonthFlag()).isEqualTo('B');
            assertThat(result.getReturnMessage()).isEqualTo("TestField : Month must be supplied.");
        }

        @Test
        @DisplayName("Non-numeric month AB sets NOT-OK flag — line 126-141")
        void monthNotNumericSetsNotOkFlag() {
            ValidationResult result = validator.editMonth(VARIABLE_NAME, "AB");

            assertThat(result.hasError()).isTrue();
            assertThat(result.getFlags()).isNotNull();
            assertThat(result.getFlags().getMonthFlag()).isEqualTo('0');
            assertThat(result.getReturnMessage())
                    .isEqualTo("TestField: Month must be a number between 1 and 12.");
        }

        @Test
        @DisplayName("Month 13 out of range sets NOT-OK flag — line 111-124")
        void monthOutOfRange_13_SetsNotOkFlag() {
            ValidationResult result = validator.editMonth(VARIABLE_NAME, "13");

            assertThat(result.hasError()).isTrue();
            assertThat(result.getFlags()).isNotNull();
            assertThat(result.getFlags().getMonthFlag()).isEqualTo('0');
            assertThat(result.getReturnMessage())
                    .isEqualTo("TestField: Month must be a number between 1 and 12.");
        }

        @Test
        @DisplayName("Month 00 out of range sets NOT-OK flag")
        void monthOutOfRange_00_SetsNotOkFlag() {
            ValidationResult result = validator.editMonth(VARIABLE_NAME, "00");

            assertThat(result.hasError()).isTrue();
            assertThat(result.getFlags()).isNotNull();
            assertThat(result.getFlags().getMonthFlag()).isEqualTo('0');
            assertThat(result.getReturnMessage())
                    .isEqualTo("TestField: Month must be a number between 1 and 12.");
        }

        @ParameterizedTest(name = "Month {0} is valid")
        @CsvSource({"01", "02", "03", "04", "05", "06", "07", "08", "09", "10", "11", "12"})
        @DisplayName("All 12 months accepted")
        void validMonths(String mm) {
            ValidationResult result = validator.editMonth(VARIABLE_NAME, mm);

            assertThat(result.hasError()).isFalse();
            assertThat(result.getFlags()).isNotNull();
            assertThat(result.getFlags().getMonthFlag()).isEqualTo('\0');
            assertThat(result.getFlags().isMonthValid()).isTrue();
        }
    }

    // ========================================================================
    // 4. EditDayTests — CSUTLDPY.cpy lines 150-207
    // ========================================================================

    @Nested
    @DisplayName("EDIT-DAY — day validation (lines 150-207)")
    class EditDayTests {

        @Test
        @DisplayName("Blank day (2 spaces) sets DAY-BLANK flag — line 154-165")
        void dayBlankSetsDayBlankFlag() {
            ValidationResult result = validator.editDay(VARIABLE_NAME, "  ");

            assertThat(result.hasError()).isTrue();
            assertThat(result.getFlags()).isNotNull();
            assertThat(result.getFlags().getDayFlag()).isEqualTo('B');
            assertThat(result.getReturnMessage()).isEqualTo("TestField : Day must be supplied.");
        }

        @Test
        @DisplayName("Null day sets DAY-BLANK flag")
        void dayNullSetsDayBlankFlag() {
            ValidationResult result = validator.editDay(VARIABLE_NAME, null);

            assertThat(result.hasError()).isTrue();
            assertThat(result.getFlags()).isNotNull();
            assertThat(result.getFlags().getDayFlag()).isEqualTo('B');
            assertThat(result.getReturnMessage()).isEqualTo("TestField : Day must be supplied.");
        }

        @Test
        @DisplayName("Non-numeric day AB sets NOT-OK flag — line 170-185")
        void dayNotNumericSetsNotOkFlag() {
            ValidationResult result = validator.editDay(VARIABLE_NAME, "AB");

            assertThat(result.hasError()).isTrue();
            assertThat(result.getFlags()).isNotNull();
            assertThat(result.getFlags().getDayFlag()).isEqualTo('0');
            assertThat(result.getReturnMessage())
                    .isEqualTo("TestField:day must be a number between 1 and 31.");
        }

        @Test
        @DisplayName("Day 32 out of range sets NOT-OK flag — line 187-200")
        void dayOutOfRange_32_SetsNotOkFlag() {
            ValidationResult result = validator.editDay(VARIABLE_NAME, "32");

            assertThat(result.hasError()).isTrue();
            assertThat(result.getFlags()).isNotNull();
            assertThat(result.getFlags().getDayFlag()).isEqualTo('0');
            assertThat(result.getReturnMessage())
                    .isEqualTo("TestField:day must be a number between 1 and 31.");
        }

        @Test
        @DisplayName("Day 00 out of range sets NOT-OK flag")
        void dayOutOfRange_00_SetsNotOkFlag() {
            ValidationResult result = validator.editDay(VARIABLE_NAME, "00");

            assertThat(result.hasError()).isTrue();
            assertThat(result.getFlags()).isNotNull();
            assertThat(result.getFlags().getDayFlag()).isEqualTo('0');
            assertThat(result.getReturnMessage())
                    .isEqualTo("TestField:day must be a number between 1 and 31.");
        }

        @ParameterizedTest(name = "Day {0} is valid")
        @CsvSource({"01", "15", "28", "29", "30", "31"})
        @DisplayName("Valid days in 1-31 range accepted")
        void validDays(String dd) {
            ValidationResult result = validator.editDay(VARIABLE_NAME, dd);

            assertThat(result.hasError()).isFalse();
            assertThat(result.getFlags()).isNotNull();
            assertThat(result.getFlags().getDayFlag()).isEqualTo('\0');
            assertThat(result.getFlags().isDayValid()).isTrue();
        }
    }

    // ========================================================================
    // 5. EditDayMonthYearTests — CSUTLDPY.cpy lines 209-282
    // ========================================================================

    @Nested
    @DisplayName("EDIT-DAY-MONTH-YEAR — combined validation (lines 209-282)")
    class EditDayMonthYearTests {

        @Test
        @DisplayName("Day 31 in June (30-day month) fails — line 213-226")
        void day31InNon31DayMonth_June() {
            ValidationResult result = validator.editDayMonthYear(VARIABLE_NAME, "2023", "06", "31");

            assertThat(result.hasError()).isTrue();
            assertThat(result.getFlags()).isNotNull();
            assertThat(result.getFlags().getDayFlag()).isEqualTo('0');
            assertThat(result.getFlags().getMonthFlag()).isEqualTo('0');
            assertThat(result.getReturnMessage())
                    .isEqualTo("TestField:Cannot have 31 days in this month.");
        }

        @Test
        @DisplayName("Day 31 in April (30-day month) fails")
        void day31InNon31DayMonth_April() {
            ValidationResult result = validator.editDayMonthYear(VARIABLE_NAME, "2023", "04", "31");

            assertThat(result.hasError()).isTrue();
            assertThat(result.getReturnMessage())
                    .isEqualTo("TestField:Cannot have 31 days in this month.");
        }

        @Test
        @DisplayName("Day 31 in September (30-day month) fails")
        void day31InNon31DayMonth_September() {
            ValidationResult result = validator.editDayMonthYear(VARIABLE_NAME, "2023", "09", "31");

            assertThat(result.hasError()).isTrue();
            assertThat(result.getReturnMessage())
                    .isEqualTo("TestField:Cannot have 31 days in this month.");
        }

        @Test
        @DisplayName("Day 31 in November (30-day month) fails")
        void day31InNon31DayMonth_November() {
            ValidationResult result = validator.editDayMonthYear(VARIABLE_NAME, "2023", "11", "31");

            assertThat(result.hasError()).isTrue();
            assertThat(result.getReturnMessage())
                    .isEqualTo("TestField:Cannot have 31 days in this month.");
        }

        @Test
        @DisplayName("Day 31 in February fails with 31-days message (not 30-days)")
        void day31InNon31DayMonth_February() {
            ValidationResult result = validator.editDayMonthYear(VARIABLE_NAME, "2023", "02", "31");

            assertThat(result.hasError()).isTrue();
            assertThat(result.getReturnMessage())
                    .isEqualTo("TestField:Cannot have 31 days in this month.");
        }

        @Test
        @DisplayName("Day 30 in February fails — line 228-241")
        void day30InFebruary() {
            ValidationResult result = validator.editDayMonthYear(VARIABLE_NAME, "2023", "02", "30");

            assertThat(result.hasError()).isTrue();
            assertThat(result.getFlags()).isNotNull();
            assertThat(result.getFlags().getDayFlag()).isEqualTo('0');
            assertThat(result.getFlags().getMonthFlag()).isEqualTo('0');
            assertThat(result.getReturnMessage())
                    .isEqualTo("TestField:Cannot have 30 days in this month.");
        }

        @Test
        @DisplayName("Day 29 in February 2024 (leap year, YY!=0, divisor=4) passes — line 243-272")
        void day29InFebruaryLeapYear() {
            ValidationResult result = validator.editDayMonthYear(VARIABLE_NAME, "2024", "02", "29");

            assertThat(result.hasError()).isFalse();
        }

        @Test
        @DisplayName("Day 29 in February 2023 (non-leap year) fails — line 258-271")
        void day29InFebruaryNonLeapYear() {
            ValidationResult result = validator.editDayMonthYear(VARIABLE_NAME, "2023", "02", "29");

            assertThat(result.hasError()).isTrue();
            assertThat(result.getFlags()).isNotNull();
            assertThat(result.getFlags().getDayFlag()).isEqualTo('0');
            assertThat(result.getFlags().getMonthFlag()).isEqualTo('0');
            assertThat(result.getFlags().getYearFlag()).isEqualTo('0');
            assertThat(result.getReturnMessage())
                    .isEqualTo("TestField:Not a leap year.Cannot have 29 days in this month.");
        }

        @Test
        @DisplayName("Day 29 in February 2000 (century leap year, YY=0, divisor=400) passes — line 245-246")
        void day29InFebruaryCenturyLeapYear_2000() {
            ValidationResult result = validator.editDayMonthYear(VARIABLE_NAME, "2000", "02", "29");

            assertThat(result.hasError()).isFalse();
        }

        @Test
        @DisplayName("Day 29 in February 1900 (century non-leap year, 1900%400!=0) fails")
        void day29InFebruaryCenturyNonLeapYear_1900() {
            ValidationResult result = validator.editDayMonthYear(VARIABLE_NAME, "1900", "02", "29");

            assertThat(result.hasError()).isTrue();
            assertThat(result.getFlags()).isNotNull();
            assertThat(result.getFlags().getDayFlag()).isEqualTo('0');
            assertThat(result.getFlags().getMonthFlag()).isEqualTo('0');
            assertThat(result.getFlags().getYearFlag()).isEqualTo('0');
            assertThat(result.getReturnMessage())
                    .isEqualTo("TestField:Not a leap year.Cannot have 29 days in this month.");
        }

        @Test
        @DisplayName("Day 31 in January (31-day month) is valid")
        void day31In31DayMonthIsValid() {
            ValidationResult result = validator.editDayMonthYear(VARIABLE_NAME, "2023", "01", "31");

            assertThat(result.hasError()).isFalse();
        }

        @Test
        @DisplayName("Day 30 in April (30-day month) is valid")
        void day30In30DayMonthIsValid() {
            ValidationResult result = validator.editDayMonthYear(VARIABLE_NAME, "2023", "04", "30");

            assertThat(result.hasError()).isFalse();
        }
    }

    // ========================================================================
    // 6. EditDateLeTests — CSUTLDPY.cpy lines 284-331
    // ========================================================================

    @Nested
    @DisplayName("EDIT-DATE-LE — LE service validation (lines 284-331)")
    class EditDateLeTests {

        @Test
        @DisplayName("Valid date passes LE validation (severity=0)")
        void validDatePassesLeValidation() {
            try (MockedStatic<DateConversionUtil> mocked = Mockito.mockStatic(DateConversionUtil.class)) {
                mocked.when(() -> DateConversionUtil.validateDate("20231215", "YYYYMMDD"))
                        .thenReturn(new DateConversionUtil.DateValidationResult(
                                0, 0, "VALID", "20231215", "YYYYMMDD", true));

                ValidationResult result = validator.editDateLe(VARIABLE_NAME, "20231215", "YYYYMMDD");

                assertThat(result.hasError()).isFalse();
                assertThat(result.getFlags()).isNotNull();
                assertThat(result.getFlags().isDayValid()).isTrue();
            }
        }

        @Test
        @DisplayName("Invalid date fails LE validation (severity=3, messageCode=2508) — line 298-315")
        void invalidDateFailsLeValidation() {
            try (MockedStatic<DateConversionUtil> mocked = Mockito.mockStatic(DateConversionUtil.class)) {
                mocked.when(() -> DateConversionUtil.validateDate("20231315", "YYYYMMDD"))
                        .thenReturn(new DateConversionUtil.DateValidationResult(
                                3, 2508, "INVALID", "20231315", "YYYYMMDD", false));

                ValidationResult result = validator.editDateLe(VARIABLE_NAME, "20231315", "YYYYMMDD");

                assertThat(result.hasError()).isTrue();
                assertThat(result.getFlags()).isNotNull();
                assertThat(result.getFlags().getYearFlag()).isEqualTo('0');
                assertThat(result.getFlags().getMonthFlag()).isEqualTo('0');
                assertThat(result.getFlags().getDayFlag()).isEqualTo('0');
                assertThat(result.getReturnMessage())
                        .isEqualTo("TestField validation error Sev code: 3 Message code: 2508");
            }
        }

        @Test
        @DisplayName("LE validation success sets date valid flags — line 319, 327")
        void leValidationSetsDateValidOnSuccess() {
            try (MockedStatic<DateConversionUtil> mocked = Mockito.mockStatic(DateConversionUtil.class)) {
                mocked.when(() -> DateConversionUtil.validateDate("20231001", "YYYYMMDD"))
                        .thenReturn(new DateConversionUtil.DateValidationResult(
                                0, 0, "VALID", "20231001", "YYYYMMDD", true));

                ValidationResult result = validator.editDateLe(VARIABLE_NAME, "20231001", "YYYYMMDD");

                assertThat(result.hasError()).isFalse();
                assertThat(result.getFlags()).isNotNull();
                assertThat(result.getFlags().isDateValid()).isTrue();
            }
        }
    }

    // ========================================================================
    // 7. EditDateOfBirthTests — CSUTLDPY.cpy lines 341-372
    // ========================================================================

    @Nested
    @DisplayName("EDIT-DATE-OF-BIRTH — future date rejection (lines 341-372)")
    class EditDateOfBirthTests {

        @Test
        @DisplayName("Past date 19800115 is valid")
        void pastDateIsValid() {
            ValidationResult result = validator.editDateOfBirth(VARIABLE_NAME, "19800115");

            assertThat(result.hasError()).isFalse();
        }

        @Test
        @DisplayName("Future date 20991231 is rejected — line 356-367")
        void futureDateIsRejected() {
            ValidationResult result = validator.editDateOfBirth(VARIABLE_NAME, "20991231");

            assertThat(result.hasError()).isTrue();
            assertThat(result.getFlags()).isNotNull();
            assertThat(result.getFlags().getYearFlag()).isEqualTo('0');
            assertThat(result.getFlags().getMonthFlag()).isEqualTo('0');
            assertThat(result.getFlags().getDayFlag()).isEqualTo('0');
            assertThat(result.getReturnMessage())
                    .isEqualTo("TestField:cannot be in the future ");
        }

        @Test
        @DisplayName("Today's date is REJECTED — COBOL strictly-greater-than (line 350)")
        void todayDateIsRejected() {
            String todayStr = LocalDate.now().format(CCYYMMDD_FMT);

            ValidationResult result = validator.editDateOfBirth(VARIABLE_NAME, todayStr);

            assertThat(result.hasError()).isTrue();
            assertThat(result.getReturnMessage())
                    .isEqualTo("TestField:cannot be in the future ");
        }

        @Test
        @DisplayName("Yesterday's date is valid")
        void yesterdayDateIsValid() {
            String yesterdayStr = LocalDate.now().minusDays(1).format(CCYYMMDD_FMT);

            ValidationResult result = validator.editDateOfBirth(VARIABLE_NAME, yesterdayStr);

            assertThat(result.hasError()).isFalse();
        }
    }

    // ========================================================================
    // 8. NumericValidationTests
    // ========================================================================

    @Nested
    @DisplayName("isNumeric — COBOL IS NUMERIC check")
    class NumericValidationTests {

        @Test
        @DisplayName("All digits returns true")
        void isNumeric_allDigits_returnsTrue() {
            assertThat(FieldValidator.isNumeric("12345")).isTrue();
        }

        @Test
        @DisplayName("Mixed characters returns false")
        void isNumeric_mixedChars_returnsFalse() {
            assertThat(FieldValidator.isNumeric("123A5")).isFalse();
        }

        @Test
        @DisplayName("Empty string returns false")
        void isNumeric_empty_returnsFalse() {
            assertThat(FieldValidator.isNumeric("")).isFalse();
        }

        @Test
        @DisplayName("Null returns false")
        void isNumeric_null_returnsFalse() {
            assertThat(FieldValidator.isNumeric(null)).isFalse();
        }

        @Test
        @DisplayName("Spaces returns false")
        void isNumeric_spaces_returnsFalse() {
            assertThat(FieldValidator.isNumeric("   ")).isFalse();
        }

        @Test
        @DisplayName("All zeros returns true")
        void isNumeric_allZeros_returnsTrue() {
            assertThat(FieldValidator.isNumeric("00000")).isTrue();
        }
    }

    // ========================================================================
    // 9. BlankOrNullTests
    // ========================================================================

    @Nested
    @DisplayName("isBlankOrNull — COBOL LOW-VALUES/SPACES check")
    class BlankOrNullTests {

        @Test
        @DisplayName("Null returns true")
        void isBlankOrNull_null_returnsTrue() {
            assertThat(FieldValidator.isBlankOrNull(null)).isTrue();
        }

        @Test
        @DisplayName("Empty string returns true")
        void isBlankOrNull_empty_returnsTrue() {
            assertThat(FieldValidator.isBlankOrNull("")).isTrue();
        }

        @Test
        @DisplayName("Spaces only returns true")
        void isBlankOrNull_spaces_returnsTrue() {
            assertThat(FieldValidator.isBlankOrNull("   ")).isTrue();
        }

        @Test
        @DisplayName("Non-blank string returns false")
        void isBlankOrNull_nonBlank_returnsFalse() {
            assertThat(FieldValidator.isBlankOrNull("test")).isFalse();
        }
    }

    // ========================================================================
    // 10. ValidateNumericFieldTests
    // ========================================================================

    @Nested
    @DisplayName("validateNumericField — combined blank+numeric check")
    class ValidateNumericFieldTests {

        @Test
        @DisplayName("Blank field returns error")
        void blankFieldReturnsError() {
            ValidationResult result = FieldValidator.validateNumericField("CreditLimit", "   ");

            assertThat(result.hasError()).isTrue();
            assertThat(result.getReturnMessage()).isEqualTo("CreditLimit must be supplied.");
        }

        @Test
        @DisplayName("Non-numeric field returns error")
        void nonNumericFieldReturnsError() {
            ValidationResult result = FieldValidator.validateNumericField("CreditLimit", "ABC");

            assertThat(result.hasError()).isTrue();
            assertThat(result.getReturnMessage()).isEqualTo("CreditLimit must be numeric.");
        }

        @Test
        @DisplayName("Numeric field returns valid")
        void numericFieldReturnsValid() {
            ValidationResult result = FieldValidator.validateNumericField("CreditLimit", "50000");

            assertThat(result.hasError()).isFalse();
        }
    }

    // ========================================================================
    // 11. ValidateRequiredFieldTests
    // ========================================================================

    @Nested
    @DisplayName("validateRequiredField — COBOL required field check")
    class ValidateRequiredFieldTests {

        @Test
        @DisplayName("Blank required field returns error")
        void blankRequiredFieldReturnsError() {
            ValidationResult result = FieldValidator.validateRequiredField("AccountId", "   ");

            assertThat(result.hasError()).isTrue();
            assertThat(result.getReturnMessage()).isEqualTo("AccountId must be supplied.");
        }

        @Test
        @DisplayName("Null required field returns error")
        void nullRequiredFieldReturnsError() {
            ValidationResult result = FieldValidator.validateRequiredField("AccountId", null);

            assertThat(result.hasError()).isTrue();
            assertThat(result.getReturnMessage()).isEqualTo("AccountId must be supplied.");
        }

        @Test
        @DisplayName("Non-blank required field returns valid")
        void nonBlankRequiredFieldReturnsValid() {
            ValidationResult result = FieldValidator.validateRequiredField("AccountId", "12345678901");

            assertThat(result.hasError()).isFalse();
        }
    }

    // ========================================================================
    // 12. ValidateFieldLengthTests
    // ========================================================================

    @Nested
    @DisplayName("validateFieldLength — COBOL PIC X(n) length check")
    class ValidateFieldLengthTests {

        @Test
        @DisplayName("Field within length is valid")
        void fieldWithinLengthIsValid() {
            ValidationResult result = FieldValidator.validateFieldLength("Name", "John", 30);

            assertThat(result.hasError()).isFalse();
        }

        @Test
        @DisplayName("Field exceeding length returns error")
        void fieldExceedingLengthReturnsError() {
            ValidationResult result = FieldValidator.validateFieldLength(
                    "Name", "A very long name exceeding the limit", 10);

            assertThat(result.hasError()).isTrue();
            assertThat(result.getReturnMessage()).isEqualTo("Name exceeds maximum length of 10.");
        }

        @Test
        @DisplayName("Field exactly at length is valid")
        void fieldExactlyAtLengthIsValid() {
            ValidationResult result = FieldValidator.validateFieldLength("AcctId", "12345678901", 11);

            assertThat(result.hasError()).isFalse();
        }
    }

    // ========================================================================
    // 13. ErrorMessageExactTests — COBOL STRING spacing parity
    // ========================================================================

    @Nested
    @DisplayName("Error message exact string verification — COBOL STRING spacing parity")
    class ErrorMessageExactTests {

        @Test
        @DisplayName("Year blank message has space-colon-space: ' : Year must be supplied.'")
        void yearBlankMessageHasSpaceColonSpace() {
            ValidationResult result = validator.editYearCcyy(VARIABLE_NAME, "    ");
            assertThat(result.getReturnMessage())
                    .isEqualTo("TestField : Year must be supplied.");
        }

        @Test
        @DisplayName("Year not numeric message has space only: ' must be 4 digit number.'")
        void yearNotNumericMessageHasSpaceOnly() {
            ValidationResult result = validator.editYearCcyy(VARIABLE_NAME, "ABCD");
            assertThat(result.getReturnMessage())
                    .isEqualTo("TestField must be 4 digit number.");
        }

        @Test
        @DisplayName("Century not valid message has space-colon-space: ' : Century is not valid.'")
        void centuryNotValidMessageHasSpaceColonSpace() {
            ValidationResult result = validator.editYearCcyy(VARIABLE_NAME, "2100");
            assertThat(result.getReturnMessage())
                    .isEqualTo("TestField : Century is not valid.");
        }

        @Test
        @DisplayName("Month blank message has space-colon-space: ' : Month must be supplied.'")
        void monthBlankMessageHasSpaceColonSpace() {
            ValidationResult result = validator.editMonth(VARIABLE_NAME, "  ");
            assertThat(result.getReturnMessage())
                    .isEqualTo("TestField : Month must be supplied.");
        }

        @Test
        @DisplayName("Month out of range message has colon-space: ': Month must be...'")
        void monthOutOfRangeMessageHasColonOnly() {
            ValidationResult result = validator.editMonth(VARIABLE_NAME, "13");
            assertThat(result.getReturnMessage())
                    .isEqualTo("TestField: Month must be a number between 1 and 12.");
        }

        @Test
        @DisplayName("Day blank message has space-colon-space: ' : Day must be supplied.'")
        void dayBlankMessageHasSpaceColonSpace() {
            ValidationResult result = validator.editDay(VARIABLE_NAME, "  ");
            assertThat(result.getReturnMessage())
                    .isEqualTo("TestField : Day must be supplied.");
        }

        @Test
        @DisplayName("Day not numeric message has colon-lowercase: ':day must be...'")
        void dayNotNumericMessageHasColonLowercase() {
            ValidationResult result = validator.editDay(VARIABLE_NAME, "AB");
            assertThat(result.getReturnMessage())
                    .isEqualTo("TestField:day must be a number between 1 and 31.");
        }

        @Test
        @DisplayName("Cannot 31 days message has colon-capital-C: ':Cannot have 31 days...'")
        void cannot31DaysMessageHasColonCapitalC() {
            ValidationResult result = validator.editDayMonthYear(VARIABLE_NAME, "2023", "06", "31");
            assertThat(result.getReturnMessage())
                    .isEqualTo("TestField:Cannot have 31 days in this month.");
        }

        @Test
        @DisplayName("Cannot 30 days message has colon-capital-C: ':Cannot have 30 days...'")
        void cannot30DaysMessageHasColonCapitalC() {
            ValidationResult result = validator.editDayMonthYear(VARIABLE_NAME, "2023", "02", "30");
            assertThat(result.getReturnMessage())
                    .isEqualTo("TestField:Cannot have 30 days in this month.");
        }

        @Test
        @DisplayName("Not leap year message has colon-capital-N: ':Not a leap year.Cannot have...'")
        void notLeapYearMessageHasColonCapitalN() {
            ValidationResult result = validator.editDayMonthYear(VARIABLE_NAME, "2023", "02", "29");
            assertThat(result.getReturnMessage())
                    .isEqualTo("TestField:Not a leap year.Cannot have 29 days in this month.");
        }

        @Test
        @DisplayName("DOB future message has colon-lowercase with trailing space: ':cannot be in the future '")
        void dobFutureMessageHasColonLowercase() {
            ValidationResult result = validator.editDateOfBirth(VARIABLE_NAME, "20991231");
            assertThat(result.getReturnMessage())
                    .isEqualTo("TestField:cannot be in the future ");
        }
    }

    // ========================================================================
    // 14. DateBoundaryTests — parameterized boundary date validation
    // ========================================================================

    @Nested
    @DisplayName("Date boundary parameterized tests")
    class DateBoundaryTests {

        @ParameterizedTest(name = "31-day month date {0} accepts day 31")
        @CsvSource({"20230131", "20230331", "20230531", "20230731",
                "20230831", "20231031", "20231231"})
        @DisplayName("31-day months accept day 31")
        void thirtyOneDayMonths_acceptDay31(String date) {
            String ccyy = date.substring(0, 4);
            String mm = date.substring(4, 6);
            String dd = date.substring(6, 8);

            ValidationResult result = validator.editDayMonthYear(VARIABLE_NAME, ccyy, mm, dd);

            assertThat(result.hasError()).isFalse();
        }

        @ParameterizedTest(name = "30-day month date {0} rejects day 31")
        @CsvSource({"20230431", "20230631", "20230931", "20231131"})
        @DisplayName("30-day months reject day 31")
        void thirtyDayMonths_rejectDay31(String date) {
            String ccyy = date.substring(0, 4);
            String mm = date.substring(4, 6);
            String dd = date.substring(6, 8);

            ValidationResult result = validator.editDayMonthYear(VARIABLE_NAME, ccyy, mm, dd);

            assertThat(result.hasError()).isTrue();
            assertThat(result.getReturnMessage())
                    .isEqualTo("TestField:Cannot have 31 days in this month.");
        }

        @ParameterizedTest(name = "February date {0} rejects day 30")
        @CsvSource({"20230230", "20240230"})
        @DisplayName("February rejects day 30")
        void february_rejectDay30(String date) {
            String ccyy = date.substring(0, 4);
            String mm = date.substring(4, 6);
            String dd = date.substring(6, 8);

            ValidationResult result = validator.editDayMonthYear(VARIABLE_NAME, ccyy, mm, dd);

            assertThat(result.hasError()).isTrue();
            assertThat(result.getReturnMessage())
                    .isEqualTo("TestField:Cannot have 30 days in this month.");
        }

        @ParameterizedTest(name = "Leap year {0} accepts Feb 29")
        @CsvSource({"20000229", "20040229", "20080229", "20120229",
                "20160229", "20200229", "20240229"})
        @DisplayName("Leap years accept February 29th")
        void leapYears_acceptFeb29(String date) {
            String ccyy = date.substring(0, 4);
            String mm = date.substring(4, 6);
            String dd = date.substring(6, 8);

            ValidationResult result = validator.editDayMonthYear(VARIABLE_NAME, ccyy, mm, dd);

            assertThat(result.hasError()).isFalse();
        }

        @ParameterizedTest(name = "Non-leap year {0} rejects Feb 29")
        @CsvSource({"19000229", "20010229", "20020229", "20030229", "20230229"})
        @DisplayName("Non-leap years reject February 29th")
        void nonLeapYears_rejectFeb29(String date) {
            String ccyy = date.substring(0, 4);
            String mm = date.substring(4, 6);
            String dd = date.substring(6, 8);

            ValidationResult result = validator.editDayMonthYear(VARIABLE_NAME, ccyy, mm, dd);

            assertThat(result.hasError()).isTrue();
            assertThat(result.getReturnMessage())
                    .isEqualTo("TestField:Not a leap year.Cannot have 29 days in this month.");
        }

        @ParameterizedTest(name = "Century {0} is invalid")
        @CsvSource({"0000", "0100", "1800", "2100", "3000", "9900"})
        @DisplayName("Invalid centuries rejected")
        void invalidCenturies(String ccyy) {
            ValidationResult result = validator.editYearCcyy(VARIABLE_NAME, ccyy);

            assertThat(result.hasError()).isTrue();
            assertThat(result.getReturnMessage())
                    .isEqualTo("TestField : Century is not valid.");
        }
    }

    // ========================================================================
    // 15. FieldValidationFlagsTests — inner class unit tests
    // ========================================================================

    @Nested
    @DisplayName("FieldValidationFlags — flag state and convenience methods")
    class FieldValidationFlagsTests {

        @Test
        @DisplayName("All flags LOW-VALUES → isDateValid() is true")
        void dateValidWhenAllFlagsLowValues() {
            FieldValidationFlags flags = new FieldValidationFlags('\0', '\0', '\0');

            assertThat(flags.isDateValid()).isTrue();
            assertThat(flags.isDateInvalid()).isFalse();
        }

        @Test
        @DisplayName("All flags '0' → isDateInvalid() is true")
        void dateInvalidWhenAllFlagsZero() {
            FieldValidationFlags flags = new FieldValidationFlags('0', '0', '0');

            assertThat(flags.isDateInvalid()).isTrue();
            assertThat(flags.isDateValid()).isFalse();
        }

        @Test
        @DisplayName("Individual flag checks for mixed states")
        void individualFlagChecks() {
            FieldValidationFlags flags = new FieldValidationFlags('B', '0', '\0');

            assertThat(flags.isYearBlank()).isTrue();
            assertThat(flags.isYearValid()).isFalse();
            assertThat(flags.isMonthValid()).isFalse();
            assertThat(flags.isMonthBlank()).isFalse();
            assertThat(flags.isDayValid()).isTrue();
            assertThat(flags.isDayBlank()).isFalse();
        }

        @Test
        @DisplayName("toFlagString returns 3-char representation")
        void toFlagStringReturns3CharRepresentation() {
            FieldValidationFlags flags = new FieldValidationFlags('B', '0', '\0');

            String flagStr = flags.toFlagString();

            assertThat(flagStr).hasSize(3);
            assertThat(flagStr.charAt(0)).isEqualTo('B');
            assertThat(flagStr.charAt(1)).isEqualTo('0');
            assertThat(flagStr.charAt(2)).isEqualTo('\0');
        }

        @Test
        @DisplayName("All valid flags produce 3-char null string")
        void allValidFlagsToFlagString() {
            FieldValidationFlags flags = new FieldValidationFlags('\0', '\0', '\0');

            String flagStr = flags.toFlagString();

            assertThat(flagStr).hasSize(3);
            assertThat(flags.isDateValid()).isTrue();
        }

        @Test
        @DisplayName("Getter methods return correct individual flags")
        void getterMethodsReturnCorrectFlags() {
            FieldValidationFlags flags = new FieldValidationFlags('B', '0', '\0');

            assertThat(flags.getYearFlag()).isEqualTo('B');
            assertThat(flags.getMonthFlag()).isEqualTo('0');
            assertThat(flags.getDayFlag()).isEqualTo('\0');
        }
    }
}
