package com.carddemo.card.repository;

import com.carddemo.card.entity.CardTokenRotationEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.repository.ListCrudRepository;

/**
 * Writes and reads the audit record of a card-token rotation.
 *
 * <p>The repository pattern's Common Business Oriented Language (COBOL) ancestor is the generic
 * parameter area {@code LK-M03B-AREA} at {@code app/cbl/CBSTM03B.CBL:L100-L112}, whose operation code
 * dispatches every read and write behind one subroutine. Only two of its codes reach a method here.
 * Code {@code 'W'}, the condition {@code M03B-WRITE} at {@code app/cbl/CBSTM03B.CBL:L107}, becomes
 * the inherited {@code save}. Code {@code 'K'}, {@code M03B-READ-K} at
 * {@code app/cbl/CBSTM03B.CBL:L106}, becomes the inherited {@code findById}.
 *
 * <p>There is no delete of any kind, and that is deliberate. The mapping rows a run owns are what
 * another store is re-keyed from and what a rollback reads in reverse, so a run's record outlives the
 * run by design. The catalogue says the same:
 * {@code src/main/resources/db/migration/V10__card_token_version_and_rotation.sql} declares the table
 * {@code retention=permanent; purge_key=none}.
 *
 * <p>No COBOL program derives a card token, so nothing here has a source ancestor beyond the
 * parameter-area pattern above.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}. The operator procedure is
 * {@code card-platform/services/card-service/README.md}.
 */
public interface CardTokenRotationRepository
        extends ListCrudRepository<CardTokenRotationEntity, UUID> {

    /**
     * Returns the most recent rotations, newest first.
     *
     * <p>An operator reads this to find the run whose mapping rows they need. The result is bounded by
     * the caller rather than unbounded, because a rotation record is never deleted and the table
     * therefore only grows.
     *
     * @param limit how many rows to return at most
     * @return the rotations, newest first
     */
    List<CardTokenRotationEntity> findByOrderByStartedAtDesc(Limit limit);
}
