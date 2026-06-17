package com.carddemo.service;

import java.util.Objects;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.carddemo.dto.AccountResponse;
import com.carddemo.dto.AccountUpdateRequest;
import com.carddemo.entity.Account;
import com.carddemo.entity.CardXref;
import com.carddemo.entity.Customer;
import com.carddemo.exception.ConcurrentModificationException;
import com.carddemo.exception.ResourceNotFoundException;
import com.carddemo.mapper.AccountMapper;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.CustomerRepository;

/**
 * Application service encapsulating the account-view and account-maintenance business operations of
 * the AWS CardDemo application: the flattened account&nbsp;+&nbsp;customer detail view and the
 * combined account&nbsp;+&nbsp;customer update guarded by optimistic locking.
 *
 * <h2>Legacy lineage &mdash; the two CICS online programs this service replaces</h2>
 * <p>This {@code @Service} is the layered-monolith re-expression of two CICS pseudo-conversational
 * online programs whose presentation tier (the BMS 3270 screens {@code COACTVW} / {@code COACTUP})
 * has been retired in favour of a stateless REST/JSON contract. Each public method ports exactly one
 * program's business logic (AAP &sect;0.4.1.3 &mdash; AccountService row):</p>
 * <table border="1">
 *   <caption>COBOL online program &rarr; service operation</caption>
 *   <tr><th>Legacy program</th><th>Function</th><th>Service method</th></tr>
 *   <tr><td>{@code app/cbl/COACTVWC.cbl}</td>
 *       <td>Account View &mdash; given an account id, resolve the owning customer through the card
 *           cross-reference ({@code CARDXREF} alternate index by account), read the {@code ACCOUNT}
 *           record and the {@code CUSTOMER} record, and display a single combined panel
 *           (paragraphs {@code 9000-READ-ACCT} &rarr; {@code 9200-GETCARDXREF-BYACCT} &rarr;
 *           {@code 9300-GETACCTDATA-BYACCT} &rarr; {@code 9400-GETCUSTDATA-BYCUST})</td>
 *       <td>{@link #getAccount(Long)}</td></tr>
 *   <tr><td>{@code app/cbl/COACTUPC.cbl}</td>
 *       <td>Account Maintenance &mdash; read account/customer for update, perform change detection
 *           ({@code 9700-CHECK-CHANGE-IN-REC}), then {@code REWRITE} the account and the customer
 *           with file-status checking; a record that changed underneath the editor raises the
 *           {@code DATA-WAS-CHANGED-BEFORE-UPDATE} guard ("Record changed by some one else. Please
 *           review")</td>
 *       <td>{@link #updateAccount(Long, AccountUpdateRequest)}</td></tr>
 * </table>
 * <p>The underlying record layouts are the COBOL {@code ACCOUNT-RECORD} of copybook
 * {@code app/cpy/CVACT01Y.cpy} (RECLN&nbsp;300), the {@code CUSTOMER-RECORD} of
 * {@code app/cpy/CVCUS01Y.cpy} (RECLN&nbsp;500), and the {@code CARD-XREF-RECORD} of
 * {@code app/cpy/CVACT03Y.cpy} (RECLN&nbsp;50).</p>
 *
 * <h2>Position in the layered architecture</h2>
 * <p>Strict Controller &rarr; Service &rarr; Repository &rarr; Entity flow (AAP &sect;0.3.2). This
 * service holds the business logic; it delegates all persistence to {@link AccountRepository},
 * {@link CustomerRepository}, and {@link CardXrefRepository} (the relational replacements for the
 * VSAM {@code ACCTDATA}, {@code CUSTDATA}, and {@code CARDXREF} KSDS datasets) and all
 * entity&harr;DTO translation to {@link AccountMapper}. It exposes only the immutable
 * {@link AccountResponse} DTO to its callers and never leaks a JPA entity ({@link Account},
 * {@link Customer}, {@link CardXref}) across the service boundary. Collaborators are supplied
 * exclusively through <strong>constructor injection</strong>, which replaces the static
 * {@code CALL}/{@code XCTL} linkage of the COBOL programs and keeps the class trivially unit-testable
 * with mocks.</p>
 *
 * <h2>Transaction semantics</h2>
 * <p>The read operation ({@link #getAccount(Long)}) runs inside a
 * {@code @Transactional(readOnly = true)} boundary, allowing the persistence provider to optimise for
 * read-only access; the mutating operation ({@link #updateAccount(Long, AccountUpdateRequest)}) runs
 * inside a read-write {@code @Transactional} boundary so the load&ndash;mutate&ndash;save unit commits
 * atomically. This mirrors the legacy {@code COACTUPC} flow, which rewrote the account and then the
 * customer within a single CICS unit of work and issued a {@code SYNCPOINT ROLLBACK} if either
 * {@code REWRITE} failed (COACTUPC L4065-L4103).</p>
 *
 * <h2>Concurrency &mdash; optimistic locking surfaced as HTTP&nbsp;409 (AAP &sect;0.6.6)</h2>
 * <p>The legacy lost-update hazard, guarded by {@code COACTUPC}'s {@code READ ... UPDATE} /
 * {@code REWRITE} file-status inspection and its {@code DATA-WAS-CHANGED-BEFORE-UPDATE} condition, is
 * reproduced here by <strong>two complementary mechanisms</strong> that both converge on
 * HTTP&nbsp;409:</p>
 * <ol>
 *   <li><strong>Explicit, deterministic stale-version check.</strong> Before applying any edit,
 *       {@link #updateAccount(Long, AccountUpdateRequest)} compares the client-supplied
 *       {@link AccountUpdateRequest#version()} (echoed from a prior {@link AccountResponse}) against
 *       the freshly-loaded {@link Account#getVersion()}. A mismatch means the client edited a stale
 *       snapshot, so the method throws the CardDemo domain
 *       {@link ConcurrentModificationException} carrying the legacy message
 *       {@link MessageService#RECORD_CHANGED_BY_OTHER}; the {@code GlobalExceptionHandler} maps it to
 *       HTTP&nbsp;409. This is the primary, screen-equivalent guard.</li>
 *   <li><strong>JPA {@code @Version} flush-race backstop.</strong> Even when the explicit check
 *       passes, two requests can still race between the load and the flush. The {@link Account}
 *       entity carries a JPA {@code @Version} column, so Hibernate detects such a collision on flush
 *       and raises {@code org.springframework.orm.ObjectOptimisticLockingFailureException}, which the
 *       {@code GlobalExceptionHandler} also maps to HTTP&nbsp;409. This service deliberately does
 *       <em>not</em> catch that exception &mdash; it is allowed to propagate so the single, central
 *       handler owns the response.</li>
 * </ol>
 * <p>Neither path ever silently overwrites a concurrent change.</p>
 *
 * <h2>PII suppression (AAP &sect;0.6.8 / &sect;0.7.1)</h2>
 * <ul>
 *   <li><strong>SSN never leaves in full.</strong> The customer Social Security Number
 *       ({@code CUST-SSN}) is persisted on {@link Customer} but is exposed outward only as its last
 *       four digits: {@link AccountResponse} declares no full-SSN component and the
 *       {@link AccountMapper} sources {@code ssnLastFour} exclusively through the sanctioned masking
 *       path. A full SSN <em>may</em> be supplied as input via {@link AccountUpdateRequest#ssn()} and
 *       is applied by the mapper, but it is never serialized back. This service never reads, logs, or
 *       echoes the raw SSN &mdash; "you cannot leak a field you never touch".</li>
 *   <li><strong>Immutable keys.</strong> The account identifier and the customer identifier are
 *       assigned natural keys; the update path never mutates them (the request DTO carries neither),
 *       exactly as in {@code COACTUPC}.</li>
 * </ul>
 *
 * <h2>Money</h2>
 * <p>All monetary fields are {@link java.math.BigDecimal} ({@code NUMERIC(12,2)}). The view and the
 * update only move values, so this service performs no rounding; any computed money introduced in the
 * future must use scale&nbsp;2 with {@link java.math.RoundingMode#HALF_UP} to preserve COBOL
 * fixed-point semantics (AAP &sect;0.7.1).</p>
 *
 * @see AccountRepository
 * @see CustomerRepository
 * @see CardXrefRepository
 * @see AccountMapper
 * @see AccountResponse
 * @see AccountUpdateRequest
 * @see <a href="file:app/cbl/COACTVWC.cbl">app/cbl/COACTVWC.cbl (account view)</a>
 * @see <a href="file:app/cbl/COACTUPC.cbl">app/cbl/COACTUPC.cbl (account maintenance)</a>
 */
@Service
public class AccountService {

    /**
     * Persistence gateway for the {@link Account} aggregate (the relational replacement for the VSAM
     * {@code ACCTDATA} KSDS). Used for primary-key access ({@code findById}) and the optimistic
     * {@code save}. Immutable, injected once at construction.
     */
    private final AccountRepository accountRepository;

    /**
     * Persistence gateway for the {@link Customer} aggregate (the relational replacement for the VSAM
     * {@code CUSTDATA} KSDS). Used for primary-key access ({@code findById}) and {@code save} of the
     * customer half of an account update. Immutable, injected once at construction.
     */
    private final CustomerRepository customerRepository;

    /**
     * Persistence gateway for the {@link CardXref} card&rarr;customer&rarr;account cross-reference (the
     * relational replacement for the VSAM {@code CARDXREF} KSDS and its account alternate index). Used
     * to resolve the owning customer id for a given account, reproducing the legacy
     * {@code 9200-GETCARDXREF-BYACCT} alternate-index read. Immutable, injected once at construction.
     */
    private final CardXrefRepository cardXrefRepository;

    /**
     * Stateless entity&harr;DTO mapper. Flattens an {@link Account} joined with its owning
     * {@link Customer} into an {@link AccountResponse} (with SSN masked to last four and the
     * optimistic-lock {@code version} echoed), and splits an {@link AccountUpdateRequest} back across
     * the two managed entities. Immutable, injected once at construction.
     */
    private final AccountMapper accountMapper;

    /**
     * Creates an {@code AccountService} with its required collaborators.
     *
     * <p>Constructor injection (the idiomatic replacement for COBOL {@code CALL}/{@code XCTL} static
     * linkage) makes every dependency {@code final} and mandatory and keeps the service trivially
     * testable with mock collaborators. Spring autowires the single constructor without an explicit
     * {@code @Autowired} annotation.</p>
     *
     * @param accountRepository  the account persistence gateway; must not be {@code null}
     * @param customerRepository the customer persistence gateway; must not be {@code null}
     * @param cardXrefRepository the card cross-reference persistence gateway; must not be {@code null}
     * @param accountMapper      the entity&harr;DTO mapper; must not be {@code null}
     */
    public AccountService(AccountRepository accountRepository,
                          CustomerRepository customerRepository,
                          CardXrefRepository cardXrefRepository,
                          AccountMapper accountMapper) {
        this.accountRepository = accountRepository;
        this.customerRepository = customerRepository;
        this.cardXrefRepository = cardXrefRepository;
        this.accountMapper = accountMapper;
    }

    /**
     * Returns the flattened account&nbsp;+&nbsp;customer view for the given account id, reproducing the
     * "Account View" panel of {@code COACTVWC}.
     *
     * <p><strong>Legacy behaviour ({@code COACTVWC} paragraph {@code 9000-READ-ACCT}).</strong> The
     * original program reads the account, resolves the owning customer through the card cross-reference
     * (the {@code CARDXREF} alternate index keyed by account id), reads the customer, and renders all
     * three on a single 3270 screen. This method reproduces that read chain:</p>
     * <ol>
     *   <li>load the {@link Account} by primary key &mdash; a miss is the relational equivalent of the
     *       legacy "account not found" branch and surfaces as HTTP&nbsp;404;</li>
     *   <li>resolve the owning {@link Customer} via {@link #resolveCustomer(Long)} (cross-reference by
     *       account &rarr; customer id &rarr; customer); a miss at either hop surfaces as
     *       HTTP&nbsp;404;</li>
     *   <li>flatten both into an {@link AccountResponse} via {@link AccountMapper}.</li>
     * </ol>
     *
     * <p><strong>PII (AAP &sect;0.6.8).</strong> The returned {@link AccountResponse} exposes the SSN
     * only as its last four digits ({@code ssnLastFour}); the mapper applies the masking and this
     * method never reads the raw SSN. The {@code version} component is populated from
     * {@link Account#getVersion()} so the client can echo it on a subsequent update.</p>
     *
     * @param acctId the account identifier ({@code ACCT-ID}) to view; must not be {@code null}
     * @return the flattened, PII-suppressed {@link AccountResponse} for the account and its owning
     *         customer; never {@code null}
     * @throws ResourceNotFoundException if the account, its cross-reference, or the owning customer
     *                                   does not exist (mapped to HTTP&nbsp;404)
     */
    @Transactional(readOnly = true)
    public AccountResponse getAccount(Long acctId) {
        // Step 1 — ACCOUNT read by primary key (COACTVWC 9300-GETACCTDATA-BYACCT); miss -> 404.
        Account account = accountRepository.findById(acctId)
                .orElseThrow(() -> ResourceNotFoundException.of("Account", acctId));

        // Step 2 — resolve the owning customer through the cross-reference (9200 -> 9400); miss -> 404.
        Customer customer = resolveCustomer(acctId);

        // Step 3 — flatten account + customer (SSN masked to last four; version echoed) for the client.
        return accountMapper.toAccountResponse(account, customer);
    }

    /**
     * Applies the editable account&nbsp;+&nbsp;customer fields of the given request under optimistic
     * locking, reproducing the "Account Maintenance" transaction of {@code COACTUPC}.
     *
     * <p><strong>Legacy behaviour ({@code COACTUPC}).</strong> The original program reads the account
     * and customer for update, performs change detection ({@code 9700-CHECK-CHANGE-IN-REC}), and then
     * {@code REWRITE}s the account followed by the customer within a single CICS unit of work, issuing
     * a {@code SYNCPOINT ROLLBACK} if either rewrite fails (COACTUPC L4065-L4103). A record that changed
     * underneath the editor trips the {@code DATA-WAS-CHANGED-BEFORE-UPDATE} guard
     * ("Record changed by some one else. Please review", COACTUPC L521-L522).</p>
     *
     * <p><strong>Flow.</strong></p>
     * <ol>
     *   <li>load the {@link Account} by primary key (miss &rarr; HTTP&nbsp;404);</li>
     *   <li>resolve the owning {@link Customer} via {@link #resolveCustomer(Long)} (miss &rarr;
     *       HTTP&nbsp;404);</li>
     *   <li><strong>explicit optimistic-lock guard:</strong> when the client supplied a non-{@code null}
     *       {@link AccountUpdateRequest#version()} that disagrees with the freshly-loaded
     *       {@link Account#getVersion()}, the client edited a stale snapshot &mdash; throw the CardDemo
     *       domain {@link ConcurrentModificationException} carrying
     *       {@link MessageService#RECORD_CHANGED_BY_OTHER} (mapped to HTTP&nbsp;409). A {@code null}
     *       version skips this check and relies on the JPA {@code @Version} backstop below;</li>
     *   <li>apply the editable fields via {@link AccountMapper#applyUpdate(AccountUpdateRequest, Account, Customer)}
     *       (account half + delegated customer half, including the full-SSN <em>input</em>); the
     *       immutable keys {@code acctId}/{@code custId} and the {@code version} token are never
     *       written here;</li>
     *   <li>persist the account and then the customer within this single read-write
     *       {@code @Transactional} method so the unit of work commits atomically (matching the legacy
     *       account-then-customer rewrite order). If a concurrent modification occurs between the load
     *       and the flush, Hibernate raises
     *       {@code org.springframework.orm.ObjectOptimisticLockingFailureException} on the
     *       {@code @Version}-guarded {@link Account}; this method does not catch it, so the
     *       {@code GlobalExceptionHandler} maps it to HTTP&nbsp;409 (the flush-race backstop);</li>
     *   <li>return the new state (including the Hibernate-incremented {@code version}) as an
     *       {@link AccountResponse}.</li>
     * </ol>
     *
     * <p><strong>PII (AAP &sect;0.6.8).</strong> The full SSN may be set through
     * {@link AccountUpdateRequest#ssn()} (input-only) and is applied by the mapper, but it is never
     * echoed: the returned {@link AccountResponse} carries only {@code ssnLastFour}. This method never
     * logs the request or the SSN.</p>
     *
     * @param acctId  the account identifier ({@code ACCT-ID}) to update; must not be {@code null}
     * @param request the combined account&nbsp;+&nbsp;customer edit carrying the optimistic-lock
     *                {@code version}; must not be {@code null}
     * @return the updated, PII-suppressed {@link AccountResponse} reflecting the new state and the
     *         incremented {@code version}; never {@code null}
     * @throws ResourceNotFoundException     if the account, its cross-reference, or the owning customer
     *                                       does not exist (mapped to HTTP&nbsp;404)
     * @throws ConcurrentModificationException if the supplied version is stale relative to the persisted
     *                                       account (mapped to HTTP&nbsp;409)
     */
    @Transactional
    public AccountResponse updateAccount(Long acctId, AccountUpdateRequest request) {
        // Step 1 — ACCOUNT read for update (COACTUPC account read); miss -> 404.
        Account account = accountRepository.findById(acctId)
                .orElseThrow(() -> ResourceNotFoundException.of("Account", acctId));

        // Step 2 — resolve the owning customer for update through the cross-reference; miss -> 404.
        Customer customer = resolveCustomer(acctId);

        // Step 3 — explicit, deterministic optimistic-lock guard (COACTUPC DATA-WAS-CHANGED-BEFORE-UPDATE,
        // L521-L522). A non-null client version that disagrees with the managed version means the client
        // edited a stale view; reject with the legacy message -> HTTP 409. A null version defers to the
        // JPA @Version flush-race backstop in step 5. Objects.equals handles the null/boxed-Long compare.
        if (request.version() != null && !Objects.equals(request.version(), account.getVersion())) {
            throw new ConcurrentModificationException(MessageService.RECORD_CHANGED_BY_OTHER);
        }

        // Step 4 — apply editable account fields + delegated customer fields (incl. SSN input). The
        // immutable acctId/custId keys and the @Version token are intentionally NOT written by the mapper.
        accountMapper.applyUpdate(request, account, customer);

        // Step 5 — persist atomically within this @Transactional unit of work, account then customer
        // (matching COACTUPC's REWRITE order L4065/L4085). saveAndFlush (NOT save) forces an IMMEDIATE
        // Hibernate flush so the versioned UPDATE is issued now and the managed Account's @Version token
        // is incremented IN PLACE *before* the response is mapped in step 6. With a plain save() the
        // UPDATE is deferred to transaction commit, so the mapped response would echo the STALE pre-flush
        // version (QA FINAL_ALT Issue 1) and force the client to re-read before its next optimistic-locked
        // update. The explicit flush also surfaces a concurrent-modification conflict here as
        // ObjectOptimisticLockingFailureException, which propagates to GlobalExceptionHandler -> HTTP 409.
        accountRepository.saveAndFlush(account);
        customerRepository.saveAndFlush(customer);

        // Step 6 — return the new state, including the Hibernate-incremented (post-flush) version, with
        // SSN masked. Because step 5 flushed, account.getVersion() now equals the persisted value a
        // subsequent GET reports, so the client can reuse the returned version for its next update.
        return accountMapper.toAccountResponse(account, customer);
    }

    /**
     * Resolves the {@link Customer} that owns the given account by walking the legacy
     * account&nbsp;&rarr;&nbsp;cross-reference&nbsp;&rarr;&nbsp;customer read chain.
     *
     * <p>This reproduces the {@code COACTVWC} paragraphs {@code 9200-GETCARDXREF-BYACCT} (read the
     * {@code CARDXREF} alternate index by account id, yielding the owning customer id) followed by
     * {@code 9400-GETCUSTDATA-BYCUST} (read the customer by that id):</p>
     * <ol>
     *   <li>fetch the first cross-reference row for the account &mdash; a {@code PageRequest.of(0, 1)}
     *       is used because only the owning customer id is needed and all of an account's
     *       cross-reference rows share the same {@code xref_cust_id}; an empty page is the relational
     *       equivalent of the legacy "account not found in cross-reference" branch and surfaces as
     *       HTTP&nbsp;404;</li>
     *   <li>load the {@link Customer} by the resolved id; a miss also surfaces as HTTP&nbsp;404.</li>
     * </ol>
     *
     * @param acctId the owning account identifier ({@code ACCT-ID}); must not be {@code null}
     * @return the owning {@link Customer}; never {@code null}
     * @throws ResourceNotFoundException if the account has no cross-reference row, or the referenced
     *                                   customer does not exist (mapped to HTTP&nbsp;404)
     */
    private Customer resolveCustomer(Long acctId) {
        // 9200-GETCARDXREF-BYACCT — alternate-index browse by account; take the first row for the
        // owning customer id (one page of size 1 is sufficient). No rows -> 404.
        CardXref xref = cardXrefRepository.findByXrefAcctId(acctId, PageRequest.of(0, 1))
                .getContent()
                .stream()
                .findFirst()
                .orElseThrow(() -> ResourceNotFoundException.of("Customer for account", acctId));

        // 9400-GETCUSTDATA-BYCUST — read the customer by the cross-referenced id. Miss -> 404.
        return customerRepository.findById(xref.getXrefCustId())
                .orElseThrow(() -> ResourceNotFoundException.of("Customer", xref.getXrefCustId()));
    }
}
