package com.carddemo.fraud.repository;

import com.carddemo.fraud.entity.OutboxEventEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Reads rows of the table {@code outbox_event}, one row per {@code FraudFlagged} or
 * {@code FraudCleared} event awaiting publication to the topic {@code fraud.assessed}. A consumer
 * writes the row in the same transaction as its assessment, and the relay publishes it later.
 *
 * <p>ADDITIVE IN FULL: net new; no COBOL ancestor. The parameter area at
 * {@code app/cbl/CBSTM03B.CBL:L99-L114} supplies shape only, no logic: its sequential read becomes
 * the finder below.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md} (planned).
 */
@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, UUID> {

    /**
     * Returns unpublished rows in creation order, at most as many as {@code pageable} allows.
     *
     * <p>The relay in the sibling {@code outbox} package reads one batch on each poll and supplies
     * the bound.
     *
     * @param pageable the row bound the caller supplies
     * @return the unpublished rows, empty when the table holds none
     */
    List<OutboxEventEntity> findByPublishedFalseOrderByCreatedAtAsc(Pageable pageable);
}
