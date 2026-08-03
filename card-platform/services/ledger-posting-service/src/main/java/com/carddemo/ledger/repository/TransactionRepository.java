package com.carddemo.ledger.repository;

import com.carddemo.ledger.entity.TransactionEntity;
import org.springframework.data.repository.ListCrudRepository;

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
 * <p>{@code card-platform/docs/decision-log.md} holds the rationale, and
 * {@code card-platform/docs/traceability-matrix.md} holds the source-to-target mapping.</p>
 */
public interface TransactionRepository extends ListCrudRepository<TransactionEntity, String> {
}
