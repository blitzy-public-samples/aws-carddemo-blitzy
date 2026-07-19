package com.aws.carddemo.repository;

import com.aws.carddemo.domain.CardXref;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for the {@link CardXref} cross-reference entity,
 * replacing the legacy {@code EXEC CICS} / {@code FILE SECTION} VSAM I/O against
 * the {@code CCXREF} (card cross-reference) KSDS with relational access over
 * PostgreSQL (AAP &sect;0.3.3, &sect;0.4.1, &sect;0.6.2).
 *
 * <p>The cross-reference maps a card number to its owning customer id and
 * account id. The full business record (card + customer + account) is the VSAM
 * primary key, so this repository is typed on the entity's nested composite
 * identifier {@link CardXref.CardXrefId}; the inherited {@link JpaRepository}
 * operations therefore provide composite-key CRUD (for example
 * {@code findById(CardXref.CardXrefId)}, {@code save}, and
 * {@code deleteById}) against the {@code card_xref} table.</p>
 *
 * <p>Origin: legacy/cpy/CVACT03Y.cpy (CARD-XREF-RECORD, RECLN 50); VSAM CCXREF;
 * alt-index CXACAIX (XREF-ACCT-ID, KEYS(11,25)) per legacy/csd/CARDDEMO.CSD +
 * legacy/jcl/XREFFILE.jcl.</p>
 *
 * <p>Alternate-index migration: the VSAM alternate index {@code CXACAIX} (built
 * over {@code XREF-ACCT-ID}) has no direct JPA analogue; it is reproduced by the
 * derived query {@link #findByXrefAcctId(Long)}, backed by a secondary database
 * index on {@code card_xref(xref_acct_id)} defined by Flyway. Lookup by the
 * unique base-cluster card-number key is exposed as
 * {@link #findByXrefCardNum(String)}. Both finders are resolved by Spring Data
 * from the entity property names ({@code xrefCardNum}, {@code xrefAcctId}); no
 * explicit {@code @Query} / JPQL is required.</p>
 *
 * <p>Consumed by the account-view service ({@code COACTVWC}), the
 * interest-calculation batch job ({@code CBACT04C}), and card/transaction flows
 * that resolve the card&#8596;account&#8596;customer mapping.</p>
 */
@Repository
public interface CardXrefRepository extends JpaRepository<CardXref, CardXref.CardXrefId> {

    /**
     * Looks up a cross-reference row by its 16-character card number, the
     * base-cluster key of the legacy {@code CCXREF} KSDS
     * ({@code XREF-CARD-NUM PIC X(16)}, verified {@code KEYS(16 0)} in
     * {@code legacy/jcl/XREFFILE.jcl}). Because the card number is unique across
     * the cross-reference, at most one row can match, so the result is wrapped
     * in an {@link Optional}. Mirrors the legacy keyed read of {@code CCXREF} by
     * {@code XREF-CARD-NUM} used by the posting and account flows.
     *
     * @param xrefCardNum the 16-character card number ({@code XREF-CARD-NUM})
     * @return an {@link Optional} containing the matching cross-reference, or
     *         {@link Optional#empty()} when no row exists for the card number
     */
    Optional<CardXref> findByXrefCardNum(String xrefCardNum);

    /**
     * Returns every cross-reference row for the given account id, reproducing
     * the legacy VSAM alternate index {@code CXACAIX}
     * ({@code XREF-ACCT-ID PIC 9(11)}, verified {@code KEYS(11,25)} in
     * {@code legacy/jcl/XREFFILE.jcl}). The alternate key is non-unique because
     * a single account may own several cards, so the result is a
     * {@link List}. Mirrors the legacy alternate-index browse of {@code CCXREF}
     * by account used by the account-view ({@code COACTVWC}) and
     * interest-calculation ({@code CBACT04C}) access paths.
     *
     * @param xrefAcctId the 11-digit account id ({@code XREF-ACCT-ID})
     * @return the (possibly empty) list of cross-reference rows for the account
     */
    List<CardXref> findByXrefAcctId(Long xrefAcctId);
}
