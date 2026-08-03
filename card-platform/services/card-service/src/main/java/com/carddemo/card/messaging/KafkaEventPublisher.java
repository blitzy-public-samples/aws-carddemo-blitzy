package com.carddemo.card.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Sends card events to an Apache Kafka broker. This class holds the only Kafka type the card
 * service imports.
 *
 * <p>ADDITIVE. No COBOL program defines this class. The nearest source construct is the
 * {@code WIRTE-JOBSUB-TDQ} paragraph at {@code app/cbl/CORPT00C.cbl:L515-L523}, which hands one
 * record to a Customer Information Control System (CICS) transient data queue.
 *
 * <p>The card row and the outbox row commit in one local transaction, and the outbox relay calls
 * this class afterwards in a separate transaction, never from inside request handling.
 *
 * <p>The card service keeps this adapter in {@code messaging}, and its peer services keep producer
 * wiring in {@code config}.
 *
 * <p>Another event bus needs one more implementation of {@link EventPublisherPort}, and a new
 * consumer of a card event needs no change in this package.
 *
 * <p>Design decisions for this class are recorded in {@code card-platform/docs/decision-log.md}.
 */
@Component
public class KafkaEventPublisher implements EventPublisherPort {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;

    /**
     * Spring Boot supplies the producer template from the {@code spring.kafka.producer} properties,
     * with a string serializer on the key and on the value.
     *
     * @param kafkaTemplate the template that sends every card event to the broker
     */
    public KafkaEventPublisher(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Sends {@code payload} to {@code topic} unchanged and waits for the broker acknowledgement. A
     * broker failure arrives as an unchecked {@code java.util.concurrent.CompletionException}.
     *
     * <p>Kafka partitions on {@code key}, which keeps every event for one account in order.
     */
    @Override
    public void publish(String topic, String key, String payload) {
        log.debug("Publishing card event to topic {} with key {}", topic, key);
        kafkaTemplate.send(topic, key, payload).join();
    }
}
