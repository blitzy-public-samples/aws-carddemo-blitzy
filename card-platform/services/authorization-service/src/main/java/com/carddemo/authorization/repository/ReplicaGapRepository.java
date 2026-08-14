package com.carddemo.authorization.repository;

import java.util.Optional;

import com.carddemo.authorization.entity.ReplicaGapEntity;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

/**
 * Reads and clears the record of accounts whose replica copy is missing a change.
 *
 * <p>ADDITIVE. The nearest source construct is the called input and output subroutine at
 * {@code app/cbl/CBSTM03B.CBL:L99-L114}, whose operation code selects a keyed read, a write or a
 * rewrite; the operations below are the same three shapes over one table. Nothing in the source
 * corresponds to the table's meaning, because the source reads its datasets directly and holds no
 * replica that can fall behind.
 *
 * <p>Three operations, and each has one caller. {@code domain/AuthorizationService} asks
 * {@link #existsForAggregate(String)} before it authorizes against a replica row. The two replica
 * listeners open or extend a gap through {@link #findById} and {@code save}, and close it through
 * {@link #clearForAggregate(String)} once a later record for that account applies.
 */
public interface ReplicaGapRepository
        extends ListCrudRepository<ReplicaGapEntity, ReplicaGapEntity.Key> {

    /**
     * Reports whether any replica stream is missing a change for one account.
     *
     * <p>The question is asked per account and not per stream, because a decision reads both replica
     * tables and a gap on either one is enough to make the answer untrustworthy.
     *
     * @param aggregateId the account a decision is about to read, eleven digits
     * @return {@code true} when at least one stream owes this account a change
     */
    @Query("select count(gap) > 0 from ReplicaGapEntity gap where gap.id.aggregateId = :aggregateId")
    boolean existsForAggregate(@Param("aggregateId") String aggregateId);

    /**
     * Finds the gap one stream holds for one account.
     *
     * @param aggregateId the account, eleven digits
     * @param stream      the replica topic
     * @return the standing gap, or an empty {@link Optional} when that stream owes nothing
     */
    @Query("select gap from ReplicaGapEntity gap where gap.id.aggregateId = :aggregateId "
            + "and gap.id.stream = :stream")
    Optional<ReplicaGapEntity> findByAggregateAndStream(@Param("aggregateId") String aggregateId,
            @Param("stream") String stream);

    /**
     * Clears every gap standing for one account.
     *
     * <p>Called from inside the transaction that applies a record, so the copy and the record of its
     * gap move together: a delete that committed without the apply would let a decision read a row
     * that is still behind.
     *
     * <p>Every stream is cleared rather than only the one that applied. A gap is opened by a delivery
     * that failed, and the record that succeeds now carries the state its producer holds; a second
     * stream's gap for the same account is cleared by its own successful delivery, so the narrower
     * form is available through {@link #findByAggregateAndStream} where a caller needs it.
     *
     * @param aggregateId the account whose record applied, eleven digits
     * @return how many gaps were standing
     */
    @Modifying
    @Query("delete from ReplicaGapEntity gap where gap.id.aggregateId = :aggregateId")
    int clearForAggregate(@Param("aggregateId") String aggregateId);

    /**
     * Counts the accounts currently missing a change, across both streams.
     *
     * <p>Read by the readiness indicator, which reports the service unable to authorize against its
     * replicas while any gap stands.
     *
     * @return how many gaps are open
     */
    @Query("select count(gap) from ReplicaGapEntity gap")
    long countOpenGaps();
}
