package com.carddemo.account.domain.validation;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link AlphabeticRequiredValidator}, the required alphabetic field edit of paragraph
 * {@code 1225-EDIT-ALPHA-REQD} at app/cbl/COACTUPC.cbl:L1898 through its exit paragraph on line
 * 1951.
 *
 * <p>The allowed set holds 52 characters. Group {@code LIT-ALL-ALPHA-FROM-X} at
 * app/cbl/COACTUPC.cbl:L587 spans {@code LIT-UPPER PIC X(26)} at lines 588 and 589 and
 * {@code LIT-LOWER PIC X(26)} at lines 590 and 591. Upper case and lower case both pass. A digit
 * belongs to the wider 62-character group at line 586, which also spans
 * {@code LIT-NUMBERS PIC X(10)} at lines 592 and 593. The wider group serves
 * {@code AlphanumericRequiredValidator}, and a digit fails the edit under test.</p>
 *
 * <p>Line 1900 raises the failure flag before any test runs. Lines 1903 to 1908 then test three
 * ways for an absent value, and line 1921 jumps to the exit paragraph. An absent value therefore
 * carries {@code " must be supplied."} from line 1915, and the character-class test never reads
 * it.</p>
 *
 * <p>Lines 1925 to 1928 convert every member of the 52-character set to a space, and lines 1930 to
 * 1933 pass the value once the trimmed remainder is empty. A space survives the conversion
 * untouched, so a value holding a space between two words passes. A surviving digit or punctuation
 * mark carries {@code " can have alphabets only."} from line 1941. Line 1949 records success.</p>
 *
 * <p>app/cbl/COACTUPC.cbl applies the edit to five fields. First Name at line 1563 and Last Name at
 * 1579 each run over 25 characters. State at 1595 runs over 2, City at 1618 over 50, and Country at
 * 1627 over 3. The gate at line 1599 reaches the state code edit only once the alphabetic edit has
 * passed, and that gate sits outside this class.</p>
 *
 * <p>Every test runs on a plain Java virtual machine. No Spring context, no database and no
 * container starts.</p>
 */
@DisplayName("AlphabeticRequiredValidator, the required alphabetic edit of paragraph 1225")
class AlphabeticRequiredValidatorTest {

    /** Label of the First Name call site, at app/cbl/COACTUPC.cbl:L1560. */
    private static final String FIRST_NAME_LABEL = "First Name";

    /** Label of the Last Name call site, at app/cbl/COACTUPC.cbl:L1576. */
    private static final String LAST_NAME_LABEL = "Last Name";

    /** Label of the State call site, at app/cbl/COACTUPC.cbl:L1592. */
    private static final String STATE_LABEL = "State";

    /** Label of the City call site, at app/cbl/COACTUPC.cbl:L1615. */
    private static final String CITY_LABEL = "City";

    /** Label of the Country call site, at app/cbl/COACTUPC.cbl:L1623. */
    private static final String COUNTRY_LABEL = "Country";

    /**
     * Inspected width of the two name call sites, from the {@code MOVE 25} statements at
     * app/cbl/COACTUPC.cbl:L1562 and line 1578.
     */
    private static final int NAME_LENGTH = 25;

    /**
     * Inspected width of the State call site, from the {@code MOVE 2} statement at
     * app/cbl/COACTUPC.cbl:L1594.
     */
    private static final int STATE_LENGTH = 2;

    /**
     * Inspected width of the City call site, from the {@code MOVE 50} statement at
     * app/cbl/COACTUPC.cbl:L1617.
     */
    private static final int CITY_LENGTH = 50;

    /**
     * Inspected width of the Country call site, from the {@code MOVE 3} statement at
     * app/cbl/COACTUPC.cbl:L1626.
     */
    private static final int COUNTRY_LENGTH = 3;

    /**
     * Declared width of the label host {@code WS-EDIT-VARIABLE-NAME PIC X(25)} at
     * app/cbl/COACTUPC.cbl:L53. A label shorter than the host carries trailing spaces.
     */
    private static final int LABEL_HOST_WIDTH = 25;

    /**
     * Message text for an absent value, quoted from the literal at
     * app/cbl/COACTUPC.cbl:L1915. One leading space and one trailing period.
     */
    private static final String MUST_BE_SUPPLIED = " must be supplied.";

    /**
     * Message text for a character outside the 52-character set, quoted from the literal
     * at app/cbl/COACTUPC.cbl:L1941. One leading space, the plural noun the source uses,
     * and one trailing period.
     */
    private static final String CAN_HAVE_ALPHABETS_ONLY = " can have alphabets only.";

    /**
     * Message text for a value wider than the edited field. No source literal carries
     * this text: the source moves a fixed-width screen field into its edit field, so a wider
     * value cannot reach paragraph 1225.
     */
    private static final String NO_LONGER_THAN = " must be no longer than ";

    /** No COBOL ancestor. Closes the text {@link #NO_LONGER_THAN} opens. */
    private static final String CHARACTERS = " characters.";

    /** The width message a State call site produces. */
    private static final String STATE_NO_LONGER_THAN_WIDTH =
            STATE_LABEL + NO_LONGER_THAN + STATE_LENGTH + CHARACTERS;

    /** The width message a Country call site produces. */
    private static final String COUNTRY_NO_LONGER_THAN_WIDTH =
            COUNTRY_LABEL + NO_LONGER_THAN + COUNTRY_LENGTH + CHARACTERS;

    /** The 26 characters of {@code LIT-UPPER} at app/cbl/COACTUPC.cbl:L588-L589. */
    /**
     * One character of the figurative constant {@code LOW-VALUES}, which a COBOL comparison tests
     * for one position at a time.
     */
    private static final String LOW_VALUE = "\0";

    private static final String LIT_UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    /** The 26 characters of {@code LIT-LOWER} at app/cbl/COACTUPC.cbl:L590-L591. */
    private static final String LIT_LOWER = "abcdefghijklmnopqrstuvwxyz";

    /** The presence message a First Name call site produces. */
    private static final String FIRST_NAME_NOT_SUPPLIED = FIRST_NAME_LABEL + MUST_BE_SUPPLIED;

    /** The character-class message a First Name call site produces. */
    private static final String FIRST_NAME_WRONG_CLASS = FIRST_NAME_LABEL + CAN_HAVE_ALPHABETS_ONLY;

    /**
     * Values that fail the edit, spanning both failing arms. The list holds a null, an
     * empty value, two all-spaces values, four values carrying a digit, four values
     * carrying a punctuation mark, and one value carrying an accented letter.
     */
    private static final List<String> FAILING_VALUES = Arrays.asList(
            null,
            "",
            " ",
            "     ",
            "John2",
            "7",
            "Smith99",
            "12345",
            "O'Brien",
            "Mary-Jane",
            "St. Louis",
            "Smith_Jones",
            "Ren\u00e9e");

    /** Values that pass the edit, spanning both letter cases and an embedded space. */
    private static final List<String> PASSING_VALUES = List.of(
            "Johnson",
            "JOHNSON",
            "McDonald",
            "Van Der Berg");

    /**
     * The five call sites of paragraph {@code 1225-EDIT-ALPHA-REQD}, each with the label
     * and the inspected width app/cbl/COACTUPC.cbl passes to it. Three of the five values
     * are shorter than the width, so the copy carries trailing pad spaces.
     *
     * @return one argument triple per call site: label, value, and inspected width
     */
    private static Stream<Arguments> measuredCallSites() {
        return Stream.of(
                Arguments.of(FIRST_NAME_LABEL, "Johnson", NAME_LENGTH),
                Arguments.of(LAST_NAME_LABEL, "Smith", NAME_LENGTH),
                Arguments.of(STATE_LABEL, "NY", STATE_LENGTH),
                Arguments.of(CITY_LABEL, "New York", CITY_LENGTH),
                Arguments.of(COUNTRY_LABEL, "USA", COUNTRY_LENGTH));
    }

    /**
     * Pads a label with trailing spaces to the width of
     * {@code WS-EDIT-VARIABLE-NAME PIC X(25)} at app/cbl/COACTUPC.cbl:L53.
     *
     * @param label label text, no longer than the host width
     * @return the label followed by pad spaces, exactly {@link #LABEL_HOST_WIDTH} long
     */
    private static String padToLabelHost(String label) {
        return label + " ".repeat(LABEL_HOST_WIDTH - label.length());
    }

    @Test
    @DisplayName("A value of only lower-case letters passes and carries no message")
    void allLowerCaseValuePasses() {
        EditResult result = AlphabeticRequiredValidator.validate(FIRST_NAME_LABEL, "johnson", NAME_LENGTH);

        assertThat(result.valid()).isTrue();
        assertThat(result.message()).isNull();
        assertThat(result.hasMessage()).isFalse();

        // All 26 characters of LIT-LOWER at app/cbl/COACTUPC.cbl:L590-L591.
        EditResult wholeLiteral = AlphabeticRequiredValidator
                .validate(CITY_LABEL, LIT_LOWER, LIT_LOWER.length());

        assertThat(LIT_LOWER).hasSize(26);
        assertThat(wholeLiteral.valid()).isTrue();
        assertThat(wholeLiteral.message()).isNull();
    }

    @Test
    @DisplayName("A value of only upper-case letters passes and carries no message")
    void allUpperCaseValuePasses() {
        EditResult result = AlphabeticRequiredValidator.validate(FIRST_NAME_LABEL, "JOHNSON", NAME_LENGTH);

        assertThat(result.valid()).isTrue();
        assertThat(result.message()).isNull();
        assertThat(result.hasMessage()).isFalse();

        // All 26 characters of LIT-UPPER at app/cbl/COACTUPC.cbl:L588-L589.
        EditResult wholeLiteral = AlphabeticRequiredValidator
                .validate(CITY_LABEL, LIT_UPPER, LIT_UPPER.length());

        assertThat(LIT_UPPER).hasSize(26);
        assertThat(wholeLiteral.valid()).isTrue();
        assertThat(wholeLiteral.message()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"McDonald", "MacArthur", "DeLaCruz", "aB", "Zy"})
    @DisplayName("A mixed-case value passes")
    void mixedCaseValuePasses(String value) {
        EditResult result = AlphabeticRequiredValidator.validate(LAST_NAME_LABEL, value, NAME_LENGTH);

        assertThat(result.valid()).isTrue();
        assertThat(result.message()).isNull();
        assertThat(result.hasMessage()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"New York", "Van Der Berg", "San Francisco", "North Dakota"})
    @DisplayName("A value holding a space between two words passes")
    void valueHoldingASpaceBetweenWordsPasses(String value) {
        EditResult result = AlphabeticRequiredValidator.validate(CITY_LABEL, value, CITY_LENGTH);

        assertThat(value).contains(" ");
        assertThat(result.valid()).isTrue();
        assertThat(result.message()).isNull();
        assertThat(result.hasMessage()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"John2", "7", "Smith99", "12345", "9Smith", "Smith 3rd"})
    @DisplayName("A digit fails with the character-class message")
    void digitFailsWithTheCharacterClassMessage(String value) {
        EditResult result = AlphabeticRequiredValidator.validate(FIRST_NAME_LABEL, value, NAME_LENGTH);

        // A digit sits in the 62-character group at app/cbl/COACTUPC.cbl:L586 and outside
        // the 52-character group at line 587.
        assertThat(result.valid()).isFalse();
        assertThat(result.hasMessage()).isTrue();
        assertThat(result.message()).isEqualTo(FIRST_NAME_WRONG_CLASS);
        assertThat(result.message()).isNotEqualTo(FIRST_NAME_NOT_SUPPLIED);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "O'Brien",
            "Mary-Jane",
            "St. Louis",
            "Smith_Jones",
            "Ford & Sons",
            "Ren\u00e9e",
            "M\u00fcller"})
    @DisplayName("Any other character outside the 52-character set fails with the character-class message")
    void characterOutsideTheFiftyTwoSetFailsWithTheCharacterClassMessage(String value) {
        EditResult result = AlphabeticRequiredValidator.validate(FIRST_NAME_LABEL, value, NAME_LENGTH);

        // Lines 1926 to 1928 leave the character untouched, so lines 1930 to 1933 find it.
        assertThat(result.valid()).isFalse();
        assertThat(result.hasMessage()).isTrue();
        assertThat(result.message()).isEqualTo(FIRST_NAME_WRONG_CLASS);
        assertThat(result.message()).isNotEqualTo(FIRST_NAME_NOT_SUPPLIED);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "  ", "     ", "                         "})
    @DisplayName("An absent value fails with the presence message")
    void absentValueFailsWithThePresenceMessage(String value) {
        EditResult result = AlphabeticRequiredValidator.validate(FIRST_NAME_LABEL, value, NAME_LENGTH);

        // The three-way test at app/cbl/COACTUPC.cbl:L1903-L1908 covers a null, an empty
        // value, and a value of only spaces.
        assertThat(result.valid()).isFalse();
        assertThat(result.hasMessage()).isTrue();
        assertThat(result.message()).isEqualTo(FIRST_NAME_NOT_SUPPLIED);
    }

    @Test
    @DisplayName("A field of LOW-VALUES at the inspected width carries the presence message, the "
            + "first arm at app/cbl/COACTUPC.cbl:L1904")
    void lowValuesAtTheInspectedWidthCarriesThePresenceMessage() {
        for (int width : new int[] {STATE_LENGTH, COUNTRY_LENGTH, NAME_LENGTH, CITY_LENGTH}) {
            EditResult result = AlphabeticRequiredValidator.validate(
                    FIRST_NAME_LABEL, LOW_VALUE.repeat(width), width);

            assertThat(result.valid()).as("width %d", width).isFalse();
            assertThat(result.message()).as("width %d", width).isEqualTo(FIRST_NAME_NOT_SUPPLIED);
        }
    }

    @Test
    @DisplayName("A field of LOW-VALUES narrower than the inspected width still carries the "
            + "presence message, because the MOVE pads the rest with spaces")
    void lowValuesBelowTheInspectedWidthCarriesThePresenceMessage() {
        for (int supplied : new int[] {1, 3, NAME_LENGTH - 1}) {
            EditResult result = AlphabeticRequiredValidator.validate(
                    FIRST_NAME_LABEL, LOW_VALUE.repeat(supplied), NAME_LENGTH);

            // Arm 1 fails: the slice is null characters then spaces. Arm 2 fails for the same
            // reason. Arm 3 measures FUNCTION TRIM, which removes the space alone, so the null
            // characters survive and the slice falls through to the character-class test.
            assertThat(result.valid()).as("%d supplied of %d", supplied, NAME_LENGTH).isFalse();
            assertThat(result.message())
                    .as("%d supplied of %d", supplied, NAME_LENGTH)
                    .isEqualTo(FIRST_NAME_WRONG_CLASS);
        }
    }

    @Test
    @DisplayName("A field of LOW-VALUES is read only to the inspected width, and content past that "
            + "width is refused")
    void lowValuesIsReadOnlyToTheInspectedWidthAndContentPastItIsRefused() {
        String padded = LOW_VALUE.repeat(STATE_LENGTH) + "  ";

        EditResult stateWidth =
                AlphabeticRequiredValidator.validate(STATE_LABEL, padded, STATE_LENGTH);

        assertThat(stateWidth.valid()).isFalse();
        assertThat(stateWidth.message()).isEqualTo(STATE_LABEL + MUST_BE_SUPPLIED);

        String beyond = LOW_VALUE.repeat(STATE_LENGTH) + "AB";
        EditResult refused =
                AlphabeticRequiredValidator.validate(STATE_LABEL, beyond, STATE_LENGTH);

        assertThat(refused.valid()).isFalse();
        assertThat(refused.message()).isEqualTo(STATE_NO_LONGER_THAN_WIDTH);

        EditResult fullWidth =
                AlphabeticRequiredValidator.validate(STATE_LABEL, beyond, beyond.length());

        assertThat(fullWidth.valid()).isFalse();
        assertThat(fullWidth.message()).isEqualTo(STATE_LABEL + CAN_HAVE_ALPHABETS_ONLY);
    }

    @Test
    @DisplayName("One alphabetic character followed by LOW-VALUES fails the character-class test, "
            + "because FUNCTION TRIM leaves a null character standing")
    void oneLetterFollowedByLowValuesFailsTheCharacterClassTest() {
        String letterThenNulls = "A" + LOW_VALUE.repeat(NAME_LENGTH - 1);

        EditResult result = AlphabeticRequiredValidator.validate(
                FIRST_NAME_LABEL, letterThenNulls, NAME_LENGTH);

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).isEqualTo(FIRST_NAME_WRONG_CLASS);
    }

    @Test
    @DisplayName("An all-spaces value carries the presence message and not the character-class message")
    void allSpacesCarriesThePresenceMessage() {
        String allSpaces = " ".repeat(NAME_LENGTH);

        EditResult result = AlphabeticRequiredValidator.validate(FIRST_NAME_LABEL, allSpaces, NAME_LENGTH);

        // Lines 1903 to 1908 run first and line 1921 jumps to the exit paragraph, so the
        // conversion at lines 1925 to 1928 never reads the value.
        assertThat(allSpaces).hasSize(NAME_LENGTH);
        assertThat(result.valid()).isFalse();
        assertThat(result.message()).isEqualTo(FIRST_NAME_NOT_SUPPLIED);
        assertThat(result.message()).doesNotContain("alphabets");
        assertThat(result.message()).isNotEqualTo(FIRST_NAME_WRONG_CLASS);
    }

    @Test
    @DisplayName("Both messages open with the trimmed label, and host pad spaces do not leak")
    void bothMessagesOpenWithTheTrimmedLabel() {
        String paddedLabel = padToLabelHost(FIRST_NAME_LABEL);

        EditResult absent = AlphabeticRequiredValidator.validate(paddedLabel, "", NAME_LENGTH);
        EditResult wrongClass = AlphabeticRequiredValidator.validate(paddedLabel, "John2", NAME_LENGTH);

        // FUNCTION TRIM(WS-EDIT-VARIABLE-NAME) at app/cbl/COACTUPC.cbl:L1914 and line 1940.
        assertThat(paddedLabel).hasSize(LABEL_HOST_WIDTH).endsWith(" ");
        assertThat(absent.message()).isEqualTo(FIRST_NAME_NOT_SUPPLIED);
        assertThat(absent.message()).startsWith("First Name ").doesNotContain("  ");
        assertThat(wrongClass.message()).isEqualTo(FIRST_NAME_WRONG_CLASS);
        assertThat(wrongClass.message()).startsWith("First Name ").doesNotContain("  ");
    }

    @Test
    @DisplayName("Each message matches its source literal character for character")
    void eachMessageMatchesItsSourceLiteral() {
        // A null label trims to no text, so each message arrives as the bare literal.
        EditResult absent = AlphabeticRequiredValidator.validate(null, "", NAME_LENGTH);
        EditResult wrongClass = AlphabeticRequiredValidator.validate(null, "John2", NAME_LENGTH);

        assertThat(absent.message()).isEqualTo(" must be supplied.");
        assertThat(wrongClass.message()).isEqualTo(" can have alphabets only.");

        // The leading space at app/cbl/COACTUPC.cbl:L1915 and line 1941 separates each
        // message from the label, and the period closes the sentence the source builds.
        assertThat(MUST_BE_SUPPLIED).startsWith(" ").endsWith(".");
        assertThat(CAN_HAVE_ALPHABETS_ONLY).startsWith(" ").endsWith(".");

        // Line 1941 spells the noun in the plural, and the target carries that spelling.
        assertThat(CAN_HAVE_ALPHABETS_ONLY).contains("alphabets");
    }

    @Test
    @DisplayName("Exactly two messages are reachable")
    void exactlyTwoMessagesAreReachable() {
        Set<String> messages = new LinkedHashSet<>();

        for (String value : FAILING_VALUES) {
            EditResult result = AlphabeticRequiredValidator.validate(FIRST_NAME_LABEL, value, NAME_LENGTH);
            assertThat(result.valid()).isFalse();
            messages.add(result.message());
        }

        for (String value : PASSING_VALUES) {
            EditResult result = AlphabeticRequiredValidator.validate(FIRST_NAME_LABEL, value, NAME_LENGTH);
            assertThat(result.valid()).isTrue();
            assertThat(result.message()).isNull();
        }

        // Line 1915 and line 1941 hold the only two literals the paragraph strings into
        // WS-RETURN-MSG at app/cbl/COACTUPC.cbl:L479.
        assertThat(messages).containsExactlyInAnyOrder(FIRST_NAME_NOT_SUPPLIED, FIRST_NAME_WRONG_CLASS);
    }

    @ParameterizedTest
    @MethodSource("measuredCallSites")
    @DisplayName("Each measured call site accepts its value at the width the source passes")
    void measuredCallSitesAcceptTheirValues(String label, String value, int length) {
        EditResult result = AlphabeticRequiredValidator.validate(label, value, length);

        assertThat(value.length()).isLessThanOrEqualTo(length);
        assertThat(result.valid()).isTrue();
        assertThat(result.message()).isNull();
        assertThat(result.hasMessage()).isFalse();
    }

    @Test
    @DisplayName("Pad spaces beyond the value do not fail the edit")
    void padSpacesBeyondTheValueDoNotFailTheEdit() {
        // A short value leaves pad spaces in the copy, and a space passes the conversion
        // at app/cbl/COACTUPC.cbl:L1926-L1928 untouched.
        EditResult onePadded = AlphabeticRequiredValidator.validate(CITY_LABEL, "A", CITY_LENGTH);
        EditResult namePadded = AlphabeticRequiredValidator.validate(FIRST_NAME_LABEL, "Jo", NAME_LENGTH);
        EditResult countryPadded = AlphabeticRequiredValidator.validate(COUNTRY_LABEL, "US", COUNTRY_LENGTH);

        assertThat(onePadded.valid()).isTrue();
        assertThat(onePadded.message()).isNull();
        assertThat(namePadded.valid()).isTrue();
        assertThat(namePadded.message()).isNull();
        assertThat(countryPadded.valid()).isTrue();
        assertThat(countryPadded.message()).isNull();
    }

    @Test
    @DisplayName("A character past the inspected window fails the edit, and pad spaces do not")
    void aCharacterPastTheInspectedWindowFailsTheEdit() {
        // Reference modification (1:WS-EDIT-ALPHANUM-LENGTH) at app/cbl/COACTUPC.cbl:L1903 and
        // line 1926 bounds the window the State call site inspects at line 1594. The source moves
        // a PIC X(02) screen field into that window, so it drops nothing but padding.
        //
        // No COBOL ancestor: a caller of this edit can supply a wider value, and the edit refuses
        // one that carries a character the window would not cover. It therefore never passes a
        // verdict on the first characters of a longer value.
        EditResult insideWindow = AlphabeticRequiredValidator.validate(STATE_LABEL, "N1", STATE_LENGTH);
        EditResult pastWindow = AlphabeticRequiredValidator.validate(STATE_LABEL, "NY1", STATE_LENGTH);
        EditResult markupPastWindow =
                AlphabeticRequiredValidator.validate(STATE_LABEL, "NY<script>", STATE_LENGTH);
        EditResult padSpacePastWindow =
                AlphabeticRequiredValidator.validate(STATE_LABEL, "NY   ", STATE_LENGTH);
        EditResult twoLetters = AlphabeticRequiredValidator.validate(STATE_LABEL, "NY", STATE_LENGTH);

        assertThat(insideWindow.valid()).isFalse();
        assertThat(insideWindow.message()).isEqualTo(STATE_LABEL + CAN_HAVE_ALPHABETS_ONLY);
        assertThat(pastWindow.valid()).isFalse();
        assertThat(pastWindow.message()).isEqualTo(STATE_NO_LONGER_THAN_WIDTH);
        assertThat(markupPastWindow.valid()).isFalse();
        assertThat(markupPastWindow.message()).isEqualTo(STATE_NO_LONGER_THAN_WIDTH);
        assertThat(padSpacePastWindow.valid()).isTrue();
        assertThat(padSpacePastWindow.message()).isNull();
        assertThat(twoLetters.valid()).isTrue();
        assertThat(twoLetters.message()).isNull();
    }

    @Test
    @DisplayName("A value wider than the edited field is refused whatever it holds")
    void aValueWiderThanTheEditedFieldIsRefused() {
        // No COBOL ancestor. No source path supplies a value wider than WS-EDIT-ALPHANUM-LENGTH:
        // the MOVE at app/cbl/COACTUPC.cbl:L61 reads a screen field of that exact width.
        String[] wider = {
            "Johnsonn",
            "Jo hnsonn",
            "Johnson\n",
            "Johnson\u0000",
            "J".repeat(COUNTRY_LENGTH + 1),
            "U".repeat(4096),
        };

        for (int index = 0; index < wider.length; index++) {
            EditResult result =
                    AlphabeticRequiredValidator.validate(COUNTRY_LABEL, wider[index], COUNTRY_LENGTH);

            assertThat(result.valid()).as("value index %d", index).isFalse();
            assertThat(result.message()).as("value index %d", index)
                    .isEqualTo(COUNTRY_NO_LONGER_THAN_WIDTH);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -25})
    @DisplayName("A width of zero or less counts as not supplied")
    void widthOfZeroOrLessCountsAsNotSupplied(int length) {
        EditResult result = AlphabeticRequiredValidator.validate(FIRST_NAME_LABEL, "Johnson", length);

        // An empty window trims to nothing, which is the third arm at
        // app/cbl/COACTUPC.cbl:L1907-L1908.
        assertThat(result.valid()).isFalse();
        assertThat(result.hasMessage()).isTrue();
        assertThat(result.message()).isEqualTo(FIRST_NAME_NOT_SUPPLIED);
    }

    @Test
    @DisplayName("The validator declares a static label, value and width edit returning a verdict")
    void validatorExposesOneStaticEdit() throws NoSuchMethodException {
        Method edit = AlphabeticRequiredValidator.class.getMethod(
                "validate", String.class, String.class, int.class);

        assertThat(Modifier.isPublic(edit.getModifiers())).isTrue();
        assertThat(Modifier.isStatic(edit.getModifiers())).isTrue();
        assertThat(edit.getReturnType()).isEqualTo(EditResult.class);
        assertThat(edit.getParameterTypes())
                .containsExactly(String.class, String.class, int.class);
    }
}
