package com.carddemo.ledger.repository;

import com.carddemo.ledger.entity.RejectedTransactionEntity;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

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
 * <p>The interface is insert-only apart from one bounded retention delete, and the application
 * assigns the Universally Unique Identifier (UUID) that keys each row. Extending {@link Repository}
 * holds the interface to that surface, so {@code save} is the one write and no general
 * {@code delete} is reachable. A reject row records a decision already reported to a caller, so it
 * is never updated and never retracted.</p>
 *
 * <p>Expiring a row by age is a different act from retracting one, and the source does the first.
 * Each reject goes to a new generation of a Generation Data Group at
 * {@code app/jcl/POSTTRAN.jcl:L34-L38}, and {@code app/jcl/DALYREJS.jcl:L24-L28} defines that base
 * with {@code LIMIT(5)} and {@code SCRATCH}: the group keeps five generations, and writing a sixth
 * deletes the oldest. Every one of the six bases in {@code app/jcl/DEFGDGB.jcl} carries the same
 * two parameters. So the reject set the source keeps is bounded and self-expiring, and
 * {@link #deleteRejectedBefore} is the analogue of that bound rather than a departure from it. The
 * deletion lives in a catalogue definition rather than in a {@code PERFORM}, which is why no
 * paragraph under {@code app/cbl/} appears to remove one.</p>
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
     * Counts the reject rows this table holds.
     *
     * <p>The count spans every row ever inserted, so it is not the per-run reject counter that
     * {@code app/cbl/CBTRN02C.cbl:L230} tests before setting {@code RETURN-CODE} to 4. That counter
     * is reset by each batch run; this table is not.
     *
     * @return how many rows the table holds
     */
    long count();

    /**
     * Deletes at most {@code limit} reject rows recorded before the given instant, and returns how
     * many it removed.
     *
     * <p>{@code COMMENT ON TABLE rejected_transaction} in
     * {@code src/main/resources/db/migration/V1__schema.sql} declares
     * {@code retention=90 days; purge_key=rejected_at}, and until this method existed nothing
     * applied it. {@code domain/RetentionSweep} deleted published outbox rows and duplicate markers
     * only, so the ninety days described an intention while the table grew by one row per refused
     * feed record and never shrank. A security review found the gap.
     *
     * <p>The row is pseudonymous, not anonymous: it carries the transaction identifier, the amount,
     * the merchant and a masked card number, and the first of those resolves to a named customer
     * through this service's own transaction table. The declared window is therefore a privacy
     * horizon and not only a housekeeping one.
     *
     * <p>{@code carddemo.retention.rejected-transaction-retention-days} in
     * {@code src/main/resources/application.yml} supplies the horizon and
     * {@code domain/RetentionSweep} applies it. {@code ix_rejected_transaction_rejected_at} serves
     * both the subquery that selects the doomed rows and the delete that removes them.
     *
     * <p>{@code limit} bounds one statement, so a schema left idle for a long time cannot produce a
     * single delete that holds the table for the length of the purge. One sweep issues one bounded
     * statement and the next scheduled sweep continues where it stopped.
     *
     * @param horizon the instant before which a reject row is removed
     * @param limit   the most rows one statement removes, at least one
     * @return the number of rows removed, and 0 when none is past the horizon
     */
    @Modifying
    @Query(value = """
            DELETE FROM rejected_transaction
            WHERE id IN (SELECT id
                           FROM rejected_transaction
                          WHERE rejected_at < :horizon
                          ORDER BY rejected_at
                          LIMIT :limit)
            """, nativeQuery = true)
    int deleteRejectedBefore(@Param("horizon") Instant horizon, @Param("limit") int limit);
}
