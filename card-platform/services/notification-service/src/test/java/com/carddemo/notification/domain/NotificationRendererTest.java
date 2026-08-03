package com.carddemo.notification.domain;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests the fixed-width helpers, the two nested payload records and the format enumeration of
 * {@link NotificationRenderer}.
 *
 * <p>Every expected value below is typed by hand from the Picture clause it reproduces. A Picture
 * clause, written {@code PIC}, fixes the width and the form of a Common Business Oriented Language
 * (COBOL) field. No expected value comes from calling the helper under test, and none comes from a
 * fixture file. A helper that changes its padding, its truncation or its sign position fails
 * here.</p>
 *
 * <p>The widths come from the statement lines of app/cbl/CBSTM03A.CBL. The name and address fields
 * are {@code ST-NAME PIC X(75)} at L91, {@code ST-ADD1 PIC X(50)} at L94,
 * {@code ST-ADD2 PIC X(50)} at L97 and {@code ST-ADD3 PIC X(80)} at L100. The detail fields are
 * {@code ST-TRANID PIC X(16)} at L133, {@code ST-TRANDT PIC X(49)} at L135 and
 * {@code ST-FICO-SCORE PIC X(20)} at L118. The amount fields are
 * {@code ST-CURR-BAL PIC 9(9).99-} at L113, {@code ST-TRANAMT PIC Z(9).99-} at L137 and
 * {@code ST-TOTAL-TRAMT PIC Z(9).99-} at L142.</p>
 *
 * <p>Two edits carry two amount forms. A {@code 9} digit position renders a leading zero as a zero
 * and a {@code Z} digit position renders it as a space. Both forms hold nine digit positions, a
 * decimal point, two decimal digits and one trailing sign position.</p>
 *
 * <p>Two source losses are reproduced and asserted here. A balance of ten integer digits, declared
 * {@code ACCT-CURR-BAL PIC S9(10)V99} at app/cpy/CVACT01Y.cpy:L7, drops its high-order digit in the
 * nine digit positions of app/cbl/CBSTM03A.CBL:L113. Three full name components emit 78 characters
 * into the 75 of app/cbl/CBSTM03A.CBL:L91 and lose the last three. Both losses are recorded in
 * card-platform/docs/business-rule-flags.md.</p>
 *
 * <p>No Spring context, no broker and no database take part, so {@code mvn test} passes on a clean
 * machine. The two render operations belong to their own implementations, and no assertion here
 * reads the content of a rendered alert.</p>
 */
@DisplayName("NotificationRenderer, the fixed-width edits and field assembly of CBSTM03A")
class NotificationRendererTest {

    /**
     * Characters an edited amount holds: nine digit positions, a decimal point, two decimal digits
     * and one trailing sign position. The form sits at app/cbl/CBSTM03A.CBL:L113.
     */
    private static final int EDITED_WIDTH = 13;

    /** Integer digit positions of {@code PIC 9(9).99-} at app/cbl/CBSTM03A.CBL:L113. */
    private static final int INTEGER_POSITIONS = 9;

    /** Index of the decimal point in an edited amount, from app/cbl/CBSTM03A.CBL:L113. */
    private static final int DECIMAL_POINT_INDEX = 9;

    /** Index of the trailing sign position in an edited amount, from app/cbl/CBSTM03A.CBL:L113. */
    private static final int SIGN_INDEX = 12;

    /**
     * Width of each name component: {@code CUST-FIRST-NAME PIC X(25)} at app/cpy/CUSTREC.cpy:L6,
     * {@code CUST-MIDDLE-NAME} at L7 and {@code CUST-LAST-NAME} at L8.
     */
    private static final int NAME_COMPONENT_WIDTH = 25;

    /** Width of {@code CUST-ADDR-LINE-3 PIC X(50)} at app/cpy/CUSTREC.cpy:L11. */
    private static final int ADDRESS_LINE_WIDTH = 50;

    /** Width of {@code CUST-ADDR-STATE-CD PIC X(02)} at app/cpy/CUSTREC.cpy:L12. */
    private static final int STATE_CODE_WIDTH = 2;

    /** Width of {@code CUST-ADDR-COUNTRY-CD PIC X(03)} at app/cpy/CUSTREC.cpy:L13. */
    private static final int COUNTRY_CODE_WIDTH = 3;

    /** Width of {@code CUST-ADDR-ZIP PIC X(10)} at app/cpy/CUSTREC.cpy:L14. */
    private static final int POSTAL_CODE_WIDTH = 10;

    /** Digits in {@code ACCT-ID PIC 9(11)} at app/cpy/CVACT01Y.cpy:L5, a zero-filled field. */
    private static final int ACCOUNT_ID_DIGITS = 11;

    /**
     * Digits in {@code CUST-FICO-CREDIT-SCORE PIC 9(03)} at app/cpy/CUSTREC.cpy:L22. FICO names a
     * credit score.
     */
    private static final int CREDIT_SCORE_DIGITS = 3;

    /** Width of {@code L23-NAME PIC X(50)} at app/cbl/CBSTM03A.CBL:L220, the markup name field. */
    private static final int MARKUP_NAME_WIDTH = 50;

    /** Width of {@code TRNX-DESC PIC X(100)} at app/cpy/COSTM01.CPY:L28, the stored description. */
    private static final int STORED_DESCRIPTION_WIDTH = 100;

    /** Width of {@code TRNX-ID PIC X(16)} at app/cpy/COSTM01.CPY:L23. */
    private static final int STORED_TRANSACTION_ID_WIDTH = 16;

    // Declared widths, each against the Picture clause it reproduces.

    /**
     * Asserts every width the interface declares against the field it reproduces at
     * app/cbl/CBSTM03A.CBL:L91-L142. Each expected number is typed here from the source and read
     * from no constant of the interface.
     */
    @Test
    @DisplayName("Every declared width matches the Picture clause it reproduces")
    void everyDeclaredWidthMatchesItsPictureClause() {
        assertThat(NotificationRenderer.ST_NAME_WIDTH)
                .as("ST-NAME PIC X(75) at app/cbl/CBSTM03A.CBL:L91")
                .isEqualTo(75);
        assertThat(NotificationRenderer.ST_ADD1_WIDTH)
                .as("ST-ADD1 PIC X(50) at app/cbl/CBSTM03A.CBL:L94")
                .isEqualTo(50);
        assertThat(NotificationRenderer.ST_ADD2_WIDTH)
                .as("ST-ADD2 PIC X(50) at app/cbl/CBSTM03A.CBL:L97")
                .isEqualTo(50);
        assertThat(NotificationRenderer.ST_ADD3_WIDTH)
                .as("ST-ADD3 PIC X(80) at app/cbl/CBSTM03A.CBL:L100")
                .isEqualTo(80);
        assertThat(NotificationRenderer.ST_ACCT_ID_WIDTH)
                .as("the account field the MOVE at app/cbl/CBSTM03A.CBL:L483 fills, repeated as"
                        + " L11-ACCT PIC X(20) at app/cbl/CBSTM03A.CBL:L215")
                .isEqualTo(20);
        assertThat(NotificationRenderer.ST_FICO_SCORE_WIDTH)
                .as("ST-FICO-SCORE PIC X(20) at app/cbl/CBSTM03A.CBL:L118")
                .isEqualTo(20);
        assertThat(NotificationRenderer.ST_TRANID_WIDTH)
                .as("ST-TRANID PIC X(16) at app/cbl/CBSTM03A.CBL:L133")
                .isEqualTo(STORED_TRANSACTION_ID_WIDTH);
        assertThat(NotificationRenderer.ST_TRANDT_WIDTH)
                .as("ST-TRANDT PIC X(49) at app/cbl/CBSTM03A.CBL:L135")
                .isEqualTo(49);
        assertThat(NotificationRenderer.EDITED_AMOUNT_WIDTH)
                .as("ST-CURR-BAL PIC 9(9).99- at app/cbl/CBSTM03A.CBL:L113 holds 13 characters")
                .isEqualTo(EDITED_WIDTH);
        assertThat(NotificationRenderer.LINE_SEPARATOR)
                .as("the records of app/cbl/CBSTM03A.CBL:L488-L502 join with a line feed")
                .isEqualTo("\n");
    }

    /**
     * Asserts the loss the {@code MOVE TRNX-DESC TO ST-TRANDT} at app/cbl/CBSTM03A.CBL:L677
     * accepts. {@code TRNX-DESC PIC X(100)} at app/cpy/COSTM01.CPY:L28 is 51 characters wider than
     * the field that renders it.
     */
    @Test
    @DisplayName("The rendered description is 51 characters narrower than the stored description")
    void theRenderedDescriptionIsFiftyOneCharactersNarrowerThanTheStoredDescription() {
        assertThat(STORED_DESCRIPTION_WIDTH - NotificationRenderer.ST_TRANDT_WIDTH)
                .as("app/cbl/CBSTM03A.CBL:L677 drops 51 characters of TRNX-DESC")
                .isEqualTo(51);
    }

    // pic. Reproduces a COBOL MOVE into a PIC X(n) field.

    /**
     * Asserts that a value shorter than the field gains trailing spaces. The
     * {@code MOVE CUST-FICO-CREDIT-SCORE TO ST-FICO-SCORE} at app/cbl/CBSTM03A.CBL:L485 carries
     * three digits into a field of 20.
     */
    @Test
    @DisplayName("A short value is left justified and padded with trailing spaces")
    void picPadsAShortValueOnTheRight() {
        String padded = NotificationRenderer.pic("742", NotificationRenderer.ST_FICO_SCORE_WIDTH);

        assertThat(padded)
                .as("a credit score of three digits reaches the field width of 20")
                .hasSize(NotificationRenderer.ST_FICO_SCORE_WIDTH);
        assertThat(padded)
                .as("the digits stay at the front and spaces fill the tail")
                .isEqualTo("742" + " ".repeat(
                        NotificationRenderer.ST_FICO_SCORE_WIDTH - CREDIT_SCORE_DIGITS));
        assertThat(NotificationRenderer.pic("", 4))
                .as("an empty value fills the field with spaces")
                .isEqualTo("    ");
        assertThat(NotificationRenderer.pic(null, 4))
                .as("a null value fills the field with spaces and raises no exception")
                .isEqualTo("    ");
    }

    /**
     * Asserts that a value longer than the field loses its tail. The
     * {@code MOVE ST-NAME TO L23-NAME} at app/cbl/CBSTM03A.CBL:L560 carries
     * {@code ST-NAME PIC X(75)} into {@code L23-NAME PIC X(50)}.
     */
    @Test
    @DisplayName("A long value keeps its leading characters and loses its tail")
    void picTruncatesALongValueToItsLeadingCharacters() {
        String assembledName = NotificationRenderer.assembleName("ALPHA", "BETA", "GAMMA");
        String markupName = NotificationRenderer.pic(assembledName, MARKUP_NAME_WIDTH);

        assertThat(assembledName)
                .as("the sending field holds 75 characters")
                .hasSize(NotificationRenderer.ST_NAME_WIDTH);
        assertThat(markupName)
                .as("the receiving field of app/cbl/CBSTM03A.CBL:L220 holds 50 characters")
                .hasSize(MARKUP_NAME_WIDTH);
        assertThat(markupName)
                .as("the first 50 characters of the sending field survive the move")
                .isEqualTo(assembledName.substring(0, MARKUP_NAME_WIDTH));
        assertThat(NotificationRenderer.pic("ABCDEF", 3))
                .as("a value of six characters keeps its first three at width 3")
                .isEqualTo("ABC");
        assertThat(NotificationRenderer.pic("ABC", 0))
                .as("a width of zero renders the empty string")
                .isEmpty();
    }

    /**
     * Asserts that a value already at the field width passes through unchanged. Nine of the
     * statement fields of app/cbl/CBSTM03A.CBL:L91-L142 receive a value already at their width.
     */
    @Test
    @DisplayName("A value already at the field width passes through unchanged")
    void picReturnsAnExactFitUnchanged() {
        String atWidth = "A".repeat(NotificationRenderer.ST_TRANID_WIDTH);
        String moved = NotificationRenderer.pic(atWidth, NotificationRenderer.ST_TRANID_WIDTH);

        assertThat(moved)
                .as("the result holds the width of ST-TRANID at app/cbl/CBSTM03A.CBL:L133")
                .hasSize(NotificationRenderer.ST_TRANID_WIDTH);
        assertThat(moved)
                .as("no character changes when the value already fits")
                .isEqualTo(atWidth);
        assertThat(NotificationRenderer.pic("     ", 5))
                .as("a value of only spaces passes through at its own width")
                .isEqualTo("     ");
    }

    /**
     * Asserts that an eleven-digit account identifier keeps its leading zeros. {@code ACCT-ID} is
     * {@code PIC 9(11)} at app/cpy/CVACT01Y.cpy:L5, and the {@code MOVE} at
     * app/cbl/CBSTM03A.CBL:L483 carries it into an alphanumeric field of 20.
     */
    @Test
    @DisplayName("An eleven-digit account identifier keeps its leading zeros")
    void picKeepsTheLeadingZerosOfAnElevenDigitAccountIdentifier() {
        String accountId = "00000000011";
        String moved = NotificationRenderer.pic(accountId, NotificationRenderer.ST_ACCT_ID_WIDTH);

        assertThat(accountId)
                .as("the sending field holds 11 digits")
                .hasSize(ACCOUNT_ID_DIGITS);
        assertThat(moved)
                .as("the result holds the width of the account field of app/cbl/CBSTM03A.CBL:L483")
                .hasSize(NotificationRenderer.ST_ACCT_ID_WIDTH);
        assertThat(moved.substring(0, ACCOUNT_ID_DIGITS))
                .as("all nine leading zeros survive the move")
                .isEqualTo(accountId);
        assertThat(moved)
                .as("the nine spare positions hold spaces")
                .isEqualTo(accountId + " ".repeat(
                        NotificationRenderer.ST_ACCT_ID_WIDTH - ACCOUNT_ID_DIGITS));
    }

    /**
     * Asserts the width invariant across every field width of app/cbl/CBSTM03A.CBL:L91-L142, for a
     * short value, a long value and a null.
     */
    @Test
    @DisplayName("Every result holds exactly the requested width")
    void picHoldsExactlyTheRequestedWidthForEveryInput() {
        String shortValue = "AB";
        String longValue = "X".repeat(NotificationRenderer.ST_ADD3_WIDTH + 10);

        for (int width = 0; width <= NotificationRenderer.ST_ADD3_WIDTH; width++) {
            assertThat(NotificationRenderer.pic(shortValue, width))
                    .as("a short value at width %d", width)
                    .hasSize(width);
            assertThat(NotificationRenderer.pic(longValue, width))
                    .as("a long value at width %d", width)
                    .hasSize(width);
            assertThat(NotificationRenderer.pic(null, width))
                    .as("a null value at width %d", width)
                    .hasSize(width);
        }
    }

    /**
     * Asserts that a negative width is refused. No field of app/cbl/CBSTM03A.CBL:L91-L142 declares
     * one.
     */
    @Test
    @DisplayName("A negative width is refused and the refusal names the width")
    void picRefusesANegativeWidth() {
        assertThatThrownBy(() -> NotificationRenderer.pic("AB", -1))
                .as("a negative width names no Picture clause")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("width must not be negative: width=-1");
        assertThatThrownBy(() -> NotificationRenderer.pic(null, -5))
                .as("the width check runs ahead of the null check")
                .isInstanceOf(IllegalArgumentException.class);
    }

    // editTrailingSign9. Reproduces ST-CURR-BAL PIC 9(9).99- at app/cbl/CBSTM03A.CBL:L113.

    /**
     * Asserts the width invariant of the zero-filled edit. Every result matches
     * {@code ST-CURR-BAL PIC 9(9).99-} at app/cbl/CBSTM03A.CBL:L113, and the decimal point sits at
     * one fixed index.
     */
    @Test
    @DisplayName("The zero-filled edit holds 13 characters for every amount")
    void editTrailingSign9HoldsThirteenCharactersForEveryAmount() {
        List<String> amounts = List.of("42.50", "-42.50", "0.00", "1.99", "1234.56",
                "999999999.99", "1.999", "1234567890.12", "-0.001");

        for (String amount : amounts) {
            String edited = NotificationRenderer.editTrailingSign9(new BigDecimal(amount));

            assertThat(edited)
                    .as("the edit of %s holds 13 characters", amount)
                    .hasSize(EDITED_WIDTH);
            assertThat(edited.charAt(DECIMAL_POINT_INDEX))
                    .as("the decimal point of %s follows nine digit positions", amount)
                    .isEqualTo('.');
        }
    }

    /**
     * Asserts that every digit position renders a digit. The {@code 9} symbol of
     * {@code ST-CURR-BAL PIC 9(9).99-} at app/cbl/CBSTM03A.CBL:L113 keeps a leading zero as a
     * zero.
     */
    @Test
    @DisplayName("Every digit position of the zero-filled edit renders a digit")
    void editTrailingSign9RendersEveryDigitPositionAsADigit() {
        String edited = NotificationRenderer.editTrailingSign9(new BigDecimal("42.50"));

        assertThat(edited)
                .as("42.50 fills all nine digit positions")
                .hasSize(EDITED_WIDTH);
        assertThat(edited.substring(0, INTEGER_POSITIONS - 2))
                .as("the seven positions ahead of the two significant digits render zeros")
                .isEqualTo("0000000");
        assertThat(edited)
                .as("42.50 renders seven zeros, the two digits, the point, the decimals and a"
                        + " blank sign position")
                .isEqualTo("000000042.50 ");
        assertThat(NotificationRenderer.editTrailingSign9(new BigDecimal("1234.56")))
                .as("1234.56 renders five leading zeros")
                .isEqualTo("000001234.56 ");
        assertThat(NotificationRenderer.editTrailingSign9(new BigDecimal("999999999.99")))
                .as("the widest amount the nine digit positions hold suppresses nothing")
                .isEqualTo("999999999.99 ");
    }

    /**
     * Asserts that a negative amount carries a minus in the trailing position of
     * {@code ST-CURR-BAL PIC 9(9).99-} at app/cbl/CBSTM03A.CBL:L113, after both decimal digits.
     */
    @Test
    @DisplayName("A negative amount carries its minus after the decimal digits")
    void editTrailingSign9PlacesTheMinusInTheTrailingPosition() {
        String edited = NotificationRenderer.editTrailingSign9(new BigDecimal("-42.50"));

        assertThat(edited)
                .as("the negative edit holds 13 characters")
                .hasSize(EDITED_WIDTH);
        assertThat(edited.charAt(SIGN_INDEX))
                .as("the sign occupies the final position")
                .isEqualTo('-');
        assertThat(edited.substring(DECIMAL_POINT_INDEX))
                .as("the minus follows the point and both decimal digits")
                .isEqualTo(".50-");
        assertThat(edited.indexOf('-'))
                .as("no minus appears ahead of the digits")
                .isEqualTo(SIGN_INDEX);
        assertThat(edited)
                .as("-42.50 renders the magnitude and a trailing minus")
                .isEqualTo("000000042.50-");
    }

    /**
     * Asserts that a positive amount leaves the trailing sign position of
     * app/cbl/CBSTM03A.CBL:L113 blank.
     */
    @Test
    @DisplayName("A positive amount leaves the trailing sign position blank")
    void editTrailingSign9LeavesTheSignPositionBlankForAPositiveAmount() {
        String edited = NotificationRenderer.editTrailingSign9(new BigDecimal("42.50"));

        assertThat(edited)
                .as("the positive edit holds 13 characters")
                .hasSize(EDITED_WIDTH);
        assertThat(edited.charAt(SIGN_INDEX))
                .as("the sign position holds a space")
                .isEqualTo(' ');
        assertThat(edited)
                .as("no minus appears anywhere in a positive edit")
                .doesNotContain("-");
    }

    /**
     * Asserts that zero renders every digit position, the decimal point and both decimal digits.
     * The declaration at app/cbl/CBSTM03A.CBL:L113 carries no {@code BLANK WHEN ZERO} clause, so
     * the field is not blanked.
     */
    @Test
    @DisplayName("Zero renders nine zero digits, the point and both decimal digits")
    void editTrailingSign9RendersZeroWithItsPointAndBothDecimalDigits() {
        String edited = NotificationRenderer.editTrailingSign9(new BigDecimal("0.00"));

        assertThat(edited)
                .as("the edit of zero holds 13 characters")
                .hasSize(EDITED_WIDTH);
        assertThat(edited)
                .as("zero renders nine zeros, the point, two zeros and a blank sign position")
                .isEqualTo("000000000.00 ");
        assertThat(edited.substring(0, INTEGER_POSITIONS))
                .as("every integer position renders a zero digit")
                .isEqualTo("000000000");
        assertThat(edited.substring(DECIMAL_POINT_INDEX + 1, SIGN_INDEX))
                .as("both decimal digits survive")
                .isEqualTo("00");
        assertThat(edited.isBlank())
                .as("no BLANK WHEN ZERO clause sits at app/cbl/CBSTM03A.CBL:L113")
                .isFalse();
    }

    /**
     * Asserts that the third decimal digit and beyond fall away toward zero. The
     * {@code MOVE ACCT-CURR-BAL TO ST-CURR-BAL} at app/cbl/CBSTM03A.CBL:L484 carries no
     * {@code ROUNDED} phrase, and no program of the source names one.
     */
    @Test
    @DisplayName("The third decimal digit falls away toward zero and never rounds")
    void editTrailingSign9TruncatesTowardZeroAndNeverRounds() {
        assertThat(NotificationRenderer.editTrailingSign9(new BigDecimal("1.999")))
                .as("the truncated edit holds 13 characters")
                .hasSize(EDITED_WIDTH);
        assertThat(NotificationRenderer.editTrailingSign9(new BigDecimal("1.999")))
                .as("1.999 holds 1.99")
                .isEqualTo("000000001.99 ");
        assertThat(NotificationRenderer.editTrailingSign9(new BigDecimal("1.999")))
                .as("half-up rounding would render 2.00 and fail here")
                .isNotEqualTo("000000002.00 ");
        assertThat(NotificationRenderer.editTrailingSign9(new BigDecimal("-1.999")))
                .as("-1.999 truncates toward zero to -1.99")
                .isEqualTo("000000001.99-");
        assertThat(NotificationRenderer.editTrailingSign9(new BigDecimal("0.009")))
                .as("0.009 holds 0.00")
                .isEqualTo("000000000.00 ");
    }

    /**
     * Asserts the high-order digit loss the two field widths produce.
     * {@code ACCT-CURR-BAL PIC S9(10)V99} at app/cpy/CVACT01Y.cpy:L7 carries ten integer digits and
     * {@code ST-CURR-BAL PIC 9(9).99-} at app/cbl/CBSTM03A.CBL:L113 carries nine, so a balance at or
     * above one billion loses its high-order digit in both rendered outputs.
     */
    @Test
    @DisplayName("A ten-digit balance loses its high-order digit and keeps the low nine")
    void editTrailingSign9DropsTheHighOrderDigitOfATenDigitBalance() {
        String edited = NotificationRenderer.editTrailingSign9(new BigDecimal("1234567890.12"));

        assertThat(edited)
                .as("the narrowed edit still holds 13 characters")
                .hasSize(EDITED_WIDTH);
        assertThat(edited.substring(0, INTEGER_POSITIONS))
                .as("the low nine integer digits survive")
                .isEqualTo("234567890");
        assertThat(edited)
                .as("the tenth integer digit reaches no output")
                .doesNotContain("1234567890");
        assertThat(edited)
                .as("the balance renders as its low nine digits and both decimals")
                .isEqualTo("234567890.12 ");
        assertThat(NotificationRenderer.editTrailingSign9(new BigDecimal("1000000000.00")))
                .as("a balance of one billion renders zero")
                .isEqualTo("000000000.00 ");
        assertThat(NotificationRenderer.editTrailingSign9(new BigDecimal("-1000000000.00")))
                .as("a negative balance whose magnitude narrows to zero renders a blank sign")
                .isEqualTo("000000000.00 ");
    }

    /**
     * Asserts that a null amount is refused. Every call site of app/cbl/CBSTM03A.CBL:L484 supplies
     * a numeric field, which holds no absent value.
     */
    @Test
    @DisplayName("The zero-filled edit refuses a null amount")
    void editTrailingSign9RefusesANullAmount() {
        assertThatThrownBy(() -> NotificationRenderer.editTrailingSign9(null))
                .as("an amount is required, unlike a text field")
                .isInstanceOf(NullPointerException.class);
    }

    // editTrailingSignZ. Reproduces ST-TRANAMT at app/cbl/CBSTM03A.CBL:L137 and ST-TOTAL-TRAMT
    // at app/cbl/CBSTM03A.CBL:L142, both PIC Z(9).99-.

    /**
     * Asserts the width invariant of the suppressed edit. Every result matches
     * {@code ST-TRANAMT PIC Z(9).99-} at app/cbl/CBSTM03A.CBL:L137, which
     * {@code ST-TOTAL-TRAMT} at app/cbl/CBSTM03A.CBL:L142 repeats.
     */
    @Test
    @DisplayName("The suppressed edit holds 13 characters for every amount")
    void editTrailingSignZHoldsThirteenCharactersForEveryAmount() {
        List<String> amounts = List.of("42.50", "-42.50", "0.00", "0.05", "-0.99", "1.99",
                "1234.56", "100000000.00", "999999999.99", "1234567890.12");

        for (String amount : amounts) {
            String edited = NotificationRenderer.editTrailingSignZ(new BigDecimal(amount));

            assertThat(edited)
                    .as("the suppressed edit of %s holds 13 characters", amount)
                    .hasSize(EDITED_WIDTH);
            assertThat(edited.charAt(DECIMAL_POINT_INDEX))
                    .as("the decimal point of %s follows nine digit positions", amount)
                    .isEqualTo('.');
        }
    }

    /**
     * Asserts that the {@code Z} symbol of {@code ST-TRANAMT PIC Z(9).99-} at
     * app/cbl/CBSTM03A.CBL:L137 renders a leading zero as a space, and that suppression stops at
     * the first digit above zero.
     */
    @Test
    @DisplayName("The suppressed edit renders a leading zero as a space")
    void editTrailingSignZRendersALeadingZeroAsASpace() {
        String edited = NotificationRenderer.editTrailingSignZ(new BigDecimal("42.50"));

        assertThat(edited)
                .as("the suppressed edit of 42.50 holds 13 characters")
                .hasSize(EDITED_WIDTH);
        assertThat(edited.substring(0, INTEGER_POSITIONS - 2))
                .as("the seven unused digit positions hold spaces")
                .isEqualTo("       ");
        assertThat(edited)
                .as("42.50 renders seven spaces, the two digits, the point and the decimals")
                .isEqualTo("       42.50 ");
        assertThat(NotificationRenderer.editTrailingSignZ(new BigDecimal("1.99")))
                .as("1.99 leaves eight positions blank ahead of its single digit")
                .isEqualTo("        1.99 ");
        assertThat(NotificationRenderer.editTrailingSignZ(new BigDecimal("100000000.00")))
                .as("an amount filling the first digit position suppresses nothing")
                .isEqualTo("100000000.00 ");
    }

    /**
     * Asserts that the two edits disagree on one amount. {@code ST-CURR-BAL PIC 9(9).99-} at
     * app/cbl/CBSTM03A.CBL:L113 renders a leading zero as a zero and
     * {@code ST-TRANAMT PIC Z(9).99-} at app/cbl/CBSTM03A.CBL:L137 renders it as a space, and the
     * two renderings of one amount differ.
     */
    @Test
    @DisplayName("The two edits disagree on the same amount")
    void theTwoEditsDisagreeOnTheSameAmount() {
        BigDecimal amount = new BigDecimal("42.50");
        String zeroFilled = NotificationRenderer.editTrailingSign9(amount);
        String suppressed = NotificationRenderer.editTrailingSignZ(amount);

        assertThat(zeroFilled)
                .as("the zero-filled form of 42.50 holds 13 characters")
                .hasSize(EDITED_WIDTH);
        assertThat(suppressed)
                .as("the suppressed form of 42.50 holds 13 characters")
                .hasSize(EDITED_WIDTH);
        assertThat(suppressed)
                .as("the two forms of 42.50 differ")
                .isNotEqualTo(zeroFilled);
        assertThat(zeroFilled)
                .as("app/cbl/CBSTM03A.CBL:L113 renders the unused positions as zeros")
                .isEqualTo("000000042.50 ");
        assertThat(suppressed)
                .as("app/cbl/CBSTM03A.CBL:L137 renders the unused positions as spaces")
                .isEqualTo("       42.50 ");
        assertThat(suppressed.substring(0, INTEGER_POSITIONS).replace(' ', '0'))
                .as("the two forms differ in the leading positions alone")
                .isEqualTo(zeroFilled.substring(0, INTEGER_POSITIONS));
        assertThat(suppressed.substring(INTEGER_POSITIONS))
                .as("the point, the decimals and the sign position match in both forms")
                .isEqualTo(zeroFilled.substring(INTEGER_POSITIONS));
    }

    /**
     * Asserts that the suppressed edit carries a negative amount's minus in the trailing position
     * of app/cbl/CBSTM03A.CBL:L137, after both decimal digits.
     */
    @Test
    @DisplayName("The suppressed edit carries its minus after the decimal digits")
    void editTrailingSignZPlacesTheMinusInTheTrailingPosition() {
        String edited = NotificationRenderer.editTrailingSignZ(new BigDecimal("-42.50"));

        assertThat(edited)
                .as("the negative suppressed edit holds 13 characters")
                .hasSize(EDITED_WIDTH);
        assertThat(edited.charAt(SIGN_INDEX))
                .as("the sign occupies the final position")
                .isEqualTo('-');
        assertThat(edited.substring(DECIMAL_POINT_INDEX))
                .as("the minus follows the point and both decimal digits")
                .isEqualTo(".50-");
        assertThat(edited)
                .as("-42.50 renders spaces, the magnitude and a trailing minus")
                .isEqualTo("       42.50-");
        assertThat(NotificationRenderer.editTrailingSignZ(new BigDecimal("-0.99")))
                .as("an amount below one keeps its minus and blanks every digit position")
                .isEqualTo("         .99-");
    }

    /**
     * Asserts that zero blanks all nine digit positions and keeps the point and both decimal
     * digits. Neither app/cbl/CBSTM03A.CBL:L137 nor app/cbl/CBSTM03A.CBL:L142 carries a
     * {@code BLANK WHEN ZERO} clause.
     */
    @Test
    @DisplayName("Zero blanks all nine digit positions and keeps the point and decimals")
    void editTrailingSignZRendersZeroWithNineBlankDigitPositions() {
        String edited = NotificationRenderer.editTrailingSignZ(new BigDecimal("0.00"));

        assertThat(edited)
                .as("the suppressed edit of zero holds 13 characters")
                .hasSize(EDITED_WIDTH);
        assertThat(edited.substring(0, INTEGER_POSITIONS))
                .as("all nine digit positions hold spaces")
                .isEqualTo("         ");
        assertThat(edited.charAt(DECIMAL_POINT_INDEX))
                .as("the decimal point survives")
                .isEqualTo('.');
        assertThat(edited.substring(DECIMAL_POINT_INDEX + 1, SIGN_INDEX))
                .as("both decimal digits survive")
                .isEqualTo("00");
        assertThat(edited)
                .as("zero renders nine spaces, the point, two zeros and a blank sign position")
                .isEqualTo("         .00 ");
        assertThat(edited.isBlank())
                .as("no BLANK WHEN ZERO clause sits at app/cbl/CBSTM03A.CBL:L137")
                .isFalse();
        assertThat(NotificationRenderer.editTrailingSignZ(new BigDecimal("0.05")))
                .as("0.05 keeps its decimals and blanks every digit position")
                .isEqualTo("         .05 ");
    }

    /**
     * Asserts that the suppressed edit meets the same nine-digit ceiling as
     * {@code ST-CURR-BAL} at app/cbl/CBSTM03A.CBL:L113. {@code ST-TRANAMT PIC Z(9).99-} at
     * app/cbl/CBSTM03A.CBL:L137 holds nine digit positions too.
     */
    @Test
    @DisplayName("The suppressed edit meets the same nine-digit ceiling")
    void editTrailingSignZMeetsTheSameNineDigitCeiling() {
        String edited = NotificationRenderer.editTrailingSignZ(new BigDecimal("1234567890.12"));

        assertThat(edited)
                .as("the narrowed suppressed edit holds 13 characters")
                .hasSize(EDITED_WIDTH);
        assertThat(edited.substring(0, INTEGER_POSITIONS))
                .as("the low nine integer digits survive")
                .isEqualTo("234567890");
        assertThat(edited)
                .as("the tenth integer digit reaches no output")
                .doesNotContain("1234567890");
        assertThat(NotificationRenderer.editTrailingSignZ(new BigDecimal("1000000000.00")))
                .as("an amount of one billion narrows to zero and blanks every digit position")
                .isEqualTo("         .00 ");
        assertThat(NotificationRenderer.editTrailingSignZ(new BigDecimal("1.999")))
                .as("the third decimal digit falls away in the suppressed form too")
                .isEqualTo("        1.99 ");
    }

    /**
     * Asserts that neither edit follows the default locale. The fields at
     * app/cbl/CBSTM03A.CBL:L113 and app/cbl/CBSTM03A.CBL:L137 fix a point as the decimal
     * separator, no grouping separator and a trailing sign, whatever locale the Java virtual
     * machine (JVM) runs under.
     */
    @Test
    @DisplayName("Neither edit follows the default locale")
    void neitherEditFollowsTheDefaultLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);

            String zeroFilled = NotificationRenderer.editTrailingSign9(new BigDecimal("-1234.56"));
            String suppressed = NotificationRenderer.editTrailingSignZ(new BigDecimal("1234.56"));
            String platformFormat = String.format(Locale.getDefault(), "%,.2f",
                    new BigDecimal("-1234.56"));

            assertThat(zeroFilled)
                    .as("the zero-filled edit holds 13 characters under a comma-decimal locale")
                    .hasSize(EDITED_WIDTH);
            assertThat(suppressed)
                    .as("the suppressed edit holds 13 characters under the same locale")
                    .hasSize(EDITED_WIDTH);
            assertThat(zeroFilled)
                    .as("the zero-filled edit keeps its point, its zeros and its trailing minus")
                    .isEqualTo("000001234.56-");
            assertThat(suppressed)
                    .as("the suppressed edit keeps its point and its blank positions")
                    .isEqualTo("     1234.56 ");
            assertThat(zeroFilled)
                    .as("no grouping separator reaches a fixed-width field")
                    .doesNotContain(",");
            assertThat(suppressed)
                    .as("no grouping separator reaches the suppressed field either")
                    .doesNotContain(",");
            assertThat(platformFormat)
                    .as("a locale-sensitive formatter groups digits and leads with the sign")
                    .contains(",")
                    .startsWith("-");
            assertThat(zeroFilled)
                    .as("a substituted locale-sensitive formatter fails these assertions")
                    .isNotEqualTo(platformFormat);
        } finally {
            Locale.setDefault(original);
        }
    }

    /**
     * Asserts that the suppressed edit refuses a null amount. The
     * {@code MOVE TRNX-AMT TO ST-TRANAMT} at app/cbl/CBSTM03A.CBL:L678 supplies a numeric field.
     */
    @Test
    @DisplayName("The suppressed edit refuses a null amount")
    void editTrailingSignZRefusesANullAmount() {
        assertThatThrownBy(() -> NotificationRenderer.editTrailingSignZ(null))
                .as("an amount is required in the suppressed form too")
                .isInstanceOf(NullPointerException.class);
    }


    // assembleName. Reproduces the STRING at app/cbl/CBSTM03A.CBL:L462-L469.

    /**
     * Asserts the three-part join. The {@code STRING} at app/cbl/CBSTM03A.CBL:L462-L469 follows each
     * component with one space through the {@code ' ' DELIMITED BY SIZE} inserts at
     * app/cbl/CBSTM03A.CBL:L463, app/cbl/CBSTM03A.CBL:L465 and app/cbl/CBSTM03A.CBL:L467, so a
     * space follows the last component too.
     */
    @Test
    @DisplayName("Three name components join with single spaces and one trailing space")
    void assembleNameJoinsThreeComponentsWithSingleSpaces() {
        String assembled = NotificationRenderer.assembleName("ALPHA", "BETA", "GAMMA");

        assertThat(assembled)
                .as("the assembled name holds the width of ST-NAME at app/cbl/CBSTM03A.CBL:L91")
                .hasSize(NotificationRenderer.ST_NAME_WIDTH);
        assertThat(assembled)
                .as("the three components join with single spaces and pad to the field width")
                .isEqualTo("ALPHA BETA GAMMA "
                        + " ".repeat(NotificationRenderer.ST_NAME_WIDTH - 17));
        assertThat(assembled.charAt(5))
                .as("one space separates the first component from the second")
                .isEqualTo(' ');
        assertThat(assembled.charAt(10))
                .as("one space separates the second component from the third")
                .isEqualTo(' ');
        assertThat(assembled.charAt(16))
                .as("the third insert places one space after the last component")
                .isEqualTo(' ');
        assertThat(assembled.substring(17))
                .as("the unused tail stays blank, as INITIALIZE at app/cbl/CBSTM03A.CBL:L459"
                        + " leaves it")
                .isBlank();
    }

    /**
     * Asserts the width invariant of the assembled name against
     * {@code ST-NAME PIC X(75)} at app/cbl/CBSTM03A.CBL:L91.
     */
    @Test
    @DisplayName("Every assembled name holds exactly 75 characters")
    void assembleNameHoldsSeventyFiveCharactersForEveryInput() {
        String full = "F".repeat(NAME_COMPONENT_WIDTH);
        List<List<String>> componentSets = List.of(
                List.of("ALPHA", "BETA", "GAMMA"),
                List.of("A", "B", "C"),
                List.of("", "", ""),
                List.of(" ", "  ", "   "),
                List.of(full, full, full));

        for (List<String> components : componentSets) {
            assertThat(NotificationRenderer.assembleName(
                    components.get(0), components.get(1), components.get(2)))
                    .as("the name assembled from %s holds 75 characters", components)
                    .hasSize(NotificationRenderer.ST_NAME_WIDTH);
        }
        assertThat(NotificationRenderer.assembleName(null, null, null))
                .as("three absent components leave an all-spaces field of 75 characters")
                .hasSize(NotificationRenderer.ST_NAME_WIDTH)
                .isBlank();
    }

    /**
     * Asserts that {@code DELIMITED BY ' '} stops each component at its first internal space. The
     * three operands at app/cbl/CBSTM03A.CBL:L462, app/cbl/CBSTM03A.CBL:L464 and
     * app/cbl/CBSTM03A.CBL:L466 each carry that phrase.
     */
    @Test
    @DisplayName("A component holding two words contributes its first word alone")
    void assembleNameStopsEachComponentAtItsFirstInternalSpace() {
        String assembled = NotificationRenderer.assembleName("ANNE", "MARY JANE", "SMITH");

        assertThat(assembled)
                .as("the truncated join still holds 75 characters")
                .hasSize(NotificationRenderer.ST_NAME_WIDTH);
        assertThat(assembled)
                .as("MARY JANE contributes MARY alone")
                .isEqualTo("ANNE MARY SMITH "
                        + " ".repeat(NotificationRenderer.ST_NAME_WIDTH - 16));
        assertThat(assembled)
                .as("the characters after the internal space reach no output")
                .doesNotContain("JANE");
        assertThat(NotificationRenderer.assembleName("ANNE MARIE", "J", "SMITH"))
                .as("a two-word first name contributes its first word alone")
                .isEqualTo("ANNE J SMITH "
                        + " ".repeat(NotificationRenderer.ST_NAME_WIDTH - 13));
    }

    /**
     * Asserts that an all-spaces component contributes no characters while its insert still fires,
     * leaving two adjacent spaces. The insert at app/cbl/CBSTM03A.CBL:L465 follows
     * {@code CUST-MIDDLE-NAME PIC X(25)} at app/cpy/CUSTREC.cpy:L7 whatever that field holds.
     */
    @Test
    @DisplayName("An all-spaces middle name leaves two adjacent spaces")
    void assembleNameLeavesTwoSpacesWhereAComponentIsAllSpaces() {
        String blankMiddle = " ".repeat(NAME_COMPONENT_WIDTH);
        String assembled = NotificationRenderer.assembleName("ALPHA", blankMiddle, "GAMMA");

        assertThat(assembled)
                .as("the join still holds 75 characters")
                .hasSize(NotificationRenderer.ST_NAME_WIDTH);
        assertThat(assembled)
                .as("two spaces separate the two surviving components")
                .isEqualTo("ALPHA  GAMMA "
                        + " ".repeat(NotificationRenderer.ST_NAME_WIDTH - 13));
        assertThat(assembled.substring(5, 7))
                .as("the separating insert and the empty component together render two spaces")
                .isEqualTo("  ");
        assertThat(assembled.charAt(7))
                .as("the third component starts after the two spaces")
                .isEqualTo('G');
        assertThat(NotificationRenderer.assembleName("ALPHA", null, "GAMMA"))
                .as("an absent middle name leaves the same two spaces")
                .isEqualTo(assembled);
    }

    /**
     * Asserts the overflow the two widths produce. Three components of
     * {@code PIC X(25)} at app/cpy/CUSTREC.cpy:L6-L8 emit 78 characters with their three inserts,
     * and {@code ST-NAME PIC X(75)} at app/cbl/CBSTM03A.CBL:L91 holds 75, so the last three
     * characters fall away.
     */
    @Test
    @DisplayName("Three full name components lose the last three of 78 characters")
    void assembleNameLosesTheLastThreeOfSeventyEightCharacters() {
        String first = "F".repeat(NAME_COMPONENT_WIDTH);
        String middle = "M".repeat(NAME_COMPONENT_WIDTH);
        String last = "L".repeat(NAME_COMPONENT_WIDTH);
        int emitted = 3 * NAME_COMPONENT_WIDTH + 3;
        String assembled = NotificationRenderer.assembleName(first, middle, last);

        assertThat(emitted)
                .as("three components and three inserts emit 78 characters")
                .isEqualTo(78);
        assertThat(emitted - NotificationRenderer.ST_NAME_WIDTH)
                .as("the field holds 75, so three characters fall away")
                .isEqualTo(3);
        assertThat(assembled)
                .as("the assembled name still holds 75 characters")
                .hasSize(NotificationRenderer.ST_NAME_WIDTH);
        assertThat(assembled)
                .as("the first two components survive whole and the third loses two characters")
                .isEqualTo(first + " " + middle + " " + "L".repeat(NAME_COMPONENT_WIDTH - 2));
        assertThat(assembled.chars().filter(character -> character == 'L').count())
                .as("23 of the 25 last-name characters reach the field")
                .isEqualTo(NAME_COMPONENT_WIDTH - 2);
        assertThat(assembled)
                .as("the trailing insert falls away with them")
                .doesNotEndWith(" ");
    }

    // assembleAddress3. Reproduces the STRING at app/cbl/CBSTM03A.CBL:L472-L481.

    /**
     * Asserts the four-part join. The {@code STRING} at app/cbl/CBSTM03A.CBL:L472-L481 follows each
     * of its four components with one space through the inserts at app/cbl/CBSTM03A.CBL:L473,
     * app/cbl/CBSTM03A.CBL:L475, app/cbl/CBSTM03A.CBL:L477 and app/cbl/CBSTM03A.CBL:L479.
     */
    @Test
    @DisplayName("Four address components join with single spaces and one trailing space")
    void assembleAddress3JoinsFourComponentsWithSingleSpaces() {
        String assembled = NotificationRenderer.assembleAddress3("500", "NY", "USA", "12345");

        assertThat(assembled)
                .as("the assembled line holds the width of ST-ADD3 at app/cbl/CBSTM03A.CBL:L100")
                .hasSize(NotificationRenderer.ST_ADD3_WIDTH);
        assertThat(assembled)
                .as("the four components join with single spaces and pad to the field width")
                .isEqualTo("500 NY USA 12345 "
                        + " ".repeat(NotificationRenderer.ST_ADD3_WIDTH - 17));
        assertThat(assembled.charAt(16))
                .as("the fourth insert places one space after the postal code")
                .isEqualTo(' ');
        assertThat(assembled.substring(17))
                .as("the unused tail stays blank")
                .isBlank();
    }

    /**
     * Asserts the width invariant of the assembled address line against
     * {@code ST-ADD3 PIC X(80)} at app/cbl/CBSTM03A.CBL:L100.
     */
    @Test
    @DisplayName("Every assembled address line holds exactly 80 characters")
    void assembleAddress3HoldsEightyCharactersForEveryInput() {
        String overLongLine = "A".repeat(NotificationRenderer.ST_ADD3_WIDTH + 10);
        List<List<String>> componentSets = List.of(
                List.of("500", "NY", "USA", "12345"),
                List.of("A", "B", "C", "D"),
                List.of("", "", "", ""),
                List.of(" ", "  ", "   ", "    "),
                List.of(overLongLine, "NY", "USA", "12345"));

        for (List<String> components : componentSets) {
            assertThat(NotificationRenderer.assembleAddress3(components.get(0), components.get(1),
                    components.get(2), components.get(3)))
                    .as("the line assembled from %s holds 80 characters", components)
                    .hasSize(NotificationRenderer.ST_ADD3_WIDTH);
        }
        assertThat(NotificationRenderer.assembleAddress3(null, null, null, null))
                .as("four absent components leave an all-spaces field of 80 characters")
                .hasSize(NotificationRenderer.ST_ADD3_WIDTH)
                .isBlank();
    }

    /**
     * Asserts that the widest input survives whole. The four components at
     * app/cpy/CUSTREC.cpy:L11-L14 measure 50, 2, 3 and 10, and their four inserts bring the
     * emission to 69 against the 80 of {@code ST-ADD3} at app/cbl/CBSTM03A.CBL:L100.
     */
    @Test
    @DisplayName("The widest address input survives whole inside 80 characters")
    void assembleAddress3KeepsTheWidestInputWhole() {
        String line = "A".repeat(ADDRESS_LINE_WIDTH);
        String state = "N".repeat(STATE_CODE_WIDTH);
        String country = "U".repeat(COUNTRY_CODE_WIDTH);
        String postalCode = "1234567890";
        int widestEmission = ADDRESS_LINE_WIDTH + STATE_CODE_WIDTH + COUNTRY_CODE_WIDTH
                + POSTAL_CODE_WIDTH + 4;
        String assembled = NotificationRenderer.assembleAddress3(line, state, country, postalCode);

        assertThat(postalCode)
                .as("the postal code fills CUST-ADDR-ZIP PIC X(10) at app/cpy/CUSTREC.cpy:L14")
                .hasSize(POSTAL_CODE_WIDTH);
        assertThat(widestEmission)
                .as("the four components and their four inserts emit 69 characters")
                .isEqualTo(69);
        assertThat(widestEmission)
                .as("69 characters fit the 80 of app/cbl/CBSTM03A.CBL:L100, so nothing falls away")
                .isLessThanOrEqualTo(NotificationRenderer.ST_ADD3_WIDTH);
        assertThat(assembled)
                .as("the assembled line holds 80 characters")
                .hasSize(NotificationRenderer.ST_ADD3_WIDTH);
        assertThat(assembled)
                .as("all four components survive and 11 spaces fill the tail")
                .isEqualTo(line + " " + state + " " + country + " " + postalCode + " "
                        + " ".repeat(NotificationRenderer.ST_ADD3_WIDTH - widestEmission));
        assertThat(assembled)
                .as("the widest address line reaches the field whole")
                .contains(line);
    }

    /**
     * Asserts that each of the four operands at app/cbl/CBSTM03A.CBL:L472,
     * app/cbl/CBSTM03A.CBL:L474, app/cbl/CBSTM03A.CBL:L476 and app/cbl/CBSTM03A.CBL:L478 stops at
     * its first internal space, and that an all-spaces component leaves two adjacent spaces.
     */
    @Test
    @DisplayName("Each address component stops at its first internal space")
    void assembleAddress3StopsEachComponentAtItsFirstInternalSpace() {
        String assembled = NotificationRenderer.assembleAddress3("500 MAIN ST", "NY", "USA",
                "12345 6789");

        assertThat(assembled)
                .as("the truncated join still holds 80 characters")
                .hasSize(NotificationRenderer.ST_ADD3_WIDTH);
        assertThat(assembled)
                .as("the city line contributes 500 and the postal code contributes 12345")
                .isEqualTo("500 NY USA 12345 "
                        + " ".repeat(NotificationRenderer.ST_ADD3_WIDTH - 17));
        assertThat(assembled)
                .as("the characters after each internal space reach no output")
                .doesNotContain("MAIN")
                .doesNotContain("6789");

        String blankState = " ".repeat(STATE_CODE_WIDTH);
        String withBlankState = NotificationRenderer.assembleAddress3("500", blankState, "USA",
                "12345");

        assertThat(withBlankState)
                .as("the join over an all-spaces component holds 80 characters")
                .hasSize(NotificationRenderer.ST_ADD3_WIDTH);
        assertThat(withBlankState)
                .as("an all-spaces state code leaves two adjacent spaces")
                .isEqualTo("500  USA 12345 "
                        + " ".repeat(NotificationRenderer.ST_ADD3_WIDTH - 15));
        assertThat(withBlankState.substring(3, 5))
                .as("the empty component and its insert together render two spaces")
                .isEqualTo("  ");
    }


    // escapeHtmlText. Guards the markup records of app/cbl/CBSTM03A.CBL:L560 and L622.

    /**
     * Asserts the five replacements. The markup path of app/cbl/CBSTM03A.CBL:L560 and
     * app/cbl/CBSTM03A.CBL:L622 carries cardholder values into markup records, and a normal field
     * of letters, digits and spaces passes through at its own width.
     */
    @Test
    @DisplayName("The five markup characters become entities and a normal field is unchanged")
    void escapeHtmlTextReplacesTheFiveMarkupCharacters() {
        assertThat(NotificationRenderer.escapeHtmlText("A & B"))
                .as("an ampersand becomes an entity")
                .isEqualTo("A &amp; B");
        assertThat(NotificationRenderer.escapeHtmlText("<p>"))
                .as("both angle brackets become entities")
                .isEqualTo("&lt;p&gt;");
        assertThat(NotificationRenderer.escapeHtmlText("\"NY\""))
                .as("a double quotation mark becomes an entity")
                .isEqualTo("&quot;NY&quot;");
        assertThat(NotificationRenderer.escapeHtmlText("O'HARA"))
                .as("an apostrophe becomes a numeric entity")
                .isEqualTo("O&#39;HARA");
        assertThat(NotificationRenderer.escapeHtmlText("&lt;"))
                .as("the ampersand replacement runs first, so no entity is escaped twice")
                .isEqualTo("&amp;lt;");
        assertThat(NotificationRenderer.escapeHtmlText(null))
                .as("a null value escapes to the empty string")
                .isEmpty();

        String normalField = NotificationRenderer.pic("ALPHA 742", 20);

        assertThat(NotificationRenderer.escapeHtmlText(normalField))
                .as("a field of letters, digits and spaces passes through unchanged")
                .isEqualTo(normalField);
        assertThat(NotificationRenderer.escapeHtmlText(normalField))
                .as("escaping leaves the width of a normal field alone")
                .hasSize(20);
    }

    // The nested types: the format enumeration and the two payload records.

    /**
     * Asserts that the enumeration carries one constant for each output file
     * app/cbl/CBSTM03A.CBL:L44-L47 declares, and no third.
     */
    @Test
    @DisplayName("The format enumeration carries exactly two constants")
    void theFormatEnumerationCarriesExactlyTwoConstants() {
        NotificationRenderer.RenderedFormat[] formats =
                NotificationRenderer.RenderedFormat.values();

        assertThat(formats)
                .as("app/cbl/CBSTM03A.CBL:L44-L47 declares two output files")
                .hasSize(2);
        assertThat(Arrays.stream(formats).map(Enum::name).toList())
                .as("the constants name the fixed-width text file and the markup file, in order")
                .isEqualTo(List.of("PLAIN_TEXT", "HTML"));
        assertThat(NotificationRenderer.RenderedFormat.valueOf("PLAIN_TEXT"))
                .as("FD-STMTFILE-REC PIC X(80) at app/cbl/CBSTM03A.CBL:L45 maps to the text form")
                .isSameAs(NotificationRenderer.RenderedFormat.PLAIN_TEXT);
        assertThat(NotificationRenderer.RenderedFormat.valueOf("HTML"))
                .as("FD-HTMLFILE-REC PIC X(100) at app/cbl/CBSTM03A.CBL:L47 maps to the markup"
                        + " form")
                .isSameAs(NotificationRenderer.RenderedFormat.HTML);
        assertThatThrownBy(() -> NotificationRenderer.RenderedFormat.valueOf("CSV"))
                .as("a format the source writes to no file resolves to nothing")
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Asserts the seven components of the cardholder payload, each at the width its source field
     * declares. Paragraph {@code 5000-CREATE-STATEMENT} at app/cbl/CBSTM03A.CBL:L458-L504 fills all
     * seven once, and both output paths read them back.
     */
    @Test
    @DisplayName("The cardholder payload carries seven components at their source widths")
    void theCardholderContextCarriesSevenComponentsAtTheirSourceWidths() {
        NotificationRenderer.CardholderContext context = cardholderContext();

        assertThat(NotificationRenderer.CardholderContext.class.getRecordComponents())
                .as("the payload declares seven components")
                .hasSize(7);
        assertThat(Arrays.stream(NotificationRenderer.CardholderContext.class
                        .getRecordComponents()).map(RecordComponent::getName).toList())
                .as("the components name the fields app/cbl/CBSTM03A.CBL:L462-L485 fills")
                .isEqualTo(List.of("assembledName", "addressLine1", "addressLine2", "addressLine3",
                        "accountId", "editedCurrentBalance", "ficoScore"));
        assertThat(context.assembledName())
                .as("the assembled name holds ST-NAME PIC X(75) at app/cbl/CBSTM03A.CBL:L91")
                .hasSize(NotificationRenderer.ST_NAME_WIDTH);
        assertThat(context.addressLine1())
                .as("the first address line holds ST-ADD1 PIC X(50) at app/cbl/CBSTM03A.CBL:L94")
                .hasSize(NotificationRenderer.ST_ADD1_WIDTH);
        assertThat(context.addressLine2())
                .as("the second address line holds ST-ADD2 PIC X(50) at app/cbl/CBSTM03A.CBL:L97")
                .hasSize(NotificationRenderer.ST_ADD2_WIDTH);
        assertThat(context.addressLine3())
                .as("the third address line holds ST-ADD3 PIC X(80) at app/cbl/CBSTM03A.CBL:L100")
                .hasSize(NotificationRenderer.ST_ADD3_WIDTH);
        assertThat(context.accountId())
                .as("the account identifier holds the field app/cbl/CBSTM03A.CBL:L483 fills")
                .hasSize(NotificationRenderer.ST_ACCT_ID_WIDTH);
        assertThat(context.editedCurrentBalance())
                .as("the balance holds ST-CURR-BAL PIC 9(9).99- at app/cbl/CBSTM03A.CBL:L113")
                .hasSize(EDITED_WIDTH);
        assertThat(context.ficoScore())
                .as("the credit score holds ST-FICO-SCORE PIC X(20) at app/cbl/CBSTM03A.CBL:L118")
                .hasSize(NotificationRenderer.ST_FICO_SCORE_WIDTH);
        assertThat(context.editedCurrentBalance())
                .as("the edited balance reaches the payload unchanged")
                .isEqualTo("000000042.50 ");
        assertThat(context.accountId().substring(0, ACCOUNT_ID_DIGITS))
                .as("the eleven digits of app/cpy/CVACT01Y.cpy:L5 keep their leading zeros")
                .isEqualTo("00000000011");
    }

    /**
     * Asserts that the cardholder payload permits no mutation. Both output paths read the fields
     * app/cbl/CBSTM03A.CBL:L458-L504 assembles, and neither writes one back.
     */
    @Test
    @DisplayName("The cardholder payload exposes no mutator and holds only final fields")
    void theCardholderContextPermitsNoMutation() {
        Class<?> payload = NotificationRenderer.CardholderContext.class;
        NotificationRenderer.CardholderContext context = cardholderContext();

        assertThat(payload.isRecord())
                .as("the payload of app/cbl/CBSTM03A.CBL:L458-L504 is a record")
                .isTrue();
        for (Field field : payload.getDeclaredFields()) {
            assertThat(Modifier.isFinal(field.getModifiers()))
                    .as("the field %s is final", field.getName())
                    .isTrue();
            assertThat(Modifier.isPrivate(field.getModifiers()))
                    .as("the field %s is private", field.getName())
                    .isTrue();
        }
        assertThat(Arrays.stream(payload.getDeclaredMethods())
                        .map(Method::getName)
                        .filter(name -> name.startsWith("set"))
                        .toList())
                .as("no accessor writes a component")
                .isEmpty();
        assertThat(context.assembledName())
                .as("a repeated read returns the same name")
                .isEqualTo(context.assembledName());
        assertThat(cardholderContext())
                .as("two payloads built from the same components are equal")
                .isEqualTo(context);
    }

    /**
     * Asserts the three components of one detail row at the widths the source fixes.
     * {@code TRNX-ID PIC X(16)} sits at app/cpy/COSTM01.CPY:L23, the
     * {@code MOVE TRNX-DESC TO ST-TRANDT} at app/cbl/CBSTM03A.CBL:L677 renders 49 characters, and
     * {@code ST-TRANAMT PIC Z(9).99-} at app/cbl/CBSTM03A.CBL:L137 renders 13.
     */
    @Test
    @DisplayName("A detail row carries three components at 16, 49 and 13 characters")
    void theTransactionRowCarriesItsThreeComponentsAtTheirSourceWidths() {
        String storedTransactionId = "TRAN000000000001";
        String storedDescription = "D".repeat(STORED_DESCRIPTION_WIDTH);
        NotificationRenderer.TransactionRow row = new NotificationRenderer.TransactionRow(
                storedTransactionId, storedDescription,
                NotificationRenderer.editTrailingSignZ(new BigDecimal("42.50")));

        assertThat(storedTransactionId)
                .as("the stored identifier fills app/cpy/COSTM01.CPY:L23")
                .hasSize(STORED_TRANSACTION_ID_WIDTH);
        assertThat(NotificationRenderer.TransactionRow.class.getRecordComponents())
                .as("the row declares three components")
                .hasSize(3);
        assertThat(Arrays.stream(NotificationRenderer.TransactionRow.class.getRecordComponents())
                        .map(RecordComponent::getName).toList())
                .as("the components name the fields app/cbl/CBSTM03A.CBL:L676-L678 fills")
                .isEqualTo(List.of("transactionId", "description", "editedAmount"));
        assertThat(row.transactionId())
                .as("the identifier holds ST-TRANID PIC X(16) at app/cbl/CBSTM03A.CBL:L133")
                .hasSize(STORED_TRANSACTION_ID_WIDTH);
        assertThat(row.transactionId())
                .as("an identifier already at 16 characters passes through unchanged")
                .isEqualTo(storedTransactionId);
        assertThat(row.description())
                .as("the description holds ST-TRANDT PIC X(49) at app/cbl/CBSTM03A.CBL:L135")
                .hasSize(NotificationRenderer.ST_TRANDT_WIDTH);
        assertThat(row.description())
                .as("the move at app/cbl/CBSTM03A.CBL:L677 keeps the leading 49 characters")
                .isEqualTo("D".repeat(NotificationRenderer.ST_TRANDT_WIDTH));
        assertThat(row.editedAmount())
                .as("the amount holds the 13 characters of app/cbl/CBSTM03A.CBL:L137")
                .hasSize(EDITED_WIDTH);
        assertThat(row.editedAmount())
                .as("the edited amount reaches the row unchanged")
                .isEqualTo("       42.50 ");
    }

    /**
     * Asserts that a diagnostic rendering of either payload carries no cardholder value. Paragraph
     * app/cbl/CBSTM03A.CBL:L458-L504 assembles a name, an address, a balance and a credit score,
     * and each stays inside the rendered statement.
     */
    @Test
    @DisplayName("A diagnostic rendering of either payload carries no cardholder value")
    void neitherPayloadRendersACardholderValue() {
        NotificationRenderer.CardholderContext context = cardholderContext();
        NotificationRenderer.TransactionRow row = new NotificationRenderer.TransactionRow(
                "TRAN000000000001", "D".repeat(STORED_DESCRIPTION_WIDTH),
                NotificationRenderer.editTrailingSignZ(new BigDecimal("42.50")));

        assertThat(NotificationRenderer.REDACTED)
                .as("the declared placeholder names no cardholder value")
                .isEqualTo("<redacted>");
        assertThat(context.toString())
                .as("the name, the balance and the credit score of app/cbl/CBSTM03A.CBL:L462-L485"
                        + " stay out of a diagnostic rendering")
                .doesNotContain("ALPHA")
                .doesNotContain("42.50")
                .doesNotContain("742");
        assertThat(row.toString())
                .as("the row names itself and hides the description and the amount")
                .contains("TRAN000000000001")
                .doesNotContain("DDD")
                .doesNotContain("42.50");
    }

    /**
     * Asserts that two implementations report distinct formats. app/cbl/CBSTM03A.CBL:L44-L47
     * declares one output file for each, and the reported format is what a caller holding both
     * compares. No assertion here reads the content of a rendered alert.
     */
    @Test
    @DisplayName("Two implementations report distinct formats")
    void theTwoImplementationsReportDistinctFormats() {
        NotificationRenderer text =
                new FormatReportingRenderer(NotificationRenderer.RenderedFormat.PLAIN_TEXT);
        NotificationRenderer markup =
                new FormatReportingRenderer(NotificationRenderer.RenderedFormat.HTML);

        assertThat(text.format())
                .as("the text implementation reports the format of app/cbl/CBSTM03A.CBL:L45")
                .isSameAs(NotificationRenderer.RenderedFormat.PLAIN_TEXT);
        assertThat(markup.format())
                .as("the markup implementation reports the format of app/cbl/CBSTM03A.CBL:L47")
                .isSameAs(NotificationRenderer.RenderedFormat.HTML);
        assertThat(text.format())
                .as("the two reported formats differ, so a caller selects one of them")
                .isNotEqualTo(markup.format());
        assertThat(text.renderStatementAlert(cardholderContext(), List.of(), BigDecimal.ZERO))
                .as("the statement operation reaches an implementation")
                .isNotBlank();
        assertThat(markup.renderFraudAlert(cardholderContext(), "TRAN000000000001", 90,
                List.of("VELOCITY")))
                .as("the fraud operation reaches an implementation")
                .isNotBlank();
    }

    /**
     * Builds one cardholder payload from the helpers, matching the field order paragraph
     * {@code 5000-CREATE-STATEMENT} fills at app/cbl/CBSTM03A.CBL:L462-L485.
     *
     * @return a payload whose seven components each sit at their source width
     */
    private static NotificationRenderer.CardholderContext cardholderContext() {
        return new NotificationRenderer.CardholderContext(
                NotificationRenderer.assembleName("ALPHA", "BETA", "GAMMA"),
                "500 MAIN ST",
                "SUITE 2",
                NotificationRenderer.assembleAddress3("500", "NY", "USA", "12345"),
                "00000000011",
                NotificationRenderer.editTrailingSign9(new BigDecimal("42.50")),
                "742");
    }

    /**
     * An implementation that reports one format and renders no statement content. The two
     * production renderers are separate files, and app/cbl/CBSTM03A.CBL:L44-L47 declares one output
     * file for each format they carry.
     *
     * @param reportedFormat the format this implementation reports
     */
    private record FormatReportingRenderer(NotificationRenderer.RenderedFormat reportedFormat)
            implements NotificationRenderer {

        @Override
        public RenderedFormat format() {
            return reportedFormat;
        }

        @Override
        public String renderStatementAlert(CardholderContext context, List<TransactionRow> rows,
                                           BigDecimal total) {
            return "statement " + reportedFormat.name() + " rows=" + rows.size()
                    + " total=" + total.toPlainString();
        }

        @Override
        public String renderFraudAlert(CardholderContext context, String transactionId,
                                       int riskScore, List<String> triggeredRules) {
            return "fraud " + reportedFormat.name() + " tran=" + transactionId
                    + " score=" + riskScore + " rules=" + triggeredRules.size();
        }
    }

}
