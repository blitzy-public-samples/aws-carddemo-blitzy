package com.carddemo.ledger.repository;

import com.carddemo.ledger.entity.RejectedTransactionEntity;
import java.util.UUID;
import org.springframework.data.repository.ListCrudRepository;

/**
 * Repository for the rejected-transaction row, which carries the whole 430-byte reject record the
 * batch posting program writes to its reject dataset.
 *
 * <p>The reject layout is {@code 01 REJECT-RECORD} at {@code app/cbl/CBTRN02C.cbl:L176-L182}, and
 * {@code app/jcl/POSTTRAN.jcl:L36} allocates that dataset with {@code LRECL=430}. The interface
 * follows {@code LK-M03B-AREA} at {@code app/cbl/CBSTM03B.CBL:L100-L112}, a generic parameter area
 * whose operation code dispatches every input and output call behind one subroutine.
 * {@code domain/RejectRecorder} is the caller, and it reproduces paragraph
 * {@code 2500-WRITE-REJECT-REC} at {@code app/cbl/CBTRN02C.cbl:L446-L465}.</p>
 *
 * <p>The interface is insert-only, and the application assigns the Universally Unique Identifier
 * (UUID) that keys each row.</p>
 *
 * <p>Rationale sits in {@code card-platform/docs/decision-log.md}, mapping in
 * {@code card-platform/docs/traceability-matrix.md}, and flagged source findings in
 * {@code card-platform/docs/business-rule-flags.md}.</p>
 */
public interface RejectedTransactionRepository
        extends ListCrudRepository<RejectedTransactionEntity, UUID> {
}
