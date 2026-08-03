package com.carddemo.account.domain.validation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

/**
 * Tests for {@link SignedDecimalValidator}, which realises paragraph
 * {@code 1250-EDIT-SIGNED-9V2} at app/cbl/COACTUPC.cbl:L2180.
 *
 * <p>Two checks run in order. The not-supplied check at app/cbl/COACTUPC.cbl:L2184-L2185 compares
 * {@code WS-EDIT-SIGNED-NUMBER-9V2-X} against {@code LOW-VALUES} and against {@code SPACES}, and
 * its message sits at app/cbl/COACTUPC.cbl:L2191. The gate at app/cbl/COACTUPC.cbl:L2201 calls
 * {@code FUNCTION TEST-NUMVAL-C}, and its message sits at app/cbl/COACTUPC.cbl:L2209. That second
 * message closes without a period, which is the one message in the paragraph set that does.
 *
 * <p>The value field is {@code WS-EDIT-SIGNED-NUMBER-9V2-X PIC X(15)} at
 * app/cbl/COACTUPC.cbl:L55. Lines 2184, 2185 and 2201 read it whole and no line slices it, so the
 * edit takes no width argument.
 *
 * <p>Five call sites reach the paragraph, at app/cbl/COACTUPC.cbl:L1486, app/cbl/COACTUPC.cbl:L1499,
 * app/cbl/COACTUPC.cbl:L1511, app/cbl/COACTUPC.cbl:L1518 and app/cbl/COACTUPC.cbl:L1525, carrying
 * the credit limit, the cash credit limit, the current balance, the current cycle credit and the
 * current cycle debit. {@link #theSourceCallSiteCountIsMeasuredAndTheTargetWiringIsRecorded()}
 * counts them in the source and records how many target classes call this validator today.
 *
 * <p>The gate accepts a currency sign and grouping commas, so the accepted set is wider than a
 * plain digit string. It checks no scale and no precision: {@code 9V2} names the
 * {@code S9(nn)V99} format the account record stores and the paragraph enforces none of it.
 *
 */
class SignedDecimalValidatorTest {

    /** Message literal at app/cbl/COACTUPC.cbl:L2191, carrying its leading space and period. */
    private static final String NOT_SUPPLIED = " must be supplied.";

    /** Message literal at app/cbl/COACTUPC.cbl:L2209, carrying its leading space and no period. */
    private static final String NOT_VALID = " is not valid";

    /** Label of the credit-limit call site at app/cbl/COACTUPC.cbl:L1486. */
    private static final String CREDIT_LIMIT_LABEL = "Credit Limit";

    /** Label of the current-balance call site at app/cbl/COACTUPC.cbl:L1511. */
    private static final String CURRENT_BALANCE_LABEL = "Current Balance";

    /** Width of {@code WS-EDIT-SIGNED-NUMBER-9V2-X PIC X(15)} at app/cbl/COACTUPC.cbl:L55. */
    private static final int VALUE_HOST_WIDTH = 15;

    /** Width of {@code WS-EDIT-VARIABLE-NAME PIC X(25)} at app/cbl/COACTUPC.cbl:L53. */
    private static final int LABEL_HOST_WIDTH = 25;

    /**
     * One character of the figurative constant {@code LOW-VALUES}, which a COBOL comparison tests
     * for one position at a time.
     */
    private static final String LOW_VALUE = "\0";

    /** The count of source call sites that perform {@code 1250-EDIT-SIGNED-9V2}. */
    private static final int EXPECTED_SOURCE_CALL_SITE_COUNT = 5;

    /**
     * The count of target classes outside this test tree that call
     * {@link SignedDecimalValidator}. The account update paragraphs this validator guards are not
     * yet migrated, so the count is zero and the validator is reachable from its tests alone.
     */
    private static final int EXPECTED_TARGET_CALL_SITE_COUNT = 0;

    @ParameterizedTest(name = "value [{0}] reports the field not supplied")
    @NullSource
    @EmptySource
    @ValueSource(strings = {" ", "   ", "               "})
    @DisplayName("A value carrying no content reports the field not supplied")
    void aValueCarryingNoContentReportsNotSupplied(String value) {
        EditResult result = SignedDecimalValidator.validate(CREDIT_LIMIT_LABEL, value);

        assertThat(result.valid()).isFalse();
        assertThat(result.hasMessage()).isTrue();
        assertThat(result.message()).isEqualTo(CREDIT_LIMIT_LABEL + NOT_SUPPLIED);
    }

    @Test
    @DisplayName("A field of LOW-VALUES reports the field not supplied at every width up to the "
            + "fifteen-character host field, the first arm at app/cbl/COACTUPC.cbl:L2184")
    void lowValuesReportsNotSuppliedAtEveryWidth() {
        for (int width = 1; width <= VALUE_HOST_WIDTH; width++) {
            EditResult result =
                    SignedDecimalValidator.validate(CREDIT_LIMIT_LABEL, LOW_VALUE.repeat(width));

            assertThat(result.valid()).as("width %d", width).isFalse();
            assertThat(result.message())
                    .as("width %d", width)
                    .isEqualTo(CREDIT_LIMIT_LABEL + NOT_SUPPLIED);
        }
    }

    @Test
    @DisplayName("A field of spaces reports the field not supplied at every width up to the "
            + "fifteen-character host field")
    void spacesReportNotSuppliedAtEveryWidth() {
        for (int width = 1; width <= VALUE_HOST_WIDTH; width++) {
            assertThat(SignedDecimalValidator.validate(CREDIT_LIMIT_LABEL, " ".repeat(width))
                    .message())
                    .as("width %d", width)
                    .isEqualTo(CREDIT_LIMIT_LABEL + NOT_SUPPLIED);
        }
    }

    @Test
    @DisplayName("A field mixing LOW-VALUES with a space reaches the gate, because neither arm "
            + "holds for a mixed field")
    void aFieldMixingLowValuesWithASpaceReachesTheGate() {
        for (String mixed : new String[] {LOW_VALUE + " ", " " + LOW_VALUE,
                LOW_VALUE.repeat(2) + " ".repeat(13)}) {
            EditResult result = SignedDecimalValidator.validate(CREDIT_LIMIT_LABEL, mixed);

            assertThat(result.valid()).as("mixed field of width %d", mixed.length()).isFalse();
            assertThat(result.message())
                    .as("mixed field of width %d", mixed.length())
                    .isEqualTo(CREDIT_LIMIT_LABEL + NOT_VALID);
        }
    }

    @ParameterizedTest(name = "value [{0}] passes the gate")
    @ValueSource(strings = {"0", "1", "-1", "+1", "1234.56", "-1234.56", "$1,234.56", "1,234.56",
            "$1234.56", "  1234.56  ", "1234.56CR", "1234.56DB", ".56", "999999999.99",
            "-999999999.99", "0.00", "000000000000.00"})
    @DisplayName("A value the currency-tolerant gate accepts passes with no message")
    void aValueTheGateAcceptsPasses(String value) {
        EditResult result = SignedDecimalValidator.validate(CREDIT_LIMIT_LABEL, value);

        assertThat(result.valid()).as("value of width %d", value.length()).isTrue();
        assertThat(result.message()).isNull();
        assertThat(result.hasMessage()).isFalse();
    }

    @ParameterizedTest(name = "value [{0}] reports the gate message")
    @ValueSource(strings = {"A", "12A", "1.2.3", "--1", "1-2", "$", ",", ".", "1 2", "\t", "1e5",
            "1..2", "1,", ",1"})
    @DisplayName("A value the currency-tolerant gate refuses reports the message that closes "
            + "without a period")
    void aValueTheGateRefusesReportsTheGateMessage(String value) {
        EditResult result = SignedDecimalValidator.validate(CREDIT_LIMIT_LABEL, value);

        assertThat(result.valid()).as("value of width %d", value.length()).isFalse();
        assertThat(result.message()).isEqualTo(CREDIT_LIMIT_LABEL + NOT_VALID);
        assertThat(result.message()).doesNotEndWith(".");
    }

    @Test
    @DisplayName("The gate this edit calls answers the same verdict as the shared parser")
    void theGateAnswersTheSameVerdictAsTheSharedParser() {
        String[] values = {"1234.56", "$1,234.56", "-1234.56", "A", "1.2.3", "--1", "1 2", "0"};

        for (String value : values) {
            boolean accepted = SignedDecimalValidator.validate(CREDIT_LIMIT_LABEL, value).valid();

            assertThat(accepted)
                    .as("value of width %d", value.length())
                    .isEqualTo(NumvalParser.isValidNumvalCurrency(value));
        }
    }

    @Test
    @DisplayName("The gate enforces no group width, so a comma anywhere between digits passes")
    void theGateEnforcesNoGroupWidth() {
        // NumvalParser records that COBOL enforces no group width, so 1,00,000 carries the same
        // value as 100000.
        for (String value : new String[] {"12,34", "1,00,000", "1,2", "1,234,567.89"}) {
            assertThat(SignedDecimalValidator.validate(CREDIT_LIMIT_LABEL, value).valid())
                    .as("value of width %d", value.length())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("The edit checks no scale and no precision, so a value with more than two "
            + "fraction digits passes")
    void theEditChecksNoScaleAndNoPrecision() {
        for (String value : new String[] {"1234.5", "1234.567", "1234.56789",
                "12345678901234.56"}) {
            assertThat(SignedDecimalValidator.validate(CURRENT_BALANCE_LABEL, value).valid())
                    .as("value of width %d", value.length())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("A value wider than the fifteen-character host field is read whole, because no "
            + "line of the paragraph slices it")
    void aValueWiderThanTheHostFieldIsReadWhole() {
        String wider = "1".repeat(VALUE_HOST_WIDTH) + ".56";

        assertThat(wider.length()).isGreaterThan(VALUE_HOST_WIDTH);
        assertThat(SignedDecimalValidator.validate(CURRENT_BALANCE_LABEL, wider).valid()).isTrue();

        String widerAndInvalid = "1".repeat(VALUE_HOST_WIDTH) + "..5";

        assertThat(SignedDecimalValidator.validate(CURRENT_BALANCE_LABEL, widerAndInvalid).message())
                .isEqualTo(CURRENT_BALANCE_LABEL + NOT_VALID);
    }

    @Test
    @DisplayName("The label is trimmed and never truncated to its 25-character host width")
    void theLabelIsTrimmedAndNeverTruncated() {
        String padded = CREDIT_LIMIT_LABEL
                + " ".repeat(LABEL_HOST_WIDTH - CREDIT_LIMIT_LABEL.length());
        String longerThanHost = "A".repeat(LABEL_HOST_WIDTH + 5);

        assertThat(SignedDecimalValidator.validate(padded, null).message())
                .isEqualTo(CREDIT_LIMIT_LABEL + NOT_SUPPLIED);
        assertThat(SignedDecimalValidator.validate("  " + CREDIT_LIMIT_LABEL + "  ", null).message())
                .isEqualTo(CREDIT_LIMIT_LABEL + NOT_SUPPLIED);
        assertThat(SignedDecimalValidator.validate(null, null).message()).isEqualTo(NOT_SUPPLIED);
        assertThat(SignedDecimalValidator.validate(longerThanHost, null).message())
                .isEqualTo(longerThanHost + NOT_SUPPLIED);
    }

    @Test
    @DisplayName("Exactly two messages are reachable, and each matches its source literal")
    void exactlyTwoMessagesAreReachable() {
        Set<String> messages = new LinkedHashSet<>();
        Set<String> passingValues = new LinkedHashSet<>();

        collectOutcome(null, passingValues, messages);
        collectOutcome("", passingValues, messages);
        collectOutcome(LOW_VALUE.repeat(VALUE_HOST_WIDTH), passingValues, messages);
        for (char character = 0; character <= 0x7F; character++) {
            collectOutcome(String.valueOf(character), passingValues, messages);
            collectOutcome("1" + character, passingValues, messages);
        }

        assertThat(messages).containsExactlyInAnyOrder(
                CREDIT_LIMIT_LABEL + NOT_SUPPLIED,
                CREDIT_LIMIT_LABEL + NOT_VALID);
        assertThat(passingValues).contains("0", "1", "9", "10", "19");
    }

    @Test
    @DisplayName("The call reads its arguments and changes neither of them")
    void theCallReadsItsArgumentsAndChangesNeither() {
        String label = CREDIT_LIMIT_LABEL;
        String value = "$1,234.56";

        EditResult first = SignedDecimalValidator.validate(label, value);
        EditResult second = SignedDecimalValidator.validate(label, value);

        assertThat(label).isEqualTo(CREDIT_LIMIT_LABEL);
        assertThat(value).isEqualTo("$1,234.56");
        assertThat(first.valid()).isTrue();
        assertThat(first.valid()).isEqualTo(second.valid());
        assertThat(first.message()).isEqualTo(second.message());
    }

    @Test
    @DisplayName("Five source call sites perform paragraph 1250, and the target wiring count this "
            + "test records is the count that exists today")
    void theSourceCallSiteCountIsMeasuredAndTheTargetWiringIsRecorded() {
        assertThat(countSourcePerformsOf("1250-EDIT-SIGNED-9V2"))
                .as("PERFORM 1250-EDIT-SIGNED-9V2 in app/cbl/COACTUPC.cbl")
                .isEqualTo(EXPECTED_SOURCE_CALL_SITE_COUNT);

        assertThat(countTargetCallersOf("SignedDecimalValidator"))
                .as("classes outside the test tree calling SignedDecimalValidator")
                .isEqualTo(EXPECTED_TARGET_CALL_SITE_COUNT);
    }

    /**
     * Edits one value and files the outcome under the verdict it produced.
     *
     * @param value         the value to edit; may be {@code null}
     * @param passingValues the set collecting the values that pass
     * @param messages      the set collecting the distinct messages
     */
    private static void collectOutcome(String value, Set<String> passingValues,
            Set<String> messages) {
        EditResult result = SignedDecimalValidator.validate(CREDIT_LIMIT_LABEL, value);
        if (result.valid()) {
            passingValues.add(value);
        } else {
            messages.add(result.message());
        }
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
                    .filter(path -> !path.getFileName().toString().equals(validatorName + ".java"))
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
