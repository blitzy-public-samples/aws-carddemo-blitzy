package com.carddemo.card.repository;

import com.carddemo.card.entity.OutboxEventEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.repository.ListCrudRepository;

/**
 * Reads rows of {@code outbox_event}, the card service's private outbox table.
 *
 * <p>ADDITIVE. No CardDemo program, copybook or job stores an event row.
 *
 * <p>The source holds one asynchronous handoff. Paragraph {@code WIRTE-JOBSUB-TDQ} at
 * {@code app/cbl/CORPT00C.cbl:L515} writes a Customer Information Control System (CICS) transient
 * data queue, and a separate job reads the Job Control Language (JCL) record back. That handoff
 * supplies the shape of the read below and none of its data.
 *
 * <p>Transformed from the called input and output subroutine {@code app/cbl/CBSTM03B.CBL}, whose
 * parameter area spans {@code app/cbl/CBSTM03B.CBL:L100-L112}. {@code LK-M03B-DD PIC X(08)} at
 * {@code app/cbl/CBSTM03B.CBL:L101} selects a dataset, and one interface serves one dataset.
 * {@code LK-M03B-KEY PIC X(25)} at {@code app/cbl/CBSTM03B.CBL:L110} becomes {@link UUID}, the type
 * of the {@code event_id} primary key. {@code LK-M03B-FLDT PIC X(1000)} at
 * {@code app/cbl/CBSTM03B.CBL:L112} becomes {@link OutboxEventEntity}.
 *
 * <p>The status field {@code LK-M03B-RC PIC X(02)} at {@code app/cbl/CBSTM03B.CBL:L109} becomes an
 * empty result or a thrown exception, and the key width {@code LK-M03B-KEY-LN PIC S9(4)} at
 * {@code app/cbl/CBSTM03B.CBL:L111} maps onto nothing.
 *
 * <p>Four of the six operation codes declared at {@code app/cbl/CBSTM03B.CBL:L103-L108} reach a
 * method, and two reach nothing.
 *
 * <pre>
 * code   condition name   locator   target
 * 'R'    M03B-READ        L105      findByPublishedFalseOrderByCreatedAtAsc, inherited findAll
 * 'K'    M03B-READ-K      L106      the inherited findById
 * 'W'    M03B-WRITE       L107      the inherited save
 * 'Z'    M03B-REWRITE     L108      the inherited save
 * 'O'    M03B-OPEN        L103      none
 * 'C'    M03B-CLOSE       L104      none
 * </pre>
 *
 * <p>{@code app/cbl/CBSTM03B.CBL} declares the write and rewrite codes at
 * {@code app/cbl/CBSTM03B.CBL:L107-L108} and implements neither. All four of its datasets open for
 * input alone, at {@code app/cbl/CBSTM03B.CBL:L136}, {@code :L160}, {@code :L184} and
 * {@code :L209}. An insert and an update therefore reach the table through the inherited
 * {@code save} methods and through no method declared below.
 *
 * <p>The card update path writes a row in the same local transaction as the card change that row
 * describes. The relay under {@code com.carddemo.card.outbox} reads that row through the method
 * below, publishes it, then calls {@code markPublished} and saves it in a transaction of its own.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md} (planned).
 */
public interface OutboxEventRepository extends ListCrudRepository<OutboxEventEntity, UUID> {

    /**
     * Returns the rows a writer has committed and the relay has not yet published.
     *
     * <p>Reproduces operation code {@code 'R'}, the condition {@code M03B-READ} at
     * {@code app/cbl/CBSTM03B.CBL:L105}, which reads a dataset forward. The ancestor of that read
     * is {@code app/cbl/CORPT00C.cbl:L517-L523}, where
     * {@code EXEC CICS WRITEQ TD QUEUE ('JOBS')} hands one record to a reader that runs later.
     *
     * <p>Rows arrive by {@code created_at} ascending, which is the order their writers committed
     * them. Partial index {@code ix_outbox_event_pending} in
     * {@code src/main/resources/db/migration/V1__schema.sql} spans
     * {@code (created_at, event_id) WHERE published = FALSE} and covers both the filter and the
     * order.
     *
     * <p>The caller supplies the row cap. Property {@code carddemo.outbox.relay.batch-size} in
     * {@code src/main/resources/application.yml} holds the configured value, which
     * {@code com.carddemo.card.config.CardProperties} binds.
     *
     * <p>An empty result means the relay has nothing to publish.
     *
     * @param limit greatest number of rows to return
     * @return unpublished rows, oldest first, and empty when none awaits publication
     */
    List<OutboxEventEntity> findByPublishedFalseOrderByCreatedAtAsc(Limit limit);
}
