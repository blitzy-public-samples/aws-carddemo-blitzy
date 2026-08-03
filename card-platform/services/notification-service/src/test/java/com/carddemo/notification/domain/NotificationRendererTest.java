package com.carddemo.notification.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link NotificationRenderer}, its fixed-width helpers, its two nested payload records
 * and its format enumeration.
 *
 * <p>Every expected string below is typed as a literal, computed by hand from the Picture clause it
 * reproduces. No expected value is produced by calling the helper under test, and no expected value
 * is read from a fixture, so a helper that changed its padding, its truncation or its sign position
 * fails here.</p>
 *
 * <p>The widths come from the statement lines of {@code app/cbl/CBSTM03A.CBL}:
 * {@code ST-NAME PIC X(75)} at L91, {@code ST-ADD1 PIC X(50)} at L94,
 * {@code ST-ADD2 PIC X(50)} at L97, {@code ST-ADD3 PIC X(80)} at L100,
 * {@code ST-ACCT-ID PIC X(20)} at L109, {@code ST-CURR-BAL PIC 9(9).99-} at L113,
 * {@code ST-FICO-SCORE PIC X(20)} at L118, {@code ST-TRANID PIC X(16)} at L133,
 * {@code ST-TRANDT PIC X(49)} at L135, {@code ST-TRANAMT PIC Z(9).99-} at L137 and
 * {@code ST-TOTAL-TRAMT PIC Z(9).99-} at L142.</p>
 *
 * <p>Two amount forms differ in one respect only. A {@code 9} digit position renders a leading zero
 * as a zero and a {@code Z} digit position renders it as a space. Both hold nine digit positions, a
 * decimal point, two decimal digits and one trailing sign position.</p>
 *
 * <p>The two abstract operations render one alert each, and this module declares no implementation
 * of them. A minimal implementation below exercises the contract shape and the format
 * discriminator, and asserts nothing about the content of a rendered alert, which belongs to the
 * two renderer implementations.</p>
 *
 */
class NotificationRendererTest {

    /** Nine digit positions plus a decimal point plus two decimals plus one sign position. */
    private static final int EDITED_WIDTH = 13;

    /** Integer digit positions an edited amount holds. */
    private static final int INTEGER_POSITIONS = 9;

    /** Width of {@code FD-STMTFILE-REC PIC X(80)} at {@code app/cbl/CBSTM03A.CBL:L45}. */
    private static final int TEXT_RECORD_WIDTH = 80;

    /** Width of {@code FD-HTMLFILE-REC PIC X(100)} at {@code app/cbl/CBSTM03A.CBL:L47}. */
    private static final int MARKUP_RECORD_WIDTH = 100;

    /** Width of {@code TRNX-DESC PIC X(100)} at {@code app/cpy/COSTM01.CPY:L28}. */
    private static final int STORED_DESCRIPTION_WIDTH = 100;

    // Constants against the Picture clauses they reproduce.

    /**
     * Asserts every published width against the Picture clause it reproduces. Each expected value
     * is typed here from {@code app/cbl/CBSTM03A.CBL} rather than read from the interface.
     */
    @Test
    void everyPublishedWidthMatchesItsPictureClause() {
        assertEquals(75, NotificationRenderer.ST_NAME_WIDTH,
                "ST-NAME PIC X(75) at app/cbl/CBSTM03A.CBL:L91");
        assertEquals(50, NotificationRenderer.ST_ADD1_WIDTH,
                "ST-ADD1 PIC X(50) at app/cbl/CBSTM03A.CBL:L94");
        assertEquals(50, NotificationRenderer.ST_ADD2_WIDTH,
                "ST-ADD2 PIC X(50) at app/cbl/CBSTM03A.CBL:L97");
        assertEquals(80, NotificationRenderer.ST_ADD3_WIDTH,
                "ST-ADD3 PIC X(80) at app/cbl/CBSTM03A.CBL:L100");
        assertEquals(20, NotificationRenderer.ST_ACCT_ID_WIDTH,
                "ST-ACCT-ID PIC X(20) at app/cbl/CBSTM03A.CBL:L109");
        assertEquals(20, NotificationRenderer.ST_FICO_SCORE_WIDTH,
                "ST-FICO-SCORE PIC X(20) at app/cbl/CBSTM03A.CBL:L118");
        assertEquals(16, NotificationRenderer.ST_TRANID_WIDTH,
                "ST-TRANID PIC X(16) at app/cbl/CBSTM03A.CBL:L133");
        assertEquals(49, NotificationRenderer.ST_TRANDT_WIDTH,
                "ST-TRANDT PIC X(49) at app/cbl/CBSTM03A.CBL:L135");
        assertEquals(EDITED_WIDTH, NotificationRenderer.EDITED_AMOUNT_WIDTH,
                "an edited amount holds " + INTEGER_POSITIONS
                        + " digit positions, a decimal point, two decimals and a sign position");
        assertEquals("\n", NotificationRenderer.LINE_SEPARATOR,
                "records are joined with a line feed and not with the host separator");
    }

    /**
     * Asserts that a stored description is 51 characters wider than the field that renders it, so
     * the loss the source accepts at {@code app/cbl/CBSTM03A.CBL:L677} is 51 characters exactly.
     */
    @Test
    void theRenderedDescriptionIsFiftyOneCharactersNarrowerThanTheStoredColumn() {
        assertEquals(51, STORED_DESCRIPTION_WIDTH - NotificationRenderer.ST_TRANDT_WIDTH,
                "TRNX-DESC PIC X(100) at app/cpy/COSTM01.CPY:L28 loses 51 characters when "
                        + "app/cbl/CBSTM03A.CBL:L677 moves it into ST-TRANDT PIC X(49)");
    }

    // pic. Reproduces a MOVE into a PIC X(n) field.

    /**
     * Asserts that {@code pic} pads a short value on the right and truncates a long one, the two
     * behaviours of a COBOL {@code MOVE} into an alphanumeric field.
     */
    @Test
    void picPadsAShortValueAndTruncatesALongOne() {
        assertEquals("AB   ", NotificationRenderer.pic("AB", 5),
                "a value of width 2 gains three trailing spaces at width 5");
        assertEquals("ABC", NotificationRenderer.pic("ABC", 3),
                "a value already at the width passes through unchanged");
        assertEquals("ABC", NotificationRenderer.pic("ABCDEF", 3),
                "a value of width 6 loses its tail at width 3");
        assertEquals("A", NotificationRenderer.pic("AB", 1),
                "a value of width 2 keeps only its first character at width 1");
        assertEquals("", NotificationRenderer.pic("ABC", 0),
                "a width of zero renders the empty string");
    }

    /**
     * Asserts that a null value and a value of only spaces both render as an all-spaces field of
     * the requested width. Nine fields of the statement read model are absent from the
     * {@code TransactionPosted} event, so a renderer receives space-filled values in normal use.
     */
    @Test
    void picRendersNullAndSpacesAsAnAllSpacesField() {
        assertEquals("    ", NotificationRenderer.pic(null, 4),
                "a null value renders as four spaces and raises no exception");
        assertEquals("", NotificationRenderer.pic(null, 0),
                "a null value at width zero renders the empty string");
        assertEquals("   ", NotificationRenderer.pic("", 3),
                "an empty value renders as three spaces");
        assertEquals("  ", NotificationRenderer.pic("  ", 2),
                "a value of only spaces passes through at its own width");
        assertEquals("  ", NotificationRenderer.pic("    ", 2),
                "a value of only spaces truncates like any other value");
    }

    /**
     * Asserts that every width from zero to the widest field renders exactly that many characters,
     * for a short value, a long value and a null. The width invariant is what every fixed-width
     * record depends on.
     */
    @Test
    void picRendersExactlyTheRequestedWidthForEveryInput() {
        String shortValue = "AB";
        String longValue = "X".repeat(NotificationRenderer.ST_ADD3_WIDTH + 10);

        for (int width = 0; width <= NotificationRenderer.ST_ADD3_WIDTH; width++) {
            assertEquals(width, NotificationRenderer.pic(shortValue, width).length(),
                    "a short value renders exactly " + width + " characters");
            assertEquals(width, NotificationRenderer.pic(longValue, width).length(),
                    "a long value renders exactly " + width + " characters");
            assertEquals(width, NotificationRenderer.pic(null, width).length(),
                    "a null value renders exactly " + width + " characters");
        }
    }

    /** Asserts that a negative width is refused, and that the refusal names the width. */
    @Test
    void picRefusesANegativeWidth() {
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> NotificationRenderer.pic("AB", -1),
                "a negative width names no Picture clause and is refused");
        assertEquals("width must not be negative: width=-1", refusal.getMessage(),
                "the refusal names the width supplied");
        assertThrows(IllegalArgumentException.class,
                () -> NotificationRenderer.pic(null, -5),
                "a negative width is refused before the null check runs");
    }

    // editTrailingSign9. Reproduces ST-CURR-BAL PIC 9(9).99-.

    /**
     * Asserts the {@code PIC 9(9).99-} form for a positive, a negative and a zero amount. Every
     * digit position renders a digit, including a leading zero.
     */
    @Test
    void editTrailingSign9RendersEveryDigitPositionAsADigit() {
        assertEquals("000001234.56 ",
                NotificationRenderer.editTrailingSign9(new BigDecimal("1234.56")),
                "1234.56 renders as five leading zeros, 1234.56 and a space sign position");
        assertEquals("000001234.56-",
                NotificationRenderer.editTrailingSign9(new BigDecimal("-1234.56")),
                "a negative amount renders a hyphen in the sign position");
        assertEquals("000000000.00 ",
                NotificationRenderer.editTrailingSign9(new BigDecimal("0.00")),
                "zero renders nine zero digits, a decimal point, two zeros and a space");
        assertEquals("000000001.99 ",
                NotificationRenderer.editTrailingSign9(new BigDecimal("1.99")),
                "1.99 renders eight leading zeros before its single integer digit");
        assertEquals("999999999.99 ",
                NotificationRenderer.editTrailingSign9(new BigDecimal("999999999.99")),
                "the largest value the nine integer positions hold renders every digit");
    }

    /**
     * Asserts that the third and later decimals are dropped toward zero rather than rounded. The
     * amount passes through the shared truncation, which offers no rounding mode, so 1.999 renders
     * as 1.99 and never as 2.00.
     */
    @Test
    void editTrailingSign9TruncatesTowardZeroAndNeverRounds() {
        assertEquals("000000001.99 ",
                NotificationRenderer.editTrailingSign9(new BigDecimal("1.999")),
                "1.999 truncates to 1.99 and does not round to 2.00");
        assertEquals("000000001.99-",
                NotificationRenderer.editTrailingSign9(new BigDecimal("-1.999")),
                "-1.999 truncates toward zero to -1.99 and does not round to -2.00");
        assertNotEquals("000000002.00 ",
                NotificationRenderer.editTrailingSign9(new BigDecimal("1.999")),
                "half-up rounding would render 2.00, and the source never rounds");
        assertEquals("000000000.00 ",
                NotificationRenderer.editTrailingSign9(new BigDecimal("0.009")),
                "0.009 truncates to 0.00");
    }

    /**
     * Asserts that an amount past the nine integer positions loses its high-order digits, and that
     * a negative amount whose magnitude truncates to zero renders a space in the sign position.
     * Both are reproduced from the source field width and both are recorded behaviour.
     */
    @Test
    void editTrailingSign9DropsDigitsAboveTheNinthAndThenReportsNoSign() {
        assertEquals("000000000.00 ",
                NotificationRenderer.editTrailingSign9(new BigDecimal("1000000000.00")),
                "an amount of ten integer digits loses its high-order digit and holds zero");
        assertEquals("345678901.99 ",
                NotificationRenderer.editTrailingSign9(new BigDecimal("12345678901.99")),
                "an amount of eleven integer digits keeps only its low nine");
        assertEquals("000000000.00 ",
                NotificationRenderer.editTrailingSign9(new BigDecimal("-1000000000.00")),
                "a negative amount that truncates to zero renders a space, not a hyphen, "
                        + "because the sign position reflects the value the field holds");
        assertEquals("000000000.00 ",
                NotificationRenderer.editTrailingSign9(new BigDecimal("-0.001")),
                "a negative amount below one cent renders a space in the sign position");
    }

    /** Asserts that a null amount is refused rather than rendered as spaces. */
    @Test
    void editTrailingSign9RefusesANullAmount() {
        assertThrows(NullPointerException.class,
                () -> NotificationRenderer.editTrailingSign9(null),
                "an amount is required, unlike a text field, which renders null as spaces");
    }

    // editTrailingSignZ. Reproduces ST-TRANAMT and ST-TOTAL-TRAMT PIC Z(9).99-.

    /**
     * Asserts the {@code PIC Z(9).99-} form. A {@code Z} digit position renders a leading zero as a
     * space, and suppression stops at the first digit that is not zero.
     */
    @Test
    void editTrailingSignZRendersALeadingZeroAsASpace() {
        assertEquals("     1234.56 ",
                NotificationRenderer.editTrailingSignZ(new BigDecimal("1234.56")),
                "1234.56 renders as five spaces, 1234.56 and a space sign position");
        assertEquals("     1234.56-",
                NotificationRenderer.editTrailingSignZ(new BigDecimal("-1234.56")),
                "a negative amount renders a hyphen in the sign position");
        assertEquals("        1.99 ",
                NotificationRenderer.editTrailingSignZ(new BigDecimal("1.99")),
                "1.99 renders eight spaces before its single integer digit");
        assertEquals("       10.00 ",
                NotificationRenderer.editTrailingSignZ(new BigDecimal("10.00")),
                "10.00 renders seven spaces before its two integer digits");
        assertEquals("100000000.00 ",
                NotificationRenderer.editTrailingSignZ(new BigDecimal("100000000.00")),
                "a value filling the first integer position suppresses nothing");
        assertEquals("999999999.99 ",
                NotificationRenderer.editTrailingSignZ(new BigDecimal("999999999.99")),
                "a value with no leading zero suppresses nothing");
    }

    /**
     * Asserts that suppression stops at the decimal point when every integer digit is zero, so an
     * amount below one renders nine spaces and keeps its decimals.
     */
    @Test
    void editTrailingSignZSuppressesEveryIntegerPositionOfAnAmountBelowOne() {
        assertEquals("         .00 ",
                NotificationRenderer.editTrailingSignZ(new BigDecimal("0.00")),
                "zero renders nine spaces, a decimal point, two zeros and a space");
        assertEquals("         .05 ",
                NotificationRenderer.editTrailingSignZ(new BigDecimal("0.05")),
                "0.05 keeps its decimals and suppresses every integer position");
        assertEquals("         .99-",
                NotificationRenderer.editTrailingSignZ(new BigDecimal("-0.99")),
                "a negative amount below one keeps its hyphen and suppresses every integer "
                        + "position");
    }

    /**
     * Asserts that the suppressed form truncates and overflows exactly as the zero-filled form
     * does, so the two differ in leading zeros alone.
     */
    @Test
    void editTrailingSignZTruncatesAndOverflowsLikeTheZeroFilledForm() {
        assertEquals("        1.99 ",
                NotificationRenderer.editTrailingSignZ(new BigDecimal("1.999")),
                "1.999 truncates to 1.99 in the suppressed form too");
        assertEquals("         .00 ",
                NotificationRenderer.editTrailingSignZ(new BigDecimal("1000000000.00")),
                "an amount of ten integer digits truncates to zero and suppresses every "
                        + "integer position");
        assertEquals("345678901.99 ",
                NotificationRenderer.editTrailingSignZ(new BigDecimal("12345678901.99")),
                "an amount of eleven integer digits keeps only its low nine");
        assertEquals("         .00 ",
                NotificationRenderer.editTrailingSignZ(new BigDecimal("-0.001")),
                "a negative amount below one cent renders a space in the sign position");
    }

    /**
     * Asserts that the two forms differ in leading zeros alone. For each amount, replacing every
     * leading space of the suppressed form with a zero gives the zero-filled form.
     */
    @Test
    void theTwoFormsDifferInLeadingZerosAlone() {
        List<String> amounts = List.of("1234.56", "-1234.56", "0.00", "1.99", "-0.99",
                "999999999.99", "100000000.00", "1.999", "12345678901.99");

        for (String amount : amounts) {
            BigDecimal value = new BigDecimal(amount);
            String zeroFilled = NotificationRenderer.editTrailingSign9(value);
            String suppressed = NotificationRenderer.editTrailingSignZ(value);

            assertEquals(EDITED_WIDTH, zeroFilled.length(),
                    "the zero-filled form of " + amount + " holds " + EDITED_WIDTH
                            + " characters");
            assertEquals(EDITED_WIDTH, suppressed.length(),
                    "the suppressed form of " + amount + " holds " + EDITED_WIDTH
                            + " characters");
            String zeroFilledIntegerPart = zeroFilled.substring(0, INTEGER_POSITIONS);
            String suppressedIntegerPart = suppressed.substring(0, INTEGER_POSITIONS);
            assertEquals(zeroFilledIntegerPart, suppressedIntegerPart.replace(' ', '0'),
                    "replacing every suppressed integer position of " + amount
                            + " with a zero gives the zero-filled integer part");
            assertEquals(zeroFilled.substring(INTEGER_POSITIONS, EDITED_WIDTH - 1),
                    suppressed.substring(INTEGER_POSITIONS, EDITED_WIDTH - 1),
                    "the decimal point and the two decimals of " + amount
                            + " are identical in both forms");
            assertEquals(zeroFilled.charAt(EDITED_WIDTH - 1),
                    suppressed.charAt(EDITED_WIDTH - 1),
                    "both forms of " + amount + " render the same sign position");
            assertEquals('.', zeroFilled.charAt(INTEGER_POSITIONS),
                    "the decimal point of " + amount + " sits after position "
                            + INTEGER_POSITIONS);
            assertEquals('.', suppressed.charAt(INTEGER_POSITIONS),
                    "the decimal point of " + amount + " sits after position "
                            + INTEGER_POSITIONS + " in the suppressed form too");
        }
    }

    /** Asserts that a null amount is refused by the suppressed form too. */
    @Test
    void editTrailingSignZRefusesANullAmount() {
        assertThrows(NullPointerException.class,
                () -> NotificationRenderer.editTrailingSignZ(null),
                "an amount is required in the suppressed form too");
    }

    // assembleName. Reproduces the STRING at app/cbl/CBSTM03A.CBL:L462-L469.

    /**
     * Asserts that each name component contributes only the characters before its first space, and
     * that one literal space follows each of the three, including the last.
     */
    @Test
    void assembleNameTakesEachComponentUpToItsFirstSpace() {
        assertEquals(NotificationRenderer.pic("ALPHA BET DEL ",
                        NotificationRenderer.ST_NAME_WIDTH),
                NotificationRenderer.assembleName("ALPHA", "BET GAMMA", "DEL"),
                "a two-word middle name contributes its first word only, and one space "
                        + "follows each of the three components");
        assertEquals("ALPHA BET DEL " + " ".repeat(NotificationRenderer.ST_NAME_WIDTH - 14),
                NotificationRenderer.assembleName("ALPHA", "BET GAMMA", "DEL"),
                "the assembled name holds the three words, three separating spaces and "
                        + "trailing spaces to the field width");
        assertEquals("A B C " + " ".repeat(NotificationRenderer.ST_NAME_WIDTH - 6),
                NotificationRenderer.assembleName("A", "B", "C"),
                "one literal space follows each component, including the last");
    }

    /**
     * Asserts that a null component, an empty component and a component starting with a space each
     * contribute no characters, and that none raises an exception.
     */
    @Test
    void assembleNameTreatsNullEmptyAndLeadingSpaceAsNoContribution() {
        String allSpaces = " ".repeat(NotificationRenderer.ST_NAME_WIDTH);

        assertEquals(allSpaces, NotificationRenderer.assembleName(null, null, null),
                "three null components contribute nothing, leaving three separating spaces "
                        + "inside an all-spaces field");
        assertEquals(allSpaces, NotificationRenderer.assembleName("", "", ""),
                "three empty components contribute nothing");
        assertEquals(allSpaces, NotificationRenderer.assembleName(" Leading", "  ", " X"),
                "a component starting with a space contributes nothing");
        assertEquals("ALPHA  DEL " + " ".repeat(NotificationRenderer.ST_NAME_WIDTH - 11),
                NotificationRenderer.assembleName("ALPHA", null, "DEL"),
                "a null middle name leaves two adjacent separating spaces");
    }

    /**
     * Asserts that an assembled name longer than the field loses its tail, and that every result
     * holds exactly the field width.
     */
    @Test
    void assembleNameAlwaysHoldsExactlyTheFieldWidth() {
        String longFirst = "F".repeat(NotificationRenderer.ST_NAME_WIDTH + 10);

        assertEquals(NotificationRenderer.ST_NAME_WIDTH,
                NotificationRenderer.assembleName(longFirst, "M", "L").length(),
                "an over-long first name is truncated to the field width");
        assertEquals("F".repeat(NotificationRenderer.ST_NAME_WIDTH),
                NotificationRenderer.assembleName(longFirst, "M", "L"),
                "the tail of the assembled name is dropped, so the later components are lost");

        List<List<String>> cases = List.of(
                List.of("ALPHA", "BET", "DEL"),
                List.of("A", "B", "C"),
                List.of("", "", ""),
                List.of(longFirst, "M", "L"));
        for (List<String> components : cases) {
            assertEquals(NotificationRenderer.ST_NAME_WIDTH,
                    NotificationRenderer.assembleName(components.get(0), components.get(1),
                            components.get(2)).length(),
                    "every assembled name holds exactly " + NotificationRenderer.ST_NAME_WIDTH
                            + " characters");
        }
    }

    // assembleAddress3. Reproduces the STRING at app/cbl/CBSTM03A.CBL:L472-L481.

    /**
     * Asserts that each of the four address components contributes only the characters before its
     * first space, and that one literal space follows each.
     */
    @Test
    void assembleAddress3TakesEachComponentUpToItsFirstSpace() {
        assertEquals("123 NY USA 10001 "
                        + " ".repeat(NotificationRenderer.ST_ADD3_WIDTH - 17),
                NotificationRenderer.assembleAddress3("123 Main St", "NY", "USA", "10001"),
                "a city line holding 123 Main St contributes 123 only, and one space follows "
                        + "each of the four components");
        assertEquals("A B C D " + " ".repeat(NotificationRenderer.ST_ADD3_WIDTH - 8),
                NotificationRenderer.assembleAddress3("A", "B", "C", "D"),
                "one literal space follows each of the four components, including the last");
    }

    /**
     * Asserts that a null component, an empty component and a component starting with a space each
     * contribute no characters, and that every result holds exactly the field width.
     */
    @Test
    void assembleAddress3TreatsNullEmptyAndLeadingSpaceAsNoContribution() {
        String allSpaces = " ".repeat(NotificationRenderer.ST_ADD3_WIDTH);

        assertEquals(allSpaces,
                NotificationRenderer.assembleAddress3(null, null, null, null),
                "four null components contribute nothing, leaving four separating spaces "
                        + "inside an all-spaces field");
        assertEquals(allSpaces, NotificationRenderer.assembleAddress3("", " ", "  ", null),
                "an empty component and a component starting with a space contribute nothing");
        assertEquals("123  USA 10001 "
                        + " ".repeat(NotificationRenderer.ST_ADD3_WIDTH - 15),
                NotificationRenderer.assembleAddress3("123", null, "USA", "10001"),
                "a null state code leaves two adjacent separating spaces");

        String longLine = "L".repeat(NotificationRenderer.ST_ADD3_WIDTH + 10);
        assertEquals(NotificationRenderer.ST_ADD3_WIDTH,
                NotificationRenderer.assembleAddress3(longLine, "NY", "USA", "10001").length(),
                "an over-long line is truncated to exactly "
                        + NotificationRenderer.ST_ADD3_WIDTH + " characters");
    }

    // Nested payload records.

    /**
     * Asserts that {@code CardholderContext} normalises every component to the width its source
     * field declares, whatever the caller supplies.
     */
    @Test
    void theCardholderContextNormalisesEveryComponentToItsFieldWidth() {
        Map<String, Integer> widths = new LinkedHashMap<>();
        widths.put("assembledName", NotificationRenderer.ST_NAME_WIDTH);
        widths.put("addressLine1", NotificationRenderer.ST_ADD1_WIDTH);
        widths.put("addressLine2", NotificationRenderer.ST_ADD2_WIDTH);
        widths.put("addressLine3", NotificationRenderer.ST_ADD3_WIDTH);
        widths.put("accountId", NotificationRenderer.ST_ACCT_ID_WIDTH);
        widths.put("editedCurrentBalance", NotificationRenderer.EDITED_AMOUNT_WIDTH);
        widths.put("ficoScore", NotificationRenderer.ST_FICO_SCORE_WIDTH);
        assertEquals(7, widths.size(), "the context declares seven components");

        NotificationRenderer.CardholderContext fromShort =
                new NotificationRenderer.CardholderContext("N", "A1", "A2", "A3", "7", "1", "7");
        assertEquals(widths.get("assembledName"), fromShort.assembledName().length(),
                "a short name is padded to ST-NAME PIC X(75)");
        assertEquals(widths.get("addressLine1"), fromShort.addressLine1().length(),
                "a short first address line is padded to ST-ADD1 PIC X(50)");
        assertEquals(widths.get("addressLine2"), fromShort.addressLine2().length(),
                "a short second address line is padded to ST-ADD2 PIC X(50)");
        assertEquals(widths.get("addressLine3"), fromShort.addressLine3().length(),
                "a short third address line is padded to ST-ADD3 PIC X(80)");
        assertEquals(widths.get("accountId"), fromShort.accountId().length(),
                "a short account identifier is padded to ST-ACCT-ID PIC X(20)");
        assertEquals(widths.get("editedCurrentBalance"),
                fromShort.editedCurrentBalance().length(),
                "a short balance is padded to the edited amount width");
        assertEquals(widths.get("ficoScore"), fromShort.ficoScore().length(),
                "a short score is padded to ST-FICO-SCORE PIC X(20)");

        assertEquals("N" + " ".repeat(NotificationRenderer.ST_NAME_WIDTH - 1),
                fromShort.assembledName(),
                "the padding is trailing, so the supplied characters stay at the front");
    }

    /**
     * Asserts that a null component of {@code CardholderContext} becomes an all-spaces field, and
     * that an over-long component loses its tail.
     */
    @Test
    void theCardholderContextRendersNullAsSpacesAndTruncatesAnOverLongComponent() {
        NotificationRenderer.CardholderContext fromNulls =
                new NotificationRenderer.CardholderContext(null, null, null, null, null, null,
                        null);

        assertEquals(" ".repeat(NotificationRenderer.ST_NAME_WIDTH), fromNulls.assembledName(),
                "a null name becomes an all-spaces ST-NAME");
        assertEquals(" ".repeat(NotificationRenderer.ST_ACCT_ID_WIDTH), fromNulls.accountId(),
                "a null account identifier becomes an all-spaces ST-ACCT-ID");
        assertEquals(" ".repeat(NotificationRenderer.EDITED_AMOUNT_WIDTH),
                fromNulls.editedCurrentBalance(),
                "a null balance becomes an all-spaces edited amount");

        String longValue = "X".repeat(NotificationRenderer.ST_NAME_WIDTH + 20);
        NotificationRenderer.CardholderContext fromLong =
                new NotificationRenderer.CardholderContext(longValue, longValue, longValue,
                        longValue, longValue, longValue, longValue);
        assertEquals("X".repeat(NotificationRenderer.ST_NAME_WIDTH), fromLong.assembledName(),
                "an over-long name loses its tail at ST-NAME PIC X(75)");
        assertEquals("X".repeat(NotificationRenderer.EDITED_AMOUNT_WIDTH),
                fromLong.editedCurrentBalance(),
                "an over-long balance loses its tail at the edited amount width");
    }

    /**
     * Asserts that {@code TransactionRow} normalises its three components, and that a description
     * longer than the rendered field loses exactly the characters the source drops.
     */
    @Test
    void theTransactionRowNormalisesItsThreeComponents() {
        String storedDescription = "D".repeat(STORED_DESCRIPTION_WIDTH);
        NotificationRenderer.TransactionRow row = new NotificationRenderer.TransactionRow(
                "T1", storedDescription, "1.00");

        assertEquals(NotificationRenderer.ST_TRANID_WIDTH, row.transactionId().length(),
                "a short identifier is padded to ST-TRANID PIC X(16)");
        assertEquals("T1" + " ".repeat(NotificationRenderer.ST_TRANID_WIDTH - 2),
                row.transactionId(),
                "the identifier keeps its characters at the front");
        assertEquals(NotificationRenderer.ST_TRANDT_WIDTH, row.description().length(),
                "a stored description of " + STORED_DESCRIPTION_WIDTH
                        + " characters renders at ST-TRANDT PIC X(49)");
        assertEquals("D".repeat(NotificationRenderer.ST_TRANDT_WIDTH), row.description(),
                "the description keeps its leading characters and loses its tail");
        assertEquals(NotificationRenderer.EDITED_AMOUNT_WIDTH, row.editedAmount().length(),
                "a short edited amount is padded to the edited amount width");

        NotificationRenderer.TransactionRow fromNulls =
                new NotificationRenderer.TransactionRow(null, null, null);
        assertEquals(" ".repeat(NotificationRenderer.ST_TRANID_WIDTH), fromNulls.transactionId(),
                "a null identifier becomes an all-spaces ST-TRANID");
        assertEquals(" ".repeat(NotificationRenderer.ST_TRANDT_WIDTH), fromNulls.description(),
                "a null description becomes an all-spaces ST-TRANDT");
        assertEquals(" ".repeat(NotificationRenderer.EDITED_AMOUNT_WIDTH),
                fromNulls.editedAmount(),
                "a null amount becomes an all-spaces edited amount");
    }

    /**
     * Asserts that an edited amount survives a round trip through a payload record unchanged, so
     * the normalisation never disturbs a value already at the edited width.
     */
    @Test
    void anEditedAmountSurvivesAPayloadRecordUnchanged() {
        for (String amount : List.of("1234.56", "-1234.56", "0.00", "999999999.99")) {
            String editedForBalance =
                    NotificationRenderer.editTrailingSign9(new BigDecimal(amount));
            String editedForRow = NotificationRenderer.editTrailingSignZ(new BigDecimal(amount));

            assertEquals(editedForBalance, new NotificationRenderer.CardholderContext(
                            "N", "A1", "A2", "A3", "7", editedForBalance, "7")
                            .editedCurrentBalance(),
                    "the balance of " + amount + " reaches the context unchanged");
            assertEquals(editedForRow, new NotificationRenderer.TransactionRow(
                            "T1", "D", editedForRow).editedAmount(),
                    "the row amount of " + amount + " reaches the row unchanged");
        }
    }

    // Format enumeration and the interface contract.

    /**
     * Asserts that the enumeration holds exactly the two formats
     * {@code app/cbl/CBSTM03A.CBL:L44-L47} declares one file for each of, and no third.
     */
    @Test
    void theEnumerationHoldsExactlyTheTwoFormatsTheSourceWrites() {
        NotificationRenderer.RenderedFormat[] formats =
                NotificationRenderer.RenderedFormat.values();

        assertEquals(2, formats.length,
                "app/cbl/CBSTM03A.CBL:L44-L47 declares two output files, so the enumeration "
                        + "holds two constants");
        assertEquals(List.of("PLAIN_TEXT", "HTML"),
                List.of(formats[0].name(), formats[1].name()),
                "the two constants name the fixed-width text file and the markup file");
        assertSame(NotificationRenderer.RenderedFormat.PLAIN_TEXT,
                NotificationRenderer.RenderedFormat.valueOf("PLAIN_TEXT"),
                "the text format resolves by name");
        assertSame(NotificationRenderer.RenderedFormat.HTML,
                NotificationRenderer.RenderedFormat.valueOf("HTML"),
                "the markup format resolves by name");
        assertThrows(IllegalArgumentException.class,
                () -> NotificationRenderer.RenderedFormat.valueOf("CSV"),
                "a format the source does not write resolves to nothing");
        assertEquals(MARKUP_RECORD_WIDTH - TEXT_RECORD_WIDTH, 20,
                "FD-HTMLFILE-REC PIC X(100) at app/cbl/CBSTM03A.CBL:L47 is 20 characters wider "
                        + "than FD-STMTFILE-REC PIC X(80) at line 45");
    }

    /**
     * Asserts that the interface is implementable and that {@code format()} discriminates one
     * implementation from another. Two minimal implementations stand in for the two renderers,
     * which are separate files, so no assertion here concerns the content of a rendered alert.
     */
    @Test
    void theInterfaceIsImplementableAndTheFormatDiscriminates() {
        NotificationRenderer text =
                new RecordingRenderer(NotificationRenderer.RenderedFormat.PLAIN_TEXT);
        NotificationRenderer markup =
                new RecordingRenderer(NotificationRenderer.RenderedFormat.HTML);

        assertSame(NotificationRenderer.RenderedFormat.PLAIN_TEXT, text.format(),
                "an implementation reports the format it produces");
        assertSame(NotificationRenderer.RenderedFormat.HTML, markup.format(),
                "a second implementation reports its own format");
        assertNotEquals(text.format(), markup.format(),
                "a caller holding both selects one by comparing the reported format");

        NotificationRenderer.CardholderContext context =
                new NotificationRenderer.CardholderContext("N", "A1", "A2", "A3", "7", "0.00",
                        "742");
        NotificationRenderer.TransactionRow row =
                new NotificationRenderer.TransactionRow("T1", "D", "0.00");

        assertTrue(text.renderStatementAlert(context, List.of(row), BigDecimal.ZERO)
                        .contains("PLAIN_TEXT"),
                "the statement operation reaches the implementation that produces the format");
        assertTrue(markup.renderFraudAlert(context, "T1", 90, List.of("VELOCITY"))
                        .contains("HTML"),
                "the fraud operation reaches the implementation that produces the format");
    }

    /**
     * A minimal implementation that records which operation ran and which format it reports.
     *
     * <p>It renders no statement content. The two production renderers are separate files, and
     * their content is theirs to assert.</p>
     *
     * @param reportedFormat the format this implementation reports
     */
    private record RecordingRenderer(NotificationRenderer.RenderedFormat reportedFormat)
            implements NotificationRenderer {

        @Override
        public RenderedFormat format() {
            return reportedFormat;
        }

        @Override
        public String renderStatementAlert(CardholderContext context, List<TransactionRow> rows,
                                           BigDecimal total) {
            return "statement " + reportedFormat.name() + " rows=" + rows.size()
                    + " total=" + total.toPlainString() + " name=" + context.assembledName();
        }

        @Override
        public String renderFraudAlert(CardholderContext context, String transactionId,
                                       int riskScore, List<String> triggeredRules) {
            return "fraud " + reportedFormat.name() + " tran=" + transactionId
                    + " score=" + riskScore + " rules=" + triggeredRules.size()
                    + " name=" + context.assembledName();
        }
    }
}
