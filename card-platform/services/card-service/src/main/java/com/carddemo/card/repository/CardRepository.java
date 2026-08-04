package com.carddemo.card.repository;

import com.carddemo.card.entity.CardEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
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
 * 'K'    M03B-READ-K      L106      findByCardNumber and the inherited findById
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
 * <p>Both optional filters sit inside the predicate, so the database applies them ahead of the
 * limit. {@code 9500-FILTER-RECORDS} at {@code app/cbl/COCRDLIC.cbl:L1382-L1409} applies two
 * independent equality tests: {@code CARD-ACCT-ID = CC-ACCT-ID} at
 * {@code app/cbl/COCRDLIC.cbl:L1386} and {@code CARD-NUM = CC-CARD-NUM-N} at
 * {@code app/cbl/COCRDLIC.cbl:L1397}. Either test may be absent, so all four combinations reach
 * the database. The screen counter advances only past that filter, at
 * {@code app/cbl/COCRDLIC.cbl:L1162-L1163}, so a full page holds filtered rows.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md} (planned).
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
     * @param accountId the account identifier, exactly eleven digits with leading zeros
     * @return every matching row, or an empty {@link List} when the table holds none
     */
    List<CardEntity> findByAccountId(String accountId);

    /**
     * Returns the page of cards following one card number, in ascending card-number order.
     *
     * <p>Reproduces {@code 9000-READ-FORWARD} at {@code app/cbl/COCRDLIC.cbl:L1123}. Its
     * {@code STARTBR} at {@code app/cbl/COCRDLIC.cbl:L1129-L1136} positions on
     * {@code RIDFLD(WS-CARD-RID-CARDNUM)} with {@code GTEQ} at
     * {@code app/cbl/COCRDLIC.cbl:L1133}, and its {@code READNEXT} at
     * {@code app/cbl/COCRDLIC.cbl:L1146-L1154} walks forward from there. The {@code ORDER BY}
     * clause below carries that walk.
     *
     * <p>{@code app/cbl/COCRDLIC.cbl:L1157-L1158} accepts {@code DFHRESP(NORMAL)} and
     * {@code DFHRESP(DUPREC)} as one outcome, which the non-unique alternate index produces.
     *
     * @param afterCardNumber the exclusive lower bound, or {@code null} for the first page
     * @param accountId       the account identifier filter, or {@code null} to apply none
     * @param cardNumber      the card number filter, or {@code null} to apply none
     * @param limit           the row count the caller wants, one more than it displays
     * @return the matching rows in ascending card-number order, or an empty {@link List}
     */
    @Query("""
           SELECT c FROM CardEntity c
            WHERE (:afterCardNumber IS NULL OR c.cardNumber > :afterCardNumber)
              AND (:accountId IS NULL OR c.accountId = :accountId)
              AND (:cardNumber IS NULL OR c.cardNumber = :cardNumber)
            ORDER BY c.cardNumber ASC
           """)
    List<CardEntity> findPageForward(@Param("afterCardNumber") String afterCardNumber,
                                     @Param("accountId") String accountId,
                                     @Param("cardNumber") String cardNumber,
                                     Limit limit);

    /**
     * Returns the page of cards preceding one card number, in descending card-number order.
     *
     * <p>Reproduces {@code 9100-READ-BACKWARDS} at {@code app/cbl/COCRDLIC.cbl:L1264}, whose
     * {@code READPREV} loop at {@code app/cbl/COCRDLIC.cbl:L1320-L1371} walks back from the
     * cursor. The caller reverses the rows for display, which
     * {@code app/cbl/COCRDLIC.cbl:L1338-L1346} reaches by filling its screen array from the high
     * index down.
     *
     * <p>{@code app/cbl/COCRDLIC.cbl:L1284-L1287} presets the screen counter one above the page
     * size and sets the next-page flag without reading a row. The browse ends at the
     * {@code ENDBR} at {@code app/cbl/COCRDLIC.cbl:L1374-L1377}.
     *
     * @param beforeCardNumber the exclusive upper bound, or {@code null} for the last page
     * @param accountId        the account identifier filter, or {@code null} to apply none
     * @param cardNumber       the card number filter, or {@code null} to apply none
     * @param limit            the row count the caller wants, one more than it displays
     * @return the matching rows in descending card-number order, or an empty {@link List}
     */
    @Query("""
           SELECT c FROM CardEntity c
            WHERE (:beforeCardNumber IS NULL OR c.cardNumber < :beforeCardNumber)
              AND (:accountId IS NULL OR c.accountId = :accountId)
              AND (:cardNumber IS NULL OR c.cardNumber = :cardNumber)
            ORDER BY c.cardNumber DESC
           """)
    List<CardEntity> findPageBackward(@Param("beforeCardNumber") String beforeCardNumber,
                                      @Param("accountId") String accountId,
                                      @Param("cardNumber") String cardNumber,
                                      Limit limit);
}
