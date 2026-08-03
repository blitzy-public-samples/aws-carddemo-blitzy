package com.carddemo.authorization.repository;

import com.carddemo.authorization.entity.OutboxEventEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.repository.ListCrudRepository;

/**
 * Reads rows of the table {@code outbox_event}, which the authorization service writes and its
 * relay publishes.
 *
 * <p>ADDITIVE. This interface has no COBOL ancestor. The source holds one asynchronous handoff.
 * Paragraph {@code WIRTE-JOBSUB-TDQ} at {@code app/cbl/CORPT00C.cbl:L515} writes a Customer
 * Information Control System (CICS) transient data queue, and a separate job reads the record.
 *
 * <p>The generic parameters follow the parameter area of the called subroutine at
 * {@code app/cbl/CBSTM03B.CBL:L100-L112}. That area carries a key at
 * {@code LK-M03B-KEY PIC X(25)} and a record area at {@code LK-M03B-FLDT PIC X(1000)}. The key
 * becomes {@link UUID}, and the record area becomes {@link OutboxEventEntity}.
 *
 * <p>{@link OutboxEventEntity} maps six columns and index {@code idx_outbox_event_unpublished}
 * over {@code (published, created_at)}. The finder below filters on the first column and orders
 * by the second.
 *
 * <p>{@code outbox/OutboxWriter.java} inserts a row in the same local transaction as the
 * authorization decision that row describes, through the inherited {@code save} methods.
 * {@code outbox/OutboxRelay.java} marks a row published by calling
 * {@link OutboxEventEntity#markPublished()} and saving it, in a transaction of its own.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
public interface OutboxEventRepository extends ListCrudRepository<OutboxEventEntity, UUID> {

    /**
     * Returns committed rows the relay has not published.
     *
     * <p>Rows come back oldest first, ordered by {@code created_at} ascending, the order in which
     * they were committed. The caller sets how many rows come back, and
     * {@code src/main/resources/application.yml} holds that count under
     * {@code carddemo.outbox.relay.batch-size}. An empty list means no row awaits publication.
     *
     * <p>Operation code {@code 'R'} at {@code app/cbl/CBSTM03B.CBL:L105} is the sequential read
     * this finder reproduces. The source publishes one record per queue write at
     * {@code app/cbl/CORPT00C.cbl:L517-L523}.
     *
     * @param limit how many rows to return, built with {@code Limit.of(int)}
     * @return unpublished rows, oldest first, at most {@code limit} of them, and empty when none
     *         awaits publication
     */
    List<OutboxEventEntity> findByPublishedFalseOrderByCreatedAtAsc(Limit limit);
}
