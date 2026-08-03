package com.carddemo.account.domain.validation;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Tests for {@link UsSocialSecurityNumberValidator}, which realises paragraph
 * {@code 1265-EDIT-US-SSN} at app/cbl/COACTUPC.cbl:L2431. One call site reaches the paragraph, at
 * app/cbl/COACTUPC.cbl:L1530. The label that call site moves at app/cbl/COACTUPC.cbl:L1529 is
 * {@code 'SSN'}, and the paragraph overwrites it three times, at app/cbl/COACTUPC.cbl:L2439,
 * app/cbl/COACTUPC.cbl:L2469 and app/cbl/COACTUPC.cbl:L2481. Tests below pin all three internal
 * labels and assert that the caller label never reaches a message.
 *
 * <p>The paragraph opens three {@code IF} statements and closes two, at
 * app/cbl/COACTUPC.cbl:L2463 and app/cbl/COACTUPC.cbl:L2464, so the period at
 * app/cbl/COACTUPC.cbl:L2488 closes the gate at app/cbl/COACTUPC.cbl:L2448. Part two and part three
 * are edited only once part one has cleared its numeric edit, and one test pins that shape.</p>
 *
 * <p>Each part reaches {@code 1245-EDIT-NUM-REQD} at app/cbl/COACTUPC.cbl:L2109 under widths 3, 2
 * and 4. That paragraph pads short content with spaces, and a space fails its
 * {@code IS NUMERIC} test at app/cbl/COACTUPC.cbl:L2137. Its not-zero test at
 * app/cbl/COACTUPC.cbl:L2156 refuses part two of {@code 00} and part three of {@code 0000}, which
 * is the whole of the range the header comments at app/cbl/COACTUPC.cbl:L2434-L2435 describe.</p>
 *
 * <p>The excluded-value condition {@code INVALID-SSN-PART1} at app/cbl/COACTUPC.cbl:L121-L123 names
 * {@code 0}, {@code 666} and the band {@code 900 THRU 999}. A part one of {@code 000} clears the
 * class test and then fails the not-zero test, so the gate closes ahead of the condition and the
 * {@code 0} value never selects.</p>
 *
 * <p>Every input below is a literal written in this class. No test reads a fixture file, opens a
 * Spring context, starts a container or substitutes a stand-in for the delegate, so the suite runs
 * on a clean machine.</p>
 */
@DisplayName("UsSocialSecurityNumberValidator, the three part Social Security Number edit")
class UsSocialSecurityNumberValidatorTest {

    /** Label literal at app/cbl/COACTUPC.cbl:L2439, the only one carrying a colon. */
    private static final String PART1_LABEL = "SSN: First 3 chars";

    /** Label literal at app/cbl/COACTUPC.cbl:L2469, the only one carrying an ampersand. */
    private static final String PART2_LABEL = "SSN 4th & 5th chars";

    /** Label literal at app/cbl/COACTUPC.cbl:L2481. */
    private static final String PART3_LABEL = "SSN Last 4 chars";

    /** Label the call site moves at app/cbl/COACTUPC.cbl:L1529, which no message carries. */
    private static final String CALLER_LABEL = "SSN";

    /** Literal at app/cbl/COACTUPC.cbl:L2457, opening with a colon and closing with no period. */
    private static final String EXCLUDED_VALUE = ": should not be 000, 666, or between 900 and 999";

    /** Literal at app/cbl/COACTUPC.cbl:L2126, opening with a space. */
    private static final String NOT_SUPPLIED = " must be supplied.";

    /** Literal at app/cbl/COACTUPC.cbl:L2146, opening with a space. */
    private static final String NOT_ALL_NUMERIC = " must be all numeric.";

    /** Stored width of {@code CUST-SSN} part one, from app/cbl/COACTUPC.cbl:L2449. */
    private static final int PART1_WIDTH = 3;

    /**
     * ADDITIVE. Opens the message the delegate produces for a part wider than its stored width. No
     * source literal carries this text: {@code app/cbl/COACTUPC.cbl:L2447-L2465} moves fixed-width
     * screen fields, so a wider part cannot reach paragraph 1265.
     */
    private static final String NO_LONGER_THAN = " must be no longer than ";

    /** ADDITIVE. Closes the text {@link #NO_LONGER_THAN} opens. */
    private static final String CHARACTERS = " characters.";

    /** Literal at app/cbl/COACTUPC.cbl:L2163, opening with a space. */
    private static final String IS_ZERO = " must not be zero.";

    /** A part one outside every excluded value of app/cbl/COACTUPC.cbl:L121-L123. */
    private static final String VALID_PART1 = "123";

    /** A part two the not-zero test at app/cbl/COACTUPC.cbl:L2156 clears. */
    private static final String VALID_PART2 = "45";

    /** A part three the not-zero test at app/cbl/COACTUPC.cbl:L2156 clears. */
    private static final String VALID_PART3 = "6789";

    /** Lowest value of the excluded band at app/cbl/COACTUPC.cbl:L123. */
    private static final int BAND_LOWEST = 900;

    /** Highest value of the excluded band at app/cbl/COACTUPC.cbl:L123. */
    private static final int BAND_HIGHEST = 999;

    @Test
    @DisplayName("Three well formed parts clear every test and carry no message")
    void acceptsThreeWellFormedParts() {
        EditResult result =
                UsSocialSecurityNumberValidator.validate(VALID_PART1, VALID_PART2, VALID_PART3);

        assertThat(result.valid()).isTrue();
        assertThat(result.message()).isNull();
        assertThat(result.hasMessage()).isFalse();
    }

    @Test
    @DisplayName("A part one of 666 yields the app/cbl/COACTUPC.cbl:L2457 message character for character")
    void refusesSixSixSixWithTheExcludedValueMessage() {
        EditResult result =
                UsSocialSecurityNumberValidator.validate("666", VALID_PART2, VALID_PART3);

        assertThat(result.valid()).isFalse();

        // app/cbl/COACTUPC.cbl:L2456 places the trimmed label ahead of the literal, so the text
        // holds two colons and closes with no period.
        assertThat(result.message())
                .isEqualTo("SSN: First 3 chars: should not be 000, 666, or between 900 and 999");
        assertThat(result.message()).isEqualTo(PART1_LABEL + EXCLUDED_VALUE);
        assertThat(result.message()).doesNotEndWith(".");
    }

    @ParameterizedTest
    @ValueSource(strings = {"900", "901", "950", "998", "999"})
    @DisplayName("A part one inside the app/cbl/COACTUPC.cbl:L123 band is refused, ends included")
    void refusesThePartOneBand(String part1) {
        EditResult result =
                UsSocialSecurityNumberValidator.validate(part1, VALID_PART2, VALID_PART3);

        assertThat(result.valid()).as("part one %s", part1).isFalse();
        assertThat(result.message()).as("part one %s", part1).isEqualTo(PART1_LABEL + EXCLUDED_VALUE);
    }

    @Test
    @DisplayName("Every one of the hundred values of the app/cbl/COACTUPC.cbl:L123 band is refused")
    void refusesEveryValueOfThePartOneBand() {
        for (int value = BAND_LOWEST; value <= BAND_HIGHEST; value++) {
            String part1 = String.valueOf(value);

            EditResult result =
                    UsSocialSecurityNumberValidator.validate(part1, VALID_PART2, VALID_PART3);

            assertThat(result.valid()).as("part one %s", part1).isFalse();
            assertThat(result.message())
                    .as("part one %s", part1)
                    .isEqualTo(PART1_LABEL + EXCLUDED_VALUE);
        }
    }

    @Test
    @DisplayName("A part one of 899 sits below the band at app/cbl/COACTUPC.cbl:L123 and passes")
    void acceptsThePartOneBelowTheBand() {
        EditResult result =
                UsSocialSecurityNumberValidator.validate("899", VALID_PART2, VALID_PART3);

        assertThat(result.valid()).isTrue();
        assertThat(result.message()).isNull();
    }

    @Test
    @DisplayName("A part one of 000 fails the app/cbl/COACTUPC.cbl:L2156 not-zero test, leaving the "
            + "first value of app/cbl/COACTUPC.cbl:L121 unreachable")
    void refusesThreeZeroDigitsThroughTheNotZeroTest() {
        EditResult result =
                UsSocialSecurityNumberValidator.validate("000", VALID_PART2, VALID_PART3);

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).isEqualTo(PART1_LABEL + IS_ZERO);
        assertThat(result.message()).doesNotContain(EXCLUDED_VALUE);
    }

    @Test
    @DisplayName("A failing part one and a failing part two report part one, since the gate at "
            + "app/cbl/COACTUPC.cbl:L2448 leaves part two unedited")
    void reportsPartOneAndNeverPartTwoWhenPartOneFails() {
        EditResult result = UsSocialSecurityNumberValidator.validate("abc", "00", "0000");

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).isEqualTo(PART1_LABEL + NOT_ALL_NUMERIC);
        assertThat(result.message()).doesNotContain(PART2_LABEL);
        assertThat(result.message()).doesNotContain(PART3_LABEL);
    }

    @Test
    @DisplayName("A valid part two reaches its edit once the gate at app/cbl/COACTUPC.cbl:L2448 opens")
    void editsPartTwoOnceTheGateOpens() {
        EditResult result = UsSocialSecurityNumberValidator.validate(VALID_PART1, "0", VALID_PART3);

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).isEqualTo(PART2_LABEL + NOT_ALL_NUMERIC);
    }

    @Test
    @DisplayName("The excluded value of part one supplies the message ahead of a failing part two "
            + "and part three, matching the one slot at app/cbl/COACTUPC.cbl:L480")
    void keepsTheFirstMessageWhenSeveralTestsFail() {
        EditResult result = UsSocialSecurityNumberValidator.validate("912", "00", "0000");

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).isEqualTo(PART1_LABEL + EXCLUDED_VALUE);
        assertThat(result.message()).doesNotContain(IS_ZERO);
    }

    @Test
    @DisplayName("A verdict carries one message and never a joined list of them")
    void carriesOneMessageOnly() {
        EditResult result = UsSocialSecurityNumberValidator.validate("912", "00", "0000");

        assertThat(result.hasMessage()).isTrue();
        assertThat(result.message()).doesNotContain(System.lineSeparator());
        assertThat(result.message()).doesNotContain(";");
        assertThat(result.message()).isEqualTo(PART1_LABEL + EXCLUDED_VALUE);
    }

    @Test
    @DisplayName("Each part reports under its own internal label, and the caller label at "
            + "app/cbl/COACTUPC.cbl:L1529 reaches no message")
    void usesTheThreeInternalLabelsAndNeverTheCallerLabel() {
        EditResult partOne = UsSocialSecurityNumberValidator.validate("", VALID_PART2, VALID_PART3);
        EditResult partTwo = UsSocialSecurityNumberValidator.validate(VALID_PART1, "", VALID_PART3);
        EditResult partThree = UsSocialSecurityNumberValidator.validate(VALID_PART1, VALID_PART2, "");

        assertThat(partOne.message()).isEqualTo("SSN: First 3 chars" + NOT_SUPPLIED);
        assertThat(partTwo.message()).isEqualTo("SSN 4th & 5th chars" + NOT_SUPPLIED);
        assertThat(partThree.message()).isEqualTo("SSN Last 4 chars" + NOT_SUPPLIED);

        assertThat(PART2_LABEL).contains("&");
        assertThat(partTwo.message()).contains("&");

        for (EditResult result : new EditResult[] {partOne, partTwo, partThree}) {
            assertThat(List.of(PART1_LABEL, PART2_LABEL, PART3_LABEL))
                    .as("message [%s] opens with an internal label", result.message())
                    .anyMatch(label -> result.message().startsWith(label));
            assertThat(result.message()).isNotEqualTo(CALLER_LABEL + NOT_SUPPLIED);
        }
    }

    @Test
    @DisplayName("No message repeats a supplied part or the nine characters they form")
    void keepsSuppliedCharactersOutOfEveryMessage() {
        assertNoSuppliedCharacters("912", "78", "6543");
        assertNoSuppliedCharacters("87", "78", "6543");
        assertNoSuppliedCharacters(VALID_PART1, "7", "6543");
        assertNoSuppliedCharacters(VALID_PART1, "78", "654");
    }

    /**
     * Asserts that three parts fail the edit and that the message repeats none of them.
     *
     * @param part1 the first part, which this method supplies to the edit
     * @param part2 the second part, which this method supplies to the edit
     * @param part3 the third part, which this method supplies to the edit
     */
    private static void assertNoSuppliedCharacters(String part1, String part2, String part3) {
        EditResult result = UsSocialSecurityNumberValidator.validate(part1, part2, part3);

        assertThat(result.valid()).as("parts %s %s %s", part1, part2, part3).isFalse();
        assertThat(result.message())
                .as("message [%s]", result.message())
                .doesNotContain(part1)
                .doesNotContain(part2)
                .doesNotContain(part3)
                .doesNotContain(part1 + part2 + part3);
    }

    @Test
    @DisplayName("A part two of two zero digits is refused by the not-zero test at "
            + "app/cbl/COACTUPC.cbl:L2156 and by no range test")
    void refusesPartTwoOfTwoZeroDigitsThroughTheNotZeroTest() {
        EditResult result = UsSocialSecurityNumberValidator.validate(VALID_PART1, "00", VALID_PART3);

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).isEqualTo(PART2_LABEL + IS_ZERO);
        assertThat(result.message()).doesNotContain(EXCLUDED_VALUE);
    }

    @Test
    @DisplayName("A part three of four zero digits is refused by the not-zero test at "
            + "app/cbl/COACTUPC.cbl:L2156 and by no range test")
    void refusesPartThreeOfFourZeroDigitsThroughTheNotZeroTest() {
        EditResult result = UsSocialSecurityNumberValidator.validate(VALID_PART1, VALID_PART2, "0000");

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).isEqualTo(PART3_LABEL + IS_ZERO);
        assertThat(result.message()).doesNotContain(EXCLUDED_VALUE);
    }

    @Test
    @DisplayName("The ranges the comments at app/cbl/COACTUPC.cbl:L2434-L2435 describe are exactly "
            + "what the not-zero test admits at widths two and four")
    void admitsTheRangesTheHeaderCommentsDescribe() {
        assertThat(UsSocialSecurityNumberValidator.validate(VALID_PART1, "01", "0001").valid())
                .isTrue();
        assertThat(UsSocialSecurityNumberValidator.validate(VALID_PART1, "99", "9999").valid())
                .isTrue();
        assertThat(UsSocialSecurityNumberValidator.validate(VALID_PART1, "00", "0001").valid())
                .isFalse();
        assertThat(UsSocialSecurityNumberValidator.validate(VALID_PART1, "01", "0000").valid())
                .isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "12"})
    @DisplayName("A part one shorter than its stored width fails the class test at "
            + "app/cbl/COACTUPC.cbl:L2137 on the space padding")
    void refusesAShortPartOneOnItsPadding(String part1) {
        EditResult result =
                UsSocialSecurityNumberValidator.validate(part1, VALID_PART2, VALID_PART3);

        assertThat(result.valid()).as("part one %s", part1).isFalse();
        assertThat(result.message()).as("part one %s", part1).isEqualTo(PART1_LABEL + NOT_ALL_NUMERIC);
    }

    @Test
    @DisplayName("A part one past its stored width is refused rather than cut back to three "
            + "characters, which the MOVE at app/cbl/COACTUPC.cbl:L2449 cannot receive")
    void refusesAPartOnePastItsStoredWidth() {
        EditResult wider =
                UsSocialSecurityNumberValidator.validate("1234", VALID_PART2, VALID_PART3);
        EditResult widerAndExcluded =
                UsSocialSecurityNumberValidator.validate("9124", VALID_PART2, VALID_PART3);

        assertThat(wider.valid()).isFalse();
        assertThat(wider.message()).isEqualTo(PART1_LABEL + NO_LONGER_THAN + PART1_WIDTH
                + CHARACTERS);
        assertThat(widerAndExcluded.valid()).isFalse();
        assertThat(widerAndExcluded.message()).isEqualTo(PART1_LABEL + NO_LONGER_THAN + PART1_WIDTH
                + CHARACTERS);
    }

    @Test
    @DisplayName("A second call on the same parts returns the same verdict")
    void returnsTheSameVerdictOnASecondCall() {
        EditResult first = UsSocialSecurityNumberValidator.validate("666", "00", "0000");
        EditResult second = UsSocialSecurityNumberValidator.validate("666", "00", "0000");

        assertThat(second).isEqualTo(first);
        assertThat(UsSocialSecurityNumberValidator.validate(VALID_PART1, VALID_PART2, VALID_PART3))
                .isEqualTo(
                        UsSocialSecurityNumberValidator.validate(
                                VALID_PART1, VALID_PART2, VALID_PART3));
    }

    @Test
    @DisplayName("No combination of absent parts throws, and each yields the not-supplied message")
    void reportsAbsentPartsAndThrowsForNone() {
        assertThatCode(() -> UsSocialSecurityNumberValidator.validate(null, null, null))
                .doesNotThrowAnyException();
        assertThatCode(() -> UsSocialSecurityNumberValidator.validate("", "", ""))
                .doesNotThrowAnyException();
        assertThatCode(() -> UsSocialSecurityNumberValidator.validate("   ", "  ", "    "))
                .doesNotThrowAnyException();

        assertThat(UsSocialSecurityNumberValidator.validate(null, null, null).message())
                .isEqualTo(PART1_LABEL + NOT_SUPPLIED);
        assertThat(UsSocialSecurityNumberValidator.validate("   ", "  ", "    ").message())
                .isEqualTo(PART1_LABEL + NOT_SUPPLIED);
        assertThat(UsSocialSecurityNumberValidator.validate(VALID_PART1, null, VALID_PART3).message())
                .isEqualTo(PART2_LABEL + NOT_SUPPLIED);
        assertThat(UsSocialSecurityNumberValidator.validate(VALID_PART1, VALID_PART2, null).message())
                .isEqualTo(PART3_LABEL + NOT_SUPPLIED);
    }
}
