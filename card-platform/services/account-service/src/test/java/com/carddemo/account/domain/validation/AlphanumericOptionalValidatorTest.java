package com.carddemo.account.domain.validation;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link AlphanumericOptionalValidator}, which realises COBOL paragraph
 * {@code 1240-EDIT-ALPHANUM-OPT} at app/cbl/COACTUPC.cbl:L2061-L2105.
 *
 * <p>A census of all 4236 lines of app/cbl/COACTUPC.cbl found zero {@code PERFORM} sites for
 * {@code 1240-EDIT-ALPHANUM-OPT}. The paragraph carries four references in total: its label at
 * app/cbl/COACTUPC.cbl:L2061, its two {@code GO TO} statements at app/cbl/COACTUPC.cbl:L2073
 * and app/cbl/COACTUPC.cbl:L2100, and its exit label at app/cbl/COACTUPC.cbl:L2105. The count
 * tolerated extra spaces after {@code PERFORM} and ran with carriage returns stripped.
 * {@code AccountUpdateService} names no member of the class under test.</p>
 *
 * <p>The paragraph is optional, so one message is reachable. The three-way not-supplied test at
 * app/cbl/COACTUPC.cbl:L2066-L2071 resolves to success at app/cbl/COACTUPC.cbl:L2072 and leaves
 * at app/cbl/COACTUPC.cbl:L2073. The one message stands at app/cbl/COACTUPC.cbl:L2095 and
 * carries the literal {@code ' can have numbers or alphabets only.'}. The required sibling
 * paragraph carries the same literal at app/cbl/COACTUPC.cbl:L1999.</p>
 *
 * <p>The paragraph is alphanumeric, so 62 characters pass. The group
 * {@code LIT-ALL-ALPHANUM-FROM-X} at app/cbl/COACTUPC.cbl:L586 spans {@code LIT-UPPER} at
 * app/cbl/COACTUPC.cbl:L588-L589, {@code LIT-LOWER} at app/cbl/COACTUPC.cbl:L590-L591 and
 * {@code LIT-NUMBERS} at app/cbl/COACTUPC.cbl:L592-L593. The destructive conversion runs at
 * app/cbl/COACTUPC.cbl:L2079-L2082, and the verification follows at
 * app/cbl/COACTUPC.cbl:L2084-L2087.</p>
 *
 * <p>The comment at app/cbl/COACTUPC.cbl:L2078 reads
 * {@code *    Only Alphabets and space allowed}. The comment stands above the alphanumeric
 * conversion, and the conversion admits digits. The zero-invocation count and the comment both
 * appear in card-platform/docs/business-rule-flags.md.</p>
 *
 * <p>Every input below is built in this file, and no fixture row is read. The methods run on
 * plain JUnit Jupiter with no Spring context, no container and no database.</p>
 *
 * <p>Decision record: card-platform/docs/decision-log.md.</p>
 */
@DisplayName("AlphanumericOptionalValidator, the optional alphanumeric field edit")
class AlphanumericOptionalValidatorTest {

    /**
     * The one reachable message, quoted from app/cbl/COACTUPC.cbl:L2095. The literal opens with
     * a space, spells "alphabets" in the plural, and closes with a period.
     * app/cbl/COACTUPC.cbl:L1999 carries the same 36 characters for the required sibling
     * paragraph {@code 1230-EDIT-ALPHANUM-REQD}.
     */
    private static final String CHARACTER_CLASS_MESSAGE = " can have numbers or alphabets only.";

    /** Character count of {@link #CHARACTER_CLASS_MESSAGE}. */
    private static final int MESSAGE_WIDTH = 36;

    /**
     * The blank-value message of the required sibling paragraph, quoted from
     * app/cbl/COACTUPC.cbl:L1972. The optional paragraph holds no branch that builds it: the
     * not-supplied test at app/cbl/COACTUPC.cbl:L2066-L2071 resolves to success.
     */
    private static final String MANDATORY_MESSAGE = " must be supplied.";

    /**
     * Field name every method below submits. The source holds the name in
     * {@code WS-EDIT-VARIABLE-NAME PIC X(25)} at app/cbl/COACTUPC.cbl:L53.
     */
    private static final String FIELD_LABEL = "Address Line 1";

    /** Declared width of {@code WS-EDIT-VARIABLE-NAME} at app/cbl/COACTUPC.cbl:L53. */
    private static final int LABEL_HOST_WIDTH = 25;

    /** The 26 characters of {@code LIT-UPPER PIC X(26)} at app/cbl/COACTUPC.cbl:L588-L589. */
    private static final String UPPER_CASE_LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    /** The 26 characters of {@code LIT-LOWER PIC X(26)} at app/cbl/COACTUPC.cbl:L590-L591. */
    private static final String LOWER_CASE_LETTERS = "abcdefghijklmnopqrstuvwxyz";

    /** The 10 characters of {@code LIT-NUMBERS PIC X(10)} at app/cbl/COACTUPC.cbl:L592-L593. */
    private static final String DIGIT_CHARACTERS = "0123456789";

    /**
     * The accepted set, the group {@code LIT-ALL-ALPHANUM-FROM-X} at
     * app/cbl/COACTUPC.cbl:L586. The group holds no space.
     */
    private static final String ALPHANUMERIC_GROUP =
            UPPER_CASE_LETTERS + LOWER_CASE_LETTERS + DIGIT_CHARACTERS;

    /** Character count of {@link #ALPHANUMERIC_GROUP}: 26 upper case, 26 lower case, 10 digits. */
    private static final int GROUP_WIDTH = 62;

    /**
     * Three characters of {@code LOW-VALUES}, the first limb of the not-supplied test at
     * app/cbl/COACTUPC.cbl:L2066-L2067.
     */
    private static final String LOW_VALUES = "\u0000\u0000\u0000";

    /** Code point of the first printable ASCII character above the space. */
    private static final int FIRST_PRINTABLE = 0x21;

    /** Code point of the last printable ASCII character. */
    private static final int LAST_PRINTABLE = 0x7E;

    @Test
    @DisplayName("A value that was not supplied passes and carries no message")
    void notSuppliedValuePassesWithNoMessage() {
        assertPasses(AlphanumericOptionalValidator.validate(FIELD_LABEL, "   ", 3));
        assertPasses(AlphanumericOptionalValidator.validate(FIELD_LABEL, "", LABEL_HOST_WIDTH));
        assertPasses(AlphanumericOptionalValidator.validate(FIELD_LABEL, null, LABEL_HOST_WIDTH));
        assertPasses(AlphanumericOptionalValidator.validate(FIELD_LABEL, LOW_VALUES, LOW_VALUES.length()));
        assertPasses(AlphanumericOptionalValidator.validate(FIELD_LABEL, "ANY", 0));
    }

    @Test
    @DisplayName("Every not-supplied limb passes, and no input reaches a must-be-supplied message")
    void noInputReachesAMustBeSuppliedMessage() {
        List<EditResult> notSuppliedLimbs = List.of(
                AlphanumericOptionalValidator.validate(FIELD_LABEL, "     ", 5),
                AlphanumericOptionalValidator.validate(FIELD_LABEL, "", 5),
                AlphanumericOptionalValidator.validate(FIELD_LABEL, null, 5),
                AlphanumericOptionalValidator.validate(FIELD_LABEL, LOW_VALUES, LOW_VALUES.length()),
                AlphanumericOptionalValidator.validate(FIELD_LABEL, "ANY", 0));

        assertThat(notSuppliedLimbs).allSatisfy(AlphanumericOptionalValidatorTest::assertPasses);

        EditResult failed = AlphanumericOptionalValidator.validate(FIELD_LABEL, "Main-St", 7);

        assertThat(failed.message())
                .doesNotContain(MANDATORY_MESSAGE)
                .isEqualTo(FIELD_LABEL + CHARACTER_CLASS_MESSAGE);
    }

    @Test
    @DisplayName("A digit passes, so an all-digit value and a letters-plus-digits value both pass")
    void digitsPassTheAlphanumericEdit() {
        assertPasses(AlphanumericOptionalValidator.validate(FIELD_LABEL, "12345", 5));
        assertPasses(AlphanumericOptionalValidator.validate(FIELD_LABEL, "Apt4B", 5));
        assertPasses(AlphanumericOptionalValidator.validate(
                FIELD_LABEL, DIGIT_CHARACTERS, DIGIT_CHARACTERS.length()));
    }

    @Test
    @DisplayName("Lower case, upper case and mixed case all pass")
    void bothLetterCasesPass() {
        assertPasses(AlphanumericOptionalValidator.validate(
                FIELD_LABEL, LOWER_CASE_LETTERS, LOWER_CASE_LETTERS.length()));
        assertPasses(AlphanumericOptionalValidator.validate(
                FIELD_LABEL, UPPER_CASE_LETTERS, UPPER_CASE_LETTERS.length()));
        assertPasses(AlphanumericOptionalValidator.validate(FIELD_LABEL, "ApArTmEnT", 9));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("alphanumericGroupCharacters")
    @DisplayName("Every character of the accepted group passes on its own")
    void everyGroupCharacterPasses(String character) {
        assertPasses(AlphanumericOptionalValidator.validate(FIELD_LABEL, character, 1));
    }

    @Test
    @DisplayName("Exactly 62 printable characters pass, and the space passes alongside them")
    void exactlySixtyTwoPrintableCharactersPass() {
        long passing = printableAsciiAboveSpace()
                .filter(character -> AlphanumericOptionalValidator
                        .validate(FIELD_LABEL, character, 1)
                        .valid())
                .count();

        assertThat(passing).isEqualTo(GROUP_WIDTH);
        assertThat(ALPHANUMERIC_GROUP).hasSize(GROUP_WIDTH);
        assertPasses(AlphanumericOptionalValidator.validate(FIELD_LABEL, " ", 1));
    }

    @Test
    @DisplayName("A value holding a space passes, and the conversion leaves the space in place")
    void valueHoldingASpacePasses() {
        assertPasses(AlphanumericOptionalValidator.validate(FIELD_LABEL, "A 1", 3));
        assertPasses(AlphanumericOptionalValidator.validate(FIELD_LABEL, "Apt 4B", 6));
        assertPasses(AlphanumericOptionalValidator.validate(FIELD_LABEL, "12 Oak Street 3", 15));
    }

    @ParameterizedTest(name = "[{index}] R{0}D")
    @ValueSource(strings = {
            "-", "&", ".", ",", "/", "#", "'", "*",
            "@", "+", "(", ")", "_", ":", ";", "?",
            "$", "%", "!", "\""})
    @DisplayName("A character outside the group fails with the character-class message")
    void characterOutsideTheGroupFails(String character) {
        String value = "R" + character + "D";

        EditResult result = AlphanumericOptionalValidator.validate(FIELD_LABEL, value, value.length());

        assertThat(result.valid()).isFalse();
        assertThat(result.hasMessage()).isTrue();
        assertThat(result.message()).isEqualTo(FIELD_LABEL + CHARACTER_CLASS_MESSAGE);
    }

    @Test
    @DisplayName("The message opens with the trimmed label, so host padding never leaks")
    void messageOpensWithTheTrimmedLabel() {
        String paddedLabel = FIELD_LABEL + " ".repeat(LABEL_HOST_WIDTH - FIELD_LABEL.length());

        assertThat(paddedLabel).hasSize(LABEL_HOST_WIDTH).endsWith(" ");

        EditResult result = AlphanumericOptionalValidator.validate(paddedLabel, "Main-St", 7);

        assertThat(result.message())
                .isEqualTo(FIELD_LABEL + CHARACTER_CLASS_MESSAGE)
                .doesNotContain(FIELD_LABEL + "  ")
                .hasSize(FIELD_LABEL.length() + MESSAGE_WIDTH);
    }

    @Test
    @DisplayName("An empty label leaves the message literal bare, exposing its leading space")
    void emptyLabelLeavesTheMessageLiteralBare() {
        EditResult fromNullLabel = AlphanumericOptionalValidator.validate(null, "R&D", 3);
        EditResult fromSpacesLabel = AlphanumericOptionalValidator.validate("      ", "R&D", 3);

        assertThat(fromNullLabel.message()).isEqualTo(CHARACTER_CLASS_MESSAGE).startsWith(" ");
        assertThat(fromSpacesLabel.message()).isEqualTo(CHARACTER_CLASS_MESSAGE).startsWith(" ");
    }

    @Test
    @DisplayName("The message literal reaches the caller with its leading space, plural and period")
    void messageLiteralSurvivesCharacterForCharacter() {
        EditResult result = AlphanumericOptionalValidator.validate(FIELD_LABEL, "R&D", 3);

        assertThat(result.message()).isEqualTo("Address Line 1 can have numbers or alphabets only.");
        assertThat(result.message()).endsWith(" can have numbers or alphabets only.");
        assertThat(result.message()).contains(" alphabets ").endsWith("only.");
        assertThat(CHARACTER_CLASS_MESSAGE).hasSize(MESSAGE_WIDTH);
    }

    @Test
    @DisplayName("The inspected width bounds the edit, so a character past the width is not read")
    void inspectedWidthBoundsTheEdit() {
        assertPasses(AlphanumericOptionalValidator.validate(FIELD_LABEL, "AB-", 2));
        assertPasses(AlphanumericOptionalValidator.validate(FIELD_LABEL, "AB", LABEL_HOST_WIDTH));

        EditResult wholeValue = AlphanumericOptionalValidator.validate(FIELD_LABEL, "AB-", 3);

        assertThat(wholeValue.valid()).isFalse();
        assertThat(wholeValue.message()).isEqualTo(FIELD_LABEL + CHARACTER_CLASS_MESSAGE);
    }

    @Test
    @DisplayName("The validator exposes one static edit method and no public constructor")
    void validatorExposesOneStaticEditMethod() throws NoSuchMethodException {
        Class<AlphanumericOptionalValidator> subject = AlphanumericOptionalValidator.class;

        assertThat(Modifier.isFinal(subject.getModifiers())).isTrue();

        Constructor<?>[] constructors = subject.getDeclaredConstructors();

        assertThat(constructors).hasSize(1);
        assertThat(Modifier.isPrivate(constructors[0].getModifiers())).isTrue();
        assertThat(constructors[0].getParameterCount()).isZero();

        List<Method> publicMethods = Stream.of(subject.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .toList();

        assertThat(publicMethods).hasSize(1);

        Method edit = subject.getMethod("validate", String.class, String.class, int.class);

        assertThat(Modifier.isStatic(edit.getModifiers())).isTrue();
        assertThat(edit.getReturnType()).isEqualTo(EditResult.class);
    }

    /**
     * Asserts a passing verdict over an empty message slot, the state
     * {@code WS-RETURN-MSG-OFF VALUE SPACES} names at app/cbl/COACTUPC.cbl:L480.
     *
     * @param result the verdict to check
     */
    private static void assertPasses(EditResult result) {
        assertThat(result.valid()).isTrue();
        assertThat(result.message()).isNull();
        assertThat(result.hasMessage()).isFalse();
    }

    /**
     * Supplies each character of {@link #ALPHANUMERIC_GROUP} as a one-character value.
     *
     * @return 62 values, one per member of the group at app/cbl/COACTUPC.cbl:L586-L593
     */
    private static Stream<String> alphanumericGroupCharacters() {
        return ALPHANUMERIC_GROUP.chars().mapToObj(Character::toString);
    }

    /**
     * Supplies every printable ASCII character above the space as a one-character value.
     *
     * @return 94 values, one per printable ASCII character from the exclamation mark to the tilde
     */
    private static Stream<String> printableAsciiAboveSpace() {
        return IntStream.rangeClosed(FIRST_PRINTABLE, LAST_PRINTABLE).mapToObj(Character::toString);
    }
}
