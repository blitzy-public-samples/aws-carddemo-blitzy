package com.carddemo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.carddemo.dto.AccountUpdateRequest;
import com.carddemo.dto.ErrorResponse;
import com.carddemo.entity.Account;
import com.carddemo.exception.GlobalExceptionHandler;
import com.carddemo.repository.AccountRepository;
import com.carddemo.service.AccountService;
import com.carddemo.service.MessageService;

import jakarta.persistence.EntityManager;

/**
 * Cross-cutting <strong>optimistic-locking parity</strong> test that pins the migrated CardDemo
 * behavior to the legacy lost-update guard of the CICS online account-update program
 * {@code COACTUPC} (REFERENCE source-of-truth: {@code app/cbl/COACTUPC.cbl}; account record layout
 * {@code app/cpy/CVACT01Y.cpy}).
 *
 * <h2>Legacy behavior reproduced</h2>
 * Before committing edits, {@code COACTUPC} re-reads the account (and customer) record
 * <em>with&nbsp;UPDATE</em> in {@code 9600-WRITE-PROCESSING} (COACTUPC&nbsp;L3888+) and then runs
 * {@code 9700-CHECK-CHANGE-IN-REC} (COACTUPC&nbsp;L4109&ndash;L4193), which compares the freshly
 * re-read record field-by-field against the {@code ACUP-OLD-*} snapshot captured when the screen was
 * first displayed. If <em>any</em> field differs it sets the {@code DATA-WAS-CHANGED-BEFORE-UPDATE}
 * 88-level (literal <em>"Record changed by some one else. Please review"</em>, COACTUPC&nbsp;L521&ndash;L522)
 * and <strong>skips the {@code REWRITE}</strong> (COACTUPC&nbsp;L3947&ndash;L3951) &mdash; it refuses to
 * overwrite a record someone else changed "while we were out". That "did someone change the record while
 * we were out?" guard is exactly JPA {@code @Version} optimistic locking (AAP&nbsp;&sect;0.6.6 /
 * &sect;0.7.1).
 *
 * <h2>Two convergent 409 paths &mdash; both proven here at their source</h2>
 * In the Spring Boot monolith the guard is realized two complementary ways, both ultimately mapped to
 * <strong>HTTP&nbsp;409 Conflict</strong> by {@code GlobalExceptionHandler}:
 * <ol>
 *   <li><strong>Explicit service-level stale-version check</strong> &mdash;
 *       {@link AccountService#updateAccount(Long, AccountUpdateRequest)} compares the client-echoed
 *       {@link AccountUpdateRequest#version()} against the freshly loaded {@link Account#getVersion()};
 *       a mismatch throws the CardDemo domain
 *       {@code com.carddemo.exception.ConcurrentModificationException} carrying
 *       {@link MessageService#RECORD_CHANGED_BY_OTHER}. This mirrors {@code 9700}'s application-level
 *       change detection (Scenario&nbsp;A).</li>
 *   <li><strong>JPA {@code @Version} flush-race backstop</strong> &mdash; a concurrent committed change
 *       between read and flush is detected by Hibernate on the {@code @Version}-guarded {@link Account},
 *       which raises {@link ObjectOptimisticLockingFailureException}. This mirrors the CICS file-level
 *       conflict on {@code REWRITE} (Scenario&nbsp;B).</li>
 * </ol>
 * Scenario&nbsp;C is a sanity check proving the {@code @Version} token actually advances on a normal
 * update (without which optimistic locking would silently no-op). Scenario&nbsp;D then proves the
 * <strong>HTTP-status mapping</strong> of the {@code @Version} backstop: the application's
 * {@code GlobalExceptionHandler} translates {@link ObjectOptimisticLockingFailureException} to
 * <strong>HTTP&nbsp;409</strong>.
 *
 * <p>Together Scenarios&nbsp;B and&nbsp;D close the end-to-end "{@code @Version} conflict &rarr;
 * HTTP&nbsp;409" proof at this level: Scenario&nbsp;B proves Hibernate genuinely raises
 * {@link ObjectOptimisticLockingFailureException} on a real version race, and Scenario&nbsp;D proves the
 * real {@code @RestControllerAdvice} bean maps that exact exception to HTTP&nbsp;409 (with the
 * Hibernate/entity/SQL detail suppressed from the client body, AAP&nbsp;&sect;0.6.8). The complementary
 * full web-stack assertion for the <em>explicit</em> stale-version guard (Scenario&nbsp;A's domain
 * {@code com.carddemo.exception.ConcurrentModificationException}) is exercised through MockMvc by the
 * sibling {@code controller/AccountControllerTest} ({@code updateAccount_staleVersion_returns409}); that
 * domain-path MockMvc round-trip is deliberately not duplicated here so this class stays focused on the
 * optimistic-lock contract rather than web-security wiring.</p>
 *
 * <h2>&#9888; Deliberate name-clash with {@code java.util.ConcurrentModificationException}</h2>
 * The production conflict exception {@code com.carddemo.exception.ConcurrentModificationException}
 * deliberately shares its simple name with the unrelated JDK
 * {@code java.util.ConcurrentModificationException}. To avoid silently testing the wrong type, this
 * file <strong>never imports either</strong> {@code ConcurrentModificationException}; the domain type
 * is referenced exclusively by its fully-qualified name
 * {@code com.carddemo.exception.ConcurrentModificationException} in Scenario&nbsp;A.
 *
 * <h2>Test context &amp; isolation</h2>
 * Runs under {@link SpringBootTest} with the {@code test} profile (in-memory H2 in PostgreSQL mode;
 * Flyway {@code V1}&ndash;{@code V4} applied on context startup, seeding 50 accounts with
 * {@code version = 0}). Every test method is {@link Transactional} so its changes roll back, leaving
 * the seeded database pristine for sibling tests.
 *
 * @see AccountService#updateAccount(Long, AccountUpdateRequest)
 * @see Account
 * @see MessageService#RECORD_CHANGED_BY_OTHER
 * @see <a href="file:app/cbl/COACTUPC.cbl">app/cbl/COACTUPC.cbl (9600-WRITE-PROCESSING / 9700-CHECK-CHANGE-IN-REC)</a>
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("AccountConcurrencyTest \u2014 @Version optimistic-lock \u2192 HTTP 409 parity vs COACTUPC")
class AccountConcurrencyTest {

    /**
     * A seeded account identifier. Flyway {@code V3__seed_master.sql} seeds 50 accounts with ids
     * {@code 1..50} at {@code version = 0}; account {@code 1} additionally has a {@code card_xref} row
     * resolving to an existing customer, so {@link AccountService#updateAccount(Long, AccountUpdateRequest)}
     * reaches its version guard (the service resolves the owning customer <em>before</em> the version
     * check; an account without a cross-reference would surface HTTP&nbsp;404 first).
     */
    private static final Long ACCT_ID = 1L;

    /** Service under test for the explicit stale-version guard (Scenario&nbsp;A). */
    @Autowired
    private AccountService accountService;

    /** Repository used to load, flush, and persist the {@link Account} aggregate. */
    @Autowired
    private AccountRepository accountRepository;

    /**
     * JPA {@link EntityManager} used to {@code detach}/{@code flush}/{@code clear} the persistence
     * context so the JPA {@code @Version} race (Scenario&nbsp;B) and the increment sanity check
     * (Scenario&nbsp;C) operate on independent snapshots rather than the first-level cache.
     */
    @Autowired
    private EntityManager entityManager;

    /**
     * The application's central {@code @RestControllerAdvice} &mdash; the SAME configured bean that maps
     * exceptions to HTTP statuses in production. Autowired (rather than instantiated) so Scenario&nbsp;D
     * proves the real, wired advice translates the JPA {@code @Version} backstop to HTTP&nbsp;409.
     */
    @Autowired
    private GlobalExceptionHandler globalExceptionHandler;

    // ---------------------------------------------------------------------------------------------
    // Scenario A — explicit service-level stale-version guard -> domain ConcurrentModificationException
    //   Mirrors COACTUPC 9700-CHECK-CHANGE-IN-REC application-level change detection.
    // ---------------------------------------------------------------------------------------------

    /**
     * When the client submits an account update whose optimistic-lock {@code version} disagrees with
     * the persisted account, {@link AccountService#updateAccount(Long, AccountUpdateRequest)} must throw
     * the CardDemo domain {@code com.carddemo.exception.ConcurrentModificationException} carrying the
     * legacy message {@link MessageService#RECORD_CHANGED_BY_OTHER} &mdash; reproducing
     * {@code COACTUPC}'s {@code DATA-WAS-CHANGED-BEFORE-UPDATE} guard.
     */
    @Test
    @Transactional
    @DisplayName("Scenario A: stale version -> domain ConcurrentModificationException (COACTUPC 9700)")
    void updateWithStaleVersion_throwsDomainConcurrentModificationException() {
        // Arrange: load the seeded account and capture its current (fresh) version.
        Account acct = accountRepository.findById(ACCT_ID).orElseThrow();
        Long currentVersion = acct.getVersion();

        // Build a well-formed request from the account's current values but with a STALE version
        // (any value != current). +1 simulates "the row was updated by someone else after I read it".
        AccountUpdateRequest staleRequest = buildRequestFrom(acct, currentVersion + 1);

        // Act + Assert: the service's explicit guard rejects the stale snapshot with the domain
        // exception. The domain type is referenced by its FULLY-QUALIFIED name on purpose so it can
        // never be confused with java.util.ConcurrentModificationException (see class javadoc).
        assertThatThrownBy(() -> accountService.updateAccount(ACCT_ID, staleRequest))
                .isInstanceOf(com.carddemo.exception.ConcurrentModificationException.class)
                .hasMessage(MessageService.RECORD_CHANGED_BY_OTHER);
    }

    // ---------------------------------------------------------------------------------------------
    // Scenario B — JPA @Version backstop -> ObjectOptimisticLockingFailureException
    //   Mirrors the CICS file-level conflict on REWRITE (COACTUPC 9600).
    // ---------------------------------------------------------------------------------------------

    /**
     * Reproduces the lost-update race at the persistence layer: a snapshot read at version {@code v} is
     * detached, a concurrent committed update advances the row to {@code v+1}, and the subsequent
     * attempt to persist the stale snapshot must fail with
     * {@link ObjectOptimisticLockingFailureException} &mdash; the JPA {@code @Version} backstop that
     * {@code GlobalExceptionHandler} maps to HTTP&nbsp;409.
     */
    @Test
    @Transactional
    @DisplayName("Scenario B: concurrent @Version race -> ObjectOptimisticLockingFailureException (COACTUPC 9600)")
    void concurrentDetachedUpdate_triggersOptimisticLockFailure() {
        // Arrange: load a snapshot and DETACH it so it keeps its original version (v), independent of
        // the persistence context's identity map.
        Account stale = accountRepository.findById(ACCT_ID).orElseThrow();
        entityManager.detach(stale);

        // Simulate the concurrent, committed update that bumps the row's version v -> v+1.
        Account fresh = accountRepository.findById(ACCT_ID).orElseThrow();
        fresh.setCurrBal(fresh.getCurrBal().add(new BigDecimal("1.00")));
        accountRepository.saveAndFlush(fresh);

        // Clear the persistence context so the stale merge below resolves its target from the database
        // (now at v+1) rather than from the cached managed instance, making the version conflict
        // deterministic regardless of identity-map timing.
        entityManager.clear();

        // Act + Assert: persisting the stale snapshot (still version v) must raise the optimistic-lock
        // failure. saveAndFlush merges the detached entity; Hibernate's version check (v vs v+1) fails
        // and Spring translates it to ObjectOptimisticLockingFailureException.
        stale.setCurrBal(stale.getCurrBal().add(new BigDecimal("2.00")));
        assertThatThrownBy(() -> accountRepository.saveAndFlush(stale))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    // ---------------------------------------------------------------------------------------------
    // Scenario C — sanity: @Version actually increments on a normal update
    //   Without this the optimistic-lock guard would silently no-op.
    // ---------------------------------------------------------------------------------------------

    /**
     * A successful update must advance the JPA {@code @Version} token, confirming optimistic locking is
     * truly wired (Hibernate increments {@code version} on flush). The account is mutated and flushed,
     * the persistence context is cleared, and the reloaded row must report a strictly greater version
     * equal to {@code v0 + 1}.
     */
    @Test
    @Transactional
    @DisplayName("Scenario C: @Version increments by one on a successful update")
    void version_incrementsOnSuccessfulUpdate() {
        // Arrange: load the account and capture its starting version.
        Account acct = accountRepository.findById(ACCT_ID).orElseThrow();
        Long v0 = acct.getVersion();

        // Act: mutate a non-key money field and flush so Hibernate issues the versioned UPDATE.
        acct.setCurrBal(acct.getCurrBal().add(new BigDecimal("0.01")));
        accountRepository.saveAndFlush(acct);

        // Force a fresh read from the database rather than the first-level cache.
        entityManager.clear();

        // Assert: the reloaded version advanced by exactly one (v0 -> v0 + 1).
        Account reloaded = accountRepository.findById(ACCT_ID).orElseThrow();
        assertThat(reloaded.getVersion()).isGreaterThan(v0);
        assertThat(reloaded.getVersion()).isEqualTo(v0 + 1L);
    }

    // ---------------------------------------------------------------------------------------------
    // Scenario D — the JPA @Version backstop SURFACES AS HTTP 409 (GlobalExceptionHandler mapping)
    //   Completes Scenario B: B proves Hibernate RAISES ObjectOptimisticLockingFailureException on a
    //   real @Version race; D proves the central @RestControllerAdvice MAPS that exact exception to
    //   HTTP 409 — the second of the two convergent 409 paths required by AAP §0.6.6 / §0.7.1.
    // ---------------------------------------------------------------------------------------------

    /**
     * Proves the HTTP-status half of the JPA {@code @Version} backstop: the application's
     * {@code GlobalExceptionHandler} translates the {@link ObjectOptimisticLockingFailureException}
     * raised by Hibernate (Scenario&nbsp;B) into <strong>HTTP&nbsp;409 Conflict</strong>, reproducing
     * the legacy {@code COACTUPC} lost-update guard at the API boundary (AAP&nbsp;&sect;0.6.6).
     *
     * <p>The exact exception type Hibernate raises on a {@code @Version} flush race is constructed
     * against the {@code @Version}-guarded {@link Account} aggregate and handed to the real, autowired
     * {@code @RestControllerAdvice} &mdash; whose {@code @ExceptionHandler} covers both the CardDemo
     * domain {@code ConcurrentModificationException} (Scenario&nbsp;A) and this Hibernate type. The
     * response must carry HTTP&nbsp;409 and, per the PII/internals-suppression rule
     * (AAP&nbsp;&sect;0.6.8), the client body must expose only the generic conflict message &mdash;
     * never the Hibernate/entity/SQL detail (for example the {@code "Account"} entity name or the
     * exception class name).</p>
     *
     * <p>This method is intentionally <em>not</em> {@link Transactional}: it asserts a pure
     * exception&rarr;status mapping and touches no persistence, so it needs no transactional rollback.</p>
     */
    @Test
    @DisplayName("Scenario D: ObjectOptimisticLockingFailureException -> HTTP 409 Conflict (GlobalExceptionHandler mapping)")
    void optimisticLockFailure_isMappedToHttp409_byGlobalExceptionHandler() {
        // Arrange: the precise exception Hibernate raises on a @Version conflict (proven in Scenario B),
        // here against the @Version-guarded Account aggregate, plus a representative request path.
        ObjectOptimisticLockingFailureException jpaConflict =
                new ObjectOptimisticLockingFailureException(Account.class, ACCT_ID);
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/accounts/" + ACCT_ID);

        // Act: the SAME @RestControllerAdvice bean wired in production translates the exception.
        ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleConflict(jpaConflict, request);

        // Assert: HTTP 409 with a populated, PII-safe body.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(response.getBody().message()).isNotBlank();

        // Internals must NOT leak to the client (AAP §0.6.8): the generic conflict text carries no
        // Hibernate/entity/SQL detail such as the "Account" entity name or the exception class name.
        assertThat(response.getBody().message())
                .doesNotContain("Account", "ObjectOptimisticLockingFailureException", "Optimistic");
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * Builds a structurally valid {@link AccountUpdateRequest} from the supplied account's current
     * values, overriding only the optimistic-lock {@code version}.
     *
     * <p>The account-side fields are copied from {@code acct} so the request represents a realistic
     * "I edited the account I just viewed" payload; the customer-side fields are left {@code null}
     * (the DTO's edit fields are null-tolerant and the account-update field validation runs at the
     * controller layer via {@code @Valid}, not in the service). Only the {@code version} component is
     * varied, which is the single input the service's stale-version guard inspects.</p>
     *
     * @param acct    the seeded account whose current values seed the request
     * @param version the optimistic-lock token to embed (a stale value triggers the 409 guard)
     * @return a fully-populated {@link AccountUpdateRequest} differing from the persisted state only in
     *         its {@code version}
     */
    private AccountUpdateRequest buildRequestFrom(Account acct, Long version) {
        return new AccountUpdateRequest(
                acct.getActiveStatus(),    // activeStatus           (ACCT-ACTIVE-STATUS)
                acct.getCurrBal(),         // currentBalance         (ACCT-CURR-BAL)
                acct.getCreditLimit(),     // creditLimit            (ACCT-CREDIT-LIMIT)
                acct.getCashCreditLimit(), // cashCreditLimit        (ACCT-CASH-CREDIT-LIMIT)
                acct.getCurrCycCredit(),   // currentCycleCredit     (ACCT-CURR-CYC-CREDIT)
                acct.getCurrCycDebit(),    // currentCycleDebit      (ACCT-CURR-CYC-DEBIT)
                acct.getOpenDate(),        // openDate               (ACCT-OPEN-DATE)
                acct.getExpirationDate(),  // expirationDate         (ACCT-EXPIRAION-DATE)
                acct.getReissueDate(),     // reissueDate            (ACCT-REISSUE-DATE)
                acct.getAddrZip(),         // accountAddressZip      (ACCT-ADDR-ZIP)
                acct.getGroupId(),         // accountGroupId         (ACCT-GROUP-ID)
                null,                      // firstName              (CUST-FIRST-NAME)
                null,                      // middleName             (CUST-MIDDLE-NAME)
                null,                      // lastName               (CUST-LAST-NAME)
                null,                      // addressLine1           (CUST-ADDR-LINE-1)
                null,                      // addressLine2           (CUST-ADDR-LINE-2)
                null,                      // addressLine3           (CUST-ADDR-LINE-3)
                null,                      // stateCode              (CUST-ADDR-STATE-CD)
                null,                      // countryCode            (CUST-ADDR-COUNTRY-CD)
                null,                      // customerZip            (CUST-ADDR-ZIP)
                null,                      // phoneNumber1           (CUST-PHONE-NUM-1)
                null,                      // phoneNumber2           (CUST-PHONE-NUM-2)
                null,                      // ssn                    (CUST-SSN, input only)
                null,                      // governmentIssuedId     (CUST-GOVT-ISSUED-ID)
                null,                      // dateOfBirth            (CUST-DOB-YYYY-MM-DD)
                null,                      // eftAccountId           (CUST-EFT-ACCOUNT-ID)
                null,                      // primaryCardHolderIndicator (CUST-PRI-CARD-HOLDER-IND)
                null,                      // ficoScore              (CUST-FICO-CREDIT-SCORE)
                version                    // version                (optimistic-lock token)
        );
    }
}
