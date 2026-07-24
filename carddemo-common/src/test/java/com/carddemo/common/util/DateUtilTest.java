package com.carddemo.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for :class:`DateUtil`.
 *
 * :purpose: Verify date validation, century/month/day/leap rules, Lillian day
 *     conversion, date-of-birth checks, and result-string formatting migrated
 *     from ``CSUTLDTC`` / ``CSUTLDPY`` / ``CSUTLDWY``.
 * :output: JUnit 5 / AssertJ assertions only; the suite holds no state and touches
 *     no database, Spring context, or other external resource (pure JDK logic).
 */
final class DateUtilTest {

    /**
     * Frozen classification-string and mask constants.
     *
     * :purpose: Assert the exact ``WS-RESULT PIC X(15)`` classification literals and
     *     the two mask pictures byte-for-byte, including every trailing space, and
     *     guard each length so a silently dropped space is caught.
     */
    @Nested
    @DisplayName("Frozen result and mask constants")
    class Constants {

        @Test
        @DisplayName("RESULT_* constants expose their exact PIC X(15) values and length 15")
        void resultConstants_haveExactValuesAndLengths() {
            assertThat(DateUtil.RESULT_VALID).isEqualTo("Date is valid" + " ".repeat(2));
            assertThat(DateUtil.RESULT_VALID).hasSize(15);

            assertThat(DateUtil.RESULT_INSUFFICIENT).isEqualTo("Insufficient" + " ".repeat(3));
            assertThat(DateUtil.RESULT_INSUFFICIENT).hasSize(15);

            assertThat(DateUtil.RESULT_BAD_DATE_VALUE).isEqualTo("Datevalue error");
            assertThat(DateUtil.RESULT_BAD_DATE_VALUE).hasSize(15);

            assertThat(DateUtil.RESULT_INVALID_ERA).isEqualTo("Invalid Era" + " ".repeat(4));
            assertThat(DateUtil.RESULT_INVALID_ERA).hasSize(15);

            assertThat(DateUtil.RESULT_UNSUPP_RANGE).isEqualTo("Unsupp. Range" + " ".repeat(2));
            assertThat(DateUtil.RESULT_UNSUPP_RANGE).hasSize(15);

            assertThat(DateUtil.RESULT_INVALID_MONTH).isEqualTo("Invalid month" + " ".repeat(2));
            assertThat(DateUtil.RESULT_INVALID_MONTH).hasSize(15);

            assertThat(DateUtil.RESULT_BAD_PIC_STRING).isEqualTo("Bad Pic String" + " ".repeat(1));
            assertThat(DateUtil.RESULT_BAD_PIC_STRING).hasSize(15);

            assertThat(DateUtil.RESULT_NON_NUMERIC).isEqualTo("Nonnumeric data");
            assertThat(DateUtil.RESULT_NON_NUMERIC).hasSize(15);

            assertThat(DateUtil.RESULT_YEAR_IN_ERA_ZERO).isEqualTo("YearInEra is 0" + " ".repeat(1));
            assertThat(DateUtil.RESULT_YEAR_IN_ERA_ZERO).hasSize(15);

            assertThat(DateUtil.RESULT_INVALID).isEqualTo("Date is invalid");
            assertThat(DateUtil.RESULT_INVALID).hasSize(15);
        }

        @Test
        @DisplayName("Mask constants expose the CCYYMMDD and ISO pictures")
        void maskConstants_haveExactValues() {
            assertThat(DateUtil.MASK_CCYYMMDD).isEqualTo("YYYYMMDD");
            assertThat(DateUtil.MASK_CCYYMMDD).hasSize(8);

            assertThat(DateUtil.MASK_ISO).isEqualTo("YYYY-MM-DD");
            assertThat(DateUtil.MASK_ISO).hasSize(10);
        }
    }

    /**
     * ``validateDate`` result shape under each mask.
     *
     * :purpose: Verify severity, the ``isValid`` flag, and the classification string
     *     for the guaranteed valid, unknown-mask, and insufficient-input outcomes,
     *     plus single-argument delegation to the default ``CCYYMMDD`` mask.
     */
    @Nested
    @DisplayName("validateDate result shape")
    class ValidateDate {

        @Test
        @DisplayName("Valid CCYYMMDD date yields severity 0, RESULT_VALID and a positive Lillian day")
        void validCcyymmdd_returnsSeverity0AndResultValid() {
            DateUtil.DateValidationResult result = DateUtil.validateDate("20240229", DateUtil.MASK_CCYYMMDD);

            assertThat(result.severity()).isEqualTo(0);
            assertThat(result.isValid()).isTrue();
            assertThat(result.result()).isEqualTo(DateUtil.RESULT_VALID);
            assertThat(result.lillian()).isGreaterThan(0);
        }

        @Test
        @DisplayName("Valid ISO date yields isValid true and RESULT_VALID")
        void validIso_returnsSeverity0AndResultValid() {
            DateUtil.DateValidationResult result = DateUtil.validateDate("2024-01-31", DateUtil.MASK_ISO);

            assertThat(result.isValid()).isTrue();
            assertThat(result.result()).isEqualTo(DateUtil.RESULT_VALID);
        }

        @Test
        @DisplayName("Unknown mask yields RESULT_BAD_PIC_STRING with non-zero severity and invalid")
        void unknownMask_returnsBadPicString_nonzeroSeverity_invalid() {
            DateUtil.DateValidationResult result = DateUtil.validateDate("15/06/1990", "DD/MM/YYYY");

            assertThat(result.result()).isEqualTo(DateUtil.RESULT_BAD_PIC_STRING);
            assertThat(result.severity()).isNotEqualTo(0);
            assertThat(result.isValid()).isFalse();
        }

        @Test
        @DisplayName("Null date yields RESULT_INSUFFICIENT with non-zero severity and invalid")
        void nullDate_returnsInsufficient_nonzeroSeverity_invalid() {
            DateUtil.DateValidationResult result = DateUtil.validateDate(null, DateUtil.MASK_CCYYMMDD);

            assertThat(result.result()).isEqualTo(DateUtil.RESULT_INSUFFICIENT);
            assertThat(result.severity()).isNotEqualTo(0);
            assertThat(result.isValid()).isFalse();
        }

        @Test
        @DisplayName("Blank date yields RESULT_INSUFFICIENT with non-zero severity and invalid")
        void blankDate_returnsInsufficient_nonzeroSeverity_invalid() {
            DateUtil.DateValidationResult result = DateUtil.validateDate("   ", DateUtil.MASK_CCYYMMDD);

            assertThat(result.result()).isEqualTo(DateUtil.RESULT_INSUFFICIENT);
            assertThat(result.severity()).isNotEqualTo(0);
            assertThat(result.isValid()).isFalse();
        }

        @Test
        @DisplayName("Single-argument overload delegates to the default CCYYMMDD mask (valid)")
        void singleArg_delegatesToCcyymmdd_valid() {
            assertThat(DateUtil.validateDate("20240229").isValid()).isTrue();
        }

        @Test
        @DisplayName("ISO string under the default CCYYMMDD mask is invalid")
        void singleArg_isoStringUnderDefaultMask_invalid() {
            assertThat(DateUtil.validateDate("2024-02-29").isValid()).isFalse();
        }
    }

    /**
     * ``isValidCcyymmdd`` century and format guard.
     *
     * :purpose: Verify the ``CSUTLDPY EDIT-YEAR-CCYY`` century restriction (only ``19``
     *     and ``20`` from the ``CSUTLDWY`` 88-levels) together with the eight-digit,
     *     all-numeric format guard, independent of raw ``LocalDate`` parseability.
     */
    @Nested
    @DisplayName("isValidCcyymmdd century and format guard")
    class CenturyValidation {

        @Test
        @DisplayName("Century 21 is rejected even though 2100-01-01 is a real date")
        void rejectsCentury21() {
            assertThat(DateUtil.isValidCcyymmdd("21000101")).isFalse();
        }

        @Test
        @DisplayName("Century 18 is rejected")
        void rejectsCentury18() {
            assertThat(DateUtil.isValidCcyymmdd("18991231")).isFalse();
        }

        @Test
        @DisplayName("Century 20 is accepted")
        void acceptsCentury20() {
            assertThat(DateUtil.isValidCcyymmdd("20240229")).isTrue();
        }

        @Test
        @DisplayName("Century 19 is accepted")
        void acceptsCentury19() {
            assertThat(DateUtil.isValidCcyymmdd("19000101")).isTrue();
        }

        @Test
        @DisplayName("A value that is not eight characters is rejected")
        void rejectsWrongLength() {
            assertThat(DateUtil.isValidCcyymmdd("1900")).isFalse();
        }

        @Test
        @DisplayName("Null is rejected")
        void rejectsNull() {
            assertThat(DateUtil.isValidCcyymmdd(null)).isFalse();
        }

        @Test
        @DisplayName("Blank is rejected")
        void rejectsBlank() {
            assertThat(DateUtil.isValidCcyymmdd("   ")).isFalse();
        }

        @Test
        @DisplayName("A non-digit character is rejected")
        void rejectsNonDigit() {
            assertThat(DateUtil.isValidCcyymmdd("2024AB01")).isFalse();
        }
    }

    /**
     * Month range and day-in-month rules.
     *
     * :purpose: Verify the ``CSUTLDWY`` month range (1..12) and 31-day-month set
     *     {1,3,5,7,8,10,12} together with the ``CSUTLDPY EDIT-DAY-MONTH-YEAR`` day
     *     overflow checks under both masks. Only the ``isValid`` boolean is asserted;
     *     the failure classification string is best-effort by contract.
     */
    @Nested
    @DisplayName("Month range and day-in-month rules")
    class MonthAndDayRange {

        @Test
        @DisplayName("A month above 12 is invalid")
        void monthAbove12_invalid() {
            assertThat(DateUtil.isValid("2024-13-01", DateUtil.MASK_ISO)).isFalse();
        }

        @Test
        @DisplayName("Month zero is invalid")
        void monthZero_invalid() {
            assertThat(DateUtil.isValid("2024-00-10", DateUtil.MASK_ISO)).isFalse();
        }

        @Test
        @DisplayName("April 31 (ISO) is invalid")
        void april31_iso_invalid() {
            assertThat(DateUtil.isValid("2024-04-31", DateUtil.MASK_ISO)).isFalse();
        }

        @Test
        @DisplayName("April 31 (CCYYMMDD) is invalid")
        void april31_ccyymmdd_invalid() {
            assertThat(DateUtil.isValid("20240431", DateUtil.MASK_CCYYMMDD)).isFalse();
        }

        @Test
        @DisplayName("January 31 (ISO) is valid")
        void january31_iso_valid() {
            assertThat(DateUtil.isValid("2024-01-31", DateUtil.MASK_ISO)).isTrue();
        }

        @Test
        @DisplayName("January 31 (CCYYMMDD) is valid")
        void january31_ccyymmdd_valid() {
            assertThat(DateUtil.isValid("20240131", DateUtil.MASK_CCYYMMDD)).isTrue();
        }

        @Test
        @DisplayName("June 30 is valid")
        void june30_valid() {
            assertThat(DateUtil.isValid("2024-06-30", DateUtil.MASK_ISO)).isTrue();
        }

        @Test
        @DisplayName("June 31 is invalid")
        void june31_invalid() {
            assertThat(DateUtil.isValid("2024-06-31", DateUtil.MASK_ISO)).isFalse();
        }

        @Test
        @DisplayName("The 31st is valid for every 31-day month (03,05,07,08,10,12)")
        void all31DayMonths_valid() {
            assertThat(DateUtil.isValid("2024-03-31", DateUtil.MASK_ISO)).isTrue();
            assertThat(DateUtil.isValid("2024-05-31", DateUtil.MASK_ISO)).isTrue();
            assertThat(DateUtil.isValid("2024-07-31", DateUtil.MASK_ISO)).isTrue();
            assertThat(DateUtil.isValid("2024-08-31", DateUtil.MASK_ISO)).isTrue();
            assertThat(DateUtil.isValid("2024-10-31", DateUtil.MASK_ISO)).isTrue();
            assertThat(DateUtil.isValid("2024-12-31", DateUtil.MASK_ISO)).isTrue();
        }
    }

    /**
     * February leap-year rules under the strict resolver.
     *
     * :purpose: Verify the ``CSUTLDPY`` leap-year divisor logic (divide the year by
     *     400 when the year-of-century is 00, otherwise by 4) as realized by the
     *     ``uuuu`` STRICT resolver. Only the ``isValid`` boolean is asserted.
     */
    @Nested
    @DisplayName("February leap-year rules")
    class FebruaryLeapYear {

        @Test
        @DisplayName("Feb 29 in 2000 is valid (divisible by 400)")
        void feb29_year2000_valid() {
            assertThat(DateUtil.isValid("2000-02-29", DateUtil.MASK_ISO)).isTrue();
        }

        @Test
        @DisplayName("Feb 29 in 2024 is valid (divisible by 4)")
        void feb29_year2024_valid() {
            assertThat(DateUtil.isValid("2024-02-29", DateUtil.MASK_ISO)).isTrue();
        }

        @Test
        @DisplayName("Feb 29 in 1900 is invalid (divisible by 100, not 400)")
        void feb29_year1900_invalid() {
            assertThat(DateUtil.isValid("1900-02-29", DateUtil.MASK_ISO)).isFalse();
        }

        @Test
        @DisplayName("Feb 29 in 2023 is invalid (non-leap year)")
        void feb29_year2023_invalid() {
            assertThat(DateUtil.isValid("2023-02-29", DateUtil.MASK_ISO)).isFalse();
        }

        @Test
        @DisplayName("Feb 30 is always invalid")
        void feb30_alwaysInvalid() {
            assertThat(DateUtil.isValid("2024-02-30", DateUtil.MASK_ISO)).isFalse();
        }

        @Test
        @DisplayName("Feb 29 in 2024 (CCYYMMDD) is valid")
        void feb29_ccyymmdd_year2024_valid() {
            assertThat(DateUtil.isValid("20240229", DateUtil.MASK_CCYYMMDD)).isTrue();
        }
    }

    /**
     * ``isValidDateOfBirth`` reasonableness check.
     *
     * :purpose: Verify the ``CSUTLDPY EDIT-DATE-OF-BIRTH`` rule that a date of birth
     *     must be strictly before the current date, so today and any future date are
     *     rejected. The "today" and "future" cases use :meth:`LocalDate.now` to remain
     *     date-independent.
     */
    @Nested
    @DisplayName("isValidDateOfBirth reasonableness check")
    class DateOfBirth {

        @Test
        @DisplayName("A past date is a valid date of birth")
        void pastDate_valid() {
            assertThat(DateUtil.isValidDateOfBirth("1990-06-15", DateUtil.MASK_ISO)).isTrue();
        }

        @Test
        @DisplayName("Today is not a valid date of birth")
        void today_invalid() {
            assertThat(DateUtil.isValidDateOfBirth(LocalDate.now().toString(), DateUtil.MASK_ISO)).isFalse();
        }

        @Test
        @DisplayName("A future date is not a valid date of birth")
        void future_invalid() {
            assertThat(DateUtil.isValidDateOfBirth(LocalDate.now().plusYears(1).toString(), DateUtil.MASK_ISO))
                    .isFalse();
        }

        @Test
        @DisplayName("An unparseable date is not a valid date of birth")
        void unparseable_invalid() {
            assertThat(DateUtil.isValidDateOfBirth("2023-02-29", DateUtil.MASK_ISO)).isFalse();
        }
    }

    /**
     * ``toLillian`` day-count conversion.
     *
     * :purpose: Verify the ``CSUTLDTC OUTPUT-LILLIAN`` day count, whose base is
     *     ``1582-10-15`` (Lillian day 1), including a known Y2K anchor and day-to-day
     *     monotonicity.
     */
    @Nested
    @DisplayName("toLillian day-count conversion")
    class Lillian {

        @Test
        @DisplayName("The Lillian base date 1582-10-15 is day 1")
        void lillianEpochBase_isOne() {
            assertThat(DateUtil.toLillian(LocalDate.of(1582, 10, 15))).isEqualTo(1);
        }

        @Test
        @DisplayName("2000-01-01 is Lillian day 152385")
        void lillianY2K_is152385() {
            assertThat(DateUtil.toLillian(LocalDate.of(2000, 1, 1))).isEqualTo(152385);
        }

        @Test
        @DisplayName("Consecutive days differ by exactly one Lillian day")
        void lillian_isMonotonic() {
            assertThat(DateUtil.toLillian(LocalDate.of(2000, 1, 2)))
                    .isEqualTo(DateUtil.toLillian(LocalDate.of(2000, 1, 1)) + 1);
        }
    }

    /**
     * ``padResult15`` fixed-width formatting.
     *
     * :purpose: Verify the ``CSUTLDWY WS-RESULT PIC X(15)`` field formatting: a shorter
     *     string is right-padded with spaces, a longer string is truncated, null becomes
     *     15 spaces, and the output is always exactly 15 characters.
     */
    @Nested
    @DisplayName("padResult15 fixed-width formatting")
    class PadResult15 {

        @Test
        @DisplayName("A short string is right-padded to 15 characters")
        void padsShortRightTo15() {
            assertThat(DateUtil.padResult15("ab")).isEqualTo("ab" + " ".repeat(13));
            assertThat(DateUtil.padResult15("ab")).hasSize(15);
        }

        @Test
        @DisplayName("An exactly-15-character string passes through unchanged")
        void passesThroughExact15() {
            assertThat(DateUtil.padResult15(DateUtil.RESULT_INVALID)).isEqualTo(DateUtil.RESULT_INVALID);
            assertThat(DateUtil.padResult15(DateUtil.RESULT_INVALID)).hasSize(15);
        }

        @Test
        @DisplayName("A string longer than 15 characters is truncated to 15")
        void truncatesLongerThan15() {
            assertThat(DateUtil.padResult15("0123456789ABCDEFGHIJ")).isEqualTo("0123456789ABCDE");
            assertThat(DateUtil.padResult15("0123456789ABCDEFGHIJ")).hasSize(15);
        }

        @Test
        @DisplayName("Null becomes 15 spaces")
        void nullReturns15Spaces() {
            assertThat(DateUtil.padResult15(null)).isEqualTo(" ".repeat(15));
            assertThat(DateUtil.padResult15(null)).hasSize(15);
        }
    }

    /**
     * ``parse`` and ``isValid`` behaviour.
     *
     * :purpose: Verify that :meth:`DateUtil.parse` returns the parsed in-range date for
     *     valid input under each mask and an empty :class:`Optional` for an unknown mask,
     *     null/blank input, or an invalid date, and that the single-argument ``isValid``
     *     overload delegates to the default ``CCYYMMDD`` mask.
     */
    @Nested
    @DisplayName("parse and isValid behaviour")
    class ParseAndIsValid {

        @Test
        @DisplayName("A valid ISO date parses to the present LocalDate")
        void parseValidIso_returnsPresentDate() {
            Optional<LocalDate> parsed = DateUtil.parse("2024-02-29", DateUtil.MASK_ISO);

            assertThat(parsed).isPresent();
            assertThat(parsed).contains(LocalDate.of(2024, 2, 29));
        }

        @Test
        @DisplayName("A valid CCYYMMDD date parses to the present LocalDate")
        void parseValidCcyymmdd_returnsPresentDate() {
            Optional<LocalDate> parsed = DateUtil.parse("20000101", DateUtil.MASK_CCYYMMDD);

            assertThat(parsed).isPresent();
            assertThat(parsed).contains(LocalDate.of(2000, 1, 1));
        }

        @Test
        @DisplayName("An unknown mask yields an empty Optional")
        void parseUnknownMask_returnsEmpty() {
            assertThat(DateUtil.parse("01/01/2000", "DD/MM/YYYY")).isEmpty();
        }

        @Test
        @DisplayName("A null date yields an empty Optional")
        void parseNull_returnsEmpty() {
            assertThat(DateUtil.parse(null, DateUtil.MASK_ISO)).isEmpty();
        }

        @Test
        @DisplayName("A blank date yields an empty Optional")
        void parseBlank_returnsEmpty() {
            assertThat(DateUtil.parse("   ", DateUtil.MASK_ISO)).isEmpty();
        }

        @Test
        @DisplayName("An invalid date value yields an empty Optional")
        void parseInvalidDate_returnsEmpty() {
            assertThat(DateUtil.parse("2023-02-29", DateUtil.MASK_ISO)).isEmpty();
        }

        @Test
        @DisplayName("The single-argument isValid overload delegates to CCYYMMDD")
        void isValidSingleArg_delegates() {
            assertThat(DateUtil.isValid("20240229")).isTrue();
            assertThat(DateUtil.isValid("2024-13-01", DateUtil.MASK_ISO)).isFalse();
        }
    }
}
