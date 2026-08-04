package com.carddemo.account.domain.validation;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link CreditScoreRangeValidator}, the credit score range edit. FICO names a credit
 * score.
 *
 * <p>The validator realises paragraph {@code 1275-EDIT-FICO-SCORE} at app/cbl/COACTUPC.cbl:L2514,
 * whose exit paragraph sits at app/cbl/COACTUPC.cbl:L2531. One call site reaches it, at
 * app/cbl/COACTUPC.cbl:L1554, and app/cbl/COACTUPC.cbl:L1545 supplies the label
 * {@code 'FICO Score'} that call site passes.</p>
 *
 * <p>The range is inclusive at both ends. {@code 88 FICO-RANGE-IS-VALID VALUES 300} at
 * app/cbl/COACTUPC.cbl:L848 continues {@code THROUGH 850.} at app/cbl/COACTUPC.cbl:L849. Both
 * bounds pass and both neighbours fail, so 300, 850, 299, and 851 carry the weight of this
 * file.</p>
 *
 * <p>Parsing is out of scope here. app/cbl/COACTUPC.cbl:L2515 tests the condition directly, with
 * no numeric re-parse, and app/cbl/COACTUPC.cbl:L1553 gates the paragraph on the numeric edit at
 * app/cbl/COACTUPC.cbl:L1549 having passed. {@code NumericRequiredValidatorTest} owns that numeric
 * edit, and the gate sits outside this class.</p>
 *
 * <p>One message reaches the caller. app/cbl/COACTUPC.cbl:L2521-L2526 builds it from
 * {@code FUNCTION TRIM} over the {@code PIC X(25)} label field at app/cbl/COACTUPC.cbl:L53, joined
 * to the text at app/cbl/COACTUPC.cbl:L2523.</p>
 *
 * <p>No Spring context, no container and no database take part.</p>
 */
@DisplayName("CreditScoreRangeValidator, the inclusive 300 to 850 credit score edit")
class CreditScoreRangeValidatorTest {

    /** Lowest passing score, from {@code VALUES 300} at app/cbl/COACTUPC.cbl:L848. */
    private static final String LOWEST_PASSING_SCORE = "300";

    /** Highest passing score, from {@code THROUGH 850} at app/cbl/COACTUPC.cbl:L849. */
    private static final String HIGHEST_PASSING_SCORE = "850";

    /** The neighbour one below {@link #LOWEST_PASSING_SCORE}. */
    private static final String ONE_BELOW_RANGE = "299";

    /** The neighbour one above {@link #HIGHEST_PASSING_SCORE}. */
    private static final String ONE_ABOVE_RANGE = "851";

    /** A score midway between the two bounds. */
    private static final String MID_RANGE_SCORE = "700";

    /**
     * The text at app/cbl/COACTUPC.cbl:L2523, carried character for character. The text opens
     * with a colon and a space, and closes with no full stop.
     */
    private static final String RANGE_MESSAGE = ": should be between 300 and 850";

    /** Character count of the text at app/cbl/COACTUPC.cbl:L2523. */
    private static final int RANGE_MESSAGE_LENGTH = 31;

    /** The label app/cbl/COACTUPC.cbl:L1545 supplies. */
    private static final String CALL_SITE_LABEL = "FICO Score";

    /** Declared width of {@code WS-EDIT-VARIABLE-NAME} at app/cbl/COACTUPC.cbl:L53. */
    private static final int LABEL_FIELD_WIDTH = 25;

    /** {@link #CALL_SITE_LABEL} padded with spaces to the width of the label field. */
    private static final String PADDED_CALL_SITE_LABEL =
            CALL_SITE_LABEL + " ".repeat(LABEL_FIELD_WIDTH - CALL_SITE_LABEL.length());

    /** A label field holding only spaces, which {@code FUNCTION TRIM} reduces to no characters. */
    private static final String ALL_SPACES_LABEL = " ".repeat(LABEL_FIELD_WIDTH);

    /** The whole message a rejected score produces for {@link #CALL_SITE_LABEL}. */
    private static final String CALL_SITE_RANGE_MESSAGE = "FICO Score: should be between 300 and 850";

    /** Two adjacent spaces. A trimmed label never emits this pair. */
    private static final String TWO_SPACES = "  ";

    /** A space ahead of the colon. A trimmed label never emits it. */
    private static final String SPACE_BEFORE_COLON = " :";

    /** Scores spanning the whole rejected set, below the range and above it. */
    private static final List<String> REJECTED_SCORES =
            List.of("000", "001", "100", "104", "274", "299", "851", "900", "999");

    /** Scores spanning the accepted set, both bounds and the middle. */
    private static final List<String> ACCEPTED_SCORES = List.of("300", "500", "700", "850");

    @ParameterizedTest(name = "score {0} passes: {1}")
    @DisplayName("The inclusive range accepts 300 and 850 and rejects 299 and 851")
    @CsvSource({
            "300, true",
            "850, true",
            "299, false",
            "851, false"
    })
    void boundaryScoresFollowTheInclusiveRange(String creditScore, boolean expectedToPass) {
        EditResult result = CreditScoreRangeValidator.validate(CALL_SITE_LABEL, creditScore);

        assertThat(result.valid()).isEqualTo(expectedToPass);
        assertThat(result.hasMessage()).isEqualTo(!expectedToPass);
    }

    @Test
    @DisplayName("Both bounds pass, both neighbours fail, and each failure carries the range message")
    void bothBoundsPassAndBothNeighboursFail() {
        EditResult atLowestBound = CreditScoreRangeValidator.validate(CALL_SITE_LABEL, LOWEST_PASSING_SCORE);
        EditResult atHighestBound = CreditScoreRangeValidator.validate(CALL_SITE_LABEL, HIGHEST_PASSING_SCORE);

        assertThat(atLowestBound.valid()).isTrue();
        assertThat(atLowestBound.message()).isNull();
        assertThat(atHighestBound.valid()).isTrue();
        assertThat(atHighestBound.message()).isNull();

        EditResult justBelowRange = CreditScoreRangeValidator.validate(CALL_SITE_LABEL, ONE_BELOW_RANGE);
        EditResult justAboveRange = CreditScoreRangeValidator.validate(CALL_SITE_LABEL, ONE_ABOVE_RANGE);

        assertThat(justBelowRange.valid()).isFalse();
        assertThat(justBelowRange.message()).isEqualTo(CALL_SITE_RANGE_MESSAGE);
        assertThat(justAboveRange.valid()).isFalse();
        assertThat(justAboveRange.message()).isEqualTo(CALL_SITE_RANGE_MESSAGE);
    }

    @Test
    @DisplayName("A score midway between the bounds passes and leaves the message slot empty")
    void midRangeScorePasses() {
        EditResult result = CreditScoreRangeValidator.validate(CALL_SITE_LABEL, MID_RANGE_SCORE);

        assertThat(result.valid()).isTrue();
        assertThat(result.message()).isNull();
        assertThat(result.hasMessage()).isFalse();
    }

    /**
     * Covers the rejected span under the lower bound, not the neighbour alone. Records of
     * app/data/ASCII/custdata.txt reach well under 300: the first record carries 274, and 21 of
     * that file's 50 records carry a score outside the range. Every value here is a literal, and
     * no method reads the file.
     *
     * @param creditScore a score under the lower bound, supplied by the value source
     */
    @ParameterizedTest(name = "score {0} fails the lower bound")
    @DisplayName("Scores below the range fail across their span")
    @ValueSource(strings = {"000", "001", "100", "104", "274", "299"})
    void scoresBelowTheRangeFail(String creditScore) {
        EditResult result = CreditScoreRangeValidator.validate(CALL_SITE_LABEL, creditScore);

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).isEqualTo(CALL_SITE_RANGE_MESSAGE);
    }

    /**
     * Covers the rejected span over the upper bound, matching the treatment of the lower one.
     *
     * @param creditScore a score over the upper bound, supplied by the value source
     */
    @ParameterizedTest(name = "score {0} fails the upper bound")
    @DisplayName("Scores above the range fail across their span")
    @ValueSource(strings = {"851", "900", "999"})
    void scoresAboveTheRangeFail(String creditScore) {
        EditResult result = CreditScoreRangeValidator.validate(CALL_SITE_LABEL, creditScore);

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).isEqualTo(CALL_SITE_RANGE_MESSAGE);
    }

    /**
     * Holds the text of app/cbl/COACTUPC.cbl:L2523 to the character. A label field of only
     * spaces trims to no characters, which leaves the message text alone in the slot.
     */
    @Test
    @DisplayName("The failure message carries the source text character for character and no full stop")
    void failureMessageCarriesSourceTextExactly() {
        EditResult result = CreditScoreRangeValidator.validate(ALL_SPACES_LABEL, ONE_BELOW_RANGE);

        assertThat(result.valid()).isFalse();
        assertThat(result.hasMessage()).isTrue();
        assertThat(result.message()).isEqualTo(": should be between 300 and 850");
        assertThat(result.message()).isEqualTo(RANGE_MESSAGE);
        assertThat(result.message()).hasSize(RANGE_MESSAGE_LENGTH);
        assertThat(result.message()).startsWith(": ");
        assertThat(result.message()).doesNotEndWith(".");
        assertThat(result.message()).doesNotContain(TWO_SPACES);
    }

    /**
     * Applies {@code FUNCTION TRIM} of app/cbl/COACTUPC.cbl:L2522 to a label field padded to the
     * width declared at app/cbl/COACTUPC.cbl:L53. The padding reaches no part of the message.
     */
    @Test
    @DisplayName("The trimmed label prefixes the message and its padding does not leak")
    void paddedLabelIsTrimmedBeforeTheMessage() {
        assertThat(PADDED_CALL_SITE_LABEL).hasSize(LABEL_FIELD_WIDTH).endsWith(" ");

        EditResult result = CreditScoreRangeValidator.validate(PADDED_CALL_SITE_LABEL, ONE_ABOVE_RANGE);

        assertThat(result.message()).isEqualTo(CALL_SITE_RANGE_MESSAGE);
        assertThat(result.message()).startsWith(CALL_SITE_LABEL + ":");
        assertThat(result.message()).doesNotContain(TWO_SPACES);
        assertThat(result.message()).doesNotContain(SPACE_BEFORE_COLON);
        assertThat(result.message()).hasSize(CALL_SITE_LABEL.length() + RANGE_MESSAGE_LENGTH);
    }

    /**
     * Proves the single reachable message of app/cbl/COACTUPC.cbl:L2521-L2526. Nine rejected
     * scores yield one distinct text, and four accepted scores yield none.
     */
    @Test
    @DisplayName("Every rejected score yields the one message the paragraph builds")
    void everyRejectedScoreYieldsTheOneMessage() {
        Set<String> distinctMessages = new LinkedHashSet<>();
        for (String creditScore : REJECTED_SCORES) {
            EditResult result = CreditScoreRangeValidator.validate(CALL_SITE_LABEL, creditScore);

            assertThat(result.valid()).isFalse();
            assertThat(result.hasMessage()).isTrue();
            distinctMessages.add(result.message());
        }

        assertThat(distinctMessages).hasSize(1).containsExactly(CALL_SITE_RANGE_MESSAGE);

        for (String creditScore : ACCEPTED_SCORES) {
            EditResult result = CreditScoreRangeValidator.validate(CALL_SITE_LABEL, creditScore);

            assertThat(result.valid()).isTrue();
            assertThat(result.message()).isNull();
            assertThat(result.hasMessage()).isFalse();
        }
    }

    /**
     * Reads the entry point the one call site at app/cbl/COACTUPC.cbl:L1554 needs: a static call
     * taking a label and a credit score, answering with a verdict.
     */
    @Test
    @DisplayName("The validator declares a static label-and-score entry point returning a verdict")
    void validatorExposesOneStaticEntryPoint() throws NoSuchMethodException {
        Method entryPoint =
                CreditScoreRangeValidator.class.getMethod("validate", String.class, String.class);

        assertThat(Modifier.isPublic(entryPoint.getModifiers())).isTrue();
        assertThat(Modifier.isStatic(entryPoint.getModifiers())).isTrue();
        assertThat(entryPoint.getReturnType()).isEqualTo(EditResult.class);
        assertThat(entryPoint.getParameterTypes()).containsExactly(String.class, String.class);
    }
}
