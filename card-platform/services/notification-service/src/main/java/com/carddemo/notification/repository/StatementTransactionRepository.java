package com.carddemo.notification.repository;

import com.carddemo.cobol.PanMasker;
import com.carddemo.notification.entity.StatementTransactionEntity;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

/**
 * Reads and writes the card-keyed statement read model this service builds from the events it
 * consumes, held in the table {@code statement_transaction}.
 *
 * <p>The composite key is the group {@code 05 TRNX-KEY.} at {@code app/cpy/COSTM01.CPY:L21}:
 * {@code TRNX-CARD-NUM PIC X(16)} at L22 then {@code TRNX-ID PIC X(16)} at L23. The cluster
 * definition declares it {@code KEYS(32 0)} at {@code app/jcl/CREASTMT.JCL:L30}, and the group key
 * {@code FD-TRNXS-ID} at {@code app/cbl/CBSTM03B.CBL:L59-L62} carries the same two parts in the
 * same order. The card half is the card token, not a card number: a masked number identifies no
 * single card and a full one belongs in no index.
 *
 * <p>The finder below takes a limit, and each caller supplies the one its own work needs.
 * {@code GET /notifications/&#123;cardNumber&#125;} passes {@link Limit#unlimited()}, because
 * {@code app/cbl/CBSTM03A.CBL:L429} totals every row of one card between two key breaks. An alert
 * renders at most {@code NotificationRenderer.MAXIMUM_STATEMENT_ROWS} rows and passes that number.
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
     * Returns at most {@code limit} of one card's transactions, in ascending transaction-identifier
     * order.
     *
     * <p>The first argument is the card token as stored: {@value PanMasker#CARD_TOKEN_LENGTH}
     * lower-case hexadecimal characters in the {@code CHAR(64)} key column. The order is the one
     * {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)} at {@code app/jcl/CREASTMT.JCL:L53} produced, and
     * the primary key of {@code statement_transaction} serves it as a range scan.
     *
     * <p>A bounded limit reaches the database as a row count on the statement, so a card holding
     * more rows than the limit costs one read of that many rows. {@link Limit#unlimited()} sets no row
     * count and returns every row of the card, which is what
     * {@code GET /notifications/&#123;cardNumber&#125;} reads.
     *
     * @param cardToken the stored card token
     * @param limit the greatest number of rows to return, or {@link Limit#unlimited()} for every row
     * @return the card's first {@code limit} rows in that order, and empty when the read model holds
     *         none for the card
     */
    List<StatementTransactionEntity> findByIdCardTokenOrderByIdTransactionIdAsc(String cardToken,
            Limit limit);

    /**
     * Deletes at most {@code limit} read-model rows whose processing timestamp precedes
     * {@code horizon}, and returns how many it removed.
     *
     * <p>The horizon is text, not an instant. {@code processing_timestamp} is {@code CHAR(26)},
     * carrying {@code TRNX-PROC-TS PIC X(26)} of {@code app/cpy/COSTM01.CPY}, and
     * {@code app/cbl/CBTRN02C.cbl:L414-L420} compares such a field as characters rather than as a
     * date. Comparing it as text here keeps that behaviour and matches
     * {@code ix_statement_transaction_processing_timestamp}, whose order is lexical.
     *
     * <p>{@code limit} bounds one statement, and {@code domain/RetentionSweeper} repeats the call
     * until it removes fewer rows than it asked for. This table gains one row per posted
     * transaction and backs {@code GET /notifications/&#123;cardNumber&#125;}, so its horizon is the
     * longest this service applies.
     *
     * @param horizon the timestamp text before which a row is removed, in the source's own
     *                twenty-six character form
     * @param limit   the most rows one statement removes, at least one
     * @return the number of rows removed, and 0 when none is past the horizon
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            DELETE FROM statement_transaction
            WHERE (card_token, transaction_id) IN (SELECT card_token, transaction_id
                                                     FROM statement_transaction
                                                    WHERE processing_timestamp < :horizon
                                                    ORDER BY processing_timestamp
                                                    LIMIT :limit)
            """, nativeQuery = true)
    int deleteProcessedBefore(@Param("horizon") String horizon, @Param("limit") int limit);
}
