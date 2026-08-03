package com.carddemo.account.messaging;

/**
 * Diagnostic detail carried alongside one message on the dead-letter topic.
 *
 * <p>Transformed field by field from the group {@code 01 ABEND-DATA.} at
 * app/cpy/CSMSG02Y.cpy:L21, whose four alphanumeric fields occupy 134 bytes.</p>
 *
 * <p>A component holds at most the width of the field it carries; a longer value
 * raises {@link IllegalArgumentException}. A null component holds the empty string,
 * matching the {@code VALUE SPACES} clause on all four fields. Text arrives
 * unchanged: no padding, no trimming.</p>
 *
 * <p>Field-by-field source mapping: card-platform/docs/traceability-matrix.md.</p>
 *
 * @param abendCode the four-character failure code. {@code ABEND-CODE PIC X(4)} at
 *                  app/cpy/CSMSG02Y.cpy:L22
 * @param culprit   the failing component. {@code ABEND-CULPRIT PIC X(8)} at
 *                  app/cpy/CSMSG02Y.cpy:L24, which app/cbl/COACTUPC.cbl:L4209 fills
 *                  with the failing program name
 * @param reason    the failure classification. {@code ABEND-REASON PIC X(50)} at
 *                  app/cpy/CSMSG02Y.cpy:L26
 * @param message   the failure detail. {@code ABEND-MSG PIC X(72)} at
 *                  app/cpy/CSMSG02Y.cpy:L28, which app/cbl/COACTUPC.cbl:L4205-L4206
 *                  sets to {@code UNEXPECTED ABEND OCCURRED.} when the field holds low
 *                  values
 */
public record DeadLetterMetadata(String abendCode, String culprit, String reason, String message) {

    /** {@code ABEND-CODE PIC X(4)} at app/cpy/CSMSG02Y.cpy:L22. */
    private static final int ABEND_CODE_WIDTH = 4;

    /** {@code ABEND-CULPRIT PIC X(8)} at app/cpy/CSMSG02Y.cpy:L24. */
    private static final int CULPRIT_WIDTH = 8;

    /** {@code ABEND-REASON PIC X(50)} at app/cpy/CSMSG02Y.cpy:L26. */
    private static final int REASON_WIDTH = 50;

    /** {@code ABEND-MSG PIC X(72)} at app/cpy/CSMSG02Y.cpy:L28. */
    private static final int MESSAGE_WIDTH = 72;

    /**
     * Replaces each null component with the empty string, then checks each component
     * against the width of the field it carries.
     *
     * @throws IllegalArgumentException when a component is longer than its field
     */
    public DeadLetterMetadata {
        abendCode = requireWidthAtMost("abendCode", abendCode, ABEND_CODE_WIDTH);
        culprit = requireWidthAtMost("culprit", culprit, CULPRIT_WIDTH);
        reason = requireWidthAtMost("reason", reason, REASON_WIDTH);
        message = requireWidthAtMost("message", message, MESSAGE_WIDTH);
    }

    /**
     * Returns the text one component will hold. A failure report names the component,
     * the width it accepts, and the length it received.
     *
     * @throws IllegalArgumentException when the text is longer than {@code maxWidth}
     */
    private static String requireWidthAtMost(String component, String value, int maxWidth) {
        if (value == null) {
            return "";
        }
        if (value.length() > maxWidth) {
            throw new IllegalArgumentException(component + " accepts at most " + maxWidth
                    + " characters and received " + value.length());
        }
        return value;
    }
}
