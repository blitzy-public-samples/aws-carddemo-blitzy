package com.carddemo.authorization.messaging;

import com.carddemo.events.EventEnvelope;
import java.util.concurrent.CompletionStage;

/**
 * Publishes one serialized event payload to one topic on the event bus.
 *
 * <p>This interface has no COBOL ancestor. No program, copybook, or job in the CardDemo
 * source declares an event bus or a publish abstraction.
 *
 * <p>A different event bus needs one more implementation of this interface.
 */
public interface EventPublisherPort {

    /**
     * Pattern every {@code aggregateId} argument matches, which is the pattern of the Kafka message
     * key. Every event this platform publishes uses the eleven decimal digits of
     * {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}.
     *
     * <p>A second, sixteen-character form is admitted and no producer writes it. One released
     * document declares it, {@code schemas/transaction-declined-v2.json}, and
     * {@code contracts/released-contracts.json} records that document as retained rather than
     * published. The form stays admissible because a record already on a topic has to stay readable.
     *
     * <p>The value is {@link EventEnvelope#AGGREGATE_KEY_PATTERN}, so the port, the envelope and the
     * {@code aggregate_id} column all admit exactly the same two forms.
     */
    String MESSAGE_KEY_PATTERN = EventEnvelope.AGGREGATE_KEY_PATTERN;

    /**
     * The same pattern as {@link #MESSAGE_KEY_PATTERN}, under the name that reads from the payload
     * side rather than the broker side. Both names are read across this service.
     */
    String AGGREGATE_ID_PATTERN = MESSAGE_KEY_PATTERN;

    /**
     * Publishes one payload to one topic, keyed on the aggregate the payload belongs to, and answers
     * with the stage the broker acknowledgement completes.
     *
     * <p>A broker keeps message order inside a single partition, and the message key selects the
     * partition. The implementation derives that key from {@code aggregateId} and takes no
     * separate key argument, so the key and the payload cannot name different aggregates.
     *
     * <p>An implementation checks {@code aggregateId} against {@link #MESSAGE_KEY_PATTERN} and
     * rejects any other value. Every check an implementation performs runs before any send starts and
     * reports its refusal by throwing, so a caller can tell a payload it must not retry from a broker
     * that did not answer.
     *
     * <p>A send that started reports its outcome through the returned stage and never by throwing.
     * The caller owns the wait, because the caller is the one holding the database locks and the
     * scheduled thread while the broker thinks. An implementation still bounds the stage itself, so a
     * broker that never answers cannot leave one caller waiting for ever.
     *
     * <p>Every implementation binds the payload to {@code topic} before it sends. It reads
     * {@code eventType} from the payload envelope, looks that type up in
     * {@code com.carddemo.events.serde.EventContracts}, and rejects the call when the type does not
     * belong on {@code topic}. The event type therefore has one source, the payload, and neither an
     * approval nor a decline can reach the topic the other travels on.
     *
     * <p>Every implementation also validates the payload against the versioned schema document that
     * event type names, and rejects a payload that fails it.
     *
     * <p>A rejection message holds a JSON pointer, a broken keyword, an event type, a topic name or
     * a length, and never a value read from the payload. A caller that logs a rejection therefore
     * records no Primary Account Number (PAN) and no account identifier.
     *
     * @param topic       the destination topic name, which the caller reads from configuration
     * @param aggregateId the value the payload carries in its own {@code aggregateId} field:
     *                    eleven account digits for every event this platform publishes, or the
     *                    retained sixteen-character form no producer writes
     * @param payload     one event, serialized as JavaScript Object Notation (JSON) before the
     *                    call
     * @return the stage the broker acknowledgement completes, which fails when the broker refuses the
     *         send or does not answer inside the bound the implementation applies
     * @throws IllegalArgumentException when an argument is absent, when {@code aggregateId} misses
     *         {@link #MESSAGE_KEY_PATTERN}, when the payload declares an event type that does not
     *         belong on {@code topic}, or when the payload fails the document that type names
     */
    CompletionStage<Void> publish(String topic, String aggregateId, String payload);
}
