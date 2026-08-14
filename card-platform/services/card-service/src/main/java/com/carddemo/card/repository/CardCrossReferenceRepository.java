package com.carddemo.card.repository;

import com.carddemo.card.entity.CardCrossReferenceEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.ListCrudRepository;

/**
 * Reads and writes {@code card_xref}, this service's replica of the cross-reference from a card
 * number to an account.
 *
 * <p>Transformed from the called input and output subroutine {@code app/cbl/CBSTM03B.CBL}, whose
 * parameter area spans {@code app/cbl/CBSTM03B.CBL:L100-L112}. {@code LK-M03B-DD PIC X(08)} at
 * {@code app/cbl/CBSTM03B.CBL:L101} selects a dataset, and {@code LK-M03B-OPER PIC X(01)} at
 * {@code app/cbl/CBSTM03B.CBL:L102} carries an operation code. {@code LK-M03B-KEY PIC X(25)} at
 * {@code app/cbl/CBSTM03B.CBL:L110} holds the key, and {@code LK-M03B-FLDT PIC X(1000)} at
 * {@code app/cbl/CBSTM03B.CBL:L112} holds the record. {@link String} takes over that key field and
 * {@link CardCrossReferenceEntity} takes over that record field.
 *
 * <p>The dataset selector drives the {@code EVALUATE LK-M03B-DD} at
 * {@code app/cbl/CBSTM03B.CBL:L118}, which routes to one of the four datasets named at
 * {@code app/cbl/CBSTM03B.CBL:L119-L126}. One interface serves one dataset, and this interface
 * serves {@code XREFFILE} from {@code app/cbl/CBSTM03B.CBL:L121-L122}. The status field
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
 * 'R'    M03B-READ        L105      findByAccountId and the inherited findAll
 * 'W'    M03B-WRITE       L107      the inherited save
 * 'Z'    M03B-REWRITE     L108      the inherited save
 * 'O'    M03B-OPEN        L103      none
 * 'C'    M03B-CLOSE       L104      none
 * </pre>
 *
 * <p>{@code app/cbl/CBSTM03B.CBL} declares the write and rewrite codes and implements neither. Its
 * {@code 2000-XREFFILE-PROC} paragraph at {@code app/cbl/CBSTM03B.CBL:L157-L179} serves the open,
 * sequential-read and close codes, and it opens the dataset for input at
 * {@code app/cbl/CBSTM03B.CBL:L160}. Both reads declared below descend from
 * {@code app/cbl/COTRN02C.cbl}.
 *
 * <p>No card program reads this dataset. {@code app/cbl/COCRDSLC.cbl:L237} and
 * {@code app/cbl/COCRDUPC.cbl:L356} both read {@code *COPY CVACT03Y.}, commented out, and
 * {@code app/cbl/COCRDLIC.cbl} names {@code CVACT03Y} nowhere.
 */
public interface CardCrossReferenceRepository
        extends ListCrudRepository<CardCrossReferenceEntity, String> {

    /**
     * Returns the cross-reference row carrying one card number.
     *
     * <p>Reproduces operation code {@code 'K'}, the condition {@code M03B-READ-K} at
     * {@code app/cbl/CBSTM03B.CBL:L106}. The Customer Information Control System (CICS) path reads
     * the same key at {@code app/cbl/COTRN02C.cbl:L611-L619}, where
     * {@code RIDFLD(XREF-CARD-NUM)} supplies it. {@code KEYS(16 0)} at
     * {@code app/jcl/XREFFILE.jcl:L43} declares that key in the Job Control Language (JCL) member
     * defining the dataset: sixteen bytes at offset zero.
     *
     * <p>The caller supplies exactly sixteen characters, left-padded with zeros.
     * {@code app/cbl/COTRN02C.cbl:L56} declares {@code WS-CARD-NUM-N PIC 9(16) VALUE 0}, and one
     * {@code MOVE} at {@code app/cbl/COTRN02C.cbl:L220-L221} writes that field into
     * {@code XREF-CARD-NUM PIC X(16)}, which renders the value zero-padded. Column
     * {@code card_number} holds {@code CHAR(16)} and matches on text, so a ten-character argument
     * matches no sixteen-character row even when those ten are its final ten digits. No card
     * number, whole or partial, appears in this file.
     *
     * <p>A missing row yields an empty {@link Optional} and throws nothing.
     * {@code app/cbl/COTRN02C.cbl:L624} takes its {@code DFHRESP(NOTFND)} branch for that
     * condition, reports {@code Card Number NOT found...} and continues.
     *
     * @param cardNumber the full card number, sixteen characters left-padded with zeros
     * @return the matching row, or an empty {@link Optional} when the table holds none
     */
    Optional<CardCrossReferenceEntity> findByCardNumber(String cardNumber);

    /**
     * Returns every cross-reference row carrying one account identifier.
     *
     * <p>Reproduces the alternate-index read at {@code app/cbl/COTRN02C.cbl:L578-L586}, where
     * {@code RIDFLD(XREF-ACCT-ID)} keys the read on the account identifier.
     * {@code app/jcl/XREFFILE.jcl:L74-L76} defines that index over the Virtual Storage Access
     * Method (VSAM) dataset. {@code KEYS(11,25)} places an eleven-byte key at offset 25,
     * {@code NONUNIQUEKEY} admits many rows under one key, and {@code UPGRADE} keeps the index
     * current with the base cluster. {@code DEFINE FILE(CXACAIX)} at
     * {@code app/csd/CARDDEMO.CSD:L63} names the path, and {@code app/csd/CARDDEMO.CSD:L64}
     * describes it as the alternate index to {@code CCXREF} by account key.
     *
     * <p>Index {@code idx_card_xref_account_id} on column {@code account_id} carries no unique
     * constraint. One account holds many cards, so the result may hold many rows. The query
     * declares no ordering, and the database may return those rows in any order.
     *
     * <p>The account identifier carries eleven digits at scale zero, from
     * {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}. Column
     * {@code account_id} holds {@code CHAR(11)} and matches on the eleven-character digit string
     * the source wrote, leading zeros included. An account with no cards yields an empty
     * {@link List} and throws nothing. {@code app/cbl/COTRN02C.cbl:L591} takes its
     * {@code DFHRESP(NOTFND)} branch for that condition, reports {@code Account ID NOT found...}
     * and continues.
     *
     * @param accountId the account identifier, exactly eleven digits with leading zeros
     * @return every matching row, or an empty {@link List} when the table holds none
     */
    List<CardCrossReferenceEntity> findByAccountId(String accountId);
}
