package com.carddemo.events.serde;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import org.apache.kafka.common.errors.SerializationException;

/**
 * The one boundary every event of this platform crosses before it is stored or published, and the
 * one place the bytes a consumer will read are decided.
 *
 * <p>No COBOL program and no copybook in this repository holds an event, a topic or a schema, so
 * this class translates nothing. It exists because two produce-side gates existed and they did not
 * agree. {@link JsonSchemaValidatingSerializer} applied every check; the outbox writers wrote their
 * own text with a plain mapper, measured it against the schema document alone, stored that text and
 * relayed it unchanged through a {@code StringSerializer}. A card number or a government identifier
 * written into a free-text property therefore committed with the business state and reached a topic,
 * where the consume-side screens of {@link JsonSchemaValidatingDeserializer} refused it: a
 * transaction was approved that could never post. A security review recorded the split as a
 * producer-side gate bypass.
 *
 * <p>{@link #checkedJsonOf(Record)} closes it. Eight checks run, in this order, and every one of
 * them runs before the caller's transaction can commit:
 *
 * <ol>
 * <li>The argument must be a record whose simple name names a registered event type, which is what
 * refuses an arbitrary object or a map whose JSON happens to carry a supported
 * {@code eventType}.</li>
 * <li>That event type must belong on the topic it is checked against, which is the default topic
 * {@link EventContracts#defaultTopicFor(String)} names. A deployment that renames a topic applies
 * the rename in the producer properties the relay reads, never in this in-process gate.</li>
 * <li>The written JSON must carry no property {@link SensitiveEventProperties} forbids.</li>
 * <li>No screened value of it may carry a card number, a government identifier or a card
 * verification value, which is the second screen of that same class.</li>
 * <li>The written JSON must carry no {@code extensions} object. Every schema declares one as the
 * channel additive evolution travels through, and no record of this platform declares the property,
 * so a document carrying it was built by something other than its own record. A consumer still
 * reads one, which is what the property exists for.</li>
 * <li>The document its event type and contract version select must accept it.</li>
 * <li>The finished document must fit {@link EventWireBounds#MAX_EVENT_BYTES}, which is also the
 * width of the {@code payload} column every outbox row is stored in.</li>
 * <li>The contract version it declares must be one a producer may still write, which
 * {@link ReleasedContracts} decides.</li>
 * </ol>
 *
 * <p>The first seven are {@link JsonSchemaValidatingSerializer}'s own checks, reached through the one
 * shared instance below, so the gate cannot drift from the serializer a Kafka producer uses. The
 * eighth is {@link EventContracts#postureViolationsOf(String, int)}, which the serializer does not
 * apply because a retained document stays readable on the consume side: what this gate refuses is a
 * producer writing under a document consumers have already moved past.
 *
 * <p>The text this method returns is the text that was checked, and a caller stores exactly that.
 * Writing the event again with another mapper would store bytes no gate has seen.
 *
 * <p>A refusal arrives as an {@link IllegalArgumentException}. The serializer reports a refusal as a
 * {@link SerializationException}, which names a Kafka concern a request handler or a listener has
 * not reached — nothing is published here — so the cause is kept and the type is the one a domain
 * caller already handles. Every message holds JSON pointers, broken keywords and property names, so
 * no card number, no verification value and no account identifier reaches a log through a refusal.
 *
 * <p>Every member is static and the shared serializer holds no mutable state after construction, so
 * service threads may call this class concurrently.
 */
public final class PublishGate {

    /**
     * The one serializer instance every producer path of this platform checks through.
     *
     * <p>Configured with no topic override, because a rename belongs to the producer properties the
     * relay publishes with and not to a check that runs inside a business transaction. Compiling the
     * schema documents once is why the instance is shared: a per-call instance would recompile
     * every document a service publishes under.
     */
    private static final JsonSchemaValidatingSerializer<Record> SHARED_SERIALIZER =
            new JsonSchemaValidatingSerializer<>();

    /** No instance is created. */
    private PublishGate() {
    }

    /**
     * Writes one event as JSON text and applies every publish-side check to that text.
     *
     * @param event the event about to be stored in an outbox row or published, a record of
     *              {@code com.carddemo.events} or of the publishing service
     * @return the checked JSON text, which is what the caller stores and the relay publishes
     * @throws NullPointerException     when {@code event} is {@code null}
     * @throws IllegalArgumentException when the argument names no registered event type, when the
     *                                  written JSON carries a forbidden property or a sensitive
     *                                  value, when it breaks the document its contract version
     *                                  selects, when it exceeds the platform byte ceiling, or when
     *                                  that version is retained rather than published
     */
    public static String checkedJsonOf(Record event) {
        Objects.requireNonNull(event, "event must be present");

        String eventType = eventTypeOf(event);
        String payload = checkedTextOf(eventType, event);

        List<String> posture = EventContracts.postureViolationsOf(eventType,
                EventContracts.declaredVersionOf(payload));
        if (!posture.isEmpty()) {
            throw new IllegalArgumentException(
                    EventContracts.describeViolations(eventType, posture));
        }
        return payload;
    }

    /**
     * Reads the registered event type one record names.
     *
     * <p>The class decides it, never a property of the written JSON. A record of a service resolves
     * through its own simple name, which is the convention every mutation event of this platform
     * follows: {@code AccountStateChanged}, {@code CustomerContextChanged} and {@code CardUpdated}
     * are records of the services that publish them and their documents live in this module.
     *
     * @param event the event to read
     * @return the registered event type
     * @throws IllegalArgumentException when the record's simple name names no registered type
     */
    private static String eventTypeOf(Record event) {
        String eventType = event.getClass().getSimpleName();
        if (!EventContracts.isRegistered(eventType)) {
            throw new IllegalArgumentException("the publish gate writes only a record whose simple"
                    + " name names a registered event type. The registered types are "
                    + EventContracts.eventTypes() + ", and " + event.getClass().getName()
                    + " names none of them.");
        }
        return eventType;
    }

    /**
     * Runs the serializer's seven checks and returns the text they passed.
     *
     * @param eventType the registered event type, which selects the topic and the document
     * @param event     the event to write
     * @return the checked JSON text, decoded from the bytes the serializer returned
     * @throws IllegalArgumentException when any check refuses the event
     */
    private static String checkedTextOf(String eventType, Record event) {
        try {
            byte[] checked =
                    SHARED_SERIALIZER.serialize(EventContracts.defaultTopicFor(eventType), event);
            return new String(checked, StandardCharsets.UTF_8);
        } catch (SerializationException refused) {
            throw new IllegalArgumentException(refused.getMessage(), refused);
        }
    }
}
