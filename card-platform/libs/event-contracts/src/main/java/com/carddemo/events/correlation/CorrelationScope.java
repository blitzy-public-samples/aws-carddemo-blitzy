package com.carddemo.events.correlation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Headers;
import org.slf4j.MDC;
import tools.jackson.databind.JsonNode;

/**
 * Holds the correlation fields of one request or one delivery for as long as the thread is inside
 * it, and puts the thread back as it found it on the way out.
 *
 * <p>No COBOL ancestor. ADDITIVE, for the reason {@link EventCorrelation} records: one process per
 * unit of work needed no such context, and six services do.
 *
 * <p>Every field is written to the Mapped Diagnostic Context (MDC) of the calling thread. Structured
 * output renders each MDC entry as a top-level member, so a field set here becomes a queryable log
 * member on every line the thread writes, including lines written by libraries that know nothing
 * about this class.
 *
 * <p>{@link #close()} restores the value each key held on entry rather than clearing the key. A
 * listener container thread is reused for delivery after delivery, and a nested scope is what a
 * request that also handles an event would open, so clearing would drop a field the outer scope
 * still needs. A key that held nothing on entry is removed.
 *
 * <p>The intended use is one try-with-resources per request or per delivery:
 *
 * <pre>{@code
 * try (CorrelationScope _ = CorrelationScope.open()
 *         .withCorrelation(correlationId)
 *         .withCausation(causationId)
 *         .withEvent(event.eventId(), TransactionAuthorized.EVENT_TYPE)) {
 *     apply(event);
 * }
 * }</pre>
 *
 * <p>This class is not thread-safe and is not meant to be: one scope belongs to the thread that
 * opened it, and the values it writes are visible to that thread alone.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
public final class CorrelationScope implements AutoCloseable {

    /**
     * The value each key held when this scope wrote to it, in write order.
     *
     * <p>A {@code null} value records a key that held nothing, which {@link #close()} removes rather
     * than restores. The map holds one entry per key, so a key written twice in one scope is
     * restored to what it held before the first write.
     */
    private final Map<String, String> replaced = new LinkedHashMap<>();

    private CorrelationScope() {
    }

    /**
     * Opens a scope that has written nothing yet.
     *
     * @return the open scope, to be closed by the thread that opened it
     */
    public static CorrelationScope open() {
        return new CorrelationScope();
    }

    /**
     * Opens the scope of one delivery from the two header values it arrived with.
     *
     * <p>A header holding anything but one rendered identifier contributes nothing, so a producer
     * outside this platform cannot write a field of its own through either header.
     *
     * @param correlationHeader the bound value of {@link EventCorrelation#CORRELATION_ID_HEADER}:
     *                          bytes, text, or {@code null}
     * @param causationHeader   the bound value of {@link EventCorrelation#CAUSATION_ID_HEADER}:
     *                          bytes, text, or {@code null}
     * @return the open scope, to be closed by the thread that opened it
     */
    public static CorrelationScope forDelivery(Object correlationHeader, Object causationHeader) {
        return open()
                .withCorrelation(EventCorrelation.readHeaderValue(correlationHeader).orElse(null))
                .withCausation(EventCorrelation.readHeaderValue(causationHeader).orElse(null));
    }

    /**
     * Opens the scope of one delivery from the headers of the record itself.
     *
     * <p>The overload a listener that binds the whole {@link ConsumerRecord} uses. It reads the same
     * two headers {@link #forDelivery(Object, Object)} reads.
     *
     * @param headers the delivered headers, possibly {@code null}
     * @return the open scope, to be closed by the thread that opened it
     */
    public static CorrelationScope forDelivery(Headers headers) {
        return open()
                .withCorrelation(EventCorrelation
                        .read(headers, EventCorrelation.CORRELATION_ID_HEADER).orElse(null))
                .withCausation(EventCorrelation
                        .read(headers, EventCorrelation.CAUSATION_ID_HEADER).orElse(null));
    }

    /**
     * Names the event one delivery carries, whatever form the value took.
     *
     * <p>Three forms reach a listener of this platform. A record of the contract library implements
     * {@link CorrelatedEvent} and answers both components directly. A value bound as a checked JSON
     * tree carries them as properties, because the wire form is flat. Anything else, a tombstone
     * included, names nothing and leaves both fields absent.
     *
     * @param value the deserialized record value, possibly {@code null}
     * @return this scope
     */
    public CorrelationScope withHandledValue(Object value) {
        if (value instanceof CorrelatedEvent event) {
            return withHandledEvent(event.eventId(), event.eventType());
        }
        if (value instanceof JsonNode tree) {
            return withHandledEvent(
                    EventCorrelation.parse(tree.path(EventCorrelation.EVENT_ID_FIELD)
                            .stringValue(null)).orElse(null),
                    tree.path(EventCorrelation.EVENT_TYPE_FIELD).stringValue(null));
        }
        return this;
    }

    /**
     * Names the event being handled, and adopts its identifier as the correlation identifier when
     * the delivery carried none.
     *
     * <p>The fallback is the event identifier rather than a fresh value, so a redelivery of one
     * event is correlated with its earlier deliveries. A fresh value per attempt would give one
     * event as many correlation identifiers as it had attempts.
     *
     * @param eventId   the identifier of the handled event
     * @param eventType the type of the handled event
     * @return this scope
     */
    public CorrelationScope withHandledEvent(UUID eventId, String eventType) {
        withEvent(eventId, eventType);
        if (eventId != null && EventCorrelation.currentCorrelationId().isEmpty()) {
            withCorrelation(eventId);
        }
        return this;
    }

    /**
     * Writes the root correlation identifier of the unit of work this thread is serving.
     *
     * @param correlationId the identifier, or {@code null} to leave the field as it stands
     * @return this scope
     */
    public CorrelationScope withCorrelation(UUID correlationId) {
        return with(EventCorrelation.CORRELATION_ID_FIELD, text(correlationId));
    }

    /**
     * Writes the identifier of the event that caused the one this thread is handling.
     *
     * @param causationId the identifier, or {@code null} when the handled event begins a chain
     * @return this scope
     */
    public CorrelationScope withCausation(UUID causationId) {
        return with(EventCorrelation.CAUSATION_ID_FIELD, text(causationId));
    }

    /**
     * Writes the identifier and the type of the event this thread is handling.
     *
     * @param eventId   the identifier of the handled event, or {@code null} to leave the field as it
     *                  stands
     * @param eventType the type of the handled event, or {@code null} to leave the field as it
     *                  stands
     * @return this scope
     */
    public CorrelationScope withEvent(UUID eventId, String eventType) {
        return with(EventCorrelation.EVENT_ID_FIELD, text(eventId))
                .with(EventCorrelation.EVENT_TYPE_FIELD, eventType);
    }

    /**
     * Writes the transaction one delivery or one request concerns.
     *
     * @param transactionId the sixteen-character transaction identifier, or {@code null} to leave
     *                      the field as it stands
     * @return this scope
     */
    public CorrelationScope withTransaction(String transactionId) {
        return with(EventCorrelation.TRANSACTION_ID_FIELD, transactionId);
    }

    /**
     * Writes one field, remembering what the key held so {@link #close()} can put it back.
     *
     * <p>A {@code null} or blank value writes nothing at all, so a field this platform has no value
     * for is absent from the output rather than present and empty.
     *
     * @param key   the MDC key, one of the field names {@link EventCorrelation} declares
     * @param value the value to write, or {@code null} to leave the key as it stands
     * @return this scope
     * @throws NullPointerException when {@code key} is {@code null}
     */
    public CorrelationScope with(String key, String value) {
        Objects.requireNonNull(key, "key must be present");
        if (value == null || value.isBlank()) {
            return this;
        }
        if (!replaced.containsKey(key)) {
            replaced.put(key, MDC.get(key));
        }
        MDC.put(key, value);
        return this;
    }

    /**
     * Puts every key this scope wrote back to the value it held on entry.
     *
     * <p>Called by try-with-resources. Calling it twice is harmless: the second call finds nothing
     * left to restore.
     */
    @Override
    public void close() {
        for (Map.Entry<String, String> entry : replaced.entrySet()) {
            if (entry.getValue() == null) {
                MDC.remove(entry.getKey());
            } else {
                MDC.put(entry.getKey(), entry.getValue());
            }
        }
        replaced.clear();
    }

    /**
     * Renders one identifier, or answers {@code null} for an absent one.
     *
     * @param value the identifier, possibly {@code null}
     * @return the thirty-six-character rendering, or {@code null}
     */
    private static String text(UUID value) {
        return value == null ? null : value.toString();
    }
}
