package com.carddemo.cobol;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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
 *       L1120 and L1134. It converts them at L1080, L1094, L1108, L1122 and L1136. L2156 tests a
 *       sliced alphanumeric field for zero, and L2201 gates a fifteen-character signed field.
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

 */
class NumvalParserTest {

    /** The count of public operations {@link NumvalParser} declares, one per COBOL function. */
    private static final int PUBLIC_OPERATION_COUNT = 4;

    // The source census. The counts below are measured, and the two tests that read them scan
    // app/cbl and app/cpy rather than trusting this list.

    /** Directory under the repository root that holds the COBOL tree. */
    private static final String SOURCE_MARKER_DIRECTORY = "app";

    /** Directory under {@link #SOURCE_MARKER_DIRECTORY} that holds the programs. */
    private static final String COBOL_DIRECTORY = "cbl";

    /** Directory under {@link #SOURCE_MARKER_DIRECTORY} that holds the copybooks. */
    private static final String COPYBOOK_DIRECTORY = "cpy";

    /** Zero-based index of the COBOL indicator area, which is column 7. */
    private static final int COMMENT_COLUMN = 6;

    /** The keyword that opens every intrinsic function reference. */
    private static final String COBOL_FUNCTION_KEYWORD = "FUNCTION";

    /**
     * Matches one intrinsic function reference. The alternation names the longest form first, so
     * {@code FUNCTION TEST-NUMVAL-C} never counts as a shorter name. The trailing look-ahead keeps
     * {@code FUNCTION NUMVAL} from matching inside {@code FUNCTION NUMVAL-C}.
     */
    private static final Pattern COBOL_FUNCTION_CALL = Pattern.compile(
            COBOL_FUNCTION_KEYWORD + "\\s+(TEST-NUMVAL-C|TEST-NUMVAL|NUMVAL-C|NUMVAL)(?![-\\w])");

    /** The name {@link NumvalParser#numval(String)} reproduces. */
    private static final String NUMVAL_FUNCTION = "FUNCTION NUMVAL";

    /** The name {@link NumvalParser#numvalCurrency(String)} reproduces. */
    private static final String NUMVAL_CURRENCY_FUNCTION = "FUNCTION NUMVAL-C";

    /** The name {@link NumvalParser#isValidNumval(String)} reproduces. */
    private static final String TEST_NUMVAL_FUNCTION = "FUNCTION TEST-NUMVAL";

    /** The name {@link NumvalParser#isValidNumvalCurrency(String)} reproduces. */
    private static final String TEST_NUMVAL_CURRENCY_FUNCTION = "FUNCTION TEST-NUMVAL-C";

    /**
     * Call sites of {@code FUNCTION NUMVAL}: one in {@code app/cbl/COACTUPC.cbl}, two in
     * {@code app/cbl/COTRN02C.cbl} and two in {@code app/cpy/CSUTLDPY.cpy}.
     */
    private static final int EXPECTED_NUMVAL_CALL_SITES = 5;

    /**
     * Call sites of {@code FUNCTION NUMVAL-C}: five in {@code app/cbl/COACTUPC.cbl}, six in
     * {@code app/cbl/CORPT00C.cbl} and two in {@code app/cbl/COTRN02C.cbl}.
     */
    private static final int EXPECTED_NUMVAL_CURRENCY_CALL_SITES = 13;

    /** Call sites of {@code FUNCTION TEST-NUMVAL}, both in {@code app/cpy/CSUTLDPY.cpy}. */
    private static final int EXPECTED_TEST_NUMVAL_CALL_SITES = 2;

    /** Call sites of {@code FUNCTION TEST-NUMVAL-C}, all six in {@code app/cbl/COACTUPC.cbl}. */
    private static final int EXPECTED_TEST_NUMVAL_CURRENCY_CALL_SITES = 6;

    /** The four counts above, added. */
    private static final int EXPECTED_CALL_SITE_TOTAL = EXPECTED_NUMVAL_CALL_SITES
            + EXPECTED_NUMVAL_CURRENCY_CALL_SITES
            + EXPECTED_TEST_NUMVAL_CALL_SITES
            + EXPECTED_TEST_NUMVAL_CURRENCY_CALL_SITES;

    /** The closing sentence of every rejection that names a length rather than a value. */
    private static final String EXPECTED_LENGTH_SENTENCE_SUFFIX = " characters.";

    /** The widest digit count {@link NumvalParser#MAXIMUM_DIGITS} accepts. */
    private static final int MAXIMUM_DIGIT_COUNT = 18;

    /**
     * Byte width of {@code WS-EDIT-SIGNED-NUMBER-9V2-X PIC X(15)} at
     * {@code app/cbl/COACTUPC.cbl:L55} and of {@code ACUP-NEW-CREDIT-LIMIT-X PIC X(15)} at
     * {@code app/cbl/COACTUPC.cbl:L412}.
     */
    private static final int SIGNED_MONEY_FIELD_WIDTH = 15;

    /**
     * Byte width of {@code WS-EDIT-ALPHANUM-ONLY PIC X(256)} at {@code app/cbl/COACTUPC.cbl:L61}.
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
     * A synthetic token at {@link #CARD_NUMBER_WIDTH}, the width of the field converted at
     * {@code app/cbl/COTRN02C.cbl:L218}. Twelve zeros and four trailing digits, and no card
     * number begins with a zero.
     */
    private static final String CARD_NUMBER_FIELD = "0".repeat(12) + "4444";

    /** The value {@link #CARD_NUMBER_FIELD} represents, since leading zeros carry no weight. */
    private static final BigDecimal CARD_NUMBER_FIELD_VALUE = new BigDecimal("4444");

    /**
     * An alphanumeric field holding three zero digits, padded to {@link #ALPHANUMERIC_FIELD_WIDTH}.
     * Its slice is the argument {@code app/cbl/COACTUPC.cbl:L2156} tests for zero.
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
     * refuses one. {@code app/cbl/COACTUPC.cbl:L1080} through {@code app/cbl/COACTUPC.cbl:L1136}
     * send five account money fields through the tolerant conversion.
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

        assertTrue(NumvalParser.isValidNumval(PLAIN_MONEY_ARGUMENT),
                "the plain gate rejected an argument carrying no currency sign");
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

        assertTrue(NumvalParser.isValidNumvalCurrency(MONEY_ARGUMENT_WITH_SIGN_AND_SEPARATOR),
                "the first gate call refused a signed argument carrying a grouping comma");
        assertTrue(NumvalParser.isValidNumvalCurrency(MONEY_ARGUMENT_WITH_SIGN_AND_SEPARATOR),
                "the second gate call refused a signed argument carrying a grouping comma");

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
        assertEquals(CARD_NUMBER_WIDTH, CARD_NUMBER_FIELD.length());

        assertTrue(NumvalParser.isValidNumval(ACCOUNT_IDENTIFIER),
                "the plain gate rejected an account identifier");
        assertTrue(NumvalParser.isValidNumval(CARD_NUMBER_FIELD),
                "the plain gate rejected a sixteen-character card-number field");

        assertEquals(0, NumvalParser.numval(ACCOUNT_IDENTIFIER)
                        .compareTo(ACCOUNT_IDENTIFIER_VALUE),
                "the account identifier converted to another value");
        assertEquals(0, NumvalParser.numval(CARD_NUMBER_FIELD).compareTo(CARD_NUMBER_FIELD_VALUE),
                "the card-number field converted to another value");
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
     * a malformed one. {@code app/cbl/COACTUPC.cbl:L2201} reads valid as {@code FUNCTION
     * TEST-NUMVAL-C(WS-EDIT-SIGNED-NUMBER-9V2-X) = 0} and answers with {@code CONTINUE} at L2202.
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
     * Proves both gates report invalid for a field of spaces and for a field of {@code LOW-VALUES}.
     * {@code app/cbl/COACTUPC.cbl:L1074} screens {@code SPACES} and L1075 moves {@code LOW-VALUES}
     * into the target field. {@code app/cbl/COACTUPC.cbl:L2184} reads both as not supplied.
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
                "a card-number field no longer fits inside the widest digit count");

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
     * Proves both gates and both conversions stop at {@link NumvalParser#MAXIMUM_ARGUMENT_LENGTH}
     * characters, and that an argument at exactly that length still converts.
     *
     * <p>The ceiling is the width of the widest field a call site passes,
     * {@code WS-EDIT-ALPHANUM-ONLY PIC X(256)} at {@code app/cbl/COACTUPC.cbl:L61}. Every other
     * call site passes a narrower field, so no argument a COBOL program could build reaches the
     * ceiling and no accepted value changes.
     */
    @Test
    void bothGatesStopAtTheWidestFieldWidthAnyCallSitePasses() {
        assertEquals(256, NumvalParser.MAXIMUM_ARGUMENT_LENGTH,
                "the ceiling no longer matches WS-EDIT-ALPHANUM-ONLY PIC X(256)");

        String atCeiling = " ".repeat(NumvalParser.MAXIMUM_ARGUMENT_LENGTH - 2) + "42";
        String pastCeiling = " ".repeat(NumvalParser.MAXIMUM_ARGUMENT_LENGTH - 1) + "42";

        assertEquals(NumvalParser.MAXIMUM_ARGUMENT_LENGTH, atCeiling.length(),
                "the argument at the ceiling is not the ceiling width");
        assertEquals(NumvalParser.MAXIMUM_ARGUMENT_LENGTH + 1, pastCeiling.length(),
                "the argument past the ceiling is not one character wider");

        assertTrue(NumvalParser.isValidNumval(atCeiling),
                "the plain gate rejected an argument at the ceiling width");
        assertTrue(NumvalParser.isValidNumvalCurrency(atCeiling),
                "the currency gate rejected an argument at the ceiling width");
        assertEquals(new BigDecimal("42"), NumvalParser.numval(atCeiling));
        assertEquals(new BigDecimal("42"), NumvalParser.numvalCurrency(atCeiling));

        assertFalse(NumvalParser.isValidNumval(pastCeiling),
                "the plain gate accepted an argument past the ceiling width");
        assertFalse(NumvalParser.isValidNumvalCurrency(pastCeiling),
                "the currency gate accepted an argument past the ceiling width");
        assertThrows(NumberFormatException.class, () -> NumvalParser.numval(pastCeiling));
        assertThrows(NumberFormatException.class, () -> NumvalParser.numvalCurrency(pastCeiling));
    }

    /**
     * Proves {@link NumvalParser} declares four public operations and no public constructor. The
     * four cover {@code FUNCTION NUMVAL} at {@code app/cbl/COTRN02C.cbl:L204}, {@code FUNCTION
     * NUMVAL-C} at {@code app/cbl/COTRN02C.cbl:L383}, {@code FUNCTION TEST-NUMVAL} at
     * {@code app/cpy/CSUTLDPY.cpy:L126}, and {@code FUNCTION TEST-NUMVAL-C} at
     * {@code app/cbl/COACTUPC.cbl:L2201}. Each reads one text argument and holds no state.
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

    // The source census, read from app/cbl and app/cpy rather than narrated.

    @Test
    void everyCobolNumericFunctionCallSiteIsCounted() {
        Map<String, Integer> tally = countCobolFunctionCallSites();

        assertEquals(EXPECTED_NUMVAL_CALL_SITES, tally.getOrDefault(NUMVAL_FUNCTION, 0),
                "the FUNCTION NUMVAL call-site count changed");
        assertEquals(EXPECTED_NUMVAL_CURRENCY_CALL_SITES,
                tally.getOrDefault(NUMVAL_CURRENCY_FUNCTION, 0),
                "the FUNCTION NUMVAL-C call-site count changed");
        assertEquals(EXPECTED_TEST_NUMVAL_CALL_SITES, tally.getOrDefault(TEST_NUMVAL_FUNCTION, 0),
                "the FUNCTION TEST-NUMVAL call-site count changed");
        assertEquals(EXPECTED_TEST_NUMVAL_CURRENCY_CALL_SITES,
                tally.getOrDefault(TEST_NUMVAL_CURRENCY_FUNCTION, 0),
                "the FUNCTION TEST-NUMVAL-C call-site count changed");

        assertEquals(EXPECTED_CALL_SITE_TOTAL,
                tally.values().stream().mapToInt(Integer::intValue).sum(),
                "the total call-site count changed");
    }

    @Test
    void onlyFourSourceMembersCallACobolNumericFunction() {
        assertEquals(Set.of("COACTUPC.cbl", "CORPT00C.cbl", "COTRN02C.cbl", "CSUTLDPY.cpy"),
                new TreeSet<>(callingSourceMembers()),
                "the set of members calling a COBOL numeric function changed");
    }

    /**
     * Asserts that a rejection names the COBOL function and the length of the argument, and
     * nothing else.
     *
     * <p>{@code app/cbl/COACTUPC.cbl:L2201} gates a fifteen-character signed money field, and a
     * caller logging the rejection must not publish the value that field held. The assertions pin
     * the whole message, so a later addition of the argument to the message fails here.</p>
     */
    @Test
    void aRejectionNamesTheFunctionAndTheLengthAndNothingElse() {
        String argument = "$4,111-2222.33XX";

        assertEquals(NUMVAL_FUNCTION + " rejects this argument. The argument holds "
                        + argument.length() + " characters.",
                assertThrows(NumberFormatException.class,
                        () -> NumvalParser.numval(argument)).getMessage(),
                "the FUNCTION NUMVAL rejection message changed");

        assertEquals(NUMVAL_CURRENCY_FUNCTION + " rejects this argument. The argument holds "
                        + argument.length() + " characters.",
                assertThrows(NumberFormatException.class,
                        () -> NumvalParser.numvalCurrency(argument)).getMessage(),
                "the FUNCTION NUMVAL-C rejection message changed");

        assertEquals(NUMVAL_FUNCTION + " rejects this argument. The argument is null.",
                assertThrows(NumberFormatException.class, () -> NumvalParser.numval(null))
                        .getMessage(),
                "the null-argument rejection message changed");

        assertEquals(NUMVAL_CURRENCY_FUNCTION + " rejects this argument. The argument is null.",
                assertThrows(NumberFormatException.class,
                        () -> NumvalParser.numvalCurrency(null)).getMessage(),
                "the currency-tolerant null-argument rejection message changed");
    }

    @Test
    void noRejectionMessageCarriesASensitiveArgument() {
        String[] sensitiveArguments = {
                "4111222233334444X",
                "451X",
                "$9,999,999.99CRX",
                "\u0000".repeat(SIGNED_MONEY_FIELD_WIDTH) + "X",
        };

        for (String argument : sensitiveArguments) {
            for (String message : new String[] {
                    assertThrows(NumberFormatException.class,
                            () -> NumvalParser.numval(argument)).getMessage(),
                    assertThrows(NumberFormatException.class,
                            () -> NumvalParser.numvalCurrency(argument)).getMessage()}) {

                assertFalse(message.contains(argument),
                        "a rejection carried an argument of length " + argument.length());
                assertTrue(message.endsWith(EXPECTED_LENGTH_SENTENCE_SUFFIX),
                        "a rejection stopped closing with the length sentence");

                for (int position = 0; position + 2 <= argument.length(); position++) {
                    String run = argument.substring(position, position + 2);
                    assertFalse(message.contains(run),
                            "a rejection carried a two-character run of the argument at position "
                                    + position);
                }
            }
        }
    }

    /**
     * Counts every call site of the four COBOL numeric functions across {@code app/cbl} and
     * {@code app/cpy}.
     *
     * @return the count per function name, holding no entry for a function with no call site
     */
    private static Map<String, Integer> countCobolFunctionCallSites() {
        Map<String, Integer> tally = new TreeMap<>();

        for (Path member : cobolSourceMembers()) {
            for (String line : sourceLines(member)) {
                countFunctionsOnLine(line, tally);
            }
        }
        return tally;
    }

    /**
     * Names every member of {@code app/cbl} or {@code app/cpy} that holds at least one call site.
     *
     * @return the file names, without their directory
     */
    private static Set<String> callingSourceMembers() {
        Set<String> members = new TreeSet<>();

        for (Path member : cobolSourceMembers()) {
            Map<String, Integer> tally = new TreeMap<>();
            for (String line : sourceLines(member)) {
                countFunctionsOnLine(line, tally);
            }
            if (!tally.isEmpty()) {
                members.add(member.getFileName().toString());
            }
        }
        return members;
    }

    /**
     * Adds the call sites one line holds to a running tally. A name is matched at its longest form
     * first, so a longer name never contributes to a shorter one.
     *
     * @param line  one source line, with its sequence area intact
     * @param tally the running count per function name
     */
    private static void countFunctionsOnLine(String line, Map<String, Integer> tally) {
        if (isCommentLine(line)) {
            return;
        }

        Matcher matcher = COBOL_FUNCTION_CALL.matcher(line);
        while (matcher.find()) {
            String function = COBOL_FUNCTION_KEYWORD + " " + matcher.group(1);
            tally.merge(function, 1, Integer::sum);
        }
    }

    /**
     * Reports whether a line carries a COBOL comment indicator in column 7.
     *
     * @param line one source line
     * @return {@code true} when column 7 holds {@code *} or {@code /}
     */
    private static boolean isCommentLine(String line) {
        return line.length() > COMMENT_COLUMN
                && (line.charAt(COMMENT_COLUMN) == '*' || line.charAt(COMMENT_COLUMN) == '/');
    }

    /**
     * Reads one member of {@code app/cbl} or {@code app/cpy}. The member is opened for reading and
     * never written.
     *
     * @param member absolute path of the member
     * @return the lines in file order
     */
    private static List<String> sourceLines(Path member) {
        try {
            return Files.readAllLines(member, StandardCharsets.ISO_8859_1);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("reading " + member + " failed", unreadable);
        }
    }

    /**
     * Lists every regular file under {@code app/cbl} and {@code app/cpy}. Both directories hold
     * members whose extension is upper case, so no extension filter is applied.
     *
     * @return the members in path order
     */
    private static List<Path> cobolSourceMembers() {
        List<Path> members = new ArrayList<>();

        for (String directory : new String[] {COBOL_DIRECTORY, COPYBOOK_DIRECTORY}) {
            Path resolved = sourceRoot().resolve(directory);

            assertTrue(Files.isDirectory(resolved), resolved + " is not a directory");
            try (Stream<Path> children = Files.list(resolved)) {
                children.filter(Files::isRegularFile).sorted().forEach(members::add);
            } catch (IOException unreadable) {
                throw new UncheckedIOException("listing " + resolved + " failed", unreadable);
            }
        }
        assertFalse(members.isEmpty(), "no source member was found");
        return members;
    }

    /**
     * Resolves the {@code app} directory by walking upward from the working directory. Maven runs a
     * module build from the module directory and an aggregator build from the aggregator directory,
     * and the walk finds the same directory from either.
     *
     * @return the absolute path of {@code app}
     */
    private static Path sourceRoot() {
        Path start = Path.of("").toAbsolutePath().normalize();

        for (Path candidate = start; candidate != null; candidate = candidate.getParent()) {
            Path application = candidate.resolve(SOURCE_MARKER_DIRECTORY);
            if (Files.isDirectory(application.resolve(COBOL_DIRECTORY))
                    && Files.isDirectory(application.resolve(COPYBOOK_DIRECTORY))) {
                return application;
            }
        }
        throw new IllegalStateException("walked upward from " + start
                + " to the filesystem root without finding a directory named '"
                + SOURCE_MARKER_DIRECTORY + "' holding '" + COBOL_DIRECTORY + "' and '"
                + COPYBOOK_DIRECTORY + "'");
    }
}
