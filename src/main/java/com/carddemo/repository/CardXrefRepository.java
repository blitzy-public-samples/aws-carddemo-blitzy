package com.carddemo.repository;

import com.carddemo.entity.CardXref;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for {@link CardXref} card cross-reference records.
 *
 * <p>Replaces the VSAM {@code CCXREF} KSDS keyed file plus its {@code CXACAIX}
 * alternate-index path. The underlying {@code card_xref} table is the
 * Card&nbsp;&harr;&nbsp;Customer&nbsp;&harr;&nbsp;Account junction mapping the 50-byte
 * {@code CARD-XREF-RECORD} layout from {@code app/cpy/CVACT03Y.cpy}
 * (CardDemo_v1.0-15-g27d6c6f-68): {@code XREF-CARD-NUM PIC X(16)} (key),
 * {@code XREF-CUST-ID PIC 9(09)}, {@code XREF-ACCT-ID PIC 9(11)}, and a trailing
 * {@code FILLER PIC X(14)} that carries no business meaning. It is populated by the
 * data-initialization seed load ({@code DataInitializationJobConfig}) from the 50 default
 * rows in {@code app/data/ASCII/cardxref.txt}.</p>
 *
 * <p><strong>Consumers (AAP &sect;0.4.1.1, &sect;0.4.1.2, &sect;0.4.1.5).</strong></p>
 * <ul>
 *   <li>{@code TransactionPostingProcessor} &mdash; the primary consumer. Per
 *       {@code app/cbl/CBTRN02C.cbl} L380-L392 ({@code 1500-A-LOOKUP-XREF}), batch posting
 *       resolves a daily-transaction card number to its owning account by reading the xref
 *       file keyed on {@code XREF-CARD-NUM}. The COBOL
 *       {@code READ XREF-FILE ... INVALID KEY MOVE 100} maps to the inherited
 *       {@code findById(String)} returning {@code Optional.empty()}, which triggers
 *       validation code 100 via
 *       {@link com.carddemo.exception.InvalidCardException} ("INVALID CARD NUMBER FOUND")
 *       per PR-03.</li>
 *   <li>{@code AccountService} / account-card linkage flows
 *       ({@code app/cbl/COACTVWC.cbl}) &mdash; use {@link #findByAccountId(Long)} to locate
 *       the card(s) and customer associated with a given account.</li>
 *   <li>{@code StatementService} / {@code StatementGenerationJobConfig} &mdash; per
 *       {@code app/cbl/CBSTM03A.CBL} ({@code 1000-XREFFILE-GET-NEXT}), statement
 *       generation iterates the xref records (keyed on {@code XREF-CUST-ID} /
 *       {@code XREF-ACCT-ID}) to enumerate the cards and account for each customer
 *       (CREASTMT batch).</li>
 *   <li>{@code DataInitializationJobConfig} &mdash; initial seed load of the 50 default
 *       cross-reference rows via the inherited {@code save}/{@code saveAll}.</li>
 * </ul>
 *
 * <p><strong>Primary key (PR-13).</strong> The {@link CardXref} entity's {@code @Id} is
 * {@code String xrefCardNum} mapping {@code XREF-CARD-NUM PIC X(16)} &rarr;
 * {@code xref_card_num VARCHAR(16) NOT NULL PRIMARY KEY}; this interface therefore extends
 * {@code JpaRepository<CardXref, String>} (ID type {@code String}, not {@code Long}). The
 * inherited {@code findById(String)} reproduces the keyed {@code READ} of
 * {@code CBTRN02C}'s {@code 1500-A-LOOKUP-XREF}; {@code save}/{@code saveAll} reproduce the
 * seed-load {@code WRITE}s; {@code existsById}/{@code deleteById}/{@code count}/
 * {@code findAll} round out the CRUD surface.</p>
 *
 * <p><strong>CRITICAL &mdash; no {@code findByCardNum} method.</strong> Although AAP
 * &sect;0.4.1.5 text mentions a hypothetical {@code findByCardNum}/{@code findByCardNumber}
 * finder, the {@link CardXref} entity exposes the card number ONLY via its primary-key
 * field {@code xrefCardNum}; there is no separate {@code cardNum} property. Spring Data
 * method-name auto-derivation requires the property segment of a finder to match an entity
 * field exactly, so {@code findByCardNum} would raise a {@code PropertyReferenceException}
 * at application bootstrap. The inherited {@code findById(String xrefCardNum)} is the
 * correct equivalent of "find by card number" because the xref card number IS the primary
 * key. This repository therefore declares exactly one derived finder,
 * {@link #findByAccountId(Long)}.</p>
 *
 * <p><strong>AAP &sect;0.6.13 alternate-index replacement.</strong> The original VSAM
 * {@code CARDXREF.AIX} alternate index ({@code CXACAIX} path) on {@code XREF-ACCT-ID}
 * (position 25, length 11) is replaced by the PostgreSQL B-tree index
 * {@code idx_xref_account_id} on the {@code xref_acct_id} column. That index is declared
 * via {@code @Index} on the {@link CardXref} entity (keeping the entity self-describing)
 * and created physically by Flyway {@code src/main/resources/db/migration/V2__indexes.sql};
 * PostgreSQL maintains it automatically on every INSERT/UPDATE/DELETE, so the IDCAMS
 * {@code DELETE}&rarr;{@code DEFINE}&rarr;{@code BLDINDEX}&rarr;{@code DEFINE PATH} rebuild
 * sequence of the VSAM AIX has no Java/SQL counterpart. The index backs
 * {@link #findByAccountId(Long)}.</p>
 *
 * <p><strong>Pattern &amp; scope.</strong> Per the Repository Pattern (AAP &sect;0.3.3 #1)
 * this extends {@code JpaRepository} (not {@code CrudRepository}) to inherit the full CRUD,
 * sort, and paging API. The explicit {@code @Repository} stereotype marks the interface for
 * component scanning and activates Spring's
 * {@code PersistenceExceptionTranslationPostProcessor}, translating provider-specific
 * persistence exceptions into the {@code org.springframework.dao.DataAccessException}
 * hierarchy. This is a pure data-access component and carries no business logic &mdash;
 * card-number validation (PR-03 code 100) lives in the batch processor and service layer
 * that consume {@code findById}. Unlike {@code Account}/{@code Card}/{@code Customer}/
 * {@code Transaction}, {@link CardXref} carries no {@code @Version} field (cross-reference
 * rows are immutable once seeded), so no optimistic-locking concerns apply here.</p>
 *
 * @see com.carddemo.entity.CardXref
 * @see org.springframework.data.jpa.repository.JpaRepository
 */
@Repository
public interface CardXrefRepository extends JpaRepository<CardXref, String> {

    /**
     * Finds all card cross-reference records linked to a given account.
     *
     * <p>Replaces the VSAM {@code CXACAIX} alternate-index path access used by statement
     * generation ({@code app/cbl/CBSTM03A.CBL}) and account-card linkage flows
     * ({@code app/cbl/COACTVWC.cbl}), where COBOL would issue a positioned browse:</p>
     *
     * <pre>{@code
     * EXEC CICS STARTBR DATASET('CXACAIX')
     *           RIDFLD(WS-XREF-ACCT-ID)
     *           GTEQ
     * END-EXEC
     * PERFORM UNTIL EOF
     *    EXEC CICS READNEXT DATASET('CXACAIX')
     *              INTO(CARD-XREF-RECORD)
     *              RIDFLD(WS-XREF-ACCT-ID)
     *    END-EXEC
     *    ...
     * END-PERFORM
     * EXEC CICS ENDBR DATASET('CXACAIX') END-EXEC
     * }</pre>
     *
     * <p>Spring Data parses the method name {@code findByAccountId} and auto-generates JPQL
     * equivalent to {@code WHERE x.accountId = :accountId} (the predicate references the
     * {@code accountId} entity field; the physical SQL column is {@code xref_acct_id}).
     * Execution is backed by the PostgreSQL B-tree index {@code idx_xref_account_id}
     * (AAP &sect;0.6.13), which replaces the VSAM {@code CARDXREF.AIX} alternate index.</p>
     *
     * <p>Returns a {@code List} (not a {@code Page}) because a typical account has only
     * one to three cross-reference rows, so pagination overhead is unnecessary. Use-case
     * examples:</p>
     * <ul>
     *   <li>Statement generation: iterate the xrefs for each customer to enumerate their
     *       cards and account.</li>
     *   <li>Account-card linkage: given an account, find which customer holds which
     *       cards.</li>
     * </ul>
     *
     * @param accountId the account ID (matches COBOL {@code XREF-ACCT-ID PIC 9(11)});
     *                  must not be {@code null}
     * @return the cross-reference records linking this account to its cards and customer;
     *         an empty list when no xrefs exist (never {@code null})
     */
    List<CardXref> findByAccountId(Long accountId);
}
