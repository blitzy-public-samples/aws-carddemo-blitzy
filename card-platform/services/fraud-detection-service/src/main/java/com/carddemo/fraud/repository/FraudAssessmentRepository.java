package com.carddemo.fraud.repository;

import com.carddemo.fraud.entity.FraudAssessmentEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes the risk assessment the fraud consumer records once per authorized transaction.
 *
 * <p>No Common Business Oriented Language (COBOL) ancestor: no COBOL program scores risk. The
 * identifier is the transaction identifier, and the interface shape follows {@code LK-M03B-AREA} at
 * {@code app/cbl/CBSTM03B.CBL:L99-L114}, shape only and no logic.</p>
 */
@Repository
public interface FraudAssessmentRepository extends JpaRepository<FraudAssessmentEntity, String> {

    /**
     * One account's newest assessments, at most {@code limit} of them.
     *
     * <p>{@code accountId} is opaque text of eleven digits, so a leading zero survives.
     *
     * <p>The order is assessment time descending and then transaction identifier descending. The
     * second column is not decoration: assessment time is not unique, because one consumer batch
     * assesses several transactions and the rows can land on the same microsecond. Ordering by time
     * alone leaves those rows in whatever order the plan happens to produce, so a page boundary
     * falling inside a group of equal times can repeat a row on one page and skip another. The
     * identifier is the primary key, so adding it makes the order total and the boundary exact.
     *
     * <p>Index {@code ix_fraud_assessment_account_cursor} carries these three columns in this
     * order, so the page is read from the index rather than sorted out of the account's history.
     *
     * @param accountId the account whose assessments to return, eleven digits of text
     * @param limit     how many rows to return, one more than the page serves
     * @return the newest rows in that order, empty when the account holds none
     */
    List<FraudAssessmentEntity> findByAccountIdOrderByAssessedAtDescTransactionIdDesc(
            String accountId, Limit limit);

    /**
     * The page of one account's assessments following one row of that account.
     *
     * <p>The bound is the pair the cursor names, and it is exclusive: a row is returned when its
     * assessment time is older, or when its time is equal and its identifier sorts lower. That is
     * the same total order {@link #findByAccountIdOrderByAssessedAtDescTransactionIdDesc} applies,
     * so the row the cursor names is never returned twice and no row between two pages is skipped.
     *
     * <p>The account equality is repeated here rather than trusted from the cursor. The cursor
     * names a position, not an entitlement, so binding the query to the account the caller asked
     * for keeps a cursor issued for one account from reading another's rows.
     *
     * <p>Cost does not grow with the page reached. The predicate is a range over
     * {@code ix_fraud_assessment_account_cursor}, so the tenth page and the ten-thousandth page
     * both read one page of index entries. An offset does grow: it reads and discards every row
     * before the one asked for.
     *
     * @param accountId  the account whose assessments to return, eleven digits of text
     * @param assessedAt the assessment time the cursor names, the exclusive upper bound
     * @param transactionId the identifier the cursor names, breaking a tie on {@code assessedAt}
     * @param limit      how many rows to return, one more than the page serves
     * @return the rows following that position, empty when the position is the account's oldest
     */
    @Query("""
           SELECT a FROM FraudAssessmentEntity a
            WHERE a.accountId = :accountId
              AND (a.assessedAt < :assessedAt
                   OR (a.assessedAt = :assessedAt AND a.transactionId < :transactionId))
            ORDER BY a.assessedAt DESC, a.transactionId DESC
           """)
    List<FraudAssessmentEntity> findPageAfter(@Param("accountId") String accountId,
            @Param("assessedAt") Instant assessedAt,
            @Param("transactionId") String transactionId,
            Limit limit);

    /**
     * Deletes at most {@code limit} assessments recorded before the given instant, and returns how
     * many it removed.
     *
     * <p>ADDITIVE, as this whole service is: no COBOL program scores risk, so none expires a score
     * either. The horizon is not an invention of this method. {@code COMMENT ON TABLE
     * fraud_assessment} in {@code src/main/resources/db/migration/V1__schema.sql} declares
     * {@code retention=90 days; purge_key=assessed_at}, and until this method existed nothing
     * applied it: the table grew by one row per authorized transaction and only the sweep's absence
     * kept the declaration from being true. A horizon nothing enforces is worse than none, because
     * a reader takes the table for bounded and it is not.
     *
     * <p>The row is pseudonymous rather than anonymous. {@code account_id} and
     * {@code transaction_id} resolve to a named customer through the account and ledger services,
     * so the declared window is a privacy horizon and not only a housekeeping one.
     *
     * <p>{@code carddemo.retention.assessment-retention-days} in
     * {@code src/main/resources/application.yml} supplies the horizon and
     * {@code domain/RetentionSweep} applies it. {@code ix_fraud_assessment_assessed_at} serves both
     * the subquery that selects the doomed rows and the delete that removes them, so neither scans
     * the table.
     *
     * <p>{@code limit} bounds one statement, so a schema left idle for a long time cannot produce a
     * single delete that holds the table for the length of the purge. One sweep issues one bounded
     * statement and the next scheduled sweep continues where it stopped.
     *
     * @param horizon the instant before which an assessment is removed
     * @param limit   the most rows one statement removes, at least one
     * @return the number of rows removed, and 0 when none is past the horizon
     */
    @Modifying
    @Query(value = """
            DELETE FROM fraud_assessment
            WHERE transaction_id IN (SELECT transaction_id
                                       FROM fraud_assessment
                                      WHERE assessed_at < :horizon
                                      ORDER BY assessed_at
                                      LIMIT :limit)
            """, nativeQuery = true)
    int deleteAssessedBefore(@Param("horizon") Instant horizon, @Param("limit") int limit);
}
