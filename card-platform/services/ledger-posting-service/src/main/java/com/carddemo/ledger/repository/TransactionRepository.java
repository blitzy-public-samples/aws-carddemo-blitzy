package com.carddemo.ledger.repository;

import com.carddemo.ledger.entity.TransactionEntity;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * Finds and stores the posted-transaction row, the {@code TRAN-RECORD} layout at
 * {@code app/cpy/CVTRA05Y.cpy:L4-L18}. The identifier is {@code TRAN-ID PIC X(16)} at
 * {@code app/cpy/CVTRA05Y.cpy:L5}, sixteen characters wide per {@code KEYS(16 0)} at
 * {@code app/jcl/TRANFILE.jcl:L53}.
 *
 * <p>The posting path inserts one row per consumed event, matching paragraph
 * {@code 2900-WRITE-TRANSACTION-FILE} at {@code app/cbl/CBTRN02C.cbl:L562-L579}, which issues
 * {@code WRITE} and never {@code REWRITE}. The Common Business Oriented Language (COBOL) ancestor
 * is the parameter area {@code LK-M03B-AREA} at {@code app/cbl/CBSTM03B.CBL:L100-L112}, whose
 * operation code dispatches each read and write.</p>
 *
 * <p>The posted-transaction row is append-only, and the interface exposes no {@code delete}.
 * {@code 2900-WRITE-TRANSACTION-FILE} at {@code app/cbl/CBTRN02C.cbl:L562-L579} issues
 * {@code WRITE}, and {@code app/cbl/} carries no {@code REWRITE} and no {@code DELETE} against the
 * transaction master. A posted transaction is a record of something that happened, so removing one
 * would leave the balance it moved unexplained. Extending {@link Repository} states that surface on
 * the interface itself.</p>
 */
public interface TransactionRepository extends Repository<TransactionEntity, String> {

    /**
     * Inserts one posted transaction, and never updates one.
     *
     * <p>The caller writes this row in the same local transaction as the balance rows it moves and
     * the idempotency marker that guards them. {@code app/cbl/CBTRN02C.cbl:L440-L442} performs the
     * three updates one after another with no rollback between them, so a failure part way through
     * leaves the source files disagreeing. One transaction removes that window.
     *
     * <p>{@link TransactionEntity#isNew()} answers {@code true} for every instance, so this method
     * inserts. An identifier the table already holds raises a primary-key violation, which reaches
     * the caller as a {@link org.springframework.dao.DataIntegrityViolationException} and rolls the
     * balance updates back with it. That is the target form of the duplicate-key limb at
     * {@code app/cbl/CBTRN02C.cbl:L566-L578}, which dumps the file status and ends the run.
     *
     * @param transaction the row to insert, carrying an application-assigned sixteen-character
     *     identifier
     * @return the inserted row
     */
    TransactionEntity save(TransactionEntity transaction);

    /**
     * Finds one posted transaction by its identifier.
     *
     * @param transactionId the sixteen-character {@code TRAN-ID}
     * @return the row, or an empty {@code Optional} when the table holds no such identifier
     */
    Optional<TransactionEntity> findById(String transactionId);

    /**
     * Reports whether a posted transaction already carries this identifier.
     *
     * <p>This is a read for a caller that wants to know, not a guard on the posting path. A read
     * followed by an insert leaves a window in which a second delivery reads nothing and both
     * insert, and skipping the insert on a hit would leave the two balance updates of that delivery
     * standing against a transaction row it did not write. The posting path therefore inserts
     * unconditionally and lets the primary key decide, matching the duplicate-key limb at
     * {@code app/cbl/CBTRN02C.cbl:L566-L578}. Duplicate delivery of one event is refused earlier
     * still, by the processed-event marker.
     *
     * @param transactionId the sixteen-character {@code TRAN-ID}
     * @return {@code true} when the table already holds the identifier
     */
    boolean existsById(String transactionId);

    /**
     * Counts the posted transactions.
     *
     * @return how many rows the table holds
     */
    long count();
}
