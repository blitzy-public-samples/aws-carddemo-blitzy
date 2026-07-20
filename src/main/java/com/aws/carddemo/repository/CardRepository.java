package com.aws.carddemo.repository;

import com.aws.carddemo.domain.Card;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

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
 * <p>Bounded base-cluster browse (AAP section 0.6.6; review finding&nbsp;#21): the unfiltered
 * {@code COCRDLIC} browse of the whole {@code CARDDAT} base cluster must not materialize the entire
 * table into memory. The paging window is therefore read one page at a time through the two keyset
 * finders {@link #findByCardNumGreaterThanEqualOrderByCardNumAsc(String, Limit)} (page-down /
 * {@code READNEXT} direction) and {@link #findByCardNumLessThanEqualOrderByCardNumDesc(String,
 * Limit)} (page-up / {@code READPREV} direction). Both are the same ascending {@code CARD-NUM}
 * base-cluster access path as the legacy {@code STARTBR}&nbsp;{@code GTEQ} browse, bounded by a
 * {@link Limit}; they add no new business access path and honor the no-feature-expansion mandate
 * (AAP section 0.7.1). The account-scoped path ({@link #findByCardAcctIdOrderByCardNumAsc(Long)})
 * remains inherently bounded to a single account and is unchanged.</p>
 *
 * <p>Scope: only the inherited primary-key CRUD, the two by-account derived queries, and the two
 * bounded card-number browse-window finders required by the COBOL access paths are exposed. No
 * further finders are declared, honoring the no-feature-expansion mandate (AAP section 0.7.1).</p>
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

    /**
     * Reads the card by its primary key ({@code CARD-NUM}) while acquiring a row-level
     * <strong>pessimistic write lock</strong> (JPA {@link LockModeType#PESSIMISTIC_WRITE};
     * PostgreSQL {@code SELECT ... FOR UPDATE}).
     *
     * <p><strong>Origin / parity (AAP &sect;0.3.3, &sect;0.6.5):</strong> the card-update program
     * {@code legacy/cbl/COCRDUPC.cbl} re-reads the card master with {@code EXEC CICS READ FILE(CARDDAT)
     * UPDATE} on the confirmation turn &mdash; an exclusive record lock held until the {@code REWRITE}
     * &mdash; and a failure to acquire it maps to the program's {@code COULD-NOT-LOCK-FOR-UPDATE}
     * condition. Under the migrated {@code READ COMMITTED} stack an ordinary {@code findById} does not
     * lock the row, so the compare-before-write leaves a lost-update window (CWE-362) the COBOL never
     * had. Acquiring this write lock inside the update service's {@code @Transactional} unit-of-work,
     * held until commit, makes the change-check and the {@code REWRITE} atomic and serializes
     * concurrent writers of the same card &mdash; reproducing the legacy record-lock semantics. This is
     * the <em>same</em> primary-key access path as {@link JpaRepository#findById(Object)} with a lock
     * added; it is not a new finder and therefore not feature expansion. The card-update path locks a
     * single record (the card) only, so no lock-order/deadlock concern arises. Must be invoked within
     * an active transaction.</p>
     *
     * @param cardNum the card primary key ({@code CARD-NUM PIC X(16)})
     * @return the locked card if present, otherwise empty
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Card c where c.cardNum = :cardNum")
    Optional<Card> findByIdForUpdate(@Param("cardNum") String cardNum);

    /**
     * Reads a bounded, ascending-by-card-number window of the {@code CARDDAT} base cluster whose
     * {@code CARD-NUM} is greater than or equal to the given key. This is the keyset-paged,
     * memory-bounded realization of the unfiltered {@code COCRDLIC} forward browse
     * ({@code EXEC CICS STARTBR CARDDAT RIDFLD(...) GTEQ} followed by {@code READNEXT}); the card-list
     * service positions at the key and reads at most one screen page plus a look-ahead record from
     * the returned window (review finding&nbsp;#21).
     *
     * <p>Ordering fidelity relies on the C/POSIX collation applied to the {@code card_num CHAR(16)}
     * column by Flyway (AAP section 0.6.6); a blank/{@code LOW-VALUES} key (the empty string) is
     * padded to sixteen spaces and therefore sorts before every sixteen-digit card number, selecting
     * the first page. This is the same ascending base-cluster access path as the legacy browse with a
     * {@link Limit} added &mdash; not a new query surface.</p>
     *
     * @param cardNum the inclusive lower-bound browse key ({@code CARD-NUM PIC X(16)}); the empty
     *                string selects the first page
     * @param limit   the maximum number of rows to materialize (the paging window size)
     * @return the matching cards ordered ascending by {@code Card.cardNum}, at most {@code limit}
     *         rows, or an empty list when none are at or beyond the key
     */
    List<Card> findByCardNumGreaterThanEqualOrderByCardNumAsc(String cardNum, Limit limit);

    /**
     * Reads a bounded, descending-by-card-number window of the {@code CARDDAT} base cluster whose
     * {@code CARD-NUM} is less than or equal to the given key. This is the keyset-paged,
     * memory-bounded realization of the unfiltered {@code COCRDLIC} backward browse (the
     * {@code READPREV} direction used by {@code PF7} page-up); the card-list service reverses the
     * returned window to ascending order, positions at the key, skips it with the priming
     * {@code READPREV}, then reads the preceding screen page (review finding&nbsp;#21).
     *
     * <p>Ordering fidelity relies on the C/POSIX collation applied to the {@code card_num CHAR(16)}
     * column by Flyway (AAP section 0.6.6). This is the same ascending base-cluster access path as the
     * legacy browse, read in reverse and bounded by a {@link Limit} &mdash; not a new query
     * surface.</p>
     *
     * @param cardNum the inclusive upper-bound browse key ({@code CARD-NUM PIC X(16)})
     * @param limit   the maximum number of rows to materialize (the paging window size)
     * @return the matching cards ordered descending by {@code Card.cardNum}, at most {@code limit}
     *         rows, or an empty list when none are at or below the key
     */
    List<Card> findByCardNumLessThanEqualOrderByCardNumDesc(String cardNum, Limit limit);
}
