package com.carddemo.notification.messaging;

import static com.carddemo.notification.messaging.DeadLetterMetadata.ABEND_CODE_MAX_LENGTH;
import static com.carddemo.notification.messaging.DeadLetterMetadata.CULPRIT_MAX_LENGTH;
import static com.carddemo.notification.messaging.DeadLetterMetadata.MESSAGE_MAX_LENGTH;
import static com.carddemo.notification.messaging.DeadLetterMetadata.REASON_MAX_LENGTH;
import static com.carddemo.notification.messaging.DeadLetterMetadata.RECORD_LENGTH;
import static com.carddemo.notification.messaging.DeadLetterMetadata.SUBSTITUTE_CHARACTER;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the four component widths of {@link DeadLetterMetadata}, the way it pads and shortens a
 * value, and its space-filled default.
 *
 * <p>The widths and the four {@code VALUE SPACES} clauses come from {@code 01 ABEND-DATA} at
 * {@code app/cpy/CSMSG02Y.cpy:L21-L29}. Filling that record in the notification service is
 * additive: the four {@code COPY} statements of {@code app/cbl/CBSTM03A.CBL} name {@code COSTM01}
 * at L51, {@code CVACT03Y} at L53, {@code CUSTREC} at L55 and {@code CVACT01Y} at L57, and
 * {@code CSMSG02Y} is absent. See {@code card-platform/docs/decision-log.md}.
 */
class DeadLetterMetadataTest {

    private static final String CODE_AT_WIDTH = "DL01";
    private static final String CULPRIT_AT_WIDTH = "notif-tp";
    private static final String REASON_AT_WIDTH = "E".repeat(REASON_MAX_LENGTH);
    private static final String MESSAGE_AT_WIDTH = "M".repeat(MESSAGE_MAX_LENGTH);

    /** The unqualified name of one exception type, the shape {@code reason} carries. */
    private static final String EXCEPTION_TYPE = "StatementTransactionRowMissingException";

    /** A JavaScript Object Notation (JSON) pointer, the shape {@code message} carries. */
    private static final String JSON_POINTER = "/maskedCardNumber";

    /** Twelve or more consecutive digits, compiled once. */
    private static final Pattern LONG_DIGIT_RUN = Pattern.compile("\\d{12,}");

    /** A made-up digit run, handed to the negative half of one check alone. */
    private static final String SYNTHETIC_DIGIT_RUN = "1234567890123456";

    /** The widths come from the Picture clauses at {@code app/cpy/CSMSG02Y.cpy:L22-L28}. */
    @Test
    @DisplayName("Width constants hold 4, 8, 50 and 72")
    void widthConstantsMatchTheSourcePictureClauses() {
        assertThat(DeadLetterMetadata.ABEND_CODE_MAX_LENGTH).isEqualTo(4);
        assertThat(DeadLetterMetadata.CULPRIT_MAX_LENGTH).isEqualTo(8);
        assertThat(DeadLetterMetadata.REASON_MAX_LENGTH).isEqualTo(50);
        assertThat(DeadLetterMetadata.MESSAGE_MAX_LENGTH).isEqualTo(72);
    }

    /**
     * The widths of {@code 01 ABEND-DATA} at {@code app/cpy/CSMSG02Y.cpy:L21-L29} sum to 134, and
     * {@code toFixedWidthRecord} joins the four components in that field order.
     */
    @Test
    @DisplayName("The four widths sum to 134 characters in copybook order")
    void widthsSumToTheDeclaredRecordLength() {
        int sum = ABEND_CODE_MAX_LENGTH + CULPRIT_MAX_LENGTH + REASON_MAX_LENGTH
                + MESSAGE_MAX_LENGTH;
        DeadLetterMetadata metadata = DeadLetterMetadata.of(CODE_AT_WIDTH, CULPRIT_AT_WIDTH,
                REASON_AT_WIDTH, MESSAGE_AT_WIDTH);

        assertThat(sum).isEqualTo(134);
        assertThat(RECORD_LENGTH).isEqualTo(sum);
        assertThat(metadata.toFixedWidthRecord()).hasSize(RECORD_LENGTH).isEqualTo(
                CODE_AT_WIDTH + CULPRIT_AT_WIDTH + REASON_AT_WIDTH + MESSAGE_AT_WIDTH);
    }

    /** A value at the width {@code app/cpy/CSMSG02Y.cpy:L22-L28} declares survives unchanged. */
    @Test
    @DisplayName("A value at the declared width survives unchanged")
    void valuesAtTheDeclaredWidthSurviveUnchanged() {
        DeadLetterMetadata metadata = DeadLetterMetadata.of(CODE_AT_WIDTH, CULPRIT_AT_WIDTH,
                REASON_AT_WIDTH, MESSAGE_AT_WIDTH);

        assertThat(metadata.abendCode()).isEqualTo(CODE_AT_WIDTH).hasSize(ABEND_CODE_MAX_LENGTH);
        assertThat(metadata.culprit()).isEqualTo(CULPRIT_AT_WIDTH).hasSize(CULPRIT_MAX_LENGTH);
        assertThat(metadata.reason()).isEqualTo(REASON_AT_WIDTH).hasSize(REASON_MAX_LENGTH);
        assertThat(metadata.message()).isEqualTo(MESSAGE_AT_WIDTH).hasSize(MESSAGE_MAX_LENGTH);
    }

    /**
     * A value one character past the width {@code app/cpy/CSMSG02Y.cpy:L22-L28} declares keeps its
     * leading characters and drops its last one.
     */
    @Test
    @DisplayName("A value one character over the width keeps its leading characters")
    void valuesOneCharacterOverTheWidthKeepTheirLeadingCharacters() {
        DeadLetterMetadata metadata = DeadLetterMetadata.of(CODE_AT_WIDTH + "Z",
                CULPRIT_AT_WIDTH + "Z", REASON_AT_WIDTH + "Z", MESSAGE_AT_WIDTH + "Z");

        assertThat(metadata.abendCode()).isEqualTo(CODE_AT_WIDTH).hasSize(ABEND_CODE_MAX_LENGTH);
        assertThat(metadata.culprit()).isEqualTo(CULPRIT_AT_WIDTH).hasSize(CULPRIT_MAX_LENGTH);
        assertThat(metadata.reason()).isEqualTo(REASON_AT_WIDTH).hasSize(REASON_MAX_LENGTH);
        assertThat(metadata.message()).isEqualTo(MESSAGE_AT_WIDTH).hasSize(MESSAGE_MAX_LENGTH);
    }

    /** A short value gains trailing spaces per {@code app/cpy/CSMSG02Y.cpy:L22-L28}. */
    @Test
    @DisplayName("A short value gains trailing spaces")
    void shortValuesGainTrailingSpaces() {
        DeadLetterMetadata metadata =
                DeadLetterMetadata.of("DL", "notif", EXCEPTION_TYPE, JSON_POINTER);

        assertThat(metadata.abendCode()).isEqualTo("DL  ");
        assertThat(metadata.culprit()).isEqualTo("notif   ");
        assertThat(metadata.reason()).startsWith(EXCEPTION_TYPE).isEqualTo(EXCEPTION_TYPE
                + " ".repeat(REASON_MAX_LENGTH - EXCEPTION_TYPE.length()));
        assertThat(metadata.message()).startsWith(JSON_POINTER).isEqualTo(JSON_POINTER
                + " ".repeat(MESSAGE_MAX_LENGTH - JSON_POINTER.length()));
    }

    /** An empty value fills its component with spaces per {@code app/cpy/CSMSG02Y.cpy:L23-L29}. */
    @Test
    @DisplayName("An empty value becomes a component of spaces")
    void emptyValuesBecomeSpaces() {
        DeadLetterMetadata metadata = DeadLetterMetadata.of("", "", "", "");

        assertThat(metadata.abendCode()).isEqualTo(" ".repeat(ABEND_CODE_MAX_LENGTH));
        assertThat(metadata.culprit()).isEqualTo(" ".repeat(CULPRIT_MAX_LENGTH));
        assertThat(metadata.reason()).isEqualTo(" ".repeat(REASON_MAX_LENGTH));
        assertThat(metadata.message()).isEqualTo(" ".repeat(MESSAGE_MAX_LENGTH));
    }

    /**
     * {@code allSpaces} returns the record the four {@code VALUE SPACES} clauses at
     * {@code app/cpy/CSMSG02Y.cpy:L23-L29} declare, with no component left null.
     */
    @Test
    @DisplayName("The all-spaces default fills every component with spaces")
    void allSpacesFillsEveryComponentWithSpaces() {
        DeadLetterMetadata metadata = DeadLetterMetadata.allSpaces();

        assertThat(metadata.abendCode()).isNotNull().isEqualTo(" ".repeat(ABEND_CODE_MAX_LENGTH));
        assertThat(metadata.culprit()).isNotNull().isEqualTo(" ".repeat(CULPRIT_MAX_LENGTH));
        assertThat(metadata.reason()).isNotNull().isEqualTo(" ".repeat(REASON_MAX_LENGTH));
        assertThat(metadata.message()).isNotNull().isEqualTo(" ".repeat(MESSAGE_MAX_LENGTH));
        assertThat(metadata.toFixedWidthRecord()).isEqualTo(" ".repeat(RECORD_LENGTH));
    }

    /** A null argument fills its component with spaces per {@code app/cpy/CSMSG02Y.cpy:L23-L29}. */
    @Test
    @DisplayName("A null argument becomes a component of spaces")
    void nullArgumentsBecomeSpaces() {
        DeadLetterMetadata blank = DeadLetterMetadata.of(null, null, null, null);
        DeadLetterMetadata partial =
                DeadLetterMetadata.of(CODE_AT_WIDTH, null, EXCEPTION_TYPE, null);

        assertThat(blank).isEqualTo(DeadLetterMetadata.allSpaces());
        assertThat(partial.abendCode()).isEqualTo(CODE_AT_WIDTH);
        assertThat(partial.culprit()).isEqualTo(" ".repeat(CULPRIT_MAX_LENGTH));
        assertThat(partial.reason()).startsWith(EXCEPTION_TYPE);
        assertThat(partial.message()).isEqualTo(" ".repeat(MESSAGE_MAX_LENGTH));
    }

    /** A culprit over the width {@code app/cpy/CSMSG02Y.cpy:L24} declares keeps eight. */
    @Test
    @DisplayName("An over-long culprit shortens to eight characters")
    void anOverLongCulpritShortensToTheDeclaredWidth() {
        DeadLetterMetadata metadata = DeadLetterMetadata.of(CODE_AT_WIDTH,
                "TransactionPostedConsumer", EXCEPTION_TYPE, JSON_POINTER);

        assertThat(metadata.culprit()).isEqualTo("Transact").hasSize(CULPRIT_MAX_LENGTH);
    }

    /** {@code ABEND-REASON} at {@code app/cpy/CSMSG02Y.cpy:L26} carries one exception type name. */
    @Test
    @DisplayName("reason carries an exception type, not an exception message")
    void reasonCarriesAnExceptionType() {
        DeadLetterMetadata metadata = DeadLetterMetadata.of(CODE_AT_WIDTH, CULPRIT_AT_WIDTH,
                EXCEPTION_TYPE, JSON_POINTER);

        assertThat(metadata.reason()).doesNotContain(".", ",", ";", "!", "?")
                .hasSize(REASON_MAX_LENGTH);
        assertThat(metadata.reason().stripTrailing()).isEqualTo(EXCEPTION_TYPE);
    }

    /**
     * {@code ABEND-MSG} at {@code app/cpy/CSMSG02Y.cpy:L28} carries the JSON pointer of one failing
     * field and never that field's value.
     */
    @Test
    @DisplayName("message carries a JavaScript Object Notation pointer")
    void messageCarriesAJsonPointer() {
        DeadLetterMetadata metadata = DeadLetterMetadata.of(CODE_AT_WIDTH, CULPRIT_AT_WIDTH,
                EXCEPTION_TYPE, JSON_POINTER);

        assertThat(metadata.message()).startsWith("/").hasSize(MESSAGE_MAX_LENGTH);
        assertThat(metadata.message().stripTrailing()).isEqualTo(JSON_POINTER);
    }

    /**
     * No component and no {@code toString} shows twelve or more consecutive digits, the shape a
     * Primary Account Number (PAN) takes. The same check finds a made-up sixteen-digit run.
     */
    @Test
    @DisplayName("No component exposes a long digit run")
    void noComponentExposesALongDigitRun() {
        DeadLetterMetadata safe = DeadLetterMetadata.of(CODE_AT_WIDTH,
                "TransactionPostedConsumer", EXCEPTION_TYPE, JSON_POINTER);
        DeadLetterMetadata leaking = DeadLetterMetadata.of(CODE_AT_WIDTH, CULPRIT_AT_WIDTH,
                SYNTHETIC_DIGIT_RUN, JSON_POINTER);

        assertThat(LONG_DIGIT_RUN.matcher(safe.abendCode()).find()).isFalse();
        assertThat(LONG_DIGIT_RUN.matcher(safe.culprit()).find()).isFalse();
        assertThat(LONG_DIGIT_RUN.matcher(safe.reason()).find()).isFalse();
        assertThat(LONG_DIGIT_RUN.matcher(safe.message()).find()).isFalse();
        assertThat(LONG_DIGIT_RUN.matcher(safe.toString()).find()).isFalse();
        assertThat(LONG_DIGIT_RUN.matcher(leaking.reason()).find()).isTrue();
        assertThat(LONG_DIGIT_RUN.matcher(leaking.toString()).find()).isTrue();
    }

    /** Two instances built from identical arguments are equal and share a hash code. */
    @Test
    @DisplayName("Records built from identical arguments are equal")
    void recordsBuiltFromIdenticalArgumentsAreEqual() {
        DeadLetterMetadata first = DeadLetterMetadata.of(CODE_AT_WIDTH, CULPRIT_AT_WIDTH,
                EXCEPTION_TYPE, JSON_POINTER);
        DeadLetterMetadata second = DeadLetterMetadata.of(CODE_AT_WIDTH, CULPRIT_AT_WIDTH,
                EXCEPTION_TYPE, JSON_POINTER);
        DeadLetterMetadata other = DeadLetterMetadata.of(CODE_AT_WIDTH, "notif-fa",
                EXCEPTION_TYPE, JSON_POINTER);

        assertThat(second).isEqualTo(first).hasSameHashCodeAs(first);
        assertThat(other).isNotEqualTo(first);
    }

    /**
     * A code point outside the printable range becomes one
     * {@link DeadLetterMetadata#SUBSTITUTE_CHARACTER}, and the component keeps the width
     * {@code app/cpy/CSMSG02Y.cpy:L22-L28} declares. No line break survives.
     */
    @Test
    @DisplayName("A code point outside the printable range becomes the substitute character")
    void codePointsOutsideThePrintableRangeBecomeTheSubstituteCharacter() {
        DeadLetterMetadata metadata =
                DeadLetterMetadata.of("D\tL", CULPRIT_AT_WIDTH, "Row\nMissing", JSON_POINTER);

        assertThat(metadata.abendCode()).isEqualTo("D" + SUBSTITUTE_CHARACTER + "L ");
        assertThat(metadata.reason()).startsWith("Row" + SUBSTITUTE_CHARACTER + "Missing")
                .doesNotContain("\n");
    }
}
