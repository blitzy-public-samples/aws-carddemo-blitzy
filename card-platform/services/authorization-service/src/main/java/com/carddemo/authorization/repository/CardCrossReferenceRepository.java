package com.carddemo.authorization.repository;

import com.carddemo.authorization.entity.CardCrossReferenceEntity;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.ListCrudRepository;

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
 * 'K'    M03B-READ-K      L106      findByCardNumber and the inherited findById
 * 'R'    M03B-READ        L105      the inherited findAll
 * 'W'    M03B-WRITE       L107      the inherited save
 * 'Z'    M03B-REWRITE     L108      the inherited save
 * 'O'    M03B-OPEN        L103      none
 * 'C'    M03B-CLOSE       L104      none
 * </pre>
 *
 * <p>Design decisions and every deviation from the source contract:
 * {@code card-platform/docs/decision-log.md}.
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
     * {@code card_number} holds {@code VARCHAR(16)} and the match runs on text. A ten-character
     * argument such as {@code "4111111111"} matches no row holding {@code "0004111111111111"}.
     *
     * <p>A missing row yields an empty {@link Optional} and throws nothing. Both paragraphs that
     * read this dataset agree: {@code app/cbl/CBTRN02C.cbl:L384} takes its {@code INVALID KEY}
     * branch and {@code app/cbl/COTRN02C.cbl:L624} takes its {@code DFHRESP(NOTFND)} branch, and
     * neither one ends the run. The rule {@code CardCrossReferenceRule} under
     * {@code com.carddemo.authorization.domain.rules} turns the empty result into decline reason
     * 100, assigned at {@code app/cbl/CBTRN02C.cbl:L385} with the text
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
     * <p>The account identifier carries eleven digits at scale zero, from
     * {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}. Column
     * {@code account_id} holds {@code NUMERIC(11,0)} and the match runs on the number. No text
     * padding applies. An account with no cards yields an empty {@link List} and throws nothing.
     *
     * @param accountId the account identifier, an eleven-digit integer at scale zero
     * @return every matching row, or an empty {@link List} when the table holds none
     */
    List<CardCrossReferenceEntity> findByAccountId(BigDecimal accountId);
}
