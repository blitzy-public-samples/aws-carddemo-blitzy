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
package com.aws.carddemo.batch.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.aws.carddemo.domain.Account;
import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.DisclosureGroup;
import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.domain.TransactionCategoryBalance;
import com.aws.carddemo.domain.id.DisclosureGroupId;
import com.aws.carddemo.domain.id.TransactionCategoryBalanceId;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.DisclosureGroupRepository;
import com.aws.carddemo.repository.TransactionCategoryBalanceRepository;
import com.aws.carddemo.repository.TransactionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Spring Batch integration test for {@link InterestCalculationJobConfig} (job {@code
 * interestCalculationJob}), the migrated counterpart of the legacy z/OS interest-calculation step
 * {@code legacy/app/jcl/INTCALC.jcl} ({@code STEP15 EXEC PGM=CBACT04C,PARM='2022071800'};
 * behavioral spec {@code legacy/app/cbl/CBACT04C.cbl}). The test launches the real job against a
 * real Testcontainers PostgreSQL database (Flyway-provisioned with the VSAM-faithful schema) and
 * asserts the two parity properties that this job most directly threatens, both mandated by the
 * Agent Action Plan:
 *
 * <ol>
 *   <li><b>Decimal truncation fidelity (AAP &sect;0.6.1).</b> The COBOL {@code COMPUTE
 *       WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200} carries <em>no</em> {@code ROUNDED}
 *       phrase, so the result is <em>truncated</em> to scale 2. The Java service reproduces this
 *       with {@link BigDecimal} and {@link RoundingMode#DOWN} — never {@code HALF_UP}, never {@code
 *       float}/{@code double}. The dataset is chosen so truncation and rounding disagree: {@code
 *       100.00 * 11.00 / 1200 = 0.91666...}, which truncates to {@code 0.91} but would round
 *       (HALF_UP) to {@code 0.92}. Asserting {@code 0.91} proves the truncation contract.
 *   <li><b>JCL {@code PARM} &rarr; {@code JobParameter} mapping (AAP &sect;0.1.1).</b> The legacy
 *       {@code PARM='2022071800'} run date becomes a Spring Batch {@code runDate} job parameter.
 *       {@link #interest_is_truncated_down_to_the_cent()} supplies it explicitly, and {@link
 *       #interest_uses_default_runDate_when_parameter_absent()} omits it to prove the
 *       configuration's {@code DEFAULT_RUN_DATE} fallback (the same {@code '2022071800'}) keeps the
 *       job runnable with identical results.
 * </ol>
 *
 * <h2>Controlled, deterministic dataset</h2>
 *
 * <p>The migrated {@code CBACT04C} service abends (a {@code FAILED} step) when a category-balance
 * row references an account, card cross-reference, or disclosure rate that cannot be resolved (the
 * COBOL {@code '00'}-only read paths). The Flyway {@code V2} seed loads 50 {@code tran_cat_balance}
 * rows whose accounts are <em>not</em> seeded, so this test first removes those balances and
 * inserts exactly one fully resolvable account scenario, guaranteeing a single processed account
 * and deterministic arithmetic:
 *
 * <ul>
 *   <li>an {@link Account} ({@code acctId = 99999999999}) opening at {@code 0.00} with an explicit
 *       {@code acctGroupId = "TESTGRPABC"} (exactly 10 characters, so the {@code char(10)} key
 *       needs no blank-padding);
 *   <li>a {@link CardXref} on that account (the interest transaction is written using its card
 *       number);
 *   <li>a {@link DisclosureGroup} keyed {@code (TESTGRPABC, 01, 0001)} with rate {@code 11.00}.
 *       Seeding an account-specific rate (rather than relying on the seeded {@code DEFAULT} group,
 *       which is {@code 15.00} and would yield an exact {@code 1.25}) is what makes the truncation
 *       observable;
 *   <li>a single {@link TransactionCategoryBalance} keyed {@code (99999999999, 01, 0001)} with
 *       balance {@code 100.00}.
 * </ul>
 *
 * <p>The seeded reference tables {@code tran_type}, {@code tran_category} and {@code
 * disclosure_group} are intentionally left intact (only {@code tran_cat_balance} is cleared) so the
 * {@code DEFAULT} group and lookup targets remain available. The schema declares <strong>no foreign
 * keys</strong> (legacy VSAM enforced none — see {@code V1__schema.sql}), so no customer or
 * transaction-category parent rows are required for the inserts above; the controlled four-entity
 * dataset is sufficient and complete.
 *
 * <h2>Test wiring</h2>
 *
 * <p>This is a full-context {@link SpringBootTest} (not a {@code @DataJpaTest} slice) because the
 * batch job must run in its own committed transactions and the assertions read the committed result
 * back — a slice's per-method rollback would hide the persisted balance. A class-managed {@link
 * Testcontainers} PostgreSQL container is bound to the Spring data source through {@link
 * ServiceConnection}, which supersedes the {@code application-test.yml} data-source settings. The
 * {@code test} profile keeps Spring Batch jobs from auto-launching ({@code spring.batch.job.enabled
 * = false}); each job here is launched explicitly via {@link JobLauncherTestUtils}. Following the
 * project convention this test does not use {@code @SpringBatchTest}: the test utilities are wired
 * manually so the specific {@code interestCalculationJob} bean is targeted (the full context
 * defines several {@link Job} beans, hence the {@link Qualifier}).
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class InterestCalculationJobConfigTest {

  /**
   * Account id of the single controlled account ({@code ACCT-ID PIC 9(11)}, fits {@code
   * numeric(11)}).
   */
  private static final long TEST_ACCT_ID = 99_999_999_999L;

  /**
   * Disclosure account-group id of the controlled account. Exactly 10 characters so it fills the
   * {@code char(10)} {@code acct_group_id} / {@code dis_acct_group_id} columns with no
   * blank-padding, keeping the disclosure-rate lookup key equality unambiguous.
   */
  private static final String TEST_GROUP_ID = "TESTGRPABC";

  /** Card number cross-referenced to the controlled account ({@code XREF-CARD-NUM PIC X(16)}). */
  private static final String TEST_CARD_NUM = "9999999999999999";

  /** Customer id cross-referenced to the controlled account ({@code XREF-CUST-ID PIC 9(09)}). */
  private static final long TEST_CUST_ID = 999_999_999L;

  /**
   * Transaction type code shared by the controlled category balance and the interest output ({@code
   * MOVE '01' TO TRAN-TYPE-CD}, CBACT04C L482).
   */
  private static final String TRAN_TYPE = "01";

  /** Category code of the controlled category-balance row ({@code TRANCAT-CD}). */
  private static final String BALANCE_CATEGORY = "0001";

  /**
   * Category code of the generated interest transaction ({@code MOVE '05' TO TRAN-CAT-CD} into
   * {@code PIC 9(04)} &rarr; {@code "0005"}, CBACT04C L483).
   */
  private static final String INTEREST_CATEGORY = "0005";

  /**
   * Opening account balance; the interest is accrued on top of this value ({@code ACCT-CURR-BAL}).
   */
  private static final BigDecimal OPENING_BALANCE = new BigDecimal("0.00");

  /** Controlled category balance fed into the interest computation ({@code TRAN-CAT-BAL}). */
  private static final BigDecimal CATEGORY_BALANCE = new BigDecimal("100.00");

  /**
   * Controlled annual disclosure interest rate ({@code DIS-INT-RATE}); chosen so {@code (100.00 *
   * 11.00) / 1200 = 0.91666...} truncates to {@code 0.91} yet rounds to {@code 0.92}.
   */
  private static final BigDecimal ANNUAL_RATE = new BigDecimal("11.00");

  /** Monthly divisor of the COBOL interest formula ({@code / 1200}, CBACT04C L465). */
  private static final BigDecimal MONTHLY_DIVISOR = BigDecimal.valueOf(1200);

  /** Legacy {@code PARM='2022071800'} run date, supplied as the {@code runDate} job parameter. */
  private static final String RUN_DATE = "2022071800";

  /**
   * Class-managed Testcontainers PostgreSQL 16 instance. {@link ServiceConnection} registers a
   * {@code JdbcConnectionDetails} bean from this running container that supersedes the {@code
   * application-test.yml} data-source settings, so Flyway provisions the VSAM-faithful schema here
   * and the batch job and assertions all share this database.
   */
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  /** Boot-provided Spring Batch job launcher used by {@link #jobLauncherTestUtils}. */
  @Autowired private JobLauncher jobLauncher;

  /** Boot-provided Spring Batch job repository used by the test utilities. */
  @Autowired private JobRepository jobRepository;

  /**
   * The job under test; qualified by name because the full context defines several {@link Job}
   * beans.
   */
  @Autowired
  @Qualifier("interestCalculationJob")
  private Job interestCalculationJob;

  /** {@code ACCTFILE} store; the interest is accrued into and read back from this repository. */
  @Autowired private AccountRepository accountRepository;

  /** {@code XREFFILE} store; supplies the card number written onto the interest transaction. */
  @Autowired private CardXrefRepository cardXrefRepository;

  /** {@code DISCGRP} store; supplies the controlled interest rate. */
  @Autowired private DisclosureGroupRepository disclosureGroupRepository;

  /** {@code TCATBALF} store; cleared and re-seeded with the single controlled balance. */
  @Autowired private TransactionCategoryBalanceRepository tcatBalRepository;

  /** {@code TRANSACT} store; the interest transaction written by the job is asserted here. */
  @Autowired private TransactionRepository transactionRepository;

  /** Manually wired launcher utility (no {@code @SpringBatchTest}); re-created before each test. */
  private JobLauncherTestUtils jobLauncherTestUtils;

  /** Manually wired repository utility used to purge prior job executions before each test. */
  private JobRepositoryTestUtils jobRepositoryTestUtils;

  /**
   * Wires the Spring Batch test utilities manually and rebuilds the controlled single-account
   * dataset before every test. Because {@link SpringBootTest} is not transactional, the deletes and
   * inserts here are committed and visible to the batch job; clearing prior job executions lets
   * each test launch the job afresh.
   */
  @BeforeEach
  void setUp() {
    // Manual test-utility wiring (deliberately not @SpringBatchTest) so the specific
    // interestCalculationJob bean is launched and prior executions are purged for a clean run.
    jobRepositoryTestUtils = new JobRepositoryTestUtils(jobRepository);
    jobRepositoryTestUtils.removeJobExecutions();
    jobLauncherTestUtils = new JobLauncherTestUtils();
    jobLauncherTestUtils.setJobLauncher(jobLauncher);
    jobLauncherTestUtils.setJobRepository(jobRepository);
    jobLauncherTestUtils.setJob(interestCalculationJob);

    // Reset to a fully controlled, deterministic dataset. tran_cat_balance is cleared (it carries
    // 50 V2-seeded rows for unseeded accounts that would abend the job); tran_type / tran_category
    // /
    // disclosure_group are left intact so the DEFAULT group and lookup targets survive.
    transactionRepository.deleteAll();
    tcatBalRepository.deleteAll();
    accountRepository.deleteAll();
    cardXrefRepository.deleteAll();

    accountRepository.save(controlledAccount());
    cardXrefRepository.save(controlledCardXref());
    // Upsert (idempotent across @BeforeEach runs): the account-specific rate coexists with the
    // seeded DEFAULT group and is what makes the truncation observable.
    disclosureGroupRepository.save(controlledDisclosureGroup());
    tcatBalRepository.save(controlledCategoryBalance());
  }

  /**
   * Proves the un-{@code ROUNDED} COBOL interest computation truncates to the cent: {@code 100.00 *
   * 11.00 / 1200 = 0.91666...} must accrue {@code 0.91} (not the rounded {@code 0.92}) into the
   * account balance, and exactly one interest transaction must be written for the cross-referenced
   * card with type {@code 01} / category {@code 0005}.
   *
   * @throws Exception if the job launcher fails to run the job
   */
  @Test
  void interest_is_truncated_down_to_the_cent() throws Exception {
    BigDecimal expectedInterest =
        CATEGORY_BALANCE.multiply(ANNUAL_RATE).divide(MONTHLY_DIVISOR, 2, RoundingMode.DOWN);
    // Guards the dataset choice: truncation (DOWN) and rounding (HALF_UP) must disagree here.
    assertThat(expectedInterest).isEqualByComparingTo("0.91");

    JobExecution execution =
        jobLauncherTestUtils.launchJob(
            new JobParametersBuilder().addString("runDate", RUN_DATE).toJobParameters());

    assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

    Account reloaded = accountRepository.findById(TEST_ACCT_ID).orElseThrow();
    // compareTo (via isEqualByComparingTo) is scale-insensitive, the correct equality for money.
    assertThat(reloaded.getAcctCurrBal())
        .isEqualByComparingTo(OPENING_BALANCE.add(expectedInterest));
    assertThat(reloaded.getAcctCurrBal()).isEqualByComparingTo("0.91");

    List<Transaction> interestTransactions =
        transactionRepository.findAll().stream()
            .filter(txn -> TRAN_TYPE.equals(txn.getTranTypeCd()))
            .filter(txn -> INTEREST_CATEGORY.equals(txn.getTranCatCd()))
            .toList();
    assertThat(interestTransactions).hasSize(1);
    Transaction interestTransaction = interestTransactions.get(0);
    assertThat(interestTransaction.getTranCardNum()).isEqualTo(TEST_CARD_NUM);
    assertThat(interestTransaction.getTranAmt()).isEqualByComparingTo(expectedInterest);
  }

  /**
   * Proves the JCL {@code PARM} default mapping: launching the job with no {@code runDate}
   * parameter still completes successfully because the configuration falls back to its {@code
   * DEFAULT_RUN_DATE} ({@code '2022071800'}). The interest math is independent of the run date, so
   * the controlled account still accrues exactly {@code 0.91}.
   *
   * @throws Exception if the job launcher fails to run the job
   */
  @Test
  void interest_uses_default_runDate_when_parameter_absent() throws Exception {
    JobExecution execution = jobLauncherTestUtils.launchJob();

    assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

    Account reloaded = accountRepository.findById(TEST_ACCT_ID).orElseThrow();
    assertThat(reloaded.getAcctCurrBal()).isEqualByComparingTo("0.91");
  }

  /**
   * Builds the single controlled account. Every column is {@code NOT NULL} in {@code
   * V1__schema.sql}, so all fields are populated; only {@link Account#getAcctCurrBal()} (opening
   * {@code 0.00}) and {@link Account#getAcctGroupId()} ({@link #TEST_GROUP_ID}) participate in the
   * interest behavior — the remaining monetary fields are arbitrary non-null scale-2 values.
   *
   * @return the controlled {@link Account} to persist
   */
  private static Account controlledAccount() {
    Account account = new Account();
    account.setAcctId(TEST_ACCT_ID);
    account.setAcctActiveStatus("Y");
    account.setAcctCurrBal(OPENING_BALANCE);
    account.setAcctCreditLimit(new BigDecimal("99999.99"));
    account.setAcctCashCreditLimit(new BigDecimal("0.00"));
    account.setAcctOpenDate("2020-01-01");
    account.setAcctExpiraionDate("2099-12-31");
    account.setAcctReissueDate("2020-01-01");
    account.setAcctCurrCycCredit(new BigDecimal("0.00"));
    account.setAcctCurrCycDebit(new BigDecimal("0.00"));
    account.setAcctAddrZip("00000");
    account.setAcctGroupId(TEST_GROUP_ID);
    return account;
  }

  /**
   * Builds the card cross-reference for the controlled account; its card number is copied onto the
   * generated interest transaction ({@code MOVE XREF-CARD-NUM TO TRAN-CARD-NUM}).
   *
   * @return the controlled {@link CardXref} to persist
   */
  private static CardXref controlledCardXref() {
    CardXref cardXref = new CardXref();
    cardXref.setXrefCardNum(TEST_CARD_NUM);
    cardXref.setXrefCustId(TEST_CUST_ID);
    cardXref.setXrefAcctId(TEST_ACCT_ID);
    return cardXref;
  }

  /**
   * Builds the account-specific disclosure-group rate keyed {@code (TESTGRPABC, 01, 0001)}. The
   * service resolves this row directly (no {@code DEFAULT} fallback), so the {@link #ANNUAL_RATE}
   * of {@code 11.00} drives the truncation assertion.
   *
   * @return the controlled {@link DisclosureGroup} to persist
   */
  private static DisclosureGroup controlledDisclosureGroup() {
    DisclosureGroup disclosureGroup = new DisclosureGroup();
    disclosureGroup.setId(new DisclosureGroupId(TEST_GROUP_ID, TRAN_TYPE, BALANCE_CATEGORY));
    disclosureGroup.setDisIntRate(ANNUAL_RATE);
    return disclosureGroup;
  }

  /**
   * Builds the single category-balance row keyed {@code (99999999999, 01, 0001)} with a balance of
   * {@code 100.00}; this is the only row the job processes after {@code tran_cat_balance} is
   * cleared.
   *
   * @return the controlled {@link TransactionCategoryBalance} to persist
   */
  private static TransactionCategoryBalance controlledCategoryBalance() {
    TransactionCategoryBalance balance = new TransactionCategoryBalance();
    balance.setId(new TransactionCategoryBalanceId(TEST_ACCT_ID, TRAN_TYPE, BALANCE_CATEGORY));
    balance.setTranCatBal(CATEGORY_BALANCE);
    return balance;
  }
}
