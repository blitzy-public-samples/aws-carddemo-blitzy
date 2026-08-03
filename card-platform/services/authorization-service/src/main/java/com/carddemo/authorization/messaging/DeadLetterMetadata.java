package com.carddemo.authorization.messaging;

import java.util.ArrayList;
import java.util.List;

/**
 * Diagnostic detail attached to a message on the dead-letter topic
 * {@code carddemo.kafka.topics.dead-letter}.
 *
 * <p>Transformed from the abend reporting group {@code 01 ABEND-DATA} at
 * app/cpy/CSMSG02Y.cpy:L21-L29. The group holds four alphanumeric fields:
 * {@code ABEND-CODE PIC X(4)} at L22, {@code ABEND-CULPRIT PIC X(8)} at L24,
 * {@code ABEND-REASON PIC X(50)} at L26 and {@code ABEND-MSG PIC X(72)} at L28.
 * Each of the four carries {@code VALUE SPACES}.
 *
 * <p>The four widths are maxima.
 *
 *
 * <p>Nothing in this record throws, nothing is padded, and no component is ever the source of a
 * second failure. A {@code null} component becomes the empty string, matching the
 * {@code VALUE SPACES} clause each source field carries. Each code point outside printable
 * American Standard Code for Information Interchange (ASCII) becomes one
 * {@link #SUBSTITUTE_CHARACTER}, so no control character and no line break reaches the
 * dead-letter topic. A component longer than its maximum keeps its leading characters, and
 * {@link #truncatedComponents()} names it. Because every retained character is printable ASCII,
 * shortening a component cannot split a surrogate pair.
 *
 * <p>A caller names a failing field by its JavaScript Object Notation (JSON) pointer, for example
 * {@code /maskedCardNumber}, and never the field value. No component carries a card number, a card
 * verification value, an account identifier, a monetary value, a raw payload or the text of a
 * {@code Throwable}. {@link #fromFailure(String, Throwable, String, String)} reads the failure type
 * and nothing else.
 *
 * @param abendCode           the four-character failure code
 * @param culprit             the failing component
 * @param reason              the failure classification
 * @param message             the failure detail
 * @param truncatedComponents names of the components the constructor shortened, in component
 *                            order, empty when it shortened none. The constructor derives this
 *                            component and replaces any value a caller supplies.
 */
public record DeadLetterMetadata(String abendCode, String culprit, String reason,
        String message, List<String> truncatedComponents) {

    /** Count of text components this record sanitises. */
    private static final int COMPONENT_COUNT = 4;

    /**
     * Widest {@code abendCode} this record holds, from {@code ABEND-CODE PIC X(4)} at
     * {@code app/cpy/CSMSG02Y.cpy:L22}.
     */
    public static final int ABEND_CODE_MAX_LENGTH = 4;

    /**
     * Widest {@code culprit} this record holds, from {@code ABEND-CULPRIT PIC X(8)} at
     * {@code app/cpy/CSMSG02Y.cpy:L24}.
     */
    public static final int CULPRIT_MAX_LENGTH = 8;

    /**
     * Widest {@code reason} this record holds, from {@code ABEND-REASON PIC X(50)} at
     * {@code app/cpy/CSMSG02Y.cpy:L26}.
     */
    public static final int REASON_MAX_LENGTH = 50;

    /**
     * Widest {@code message} this record holds, from {@code ABEND-MSG PIC X(72)} at
     * {@code app/cpy/CSMSG02Y.cpy:L28}.
     */
    public static final int MESSAGE_MAX_LENGTH = 72;

    /**
     * Replacement for any character outside printable American Standard Code for Information
     * Interchange (ASCII). Sanitising to printable ASCII first is what makes the length cap below
     * safe: every retained character is one code unit, so shortening a component can never split a
     * surrogate pair.
     */
    public static final char SUBSTITUTE_CHARACTER = '.';

    /** Lowest code point kept unchanged, the space. */
    private static final int FIRST_PRINTABLE_ASCII = 0x20;

    /** Highest code point kept unchanged, the tilde. */
    private static final int LAST_PRINTABLE_ASCII = 0x7E;

    /**
     * Sanitises all four text components and derives {@link #truncatedComponents()}.
     *
     * <p>This constructor throws nothing. A dead-letter record describes a failure that already
     * happened, so a second failure raised while building it would suppress the dead-letter
     * message and leave the broker redelivering the same message for ever.</p>
     *
     * <p>A supplied {@code truncatedComponents} argument is discarded and replaced by the list this
     * constructor derives, so the fifth component always agrees with the other four.</p>
     */
    public DeadLetterMetadata {
        List<String> shortened = new ArrayList<>(COMPONENT_COUNT);
        abendCode = sanitise("abendCode", abendCode, ABEND_CODE_MAX_LENGTH, shortened);
        culprit = sanitise("culprit", culprit, CULPRIT_MAX_LENGTH, shortened);
        reason = sanitise("reason", reason, REASON_MAX_LENGTH, shortened);
        message = sanitise("message", message, MESSAGE_MAX_LENGTH, shortened);
        truncatedComponents = List.copyOf(shortened);
    }

    /**
     * Builds a record from the four text components.
     *
     * <p>Every component may be {@code null}, may be longer than its maximum, and may hold any
     * character. The constructor sanitises each one and records which ones it shortened.</p>
     *
     * @param abendCode the failure code; may be {@code null}
     * @param culprit   the failing component; may be {@code null}
     * @param reason    the failure classification; may be {@code null}
     * @param message   the failure detail; may be {@code null}
     * @return a record whose five components are consistent
     */
    public static DeadLetterMetadata of(String abendCode, String culprit, String reason,
            String message) {
        return new DeadLetterMetadata(abendCode, culprit, reason, message, List.of());
    }

    /**
     * Builds a record whose {@code culprit} names the type of a failure.
     *
     * <p>This method reads the failure's simple class name and nothing else. Neither
     * {@code Throwable.getMessage()} nor any stack frame reaches the record, so a failure carrying
     * a card number, an account identifier or a fragment of the rejected payload cannot leak
     * through the dead-letter topic.</p>
     *
     * @param abendCode the failure code; may be {@code null}
     * @param failure   the failure whose type names the culprit; may be {@code null}
     * @param reason    the failure classification, assembled by the caller; may be {@code null}
     * @param message   the failure detail; may be {@code null}
     * @return a record whose five components are consistent
     */
    public static DeadLetterMetadata fromFailure(String abendCode, Throwable failure, String reason,
            String message) {
        String culprit = failure == null ? "" : failure.getClass().getSimpleName();
        return of(abendCode, culprit, reason, message);
    }

    /**
     * Maps one component onto the text it will hold.
     *
     * <p>A {@code null} component becomes the empty string, matching the {@code VALUE SPACES}
     * clause the copybook sets on every field. Each code point outside printable ASCII becomes one
     * {@link #SUBSTITUTE_CHARACTER}, which removes every control character, every line break and
     * every character a log reader could misread. The scan stops once {@code maxLength} characters
     * are in hand, so the work does not grow with the size of an over-long argument.</p>
     *
     * @param component the component name, added to {@code shortened} when text is dropped
     * @param value     the text the caller supplied; may be {@code null}
     * @param maxLength the widest text the component holds
     * @param shortened collects the names of the components this call shortened
     * @return at most {@code maxLength} printable ASCII characters
     */
    private static String sanitise(String component, String value, int maxLength,
            List<String> shortened) {
        if (value == null || value.isEmpty()) {
            return "";
        }

        StringBuilder kept = new StringBuilder(maxLength);
        int index = 0;
        while (index < value.length() && kept.length() < maxLength) {
            int codePoint = value.codePointAt(index);
            index += Character.charCount(codePoint);
            boolean printable =
                    codePoint >= FIRST_PRINTABLE_ASCII && codePoint <= LAST_PRINTABLE_ASCII;
            kept.append(printable ? (char) codePoint : SUBSTITUTE_CHARACTER);
        }

        if (index < value.length()) {
            shortened.add(component);
        }
        return kept.toString();
    }
}
