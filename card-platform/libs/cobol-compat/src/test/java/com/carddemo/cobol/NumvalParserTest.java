package com.carddemo.cobol;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserts that {@link NumvalParser} accepts and rejects what the four CardDemo COBOL numeric
 * functions accept and reject, no stricter and no looser.
 *
 * <p>{@code FUNCTION NUMVAL-C} reads a currency sign and grouping commas. {@code FUNCTION NUMVAL}
 * reads neither, and {@code new BigDecimal(String)} reads neither. The first two tests hold one
 * argument against both conversions and record that split.
 *
 * <p>Four source files supply every expected value below:
 *
 * <ul>
 *   <li>{@code app/cbl/COACTUPC.cbl} gates five account money fields at L1078, L1092, L1106,
 *       L1120 and L1134, then converts them at L1080, L1094, L1108, L1122 and L1136. L2156 tests
 *       a sliced alphanumeric field for zero, and L2201 gates a fifteen-character signed field.
 *   <li>{@code app/cbl/COTRN02C.cbl} converts an account identifier at L204 and a card number at
 *       L218 with the plain function. L383 and L456 convert one transaction amount field twice
 *       with the currency function.
 *   <li>{@code app/cbl/CORPT00C.cbl} converts six date components with the currency function, at
 *       L305, L309, L313, L317, L321 and L325.
 *   <li>{@code app/cpy/CSUTLDPY.cpy} gates a month at L126 and a day at L170, then converts each
 *       with the plain function at L128 and L172.
 * </ul>
 *
 * <p>A COBOL gate reads valid as zero, at {@code app/cbl/COACTUPC.cbl:L2201} and at
 * {@code app/cpy/CSUTLDPY.cpy:L126}. Both Java gates read valid as {@code true}, and every gate
 * test below names the verdict it expects. A gate reports and never throws;
 * {@code app/cbl/COACTUPC.cbl:L2180} raises the error later.
 *
 * <p>Two properties fall outside this class. No test asserts a scale, which {@link PicClause}
 * carries. No test asserts a validation message, which the account service holds.
 *
 * <p>Rationale lives in {@code card-platform/docs/decision-log.md}. Flagged source rules live in
 * {@code card-platform/docs/business-rule-flags.md}.
 */
class NumvalParserTest {

    /** The count of public operations {@link NumvalParser} declares, one per COBOL function. */
    private static final int PUBLIC_OPERATION_COUNT = 4;

    /** The widest digit count {@link NumvalParser#MAXIMUM_DIGITS} accepts. */
    private static final int MAXIMUM_DIGIT_COUNT = 18;

    /**
     * Byte width of {@code WS-EDIT-SIGNED-NUMBER-9V2-X PIC X(15)} at
     * {@code app/cbl/COACTUPC.cbl:L55} and of {@code ACUP-NEW-CREDIT-LIMIT-X PIC X(15)} at
     * {@code app/cbl/COACTUPC.cbl:L412}.
     */
    private static final int SIGNED_MONEY_FIELD_WIDTH = 15;

    /**
     * Byte width of {@code WS-EDIT-ALPHANUM-ONLY PIC X(256)} at
     * {@code app/cbl/COACTUPC.cbl:L61}.
     */
    private static final int ALPHANUMERIC_FIELD_WIDTH = 256;

    /**
     * Slice length {@code app/cbl/COACTUPC.cbl:L1548} moves into
     * {@code WS-EDIT-ALPHANUM-LENGTH} ahead of the zero test at L2156.
     */
    private static final int ALPHANUMERIC_SLICE_LENGTH = 3;

    /** Digit count of {@code WS-ACCT-ID-N PIC 9(11)} at {@code app/cbl/COTRN02C.cbl:L55}. */
    private static final int ACCOUNT_IDENTIFIER_WIDTH = 11;

    /** Digit count of {@code WS-CARD-NUM-N PIC 9(16)} at {@code app/cbl/COTRN02C.cbl:L56}. */
    private static final int CARD_NUMBER_WIDTH = 16;

    /** A money argument carrying no currency sign and no grouping comma. */
    private static final String PLAIN_MONEY_ARGUMENT = "1234.56";

    /** The same value carrying one currency sign. */
    private static final String MONEY_ARGUMENT_WITH_CURRENCY_SIGN = "$1234.56";

    /** The same value carrying one grouping comma. */
    private static final String MONEY_ARGUMENT_WITH_SEPARATOR = "1,234.56";

    /** The same value carrying a currency sign and a grouping comma together. */
    private static final String MONEY_ARGUMENT_WITH_SIGN_AND_SEPARATOR = "$1,234.56";

    /** The value the four money arguments above represent. */
    private static final BigDecimal MONEY_VALUE = new BigDecimal("1234.56");

    /** The negated {@link #MONEY_VALUE}. */
    private static final BigDecimal NEGATIVE_MONEY_VALUE = new BigDecimal("-1234.56");

    /**
     * A grouped argument whose digit groups run to one, then two, then three. COBOL fixes no
     * group width.
     */
    private static final String IRREGULARLY_GROUPED_ARGUMENT = "$1,00,000";

    /** The value {@link #IRREGULARLY_GROUPED_ARGUMENT} represents. */
    private static final BigDecimal IRREGULARLY_GROUPED_VALUE = new BigDecimal("100000");

    /**
     * A month for {@code WS-NUM-99 PIC 99} at {@code app/cbl/CORPT00C.cbl:L74}, converted at
     * {@code app/cbl/CORPT00C.cbl:L305}.
     */
    private static final String START_DATE_MONTH = "07";

    /** The value {@link #START_DATE_MONTH} represents. Its leading zero adds nothing. */
    private static final BigDecimal START_DATE_MONTH_VALUE = new BigDecimal("7");

    /**
     * A day at the width of {@link #START_DATE_MONTH}, converted at
     * {@code app/cbl/CORPT00C.cbl:L309}.
     */
    private static final String START_DATE_DAY = "31";

    /** The value {@link #START_DATE_DAY} represents. */
    private static final BigDecimal START_DATE_DAY_VALUE = new BigDecimal("31");

    /**
     * A year for {@code WS-NUM-9999 PIC 9999} at {@code app/cbl/CORPT00C.cbl:L75}, converted at
     * {@code app/cbl/CORPT00C.cbl:L313}.
     */
    private static final String START_DATE_YEAR = "2024";

    /** The value {@link #START_DATE_YEAR} represents. */
    private static final BigDecimal START_DATE_YEAR_VALUE = new BigDecimal("2024");

    /**
     * An account identifier at {@link #ACCOUNT_IDENTIFIER_WIDTH}, converted at
     * {@code app/cbl/COTRN02C.cbl:L204}.
     */
    private static final String ACCOUNT_IDENTIFIER = "00000000011";

    /** The value {@link #ACCOUNT_IDENTIFIER} represents. */
    private static final BigDecimal ACCOUNT_IDENTIFIER_VALUE = new BigDecimal("11");

    /**
     * A card number at {@link #CARD_NUMBER_WIDTH}, converted at
     * {@code app/cbl/COTRN02C.cbl:L218}.
     */
    private static final String CARD_NUMBER = "4111222233334444";

    /** The value {@link #CARD_NUMBER} represents. */
    private static final BigDecimal CARD_NUMBER_VALUE = new BigDecimal("4111222233334444");

    /**
     * An alphanumeric field holding three zero digits, padded to
     * {@link #ALPHANUMERIC_FIELD_WIDTH}. Its slice is the argument
     * {@code app/cbl/COACTUPC.cbl:L2156} tests for zero.
     */
    private static final String ALL_ZERO_ALPHANUMERIC_FIELD =
            "000" + " ".repeat(ALPHANUMERIC_FIELD_WIDTH - ALPHANUMERIC_SLICE_LENGTH);

    /** An alphanumeric field holding {@code 012}, padded to the same width. */
    private static final String NON_ZERO_ALPHANUMERIC_FIELD =
            "012" + " ".repeat(ALPHANUMERIC_FIELD_WIDTH - ALPHANUMERIC_SLICE_LENGTH);

    /** The value the slice of {@link #NON_ZERO_ALPHANUMERIC_FIELD} represents. */
    private static final BigDecimal NON_ZERO_SLICE_VALUE = new BigDecimal("12");

    /**
     * The signed money field filled with {@code SPACES}, which
     * {@code app/cbl/COACTUPC.cbl:L1074} screens ahead of the gate.
     */
    private static final String SPACES_ARGUMENT = " ".repeat(SIGNED_MONEY_FIELD_WIDTH);

    /**
     * The signed money field filled with {@code LOW-VALUES}, which
     * {@code app/cbl/COACTUPC.cbl:L1075} moves in and {@code app/cbl/COACTUPC.cbl:L2184} reads as
     * not supplied.
     */
    private static final String LOW_VALUES_ARGUMENT = "\u0000".repeat(SIGNED_MONEY_FIELD_WIDTH);

    /** The sentinel {@code app/cbl/COACTUPC.cbl:L1073} screens ahead of the gate. */
    private static final String SENTINEL_ARGUMENT = "*";

    /** A money argument carrying a second decimal point. */
    private static final String TWO_DECIMAL_POINT_ARGUMENT = "12.34.56";

    /** A money argument carrying a letter among its digits. */
    private static final String LETTER_ARGUMENT = "1234.5A";

    /** A month carrying a letter, the argument {@code app/cpy/CSUTLDPY.cpy:L126} rejects. */
    private static final String MONTH_WITH_A_LETTER = "1A";

    /** A grouped argument ending on its separator. */
    private static final String TRAILING_SEPARATOR_ARGUMENT = "1,234,";

    /** A currency sign carrying no digits. */
    private static final String CURRENCY_SIGN_ALONE = "$";

    /** Digits at {@link #MAXIMUM_DIGIT_COUNT}. */
    private static final String EIGHTEEN_DIGIT_ARGUMENT = "123456789012345678";

    /** Digits one above {@link #MAXIMUM_DIGIT_COUNT}. */
    private static final String NINETEEN_DIGIT_ARGUMENT = "1234567890123456789";

    /**
     * The four negative forms both conversions read: a leading sign, a trailing sign, a trailing
     * credit marker and a trailing debit marker. Each represents {@link #NEGATIVE_MONEY_VALUE}.
     */
    private static final String[] NEGATIVE_MONEY_ARGUMENTS = {
            "-1234.56", "1234.56-", "1234.56CR", "1234.56DB",
    };

    /**
     * Proves the currency-tolerant conversion reads a currency sign and the plain conversion
     * refuses one. {@code app/cbl/COACTUPC.cbl:L1080} through
     * {@code app/cbl/COACTUPC.cbl:L1136} send five account money fields through the tolerant
     * conversion.
     */
    @Test
    void currencySignIsAcceptedByNumvalCurrencyAndRejectedByNumval() {
        assertEquals('$', NumvalParser.CURRENCY_SIGN);
        assertTrue(MONEY_ARGUMENT_WITH_CURRENCY_SIGN.indexOf(NumvalParser.CURRENCY_SIGN) >= 0,
                "the argument carries no currency sign: " + MONEY_ARGUMENT_WITH_CURRENCY_SIGN);

        assertTrue(NumvalParser.isValidNumvalCurrency(MONEY_ARGUMENT_WITH_CURRENCY_SIGN),
                "the currency gate rejected a currency sign");
        assertEquals(0, NumvalParser.numvalCurrency(MONEY_ARGUMENT_WITH_CURRENCY_SIGN)
                        .compareTo(MONEY_VALUE),
                "the currency conversion returned another value");

        assertFalse(NumvalParser.isValidNumval(MONEY_ARGUMENT_WITH_CURRENCY_SIGN),
                "the plain gate accepted a currency sign");
        assertThrows(NumberFormatException.class,
                () -> NumvalParser.numval(MONEY_ARGUMENT_WITH_CURRENCY_SIGN),
                "the plain conversion accepted a currency sign");

        assertTrue(NumvalParser.isValidNumval(PLAIN_MONEY_ARGUMENT));
        assertEquals(0, NumvalParser.numval(PLAIN_MONEY_ARGUMENT).compareTo(MONEY_VALUE));
    }

    /**
     * Proves the currency-tolerant conversion reads a grouping comma and the plain conversion
     * refuses one. One grammar covers all thirteen tolerant call sites, among them
     * {@code app/cbl/COACTUPC.cbl:L1080} and {@code app/cbl/COTRN02C.cbl:L383}. Groups of an
     * uneven width convert to the same value, since COBOL fixes no group width.
     */
    @Test
    void thousandsSeparatorIsAcceptedByNumvalCurrencyAndRejectedByNumval() {
        assertEquals(',', NumvalParser.DIGIT_SEPARATOR);
        assertEquals('.', NumvalParser.DECIMAL_POINT);

        assertTrue(NumvalParser.isValidNumvalCurrency(MONEY_ARGUMENT_WITH_SEPARATOR),
                "the currency gate rejected a grouping comma");
        assertEquals(0, NumvalParser.numvalCurrency(MONEY_ARGUMENT_WITH_SEPARATOR)
                        .compareTo(MONEY_VALUE),
                "a grouped argument converted to another value");
        assertEquals(0, NumvalParser.numvalCurrency(MONEY_ARGUMENT_WITH_SIGN_AND_SEPARATOR)
                        .compareTo(MONEY_VALUE),
                "a grouped argument with a currency sign converted to another value");
        assertEquals(0, NumvalParser.numvalCurrency(IRREGULARLY_GROUPED_ARGUMENT)
                        .compareTo(IRREGULARLY_GROUPED_VALUE),
                "an unevenly grouped argument converted to another value");

        assertFalse(NumvalParser.isValidNumval(MONEY_ARGUMENT_WITH_SEPARATOR),
                "the plain gate accepted a grouping comma");
        assertThrows(NumberFormatException.class,
                () -> NumvalParser.numval(MONEY_ARGUMENT_WITH_SEPARATOR),
                "the plain conversion accepted a grouping comma");
        assertThrows(NumberFormatException.class,
                () -> NumvalParser.numval(MONEY_ARGUMENT_WITH_SIGN_AND_SEPARATOR));
    }

    /**
     * Proves the currency-tolerant conversion reads a bare two-digit and a bare four-digit
     * argument. {@code app/cbl/CORPT00C.cbl:L305} through {@code app/cbl/CORPT00C.cbl:L325} send
     * six date components through it, a month and a day into {@code WS-NUM-99 PIC 99} and a year
     * into {@code WS-NUM-9999 PIC 9999}. None of the six carries a currency sign or a comma.
     */
    @Test
    void numvalCurrencyConvertsBareTwoDigitAndFourDigitArguments() {
        assertTrue(NumvalParser.isValidNumvalCurrency(START_DATE_MONTH),
                "the currency gate rejected a two-digit month");
        assertTrue(NumvalParser.isValidNumvalCurrency(START_DATE_DAY),
                "the currency gate rejected a two-digit day");
        assertTrue(NumvalParser.isValidNumvalCurrency(START_DATE_YEAR),
                "the currency gate rejected a four-digit year");

        assertEquals(0, NumvalParser.numvalCurrency(START_DATE_MONTH)
                        .compareTo(START_DATE_MONTH_VALUE),
                "the month converted to another value");
        assertEquals(0, NumvalParser.numvalCurrency(START_DATE_DAY)
                        .compareTo(START_DATE_DAY_VALUE),
                "the day converted to another value");
        assertEquals(0, NumvalParser.numvalCurrency(START_DATE_YEAR)
                        .compareTo(START_DATE_YEAR_VALUE),
                "the year converted to another value");
    }

    /**
     * Proves two conversions of one argument return one value and leave no state behind.
     * {@code app/cbl/COTRN02C.cbl:L383} converts {@code TRNAMTI} for the screen echo, and
     * {@code app/cbl/COTRN02C.cbl:L456} converts the same field again for the stored transaction
     * amount. A gate call between the two conversions changes neither result.
     */
    @Test
    void numvalCurrencyReturnsOneValueWhenTheSameArgumentIsParsedTwice() {
        BigDecimal firstPass = NumvalParser.numvalCurrency(MONEY_ARGUMENT_WITH_SIGN_AND_SEPARATOR);
        BigDecimal secondPass = NumvalParser.numvalCurrency(MONEY_ARGUMENT_WITH_SIGN_AND_SEPARATOR);

        assertEquals(firstPass, secondPass, "a repeat conversion returned another value");
        assertEquals(0, firstPass.compareTo(MONEY_VALUE));
        assertEquals(0, secondPass.compareTo(MONEY_VALUE));

        assertTrue(NumvalParser.isValidNumvalCurrency(MONEY_ARGUMENT_WITH_SIGN_AND_SEPARATOR));
        assertTrue(NumvalParser.isValidNumvalCurrency(MONEY_ARGUMENT_WITH_SIGN_AND_SEPARATOR));

        assertEquals(firstPass,
                NumvalParser.numvalCurrency(MONEY_ARGUMENT_WITH_SIGN_AND_SEPARATOR),
                "a conversion after two gate calls returned another value");
    }

    /**
     * Proves the plain conversion reads the two identifier fields
     * {@code app/cbl/COTRN02C.cbl} converts. L204 fills {@code WS-ACCT-ID-N PIC 9(11)} and L218
     * fills {@code WS-CARD-NUM-N PIC 9(16)}. Leading zeros carry no weight in the value.
     */
    @Test
    void numvalConvertsAnElevenDigitAccountIdentifierAndASixteenDigitCardNumber() {
        assertEquals(ACCOUNT_IDENTIFIER_WIDTH, ACCOUNT_IDENTIFIER.length());
        assertEquals(CARD_NUMBER_WIDTH, CARD_NUMBER.length());

        assertTrue(NumvalParser.isValidNumval(ACCOUNT_IDENTIFIER),
                "the plain gate rejected an account identifier");
        assertTrue(NumvalParser.isValidNumval(CARD_NUMBER),
                "the plain gate rejected a card number");

        assertEquals(0, NumvalParser.numval(ACCOUNT_IDENTIFIER)
                        .compareTo(ACCOUNT_IDENTIFIER_VALUE),
                "the account identifier converted to another value");
        assertEquals(0, NumvalParser.numval(CARD_NUMBER).compareTo(CARD_NUMBER_VALUE),
                "the card number converted to another value");
    }

    /**
     * Proves the plain conversion returns zero for a slice of zero digits and returns the value of
     * a slice that holds a digit above zero. {@code app/cbl/COACTUPC.cbl:L2156} tests
     * {@code FUNCTION NUMVAL(WS-EDIT-ALPHANUM-ONLY(1: WS-EDIT-ALPHANUM-LENGTH)) = 0} against a
     * slice, never against the whole field. The numeric class test at
     * {@code app/cbl/COACTUPC.cbl:L2137} runs ahead of that zero test.
     */
    @Test
    void numvalReturnsZeroForAnAllZeroSliceOfAnAlphanumericField() {
        assertEquals(ALPHANUMERIC_FIELD_WIDTH, ALL_ZERO_ALPHANUMERIC_FIELD.length());
        assertEquals(ALPHANUMERIC_FIELD_WIDTH, NON_ZERO_ALPHANUMERIC_FIELD.length());

        String zeroSlice = ALL_ZERO_ALPHANUMERIC_FIELD.substring(0, ALPHANUMERIC_SLICE_LENGTH);
        String nonZeroSlice = NON_ZERO_ALPHANUMERIC_FIELD.substring(0, ALPHANUMERIC_SLICE_LENGTH);

        assertEquals(ALPHANUMERIC_SLICE_LENGTH, zeroSlice.length());
        assertEquals(ALPHANUMERIC_SLICE_LENGTH, nonZeroSlice.length());

        assertEquals(0, NumvalParser.numval(zeroSlice).signum(),
                "a slice of zero digits converted to a value away from zero");
        assertEquals(1, NumvalParser.numval(nonZeroSlice).signum(),
                "a slice holding a digit above zero converted to zero");
        assertEquals(0, NumvalParser.numval(nonZeroSlice).compareTo(NON_ZERO_SLICE_VALUE),
                "the slice converted to another value");
    }

    /**
     * Proves the plain gate reports valid for a two-digit month and reports invalid for a month
     * carrying a letter. {@code app/cpy/CSUTLDPY.cpy:L126} reads valid as
     * {@code FUNCTION TEST-NUMVAL (WS-EDIT-DATE-MM) = 0} and converts at L128. The day at L170
     * and L172 follows the same pair.
     */
    @Test
    void isValidNumvalReportsValidForATwoDigitMonthAndInvalidForALetter() {
        assertTrue(NumvalParser.isValidNumval(START_DATE_MONTH),
                "the plain gate rejected a two-digit month");
        assertTrue(NumvalParser.isValidNumval(START_DATE_DAY),
                "the plain gate rejected a two-digit day");

        assertFalse(NumvalParser.isValidNumval(MONTH_WITH_A_LETTER),
                "the plain gate accepted a month carrying a letter");
        assertFalse(NumvalParser.isValidNumval(TWO_DECIMAL_POINT_ARGUMENT),
                "the plain gate accepted a second decimal point");

        assertEquals(0, NumvalParser.numval(START_DATE_MONTH).compareTo(START_DATE_MONTH_VALUE));
        assertEquals(0, NumvalParser.numval(START_DATE_DAY).compareTo(START_DATE_DAY_VALUE));
    }

    /**
     * Proves the currency-tolerant gate reports valid for a money argument and reports invalid for
     * a malformed one. {@code app/cbl/COACTUPC.cbl:L2201} reads valid as
     * {@code FUNCTION TEST-NUMVAL-C(WS-EDIT-SIGNED-NUMBER-9V2-X) = 0} and answers with
     * {@code CONTINUE} at L2202.
     */
    @Test
    void isValidNumvalCurrencyReportsValidForAMoneyArgumentAndInvalidForAMalformedOne() {
        assertTrue(NumvalParser.isValidNumvalCurrency(MONEY_ARGUMENT_WITH_SIGN_AND_SEPARATOR),
                "the currency gate rejected a money argument");
        assertTrue(NumvalParser.isValidNumvalCurrency(PLAIN_MONEY_ARGUMENT),
                "the currency gate rejected an undecorated money argument");

        assertFalse(NumvalParser.isValidNumvalCurrency(TWO_DECIMAL_POINT_ARGUMENT),
                "the currency gate accepted a second decimal point");
        assertFalse(NumvalParser.isValidNumvalCurrency(LETTER_ARGUMENT),
                "the currency gate accepted a letter among the digits");
        assertFalse(NumvalParser.isValidNumvalCurrency(TRAILING_SEPARATOR_ARGUMENT),
                "the currency gate accepted a trailing grouping comma");
        assertFalse(NumvalParser.isValidNumvalCurrency(CURRENCY_SIGN_ALONE),
                "the currency gate accepted a currency sign carrying no digits");
        assertFalse(NumvalParser.isValidNumvalCurrency(SENTINEL_ARGUMENT),
                "the currency gate accepted the screen sentinel");
    }

    /**
     * Proves both gates report invalid for {@code null} and for an empty argument, and that
     * neither conversion turns either one into zero. {@code app/cbl/COACTUPC.cbl:L2181} enters
     * {@code 1250-EDIT-SIGNED-9V2} with {@code FLG-SIGNED-NUMBER-NOT-OK} already set.
     */
    @Test
    void bothGatesReportInvalidForNullAndForAnEmptyArgument() {
        assertFalse(NumvalParser.isValidNumval(null), "the plain gate accepted null");
        assertFalse(NumvalParser.isValidNumvalCurrency(null),
                "the currency gate accepted null");
        assertFalse(NumvalParser.isValidNumval(""), "the plain gate accepted an empty argument");
        assertFalse(NumvalParser.isValidNumvalCurrency(""),
                "the currency gate accepted an empty argument");

        assertThrows(NumberFormatException.class, () -> NumvalParser.numval(null),
                "the plain conversion turned null into a value");
        assertThrows(NumberFormatException.class, () -> NumvalParser.numvalCurrency(null),
                "the currency conversion turned null into a value");
        assertThrows(NumberFormatException.class, () -> NumvalParser.numval(""),
                "the plain conversion turned an empty argument into a value");
        assertThrows(NumberFormatException.class, () -> NumvalParser.numvalCurrency(""),
                "the currency conversion turned an empty argument into a value");
    }

    /**
     * Proves both gates report invalid for a field of spaces and for a field of
     * {@code LOW-VALUES}. {@code app/cbl/COACTUPC.cbl:L1074} screens {@code SPACES} and L1075
     * moves {@code LOW-VALUES} into the target field. {@code app/cbl/COACTUPC.cbl:L2184} reads
     * both as not supplied.
     */
    @Test
    void bothGatesReportInvalidForSpacesAndForLowValues() {
        assertEquals(SIGNED_MONEY_FIELD_WIDTH, SPACES_ARGUMENT.length());
        assertEquals(SIGNED_MONEY_FIELD_WIDTH, LOW_VALUES_ARGUMENT.length());

        assertFalse(NumvalParser.isValidNumval(SPACES_ARGUMENT),
                "the plain gate accepted a field of spaces");
        assertFalse(NumvalParser.isValidNumvalCurrency(SPACES_ARGUMENT),
                "the currency gate accepted a field of spaces");
        assertFalse(NumvalParser.isValidNumval(LOW_VALUES_ARGUMENT),
                "the plain gate accepted a field of low values");
        assertFalse(NumvalParser.isValidNumvalCurrency(LOW_VALUES_ARGUMENT),
                "the currency gate accepted a field of low values");

        assertThrows(NumberFormatException.class,
                () -> NumvalParser.numvalCurrency(SPACES_ARGUMENT),
                "the currency conversion turned a field of spaces into a value");
        assertThrows(NumberFormatException.class,
                () -> NumvalParser.numvalCurrency(LOW_VALUES_ARGUMENT),
                "the currency conversion turned a field of low values into a value");
    }

    /**
     * Proves a gate reports an invalid argument and throws nothing. All five pairs in
     * {@code app/cbl/COACTUPC.cbl} answer a rejected argument with {@code CONTINUE}, at L1082,
     * L1096, L1110, L1124 and L1138.
     */
    @Test
    void neitherGateThrowsForAnArgumentItReportsInvalid() {
        String[] invalidArguments = {
                null,
                "",
                SPACES_ARGUMENT,
                LOW_VALUES_ARGUMENT,
                SENTINEL_ARGUMENT,
                TWO_DECIMAL_POINT_ARGUMENT,
                LETTER_ARGUMENT,
                MONTH_WITH_A_LETTER,
                TRAILING_SEPARATOR_ARGUMENT,
                CURRENCY_SIGN_ALONE,
                NINETEEN_DIGIT_ARGUMENT,
        };

        for (String argument : invalidArguments) {
            boolean plainVerdict = assertDoesNotThrow(
                    () -> NumvalParser.isValidNumval(argument),
                    "the plain gate threw for an argument it reports invalid");
            boolean currencyVerdict = assertDoesNotThrow(
                    () -> NumvalParser.isValidNumvalCurrency(argument),
                    "the currency gate threw for an argument it reports invalid");

            assertFalse(plainVerdict, "the plain gate accepted an invalid argument");
            assertFalse(currencyVerdict, "the currency gate accepted an invalid argument");
        }
    }

    /**
     * Proves both conversions read every negative marker as a negative value. Two source fields
     * hold a sign: {@code WS-TRAN-AMT-N PIC S9(9)V99} at {@code app/cbl/COTRN02C.cbl:L58}, and
     * the argument of {@code 1250-EDIT-SIGNED-9V2} at {@code app/cbl/COACTUPC.cbl:L2180}.
     */
    @Test
    void bothConversionsReadEveryNegativeMarkerAsANegativeValue() {
        for (String argument : NEGATIVE_MONEY_ARGUMENTS) {
            assertTrue(NumvalParser.isValidNumval(argument),
                    "the plain gate rejected a negative form: " + argument);
            assertTrue(NumvalParser.isValidNumvalCurrency(argument),
                    "the currency gate rejected a negative form: " + argument);

            assertEquals(0, NumvalParser.numval(argument).compareTo(NEGATIVE_MONEY_VALUE),
                    "the plain conversion returned another value for: " + argument);
            assertEquals(0,
                    NumvalParser.numvalCurrency(argument).compareTo(NEGATIVE_MONEY_VALUE),
                    "the currency conversion returned another value for: " + argument);
        }

        assertEquals(0, NumvalParser.numvalCurrency("-" + MONEY_ARGUMENT_WITH_SIGN_AND_SEPARATOR)
                        .compareTo(NEGATIVE_MONEY_VALUE),
                "a leading sign ahead of a currency sign returned another value");
    }

    /**
     * Proves both gates accept an argument of eighteen digits and reject one of nineteen. The
     * widest field any call site converts is {@code WS-CARD-NUM-N PIC 9(16)} at
     * {@code app/cbl/COTRN02C.cbl:L56}, which sits below eighteen digits.
     */
    @Test
    void bothGatesAcceptEighteenDigitsAndRejectNineteen() {
        assertEquals(MAXIMUM_DIGIT_COUNT, NumvalParser.MAXIMUM_DIGITS);
        assertEquals(MAXIMUM_DIGIT_COUNT, EIGHTEEN_DIGIT_ARGUMENT.length());
        assertEquals(MAXIMUM_DIGIT_COUNT + 1, NINETEEN_DIGIT_ARGUMENT.length());
        assertTrue(CARD_NUMBER_WIDTH <= NumvalParser.MAXIMUM_DIGITS,
                "a card number no longer fits inside the widest digit count");

        assertTrue(NumvalParser.isValidNumval(EIGHTEEN_DIGIT_ARGUMENT),
                "the plain gate rejected an argument of eighteen digits");
        assertTrue(NumvalParser.isValidNumvalCurrency(EIGHTEEN_DIGIT_ARGUMENT),
                "the currency gate rejected an argument of eighteen digits");

        assertFalse(NumvalParser.isValidNumval(NINETEEN_DIGIT_ARGUMENT),
                "the plain gate accepted an argument of nineteen digits");
        assertFalse(NumvalParser.isValidNumvalCurrency(NINETEEN_DIGIT_ARGUMENT),
                "the currency gate accepted an argument of nineteen digits");

        assertThrows(NumberFormatException.class,
                () -> NumvalParser.numval(NINETEEN_DIGIT_ARGUMENT));
        assertThrows(NumberFormatException.class,
                () -> NumvalParser.numvalCurrency(NINETEEN_DIGIT_ARGUMENT));
    }

    /**
     * Proves {@link NumvalParser} declares four public operations and no public constructor. The
     * four cover {@code FUNCTION NUMVAL} at {@code app/cbl/COTRN02C.cbl:L204},
     * {@code FUNCTION NUMVAL-C} at {@code app/cbl/COTRN02C.cbl:L383},
     * {@code FUNCTION TEST-NUMVAL} at {@code app/cpy/CSUTLDPY.cpy:L126}, and
     * {@code FUNCTION TEST-NUMVAL-C} at {@code app/cbl/COACTUPC.cbl:L2201}. Each reads one text
     * argument and holds no state.
     */
    @Test
    void numvalParserDeclaresFourPublicOperationsAndNoPublicConstructor() {
        int publicOperations = 0;

        for (Method method : NumvalParser.class.getDeclaredMethods()) {
            if (method.isSynthetic() || !Modifier.isPublic(method.getModifiers())) {
                continue;
            }

            publicOperations++;

            assertTrue(Modifier.isStatic(method.getModifiers()),
                    "a public operation is not static: " + method.getName());
            assertEquals(1, method.getParameterCount(),
                    "a public operation reads a second argument: " + method.getName());
            assertEquals(String.class, method.getParameterTypes()[0],
                    "a public operation reads something other than text: " + method.getName());
        }

        assertEquals(PUBLIC_OPERATION_COUNT, publicOperations,
                "the count of public operations changed");

        for (Constructor<?> constructor : NumvalParser.class.getDeclaredConstructors()) {
            assertFalse(Modifier.isPublic(constructor.getModifiers()),
                    "NumvalParser declares a public constructor");
        }
    }
}
