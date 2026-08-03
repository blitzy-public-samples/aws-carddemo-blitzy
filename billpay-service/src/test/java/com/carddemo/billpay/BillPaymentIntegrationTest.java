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
package com.carddemo.billpay;

import com.carddemo.billpay.repository.AccountRepository;
import com.carddemo.billpay.repository.CardXrefRepository;
import com.carddemo.billpay.repository.TransactionRepository;
import com.carddemo.billpay.service.BillPaymentService;
import com.carddemo.common.domain.Account;
import com.carddemo.common.testsupport.MigratedSchemaContainer;
import com.carddemo.common.domain.Transaction;
import com.carddemo.common.dto.BillPaymentRequestDto;
import com.carddemo.common.dto.BillPaymentResponseDto;
import com.carddemo.common.dto.SessionContext;
import com.carddemo.common.exception.CardDemoException;
import com.carddemo.common.exception.OptimisticLockConflictException;
import com.carddemo.common.exception.RecordNotFoundException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * :purpose: Testcontainers PostgreSQL integration test for the ``billpay-service``
 *  migration of CICS program ``COBIL00C`` (transaction ``CB00``). Boots the full
 *  ``BillPaymentServiceApplication`` context against a real ``postgres:18`` database
 *  and verifies the behaviours a Mockito unit test cannot: full-context wiring, real
 *  atomic persistence of the payment ``Transaction`` alongside the zeroed account
 *  balance, and the database-sequence-driven 16-digit zero-padded transaction id.
 * :note: ``billpay-service`` is online-only — no batch, no security module and no
 *  Redis at test time (the ``test`` profile excludes the Redis and Spring Session
 *  auto-configuration). The service owns no tables and ships no migrations, so this
 *  test self-provisions the schema through Hibernate ``create-drop`` over the shared
 *  ``carddemo-common`` entities and explicitly creates the ``transaction_id_seq``
 *  sequence that ``TransactionRepository.getNextTransactionId()`` draws from.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
public class BillPaymentIntegrationTest {

    /*
     * The PII encryption key CryptoConverter fails fast without is supplied by this
     * module's surefire AND failsafe <systemPropertyVariables> configuration, so it is
     * present for every test in the module regardless of execution order or of which
     * class runs first. It is deliberately NOT set from a static initializer here: a
     * test class that installs a process-wide system property makes every other class
     * in the fork depend on it having run, which is precisely the isolation defect the
     * build configuration exists to avoid.
     */

    /** Sequence backing ``TransactionRepository.getNextTransactionId()`` (native query). */
    /**
     * :purpose: Idempotent guard for ``transaction_id_seq``. The sequence is created by the
     *  transaction-service migration ``V4__create_transaction_id_sequence.sql``; the
     *  ``IF NOT EXISTS`` form keeps the fixture reset self-contained without redefining it.
     */
    private static final String TRANSACTION_ID_SEQ =
            "CREATE SEQUENCE IF NOT EXISTS transaction_id_seq AS BIGINT START WITH 1 INCREMENT BY 1";

    // --- Verbatim COBIL00C user-facing messages (byte-for-byte; see app/cbl/COBIL00C.cbl). ---

    /** ``COBIL00C`` account / cross-reference not-found message (shared text, L361/L425). */
    private static final String MSG_ACCOUNT_NOT_FOUND = "Account ID NOT found...";

    /** ``COBIL00C`` zero-or-negative balance message (L201). */
    private static final String MSG_NOTHING_TO_PAY = "You have nothing to pay...";

    /** ``COBIL00C`` two-space success-banner prefix (L527-530); note the double space. */
    private static final String SUCCESS_MESSAGE_PREFIX = "Payment successful.  Your Transaction ID is ";

    // --- Frozen bill-payment transaction field values (COBIL00C L218-232 / BillPaymentMapper). ---

    private static final String FROZEN_TRAN_TYPE_CD = "02";
    private static final Integer FROZEN_TRAN_CAT_CD = 2;
    private static final String FROZEN_TRAN_SOURCE = "POS TERM";
    private static final String FROZEN_TRAN_DESC = "BILL PAYMENT - ONLINE";
    private static final Long FROZEN_MERCHANT_ID = 999999999L;
    private static final String FROZEN_MERCHANT_NAME = "BILL PAYMENT";
    private static final String FROZEN_MERCHANT_CITY = "N/A";
    private static final String FROZEN_MERCHANT_ZIP = "N/A";

    /** Wire-format shape of the 26-character CSDAT01Y timestamp: ``yyyy-MM-dd HH:mm:ss.000000``. */
    private static final String TIMESTAMP_REGEX =
            "^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.000000$";

    /** 16-digit zero-padded transaction-id wire form (AAP 0.6.5). */
    private static final String TRAN_ID_REGEX = "^\\d{16}$";

    // --- Deterministic fixture identifiers. ---

    // ACCT-ID is PIC 9(11), and chk_accounts_acct_id enforces that width, so a fixture id
    // must stay within eleven digits; 9xxxxxxxxxx keeps it clear of the 1..50 seed range.
    private static final long ACCT_ID_1 = 90000000001L;
    private static final long ACCT_ID_2 = 90000000002L;
    private static final long CUST_ID_1 = 100000001L;
    private static final long CUST_ID_2 = 100000002L;
    private static final String CARD_NUM_1 = "4111111111111111";
    private static final String CARD_NUM_2 = "4222222222222222";
    private static final BigDecimal PAYABLE_BALANCE = new BigDecimal("100.00");
    private static final BigDecimal ZERO_BALANCE = new BigDecimal("0.00");

    /**
     * :purpose: Bind the Spring datasource to the shared, already-migrated ``postgres:18``
     *  container from :java:class:`com.carddemo.common.testsupport.MigratedSchemaContainer`,
     *  whose schema - ``accounts``, ``card_xref``, ``transactions``, the transaction-id
     *  sequence and every other scanned entity's table - is produced exclusively by the
     *  owning modules' committed Flyway migrations. Hibernate is set to ``validate``, never
     *  ``create-drop``: the mapping is asserted against the deployed schema. Overrides the
     *  ``jdbc:tc:`` URL and Testcontainers driver declared in ``application-test.yml``.
     * :param registry: the Spring dynamic-property registry to populate.
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        MigratedSchemaContainer.registerDataSource(registry);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    private BillPaymentService billPaymentService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CardXrefRepository cardXrefRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * :purpose: Reset the shared database to a clean, known baseline before each test:
     *  ensure the transaction-id sequence exists and truncate the fixture tables in
     *  foreign-key-safe order (``card_xref`` references both ``accounts`` and
     *  ``customers``).
     */
    @BeforeEach
    void resetSchema() {
        resetDatabase(jdbcTemplate);
    }

    /**
     * :purpose: Ensure ``transaction_id_seq`` exists and empty the fixture tables in
     *  foreign-key-safe order. Extracted as a static helper so the nested,
     *  separately-wired context can reuse it with its own ``JdbcTemplate``.
     * :param jdbc: the JDBC template bound to the shared container datasource.
     */
    static void resetDatabase(JdbcTemplate jdbc) {
        jdbc.execute(TRANSACTION_ID_SEQ);
        jdbc.execute("DELETE FROM card_xref");
        jdbc.execute("DELETE FROM transactions");
        // The migration seed carries 50 cards that foreign-key into accounts, so they are
        // removed before their parents; these tests own the content of the fixture tables.
        jdbc.execute("DELETE FROM cards");
        // tran_cat_bal foreign-keys into accounts (fk_tran_cat_bal_acct), so the seeded
        // category balances go before their parent accounts.
        jdbc.execute("DELETE FROM tran_cat_bal");
        jdbc.execute("DELETE FROM accounts");
        jdbc.execute("DELETE FROM customers");
    }

    /**
     * :purpose: Seed a fully payable fixture — a customer, an account carrying the
     *  supplied positive balance, and the card cross-reference linking them — so the
     *  end-to-end bill-payment flow (account read, cross-reference lookup, transaction
     *  insert, balance rewrite) can execute against real rows.
     * :param jdbc: the JDBC template bound to the shared container datasource.
     * :param acctId: the account identifier to seed.
     * :param custId: the owning customer identifier to seed.
     * :param cardNum: the 16-character card number for the cross-reference.
     * :param balance: the positive current balance to pay in full.
     */
    static void seedPayableAccount(JdbcTemplate jdbc, long acctId, long custId,
                                   String cardNum, BigDecimal balance) {
        seedCustomer(jdbc, custId);
        seedAccount(jdbc, acctId, balance);
        seedCardXref(jdbc, cardNum, custId, acctId);
    }

    /**
     * :purpose: Insert a minimal customer row satisfying the NOT-NULL contract of the
     *  ``customers`` table; the PII columns are left null so no encryption key is needed.
     * :param jdbc: the JDBC template bound to the shared container datasource.
     * :param custId: the customer identifier to seed.
     */
    static void seedCustomer(JdbcTemplate jdbc, long custId) {
        jdbc.update(
                "INSERT INTO customers (cust_id, cust_first_name, cust_middle_name, "
                        + "cust_last_name, cust_addr_line_1, cust_addr_line_2, cust_addr_line_3, "
                        + "cust_addr_state_cd, cust_addr_country_cd, cust_addr_zip, "
                        + "cust_phone_num_1, cust_phone_num_2, cust_ssn, cust_govt_issued_id, "
                        + "cust_dob_yyyy_mm_dd, cust_eft_account_id, cust_pri_card_holder_ind, "
                        + "cust_fico_credit_score, version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                custId, "Test", "T", "Customer", "Addr line 1", "Addr line 2", "Addr line 3",
                "NC", "USA", "00000", "(000)000-0000", "(000)000-0000", "", "", "1970-01-01",
                "", "Y", 700, 0L);
    }

    /**
     * :purpose: Insert an active account row with the supplied balance, populating every
     *  NOT-NULL column and an initial optimistic-locking ``version`` of zero so the JPA
     *  ``@Version`` update path behaves as in production.
     * :param jdbc: the JDBC template bound to the shared container datasource.
     * :param acctId: the account identifier to seed.
     * :param balance: the current balance (scale 2) to store.
     */
    static void seedAccount(JdbcTemplate jdbc, long acctId, BigDecimal balance) {
        jdbc.update(
                "INSERT INTO accounts (acct_id, acct_active_status, acct_curr_bal, "
                        + "acct_credit_limit, acct_cash_credit_limit, acct_open_date, "
                        + "acct_expiraion_date, acct_reissue_date, acct_curr_cyc_credit, "
                        + "acct_curr_cyc_debit, acct_addr_zip, version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                acctId, "Y", balance, new BigDecimal("5000.00"), new BigDecimal("5000.00"),
                // The legacy copybook misspelling ACCT-EXPIRAION-DATE is preserved verbatim.
                "2020-01-01", "2099-12-31", "2020-01-01", new BigDecimal("0.00"),
                new BigDecimal("0.00"), "00000", 0L);
    }

    /**
     * :purpose: Insert the card cross-reference linking a card number to its owning
     *  customer and account (the ``CXACAIX`` alternate index resolved by
     *  ``CardXrefRepository.findByXrefAcctId``). Requires the customer and account rows
     *  to exist first to satisfy the foreign-key constraints.
     * :param jdbc: the JDBC template bound to the shared container datasource.
     * :param cardNum: the 16-character card number (primary key).
     * :param custId: the owning customer identifier.
     * :param acctId: the owning account identifier.
     */
    static void seedCardXref(JdbcTemplate jdbc, String cardNum, long custId, long acctId) {
        jdbc.update(
                "INSERT INTO card_xref (xref_card_num, xref_cust_id, xref_acct_id) "
                        + "VALUES (?, ?, ?)",
                cardNum, custId, acctId);
    }

    /**
     * :purpose: Build a confirmed (``Y``) bill-payment request for the supplied account.
     * :param acctId: the account identifier to pay.
     * :returns: a request DTO whose account id is the zero-safe string form of ``acctId``.
     */
    static BillPaymentRequestDto confirmedRequest(long acctId) {
        return new BillPaymentRequestDto(Long.toString(acctId), "Y");
    }

    /**
     * :purpose: Build a minimal authenticated session context; the service reads the
     *  account id from the request DTO, so only a representative user id is set.
     * :returns: a populated {@link SessionContext}.
     */
    static SessionContext session() {
        SessionContext context = new SessionContext();
        context.setUserId("TESTUSER");
        return context;
    }

    /**
     * :purpose: Prove the full application context boots against the container with the
     *  ``test`` profile (Redis/session excluded) by asserting the service under test and
     *  its collaborators are wired.
     */
    @Test
    @DisplayName("full application context boots against postgres:18 with the test profile")
    void contextLoads() {
        assertThat(billPaymentService).isNotNull();
        assertThat(accountRepository).isNotNull();
        assertThat(cardXrefRepository).isNotNull();
        assertThat(transactionRepository).isNotNull();
    }

    /**
     * :purpose: Happy path against the real database. A confirmed payment must persist a
     *  bill-payment transaction carrying the frozen COBIL00C field values, zero the
     *  account balance in the same unit of work, and return the two-space success banner
     *  with a 16-digit transaction id.
     */
    @Test
    @DisplayName("confirmed payment persists the transaction and zeroes the balance")
    void confirmedPaymentPersistsTransactionAndZeroesBalance() {
        seedPayableAccount(jdbcTemplate, ACCT_ID_1, CUST_ID_1, CARD_NUM_1, PAYABLE_BALANCE);

        BillPaymentResponseDto response =
                billPaymentService.processBillPayment(confirmedRequest(ACCT_ID_1), session());

        // Response contract: zeroed balance (scale 2), 16-digit id, verbatim two-space message.
        assertThat(response).isNotNull();
        assertThat(response.getAccountId()).isEqualTo(Long.toString(ACCT_ID_1));
        assertThat(response.getCurrentBalance()).isEqualByComparingTo(ZERO_BALANCE);
        assertThat(response.getCurrentBalance().scale()).isEqualTo(2);
        assertThat(response.getTransactionId()).matches(TRAN_ID_REGEX);
        assertThat(response.getMessage())
                .isEqualTo(SUCCESS_MESSAGE_PREFIX + response.getTransactionId() + ".");

        // Persisted transaction carries the frozen bill-payment field values verbatim.
        Transaction persisted = transactionRepository.findById(response.getTransactionId())
                .orElseThrow(() -> new AssertionError("bill-payment transaction was not persisted"));
        assertThat(persisted.getTranTypeCd()).isEqualTo(FROZEN_TRAN_TYPE_CD);
        assertThat(persisted.getTranCatCd()).isEqualTo(FROZEN_TRAN_CAT_CD);
        assertThat(persisted.getTranSource()).isEqualTo(FROZEN_TRAN_SOURCE);
        assertThat(persisted.getTranDesc()).isEqualTo(FROZEN_TRAN_DESC);
        assertThat(persisted.getTranMerchantId()).isEqualTo(FROZEN_MERCHANT_ID);
        assertThat(persisted.getTranMerchantName()).isEqualTo(FROZEN_MERCHANT_NAME);
        assertThat(persisted.getTranMerchantCity()).isEqualTo(FROZEN_MERCHANT_CITY);
        assertThat(persisted.getTranMerchantZip()).isEqualTo(FROZEN_MERCHANT_ZIP);
        assertThat(persisted.getTranCardNum()).isEqualTo(CARD_NUM_1);
        assertThat(persisted.getTranAmt()).isEqualByComparingTo(PAYABLE_BALANCE);
        assertThat(persisted.getTranAmt().scale()).isEqualTo(2);

        // Origination and processing timestamps share the identical 26-character wire form.
        assertThat(persisted.getTranOrigTs()).hasSize(26).matches(TIMESTAMP_REGEX);
        assertThat(persisted.getTranProcTs()).isEqualTo(persisted.getTranOrigTs());

        // Balance netted to zero in the persisted account row (COBIL00C L234, pay-in-full).
        Account reloaded = accountRepository.findById(ACCT_ID_1)
                .orElseThrow(() -> new AssertionError("account row disappeared after payment"));
        assertThat(reloaded.getAcctCurrBal()).isEqualByComparingTo(ZERO_BALANCE);
        assertThat(reloaded.getAcctCurrBal().scale()).isEqualTo(2);
    }

    /**
     * :purpose: Prove the transaction id is drawn from the database sequence
     *  ``transaction_id_seq`` (AAP 0.6.5) rather than the legacy browse-last-then-increment:
     *  two successive confirmed payments must yield distinct, sequential 16-digit ids.
     */
    @Test
    @DisplayName("successive payments draw distinct, sequential 16-digit transaction ids")
    void successivePaymentsDrawDistinctSequentialTransactionIds() {
        seedPayableAccount(jdbcTemplate, ACCT_ID_1, CUST_ID_1, CARD_NUM_1, PAYABLE_BALANCE);
        seedPayableAccount(jdbcTemplate, ACCT_ID_2, CUST_ID_2, CARD_NUM_2, PAYABLE_BALANCE);

        String firstId = billPaymentService
                .processBillPayment(confirmedRequest(ACCT_ID_1), session()).getTransactionId();
        String secondId = billPaymentService
                .processBillPayment(confirmedRequest(ACCT_ID_2), session()).getTransactionId();

        assertThat(firstId).matches(TRAN_ID_REGEX);
        assertThat(secondId).matches(TRAN_ID_REGEX);
        assertThat(secondId).isNotEqualTo(firstId);
        assertThat(Long.parseLong(secondId)).isEqualTo(Long.parseLong(firstId) + 1L);
    }

    /**
     * :purpose: An unknown account id must surface the verbatim ``COBIL00C`` not-found
     *  outcome as a {@link RecordNotFoundException} (HTTP 404) against the real, empty
     *  ``accounts`` table.
     */
    @Test
    @DisplayName("unknown account id throws RecordNotFoundException with the verbatim message")
    void accountNotFoundThrowsRecordNotFound() {
        BillPaymentRequestDto request = new BillPaymentRequestDto("999999999999", "Y");

        assertThatThrownBy(() -> billPaymentService.processBillPayment(request, session()))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage(MSG_ACCOUNT_NOT_FOUND);

        assertThat(transactionRepository.count()).isZero();
    }

    /**
     * :purpose: An account whose balance is not positive must surface the verbatim
     *  ``COBIL00C`` "nothing to pay" outcome as a {@link CardDemoException} (HTTP 400) and
     *  must not record any transaction.
     */
    @Test
    @DisplayName("non-positive balance throws CardDemoException and records nothing")
    void nothingToPayWhenBalanceNotPositive() {
        seedAccount(jdbcTemplate, ACCT_ID_1, ZERO_BALANCE);

        assertThatThrownBy(() ->
                billPaymentService.processBillPayment(confirmedRequest(ACCT_ID_1), session()))
                .isInstanceOf(CardDemoException.class)
                .hasMessage(MSG_NOTHING_TO_PAY);

        assertThat(transactionRepository.count()).isZero();
    }

    /**
     * :purpose: Atomicity and optimistic-lock scenarios that require the account update
     *  to fail deterministically. The ``AccountRepository`` is replaced by a Mockito bean
     *  (Spring Boot 4.x ``@MockitoBean``) whose ``findById`` returns an in-memory account
     *  but whose ``save`` throws, while the ``TransactionRepository`` and the card
     *  cross-reference remain real against the shared container. This nested context is
     *  the only place the repository is mocked, keeping the failure path deterministic
     *  without perturbing the happy-path tests above.
     */
    @Nested
    @DisplayName("atomicity and optimistic-lock scenarios (mocked AccountRepository)")
    class AtomicityAndOptimisticLockScenarios {

        @MockitoBean
        private AccountRepository accountRepository;

        @Autowired
        private BillPaymentService billPaymentService;

        @Autowired
        private TransactionRepository transactionRepository;

        @Autowired
        private JdbcTemplate jdbcTemplate;

        /**
         * :purpose: Establish a clean baseline and seed the real customer, account and card
         *  cross-reference rows the service's cross-reference lookup and transaction insert
         *  depend on. The account row exists purely to satisfy the ``card_xref`` foreign key;
         *  the mocked repository supplies the account the service actually reads.
         */
        @BeforeEach
        void seedForMockScenarios() {
            resetDatabase(jdbcTemplate);
            seedPayableAccount(jdbcTemplate, ACCT_ID_1, CUST_ID_1, CARD_NUM_1, PAYABLE_BALANCE);
        }

        /**
         * :purpose: Build the in-memory account the mocked repository returns for the
         *  payment under test, carrying a positive balance so the flow proceeds to the
         *  transaction insert and the failing account update.
         * :returns: a transient {@link Account} with the fixture id and payable balance.
         */
        private Account payableAccount() {
            Account account = new Account();
            account.setAcctId(ACCT_ID_1);
            account.setAcctActiveStatus("Y");
            account.setAcctCurrBal(PAYABLE_BALANCE);
            return account;
        }

        /**
         * :purpose: Atomicity (single ``@Transactional`` unit, AAP 0.3.4 / 0.6.2 / 0.7.6).
         *  When the account rewrite fails after the transaction has been inserted, the whole
         *  unit of work must roll back so no orphaned transaction row survives.
         */
        @Test
        @DisplayName("failed account update rolls back the transaction insert")
        void failedAccountUpdateRollsBackTransactionInsert() {
            when(accountRepository.findById(ACCT_ID_1)).thenReturn(Optional.of(payableAccount()));
            when(accountRepository.save(any(Account.class)))
                    .thenThrow(new RuntimeException("forced account update failure"));

            assertThatThrownBy(() ->
                    billPaymentService.processBillPayment(confirmedRequest(ACCT_ID_1), session()))
                    .isInstanceOf(RuntimeException.class);

            // The real transaction insert must have been rolled back within the single unit.
            assertThat(transactionRepository.count()).isZero();
        }

        /**
         * :purpose: Optimistic locking (AAP 0.6.2). A concurrent-modification failure on the
         *  account rewrite must surface as {@link OptimisticLockConflictException} (which the
         *  global handler maps to HTTP 409) carrying the byte-frozen legacy conflict message,
         *  and must leave no transaction row behind.
         */
        @Test
        @DisplayName("concurrent modification surfaces OptimisticLockConflictException (409)")
        void concurrentModificationSurfacesOptimisticLockConflict() {
            when(accountRepository.findById(ACCT_ID_1)).thenReturn(Optional.of(payableAccount()));
            when(accountRepository.save(any(Account.class)))
                    .thenThrow(new ObjectOptimisticLockingFailureException(Account.class, ACCT_ID_1));

            assertThatThrownBy(() ->
                    billPaymentService.processBillPayment(confirmedRequest(ACCT_ID_1), session()))
                    .isInstanceOf(OptimisticLockConflictException.class)
                    .hasMessage(OptimisticLockConflictException.MESSAGE);

            assertThat(transactionRepository.count()).isZero();
        }
    }
}
