package com.carddemo.card.repository;

import com.carddemo.card.entity.CardTokenRotationMappingEntity;
import com.carddemo.card.entity.CardTokenRotationMappingEntity.CardTokenRotationMappingId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.ListCrudRepository;

/**
 * Writes and reads the previous-token to current-token mapping one rotation produced.
 *
 * <p>The repository pattern's Common Business Oriented Language (COBOL) ancestor is the generic
 * parameter area {@code LK-M03B-AREA} at {@code app/cbl/CBSTM03B.CBL:L100-L112}. Code {@code 'W'} at
 * {@code app/cbl/CBSTM03B.CBL:L107} becomes the inherited {@code saveAll}, and code {@code 'S'},
 * {@code M03B-READ-S} at {@code app/cbl/CBSTM03B.CBL:L105}, becomes the ordered read below.
 *
 * <p>There is no delete. A mapping row is what {@code statement_transaction},
 * {@code notification_log}, {@code authorization_decision} and a granted {@code SCOPE_CARD} authority
 * are re-keyed from, and reading it in reverse is the rollback, so it outlives the run that wrote it.
 *
 * <p>No COBOL program derives a card token, so this interface has no source ancestor beyond the
 * parameter-area pattern.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}. The operator procedure is
 * {@code card-platform/services/card-service/README.md}.
 */
public interface CardTokenRotationMappingRepository
        extends ListCrudRepository<CardTokenRotationMappingEntity, CardTokenRotationMappingId> {

    /**
     * Returns one run's mapping, ordered by the token another store still holds.
     *
     * <p>The order is stable so that an export of the mapping is reproducible, which is what lets an
     * operator compare two exports of one run.
     *
     * @param rotationId the run whose mapping to read
     * @return the mapping rows of that run, ordered by previous token
     */
    List<CardTokenRotationMappingEntity> findByIdRotationIdOrderByIdPreviousCardTokenAsc(
            UUID rotationId);
}
