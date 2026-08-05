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
package com.carddemo.batch;

import com.carddemo.batch.repository.AccountRepository;
import com.carddemo.batch.repository.CardXrefRepository;
import com.carddemo.batch.repository.DiscGroupRepository;
import com.carddemo.batch.repository.TranCatBalRepository;
import com.carddemo.batch.repository.TransactionRepository;
import com.carddemo.common.domain.Account;
import com.carddemo.common.domain.CardXref;
import com.carddemo.common.domain.DiscGroup;
import com.carddemo.common.domain.TranCatBal;
import com.carddemo.common.testsupport.MigratedSchemaContainer;
import com.carddemo.common.domain.Transaction;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test for the monthly interest-calculation job.
 *
 * :purpose: Boot the full ``batch-service`` Spring context against a real PostgreSQL
 *     Testcontainer, seed business data, launch ``interestCalculationJob`` synchronously
 *     and deterministically, and assert the produced {@link Transaction} rows and the
 *     rolled-up {@link Account} balances at exact financial precision. The behavioral
 *     source of truth is the legacy batch program ``app/cbl/CBACT04C.cbl`` (driven by
 *     ``app/jcl/INTCALC.jcl`` ``PARM='2022071800'``): monthly interest is
 *     ``(TRAN-CAT-BAL * DIS-INT-RATE) / 1200`` at scale 2 TRUNCATED toward zero (the
 *     ``COMPUTE`` carries no ``ROUNDED`` phrase), each
 *     non-zero-rate category posts one interest transaction, and every account has its
 *     current-cycle credit and debit figures zeroed on the control break.
 * :output: Two verified scenarios — a non-zero-rate account that posts one interest
 *     transaction and rolls its balance forward, and a zero-rate account that posts none
 *     yet still has its cycle figures zeroed — with all money compared via
 *     ``isEqualByComparingTo``.
 * :note: The job is launched through a dedicated synchronous {@link TaskExecutorJobLauncher}
 *     (a {@link SyncTaskExecutor} over the context {@link JobRepository}) so the launch
 *     blocks until the job finishes; the asynchronous ``asyncJobLauncher`` bean is never
 *     used. Schema provisioning inherits the production ``application.yml`` settings unchanged —
 *     batch-service is the single Flyway migration owner, so its own configuration applies the
 *     shared migration set from ``carddemo-common`` (``classpath:db/migration``) and builds the
 *     complete schema: business tables, reference data, fixtures and the Spring Batch metadata,
 *     validated by Hibernate ``ddl-auto=validate``. Nothing about the schema configuration is
 *     overridden, so the test runs exactly what production runs. Each scenario starts from an
 *     empty business state: the seeded fixtures are cleared child-before-parent in
 *     ``@BeforeEach``, and a parent ``customers`` row is seeded per account because
 *     {@link CardXref} declares a real foreign key to ``customers``.
 */
@SpringBootTest(classes = BatchServiceApplication.class)
@DisplayName("InterestCalculationJob (CBACT04C) Testcontainers integration test")
class InterestCalculationJobIT {

    /**
     * ``INSERT`` statement seeding the parent ``customers`` row required by the card
     * cross-reference foreign key. It populates every ``NOT NULL`` column of the migrated
     * ``customers`` table (``CVCUS01Y``): the identifier, the name, address, phone, PII, date of
     * birth and card-holder fields, the FICO score, and the ``@Version`` optimistic-locking
     * column. The three PII columns are seeded EMPTY, which the at-rest ``CryptoConverter``
     * passes through unchanged, so the row is readable without depending on an encryption key.
     */
    private static final String INSERT_CUSTOMER_SQL =
            "INSERT INTO customers (cust_id, cust_first_name, cust_middle_name, cust_last_name, "
                    + "cust_addr_line_1, cust_addr_line_2, cust_addr_line_3, cust_addr_state_cd, "
                    + "cust_addr_country_cd, cust_addr_zip, cust_phone_num_1, cust_phone_num_2, "
                    + "cust_ssn, cust_govt_issued_id, cust_dob_yyyy_mm_dd, cust_eft_account_id, "
                    + "cust_pri_card_holder_ind, cust_fico_credit_score, version) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    /**
     * :purpose: Point the JPA datasource at the shared, already-migrated ``postgres:18``
     *     container from
     *     :java:class:`com.carddemo.common.testsupport.MigratedSchemaContainer`, whose schema -
     *     the batch metadata and ``disclosure_group`` this service owns plus every table owned
     *     by the other services - is produced exclusively by the committed Flyway migrations.
     *     ``ddl-auto`` is set to ``validate`` (never ``create``/``update``): the test therefore
     *     asserts that the entities match the migrated schema instead of materializing tables
     *     from the entities and hiding a missing migration.
     * :param registry: registry the test framework resolves datasource and schema properties
     *     from before the application context starts.
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        MigratedSchemaContainer.registerDataSource(registry);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    /** Interest-calculation job under test; qualified because nine ``Job`` beans exist in the context. */
    @Autowired
    @Qualifier("interestCalculationJob")
    private Job interestCalculationJob;

    /** Context batch job repository backing the test's synchronous launcher. */
    @Autowired
    private JobRepository jobRepository;

    /** Raw JDBC access used to seed and clear the tables this test reaches without a repository. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Account repository for seeding and reloading the rolled-up account. */
    @Autowired
    private AccountRepository accountRepository;

    /** Card cross-reference repository for seeding the account-to-card linkage. */
    @Autowired
    private CardXrefRepository cardXrefRepository;

    /** Disclosure-group repository for seeding the interest rate. */
    @Autowired
    private DiscGroupRepository discGroupRepository;

    /** Transaction-category-balance repository supplying the job's driving read. */
    @Autowired
    private TranCatBalRepository tranCatBalRepository;

    /** Transaction repository for verifying the posted interest transactions. */
    @Autowired
    private TransactionRepository transactionRepository;

    /** Used to resolve the step-scoped reader inside two distinct step scopes. */
    @Autowired
    private ApplicationContext applicationContext;

    /**
     * :purpose: Reset all business state before each scenario so row counts and balances are
     *     exact, clearing the migration-seeded fixtures as well as anything a previous scenario
     *     wrote. Tables are cleared child-before-parent to respect the foreign keys of
     *     {@link CardXref} (to ``accounts`` and ``customers``), ``cards`` (to ``accounts``) and
     *     ``tran_cat_bal`` (to ``accounts``); batch metadata is intentionally left in place
     *     because each launch uses a unique ``run.id``.
     */
    @BeforeEach
    void resetBusinessState() {
        transactionRepository.deleteAll();
        jdbcTemplate.update("DELETE FROM daily_transactions");
        tranCatBalRepository.deleteAll();
        cardXrefRepository.deleteAll();
        // The migration seed carries 50 cards that foreign-key into accounts, so they are
        // removed before their parents; this job consumes whole tables, so the test owns
        // their content.
        jdbcTemplate.update("DELETE FROM cards");
        discGroupRepository.deleteAll();
        accountRepository.deleteAll();
        jdbcTemplate.update("DELETE FROM customers");
    }

    /**
     * :purpose: Verify the happy path — a category balance with a non-zero disclosure rate
     *     posts exactly one interest transaction whose fields match ``CBACT04C``'s
     *     ``1300-B-WRITE-TX`` assembly, and the owning account is rolled up (interest added to
     *     the current balance, current-cycle figures zeroed). Interest is
     *     ``1000.00 * 12.00 / 1200 = 10.00``.
     */
    @Test
    @DisplayName("posts one interest transaction and rolls the account balance forward with cycles zeroed")
    void postsInterestTransactionAndRollsUpAccount() throws Exception {
        seedCustomer(100000001L);
        seedAccount(1L, "GRP0000001", "100.00", "500.00", "200.00");
        seedCard("1234567890123456", 1L);
        cardXrefRepository.save(new CardXref("1234567890123456", 100000001L, 1L));
        discGroupRepository.save(new DiscGroup("GRP0000001", "01", 5, new BigDecimal("12.00")));
        tranCatBalRepository.save(new TranCatBal(1L, "01", 5, new BigDecimal("1000.00")));

        JobExecution execution = launchInterestJob("2022071800");

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode()).isEqualTo("COMPLETED");

        List<Transaction> transactions = transactionRepository.findAll();
        assertThat(transactions).hasSize(1);

        Transaction interest = transactions.get(0);
        assertThat(interest.getTranAmt()).isEqualByComparingTo("10.00");
        assertThat(interest.getTranTypeCd()).isEqualTo("01");
        assertThat(interest.getTranCatCd()).isEqualTo(5);
        assertThat(interest.getTranSource()).isEqualTo("System");
        assertThat(interest.getTranDesc()).isEqualTo("Int. for a/c 00000000001");
        assertThat(interest.getTranCardNum()).isEqualTo("1234567890123456");
        assertThat(interest.getTranId()).hasSize(16);
        assertThat(interest.getTranId()).startsWith("2022071800");
        // Z-GET-DB2-FORMAT-TIMESTAMP writes DB2-FORMAT-TS as YYYY-MM-DD-HH.MM.SS.mmmmmm: a
        // length check alone would also accept the 'YYYY-MM-DD HH:MM:SS.mmmmmm' space/colon
        // form, so the exact dash/dot layout is pinned here as well as in
        // InterestCalculationServiceTest, for BOTH timestamps written by the job.
        assertThat(interest.getTranOrigTs()).hasSize(26);
        assertThat(interest.getTranOrigTs())
                .matches("^\\d{4}-\\d{2}-\\d{2}-\\d{2}\\.\\d{2}\\.\\d{2}\\.\\d{6}$");
        assertThat(interest.getTranOrigTs()).endsWith("0000");
        assertThat(interest.getTranProcTs()).hasSize(26);
        assertThat(interest.getTranProcTs())
                .matches("^\\d{4}-\\d{2}-\\d{2}-\\d{2}\\.\\d{2}\\.\\d{2}\\.\\d{6}$");
        assertThat(interest.getTranOrigTs()).isEqualTo(interest.getTranProcTs());

        Account rolledUp = accountRepository.findById(1L).orElseThrow();
        assertThat(rolledUp.getAcctCurrBal()).isEqualByComparingTo("110.00");
        assertThat(rolledUp.getAcctCurrCycCredit()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(rolledUp.getAcctCurrCycDebit()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /**
     * :purpose: Verify the zero-rate path — a category balance whose disclosure rate is zero
     *     posts no interest transaction (the processor's ``compareTo``-against-zero guard),
     *     yet the owning account is still rolled up so its current-cycle figures are zeroed and
     *     its balance is unchanged (``250.00 + 0.00 = 250.00``), reproducing ``CBACT04C``'s
     *     unconditional ``1050-UPDATE-ACCOUNT``.
     */
    @Test
    @DisplayName("zero interest rate posts no transaction yet still zeroes the account cycle figures")
    void zeroRatePostsNoTransactionButZeroesCycles() throws Exception {
        seedCustomer(100000002L);
        seedAccount(2L, "GRP0000002", "250.00", "300.00", "100.00");
        seedCard("6543210987654321", 2L);
        cardXrefRepository.save(new CardXref("6543210987654321", 100000002L, 2L));
        discGroupRepository.save(new DiscGroup("GRP0000002", "01", 5, new BigDecimal("0.00")));
        tranCatBalRepository.save(new TranCatBal(2L, "01", 5, new BigDecimal("2000.00")));

        JobExecution execution = launchInterestJob("2022071801");

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(transactionRepository.count()).isZero();

        Account rolledUp = accountRepository.findById(2L).orElseThrow();
        assertThat(rolledUp.getAcctCurrBal()).isEqualByComparingTo("250.00");
        assertThat(rolledUp.getAcctCurrCycCredit()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(rolledUp.getAcctCurrCycDebit()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /**
     * :purpose: Launch ``interestCalculationJob`` synchronously and block until it finishes.
     * :param parmDate: the required 10-character ``YYYYMMDDHH`` business date job parameter
     *     (legacy ``INTCALC.jcl`` ``PARM``); it forms the leading portion of each generated
     *     ``TRAN-ID``. A unique ``run.id`` is added so every launch starts a fresh
     *     ``JobInstance``.
     * :returns: the completed {@link JobExecution}.
     */
    private JobExecution launchInterestJob(String parmDate) throws Exception {
        JobParameters parameters = new JobParametersBuilder()
                .addString("parmDate", parmDate)
                .addLong("run.id", System.nanoTime())
                .toJobParameters();
        return synchronousJobLauncher().run(interestCalculationJob, parameters);
    }

    /**
     * :purpose: Build a synchronous {@link JobLauncher} over the context
     *     {@link JobRepository}, backed by a {@link SyncTaskExecutor} so a launched job runs
     *     on the calling thread and the launch call blocks until completion. This keeps the
     *     assertions race-free and never uses the asynchronous ``asyncJobLauncher`` bean.
     * :returns: an initialized synchronous {@link JobLauncher}.
     */
    private JobLauncher synchronousJobLauncher() throws Exception {
        TaskExecutorJobLauncher launcher = new TaskExecutorJobLauncher();
        launcher.setJobRepository(jobRepository);
        launcher.setTaskExecutor(new SyncTaskExecutor());
        launcher.afterPropertiesSet();
        return launcher;
    }

    /**
     * :purpose: Seed the parent ``customers`` row (every ``NOT NULL`` column, including the
     *     ``@Version`` column) so the card cross-reference foreign key ``fk_card_xref_cust`` is
     *     satisfied. Raw JDBC is used so the whitelist-scoped ``Customer`` entity does not have
     *     to be imported.
     * :param custId: the customer id referenced by the seeded {@link CardXref}.
     */
    private void seedCustomer(long custId) {
        // Every NOT NULL column of the migrated ``customers`` table is supplied; the three
        // encrypted PII columns carry blank values, so no PII is seeded (AAP 0.6.7).
        jdbcTemplate.update(INSERT_CUSTOMER_SQL, custId, "Test", "T", "Customer",
                "Addr line 1", "Addr line 2", "Addr line 3", "NC", "USA", "00000",
                "(000)000-0000", "(000)000-0000", "", "", "1970-01-01", "", "Y", 750, 0L);
        jdbcTemplate.update(INSERT_CUSTOMER_SQL, custId, 750, 0L);
    }

    /**
     * :purpose: Seed the card-master row that backs a cross-reference. The interest run posts a
     *     transaction whose ``tran_card_num`` foreign-keys into ``cards``
     *     (``fk_transactions_card``, ``V8__transactions_card_fk.sql``), so a fixture carrying
     *     only the cross-reference would describe a state the database does not permit. The
     *     sensitive CVV is left null so the fixture needs no encryption key.
     * :param cardNum: the 16-character card number (primary key).
     * :param acctId: the owning account id, which must already exist (``fk_cards_account``).
     */
    private void seedCard(String cardNum, long acctId) {
        jdbcTemplate.update(
                "INSERT INTO cards (card_num, card_acct_id, card_cvv_cd, card_embossed_name, "
                        // Preserves the legacy copybook misspelling CARD-EXPIRAION-DATE.
                        + "card_expiraion_date, card_active_status, version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                cardNum, acctId, null, "Interest Holder", "2099-12-31", "Y", 0L);
    }

    /**
     * :purpose: Seed one account with all non-null fields populated and valid lifecycle dates.
     * :param acctId: the 11-digit account id.
     * :param groupId: the disclosure/pricing account group id (``ACCT-GROUP-ID``).
     * :param currBal: the current balance (``ACCT-CURR-BAL``) as a scale-2 decimal string.
     * :param cycCredit: the current-cycle credit (``ACCT-CURR-CYC-CREDIT``) as a decimal string.
     * :param cycDebit: the current-cycle debit (``ACCT-CURR-CYC-DEBIT``) as a decimal string.
     */
    private void seedAccount(long acctId, String groupId, String currBal, String cycCredit, String cycDebit) {
        Account account = new Account();
        account.setAcctId(acctId);
        account.setAcctActiveStatus("Y");
        account.setAcctCurrBal(new BigDecimal(currBal));
        account.setAcctCreditLimit(new BigDecimal("5000.00"));
        account.setAcctCashCreditLimit(new BigDecimal("1000.00"));
        account.setAcctOpenDate("2020-01-01");
        // Preserves the legacy copybook misspelling ``ACCT-EXPIRAION-DATE`` verbatim.
        account.setAcctExpiraionDate("2027-12-31");
        account.setAcctReissueDate("2023-01-01");
        account.setAcctCurrCycCredit(new BigDecimal(cycCredit));
        account.setAcctCurrCycDebit(new BigDecimal(cycDebit));
        account.setAcctAddrZip("12345");
        account.setAcctGroupId(groupId);
        accountRepository.save(account);
    }
}
