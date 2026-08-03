/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.carddemo.account.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.carddemo.account.repository.AccountRepository;
import com.carddemo.account.repository.CardXrefRepository;
import com.carddemo.account.repository.CustomerRepository;
import com.carddemo.account.service.AccountService;
import com.carddemo.common.domain.Account;
import com.carddemo.common.domain.CardXref;
import com.carddemo.common.domain.Customer;
import com.carddemo.common.dto.AccountUpdateRequestDto;
import com.carddemo.common.dto.ErrorResponse;
import com.carddemo.common.dto.SessionContext;
import com.carddemo.common.exception.OptimisticLockConflictException;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.math.BigDecimal;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

/**
 * :purpose: Full-context integration test proving that the migrated account-update
 *  flow reproduces the legacy CICS ``COACTUPC`` (transaction ``CAUP``,
 *  ``PUT /accounts/{id}``) read-snapshot-compare-rewrite concurrency semantics as
 *  JPA ``@Version`` optimistic locking. A write against a stale account version
 *  raises {@link ObjectOptimisticLockingFailureException} on flush, which
 *  ``AccountService.updateAccount`` rethrows as {@link OptimisticLockConflictException}
 *  and the shared exception handler surfaces as HTTP 409 Conflict carrying the
 *  verbatim legacy message ``DATA-WAS-CHANGED-BEFORE-UPDATE`` (COACTUPC:L522).
 * :note: The materialized service re-reads the managed entity inside its own
 *  transaction, so a deterministic single-threaded conflict is produced by sharing
 *  the persistence context: an ambient {@link TransactionTemplate} caches the
 *  version-N account, a separate committed transaction advances the row to version
 *  N+1, and the service then flushes the cached stale entity into the conflict. The
 *  class is intentionally NOT annotated with test-level ``@Transactional`` (a
 *  rollback-only test transaction would prevent the real commit that advances the
 *  version); isolation is provided by explicit per-test seed and cleanup instead.
 *  Sensitive customer fields (SSN, government-issued id, card number) are seeded but
 *  never asserted.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
// The production filter chain IS applied: every request below presents the shared
// session context, which is exactly how a request authenticates in production, so the
// security, correlation-id and hardening filters are all exercised end to end.
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class OptimisticLockConflictIT {

    // The ephemeral PostgreSQL 18 backing store is provisioned by the ``test`` profile
    // Testcontainers JDBC-URL scheme (``jdbc:tc:postgresql:18:///carddemo`` +
    // ``ContainerDatabaseDriver``), so the ``@Version`` optimistic-lock behavior is
    // exercised against real PostgreSQL without a manually managed container. The
    // shared migration set creates every entity-scanned table (``card_xref``
    // included), so Hibernate validates the mapping instead of altering the schema:
    // that database is reused by the other classes in this fork, and a schema export
    // would silently reshape it underneath them.

    static {
        // The customer PII columns are encrypted at rest by a JPA AttributeConverter
        // that resolves its AES-256 key from the ``carddemo.pii.key`` system property.
        // A throwaway all-zero test key (never a real secret) is installed ONLY when the
        // environment supplies none, so this class still runs standalone while never
        // displacing the fixture key the module's Surefire/Failsafe configuration
        // installs. Overwriting it unconditionally would re-key the JVM for every later
        // context in the same fork, and the seeded PII already encrypted at rest under
        // the fixture key would then fail to decrypt (AEADBadTagException).
        if (System.getProperty("carddemo.pii.key") == null
                && System.getenv("CARDDEMO_PII_KEY") == null) {
            System.setProperty("carddemo.pii.key", Base64.getEncoder().encodeToString(new byte[32]));
        }
    }

    /** :purpose: Account identifier of the self-contained fixture row (non-colliding with the 1..50 seed). */
    private static final Long SEED_ACCT_ID = 90000003L;

    /** :purpose: Customer identifier of the self-contained fixture row (non-colliding with the 1..50 seed). */
    private static final Long SEED_CUST_ID = 900000003L;

    /** :purpose: Sixteen-digit card number of the fixture cross-reference (non-sensitive fabricated fixture). */
    private static final String SEED_CARD_NUM = "9000000000000003";

    /** :purpose: Original persisted balance of the fixture account at version 0. */
    private static final BigDecimal SEED_BALANCE = new BigDecimal("147.00");

    /**
     * :purpose: Balance written by the out-of-band "winning" writer that advances the
     *  row to version 1; the value that must survive the losing stale update.
     */
    private static final BigDecimal WINNING_BALANCE = new BigDecimal("4321.55");

    /**
     * :purpose: Balance carried by the stale losing update; it must NEVER be persisted
     *  because that write is rejected by the optimistic-lock conflict.
     */
    private static final BigDecimal STALE_BALANCE = new BigDecimal("9999.99");

    // Remaining fixture field values (fabricated, non-sensitive) used both to seed the
    // fixture and to build a fully valid update payload so the ONLY difference the flush
    // detects on the account is the balance change against a stale @Version.
    private static final String SEED_ACTIVE_STATUS = "Y";
    private static final BigDecimal SEED_CREDIT_LIMIT = new BigDecimal("5000.00");
    private static final BigDecimal SEED_CASH_CREDIT_LIMIT = new BigDecimal("1000.00");
    private static final BigDecimal SEED_CYC_CREDIT = new BigDecimal("0.00");
    private static final BigDecimal SEED_CYC_DEBIT = new BigDecimal("0.00");
    private static final String SEED_OPEN_DATE = "2013-08-23";
    private static final String SEED_EXPIRAION_DATE = "2026-01-10";
    private static final String SEED_REISSUE_DATE = "2024-01-10";
    private static final String SEED_ACCT_ADDR_ZIP = "99999";
    private static final String SEED_GROUP_ID = null;

    private static final String SEED_CUST_FIRST_NAME = "Larry";
    private static final String SEED_CUST_MIDDLE_NAME = "Cody";
    private static final String SEED_CUST_LAST_NAME = "Homenick";
    private static final String SEED_CUST_ADDR_LINE_1 = "362 Esta Parks";
    private static final String SEED_CUST_ADDR_LINE_2 = "Apt 390";
    private static final String SEED_CUST_ADDR_LINE_3 = "New Gladys";
    private static final String SEED_CUST_ADDR_STATE_CD = "GA";
    private static final String SEED_CUST_ADDR_COUNTRY_CD = "USA";
    // Paired with the state code above in the CSLKPCDY state-ZIP table, which
    // COACTUPC's 1280-EDIT-US-STATE-ZIP-CD checks on every rewrite.
    private static final String SEED_CUST_ADDR_ZIP = "30301";
    // Area codes are taken from the CSLKPCDY general-purpose table: COACTUPC's
    // EDIT-AREA-CODE rejects anything absent from it, and this fixture is submitted
    // through the full update path whose subject here is the optimistic lock.
    private static final String SEED_CUST_PHONE_1 = "(212)396-9024";
    private static final String SEED_CUST_PHONE_2 = "(801)168-8826";
    private static final String SEED_CUST_SSN = "317460867";
    private static final String SEED_CUST_GOVT_ID = "GA1234567";
    private static final String SEED_CUST_DOB = "1987-11-30";
    private static final String SEED_CUST_EFT_ID = "0006465789";
    private static final String SEED_CUST_PRI_HOLDER_IND = "Y";
    private static final Integer SEED_CUST_FICO = 616;

    /** :purpose: In-thread HTTP client dispatching against the real controller/service/repository stack. */
    @Autowired
    private MockMvc mockMvc;

    /** :purpose: Service under test (``COACTUPC``); driven directly in the service-level scenario. */
    @Autowired
    private AccountService accountService;

    /** :purpose: Account master repository used to seed, reload, and clean up the fixture row. */
    @Autowired
    private AccountRepository accountRepository;

    /** :purpose: Customer master repository used to seed and clean up the fixture customer. */
    @Autowired
    private CustomerRepository customerRepository;

    /** :purpose: Card cross-reference repository used to seed the account-to-customer linkage. */
    @Autowired
    private CardXrefRepository cardXrefRepository;

    /** :purpose: JSON serializer/deserializer for the request payload and the error body. */
    @Autowired
    private ObjectMapper objectMapper;

    /** :purpose: Transaction manager backing the ambient and out-of-band transaction templates. */
    @Autowired
    private PlatformTransactionManager transactionManager;

    /** :purpose: Plain-SQL access used to advance the persisted version out-of-band. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** :purpose: Entity manager used to pre-load the account into the ambient persistence context. */
    @PersistenceContext
    private EntityManager entityManager;

    /**
     * :purpose: Install a self-contained customer, account (version 0), and card
     *  cross-reference so the update read order (xref, then account, then customer)
     *  resolves before the optimistic-lock check is reached. A fresh customer is used
     *  (rather than the plaintext-seeded rows) so its encrypted PII round-trips under
     *  the configured test key.
     */
    @BeforeEach
    void setUp() {
        deleteFixture();
        customerRepository.save(buildFixtureCustomer());
        accountRepository.save(buildFixtureAccount());
        cardXrefRepository.save(new CardXref(SEED_CARD_NUM, SEED_CUST_ID, SEED_ACCT_ID));
    }

    /**
     * :purpose: Remove the fixture rows in foreign-key-safe order so each test runs
     *  in isolation and in any order.
     */
    @AfterEach
    void tearDown() {
        deleteFixture();
    }

    /**
     * :purpose: Delete the cross-reference, account, and customer fixture rows if
     *  present, honoring the ``card_xref -> account/customer`` foreign-key order.
     */
    private void deleteFixture() {
        cardXrefRepository.deleteAllById(List.of(SEED_CARD_NUM));
        accountRepository.deleteAllById(List.of(SEED_ACCT_ID));
        customerRepository.deleteAllById(List.of(SEED_CUST_ID));
    }

    /**
     * :purpose: Build the fixture account entity at its initial (version 0) state.
     * :returns: a fully populated {@link Account} ready to persist.
     */
    private Account buildFixtureAccount() {
        Account account = new Account();
        account.setAcctId(SEED_ACCT_ID);
        account.setAcctActiveStatus(SEED_ACTIVE_STATUS);
        account.setAcctCurrBal(SEED_BALANCE);
        account.setAcctCreditLimit(SEED_CREDIT_LIMIT);
        account.setAcctCashCreditLimit(SEED_CASH_CREDIT_LIMIT);
        account.setAcctOpenDate(SEED_OPEN_DATE);
        account.setAcctExpiraionDate(SEED_EXPIRAION_DATE);
        account.setAcctReissueDate(SEED_REISSUE_DATE);
        account.setAcctCurrCycCredit(SEED_CYC_CREDIT);
        account.setAcctCurrCycDebit(SEED_CYC_DEBIT);
        account.setAcctAddrZip(SEED_ACCT_ADDR_ZIP);
        account.setAcctGroupId(SEED_GROUP_ID);
        return account;
    }

    /**
     * :purpose: Build the fixture customer entity with fabricated, non-sensitive
     *  values for every non-nullable column (PII fields are encrypted on save).
     * :returns: a fully populated {@link Customer} ready to persist.
     */
    private Customer buildFixtureCustomer() {
        Customer customer = new Customer();
        customer.setCustId(SEED_CUST_ID);
        customer.setCustFirstName(SEED_CUST_FIRST_NAME);
        customer.setCustMiddleName(SEED_CUST_MIDDLE_NAME);
        customer.setCustLastName(SEED_CUST_LAST_NAME);
        customer.setCustAddrLine1(SEED_CUST_ADDR_LINE_1);
        customer.setCustAddrLine2(SEED_CUST_ADDR_LINE_2);
        customer.setCustAddrLine3(SEED_CUST_ADDR_LINE_3);
        customer.setCustAddrStateCd(SEED_CUST_ADDR_STATE_CD);
        customer.setCustAddrCountryCd(SEED_CUST_ADDR_COUNTRY_CD);
        customer.setCustAddrZip(SEED_CUST_ADDR_ZIP);
        customer.setCustPhoneNum1(SEED_CUST_PHONE_1);
        customer.setCustPhoneNum2(SEED_CUST_PHONE_2);
        customer.setCustSsn(SEED_CUST_SSN);
        customer.setCustGovtIssuedId(SEED_CUST_GOVT_ID);
        customer.setCustDobYyyyMmDd(SEED_CUST_DOB);
        customer.setCustEftAccountId(SEED_CUST_EFT_ID);
        customer.setCustPriCardHolderInd(SEED_CUST_PRI_HOLDER_IND);
        customer.setCustFicoCreditScore(SEED_CUST_FICO);
        return customer;
    }

    /**
     * :purpose: Build a fully valid account-update payload that mirrors the fixture in
     *  every field except the account balance, which carries the stale losing value.
     *  Mirroring the customer fields keeps the customer record unchanged, so the only
     *  write the flush attempts is the account balance change against a stale version.
     * :returns: the stale {@link AccountUpdateRequestDto} to submit.
     */
    private AccountUpdateRequestDto buildStaleUpdateRequest() {
        AccountUpdateRequestDto request = new AccountUpdateRequestDto();
        request.setAcctActiveStatus(SEED_ACTIVE_STATUS);
        request.setAcctCurrBal(STALE_BALANCE);
        request.setAcctCreditLimit(SEED_CREDIT_LIMIT);
        request.setAcctCashCreditLimit(SEED_CASH_CREDIT_LIMIT);
        request.setAcctCurrCycCredit(SEED_CYC_CREDIT);
        request.setAcctCurrCycDebit(SEED_CYC_DEBIT);
        request.setAcctOpenDate(SEED_OPEN_DATE);
        request.setAcctExpiraionDate(SEED_EXPIRAION_DATE);
        request.setAcctReissueDate(SEED_REISSUE_DATE);
        request.setAcctGroupId(SEED_GROUP_ID);
        request.setCustFirstName(SEED_CUST_FIRST_NAME);
        request.setCustMiddleName(SEED_CUST_MIDDLE_NAME);
        request.setCustLastName(SEED_CUST_LAST_NAME);
        request.setCustAddrLine1(SEED_CUST_ADDR_LINE_1);
        request.setCustAddrLine2(SEED_CUST_ADDR_LINE_2);
        request.setCustAddrLine3(SEED_CUST_ADDR_LINE_3);
        request.setCustAddrStateCd(SEED_CUST_ADDR_STATE_CD);
        request.setCustAddrCountryCd(SEED_CUST_ADDR_COUNTRY_CD);
        request.setCustAddrZip(SEED_CUST_ADDR_ZIP);
        request.setCustPhoneNum1(SEED_CUST_PHONE_1);
        request.setCustPhoneNum2(SEED_CUST_PHONE_2);
        request.setCustSsn(SEED_CUST_SSN);
        request.setCustGovtIssuedId(SEED_CUST_GOVT_ID);
        request.setCustDobYyyyMmDd(SEED_CUST_DOB);
        request.setCustEftAccountId(SEED_CUST_EFT_ID);
        request.setCustPriCardHolderInd(SEED_CUST_PRI_HOLDER_IND);
        request.setCustFicoCreditScore(SEED_CUST_FICO);
        return request;
    }

    /**
     * :purpose: Advance the persisted fixture account to the next version in a
     *  separate committed transaction, simulating a concurrent winning writer whose
     *  commit lands between the ambient snapshot load and the service flush.
     */
    private void advanceVersionOutOfBand() {
        TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        requiresNew.executeWithoutResult(status -> jdbcTemplate.update(
                "UPDATE accounts SET acct_curr_bal = ?, version = version + 1 WHERE acct_id = ?",
                WINNING_BALANCE, SEED_ACCT_ID));
    }

    /**
     * :purpose: Reload the fixture account in its own fresh transaction so the assertions
     *  observe the latest committed state after the ambient transaction has rolled back.
     * :returns: the freshly loaded {@link Account}.
     */
    private Account reloadAccount() {
        return accountRepository.findById(SEED_ACCT_ID).orElseThrow();
    }

    /**
     * :purpose: MANDATORY scenario (AAP 0.6.2). Drive a stale-version update through the
     *  full ``PUT /accounts/{id}`` controller stack and assert it surfaces as HTTP 409
     *  Conflict carrying the verbatim legacy concurrency message, and that the losing
     *  write does not overwrite the winning writer's committed value.
     */
    @Test
    void staleVersionUpdate_throughController_returns409WithConflictMessage() {
        AccountUpdateRequestDto staleRequest = buildStaleUpdateRequest();
        final String requestJson;
        try {
            requestJson = objectMapper.writeValueAsString(staleRequest);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to serialize update payload", ex);
        }

        int[] capturedStatus = new int[1];
        String[] capturedBody = new String[1];

        TransactionTemplate ambientTx = new TransactionTemplate(transactionManager);
        try {
            ambientTx.executeWithoutResult(status -> {
                // 1. Snapshot the account (version 0) into the ambient persistence context.
                entityManager.find(Account.class, SEED_ACCT_ID);
                // 2. A competing writer commits, advancing the persisted row to version 1.
                advanceVersionOutOfBand();
                // 3. The controller/service join this transaction, re-read the cached stale
                //    entity, and flush the losing update straight into the version conflict.
                MvcResult result;
                try {
                    result = mockMvc.perform(put("/accounts/{id}", SEED_ACCT_ID)
                                    .sessionAttr(SessionContext.SESSION_ATTRIBUTE_NAME, updatingSession())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(requestJson))
                            .andReturn();
                    capturedStatus[0] = result.getResponse().getStatus();
                    capturedBody[0] = result.getResponse().getContentAsString();
                } catch (Exception ex) {
                    throw new IllegalStateException("MockMvc PUT /accounts dispatch failed", ex);
                }
            });
        } catch (UnexpectedRollbackException expected) {
            // The service marked the joined transaction rollback-only while mapping the
            // optimistic-lock failure to 409, so the ambient commit rolls back as designed.
        }

        assertThat(capturedStatus[0]).isEqualTo(409);

        final ErrorResponse error;
        try {
            error = objectMapper.readValue(capturedBody[0], ErrorResponse.class);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to parse 409 error body: " + capturedBody[0], ex);
        }
        assertThat(error.getStatus()).isEqualTo(409);
        assertThat(error.getMessage()).isEqualTo(OptimisticLockConflictException.MESSAGE);
        assertThat(error.getMessage()).isEqualTo("Record changed by some one else. Please review");
        assertThat(error.getError()).isEqualTo("Conflict");
        assertThat(error.getPath()).endsWith("/accounts/" + SEED_ACCT_ID);

        // The winning writer's value survived; the stale losing update never persisted.
        Account persisted = reloadAccount();
        assertThat(persisted.getAcctCurrBal()).isEqualByComparingTo(WINNING_BALANCE);
        assertThat(persisted.getAcctCurrBal()).isNotEqualByComparingTo(STALE_BALANCE);
        assertThat(persisted.getVersion()).isEqualTo(1L);
    }

    /**
     * :purpose: The signed-on session context a real caller presents with the update, the
     *  externalized COMMAREA that carries ``CDEMO-USER-ID`` and ``CDEMO-USER-TYPE`` and from
     *  which the request's principal and ``ROLE_USER`` authority are rebuilt.
     * :returns: a regular-user session context for user ``TESTUSR1``.
     */
    private SessionContext updatingSession() {
        SessionContext session = new SessionContext();
        session.setUserId("TESTUSR1");
        session.setUserType(SessionContext.UserType.CDEMO_USRTYP_USER);
        return session;
    }

    /**
     * :purpose: Service-level scenario. Drive ``AccountService.updateAccount`` directly
     *  with a stale snapshot and assert it throws {@link OptimisticLockConflictException}
     *  (caused by {@link ObjectOptimisticLockingFailureException}) bearing the verbatim
     *  legacy message, leaving the winning writer's value in place.
     */
    @Test
    void staleVersionUpdate_throughService_throwsOptimisticLockConflictException() {
        AccountUpdateRequestDto staleRequest = buildStaleUpdateRequest();
        SessionContext session = new SessionContext();
        session.setUserId("TESTUSR1");
        session.setUserType(SessionContext.UserType.CDEMO_USRTYP_USER);

        TransactionTemplate ambientTx = new TransactionTemplate(transactionManager);
        assertThatExceptionOfType(OptimisticLockConflictException.class)
                .isThrownBy(() -> ambientTx.executeWithoutResult(status -> {
                    // Snapshot version 0, let a competing writer commit version 1, then flush
                    // the stale update through the service to trigger the optimistic-lock failure.
                    entityManager.find(Account.class, SEED_ACCT_ID);
                    advanceVersionOutOfBand();
                    accountService.updateAccount(SEED_ACCT_ID, staleRequest, session);
                }))
                .withMessage(OptimisticLockConflictException.MESSAGE)
                .withCauseInstanceOf(ObjectOptimisticLockingFailureException.class);

        // The failed stale attempt left the winning writer's committed value untouched.
        Account persisted = reloadAccount();
        assertThat(persisted.getAcctCurrBal()).isEqualByComparingTo(WINNING_BALANCE);
        assertThat(persisted.getAcctCurrBal()).isNotEqualByComparingTo(STALE_BALANCE);
        assertThat(persisted.getVersion()).isEqualTo(1L);
    }
}
