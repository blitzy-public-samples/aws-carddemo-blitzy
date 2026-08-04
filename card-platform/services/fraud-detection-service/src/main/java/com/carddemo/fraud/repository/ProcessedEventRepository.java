package com.carddemo.fraud.repository;

import com.carddemo.fraud.entity.ProcessedEventEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * The consumer in the sibling {@code messaging} package records one marker row per handled event in
 * table {@code processed_event}, so a duplicate delivery does nothing twice.
 *
 * <p>ADDITIVE IN FULL: net new; no COBOL ancestor, where COBOL expands to Common Business Oriented
 * Language.
 *
 * <p>The generic parameter area at {@code app/cbl/CBSTM03B.CBL:L99-L114} carries an operation code
 * that dispatches every read and write behind one subroutine: shape only, no logic.
 *
 * <p>The inherited existence check on {@code eventId}, a Universally Unique Identifier (UUID), is
 * the idempotency guard, and the {@code eventId} of the event envelope is the idempotency key.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md} (planned).
 */
@Repository
public interface ProcessedEventRepository extends JpaRepository<ProcessedEventEntity, UUID> {
}
