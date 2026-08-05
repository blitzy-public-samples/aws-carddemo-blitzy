package com.carddemo.card.repository;

import com.carddemo.card.entity.ProcessedEventEntity;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

/**
 * Reads and writes {@code processed_event}, the table naming every event identifier this service
 * has already handled.
 *
 * <p>Transformed from the called input and output subroutine {@code app/cbl/CBSTM03B.CBL}, whose
 * parameter area spans {@code app/cbl/CBSTM03B.CBL:L100-L112}. {@code LK-M03B-KEY PIC X(25)} at
 * {@code app/cbl/CBSTM03B.CBL:L110} holds the key, and a Universally Unique Identifier
 * ({@link UUID}) takes it over. {@code LK-M03B-FLDT PIC X(1000)} at
 * {@code app/cbl/CBSTM03B.CBL:L112} holds the record, and {@link ProcessedEventEntity} takes it
 * over.
 *
 * <p>{@code LK-M03B-DD PIC X(08)} at {@code app/cbl/CBSTM03B.CBL:L101} selects a dataset, and one
 * interface serves one table. {@code LK-M03B-RC PIC X(02)} at {@code app/cbl/CBSTM03B.CBL:L109}
 * carries an operation's outcome, and the {@code boolean} returned below takes over that role.
 * {@code LK-M03B-KEY-LN PIC S9(4)} at {@code app/cbl/CBSTM03B.CBL:L111} and the open and close
 * codes at {@code app/cbl/CBSTM03B.CBL:L103-L104} map onto nothing.
 *
 * <p>Two of the six operation codes declared at {@code app/cbl/CBSTM03B.CBL:L103-L108} reach a
 * method. Code {@code 'K'}, the condition {@code M03B-READ-K} at
 * {@code app/cbl/CBSTM03B.CBL:L106}, becomes the lookup below and the inherited {@code findById}.
 * Code {@code 'W'}, the condition {@code M03B-WRITE} at {@code app/cbl/CBSTM03B.CBL:L107},
 * becomes the inherited {@code save}. {@code app/cbl/CBSTM03B.CBL} declares that write code and
 * implements it nowhere, opening each of its four datasets for input at
 * {@code app/cbl/CBSTM03B.CBL:L136}, L160, L184 and L209.
 *
 * <p>No COBOL ancestor: no Common Business Oriented Language (COBOL) program detects a duplicate
 * delivery. Paragraph {@code 2900-WRITE-TRANSACTION-FILE} at {@code app/cbl/CBTRN02C.cbl:L562-L579}
 * writes each posted transaction, and a duplicate key fails its status test at L566. That failure
 * reaches {@code PERFORM 9999-ABEND-PROGRAM} at L577, whose routine at L707-L711 holds four
 * statements and cleans nothing up. Each of the eight file definitions in {@code
 * app/csd/CARDDEMO.CSD} carries {@code RECOVERY(NONE)} and {@code JOURNAL(NO)}.
 *
 * <p>The card service consumes no topic today. Every service of this platform declares the same
 * marker over its own schema. A consumer added here inherits that table and the discipline the
 * method below describes.
 */
public interface ProcessedEventRepository extends ListCrudRepository<ProcessedEventEntity, UUID> {

    /**
     * Deletes at most {@code limit} markers written before the given instant, and returns how many
     * it removed.
     *
     * <p>A marker matters only while a redelivery of its event is still possible. Past that horizon
     * it is dead weight on a table that otherwise grows for the life of the service.
     * {@code carddemo.retention.marker-retention} in {@code src/main/resources/application.yml}
     * supplies the horizon, and {@code ix_processed_event_processed_at} serves both the subquery and
     * the delete.
     *
     * <p>{@code limit} bounds one statement, and {@code domain/RetentionSweep} names the bound. A
     * horizon shorter than the broker's own retention lets a redelivery arrive after its marker is
     * gone, and the delivery is then applied a second time.
     *
     * @param horizon the instant before which a marker is removed
     * @param limit   the most markers one statement removes, at least one
     * @return the number of markers removed, and 0 when none is past the horizon
     */
    @Modifying
    @Query(value = """
            DELETE FROM processed_event
            WHERE event_id IN (SELECT event_id
                                 FROM processed_event
                                WHERE processed_at < :horizon
                                ORDER BY processed_at
                                LIMIT :limit)
            """, nativeQuery = true)
    int deleteMarkersProcessedBefore(@Param("horizon") Instant horizon,
            @Param("limit") int limit);

    /**
     * Answers whether one event identifier has already been processed.
     *
     * <p>A {@code true} result means the caller skips its side effects and acknowledges the
     * message. A {@code false} result means the caller applies those side effects, then writes the
     * marker through the inherited {@code save}. That write and those side effects commit in the
     * same local transaction, and the caller acknowledges only after that transaction commits.
     *
     * <p>Column {@code event_id} is the primary key of {@code processed_event}, so at most one row
     * carries any one identifier, and this lookup reads the primary-key index.
     *
     * @param eventId the event identifier the producing service assigned
     * @return {@code true} when the table already holds a marker for {@code eventId}
     */
    boolean existsByEventId(UUID eventId);
}
