package com.carddemo.ledger.repository;

import com.carddemo.ledger.entity.OutboxEventEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.repository.ListCrudRepository;

/**
 * Data access for the pending domain events of the ledger posting service, held in the table
 * {@code outbox_event}.
 *
 * <p>{@code outbox/OutboxWriter} inserts one row in the same local transaction as the domain write
 * that produced the event. {@code outbox/OutboxRelay} reads the unpublished rows and marks each one
 * with {@link OutboxEventEntity#markPublished()}.</p>
 *
 * <p>The one-interface-per-aggregate shape comes from the generic parameter area
 * {@code 01 LK-M03B-AREA} at {@code app/cbl/CBSTM03B.CBL:L100-L112}, whose operation code
 * {@code LK-M03B-OPER} dispatches every read and write behind one called subroutine. The queued
 * handoff comes from {@code EXEC CICS WRITEQ TD QUEUE ('JOBS')} at
 * {@code app/cbl/CORPT00C.cbl:L517-L518}. That Customer Information Control System (CICS) write is
 * the only transient-data queue write in the 28 programs of {@code app/cbl/}, and the one
 * asynchronous handoff the CardDemo source performs.</p>
 *
 * <p>Rationale for this interface: {@code card-platform/docs/decision-log.md}.</p>
 */
public interface OutboxEventRepository extends ListCrudRepository<OutboxEventEntity, UUID> {

    /**
     * Returns the unpublished rows, oldest first, up to the count the caller supplies.
     *
     * <p>The method orders by {@link OutboxEventEntity#getCreatedAt()} ascending, so two calls over
     * an unchanged table select the same rows in the same order.</p>
     *
     * @param limit greatest number of rows to return, built with {@link Limit#of(int)}
     * @return the unpublished rows in ascending creation order, empty when the table holds none
     */
    List<OutboxEventEntity> findByPublishedFalseOrderByCreatedAtAsc(Limit limit);
}
