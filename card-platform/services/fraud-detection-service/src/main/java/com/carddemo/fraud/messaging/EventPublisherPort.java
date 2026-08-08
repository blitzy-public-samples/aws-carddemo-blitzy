package com.carddemo.fraud.messaging;

import java.util.concurrent.CompletionStage;

/**
 * The fraud detection service publishes every event through this interface.
 *
 * <p>No COBOL program and no copybook defines this contract, and this service has no source ancestor
 * at all: nothing in the repository scores risk, checks velocity or evaluates rules. The locator below
 * is a reference to the one asynchronous handoff the source does contain, not an ancestor of this
 * service.
 *
 * <p>Source reference: the {@code WIRTE-JOBSUB-TDQ} paragraph at
 * {@code app/cbl/CORPT00C.cbl:L515-L523} writes one record to a Customer Information Control System
 * (CICS) transient data queue, and a separate job reads that record later.
 *
 * <p><b>Why this interface exists.</b> It is the one swappable seam this platform declares. Every
 * other producing service already published through an interface of this name, and this one held a
 * {@code KafkaTemplate} directly, so the broker choice was a compile-time dependency of its relay
 * rather than a configuration decision. Substituting a managed event service — which the platform
 * requirements keep open — meant editing the relay here and only the adapter elsewhere. It is now one
 * new implementation of this interface and no other change, in this service as in the other four.
 *
 * <p><b>What travels through it, and why this signature differs from its siblings.</b> The
 * authorization, ledger, account and card services store a serialized payload in {@code outbox_event}
 * and hand that text to their port. This service hands over the event <em>record</em>, because its
 * producer is built with {@code JsonSchemaValidatingSerializer} and that serializer is where the
 * payload is written, validated against the schema document its event type names, and checked against
 * the topic it is being sent to. Taking text here would mean serializing twice and validating in two
 * places.
 *
 * <p>A caller publishes after the local transaction that recorded the assessment has committed, so
 * consuming an authorization event publishes nothing.
 *
 * <p>A call that cannot be attempted at all throws before any send is started. A send that was started
 * reports on the stage it returned, so a caller that has to know the outcome waits on that stage and
 * owns the bound it waits under — {@code outbox/OutboxRelay} waits under the remaining budget of one
 * sweep.
 */
public interface EventPublisherPort {

    /**
     * Pattern every {@code aggregateId} argument matches: exactly eleven decimal digits, the width of
     * {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}. The same pattern constrains
     * {@code aggregateId} in every event schema under
     * {@code card-platform/libs/event-contracts/src/main/resources/schemas}.
     */
    String AGGREGATE_ID_PATTERN = "^[0-9]{11}$";

    /**
     * Publishes one event to one topic, keyed on the aggregate the event belongs to.
     *
     * <p>The implementation derives the broker message key from {@code aggregateId} and takes no
     * separate key argument, so the key and the payload cannot name different accounts. It checks
     * {@code aggregateId} against {@link #AGGREGATE_ID_PATTERN} and rejects any other value.
     *
     * <p>The payload is bound to {@code topic} and validated against the schema document its event type
     * names before it reaches the broker. For this service both happen inside the configured
     * serializer, which {@code config/KafkaProducerConfig} builds with one topic override per event
     * type, so an event sent to a topic its type is not bound to is refused rather than delivered.
     *
     * <p>A rejection message holds a JSON pointer, a broken keyword, an event type, a topic name or a
     * length, and never a value read from the payload. A caller that logs a rejection therefore records
     * no Primary Account Number, no card token and no account identifier.
     *
     * @param topic       the destination topic name, which the caller reads from configuration
     * @param aggregateId the eleven-digit account identifier the event belongs to, and the value the
     *                    payload carries in its own {@code aggregateId} field. Callers pass all eleven
     *                    characters, leading zeros included
     * @param event       the event record, one of the two this service publishes or the shared
     *                    dead-letter envelope
     * @return the stage the broker acknowledgement completes, which fails when the broker refuses the
     *         send or when the serializer refuses the payload
     * @throws IllegalArgumentException when {@code topic} or {@code event} is absent, or when
     *                                  {@code aggregateId} does not match
     *                                  {@link #AGGREGATE_ID_PATTERN}
     */
    CompletionStage<Void> publish(String topic, String aggregateId, Object event);
}
