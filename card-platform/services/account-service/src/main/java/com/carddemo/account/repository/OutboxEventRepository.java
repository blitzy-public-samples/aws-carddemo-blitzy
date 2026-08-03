package com.carddemo.account.repository;

import com.carddemo.account.entity.OutboxEventEntity;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.repository.ListCrudRepository;

/**
 * Access to the transactional-outbox rows of the account service, table {@code outbox_event} in its
 * private schema.
 *
 * <p>{@code app/cbl/CBSTM03B.CBL:L99-L112} declares a generic parameter area whose operation code
 * selects the access path, and this package reproduces that contract as one interface per
 * aggregate. The inherited {@code save} reproduces the write {@code 'W'} at
 * {@code app/cbl/CBSTM03B.CBL:L107} and the rewrite {@code 'Z'} at {@code L108}, and the inherited
 * {@code findById} reproduces the keyed read {@code 'K'} at {@code L106}.
 *
 * <p>ADDITIVE, and this interface has one ancestor: the single asynchronous handoff of the source.
 * The source writes that handoff with the Customer Information Control System (CICS) command
 * {@code EXEC CICS WRITEQ TD} on {@code QUEUE ('JOBS')} at
 * {@code app/cbl/CORPT00C.cbl:L517-L518}, inside the paragraph {@code WIRTE-JOBSUB-TDQ} at
 * {@code L515}.
 */
public interface OutboxEventRepository extends ListCrudRepository<OutboxEventEntity, String> {

    /**
     * Returns the unpublished rows, ordered by {@code occurred_at} ascending then {@code event_id}
     * ascending, and bounded by the supplied limit.
     *
     * <p>{@code outbox/OutboxRelay} polls on a 500-millisecond fixed delay with a limit of one
     * hundred rows, then publishes each row to the {@code account.state-changed} topic. A table
     * holding no unpublished row yields an empty list.</p>
     *
     * @param limit the greatest number of rows to return
     * @return the unpublished rows in publication order
     */
    List<OutboxEventEntity> findByPublishedFalseOrderByOccurredAtAscEventIdAsc(Limit limit);
}
