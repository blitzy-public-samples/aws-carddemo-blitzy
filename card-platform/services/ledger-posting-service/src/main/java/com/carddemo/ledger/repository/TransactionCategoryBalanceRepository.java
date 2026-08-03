package com.carddemo.ledger.repository;

import com.carddemo.ledger.entity.TransactionCategoryBalanceEntity;
import org.springframework.data.repository.ListCrudRepository;

/**
 * Finds and stores the running balance one account holds for one transaction type and category.
 *
 * <p>{@link TransactionCategoryBalanceEntity.TransactionCategoryBalanceId} carries the
 * {@code TRAN-CAT-KEY} group at {@code app/cpy/CVTRA01Y.cpy:L5-L8}, whose three parts span the
 * seventeen bytes {@code app/jcl/TCATBALF.jcl:L40} declares as {@code KEYS(17 0)}.</p>
 *
 * <p>The inherited {@code findById} returns an empty {@code Optional} for a key the table does not
 * hold and never throws, and the caller then creates the row, matching
 * {@code app/cbl/CBTRN02C.cbl:L481}.</p>
 *
 * <p>The source construct is the parameter area {@code LK-M03B-AREA} at
 * {@code app/cbl/CBSTM03B.CBL:L100-L112}, whose operation code dispatches every read and write
 * through one subroutine.</p>
 *
 * <p>Rationale sits in {@code card-platform/docs/decision-log.md}, and the source-to-target
 * mapping in {@code card-platform/docs/traceability-matrix.md}.</p>
 */
public interface TransactionCategoryBalanceRepository
        extends ListCrudRepository<TransactionCategoryBalanceEntity,
                TransactionCategoryBalanceEntity.TransactionCategoryBalanceId> {
}
