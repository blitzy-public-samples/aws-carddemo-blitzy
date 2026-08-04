package com.carddemo.ledger.repository;

import com.carddemo.ledger.entity.TransactionCategoryBalanceEntity;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * Finds and stores the running balance one account holds for one transaction type and category.
 *
 * <p>{@link TransactionCategoryBalanceEntity.TransactionCategoryBalanceId} carries the
 * {@code TRAN-CAT-KEY} group at {@code app/cpy/CVTRA01Y.cpy:L5-L8}, whose three parts span the
 * seventeen bytes {@code app/jcl/TCATBALF.jcl:L40} declares as {@code KEYS(17 0)}.</p>
 *
 * <p>{@code findById} returns an empty {@code Optional} for a key the table does not hold and never
 * throws, and the caller then creates the row, matching {@code app/cbl/CBTRN02C.cbl:L481}.</p>
 *
 * <p>The source construct is the parameter area {@code LK-M03B-AREA} at
 * {@code app/cbl/CBSTM03B.CBL:L100-L112}, whose operation code dispatches every read and write
 * through one subroutine.</p>
 *
 * <p>The aggregate supports a keyed read and a save, and it supports no removal.
 * {@code 2700-UPDATE-TCATBAL} at {@code app/cbl/CBTRN02C.cbl:L467-L501} reads, then branches to
 * {@code 2700-A-CREATE-TCATBAL-REC} at {@code :L503} for a {@code WRITE} or to
 * {@code 2700-B-UPDATE-TCATBAL-REC} at {@code :L526} for a {@code REWRITE}. Both branches end in
 * {@code save} here. No paragraph deletes a category balance, and the accumulator it carries is
 * cleared by assignment and never by removing the row. Extending {@link Repository} keeps every
 * {@code delete} off the interface.</p>
 *
 * <p>Rationale sits in {@code card-platform/docs/decision-log.md} (planned), and the
 * source-to-target mapping in {@code card-platform/docs/traceability-matrix.md} (planned).</p>
 */
public interface TransactionCategoryBalanceRepository
        extends Repository<TransactionCategoryBalanceEntity,
                TransactionCategoryBalanceEntity.TransactionCategoryBalanceId> {

    /**
     * Finds the running balance for one account, transaction type and category.
     *
     * <p>An empty {@code Optional} is the {@code INVALID KEY} condition at
     * {@code app/cbl/CBTRN02C.cbl:L475-L479}, which raises the create flag. The caller then builds
     * a new row, matching {@code 2700-A-CREATE-TCATBAL-REC}.
     *
     * @param key the account identifier, transaction type code and transaction category code
     * @return the row, or an empty {@code Optional} when the table holds no such key
     */
    Optional<TransactionCategoryBalanceEntity> findById(
            TransactionCategoryBalanceEntity.TransactionCategoryBalanceId key);

    /**
     * Saves a category balance, whether the caller has just built it or has changed one it read.
     *
     * @param categoryBalance the row to save
     * @return the saved row
     */
    TransactionCategoryBalanceEntity save(TransactionCategoryBalanceEntity categoryBalance);

    /**
     * Counts the category balance rows.
     *
     * @return how many rows the table holds
     */
    long count();
}
