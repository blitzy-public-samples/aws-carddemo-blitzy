package com.carddemo.card.messaging;

/**
 * Diagnostic detail the card service attaches to a message it routes to the dead-letter topic.
 *
 * <p>The four components map one for one onto {@code 01 ABEND-DATA} at
 * {@code app/cpy/CSMSG02Y.cpy:L21}, in the copybook's own field order. Per-component provenance
 * appears in {@code card-platform/docs/traceability-matrix.md}.
 *
 * <p>The type is service-local. {@code OutboxRelay} builds one instance for a row the broker will
 * never accept. A validation reject is not a dead-letter case: a reject answers with a Hypertext
 * Transfer Protocol (HTTP) 422 response and a declined event. Only an infrastructure failure or an
 * unpublishable row reaches the dead-letter topic.
 *
 * <p>No caller puts a card number, masked or unmasked, or a card verification value in
 * {@code reason} or {@code message}. A caller names a failing field by its JavaScript Object
 * Notation (JSON) pointer, {@code /maskedCardNumber}, never the field value and never the payload.
 *
 * @param abendCode the failure code. Source {@code ABEND-CODE PIC X(4)} at
 *        {@code app/cpy/CSMSG02Y.cpy:L22}, alphanumeric, so a code keeps its leading zeroes.
 * @param culprit a short identifier of the component that failed. Source
 *        {@code ABEND-CULPRIT PIC X(8)} at {@code app/cpy/CSMSG02Y.cpy:L24}, filled in the source
 *        from the eight-character {@code LIT-THISPGM} at {@code app/cbl/COCRDUPC.cbl:L219}. A Java
 *        class name runs past eight characters and the constructor rejects it.
 * @param reason the failure reason. Source {@code ABEND-REASON PIC X(50)} at
 *        {@code app/cpy/CSMSG02Y.cpy:L26}.
 * @param message the operator-facing text. Source {@code ABEND-MSG PIC X(72)} at
 *        {@code app/cpy/CSMSG02Y.cpy:L28}.
 */
public record DeadLetterMetadata(String abendCode, String culprit, String reason, String message) {

    /** Maximum length of {@code abendCode}. Source {@code ABEND-CODE PIC X(4)}. */
    public static final int MAX_ABEND_CODE_LENGTH = 4;
    /** Maximum length of {@code culprit}. Source {@code ABEND-CULPRIT PIC X(8)}. */
    public static final int MAX_CULPRIT_LENGTH = 8;
    /** Maximum length of {@code reason}. Source {@code ABEND-REASON PIC X(50)}. */
    public static final int MAX_REASON_LENGTH = 50;
    /** Maximum length of {@code message}. Source {@code ABEND-MSG PIC X(72)}. */
    public static final int MAX_MESSAGE_LENGTH = 72;

    /**
     * Text the source substitutes for a blank message at {@code app/cbl/COCRDUPC.cbl:L1534}. The
     * constructor never substitutes it, so a caller that wants the source behaviour passes it.
     */
    public static final String DEFAULT_MESSAGE = "UNEXPECTED ABEND OCCURRED.";

    /**
     * Turns a null component into the empty string and rejects an over-length one. All four
     * copybook fields carry {@code VALUE SPACES}, and {@code app/cbl/COCRDUPC.cbl:L1022} moves
     * {@code SPACES} into {@code ABEND-REASON}. No component is padded, trimmed or truncated.
     *
     * @throws IllegalArgumentException when a component runs past its maximum length
     */
    public DeadLetterMetadata {
        abendCode = normalise("abendCode", abendCode, MAX_ABEND_CODE_LENGTH);
        culprit = normalise("culprit", culprit, MAX_CULPRIT_LENGTH);
        reason = normalise("reason", reason, MAX_REASON_LENGTH);
        message = normalise("message", message, MAX_MESSAGE_LENGTH);
    }

    /** Returns the empty string for null. Applies no other transformation. */
    private static String normalise(String component, String value, int max) {
        String text = value == null ? "" : value;
        if (text.length() > max) {
            throw new IllegalArgumentException(component + " holds " + text.length()
                    + " characters and its maximum is " + max);
        }
        return text;
    }
}
