package com.carddemo.authorization.repository;

import com.carddemo.authorization.entity.UnresolvedCardAttemptEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

/**
 * Stores and reads {@code unresolved_card_attempt}, the record of an attempt whose card resolved to
 * no account.
 *
 * <p>The table has no COBOL ancestor; the behaviour it records does. Reject code {@code 0100} is
 * assigned at {@code app/cbl/CBTRN02C.cbl:L385} and {@code app/cbl/CBTRN02C.cbl:L446-L465} writes a
 * reject record for it. That record is what this table holds, minus the
 * three-hundred-and-fifty-byte transaction payload beside the trailer.
 *
 * <p>The generic parameter area at {@code app/cbl/CBSTM03B.CBL:L100-L112} is the pattern this
 * interface follows, as the other repositories of this service do. Operation code {@code 'W'}, the
 * condition {@code M03B-WRITE} at {@code app/cbl/CBSTM03B.CBL:L107}, maps onto the inherited
 * {@code save}, and code {@code 'R'} at {@code app/cbl/CBSTM03B.CBL:L105} maps onto the read below.
 */
public interface UnresolvedCardAttemptRepository
        extends ListCrudRepository<UnresolvedCardAttemptEntity, String> {

    /**
     * Returns the most recent attempts, newest first.
     *
     * <p>An operator reading this table wants the newest attempts, so the ordering runs descending on
     * the moment each attempt was decided. {@code idx_unresolved_card_attempt_attempted_at} carries
     * that column.
     *
     * @param limit rows to return
     * @return the newest attempts, or an empty {@link List} when the table holds none
     */
    List<UnresolvedCardAttemptEntity> findByOrderByAttemptedAtDesc(Limit limit);

    /**
     * Deletes at most {@code limit} attempts recorded before the given instant, and returns how many
     * it removed.
     *
     * <p>{@code carddemo.retention.decision-retention-days} in
     * {@code src/main/resources/application.yml} supplies the horizon, and the same horizon governs
     * {@code authorization_decision}, because a reject code 0100 outcome is recorded in both.
     *
     * <p>{@code limit} bounds one statement, and {@code domain/RetentionSweep} repeats the call
     * until it removes fewer rows than it asked for.
     *
     * @param horizon the instant before which an attempt is removed
     * @param limit   the most rows one statement removes, at least one
     * @return the number of rows removed, and 0 when none is past the horizon
     */
    @Modifying
    @Query(value = """
            DELETE FROM unresolved_card_attempt
            WHERE transaction_id IN (SELECT transaction_id
                                       FROM unresolved_card_attempt
                                      WHERE attempted_at < :horizon
                                      ORDER BY attempted_at
                                      LIMIT :limit)
            """, nativeQuery = true)
    int deleteAttemptedBefore(@Param("horizon") Instant horizon, @Param("limit") int limit);
}
