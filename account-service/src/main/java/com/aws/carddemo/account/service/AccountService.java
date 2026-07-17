/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.account.service;

import jakarta.persistence.EntityManager;

import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aws.carddemo.account.domain.Account;
import com.aws.carddemo.account.dto.AccountResponse;
import com.aws.carddemo.account.dto.AccountUpdateRequest;
import com.aws.carddemo.account.exception.AccountNotFoundException;
import com.aws.carddemo.account.mapper.AccountMapper;
import com.aws.carddemo.account.repository.AccountRepository;

/**
 * Application service that owns the account <em>inquiry</em> and <em>update</em> use cases and
 * defines the transaction boundary for the CardDemo Account Management microservice
 * (Feature&nbsp;F-003).
 *
 * <p>This class is the modern, testable re-expression of the runtime logic that the legacy
 * CICS/COBOL programs interleaved with screen handling. It coordinates the persistence
 * ({@link AccountRepository}), presentation ({@link AccountMapper}) and business-rule
 * ({@link AccountValidator}) collaborators, and translates failure conditions into the domain
 * exceptions that {@code GlobalExceptionHandler} maps to the correct HTTP semantics
 * (<strong>404</strong> not-found, <strong>400</strong> bad-input, <strong>409</strong>
 * concurrent-conflict). It sits in the service layer of the module's layered architecture
 * ({@code controller -> service -> repository -> domain}); the {@code controller} layer invokes
 * {@link #getAccount(String)} and {@link #updateAccount(String, AccountUpdateRequest)} exactly.</p>
 *
 * <h2>Legacy behavior re-homed (extraction-by-reference; nothing is ported)</h2>
 * <ul>
 *   <li><strong>Account inquiry ({@code CAVW}).</strong> {@link #getAccount(String)} reproduces
 *       {@code 9300-GETACCTDATA-BYACCT} of {@code app/cbl/COACTVWC.cbl} (L774&ndash;L807): a keyed
 *       read of {@code ACCTFILE} whose {@code DFHRESP(NORMAL)} path returns the record and whose
 *       {@code DFHRESP(NOTFND)} path (L789&ndash;L807) surfaces a not-found outcome.</li>
 *   <li><strong>Account update ({@code CAUP}).</strong> {@link #updateAccount(String,
 *       AccountUpdateRequest)} reproduces the {@code COACTUPC.cbl} update flow:
 *       {@code 9000-READ-ACCT} (fetch the before-image the user last saw), the field-edit chain
 *       {@code 1200-EDIT-*} (L1783&ndash;L2223), {@code 9600-WRITE-PROCESSING} (the
 *       {@code READ ... UPDATE} lock plus {@code REWRITE}, L3888&ndash;L4105), and
 *       {@code 9700-CHECK-CHANGE-IN-REC} (the before-image conflict check, L4109&ndash;L4192).</li>
 * </ul>
 *
 * <h2>Optimistic concurrency &mdash; why the explicit version check is required</h2>
 * <p>The legacy program re-reads the record under an exclusive lock and compares it field by
 * field against the before-image the user last saw; any drift aborts the write with
 * <em>"Record changed by some one else. Please review"</em>. JPA optimistic locking via the
 * {@link Account} {@code @Version} column provides the same "changed-under-me&nbsp;&rarr;&nbsp;reject"
 * semantics. Because the update flow must first <em>load</em> the managed entity &mdash; both to
 * preserve read-only fields such as {@code groupId} that are structurally absent from the request
 * and to reproduce the legacy read-then-rewrite sequence &mdash; the loaded entity already carries
 * the <em>current</em> database version. Hibernate's own {@code @Version} check at flush therefore
 * would not, on its own, detect a client that submitted a <em>stale</em> version. The explicit
 * comparison of {@code account.getVersion()} against {@code request.getVersion()} is consequently
 * the primary, deterministic conflict mechanism; the natural version increment at flush remains as
 * defense-in-depth for a genuinely concurrent commit occurring between load and flush. A stale
 * version raises {@link ObjectOptimisticLockingFailureException}, which the central handler maps to
 * HTTP&nbsp;409 with the spec wording {@code "Record updated by another user - please retry"} (that
 * wording is owned by the handler, never written here).</p>
 *
 * <h2>Immutability of identifier and group id</h2>
 * <p>{@link AccountUpdateRequest} structurally omits {@code accountId} and {@code groupId}
 * (read-only by design), so there is no body id to reconcile against the path: the authoritative
 * key is the URL path variable, validated via {@link AccountValidator#validateAccountId(String)}
 * (the realized form of the legacy {@code 1210-EDIT-ACCOUNT} edit). {@link AccountMapper#applyUpdate}
 * writes only the editable fields and never touches {@code accountId}, {@code groupId} or
 * {@code version}, so no code path in this service can mutate them.</p>
 * <p><strong>This is a CONFIRMED design decision (finalized; not pending sign-off):</strong> the
 * business-rule intent in Technical Specification &sect;2.2.3.2 ("Account ID and group ID immutable —
 * display only"), reaffirmed in &sect;4.2.3.2, governs; the editable-field 3270 BMS screen layer is
 * retired and not carried forward, so there is no legacy screen contract left to preserve. The
 * invariant is verified end-to-end by
 * {@code AccountApiIntegrationTest#accountIdAndGroupIdRemainImmutable_confirmedByDesign}.</p>
 *
 * <h2>Scope</h2>
 * <p>This service touches the <strong>account record only</strong>. The customer-record handling
 * that the legacy programs also perform (the {@code 9200} cross-reference read and the
 * {@code 9400}/{@code CUSTFILE} rewrite) is out of scope for this migration slice.</p>
 *
 * <h2>Security (AAP &sect;0.6.6)</h2>
 * <p>The service performs <strong>no logging</strong>, so the full account number, balances,
 * credit limits and cycle amounts can never be written to logs in plaintext. All persistence uses
 * bound parameters and never string-concatenated SQL: reads and writes flow through Spring Data JPA
 * ({@code findById} / {@code saveAndFlush}), and the update path additionally issues a
 * parameterized JPQL {@code UPDATE} through the injected {@code EntityManager} (followed by an
 * entity {@code refresh}), so there is no SQL-injection surface by construction on either path.</p>
 *
 * <h2>Wiring</h2>
 * <p>Collaborators are supplied by <strong>constructor injection</strong> only (no field
 * {@code @Autowired}); the four dependencies &mdash; the {@link AccountRepository},
 * the {@link AccountMapper}, the {@link AccountValidator} and the transaction-aware
 * {@code EntityManager} &mdash; are all {@code final}. As this class declares a single
 * constructor, Spring auto-wires it without an explicit {@code @Autowired} annotation.</p>
 *
 * <h2>Persistence paths</h2>
 * <p>The service uses <strong>two</strong> complementary persistence mechanisms, both fully
 * parameterized: (1) the Spring Data JPA {@link AccountRepository} for the keyed read
 * ({@code findById}, the legacy {@code READ}) and the field rewrite ({@code saveAndFlush}, the
 * legacy {@code REWRITE}); and (2) the injected {@code EntityManager}, used on the update path to
 * force exactly one optimistic-version increment for an accepted no-op rewrite (a guarded JPQL
 * {@code UPDATE}) and to {@code refresh} the managed entity so the mapped response carries the
 * committed {@code version}.</p>
 */
@Service
public class AccountService {

    /**
     * Persistence gateway for the {@code accounts} table. {@code findById} corresponds to the legacy
     * keyed {@code READ} and {@code save} to the {@code REWRITE}.
     */
    private final AccountRepository repository;

    /**
     * Bidirectional {@code Account}&nbsp;&harr;&nbsp;DTO converter. {@code toResponse} builds the read
     * projection; {@code applyUpdate} copies only the editable fields onto a managed entity.
     */
    private final AccountMapper mapper;

    /**
     * Business-rule validator reproducing the legacy {@code 1200-EDIT-*} edits and the
     * {@code CSUTLDPY} date chain. Throws {@code ValidationException} (mapped to HTTP&nbsp;400) on the
     * first violation.
     */
    private final AccountValidator validator;

    /**
     * JPA entity manager, used only on the update path to (a) force exactly one optimistic-version
     * increment for an accepted <em>no-op</em> rewrite and (b) refresh the managed entity so the
     * mapped response carries the committed {@code version}. Supplied by constructor injection as the
     * transaction-aware shared {@link EntityManager} proxy that Spring Data JPA registers.
     */
    private final EntityManager entityManager;

    /**
     * Creates the service with its collaborators.
     *
     * <p>All four dependencies are required and retained as {@code final} fields. Because this is the
     * only constructor, the Spring container injects the managed {@link AccountRepository},
     * {@link AccountMapper}, {@link AccountValidator} and (transaction-aware) {@link EntityManager}
     * beans automatically.</p>
     *
     * @param repository    the account persistence repository
     * @param mapper        the entity&nbsp;&harr;&nbsp;DTO mapper
     * @param validator     the account business-rule validator
     * @param entityManager the transaction-aware JPA entity manager (update-path version control)
     */
    public AccountService(final AccountRepository repository,
                          final AccountMapper mapper,
                          final AccountValidator validator,
                          final EntityManager entityManager) {
        this.repository = repository;
        this.mapper = mapper;
        this.validator = validator;
        this.entityManager = entityManager;
    }

    /**
     * Retrieves a single account by its 11-digit key, reproducing the legacy account-view read
     * {@code 9300-GETACCTDATA-BYACCT} of {@code app/cbl/COACTVWC.cbl} (L774&ndash;L807).
     *
     * <p>The lookup runs inside a read-only transaction (consistent with {@code open-in-view=false}),
     * so the entity remains available for mapping while the persistence context is open. The
     * sequence is:</p>
     * <ol>
     *   <li>Validate that {@code accountId} is a well-formed, non-zero 11-digit key
     *       (reproduces {@code 1210-EDIT-ACCOUNT}); an invalid key raises {@code ValidationException}
     *       &rarr; HTTP&nbsp;400.</li>
     *   <li>Read the record by key; an absent row reproduces the legacy {@code NOTFND} branch and
     *       raises {@link AccountNotFoundException} &rarr; HTTP&nbsp;404.</li>
     *   <li>Map the entity to the {@link AccountResponse} read projection (formatted currency and ISO
     *       dates are produced by the mapper).</li>
     * </ol>
     *
     * <p>None of the raised exceptions are caught here; they propagate to
     * {@code GlobalExceptionHandler}.</p>
     *
     * @param accountId the zero-padded 11-digit account key taken from the request path
     * @return the mapped read projection of the requested account
     * @throws com.aws.carddemo.account.exception.ValidationException if {@code accountId} is not a
     *                                                                valid 11-digit non-zero key
     * @throws AccountNotFoundException                               if no account exists for the key
     */
    @Transactional(readOnly = true)
    public AccountResponse getAccount(final String accountId) {
        // 1210-EDIT-ACCOUNT: reject a malformed key before touching persistence (-> 400).
        validator.validateAccountId(accountId);

        // Keyed READ of ACCTFILE; empty Optional reproduces DFHRESP(NOTFND) (-> 404). The
        // requested id is carried into the not-found message ("Account: <id> not found in Acct
        // Master file.", F-01 legacy parity); it is echoed only to the caller and never logged.
        final Account account = repository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));

        // Read projection: mapper echoes/formats the account fields for the client.
        return mapper.toResponse(account);
    }

    /**
     * Applies an edited set of account fields, reproducing the legacy account-update flow of
     * {@code app/cbl/COACTUPC.cbl} ({@code 9000}/{@code 9600}/{@code 9700}).
     *
     * <p>The whole load&nbsp;&rarr;&nbsp;check&nbsp;&rarr;&nbsp;apply&nbsp;&rarr;&nbsp;save sequence
     * executes within a single read-write transaction owned by this method, so the entity remains
     * managed and the version check at flush occurs inside this boundary. The steps, in the exact
     * order the legacy program applies them, are:</p>
     * <ol>
     *   <li><strong>Path-id validity</strong> &mdash; validate the URL key (reproduces
     *       {@code 1210-EDIT-ACCOUNT}). Because {@link AccountUpdateRequest} omits {@code accountId},
     *       the path is the authoritative id and there is no body id to reconcile; an invalid key
     *       raises {@code ValidationException} &rarr; HTTP&nbsp;400.</li>
     *   <li><strong>Field edits</strong> &mdash; validate the request body (active-status domain,
     *       signed monetary range and scale, strict dates), reproducing the {@code 1200-EDIT-*} chain;
     *       any violation raises {@code ValidationException} &rarr; HTTP&nbsp;400.</li>
     *   <li><strong>Load the managed entity</strong> &mdash; the {@code READ ... UPDATE} of
     *       {@code 9600} that fetches the current record; an absent row raises
     *       {@link AccountNotFoundException} &rarr; HTTP&nbsp;404.</li>
     *   <li><strong>Optimistic-concurrency check</strong> &mdash; the explicit before-image
     *       comparison of {@code 9700-CHECK-CHANGE-IN-REC} (L4109&ndash;L4192). If the client's
     *       submitted version differs from the current persisted version the update is rejected with
     *       {@link ObjectOptimisticLockingFailureException} &rarr; HTTP&nbsp;409. The loaded entity's
     *       version is non-null ({@code version BIGINT NOT NULL}), so the comparison is NPE-safe and a
     *       null client version (which the DTO already rejects as 400 upstream) would null-safely be
     *       treated as a conflict.</li>
     *   <li><strong>Apply editable fields</strong> &mdash; the mapper copies only the ten editable
     *       fields onto the managed entity and never touches {@code accountId}, {@code groupId} or
     *       {@code version}, enforcing identifier/group immutability at the mapping layer.</li>
     *   <li><strong>Persist and flush</strong> &mdash; the {@code REWRITE}. The entity is saved and
     *       flushed <em>within this transaction and before mapping</em> ({@code saveAndFlush}), so the
     *       {@code @Version} increment is materialized and the response carries the committed token
     *       rather than a pre-flush value. A genuinely concurrent modification detected at flush
     *       surfaces as {@link ObjectOptimisticLockingFailureException} &rarr; HTTP&nbsp;409, which
     *       also translates the load-to-flush race for a changed rewrite.</li>
     *   <li><strong>Guarantee token advancement for a no-op rewrite</strong> &mdash; the legacy program
     *       performs an unconditional {@code REWRITE} on an accepted update, so <em>every</em> accepted
     *       PUT must advance the optimistic-lock token. When the submitted representation is identical
     *       to the stored row, Hibernate detects no dirty state and emits no {@code UPDATE}, leaving the
     *       version unchanged. In that case exactly one increment is forced with a guarded conditional
     *       update ({@code WHERE account_id = :id AND version = :version}); a zero row count means the
     *       row was advanced concurrently and yields {@link ObjectOptimisticLockingFailureException}
     *       &rarr; HTTP&nbsp;409. The managed entity is then refreshed so the mapped response reflects
     *       the committed version. A changed rewrite has already advanced the token in the previous step
     *       and skips this branch, so the token advances by <em>exactly one</em> in either case.</li>
     *   <li><strong>Return</strong> &mdash; map the flushed/refreshed entity so the client receives the
     *       updated resource, including the newly incremented {@code version}.</li>
     * </ol>
     *
     * <p>None of the raised exceptions are caught here; each propagates to
     * {@code GlobalExceptionHandler}, which owns the 400/404/409 responses (including the 409 body
     * wording).</p>
     *
     * @param accountId the zero-padded 11-digit account key taken from the request path
     * @param request   the editable account fields plus the client's last-seen {@code version}
     * @return the mapped read projection of the updated account, with the incremented version
     * @throws com.aws.carddemo.account.exception.ValidationException if the key or any edited field
     *                                                                violates a business rule
     * @throws AccountNotFoundException                               if no account exists for the key
     * @throws ObjectOptimisticLockingFailureException                if the submitted version is stale
     */
    @Transactional
    public AccountResponse updateAccount(final String accountId, final AccountUpdateRequest request) {
        // 1. Path-id validity (1210-EDIT-ACCOUNT) -> 400 on a malformed key.
        validator.validateAccountId(accountId);

        // 2. Field edits (1200-EDIT-* chain) -> 400 on the first invalid field.
        validator.validate(request);

        // 3. Load the managed entity (legacy READ ... UPDATE, 9600) -> 404 if absent. The requested
        //    id is carried into the not-found message (F-01 legacy parity); echoed only to the
        //    caller and never logged.
        final Account account = repository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));

        // 4. Explicit before-image comparison (9700-CHECK-CHANGE-IN-REC) -> 409 on a stale version.
        //    The loaded entity carries the CURRENT DB version, so this deterministic check — not
        //    Hibernate's flush-time check alone — is what detects a client-submitted stale version.
        final Long expectedVersion = account.getVersion();
        if (!expectedVersion.equals(request.getVersion())) {
            throw new ObjectOptimisticLockingFailureException(Account.class, accountId);
        }

        // 5. Apply only the editable fields; accountId, groupId and version are never mutated here.
        mapper.applyUpdate(request, account);

        // 6. Persist AND FLUSH within this transaction, before mapping (C1). For a changed rewrite the
        //    dirty UPDATE increments @Version to expectedVersion + 1 and that value is visible on the
        //    managed entity immediately; a concurrent modification detected at flush surfaces as
        //    ObjectOptimisticLockingFailureException -> 409 (load-to-flush race translation).
        repository.saveAndFlush(account);

        // 7. Guarantee token advancement for an accepted NO-OP rewrite (M10). If the submitted
        //    representation was identical to the stored row, Hibernate emitted no UPDATE and the version
        //    is unchanged (still expectedVersion). Force exactly one increment with a guarded
        //    conditional update, then refresh so the mapped response reflects the committed version.
        //    A changed rewrite already advanced the token in step 6 and skips this branch, so the token
        //    advances by exactly one in either case.
        if (account.getVersion().equals(expectedVersion)) {
            final int rows = entityManager.createQuery(
                            "update Account a set a.version = a.version + 1 "
                                    + "where a.accountId = :id and a.version = :version")
                    .setParameter("id", accountId)
                    .setParameter("version", expectedVersion)
                    .executeUpdate();
            if (rows == 0) {
                // The row was advanced by a concurrent writer between load and this update.
                throw new ObjectOptimisticLockingFailureException(Account.class, accountId);
            }
            entityManager.refresh(account);
        }

        // 8. Return the updated resource, including the newly incremented version.
        return mapper.toResponse(account);
    }
}
