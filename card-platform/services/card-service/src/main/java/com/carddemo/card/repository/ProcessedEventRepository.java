package com.carddemo.card.repository;

import com.carddemo.card.entity.ProcessedEventEntity;
import com.carddemo.card.entity.ProcessedEventEntity.ProcessedEventId;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

/**
 * Reads and writes {@code processed_event}, the table naming every event identifier this service
 * has already handled and the topic each arrived on.
 *
 * <p>Transformed from the called input and output subroutine {@code app/cbl/CBSTM03B.CBL}, whose
 * parameter area spans {@code app/cbl/CBSTM03B.CBL:L100-L112}. {@code LK-M03B-KEY PIC X(25)} at
 * {@code app/cbl/CBSTM03B.CBL:L110} holds the key, and {@link ProcessedEventId} takes it over.
 * {@code LK-M03B-FLDT PIC X(1000)} at {@code app/cbl/CBSTM03B.CBL:L112} holds the record, and
 * {@link ProcessedEventEntity} takes it over.
 *
 * <p>{@code LK-M03B-DD PIC X(08)} at {@code app/cbl/CBSTM03B.CBL:L101} selects a dataset, and one
 * interface serves one table. {@code LK-M03B-RC PIC X(02)} at {@code app/cbl/CBSTM03B.CBL:L109}
 * carries an operation's outcome, and the {@code boolean} returned below takes over that role.
 * {@code LK-M03B-KEY-LN PIC S9(4)} at {@code app/cbl/CBSTM03B.CBL:L111} and the open and close
 * codes at {@code app/cbl/CBSTM03B.CBL:L103-L104} map onto nothing.
 *
 * <p>Two of the six operation codes declared at {@code app/cbl/CBSTM03B.CBL:L103-L108} reach a
 * method. Code {@code 'K'}, the condition {@code M03B-READ-K} at
 * {@code app/cbl/CBSTM03B.CBL:L106}, becomes the inherited {@code existsById} and {@code findById}.
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
 * <p><b>The marker is keyed by the event and the topic together.</b> An identifier is assigned by
 * the service that publishes the event, and two producing services assign them independently, so the
 * identifier alone does not identify a delivery once a service reads two topics.
 * {@code src/main/resources/db/migration/V3__processed_event_topic_key.sql} carries the reasoning at
 * length, and it applies here even though this service reads no topic yet: a contract inherited
 * narrow reintroduces the defect silently on the day a second listener is added.
 *
 * <p>The card service consumes no topic today. Every service of this platform declares the same
 * marker over its own schema. A consumer added here inherits that table and the discipline the
 * methods below describe.
 */
public interface ProcessedEventRepository
        extends ListCrudRepository<ProcessedEventEntity, ProcessedEventId> {

    /**
     * Answers whether one event identifier has already been processed on any topic.
     *
     * <p>This is a diagnostic read rather than the idempotency guard. The guard is the
     * inherited {@code existsById}, which takes the whole key: a delivery is identified by its event
     * and the stream it arrived on, so a consumer that asked this question instead would refuse a
     * different event that happens to share an identifier with one already handled elsewhere. The
     * name says {@code OnAnyTopic} so that no caller reaches for it by accident.
     *
     * <p>A {@code true} result from the guard means the caller skips its side effects and
     * acknowledges the message. A {@code false} result means the caller applies those side effects,
     * then writes the marker through the inherited {@code save}. That write and those side effects
     * commit in the same local transaction, and the caller acknowledges only after that transaction
     * commits.
     *
     * @param eventId the event identifier the producing service assigned
     * @return {@code true} when the table holds a marker for {@code eventId} on any topic
     */
    @Query("SELECT COUNT(marker) > 0 FROM ProcessedEventEntity marker "
            + "WHERE marker.id.eventId = :eventId")
    boolean existsByEventIdOnAnyTopic(@Param("eventId") UUID eventId);
}
