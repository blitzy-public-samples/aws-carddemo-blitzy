package com.carddemo.notification.repository;

import com.carddemo.notification.entity.NotificationLogEntity;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Writes the row that records one rendered cardholder alert.
 *
 * <p>{@code domain/NotificationService} is the caller, and it writes one row per alert with content
 * rendered by {@code domain/PlainTextRenderer} or {@code domain/HtmlRenderer}. The row carries the
 * masked card number, the transaction identifier, the rendered format and the instant rendering
 * finished, and {@link NotificationLogEntity} declares no column for a rendered document.
 *
 * <p>A row is not evidence of a delivery. This service reaches no mail, message, webhook or push
 * gateway, and every row carries {@link NotificationLogEntity#RENDERED_NOT_SENT} in its
 * {@code outcome} column, which {@code ck_notification_log_outcome} holds to that one value. Until
 * {@code V5__rendered_not_delivered.sql} the table, the column and this interface all called a row
 * a delivery attempt, which claimed a transport that has never existed and would have let an
 * operator read a row count as proof that a cardholder was told something.
 *
 * <p>The column {@code masked_card_number} holds the masked card number, twelve asterisks then the
 * last four digits, and no full Primary Account Number (PAN) reaches {@code notification_log}.
 * {@link NotificationLogEntity} enforces that shape on construction, so a row carrying a full PAN
 * cannot be written through this interface. The masked value identifies nothing;
 * {@code card_token} scopes the row to one card.
 *
 * <p>The repository pattern's Common Business Oriented Language (COBOL) ancestor is the generic
 * parameter area {@code LK-M03B-AREA} at {@code app/cbl/CBSTM03B.CBL:L100-L112}, whose operation
 * code dispatches every read and write behind one subroutine.
 *
 * <p>No COBOL ancestor: no COBOL program records a rendered alert. {@code app/cbl/CBSTM03A.CBL}
 * writes a statement to a sequential dataset at {@code app/cbl/CBSTM03A.CBL:L488-L502} and records
 * nothing about the write.
 *
 * <p>The interface is insert-only, and the application assigns the Universally Unique Identifier
 * (UUID) that keys each row. Extending {@link Repository} holds the interface to that surface, so
 * {@code save} is the one write and no {@code delete} is reachable. A row records an alert already
 * returned to a caller, so it stays as written.
 */
public interface NotificationLogRepository extends Repository<NotificationLogEntity, UUID> {

    /**
     * Inserts one rendered-alert row.
     *
     * @param rendered the row to insert, carrying an application-assigned identifier
     * @return the inserted row
     */
    NotificationLogEntity save(NotificationLogEntity rendered);

    /**
     * Counts the rendered-alert rows.
     *
     * <p>This is a count of alerts rendered, and of nothing sent. Reading it as a delivery figure
     * is the misreading {@code V5__rendered_not_delivered.sql} exists to prevent.
     *
     * @return how many rows the table holds
     */
    long count();

    /**
     * Deletes rendered-alert rows older than {@code horizon}, and returns how many it removed.
     *
     * <p>This is the one delete on this interface, and it is deliberately the only one. The
     * interface extends {@link org.springframework.data.repository.Repository} rather than a CRUD
     * interface so that no general delete is reachable: a row records an alert already returned to
     * a caller, and nothing may retract one. Expiring a row by age is a different act from
     * retracting it, and it is what keeps a table that grows by one row per consumed event bounded.
     * {@code carddemo.history.log-retention-days} carries the horizon and {@code
     * domain/RetentionSweep} applies it.
     *
     * <p>{@code ix_notification_log_rendered_at}, renamed from
     * {@code ix_notification_log_attempted_at} by
     * {@code src/main/resources/db/migration/V5__rendered_not_delivered.sql}, serves both the
     * subquery and the ordering of this delete.
     *
     * <p>{@code limit} bounds one statement. An unbounded delete holds every row it removes under
     * one lock for the whole statement, so a schema idle long enough to accumulate months of
     * rendered alerts takes one long statement that blocks every listener writing this table, for a
     * duration nobody can predict from the configuration. {@code domain/RetentionSweep} repeats
     * this call until it removes fewer rows than the limit, which drains the same backlog in short
     * transactions that each release their locks.
     *
     * <p>{@code ORDER BY rendered_at} makes the batches deterministic, so the oldest rows leave
     * first and no batch overlaps another.
     *
     * @param horizon the instant before which a rendered-alert row is removed
     * @param limit   the largest number of rows one statement removes
     * @return the number of rows removed
     */
    @Modifying
    @Query(value = """
            DELETE FROM notification_log
            WHERE id IN (SELECT id
                         FROM notification_log
                         WHERE rendered_at < :horizon
                         ORDER BY rendered_at
                         LIMIT :limit)
            """, nativeQuery = true)
    int deleteRenderedBefore(@Param("horizon") Instant horizon, @Param("limit") int limit);
}
