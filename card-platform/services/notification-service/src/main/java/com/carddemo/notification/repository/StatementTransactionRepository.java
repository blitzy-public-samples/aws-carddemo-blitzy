package com.carddemo.notification.repository;

import com.carddemo.cobol.PanMasker;
import com.carddemo.notification.entity.StatementTransactionEntity;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
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
 * <p>The finders below take a limit, and each caller supplies the one its own work needs. An alert
 * renders at most {@code NotificationRenderer.MAXIMUM_STATEMENT_ROWS} rows and passes that number,
 * and {@code GET /notifications/&#123;cardToken&#125;} passes one page.
 *
 * <p>No read of this interface is unbounded. The history route once passed
 * {@link Limit#unlimited()}, so one request materialised every retained row of a card, totalled them
 * in memory and copied the list: the work and the response both grew with one card's history and
 * nothing capped either. The route now reads whole-history metadata through
 * {@link #totalsOfCard(String)}, which is one keyed read of {@code statement_card_total}, and
 * returns detail rows one bounded page at a time through
 * {@link #findByIdCardTokenAndIdTransactionIdGreaterThanOrderByIdTransactionIdAsc(String, String,
 * Limit)}. The count and the total still cover the whole card, so they describe the row set
 * {@code app/cbl/CBSTM03A.CBL:L429} would have totalled between two key breaks.
 *
 * <p>An alert reads the descending finder under
 * {@code NotificationRenderer.MAXIMUM_STATEMENT_ROWS} and reverses the rows it renders,
 * because a bounded alert has to carry the transaction that triggered it, and that
 * transaction always holds the highest identifier of its card. Reading the ascending
 * finder under the same ceiling would return the card's oldest rows and omit the
 * triggering one once a card passed the ceiling.
 *
 * <p>The one-interface-per-aggregate shape comes from the generic parameter area
 * {@code 01 LK-M03B-AREA} at {@code app/cbl/CBSTM03B.CBL:L100-L112}, whose operation code
 * dispatches every read and write behind one subroutine.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}. Source-to-target mapping:
 * {@code card-platform/docs/traceability-matrix.md}. Flagged findings:
 * {@code card-platform/docs/business-rule-flags.md}.
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
     * more rows than the limit costs one read of that many rows. Every caller supplies a bounded
     * limit; {@link Limit#unlimited()} reaches this method from nowhere.
     *
     * @param cardToken the stored card token
     * @param limit the greatest number of rows to return
     * @return the card's first {@code limit} rows in that order, and empty when the read model holds
     *         none for the card
     */
    List<StatementTransactionEntity> findByIdCardTokenOrderByIdTransactionIdAsc(String cardToken,
            Limit limit);

    /**
     * Returns at most {@code limit} of one card's most recent transactions, in descending
     * transaction-identifier order.
     *
     * <p>Same rows, same index, opposite end. The primary key of {@code statement_transaction}
     * serves this order as a backward range scan, so a bounded read costs the rows it returns rather
     * than the card's whole history.
     *
     * <p>Descending order is what makes a bounded alert honest. An identifier is minted from one
     * monotonic sequence and left-padded with zeros to the sixteen characters
     * {@code TRAN-ID PIC X(16)} holds, by
     * {@code authorization-service/.../domain/TransactionIdentifierSource}, so its text order is its
     * allocation order. The transaction an alert reports has therefore just taken the highest
     * identifier its card holds, and {@code messaging/TransactionPostedConsumer} writes that row
     * before it asks for the alert. Reading from the descending end under any ceiling of at least one
     * row consequently always includes it. The ascending finder under a ceiling does not: past the
     * ceiling it returns rows the cardholder has already seen and leaves out the one the alert is
     * about, and the total then covers the wrong subset.
     *
     * <p>A caller that renders these rows reverses them, because
     * {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)} at {@code app/jcl/CREASTMT.JCL:L53} presents a
     * statement oldest first.
     *
     * @param cardToken the stored card token
     * @param limit the greatest number of rows to return, or {@link Limit#unlimited()} for every row
     * @return the card's last {@code limit} rows, newest first, and empty when the read model holds
     *         none for the card
     */
    List<StatementTransactionEntity> findByIdCardTokenOrderByIdTransactionIdDesc(String cardToken,
            Limit limit);

    /**
     * Returns at most {@code limit} of one card's transactions after {@code afterTransactionId}, in
     * ascending transaction-identifier order.
     *
     * <p>The keyset continuation of {@link #findByIdCardTokenOrderByIdTransactionIdAsc(String,
     * Limit)}. Both walk the primary key of {@code statement_transaction} as one range scan, so the
     * cost of a page is the size of that page wherever the page sits in the history. An offset page
     * would instead read and discard every row before it, which makes the last page of a long history
     * the most expensive one to serve.
     *
     * <p>The bound is exclusive, so the row named by the cursor is not returned again and no row is
     * returned twice. The cursor is the transaction identifier of the last row of the previous page,
     * which every response already carries in its items: nothing about a card is disclosed by it that
     * the page it came from did not already disclose.
     *
     * @param cardToken         the stored card token
     * @param afterTransactionId the identifier the previous page ended on, excluded from this page
     * @param limit             the greatest number of rows to return
     * @return the card's next rows in that order, and empty when the cursor names its last row
     */
    List<StatementTransactionEntity>
            findByIdCardTokenAndIdTransactionIdGreaterThanOrderByIdTransactionIdAsc(
                    String cardToken, String afterTransactionId, Limit limit);

    /**
     * Returns one card's whole-history metadata as a single row, or empty when the read model has
     * never held a row for that card.
     *
     * <p>One primary-key lookup of {@code statement_card_total} answers everything the history
     * response needs about the rows it does not carry: how many there are, what they sum to, and the
     * masked number that names the card. Reading them from a maintained row is what lets the response
     * hold a bounded page while still describing the whole card, at a cost that does not grow with
     * the card.
     *
     * <p>This was an aggregate over {@code card_token} until a performance review measured it. On a
     * card holding 21,299 rows it cost a sequential scan of 1,228 buffers and 7.775 ms on every
     * request, against 4 buffers and 0.048 ms on a card holding one, and the page size changed
     * neither figure: the page was bounded and the three numbers beside it were not.
     * {@code src/main/resources/db/migration/V10__statement_card_totals.sql} maintains those numbers
     * by delta instead, in the same statements that change the rows they describe.
     *
     * <p>{@code absoluteTotal} is the reason this returns three numbers rather than two.
     * {@code domain/NotificationService} reproduces {@code ADD TRNX-AMT TO WS-TOTAL-AMT} at
     * {@code app/cbl/CBSTM03A.CBL:L429}, which stores into {@code WS-TOTAL-AMT PIC S9(9)V99} at
     * {@code app/cbl/CBSTM03A.CBL:L65} and therefore drops high-order digits at <em>every</em>
     * addition. A single sum drops them at most once, and the two answers differ when a running total
     * crosses that field's ceiling and the amounts do not all share one sign. Because the running
     * total never exceeds the sum of the magnitudes, {@code absoluteTotal} fitting the field proves no
     * addition could have overflowed, and the sum is then exactly what the source would have
     * accumulated. That is the test {@code NotificationService} applies.
     *
     * <p>A card the read model has never held answers nothing, where the aggregate answered one row
     * of zeros. A card whose every row retention has removed answers a row carrying a count of zero,
     * because the card stays known. Both mean the same thing to the route, which has one answer for
     * a card with no history.
     *
     * @param cardToken the stored card token
     * @return that card's totals, or empty when no row of this table names the card
     */
    @Query(value = """
            SELECT transaction_count  AS transactionCount,
                   total_amount       AS totalAmount,
                   absolute_total     AS absoluteTotal,
                   masked_card_number AS maskedCardNumber
              FROM statement_card_total
             WHERE card_token = :cardToken
            """, nativeQuery = true)
    Optional<CardHistoryTotals> totalsOfCard(@Param("cardToken") String cardToken);

    /**
     * The row {@link #totalsOfCard(String)} answers with.
     *
     * <p>An interface rather than a record, so Spring Data maps the aliased columns of the native
     * statement by accessor name without a constructor contract to keep in step.
     */
    interface CardHistoryTotals {

        /**
         * @return how many rows the read model holds for the card, and zero when it holds none
         */
        long getTransactionCount();

        /**
         * @return the exact sum of those rows' amounts at the scale the column declares, zero when
         *         there are none
         */
        BigDecimal getTotalAmount();

        /**
         * @return the sum of the magnitudes of those amounts, which bounds every running total the
         *         source's accumulation passes through
         */
        BigDecimal getAbsoluteTotal();

        /**
         * @return the masked card number the card's rows carry, or {@code null} for a card whose
         *         every row retention has removed. Every row of one card carries the same masked
         *         form, so which row the value was taken from cannot matter
         */
        String getMaskedCardNumber();
    }

    /**
     * Stores one read-model row under its composite key, whether or not that key is already held.
     *
     * <p>One statement, and that is the point of it. Reading the key to choose a diagnostic line and
     * then calling {@code save} would take three round trips for one event, since {@code save} on an
     * entity carrying an assigned key is a merge and performs a select of its own. This upsert is one
     * statement, and it is the {@code WRITE} and {@code REWRITE} of {@code app/cbl/CBSTM03B.CBL}
     * expressed as the single operation the read model needs.
     *
     * <p>Statement-level atomicity is what makes a duplicate delivery harmless here. It runs in the
     * transaction that claims the processed-event marker, so the row and the marker commit together,
     * and {@code ON CONFLICT} makes a redelivery of one event store the same twelve values it stored
     * the first time rather than raising on the key. {@code app/cbl/CBTRN02C.cbl:L562-L579} had no such
     * guard: a repeated write of one key failed its status test and abended.
     *
     * <p>The twelve non-key columns are all replaced. Every one of them is copied from the event
     * rather than accumulated, so a later delivery of one transaction carries the same values and
     * replacing them cannot lose anything a previous delivery established.
     *
     * <p>The statement also carries this card's totals forward, which is why it opens with two
     * common table expressions. {@code prior} reads the row as it stood before the statement, and
     * {@code totals} adds the difference that reading implies to
     * {@code statement_card_total}: one row and this amount where the key was not held, and no row
     * and the difference between the two amounts where it was. PostgreSQL runs a data-modifying
     * expression exactly once and to completion whether or not the statement that follows reads its
     * output, and every expression here sees the same snapshot, so the delta describes the row this
     * statement is about to replace rather than the row it leaves behind. The totals therefore stay
     * equal to an aggregate over the rows, and they stay equal through a redelivery.
     *
     * <p>That is what makes the history route's whole-card figures cost one index lookup. See
     * {@link #totalsOfCard(String)} and
     * {@code src/main/resources/db/migration/V10__statement_card_totals.sql}.
     *
     * @param cardToken           the card token, the first part of the key
     * @param transactionId       the transaction identifier, the second part
     * @param maskedCardNumber    masked card number, display data alone
     * @param typeCode            {@code TRNX-TYPE-CD PIC X(02)}
     * @param categoryCode        {@code TRNX-CAT-CD PIC 9(04)}, held as display text
     * @param source              {@code TRNX-SOURCE PIC X(10)}
     * @param description         {@code TRNX-DESC PIC X(100)}
     * @param amount              {@code TRNX-AMT PIC S9(09)V99}
     * @param merchantId          {@code TRNX-MERCHANT-ID PIC 9(09)}, held as display text
     * @param merchantName        {@code TRNX-MERCHANT-NAME PIC X(50)}
     * @param merchantCity        {@code TRNX-MERCHANT-CITY PIC X(50)}
     * @param merchantZip         {@code TRNX-MERCHANT-ZIP PIC X(10)}
     * @param originTimestamp     {@code TRNX-ORIG-TS PIC X(26)}, compared as characters
     * @param processingTimestamp {@code TRNX-PROC-TS PIC X(26)}, compared as characters
     * @return the number of rows the statement wrote, which is one
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            WITH prior AS (
                SELECT amount
                  FROM statement_transaction
                 WHERE card_token = :cardToken
                   AND transaction_id = :transactionId
            ), totals AS (
                INSERT INTO statement_card_total AS held (card_token, transaction_count,
                            total_amount, absolute_total, masked_card_number)
                     VALUES (:cardToken,
                            CASE WHEN EXISTS (SELECT 1 FROM prior) THEN 0 ELSE 1 END,
                            :amount - COALESCE((SELECT amount FROM prior), 0),
                            ABS(:amount) - COALESCE((SELECT ABS(amount) FROM prior), 0),
                            :maskedCardNumber)
                ON CONFLICT ON CONSTRAINT pk_statement_card_total DO UPDATE
                        SET transaction_count  = held.transaction_count
                                                 + EXCLUDED.transaction_count,
                            total_amount       = held.total_amount + EXCLUDED.total_amount,
                            absolute_total     = held.absolute_total + EXCLUDED.absolute_total,
                            masked_card_number = EXCLUDED.masked_card_number
                  RETURNING held.card_token
            )
            INSERT INTO statement_transaction (card_token, transaction_id, masked_card_number,
                        type_code, category_code, source, description, amount, merchant_id,
                        merchant_name, merchant_city, merchant_zip, origin_timestamp,
                        processing_timestamp)
                 VALUES (:cardToken, :transactionId, :maskedCardNumber,
                        :typeCode, :categoryCode, :source, :description, :amount, :merchantId,
                        :merchantName, :merchantCity, :merchantZip, :originTimestamp,
                        :processingTimestamp)
            ON CONFLICT ON CONSTRAINT pk_statement_transaction DO UPDATE
                    SET masked_card_number   = EXCLUDED.masked_card_number,
                        type_code            = EXCLUDED.type_code,
                        category_code        = EXCLUDED.category_code,
                        source               = EXCLUDED.source,
                        description          = EXCLUDED.description,
                        amount               = EXCLUDED.amount,
                        merchant_id          = EXCLUDED.merchant_id,
                        merchant_name        = EXCLUDED.merchant_name,
                        merchant_city        = EXCLUDED.merchant_city,
                        merchant_zip         = EXCLUDED.merchant_zip,
                        origin_timestamp     = EXCLUDED.origin_timestamp,
                        processing_timestamp = EXCLUDED.processing_timestamp
            """, nativeQuery = true)
    int upsertRow(@Param("cardToken") String cardToken,
            @Param("transactionId") String transactionId,
            @Param("maskedCardNumber") String maskedCardNumber,
            @Param("typeCode") String typeCode,
            @Param("categoryCode") String categoryCode,
            @Param("source") String source,
            @Param("description") String description,
            @Param("amount") BigDecimal amount,
            @Param("merchantId") String merchantId,
            @Param("merchantName") String merchantName,
            @Param("merchantCity") String merchantCity,
            @Param("merchantZip") String merchantZip,
            @Param("originTimestamp") String originTimestamp,
            @Param("processingTimestamp") String processingTimestamp);

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
     * <p>The statement subtracts what it removes from {@code statement_card_total} in the same
     * breath. {@code doomed} is the bounded set of rows this call takes, {@code removed} groups it
     * per card, and {@code adjusted} takes those figures off each card's totals; the {@code DELETE}
     * that follows removes the same rows and is what the return value counts, because
     * {@code domain/RetentionSweep} reads that number to tell a pass that finished from one that
     * filled its bound. PostgreSQL runs {@code adjusted} exactly once and to completion even though
     * the {@code DELETE} reads none of its output, and both read one materialised {@code doomed}, so
     * the rows counted off the totals are the rows removed and no others.
     *
     * <p>A card whose every row this removes keeps its totals row, carrying a count of zero. That is
     * a card the read model knows and holds nothing for, which is the answer
     * {@code GET /notifications/&#123;cardToken&#125;} already gave: no history. The set of such rows
     * is bounded by the number of cards the read model has ever held.
     *
     * @param horizon the timestamp text before which a row is removed, in the source's own
     *                twenty-six character form
     * @param limit   the most rows one statement removes, at least one
     * @return the number of rows removed, and 0 when none is past the horizon
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            WITH doomed AS (
                SELECT card_token, transaction_id, amount
                  FROM statement_transaction
                 WHERE processing_timestamp < :horizon
                 ORDER BY processing_timestamp
                 LIMIT :limit
            ), removed AS (
                SELECT card_token,
                       COUNT(*)         AS rows_removed,
                       SUM(amount)      AS amount_removed,
                       SUM(ABS(amount)) AS magnitude_removed
                  FROM doomed
                 GROUP BY card_token
            ), adjusted AS (
                UPDATE statement_card_total AS held
                   SET transaction_count = held.transaction_count - removed.rows_removed,
                       total_amount      = held.total_amount - removed.amount_removed,
                       absolute_total    = held.absolute_total - removed.magnitude_removed
                  FROM removed
                 WHERE held.card_token = removed.card_token
              RETURNING held.card_token
            )
            DELETE FROM statement_transaction AS stored
             USING doomed
             WHERE stored.card_token = doomed.card_token
               AND stored.transaction_id = doomed.transaction_id
            """, nativeQuery = true)
    int deleteProcessedBefore(@Param("horizon") String horizon, @Param("limit") int limit);
}
