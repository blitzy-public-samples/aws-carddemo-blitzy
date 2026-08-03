package com.carddemo.account.domain.validation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.carddemo.cobol.NumvalParser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link NumericRequiredValidator}, which realises paragraph
 * {@code 1245-EDIT-NUM-REQD} at app/cbl/COACTUPC.cbl:L2109.
 *
 * <p>Three checks run in a fixed order, and the verdict carries the message of the first one that
 * fails. The not-supplied check sits at app/cbl/COACTUPC.cbl:L2114 with its message at
 * app/cbl/COACTUPC.cbl:L2126, the {@code IS NUMERIC} class check at app/cbl/COACTUPC.cbl:L2137
 * with its message at app/cbl/COACTUPC.cbl:L2146, and the not-zero check at
 * app/cbl/COACTUPC.cbl:L2156 with its message at app/cbl/COACTUPC.cbl:L2163.
 *
 * <p>Six call sites perform the paragraph. Three sit in the customer edit sequence at
 * app/cbl/COACTUPC.cbl:L1549, app/cbl/COACTUPC.cbl:L1608 and app/cbl/COACTUPC.cbl:L1652 with
 * widths 3, 5 and 10. Three sit in {@code 1265-EDIT-US-SSN} at app/cbl/COACTUPC.cbl:L2442,
 * app/cbl/COACTUPC.cbl:L2472 and app/cbl/COACTUPC.cbl:L2484 with widths 3, 2 and 4.
 * {@link #theSourceCallSiteCountIsMeasuredAndTheTargetWiringIsRecorded()} counts those call sites
 * in the source and records how many target classes call this validator today, so the count is an
 * asserted fact rather than a claim in prose.
 *
 * <p>Every message literal below is quoted from the source. Widths come from
 * {@code WS-EDIT-ALPHANUM-ONLY PIC X(256)} at app/cbl/COACTUPC.cbl:L61 and
 * {@code WS-EDIT-ALPHANUM-LENGTH PIC S9(4) COMP-3} at app/cbl/COACTUPC.cbl:L62. A COBOL
 * {@code MOVE} into the edit field pads on the right with spaces, and a space is not a digit, so a
 * value shorter than its declared width clears the presence check and then fails the class check.
 *
 */
class NumericRequiredValidatorTest {

    /** Message literal at app/cbl/COACTUPC.cbl:L2126, carrying its leading space and period. */
    private static final String NOT_SUPPLIED = " must be supplied.";

    /** Message literal at app/cbl/COACTUPC.cbl:L2146, carrying its leading space and period. */
    private static final String NOT_ALL_NUMERIC = " must be all numeric.";

    /** Message literal at app/cbl/COACTUPC.cbl:L2163, carrying its leading space and period. */
    private static final String IS_ZERO = " must not be zero.";

    /**
     * ADDITIVE. Opens the message for a value wider than the declared width. No source literal
     * carries this text: the source moves a fixed-width screen field into its edit field, so a
     * wider value cannot reach paragraph 1245.
     */
    private static final String NO_LONGER_THAN = " must be no longer than ";

    /** ADDITIVE. Closes the text {@link #NO_LONGER_THAN} opens. */
    private static final String CHARACTERS = " characters.";

    /** Label of the credit-score call site at app/cbl/COACTUPC.cbl:L1549. */
    private static final String CREDIT_SCORE_LABEL = "Credit Score";

    /** Width the credit-score call site passes. */
    private static final int CREDIT_SCORE_WIDTH = 3;

    /** Width the postal-code call site at app/cbl/COACTUPC.cbl:L1608 passes. */
    private static final int ZIP_WIDTH = 5;

    /**
     * Width the electronic funds transfer account identifier call site at
     * app/cbl/COACTUPC.cbl:L1652 passes.
     */
    private static final int EFT_ACCOUNT_WIDTH = 10;

    /** The six widths the source call sites pass, in call-site order. */
    private static final List<Integer> SOURCE_CALL_SITE_WIDTHS =
            List.of(CREDIT_SCORE_WIDTH, ZIP_WIDTH, EFT_ACCOUNT_WIDTH, 3, 2, 4);

    /** Width of {@code WS-EDIT-VARIABLE-NAME PIC X(25)} at app/cbl/COACTUPC.cbl:L53. */
    private static final int LABEL_HOST_WIDTH = 25;

    /** Width of {@code WS-EDIT-ALPHANUM-ONLY PIC X(256)} at app/cbl/COACTUPC.cbl:L61. */
    private static final int EDIT_FIELD_WIDTH = 256;

    /**
     * One character of the figurative constant {@code LOW-VALUES}, which a COBOL comparison tests
     * for one position at a time.
     */
    private static final String LOW_VALUE = "\0";

    /** The count of source call sites that perform {@code 1245-EDIT-NUM-REQD}. */
    private static final int EXPECTED_SOURCE_CALL_SITE_COUNT = 6;

    /**
     * The count of target classes outside this test tree that call
     * {@link NumericRequiredValidator}. Paragraph {@code 1265-EDIT-US-SSN} is migrated and its
     * validator performs this edit on each of the three Social Security Number parts, exactly as
     * {@code app/cbl/COACTUPC.cbl:L2447-L2465} does. The remaining paragraphs this validator guards
     * are not yet migrated.
     */
    private static final int EXPECTED_TARGET_CALL_SITE_COUNT = 1;

    @ParameterizedTest(name = "value [{0}] reports the field not supplied")
    @NullSource
    @EmptySource
    @ValueSource(strings = {" ", "   ", "     "})
    @DisplayName("A value carrying no content reports the field not supplied")
    void aValueCarryingNoContentReportsNotSupplied(String value) {
        EditResult result =
                NumericRequiredValidator.validate(CREDIT_SCORE_LABEL, value, CREDIT_SCORE_WIDTH);

        assertThat(result.valid()).isFalse();
        assertThat(result.hasMessage()).isTrue();
        assertThat(result.message()).isEqualTo(CREDIT_SCORE_LABEL + NOT_SUPPLIED);
    }

    @Test
    @DisplayName("A field of LOW-VALUES at its declared width reports the field not supplied, the "
            + "first arm at app/cbl/COACTUPC.cbl:L2115")
    void lowValuesAtTheDeclaredWidthReportsNotSupplied() {
        for (int width : SOURCE_CALL_SITE_WIDTHS) {
            EditResult result = NumericRequiredValidator.validate(
                    CREDIT_SCORE_LABEL, LOW_VALUE.repeat(width), width);

            assertThat(result.valid()).as("width %d", width).isFalse();
            assertThat(result.message())
                    .as("width %d", width)
                    .isEqualTo(CREDIT_SCORE_LABEL + NOT_SUPPLIED);
        }
    }

    @Test
    @DisplayName("A field of LOW-VALUES narrower than its declared width reports the class check, "
            + "because FUNCTION TRIM removes the space alone")
    void lowValuesBelowTheDeclaredWidthReportsTheClassCheck() {
        for (int supplied : new int[] {1, 2, EFT_ACCOUNT_WIDTH - 1}) {
            EditResult result = NumericRequiredValidator.validate(
                    CREDIT_SCORE_LABEL, LOW_VALUE.repeat(supplied), EFT_ACCOUNT_WIDTH);

            assertThat(result.valid())
                    .as("%d supplied of %d", supplied, EFT_ACCOUNT_WIDTH)
                    .isFalse();
            assertThat(result.message())
                    .as("%d supplied of %d", supplied, EFT_ACCOUNT_WIDTH)
                    .isEqualTo(CREDIT_SCORE_LABEL + NOT_ALL_NUMERIC);
        }
    }

    @Test
    @DisplayName("A field of LOW-VALUES is read only to its declared width, and content past that "
            + "width is refused")
    void lowValuesIsReadOnlyToTheDeclaredWidthAndContentPastItIsRefused() {
        String padded = LOW_VALUE.repeat(CREDIT_SCORE_WIDTH) + "   ";

        assertThat(NumericRequiredValidator
                .validate(CREDIT_SCORE_LABEL, padded, CREDIT_SCORE_WIDTH).message())
                .isEqualTo(CREDIT_SCORE_LABEL + NOT_SUPPLIED);

        String beyond = LOW_VALUE.repeat(CREDIT_SCORE_WIDTH) + "750";

        assertThat(NumericRequiredValidator
                .validate(CREDIT_SCORE_LABEL, beyond, CREDIT_SCORE_WIDTH).message())
                .isEqualTo(CREDIT_SCORE_LABEL + NO_LONGER_THAN + CREDIT_SCORE_WIDTH + CHARACTERS);
        assertThat(NumericRequiredValidator
                .validate(CREDIT_SCORE_LABEL, beyond, beyond.length()).message())
                .isEqualTo(CREDIT_SCORE_LABEL + NOT_ALL_NUMERIC);
    }

    @ParameterizedTest(name = "value [{0}] reports the class check")
    @ValueSource(strings = {"7A0", "A75", "75-", "7.5", "7 5", " 75", "75 ", "+75", "$75", "7,5"})
    @DisplayName("A value carrying a character that is not a digit reports the class check")
    void aValueCarryingANonDigitReportsTheClassCheck(String value) {
        EditResult result =
                NumericRequiredValidator.validate(CREDIT_SCORE_LABEL, value, CREDIT_SCORE_WIDTH);

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).isEqualTo(CREDIT_SCORE_LABEL + NOT_ALL_NUMERIC);
    }

    @Test
    @DisplayName("A value shorter than its declared width reports the class check, because the "
            + "MOVE pads with spaces and a space is not a digit")
    void aValueShorterThanItsDeclaredWidthReportsTheClassCheck() {
        assertThat(NumericRequiredValidator.validate(CREDIT_SCORE_LABEL, "7", CREDIT_SCORE_WIDTH)
                .message()).isEqualTo(CREDIT_SCORE_LABEL + NOT_ALL_NUMERIC);
        assertThat(NumericRequiredValidator.validate(CREDIT_SCORE_LABEL, "75", CREDIT_SCORE_WIDTH)
                .message()).isEqualTo(CREDIT_SCORE_LABEL + NOT_ALL_NUMERIC);
        assertThat(NumericRequiredValidator.validate(CREDIT_SCORE_LABEL, "12345", ZIP_WIDTH)
                .valid()).isTrue();
    }

    @ParameterizedTest(name = "value [{0}] reports the not-zero check")
    @ValueSource(strings = {"000", "0000000000", "00", "0"})
    @DisplayName("A value of digits that converts to zero reports the not-zero check")
    void aValueOfDigitsConvertingToZeroReportsTheNotZeroCheck(String value) {
        EditResult result =
                NumericRequiredValidator.validate(CREDIT_SCORE_LABEL, value, value.length());

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).isEqualTo(CREDIT_SCORE_LABEL + IS_ZERO);
    }

    @ParameterizedTest(name = "value [{0}] passes")
    @ValueSource(strings = {"001", "750", "999", "100", "010"})
    @DisplayName("A value of digits that converts to a value other than zero passes")
    void aValueOfDigitsConvertingToNonZeroPasses(String value) {
        EditResult result =
                NumericRequiredValidator.validate(CREDIT_SCORE_LABEL, value, value.length());

        assertThat(result.valid()).isTrue();
        assertThat(result.message()).isNull();
        assertThat(result.hasMessage()).isFalse();
    }

    @Test
    @DisplayName("Every source call-site width accepts a value of digits and rejects a value of "
            + "zeros at that width")
    void everySourceCallSiteWidthAcceptsDigitsAndRejectsZeros() {
        for (int width : SOURCE_CALL_SITE_WIDTHS) {
            String zeros = "0".repeat(width);
            String nonZero = "0".repeat(width - 1) + "1";

            assertThat(NumericRequiredValidator
                    .validate(CREDIT_SCORE_LABEL, zeros, width).message())
                    .as("width %d", width)
                    .isEqualTo(CREDIT_SCORE_LABEL + IS_ZERO);
            assertThat(NumericRequiredValidator
                    .validate(CREDIT_SCORE_LABEL, nonZero, width).valid())
                    .as("width %d", width)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("A width of zero or below reports the field not supplied")
    void aWidthOfZeroOrBelowReportsNotSupplied() {
        for (int width : new int[] {0, -1, -25}) {
            assertThat(NumericRequiredValidator.validate(CREDIT_SCORE_LABEL, "750", width).message())
                    .as("width %d", width)
                    .isEqualTo(CREDIT_SCORE_LABEL + NOT_SUPPLIED);
        }
    }

    @Test
    @DisplayName("The not-zero check reaches the eighteen-digit ARITH(COMPAT) ceiling and the "
            + "shared gate refuses a wider field, which no source call site reaches")
    void theNotZeroCheckReachesTheArithCompatCeiling() {
        int ceiling = NumvalParser.MAXIMUM_DIGITS;

        assertThat(NumericRequiredValidator
                .validate(CREDIT_SCORE_LABEL, "1".repeat(ceiling), ceiling).valid()).isTrue();
        assertThat(NumericRequiredValidator
                .validate(CREDIT_SCORE_LABEL, "0".repeat(ceiling), ceiling).message())
                .isEqualTo(CREDIT_SCORE_LABEL + IS_ZERO);

        // app/cbl/COACTUPC.cbl names no ARITH option, so ARITH(COMPAT) applies and the source's own
        // FUNCTION NUMVAL is bounded the same way. Every call-site width sits far below it.
        assertThatThrownBy(() -> NumericRequiredValidator.validate(
                CREDIT_SCORE_LABEL, "1".repeat(ceiling + 1), ceiling + 1))
                .isInstanceOf(NumberFormatException.class);

        assertThat(SOURCE_CALL_SITE_WIDTHS).allSatisfy(width -> assertThat(width).isLessThan(ceiling));
        assertThat(EDIT_FIELD_WIDTH).isGreaterThan(ceiling);
    }

    @Test
    @DisplayName("A value wider than its declared width is refused rather than read to that width")
    void aValueWiderThanItsDeclaredWidthIsRefusedRatherThanReadToThatWidth() {
        String wider = "750" + "ABC";

        assertThat(NumericRequiredValidator
                .validate(CREDIT_SCORE_LABEL, wider, CREDIT_SCORE_WIDTH).message())
                .isEqualTo(CREDIT_SCORE_LABEL + NO_LONGER_THAN + CREDIT_SCORE_WIDTH + CHARACTERS);
        assertThat(NumericRequiredValidator
                .validate(CREDIT_SCORE_LABEL, "750" + "   ", CREDIT_SCORE_WIDTH).valid()).isTrue();
        assertThat(NumericRequiredValidator
                .validate(CREDIT_SCORE_LABEL, wider, wider.length()).message())
                .isEqualTo(CREDIT_SCORE_LABEL + NOT_ALL_NUMERIC);
    }

    @Test
    @DisplayName("The label is trimmed and never truncated to its 25-character host width")
    void theLabelIsTrimmedAndNeverTruncated() {
        String padded = CREDIT_SCORE_LABEL
                + " ".repeat(LABEL_HOST_WIDTH - CREDIT_SCORE_LABEL.length());
        String longerThanHost = "A".repeat(LABEL_HOST_WIDTH + 5);

        assertThat(NumericRequiredValidator.validate(padded, null, CREDIT_SCORE_WIDTH).message())
                .isEqualTo(CREDIT_SCORE_LABEL + NOT_SUPPLIED);
        assertThat(NumericRequiredValidator.validate("  " + CREDIT_SCORE_LABEL + "  ", null,
                CREDIT_SCORE_WIDTH).message()).isEqualTo(CREDIT_SCORE_LABEL + NOT_SUPPLIED);
        assertThat(NumericRequiredValidator.validate(null, null, CREDIT_SCORE_WIDTH).message())
                .isEqualTo(NOT_SUPPLIED);
        assertThat(NumericRequiredValidator.validate(longerThanHost, null, CREDIT_SCORE_WIDTH)
                .message()).isEqualTo(longerThanHost + NOT_SUPPLIED);
    }

    @Test
    @DisplayName("Exactly three messages are reachable, and each matches its source literal")
    void exactlyThreeMessagesAreReachable() {
        Set<String> messages = new LinkedHashSet<>();
        Set<String> passingValues = new LinkedHashSet<>();

        List<String> sweep = new ArrayList<>();
        sweep.add(null);
        sweep.add("");
        sweep.add(" ".repeat(CREDIT_SCORE_WIDTH));
        sweep.add(LOW_VALUE.repeat(CREDIT_SCORE_WIDTH));
        for (char character = 0x20; character <= 0x7E; character++) {
            sweep.add(String.valueOf(character).repeat(CREDIT_SCORE_WIDTH));
        }

        for (String value : sweep) {
            EditResult result = NumericRequiredValidator.validate(
                    CREDIT_SCORE_LABEL, value, CREDIT_SCORE_WIDTH);
            if (result.valid()) {
                passingValues.add(value);
            } else {
                messages.add(result.message());
            }
        }

        assertThat(messages).containsExactlyInAnyOrder(
                CREDIT_SCORE_LABEL + NOT_SUPPLIED,
                CREDIT_SCORE_LABEL + NOT_ALL_NUMERIC,
                CREDIT_SCORE_LABEL + IS_ZERO);
        assertThat(passingValues).containsExactly("111", "222", "333", "444", "555", "666", "777",
                "888", "999");
    }

    @Test
    @DisplayName("The call reads its arguments and changes neither of them")
    void theCallReadsItsArgumentsAndChangesNeither() {
        String label = CREDIT_SCORE_LABEL;
        String value = "750";

        EditResult first = NumericRequiredValidator.validate(label, value, CREDIT_SCORE_WIDTH);
        EditResult second = NumericRequiredValidator.validate(label, value, CREDIT_SCORE_WIDTH);

        assertThat(label).isEqualTo(CREDIT_SCORE_LABEL);
        assertThat(value).isEqualTo("750");
        assertThat(first.valid()).isEqualTo(second.valid());
        assertThat(first.message()).isEqualTo(second.message());
    }

    @Test
    @DisplayName("Six source call sites perform paragraph 1245, and the target wiring count this "
            + "test records is the count that exists today")
    void theSourceCallSiteCountIsMeasuredAndTheTargetWiringIsRecorded() {
        assertThat(countSourcePerformsOf("1245-EDIT-NUM-REQD"))
                .as("PERFORM 1245-EDIT-NUM-REQD in app/cbl/COACTUPC.cbl")
                .isEqualTo(EXPECTED_SOURCE_CALL_SITE_COUNT);
        assertThat(SOURCE_CALL_SITE_WIDTHS).hasSize(EXPECTED_SOURCE_CALL_SITE_COUNT);

        assertThat(countTargetCallersOf("NumericRequiredValidator"))
                .as("classes outside the test tree calling NumericRequiredValidator")
                .isEqualTo(EXPECTED_TARGET_CALL_SITE_COUNT);
    }

    /**
     * Counts the {@code PERFORM} statements that name one paragraph in
     * {@code app/cbl/COACTUPC.cbl}, skipping comment lines.
     *
     * @param paragraphName the paragraph name, without a trailing period
     * @return the count of call sites
     */
    private static int countSourcePerformsOf(String paragraphName) {
        int count = 0;
        for (String line : readLines(repositoryRoot().resolve("app").resolve("cbl")
                .resolve("COACTUPC.cbl"))) {
            if (line.strip().startsWith("*")) {
                continue;
            }
            if (line.contains("PERFORM " + paragraphName)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Counts the Java files under {@code card-platform/services/account-service/src/main} that
     * name one validator, excluding the validator's own declaration.
     *
     * @param validatorName the simple class name of the validator
     * @return the count of calling classes
     */
    private static int countTargetCallersOf(String validatorName) {
        Path mainTree = repositoryRoot().resolve("card-platform").resolve("services")
                .resolve("account-service").resolve("src").resolve("main");
        try (Stream<Path> files = Files.walk(mainTree)) {
            return (int) files
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString()
                            .equals(validatorName + ".java"))
                    .filter(path -> String.join("\n", readLines(path)).contains(validatorName))
                    .count();
        } catch (IOException failure) {
            throw new UncheckedIOException("Walking %s failed.".formatted(mainTree), failure);
        }
    }

    /**
     * Reads every line of one file.
     *
     * @param file the file to read
     * @return the lines, in file order
     */
    private static List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.ISO_8859_1);
        } catch (IOException failure) {
            throw new UncheckedIOException("Reading %s failed.".formatted(file), failure);
        }
    }

    /**
     * Walks upward from the working directory until a candidate holds
     * {@code app/cbl/COACTUPC.cbl}.
     *
     * @return the repository root
     */
    private static Path repositoryRoot() {
        Path start = Path.of("").toAbsolutePath().normalize();
        for (Path candidate = start; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("app").resolve("cbl")
                    .resolve("COACTUPC.cbl"))) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                ("Walked upward from %s to the filesystem root without finding "
                        + "'app/cbl/COACTUPC.cbl'. Run the tests from inside the repository.")
                        .formatted(start));
    }
}
