package com.carddemo.account.domain.validation;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Tests for {@link UsSocialSecurityNumberValidator}, which realises paragraph
 * {@code 1265-EDIT-US-SSN} at app/cbl/COACTUPC.cbl:L2431. The exit paragraph sits at
 * app/cbl/COACTUPC.cbl:L2489, and one call site performs the paragraph, at
 * app/cbl/COACTUPC.cbl:L1530.
 *
 * <p>Four checks run in sequence, and no {@code GO TO} appears between
 * app/cbl/COACTUPC.cbl:L2431 and app/cbl/COACTUPC.cbl:L2488. Part one takes the numeric edit at
 * app/cbl/COACTUPC.cbl:L2442 and the excluded-value test at app/cbl/COACTUPC.cbl:L2450. Part two
 * takes its numeric edit at app/cbl/COACTUPC.cbl:L2472, and part three takes its own at
 * app/cbl/COACTUPC.cbl:L2484. All three numeric edits reach paragraph
 * {@code 1245-EDIT-NUM-REQD} at app/cbl/COACTUPC.cbl:L2109, under the widths that
 * app/cbl/COACTUPC.cbl:L2441, app/cbl/COACTUPC.cbl:L2471 and app/cbl/COACTUPC.cbl:L2483 move.</p>
 *
 * <p>The message slot {@code WS-RETURN-MSG PIC X(75)} at app/cbl/COACTUPC.cbl:L479 holds one
 * message. Its condition name at app/cbl/COACTUPC.cbl:L480 guards every write, and
 * app/cbl/COACTUPC.cbl:L876 clears the slot once per pass, so the earliest failing check supplies
 * the text. {@link #keepsTheEarliestFailureOfTheFourChecks()} pins that ordering, and
 * {@link #carriesOneMessageThatFitsTheSourceSlot()} pins the single slot.</p>
 *
 * <p>The scope of the gate follows from counting statements. The paragraph opens three {@code IF}
 * statements, at app/cbl/COACTUPC.cbl:L2448, app/cbl/COACTUPC.cbl:L2450 and
 * app/cbl/COACTUPC.cbl:L2454. The paragraph closes two, at app/cbl/COACTUPC.cbl:L2463 and
 * app/cbl/COACTUPC.cbl:L2464, which close app/cbl/COACTUPC.cbl:L2454 and
 * app/cbl/COACTUPC.cbl:L2450 in that order. The period at app/cbl/COACTUPC.cbl:L2488 closes the
 * gate at app/cbl/COACTUPC.cbl:L2448, so app/cbl/COACTUPC.cbl:L2469-L2487 sits inside that gate
 * and a failing part one leaves part two and part three unedited.</p>
 *
 * <p>Three labels reach the messages, and the paragraph moves each one itself:
 * {@code 'SSN: First 3 chars'} at app/cbl/COACTUPC.cbl:L2439, the ampersand-carrying
 * {@link #PART2_LABEL} at app/cbl/COACTUPC.cbl:L2469, and {@code 'SSN Last 4 chars'} at
 * app/cbl/COACTUPC.cbl:L2481. The call site moves {@code 'SSN'} at app/cbl/COACTUPC.cbl:L1529, and
 * all three moves overwrite it.</p>
 *
 * <p>Part one of {@code 000} clears the class test at app/cbl/COACTUPC.cbl:L2137 and fails the
 * not-zero test at app/cbl/COACTUPC.cbl:L2156, which closes the gate ahead of
 * app/cbl/COACTUPC.cbl:L2450. The value {@code 0} that {@code INVALID-SSN-PART1} names at
 * app/cbl/COACTUPC.cbl:L121 therefore never selects. The value {@code 666} at
 * app/cbl/COACTUPC.cbl:L122 and the band {@code 900 THRU 999} at app/cbl/COACTUPC.cbl:L123 do
 * select, and both pin the app/cbl/COACTUPC.cbl:L2457 literal character for character.</p>
 *
 * <p>Every input is a literal written in this class. No test opens a Spring context, starts a
 * container or reads a fixture file. No test substitutes a stand-in for the delegate.</p>
 */
@DisplayName("UsSocialSecurityNumberValidator, the three part Social Security Number edit")
class UsSocialSecurityNumberValidatorTest {

    /** Label literal at app/cbl/COACTUPC.cbl:L2439, the only one of the three carrying a colon. */
    private static final String PART1_LABEL = "SSN: First 3 chars";

    /** Label literal at app/cbl/COACTUPC.cbl:L2469, the only one of the three with an ampersand. */
    private static final String PART2_LABEL = "SSN 4th & 5th chars";

    /** Label literal at app/cbl/COACTUPC.cbl:L2481. */
    private static final String PART3_LABEL = "SSN Last 4 chars";

    /** Label the call site moves at app/cbl/COACTUPC.cbl:L1529, which the paragraph overwrites. */
    private static final String CALLER_LABEL = "SSN";

    /**
     * Literal at app/cbl/COACTUPC.cbl:L2457. The text opens with a colon and a space and closes
     * with no period, one of the no-period literals of that source file.
     */
    private static final String EXCLUDED_VALUE = ": should not be 000, 666, or between 900 and 999";

    /** Literal at app/cbl/COACTUPC.cbl:L2126, opening with a space and closing with a period. */
    private static final String NOT_SUPPLIED = " must be supplied.";

    /** Literal at app/cbl/COACTUPC.cbl:L2146, opening with a space and closing with a period. */
    private static final String NOT_ALL_NUMERIC = " must be all numeric.";

    /** Literal at app/cbl/COACTUPC.cbl:L2163, opening with a space and closing with a period. */
    private static final String IS_ZERO = " must not be zero.";

    /**
     * ADDITIVE. Opens the message the delegate produces for a part wider than its stored width. No
     * source literal carries the text. app/cbl/COACTUPC.cbl:L2440, app/cbl/COACTUPC.cbl:L2470 and
     * app/cbl/COACTUPC.cbl:L2482 move fixed-width screen fields, so a wider part cannot reach
     * paragraph 1265 through the one call site at app/cbl/COACTUPC.cbl:L1530.
     */
    private static final String NO_LONGER_THAN = " must be no longer than ";

    /** ADDITIVE. Closes the text {@link #NO_LONGER_THAN} opens. */
    private static final String CHARACTERS = " characters.";

    /** Width the move at app/cbl/COACTUPC.cbl:L2441 supplies for part one. */
    private static final int PART1_WIDTH = 3;

    /** Width the move at app/cbl/COACTUPC.cbl:L2471 supplies for part two. */
    private static final int PART2_WIDTH = 2;

    /** Width the move at app/cbl/COACTUPC.cbl:L2483 supplies for part three. */
    private static final int PART3_WIDTH = 4;

    /** Storage width of the message slot at app/cbl/COACTUPC.cbl:L479. */
    private static final int MESSAGE_SLOT_WIDTH = 75;

    /**
     * Salt drawn once for the run. No assertion prints it, so a printed digest identifies a case
     * across two assertions without carrying the characters that case supplied.
     */
    private static final byte[] CASE_SALT = newCaseSalt();

    /** A part one outside every value {@code INVALID-SSN-PART1} names at L121-L123. */
    private static final String VALID_PART1 = "123";

    /** A part two the not-zero test at app/cbl/COACTUPC.cbl:L2156 clears. */
    private static final String VALID_PART2 = "45";

    /** A part three the not-zero test at app/cbl/COACTUPC.cbl:L2156 clears. */
    private static final String VALID_PART3 = "6789";

    /** The second value {@code INVALID-SSN-PART1} names, at app/cbl/COACTUPC.cbl:L122. */
    private static final String EXCLUDED_SIX_SIX_SIX = "666";

    /** Lowest value of the band {@code 900 THRU 999} at app/cbl/COACTUPC.cbl:L123. */
    private static final int BAND_LOWEST = 900;

    /** Highest value of the band {@code 900 THRU 999} at app/cbl/COACTUPC.cbl:L123. */
    private static final int BAND_HIGHEST = 999;

    /** The value one below the band at app/cbl/COACTUPC.cbl:L123. */
    private static final String BELOW_BAND = "899";

    /** The value two below the band at app/cbl/COACTUPC.cbl:L123. */
    private static final String WELL_BELOW_BAND = "898";

    /** Widest value the {@code PIC 9(3)} redefine at app/cbl/COACTUPC.cbl:L120 reads. */
    private static final int PART1_VALUE_CEILING = 999;

    /**
     * Part one of a synthetic three-part input, built only from the digits 7, 8 and 1. The value is
     * assembled for this test and matches no row of {@code app/data/ASCII/custdata.txt}. No message
     * the edit can produce carries any of those three characters, so a per-character assertion holds
     * across every failure path the three parts reach. The assembled nine-digit form is deliberately
     * written nowhere in this file.
     */
    private static final String DISTINCTIVE_PART1 = "781";

    /** Part two of that synthetic input, two digits drawn from the same three characters. */
    private static final String DISTINCTIVE_PART2 = "17";

    /** Part three of that synthetic input, four digits drawn from the same three characters. */
    private static final String DISTINCTIVE_PART3 = "8171";

    @Test
    @DisplayName("Three well formed parts clear all four checks and carry no message")
    void acceptsThreeWellFormedParts() {
        EditResult result =
                UsSocialSecurityNumberValidator.validate(VALID_PART1, VALID_PART2, VALID_PART3);

        assertThat(result.valid()).isTrue();
        assertThat(result.message()).isNull();
        assertThat(result.hasMessage()).isFalse();
    }

    @Test
    @DisplayName("A part one of 666 yields the app/cbl/COACTUPC.cbl:L2457 message character for "
            + "character, closing with no period")
    void refusesSixSixSixWithTheExcludedValueMessage() {
        EditResult result = UsSocialSecurityNumberValidator.validate(
                EXCLUDED_SIX_SIX_SIX, VALID_PART2, VALID_PART3);

        assertThat(result.valid()).isFalse();
        assertThat(result.hasMessage()).isTrue();

        // app/cbl/COACTUPC.cbl:L2456 places the trimmed part one label ahead of the literal, so the
        // text carries two colons.
        assertThat(result.message())
                .isEqualTo("SSN: First 3 chars: should not be 000, 666, or between 900 and 999");
        assertThat(result.message()).isEqualTo(PART1_LABEL + EXCLUDED_VALUE);
        assertThat(result.message()).doesNotEndWith(".");
        assertThat(EXCLUDED_VALUE).startsWith(": ");
        assertThat(EXCLUDED_VALUE).doesNotEndWith(".");
    }

    @ParameterizedTest
    @ValueSource(strings = {"900", "901", "950", "998", "999"})
    @DisplayName("A part one inside the app/cbl/COACTUPC.cbl:L123 band is refused at both ends and "
            + "inside")
    void refusesThePartOneBandAtBothEndsAndInside(String part1) {
        EditResult result =
                UsSocialSecurityNumberValidator.validate(part1, VALID_PART2, VALID_PART3);

        assertThat(result.valid()).as("part one, %s", reference(part1)).isFalse();
        assertThat(result.message())
                .as("part one, %s", reference(part1))
                .isEqualTo(PART1_LABEL + EXCLUDED_VALUE);
    }

    @Test
    @DisplayName("Every one of the hundred values of the app/cbl/COACTUPC.cbl:L123 band is refused")
    void refusesEveryValueOfThePartOneBand() {
        for (int value = BAND_LOWEST; value <= BAND_HIGHEST; value++) {
            String part1 = String.valueOf(value);

            EditResult result =
                    UsSocialSecurityNumberValidator.validate(part1, VALID_PART2, VALID_PART3);

            // The band ordinal would restate the value, since app/cbl/COACTUPC.cbl:L123 fixes the
            // lowest member, so the digest alone tells two failing members apart.
            assertThat(result.valid())
                    .as("band member, %s", reference(part1))
                    .isFalse();
            assertThat(result.message())
                    .as("band member, %s", reference(part1))
                    .isEqualTo(PART1_LABEL + EXCLUDED_VALUE);
        }
    }

    @Test
    @DisplayName("A part one of 899 sits one below the app/cbl/COACTUPC.cbl:L123 band and passes, "
            + "while 900 is refused")
    void acceptsThePartOneJustBelowTheBand() {
        EditResult wellBelow =
                UsSocialSecurityNumberValidator.validate(WELL_BELOW_BAND, VALID_PART2, VALID_PART3);
        EditResult justBelow =
                UsSocialSecurityNumberValidator.validate(BELOW_BAND, VALID_PART2, VALID_PART3);
        EditResult lowestOfBand = UsSocialSecurityNumberValidator.validate(
                String.valueOf(BAND_LOWEST), VALID_PART2, VALID_PART3);

        assertThat(wellBelow.valid()).isTrue();
        assertThat(wellBelow.message()).isNull();
        assertThat(justBelow.valid()).isTrue();
        assertThat(justBelow.message()).isNull();
        assertThat(lowestOfBand.valid()).isFalse();
        assertThat(lowestOfBand.message()).isEqualTo(PART1_LABEL + EXCLUDED_VALUE);
    }

    @Test
    @DisplayName("Across all thousand three digit values, the app/cbl/COACTUPC.cbl:L2457 message "
            + "reaches 666 and the band alone")
    void sweepsEveryThreeDigitPartOne() {
        for (int value = 0; value <= PART1_VALUE_CEILING; value++) {
            String part1 = String.format("%03d", value);

            EditResult result =
                    UsSocialSecurityNumberValidator.validate(part1, VALID_PART2, VALID_PART3);

            if (value == 0) {
                // app/cbl/COACTUPC.cbl:L2156 answers first, so app/cbl/COACTUPC.cbl:L2457 is out
                // of reach for the first value app/cbl/COACTUPC.cbl:L121 names.
                assertThat(result.valid())
                        .as("not-zero branch, %s", reference(part1)).isFalse();
                assertThat(result.message())
                        .as("not-zero branch, %s", reference(part1))
                        .isEqualTo(PART1_LABEL + IS_ZERO);
            } else if (part1.equals(EXCLUDED_SIX_SIX_SIX) || value >= BAND_LOWEST) {
                assertThat(result.valid())
                        .as("excluded-value branch, %s", reference(part1)).isFalse();
                assertThat(result.message())
                        .as("excluded-value branch, %s", reference(part1))
                        .isEqualTo(PART1_LABEL + EXCLUDED_VALUE);
            } else {
                assertThat(result.valid())
                        .as("accepted branch, %s", reference(part1)).isTrue();
                assertThat(result.message())
                        .as("accepted branch, %s", reference(part1)).isNull();
            }
        }
    }

    @Test
    @DisplayName("A part one of 000 is refused by the app/cbl/COACTUPC.cbl:L2156 not-zero test, "
            + "which leaves the first value of app/cbl/COACTUPC.cbl:L121 out of reach")
    void refusesThreeZeroDigitsThroughTheNotZeroTest() {
        EditResult result =
                UsSocialSecurityNumberValidator.validate("000", VALID_PART2, VALID_PART3);

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).isEqualTo(PART1_LABEL + IS_ZERO);
        assertThat(result.message()).doesNotContain(EXCLUDED_VALUE);
    }

    @Test
    @DisplayName("A failing part one and a failing part two report part one alone, since the gate "
            + "at app/cbl/COACTUPC.cbl:L2448 leaves part two and part three unedited")
    void reportsPartOneAloneWhenPartOneAndPartTwoBothFail() {
        EditResult result = UsSocialSecurityNumberValidator.validate("abc", "00", "0000");

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).isEqualTo(PART1_LABEL + NOT_ALL_NUMERIC);
        assertThat(result.message()).doesNotContain(PART2_LABEL);
        assertThat(result.message()).doesNotContain(PART3_LABEL);

        // Part two of 00 and part three of 0000 each fail app/cbl/COACTUPC.cbl:L2156 on their own,
        // and neither message appears here.
        assertThat(UsSocialSecurityNumberValidator.validate(VALID_PART1, "00", VALID_PART3).valid())
                .isFalse();
        assertThat(UsSocialSecurityNumberValidator.validate(VALID_PART1, VALID_PART2, "0000")
                .valid()).isFalse();
        assertThat(result.message()).isNotEqualTo(PART2_LABEL + IS_ZERO);
        assertThat(result.message()).isNotEqualTo(PART3_LABEL + IS_ZERO);
    }

    @Test
    @DisplayName("Part two reaches its own edit once the gate at app/cbl/COACTUPC.cbl:L2448 opens")
    void editsPartTwoOnceTheGateOpens() {
        EditResult result = UsSocialSecurityNumberValidator.validate(VALID_PART1, "0", VALID_PART3);

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).isEqualTo(PART2_LABEL + NOT_ALL_NUMERIC);
    }

    @Test
    @DisplayName("Part three reaches its own edit once the gate at app/cbl/COACTUPC.cbl:L2448 is "
            + "open")
    void editsPartThreeOnceTheGateOpens() {
        EditResult result =
                UsSocialSecurityNumberValidator.validate(VALID_PART1, VALID_PART2, "67");

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).isEqualTo(PART3_LABEL + NOT_ALL_NUMERIC);
    }

    @Test
    @DisplayName("With the excluded value, part two and part three all failing, the message is the "
            + "excluded value of part one")
    void keepsTheEarliestFailureOfTheFourChecks() {
        EditResult result = UsSocialSecurityNumberValidator.validate("912", "00", "0000");

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).isEqualTo(PART1_LABEL + EXCLUDED_VALUE);
        assertThat(result.message()).doesNotContain(IS_ZERO);
        assertThat(result.message()).doesNotContain(PART2_LABEL);
        assertThat(result.message()).doesNotContain(PART3_LABEL);
    }

    @Test
    @DisplayName("With part two and part three both failing, the message is the one of part two, "
            + "which app/cbl/COACTUPC.cbl:L2472 edits first")
    void keepsPartTwoAheadOfPartThree() {
        EditResult result = UsSocialSecurityNumberValidator.validate(VALID_PART1, "00", "0000");

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).isEqualTo(PART2_LABEL + IS_ZERO);
        assertThat(result.message()).doesNotContain(PART3_LABEL);
    }

    @Test
    @DisplayName("A verdict carries one message, never a joined list, and the text fits the slot "
            + "at app/cbl/COACTUPC.cbl:L479")
    void carriesOneMessageThatFitsTheSourceSlot() {
        List<EditResult> failures = List.of(
                UsSocialSecurityNumberValidator.validate("912", "00", "0000"),
                UsSocialSecurityNumberValidator.validate("000", VALID_PART2, VALID_PART3),
                UsSocialSecurityNumberValidator.validate("abc", "00", "0000"),
                UsSocialSecurityNumberValidator.validate(VALID_PART1, "0", VALID_PART3),
                UsSocialSecurityNumberValidator.validate(VALID_PART1, VALID_PART2, "0000"),
                UsSocialSecurityNumberValidator.validate("1234", VALID_PART2, VALID_PART3),
                UsSocialSecurityNumberValidator.validate(null, null, null));

        for (int index = 0; index < failures.size(); index++) {
            EditResult failure = failures.get(index);
            String message = failure.message();

            assertThat(failure.valid()).isFalse();
            assertThat(failure.hasMessage()).isTrue();

            // The subjects below are derived counts, so a failure names a length or a count and
            // prints no message text.
            assertThat(message.length())
                    .as("failure %d of %d, message length", index + 1, failures.size())
                    .isLessThanOrEqualTo(MESSAGE_SLOT_WIDTH);
            assertThat(countOf(message, '\n'))
                    .as("failure %d of %d, line feeds", index + 1, failures.size())
                    .isZero();
            assertThat(countOf(message, '\r'))
                    .as("failure %d of %d, carriage returns", index + 1, failures.size())
                    .isZero();
            assertThat(countOf(message, ';'))
                    .as("failure %d of %d, semicolons", index + 1, failures.size())
                    .isZero();
        }
    }

    @Test
    @DisplayName("Each part reports under its own internal label, and the caller label at "
            + "app/cbl/COACTUPC.cbl:L1529 reaches no message")
    void usesTheThreeInternalLabelsAndNeverTheCallerLabel() {
        EditResult partOne = UsSocialSecurityNumberValidator.validate("", VALID_PART2, VALID_PART3);
        EditResult partTwo = UsSocialSecurityNumberValidator.validate(VALID_PART1, "", VALID_PART3);
        EditResult partThree =
                UsSocialSecurityNumberValidator.validate(VALID_PART1, VALID_PART2, "");

        assertThat(partOne.message()).isEqualTo("SSN: First 3 chars" + NOT_SUPPLIED);
        assertThat(partTwo.message()).isEqualTo("SSN 4th & 5th chars" + NOT_SUPPLIED);
        assertThat(partThree.message()).isEqualTo("SSN Last 4 chars" + NOT_SUPPLIED);

        // app/cbl/COACTUPC.cbl:L2469 spells the second label with an ampersand.
        assertThat(PART2_LABEL).isEqualTo("SSN 4th & 5th chars");
        assertThat(PART2_LABEL).contains("&");
        assertThat(partTwo.message()).contains("&");
        assertThat(partTwo.message()).doesNotContain(" and ");

        for (EditResult result : List.of(partOne, partTwo, partThree)) {
            assertThat(List.of(PART1_LABEL, PART2_LABEL, PART3_LABEL))
                    .as("one of the three internal labels opens the message")
                    .anyMatch(label -> result.message().startsWith(label));
            assertThat(result.message()).isNotEqualTo(CALLER_LABEL + NOT_SUPPLIED);
        }
    }

    @Test
    @DisplayName("Every message is assembled from an internal label and a source literal alone, so "
            + "no supplied part reaches it")
    void assemblesEveryMessageFromLabelsAndSourceLiteralsAlone() {
        assertMessageIsAssembled(
                UsSocialSecurityNumberValidator.validate("912", "00", "0000"),
                PART1_LABEL + EXCLUDED_VALUE);
        assertMessageIsAssembled(
                UsSocialSecurityNumberValidator.validate(EXCLUDED_SIX_SIX_SIX, "00", "0000"),
                PART1_LABEL + EXCLUDED_VALUE);
        assertMessageIsAssembled(
                UsSocialSecurityNumberValidator.validate("000", VALID_PART2, VALID_PART3),
                PART1_LABEL + IS_ZERO);
        assertMessageIsAssembled(
                UsSocialSecurityNumberValidator.validate("54321", VALID_PART2, VALID_PART3),
                PART1_LABEL + NO_LONGER_THAN + PART1_WIDTH + CHARACTERS);
        assertMessageIsAssembled(
                UsSocialSecurityNumberValidator.validate(VALID_PART1, "00", "0000"),
                PART2_LABEL + IS_ZERO);
        assertMessageIsAssembled(
                UsSocialSecurityNumberValidator.validate(VALID_PART1, VALID_PART2, "0000"),
                PART3_LABEL + IS_ZERO);
    }

    @Test
    @DisplayName("Across seven failure paths of the synthetic three-part input, no supplied "
            + "character reaches the message")
    void keepsEverySuppliedDigitOutOfEveryMessage() {
        assertNoSuppliedCharacterReachesTheMessage("", DISTINCTIVE_PART2, DISTINCTIVE_PART3);
        assertNoSuppliedCharacterReachesTheMessage("78", DISTINCTIVE_PART2, DISTINCTIVE_PART3);
        assertNoSuppliedCharacterReachesTheMessage("7811", DISTINCTIVE_PART2, DISTINCTIVE_PART3);
        assertNoSuppliedCharacterReachesTheMessage(DISTINCTIVE_PART1, "1", DISTINCTIVE_PART3);
        assertNoSuppliedCharacterReachesTheMessage(DISTINCTIVE_PART1, "171", DISTINCTIVE_PART3);
        assertNoSuppliedCharacterReachesTheMessage(DISTINCTIVE_PART1, DISTINCTIVE_PART2, "817");
        assertNoSuppliedCharacterReachesTheMessage(DISTINCTIVE_PART1, DISTINCTIVE_PART2, "81718");
    }

    @Test
    @DisplayName("No message carries the nine character value the three parts form, joined or "
            + "punctuated")
    void keepsTheJoinedNineCharacterValueOutOfEveryMessage() {
        assertNoJoinedValue("912", "00", "0000");
        assertNoJoinedValue(EXCLUDED_SIX_SIX_SIX, "00", "0000");
        assertNoJoinedValue("000", VALID_PART2, VALID_PART3);
        assertNoJoinedValue(DISTINCTIVE_PART1, "1", DISTINCTIVE_PART3);
        assertNoJoinedValue(DISTINCTIVE_PART1, DISTINCTIVE_PART2, "817");
    }

    @Test
    @DisplayName("A part two of two zero digits is refused by the app/cbl/COACTUPC.cbl:L2156 "
            + "not-zero test and by no range test")
    void refusesPartTwoOfTwoZeroDigits() {
        EditResult result =
                UsSocialSecurityNumberValidator.validate(VALID_PART1, "00", VALID_PART3);

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).isEqualTo(PART2_LABEL + IS_ZERO);
        assertThat(result.message()).doesNotContain(EXCLUDED_VALUE);
    }

    @Test
    @DisplayName("A part three of four zero digits is refused by the app/cbl/COACTUPC.cbl:L2156 "
            + "not-zero test and by no range test")
    void refusesPartThreeOfFourZeroDigits() {
        EditResult result =
                UsSocialSecurityNumberValidator.validate(VALID_PART1, VALID_PART2, "0000");

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).isEqualTo(PART3_LABEL + IS_ZERO);
        assertThat(result.message()).doesNotContain(EXCLUDED_VALUE);
    }

    @Test
    @DisplayName("Every part two the stored width holds passes, from 01 through 99, since the body "
            + "carries no range test for it")
    void admitsEveryPartTwoTheStoredWidthHolds() {
        for (int value = 1; value <= 99; value++) {
            String part2 = String.format("%02d", value);

            EditResult result =
                    UsSocialSecurityNumberValidator.validate(VALID_PART1, part2, VALID_PART3);

            assertThat(result.valid())
                    .as("part two inside the stored width, %s", reference(part2)).isTrue();
            assertThat(result.message())
                    .as("part two inside the stored width, %s", reference(part2)).isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"0001", "0002", "0099", "1000", "5000", "9998", "9999"})
    @DisplayName("Every part three the stored width holds passes, at both bounds and inside, since "
            + "the body carries no range test for it")
    void admitsEveryPartThreeBoundTheStoredWidthHolds(String part3) {
        EditResult result =
                UsSocialSecurityNumberValidator.validate(VALID_PART1, VALID_PART2, part3);

        assertThat(result.valid()).as("part three, %s", reference(part3)).isTrue();
        assertThat(result.message()).as("part three, %s", reference(part3)).isNull();
    }

    @Test
    @DisplayName("The widest part two and the widest part three pass together")
    void admitsTheWidestPartTwoAndPartThreeTogether() {
        EditResult result = UsSocialSecurityNumberValidator.validate(VALID_PART1, "99", "9999");

        assertThat(result.valid()).isTrue();
        assertThat(result.message()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "12"})
    @DisplayName("A part one shorter than its stored width fails the class test at "
            + "app/cbl/COACTUPC.cbl:L2137 on its space padding")
    void refusesAShortPartOneOnItsPadding(String part1) {
        EditResult result =
                UsSocialSecurityNumberValidator.validate(part1, VALID_PART2, VALID_PART3);

        assertThat(result.valid()).as("part one, %s", reference(part1)).isFalse();
        assertThat(result.message())
                .as("part one, %s", reference(part1))
                .isEqualTo(PART1_LABEL + NOT_ALL_NUMERIC);
    }

    @Test
    @DisplayName("A short part two and a short part three fail the class test at "
            + "app/cbl/COACTUPC.cbl:L2137 on their space padding")
    void refusesAShortPartTwoAndPartThreeOnTheirPadding() {
        EditResult shortPartTwo =
                UsSocialSecurityNumberValidator.validate(VALID_PART1, "4", VALID_PART3);
        EditResult shortPartThree =
                UsSocialSecurityNumberValidator.validate(VALID_PART1, VALID_PART2, "678");

        assertThat(shortPartTwo.message()).isEqualTo(PART2_LABEL + NOT_ALL_NUMERIC);
        assertThat(shortPartThree.message()).isEqualTo(PART3_LABEL + NOT_ALL_NUMERIC);
    }

    @Test
    @DisplayName("A part carrying content past its stored width is refused, and the excluded value "
            + "of a wider part one is never read from its first three characters")
    void refusesAPartPastItsStoredWidth() {
        EditResult widerPartOne =
                UsSocialSecurityNumberValidator.validate("1234", VALID_PART2, VALID_PART3);
        EditResult widerAndExcluded =
                UsSocialSecurityNumberValidator.validate("9124", VALID_PART2, VALID_PART3);
        EditResult widerPartTwo =
                UsSocialSecurityNumberValidator.validate(VALID_PART1, "456", VALID_PART3);
        EditResult widerPartThree =
                UsSocialSecurityNumberValidator.validate(VALID_PART1, VALID_PART2, "67890");

        assertThat(widerPartOne.message())
                .isEqualTo(PART1_LABEL + NO_LONGER_THAN + PART1_WIDTH + CHARACTERS);
        assertThat(widerAndExcluded.message())
                .isEqualTo(PART1_LABEL + NO_LONGER_THAN + PART1_WIDTH + CHARACTERS);
        assertThat(widerAndExcluded.message()).doesNotContain(EXCLUDED_VALUE);
        assertThat(widerPartTwo.message())
                .isEqualTo(PART2_LABEL + NO_LONGER_THAN + PART2_WIDTH + CHARACTERS);
        assertThat(widerPartThree.message())
                .isEqualTo(PART3_LABEL + NO_LONGER_THAN + PART3_WIDTH + CHARACTERS);
    }

    @Test
    @DisplayName("Trailing padding past the stored width is the padding the source move holds, and "
            + "it changes no verdict")
    void acceptsTrailingPaddingPastTheStoredWidth() {
        EditResult padded = UsSocialSecurityNumberValidator.validate("123 ", "45 ", "6789 ");
        EditResult paddedAndExcluded =
                UsSocialSecurityNumberValidator.validate("666 ", VALID_PART2, VALID_PART3);

        assertThat(padded.valid()).isTrue();
        assertThat(padded.message()).isNull();
        assertThat(paddedAndExcluded.valid()).isFalse();
        assertThat(paddedAndExcluded.message()).isEqualTo(PART1_LABEL + EXCLUDED_VALUE);
    }

    @Test
    @DisplayName("A second call on the same three parts returns the same verdict")
    void returnsTheSameVerdictOnASecondCall() {
        String part1 = EXCLUDED_SIX_SIX_SIX;
        String part2 = "00";
        String part3 = "0000";

        EditResult first = UsSocialSecurityNumberValidator.validate(part1, part2, part3);
        EditResult second = UsSocialSecurityNumberValidator.validate(part1, part2, part3);

        assertThat(second).isEqualTo(first);
        assertThat(part1).isEqualTo("666");
        assertThat(part2).isEqualTo("00");
        assertThat(part3).isEqualTo("0000");
        assertThat(UsSocialSecurityNumberValidator.validate(VALID_PART1, VALID_PART2, VALID_PART3))
                .isEqualTo(UsSocialSecurityNumberValidator.validate(
                        VALID_PART1, VALID_PART2, VALID_PART3));
    }

    @Test
    @DisplayName("No combination of absent parts throws, and each reports the earliest absent part")
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
        assertThat(UsSocialSecurityNumberValidator.validate(VALID_PART1, null, VALID_PART3)
                .message()).isEqualTo(PART2_LABEL + NOT_SUPPLIED);
        assertThat(UsSocialSecurityNumberValidator.validate(VALID_PART1, VALID_PART2, null)
                .message()).isEqualTo(PART3_LABEL + NOT_SUPPLIED);
    }

    @Test
    @DisplayName("The edit exposes one static call, taking the three parts and no field label")
    void exposesOneStaticCallTakingThreePartsAndNoFieldLabel() throws NoSuchMethodException {
        Class<UsSocialSecurityNumberValidator> subject = UsSocialSecurityNumberValidator.class;

        Method validate = subject.getMethod("validate", String.class, String.class, String.class);

        assertThat(Modifier.isPublic(validate.getModifiers())).isTrue();
        assertThat(Modifier.isStatic(validate.getModifiers())).isTrue();
        assertThat(validate.getReturnType()).isEqualTo(EditResult.class);
        // app/cbl/COACTUPC.cbl:L2439, app/cbl/COACTUPC.cbl:L2469 and app/cbl/COACTUPC.cbl:L2481
        // move all three labels inside the paragraph, so the signature carries no label argument.
        assertThat(validate.getParameterCount()).isEqualTo(3);
        assertThat(validate.getParameters()[0].getName()).isEqualTo("part1");
        assertThat(validate.getParameters()[1].getName()).isEqualTo("part2");
        assertThat(validate.getParameters()[2].getName()).isEqualTo("part3");
    }

    /**
     * Pins the diagnostic policy this class follows. Every assertion above identifies its case
     * through {@link #reference(String)}, and this test proves that a reference carries a character
     * count and eight hexadecimal characters and nothing else. A reference cannot therefore
     * disclose a supplied part, and two references drawn from different parts still differ.
     */
    @Test
    @DisplayName("A failing assertion identifies its case by length and salted digest alone")
    void identifiesAFailingCaseWithoutDisclosingIt() {
        String nineCharacters = "912345678";

        assertThat(reference(nineCharacters)).matches("length 9, digest [0-9a-f]{8}");
        assertThat(reference(nineCharacters)).isEqualTo(reference(nineCharacters));
        assertThat(reference(nineCharacters)).isNotEqualTo(reference("912345679"));
        assertThat(reference("")).matches("length 0, digest [0-9a-f]{8}");
        assertThat(reference(null)).isEqualTo("absent part");

        // The reference width follows the count of digits in the length alone, so no part can widen
        // it and no part can appear inside it.
        assertThat(reference("111111111").length()).isEqualTo(reference(nineCharacters).length());
    }

    /**
     * Asserts that a verdict fails and that its message equals text assembled from an internal
     * label and a source literal. Both operands are constants of this class, transcribed from
     * app/cbl/COACTUPC.cbl, so an exact match admits no supplied character.
     *
     * @param result   the verdict under test
     * @param expected the text an internal label and a source literal form
     */
    private static void assertMessageIsAssembled(EditResult result, String expected) {
        assertThat(result.valid()).isFalse();
        assertThat(result.hasMessage()).isTrue();
        assertThat(result.message())
                .as("the message equals one internal label joined to one source literal")
                .isEqualTo(expected);
    }

    private static byte[] newCaseSalt() {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        return salt;
    }

    /**
     * Counts one character in a message. A count is a safe assertion subject, since a failing count
     * assertion prints a number rather than the message it counted over.
     *
     * @param message the message to scan
     * @param sought  the character to count
     * @return the number of occurrences
     */
    private static int countOf(String message, char sought) {
        int occurrences = 0;
        for (int index = 0; index < message.length(); index++) {
            if (message.charAt(index) == sought) {
                occurrences++;
            }
        }
        return occurrences;
    }

    /**
     * Identifies one part without disclosing it. The reference carries the character count and
     * eight hexadecimal characters of a salted digest, which is enough to tell two failing cases
     * apart and to recognise the same case in a second assertion.
     *
     * @param part the part a failing assertion examined, which may be absent
     * @return a reference carrying no character the part supplied
     */
    private static String reference(String part) {
        if (part == null) {
            return "absent part";
        }
        return "length " + part.length() + ", digest " + saltedDigest(part);
    }

    /**
     * Digests a part under {@link #CASE_SALT} and returns the leading eight hexadecimal characters.
     * The salt never reaches a message, so the digest identifies without disclosing.
     *
     * @param part the part to digest
     * @return eight hexadecimal characters
     */
    private static String saltedDigest(String part) {
        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 identifies a failing case", unavailable);
        }
        sha256.update(CASE_SALT);
        byte[] digest = sha256.digest(part.getBytes(StandardCharsets.UTF_8));
        StringBuilder hexadecimal = new StringBuilder(8);
        for (int index = 0; index < 4; index++) {
            hexadecimal.append("%02x".formatted(digest[index]));
        }
        return hexadecimal.toString();
    }

    /**
     * Asserts that three parts fail the edit and that the message carries none of the characters
     * they supply. Every character of the three parts is tested on its own.
     *
     * @param part1 the first part this method supplies to the edit
     * @param part2 the second part this method supplies to the edit
     * @param part3 the third part this method supplies to the edit
     */
    private static void assertNoSuppliedCharacterReachesTheMessage(
            String part1, String part2, String part3) {
        EditResult result = UsSocialSecurityNumberValidator.validate(part1, part2, part3);
        String joined = part1 + part2 + part3;

        assertThat(result.valid()).as("three parts, %s", reference(joined)).isFalse();
        assertThat(result.hasMessage()).isTrue();

        for (int position = 0; position < joined.length(); position++) {
            // The subject is the index the character reaches, so a failure names a position and
            // prints neither the character nor the message.
            assertThat(result.message().indexOf(joined.charAt(position)))
                    .as("character at position %d of %d, %s",
                            position + 1, joined.length(), reference(joined))
                    .isNegative();
        }
    }

    /**
     * Asserts that three parts fail the edit and that the message carries neither the nine
     * character value they join to nor the punctuated form of that value.
     *
     * @param part1 the first part this method supplies to the edit
     * @param part2 the second part this method supplies to the edit
     * @param part3 the third part this method supplies to the edit
     */
    private static void assertNoJoinedValue(String part1, String part2, String part3) {
        EditResult result = UsSocialSecurityNumberValidator.validate(part1, part2, part3);
        String joined = part1 + part2 + part3;
        String punctuated = part1 + "-" + part2 + "-" + part3;

        assertThat(result.valid()).as("three parts, %s", reference(joined)).isFalse();

        // Both subjects are booleans, so a failure prints neither the joined value nor the message.
        assertThat(result.message().contains(joined))
                .as("the message carries the joined nine character value, %s", reference(joined))
                .isFalse();
        assertThat(result.message().contains(punctuated))
                .as("the message carries the punctuated form, %s", reference(punctuated))
                .isFalse();
    }
}
