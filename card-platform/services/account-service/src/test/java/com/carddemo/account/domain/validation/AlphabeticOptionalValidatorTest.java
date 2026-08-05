package com.carddemo.account.domain.validation;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link AlphabeticOptionalValidator}, the edit for an optional alphabetic field.
 *
 * <p>The subject reproduces paragraph {@code 1235-EDIT-ALPHA-OPT} at app/cbl/COACTUPC.cbl:L2012,
 * through its exit paragraph at app/cbl/COACTUPC.cbl:L2057.</p>
 *
 * <p>An absent value passes. The not-supplied test at app/cbl/COACTUPC.cbl:L2017-L2022 resolves to
 * success at app/cbl/COACTUPC.cbl:L2024, and app/cbl/COACTUPC.cbl:L2025 leaves the paragraph with
 * the message slot untouched. A null value, an empty value and an all-space value each take that
 * path.</p>
 *
 * <p>One message reaches the caller, {@code ' can have alphabets only.'} at
 * app/cbl/COACTUPC.cbl:L2047. The required form of the same edit is {@code 1225-EDIT-ALPHA-REQD} at
 * app/cbl/COACTUPC.cbl:L1898. That paragraph carries the same literal at app/cbl/COACTUPC.cbl:L1941
 * and adds a second message at app/cbl/COACTUPC.cbl:L1915. {@link #onlyOneMessageIsReachable()}
 * asserts that the second message never appears here.</p>
 *
 * <p>The accepted characters are {@code LIT-UPPER PIC X(26)} plus {@code LIT-LOWER PIC X(26)} at
 * app/cbl/COACTUPC.cbl:L588-L591, grouped as {@code LIT-ALL-ALPHA-FROM-X} at
 * app/cbl/COACTUPC.cbl:L587. The conversion at app/cbl/COACTUPC.cbl:L2031-L2034 replaces each of
 * the 52 with a space, and the check at app/cbl/COACTUPC.cbl:L2036-L2039 measures what survives.
 * That set holds no space. A space therefore survives the conversion, and a value holding a space
 * passes.</p>
 *
 * <p>The one call site is app/cbl/COACTUPC.cbl:L1571, which edits the middle name with the label
 * {@code 'Middle Name'} and a width of 25. Every test builds its own input and runs with no Spring
 * context, no container and no database.</p>
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@DisplayName("AlphabeticOptionalValidator, the edit for an optional alphabetic field")
class AlphabeticOptionalValidatorTest {

    /** The one message the edit produces, the literal at app/cbl/COACTUPC.cbl:L2047. */
    private static final String ALPHABETIC_ONLY_MESSAGE = " can have alphabets only.";

    /** Character count of {@link #ALPHABETIC_ONLY_MESSAGE}. */
    private static final int ALPHABETIC_ONLY_MESSAGE_WIDTH = 25;

    /**
     * The message the required form adds at app/cbl/COACTUPC.cbl:L1915. The optional form reaches
     * no statement that produces it.
     */
    private static final String MUST_BE_SUPPLIED_MESSAGE = " must be supplied.";

    /** {@code LIT-UPPER PIC X(26)} at app/cbl/COACTUPC.cbl:L588-L589. */
    private static final String UPPER_CASE_LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    /** {@code LIT-LOWER PIC X(26)} at app/cbl/COACTUPC.cbl:L590-L591. */
    private static final String LOWER_CASE_LETTERS = "abcdefghijklmnopqrstuvwxyz";

    /** {@code LIT-ALL-ALPHA-FROM-X} at app/cbl/COACTUPC.cbl:L587, the 52 accepted characters. */
    private static final String ACCEPTED_CHARACTERS = UPPER_CASE_LETTERS + LOWER_CASE_LETTERS;

    /** Count of the characters the conversion replaces. */
    private static final int ACCEPTED_CHARACTER_COUNT = 52;

    /** Label the call site supplies at app/cbl/COACTUPC.cbl:L1568. */
    private static final String CALL_SITE_LABEL = "Middle Name";

    /** Edited width the call site supplies at app/cbl/COACTUPC.cbl:L1570. */
    private static final int CALL_SITE_LENGTH = 25;

    /** Width of {@code WS-EDIT-VARIABLE-NAME PIC X(25)} at app/cbl/COACTUPC.cbl:L53. */
    private static final int LABEL_HOST_WIDTH = 25;

    /** The label as its 25-character host holds it, padded on the right with spaces. */
    private static final String PADDED_CALL_SITE_LABEL =
            CALL_SITE_LABEL + " ".repeat(LABEL_HOST_WIDTH - CALL_SITE_LABEL.length());

    /** The message the call site produces, opening with the trimmed label. */
    private static final String CALL_SITE_MESSAGE = "Middle Name can have alphabets only.";

    /**
     * Message text for a value wider than the edited field. No source literal carries this
     * text: the source moves a fixed-width screen field into its edit field, so a wider value
     * cannot reach paragraph 1235.
     */
    private static final String CALL_SITE_WIDTH_MESSAGE =
            "Middle Name must be no longer than " + CALL_SITE_LENGTH + " characters.";

    /** A middle name that carries a digit, which the edit rejects. */
    private static final String VALUE_WITH_A_DIGIT = "Jane1";

    /**
     * One character of the figurative constant {@code LOW-VALUES}, which a COBOL comparison tests
     * for one position at a time.
     */
    private static final String LOW_VALUE = "\0";

    @ParameterizedTest(name = "value [{0}] passes and carries no message")
    @NullSource
    @EmptySource
    @ValueSource(strings = {" ", "   "})
    @DisplayName("An absent value passes and carries no message")
    void absentValuePassesWithNoMessage(String value) {
        EditResult result =
                AlphabeticOptionalValidator.validate(CALL_SITE_LABEL, value, CALL_SITE_LENGTH);

        // app/cbl/COACTUPC.cbl:L2024 sets the valid condition, and L2025 leaves the paragraph.
        assertThat(result.valid()).isTrue();
        assertThat(result.message()).isNull();
        assertThat(result.hasMessage()).isFalse();
    }

    @Test
    @DisplayName("A field of LOW-VALUES at the edited width passes, the first arm at "
            + "app/cbl/COACTUPC.cbl:L2018")
    void lowValuesAtTheEditedWidthPasses() {
        for (int width : new int[] {1, 2, CALL_SITE_LENGTH - 1, CALL_SITE_LENGTH}) {
            EditResult result = AlphabeticOptionalValidator.validate(
                    CALL_SITE_LABEL, LOW_VALUE.repeat(width), width);

            assertThat(result.valid()).as("width %d", width).isTrue();
            assertThat(result.message()).as("width %d", width).isNull();
        }
    }

    @Test
    @DisplayName("A field of LOW-VALUES narrower than the edited width fails the character-class "
            + "test, because the padding spaces break the LOW-VALUES arm")
    void lowValuesBelowTheEditedWidthFailsTheCharacterClassTest() {
        for (int supplied : new int[] {1, 3, CALL_SITE_LENGTH - 1}) {
            EditResult result = AlphabeticOptionalValidator.validate(
                    CALL_SITE_LABEL, LOW_VALUE.repeat(supplied), CALL_SITE_LENGTH);

            // FUNCTION TRIM removes the space alone, so the null characters survive both the
            // presence test and the conversion, and the paragraph reports the character class.
            assertThat(result.valid()).as("%d supplied of %d", supplied, CALL_SITE_LENGTH).isFalse();
            assertThat(result.message())
                    .as("%d supplied of %d", supplied, CALL_SITE_LENGTH)
                    .isEqualTo(CALL_SITE_MESSAGE);
        }
    }

    @Test
    @DisplayName("A field of LOW-VALUES is read only to the edited width, and content past that "
            + "width is refused")
    void lowValuesIsReadOnlyToTheEditedWidthAndContentPastItIsRefused() {
        String padded = LOW_VALUE.repeat(CALL_SITE_LENGTH) + "    ";

        assertThat(AlphabeticOptionalValidator
                .validate(CALL_SITE_LABEL, padded, CALL_SITE_LENGTH).valid()).isTrue();

        String beyond = LOW_VALUE.repeat(CALL_SITE_LENGTH) + "Jane";
        EditResult refused =
                AlphabeticOptionalValidator.validate(CALL_SITE_LABEL, beyond, CALL_SITE_LENGTH);

        assertThat(refused.valid()).isFalse();
        assertThat(refused.message()).isEqualTo(CALL_SITE_WIDTH_MESSAGE);

        EditResult wider = AlphabeticOptionalValidator.validate(
                CALL_SITE_LABEL, beyond, beyond.length());

        assertThat(wider.valid()).isFalse();
        assertThat(wider.message()).isEqualTo(CALL_SITE_MESSAGE);
    }

    @Test
    @DisplayName("One letter followed by LOW-VALUES fails the character-class test")
    void oneLetterFollowedByLowValuesFailsTheCharacterClassTest() {
        String letterThenNulls = "J" + LOW_VALUE.repeat(CALL_SITE_LENGTH - 1);

        EditResult result = AlphabeticOptionalValidator.validate(
                CALL_SITE_LABEL, letterThenNulls, CALL_SITE_LENGTH);

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).isEqualTo(CALL_SITE_MESSAGE);
    }

    @Test
    @DisplayName("A value of spaces at and beyond the edited width passes")
    void spacesAtAndBeyondTheEditedWidthPass() {
        int[] widths = {1, CALL_SITE_LENGTH - 1, CALL_SITE_LENGTH, CALL_SITE_LENGTH + 6};

        for (int width : widths) {
            EditResult result = AlphabeticOptionalValidator.validate(
                    CALL_SITE_LABEL, " ".repeat(width), CALL_SITE_LENGTH);

            assertThat(result.valid()).as("space count %d", width).isTrue();
            assertThat(result.message()).as("space count %d", width).isNull();
        }
    }

    @Test
    @DisplayName("One message is reachable, and no input produces the must-be-supplied message")
    void onlyOneMessageIsReachable() {
        List<String> values = new ArrayList<>();
        values.add(null);
        values.add("");
        values.add(" ");
        values.add("   ");
        values.add(" ".repeat(CALL_SITE_LENGTH));
        values.add("Jane");
        values.add("Anne Marie");
        values.add(VALUE_WITH_A_DIGIT);
        values.add("O'Brien");
        values.add("\t");

        List<String> messages = new ArrayList<>();
        for (String value : values) {
            EditResult result =
                    AlphabeticOptionalValidator.validate(CALL_SITE_LABEL, value, CALL_SITE_LENGTH);
            if (result.hasMessage()) {
                messages.add(result.message());
            } else {
                assertThat(result.valid()).as("value [%s]", value).isTrue();
                assertThat(result.message()).as("value [%s]", value).isNull();
            }
        }

        // app/cbl/COACTUPC.cbl:L2045-L2050 holds the paragraph's only STRING statement, guarded at
        // app/cbl/COACTUPC.cbl:L2044 over the single slot at app/cbl/COACTUPC.cbl:L479.
        assertThat(messages).isNotEmpty();
        assertThat(messages).containsOnly(CALL_SITE_MESSAGE);

        for (String message : messages) {
            assertThat(message).doesNotContain(MUST_BE_SUPPLIED_MESSAGE);
            assertThat(message).endsWith(ALPHABETIC_ONLY_MESSAGE);
        }

        assertThat(CALL_SITE_MESSAGE).doesNotContain(MUST_BE_SUPPLIED_MESSAGE);
        assertThat(ALPHABETIC_ONLY_MESSAGE).doesNotContain(MUST_BE_SUPPLIED_MESSAGE);
    }

    @ParameterizedTest(name = "value [{0}] passes")
    @ValueSource(strings = {"jane", "JANE", "JaNe", "aZ", "Za", "middlename", "MIDDLENAME"})
    @DisplayName("Lower case, upper case and mixed case all pass")
    void bothLetterCasesPass(String value) {
        EditResult result =
                AlphabeticOptionalValidator.validate(CALL_SITE_LABEL, value, CALL_SITE_LENGTH);

        assertThat(result.valid()).isTrue();
        assertThat(result.message()).isNull();
    }

    @Test
    @DisplayName("The 52 accepted characters pass, one at a time and all together")
    void acceptedCharactersPass() {
        assertThat(UPPER_CASE_LETTERS).hasSize(26);
        assertThat(LOWER_CASE_LETTERS).hasSize(26);
        assertThat(ACCEPTED_CHARACTERS).hasSize(ACCEPTED_CHARACTER_COUNT);

        EditResult wholeSet = AlphabeticOptionalValidator.validate(
                CALL_SITE_LABEL, ACCEPTED_CHARACTERS, ACCEPTED_CHARACTER_COUNT);

        assertThat(wholeSet.valid()).isTrue();
        assertThat(wholeSet.message()).isNull();

        for (int position = 0; position < ACCEPTED_CHARACTERS.length(); position++) {
            String single = String.valueOf(ACCEPTED_CHARACTERS.charAt(position));
            EditResult result =
                    AlphabeticOptionalValidator.validate(CALL_SITE_LABEL, single, CALL_SITE_LENGTH);

            assertThat(result.valid()).as("character [%s]", single).isTrue();
            assertThat(result.message()).as("character [%s]", single).isNull();
        }
    }

    @ParameterizedTest(name = "value [{0}] passes")
    @ValueSource(strings = {"Anne Marie", "de la Cruz", " Anne", "Anne ", "A B", "a b c d e"})
    @DisplayName("A value holding a space passes, since the conversion leaves a space untouched")
    void valueHoldingASpacePasses(String value) {
        EditResult result =
                AlphabeticOptionalValidator.validate(CALL_SITE_LABEL, value, CALL_SITE_LENGTH);

        assertThat(value).contains(" ");
        assertThat(result.valid()).isTrue();
        assertThat(result.message()).isNull();
    }

    @ParameterizedTest(name = "digit [{0}] fails")
    @ValueSource(chars = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9'})
    @DisplayName("A digit fails with the alphabets-only message")
    void digitFails(char digit) {
        assertThat(ACCEPTED_CHARACTERS).doesNotContain(String.valueOf(digit));

        EditResult withLetters = AlphabeticOptionalValidator.validate(
                CALL_SITE_LABEL, "Jane" + digit, CALL_SITE_LENGTH);
        EditResult alone = AlphabeticOptionalValidator.validate(
                CALL_SITE_LABEL, String.valueOf(digit), CALL_SITE_LENGTH);

        assertThat(withLetters.valid()).isFalse();
        assertThat(withLetters.hasMessage()).isTrue();
        assertThat(withLetters.message()).isEqualTo(CALL_SITE_MESSAGE);
        assertThat(alone.valid()).isFalse();
        assertThat(alone.message()).isEqualTo(CALL_SITE_MESSAGE);
    }

    @ParameterizedTest(name = "character [{0}] fails")
    @ValueSource(chars = {'\'', '-', '.', ',', '/', '&', '#', '@', '(', ')', '_', '+', '*', '$'})
    @DisplayName("A punctuation character fails with the same message")
    void punctuationFails(char punctuation) {
        assertThat(ACCEPTED_CHARACTERS).doesNotContain(String.valueOf(punctuation));

        EditResult withLetters = AlphabeticOptionalValidator.validate(
                CALL_SITE_LABEL, "Anne" + punctuation + "Marie", CALL_SITE_LENGTH);
        EditResult alone = AlphabeticOptionalValidator.validate(
                CALL_SITE_LABEL, String.valueOf(punctuation), CALL_SITE_LENGTH);

        assertThat(withLetters.valid()).isFalse();
        assertThat(withLetters.message()).isEqualTo(CALL_SITE_MESSAGE);
        assertThat(alone.valid()).isFalse();
        assertThat(alone.message()).isEqualTo(CALL_SITE_MESSAGE);
    }

    @Test
    @DisplayName("The message opens with the trimmed label, and the host padding never leaks")
    void messageOpensWithTheTrimmedLabel() {
        assertThat(PADDED_CALL_SITE_LABEL).hasSize(LABEL_HOST_WIDTH).endsWith(" ");

        // app/cbl/COACTUPC.cbl:L2046 trims WS-EDIT-VARIABLE-NAME before the literal.
        EditResult padded = AlphabeticOptionalValidator.validate(
                PADDED_CALL_SITE_LABEL, VALUE_WITH_A_DIGIT, CALL_SITE_LENGTH);

        assertThat(padded.message())
                .isEqualTo(CALL_SITE_MESSAGE)
                .hasSize(CALL_SITE_LABEL.length() + ALPHABETIC_ONLY_MESSAGE_WIDTH)
                .doesNotContain("  ");

        EditResult surrounded = AlphabeticOptionalValidator.validate(
                "   " + PADDED_CALL_SITE_LABEL, VALUE_WITH_A_DIGIT, CALL_SITE_LENGTH);

        assertThat(surrounded.message()).isEqualTo(CALL_SITE_MESSAGE);

        EditResult withoutLabel = AlphabeticOptionalValidator.validate(
                null, VALUE_WITH_A_DIGIT, CALL_SITE_LENGTH);

        // The opening space belongs to the literal, and no code inserts a separator.
        assertThat(withoutLabel.message()).isEqualTo(ALPHABETIC_ONLY_MESSAGE);
    }

    @Test
    @DisplayName("The message text matches the required form's character-class literal, character for character")
    void messageTextMatchesTheRequiredFormLiteral() {
        assertThat(ALPHABETIC_ONLY_MESSAGE)
                .isEqualTo(" can have alphabets only.")
                .hasSize(ALPHABETIC_ONLY_MESSAGE_WIDTH)
                .startsWith(" ")
                .endsWith(".")
                .contains("alphabets");
        assertThat(ALPHABETIC_ONLY_MESSAGE.charAt(0)).isEqualTo(' ');
        assertThat(ALPHABETIC_ONLY_MESSAGE.charAt(ALPHABETIC_ONLY_MESSAGE_WIDTH - 1)).isEqualTo('.');

        EditResult result = AlphabeticOptionalValidator.validate(
                CALL_SITE_LABEL, VALUE_WITH_A_DIGIT, CALL_SITE_LENGTH);

        assertThat(result.message()).endsWith(ALPHABETIC_ONLY_MESSAGE);
        assertThat(result.message().substring(CALL_SITE_LABEL.length()))
                .isEqualTo(ALPHABETIC_ONLY_MESSAGE);
        assertThat(CALL_SITE_MESSAGE).isEqualTo(CALL_SITE_LABEL + ALPHABETIC_ONLY_MESSAGE);
    }

    @Test
    @DisplayName("The middle name at the call site passes when absent, passes when alphabetic, and fails otherwise")
    void callSiteMiddleNameBehavesAsTheParagraphDoes() {
        assertThat(CALL_SITE_LABEL).isEqualTo("Middle Name");
        assertThat(CALL_SITE_LENGTH).isEqualTo(25);

        EditResult absent =
                AlphabeticOptionalValidator.validate(CALL_SITE_LABEL, null, CALL_SITE_LENGTH);
        EditResult blank = AlphabeticOptionalValidator.validate(
                CALL_SITE_LABEL, " ".repeat(CALL_SITE_LENGTH), CALL_SITE_LENGTH);
        EditResult oneWord =
                AlphabeticOptionalValidator.validate(CALL_SITE_LABEL, "Anne", CALL_SITE_LENGTH);
        EditResult twoWords = AlphabeticOptionalValidator.validate(
                CALL_SITE_LABEL, "Anne Marie", CALL_SITE_LENGTH);
        EditResult hyphenated = AlphabeticOptionalValidator.validate(
                CALL_SITE_LABEL, "Anne-Marie", CALL_SITE_LENGTH);

        assertThat(absent.valid()).isTrue();
        assertThat(blank.valid()).isTrue();
        assertThat(oneWord.valid()).isTrue();
        assertThat(twoWords.valid()).isTrue();
        assertThat(hyphenated.valid()).isFalse();
        assertThat(hyphenated.message()).isEqualTo("Middle Name can have alphabets only.");
    }

    @Test
    @DisplayName("A character beyond the edited width fails the edit")
    void charactersBeyondTheEditedWidthFailTheEdit() {
        // app/cbl/COACTUPC.cbl:L2032 edits the slice (1:WS-EDIT-ALPHANUM-LENGTH) alone, and the
        // MOVE that fills it reads a screen field of that exact width, so it drops nothing but
        // padding.
        //
        // No COBOL ancestor. A caller of this edit can supply a wider value, and the edit refuses
        // one that carries a character the slice would not cover.
        EditResult pastWidth =
                AlphabeticOptionalValidator.validate(CALL_SITE_LABEL, VALUE_WITH_A_DIGIT, 4);
        EditResult atWidth =
                AlphabeticOptionalValidator.validate(CALL_SITE_LABEL, VALUE_WITH_A_DIGIT, 5);
        EditResult fullWidth = AlphabeticOptionalValidator.validate(
                CALL_SITE_LABEL, VALUE_WITH_A_DIGIT, CALL_SITE_LENGTH);
        EditResult padSpacePastWidth =
                AlphabeticOptionalValidator.validate(CALL_SITE_LABEL, "Jane   ", 4);

        assertThat(pastWidth.valid()).isFalse();
        assertThat(pastWidth.message())
                .isEqualTo(CALL_SITE_LABEL + " must be no longer than 4 characters.");
        assertThat(atWidth.valid()).isFalse();
        assertThat(atWidth.message()).isEqualTo(CALL_SITE_MESSAGE);
        assertThat(fullWidth.valid()).isFalse();
        assertThat(fullWidth.message()).isEqualTo(CALL_SITE_MESSAGE);

        // Trailing spaces past the width are the padding the source MOVE itself drops.
        assertThat(padSpacePastWidth.valid()).isTrue();
        assertThat(padSpacePastWidth.message()).isNull();
    }

    @Test
    @DisplayName("A value wider than the edited field is refused whatever it holds")
    void aValueWiderThanTheEditedFieldIsRefused() {
        String[] wider = {
            "A".repeat(CALL_SITE_LENGTH + 1),
            "Jane<script>alert(1)</script>Doe",
            "A".repeat(CALL_SITE_LENGTH) + "\n",
            "A".repeat(CALL_SITE_LENGTH) + "\u0000",
            "A".repeat(4096),
        };

        for (int index = 0; index < wider.length; index++) {
            EditResult result = AlphabeticOptionalValidator.validate(
                    CALL_SITE_LABEL, wider[index], CALL_SITE_LENGTH);

            assertThat(result.valid()).as("value index %d", index).isFalse();
            assertThat(result.message()).as("value index %d", index)
                    .isEqualTo(CALL_SITE_WIDTH_MESSAGE);
        }
    }

    @ParameterizedTest(name = "width [{0}] passes")
    @ValueSource(ints = {0, -1, -25})
    @DisplayName("A width of zero or less reads nothing and passes")
    void widthOfZeroOrLessPasses(int length) {
        EditResult result =
                AlphabeticOptionalValidator.validate(CALL_SITE_LABEL, "1234567890", length);

        assertThat(result.valid()).isTrue();
        assertThat(result.message()).isNull();
    }

    @Test
    @DisplayName("White space other than a space fails, matching the reach of FUNCTION TRIM")
    void whiteSpaceOtherThanASpaceFails() {
        String[] values = {"\t", "\n", "\r", "Anne\tMarie", "Anne\nMarie"};

        for (int index = 0; index < values.length; index++) {
            EditResult result = AlphabeticOptionalValidator.validate(
                    CALL_SITE_LABEL, values[index], CALL_SITE_LENGTH);

            assertThat(result.valid()).as("value index %d", index).isFalse();
            assertThat(result.message()).as("value index %d", index).isEqualTo(CALL_SITE_MESSAGE);
        }
    }

    @Test
    @DisplayName("The validator declares a static label, value and width edit returning a verdict")
    void validatorExposesOneStaticEdit() throws NoSuchMethodException {
        Method edit = AlphabeticOptionalValidator.class.getMethod(
                "validate", String.class, String.class, int.class);

        assertThat(Modifier.isPublic(edit.getModifiers())).isTrue();
        assertThat(Modifier.isStatic(edit.getModifiers())).isTrue();
        assertThat(edit.getReturnType()).isEqualTo(EditResult.class);
        assertThat(edit.getParameterTypes())
                .containsExactly(String.class, String.class, int.class);
    }

    @Test
    @DisplayName("Repeated edits of one value return one verdict and leave the value unchanged")
    void repeatedEditsReturnOneVerdictAndLeaveTheValueUnchanged() {
        String accepted = "Anne Marie";
        String rejected = "Anne-Marie";

        EditResult firstAccepted =
                AlphabeticOptionalValidator.validate(CALL_SITE_LABEL, accepted, CALL_SITE_LENGTH);
        EditResult secondAccepted =
                AlphabeticOptionalValidator.validate(CALL_SITE_LABEL, accepted, CALL_SITE_LENGTH);
        EditResult firstRejected =
                AlphabeticOptionalValidator.validate(CALL_SITE_LABEL, rejected, CALL_SITE_LENGTH);
        EditResult secondRejected =
                AlphabeticOptionalValidator.validate(CALL_SITE_LABEL, rejected, CALL_SITE_LENGTH);

        assertThat(firstAccepted).isEqualTo(secondAccepted).isEqualTo(EditResult.ok());
        assertThat(firstRejected)
                .isEqualTo(secondRejected)
                .isEqualTo(EditResult.failure(CALL_SITE_MESSAGE));
        assertThat(accepted).isEqualTo("Anne Marie");
        assertThat(rejected).isEqualTo("Anne-Marie");
    }
}
