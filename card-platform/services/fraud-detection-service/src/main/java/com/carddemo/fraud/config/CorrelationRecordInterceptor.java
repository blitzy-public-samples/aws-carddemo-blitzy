package com.carddemo.fraud.config;

import com.carddemo.events.correlation.CorrelationScope;
import com.carddemo.events.correlation.EventCorrelation;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.RecordInterceptor;

/**
 * Puts the correlation fields of one delivery on every log record the fraud detection service writes while handling
 * it, and takes them away again afterwards.
 *
 * <p>ADDITIVE. The source needed nothing of the kind: one batch program wrote to one job log, and
 * {@code app/cbl/CBTRN02C.cbl:L714-L727} formatted a file status without needing to say which run it
 * belonged to. An event consumed by three services in parallel does.
 *
 * <p>The interceptor is the one place per service this happens. Placing it here rather than in each
 * listener has two consequences worth naming. A listener added later is covered without being
 * changed, and the opening and the closing sit in one class, so no listener can open a scope and
 * leave it on a container thread that serves the next delivery.
 *
 * <p>Four values are written. {@code correlationId} and {@code causationId} come from the record
 * headers {@link EventCorrelation} declares, and neither is accepted unless it is one rendered
 * Universally Unique Identifier (UUID). {@code eventId} and {@code eventType} come from the record
 * value, whichever of the two forms this platform delivers. A delivery carrying no correlation header
 * adopts its own event identifier, so a chain that lost its header still has one identity across the
 * retries of that delivery.
 *
 * <p>{@link #intercept} closes any scope it finds before opening its own, and
 * {@link #clearThreadState} closes one on the way out of a poll. Either alone would be enough while
 * {@link #afterRecord} runs; together they mean a delivery cannot inherit a field from the one
 * before it even if it does not.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 *
 * @param <K> key type of the containers this interceptor is installed on
 * @param <V> value type of the containers this interceptor is installed on
 */
final class CorrelationRecordInterceptor<K, V> implements RecordInterceptor<K, V> {

    /** The scope of the delivery this container thread is handling, or null between deliveries. */
    private static final ThreadLocal<CorrelationScope> OPEN_SCOPE = new ThreadLocal<>();

    /**
     * Opens the scope of one delivery and hands the record on unchanged.
     *
     * @param record   the delivery, read for its headers and its value alone
     * @param consumer the consumer the container polled with, unused
     * @return the same record, so no listener sees a different one
     */
    @Override
    public ConsumerRecord<K, V> intercept(ConsumerRecord<K, V> record, Consumer<K, V> consumer) {
        closeOpenScope();
        OPEN_SCOPE.set(CorrelationScope.forDelivery(record.headers())
                .withHandledValue(record.value()));
        return record;
    }

    /**
     * Closes the scope of one delivery, whether its listener returned or threw.
     *
     * @param record   the delivery that was handled, unused
     * @param consumer the consumer the container polled with, unused
     */
    @Override
    public void afterRecord(ConsumerRecord<K, V> record, Consumer<K, V> consumer) {
        closeOpenScope();
    }

    /**
     * Closes a scope left open on the way out of one poll.
     *
     * @param consumer the consumer the container polled with, unused
     */
    @Override
    public void clearThreadState(Consumer<?, ?> consumer) {
        closeOpenScope();
    }

    /** Closes and forgets the scope of this thread, and does nothing when there is none. */
    private static void closeOpenScope() {
        CorrelationScope open = OPEN_SCOPE.get();
        if (open != null) {
            open.close();
            OPEN_SCOPE.remove();
        }
    }
}
