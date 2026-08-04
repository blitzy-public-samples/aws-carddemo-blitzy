package com.carddemo.account.domain.validation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.carddemo.cobol.NumvalParser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Tests for {@link SignedDecimalValidator}, which realises paragraph
 * {@code 1250-EDIT-SIGNED-9V2} at app/cbl/COACTUPC.cbl:L2180 through its exit label at
 * app/cbl/COACTUPC.cbl:L2221.
 *
 * <p>Two checks run in order. The not-supplied check at app/cbl/COACTUPC.cbl:L2184-L2185 compares
 * {@code WS-EDIT-SIGNED-NUMBER-9V2-X} against {@code LOW-VALUES} and against {@code SPACES}, and
 * carries its message from app/cbl/COACTUPC.cbl:L2191. The gate at app/cbl/COACTUPC.cbl:L2201
 * calls {@code FUNCTION TEST-NUMVAL-C}, which answers 0 for a valid field, and its message sits at
 * app/cbl/COACTUPC.cbl:L2209. That second message closes with no period, the one message in the
 * paragraph set that closes that way.
 *
 * <p>The edit takes a label and a value, and no width. The value field is
 * {@code WS-EDIT-SIGNED-NUMBER-9V2-X PIC X(15)} at app/cbl/COACTUPC.cbl:L55, and lines 2184, 2185
 * and 2201 read it whole with no reference modification. The label field is
 * {@code WS-EDIT-VARIABLE-NAME PIC X(25)} at app/cbl/COACTUPC.cbl:L53, so the caller's assignment
 * sets the label width.
 *
 * <p>The edit binds the currency-tolerant gate, {@code FUNCTION TEST-NUMVAL-C}, which accepts a
 * currency sign and grouping commas.
 * {@link #theToleratedCurrencyValuePassesAndDefeatsTheBigDecimalConstructor()} asserts both halves
 * of that tolerance in one test. A currency sign fails the {@code IS NUMERIC} class check at
 * app/cbl/COACTUPC.cbl:L2137, and {@code NumericRequiredValidatorTest} owns that refusal.
 *
 * <p>The paragraph writes one flag, {@code WS-FLG-SIGNED-NUMBER-EDIT PIC X(1)} at
 * app/cbl/COACTUPC.cbl:L56, over the three condition names at app/cbl/COACTUPC.cbl:L57-L59: valid
 * on {@code LOW-VALUES}, not-ok on {@code '0'}, and blank on {@code 'B'}. {@link EditResult}
 * carries one verdict and one message, so each failing state arrives as its own message.
 * {@link #exactlyTwoMessagesAreReachable()} asserts the pair.
 *
 * <p>Five call sites reach the paragraph, at app/cbl/COACTUPC.cbl:L1486,
 * app/cbl/COACTUPC.cbl:L1499, app/cbl/COACTUPC.cbl:L1511, app/cbl/COACTUPC.cbl:L1518 and
 * app/cbl/COACTUPC.cbl:L1525. They carry the credit limit, the cash credit limit, the current
 * balance, the current cycle credit and the current cycle debit.
 * {@link #fiveSourceCallSitesPerformParagraph1250()} counts them in the source, so the count is an
 * asserted fact.
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

    /**
     * The value both halves of the decisive test read: one currency sign and two grouping
     * separators over a two-decimal amount. The currency-tolerant gate at
     * app/cbl/COACTUPC.cbl:L2201 accepts it, and {@link BigDecimal#BigDecimal(String)} refuses it.
     */
    private static final String TOLERATED_CURRENCY_VALUE = "$1,234,567.89";

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

    @Test
    @DisplayName("The gate accepts a value carrying a currency sign and grouping separators, and "
            + "the plain BigDecimal constructor refuses the identical value")
    void theToleratedCurrencyValuePassesAndDefeatsTheBigDecimalConstructor() {
        assertThat(TOLERATED_CURRENCY_VALUE)
                .contains(String.valueOf(NumvalParser.CURRENCY_SIGN))
                .contains(String.valueOf(NumvalParser.DIGIT_SEPARATOR))
                .contains(String.valueOf(NumvalParser.DECIMAL_POINT));

        EditResult result =
                SignedDecimalValidator.validate(CREDIT_LIMIT_LABEL, TOLERATED_CURRENCY_VALUE);

        assertThat(result.valid())
                .as("the gate at app/cbl/COACTUPC.cbl:L2201 accepts [%s]", TOLERATED_CURRENCY_VALUE)
                .isTrue();
        assertThat(result.message()).isNull();
        assertThat(result.hasMessage()).isFalse();

        Throwable refusal = catchThrowable(() -> new BigDecimal(TOLERATED_CURRENCY_VALUE));

        assertThat(refusal)
                .as("new BigDecimal(\"%s\")", TOLERATED_CURRENCY_VALUE)
                .isExactlyInstanceOf(NumberFormatException.class);
        assertThat(refusal.getMessage()).isNotBlank();
    }

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
        assertThat(result.message()).endsWith(".");
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
            + "fifteen-character host field, the second arm at app/cbl/COACTUPC.cbl:L2185")
    void spacesReportNotSuppliedAtEveryWidth() {
        for (int width = 1; width <= VALUE_HOST_WIDTH; width++) {
            assertThat(SignedDecimalValidator.validate(CREDIT_LIMIT_LABEL, " ".repeat(width))
                    .message())
                    .as("width %d", width)
                    .isEqualTo(CREDIT_LIMIT_LABEL + NOT_SUPPLIED);
        }
    }

    @Test
    @DisplayName("A field mixing LOW-VALUES with a space reaches the gate, since neither arm of "
            + "the two-way test holds for a mixed field")
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

    @Test
    @DisplayName("A signed two-decimal amount passes with no message, in both signs")
    void aSignedTwoDecimalAmountPassesInBothSigns() {
        EditResult positive = SignedDecimalValidator.validate(CURRENT_BALANCE_LABEL, "1234.56");
        EditResult negative = SignedDecimalValidator.validate(CURRENT_BALANCE_LABEL, "-1234.56");

        assertThat(positive.valid()).isTrue();
        assertThat(positive.message()).isNull();
        assertThat(positive.hasMessage()).isFalse();
        assertThat(negative.valid()).isTrue();
        assertThat(negative.message()).isNull();
        assertThat(negative.hasMessage()).isFalse();
    }

    @ParameterizedTest(name = "value [{0}] passes the gate")
    @ValueSource(strings = {"0", "1", "-1", "+1", "1234.56", "-1234.56", "$1,234.56", "1,234.56",
            "1,234,567.89", "$1234.56", "  1234.56  ", "1234.56CR", "1234.56DB", ".56",
            "999999999.99", "-999999999.99", "0.00", "000000000000.00"})
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
    @DisplayName("A value the currency-tolerant gate refuses reports the message that closes with "
            + "no period")
    void aValueTheGateRefusesReportsTheGateMessage(String value) {
        EditResult result = SignedDecimalValidator.validate(CREDIT_LIMIT_LABEL, value);

        assertThat(result.valid()).as("value of width %d", value.length()).isFalse();
        assertThat(result.hasMessage()).isTrue();
        assertThat(result.message()).isEqualTo(CREDIT_LIMIT_LABEL + NOT_VALID);
        assertThat(result.message()).doesNotEndWith(".");
    }

    @Test
    @DisplayName("A value shorter than the fifteen-character host field passes, since no line of "
            + "the paragraph slices the field")
    void aValueShorterThanTheHostFieldPasses() {
        String shorter = "12.34";

        assertThat(shorter.length()).isLessThan(VALUE_HOST_WIDTH);

        EditResult result = SignedDecimalValidator.validate(CURRENT_BALANCE_LABEL, shorter);

        assertThat(result.valid()).isTrue();
        assertThat(result.message()).isNull();

        String shortest = "1";

        assertThat(SignedDecimalValidator.validate(CURRENT_BALANCE_LABEL, shortest).valid())
                .as("width %d", shortest.length())
                .isTrue();
    }

    @Test
    @DisplayName("A value wider than the fifteen-character host field is read whole, since the "
            + "paragraph applies no reference modification")
    void aValueWiderThanTheHostFieldIsReadWhole() {
        String wider = "1".repeat(VALUE_HOST_WIDTH) + ".56";

        assertThat(wider.length()).isGreaterThan(VALUE_HOST_WIDTH);
        assertThat(SignedDecimalValidator.validate(CURRENT_BALANCE_LABEL, wider).valid()).isTrue();

        String widerAndInvalid = "1".repeat(VALUE_HOST_WIDTH) + "..5";

        assertThat(SignedDecimalValidator.validate(CURRENT_BALANCE_LABEL, widerAndInvalid).message())
                .isEqualTo(CURRENT_BALANCE_LABEL + NOT_VALID);
    }

    @Test
    @DisplayName("The edit answers the verdict of the currency-tolerant gate, and never the "
            + "verdict of the plain gate")
    void theEditAnswersTheCurrencyTolerantGate() {
        String[] values = {"1234.56", "$1,234.56", TOLERATED_CURRENCY_VALUE, "-1234.56", "A",
                "1.2.3", "--1", "1 2", "0"};

        for (String value : values) {
            boolean accepted = SignedDecimalValidator.validate(CREDIT_LIMIT_LABEL, value).valid();

            assertThat(accepted)
                    .as("value [%s] against FUNCTION TEST-NUMVAL-C", value)
                    .isEqualTo(NumvalParser.isValidNumvalCurrency(value));
        }

        assertThat(SignedDecimalValidator.validate(CREDIT_LIMIT_LABEL, TOLERATED_CURRENCY_VALUE)
                .valid())
                .as("the plain gate refuses [%s]", TOLERATED_CURRENCY_VALUE)
                .isNotEqualTo(NumvalParser.isValidNumval(TOLERATED_CURRENCY_VALUE));
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
    @DisplayName("Both messages open with the trimmed label, and label padding never leaks")
    void bothMessagesOpenWithTheTrimmedLabel() {
        String padded = CREDIT_LIMIT_LABEL
                + " ".repeat(LABEL_HOST_WIDTH - CREDIT_LIMIT_LABEL.length());

        assertThat(padded.length()).isEqualTo(LABEL_HOST_WIDTH);

        assertThat(SignedDecimalValidator.validate(padded, null).message())
                .isEqualTo(CREDIT_LIMIT_LABEL + NOT_SUPPLIED);
        assertThat(SignedDecimalValidator.validate(padded, "A").message())
                .isEqualTo(CREDIT_LIMIT_LABEL + NOT_VALID);
        assertThat(SignedDecimalValidator.validate("  " + CREDIT_LIMIT_LABEL + "  ", null).message())
                .isEqualTo(CREDIT_LIMIT_LABEL + NOT_SUPPLIED);
        assertThat(SignedDecimalValidator.validate("  " + CREDIT_LIMIT_LABEL + "  ", "A").message())
                .isEqualTo(CREDIT_LIMIT_LABEL + NOT_VALID);
        assertThat(SignedDecimalValidator.validate(null, null).message()).isEqualTo(NOT_SUPPLIED);
        assertThat(SignedDecimalValidator.validate(null, "A").message()).isEqualTo(NOT_VALID);
    }

    @Test
    @DisplayName("Exactly two messages are reachable, and each matches its source literal")
    void exactlyTwoMessagesAreReachable() {
        Set<String> messages = new LinkedHashSet<>();
        Set<String> passingValues = new LinkedHashSet<>();

        collectOutcome(null, passingValues, messages);
        collectOutcome("", passingValues, messages);
        collectOutcome(LOW_VALUE.repeat(VALUE_HOST_WIDTH), passingValues, messages);
        collectOutcome(TOLERATED_CURRENCY_VALUE, passingValues, messages);
        for (char character = 0; character <= 0x7F; character++) {
            collectOutcome(String.valueOf(character), passingValues, messages);
            collectOutcome("1" + character, passingValues, messages);
        }

        assertThat(messages).containsExactlyInAnyOrder(
                CREDIT_LIMIT_LABEL + NOT_SUPPLIED,
                CREDIT_LIMIT_LABEL + NOT_VALID);
        assertThat(passingValues).contains("0", "1", "9", "10", "19", TOLERATED_CURRENCY_VALUE);
    }

    @Test
    @DisplayName("The call reads its arguments and changes neither of them")
    void theCallReadsItsArgumentsAndChangesNeither() {
        String label = CREDIT_LIMIT_LABEL;
        String value = TOLERATED_CURRENCY_VALUE;

        EditResult first = SignedDecimalValidator.validate(label, value);
        EditResult second = SignedDecimalValidator.validate(label, value);

        assertThat(label).isEqualTo(CREDIT_LIMIT_LABEL);
        assertThat(value).isEqualTo(TOLERATED_CURRENCY_VALUE);
        assertThat(first.valid()).isTrue();
        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("Five source call sites perform paragraph 1250")
    void fiveSourceCallSitesPerformParagraph1250() {
        assertThat(countSourcePerformsOf("1250-EDIT-SIGNED-9V2"))
                .as("PERFORM 1250-EDIT-SIGNED-9V2 in app/cbl/COACTUPC.cbl")
                .isEqualTo(EXPECTED_SOURCE_CALL_SITE_COUNT);
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
     * @param paragraphName the paragraph name, with no trailing period
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
