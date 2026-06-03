package com.carddemo.repository;

import com.carddemo.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link Account} account records.
 *
 * <p>Replaces the VSAM {@code ACCTDAT} KSDS keyed file access. The underlying
 * {@code accounts} table is the system-of-record account store mapping the 300-byte
 * {@code ACCOUNT-RECORD} layout from {@code app/cpy/CVACT01Y.cpy}
 * (CardDemo_v1.0-15-g27d6c6f-68): {@code ACCT-ID PIC 9(11)} (key),
 * {@code ACCT-ACTIVE-STATUS}, {@code ACCT-CURR-BAL}, {@code ACCT-CREDIT-LIMIT},
 * {@code ACCT-CASH-CREDIT-LIMIT}, {@code ACCT-OPEN-DATE}, {@code ACCT-EXPIRAION-DATE} [sic],
 * {@code ACCT-REISSUE-DATE}, {@code ACCT-CURR-CYC-CREDIT}, {@code ACCT-CURR-CYC-DEBIT},
 * {@code ACCT-ADDR-ZIP}, and {@code ACCT-GROUP-ID}. The 50 default accounts are seeded by
 * {@code DataInitializationJobConfig} from {@code app/data/ASCII/acctdata.txt}.</p>
 *
 * <p>The original VSAM access verbs this interface supersedes:</p>
 * <ul>
 *   <li>{@code COACTVWC.cbl} {@code 9300-GETACCTDATA-BYACCT}
 *       ({@code EXEC CICS READ DATASET('ACCTDAT') RIDFLD ... INTO(ACCOUNT-RECORD)})
 *       &mdash; keyed read by account id, reproduced by the inherited
 *       {@code findById(Long)}; the COBOL {@code DFHRESP(NOTFND)} branch maps to
 *       {@code Optional.empty()}, which {@code AccountService} surfaces as
 *       {@code AccountNotFoundException} (HTTP 404 / batch code 101).</li>
 *   <li>{@code COACTUPC.cbl} {@code 9600-WRITE-PROCESSING}
 *       ({@code EXEC CICS READ FILE('ACCTDAT') UPDATE ...} then
 *       {@code EXEC CICS REWRITE FILE('ACCTDAT') FROM(ACCT-UPDATE-RECORD)})
 *       &mdash; read-for-update then rewrite, reproduced by {@code findById(Long)} inside a
 *       {@code @Transactional} scope followed by the inherited {@code save(Account)} on the
 *       managed entity. The VSAM CI-level exclusive lock is replaced by PR-22 optimistic
 *       locking (see below).</li>
 * </ul>
 *
 * <p><strong>Consumers (AAP &sect;0.4.1.1, &sect;0.4.1.2):</strong></p>
 * <ul>
 *   <li>{@code AccountService} &mdash; backs {@code GET /api/accounts/{acctId}}
 *       and {@code PUT /api/accounts/{acctId}} (online view/update REST endpoints
 *       replacing {@code COACTVWC.cbl} and {@code COACTUPC.cbl}).</li>
 *   <li>{@code BillPaymentService} &mdash; reads the account, computes
 *       {@code availableCredit = creditLimit - currBal}, and updates the balance
 *       atomically within a {@code @Transactional} scope.</li>
 *   <li>{@code TransactionService} &mdash; validates the account during online
 *       transaction creation (validation codes 101/102/103 mirror the
 *       {@code CBTRN02C} batch chain).</li>
 *   <li>{@code TransactionPostingJobConfig} + {@code AccountBalanceUpdater} &mdash;
 *       batch updates per {@code CBTRN02C.cbl} {@code 2800-UPDATE-ACCOUNT-REC}
 *       [app/cbl/CBTRN02C.cbl:L545-L560] (PR-07): sign-based bucket assignment to
 *       {@code currCycCredit}/{@code currCycDebit} plus an unconditional add to
 *       {@code currBal}, then REWRITE via the inherited {@code save(Account)}.</li>
 *   <li>{@code InterestCalculationJobConfig} &mdash; applies interest per
 *       {@code CBACT04C.cbl} {@code 1050-UPDATE-ACCOUNT}
 *       [app/cbl/CBACT04C.cbl:L350-L370] (PR-08): adds {@code totalInt} to
 *       {@code currBal}, zeros out {@code currCycCredit} and {@code currCycDebit},
 *       then REWRITE via the inherited {@code save(Account)}. This is the most
 *       heavily exercised repository in batch processing &mdash; POSTTRAN, INTCALC,
 *       and CREASTMT all touch it.</li>
 *   <li>{@code DataInitializationJobConfig} &mdash; initial seed load of the 50
 *       default accounts via {@code save}/{@code saveAll}.</li>
 * </ul>
 *
 * <p><strong>Primary key (PR-13).</strong> The {@link Account} entity's {@code @Id}
 * is {@code Long acctId} mapping {@code ACCT-ID PIC 9(11)} &rarr;
 * {@code acct_id BIGINT NOT NULL PRIMARY KEY}; this interface therefore extends
 * {@code JpaRepository<Account, Long>} (ID type {@code Long}, <em>not</em>
 * {@code String} &mdash; an 11-digit value exceeds the range of {@code int} and is held
 * as a {@code Long}). All consumer access patterns are by primary key, so no custom
 * finder methods are required (AAP &sect;0.4.1.5).</p>
 *
 * <p><strong>PR-22 Optimistic Locking.</strong> The {@link Account} entity carries a
 * {@code @Version} field; concurrent updates (the modern equivalent of the COBOL
 * {@code READ UPDATE} exclusive lock used by {@code COACTUPC}) raise
 * {@code OptimisticLockException}, which {@code GlobalExceptionHandler} maps to HTTP 409
 * Conflict &mdash; non-blocking optimistic concurrency replacing the VSAM CI-level
 * exclusive lock. The {@code @Version} field lives on the <em>entity</em>, never on this
 * repository.</p>
 *
 * <p><strong>PR-23 Lock Ordering.</strong> Service methods that lock multiple entities
 * acquire locks in the consistent order
 * {@code CUSTOMER -> ACCOUNT -> CARD -> TRANSACTION} to prevent deadlocks (matching the
 * documented VSAM convention).</p>
 *
 * <p><strong>Pattern &amp; scope.</strong> Per the Repository Pattern (AAP &sect;0.3.3 #1)
 * this extends {@code JpaRepository} (not {@code CrudRepository}) to inherit the full CRUD,
 * sort, and paging API. The explicit {@code @Repository} stereotype marks the interface for
 * component scanning and activates Spring's
 * {@code PersistenceExceptionTranslationPostProcessor}, translating provider-specific
 * persistence exceptions into the {@code org.springframework.dao.DataAccessException}
 * hierarchy. This is a pure data-access component and carries no business logic &mdash; the
 * PR-07 sign-based balance bucket and PR-08 interest-application REWRITE semantics live in
 * the batch writer/processor beans and services, which invoke the inherited {@code save}.</p>
 *
 * <p>No custom finder methods are declared. Browse-by-customer is performed via
 * {@code CardXrefRepository.findByAccountId}, which returns the cross-reference records that
 * carry the {@code account_id} linkage.</p>
 *
 * @see com.carddemo.entity.Account
 * @see org.springframework.data.jpa.repository.JpaRepository
 */
@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {
}
