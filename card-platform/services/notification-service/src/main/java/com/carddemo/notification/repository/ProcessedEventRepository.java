package com.carddemo.notification.repository;

import com.carddemo.notification.entity.ProcessedEventEntity;
import java.util.UUID;
import org.springframework.data.repository.ListCrudRepository;

/**
 * Reads and writes the marker that records one processed event identifier, so a second delivery
 * of that event changes nothing.
 *
 * <p>The planned listeners {@code messaging/TransactionPostedConsumer} and
 * {@code messaging/FraudFlaggedConsumer} check the event identifier before acting, then write the
 * marker in the same local transaction as the read-model row it guards.
 *
 * <p>The repository pattern's Common Business Oriented Language (COBOL) ancestor is the generic
 * parameter area {@code LK-M03B-AREA} at {@code app/cbl/CBSTM03B.CBL:L100-L112}, whose operation
 * code dispatches every read and write behind one subroutine.
 *
 * <p>ADDITIVE: no COBOL program detects a duplicate delivery, and the transaction write at
 * {@code app/cbl/CBTRN02C.cbl:L562-L579} ends the run at L577.
 *
 * <p>Rationale sits in {@code card-platform/docs/decision-log.md}, and the source-to-target
 * mapping in {@code card-platform/docs/traceability-matrix.md}.
 */
public interface ProcessedEventRepository extends ListCrudRepository<ProcessedEventEntity, UUID> {
}
