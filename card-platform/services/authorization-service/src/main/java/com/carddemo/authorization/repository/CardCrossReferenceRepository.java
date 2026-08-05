package com.carddemo.authorization.repository;

import com.carddemo.authorization.entity.CardCrossReferenceEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

/**
 * Reads and writes {@code card_xref}, the cross-reference from a card number to an account.
 *
 * <p>Transformed from the called input and output subroutine {@code app/cbl/CBSTM03B.CBL}, whose
 * parameter area spans {@code app/cbl/CBSTM03B.CBL:L100-L112}. {@code LK-M03B-DD PIC X(08)} at
 * {@code app/cbl/CBSTM03B.CBL:L101} selects a dataset, and {@code LK-M03B-OPER PIC X(01)} at
 * {@code app/cbl/CBSTM03B.CBL:L102} carries an operation code. {@code LK-M03B-KEY PIC X(25)} at
 * {@code app/cbl/CBSTM03B.CBL:L110} carries the key, and {@code LK-M03B-FLDT PIC X(1000)} at
 * {@code app/cbl/CBSTM03B.CBL:L112} carries the record. {@link String} takes over that key field
 * and {@link CardCrossReferenceEntity} takes over that record field.
 *
 * <p>The dataset selector drives the {@code EVALUATE LK-M03B-DD} at
 * {@code app/cbl/CBSTM03B.CBL:L118}, which routes to one of the four datasets named at
 * {@code app/cbl/CBSTM03B.CBL:L119-L126}. One interface serves one dataset, and this interface
 * serves {@code XREFFILE} from {@code app/cbl/CBSTM03B.CBL:L121-L122}. The status field
 * {@code LK-M03B-RC PIC X(02)} at {@code app/cbl/CBSTM03B.CBL:L109} becomes an empty result or a
 * thrown exception. The key length {@code LK-M03B-KEY-LN PIC S9(4)} at
 * {@code app/cbl/CBSTM03B.CBL:L111} maps onto nothing.
 *
 * <p>Four of the six operation codes declared at {@code app/cbl/CBSTM03B.CBL:L103-L108} map onto
 * a method. Two map onto nothing.
 *
 * <pre>
 * code   condition name   locator   target
 * 'K'    M03B-READ-K      L106      findByCardNumber, findFirstByAccountIdOrderByCardNumberAsc
 *                                   and the inherited findById
 * 'R'    M03B-READ        L105      findByAccountIdOrderByCardNumberAsc and the inherited findAll
 * 'W'    M03B-WRITE       L107      the inherited save
 * 'Z'    M03B-REWRITE     L108      the inherited save
 * 'O'    M03B-OPEN        L103      none
 * 'C'    M03B-CLOSE       L104      none
 * </pre>
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
public interface CardCrossReferenceRepository
        extends ListCrudRepository<CardCrossReferenceEntity, String> {

    /**
     * Returns the cross-reference row that carries one card number.
     *
     * <p>Reproduces operation code {@code 'K'}, the condition {@code M03B-READ-K} at
     * {@code app/cbl/CBSTM03B.CBL:L106}. The batch ancestor moves the card number into the key
     * field at {@code app/cbl/CBTRN02C.cbl:L382} and reads the file at
     * {@code app/cbl/CBTRN02C.cbl:L383}. {@code KEYS(16 0)} at {@code app/jcl/XREFFILE.jcl:L43}
     * declares that key: sixteen bytes at offset zero. The Customer Information Control System
     * path reads the same key at {@code app/cbl/COTRN02C.cbl:L611-L619}, where
     * {@code RIDFLD(XREF-CARD-NUM)} supplies it.
     *
     * <p>The caller supplies sixteen characters, left-padded with zeros. The source builds that
     * shape at {@code app/cbl/COTRN02C.cbl:L220-L221}, where one {@code MOVE} writes
     * {@code WS-CARD-NUM-N PIC 9(16)} into {@code XREF-CARD-NUM PIC X(16)}. Column
     * {@code card_number} holds {@code VARCHAR(16)} and the match runs on text, so an argument of
     * ten characters matches no sixteen-character row even when the ten are its last ten digits.
     * No card number, whole or partial, is reproduced in this file.
     *
     * <p>A missing row yields an empty {@link Optional} and throws nothing. Both paragraphs that
     * read this dataset agree: {@code app/cbl/CBTRN02C.cbl:L384} takes its {@code INVALID KEY}
     * branch and {@code app/cbl/COTRN02C.cbl:L624} takes its {@code DFHRESP(NOTFND)} branch, and
     * neither one ends the run. {@code CardCrossReferenceRule} under
     * {@code com.carddemo.authorization.domain.rules} converts the empty {@link Optional} into
     * decline reason 0100, assigned at {@code app/cbl/CBTRN02C.cbl:L385} with the text
     * {@code INVALID CARD NUMBER FOUND} at {@code app/cbl/CBTRN02C.cbl:L386-L387}.
     *
     * @param cardNumber the full card number, sixteen characters left-padded with zeros
     * @return the matching row, or an empty {@link Optional} when the table holds none
     */
    Optional<CardCrossReferenceEntity> findByCardNumber(String cardNumber);

    /**
     * Returns every cross-reference row that carries one account identifier.
     *
     * <p>Reproduces the alternate-index read at {@code app/cbl/COTRN02C.cbl:L578-L586}, where
     * {@code RIDFLD(XREF-ACCT-ID)} keys the read on the account identifier.
     * {@code app/jcl/XREFFILE.jcl:L74-L76} defines that index. {@code KEYS(11,25)} places an
     * eleven-byte key at offset 25, {@code NONUNIQUEKEY} admits many rows under one key, and
     * {@code UPGRADE} keeps the index current with the base cluster.
     * {@code DEFINE FILE(CXACAIX)} at {@code app/csd/CARDDEMO.CSD:L63} names the path, and
     * {@code app/csd/CARDDEMO.CSD:L64} describes it as the alternate index to {@code CCXREF} by
     * account key.
     *
     * <p>Index {@code idx_card_xref_account_id} on column {@code account_id} carries no unique
     * constraint. One account holds many cards, and the result may hold more than one row.
     *
     * <p>Rows arrive ordered by card number, ascending. A query with no {@code ORDER BY} lets the
     * database return rows in any order. That order can change between two runs of one query,
     * after a vacuum, an index rebuild or a plan change. A caller that reads the first row
     * would then resolve one card today and another tomorrow for one unchanged account. The order is
     * therefore part of this contract, and {@code card_number} is the column that fixes it because
     * it is the primary key and holds one value per row.
     *
     * <p>Ascending card number is also the order the source reads. {@code KEYS(16 0)} at
     * {@code app/jcl/XREFFILE.jcl:L43} makes the card number the base-cluster key, and a Virtual
     * Storage Access Method alternate index stores the duplicate entries of one key in ascending
     * base-key order. The single {@code EXEC CICS READ} at
     * {@code app/cbl/COTRN02C.cbl:L577-L585} therefore returns the row with the lowest card number
     * of that account, and {@code app/cbl/COTRN02C.cbl:L210} moves that card number into the screen
     * field.
     *
     * <p>The account identifier carries eleven digits at scale zero, from
     * {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}. Column
     * {@code account_id} holds {@code CHAR(11)} and the match runs on the eleven-character digit
     * string the source wrote, leading zeros included. An account with no cards yields an empty
     * {@link List} and throws nothing.
     *
     * @param accountId the account identifier, exactly eleven digits with leading zeros
     * @return every matching row ordered by card number ascending, or an empty {@link List} when the
     *         table holds none
     */
    List<CardCrossReferenceEntity> findByAccountIdOrderByCardNumberAsc(String accountId);

    /**
     * Returns the one cross-reference row an account identifier resolves to.
     *
     * <p>This is the selection {@code app/cbl/COTRN02C.cbl:L577-L585} performs. That paragraph
     * issues one {@code EXEC CICS READ} against the alternate-index path rather than a browse, so it
     * reads a single row and never iterates. The row it reads is the one with the lowest card number
     * of that account, for the reason
     * {@link #findByAccountIdOrderByCardNumberAsc(String)} states.
     *
     * <p>An account holding several cards therefore resolves to its lowest card number, every time,
     * and a caller resolving a card from an account identifier gets one deterministic answer. A
     * caller that needs every card of the account calls the ordered list method instead.
     *
     * <p>An account with no cards yields an empty {@link Optional} and throws nothing.
     * {@code app/cbl/COTRN02C.cbl:L588-L593} takes its {@code DFHRESP(NOTFND)} branch for the same
     * condition and reports {@code Account ID NOT found...} without ending the run.
     *
     * <p>No authorization decision calls this method, and none may. The source paragraph it mirrors
     * fills a screen field for an operator who already named the account; a decision that resolved a
     * card this way would authorize against whichever card of that account happens to sort lowest,
     * which is a card the caller never presented. {@code domain/AuthorizationService} therefore reads
     * {@link #findByCardNumber(String)} only, and a test in {@code domain/AuthorizationServiceTest}
     * asserts this method is never reached from a decision.
     *
     * @param accountId the account identifier, exactly eleven digits with leading zeros
     * @return the row carrying the lowest card number of that account, or an empty {@link Optional}
     *         when the table holds none
     */
    Optional<CardCrossReferenceEntity> findFirstByAccountIdOrderByCardNumberAsc(
            String accountId);

    /**
     * Applies one cross-reference state change, unless the row already carries a newer one.
     *
     * <p>One statement rather than read-then-write, for two reasons. It is idempotent: a duplicate
     * delivery of the same event finds {@code source_occurred_at} already at or past its own and
     * updates nothing, so replaying the topic converges on the same rows. And it is ordered. The
     * {@code WHERE} clause on the conflict path discards an event that did not occur after the one
     * already recorded. A redelivery arriving behind a newer event cannot move the replica
     * backwards. A read followed by a write has a window between the two in which both of those
     * guarantees fail.
     *
     * <p>{@code source_occurred_at IS NULL} on the stored row means the row came from
     * {@code V2__seed.sql}, which is the initial load. Any event supersedes it.
     *
     * @param cardNumber       the sixteen-character card number, the primary key
     * @param customerId       the nine-digit customer identifier the event carried
     * @param accountId        the eleven-digit account identifier the event carried
     * @param sourceEventId    the event that carried the change
     * @param sourceOccurredAt when that event occurred, from its envelope
     * @param observedAt       when this service applied it
     * @return 1 when the row was written, and 0 when a newer change was already recorded
     */
    @Modifying
    @Query(value = """
            INSERT INTO card_xref (card_number, customer_id, account_id,
                                   source_event_id, source_occurred_at, observed_at)
            VALUES (:cardNumber, :customerId, :accountId,
                    :sourceEventId, :sourceOccurredAt, :observedAt)
            ON CONFLICT (card_number) DO UPDATE SET
                customer_id        = EXCLUDED.customer_id,
                account_id         = EXCLUDED.account_id,
                source_event_id    = EXCLUDED.source_event_id,
                source_occurred_at = EXCLUDED.source_occurred_at,
                observed_at        = EXCLUDED.observed_at
            WHERE card_xref.source_occurred_at IS NULL
               OR card_xref.source_occurred_at < EXCLUDED.source_occurred_at
            """, nativeQuery = true)
    int applyStateChange(@Param("cardNumber") String cardNumber,
            @Param("customerId") String customerId,
            @Param("accountId") String accountId,
            @Param("sourceEventId") UUID sourceEventId,
            @Param("sourceOccurredAt") Instant sourceOccurredAt,
            @Param("observedAt") Instant observedAt);

    /**
     * Counts rows last observed before {@code cutoff}, so a service can report how much of its
     * replica has gone stale rather than discovering it one authorization at a time.
     *
     * @param cutoff the freshness cutoff
     * @return how many rows were last observed before {@code cutoff}
     */
    long countByObservedAtBefore(Instant cutoff);

    /**
     * Records that a card-update event confirmed the rows of one account whose card number ends in
     * the digits the event left visible, and writes no mapping field.
     *
     * <p>Why this is a refresh and not an upsert. A {@code CardUpdated} message carries a masked card
     * number, because no full card number travels on a topic in this platform. This table is keyed by
     * the full sixteen-character number, so the message cannot name a key and cannot create a row.
     * What it does carry is the account identifier, so it confirms that the service owning the card
     * has just written that account's card data, which is exactly the mapping this table holds.
     *
     * <p>The {@code SET} list is therefore the three observation columns and nothing else.
     * {@code card_number}, {@code customer_id} and {@code account_id} are untouched, which is what
     * makes the statement safe when two cards of one account share their last four digits: both rows
     * are refreshed, no mapping moves, and no authorization can be misrouted by it. Confirming a
     * sibling row alongside the one the event named is the cost of the masked contract, and it is
     * bounded to rows that already name this account.
     *
     * <p>The {@code WHERE} clause carries the same newer-wins guard as
     * {@link #applyStateChange}, so a redelivery arriving behind a newer event changes nothing.
     *
     * @param accountId          the account the event named, eleven digits
     * @param cardNumberSuffix   a {@code LIKE} pattern matching the visible digits, from
     *                           {@code CardUpdated.visibleDigitsSuffix()}
     * @param sourceEventId      the event that carried the confirmation
     * @param sourceOccurredAt   when that event occurred, from its envelope
     * @param observedAt         when this service applied it
     * @return how many rows were refreshed, which is zero when the account has no row with those
     *         visible digits or when a newer event was already recorded
     */
    @Modifying
    @Query(value = """
            UPDATE card_xref
               SET source_event_id    = :sourceEventId,
                   source_occurred_at = :sourceOccurredAt,
                   observed_at        = :observedAt
             WHERE account_id = :accountId
               AND card_number LIKE :cardNumberSuffix
               AND (source_occurred_at IS NULL OR source_occurred_at < :sourceOccurredAt)
            """, nativeQuery = true)
    int refreshObservation(@Param("accountId") String accountId,
            @Param("cardNumberSuffix") String cardNumberSuffix,
            @Param("sourceEventId") UUID sourceEventId,
            @Param("sourceOccurredAt") Instant sourceOccurredAt,
            @Param("observedAt") Instant observedAt);
}
