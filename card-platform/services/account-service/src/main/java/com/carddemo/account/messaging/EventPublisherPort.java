package com.carddemo.account.messaging;

/**
 * Publishes one already-serialized event payload to one topic on the event bus.
 *
 * <p>ADDITIVE. This interface has no COBOL ancestor. The CardDemo source carries no event
 * bus and no publish abstraction.
 *
 * <p>Rationale for the port lives in {@code card-platform/docs/decision-log.md}.
 */
public interface EventPublisherPort {

    /**
     * Publishes one payload to one topic under one key.
     *
     * <p>A broker orders messages within a partition only, and the key selects the
     * partition, so the events of one account stay in order.
     *
     * @param topic   the destination topic name
     * @param key     the account identifier, declared {@code XREF-ACCT-ID PIC 9(11)} at
     *                {@code app/cpy/CVACT03Y.cpy:L7}. Eleven digits, and a {@code String}
     *                keeps leading zeros
     * @param payload one event, serialized as JavaScript Object Notation before the call
     */
    void publish(String topic, String key, String payload);
}
