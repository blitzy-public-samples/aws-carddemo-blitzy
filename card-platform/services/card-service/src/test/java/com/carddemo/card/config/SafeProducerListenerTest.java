package com.carddemo.card.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Asserts a failed send records its destination and nothing a producer sent.
 *
 * <p>The listener under test replaces {@code LoggingProducerListener}, which writes the message key
 * and the first hundred characters of the payload on every failure. This class plants a sentinel in
 * the key and a second sentinel in the value, drives the failure path, and reads the emitted line
 * back through a recorder attached to the {@code com.carddemo} logger.
 *
 * <p>Two things are asserted that a message search alone would miss. The throwable is asserted
 * absent, because a throwable attached to a log event is rendered by the appender rather than by the
 * message and a search of the formatted message would never see it. And the line is asserted to be
 * present at all, so a listener that silently logged nothing could not pass by emitting no text for
 * a search to fail on.
 *
 * <p>Every test runs in memory. No broker, no container and no connection is involved.
 */
@DisplayName("A failed send of the card service names its destination and no record content")
class SafeProducerListenerTest {

    /** Logger the recorder attaches to, which is the package every service logs under. */
    private static final String SERVICE_LOGGER = "com.carddemo";

    /** Destination of the failed send. */
    private static final String TOPIC = "carddemo.contract-topic";

    /** Partition of the failed send. */
    private static final int PARTITION = 3;

    /** A sentinel in the shape of an account identifier, which is what a key holds here. */
    private static final String SENTINEL_KEY = "00000000042";

    /** A sentinel amount inside the payload, in the shape an event carries. */
    private static final String SENTINEL_AMOUNT = "1234.56";

    /** A sentinel payload, shaped like an event of this platform. */
    private static final String SENTINEL_VALUE =
            "{\"aggregateId\":\"" + SENTINEL_KEY + "\",\"amount\":\"" + SENTINEL_AMOUNT + "\"}";

    /** Records the lines the listener writes. */
    private ListAppender<ILoggingEvent> recorder;

    /** The logger the recorder is attached to. */
    private Logger serviceLogger;

    @BeforeEach
    void attachRecorder() {
        recorder = new ListAppender<>();
        recorder.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        recorder.start();
        serviceLogger = (Logger) LoggerFactory.getLogger(SERVICE_LOGGER);
        serviceLogger.addAppender(recorder);
    }

    @AfterEach
    void detachRecorder() {
        serviceLogger.detachAppender(recorder);
        recorder.stop();
    }

    @Test
    @DisplayName("the line names the topic, the partition and the failure type only")
    void theLineNamesTheDestinationAndTheFailureTypeOnly() {
        new SafeProducerListener<String, String>().onError(failedRecord(),
                new RecordMetadata(new TopicPartition(TOPIC, PARTITION), 0L, 0, 0L, 0, 0),
                new IllegalStateException("broker refused subject " + SENTINEL_KEY));

        ILoggingEvent event = onlyEvent();
        String line = event.getFormattedMessage();

        assertAll(
                () -> assertEquals(Level.ERROR, event.getLevel(),
                        "a send that failed is an error rather than a warning"),
                () -> assertTrue(line.contains(TOPIC), "the line names the destination topic"),
                () -> assertTrue(line.contains(String.valueOf(PARTITION)),
                        "the line names the partition"),
                () -> assertTrue(line.contains(IllegalStateException.class.getName()),
                        "the line names the failure type: " + line),
                () -> assertFalse(line.contains(SENTINEL_KEY),
                        "the key is an account identifier and reached the log: " + line),
                () -> assertFalse(line.contains(SENTINEL_AMOUNT),
                        "the payload reached the log: " + line),
                () -> assertNull(event.getThrowableProxy(),
                        "no throwable is attached, so no stack trace and no exception message is"
                                + " rendered by the appender"));
    }

    @Test
    @DisplayName("the cause chain is rendered by type and cut at three")
    void theCauseChainIsRenderedByTypeAndCut() {
        Throwable fourth = new ArithmeticException("deepest " + SENTINEL_AMOUNT);
        Throwable third = new IllegalArgumentException("third", fourth);
        Throwable second = new UnsupportedOperationException("second", third);

        new SafeProducerListener<String, String>().onError(failedRecord(), null,
                new IllegalStateException("first", second));

        String line = onlyEvent().getFormattedMessage();

        assertAll(
                () -> assertTrue(line.contains(IllegalStateException.class.getName()),
                        "the failure type is named"),
                () -> assertTrue(line.contains(UnsupportedOperationException.class.getName()),
                        "the first cause is named"),
                () -> assertTrue(line.contains(IllegalArgumentException.class.getName()),
                        "the second cause is named"),
                () -> assertFalse(line.contains(ArithmeticException.class.getName()),
                        "the chain is cut at three, and this fourth type is beyond it: " + line),
                () -> assertFalse(line.contains("deepest"),
                        "no message of any cause is rendered: " + line),
                () -> assertFalse(line.contains(SENTINEL_AMOUNT),
                        "a value quoted by a cause message reached the log: " + line));
    }

    @Test
    @DisplayName("a send the broker never accepted still names where it was going")
    void aSendTheBrokerNeverAcceptedStillNamesWhereItWasGoing() {
        new SafeProducerListener<String, String>().onError(
                new ProducerRecord<>(TOPIC, SENTINEL_KEY, SENTINEL_VALUE), null,
                new IllegalStateException("no broker"));

        String line = onlyEvent().getFormattedMessage();

        assertAll(
                () -> assertTrue(line.contains(TOPIC),
                        "an unreachable broker assigns no metadata, and the topic is still known"),
                () -> assertTrue(line.contains(SafeProducerListener.NOT_SUPPLIED),
                        "no partition was assigned, and the line says so rather than inventing"
                                + " one: " + line),
                () -> assertFalse(line.contains(SENTINEL_KEY), "the key reached the log: " + line));
    }

    /**
     * Builds the record whose send failed, carrying a sentinel key and a sentinel payload.
     *
     * @return the record, addressed to the contract topic and partition
     */
    private static ProducerRecord<String, String> failedRecord() {
        return new ProducerRecord<>(TOPIC, PARTITION, SENTINEL_KEY, SENTINEL_VALUE);
    }

    /**
     * Returns the one event the listener emitted.
     *
     * @return that event
     */
    private ILoggingEvent onlyEvent() {
        List<ILoggingEvent> events = List.copyOf(recorder.list);
        assertEquals(1, events.size(),
                "one failed send writes exactly one line, and these were written: "
                        + events.stream().map(ILoggingEvent::getFormattedMessage).toList());
        return events.getFirst();
    }
}
