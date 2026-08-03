package com.carddemo.authorization.messaging;

/**
 * Diagnostic detail attached to a message on the dead-letter topic
 * {@code carddemo.dead-letter}.
 *
 * <p>Transformed from the abend reporting group {@code 01 ABEND-DATA} at
 * app/cpy/CSMSG02Y.cpy:L21-L29. The group holds four alphanumeric fields:
 * {@code ABEND-CODE PIC X(4)} at L22, {@code ABEND-CULPRIT PIC X(8)} at L24,
 * {@code ABEND-REASON PIC X(50)} at L26 and {@code ABEND-MSG PIC X(72)} at L28.
 * Each of the four carries {@code VALUE SPACES}.
 *
 * <p>The four widths are maxima. A null component becomes the empty string. A
 * component longer than its maximum throws {@link IllegalArgumentException}. Text
 * is stored character for character, with no padding and no trimming.
 *
 * <p>Source mapping for this record: card-platform/docs/traceability-matrix.md.
 *
 * @param abendCode the four-character failure code
 * @param culprit   the failing component
 * @param reason    the failure classification
 * @param message   the failure detail
 */
public record DeadLetterMetadata(String abendCode, String culprit, String reason, String message) {

    /**
     * Longest {@link #abendCode()} this record accepts, from
     * {@code ABEND-CODE PIC X(4)} at app/cpy/CSMSG02Y.cpy:L22.
     */
    public static final int ABEND_CODE_MAX_LENGTH = 4;

    /**
     * Longest {@link #culprit()} this record accepts, from
     * {@code ABEND-CULPRIT PIC X(8)} at app/cpy/CSMSG02Y.cpy:L24.
     */
    public static final int ABEND_CULPRIT_MAX_LENGTH = 8;

    /**
     * Longest {@link #reason()} this record accepts, from
     * {@code ABEND-REASON PIC X(50)} at app/cpy/CSMSG02Y.cpy:L26.
     */
    public static final int ABEND_REASON_MAX_LENGTH = 50;

    /**
     * Longest {@link #message()} this record accepts, from
     * {@code ABEND-MSG PIC X(72)} at app/cpy/CSMSG02Y.cpy:L28.
     */
    public static final int ABEND_MSG_MAX_LENGTH = 72;

    /**
     * Turns a null component into the empty string.
     *
     * @throws IllegalArgumentException when a component runs past its maximum
     */
    public DeadLetterMetadata {
        abendCode = checked("abendCode", abendCode, ABEND_CODE_MAX_LENGTH);
        culprit = checked("culprit", culprit, ABEND_CULPRIT_MAX_LENGTH);
        reason = checked("reason", reason, ABEND_REASON_MAX_LENGTH);
        message = checked("message", message, ABEND_MSG_MAX_LENGTH);
    }

    /**
     * Measures one component against its maximum.
     *
     * @param component the component name that the failure text reports
     * @param value     the text the caller supplied, possibly null
     * @param maxLength the longest text the component accepts
     * @return the empty string when {@code value} is null, otherwise {@code value}
     *         unchanged
     * @throws IllegalArgumentException when {@code value} runs past
     *         {@code maxLength}
     */
    private static String checked(String component, String value, int maxLength) {
        if (value == null) {
            return "";
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(component + " holds " + value.length()
                    + " characters and accepts at most " + maxLength);
        }
        return value;
    }
}
