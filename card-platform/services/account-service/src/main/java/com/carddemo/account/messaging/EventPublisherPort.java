package com.carddemo.account.messaging;

/**
 * Publishes one already-serialized event payload to one topic on the event bus.
 *
 * <p>ADDITIVE. This interface has no COBOL ancestor. The CardDemo source carries no event
 * bus and no publish abstraction.
 */
public interface EventPublisherPort {

    /**
     * Pattern every {@code aggregateId} argument matches: exactly eleven decimal digits, the width
     * of {@code ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT01Y.cpy:L5}. The same pattern
     * constrains {@code aggregateId} in every event schema under
     * {@code card-platform/libs/event-contracts/src/main/resources/schemas}.
     */
    String AGGREGATE_ID_PATTERN = "^[0-9]{11}$";

    /**
     * Publishes one payload to one topic, keyed on the aggregate the payload belongs to.
     *
     * <p>A broker orders messages within a partition only, and the message key selects the
     * partition, so the events of one account stay in order. The implementation derives that key
     * from {@code aggregateId} and takes no separate key argument, so the key and the payload
     * cannot name different accounts.
     *
     * <p>An implementation checks {@code aggregateId} against {@link #AGGREGATE_ID_PATTERN} and
     * rejects any other value.
     *
     * @param topic       the destination topic name
     * @param aggregateId the account identifier the event belongs to, and the value the payload
     *                    carries in its own {@code aggregateId} field. Eleven digits, and a
     *                    {@code String} keeps leading zeros
     * @param payload     one event, serialized as JavaScript Object Notation before the call
     */
    void publish(String topic, String aggregateId, String payload);
}
