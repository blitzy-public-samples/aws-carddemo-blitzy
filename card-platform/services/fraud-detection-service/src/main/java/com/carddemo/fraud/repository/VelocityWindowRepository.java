package com.carddemo.fraud.repository;

import com.carddemo.fraud.entity.VelocityWindowEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Finds the per-account authorization windows the risk rules read and the consumer in the sibling
 * {@code messaging} package maintains.
 *
 * <p>ADDITIVE IN FULL: net new; no COBOL ancestor. The repository shape comes from the parameter
 * area {@code LK-M03B-AREA} at {@code app/cbl/CBSTM03B.CBL:L99-L114}, whose operation code routes
 * every read and write through one subroutine: shape only, no logic.</p>
 *
 * <p>The identifier is {@link VelocityWindowEntity.VelocityWindowId}, the nested composite key of
 * account identifier then window start, and {@code findById} over that key is inherited.</p>
 *
 * <p>The source-to-target mapping sits in {@code card-platform/docs/traceability-matrix.md}
 * (planned).</p>
 */
@Repository
public interface VelocityWindowRepository
        extends JpaRepository<VelocityWindowEntity, VelocityWindowEntity.VelocityWindowId> {

    /**
     * Reads every window row one account holds whose start falls at or after {@code from}.
     *
     * <p>The bound is inclusive: a bucket starting exactly at {@code from} belongs in the result.
     * The velocity rule in the sibling {@code domain} package reads the rows in a span.</p>
     *
     * @param accountId the account to read, opaque text of eleven digits that is never parsed into
     *                  a number
     * @param from      inclusive lower bound on {@code window_start}
     * @return the matching rows, empty when the account holds no window at or after {@code from}
     */
    List<VelocityWindowEntity> findByAccountIdAndWindowStartGreaterThanEqual(
            String accountId, Instant from);
}
