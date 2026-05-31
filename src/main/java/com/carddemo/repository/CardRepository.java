package com.carddemo.repository;

import com.carddemo.entity.Card;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link Card} credit-card records.
 *
 * <p>Replaces the VSAM {@code CARDDAT} KSDS keyed file plus its {@code CARDAIX}
 * alternate-index path. The underlying {@code cards} table is the system-of-record
 * card store mapping the 150-byte {@code CARD-RECORD} layout from
 * {@code app/cpy/CVACT02Y.cpy} (CardDemo_v1.0-15-g27d6c6f-68):
 * {@code CARD-NUM PIC X(16)} (key), {@code CARD-ACCT-ID PIC 9(11)},
 * {@code CARD-CVV-CD PIC 9(03)}, {@code CARD-EMBOSSED-NAME PIC X(50)},
 * {@code CARD-EXPIRAION-DATE PIC X(10)}, {@code CARD-ACTIVE-STATUS PIC X(01)}, and a
 * trailing {@code FILLER PIC X(59)} that carries no business meaning. It is populated by
 * the data-initialization seed load ({@code DataInitializationJobConfig}) from the 50
 * default rows in {@code app/data/ASCII/carddata.txt}.</p>
 *
 * <p><strong>Consumers (AAP &sect;0.4.1.1).</strong> {@code CardService} is the sole
 * consumer; it backs the three card flows that replace the original CICS online
 * programs:</p>
 * <ul>
 *   <li>{@code GET /api/accounts/{acctId}/cards} &mdash; paginated card list, replacing
 *       {@code app/cbl/COCRDLIC.cbl}. The COBOL forward/backward browse
 *       ({@code STARTBR DATASET('CARDAIX')} &rarr; {@code READNEXT} for PF8,
 *       {@code STARTBR} &rarr; {@code READPREV} for PF7, then {@code ENDBR}) is replaced
 *       by {@link #findByAccountId(Long, Pageable)} with stateless {@code Pageable}
 *       pagination per AAP &sect;0.6.1.</li>
 *   <li>{@code GET /api/cards/{cardNum}} &mdash; single-card view, replacing
 *       {@code app/cbl/COCRDSLC.cbl} ({@code EXEC CICS READ DATASET('CARDDAT')}); served
 *       by the inherited {@code findById(String)} keyed read.</li>
 *   <li>{@code PUT /api/cards/{cardNum}} &mdash; card update, replacing
 *       {@code app/cbl/COCRDUPC.cbl} ({@code READ UPDATE} + {@code REWRITE}); served by
 *       the inherited {@code save(Card)} on a managed entity.</li>
 * </ul>
 *
 * <p><strong>Primary key (PR-13).</strong> The {@link Card} entity's {@code @Id} is
 * {@code String cardNum} mapping {@code CARD-NUM PIC X(16)} &rarr;
 * {@code card_num VARCHAR(16) NOT NULL PRIMARY KEY}; this interface therefore extends
 * {@code JpaRepository<Card, String>} (ID type {@code String}, <em>not</em> {@code Long}).
 * The inherited {@code findById(String)} reproduces the keyed {@code READ} of
 * {@code COCRDSLC}; {@code save}/{@code saveAndFlush} reproduce the {@code WRITE}
 * (seed load) and {@code REWRITE} (update) of {@code COCRDUPC}; and
 * {@code existsById}/{@code deleteById}/{@code delete}/{@code count}/{@code findAll}/
 * {@code getReferenceById} round out the CRUD surface.</p>
 *
 * <p><strong>AAP &sect;0.6.13 alternate-index replacement.</strong> The original VSAM
 * {@code CARDDATA.AIX} alternate index ({@code CARDAIX} path) on {@code CARD-ACCT-ID}
 * (position 16, length 11) is replaced by the PostgreSQL B-tree index
 * {@code idx_card_account_id} on the {@code account_id} column. That index is declared
 * via {@code @Index} on the {@link Card} entity (keeping the entity self-describing) and
 * created physically by Flyway {@code src/main/resources/db/migration/V2__indexes.sql};
 * PostgreSQL maintains it automatically on every INSERT/UPDATE/DELETE, so the IDCAMS
 * {@code DELETE}&rarr;{@code DEFINE}&rarr;{@code BLDINDEX}&rarr;{@code DEFINE PATH} rebuild
 * sequence (the VSAM {@code TRANIDX}-style AIX rebuild) has no Java/SQL counterpart and is
 * ELIMINATED. The index backs {@link #findByAccountId(Long, Pageable)}. Note: the
 * {@code @Index} declaration lives on the <em>entity</em>, never on this repository &mdash;
 * this interface only <em>uses</em> the index implicitly via the derived finder.</p>
 *
 * <p><strong>PR-22 optimistic locking.</strong> The {@link Card} entity carries a
 * {@code @Version} field; concurrent updates (the modern equivalent of the COBOL
 * {@code READ UPDATE} exclusive lock whose failure modes {@code COCRDUPC} reports as
 * "Could not lock record for update" / "Data was changed before update") raise
 * {@code OptimisticLockException}, which {@code GlobalExceptionHandler} maps to HTTP 409
 * Conflict &mdash; non-blocking optimistic concurrency replacing the VSAM CI-level
 * exclusive lock.</p>
 *
 * <p><strong>Pattern &amp; scope.</strong> Per the Repository Pattern (AAP &sect;0.3.3 #1)
 * this extends {@code JpaRepository} (not {@code CrudRepository}) to inherit the full CRUD,
 * sort, and paging API. The explicit {@code @Repository} stereotype marks the interface for
 * component scanning and activates Spring's
 * {@code PersistenceExceptionTranslationPostProcessor}, translating provider-specific
 * persistence exceptions into the {@code org.springframework.dao.DataAccessException}
 * hierarchy. This is a pure data-access component and carries no business logic &mdash;
 * field-level card validation and update orchestration live in {@code CardService}.</p>
 *
 * @see com.carddemo.entity.Card
 * @see org.springframework.data.jpa.repository.JpaRepository
 */
@Repository
public interface CardRepository extends JpaRepository<Card, String> {

    /**
     * Finds all cards belonging to a specific account, with pagination.
     *
     * <p>Replaces the COBOL browse pattern from {@code app/cbl/COCRDLIC.cbl}, which paged
     * the card list forward (PF8) and backward (PF7) with a server-side VSAM browse
     * cursor over the {@code CARDAIX} alternate-index path:</p>
     *
     * <pre>{@code
     * EXEC CICS STARTBR DATASET('CARDAIX')
     *           RIDFLD(WS-CARD-ACCT-ID)
     *           KEYLENGTH(LENGTH OF WS-CARD-ACCT-ID)
     *           GTEQ
     * END-EXEC
     * PERFORM UNTIL EOF
     *    EXEC CICS READNEXT DATASET('CARDAIX')
     *              INTO(CARD-RECORD)
     *              RIDFLD(WS-CARD-ACCT-ID)
     *    END-EXEC
     *    ...
     * END-PERFORM
     * EXEC CICS ENDBR DATASET('CARDAIX') END-EXEC
     * }</pre>
     *
     * <p>The original PF7 (page back) / PF8 (page forward) browse cursor is replaced by
     * stateless {@code Pageable} pagination per AAP &sect;0.6.1. Each REST request
     * recomputes the cursor from its {@code page} and {@code size} parameters; no
     * server-side cursor lifecycle ({@code STARTBR}/{@code ENDBR}) is maintained between
     * requests.</p>
     *
     * <p>Spring Data parses the method name {@code findByAccountId} and auto-generates JPQL
     * equivalent to {@code WHERE c.accountId = :accountId} (the predicate references the
     * {@code accountId} entity field; the physical SQL column is {@code account_id}).
     * Execution is backed by the PostgreSQL B-tree index {@code idx_card_account_id}
     * (AAP &sect;0.6.13), which replaces the VSAM {@code CARDDATA.AIX} alternate index;
     * PostgreSQL maintains that index automatically, so no rebuild job is required.</p>
     *
     * @param accountId the account ID whose cards to retrieve (matches COBOL
     *                  {@code CARD-ACCT-ID PIC 9(11)}); must not be {@code null}
     * @param pageable  page/size/sort instructions; typically
     *                  {@code PageRequest.of(page, 10, Sort.by("cardNum"))} to mirror the
     *                  original 10-rows-per-screen card-list layout
     * @return a {@link Page} of cards belonging to the given account (an empty page when
     *         the account has no cards); never {@code null}
     */
    Page<Card> findByAccountId(Long accountId, Pageable pageable);
}
