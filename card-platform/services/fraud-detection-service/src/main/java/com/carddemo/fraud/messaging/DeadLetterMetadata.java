package com.carddemo.fraud.messaging;

/**
 * Four fields that travel with a message routed to the dead-letter topic.
 *
 * <p>ADDITIVE IN FULL: net new; no COBOL ancestor. The four widths come from
 * {@code 01 ABEND-DATA} at {@code app/cpy/CSMSG02Y.cpy:L21-L29}. The four-character
 * {@code abendCode} follows the file-status formatter at {@code app/cbl/CBTRN02C.cbl:L714-L727},
 * which widens a two-byte status to four digits. Both locators are shape only, no logic.
 *
 * <p>The canonical constructor turns a {@code null} component into the empty string, matching the
 * {@code VALUE SPACES} clause each source field carries. A longer component is truncated on the
 * right; its leading characters survive. Nothing is rejected and nothing is padded.
 *
 * <p>Callers name the failing field's JavaScript Object Notation (JSON) pointer in {@code reason},
 * for example {@code /maskedCardNumber}. No component carries an instance value or a raw payload.
 *
 * <p>The field mapping is recorded in {@code card-platform/docs/traceability-matrix.md}.
 *
 * @param abendCode a four-character failure code, from {@code ABEND-CODE PIC X(4)} at
 *                  {@code app/cpy/CSMSG02Y.cpy:L22}
 * @param culprit   a short name for the failing component, from {@code ABEND-CULPRIT PIC X(8)} at
 *                  {@code app/cpy/CSMSG02Y.cpy:L24}
 * @param reason    what failed, from {@code ABEND-REASON PIC X(50)} at
 *                  {@code app/cpy/CSMSG02Y.cpy:L26}
 * @param message   text for whoever reads the dead-letter topic, from
 *                  {@code ABEND-MSG PIC X(72)} at {@code app/cpy/CSMSG02Y.cpy:L28}
 */
public record DeadLetterMetadata(String abendCode, String culprit, String reason, String message) {

    /**
     * Longest {@code abendCode} kept. {@code ABEND-CODE PIC X(4)} at
     * {@code app/cpy/CSMSG02Y.cpy:L22}.
     */
    public static final int CODE_MAX_LENGTH = 4;

    /**
     * Longest {@code culprit} kept. {@code ABEND-CULPRIT PIC X(8)} at
     * {@code app/cpy/CSMSG02Y.cpy:L24}.
     */
    public static final int CULPRIT_MAX_LENGTH = 8;

    /**
     * Longest {@code reason} kept. {@code ABEND-REASON PIC X(50)} at
     * {@code app/cpy/CSMSG02Y.cpy:L26}.
     */
    public static final int REASON_MAX_LENGTH = 50;

    /**
     * Longest {@code message} kept. {@code ABEND-MSG PIC X(72)} at
     * {@code app/cpy/CSMSG02Y.cpy:L28}.
     */
    public static final int MESSAGE_MAX_LENGTH = 72;

    /**
     * Normalises all four components. A {@code null} value becomes the empty string. A value
     * longer than its maximum keeps its leading characters and loses the rest. The constructor
     * throws nothing.
     */
    public DeadLetterMetadata {
        abendCode = fit(abendCode, CODE_MAX_LENGTH);
        culprit = fit(culprit, CULPRIT_MAX_LENGTH);
        reason = fit(reason, REASON_MAX_LENGTH);
        message = fit(message, MESSAGE_MAX_LENGTH);
    }

    /**
     * Builds a record whose {@code culprit} names the type of a failure.
     *
     * <p>The {@code culprit} holds the first {@value #CULPRIT_MAX_LENGTH} characters of the
     * failure's simple class name. A {@code null} failure yields an empty {@code culprit}. This
     * method reads no other part of the failure. No text the failure carries reaches the record.
     *
     * @param abendCode a four-character failure code; may be {@code null}
     * @param failure   the failure whose type names the culprit; may be {@code null}
     * @param reason    what failed, assembled by the caller; may be {@code null}
     * @param message   text for whoever reads the dead-letter topic; may be {@code null}
     * @return a record with all four components normalised
     */
    public static DeadLetterMetadata fromFailure(String abendCode, Throwable failure,
            String reason, String message) {
        String culprit = failure == null ? "" : failure.getClass().getSimpleName();
        return new DeadLetterMetadata(abendCode, culprit, reason, message);
    }

    /**
     * Trims one value to the width of its component.
     *
     * @param value     the value to fit; may be {@code null}
     * @param maxLength the component's maximum length
     * @return the empty string for {@code null}, the value itself when it fits, or its leading
     *         {@code maxLength} characters
     */
    private static String fit(String value, int maxLength) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
