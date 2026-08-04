package com.carddemo.authorization.repository;

import com.carddemo.authorization.entity.UnresolvedCardAttemptEntity;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.repository.ListCrudRepository;

/**
 * Stores and reads {@code unresolved_card_attempt}, the record of an attempt whose card resolved to
 * no account.
 *
 * <p>ADDITIVE as a table, and source-faithful as a behaviour. Reject code {@code 0100} is assigned at
 * {@code app/cbl/CBTRN02C.cbl:L385} and {@code app/cbl/CBTRN02C.cbl:L446-L465} writes a reject record
 * for it. That record is what this table holds, minus the three-hundred-and-fifty-byte transaction
 * payload beside the trailer.
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
}
