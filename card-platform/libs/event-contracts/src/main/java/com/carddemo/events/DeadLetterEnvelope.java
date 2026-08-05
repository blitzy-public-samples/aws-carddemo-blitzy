package com.carddemo.events;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The one shape every dead-letter topic on this platform carries.
 *
 * <p>The routing has no COBOL ancestor. The four diagnostic fields reproduce the abend
 * reporting record {@code 01 ABEND-DATA} at {@code app/cpy/CSMSG02Y.cpy:L21-L29} field for field and
 * width for width: {@code ABEND-CODE PIC X(4)} at L22, {@code ABEND-CULPRIT PIC X(8)} at L24,
 * {@code ABEND-REASON PIC X(50)} at L26 and {@code ABEND-MSG PIC X(72)} at L28.
 * {@code app/cbl/CBTRN02C.cbl:L707-L711} fills that record and calls the language environment abend
 * service. The dead-letter routing itself has no ancestor, because the source answers a failure by
 * ending the run.
 *
 * <p>This record exists because five services previously declared the same four fields as five
 * unrelated types with no shared document. One handler could therefore read no dead-letter topic but
 * its own. Every service now converts its local diagnostics into this envelope, and
 * {@code schemas/dead-letter-v1.json} is the single contract a handler validates against.
 *
 * <p><strong>The envelope never carries the record that failed.</strong> A failed payload can hold a
 * full Primary Account Number, a cardholder name and an amount, and a dead-letter topic is read by
 * hand, long after the failure, by whoever is on call. What travels instead is enough to find the
 * record where it already sits under the access controls its own topic carries: the topic, the
 * partition, the offset, and the identifier and type of the failing event when those could be read.
 * A stack trace, an exception message and a raw payload all stay out.
 *
 * <p>Every text component is bounded and sanitised by the canonical constructor, which throws
 * nothing. A dead letter describes a failure that already happened, so a second failure raised while
 * building one would suppress the dead letter and leave the broker redelivering for ever. A
 * {@code null} text component becomes the empty string, matching the {@code VALUE SPACES} clause each
 * source field carries, and every code point outside printable American Standard Code for
 * Information Interchange (ASCII) becomes one {@link #SUBSTITUTE_CHARACTER}, so no control character
 * and no line break reaches the topic. Because every retained character is one code unit, the length
 * cap cannot split a surrogate pair.
 *
 * <p>The wire form is flat, exactly as it is for every event of this module: the five envelope fields
 * sit beside the diagnostic fields in one JSON object.
 *
 * @param envelope        the five fields every event of this module carries. {@code eventType} is
 *                        {@link #EVENT_TYPE} and {@code aggregateId} is the account identifier the
 *                        failing record belonged to, so a dead letter lands on the partition of the
 *                        account it concerns
 * @param abendCode       the failure code, at most {@value #ABEND_CODE_MAX_LENGTH} characters
 * @param culprit         what failed, at most {@value #CULPRIT_MAX_LENGTH} characters
 * @param reason          the failure classification, at most {@value #REASON_MAX_LENGTH} characters
 * @param message         the operator-facing detail, at most {@value #MESSAGE_MAX_LENGTH} characters
 * @param sourceTopic     the topic the failing record arrived on
 * @param sourcePartition the partition the failing record arrived on
 * @param sourceOffset    the offset of the failing record, which is how an operator reaches the
 *                        record itself without a copy of it travelling here
 * @param failedEventId   the {@code eventId} of the failing record, or {@code null} when the record
 *                        could not be parsed at all
 * @param failedEventType the {@code eventType} of the failing record, or {@code null} when it could
 *                        not be read
 * @param attemptCount    how many delivery attempts were made before the consumer gave up, at least
 *                        one
 * @param terminal        {@code true} when no further automatic attempt will be made
 * @param truncatedComponents names of the text components the constructor shortened, in component
 *                        order, and empty when it shortened none. The constructor derives this
 *                        component and replaces any value a caller supplies. It stays out of the
 *                        wire form, because {@code schemas/dead-letter-v1.json} closes its property
 *                        set and this component is local bookkeeping rather than contract
 */
public record DeadLetterEnvelope(@JsonUnwrapped EventEnvelope envelope, String abendCode,
        String culprit, String reason, String message, String sourceTopic, int sourcePartition,
        long sourceOffset, String failedEventId, String failedEventType, int attemptCount,
        boolean terminal, @JsonIgnore List<String> truncatedComponents) {

    /** The {@code eventType} this record carries, and the {@code const} its schema declares. */
    public static final String EVENT_TYPE = "DeadLetterEnvelope";

    /** The schema document this record serializes against. */
    public static final String SCHEMA_RESOURCE = "schemas/dead-letter-v1.json";

    /** Widest {@code abendCode}, from {@code ABEND-CODE PIC X(4)} at {@code app/cpy/CSMSG02Y.cpy:L22}. */
    public static final int ABEND_CODE_MAX_LENGTH = 4;

    /** Widest {@code culprit}, from {@code ABEND-CULPRIT PIC X(8)} at {@code app/cpy/CSMSG02Y.cpy:L24}. */
    public static final int CULPRIT_MAX_LENGTH = 8;

    /** Widest {@code reason}, from {@code ABEND-REASON PIC X(50)} at {@code app/cpy/CSMSG02Y.cpy:L26}. */
    public static final int REASON_MAX_LENGTH = 50;

    /** Widest {@code message}, from {@code ABEND-MSG PIC X(72)} at {@code app/cpy/CSMSG02Y.cpy:L28}. */
    public static final int MESSAGE_MAX_LENGTH = 72;

    /** Widest topic name, the ceiling Apache Kafka itself applies. */
    public static final int SOURCE_TOPIC_MAX_LENGTH = 249;

    /** Widest {@code failedEventType}, the width its schema declares. */
    public static final int FAILED_EVENT_TYPE_MAX_LENGTH = 64;

    /** Widest {@code failedEventId}, the length of a canonical Universally Unique Identifier. */
    public static final int FAILED_EVENT_ID_MAX_LENGTH = 36;

    /**
     * Replacement for any character outside printable ASCII. Sanitising first is what makes the
     * length cap safe: every retained character is one code unit.
     */
    public static final char SUBSTITUTE_CHARACTER = '.';

    /** The code point of the space, the lowest one kept unchanged. */
    private static final int FIRST_PRINTABLE_ASCII = 0x20;

    /** The code point of the tilde, the highest one kept unchanged. */
    private static final int LAST_PRINTABLE_ASCII = 0x7E;

    /** How many text components the constructor sanitises. */
    private static final int TEXT_COMPONENT_COUNT = 7;

    /**
     * Sanitises every text component, floors the counters, and derives
     * {@link #truncatedComponents()}.
     *
     * <p>This constructor throws only when {@code envelope} is absent or carries the wrong
     * {@code eventType}, both of which are programming faults in the producer rather than
     * consequences of the failure being reported. Every other component is corrected in place: a
     * negative partition, offset or attempt count becomes its floor, and every text component is
     * shortened and sanitised.
     *
     * @throws NullPointerException     when {@code envelope} is {@code null}
     * @throws IllegalArgumentException when the envelope's {@code eventType} is not
     *                                  {@link #EVENT_TYPE}
     */
    public DeadLetterEnvelope {
        Objects.requireNonNull(envelope, "envelope must be present");
        if (!EVENT_TYPE.equals(envelope.eventType())) {
            throw new IllegalArgumentException("eventType must be \"" + EVENT_TYPE
                    + "\" and the supplied envelope names another type");
        }

        List<String> shortened = new ArrayList<>(TEXT_COMPONENT_COUNT);
        abendCode = sanitise("abendCode", abendCode, ABEND_CODE_MAX_LENGTH, shortened);
        culprit = sanitise("culprit", culprit, CULPRIT_MAX_LENGTH, shortened);
        reason = sanitise("reason", reason, REASON_MAX_LENGTH, shortened);
        message = sanitise("message", message, MESSAGE_MAX_LENGTH, shortened);
        sourceTopic = sanitise("sourceTopic", sourceTopic, SOURCE_TOPIC_MAX_LENGTH, shortened);

        failedEventId = failedEventId == null ? null
                : sanitise("failedEventId", failedEventId, FAILED_EVENT_ID_MAX_LENGTH, shortened);
        failedEventType = failedEventType == null ? null
                : sanitise("failedEventType", failedEventType, FAILED_EVENT_TYPE_MAX_LENGTH,
                        shortened);

        sourcePartition = Math.max(sourcePartition, 0);
        sourceOffset = Math.max(sourceOffset, 0L);
        attemptCount = Math.max(attemptCount, 1);

        // A caller may have shortened a value before handing it over, and a producer that bounds
        // its own diagnostics to a narrower width than this record does exactly that. Replacing the
        // supplied list with the list computed above would drop that, and then a value shortened
        // upstream would arrive here already inside its bound and be reported as untouched. What an
        // operator loses in that case is the difference between a shortened message and a wrong
        // one, which is the whole reason the list exists. The two are joined instead, in the
        // order the components are declared and with no component named twice.
        Set<String> joined = new LinkedHashSet<>(shortened);
        if (truncatedComponents != null) {
            for (String component : truncatedComponents) {
                if (component != null && !component.isBlank()) {
                    joined.add(component);
                }
            }
        }
        truncatedComponents = List.copyOf(joined);
    }

    /**
     * Builds an envelope for a record a consumer read but could not process.
     *
     * <p>The failure supplies its type name and nothing else. Neither
     * {@link Throwable#getMessage()} nor any stack frame reaches the record, so a failure carrying a
     * card number, an account identifier or a fragment of the rejected payload cannot leak through
     * the dead-letter topic.
     *
     * @param aggregateId     the eleven-digit account identifier of the failing record, and the
     *                        Kafka message key of this envelope
     * @param abendCode       the failure code; may be {@code null}
     * @param failure         the failure whose type names the culprit; may be {@code null}
     * @param reason          the failure classification; may be {@code null}
     * @param message         the operator-facing detail; may be {@code null}
     * @param sourceTopic     the topic the failing record arrived on; may be {@code null}
     * @param sourcePartition the partition the failing record arrived on
     * @param sourceOffset    the offset of the failing record
     * @param failedEventId   the {@code eventId} of the failing record, or {@code null}
     * @param failedEventType the {@code eventType} of the failing record, or {@code null}
     * @param attemptCount    how many delivery attempts were made
     * @return a terminal envelope whose components are consistent
     * @throws IllegalArgumentException when {@code aggregateId} is not eleven decimal digits
     */
    public static DeadLetterEnvelope fromFailure(String aggregateId, String abendCode,
            Throwable failure, String reason, String message, String sourceTopic,
            int sourcePartition, long sourceOffset, String failedEventId, String failedEventType,
            int attemptCount) {
        String culprit = failure == null ? "" : failure.getClass().getSimpleName();
        return new DeadLetterEnvelope(EventEnvelope.of(EVENT_TYPE, aggregateId), abendCode, culprit,
                reason, message, sourceTopic, sourcePartition, sourceOffset, failedEventId,
                failedEventType, attemptCount, true, List.of());
    }

    /**
     * Returns the account identifier this dead letter concerns, which is the Kafka message key.
     *
     * @return the {@code aggregateId} of {@link #envelope()}, eleven digits
     */
    public String aggregateId() {
        return envelope.aggregateId();
    }

    /**
     * Returns a rendering that names the failure and no value from the failing record.
     *
     * <p>The four diagnostic components are already bounded to printable ASCII and already free of
     * payload values, so they appear here; the source coordinates appear because they are how an
     * operator reaches the record itself. No field of the failing payload is held by this record at
     * all, so none can appear.
     *
     * @return the failure code, culprit, reason, source coordinates and delivery state
     */
    @Override
    public String toString() {
        return "DeadLetterEnvelope[eventId=" + envelope.eventId()
                + ", abendCode=" + abendCode
                + ", culprit=" + culprit
                + ", reason=" + reason
                + ", sourceTopic=" + sourceTopic
                + ", sourcePartition=" + sourcePartition
                + ", sourceOffset=" + sourceOffset
                + ", failedEventId=" + failedEventId
                + ", failedEventType=" + failedEventType
                + ", attemptCount=" + attemptCount
                + ", terminal=" + terminal + "]";
    }

    /**
     * Maps one text component onto the text it will hold.
     *
     * <p>The scan stops once {@code maxLength} characters are in hand, so the work does not grow
     * with the size of an over-long argument, and an attempt to write a very long value into a log
     * costs nothing.
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
