package com.carddemo.account.domain.validation;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.carddemo.cobol.NumvalParser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link NumericRequiredValidator}, the required numeric edit of paragraph
 * {@code 1245-EDIT-NUM-REQD} at app/cbl/COACTUPC.cbl:L2109.
 *
 * <p>Three checks run in the order the paragraph writes them, and each one owns a message. The
 * not-supplied check sits at app/cbl/COACTUPC.cbl:L2114-L2119 and builds its message at
 * app/cbl/COACTUPC.cbl:L2126. The {@code IS NUMERIC} class check sits at
 * app/cbl/COACTUPC.cbl:L2137-L2138 and builds its message at app/cbl/COACTUPC.cbl:L2146. The
 * not-zero check sits at app/cbl/COACTUPC.cbl:L2156-L2157 and builds its message at
 * app/cbl/COACTUPC.cbl:L2163. Each failing check runs a {@code GO TO} to the exit paragraph at
 * app/cbl/COACTUPC.cbl:L2176, so a verdict carries the message of the first failure alone.</p>
 *
 * <p>Entry is pessimistic. app/cbl/COACTUPC.cbl:L2111 sets the not-ok flag, and
 * app/cbl/COACTUPC.cbl:L2174 sets the valid flag once all three checks clear. The source spells
 * that valid flag {@code FLG-ALPHNANUM-ISVALID}, transposing two letters of the word alphanumeric,
 * and the target corrects the spelling in identifier names and leaves the behaviour untouched.
 * card-platform/docs/business-rule-flags.md records the spelling.</p>
 *
 * <p>The not-zero check at app/cbl/COACTUPC.cbl:L2156 converts through the plain
 * {@code FUNCTION NUMVAL}, which {@link NumvalParser#numval(String)} reproduces. The
 * currency-tolerant gate {@code FUNCTION TEST-NUMVAL-C} belongs to paragraph
 * {@code 1250-EDIT-SIGNED-9V2} at app/cbl/COACTUPC.cbl:L2201, and {@code SignedDecimalValidator}
 * binds that gate. A dollar sign and a grouping comma therefore fail the class check here, and
 * {@link #aValueCarryingACurrencySignOrAGroupingCommaReportsTheClassMessage(String)} pins the
 * rejection. {@code SignedDecimalValidatorTest} pins the matching acceptance of the same
 * characters, and the two files together pin the asymmetry.</p>
 *
 * <p>Six call sites perform the paragraph. app/cbl/COACTUPC.cbl:L1549 edits a FICO Score, a credit
 * score, at width 3. app/cbl/COACTUPC.cbl:L1608 edits a Zip, a postal code, at width 5.
 * app/cbl/COACTUPC.cbl:L1652 edits an EFT Account Id, an electronic funds transfer account
 * identifier, at width 10. The other three sit inside paragraph {@code 1265-EDIT-US-SSN} at
 * app/cbl/COACTUPC.cbl:L2431 and edit the three Social Security Number parts at widths 3, 2 and 4,
 * at app/cbl/COACTUPC.cbl:L2442, app/cbl/COACTUPC.cbl:L2472 and app/cbl/COACTUPC.cbl:L2484.</p>
 *
 * <p>The gate at app/cbl/COACTUPC.cbl:L1553 reaches the credit score range check at
 * app/cbl/COACTUPC.cbl:L1554 only after the edit passes. That sequencing is orchestration, and
 * {@code AccountUpdateServiceTest} owns it. Every method below calls one validator.</p>
 *
 * <p>Host widths the constants below carry: {@code WS-EDIT-VARIABLE-NAME PIC X(25)} at
 * app/cbl/COACTUPC.cbl:L53, {@code WS-EDIT-ALPHANUM-ONLY PIC X(256)} at
 * app/cbl/COACTUPC.cbl:L61, and {@code WS-EDIT-ALPHANUM-LENGTH PIC S9(4) COMP-3} at
 * app/cbl/COACTUPC.cbl:L62. A COBOL {@code MOVE} into the 256-byte field pads on the right with
 * spaces, and a space fails the class check at app/cbl/COACTUPC.cbl:L2137. Content shorter than
 * its declared width therefore clears the presence check and then fails the class check.</p>
 *
 * <p>Every message the paragraph builds passes through the single slot
 * {@code WS-RETURN-MSG PIC X(75)} at app/cbl/COACTUPC.cbl:L479, whose condition name sits at
 * app/cbl/COACTUPC.cbl:L480 and whose reset sits at app/cbl/COACTUPC.cbl:L876. The three literals
 * below are quoted from the source and keep their leading space and their trailing period.</p>
 *
 * <p>No Spring context, container or database takes part, and every input below is built in this
 * file. {@code mvn test} therefore covers the subject on a clean machine.</p>
 */
@DisplayName("NumericRequiredValidator, the required numeric edit of paragraph 1245-EDIT-NUM-REQD")
class NumericRequiredValidatorTest {

    /**
     * Literal the message build appends at app/cbl/COACTUPC.cbl:L2126, opening with one space and
     * closing with a period.
     */
    private static final String NOT_SUPPLIED_LITERAL = " must be supplied.";

    /**
     * Literal the message build appends at app/cbl/COACTUPC.cbl:L2146, opening with one space and
     * closing with a period.
     */
    private static final String NOT_ALL_NUMERIC_LITERAL = " must be all numeric.";

    /**
     * Literal the message build appends at app/cbl/COACTUPC.cbl:L2163, opening with one space and
     * closing with a period.
     */
    private static final String IS_ZERO_LITERAL = " must not be zero.";

    /** Width of the literal at app/cbl/COACTUPC.cbl:L2126, counting the space and the period. */
    private static final int NOT_SUPPLIED_LITERAL_WIDTH = 18;

    /** Width of the literal at app/cbl/COACTUPC.cbl:L2146, counting the space and the period. */
    private static final int NOT_ALL_NUMERIC_LITERAL_WIDTH = 21;

    /** Width of the literal at app/cbl/COACTUPC.cbl:L2163, counting the space and the period. */
    private static final int IS_ZERO_LITERAL_WIDTH = 18;

    /**
     * ADDITIVE. Opens the message for a value carrying content past its declared width. No source
     * literal carries the text. app/cbl/COACTUPC.cbl:L1546-L1547 moves a fixed-width screen field
     * into the edit field, so a wider value never reaches paragraph 1245.
     */
    private static final String NO_LONGER_THAN_LITERAL = " must be no longer than ";

    /** ADDITIVE. Closes the text {@link #NO_LONGER_THAN_LITERAL} opens. */
    private static final String CHARACTERS_LITERAL = " characters.";

    /** Label app/cbl/COACTUPC.cbl:L1545 moves for the credit score field. */
    private static final String FICO_SCORE_LABEL = "FICO Score";

    /** Width app/cbl/COACTUPC.cbl:L1548 moves for the credit score field. */
    private static final int FICO_SCORE_WIDTH = 3;

    /** Label app/cbl/COACTUPC.cbl:L1605 moves for the postal code field. */
    private static final String ZIP_LABEL = "Zip";

    /** Width app/cbl/COACTUPC.cbl:L1607 moves for the postal code field. */
    private static final int ZIP_WIDTH = 5;

    /** Label app/cbl/COACTUPC.cbl:L1648 moves for the transfer account identifier. */
    private static final String EFT_ACCOUNT_LABEL = "EFT Account Id";

    /** Width app/cbl/COACTUPC.cbl:L1651 moves for the transfer account identifier. */
    private static final int EFT_ACCOUNT_WIDTH = 10;

    /** Label app/cbl/COACTUPC.cbl:L2439 moves for the first Social Security Number part. */
    private static final String SSN_PART_1_LABEL = "SSN: First 3 chars";

    /** Width app/cbl/COACTUPC.cbl:L2441 moves for the first Social Security Number part. */
    private static final int SSN_PART_1_WIDTH = 3;

    /** Label app/cbl/COACTUPC.cbl:L2469 moves for the second Social Security Number part. */
    private static final String SSN_PART_2_LABEL = "SSN 4th & 5th chars";

    /** Width app/cbl/COACTUPC.cbl:L2471 moves for the second Social Security Number part. */
    private static final int SSN_PART_2_WIDTH = 2;

    /** Label app/cbl/COACTUPC.cbl:L2481 moves for the third Social Security Number part. */
    private static final String SSN_PART_3_LABEL = "SSN Last 4 chars";

    /** Width app/cbl/COACTUPC.cbl:L2483 moves for the third Social Security Number part. */
    private static final int SSN_PART_3_WIDTH = 4;

    /** The six widths the source call sites move, in call-site order. */
    private static final List<Integer> CALL_SITE_WIDTHS = List.of(
            FICO_SCORE_WIDTH, ZIP_WIDTH, EFT_ACCOUNT_WIDTH,
            SSN_PART_1_WIDTH, SSN_PART_2_WIDTH, SSN_PART_3_WIDTH);

    /** Declared width of {@code WS-EDIT-VARIABLE-NAME} at app/cbl/COACTUPC.cbl:L53. */
    private static final int LABEL_HOST_WIDTH = 25;

    /** Declared width of {@code WS-EDIT-ALPHANUM-ONLY} at app/cbl/COACTUPC.cbl:L61. */
    private static final int EDIT_FIELD_HOST_WIDTH = 256;

    /**
     * One character of the figurative constant {@code LOW-VALUES}, which the first arm of the
     * not-supplied test at app/cbl/COACTUPC.cbl:L2114-L2115 compares against.
     */
    private static final String LOW_VALUE = "\0";

    /**
     * The credit score label at its {@code PIC X(25)} host width, carrying fifteen trailing
     * spaces. app/cbl/COACTUPC.cbl:L2125, app/cbl/COACTUPC.cbl:L2145 and
     * app/cbl/COACTUPC.cbl:L2162 each wrap the label in {@code FUNCTION TRIM}.
     */
    private static final String PADDED_LABEL =
            FICO_SCORE_LABEL + " ".repeat(LABEL_HOST_WIDTH - FICO_SCORE_LABEL.length());

    /**
     * A money amount carrying both a currency sign and a grouping comma.
     * {@code SignedDecimalValidatorTest} lists the same characters among the values the
     * currency-tolerant gate at app/cbl/COACTUPC.cbl:L2201 accepts.
     */
    private static final String CURRENCY_AMOUNT = "$1,234.56";

    /**
     * Supplies the six call sites of paragraph 1245 in call-site order. Each set carries a label,
     * a declared width, and a value of digits that converts above zero.
     *
     * @return one argument set per call site
     */
    private static Stream<Arguments> sourceCallSites() {
        return Stream.of(
                Arguments.of(FICO_SCORE_LABEL, FICO_SCORE_WIDTH, "750"),
                Arguments.of(ZIP_LABEL, ZIP_WIDTH, "07094"),
                Arguments.of(EFT_ACCOUNT_LABEL, EFT_ACCOUNT_WIDTH, "0000000123"),
                Arguments.of(SSN_PART_1_LABEL, SSN_PART_1_WIDTH, "123"),
                Arguments.of(SSN_PART_2_LABEL, SSN_PART_2_WIDTH, "45"),
                Arguments.of(SSN_PART_3_LABEL, SSN_PART_3_WIDTH, "6789"));
    }

    @ParameterizedTest(name = "{0} accepts [{2}] at width {1}")
    @MethodSource("sourceCallSites")
    @DisplayName("Every call-site width accepts a value of digits that converts above zero")
    void everyCallSiteWidthAcceptsANonZeroNumericValue(String label, int width, String value) {
        // app/cbl/COACTUPC.cbl:L2174 sets the valid flag once all three checks clear, and the
        // paragraph builds no message on that path.
        EditResult result = NumericRequiredValidator.validate(label, value, width);

        assertThat(result.valid()).isTrue();
        assertThat(result.message()).isNull();
        assertThat(result.hasMessage()).isFalse();
    }

    @ParameterizedTest(name = "{0} rejects a field of zeros at width {1}")
    @MethodSource("sourceCallSites")
    @DisplayName("Every call-site width reports the not-zero message for a field of zeros")
    void everyCallSiteWidthRejectsAFieldOfZeros(String label, int width, String value) {
        // app/cbl/COACTUPC.cbl:L2156-L2157 tests the converted value, so a field of digits that
        // converts to zero fails at any width.
        String zeros = "0".repeat(width);

        EditResult result = NumericRequiredValidator.validate(label, zeros, width);

        assertThat(zeros).as("the field of zeros and the accepted value share a width")
                .hasSameSizeAs(value);
        assertThat(result.valid()).isFalse();
        assertThat(result.message()).isEqualTo(label + IS_ZERO_LITERAL);
    }

    @ParameterizedTest(name = "value [{0}] reports the field not supplied")
    @NullSource
    @EmptySource
    @ValueSource(strings = {" ", "  ", "   ", "     "})
    @DisplayName("A value carrying no content reports the field not supplied")
    void aValueCarryingNoContentReportsTheNotSuppliedMessage(String value) {
        // app/cbl/COACTUPC.cbl:L2117 covers the SPACES arm, and
        // app/cbl/COACTUPC.cbl:L2118-L2119 covers the trimmed-length arm. A null value and an
        // empty value both arrive as a field of spaces after the MOVE.
        EditResult result =
                NumericRequiredValidator.validate(FICO_SCORE_LABEL, value, FICO_SCORE_WIDTH);

        assertNotSupplied(result, FICO_SCORE_LABEL);
    }

    @Test
    @DisplayName("A field of LOW-VALUES at its declared width reports the field not supplied")
    void aFieldOfLowValuesAtItsDeclaredWidthReportsTheNotSuppliedMessage() {
        // app/cbl/COACTUPC.cbl:L2114-L2115 is the first arm of the not-supplied test, and the
        // comparison covers every character of the declared width.
        for (int width : CALL_SITE_WIDTHS) {
            EditResult result = NumericRequiredValidator.validate(
                    FICO_SCORE_LABEL, LOW_VALUE.repeat(width), width);

            assertThat(result.valid()).as("width %d", width).isFalse();
            assertThat(result.message())
                    .as("width %d", width)
                    .isEqualTo(FICO_SCORE_LABEL + NOT_SUPPLIED_LITERAL);
        }
    }

    @Test
    @DisplayName("The not-supplied check answers ahead of the class check")
    void theNotSuppliedCheckAnswersAheadOfTheClassCheck() {
        // app/cbl/COACTUPC.cbl:L2132 leaves the paragraph before the class check at
        // app/cbl/COACTUPC.cbl:L2137 reads the field. A field of spaces fails the class check on
        // its own, so the message proves which check answered first.
        String spaces = " ".repeat(FICO_SCORE_WIDTH);

        EditResult result =
                NumericRequiredValidator.validate(FICO_SCORE_LABEL, spaces, FICO_SCORE_WIDTH);

        assertThat(result.message()).isEqualTo(FICO_SCORE_LABEL + NOT_SUPPLIED_LITERAL);
        assertThat(result.message()).isNotEqualTo(FICO_SCORE_LABEL + NOT_ALL_NUMERIC_LITERAL);
        assertThat(result.message()).isNotEqualTo(FICO_SCORE_LABEL + IS_ZERO_LITERAL);
    }

    @ParameterizedTest(name = "value [{0}] reports the class check")
    @ValueSource(strings = {"7A0", "A75", "ABC", "75X", "O00", "abc"})
    @DisplayName("A value carrying a letter reports the field not all numeric")
    void aValueCarryingALetterReportsTheClassMessage(String value) {
        // app/cbl/COACTUPC.cbl:L2137-L2138 tests WS-EDIT-ALPHANUM-ONLY, an alphanumeric item
        // declared at app/cbl/COACTUPC.cbl:L61, so IS NUMERIC holds only for a field of digits.
        EditResult result =
                NumericRequiredValidator.validate(FICO_SCORE_LABEL, value, FICO_SCORE_WIDTH);

        assertNotAllNumeric(result, FICO_SCORE_LABEL);
    }

    @ParameterizedTest(name = "value [{0}] reports the class check")
    @ValueSource(strings = {"+75", "-75", "75-", "1.5", "7.5", ".50", "75+"})
    @DisplayName("A sign character and a decimal point both report the field not all numeric")
    void aSignOrADecimalPointReportsTheClassMessage(String value) {
        // The class test at app/cbl/COACTUPC.cbl:L2137 reads an alphanumeric item, and a sign
        // character and a decimal point are not digits. The paragraph applies the class test ahead
        // of the conversion at app/cbl/COACTUPC.cbl:L2156, so neither character reaches it.
        EditResult result =
                NumericRequiredValidator.validate(FICO_SCORE_LABEL, value, FICO_SCORE_WIDTH);

        assertNotAllNumeric(result, FICO_SCORE_LABEL);
    }

    @ParameterizedTest(name = "value [{0}] reports the class check")
    @ValueSource(strings = {"7 5", " 75", "75 ", "1 "})
    @DisplayName("A space among the digits reports the field not all numeric")
    void aSpaceAmongTheDigitsReportsTheClassMessage(String value) {
        // FUNCTION TRIM at app/cbl/COACTUPC.cbl:L2118-L2119 clears the presence test for a field
        // holding one digit. The class test at app/cbl/COACTUPC.cbl:L2137 then reads every
        // position of the declared width, space positions included.
        EditResult result =
                NumericRequiredValidator.validate(FICO_SCORE_LABEL, value, value.length());

        assertNotAllNumeric(result, FICO_SCORE_LABEL);
    }

    @ParameterizedTest(name = "value [{0}] is rejected here and reports the class check")
    @ValueSource(strings = {CURRENCY_AMOUNT, "1,234.56", "$1234.56", "1,234", "$750", "750$", "$0"})
    @DisplayName("A value carrying a currency sign or a grouping comma reports the class check")
    void aValueCarryingACurrencySignOrAGroupingCommaReportsTheClassMessage(String value) {
        // Paragraph 1245 owns the plain conversion at app/cbl/COACTUPC.cbl:L2156 and reaches it
        // only past the class test at app/cbl/COACTUPC.cbl:L2137. Paragraph 1250-EDIT-SIGNED-9V2
        // owns the currency-tolerant gate at app/cbl/COACTUPC.cbl:L2201, applies no class test,
        // and accepts these same characters. SignedDecimalValidatorTest holds that acceptance.
        assertThat(value.length())
                .as("the value fits the declared width")
                .isLessThanOrEqualTo(EFT_ACCOUNT_WIDTH);

        EditResult result =
                NumericRequiredValidator.validate(EFT_ACCOUNT_LABEL, value, EFT_ACCOUNT_WIDTH);

        assertNotAllNumeric(result, EFT_ACCOUNT_LABEL);
    }

    @Test
    @DisplayName("The class check answers ahead of the not-zero check")
    void theClassCheckAnswersAheadOfTheNotZeroCheck() {
        // app/cbl/COACTUPC.cbl:L2151 leaves the paragraph before the conversion at
        // app/cbl/COACTUPC.cbl:L2156 runs. Each value below carries a zero digit and one
        // character that is not a digit, so only the class message can appear.
        for (String value : new String[] {"00A", "0.0", "$00", "0,0"}) {
            EditResult result =
                    NumericRequiredValidator.validate(FICO_SCORE_LABEL, value, FICO_SCORE_WIDTH);

            assertThat(result.message())
                    .as("value [%s]", value)
                    .isEqualTo(FICO_SCORE_LABEL + NOT_ALL_NUMERIC_LITERAL);
            assertThat(result.message())
                    .as("value [%s]", value)
                    .isNotEqualTo(FICO_SCORE_LABEL + IS_ZERO_LITERAL);
        }
    }

    @Test
    @DisplayName("A value shorter than its declared width reports the field not all numeric")
    void aValueShorterThanItsDeclaredWidthReportsTheClassMessage() {
        // A MOVE into WS-EDIT-ALPHANUM-ONLY at app/cbl/COACTUPC.cbl:L61 pads on the right with
        // spaces, and the class test at app/cbl/COACTUPC.cbl:L2137 covers the full declared width.
        // Paragraph 1215-EDIT-MANDATORY at app/cbl/COACTUPC.cbl:L1824 carries the presence test
        // alone and accepts the same short value.
        assertNotAllNumeric(
                NumericRequiredValidator.validate(ZIP_LABEL, "75", ZIP_WIDTH), ZIP_LABEL);
        assertNotAllNumeric(
                NumericRequiredValidator.validate(FICO_SCORE_LABEL, "7", FICO_SCORE_WIDTH),
                FICO_SCORE_LABEL);
        assertNotAllNumeric(
                NumericRequiredValidator.validate(EFT_ACCOUNT_LABEL, "123", EFT_ACCOUNT_WIDTH),
                EFT_ACCOUNT_LABEL);
        assertThat(NumericRequiredValidator.validate(ZIP_LABEL, "07094", ZIP_WIDTH).valid())
                .isTrue();
    }

    @ParameterizedTest(name = "value [{0}] reports the not-zero check")
    @ValueSource(strings = {"0", "00", "000", "0000", "00000", "0000000000"})
    @DisplayName("A field of zeros clears the class check and reports the not-zero check")
    void aFieldOfZerosReportsTheNotZeroMessage(String value) {
        // app/cbl/COACTUPC.cbl:L2156-L2157 holds when FUNCTION NUMVAL returns zero, and
        // app/cbl/COACTUPC.cbl:L2158 places the failure on that branch. The polarity is inverted
        // against the two checks ahead of it, whose failures sit on the false branch.
        EditResult result =
                NumericRequiredValidator.validate(FICO_SCORE_LABEL, value, value.length());

        assertThat(result.valid()).isFalse();
        assertThat(result.hasMessage()).isTrue();
        assertThat(result.message()).isEqualTo(FICO_SCORE_LABEL + IS_ZERO_LITERAL);
        assertThat(result.message()).isNotEqualTo(FICO_SCORE_LABEL + NOT_ALL_NUMERIC_LITERAL);
        assertThat(result.message()).isNotEqualTo(FICO_SCORE_LABEL + NOT_SUPPLIED_LITERAL);
    }

    @Test
    @DisplayName("A declared width of zero or below reports the field not supplied")
    void aDeclaredWidthOfZeroOrBelowReportsTheNotSuppliedMessage() {
        // The reference modification at app/cbl/COACTUPC.cbl:L2114 needs a positive length. Every
        // call site moves a positive one, as app/cbl/COACTUPC.cbl:L1548 does with 3.
        for (int width : new int[] {0, -1, -LABEL_HOST_WIDTH}) {
            EditResult result =
                    NumericRequiredValidator.validate(FICO_SCORE_LABEL, "750", width);

            assertThat(result.valid()).as("width %d", width).isFalse();
            assertThat(result.message())
                    .as("width %d", width)
                    .isEqualTo(FICO_SCORE_LABEL + NOT_SUPPLIED_LITERAL);
        }
    }

    @Test
    @DisplayName("All three messages open with the trimmed label")
    void allThreeMessagesOpenWithTheTrimmedLabel() {
        // FUNCTION TRIM wraps the label at app/cbl/COACTUPC.cbl:L2125,
        // app/cbl/COACTUPC.cbl:L2145 and app/cbl/COACTUPC.cbl:L2162, so the trailing spaces of the
        // PIC X(25) host at app/cbl/COACTUPC.cbl:L53 never reach a message.
        assertThat(PADDED_LABEL).hasSize(LABEL_HOST_WIDTH).endsWith(" ");

        assertThat(NumericRequiredValidator.validate(PADDED_LABEL, null, FICO_SCORE_WIDTH)
                .message()).isEqualTo(FICO_SCORE_LABEL + NOT_SUPPLIED_LITERAL);
        assertThat(NumericRequiredValidator.validate(PADDED_LABEL, "7A0", FICO_SCORE_WIDTH)
                .message()).isEqualTo(FICO_SCORE_LABEL + NOT_ALL_NUMERIC_LITERAL);
        assertThat(NumericRequiredValidator.validate(PADDED_LABEL, "000", FICO_SCORE_WIDTH)
                .message()).isEqualTo(FICO_SCORE_LABEL + IS_ZERO_LITERAL);
    }

    @Test
    @DisplayName("The label loses its leading and trailing spaces and keeps every other character")
    void theLabelLosesItsLeadingAndTrailingSpacesAndKeepsEveryOtherCharacter() {
        // FUNCTION TRIM removes the space character alone, so the ampersand of the label at
        // app/cbl/COACTUPC.cbl:L2469 and the colon of the label at app/cbl/COACTUPC.cbl:L2439 both
        // survive. A label wider than its PIC X(25) host arrives from the caller, and the edit
        // passes it along whole.
        String longLabel = "L".repeat(LABEL_HOST_WIDTH + 5);

        assertThat(NumericRequiredValidator
                .validate("  " + SSN_PART_2_LABEL + "  ", null, SSN_PART_2_WIDTH).message())
                .isEqualTo(SSN_PART_2_LABEL + NOT_SUPPLIED_LITERAL);
        assertThat(NumericRequiredValidator
                .validate(SSN_PART_1_LABEL, "00A", SSN_PART_1_WIDTH).message())
                .isEqualTo(SSN_PART_1_LABEL + NOT_ALL_NUMERIC_LITERAL);
        assertThat(NumericRequiredValidator.validate(null, null, FICO_SCORE_WIDTH).message())
                .isEqualTo(NOT_SUPPLIED_LITERAL);
        assertThat(NumericRequiredValidator.validate(longLabel, null, FICO_SCORE_WIDTH).message())
                .isEqualTo(longLabel + NOT_SUPPLIED_LITERAL);
    }

    @Test
    @DisplayName("Each of the three literals keeps its leading space, its trailing period and its "
            + "width")
    void eachLiteralKeepsItsLeadingSpaceTrailingPeriodAndWidth() {
        // The literals at app/cbl/COACTUPC.cbl:L2126, app/cbl/COACTUPC.cbl:L2146 and
        // app/cbl/COACTUPC.cbl:L2163 each close with a period. The trailing period is a per-message
        // property of the source: the literal at app/cbl/COACTUPC.cbl:L2209 closes without one.
        assertThat(NOT_SUPPLIED_LITERAL).startsWith(" ").endsWith(".")
                .hasSize(NOT_SUPPLIED_LITERAL_WIDTH);
        assertThat(NOT_ALL_NUMERIC_LITERAL).startsWith(" ").endsWith(".")
                .hasSize(NOT_ALL_NUMERIC_LITERAL_WIDTH);
        assertThat(IS_ZERO_LITERAL).startsWith(" ").endsWith(".")
                .hasSize(IS_ZERO_LITERAL_WIDTH);
        assertThat(NumericRequiredValidator.validate(FICO_SCORE_LABEL, "000", FICO_SCORE_WIDTH)
                .message()).containsOnlyOnce(IS_ZERO_LITERAL);
    }

    @Test
    @DisplayName("Exactly three messages are reachable within the declared width")
    void exactlyThreeMessagesAreReachableWithinTheDeclaredWidth() {
        // The paragraph builds a message at app/cbl/COACTUPC.cbl:L2124,
        // app/cbl/COACTUPC.cbl:L2144 and app/cbl/COACTUPC.cbl:L2161, and nowhere else. The sweep
        // below covers every printable character at the credit score width, plus the three inputs
        // that carry no content at all.
        Set<String> messages = new LinkedHashSet<>();
        Set<String> passingValues = new LinkedHashSet<>();
        List<String> sweep = new ArrayList<>();

        sweep.add(null);
        sweep.add("");
        sweep.add(LOW_VALUE.repeat(FICO_SCORE_WIDTH));
        for (char character = 0x20; character <= 0x7E; character++) {
            sweep.add(String.valueOf(character).repeat(FICO_SCORE_WIDTH));
        }

        for (String value : sweep) {
            EditResult result =
                    NumericRequiredValidator.validate(FICO_SCORE_LABEL, value, FICO_SCORE_WIDTH);
            if (result.valid()) {
                passingValues.add(value);
            } else {
                messages.add(result.message());
            }
        }

        assertThat(messages).containsExactlyInAnyOrder(
                FICO_SCORE_LABEL + NOT_SUPPLIED_LITERAL,
                FICO_SCORE_LABEL + NOT_ALL_NUMERIC_LITERAL,
                FICO_SCORE_LABEL + IS_ZERO_LITERAL);
        assertThat(passingValues).containsExactly(
                "111", "222", "333", "444", "555", "666", "777", "888", "999");
    }

    @Test
    @DisplayName("A value carrying content past its declared width reports the added width message")
    void aValueCarryingContentPastItsDeclaredWidthReportsTheAddedWidthMessage() {
        // ADDITIVE. app/cbl/COACTUPC.cbl:L1546-L1547 moves a fixed-width screen field into the
        // edit field, so the source never receives a wider value and carries no literal for one.
        // Trailing spaces past the width are the padding the MOVE itself supplies, and they leave
        // the verdict alone. card-platform/docs/decision-log.md carries the rationale.
        String wider = "750ABC";

        assertThat(NumericRequiredValidator
                .validate(FICO_SCORE_LABEL, wider, FICO_SCORE_WIDTH).message())
                .isEqualTo(FICO_SCORE_LABEL + NO_LONGER_THAN_LITERAL + FICO_SCORE_WIDTH
                        + CHARACTERS_LITERAL);
        assertThat(NumericRequiredValidator
                .validate(FICO_SCORE_LABEL, "750" + " ".repeat(EDIT_FIELD_HOST_WIDTH),
                        FICO_SCORE_WIDTH).valid())
                .isTrue();
        assertThat(NumericRequiredValidator
                .validate(FICO_SCORE_LABEL, wider, wider.length()).message())
                .isEqualTo(FICO_SCORE_LABEL + NOT_ALL_NUMERIC_LITERAL);
    }

    @Test
    @DisplayName("The not-zero check carries the eighteen-digit conversion ceiling")
    void theNotZeroCheckCarriesTheEighteenDigitConversionCeiling() {
        // The conversion at app/cbl/COACTUPC.cbl:L2156 runs under ARITH(COMPAT), which
        // app/cbl/COACTUPC.cbl names no option against, and NumvalParser.MAXIMUM_DIGITS holds the
        // matching ceiling. Every one of the six call-site widths sits below it, so no migrated
        // path reaches the throw.
        int ceiling = NumvalParser.MAXIMUM_DIGITS;

        assertThat(NumericRequiredValidator
                .validate(FICO_SCORE_LABEL, "1".repeat(ceiling), ceiling).valid()).isTrue();
        assertThat(NumericRequiredValidator
                .validate(FICO_SCORE_LABEL, "0".repeat(ceiling), ceiling).message())
                .isEqualTo(FICO_SCORE_LABEL + IS_ZERO_LITERAL);
        assertThatThrownBy(() -> NumericRequiredValidator
                .validate(FICO_SCORE_LABEL, "1".repeat(ceiling + 1), ceiling + 1))
                .isInstanceOf(NumberFormatException.class);

        assertThat(CALL_SITE_WIDTHS).hasSize(6).allSatisfy(width ->
                assertThat(width).isLessThan(ceiling));
        assertThat(EDIT_FIELD_HOST_WIDTH).isGreaterThan(ceiling);
    }

    @Test
    @DisplayName("The subject exposes one static three-argument method on a final class")
    void theSubjectExposesOneStaticThreeArgumentMethodOnAFinalClass()
            throws NoSuchMethodException {
        // app/cbl/COACTUPC.cbl:L1545-L1548 moves a label, then a value, then a width, and the
        // parameter order follows those three moves. A reordered signature, an added overload or a
        // visible constructor fails one of the assertions below.
        assertThat(Modifier.isFinal(NumericRequiredValidator.class.getModifiers())).isTrue();
        assertThat(NumericRequiredValidator.class.getDeclaredMethods())
                .filteredOn(method -> Modifier.isPublic(method.getModifiers()))
                .hasSize(1);

        Method validate = NumericRequiredValidator.class
                .getMethod("validate", String.class, String.class, int.class);

        assertThat(Modifier.isStatic(validate.getModifiers())).isTrue();
        assertThat(validate.getReturnType()).isEqualTo(EditResult.class);
        assertThat(validate.getParameters()[0].getName()).isEqualTo("fieldLabel");
        assertThat(validate.getParameters()[1].getName()).isEqualTo("value");
        assertThat(validate.getParameters()[2].getName()).isEqualTo("length");

        Constructor<?>[] constructors =
                NumericRequiredValidator.class.getDeclaredConstructors();

        assertThat(constructors).hasSize(1);
        assertThat(Modifier.isPrivate(constructors[0].getModifiers())).isTrue();
    }

    @Test
    @DisplayName("The call changes no argument and repeats its verdict")
    void theCallChangesNoArgumentAndRepeatsItsVerdict() {
        // The paragraph writes to WS-EDIT-ALPHANUM-ONLY-FLAGS and to the message slot at
        // app/cbl/COACTUPC.cbl:L479, and it writes to neither the label nor the value. Two calls on
        // one pair of arguments therefore agree.
        String label = PADDED_LABEL;
        String value = CURRENCY_AMOUNT;

        EditResult first = NumericRequiredValidator.validate(label, value, EFT_ACCOUNT_WIDTH);
        EditResult second = NumericRequiredValidator.validate(label, value, EFT_ACCOUNT_WIDTH);

        assertThat(label).hasSize(LABEL_HOST_WIDTH);
        assertThat(value).isEqualTo("$1,234.56");
        assertThat(first).isEqualTo(second);
        assertThat(first.message()).isEqualTo(FICO_SCORE_LABEL + NOT_ALL_NUMERIC_LITERAL);
    }

    /**
     * Asserts the verdict paragraph 1245 sets at app/cbl/COACTUPC.cbl:L2121-L2122 and the message
     * it builds at app/cbl/COACTUPC.cbl:L2124-L2128.
     *
     * @param result the verdict under test
     * @param label  the label the caller supplied, already trimmed
     */
    private static void assertNotSupplied(EditResult result, String label) {
        assertThat(result.valid()).isFalse();
        assertThat(result.hasMessage()).isTrue();
        assertThat(result.message()).isEqualTo(label + NOT_SUPPLIED_LITERAL);
        assertThat(result.message()).containsOnlyOnce(NOT_SUPPLIED_LITERAL);
    }

    /**
     * Asserts the verdict paragraph 1245 sets at app/cbl/COACTUPC.cbl:L2141-L2142 and the message
     * it builds at app/cbl/COACTUPC.cbl:L2144-L2148.
     *
     * @param result the verdict under test
     * @param label  the label the caller supplied, already trimmed
     */
    private static void assertNotAllNumeric(EditResult result, String label) {
        assertThat(result.valid()).isFalse();
        assertThat(result.hasMessage()).isTrue();
        assertThat(result.message()).isEqualTo(label + NOT_ALL_NUMERIC_LITERAL);
        assertThat(result.message()).containsOnlyOnce(NOT_ALL_NUMERIC_LITERAL);
    }
}
