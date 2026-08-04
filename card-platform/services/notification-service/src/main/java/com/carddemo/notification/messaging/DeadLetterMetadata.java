package com.carddemo.notification.messaging;

/**
 * Four diagnostic fields describing one message the notification service could not process.
 *
 * <p>SOURCE-DERIVED SHAPE, ADDITIVE POPULATION. The four widths and the space-filled initial state
 * come from {@code 01 ABEND-DATA} at {@code app/cpy/CSMSG02Y.cpy:L21-L29}. Populating such a record
 * in this service is additive. {@code app/cbl/CBSTM03A.CBL} carries four {@code COPY} statements,
 * and this copybook is not among them: {@code COSTM01} at L51, {@code CVACT03Y} at L53,
 * {@code CUSTREC} at L55, {@code CVACT01Y} at L57.
 *
 * <p>{@code app/cbl/CBSTM03A.CBL} reaches its abend paragraph at L921 through L923, which runs a
 * {@code DISPLAY} and a {@code CALL 'CEE3ABD'}, moving no code and zeroing no field. The batch
 * posting program adds {@code MOVE 0 TO TIMING} and {@code MOVE 999 TO ABCODE} at
 * {@code app/cbl/CBTRN02C.cbl:L707-L711}. Five programs include the copybook, and all five are
 * online Customer Information Control System (CICS) programs: {@code app/cbl/COCRDLIC.cbl},
 * {@code app/cbl/COCRDUPC.cbl}, {@code app/cbl/COACTVWC.cbl}, {@code app/cbl/COACTUPC.cbl} and
 * {@code app/cbl/COCRDSLC.cbl}.
 *
 * <p>Each component holds exactly its declared width. A short argument gains trailing spaces, and
 * an over-long argument keeps its leading characters. A {@code null} argument becomes spaces,
 * matching the {@code VALUE SPACES} clause on all four source fields. Each code point outside
 * printable American Standard Code for Information Interchange (ASCII) becomes one
 * {@link #SUBSTITUTE_CHARACTER}. Each retained character is then one code unit wide, so
 * shortening a component cannot split a surrogate pair.
 *
 * <p>A caller names a failing field by its JavaScript Object Notation (JSON) pointer, for example
 * {@code /maskedCardNumber}. No component carries a full Primary Account Number (PAN), a card
 * verification value, or any part of a payload. No method here accepts a payload, and nothing here
 * throws, logs, counts, retries or publishes.
 *
 * <p>For the path one message travels from publish through consume to the dead-letter topic, read
 * {@code card-platform/docs/event-flow.md}. The choices behind this type sit in
 * {@code card-platform/docs/decision-log.md}.
 *
 * @param abendCode a stable failure classifier, from {@code ABEND-CODE PIC X(4)} at
 *        {@code app/cpy/CSMSG02Y.cpy:L22}
 * @param culprit   the failing consumer or topic identity in eight characters, for example
 *        {@code notif-tp}, from {@code ABEND-CULPRIT PIC X(8)} at {@code app/cpy/CSMSG02Y.cpy:L24}
 * @param reason    the exception type, from {@code ABEND-REASON PIC X(50)} at
 *        {@code app/cpy/CSMSG02Y.cpy:L26}
 * @param message   the failure detail, carrying the JSON pointer of the failing field and never its
 *        value, from {@code ABEND-MSG PIC X(72)} at {@code app/cpy/CSMSG02Y.cpy:L28}
 */
public record DeadLetterMetadata(String abendCode, String culprit, String reason, String message) {

    /**
     * Width of {@code abendCode}, from {@code ABEND-CODE PIC X(4)} at
     * {@code app/cpy/CSMSG02Y.cpy:L22}.
     */
    public static final int ABEND_CODE_MAX_LENGTH = 4;

    /**
     * Width of {@code culprit}, from {@code ABEND-CULPRIT PIC X(8)} at
     * {@code app/cpy/CSMSG02Y.cpy:L24}. Eight characters hold a short consumer or topic identity
     * and no package path.
     */
    public static final int CULPRIT_MAX_LENGTH = 8;

    /**
     * Width of {@code reason}, from {@code ABEND-REASON PIC X(50)} at
     * {@code app/cpy/CSMSG02Y.cpy:L26}.
     */
    public static final int REASON_MAX_LENGTH = 50;

    /**
     * Width of {@code message}, from {@code ABEND-MSG PIC X(72)} at
     * {@code app/cpy/CSMSG02Y.cpy:L28}.
     */
    public static final int MESSAGE_MAX_LENGTH = 72;

    /**
     * Length of the text {@link #toFixedWidthRecord()} returns. The sum of the four widths above
     * matches the byte count of {@code 01 ABEND-DATA} at {@code app/cpy/CSMSG02Y.cpy:L21-L29}.
     */
    public static final int RECORD_LENGTH =
            ABEND_CODE_MAX_LENGTH + CULPRIT_MAX_LENGTH + REASON_MAX_LENGTH + MESSAGE_MAX_LENGTH;

    /**
     * Replacement for any character outside printable American Standard Code for Information
     * Interchange (ASCII). No control character and no line break survives it.
     */
    public static final char SUBSTITUTE_CHARACTER = '.';

    /** Lowest code point kept unchanged, the space. */
    private static final int FIRST_PRINTABLE_ASCII = 0x20;

    /** Highest code point kept unchanged, the tilde. */
    private static final int LAST_PRINTABLE_ASCII = 0x7E;

    /**
     * Fixes all four components at their declared widths.
     *
     * <p>Nothing here throws, and no component holds {@code null} once the constructor
     * returns.</p>
     */
    public DeadLetterMetadata {
        abendCode = fixedWidth(abendCode, ABEND_CODE_MAX_LENGTH);
        culprit = fixedWidth(culprit, CULPRIT_MAX_LENGTH);
        reason = fixedWidth(reason, REASON_MAX_LENGTH);
        message = fixedWidth(message, MESSAGE_MAX_LENGTH);
    }

    /**
     * Builds one metadata record from four texts.
     *
     * <p>Each argument may be {@code null}, may be shorter or longer than its width, and may hold
     * any character. The canonical constructor fixes each one at its declared width.</p>
     *
     * @param abendCode a stable failure classifier; may be {@code null}
     * @param culprit   the failing consumer or topic identity; may be {@code null}
     * @param reason    the exception type; may be {@code null}
     * @param message   the failure detail, a JSON pointer and never a field value; may be
     *                  {@code null}
     * @return a record whose components are 4, 8, 50 and 72 characters wide
     */
    public static DeadLetterMetadata of(String abendCode, String culprit, String reason,
            String message) {
        return new DeadLetterMetadata(abendCode, culprit, reason, message);
    }

    /**
     * Builds the record the copybook declares before anything is moved into it.
     *
     * <p>A caller that knows only that something failed still has a valid record to hand on.</p>
     *
     * @return a record whose four components hold spaces alone
     */
    public static DeadLetterMetadata allSpaces() {
        return new DeadLetterMetadata(null, null, null, null);
    }

    /**
     * Renders the four components in copybook order as one text of {@value #RECORD_LENGTH}
     * characters, the layout of {@code 01 ABEND-DATA} at {@code app/cpy/CSMSG02Y.cpy:L21-L29}.
     *
     * <p>Each character is printable ASCII, so the text holds {@value #RECORD_LENGTH} bytes as
     * well as {@value #RECORD_LENGTH} characters.</p>
     *
     * @return {@code abendCode}, {@code culprit}, {@code reason} and {@code message} joined in that
     *         order
     */
    public String toFixedWidthRecord() {
        return abendCode + culprit + reason + message;
    }

    /**
     * Maps one argument onto the text a component holds.
     *
     * <p>The scan stops once {@code width} characters are in hand, so an over-long argument costs
     * no more work than a fitting one. Spaces then fill any position left over.</p>
     *
     * @param value the text the caller supplied; may be {@code null}
     * @param width the declared width of the component
     * @return exactly {@code width} printable ASCII characters
     */
    private static String fixedWidth(String value, int width) {
        StringBuilder kept = new StringBuilder(width);
        if (value != null) {
            int index = 0;
            while (index < value.length() && kept.length() < width) {
                int codePoint = value.codePointAt(index);
                index += Character.charCount(codePoint);
                boolean printable =
                        codePoint >= FIRST_PRINTABLE_ASCII && codePoint <= LAST_PRINTABLE_ASCII;
                kept.append(printable ? (char) codePoint : SUBSTITUTE_CHARACTER);
            }
        }
        return kept.append(" ".repeat(width - kept.length())).toString();
    }
}
