package com.carddemo.card.repository;

import com.carddemo.card.entity.CardEntity;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

/**
 * Reads and writes {@code card}, the table this service owns.
 *
 * <p>Transformed from the called input and output subroutine {@code app/cbl/CBSTM03B.CBL}, whose
 * parameter area spans {@code app/cbl/CBSTM03B.CBL:L100-L112}. {@code LK-M03B-DD PIC X(08)} at
 * {@code app/cbl/CBSTM03B.CBL:L101} selects a dataset, and {@code LK-M03B-OPER PIC X(01)} at
 * {@code app/cbl/CBSTM03B.CBL:L102} carries an operation code. {@code LK-M03B-KEY PIC X(25)} at
 * {@code app/cbl/CBSTM03B.CBL:L110} holds the key, and {@code LK-M03B-FLDT PIC X(1000)} at
 * {@code app/cbl/CBSTM03B.CBL:L112} holds the record. {@link String} takes over that key field and
 * {@link CardEntity} takes over that record field.
 *
 * <p>The dataset selector drives the {@code EVALUATE LK-M03B-DD} at
 * {@code app/cbl/CBSTM03B.CBL:L118}. One interface serves one dataset, and this interface serves
 * the card file that {@code app/csd/CARDDEMO.CSD:L25} opens as {@code CARDDAT}. The status field
 * {@code LK-M03B-RC PIC X(02)} at {@code app/cbl/CBSTM03B.CBL:L109} becomes an empty result or a
 * thrown exception. The key width {@code LK-M03B-KEY-LN PIC S9(4)} at
 * {@code app/cbl/CBSTM03B.CBL:L111} maps onto nothing.
 *
 * <p>Four of the six operation codes declared at {@code app/cbl/CBSTM03B.CBL:L103-L108} reach a
 * method, and two reach nothing.
 *
 * <pre>
 * code   condition name   locator   target
 * 'K'    M03B-READ-K      L106      findByCardNumber, findByCardToken, the inherited findById
 * 'R'    M03B-READ        L105      findByAccountId, both page finders, the inherited findAll
 * 'W'    M03B-WRITE       L107      the inherited save
 * 'Z'    M03B-REWRITE     L108      the inherited save
 * 'O'    M03B-OPEN        L103      none
 * 'C'    M03B-CLOSE       L104      none
 * </pre>
 *
 * <p>{@code app/cbl/CBSTM03B.CBL} declares the write and rewrite codes and implements neither. Its
 * dispatch reaches four datasets at {@code app/cbl/CBSTM03B.CBL:L119-L126}, and each of those four
 * paragraphs opens its dataset for input alone.
 *
 * <p>The two page finders below browse the card file in both directions, reproducing
 * {@code 9000-READ-FORWARD} at {@code app/cbl/COCRDLIC.cbl:L1123} and
 * {@code 9100-READ-BACKWARDS} at {@code app/cbl/COCRDLIC.cbl:L1264}. Both position on a card
 * number and both take the same three arguments plus a row limit.
 *
 * <p>Each cursor is exclusive, and {@code null} asks for the first or the last page. The source
 * excludes the boundary row: its first {@code READPREV} at
 * {@code app/cbl/COCRDLIC.cbl:L1294-L1302} reads that row, and
 * {@code app/cbl/COCRDLIC.cbl:L1304-L1307} discards it. The caller supplies the row limit and asks
 * for one row more than it displays. That extra row is the lookahead {@code READNEXT} at
 * {@code app/cbl/COCRDLIC.cbl:L1197-L1205}, from which
 * {@code app/cbl/COCRDLIC.cbl:L1191-L1216} derives the next-page flag.
 *
 * <p>{@code 9500-FILTER-RECORDS} at {@code app/cbl/COCRDLIC.cbl:L1382-L1409} applies two
 * independent equality tests: {@code CARD-ACCT-ID = CC-ACCT-ID} at
 * {@code app/cbl/COCRDLIC.cbl:L1386} and {@code CARD-NUM = CC-CARD-NUM-N} at
 * {@code app/cbl/COCRDLIC.cbl:L1397}. The screen counter advances only past that filter, at
 * {@code app/cbl/COCRDLIC.cbl:L1162-L1163}, so a full page holds filtered rows.
 *
 * <p>Each finder below carries one fixed predicate. A page opens either at the start of the key
 * order or after a cursor, and it either scopes to one account or spans every account, which is
 * four shapes per direction. A card-number filter reaches {@link #findByCardNumber(String)}
 * instead: {@code card_number} is the primary key, so that filter matches at most one row and the
 * caller intersects it with the other two tests on that row.
 *
 * <p>Every predicate is therefore an equality or a range over an indexed column.
 * {@code pk_card} serves the two unscoped shapes and {@code idx_card_account_id} over
 * {@code (account_id, card_number)} serves the two scoped ones, cursor included.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
public interface CardRepository extends ListCrudRepository<CardEntity, String> {

    /**
     * Returns the card row carrying one card number.
     *
     * <p>Reproduces operation code {@code 'K'}, the condition {@code M03B-READ-K} at
     * {@code app/cbl/CBSTM03B.CBL:L106}, and implements {@code 9100-GETCARD-BYACCTCARD} at
     * {@code app/cbl/COCRDSLC.cbl:L736-L773}. The Customer Information Control System (CICS) path
     * reads the same key at {@code app/cbl/COCRDSLC.cbl:L742-L750}, where
     * {@code RIDFLD(WS-CARD-RID-CARDNUM)} supplies it and {@code FILE(LIT-CARDFILENAME)} names
     * {@code 'CARDDAT '} from {@code app/cbl/COCRDSLC.cbl:L187-L188}. {@code KEYS(16 0)} at
     * {@code app/jcl/CARDFILE.jcl:L54} declares that key in the Job Control Language (JCL) member
     * defining the Virtual Storage Access Method (VSAM) dataset: sixteen bytes at offset zero.
     *
     * <p>The caller supplies exactly sixteen characters, left-padded with zeros. Column
     * {@code card_number} holds {@code CHAR(16)} and matches on text, so a ten-character argument
     * matches no sixteen-character row even when those ten are its final ten digits. No card
     * number, whole or partial, appears in this file.
     *
     * <p>The argument carries the full Primary Account Number (PAN) and never a masked form.
     * {@code app/bms/COCRDSL.bms:L99} declares the source screen field {@code LENGTH=16}, and
     * {@code app/cbl/COCRDSLC.cbl:L740} moves that field straight into the read key.
     *
     * <p>A missing row yields an empty {@link Optional} and throws nothing.
     * {@code app/cbl/COCRDSLC.cbl:L755} takes its {@code DFHRESP(NOTFND)} branch for that
     * condition, and {@code app/cbl/COCRDSLC.cbl:L759-L760} reports
     * {@code Did not find cards for this search condition} and continues.
     *
     * @param cardNumber the full card number, sixteen characters left-padded with zeros
     * @return the matching row, or an empty {@link Optional} when the table holds none
     */
    Optional<CardEntity> findByCardNumber(String cardNumber);

    /**
     * Returns the card row carrying one card token.
     *
     * <p>This finder reproduces no paragraph. Column {@code card_token} is an addition with no
     * field in {@code app/cpy/CVACT02Y.cpy} behind it, and {@code V1__schema.sql} documents both
     * its derivation and the constraint {@code uq_card_card_token} that keeps it unique.
     *
     * <p>The paging cursor of the card list carries a card token, and this method turns that cursor
     * back into a browse position. A caller holding a token holds no card number:
     * {@code com.carddemo.cobol.PanMasker#cardToken(String)} is a digest, so the value travels
     * where a Primary Account Number (PAN) must not and this lookup is the only way back.
     *
     * <p>The unique constraint admits at most one row, so the return type is an
     * {@link Optional} rather than a {@link List}. A token naming no row yields an empty
     * {@link Optional} and throws nothing, which is the same outcome
     * {@link #findByCardNumber(String)} reports for a missing card.
     *
     * @param cardToken the card token, exactly sixty-four lower-case hexadecimal characters
     * @return the matching row, or an empty {@link Optional} when the table holds none
     */
    Optional<CardEntity> findByCardToken(String cardToken);

    /**
     * Returns every card row carrying one account identifier.
     *
     * <p>Reproduces {@code 9150-GETCARD-BYACCT} at {@code app/cbl/COCRDSLC.cbl:L779-L809}, whose
     * read at {@code app/cbl/COCRDSLC.cbl:L783-L791} names
     * {@code FILE(LIT-CARDFILENAME-ACCT-PATH)} and keys on {@code RIDFLD(WS-CARD-RID-ACCT-ID)}.
     * That literal holds {@code 'CARDAIX '} at {@code app/cbl/COCRDSLC.cbl:L189-L190}, and
     * {@code app/csd/CARDDEMO.CSD:L13-L14} opens the alternate-index path under that name.
     *
     * <p>{@code grep -n '9150' app/cbl/COCRDSLC.cbl} returns two hits: the paragraph at
     * {@code app/cbl/COCRDSLC.cbl:L779} and its exit at {@code app/cbl/COCRDSLC.cbl:L810}. No
     * {@code PERFORM} statement under {@code app/} names the paragraph, so it carries zero
     * {@code PERFORM} sites. {@code 9000-READ-DATA} at {@code app/cbl/COCRDSLC.cbl:L726-L730}
     * performs {@code 9100-GETCARD-BYACCTCARD} alone.
     *
     * <p>{@code KEYS(11 16)} at {@code app/jcl/CARDFILE.jcl:L85} places an eleven-byte key at
     * offset 16, where {@code CARD-ACCT-ID} starts. {@code NONUNIQUEKEY} at
     * {@code app/jcl/CARDFILE.jcl:L86} admits many rows under one key, and {@code UPGRADE} at
     * {@code app/jcl/CARDFILE.jcl:L87} keeps the index current with the base cluster. Index
     * {@code idx_card_account_id} carries no unique constraint, so the result may hold many rows.
     * The query declares no ordering, and the database may return those rows in any order.
     *
     * <p>The account identifier carries eleven digits at scale zero, from
     * {@code CARD-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT02Y.cpy:L6}. Column
     * {@code account_id} holds {@code CHAR(11)} and matches on the eleven-character digit string
     * the source wrote, leading zeros included. An account with no cards yields an empty
     * {@link List} and throws nothing.
     *
     * <p>The caller supplies the row bound. An account carries one card in
     * {@code app/data/ASCII/carddata.txt} and the alternate index admits many, so an unbounded read
     * would materialise however many rows one account has come to hold.
     *
     * @param accountId the account identifier, exactly eleven digits with leading zeros
     * @param limit     greatest number of rows to return
     * @return every matching row up to {@code limit}, or an empty {@link List} when the table holds
     *         none
     */
    List<CardEntity> findByAccountId(String accountId, Limit limit);

    /**
     * Returns the first page of cards in ascending card-number order.
     *
     * <p>Reproduces the opening of {@code 9000-READ-FORWARD} at
     * {@code app/cbl/COCRDLIC.cbl:L1123}, whose {@code STARTBR} at
     * {@code app/cbl/COCRDLIC.cbl:L1129-L1136} positions with {@code GTEQ} at
     * {@code app/cbl/COCRDLIC.cbl:L1133} on the key the screen carried. A first request carries
     * none, and the browse then opens on the lowest key.
     *
     * <p>{@code app/cbl/COCRDLIC.cbl:L1157-L1158} accepts {@code DFHRESP(NORMAL)} and
     * {@code DFHRESP(DUPREC)} as one outcome, which the non-unique alternate index produces.
     *
     * @param limit the row count the caller wants, one more than it displays
     * @return the lowest rows in ascending card-number order, or an empty {@link List}
     */
    @Query("SELECT c FROM CardEntity c ORDER BY c.cardNumber ASC")
    List<CardEntity> findFirstPage(Limit limit);

    /**
     * Returns the page of cards following one card number, in ascending card-number order.
     *
     * <p>Reproduces the {@code READNEXT} of {@code 9000-READ-FORWARD} at
     * {@code app/cbl/COCRDLIC.cbl:L1146-L1154}, which walks forward from the browse position. The
     * bound is exclusive, so the row the cursor names is never returned twice.
     *
     * @param afterCardNumber the exclusive lower bound
     * @param limit           the row count the caller wants, one more than it displays
     * @return the matching rows in ascending card-number order, or an empty {@link List}
     */
    @Query("""
           SELECT c FROM CardEntity c
            WHERE c.cardNumber > :afterCardNumber
            ORDER BY c.cardNumber ASC
           """)
    List<CardEntity> findPageAfter(@Param("afterCardNumber") String afterCardNumber, Limit limit);

    /**
     * Returns the first page of one account's cards in ascending card-number order.
     *
     * <p>The equality is {@code CARD-ACCT-ID = CC-ACCT-ID} at
     * {@code app/cbl/COCRDLIC.cbl:L1386}, applied while {@code FLG-ACCTFILTER-ISVALID} holds.
     *
     * @param accountId the account identifier, exactly eleven digits with leading zeros
     * @param limit     the row count the caller wants, one more than it displays
     * @return the account's lowest rows in ascending card-number order, or an empty {@link List}
     */
    @Query("""
           SELECT c FROM CardEntity c
            WHERE c.accountId = :accountId
            ORDER BY c.cardNumber ASC
           """)
    List<CardEntity> findFirstPageForAccount(@Param("accountId") String accountId, Limit limit);

    /**
     * Returns the page of one account's cards following one card number, ascending.
     *
     * @param accountId       the account identifier, exactly eleven digits with leading zeros
     * @param afterCardNumber the exclusive lower bound
     * @param limit           the row count the caller wants, one more than it displays
     * @return the matching rows in ascending card-number order, or an empty {@link List}
     */
    @Query("""
           SELECT c FROM CardEntity c
            WHERE c.accountId = :accountId AND c.cardNumber > :afterCardNumber
            ORDER BY c.cardNumber ASC
           """)
    List<CardEntity> findPageAfterForAccount(@Param("accountId") String accountId,
                                             @Param("afterCardNumber") String afterCardNumber,
                                             Limit limit);

    /**
     * Returns the last page of cards in descending card-number order.
     *
     * <p>Reproduces the opening of {@code 9100-READ-BACKWARDS} at
     * {@code app/cbl/COCRDLIC.cbl:L1264}, whose {@code READPREV} loop at
     * {@code app/cbl/COCRDLIC.cbl:L1320-L1371} walks back from the browse position. The caller
     * reverses the rows for display, which {@code app/cbl/COCRDLIC.cbl:L1338-L1346} reaches by
     * filling its screen array from the high index down.
     *
     * @param limit the row count the caller wants, one more than it displays
     * @return the highest rows in descending card-number order, or an empty {@link List}
     */
    @Query("SELECT c FROM CardEntity c ORDER BY c.cardNumber DESC")
    List<CardEntity> findLastPage(Limit limit);

    /**
     * Returns the page of cards preceding one card number, in descending card-number order.
     *
     * <p>The bound is exclusive. The source excludes the boundary row too: its priming
     * {@code READPREV} at {@code app/cbl/COCRDLIC.cbl:L1294-L1302} reads that row and
     * {@code app/cbl/COCRDLIC.cbl:L1304-L1307} discards it.
     *
     * @param beforeCardNumber the exclusive upper bound
     * @param limit            the row count the caller wants, one more than it displays
     * @return the matching rows in descending card-number order, or an empty {@link List}
     */
    @Query("""
           SELECT c FROM CardEntity c
            WHERE c.cardNumber < :beforeCardNumber
            ORDER BY c.cardNumber DESC
           """)
    List<CardEntity> findPageBefore(@Param("beforeCardNumber") String beforeCardNumber,
                                    Limit limit);

    /**
     * Returns the last page of one account's cards in descending card-number order.
     *
     * @param accountId the account identifier, exactly eleven digits with leading zeros
     * @param limit     the row count the caller wants, one more than it displays
     * @return the account's highest rows in descending card-number order, or an empty {@link List}
     */
    @Query("""
           SELECT c FROM CardEntity c
            WHERE c.accountId = :accountId
            ORDER BY c.cardNumber DESC
           """)
    List<CardEntity> findLastPageForAccount(@Param("accountId") String accountId, Limit limit);

    /**
     * Returns the page of one account's cards preceding one card number, descending.
     *
     * @param accountId        the account identifier, exactly eleven digits with leading zeros
     * @param beforeCardNumber the exclusive upper bound
     * @param limit            the row count the caller wants, one more than it displays
     * @return the matching rows in descending card-number order, or an empty {@link List}
     */
    @Query("""
           SELECT c FROM CardEntity c
            WHERE c.accountId = :accountId AND c.cardNumber < :beforeCardNumber
            ORDER BY c.cardNumber DESC
           """)
    List<CardEntity> findPageBeforeForAccount(@Param("accountId") String accountId,
                                              @Param("beforeCardNumber") String beforeCardNumber,
                                              Limit limit);

    /**
     * Reads the three cardholder values of one card without loading the row as an entity.
     *
     * <p>Reproduces the fetch of {@code 9100-GETCARD-BYACCTCARD} at
     * {@code app/cbl/COCRDUPC.cbl:L1376-L1417}, whose plain read sits at
     * {@code app/cbl/COCRDUPC.cbl:L1382-L1390}, and whose values
     * {@code 9000-READ-DATA} stores as the comparison baseline at
     * {@code app/cbl/COCRDUPC.cbl:L1360}.
     *
     * <p>The result is a projection and not an entity, so the row does not enter the persistence
     * context. {@link #findForUpdateByCardNumber(String)} then reads the same row afresh under a
     * lock, and the two values can differ. An entity read here would place the row in the
     * persistence context, the locked read would answer with that same cached instance, and the
     * comparison at {@code app/cbl/COCRDUPC.cbl:L1503-L1508} could never find a difference.
     *
     * <p>Every alias below matches an accessor of {@link CardholderValues}, which is what binds one
     * selected value to one component.
     *
     * @param cardNumber the full card number, sixteen characters left-padded with zeros
     * @return the three values, or an empty {@link Optional} when the table holds no such row
     */
    @Query("""
           SELECT c.embossedName AS embossedName,
                  c.expirationDate AS expirationDate,
                  c.activeStatus AS activeStatus
             FROM CardEntity c
            WHERE c.cardNumber = :cardNumber
           """)
    Optional<CardholderValues> findCardholderValuesByCardNumber(
            @Param("cardNumber") String cardNumber);

    /**
     * Reads one card row and holds a write lock on it until the transaction ends.
     *
     * <p>Reproduces the keyed read for update at {@code app/cbl/COCRDUPC.cbl:L1427-L1436}, inside
     * {@code 9200-WRITE-PROCESSING.} at {@code app/cbl/COCRDUPC.cbl:L1420}, where the source issues
     * a Customer Information Control System (CICS) {@code READ} with {@code UPDATE} at
     * {@code app/cbl/COCRDUPC.cbl:L1429}.
     *
     * <p>A response other than {@code DFHRESP(NORMAL)} at {@code app/cbl/COCRDUPC.cbl:L1441} makes
     * the source set {@code COULD-NOT-LOCK-FOR-UPDATE} at {@code app/cbl/COCRDUPC.cbl:L1446} and
     * exit at {@code app/cbl/COCRDUPC.cbl:L1448}. That condition carries the text
     * {@code 'Could not lock record for update'} at {@code app/cbl/COCRDUPC.cbl:L206}, and the
     * domain layer reports that message. A miss returns an empty {@link Optional}.
     *
     * @param cardNumber the full card number, sixteen characters left-padded with zeros
     * @return the locked row, or an empty {@link Optional} when the table holds no such row
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<CardEntity> findForUpdateByCardNumber(String cardNumber);

    /**
     * The three values one card update may change, read as a projection.
     *
     * <p>{@code CARD-EMBOSSED-NAME PIC X(50)} at {@code app/cpy/CVACT02Y.cpy:L8},
     * {@code CARD-EXPIRAION-DATE PIC X(10)} at {@code app/cpy/CVACT02Y.cpy:L9} and
     * {@code CARD-ACTIVE-STATUS PIC X(01)} at {@code app/cpy/CVACT02Y.cpy:L10}. The field name
     * keeps the source misspelling.
     *
     * <p>{@code CARD-CVV-CD PIC 9(03)} at {@code app/cpy/CVACT02Y.cpy:L7} is absent.
     * {@code app/cbl/COCRDUPC.cbl:L1503} compares it, {@code entity/CardEntity} exposes no
     * accessor for it, and {@code entity/CardEntity#applyUpdate} changes it never.
     */
    interface CardholderValues {

        /**
         * Returns the embossed cardholder name as the row holds it.
         *
         * @return the name, at most fifty characters
         */
        String getEmbossedName();

        /**
         * Returns the expiry date as the row holds it.
         *
         * @return the expiry date
         */
        LocalDate getExpirationDate();

        /**
         * Returns the active-status flag as the row holds it.
         *
         * @return {@code Y} or {@code N}
         */
        String getActiveStatus();
    }

    /**
     * Bounds how long the locked reads of this transaction wait for a row another writer holds.
     *
     * <p>PostgreSQL waits forever by default, so a contended update held its request open for as long
     * as the other writer held the row. The documented lock-failure answer was then unreachable
     * through contention: the wait ended in a lock or it did not end.
     *
     * <p>{@code set_config} with its third argument true is transaction-local, so the bound governs
     * the locked reads of this one update and is discarded at commit or rollback. That is the reason
     * for this method rather than a datasource setting: a bound on every connection would also bound a
     * schema migration and the relay sweep, and a migration that gives up on a lock leaves a
     * half-applied schema. A parameter is used rather than {@code SET LOCAL} because PostgreSQL admits
     * no placeholder in a {@code SET} statement.
     *
     * <p>Once the bound is in force, a lock the datastore will not grant inside it raises rather than
     * waiting, and the caller reports the documented lock-failure answer.
     *
     * @param milliseconds the bound, as a PostgreSQL interval string such as {@code 3000ms}
     * @return the value the setting now holds, which the caller reads for nothing
     */
    @Query(value = "SELECT set_config('lock_timeout', :milliseconds, true)", nativeQuery = true)
    String applyLockWaitBound(@Param("milliseconds") String milliseconds);
}
