package com.carddemo.ledger.repository;

import com.carddemo.ledger.entity.RejectedTransactionEntity;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/**
 * Repository for the rejected-transaction row, which carries the whole 430-byte reject record the
 * batch posting program writes to its reject dataset.
 *
 * <p>The reject layout is {@code 01 REJECT-RECORD} at {@code app/cbl/CBTRN02C.cbl:L176-L182}, and
 * {@code app/jcl/POSTTRAN.jcl:L36} allocates that dataset with {@code LRECL=430}. The interface
 * follows {@code LK-M03B-AREA} at {@code app/cbl/CBSTM03B.CBL:L100-L112}, a generic parameter area
 * whose operation code dispatches every input and output call behind one subroutine.
 * The planned reject recorder in the {@code domain} package is to be the caller, reproducing
 * paragraph {@code 2500-WRITE-REJECT-REC} at {@code app/cbl/CBTRN02C.cbl:L446-L465}. That
 * component is not authored yet.</p>
 *
 * <p>The interface is insert-only, and the application assigns the Universally Unique Identifier
 * (UUID) that keys each row. Extending {@link Repository} holds the interface to that surface, so
 * {@code save} is the one write and no {@code delete} is reachable. The source writes each reject
 * to a new generation of a Generation Data Group at {@code app/jcl/POSTTRAN.jcl:L34-L38}, and a
 * generation is never updated or removed in place. A reject row records a decision already
 * reported to a caller, so it stays as written.</p>
 *
 * <p>Rationale sits in {@code card-platform/docs/decision-log.md} (planned), mapping in
 * {@code card-platform/docs/traceability-matrix.md} (planned), and flagged source findings in
 * {@code card-platform/docs/business-rule-flags.md} (planned).</p>
 */
public interface RejectedTransactionRepository
        extends Repository<RejectedTransactionEntity, UUID> {

    /**
     * Inserts one reject row.
     *
     * @param rejected the row to insert, carrying an application-assigned identifier
     * @return the inserted row
     */
    RejectedTransactionEntity save(RejectedTransactionEntity rejected);

    /**
     * Counts the reject rows.
     *
     * <p>{@code app/cbl/CBTRN02C.cbl:L230} sets {@code RETURN-CODE} to 4 when its reject counter is
     * above zero, which is the source treating rejects as expected traffic and not as an error.
     * This count is the same number.
     *
     * @return how many rows the table holds
     */
    long count();
}
