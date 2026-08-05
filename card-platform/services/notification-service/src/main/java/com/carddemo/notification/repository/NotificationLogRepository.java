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
 * masked card number, the transaction identifier, the rendered format and the instant of the
 * attempt, and {@link NotificationLogEntity} declares no column for a rendered document.
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
 * <p>No COBOL ancestor: no COBOL program records a delivery attempt. {@code app/cbl/CBSTM03A.CBL}
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
     * Inserts one delivery-attempt row.
     *
     * @param attempt the row to insert, carrying an application-assigned identifier
     * @return the inserted row
     */
    NotificationLogEntity save(NotificationLogEntity attempt);

    /**
     * Counts the delivery-attempt rows.
     *
     * @return how many rows the table holds
     */
    long count();

    /**
     * Deletes attempt rows older than {@code horizon}, and returns how many it removed.
     *
     * <p>This is the one delete on this interface, and it is deliberately the only one. The interface
     * extends {@link org.springframework.data.repository.Repository} rather than a CRUD interface so
     * that no general delete is reachable: a row records an alert that was already attempted, and
     * nothing may retract one. Expiring a row by age is a different act from retracting it, and it is
     * what keeps a table that grows by one row per consumed event bounded.
     * {@code carddemo.history.log-retention-days} carries the horizon and
     * {@code domain/RetentionSweep} applies it.
     *
     * <p>{@code ix_notification_log_attempted_at} in
     * {@code src/main/resources/db/migration/V1__schema.sql} serves this delete.
     *
     * @param horizon the instant before which an attempt row is removed
     * @return the number of rows removed
     */
    @Modifying
    @Query("DELETE FROM NotificationLogEntity attempt WHERE attempt.attemptedAt < :horizon")
    int deleteAttemptsBefore(@Param("horizon") Instant horizon);
}
