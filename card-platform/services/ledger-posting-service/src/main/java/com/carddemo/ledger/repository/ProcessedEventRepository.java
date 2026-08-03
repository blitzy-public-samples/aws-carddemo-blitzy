package com.carddemo.ledger.repository;

import com.carddemo.ledger.entity.ProcessedEventEntity;
import java.util.UUID;
import org.springframework.data.repository.ListCrudRepository;

/**
 * Reads and writes the processed-event marker, so a duplicate delivery changes nothing.
 *
 * <p>The consumer {@code messaging/TransactionAuthorizedConsumer} checks for the event identifier
 * before acting, then writes the marker in the same local transaction as the business effect.</p>
 *
 * <p>The repository pattern's Common Business Oriented Language (COBOL) ancestor is the generic
 * parameter area {@code LK-M03B-AREA} at {@code app/cbl/CBSTM03B.CBL:L100-L112}, whose operation
 * code dispatches every read and write behind one subroutine.</p>
 *
 * <p>ADDITIVE. No COBOL program checks for a duplicate delivery: a failed transaction write at
 * {@code app/cbl/CBTRN02C.cbl:L562-L579} ends the run at L577. Rationale sits in
 * {@code card-platform/docs/decision-log.md}.</p>
 */
public interface ProcessedEventRepository extends ListCrudRepository<ProcessedEventEntity, UUID> {
}
