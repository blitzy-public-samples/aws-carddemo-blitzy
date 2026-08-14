package com.carddemo.authorization.config;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.support.ProducerListener;

/**
 * Records a failed send by destination and failure type, and never by key or payload.
 *
 * <p>This class exists to displace {@code LoggingProducerListener}, which every
 * {@link org.springframework.kafka.core.KafkaTemplate} installs unless told otherwise. That listener
 * writes the message key and the first hundred characters of the payload into the log line, and on
 * this platform the key is an account identifier while the payload is an event carrying a masked
 * card number, a merchant category and an amount. A send failure is exactly the moment a broker is
 * unreachable and every retry writes another line, so the default turns one outage into a durable
 * copy of production traffic in whatever retains the logs. That is CWE-532.
 *
 * <p>What is recorded instead is the topic, the partition and the type of the failure with the types
 * of its causes. All three are code or configuration rather than data: the topic is one this service
 * declares, the partition is a number, and a type names a class. The record itself is read for
 * nothing beyond its topic and partition, so a field added to an event later cannot leak through
 * this path either.
 *
 * <p>Nothing a reader needs is lost. The event stays on its outbox row until a send succeeds, so the
 * payload sits in the database rather than nowhere, and the relay counts the failure. A stack trace
 * is not written because a stack trace renders the exception message, and a message quotes what
 * caused the failure: a broker address, the subject a topic refused, or a serializer's view of the
 * value it could not write.
 *
 * <p>Decisions: {@code card-platform/docs/decision-log.md}.
 *
 * @param <K> key type of the template this listener is installed on
 * @param <V> value type of the template this listener is installed on
 */
final class SafeProducerListener<K, V> implements ProducerListener<K, V> {

    /** Named for this class, so a failed send is attributable without naming a record. */
    private static final Logger LOG = LoggerFactory.getLogger(SafeProducerListener.class);

    /** Causes rendered into one failure line before the chain is cut. */
    private static final int FAILURE_TYPE_DEPTH = 3;

    /** Reported in place of a value neither the record nor the broker supplied. */
    static final String NOT_SUPPLIED = "not supplied";

    /**
     * Records one failed send.
     *
     * <p>{@code metadata} is null when the failure happened before the broker accepted the record,
     * which is the ordinary case for an unreachable broker, so the partition is read from the record
     * and reported as unsupplied when the record names none either.
     *
     * <p>Reported at warning rather than error, and it is the one line a failed attempt writes. An
     * attempt is not a loss: the event stays on its outbox row and the relay attempts it again, so a
     * level that says otherwise trains an operator to ignore the level. A send this service gives up
     * on is reported once at error by whichever component gave up, which is the relay for an outbox
     * row and the recoverer for a consumed record.
     *
     * @param record   the record that could not be sent, read for its topic and partition alone
     * @param metadata the broker metadata, or null when the send never reached the broker
     * @param failure  the failure the producer raised
     */
    @Override
    public void onError(ProducerRecord<K, V> record, RecordMetadata metadata, Exception failure) {
        LOG.warn("A send to topic {} partition {} failed and will be attempted again. The failure"
                        + " was {}. Neither the key nor the value is recorded, because the key is an"
                        + " account identifier and the value is an event.",
                topicOf(record, metadata), partitionOf(record, metadata), failureType(failure));
    }

    /**
     * Returns the topic the failed send was addressed to.
     *
     * @param record   the failed record, or null
     * @param metadata the broker metadata, or null
     * @return the topic, or the unsupplied marker when neither names one
     */
    private static String topicOf(ProducerRecord<?, ?> record, RecordMetadata metadata) {
        if (record != null && record.topic() != null) {
            return record.topic();
        }
        return metadata == null ? NOT_SUPPLIED : metadata.topic();
    }

    /**
     * Returns the partition the failed send was addressed to.
     *
     * @param record   the failed record, or null
     * @param metadata the broker metadata, or null
     * @return the partition as text, or the unsupplied marker when neither names one
     */
    private static String partitionOf(ProducerRecord<?, ?> record, RecordMetadata metadata) {
        if (record != null && record.partition() != null) {
            return String.valueOf(record.partition());
        }
        return metadata == null ? NOT_SUPPLIED : String.valueOf(metadata.partition());
    }

    /**
     * Renders one failure as its type and the types of its causes, and never as its message.
     *
     * <p>A type is code and safe to record. A message is not: it quotes the broker address, the
     * subject a topic refused, or the value a serializer could not write. The chain is bounded
     * because a wrapped failure nests and one log line is not the place to render all of it.
     *
     * @param failure the failure the producer raised, possibly null
     * @return the type chain as text, never null and never a message
     */
    private static String failureType(Throwable failure) {
        if (failure == null) {
            return NOT_SUPPLIED;
        }
        StringBuilder types = new StringBuilder();
        Throwable current = failure;
        for (int depth = 0; current != null && depth < FAILURE_TYPE_DEPTH; depth++) {
            if (depth > 0) {
                types.append(" caused by ");
            }
            types.append(current.getClass().getName());
            current = current.getCause() == current ? null : current.getCause();
        }
        return types.toString();
    }
}
