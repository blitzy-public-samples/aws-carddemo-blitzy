package com.carddemo.account.domain.validation;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link AlphanumericRequiredValidator}, the edit that admits letters, digits
 * and spaces.
 *
 * <p>The subject translates paragraph {@code 1230-EDIT-ALPHANUM-REQD}, which spans
 * app/cbl/COACTUPC.cbl:L1955 through app/cbl/COACTUPC.cbl:L2009. Two messages are
 * reachable: the not-supplied text at app/cbl/COACTUPC.cbl:L1972 and the character-class
 * text at app/cbl/COACTUPC.cbl:L1999. The allowed set holds the 62 characters of group
 * {@code LIT-ALL-ALPHANUM-FROM-X} at app/cbl/COACTUPC.cbl:L586-L593.</p>
 *
 * <p>Census result for paragraph 1230: zero invocation sites. A whole-file scan of all
 * 4236 lines found no {@code PERFORM} of the paragraph. The scan stripped carriage
 * returns and tolerated repeated spaces after the verb. The four references are the label
 * at L1955, the {@code GO TO} statements at L1978 and L2004, and the exit label at L2009.
 * The register at card-platform/docs/business-rule-flags.md carries the finding.</p>
 *
 * <p>A digit passes the edit here. The same digit fails paragraph
 * {@code 1225-EDIT-ALPHA-REQD}, whose message at app/cbl/COACTUPC.cbl:L1941 names
 * alphabets alone. The methods below assert that boundary, both letter cases, and the
 * space the destructive conversion leaves behind. They also assert the three-way
 * not-supplied test, the order of the two tests, label trimming, and the count of
 * reachable messages.</p>
 *
 * <p>Every input below is built in the test. No container, no data source and no test
 * double takes part, so {@code mvn test} covers the class on a clean machine.</p>
 *
 * <p>Decision record: card-platform/docs/decision-log.md.</p>
 */
@DisplayName("AlphanumericRequiredValidator, the letters, digits and spaces edit")
class AlphanumericRequiredValidatorTest {

    /** Field name the two messages open with, supplied to every call below. */
    private static final String LABEL = "Account Group Id";

    /**
     * Declared width of {@code WS-EDIT-VARIABLE-NAME PIC X(25)} at
     * app/cbl/COACTUPC.cbl:L53, the host that carries the field name.
     */
    private static final int LABEL_HOST_WIDTH = 25;

    /**
     * Message literal at app/cbl/COACTUPC.cbl:L1972, eighteen characters wide, opening
     * with a space and closing with a period.
     */
    private static final String NOT_SUPPLIED = " must be supplied.";

    /**
     * Message literal at app/cbl/COACTUPC.cbl:L1999, thirty-six characters wide, opening
     * with a space and closing with a period. The source spells the word "alphabets".
     */
    private static final String NOT_ALPHANUMERIC = " can have numbers or alphabets only.";

    /**
     * The characters group {@code LIT-ALL-ALPHANUM-FROM-X} admits, restated from
     * app/cbl/COACTUPC.cbl:L586-L593: {@code LIT-UPPER PIC X(26)},
     * {@code LIT-LOWER PIC X(26)} and {@code LIT-NUMBERS PIC X(10)}.
     */
    private static final String ALLOWED =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
                    + "abcdefghijklmnopqrstuvwxyz"
                    + "0123456789";

    /**
     * Size of the group at app/cbl/COACTUPC.cbl:L586. The receiving field of the move on
     * line 1982 is {@code LIT-ALL-ALPHANUM-FROM PIC X(62)} at app/cbl/COACTUPC.cbl:L608.
     */
    private static final int ALLOWED_SET_SIZE = 62;

    /**
     * Punctuation outside the allowed set, drawn from characters a submitted form carries.
     * Every one of them survives the conversion at app/cbl/COACTUPC.cbl:L1984-L1986.
     */
    private static final String OUTSIDE_ALLOWED_SET = "-&.,/@#*$%+=?!;:'_()";

    /**
     * Width the edit covers, standing in for {@code WS-EDIT-ALPHANUM-LENGTH} at
     * app/cbl/COACTUPC.cbl:L62. Every value these tests submit fits inside it.
     */
    private static final int EDIT_LENGTH = 20;

    /** Value of digits alone, the boundary case against the alphabetic-only edit. */
    private static final String DIGITS_ONLY = "0123456789";

    /** Value of letters followed by digits. */
    private static final String LETTERS_AND_DIGITS = "GOLD100";

    /** Value of letters, a space and digits. */
    private static final String LETTERS_SPACE_DIGITS = "GOLD 100";

    /** Value carrying one hyphen, which sits outside the allowed set. */
    private static final String LETTERS_HYPHEN_DIGITS = "GOLD-100";

    @Test
    @DisplayName("A value of digits alone passes, and a value of letters with digits passes")
    void digitsAloneAndLettersWithDigitsPass() {
        EditResult digits = AlphanumericRequiredValidator.validate(LABEL, DIGITS_ONLY, EDIT_LENGTH);
        EditResult lettersAndDigits =
                AlphanumericRequiredValidator.validate(LABEL, LETTERS_AND_DIGITS, EDIT_LENGTH);

        assertThat(digits.valid()).isTrue();
        assertThat(digits.message()).isNull();
        assertThat(digits.hasMessage()).isFalse();

        assertThat(lettersAndDigits.valid()).isTrue();
        assertThat(lettersAndDigits.message()).isNull();
        assertThat(lettersAndDigits.hasMessage()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"account", "ACCOUNT", "AcCoUnT", "zebra", "ZEBRA", "aZ"})
    @DisplayName("Lower case, upper case and mixed case all pass")
    void bothLetterCasesPass(String value) {
        EditResult result = AlphanumericRequiredValidator.validate(LABEL, value, EDIT_LENGTH);

        assertThat(result.valid()).isTrue();
        assertThat(result.message()).isNull();
        assertThat(result.hasMessage()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GOLD 100", "A B", "GOLD 100 PLUS", " LEADING", "TRAILING ", "A  B"})
    @DisplayName("A value carrying a space passes, and the conversion leaves the space in place")
    void valueCarryingSpacePasses(String value) {
        EditResult result = AlphanumericRequiredValidator.validate(LABEL, value, EDIT_LENGTH);

        assertThat(result.valid()).isTrue();
        assertThat(result.message()).isNull();
    }

    @ParameterizedTest
    @MethodSource("allowedCharacters")
    @DisplayName("Every one of the 62 allowed characters passes on its own")
    void everyAllowedCharacterPasses(String character) {
        EditResult result = AlphanumericRequiredValidator.validate(LABEL, character, 1);

        assertThat(result.valid()).isTrue();
        assertThat(result.message()).isNull();
    }

    @Test
    @DisplayName("The allowed set restated here holds 62 distinct characters and no space")
    void allowedSetHoldsSixtyTwoDistinctCharacters() {
        assertThat(ALLOWED).hasSize(ALLOWED_SET_SIZE);
        assertThat(ALLOWED.chars().distinct().count()).isEqualTo(ALLOWED_SET_SIZE);
        assertThat(ALLOWED).doesNotContain(" ");
    }

    @ParameterizedTest
    @MethodSource("charactersOutsideAllowedSet")
    @DisplayName("A character outside the 62 fails and names the character class")
    void characterOutsideAllowedSetFails(String character) {
        String value = "GOLD" + character + "100";

        EditResult result = AlphanumericRequiredValidator.validate(LABEL, value, EDIT_LENGTH);

        assertThat(result.valid()).isFalse();
        assertThat(result.hasMessage()).isTrue();
        assertThat(result.message())
                .isEqualTo(LABEL + NOT_ALPHANUMERIC)
                .endsWith(NOT_ALPHANUMERIC);
    }

    @Test
    @DisplayName("A null value, an empty value and a value of spaces all report the field not supplied")
    void nullEmptyAndSpacesReportNotSupplied() {
        List<EditResult> results = List.of(
                AlphanumericRequiredValidator.validate(LABEL, null, EDIT_LENGTH),
                AlphanumericRequiredValidator.validate(LABEL, "", EDIT_LENGTH),
                AlphanumericRequiredValidator.validate(LABEL, "     ", EDIT_LENGTH));

        for (EditResult result : results) {
            assertThat(result.valid()).isFalse();
            assertThat(result.hasMessage()).isTrue();
            assertThat(result.message())
                    .isEqualTo(LABEL + NOT_SUPPLIED)
                    .endsWith(NOT_SUPPLIED);
        }
    }

    @Test
    @DisplayName("A value of spaces reports the field not supplied, and the character class never appears")
    void valueOfSpacesReportsNotSupplied() {
        EditResult spacesOnly = AlphanumericRequiredValidator.validate(LABEL, "   ", EDIT_LENGTH);
        EditResult oneLetter = AlphanumericRequiredValidator.validate(LABEL, "A", EDIT_LENGTH);

        assertThat(spacesOnly.valid()).isFalse();
        assertThat(spacesOnly.message()).isEqualTo(LABEL + NOT_SUPPLIED);
        assertThat(spacesOnly.message()).doesNotContain("alphabets");

        // Both values reduce to spaces under the conversion at
        // app/cbl/COACTUPC.cbl:L1984-L1986. The presence test at
        // app/cbl/COACTUPC.cbl:L1960-L1965 separates them, and the jump at
        // app/cbl/COACTUPC.cbl:L1978 leaves the paragraph before the conversion runs.
        assertThat(oneLetter.valid()).isTrue();
        assertThat(oneLetter.message()).isNull();
    }

    @Test
    @DisplayName("A field name padded to the 25-character host leaves no space in either message")
    void paddedLabelLeavesNoSpaceInEitherMessage() {
        String paddedLabel = padToLabelHost(LABEL);

        EditResult notSupplied =
                AlphanumericRequiredValidator.validate(paddedLabel, "   ", EDIT_LENGTH);
        EditResult notAlphanumeric = AlphanumericRequiredValidator.validate(
                paddedLabel, LETTERS_HYPHEN_DIGITS, EDIT_LENGTH);

        assertThat(paddedLabel).hasSize(LABEL_HOST_WIDTH).endsWith(" ");

        assertThat(notSupplied.message()).isEqualTo(LABEL + NOT_SUPPLIED);
        assertThat(notSupplied.message()).doesNotContain("  ");

        assertThat(notAlphanumeric.message()).isEqualTo(LABEL + NOT_ALPHANUMERIC);
        assertThat(notAlphanumeric.message()).doesNotContain("  ");
    }

    @Test
    @DisplayName("Exactly two messages are reachable across a broad sweep of values")
    void exactlyTwoMessagesAreReachable() {
        Set<String> messages = new LinkedHashSet<>();

        for (String value : sweepValues()) {
            EditResult result = AlphanumericRequiredValidator.validate(LABEL, value, EDIT_LENGTH);

            if (result.hasMessage()) {
                messages.add(result.message());
            }
        }

        assertThat(messages)
                .containsExactlyInAnyOrder(LABEL + NOT_SUPPLIED, LABEL + NOT_ALPHANUMERIC);
    }

    @Test
    @DisplayName("The length argument bounds the edit, and a character past it stays uninspected")
    void lengthArgumentBoundsTheEdit() {
        String value = "GOLD-";

        EditResult insideWindow =
                AlphanumericRequiredValidator.validate(LABEL, value, value.length());
        EditResult outsideWindow =
                AlphanumericRequiredValidator.validate(LABEL, value, value.length() - 1);

        assertThat(insideWindow.valid()).isFalse();
        assertThat(insideWindow.message()).isEqualTo(LABEL + NOT_ALPHANUMERIC);

        assertThat(outsideWindow.valid()).isTrue();
        assertThat(outsideWindow.message()).isNull();
    }

    @Test
    @DisplayName("A second call on the same value returns the same verdict, never the not-supplied one")
    void secondCallReturnsSameVerdict() {
        // The conversion at app/cbl/COACTUPC.cbl:L1984 overwrites the edit field, which
        // leaves a field of spaces behind. The subject converts a copy, so a repeated call
        // reaches the character-class test with the submitted characters still in place.
        EditResult notSuppliedVerdict = EditResult.failure(LABEL + NOT_SUPPLIED);

        EditResult firstPass =
                AlphanumericRequiredValidator.validate(LABEL, LETTERS_SPACE_DIGITS, EDIT_LENGTH);
        EditResult secondPass =
                AlphanumericRequiredValidator.validate(LABEL, LETTERS_SPACE_DIGITS, EDIT_LENGTH);

        assertThat(secondPass).isEqualTo(firstPass).isEqualTo(EditResult.ok());
        assertThat(secondPass).isNotEqualTo(notSuppliedVerdict);

        EditResult firstFail =
                AlphanumericRequiredValidator.validate(LABEL, LETTERS_HYPHEN_DIGITS, EDIT_LENGTH);
        EditResult secondFail =
                AlphanumericRequiredValidator.validate(LABEL, LETTERS_HYPHEN_DIGITS, EDIT_LENGTH);

        assertThat(secondFail)
                .isEqualTo(firstFail)
                .isEqualTo(EditResult.failure(LABEL + NOT_ALPHANUMERIC));
        assertThat(secondFail).isNotEqualTo(notSuppliedVerdict);
    }

    @Test
    @DisplayName("The subject is final, exposes one static entry point, and hides its constructor")
    void subjectExposesOneStaticEntryPoint() {
        assertThat(Modifier.isFinal(AlphanumericRequiredValidator.class.getModifiers())).isTrue();

        List<Method> publicMethods =
                Arrays.stream(AlphanumericRequiredValidator.class.getDeclaredMethods())
                        .filter(method -> !method.isSynthetic())
                        .filter(method -> Modifier.isPublic(method.getModifiers()))
                        .toList();

        assertThat(publicMethods).hasSize(1);

        Method entryPoint = publicMethods.get(0);

        assertThat(entryPoint.getName()).isEqualTo("validate");
        assertThat(Modifier.isStatic(entryPoint.getModifiers())).isTrue();
        assertThat(entryPoint.getReturnType()).isEqualTo(EditResult.class);
        assertThat(entryPoint.getParameterTypes())
                .containsExactly(String.class, String.class, int.class);

        Constructor<?>[] constructors =
                AlphanumericRequiredValidator.class.getDeclaredConstructors();

        assertThat(constructors).hasSize(1);
        assertThat(constructors[0].getParameterCount()).isZero();
        assertThat(Modifier.isPrivate(constructors[0].getModifiers())).isTrue();
    }

    /**
     * Streams the 62 characters of group {@code LIT-ALL-ALPHANUM-FROM-X}, one per test
     * case.
     *
     * @return one single-character value for each allowed character
     */
    private static Stream<String> allowedCharacters() {
        return ALLOWED.chars().mapToObj(Character::toString);
    }

    /**
     * Streams the punctuation held by {@link #OUTSIDE_ALLOWED_SET}, one per test case.
     *
     * @return one single-character value for each character outside the allowed set
     */
    private static Stream<String> charactersOutsideAllowedSet() {
        return OUTSIDE_ALLOWED_SET.chars().mapToObj(Character::toString);
    }

    /**
     * Builds the value sweep the message-count test walks. The sweep covers the three
     * not-supplied arms, every allowed character on its own, every punctuation character
     * inside a longer value, and two values that pass.
     *
     * @return the sweep, holding one null entry for the low-values arm
     */
    private static List<String> sweepValues() {
        List<String> values = new ArrayList<>();

        values.add(null);
        values.add("");
        values.add("     ");
        allowedCharacters().forEach(values::add);
        charactersOutsideAllowedSet()
                .map(character -> "GOLD" + character + "100")
                .forEach(values::add);
        values.add(LETTERS_SPACE_DIGITS);
        values.add(DIGITS_ONLY);

        return values;
    }

    /**
     * Pads a field name to the width of {@code WS-EDIT-VARIABLE-NAME PIC X(25)} at
     * app/cbl/COACTUPC.cbl:L53, as a COBOL {@code MOVE} into that host does.
     *
     * @param label the field name, shorter than the host width
     * @return the field name followed by spaces, 25 characters wide
     */
    private static String padToLabelHost(String label) {
        return label + " ".repeat(LABEL_HOST_WIDTH - label.length());
    }
}
