package com.carddemo.events.correlation;

import java.util.UUID;

/**
 * The two envelope components a listener boundary reads without knowing which event it holds.
 *
 * <p>Every event record of this module already declares both accessors, so implementing this
 * interface adds no member to a record and changes no serialized form. What it adds is one type a
 * record interceptor can bind to, which is what lets one interceptor per service name the event
 * being handled in the structured log context rather than one branch per event type.
 *
 * <p>{@code aggregateId} is deliberately absent. It is an account identifier on every contract but
 * one, and this interface exists to serve a logging boundary, so widening it would put an account
 * identifier one accessor away from a log line.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
public interface CorrelatedEvent {

    /**
     * Returns the idempotency key of this event.
     *
     * @return the event identifier, never {@code null}
     */
    UUID eventId();

    /**
     * Returns the routing discriminator of this event.
     *
     * @return the simple name of the event type, for example {@code TransactionAuthorized}
     */
    String eventType();
}
