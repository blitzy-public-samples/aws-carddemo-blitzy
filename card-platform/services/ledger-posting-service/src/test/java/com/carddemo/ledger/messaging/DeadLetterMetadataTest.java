package com.carddemo.ledger.messaging;

import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserts the width invariants of {@link DeadLetterMetadata} against the abend reporting record
 * {@code 01 ABEND-DATA} at {@code app/cpy/CSMSG02Y.cpy:L21-L29}. Each component is exercised at
 * its maximum length and one character past it.
 *
 * <p>The constructor throws nothing. A dead-letter record describes a failure that already
 * happened, so a second failure raised while building it would suppress the dead-letter message
 * and leave the broker redelivering for ever. An over-long component therefore keeps its leading
 * characters.</p>
 *
 * <p>ADDITIVE. Dead-letter routing has no CardDemo ancestor: the posting job sends rejected
 * records to a fresh generation of an output dataset at {@code app/jcl/POSTTRAN.jcl:L34-L38},
 * which no program reads back.</p>
 *
 * <p>This record is service-local. Five services each declare their own record of this name. Each
 * one shortens an over-length component rather than refusing it. The record stays inside this
 * service's own package and carries no shared-library package prefix.</p>
 */
class DeadLetterMetadataTest {

    /**
     * Maximum length of the first component, from {@code ABEND-CODE PIC X(4)} at
     * {@code app/cpy/CSMSG02Y.cpy:L22}.
     */
    private static final int ABEND_CODE_MAX_LENGTH = 4;

    /**
     * Maximum length of the second component, from {@code ABEND-CULPRIT PIC X(8)} at
     * {@code app/cpy/CSMSG02Y.cpy:L24}.
     */
    private static final int CULPRIT_MAX_LENGTH = 8;

    /**
     * Maximum length of the third component, from {@code ABEND-REASON PIC X(50)} at
     * {@code app/cpy/CSMSG02Y.cpy:L26}.
     */
    private static final int REASON_MAX_LENGTH = 50;

    /**
     * Maximum length of the fourth component, from {@code ABEND-MSG PIC X(72)} at
     * {@code app/cpy/CSMSG02Y.cpy:L28}.
     */
    private static final int MESSAGE_MAX_LENGTH = 72;

    /** A failure code at {@link #ABEND_CODE_MAX_LENGTH}. */
    private static final String ABEND_CODE = "LDGR";

    /**
     * A program name at {@link #CULPRIT_MAX_LENGTH}, the value {@code ABEND-CULPRIT} carries in
     * the CardDemo source.
     */
    private static final String CULPRIT = "CBTRN02C";

    /** A failure classification shorter than {@link #REASON_MAX_LENGTH}. */
    private static final String REASON = "CATEGORY BALANCE UPSERT FAILED";

    /**
     * An exception simple name shorter than {@link #MESSAGE_MAX_LENGTH}, the shape a consumer
     * supplies for the fourth component.
     */
    private static final String MESSAGE = "AccountBalanceRowMissingException";

    /** Twelve or more consecutive digits, the shape of a card number. */
    private static final Pattern LONG_DIGIT_RUN = Pattern.compile("\\d{12,}");

    /** A three-digit run with no digit on either side. */
    private static final Pattern STANDALONE_THREE_DIGIT_RUN =
            Pattern.compile("(?<!\\d)\\d{3}(?!\\d)");

    /** Package this record belongs to. A shared, interoperable payload would sit elsewhere. */
    private static final String OWNING_PACKAGE = "com.carddemo.ledger.messaging";

    /**
     * Asserts that values at the four maximum lengths construct and survive unchanged. Widths from
     * {@code app/cpy/CSMSG02Y.cpy:L21-L29}.
     */
    @Test
    @DisplayName("Values at the four maximum lengths construct and survive unchanged")
    void constructorAcceptsValuesAtEachMaximumLength() {
        String codeAtMaximum = "A".repeat(ABEND_CODE_MAX_LENGTH);
        String culpritAtMaximum = "B".repeat(CULPRIT_MAX_LENGTH);
        String classificationAtMaximum = "C".repeat(REASON_MAX_LENGTH);
        String detailAtMaximum = "D".repeat(MESSAGE_MAX_LENGTH);

        DeadLetterMetadata metadata = DeadLetterMetadata.of(
                codeAtMaximum, culpritAtMaximum, classificationAtMaximum, detailAtMaximum);

        assertEquals(codeAtMaximum, metadata.abendCode());
        assertEquals(culpritAtMaximum, metadata.culprit());
        assertEquals(classificationAtMaximum, metadata.reason());
        assertEquals(detailAtMaximum, metadata.message());
        assertEquals(ABEND_CODE_MAX_LENGTH, metadata.abendCode().length());
        assertEquals(CULPRIT_MAX_LENGTH, metadata.culprit().length());
        assertEquals(REASON_MAX_LENGTH, metadata.reason().length());
        assertEquals(MESSAGE_MAX_LENGTH, metadata.message().length());
    }

    /**
     * Asserts that a one-character code stays one character. Each width at
     * {@code app/cpy/CSMSG02Y.cpy:L21-L29} caps a component and does not fix its size.
     */
    @Test
    @DisplayName("Short values keep their own length")
    void constructorPadsNothingAndTrimsNothing() {
        DeadLetterMetadata metadata = DeadLetterMetadata.of("L", CULPRIT, REASON, MESSAGE);

        assertEquals("L", metadata.abendCode());
        assertEquals(1, metadata.abendCode().length());
        assertEquals(CULPRIT, metadata.culprit());
        assertEquals(REASON, metadata.reason());
        assertEquals(MESSAGE, metadata.message());
        assertEquals(REASON.length(), metadata.reason().length());
        assertEquals(MESSAGE.length(), metadata.message().length());
    }

    /**
     * Asserts that a component one character over its maximum keeps its leading characters, and
     * that the constructor throws nothing. Widths from {@code app/cpy/CSMSG02Y.cpy:L21-L29}.
     */
    @Test
    @DisplayName("A component over its maximum is shortened, and nothing throws")
    void constructorShortensAnOverLongComponent() {
        String longCode = "A".repeat(ABEND_CODE_MAX_LENGTH + 1);
        String longCulprit = "B".repeat(CULPRIT_MAX_LENGTH + 1);
        String longReason = "C".repeat(REASON_MAX_LENGTH + 1);
        String longDetail = "D".repeat(MESSAGE_MAX_LENGTH + 1);

        DeadLetterMetadata metadata = assertDoesNotThrow(() -> DeadLetterMetadata.of(
                longCode, longCulprit, longReason, longDetail));

        assertEquals("A".repeat(ABEND_CODE_MAX_LENGTH), metadata.abendCode());
        assertEquals("B".repeat(CULPRIT_MAX_LENGTH), metadata.culprit());
        assertEquals("C".repeat(REASON_MAX_LENGTH), metadata.reason());
        assertEquals("D".repeat(MESSAGE_MAX_LENGTH), metadata.message());
    }

    /**
     * Asserts that shortening one over-long component leaves the other three as they were supplied.
     */
    @Test
    @DisplayName("Only the component actually over its maximum is shortened")
    void onlyTheComponentOverItsMaximumIsShortened() {
        DeadLetterMetadata onlyReason = DeadLetterMetadata.of(
                ABEND_CODE, CULPRIT, "C".repeat(REASON_MAX_LENGTH + 1), MESSAGE);

        assertEquals("C".repeat(REASON_MAX_LENGTH), onlyReason.reason());
        assertEquals(ABEND_CODE, onlyReason.abendCode());
        assertEquals(CULPRIT, onlyReason.culprit());
        assertEquals(MESSAGE, onlyReason.message());

        DeadLetterMetadata nothingShortened =
                DeadLetterMetadata.of(ABEND_CODE, CULPRIT, REASON, MESSAGE);

        assertIterableEquals(List.of(ABEND_CODE, CULPRIT, REASON, MESSAGE),
                List.of(nothingShortened.abendCode(), nothingShortened.culprit(),
                        nothingShortened.reason(), nothingShortened.message()),
                "a record within every maximum reached its components unchanged");
    }

    /**
     * Asserts that a Java class name is shortened to the culprit width rather than refused. The
     * component holds an eight-character program name at {@code app/cpy/CSMSG02Y.cpy:L24}.
     */
    @Test
    @DisplayName("A Java class name is shortened to the culprit width")
    void constructorShortensAJavaClassNameToTheCulpritWidth() {
        String className = "TransactionCategoryBalanceEntity";

        assertTrue(className.length() > CULPRIT_MAX_LENGTH, className);

        DeadLetterMetadata metadata = assertDoesNotThrow(() -> DeadLetterMetadata.of(
                ABEND_CODE, className, REASON, MESSAGE));

        assertEquals(className.substring(0, CULPRIT_MAX_LENGTH), metadata.culprit());
    }

    @Test
    @DisplayName("Every character outside printable ASCII becomes one substitute character")
    void constructorReplacesEveryCharacterOutsidePrintableAscii() {
        DeadLetterMetadata metadata = DeadLetterMetadata.of(
                "A\u0000B", "C\r\nD", "E\u00e9F", "G\ud83d\ude00H");

        assertEquals("A.B", metadata.abendCode());
        assertEquals("C..D", metadata.culprit());
        assertEquals("E.F", metadata.reason());
        assertEquals("G.H", metadata.message());

        String rendered = metadata.toString();

        assertFalse(rendered.contains("\n"), "the rendered record carried a line break");
        assertFalse(rendered.contains("\u0000"), "the rendered record carried a control character");
    }

    @Test
    @DisplayName("The failure factory copies the failure type and no failure text")
    void failureFactoryCopiesOnlyTheFailureType() {
        Exception failure = new IllegalStateException("card 4111222233337065 balance 1250.75");

        DeadLetterMetadata metadata =
                DeadLetterMetadata.fromFailure(ABEND_CODE, failure, REASON, MESSAGE);

        assertEquals("IllegalS", metadata.culprit());
        assertFalse(metadata.toString().contains("4111222233337065"),
                "the record carried a card number from the failure text");
        assertFalse(metadata.toString().contains("1250.75"),
                "the record carried a monetary value from the failure text");

        DeadLetterMetadata noFailure =
                DeadLetterMetadata.fromFailure(ABEND_CODE, null, REASON, MESSAGE);

        assertEquals("", noFailure.culprit());
    }

    /**
     * Asserts that an exception simple name is accepted as the fourth component, whose width comes
     * from {@code app/cpy/CSMSG02Y.cpy:L28}.
     */
    @Test
    @DisplayName("An exception simple name is accepted as the fourth component")
    void constructorAcceptsExceptionSimpleNameAsMessage() {
        String shortName = "DataAccessException";

        DeadLetterMetadata fromLongName =
                DeadLetterMetadata.of(ABEND_CODE, CULPRIT, REASON, MESSAGE);
        DeadLetterMetadata fromShortName =
                DeadLetterMetadata.of(ABEND_CODE, CULPRIT, REASON, shortName);

        assertEquals(MESSAGE, fromLongName.message());
        assertEquals(shortName, fromShortName.message());
        assertTrue(MESSAGE.length() <= MESSAGE_MAX_LENGTH, MESSAGE);
    }

    /**
     * Asserts that equality and hash code cover the four text components of the record at
     * {@code app/cpy/CSMSG02Y.cpy:L21-L29}.
     */
    @Test
    @DisplayName("Equality and hash code cover all four text components")
    void equalityCoversAllFourTextComponents() {
        DeadLetterMetadata first = DeadLetterMetadata.of(ABEND_CODE, CULPRIT, REASON, MESSAGE);
        DeadLetterMetadata second = DeadLetterMetadata.of(ABEND_CODE, CULPRIT, REASON, MESSAGE);

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());

        assertNotEquals(first, DeadLetterMetadata.of("POST", CULPRIT, REASON, MESSAGE));
        assertNotEquals(first, DeadLetterMetadata.of(ABEND_CODE, "POSTTRAN", REASON, MESSAGE));
        assertNotEquals(first,
                DeadLetterMetadata.of(ABEND_CODE, CULPRIT, "ACCOUNT BALANCE ROW ABSENT", MESSAGE));
        assertNotEquals(first,
                DeadLetterMetadata.of(ABEND_CODE, CULPRIT, REASON, "DataAccessException"));
    }

    /**
     * Asserts that the rendered record carries no long digit run and no standalone three-digit run.
     * The record at {@code app/cpy/CSMSG02Y.cpy:L21-L29} carries no card data of any kind.
     *
     * <p>Each failure message below is fixed text. A digit run these checks caught would be the
     * very text that must not reach a log, so the message names the check and prints nothing the
     * rendering held.</p>
     */
    @Test
    @DisplayName("Rendered text carries no digit run that could be card data")
    void toStringCarriesNoCardDigits() {
        DeadLetterMetadata metadata = DeadLetterMetadata.of(ABEND_CODE, CULPRIT, REASON, MESSAGE);

        String rendered = metadata.toString();

        assertNotNull(rendered, "the rendered record was null");
        assertFalse(LONG_DIGIT_RUN.matcher(rendered).find(),
                "the rendered record carries a digit run long enough to be a card number");
        assertFalse(STANDALONE_THREE_DIGIT_RUN.matcher(rendered).find(),
                "the rendered record carries a standalone three-digit run");
    }

    /**
     * Asserts that a null component becomes the empty string, matching the {@code VALUE SPACES}
     * default each field carries at {@code app/cpy/CSMSG02Y.cpy:L21-L29}.
     */
    @Test
    @DisplayName("A null component becomes the empty string")
    void constructorTurnsNullComponentsIntoEmptyStrings() {
        DeadLetterMetadata allNull = DeadLetterMetadata.of(null, null, null, null);

        assertEquals("", allNull.abendCode());
        assertEquals("", allNull.culprit());
        assertEquals("", allNull.reason());
        assertEquals("", allNull.message());

        DeadLetterMetadata oneNull = DeadLetterMetadata.of(ABEND_CODE, null, REASON, MESSAGE);

        assertEquals("", oneNull.culprit());
        assertEquals(ABEND_CODE, oneNull.abendCode());
        assertEquals(REASON, oneNull.reason());
        assertEquals(MESSAGE, oneNull.message());
    }

    @Test
    @DisplayName("Belongs to this service alone and to no shared library")
    void recordBelongsToThisServiceAloneAndToNoSharedLibrary() {
        String actualPackage = DeadLetterMetadata.class.getPackageName();

        assertEquals(OWNING_PACKAGE, actualPackage,
                "DeadLetterMetadata belongs to the ledger posting service alone");
        assertFalse(actualPackage.startsWith("com.carddemo.events"),
                "a shared event contract would sit under com.carddemo.events, and this record "
                        + "does not");
        assertFalse(actualPackage.startsWith("com.carddemo.cobol"),
                "a shared compatibility type would sit under com.carddemo.cobol, and this record "
                        + "does not");
        assertEquals(0, DeadLetterMetadata.class.getAnnotations().length,
                "DeadLetterMetadata carries no annotation, so no framework publishes it as a "
                        + "shared contract");
    }

    @Test
    @DisplayName("Truncates an over-length component instead of refusing it")
    void recordTruncatesAnOverLengthComponentInsteadOfRefusingIt() {
        String overLength = "E".repeat(CULPRIT_MAX_LENGTH + 1);

        DeadLetterMetadata truncated = assertDoesNotThrow(
                () -> DeadLetterMetadata.of(ABEND_CODE, overLength, REASON, MESSAGE),
                "building the record raised a failure of its own");

        assertEquals(CULPRIT_MAX_LENGTH, truncated.culprit().length(),
                "the culprit holds its leading " + CULPRIT_MAX_LENGTH + " characters");
        assertEquals(overLength.substring(0, CULPRIT_MAX_LENGTH), truncated.culprit(),
                "the culprit keeps the leading characters of the value supplied");
        assertNotEquals(overLength, truncated.culprit(),
                "the culprit differs from the value supplied, so the record truncated it");

        DeadLetterMetadata atMaximum = DeadLetterMetadata.of(
                ABEND_CODE, overLength.substring(0, CULPRIT_MAX_LENGTH), REASON, MESSAGE);
        assertEquals(truncated.culprit(), atMaximum.culprit(),
                "the value already cut to width " + CULPRIT_MAX_LENGTH + " reaches the component "
                        + "unchanged, which shows the truncation is about length alone");
    }
}
