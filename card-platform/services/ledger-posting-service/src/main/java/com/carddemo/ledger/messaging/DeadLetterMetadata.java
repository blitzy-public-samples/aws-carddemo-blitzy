package com.carddemo.ledger.messaging;

/**
 * Four bounded strings of diagnostic detail, carried alongside a message routed to the
 * dead-letter topic.
 *
 * <p>The four components reproduce the abend reporting record {@code 01 ABEND-DATA} at
 * {@code app/cpy/CSMSG02Y.cpy:L21-L29}, field for field and width for width. The source fields
 * are {@code ABEND-CODE PIC X(4)} at L22, {@code ABEND-CULPRIT PIC X(8)} at L24,
 * {@code ABEND-REASON PIC X(50)} at L26, and {@code ABEND-MSG PIC X(72)} at L28. All four are
 * alphanumeric, so all four components are {@code String}. {@code ABEND-CULPRIT} receives a
 * program-name literal, declared {@code LIT-THISPGM PIC X(8)} at
 * {@code app/cbl/COACTVWC.cbl:L143}.</p>
 *
 * <p>ADDITIVE. The four-field shape comes from the copybook. The dead-letter routing this record
 * serves has no CardDemo ancestor: the posting program answers a failure by ending the run at
 * {@code app/cbl/CBTRN02C.cbl:L707-L711}.</p>
 *
 * <p>The canonical constructor turns a null component into the empty string, matching the
 * {@code VALUE SPACES} default the copybook sets on each field. The constructor rejects a
 * component longer than its maximum, naming it in the thrown message. Each width is a maximum
 * and not fixed storage: the constructor pads, trims, and truncates nothing.</p>
 *
 * <p>The record stores the text a caller supplies and adds nothing to it. No component carries a
 * card number, a monetary value, a point in time, an identifier, or a stack trace.</p>
 *
 * <p>Source mapping for this file: {@code card-platform/docs/traceability-matrix.md}.</p>
 *
 * @param abendCode the four-character failure code
 * @param culprit   the failing component
 * @param reason    the failure classification
 * @param message   the failure detail
 */
public record DeadLetterMetadata(String abendCode, String culprit, String reason, String message) {

    /** Maximum length, from {@code ABEND-CODE PIC X(4)} at {@code app/cpy/CSMSG02Y.cpy:L22}. */
    private static final int ABEND_CODE_MAX_LENGTH = 4;

    /** Maximum length, from {@code ABEND-CULPRIT PIC X(8)} at {@code app/cpy/CSMSG02Y.cpy:L24}. */
    private static final int CULPRIT_MAX_LENGTH = 8;

    /** Maximum length, from {@code ABEND-REASON PIC X(50)} at {@code app/cpy/CSMSG02Y.cpy:L26}. */
    private static final int REASON_MAX_LENGTH = 50;

    /** Maximum length, from {@code ABEND-MSG PIC X(72)} at {@code app/cpy/CSMSG02Y.cpy:L28}. */
    private static final int MESSAGE_MAX_LENGTH = 72;

    /**
     * Applies the null policy and the four maxima.
     *
     * @throws IllegalArgumentException when a component is longer than its maximum
     */
    public DeadLetterMetadata {
        abendCode = bounded("abendCode", abendCode, ABEND_CODE_MAX_LENGTH);
        culprit = bounded("culprit", culprit, CULPRIT_MAX_LENGTH);
        reason = bounded("reason", reason, REASON_MAX_LENGTH);
        message = bounded("message", message, MESSAGE_MAX_LENGTH);
    }

    /**
     * Returns the empty string for a null value, and the value unchanged when it fits.
     *
     * @throws IllegalArgumentException when the value is longer than {@code maxLength}
     */
    private static String bounded(String component, String value, int maxLength) {
        if (value == null) {
            return "";
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(component + " accepts at most " + maxLength
                    + " characters, and the supplied value is " + value.length() + " characters");
        }
        return value;
    }
}
