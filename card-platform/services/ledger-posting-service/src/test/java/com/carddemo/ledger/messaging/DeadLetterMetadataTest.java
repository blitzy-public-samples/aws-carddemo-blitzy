package com.carddemo.ledger.messaging;

import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserts the width invariants of {@link DeadLetterMetadata} against the abend reporting record
 * {@code 01 ABEND-DATA} at {@code app/cpy/CSMSG02Y.cpy:L21-L29}. Each component is exercised at
 * its maximum length and one character past it.
 *
 * <p>ADDITIVE. Dead-letter routing has no CardDemo ancestor: the posting job sends rejected
 * records to a fresh generation of an output dataset at {@code app/jcl/POSTTRAN.jcl:L34-L38},
 * which no program reads back. Decisions behind the contract asserted here sit in
 * {@code card-platform/docs/decision-log.md}.</p>
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

        DeadLetterMetadata metadata = new DeadLetterMetadata(
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
        DeadLetterMetadata metadata = new DeadLetterMetadata("L", CULPRIT, REASON, MESSAGE);

        assertEquals("L", metadata.abendCode());
        assertEquals(1, metadata.abendCode().length());
        assertEquals(CULPRIT, metadata.culprit());
        assertEquals(REASON, metadata.reason());
        assertEquals(MESSAGE, metadata.message());
        assertEquals(REASON.length(), metadata.reason().length());
        assertEquals(MESSAGE.length(), metadata.message().length());
    }

    /**
     * Asserts that a code one character over its maximum is rejected. Width from
     * {@code app/cpy/CSMSG02Y.cpy:L22}.
     */
    @Test
    @DisplayName("An abend code one character over its maximum is rejected")
    void constructorRejectsAbendCodeOverItsMaximum() {
        String oversized = "A".repeat(ABEND_CODE_MAX_LENGTH + 1);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new DeadLetterMetadata(oversized, CULPRIT, REASON, MESSAGE));

        assertMessageNamesComponentAndLengths(
                thrown, "abendCode", ABEND_CODE_MAX_LENGTH, oversized.length());
    }

    /**
     * Asserts that a culprit one character over its maximum is rejected. Width from
     * {@code app/cpy/CSMSG02Y.cpy:L24}.
     */
    @Test
    @DisplayName("A culprit one character over its maximum is rejected")
    void constructorRejectsCulpritOverItsMaximum() {
        String oversized = "B".repeat(CULPRIT_MAX_LENGTH + 1);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new DeadLetterMetadata(ABEND_CODE, oversized, REASON, MESSAGE));

        assertMessageNamesComponentAndLengths(
                thrown, "culprit", CULPRIT_MAX_LENGTH, oversized.length());
    }

    /**
     * Asserts that a classification one character over its maximum is rejected. Width from
     * {@code app/cpy/CSMSG02Y.cpy:L26}.
     */
    @Test
    @DisplayName("A classification one character over its maximum is rejected")
    void constructorRejectsReasonOverItsMaximum() {
        String oversized = "C".repeat(REASON_MAX_LENGTH + 1);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new DeadLetterMetadata(ABEND_CODE, CULPRIT, oversized, MESSAGE));

        assertMessageNamesComponentAndLengths(
                thrown, "reason", REASON_MAX_LENGTH, oversized.length());
    }

    /**
     * Asserts that a detail one character over its maximum is rejected. Width from
     * {@code app/cpy/CSMSG02Y.cpy:L28}.
     */
    @Test
    @DisplayName("A detail one character over its maximum is rejected")
    void constructorRejectsMessageOverItsMaximum() {
        String oversized = "D".repeat(MESSAGE_MAX_LENGTH + 1);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new DeadLetterMetadata(ABEND_CODE, CULPRIT, REASON, oversized));

        assertMessageNamesComponentAndLengths(
                thrown, "message", MESSAGE_MAX_LENGTH, oversized.length());
    }

    /**
     * Asserts that a Java class name does not fit the culprit component, which holds an
     * eight-character program name at {@code app/cpy/CSMSG02Y.cpy:L24}.
     */
    @Test
    @DisplayName("A Java class name does not fit the culprit component")
    void constructorRejectsJavaClassNameAsCulprit() {
        String className = "CategoryBalanceUpdater";

        assertTrue(className.length() > CULPRIT_MAX_LENGTH, className);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new DeadLetterMetadata(ABEND_CODE, className, REASON, MESSAGE));

        assertMessageNamesComponentAndLengths(
                thrown, "culprit", CULPRIT_MAX_LENGTH, className.length());
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
                new DeadLetterMetadata(ABEND_CODE, CULPRIT, REASON, MESSAGE);
        DeadLetterMetadata fromShortName =
                new DeadLetterMetadata(ABEND_CODE, CULPRIT, REASON, shortName);

        assertEquals(MESSAGE, fromLongName.message());
        assertEquals(shortName, fromShortName.message());
        assertTrue(MESSAGE.length() <= MESSAGE_MAX_LENGTH, MESSAGE);
    }

    /**
     * Asserts that equality and hash code cover all four components of the record at
     * {@code app/cpy/CSMSG02Y.cpy:L21-L29}.
     */
    @Test
    @DisplayName("Equality and hash code cover all four components")
    void equalityCoversAllFourComponents() {
        DeadLetterMetadata first = new DeadLetterMetadata(ABEND_CODE, CULPRIT, REASON, MESSAGE);
        DeadLetterMetadata second = new DeadLetterMetadata(ABEND_CODE, CULPRIT, REASON, MESSAGE);

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());

        assertNotEquals(first, new DeadLetterMetadata("POST", CULPRIT, REASON, MESSAGE));
        assertNotEquals(first, new DeadLetterMetadata(ABEND_CODE, "POSTTRAN", REASON, MESSAGE));
        assertNotEquals(first,
                new DeadLetterMetadata(ABEND_CODE, CULPRIT, "ACCOUNT BALANCE ROW ABSENT", MESSAGE));
        assertNotEquals(first,
                new DeadLetterMetadata(ABEND_CODE, CULPRIT, REASON, "DataAccessException"));
    }

    /**
     * Asserts that the rendered record carries no long digit run and no standalone three-digit run.
     * The record at {@code app/cpy/CSMSG02Y.cpy:L21-L29} carries no card data of any kind.
     */
    @Test
    @DisplayName("Rendered text carries no digit run that could be card data")
    void toStringCarriesNoCardDigits() {
        DeadLetterMetadata metadata = new DeadLetterMetadata(ABEND_CODE, CULPRIT, REASON, MESSAGE);

        String rendered = metadata.toString();

        assertNotNull(rendered);
        assertFalse(LONG_DIGIT_RUN.matcher(rendered).find(), rendered);
        assertFalse(STANDALONE_THREE_DIGIT_RUN.matcher(rendered).find(), rendered);
    }

    /**
     * Asserts that a null component becomes the empty string, matching the {@code VALUE SPACES}
     * default each field carries at {@code app/cpy/CSMSG02Y.cpy:L21-L29}.
     */
    @Test
    @DisplayName("A null component becomes the empty string")
    void constructorTurnsNullComponentsIntoEmptyStrings() {
        DeadLetterMetadata allNull = new DeadLetterMetadata(null, null, null, null);

        assertEquals("", allNull.abendCode());
        assertEquals("", allNull.culprit());
        assertEquals("", allNull.reason());
        assertEquals("", allNull.message());

        DeadLetterMetadata oneNull = new DeadLetterMetadata(ABEND_CODE, null, REASON, MESSAGE);

        assertEquals("", oneNull.culprit());
        assertEquals(ABEND_CODE, oneNull.abendCode());
        assertEquals(REASON, oneNull.reason());
        assertEquals(MESSAGE, oneNull.message());
    }

    /**
     * Asserts that a width violation message names its component, that component's maximum, and
     * the length of the value supplied.
     *
     * @param thrown         the exception the constructor threw
     * @param component      the component name the message carries
     * @param maxLength      the maximum the message carries
     * @param suppliedLength the length of the value the constructor refused
     */
    private static void assertMessageNamesComponentAndLengths(
            IllegalArgumentException thrown, String component, int maxLength, int suppliedLength) {

        String violation = thrown.getMessage();

        assertNotNull(violation);
        assertTrue(violation.contains(component), violation);
        assertTrue(violation.contains(String.valueOf(maxLength)), violation);
        assertTrue(violation.contains(String.valueOf(suppliedLength)), violation);
    }
}
