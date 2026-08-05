package com.carddemo.authorization.repository;

import com.carddemo.authorization.entity.AuthorizationDecisionEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
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
 * {@code save}, and code {@code 'R'} at {@code app/cbl/CBSTM03B.CBL:L105} maps onto the two reads
 * below. No operation maps onto {@code REWRITE}: {@link AuthorizationDecisionEntity} reports every
 * instance as new, so a store is always an insert.
 *
 * <p>Both reads below are bounded. The account read filters on {@code account_id} and orders by
 * {@code decided_at} descending, which is the shape of {@code ix_authorization_decision_account_decided}
 * in {@code src/main/resources/db/migration/V5__authorization_decision.sql}.
 */
public interface AuthorizationDecisionRepository
        extends ListCrudRepository<AuthorizationDecisionEntity, String> {

    /**
     * Returns the decision taken for one transaction.
     *
     * @param transactionId the sixteen-character identifier that is the primary key
     * @return the decision, or empty when this service decided no such transaction
     */
    Optional<AuthorizationDecisionEntity> findByTransactionId(String transactionId);

    /**
     * Returns the most recent decisions of one account, newest first.
     *
     * <p>The database applies {@code limit}, so no unbounded result set is materialized in this
     * service. Index {@code ix_authorization_decision_account_decided} answers both the filter and
     * the order.
     *
     * @param accountId eleven decimal digits
     * @param limit     rows to return
     * @return the newest decisions of that account, or an empty {@link List} when it has none
     */
    List<AuthorizationDecisionEntity> findByAccountIdOrderByDecidedAtDesc(String accountId,
            Limit limit);

    /**
     * Returns the most recent decisions of one actor, newest first.
     *
     * <p>This is the attribution read: it answers what one authenticated identity decided. Index
     * {@code ix_authorization_decision_actor} serves both the filter and the order, and the database
     * applies {@code limit}, so no unbounded result set is materialized in this service.
     *
     * @param actor the request identity, at most
     *              {@value AuthorizationDecisionEntity#ACTOR_MAX_LENGTH} characters
     * @param limit rows to return
     * @return the newest decisions that actor took, or an empty {@link List} when it took none
     */
    List<AuthorizationDecisionEntity> findByActorOrderByDecidedAtDesc(String actor, Limit limit);

    /**
     * Returns the most recent decisions this service took, newest first.
     *
     * <p>Index {@code ix_authorization_decision_decided_at} serves the order, and the database
     * applies {@code limit}.
     *
     * @param limit rows to return
     * @return the newest decisions, or an empty {@link List} when this service took none
     */
    List<AuthorizationDecisionEntity> findByOrderByDecidedAtDesc(Limit limit);

    /**
     * Deletes at most {@code limit} decisions taken before the given instant, and returns how many
     * it removed.
     *
     * <p>{@code carddemo.retention.decision-retention-days} in
     * {@code src/main/resources/application.yml} supplies the horizon, and
     * {@code ix_authorization_decision_decided_at} serves both the subquery and the delete.
     *
     * <p>{@code limit} bounds one statement, and {@code domain/RetentionSweep} repeats the call
     * until it removes fewer rows than it asked for. Every decision this service takes writes one
     * row here, so the table grows for the life of the service while nothing removes from it.
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
