package com.carddemo.ledger.repository;

import com.carddemo.ledger.entity.RejectedTransactionEntity;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/**
 * Repository for the rejected-transaction row, a reduced diagnostic record of one refused
 * transaction carrying a masked card number.
 *
 * <p>The reject layout the row derives from is {@code 01 REJECT-RECORD} at
 * {@code app/cbl/CBTRN02C.cbl:L176-L182}, and {@code app/jcl/POSTTRAN.jcl:L36} allocates that
 * dataset with {@code LRECL=430}. {@link RejectedTransactionEntity} records nine of its fields
 * rather than the whole block, so no unmasked Primary Account Number is stored. The interface
 * follows {@code LK-M03B-AREA} at {@code app/cbl/CBSTM03B.CBL:L100-L112}, a generic parameter area
 * whose operation code dispatches every input and output call behind one subroutine.
 * {@code domain/RejectRecorder} is the one caller, reproducing paragraph
 * {@code 2500-WRITE-REJECT-REC} at {@code app/cbl/CBTRN02C.cbl:L446-L465}. A reject is a
 * feed-validation failure, so no Kafka listener of this service reaches this interface.</p>
 *
 * <p>The interface is insert-only, and the application assigns the Universally Unique Identifier
 * (UUID) that keys each row. Extending {@link Repository} holds the interface to that surface, so
 * {@code save} is the one write and no {@code delete} is reachable. The source writes each reject
 * to a new generation of a Generation Data Group at {@code app/jcl/POSTTRAN.jcl:L34-L38}, and a
 * generation is never updated or removed in place. A reject row records a decision already
 * reported to a caller, so it stays as written.</p>
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
