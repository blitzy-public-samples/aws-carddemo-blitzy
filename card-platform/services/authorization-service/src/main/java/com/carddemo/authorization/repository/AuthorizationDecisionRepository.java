package com.carddemo.authorization.repository;

import com.carddemo.authorization.entity.AuthorizationDecisionEntity;
import java.time.Instant;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

/**
 * Stores and reads {@code authorization_decision}, the record of every decision this service took.
 *
 * <p>ADDITIVE as a table. {@code app/cbl/CBTRN02C.cbl:L424-L444} posts an approved transaction into
 * three files and {@code app/cbl/CBTRN02C.cbl:L446-L465} writes a 430-byte reject record for a
 * declined one, so the source keeps the two outcomes in different places and neither carries the
 * decision as such.
 *
 * <p>The generic parameter area at {@code app/cbl/CBSTM03B.CBL:L100-L112} is the pattern this
 * interface follows, as the other repositories of this service do. Operation code {@code 'W'}, the
 * condition {@code M03B-WRITE} at {@code app/cbl/CBSTM03B.CBL:L107}, maps onto the inherited
 * {@code save}, and code {@code 'R'} at {@code app/cbl/CBSTM03B.CBL:L105} maps onto the inherited
 * {@code findById}. No operation maps onto {@code REWRITE}:
 * {@link AuthorizationDecisionEntity} reports every instance as new, so a store is always an
 * insert.
 *
 * <p><b>The surface is the write, the retention delete and what the base interface inherits.</b>
 * Four declared reads have been withdrawn: one keyed on {@code transaction_id}, which is the primary
 * key and therefore the inherited {@code findById} spelled a second way, and three bounded list
 * reads over account, actor and age. No caller reached any of the four. Two composite indexes
 * existed to serve two of them, so every authorization paid for index maintenance no read ever used;
 * {@code V18__authorization_decision_index_pruning.sql} withdraws those two.
 * {@code ix_authorization_decision_decided_at} stays, because the delete below reads it.
 *
 * <p>An operator query over this table is a real requirement and it is recorded as a follow-up task
 * rather than left as an unread index. Adding it means adding the endpoint, the bounded read and the
 * index that serves it together, in one change whose read path is visible.
 */
public interface AuthorizationDecisionRepository
        extends ListCrudRepository<AuthorizationDecisionEntity, String> {

    /**
     * Deletes at most {@code limit} decisions taken before the given instant, and returns how many
     * it removed.
     *
     * <p>{@code carddemo.retention.decision-retention-days} in
     * {@code src/main/resources/application.yml} supplies the horizon, and
     * {@code ix_authorization_decision_decided_at} serves both the subquery and the delete.
     *
     * <p>{@code limit} bounds one statement, and {@code domain/RetentionSweep} repeats the call
     * until it removes fewer rows than it asked for or until that table's own deadline passes. Both
     * halves matter here: every decision this service takes writes one row, so a bound with no
     * repetition would leave every row above one batch behind for ever.
     *
     * @param horizon the instant before which a decision is removed
     * @param limit   the most rows one statement removes, at least one
     * @return the number of rows removed, and 0 when none is past the horizon
     */
    @Modifying
    @Query(value = """
            DELETE FROM authorization_decision
            WHERE transaction_id IN (SELECT transaction_id
                                       FROM authorization_decision
                                      WHERE decided_at < :horizon
                                      ORDER BY decided_at
                                      LIMIT :limit)
            """, nativeQuery = true)
    int deleteDecidedBefore(@Param("horizon") Instant horizon, @Param("limit") int limit);
}
