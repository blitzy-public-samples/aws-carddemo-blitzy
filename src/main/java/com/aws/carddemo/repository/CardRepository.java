package com.aws.carddemo.repository;

import com.aws.carddemo.domain.Card;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for the {@link Card} entity, replacing the legacy VSAM
 * {@code CARDDAT} KSDS input/output that the original online COBOL programs performed through
 * {@code EXEC CICS} file commands and {@code FILE SECTION} definitions (AAP sections 0.3.3, 0.4.1,
 * 0.6.2).
 *
 * <p>Origin: legacy/cpy/CVACT02Y.cpy (CARD-RECORD, RECLN 150); VSAM CARDDAT; alt-index CARDAIX
 * (CARD-ACCT-ID, AXRKP=16) per legacy/csd/CARDDEMO.CSD + legacy/jcl/CARDFILE.jcl.</p>
 *
 * <p>Primary-key parity (AAP section 0.6.2): the VSAM primary key {@code CARD-NUM PIC X(16)} is
 * preserved as the entity identifier {@code Card.cardNum} (a {@link String}), so this repository is
 * typed {@code JpaRepository<Card, String>}. All primary-key access therefore reuses the inherited
 * CRUD operations &mdash; the inherited {@code findById(String)} reproduces the keyed
 * {@code READ CARDDAT} of the card-detail program {@code COCRDSLC}, and the inherited
 * {@code save(Card)} reproduces the {@code REWRITE}/{@code WRITE} of the card-update program
 * {@code COCRDUPC}. Those operations are not redeclared here.</p>
 *
 * <p>Alternate-index parity (AAP section 0.4.1): the VSAM alternate index {@code CARDAIX}, built on
 * {@code CARD-ACCT-ID} at key offset 16, is reproduced by the derived query
 * {@link #findByCardAcctId(Long)}. That method name resolves against the entity property
 * {@code cardAcctId} and is backed at the database level by a secondary index on
 * {@code card(card_acct_id)} created by the Flyway schema migration; declaring that index is the
 * schema's concern and is intentionally not expressed here.</p>
 *
 * <p>Deterministic-ordering parity (AAP section 0.6.6): the card-list program {@code COCRDLIC}
 * browses {@code CARDDAT} in ascending {@code CARD-NUM} order (VSAM {@code STARTBR} followed by
 * {@code READNEXT} on the card-number RIDFLD). The ordered variant
 * {@link #findByCardAcctIdOrderByCardNumAsc(Long)} preserves that observable base-cluster ordering;
 * byte-for-byte ordering fidelity ultimately relies on the C/POSIX collation applied to the
 * {@code card_num CHAR(16)} column by Flyway. The ordered method is the same by-account access path
 * as {@link #findByCardAcctId(Long)} with an explicit sort added &mdash; it is not a new query
 * surface.</p>
 *
 * <p>Scope: only the inherited primary-key CRUD and the two by-account derived queries required by
 * the COBOL access paths are exposed. No further finders are declared, honoring the
 * no-feature-expansion mandate (AAP section 0.7.1).</p>
 */
@Repository
public interface CardRepository extends JpaRepository<Card, String> {

    /**
     * Finds every card owned by the given account, reproducing the VSAM {@code CARDAIX}
     * alternate-index access path on {@code CARD-ACCT-ID}. A {@link List} is returned because that
     * alternate key is non-unique &mdash; one account may own several cards. Used by the card-list
     * screen {@code COCRDLIC} when filtered by account and by the account-view flows.
     *
     * @param cardAcctId the owning account id ({@code CARD-ACCT-ID PIC 9(11)}); resolves against the
     *                   {@code Card.cardAcctId} property
     * @return the matching cards in unspecified order, or an empty list when the account owns none
     */
    List<Card> findByCardAcctId(Long cardAcctId);

    /**
     * Finds every card owned by the given account, ordered ascending by card number. This
     * reproduces the VSAM {@code CARDAIX} alternate-index access path combined with the ascending
     * {@code CARD-NUM} base-cluster browse order that the card-list program {@code COCRDLIC}
     * observes via {@code STARTBR}/{@code READNEXT}. It is the same by-account access path as
     * {@link #findByCardAcctId(Long)} with deterministic ordering added (AAP section 0.6.6).
     *
     * @param cardAcctId the owning account id ({@code CARD-ACCT-ID PIC 9(11)}); resolves against the
     *                   {@code Card.cardAcctId} property
     * @return the matching cards ordered ascending by the {@code Card.cardNum} property, or an empty
     *         list when the account owns none
     */
    List<Card> findByCardAcctIdOrderByCardNumAsc(Long cardAcctId);
}
