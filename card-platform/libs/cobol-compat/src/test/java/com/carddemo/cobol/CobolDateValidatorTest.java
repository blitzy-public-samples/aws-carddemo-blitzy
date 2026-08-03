package com.carddemo.cobol;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserts that {@link CobolDateValidator} reports what {@code app/cbl/CSUTLDTC.cbl} reports and
 * edits what {@code app/cpy/CSUTLDPY.cpy} edits, over the working storage
 * {@code app/cpy/CSUTLDWY.cpy} declares.
 *
 * <p>Two acceptance policies read the same outcome. The tolerant policy accepts severity text
 * {@code 0000} or message number text {@code 2513}, at {@code app/cbl/COTRN02C.cbl:L397-L400}. The
 * strict policy accepts numeric severity zero alone, at {@code app/cpy/CSUTLDPY.cpy:L298}, and
 * that paragraph holds no {@code 2513} branch.
 * {@link #anOutOfRangeDateIsAcceptedOnlyByTheToleratedMessageNumber()} and
 * {@link #theStrictPolicyRejectsTheSameDateTheToleratedMessageNumberAccepts()} take one input and
 * assert one policy each.
 *
 * <p>The two field-edit paragraphs check in opposite orders.
 * {@link #theMonthEditRangeTestRunsAheadOfItsNumericGate()} and
 * {@link #theDayEditNumericGateRunsAheadOfItsRangeTest()} hand the same two characters to each
 * paragraph and record opposite verdicts.
 *
 * <p>Every date and every reference date below is a fixed value. No assertion reads the system
 * clock, so no method changes verdict as the calendar advances. The two-argument date-of-birth
 * overload takes today's date from {@link LocalDate#now()}, and
 * {@link #theTwoArgumentDateOfBirthOverloadAgreesWithTheThreeArgumentFormOnToday()} covers it
 * without pinning a date.
 *
 * <p>Expected message texts are quoted from {@code app/cpy/CSUTLDPY.cpy} character for character,
 * keeping the leading spaces, the missing spaces, the trailing space, and the lower-case
 * {@code day} the source carries.
 *
 * <p>Rationale for every choice these assertions pin sits in
 * {@code card-platform/docs/decision-log.md}. The flagged COBOL rules sit in
 * {@code card-platform/docs/business-rule-flags.md}.
 */
class CobolDateValidatorTest {

    // Dates. Each value is fixed, and each one reaches one condition.

    /** A date the source accepts under both policies. */
    private static final String VALID_DATE = "2022-06-10";

    /** {@link #VALID_DATE} in the eight-character form the strict policy passes. */
    private static final String VALID_DATE_WITHOUT_SEPARATORS = "20220610";

    /** The day count {@link #VALID_DATE} carries. */
    private static final int VALID_DATE_DAY_COUNT = 160581;

    /** The earliest date the call supports, numbered day one. */
    private static final String EARLIEST_SUPPORTED_DATE_TEXT = "1582-10-15";

    /** One day before {@link #EARLIEST_SUPPORTED_DATE_TEXT}. */
    private static final String ONE_DAY_BEFORE_THE_EARLIEST_DATE = "1582-10-14";

    /** {@link #ONE_DAY_BEFORE_THE_EARLIEST_DATE} in the eight-character form. */
    private static final String ONE_DAY_BEFORE_THE_EARLIEST_DATE_WITHOUT_SEPARATORS = "15821014";

    /** A date a whole year before the earliest supported date. */
    private static final String ONE_YEAR_BEFORE_THE_EARLIEST_DATE = "1581-12-31";

    /**
     * The out-of-range inputs the two policies disagree over, each paired with the mask its call
     * site passes. The first and third pairs carry the ten-character mask of
     * {@code app/cbl/COTRN02C.cbl:L60}, and the second carries the eight-character mask of
     * {@code app/cpy/CSUTLDPY.cpy:L291}.
     */
    private static final String[][] OUT_OF_RANGE_DATES_AND_MASKS = {
            {ONE_DAY_BEFORE_THE_EARLIEST_DATE, CobolDateValidator.TOLERANT_POLICY_DATE_MASK},
            {ONE_DAY_BEFORE_THE_EARLIEST_DATE_WITHOUT_SEPARATORS,
                    CobolDateValidator.STRICT_POLICY_DATE_MASK},
            {ONE_YEAR_BEFORE_THE_EARLIEST_DATE, CobolDateValidator.TOLERANT_POLICY_DATE_MASK},
    };

    /** The latest date the call supports. */
    private static final String LATEST_SUPPORTED_DATE_TEXT = "9999-12-31";

    /** The day count {@link #LATEST_SUPPORTED_DATE_TEXT} carries. */
    private static final int LATEST_SUPPORTED_DATE_DAY_COUNT = 3074324;

    /** A date whose year is zero, which reaches the year-in-era condition. */
    private static final String YEAR_ZERO_DATE = "0000-01-01";

    /** A date whose month is 13. */
    private static final String MONTH_THIRTEEN_DATE = "2022-13-01";

    /** A date naming 30 February. */
    private static final String FEBRUARY_THIRTY_DATE = "2022-02-30";

    /** 29 February in a year divisible by four. */
    private static final String LEAP_DAY_IN_A_LEAP_YEAR = "2024-02-29";

    /** 29 February in a year not divisible by four. */
    private static final String LEAP_DAY_IN_AN_ORDINARY_YEAR = "2023-02-29";

    /** 29 February in a century year divisible by 400. */
    private static final String LEAP_DAY_IN_A_LEAP_CENTURY = "2000-02-29";

    /** 29 February in a century year not divisible by 400. */
    private static final String LEAP_DAY_IN_AN_ORDINARY_CENTURY = "1900-02-29";

    /** A date carrying a letter at a month offset. */
    private static final String DATE_WITH_A_LETTER_IN_THE_MONTH = "2022-0A-01";

    /** A date whose separators are solidus characters rather than the dash the mask names. */
    private static final String DATE_WITH_THE_WRONG_SEPARATORS = "2022/06/10";

    /** A date shorter than the mask. */
    private static final String DATE_SHORTER_THAN_THE_MASK = "2022-06";

    /** A mask naming no four-character year token. */
    private static final String MASK_WITHOUT_A_YEAR_TOKEN = "DD-MM-YY";

    /** A mask carrying a character no token and no separator claims. */
    private static final String MASK_WITH_AN_UNKNOWN_CHARACTER = "YYYY/MM/DD";

    /**
     * {@value CobolDateValidator#STRICT_POLICY_DATE_MASK} held to
     * {@value CobolDateValidator#DATE_MASK_WIDTH} characters, the width
     * {@code LS-DATE-FORMAT PIC X(10)} at {@code app/cbl/CSUTLDTC.cbl:L85} declares.
     */
    private static final String PADDED_STRICT_POLICY_DATE_MASK = "YYYYMMDD  ";

    /** Sixteen hexadecimal digits no condition names. */
    private static final String UNRECOGNISED_FEEDBACK_TOKEN = "FFFFFFFFFFFFFFFF";

    /** The lower-case form of the valid-date token, which the lookup matches. */
    private static final String LOWER_CASE_BAD_DATE_TOKEN = "000309cc59c3c5c5";

    // Field-edit inputs, from app/cpy/CSUTLDPY.cpy.

    /** The field name every composed message opens with. */
    private static final String EDIT_VARIABLE_NAME = "Date of Birth";

    /** Eight characters of {@code LOW-VALUES}, the state the year edit tests first. */
    private static final String LOW_VALUES_EDIT_DATE =
            String.valueOf('\u0000').repeat(CobolDateValidator.EDIT_DATE_WIDTH);

    /** Eight spaces, the other state the year edit reads as not supplied. */
    private static final String SPACES_EDIT_DATE = " ".repeat(CobolDateValidator.EDIT_DATE_WIDTH);

    /** A date whose century is 18, which neither accepted century names. */
    private static final String CENTURY_EIGHTEEN_DATE = "18220610";

    /** A date carrying letters at the month offsets. */
    private static final String EDIT_DATE_WITH_LETTERS_IN_THE_MONTH = "2022AB10";

    /** A date whose month is 13. */
    private static final String EDIT_DATE_WITH_MONTH_THIRTEEN = "20221310";

    /** A date whose day is zero. */
    private static final String EDIT_DATE_WITH_DAY_ZERO = "20220600";

    /** A date whose day is 32. */
    private static final String EDIT_DATE_WITH_DAY_THIRTY_TWO = "20220632";

    /** A date naming 31 April, a month holding 30 days. */
    private static final String EDIT_DATE_WITH_DAY_31_IN_A_SHORT_MONTH = "20220431";

    /** A date naming 30 February. */
    private static final String EDIT_DATE_WITH_DAY_30_IN_FEBRUARY = "20220230";

    /** A date naming 29 February in a year not divisible by four. */
    private static final String EDIT_DATE_WITH_LEAP_DAY_IN_AN_ORDINARY_YEAR = "20230229";

    /** A date naming 29 February in a year divisible by four. */
    private static final String EDIT_DATE_WITH_LEAP_DAY_IN_A_LEAP_YEAR = "20240229";

    /** A date naming 29 February in a century year divisible by 400. */
    private static final String EDIT_DATE_WITH_LEAP_DAY_IN_A_LEAP_CENTURY = "20000229";

    /** A date naming 29 February in a century year not divisible by 400. */
    private static final String EDIT_DATE_WITH_LEAP_DAY_IN_AN_ORDINARY_CENTURY = "19000229";

    /** A date whose month field holds two spaces. */
    private static final String EDIT_DATE_WITH_A_BLANK_MONTH = "2022  10";

    /** A date whose day field holds two spaces. */
    private static final String EDIT_DATE_WITH_A_BLANK_DAY = "202206  ";

    /** A date carrying a letter inside the four characters of the year. */
    private static final String EDIT_DATE_WITH_A_LETTER_IN_THE_YEAR = "20X20610";

    /**
     * Two characters the numeric gate accepts and the {@code PIC 9(2)} redefine reads as no value.
     * The plus sign is {@code X'2B'}, whose low four bits hold 11 and name no digit.
     */
    private static final String SIGNED_FIELD = "+5";

    /** {@link #SIGNED_FIELD} in the month position, ahead of a day of 10. */
    private static final String EDIT_DATE_WITH_A_SIGNED_MONTH = "2022+510";

    /** {@link #SIGNED_FIELD} in the day position, behind a month of 05. */
    private static final String EDIT_DATE_WITH_A_SIGNED_DAY = "202205+5";

    /**
     * The eight digits {@link #EDIT_DATE_WITH_A_SIGNED_DAY} reaches the strict-policy call as, held
     * to {@value CobolDateValidator#TESTED_DATE_WIDTH} characters. The day arrives converted, so
     * the plus sign reaches the call as a zero-padded 05.
     */
    private static final String SIGNED_DAY_AFTER_CONVERSION = "20220505  ";

    /** A date of birth well before every reference date below. */
    private static final String DATE_OF_BIRTH = "19610608";

    /** The reference date the date-of-birth check compares against. */
    private static final LocalDate REFERENCE_TODAY = LocalDate.of(2022, 6, 10);

    /** {@link #REFERENCE_TODAY} in the eight-character edit form. */
    private static final String EDIT_DATE_ON_REFERENCE_TODAY = "20220610";

    /** One day after {@link #REFERENCE_TODAY}, in the eight-character edit form. */
    private static final String EDIT_DATE_ONE_DAY_AFTER_REFERENCE_TODAY = "20220611";

    // Message texts, quoted from app/cpy/CSUTLDPY.cpy.

    /** {@code app/cpy/CSUTLDPY.cpy:L37}, reached when the four-character year is not supplied. */
    private static final String YEAR_NOT_SUPPLIED = "Date of Birth : Year must be supplied.";

    /** {@code app/cpy/CSUTLDPY.cpy:L54}, reached when the year fails the numeric class test. */
    private static final String YEAR_NOT_FOUR_DIGITS = "Date of Birth must be 4 digit number.";

    /** {@code app/cpy/CSUTLDPY.cpy:L79}, reached when the century is neither 19 nor 20. */
    private static final String CENTURY_NOT_VALID = "Date of Birth : Century is not valid.";

    /**
     * {@code app/cpy/CSUTLDPY.cpy:L119} and {@code app/cpy/CSUTLDPY.cpy:L136}, reached by the
     * month range test and by the month numeric gate.
     */
    private static final String MONTH_OUT_OF_RANGE =
            "Date of Birth: Month must be a number between 1 and 12.";

    /** {@code app/cpy/CSUTLDPY.cpy:L101}, reached when the month is not supplied. */
    private static final String MONTH_NOT_SUPPLIED = "Date of Birth : Month must be supplied.";

    /** {@code app/cpy/CSUTLDPY.cpy:L161}, reached when the day is not supplied. */
    private static final String DAY_NOT_SUPPLIED = "Date of Birth : Day must be supplied.";

    /**
     * {@code app/cpy/CSUTLDPY.cpy:L180} and {@code app/cpy/CSUTLDPY.cpy:L195}, reached by the day
     * numeric gate and by the day range test.
     */
    private static final String DAY_OUT_OF_RANGE =
            "Date of Birth:day must be a number between 1 and 31.";

    /** {@code app/cpy/CSUTLDPY.cpy:L221}, reached when day 31 lands in a shorter month. */
    private static final String DAY_31_NOT_IN_MONTH =
            "Date of Birth:Cannot have 31 days in this month.";

    /** {@code app/cpy/CSUTLDPY.cpy:L236}, reached when day 30 lands in February. */
    private static final String DAY_30_NOT_IN_MONTH =
            "Date of Birth:Cannot have 30 days in this month.";

    /** {@code app/cpy/CSUTLDPY.cpy:L266}, reached when day 29 lands in an ordinary February. */
    private static final String NOT_A_LEAP_YEAR =
            "Date of Birth:Not a leap year.Cannot have 29 days in this month.";

    /** {@code app/cpy/CSUTLDPY.cpy:L363}, reached when the date of birth is not in the past. */
    private static final String CANNOT_BE_IN_THE_FUTURE = "Date of Birth:cannot be in the future ";

    // The called program. app/cbl/CSUTLDTC.cbl.

    /**
     * Asserts that a well-formed date inside the supported range reports the valid condition,
     * severity {@code 0000}, and message number {@code 0000}. The result text is the padded literal
     * of {@code app/cbl/CSUTLDTC.cbl:L129}, and the day count numbers the earliest supported date
     * one.
     */
    @Test
    void aValidDateReportsSeverityZeroAndADayCount() {
        CobolDateValidator.DateValidationResult result =
                CobolDateValidator.validateDate(VALID_DATE,
                        CobolDateValidator.TOLERANT_POLICY_DATE_MASK);

        assertEquals(CobolDateValidator.FeedbackCondition.DATE_IS_VALID, result.condition());
        assertEquals(CobolDateValidator.ACCEPTED_SEVERITY_TEXT, result.severityText());
        assertEquals(CobolDateValidator.ACCEPTED_SEVERITY_NUMBER, result.severityNumber());
        assertEquals("0000", result.messageNumberText());
        assertEquals(0, result.messageNumber());
        assertEquals("Date is valid  ", result.resultText());
        assertEquals(VALID_DATE, result.testedDate());
        assertEquals(CobolDateValidator.TOLERANT_POLICY_DATE_MASK, result.dateMask());
        assertEquals(VALID_DATE_DAY_COUNT, result.dayCount());

        assertTrue(result.acceptedByTolerantPolicy(),
                "the tolerant policy refused severity zero");
        assertTrue(result.acceptedByStrictPolicy(),
                "the strict policy refused severity zero");
    }

    /**
     * Asserts that the eight-character mask of {@code app/cpy/CSUTLDPY.cpy:L291} and the
     * ten-character mask of {@code app/cbl/COTRN02C.cbl} report the same day count for the same
     * calendar date.
     */
    @Test
    void bothSourceMasksReportTheSameDayCountForOneDate() {
        CobolDateValidator.DateValidationResult tolerant = CobolDateValidator.validateDate(
                VALID_DATE, CobolDateValidator.TOLERANT_POLICY_DATE_MASK);
        CobolDateValidator.DateValidationResult strict = CobolDateValidator.validateDate(
                VALID_DATE_WITHOUT_SEPARATORS, CobolDateValidator.STRICT_POLICY_DATE_MASK);

        assertEquals(tolerant.dayCount(), strict.dayCount());
        assertEquals(CobolDateValidator.FeedbackCondition.DATE_IS_VALID, strict.condition());
        assertEquals(PADDED_STRICT_POLICY_DATE_MASK, strict.dateMask());
    }

    /**
     * Asserts the {@code 2513} branch at {@code app/cbl/COTRN02C.cbl:L400}. A well-formed date
     * outside the supported range reports
     * {@link CobolDateValidator.FeedbackCondition#UNSUPPORTED_RANGE}, whose severity is 3. The
     * severity half of the policy therefore fails, and the message number carries the acceptance
     * alone.
     *
     * <p>Each input runs under the mask its own call site passes, so the branch holds under the
     * eight-character mask and the ten-character mask alike.
     * {@link #theStrictPolicyRejectsTheSameDateTheToleratedMessageNumberAccepts()} takes the same
     * inputs and asserts the opposite verdict.
     */
    @Test
    void anOutOfRangeDateIsAcceptedOnlyByTheToleratedMessageNumber() {
        for (String[] dateAndMask : OUT_OF_RANGE_DATES_AND_MASKS) {
            String date = dateAndMask[0];
            String mask = dateAndMask[1];
            CobolDateValidator.DateValidationResult result =
                    CobolDateValidator.validateDate(date, mask);

            assertEquals(CobolDateValidator.FeedbackCondition.UNSUPPORTED_RANGE,
                    result.condition(), date);
            assertEquals(CobolDateValidator.TOLERATED_MESSAGE_NUMBER_TEXT,
                    result.messageNumberText(), date);
            assertEquals(CobolDateValidator.TOLERATED_MESSAGE_NUMBER, result.messageNumber(), date);

            assertNotEquals(CobolDateValidator.ACCEPTED_SEVERITY_TEXT, result.severityText(), date);
            assertNotEquals(CobolDateValidator.ACCEPTED_SEVERITY_NUMBER, result.severityNumber(),
                    date);

            assertTrue(result.acceptedByTolerantPolicy(),
                    "the tolerant policy stopped accepting message number 2513 for " + date);
            assertTrue(CobolDateValidator.isAcceptedByTolerantPolicy(date, mask), date);
            assertTrue(CobolDateValidator.isAcceptedByTolerantPolicy(result), date);
        }
    }

    /**
     * Asserts the numeric comparison at {@code app/cpy/CSUTLDPY.cpy:L298} over the inputs
     * {@link #anOutOfRangeDateIsAcceptedOnlyByTheToleratedMessageNumber()} accepts. That paragraph
     * reads the severity redefine and holds no {@code 2513} branch, so severity 3 is a rejection.
     *
     * <p>Each input keeps its tolerant verdict here, so the two policies are shown disagreeing over
     * one outcome.
     */
    @Test
    void theStrictPolicyRejectsTheSameDateTheToleratedMessageNumberAccepts() {
        for (String[] dateAndMask : OUT_OF_RANGE_DATES_AND_MASKS) {
            String date = dateAndMask[0];
            String mask = dateAndMask[1];
            CobolDateValidator.DateValidationResult result =
                    CobolDateValidator.validateDate(date, mask);

            assertEquals(CobolDateValidator.TOLERATED_MESSAGE_NUMBER_TEXT,
                    result.messageNumberText(), date);

            assertFalse(result.acceptedByStrictPolicy(),
                    "the strict policy started accepting severity 3 for " + date);
            assertFalse(CobolDateValidator.isAcceptedByStrictPolicy(date, mask), date);
            assertFalse(CobolDateValidator.isAcceptedByStrictPolicy(result), date);

            assertTrue(result.acceptedByTolerantPolicy(),
                    "the two policies stopped disagreeing over " + date);
        }
    }

    /**
     * Asserts that exactly one condition carries message number
     * {@value CobolDateValidator#TOLERATED_MESSAGE_NUMBER}, and that every other condition other
     * than the valid one carries severity 3. The ten conditions are declared at
     * {@code app/cbl/CSUTLDTC.cbl:L62-L70}, and each severity and message number is read from the
     * token of its own declaration.
     */
    @Test
    void onlyOneConditionCarriesTheToleratedMessageNumber() {
        Set<CobolDateValidator.FeedbackCondition> tolerated =
                EnumSet.noneOf(CobolDateValidator.FeedbackCondition.class);

        for (CobolDateValidator.FeedbackCondition condition
                : CobolDateValidator.FeedbackCondition.values()) {
            if (condition.messageNumber() == CobolDateValidator.TOLERATED_MESSAGE_NUMBER) {
                tolerated.add(condition);
            }
        }

        assertEquals(EnumSet.of(CobolDateValidator.FeedbackCondition.UNSUPPORTED_RANGE), tolerated);

        for (CobolDateValidator.FeedbackCondition condition
                : CobolDateValidator.FeedbackCondition.values()) {
            int expectedSeverity =
                    condition == CobolDateValidator.FeedbackCondition.DATE_IS_VALID
                            ? CobolDateValidator.ACCEPTED_SEVERITY_NUMBER
                            : 3;

            assertEquals(expectedSeverity, condition.severity(), condition.name());
        }
    }

    /**
     * Asserts the boundary of the supported range. The earliest supported date is day one, the
     * latest carries the highest day count, and the day before the earliest reports
     * {@link CobolDateValidator.FeedbackCondition#UNSUPPORTED_RANGE}, declared at
     * {@code app/cbl/CSUTLDTC.cbl:L66}. The count lands in
     * {@code OUTPUT-LILLIAN PIC S9(9) BINARY} at {@code app/cbl/CSUTLDTC.cbl:L41}.
     */
    @Test
    void theSupportedRangeRunsFromDayOneToTheLatestDate() {
        CobolDateValidator.DateValidationResult earliest = CobolDateValidator.validateDate(
                EARLIEST_SUPPORTED_DATE_TEXT, CobolDateValidator.TOLERANT_POLICY_DATE_MASK);

        assertEquals(CobolDateValidator.FeedbackCondition.DATE_IS_VALID, earliest.condition());
        assertEquals(1, earliest.dayCount());
        assertEquals(LocalDate.of(1582, 10, 15), CobolDateValidator.EARLIEST_SUPPORTED_DATE);

        CobolDateValidator.DateValidationResult latest = CobolDateValidator.validateDate(
                LATEST_SUPPORTED_DATE_TEXT, CobolDateValidator.TOLERANT_POLICY_DATE_MASK);

        assertEquals(CobolDateValidator.FeedbackCondition.DATE_IS_VALID, latest.condition());
        assertEquals(LATEST_SUPPORTED_DATE_DAY_COUNT, latest.dayCount());
        assertEquals(LocalDate.of(9999, 12, 31), CobolDateValidator.LATEST_SUPPORTED_DATE);

        assertEquals(CobolDateValidator.FeedbackCondition.UNSUPPORTED_RANGE,
                CobolDateValidator.validateDate(ONE_DAY_BEFORE_THE_EARLIEST_DATE,
                        CobolDateValidator.TOLERANT_POLICY_DATE_MASK).condition());
    }

    /**
     * Asserts the order the checks run in, listed at {@link CobolDateValidator}. The mask is read
     * first, the width second, the separators third, the digits fourth, then the year, the month,
     * the day of the month, and the range.
     *
     * <p>Every condition below is declared at {@code app/cbl/CSUTLDTC.cbl:L62-L70} and reaches its
     * result text through the branch table at {@code app/cbl/CSUTLDTC.cbl:L128-L149}.
     */
    @Test
    void theFirstFailingCheckSelectsTheReportedCondition() {
        assertEquals(CobolDateValidator.FeedbackCondition.BAD_PICTURE_STRING,
                CobolDateValidator.validateDate(VALID_DATE, MASK_WITHOUT_A_YEAR_TOKEN).condition());
        assertEquals(CobolDateValidator.FeedbackCondition.BAD_PICTURE_STRING,
                CobolDateValidator.validateDate(VALID_DATE, MASK_WITH_AN_UNKNOWN_CHARACTER)
                        .condition());
        assertEquals(CobolDateValidator.FeedbackCondition.INSUFFICIENT_DATA,
                CobolDateValidator.validateDate(DATE_SHORTER_THAN_THE_MASK,
                        CobolDateValidator.TOLERANT_POLICY_DATE_MASK).condition());
        assertEquals(CobolDateValidator.FeedbackCondition.BAD_DATE_VALUE,
                CobolDateValidator.validateDate(DATE_WITH_THE_WRONG_SEPARATORS,
                        CobolDateValidator.TOLERANT_POLICY_DATE_MASK).condition());
        assertEquals(CobolDateValidator.FeedbackCondition.NON_NUMERIC_DATA,
                CobolDateValidator.validateDate(DATE_WITH_A_LETTER_IN_THE_MONTH,
                        CobolDateValidator.TOLERANT_POLICY_DATE_MASK).condition());
        assertEquals(CobolDateValidator.FeedbackCondition.YEAR_IN_ERA_ZERO,
                CobolDateValidator.validateDate(YEAR_ZERO_DATE,
                        CobolDateValidator.TOLERANT_POLICY_DATE_MASK).condition());
        assertEquals(CobolDateValidator.FeedbackCondition.INVALID_MONTH,
                CobolDateValidator.validateDate(MONTH_THIRTEEN_DATE,
                        CobolDateValidator.TOLERANT_POLICY_DATE_MASK).condition());
        assertEquals(CobolDateValidator.FeedbackCondition.BAD_DATE_VALUE,
                CobolDateValidator.validateDate(FEBRUARY_THIRTY_DATE,
                        CobolDateValidator.TOLERANT_POLICY_DATE_MASK).condition());
    }

    /**
     * Asserts that {@code null} on either argument produces a result and throws no exception. A
     * {@code null} date is read as spaces and reports
     * {@link CobolDateValidator.FeedbackCondition#INSUFFICIENT_DATA}; a {@code null} mask reports
     * {@link CobolDateValidator.FeedbackCondition#BAD_PICTURE_STRING}.
     *
     * <p>{@code app/cbl/CSUTLDTC.cbl:L84-L85} declares both parameters as
     * {@code PIC X(10)}, and a group item of that class holds spaces where it holds nothing else.
     */
    @Test
    void aNullArgumentProducesAResultAndThrowsNothing() {
        CobolDateValidator.DateValidationResult nullDate = CobolDateValidator.validateDate(null,
                CobolDateValidator.TOLERANT_POLICY_DATE_MASK);

        assertEquals(CobolDateValidator.FeedbackCondition.INSUFFICIENT_DATA,
                nullDate.condition());
        assertEquals(CobolDateValidator.TESTED_DATE_WIDTH, nullDate.testedDate().length());
        assertTrue(nullDate.testedDate().isBlank(),
                "a null date was read as something other than spaces");

        CobolDateValidator.DateValidationResult nullMask =
                CobolDateValidator.validateDate(VALID_DATE, null);

        assertEquals(CobolDateValidator.FeedbackCondition.BAD_PICTURE_STRING,
                nullMask.condition());
        assertEquals(CobolDateValidator.DATE_MASK_WIDTH, nullMask.dateMask().length());

        assertEquals(CobolDateValidator.FeedbackCondition.BAD_PICTURE_STRING,
                CobolDateValidator.validateDate(null, null).condition());
    }

    /**
     * Asserts that a field of {@code LOW-VALUES} is rejected under both masks, and that the mask
     * selects which check reports it. A low value is neither a digit nor the dash the tolerant mask
     * names at offset four. The tolerant mask therefore reports
     * {@link CobolDateValidator.FeedbackCondition#BAD_DATE_VALUE} at the separator check, and the
     * strict mask reaches
     * {@link CobolDateValidator.FeedbackCondition#NON_NUMERIC_DATA}.
     *
     * <p>The tolerant mask comes from {@code app/cbl/COTRN02C.cbl:L60} and the strict mask from
     * {@code app/cpy/CSUTLDPY.cpy:L291}.
     */
    @Test
    void aFieldOfLowValuesIsRejectedUnderBothMasks() {
        String lowValues =
                String.valueOf('\u0000').repeat(CobolDateValidator.TESTED_DATE_WIDTH);

        assertEquals(CobolDateValidator.FeedbackCondition.BAD_DATE_VALUE,
                CobolDateValidator.validateDate(lowValues,
                        CobolDateValidator.TOLERANT_POLICY_DATE_MASK).condition());
        assertEquals(CobolDateValidator.FeedbackCondition.NON_NUMERIC_DATA,
                CobolDateValidator.validateDate(lowValues,
                        CobolDateValidator.STRICT_POLICY_DATE_MASK).condition());

        for (String mask : new String[] {CobolDateValidator.TOLERANT_POLICY_DATE_MASK,
                CobolDateValidator.STRICT_POLICY_DATE_MASK}) {
            assertFalse(CobolDateValidator.isAcceptedByTolerantPolicy(lowValues, mask), mask);
            assertFalse(CobolDateValidator.isAcceptedByStrictPolicy(lowValues, mask), mask);
        }
    }

    /**
     * Asserts the leap-year rule of {@code app/cbl/CSUTLDTC.cbl} over four years. A year divisible
     * by four holds 29 February, and a year that is not divisible by four does not. A century year
     * divisible by 400 holds it, and a century year that is not divisible by 400 does not.
     */
    @Test
    void theLeapDayFollowsTheGregorianRule() {
        assertEquals(CobolDateValidator.FeedbackCondition.DATE_IS_VALID,
                CobolDateValidator.validateDate(LEAP_DAY_IN_A_LEAP_YEAR,
                        CobolDateValidator.TOLERANT_POLICY_DATE_MASK).condition());
        assertEquals(CobolDateValidator.FeedbackCondition.BAD_DATE_VALUE,
                CobolDateValidator.validateDate(LEAP_DAY_IN_AN_ORDINARY_YEAR,
                        CobolDateValidator.TOLERANT_POLICY_DATE_MASK).condition());
        assertEquals(CobolDateValidator.FeedbackCondition.DATE_IS_VALID,
                CobolDateValidator.validateDate(LEAP_DAY_IN_A_LEAP_CENTURY,
                        CobolDateValidator.TOLERANT_POLICY_DATE_MASK).condition());
        assertEquals(CobolDateValidator.FeedbackCondition.BAD_DATE_VALUE,
                CobolDateValidator.validateDate(LEAP_DAY_IN_AN_ORDINARY_CENTURY,
                        CobolDateValidator.TOLERANT_POLICY_DATE_MASK).condition());
    }

    /**
     * Asserts the eighty-character layout {@code app/cbl/CSUTLDTC.cbl:L97} moves into
     * {@code LS-RESULT PIC X(80)}, field by field, using the five filler literals at
     * {@code app/cbl/CSUTLDTC.cbl:L45}, {@code :L48}, {@code :L51}, {@code :L54} and {@code :L57}.
     */
    @Test
    void theRenderedFeedbackHoldsEightyCharactersInTheSourceLayout() {
        CobolDateValidator.DateValidationResult result = CobolDateValidator.validateDate(
                VALID_DATE, CobolDateValidator.TOLERANT_POLICY_DATE_MASK);
        String rendered = result.renderedResult();

        assertEquals(CobolDateValidator.RENDERED_RESULT_WIDTH, rendered.length());
        assertEquals("0000Mesg Code: 0000 Date is valid   TstDate: 2022-06-10 "
                + "Mask used:YYYY-MM-DD    ", rendered);

        assertEquals(result.severityText(), rendered.substring(0, 4));
        assertEquals(result.messageNumberText(), rendered.substring(15, 19));
        assertEquals(result.resultText(), rendered.substring(20, 35));
        assertEquals(result.testedDate(), rendered.substring(45, 55));
        assertEquals(result.dateMask(), rendered.substring(66, 76));
    }

    /**
     * Asserts that every rendered outcome holds eighty characters, whichever condition it carries,
     * and that the severity and the message number sit at the same offsets in each.
     * {@code app/cbl/CSUTLDTC.cbl:L42-L57} declares the layout and
     * {@code app/cbl/CSUTLDTC.cbl:L97} moves it whole.
     */
    @Test
    void everyRenderedOutcomeHoldsEightyCharacters() {
        String[] dates = {VALID_DATE, ONE_DAY_BEFORE_THE_EARLIEST_DATE, YEAR_ZERO_DATE,
                MONTH_THIRTEEN_DATE, FEBRUARY_THIRTY_DATE, DATE_WITH_A_LETTER_IN_THE_MONTH,
                DATE_WITH_THE_WRONG_SEPARATORS, DATE_SHORTER_THAN_THE_MASK, null};

        for (String date : dates) {
            CobolDateValidator.DateValidationResult result = CobolDateValidator.validateDate(date,
                    CobolDateValidator.TOLERANT_POLICY_DATE_MASK);

            assertEquals(CobolDateValidator.RENDERED_RESULT_WIDTH,
                    result.renderedResult().length(), String.valueOf(date));
            assertEquals(result.severityText(), result.renderedResult().substring(0, 4),
                    String.valueOf(date));
            assertEquals(result.messageNumberText(), result.renderedResult().substring(15, 19),
                    String.valueOf(date));
        }
    }

    /**
     * Asserts the ten branches of {@code app/cbl/CSUTLDTC.cbl:L128-L149}. Nine name a token, and
     * the tenth is {@code WHEN OTHER} at {@code app/cbl/CSUTLDTC.cbl:L148}, which moves
     * {@value CobolDateValidator#UNRECOGNISED_FEEDBACK_RESULT_TEXT}. Every returned text holds
     * fifteen characters.
     */
    @Test
    void everyFeedbackTokenSelectsItsResultTextAndUnknownTokensFallThrough() {
        Set<String> distinctTexts = new HashSet<>();

        for (CobolDateValidator.FeedbackCondition condition
                : CobolDateValidator.FeedbackCondition.values()) {
            String text = CobolDateValidator
                    .resultTextForFeedbackToken(condition.feedbackToken());

            assertEquals(condition.resultText(), text, condition.name());
            assertEquals(CobolDateValidator.RESULT_TEXT_WIDTH, text.length(), condition.name());
            distinctTexts.add(text);
        }

        assertEquals(CobolDateValidator.FeedbackCondition.values().length, distinctTexts.size());

        for (String unknown : new String[] {UNRECOGNISED_FEEDBACK_TOKEN, "0000", "", null}) {
            assertEquals(CobolDateValidator.UNRECOGNISED_FEEDBACK_RESULT_TEXT,
                    CobolDateValidator.resultTextForFeedbackToken(unknown).strip(),
                    String.valueOf(unknown));
            assertEquals(CobolDateValidator.RESULT_TEXT_WIDTH,
                    CobolDateValidator.resultTextForFeedbackToken(unknown).length(),
                    String.valueOf(unknown));
        }

        assertEquals(CobolDateValidator.FeedbackCondition.BAD_DATE_VALUE,
                CobolDateValidator.FeedbackCondition.fromFeedbackToken(LOWER_CASE_BAD_DATE_TOKEN));
        assertNull(CobolDateValidator.FeedbackCondition
                        .fromFeedbackToken(UNRECOGNISED_FEEDBACK_TOKEN),
                "an unrecognised feedback token mapped to a condition");
        assertNull(CobolDateValidator.FeedbackCondition.fromFeedbackToken(null),
                "a null feedback token mapped to a condition");
    }

    /**
     * Asserts the widths of the ten result literals at
     * {@code app/cbl/CSUTLDTC.cbl:L128-L149}. Eight of them fill
     * {@value CobolDateValidator#RESULT_TEXT_WIDTH} characters on their own. Two are shorter, and
     * the source {@code MOVE} into {@code WS-RESULT PIC X(15)} pads those two on the right.
     *
     * <p>The author counted the same fifteen columns in the ruler comment at
     * {@code app/cbl/CSUTLDTC.cbl:L126-L127}.
     */
    @Test
    void eightResultLiteralsFillFifteenCharactersAndTwoAreShorter() {
        String[] sourceLiterals = {"Date is valid", "Insufficient", "Datevalue error",
                "Invalid Era    ", "Unsupp. Range  ", "Invalid month  ", "Bad Pic String ",
                "Nonnumeric data", "YearInEra is 0 ", "Date is invalid"};

        int atFifteen = 0;
        int shorter = 0;
        for (String literal : sourceLiterals) {
            if (literal.length() == CobolDateValidator.RESULT_TEXT_WIDTH) {
                atFifteen++;
            } else {
                shorter++;
            }
        }

        assertEquals(10, sourceLiterals.length, "app/cbl/CSUTLDTC.cbl:L128-L149");
        assertEquals(8, atFifteen, "eight literals fill fifteen characters");
        assertEquals(2, shorter, "two literals are shorter than fifteen characters");

        assertEquals(13, "Date is valid".length(), "app/cbl/CSUTLDTC.cbl:L130");
        assertEquals(12, "Insufficient".length(), "app/cbl/CSUTLDTC.cbl:L132");

        assertEquals("Date is valid  ",
                CobolDateValidator.FeedbackCondition.DATE_IS_VALID.resultText());
        assertEquals("Insufficient   ",
                CobolDateValidator.FeedbackCondition.INSUFFICIENT_DATA.resultText());
        assertEquals("Datevalue error",
                CobolDateValidator.FeedbackCondition.BAD_DATE_VALUE.resultText());
    }

    /**
     * Asserts that both policy predicates reject a {@code null} outcome. The tolerant policy is
     * read at {@code app/cbl/COTRN02C.cbl:L397-L400} and the strict policy at
     * {@code app/cpy/CSUTLDPY.cpy:L298}, and neither source site can be handed a missing result.
     */
    @Test
    void bothPolicyPredicatesRejectANullOutcome() {
        assertThrows(NullPointerException.class,
                () -> CobolDateValidator.isAcceptedByTolerantPolicy(
                        (CobolDateValidator.DateValidationResult) null));
        assertThrows(NullPointerException.class,
                () -> CobolDateValidator.isAcceptedByStrictPolicy(
                        (CobolDateValidator.DateValidationResult) null));
    }

    /**
     * Asserts that the outcome record holds every text field to the width of its source
     * declaration, whatever width the caller supplied. Each width comes from
     * {@code app/cbl/CSUTLDTC.cbl:L42-L57}, whose fields sum to
     * {@value CobolDateValidator#RENDERED_RESULT_WIDTH}.
     */
    @Test
    void theOutcomeRecordHoldsEveryTextFieldToItsDeclaredWidth() {
        CobolDateValidator.DateValidationResult result =
                new CobolDateValidator.DateValidationResult(
                        CobolDateValidator.FeedbackCondition.DATE_IS_VALID, "0", 0, "0", 0, "x",
                        "y", "z", 0);

        assertEquals(CobolDateValidator.SEVERITY_WIDTH, result.severityText().length());
        assertEquals(CobolDateValidator.MESSAGE_NUMBER_WIDTH,
                result.messageNumberText().length());
        assertEquals(CobolDateValidator.RESULT_TEXT_WIDTH, result.resultText().length());
        assertEquals(CobolDateValidator.TESTED_DATE_WIDTH, result.testedDate().length());
        assertEquals(CobolDateValidator.DATE_MASK_WIDTH, result.dateMask().length());
        assertEquals(CobolDateValidator.RENDERED_RESULT_WIDTH,
                result.renderedResult().length());

        assertThrows(NullPointerException.class,
                () -> new CobolDateValidator.DateValidationResult(null, "0000", 0, "0000", 0, "",
                        "", "", 0));
    }

    // The field edits. app/cpy/CSUTLDPY.cpy.

    /**
     * Asserts the thirteen message literals of {@code app/cpy/CSUTLDPY.cpy} character for
     * character. The message argument of each assertion names the source line, and each expected
     * value keeps the spacing, the casing, and the punctuation of that line.
     */
    @Test
    void theThirteenMessageLiteralsMatchTheirSourceLines() {
        assertEquals(" : Year must be supplied.", CobolDateValidator.YEAR_NOT_SUPPLIED_MESSAGE,
                "app/cpy/CSUTLDPY.cpy:L37");
        assertEquals(" must be 4 digit number.", CobolDateValidator.YEAR_NOT_FOUR_DIGITS_MESSAGE,
                "app/cpy/CSUTLDPY.cpy:L54");
        assertEquals(" : Century is not valid.", CobolDateValidator.CENTURY_NOT_VALID_MESSAGE,
                "app/cpy/CSUTLDPY.cpy:L79");
        assertEquals(" : Month must be supplied.", CobolDateValidator.MONTH_NOT_SUPPLIED_MESSAGE,
                "app/cpy/CSUTLDPY.cpy:L101");
        assertEquals(": Month must be a number between 1 and 12.",
                CobolDateValidator.MONTH_OUT_OF_RANGE_MESSAGE,
                "app/cpy/CSUTLDPY.cpy:L119 and app/cpy/CSUTLDPY.cpy:L136");
        assertEquals(" : Day must be supplied.", CobolDateValidator.DAY_NOT_SUPPLIED_MESSAGE,
                "app/cpy/CSUTLDPY.cpy:L161");
        assertEquals(":day must be a number between 1 and 31.",
                CobolDateValidator.DAY_OUT_OF_RANGE_MESSAGE,
                "app/cpy/CSUTLDPY.cpy:L180 and app/cpy/CSUTLDPY.cpy:L195");
        assertEquals(":Cannot have 31 days in this month.",
                CobolDateValidator.DAY_31_NOT_IN_MONTH_MESSAGE, "app/cpy/CSUTLDPY.cpy:L221");
        assertEquals(":Cannot have 30 days in this month.",
                CobolDateValidator.DAY_30_NOT_IN_MONTH_MESSAGE, "app/cpy/CSUTLDPY.cpy:L236");
        assertEquals(":Not a leap year.Cannot have 29 days in this month.",
                CobolDateValidator.NOT_A_LEAP_YEAR_MESSAGE, "app/cpy/CSUTLDPY.cpy:L266");
        assertEquals(" validation error Sev code: ", CobolDateValidator.SEVERITY_CODE_MESSAGE,
                "app/cpy/CSUTLDPY.cpy:L308");
        assertEquals(" Message code: ", CobolDateValidator.MESSAGE_CODE_MESSAGE,
                "app/cpy/CSUTLDPY.cpy:L310");
        assertEquals(":cannot be in the future ", CobolDateValidator.FUTURE_DATE_MESSAGE,
                "app/cpy/CSUTLDPY.cpy:L363");
    }

    /**
     * Asserts the composition the strict policy builds on rejection. The {@code STRING} statement
     * at {@code app/cpy/CSUTLDPY.cpy:L306-L313} joins five parts in order. They are the trimmed
     * field name, the literal of {@code app/cpy/CSUTLDPY.cpy:L308}, the four-character severity of
     * {@code app/cpy/CSUTLDPY.cpy:L309}, the literal of {@code app/cpy/CSUTLDPY.cpy:L310}, and the
     * four-character message number of {@code app/cpy/CSUTLDPY.cpy:L311}.
     */
    @Test
    void theStrictPolicyRejectionComposesTheSeverityAndTheMessageCode() {
        CobolDateValidator.DateValidationResult rejected = CobolDateValidator.validateDate(
                ONE_DAY_BEFORE_THE_EARLIEST_DATE, CobolDateValidator.TOLERANT_POLICY_DATE_MASK);

        assertFalse(rejected.acceptedByStrictPolicy(),
                "the strict policy accepted severity 3");
        assertEquals("Date of Birth validation error Sev code: 0003 Message code: 2513",
                EDIT_VARIABLE_NAME + CobolDateValidator.SEVERITY_CODE_MESSAGE
                        + rejected.severityText() + CobolDateValidator.MESSAGE_CODE_MESSAGE
                        + rejected.messageNumberText());
    }

    /**
     * Asserts that a well-formed eight-character date clears all six paragraphs, leaves all three
     * flag bytes valid, composes no message, and carries the outcome of the strict-policy call at
     * {@code app/cpy/CSUTLDPY.cpy:L293-L296}.
     */
    @Test
    void aWellFormedEditDateClearsEveryParagraph() {
        CobolDateValidator.FieldEditResult result = CobolDateValidator
                .editDateCcyymmdd(VALID_DATE_WITHOUT_SEPARATORS, EDIT_VARIABLE_NAME);

        assertFalse(result.inputError(),
                "a well-formed date raised the input-error flag");
        assertEquals(CobolDateValidator.FieldEditFlag.IS_VALID, result.yearFlag());
        assertEquals(CobolDateValidator.FieldEditFlag.IS_VALID, result.monthFlag());
        assertEquals(CobolDateValidator.FieldEditFlag.IS_VALID, result.dayFlag());
        assertEquals("", result.firstReturnMessage());
        assertFalse(result.hasReturnMessage(),
                "a well-formed date composed a return message");
        assertTrue(result.accepted(), "a well-formed date was not accepted");

        assertNotNull(result.dateValidation(),
                "a well-formed date carried no strict-policy outcome");
        assertEquals(CobolDateValidator.FeedbackCondition.DATE_IS_VALID,
                result.dateValidation().condition());
        assertEquals(PADDED_STRICT_POLICY_DATE_MASK, result.dateValidation().dateMask());
        assertEquals(VALID_DATE_DAY_COUNT, result.dateValidation().dayCount());
    }

    /**
     * Asserts that a field of {@code LOW-VALUES}, a field of spaces, and a {@code null} argument
     * each reach the year check at {@code app/cpy/CSUTLDPY.cpy:L30}. Each one sets all three flag
     * bytes to {@link CobolDateValidator.FieldEditFlag#BLANK} and composes the year message.
     */
    @Test
    void anEditDateOfLowValuesOrSpacesReachesTheYearNotSuppliedMessage() {
        for (String editDate : new String[] {LOW_VALUES_EDIT_DATE, SPACES_EDIT_DATE, null}) {
            CobolDateValidator.FieldEditResult result =
                    CobolDateValidator.editDateCcyymmdd(editDate, EDIT_VARIABLE_NAME);

            assertTrue(result.inputError(),
                    "a blank edit date left the input-error flag clear");
            assertEquals(CobolDateValidator.FieldEditFlag.BLANK, result.yearFlag());
            assertEquals(CobolDateValidator.FieldEditFlag.BLANK, result.monthFlag());
            assertEquals(CobolDateValidator.FieldEditFlag.BLANK, result.dayFlag());
            assertEquals(YEAR_NOT_SUPPLIED, result.firstReturnMessage());
            assertTrue(result.hasReturnMessage(),
                    "a blank edit date composed no return message");
            assertFalse(result.accepted(), "a blank edit date was accepted");
            assertNull(result.dateValidation(),
                    "a rejected edit date carried a strict-policy outcome");
        }
    }

    /**
     * Asserts the year edit at {@code app/cpy/CSUTLDPY.cpy:L25-L87}. Century
     * {@value CobolDateValidator#THIS_CENTURY} and century
     * {@value CobolDateValidator#LAST_CENTURY} pass, and any other century composes the century
     * message.
     */
    @Test
    void theYearEditAcceptsTwoCenturiesAndRejectsTheRest() {
        CobolDateValidator.FieldEditResult rejected =
                CobolDateValidator.editDateCcyymmdd(CENTURY_EIGHTEEN_DATE, EDIT_VARIABLE_NAME);

        assertTrue(rejected.inputError(),
                "century eighteen left the input-error flag clear");
        assertEquals(CobolDateValidator.FieldEditFlag.NOT_OK, rejected.yearFlag());
        assertEquals(CENTURY_NOT_VALID, rejected.firstReturnMessage());
        assertFalse(rejected.accepted(), "century eighteen was accepted");

        assertEquals(20, CobolDateValidator.THIS_CENTURY);
        assertEquals(19, CobolDateValidator.LAST_CENTURY);

        assertTrue(CobolDateValidator.editDateCcyymmdd("19991231", EDIT_VARIABLE_NAME).accepted(),
                "century nineteen was rejected");
        assertTrue(CobolDateValidator.editDateCcyymmdd("20991231", EDIT_VARIABLE_NAME).accepted(),
                "century twenty was rejected");
    }

    /**
     * Asserts the second year check, the numeric class test at
     * {@code app/cpy/CSUTLDPY.cpy:L48}, whose message sits at {@code app/cpy/CSUTLDPY.cpy:L54}. A
     * year carrying a letter fails the test and leaves the year flag not valid.
     */
    @Test
    void aYearCarryingALetterComposesTheFourDigitMessage() {
        CobolDateValidator.FieldEditResult result = CobolDateValidator
                .editDateCcyymmdd(EDIT_DATE_WITH_A_LETTER_IN_THE_YEAR, EDIT_VARIABLE_NAME);

        assertTrue(result.inputError(),
                "a year carrying a letter left the input-error flag clear");
        assertEquals(CobolDateValidator.FieldEditFlag.NOT_OK, result.yearFlag());
        assertEquals(YEAR_NOT_FOUR_DIGITS, result.firstReturnMessage());
        assertFalse(result.accepted(), "a year carrying a letter was accepted");
    }

    /**
     * Asserts the first day check at {@code app/cpy/CSUTLDPY.cpy:L154-L155}, whose message sits at
     * {@code app/cpy/CSUTLDPY.cpy:L161}. A day field of two spaces sets the day flag to
     * {@link CobolDateValidator.FieldEditFlag#BLANK}, and the year flag and the month flag stay
     * valid.
     */
    @Test
    void aBlankDayComposesTheDayNotSuppliedMessage() {
        CobolDateValidator.FieldEditResult result = CobolDateValidator
                .editDateCcyymmdd(EDIT_DATE_WITH_A_BLANK_DAY, EDIT_VARIABLE_NAME);

        assertTrue(result.inputError(), "a blank day left the input-error flag clear");
        assertEquals(CobolDateValidator.FieldEditFlag.IS_VALID, result.yearFlag());
        assertEquals(CobolDateValidator.FieldEditFlag.IS_VALID, result.monthFlag());
        assertEquals(CobolDateValidator.FieldEditFlag.BLANK, result.dayFlag());
        assertEquals(DAY_NOT_SUPPLIED, result.firstReturnMessage());
        assertFalse(result.accepted(), "a blank day was accepted");
    }

    /**
     * Asserts the month edit at {@code app/cpy/CSUTLDPY.cpy:L91-L144}. The range test runs ahead of
     * the numeric gate, and both failure paths compose the same message. A blank month composes
     * the not-supplied message instead.
     */
    @Test
    void theMonthEditReportsOneMessageForBothFailurePaths() {
        for (String editDate : new String[] {EDIT_DATE_WITH_MONTH_THIRTEEN,
                EDIT_DATE_WITH_LETTERS_IN_THE_MONTH}) {
            CobolDateValidator.FieldEditResult result =
                    CobolDateValidator.editDateCcyymmdd(editDate, EDIT_VARIABLE_NAME);

            assertTrue(result.inputError(), editDate);
            assertEquals(CobolDateValidator.FieldEditFlag.IS_VALID, result.yearFlag(), editDate);
            assertEquals(CobolDateValidator.FieldEditFlag.NOT_OK, result.monthFlag(), editDate);
            assertEquals(MONTH_OUT_OF_RANGE, result.firstReturnMessage(), editDate);
        }

        CobolDateValidator.FieldEditResult blankMonth =
                CobolDateValidator.editDateCcyymmdd(EDIT_DATE_WITH_A_BLANK_MONTH,
                        EDIT_VARIABLE_NAME);

        assertEquals(CobolDateValidator.FieldEditFlag.BLANK, blankMonth.monthFlag());
        assertEquals(MONTH_NOT_SUPPLIED, blankMonth.firstReturnMessage());
    }

    /**
     * Asserts the day edit at {@code app/cpy/CSUTLDPY.cpy:L150-L204}. Day zero and day 32 both
     * compose the day range message and leave the year flag and the month flag valid.
     */
    @Test
    void theDayEditRejectsDayZeroAndDayThirtyTwo() {
        for (String editDate : new String[] {EDIT_DATE_WITH_DAY_ZERO,
                EDIT_DATE_WITH_DAY_THIRTY_TWO}) {
            CobolDateValidator.FieldEditResult result =
                    CobolDateValidator.editDateCcyymmdd(editDate, EDIT_VARIABLE_NAME);

            assertTrue(result.inputError(), editDate);
            assertEquals(CobolDateValidator.FieldEditFlag.IS_VALID, result.yearFlag(), editDate);
            assertEquals(CobolDateValidator.FieldEditFlag.IS_VALID, result.monthFlag(), editDate);
            assertEquals(CobolDateValidator.FieldEditFlag.NOT_OK, result.dayFlag(), editDate);
            assertEquals(DAY_OUT_OF_RANGE, result.firstReturnMessage(), editDate);
        }
    }

    /**
     * Asserts the check order of {@code EDIT-MONTH}. The range test at
     * {@code app/cpy/CSUTLDPY.cpy:L111} runs ahead of the numeric gate at
     * {@code app/cpy/CSUTLDPY.cpy:L126}, so the range test reads the two characters through the
     * {@code PIC 9(2)} redefine that {@code app/cpy/CSUTLDWY.cpy:L17-L18} declares.
     *
     * <p>The gate accepts {@value #SIGNED_FIELD} and the redefine reads no value from it, so the
     * order decides the verdict. The month field is rejected.
     * {@link #theDayEditNumericGateRunsAheadOfItsRangeTest()} hands the same two characters to the
     * day field.
     */
    @Test
    void theMonthEditRangeTestRunsAheadOfItsNumericGate() {
        assertTrue(NumvalParser.isValidNumval(SIGNED_FIELD),
                "the numeric gate stopped accepting a signed field");

        CobolDateValidator.FieldEditResult result = CobolDateValidator
                .editDateCcyymmdd(EDIT_DATE_WITH_A_SIGNED_MONTH, EDIT_VARIABLE_NAME);

        assertFalse(result.accepted(), "the month range test accepted a signed field");
        assertTrue(result.inputError(),
                "the month range test left the input-error flag clear");
        assertEquals(CobolDateValidator.FieldEditFlag.IS_VALID, result.yearFlag());
        assertEquals(CobolDateValidator.FieldEditFlag.NOT_OK, result.monthFlag());
        assertEquals(MONTH_OUT_OF_RANGE, result.firstReturnMessage());
        assertNull(result.dateValidation(),
                "a rejected month reached the strict-policy call");
    }

    /**
     * Asserts the check order of {@code EDIT-DAY}. The numeric gate at
     * {@code app/cpy/CSUTLDPY.cpy:L170} runs ahead of the range test at
     * {@code app/cpy/CSUTLDPY.cpy:L187}, so the range test reads a converted value.
     *
     * <p>The gate converts {@value #SIGNED_FIELD} to 5 and the range test clears it, so the day
     * field is accepted. {@link #theMonthEditRangeTestRunsAheadOfItsNumericGate()} rejects the same
     * two characters in the month field, and the opposite check orders are the whole of the
     * difference.
     */
    @Test
    void theDayEditNumericGateRunsAheadOfItsRangeTest() {
        CobolDateValidator.FieldEditResult result = CobolDateValidator
                .editDateCcyymmdd(EDIT_DATE_WITH_A_SIGNED_DAY, EDIT_VARIABLE_NAME);

        assertTrue(result.accepted(), "the day numeric gate rejected a signed field");
        assertFalse(result.inputError(),
                "the day numeric gate raised the input-error flag");
        assertEquals(CobolDateValidator.FieldEditFlag.IS_VALID, result.dayFlag());
        assertEquals("", result.firstReturnMessage());

        assertNotNull(result.dateValidation(),
                "an accepted day carried no strict-policy outcome");
        assertEquals(SIGNED_DAY_AFTER_CONVERSION, result.dateValidation().testedDate());
    }

    /**
     * Asserts the combination edit at {@code app/cpy/CSUTLDPY.cpy:L209-L279}. The edit rejects
     * three pairings: day 31 in a shorter month, day 30 in February, and day 29 in an ordinary
     * February. Each one composes its own message and sets both the day flag and the month flag.
     */
    @Test
    void theCombinationEditRejectsThreeDayAndMonthPairings() {
        CobolDateValidator.FieldEditResult dayThirtyOne = CobolDateValidator
                .editDateCcyymmdd(EDIT_DATE_WITH_DAY_31_IN_A_SHORT_MONTH, EDIT_VARIABLE_NAME);

        assertEquals(DAY_31_NOT_IN_MONTH, dayThirtyOne.firstReturnMessage());
        assertEquals(CobolDateValidator.FieldEditFlag.NOT_OK, dayThirtyOne.dayFlag());
        assertEquals(CobolDateValidator.FieldEditFlag.NOT_OK, dayThirtyOne.monthFlag());

        CobolDateValidator.FieldEditResult dayThirty = CobolDateValidator
                .editDateCcyymmdd(EDIT_DATE_WITH_DAY_30_IN_FEBRUARY, EDIT_VARIABLE_NAME);

        assertEquals(DAY_30_NOT_IN_MONTH, dayThirty.firstReturnMessage());
        assertEquals(CobolDateValidator.FieldEditFlag.NOT_OK, dayThirty.dayFlag());
        assertEquals(CobolDateValidator.FieldEditFlag.NOT_OK, dayThirty.monthFlag());

        CobolDateValidator.FieldEditResult ordinaryYear = CobolDateValidator
                .editDateCcyymmdd(EDIT_DATE_WITH_LEAP_DAY_IN_AN_ORDINARY_YEAR, EDIT_VARIABLE_NAME);

        assertEquals(NOT_A_LEAP_YEAR, ordinaryYear.firstReturnMessage());
        assertEquals(CobolDateValidator.FieldEditFlag.NOT_OK, ordinaryYear.yearFlag());
        assertEquals(CobolDateValidator.FieldEditFlag.NOT_OK, ordinaryYear.monthFlag());
        assertEquals(CobolDateValidator.FieldEditFlag.NOT_OK, ordinaryYear.dayFlag());
    }

    /**
     * Asserts the divisor switch at {@code app/cpy/CSUTLDPY.cpy:L243-L256}. A two-digit year of
     * zero divides by {@value CobolDateValidator#CENTURY_LEAP_DIVISOR}, and any other two-digit
     * year divides by {@value CobolDateValidator#ORDINARY_LEAP_DIVISOR}.
     */
    @Test
    void theLeapYearDivisorSwitchesOnTheTwoDigitYear() {
        assertTrue(CobolDateValidator
                        .editDateCcyymmdd(EDIT_DATE_WITH_LEAP_DAY_IN_A_LEAP_YEAR, EDIT_VARIABLE_NAME)
                        .accepted(),
                "a leap day in a leap year was rejected");
        assertTrue(CobolDateValidator
                        .editDateCcyymmdd(EDIT_DATE_WITH_LEAP_DAY_IN_A_LEAP_CENTURY,
                                EDIT_VARIABLE_NAME)
                        .accepted(),
                "a leap day in a leap century was rejected");

        CobolDateValidator.FieldEditResult ordinaryCentury = CobolDateValidator
                .editDateCcyymmdd(EDIT_DATE_WITH_LEAP_DAY_IN_AN_ORDINARY_CENTURY,
                        EDIT_VARIABLE_NAME);

        assertFalse(ordinaryCentury.accepted(),
                "a leap day in an ordinary century was accepted");
        assertEquals(NOT_A_LEAP_YEAR, ordinaryCentury.firstReturnMessage());

        assertFalse(CobolDateValidator.editDateCcyymmdd("19000229", EDIT_VARIABLE_NAME).accepted(),
                "1900 divides by four and not by 400, so it holds no leap day");
        assertTrue(CobolDateValidator.editDateCcyymmdd("20000229", EDIT_VARIABLE_NAME).accepted(),
                "2000 divides by 400, so it holds a leap day");
    }

    /**
     * Asserts that one pass composes one message. The year, the month and the day all fail in this
     * date, and the message is the one the year edit composed first, matching
     * {@code IF WS-RETURN-MSG-OFF} at {@code app/cbl/COACTUPC.cbl:L479-L480}.
     */
    @Test
    void onePassComposesTheFirstMessageOnly() {
        CobolDateValidator.FieldEditResult result =
                CobolDateValidator.editDateCcyymmdd("18221399", EDIT_VARIABLE_NAME);

        assertEquals(CENTURY_NOT_VALID, result.firstReturnMessage());
        assertEquals(CobolDateValidator.FieldEditFlag.NOT_OK, result.yearFlag());
        assertEquals(CobolDateValidator.FieldEditFlag.NOT_OK, result.monthFlag());
        assertEquals(CobolDateValidator.FieldEditFlag.NOT_OK, result.dayFlag());
        assertFalse(result.accepted(),
                "a date whose year, month and day all fail was accepted");
    }

    /**
     * Asserts that the edit holds the argument to
     * {@value CobolDateValidator#EDIT_DATE_WIDTH} characters. A shorter argument is padded with
     * spaces, and a longer one is cut. {@code app/cpy/CSUTLDWY.cpy:L4-L8} declares
     * {@code WS-EDIT-DATE-CCYYMMDD} and its eight subordinate characters.
     */
    @Test
    void theEditHoldsItsArgumentToEightCharacters() {
        assertEquals(8, CobolDateValidator.EDIT_DATE_WIDTH);

        CobolDateValidator.FieldEditResult cut = CobolDateValidator
                .editDateCcyymmdd(VALID_DATE_WITHOUT_SEPARATORS + "99", EDIT_VARIABLE_NAME);

        assertTrue(cut.accepted(), "a longer argument was not cut to eight characters");
        assertEquals(VALID_DATE_DAY_COUNT, cut.dateValidation().dayCount());

        CobolDateValidator.FieldEditResult padded =
                CobolDateValidator.editDateCcyymmdd("2022", EDIT_VARIABLE_NAME);

        assertEquals(MONTH_NOT_SUPPLIED, padded.firstReturnMessage());
    }

    /**
     * Asserts that the field name opens every message and that a {@code null} name contributes no
     * characters. The name is trimmed, matching
     * {@code FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)} over
     * {@code WS-EDIT-VARIABLE-NAME PIC X(25)} at {@code app/cbl/COACTUPC.cbl:L53}.
     */
    @Test
    void theFieldNameOpensEveryMessageAndIsTrimmed() {
        assertEquals(YEAR_NOT_SUPPLIED, CobolDateValidator
                .editDateCcyymmdd(SPACES_EDIT_DATE, "   " + EDIT_VARIABLE_NAME + "   ")
                .firstReturnMessage());
        assertEquals(CobolDateValidator.YEAR_NOT_SUPPLIED_MESSAGE, CobolDateValidator
                .editDateCcyymmdd(SPACES_EDIT_DATE, null).firstReturnMessage());
        assertEquals(25, CobolDateValidator.EDIT_VARIABLE_NAME_WIDTH);
    }

    /**
     * Asserts that the outcome record rejects a missing flag and holds the message to
     * {@value CobolDateValidator#RETURN_MESSAGE_WIDTH} characters.
     * {@code app/cbl/COACTUPC.cbl:L479-L480} declares
     * {@code WS-RETURN-MSG PIC X(75)} and the condition name that tests it for spaces.
     */
    @Test
    void theFieldEditRecordRejectsAMissingFlagAndCutsTheMessage() {
        String overLong = "x".repeat(CobolDateValidator.RETURN_MESSAGE_WIDTH + 10);

        CobolDateValidator.FieldEditResult cut = new CobolDateValidator.FieldEditResult(true,
                CobolDateValidator.FieldEditFlag.NOT_OK, CobolDateValidator.FieldEditFlag.NOT_OK,
                CobolDateValidator.FieldEditFlag.NOT_OK, overLong, null);

        assertEquals(CobolDateValidator.RETURN_MESSAGE_WIDTH, cut.firstReturnMessage().length());
        assertTrue(cut.hasReturnMessage(), "a cut message was reported as absent");
        assertFalse(cut.accepted(),
                "an outcome whose input-error flag is set was accepted");

        CobolDateValidator.FieldEditResult noMessage = new CobolDateValidator.FieldEditResult(false,
                CobolDateValidator.FieldEditFlag.IS_VALID,
                CobolDateValidator.FieldEditFlag.IS_VALID,
                CobolDateValidator.FieldEditFlag.IS_VALID, null, null);

        assertEquals("", noMessage.firstReturnMessage());
        assertFalse(noMessage.hasReturnMessage(), "an empty message was reported as present");
        assertFalse(noMessage.accepted(),
                "an outcome carrying no strict-policy outcome was accepted");

        assertThrows(NullPointerException.class, () -> new CobolDateValidator.FieldEditResult(false,
                null, CobolDateValidator.FieldEditFlag.IS_VALID,
                CobolDateValidator.FieldEditFlag.IS_VALID, "", null));
    }

    /**
     * Asserts that {@link CobolDateValidator.FieldEditResult#accepted()} reads the severity of the
     * strict-policy call and the state of {@code 88 INPUT-ERROR} at
     * {@code app/cbl/COACTUPC.cbl:L173}, and reads no flag byte.
     *
     * <p>Each outcome below carries three valid flag bytes.
     * {@code app/cpy/CSUTLDPY.cpy:L324} holds an {@code EXIT} sentence and
     * {@code app/cpy/CSUTLDPY.cpy:L327} sets the group flag valid after it, so whether that
     * statement runs is a matter for the compiler. The first two outcomes are refused on the
     * severity and on the input-error state alone.
     */
    @Test
    void theAcceptedVerdictReadsTheSeverityAndTheInputErrorStateOnly() {
        CobolDateValidator.DateValidationResult severityThree = CobolDateValidator.validateDate(
                ONE_DAY_BEFORE_THE_EARLIEST_DATE, CobolDateValidator.TOLERANT_POLICY_DATE_MASK);
        CobolDateValidator.DateValidationResult severityZero = CobolDateValidator.validateDate(
                VALID_DATE_WITHOUT_SEPARATORS, CobolDateValidator.STRICT_POLICY_DATE_MASK);

        assertEquals(3, severityThree.severityNumber());
        assertEquals(CobolDateValidator.ACCEPTED_SEVERITY_NUMBER, severityZero.severityNumber());

        assertFalse(new CobolDateValidator.FieldEditResult(false,
                        CobolDateValidator.FieldEditFlag.IS_VALID,
                        CobolDateValidator.FieldEditFlag.IS_VALID,
                        CobolDateValidator.FieldEditFlag.IS_VALID, "", severityThree).accepted(),
                "three valid flag bytes outvoted severity 3");
        assertFalse(new CobolDateValidator.FieldEditResult(true,
                        CobolDateValidator.FieldEditFlag.IS_VALID,
                        CobolDateValidator.FieldEditFlag.IS_VALID,
                        CobolDateValidator.FieldEditFlag.IS_VALID, "", severityZero).accepted(),
                "three valid flag bytes outvoted the input-error state");
        assertTrue(new CobolDateValidator.FieldEditResult(false,
                        CobolDateValidator.FieldEditFlag.IS_VALID,
                        CobolDateValidator.FieldEditFlag.IS_VALID,
                        CobolDateValidator.FieldEditFlag.IS_VALID, "", severityZero).accepted(),
                "severity zero and a clear input-error state were refused");
    }

    /** Asserts the three flag characters {@code app/cpy/CSUTLDWY.cpy:L43-L57} declares. */
    @Test
    void theThreeFlagBytesHoldTheCharactersTheSourceDeclares() {
        assertEquals('\u0000', CobolDateValidator.FieldEditFlag.IS_VALID.flagCharacter());
        assertEquals('0', CobolDateValidator.FieldEditFlag.NOT_OK.flagCharacter());
        assertEquals('B', CobolDateValidator.FieldEditFlag.BLANK.flagCharacter());
        assertEquals(3, CobolDateValidator.FieldEditFlag.values().length);
    }

    // The date-of-birth reasonableness check. app/cpy/CSUTLDPY.cpy:L341-L368.

    /**
     * Asserts the check at {@code app/cpy/CSUTLDPY.cpy:L350}. A date strictly before the reference
     * date passes, and the reference date itself is a rejection carrying
     * {@value CobolDateValidator#FUTURE_DATE_MESSAGE}.
     */
    @Test
    void aDateOfBirthMustBeStrictlyBeforeTheReferenceDate() {
        CobolDateValidator.FieldEditResult past =
                CobolDateValidator.editDateOfBirth(DATE_OF_BIRTH, EDIT_VARIABLE_NAME,
                        REFERENCE_TODAY);

        assertTrue(past.accepted(),
                "a date of birth before the reference date was rejected");
        assertFalse(past.inputError(),
                "a date of birth before the reference date raised the input-error flag");
        assertEquals("", past.firstReturnMessage());

        for (String editDate : new String[] {EDIT_DATE_ON_REFERENCE_TODAY,
                EDIT_DATE_ONE_DAY_AFTER_REFERENCE_TODAY}) {
            CobolDateValidator.FieldEditResult rejected = CobolDateValidator
                    .editDateOfBirth(editDate, EDIT_VARIABLE_NAME, REFERENCE_TODAY);

            assertFalse(rejected.accepted(), editDate);
            assertTrue(rejected.inputError(), editDate);
            assertEquals(CANNOT_BE_IN_THE_FUTURE, rejected.firstReturnMessage(), editDate);
            assertEquals(CobolDateValidator.FieldEditFlag.NOT_OK, rejected.yearFlag(), editDate);
            assertEquals(CobolDateValidator.FieldEditFlag.NOT_OK, rejected.monthFlag(), editDate);
            assertEquals(CobolDateValidator.FieldEditFlag.NOT_OK, rejected.dayFlag(), editDate);
        }
    }

    /**
     * Asserts the sequence at {@code app/cbl/COACTUPC.cbl:L1536-L1543}. A date the field edits
     * reject returns the field-edit outcome unchanged, so the reasonableness check composes
     * nothing over it.
     */
    @Test
    void aDateOfBirthTheFieldEditsRejectKeepsTheFieldEditMessage() {
        CobolDateValidator.FieldEditResult result = CobolDateValidator
                .editDateOfBirth(EDIT_DATE_WITH_MONTH_THIRTEEN, EDIT_VARIABLE_NAME,
                        REFERENCE_TODAY);

        assertEquals(MONTH_OUT_OF_RANGE, result.firstReturnMessage());
        assertFalse(result.accepted(), "a month of thirteen was accepted");
        assertNull(result.dateValidation(),
                "a rejected date of birth carried a strict-policy outcome");
    }

    /**
     * Asserts that the two-argument overload agrees with the three-argument form when the three
     * argument form is handed the same reference date the overload uses. The reference date is read
     * once and passed in, so the two calls compare like for like.
     * {@code app/cpy/CSUTLDPY.cpy:L343} reads the same date through
     * {@code FUNCTION CURRENT-DATE}.
     */
    @Test
    void theTwoArgumentDateOfBirthOverloadAgreesWithTheThreeArgumentFormOnToday() {
        LocalDate today = LocalDate.now();

        CobolDateValidator.FieldEditResult overload =
                CobolDateValidator.editDateOfBirth(DATE_OF_BIRTH, EDIT_VARIABLE_NAME);
        CobolDateValidator.FieldEditResult explicit =
                CobolDateValidator.editDateOfBirth(DATE_OF_BIRTH, EDIT_VARIABLE_NAME, today);

        assertEquals(explicit.inputError(), overload.inputError());
        assertEquals(explicit.firstReturnMessage(), overload.firstReturnMessage());
        assertEquals(explicit.accepted(), overload.accepted());
        assertTrue(overload.accepted(),
                "a date of birth in 1961 stopped passing the reasonableness check");

        CobolDateValidator.FieldEditResult rejectedOverload =
                CobolDateValidator.editDateOfBirth(EDIT_DATE_WITH_MONTH_THIRTEEN,
                        EDIT_VARIABLE_NAME);

        assertEquals(MONTH_OUT_OF_RANGE, rejectedOverload.firstReturnMessage());
        assertFalse(rejectedOverload.accepted(),
                "the two-argument overload accepted a month of thirteen");
    }

    /**
     * Asserts that the three-argument overload rejects a {@code null} reference date. The argument
     * stands in for {@code FUNCTION CURRENT-DATE} at {@code app/cpy/CSUTLDPY.cpy:L343}, which
     * always yields a date.
     */
    @Test
    void theThreeArgumentDateOfBirthOverloadRejectsANullReferenceDate() {
        assertEquals("today", assertThrows(NullPointerException.class, () -> CobolDateValidator
                .editDateOfBirth(DATE_OF_BIRTH, EDIT_VARIABLE_NAME, null)).getMessage());
    }
}
