package com.carddemo.notification.repository;

import com.carddemo.notification.entity.StatementTransactionEntity;
import java.util.List;
import org.springframework.data.repository.ListCrudRepository;

/**
 * Reads and writes the card-keyed statement read model this service builds from the events it
 * consumes, held in the table {@code statement_transaction}.
 *
 * <p>The composite key is the group {@code 05 TRNX-KEY.} at {@code app/cpy/COSTM01.CPY:L21}:
 * {@code TRNX-CARD-NUM PIC X(16)} at L22 then {@code TRNX-ID PIC X(16)} at L23. The cluster
 * definition declares it {@code KEYS(32 0)} at {@code app/jcl/CREASTMT.JCL:L30}, and the group key
 * {@code FD-TRNXS-ID} at {@code app/cbl/CBSTM03B.CBL:L59-L62} carries the same two parts in the
 * same order.
 *
 * <p>The one-interface-per-aggregate shape comes from the generic parameter area
 * {@code 01 LK-M03B-AREA} at {@code app/cbl/CBSTM03B.CBL:L100-L112}, whose operation code
 * dispatches every read and write behind one subroutine.
 *
 * <p>Rationale sits in {@code card-platform/docs/decision-log.md}, the source-to-target mapping in
 * {@code card-platform/docs/traceability-matrix.md}, and flagged findings in
 * {@code card-platform/docs/business-rule-flags.md} (all planned).
 */
public interface StatementTransactionRepository
        extends ListCrudRepository<StatementTransactionEntity,
                                   StatementTransactionEntity.StatementTransactionId> {

    /**
     * Returns one card's transactions in ascending transaction-identifier order.
     *
     * <p>The argument is the card number as stored, in masked form: twelve mask characters then the
     * last four digits, sixteen characters in the {@code CHAR(16)} key column.
     *
     * @param cardNumber the stored, masked card number
     * @return the card's rows in that order, and empty when the read model holds none for the card
     */
    List<StatementTransactionEntity> findByIdCardNumberOrderByIdTransactionIdAsc(String cardNumber);
}
