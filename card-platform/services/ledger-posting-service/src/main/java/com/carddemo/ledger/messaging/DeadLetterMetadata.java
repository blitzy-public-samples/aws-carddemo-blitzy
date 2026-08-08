package com.carddemo.ledger.messaging;

import com.carddemo.events.DeadLetterEnvelope;
import com.carddemo.events.EventEnvelope;
import java.util.List;

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
 * <p>No COBOL ancestor. The four-field shape comes from the copybook. The dead-letter routing this
 * record serves has no CardDemo ancestor: the posting program answers a failure by ending the run
 * at {@code app/cbl/CBTRN02C.cbl:L707-L711}.</p>
 *
 * <p>Nothing in this record throws, nothing is padded, and no component is ever the source of a
 * second failure. A {@code null} component becomes the empty string, matching the
 * {@code VALUE SPACES} clause each source field carries. Each code point outside printable
 * American Standard Code for Information Interchange (ASCII) becomes one
 * {@link #SUBSTITUTE_CHARACTER}, so no control character and no line break reaches the
 * dead-letter topic. A component longer than its maximum keeps its leading characters. Every
 * retained character is printable ASCII and one code unit wide, so shortening a component cannot
 * split a surrogate pair.
 *
 * <p>A caller names a failing field by its JavaScript Object Notation (JSON) pointer, for example
 * {@code /maskedCardNumber}, and never the field value. No component carries a card number, a card
 * verification value, an account identifier, a monetary value, a raw payload or the text of a
 * {@code Throwable}. {@link #fromFailure(String, Throwable, String, String)} reads the failure type
 * and nothing else.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 *
 * @param abendCode the four-character failure code
 * @param culprit   the failing component
 * @param reason    the failure classification
 * @param message   the failure detail
 */
public record DeadLetterMetadata(String abendCode, String culprit, String reason,
        String message) {

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

    /**
     * Width of the rendered record, the sum of the four declared widths.
     *
     * <p>{@code 01 ABEND-DATA} at {@code app/cpy/CSMSG02Y.cpy:L21-L29} declares four fixed-width
     * fields and no separators, so the rendered form is their widths added together.
     */
    public static final int RECORD_LENGTH =
            ABEND_CODE_MAX_LENGTH + CULPRIT_MAX_LENGTH + REASON_MAX_LENGTH + MESSAGE_MAX_LENGTH;

    /** Lowest code point kept unchanged, the space. */
    private static final int FIRST_PRINTABLE_ASCII = 0x20;

    /** Highest code point kept unchanged, the tilde. */
    private static final int LAST_PRINTABLE_ASCII = 0x7E;

    /**
     * Sanitises all four text components.
     *
     * <p>This constructor throws nothing. A dead-letter record describes a failure that already
     * happened. A second failure raised while building it would suppress the dead-letter message,
     * and leave the broker redelivering the same message for ever.</p>
     */
    public DeadLetterMetadata {
        abendCode = sanitise(abendCode, ABEND_CODE_MAX_LENGTH);
        culprit = sanitise(culprit, CULPRIT_MAX_LENGTH);
        reason = sanitise(reason, REASON_MAX_LENGTH);
        message = sanitise(message, MESSAGE_MAX_LENGTH);
    }

    /**
     * Builds a record from the four text components.
     *
     * <p>Every component may be {@code null}, may be longer than its maximum, and may hold any
     * character. The constructor sanitises each one.</p>
     *
     * @param abendCode the failure code; may be {@code null}
     * @param culprit   the failing component; may be {@code null}
     * @param reason    the failure classification; may be {@code null}
     * @param message   the failure detail; may be {@code null}
     * @return a record whose four components are consistent
     */
    public static DeadLetterMetadata of(String abendCode, String culprit, String reason,
            String message) {
        return new DeadLetterMetadata(abendCode, culprit, reason, message);
    }

    /**
     * Builds a record whose {@code culprit} names the type of a failure.
     *
     * <p>This method reads the failure's simple class name and nothing else. Neither
     * {@code Throwable.getMessage()} nor any stack frame reaches the record. No card number, no
     * account identifier and no fragment of the rejected payload can travel in it.</p>
     *
     * @param abendCode the failure code; may be {@code null}
     * @param failure   the failure whose type names the culprit; may be {@code null}
     * @param reason    the failure classification, assembled by the caller; may be {@code null}
     * @param message   the failure detail; may be {@code null}
     * @return a record whose four components are consistent
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
     * @param value     the text the caller supplied; may be {@code null}
     * @param maxLength the widest text the component holds
     * @return at most {@code maxLength} printable ASCII characters
     */
    private static String sanitise(String value, int maxLength) {
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
        return kept.toString();
    }

    /**
     * Renders the four components in copybook order, padded to their declared widths.
     *
     * <p>This is the form a terminal consumer failure travels in. The value is bytes on the wire
     * rather than an event, which is what lets the dead-letter destination stay the dead-letter topic
     * of the stream the record arrived on: every governed event type is bound to one topic, so an
     * event-shaped value could only ever be addressed to that one topic. The envelope form below is
     * the other case, an outbox row this service gave up on, and it is bound to the shared
     * dead-letter topic.
     *
     * @return one printable ASCII record of exactly {@value #RECORD_LENGTH} characters
     */
    public String toFixedWidthRecord() {
        return fixedWidth(abendCode, ABEND_CODE_MAX_LENGTH)
                + fixedWidth(culprit, CULPRIT_MAX_LENGTH)
                + fixedWidth(reason, REASON_MAX_LENGTH)
                + fixedWidth(message, MESSAGE_MAX_LENGTH);
    }

    /** Pads one already-sanitized component with spaces to its declared width. */
    private static String fixedWidth(String value, int width) {
        return value + " ".repeat(width - value.length());
    }

    /**
     * Renders this metadata as the one dead-letter contract the platform publishes.
     *
     * <p>Six service modules declare a record of this name as their in-process carrier for the four
     * diagnostic values, and every one of them reaches a topic only through this method.
     *
     * <p>{@link DeadLetterEnvelope} in {@code libs/event-contracts} is that contract now. It
     * carries a version, it validates against {@code schemas/dead-letter-v1.json} through the same
     * serializer and deserializer every other event passes, and it holds no field of the failing
     * payload at all. This record stays as the in-process carrier for the four diagnostic values,
     * and this method is the only way it reaches a topic.
     *
     * <p>What the envelope adds is where the failing record was: topic, partition and offset. An
     * operator needs those to reach the record itself, and none of the three is derivable from the
     * diagnostics. The list of shortened components is left empty here, and that is exact rather
     * than lazy. This record bounds each diagnostic to the same width {@link DeadLetterEnvelope}
     * bounds it to, so every value handed over already fits and the envelope has nothing left to
     * shorten. A producer that bounds its diagnostics more narrowly
     * than this record does should build the envelope directly, so its own shortening is named.
     *
     * @param aggregateId     the eleven-digit account identifier of the failing record, and the
     *                        Kafka message key of the envelope
     * @param sourceTopic     the topic the failing record arrived on
     * @param sourcePartition the partition it arrived on
     * @param sourceOffset    its offset
     * @param failedEventId   the {@code eventId} of the failing record, or null when the record
     *                        could not be parsed far enough to carry one
     * @param failedEventType its {@code eventType}, or null for the same reason
     * @param attemptCount    how many delivery attempts were made before the record was dead
     *                        lettered
     * @return one schema-governed envelope carrying these diagnostics and no payload value
     * @throws IllegalArgumentException if {@code aggregateId} is not eleven decimal digits
     */
    public DeadLetterEnvelope toEnvelope(String aggregateId, String sourceTopic,
            int sourcePartition, long sourceOffset, String failedEventId, String failedEventType,
            int attemptCount) {
        return new DeadLetterEnvelope(
                EventEnvelope.of(DeadLetterEnvelope.EVENT_TYPE, aggregateId),
                abendCode, culprit, reason, message, sourceTopic, sourcePartition, sourceOffset,
                failedEventId, failedEventType, attemptCount, true, List.of());
    }
}
