package com.carddemo.fraud.repository;

import com.carddemo.fraud.entity.VelocityWindowEntity;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Finds the per-account authorization windows the risk rules read and the consumer in the sibling
 * {@code messaging} package maintains.
 *
 * <p>No COBOL ancestor. The repository shape comes from the parameter
 * area {@code LK-M03B-AREA} at {@code app/cbl/CBSTM03B.CBL:L99-L114}, whose operation code routes
 * every read and write through one subroutine: shape only, no logic.</p>
 *
 * <p>The identifier is {@link VelocityWindowEntity.VelocityWindowId}, the nested composite key of
 * account identifier then window start, and {@code findById} over that key is inherited.</p>
 */
@Repository
public interface VelocityWindowRepository
        extends JpaRepository<VelocityWindowEntity, VelocityWindowEntity.VelocityWindowId> {

    /**
     * Counts one authorization into the window the two key parts name, creating that window at this
     * amount when the table holds none, in one statement.
     *
     * <p>A read that reports no row, followed by an insert, has a window: a second consumer instance
     * reads nothing as well, and one of the two inserts fails on the primary key. A read that
     * reports a row, followed by an update of the value it read, has the other window: two
     * instances read the same count and each stores that count plus one, so one authorization
     * disappears. {@code ON CONFLICT ... DO UPDATE} has neither, and the primary key decides the
     * race inside the statement. The counter and the total are raised from the stored values in the
     * same statement that reads them.</p>
     *
     * <p>Addition happens in the database at {@code NUMERIC(15,2)}, the precision and scale
     * {@code src/main/resources/db/migration/V3__velocity_total_headroom.sql} leaves
     * {@code total_amount}, so the sum needs no rounding, cannot acquire a third fractional digit,
     * and holds ten thousand maximum-magnitude authorizations in one bucket. The caller supplies an
     * amount already held to that scale, and supplies its magnitude, so a refund contributes what
     * it is worth instead of lowering the total.</p>
     *
     * <p>The statement is written in Structured Query Language (SQL); the Jakarta Persistence Query
     * Language declares no conflict clause. {@code currentSchema} in the datasource Uniform Resource
     * Locator of {@code src/main/resources/application.yml} puts the schema this service owns on the
     * connection search path, so the unqualified table name resolves to it.</p>
     *
     * @param accountId   the account whose bucket is updated, eleven digits of text
     * @param windowStart the bucket start, the event time truncated to the bucket width
     * @param amount      the non-negative amount magnitude at transaction scale
     * @param updatedAt   the moment written to {@code updated_at}
     * @return 1, the one row the statement writes on either arm
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO velocity_window
                (account_id, window_start, authorization_count, total_amount, updated_at)
            VALUES (:accountId, :windowStart, 1, :amount, :updatedAt)
            ON CONFLICT (account_id, window_start) DO UPDATE
            SET authorization_count = velocity_window.authorization_count + 1,
                total_amount = velocity_window.total_amount + EXCLUDED.total_amount,
                updated_at = EXCLUDED.updated_at
            """, nativeQuery = true)
    int addAuthorization(@Param("accountId") String accountId,
            @Param("windowStart") Instant windowStart,
            @Param("amount") BigDecimal amount,
            @Param("updatedAt") Instant updatedAt);

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

    /**
     * Deletes at most {@code limit} windows that started before the given instant, and returns how
     * many it removed.
     *
     * <p>ADDITIVE, as this whole service is. A window is read only while it is the current one, and
     * {@code domain/RiskScoringService} opens a new window as soon as the configured span elapses.
     * Every authorization therefore leaves a row behind that nothing reads again.
     * {@code carddemo.retention.velocity-retention} in
     * {@code src/main/resources/application.yml} supplies the horizon, and
     * {@code ix_velocity_window_start} serves both the subquery and the delete.
     *
     * <p>{@code limit} bounds one statement, and {@code outbox/RetentionSweeper} repeats the call
     * until it removes fewer rows than it asked for. The horizon must exceed the window span, or
     * the delete removes the window a live authorization is counting into.
     *
     * @param horizon the instant before which a window is removed
     * @param limit   the most rows one statement removes, at least one
     * @return the number of rows removed, and 0 when none is past the horizon
     */
    @Modifying
    @Query(value = """
            DELETE FROM velocity_window
            WHERE (account_id, window_start) IN (SELECT account_id, window_start
                                                   FROM velocity_window
                                                  WHERE window_start < :horizon
                                                  ORDER BY window_start
                                                  LIMIT :limit)
            """, nativeQuery = true)
    int deleteWindowsStartedBefore(@Param("horizon") Instant horizon, @Param("limit") int limit);
}
