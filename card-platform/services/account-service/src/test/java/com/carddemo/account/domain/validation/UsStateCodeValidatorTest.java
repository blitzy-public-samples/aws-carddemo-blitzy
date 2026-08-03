package com.carddemo.account.domain.validation;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import com.carddemo.cobol.reference.UsStateCodes;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link UsStateCodeValidator}, the state-code edit.
 *
 * <p>The subject realises paragraph {@code 1270-EDIT-US-STATE-CD} at app/cbl/COACTUPC.cbl:L2493,
 * whose exit paragraph sits at app/cbl/COACTUPC.cbl:L2511. The accepted band is
 * app/cpy/CSLKPCDY.cpy:L1012-L1069. The host field {@code 01 US-STATE-CODE-TO-EDIT PIC X(2).} sits
 * at L1012 and the condition name {@code VALID-US-STATE-CODE} at L1013. Lines L1014 through L1069
 * hold 56 literals.</p>
 *
 * <p>A census across the 28 members of app/cbl finds one reference to that condition name, at
 * app/cbl/COACTUPC.cbl:L2495. The edit tests band membership and nothing more.</p>
 *
 * <p>app/cbl/COACTUPC.cbl:L2494 fills the host field with a plain {@code MOVE} and applies no
 * {@code FUNCTION TRIM} to the submitted value. The phone edit at app/cbl/COACTUPC.cbl:L2296-L2297
 * does apply one. The methods below assert the consequence: a value carrying a leading space
 * reaches the comparison with that space in place, and it matches no literal.</p>
 *
 * <p>The paragraph only ever condemns. app/cbl/COACTUPC.cbl:L2496 answers a match with a bare
 * {@code CONTINUE} and sets no flag. app/cbl/COACTUPC.cbl:L2499 sets a failure flag on the one path
 * that leaves the band, and app/cbl/COACTUPC.cbl:L2502-L2503 builds the text: the label under
 * {@code FUNCTION TRIM}, then {@code ': is not a valid state code'}. That literal carries no
 * trailing period.</p>
 *
 * <p>Every value below is built in the test method that uses it. The gate at
 * app/cbl/COACTUPC.cbl:L1599 guarding the call at app/cbl/COACTUPC.cbl:L1600 is orchestration and
 * sits outside this class, as does the seeded reference table.</p>
 *
 * <p>The comment at app/cpy/CSLKPCDY.cpy:L1011 reads {@code *Search list of valid Phone area codes}
 * and sits directly above the state host field at L1012. The phone band that comment names ends at
 * app/cpy/CSLKPCDY.cpy:L1010.</p>
 */
@DisplayName("UsStateCodeValidator, the state-code edit at COACTUPC paragraph 1270")
class UsStateCodeValidatorTest {

    /**
     * The failure literal at app/cbl/COACTUPC.cbl:L2503, opening with a colon and a
     * space, closing with no period.
     */
    private static final String SOURCE_FAILURE_LITERAL = ": is not a valid state code";

    /** Measured width of the literal at app/cbl/COACTUPC.cbl:L2503. */
    private static final int SOURCE_FAILURE_LITERAL_LENGTH = 27;

    /** Label app/cbl/COACTUPC.cbl:L1592 moves into {@code WS-EDIT-VARIABLE-NAME}. */
    private static final String STATE_LABEL = "State";

    /**
     * Declared width of {@code WS-EDIT-VARIABLE-NAME}, {@code PIC X(25)} at
     * app/cbl/COACTUPC.cbl:L53. A caller assignment fills the unused positions with spaces.
     */
    private static final int LABEL_FIELD_WIDTH = 25;

    /** Literal count app/cpy/CSLKPCDY.cpy declares at L1014 through L1069. */
    private static final int DECLARED_CODE_COUNT = 56;

    /** Declared width of the host field at app/cpy/CSLKPCDY.cpy:L1012. */
    private static final int HOST_FIELD_WIDTH = 2;

    /** First literal in copybook declaration order, at app/cpy/CSLKPCDY.cpy:L1014. */
    private static final String DECLARATION_ORDER_FIRST = "AL";

    /** Last literal in copybook declaration order, at app/cpy/CSLKPCDY.cpy:L1069. */
    private static final String DECLARATION_ORDER_LAST = "VI";

    /** Lowest literal of the band read in sorted order. */
    private static final String SORTED_ORDER_LOWEST = "AK";

    /** Highest literal of the band read in sorted order. */
    private static final String SORTED_ORDER_HIGHEST = "WY";

    /** A two-letter pair absent from the 56 literals at app/cpy/CSLKPCDY.cpy:L1014-L1069. */
    private static final String ABSENT_PAIR = "AP";

    /** Lowest letter the exhaustive pair sweep starts from. */
    private static final char FIRST_LETTER = 'A';

    /** Highest letter the exhaustive pair sweep ends on. */
    private static final char LAST_LETTER = 'Z';

    @ParameterizedTest(name = "state code {0} passes the edit")
    @ValueSource(strings = {
            // Opening run, app/cpy/CSLKPCDY.cpy:L1014, L1015 and L1018.
            "AL", "AK", "CA",
            // Middle of the band, app/cpy/CSLKPCDY.cpy:L1029, L1038 and L1041.
            "KS", "MO", "NV",
            // Closing run, app/cpy/CSLKPCDY.cpy:L1063 through L1069.
            "WY", "DC", "AS", "GU", "MP", "PR", "VI"})
    @DisplayName("A code the copybook declares passes, drawn from the opening, middle and closing runs")
    void declaredCodePasses(String stateCode) {
        EditResult result = UsStateCodeValidator.validate(STATE_LABEL, stateCode);

        assertThat(result.valid()).isTrue();
        assertThat(result.message()).isNull();
        assertThat(result.hasMessage()).isFalse();
    }

    @Test
    @DisplayName("All 56 declared codes pass, and each is two characters wide")
    void allDeclaredCodesPass() {
        Set<String> band = CslkpcdyCopybookOracle.stateCodes();

        assertThat(band).hasSize(DECLARED_CODE_COUNT);
        for (String stateCode : band) {
            assertThat(stateCode).hasSize(HOST_FIELD_WIDTH);
            assertThat(UsStateCodeValidator.validate(STATE_LABEL, stateCode).valid())
                    .as("declared code %s", stateCode)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("The production band equals the band app/cpy/CSLKPCDY.cpy:L1013 lists, value for "
            + "value and in declaration order")
    void theProductionBandEqualsTheBandTheCopybookLists() {
        assertThat(UsStateCodes.stateAndTerritoryCodes())
                .as("VALID-US-STATE-CODE at app/cpy/CSLKPCDY.cpy:L1013")
                .containsExactlyElementsOf(CslkpcdyCopybookOracle.stateCodes());
    }

    @ParameterizedTest(name = "state code {0} fails the edit")
    @ValueSource(strings = {"AP", "FM", "MH", "PW"})
    @DisplayName("A two-character pair the copybook omits fails and carries the source failure text")
    void omittedPairFails(String stateCode) {
        assertThat(UsStateCodes.stateAndTerritoryCodes())
                .as("the pair under test is absent from the 56 declared literals")
                .doesNotContain(stateCode);

        EditResult result = UsStateCodeValidator.validate(STATE_LABEL, stateCode);

        assertThat(result.valid()).isFalse();
        assertThat(result.hasMessage()).isTrue();
        assertThat(result.message()).isEqualTo(STATE_LABEL + SOURCE_FAILURE_LITERAL);
    }

    @Test
    @DisplayName("The failure text matches source line 2503 character for character and carries no trailing period")
    void failureTextMatchesSourceLineCharacterForCharacter() {
        String message = UsStateCodeValidator.validate(STATE_LABEL, ABSENT_PAIR).message();

        assertThat(message).isEqualTo("State: is not a valid state code");

        String literal = message.substring(STATE_LABEL.length());

        assertThat(literal)
                .isEqualTo(": is not a valid state code")
                .hasSize(SOURCE_FAILURE_LITERAL_LENGTH)
                .startsWith(": ")
                .endsWith("code")
                .doesNotEndWith(".")
                .doesNotContain(".");
        assertThat(message).hasSize(STATE_LABEL.length() + SOURCE_FAILURE_LITERAL_LENGTH);
    }

    @Test
    @DisplayName("A leading space stays in the value, so the padded form fails where the bare form passes")
    void leadingSpaceStaysInTheValue() {
        assertThat(UsStateCodeValidator.validate(STATE_LABEL, "AL").valid()).isTrue();
        assertThat(UsStateCodeValidator.validate(STATE_LABEL, "NY").valid()).isTrue();
        assertThat(UsStateCodeValidator.validate(STATE_LABEL, "GU").valid()).isTrue();

        EditResult leadingSpace = UsStateCodeValidator.validate(STATE_LABEL, " AL");

        assertThat(leadingSpace.valid()).isFalse();
        assertThat(leadingSpace.message()).isEqualTo(STATE_LABEL + SOURCE_FAILURE_LITERAL);

        assertThat(UsStateCodeValidator.validate(STATE_LABEL, " NY").valid()).isFalse();
        assertThat(UsStateCodeValidator.validate(STATE_LABEL, " GU").valid()).isFalse();
        assertThat(UsStateCodeValidator.validate(STATE_LABEL, "  AL").valid()).isFalse();
    }

    @Test
    @DisplayName("A trimming move would change the outcome, and the edit at source line 2494 applies none")
    void trimmingTheValueWouldChangeTheOutcome() {
        // app/cbl/COACTUPC.cbl:L2296-L2297 moves a trimmed value into the phone host
        // field. app/cbl/COACTUPC.cbl:L2494 moves an untrimmed value into the state host
        // field. The four values below separate the two forms of the move.
        for (String paddedValue : new String[] {" AL", " NY", "  GU", " VI"}) {
            assertThat(UsStateCodes.stateAndTerritoryCodes())
                    .as("a trimming move would reach a declared literal for %s", paddedValue)
                    .contains(paddedValue.trim());

            assertThat(UsStateCodeValidator.validate(STATE_LABEL, paddedValue).valid())
                    .as("the untrimmed move keeps the leading space of %s", paddedValue)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("A short value, a blank value, an empty value and a null value all fail")
    void shortAndBlankValuesFail() {
        assertThat(UsStateCodeValidator.validate(STATE_LABEL, " A").valid()).isFalse();
        assertThat(UsStateCodeValidator.validate(STATE_LABEL, "A ").valid()).isFalse();
        assertThat(UsStateCodeValidator.validate(STATE_LABEL, "N").valid()).isFalse();
        assertThat(UsStateCodeValidator.validate(STATE_LABEL, " ").valid()).isFalse();
        assertThat(UsStateCodeValidator.validate(STATE_LABEL, "  ").valid()).isFalse();
        assertThat(UsStateCodeValidator.validate(STATE_LABEL, "").valid()).isFalse();
        assertThat(UsStateCodeValidator.validate(STATE_LABEL, null).valid()).isFalse();

        assertThat(UsStateCodeValidator.validate(STATE_LABEL, null).message())
                .isEqualTo(STATE_LABEL + SOURCE_FAILURE_LITERAL);
    }

    @Test
    @DisplayName("The two-character host field holds the first two characters, and a third character fails the edit")
    void hostFieldHoldsTheFirstTwoCharacters() {
        // app/cbl/COACTUPC.cbl:L2494 moves into the PIC X(2) field at
        // app/cpy/CSLKPCDY.cpy:L1012, and a fixed-width MOVE keeps two characters. The value it
        // moves comes from a PIC X(02) screen field at app/cbl/COACTUPC.cbl:L807, so the MOVE drops
        // nothing but padding.
        assertThat(UsStateCodeValidator.validate(STATE_LABEL, "AL ").valid()).isTrue();
        assertThat(UsStateCodeValidator.validate(STATE_LABEL, "AL   ").valid()).isTrue();

        // ADDITIVE. A caller of this edit can supply a wider value. The membership list holds
        // two-character codes alone, so a wider value fails it and takes the message the source
        // writes at app/cbl/COACTUPC.cbl:L2501-L2506. The edit passes no verdict on the first two
        // characters of a longer value.
        assertThat(UsStateCodeValidator.validate(STATE_LABEL, "ALX").valid()).isFalse();
        assertThat(UsStateCodeValidator.validate(STATE_LABEL, "ALX").message())
                .isEqualTo(STATE_LABEL + SOURCE_FAILURE_LITERAL);
        assertThat(UsStateCodeValidator.validate(STATE_LABEL, "ALABAMA").valid()).isFalse();
        assertThat(UsStateCodeValidator.validate(STATE_LABEL, "AL<script>").valid()).isFalse();
        assertThat(UsStateCodeValidator.validate(STATE_LABEL, "AL<script>").message())
                .isEqualTo(STATE_LABEL + SOURCE_FAILURE_LITERAL);

        // The first two characters decide the outcome on the failing side as well.
        assertThat(UsStateCodeValidator.validate(STATE_LABEL, "APX").valid()).isFalse();
        assertThat(UsStateCodeValidator.validate(STATE_LABEL, "AP ").valid()).isFalse();
    }

    @ParameterizedTest(name = "lower or mixed case value {0} fails the edit")
    @ValueSource(strings = {"al", "aL", "Al", "ny", "nY", "Ny", "gu", "vi", "wy"})
    @DisplayName("Letter case is significant, and a lower or mixed case value fails")
    void letterCaseIsSignificant(String stateCode) {
        EditResult result = UsStateCodeValidator.validate(STATE_LABEL, stateCode);

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).isEqualTo(STATE_LABEL + SOURCE_FAILURE_LITERAL);

        // Locale.ROOT keeps the case fold identical on every machine that runs the suite.
        assertThat(UsStateCodeValidator.validate(STATE_LABEL, stateCode.toUpperCase(Locale.ROOT)).valid())
                .as("the same code in upper case")
                .isTrue();
    }

    @Test
    @DisplayName("A passing verdict carries no message, matching the bare CONTINUE at source line 2496")
    void passingVerdictCarriesNoMessage() {
        for (String stateCode : UsStateCodes.stateAndTerritoryCodes()) {
            EditResult result = UsStateCodeValidator.validate(STATE_LABEL, stateCode);

            assertThat(result.valid()).as("verdict for %s", stateCode).isTrue();
            assertThat(result.message()).as("message slot for %s", stateCode).isNull();
            assertThat(result.hasMessage()).as("slot guard for %s", stateCode).isFalse();
        }
    }

    @Test
    @DisplayName("The label loses its padding spaces before it prefixes the failure text")
    void labelLosesItsPaddingSpaces() {
        String paddedLabel = STATE_LABEL
                + " ".repeat(LABEL_FIELD_WIDTH - STATE_LABEL.length());

        assertThat(paddedLabel).hasSize(LABEL_FIELD_WIDTH);

        String message = UsStateCodeValidator.validate(paddedLabel, ABSENT_PAIR).message();

        assertThat(message)
                .isEqualTo("State: is not a valid state code")
                .doesNotContain("  ")
                .hasSize(STATE_LABEL.length() + SOURCE_FAILURE_LITERAL_LENGTH);

        assertThat(UsStateCodeValidator.validate("   State   ", ABSENT_PAIR).message())
                .isEqualTo(STATE_LABEL + SOURCE_FAILURE_LITERAL);
    }

    @Test
    @DisplayName("Label trimming and value trimming are separate, and interior label spaces survive")
    void labelTrimmingAndValueTrimmingAreSeparate() {
        // app/cbl/COACTUPC.cbl:L2502 trims the label. app/cbl/COACTUPC.cbl:L2494 leaves
        // the value untouched.
        assertThat(UsStateCodeValidator.validate("  " + STATE_LABEL + "  ", " AL").message())
                .isEqualTo(STATE_LABEL + SOURCE_FAILURE_LITERAL);

        assertThat(UsStateCodeValidator.validate("Two Words", ABSENT_PAIR).message())
                .isEqualTo("Two Words" + SOURCE_FAILURE_LITERAL);

        assertThat(UsStateCodeValidator.validate("", ABSENT_PAIR).message())
                .isEqualTo(SOURCE_FAILURE_LITERAL);
        assertThat(UsStateCodeValidator.validate("     ", ABSENT_PAIR).message())
                .isEqualTo(SOURCE_FAILURE_LITERAL);
        assertThat(UsStateCodeValidator.validate(null, ABSENT_PAIR).message())
                .isEqualTo(SOURCE_FAILURE_LITERAL);
    }

    @Test
    @DisplayName("One failure text is reachable, and the codes that pass are exactly the declared band")
    void oneFailureTextIsReachableAcrossEveryTwoLetterPair() {
        Set<String> distinctFailureTexts = new LinkedHashSet<>();
        Set<String> passingPairs = new LinkedHashSet<>();

        for (char first = FIRST_LETTER; first <= LAST_LETTER; first++) {
            for (char second = FIRST_LETTER; second <= LAST_LETTER; second++) {
                String pair = String.valueOf(new char[] {first, second});
                EditResult result = UsStateCodeValidator.validate(STATE_LABEL, pair);

                if (result.valid()) {
                    passingPairs.add(pair);
                } else {
                    distinctFailureTexts.add(result.message());
                }
            }
        }

        assertThat(distinctFailureTexts)
                .containsExactly(STATE_LABEL + SOURCE_FAILURE_LITERAL);
        assertThat(passingPairs)
                .hasSize(DECLARED_CODE_COUNT)
                .containsExactlyInAnyOrderElementsOf(CslkpcdyCopybookOracle.stateCodes());
    }

    @Test
    @DisplayName("The band holds 56 codes, opening at AL and closing at VI in copybook declaration order")
    void bandHoldsFiftySixCodesInDeclarationOrder() {
        Set<String> band = CslkpcdyCopybookOracle.stateCodes();

        assertThat(band).hasSize(DECLARED_CODE_COUNT);

        // Declaration order, the order app/cpy/CSLKPCDY.cpy:L1014-L1069 lists the literals.
        assertThat(band).first().isEqualTo(DECLARATION_ORDER_FIRST);
        assertThat(band).last().isEqualTo(DECLARATION_ORDER_LAST);

        // Sorted order, a second reading of the same 56 codes.
        Set<String> sortedBand = new TreeSet<>(band);

        assertThat(sortedBand).hasSize(DECLARED_CODE_COUNT);
        assertThat(sortedBand).first().isEqualTo(SORTED_ORDER_LOWEST);
        assertThat(sortedBand).last().isEqualTo(SORTED_ORDER_HIGHEST);

        assertThat(UsStateCodeValidator.validate(STATE_LABEL, DECLARATION_ORDER_FIRST).valid()).isTrue();
        assertThat(UsStateCodeValidator.validate(STATE_LABEL, DECLARATION_ORDER_LAST).valid()).isTrue();
        assertThat(UsStateCodeValidator.validate(STATE_LABEL, SORTED_ORDER_LOWEST).valid()).isTrue();
        assertThat(UsStateCodeValidator.validate(STATE_LABEL, SORTED_ORDER_HIGHEST).valid()).isTrue();
    }
}
