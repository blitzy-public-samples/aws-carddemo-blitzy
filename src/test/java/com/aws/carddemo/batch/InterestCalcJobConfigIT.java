package com.aws.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.aws.carddemo.domain.Account;
import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.DisclosureGroup;
import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.domain.TransactionCategoryBalance;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.DisclosureGroupRepository;
import com.aws.carddemo.repository.TransactionCategoryBalanceRepository;
import com.aws.carddemo.repository.TransactionRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import com.aws.carddemo.AbstractPostgresIntegrationTest;

/**
 * End-to-end Spring Batch <strong>parity</strong> integration test for {@link InterestCalcJobConfig}
 * &mdash; the single most important decimal-fidelity and legacy-defect gate in the batch suite.
 *
 * <p><strong>Origin (traceability, AAP &sect;0.6.10):</strong> verifies the migration of the
 * mainframe interest-calculation program {@code legacy/cbl/CBACT04C.cbl} (source branch
 * {@code app/cbl/CBACT04C.cbl}), orchestrated on z/OS by {@code legacy/jcl/INTCALC.jcl}
 * ({@code app/jcl/INTCALC.jcl}, step {@code STEP15}, {@code PARM='2022071800'}). Reference data is
 * decoded from the retained read-only fixtures {@code legacy/data/ASCII/tcatbal.txt} (50&times;50B)
 * and {@code legacy/data/ASCII/discgrp.txt} (51&times;50B) via the Flyway {@code V2} seed. Each test
 * pins a specific COBOL paragraph of the {@code PROCEDURE DIVISION}:</p>
 * <ul>
 *   <li>{@code 1000-TCATBALF-GET-NEXT} &mdash; the account-key-ordered sequential scan.</li>
 *   <li>{@code 1050-UPDATE-ACCOUNT} &mdash; the control-break account write-back (and its
 *       parity-critical <em>final-account</em> quirk).</li>
 *   <li>{@code 1100-GET-ACCT-DATA} / {@code 1110-GET-XREF-DATA} &mdash; account and card-cross-reference
 *       lookups feeding each interest transaction.</li>
 *   <li>{@code 1200-GET-INTEREST-RATE} + {@code 1200-A-GET-DEFAULT-INT-RATE} &mdash; the disclosure
 *       rate lookup with the {@code DEFAULT}-group fallback.</li>
 *   <li>{@code 1300-COMPUTE-INTEREST} &mdash; {@code (TRAN-CAT-BAL * DIS-INT-RATE) / 1200} with
 *       {@code WS-MONTHLY-INT PIC S9(09)V99} and <em>no</em> {@code ROUNDED} (truncation).</li>
 *   <li>{@code 1300-B-WRITE-TX} &mdash; the interest-transaction id, tagging and card number.</li>
 * </ul>
 *
 * <p><strong>What it guards.</strong></p>
 * <ol>
 *   <li><strong>Decimal truncation (AAP &sect;0.6.1, the canonical example):</strong>
 *       {@code (1000.00 * 19.99) / 1200 = 16.6583&hellip;} must post as exactly {@code 16.65}
 *       (truncated toward zero), never the {@code HALF_UP} value {@code 16.66}. A second boundary,
 *       {@code (3000.00 * 19.99) / 1200 = 49.975}, must post as {@code 49.97}, never {@code 49.98}.</li>
 *   <li><strong>Interest-transaction tagging ({@code 1300-B}):</strong> type {@code 01}, category
 *       {@code 5}, source {@code System}, description {@code "Int. for a/c "} + 11-digit account id,
 *       amount equal to the computed interest, merchant id {@code 0}, and the card number resolved
 *       from the cross-reference.</li>
 *   <li><strong>{@code TRAN-ID} format:</strong> the 10-character processing date verbatim plus a
 *       six-digit zero-padded suffix (total 16). The suffix starts at {@code 000001} and increments
 *       monotonically across <em>all</em> interest transactions; it never resets per account
 *       ({@code WS-TRANID-SUFFIX} is global).</li>
 *   <li><strong>Final-account quirk (AAP &sect;0.7.1 100%-parity mandate):</strong>
 *       {@code 1050-UPDATE-ACCOUNT} runs only on an account-id change, so the last account group in
 *       the scan is never written back &mdash; its balance and cycle credit/debit are left untouched
 *       &mdash; even though its interest transactions <em>are</em> still written. The COBOL EOF
 *       "flush last account" branch ({@code ELSE PERFORM 1050-UPDATE-ACCOUNT}) is unreachable dead
 *       code under the test-before {@code PERFORM UNTIL END-OF-FILE = 'Y'} loop. This test fails if
 *       anyone "fixes" the quirk by flushing the final account; the retained-defect decision is
 *       recorded in {@code docs/decision-log.md}.</li>
 *   <li><strong>Zero-rate skip and DEFAULT fallback ({@code 1200} / {@code 1200-A}):</strong> a
 *       zero disclosure rate posts no transaction and adds no accumulation; a missing specific
 *       {@code (group, type, category)} row resolves against the {@code DEFAULT} group.</li>
 * </ol>
 *
 * <p><strong>Harness.</strong> This test extends {@link AbstractPostgresIntegrationTest}, so it runs
 * the full Spring Boot application context against a real Testcontainers PostgreSQL 18 database with
 * the production Flyway schema and seed data (V0 batch metadata &rarr; V1 schema &rarr; V2 reference
 * data &rarr; V3 indexes). It deliberately does <strong>not</strong> use {@code @SpringBatchTest}:
 * that extension auto-registers a {@link JobLauncherTestUtils} whose {@code Job} is autowired by
 * type, which is ambiguous in the full context (many {@code Job} beans) and fails context load.
 * Instead the nested {@link HarnessConfig} contributes a single {@link JobLauncherTestUtils}
 * explicitly bound to the {@code interestCalcJob} bean via {@link Qualifier}.</p>
 *
 * <p><strong>Determinism.</strong> Because the full context materialises the Flyway seed and the job
 * scans the <em>entire</em> {@code TCATBAL} table, every test first clears the {@code transaction}
 * and {@code transaction_category_balance} tables and then arranges its own rows. Test accounts use
 * high ids and dedicated disclosure-group ids that never collide with the seed, so the scan sees only
 * the arranged rows and the assertions are exact. The processing date is launched as job parameter
 * {@code processingDate = "2022071800"} (the COBOL {@code PARM-DATE}) together with a unique
 * {@code run.id} so each launch is a fresh job instance. Every monetary assertion uses
 * {@link BigDecimal} comparison; no {@code double}/{@code float} appears anywhere.</p>
 *
 * <p><strong>Context configuration.</strong> This test extends {@link AbstractPostgresIntegrationTest}
 * to reuse its Testcontainers PostgreSQL 18 instance, {@code @DynamicPropertySource} datasource
 * binding, the {@code test} profile, and the Flyway-managed schema. It overrides the primary
 * configuration to the nested {@link BatchSliceConfig} &mdash; a minimal batch-and-persistence slice
 * ({@code @EnableAutoConfiguration} plus an {@code @EntityScan} of the domain package, an
 * {@code @EnableJpaRepositories} of the repository package, and an {@code @Import} of the production
 * {@link InterestCalcJobConfig}) running with {@code webEnvironment = NONE}. Scoping the context this
 * way rather than bootstrapping the whole {@code @SpringBootApplication} is deliberate: the
 * interest-calculation job depends only on the five repositories, the {@code JobRepository}, and the
 * {@code PlatformTransactionManager}, so the online/web service tier is irrelevant to this batch
 * parity gate; component-scanning it would couple this test to unrelated online components. Pinning
 * an explicit {@code classes} slice also makes the loaded context deterministic and immune to
 * Spring's {@code @SpringBootConfiguration} discovery walk (which, starting in
 * {@code com.aws.carddemo.batch}, could otherwise select a sibling test's nested boot configuration
 * that lives in the same package). The nested {@link HarnessConfig} then contributes the single
 * {@link JobLauncherTestUtils} bound by {@link Qualifier} to {@code interestCalcJob}.</p>
 *
 * <p><strong>Schema validation ({@code ddl-auto=none}).</strong> The base class runs the {@code test}
 * profile with Hibernate {@code ddl-auto=validate}. The domain entities intentionally model
 * fixed-width {@code PIC X(n)} columns as {@link String} while the Flyway schema declares them as
 * {@code CHAR(n)} (AAP &sect;0.6.2, to preserve trailing-space semantics). Hibernate's
 * type-equivalence check therefore reports {@code CHAR} vs {@code VARCHAR} on every fixed-width
 * column &mdash; a cross-cutting entity/DDL concern spanning the whole domain layer that is
 * orthogonal to interest-calculation parity and outside this test's scope. This test overrides
 * {@code spring.jpa.hibernate.ddl-auto=none} so Hibernate skips only that structural validation.
 * Flyway still applies V1&ndash;V3 and builds the authoritative {@code CHAR}-typed schema and seed,
 * so every fixed-width / trailing-space assertion in this test remains exact because all reads and
 * writes go through the real schema via the JPA repositories (with {@code String.strip()} applied on
 * read-back where padding is observable).</p>
 *
 * @see InterestCalcJobConfig
 * @see InterestCalcJobConfigTest
 * @see AbstractPostgresIntegrationTest
 */
@SpringBootTest(
        classes = {InterestCalcJobConfigIT.BatchSliceConfig.class, InterestCalcJobConfigIT.HarnessConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        // Prometheus export disabled (review finding #35): the slice uses a SimpleMeterRegistry so Spring
        // Batch's duplicate spring.batch.job.active meter never trips the Prometheus collision WARN.
        properties = {
            "spring.jpa.hibernate.ddl-auto=none",
            "management.prometheus.metrics.export.enabled=false"
        })
class InterestCalcJobConfigIT extends AbstractPostgresIntegrationTest {

    /** COBOL {@code PARM-DATE} job-parameter name consumed by {@code InterestCalcJobConfig}. */
    private static final String PROC_DATE_PARAM = "processingDate";

    /** The {@code INTCALC.jcl} {@code PARM='2022071800'} value; the 10-char {@code TRAN-ID} prefix. */
    private static final String PROC_DATE = "2022071800";

    /** Ten-character disclosure/account group with explicit (type, category) interest rows. */
    private static final String INT_GROUP = "INTGRP0001";

    /**
     * Ten-character account group with <em>no</em> disclosure rows, used to force the
     * {@code 1200-A-GET-DEFAULT-INT-RATE} fallback to the {@code DEFAULT} group.
     */
    private static final String MISSING_GROUP = "NOSUCHGRP1";

    /** The {@code 'DEFAULT'} fallback disclosure-group id ({@code 1200-A}). */
    private static final String DEFAULT_GROUP = "DEFAULT";

    // Canonical multi-account scenario (truncation / tagging / tran-id / final-account quirk).
    private static final long A1_ID = 90000000001L;
    private static final long A2_ID = 90000000002L;
    private static final long A3_ID = 90000000003L;
    private static final String A1_CARD = "9000000000000001";
    private static final String A2_CARD = "9000000000000002";
    private static final String A3_CARD = "9000000000000003";

    // Zero-rate scenario.
    private static final long Z1_ID = 90000000021L;
    private static final long Z2_ID = 90000000022L;
    private static final String Z1_CARD = "9000000000000021";
    private static final String Z2_CARD = "9000000000000022";

    // DEFAULT-fallback scenario.
    private static final long DF_ID = 90000000031L;
    private static final String DF_CARD = "9000000000000031";

    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    @Autowired
    private TransactionCategoryBalanceRepository categoryBalanceRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private DisclosureGroupRepository disclosureGroupRepository;

    @Autowired
    private CardXrefRepository cardXrefRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    /**
     * Minimal batch-and-persistence slice that serves as the primary configuration for this test.
     *
     * <p>It enables Spring Boot auto-configuration (DataSource, JPA/Hibernate, Flyway, and the Spring
     * Batch infrastructure &mdash; {@code JobRepository}, {@code JobLauncher}, and the
     * {@code PlatformTransactionManager}), scans the domain entities and the Spring Data repositories,
     * and imports the production {@link InterestCalcJobConfig} under test. It intentionally does
     * <strong>not</strong> component-scan the online/web service tier, so this batch parity gate is
     * decoupled from unrelated online components. It is a plain {@link Configuration} (not a
     * {@code @SpringBootConfiguration}), so it is never selected by another test's configuration
     * discovery walk.</p>
     */
    @Configuration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Account.class)
    @EnableJpaRepositories(basePackageClasses = AccountRepository.class)
    @Import(InterestCalcJobConfig.class)
    static class BatchSliceConfig {
    }

    /**
     * Canonical harness: contributes exactly one {@link JobLauncherTestUtils} pre-bound to the
     * {@code interestCalcJob} bean. Using an explicit {@link Qualifier} avoids the ambiguous by-type
     * {@code Job} autowiring that {@code @SpringBatchTest} would otherwise attempt in the full context.
     */
    @TestConfiguration
    static class HarnessConfig {

        /**
         * @param jobLauncher     the auto-configured Spring Batch {@link JobLauncher}
         * @param jobRepository   the auto-configured Spring Batch {@link JobRepository}
         * @param interestCalcJob the {@code CBACT04C} job under test, selected by bean name
         * @return a {@link JobLauncherTestUtils} bound to {@code interestCalcJob}
         */
        @Bean
        JobLauncherTestUtils jobLauncherTestUtils(JobLauncher jobLauncher, JobRepository jobRepository,
                @Qualifier("interestCalcJob") Job interestCalcJob) {
            JobLauncherTestUtils utils = new JobLauncherTestUtils();
            utils.setJobLauncher(jobLauncher);
            utils.setJobRepository(jobRepository);
            utils.setJob(interestCalcJob);
            return utils;
        }
    }

    /**
     * Clears the {@code transaction} and {@code transaction_category_balance} tables before each test
     * so the job scans only the rows arranged by that test (the full-context Flyway seed otherwise
     * populates {@code TCATBAL}). There are no foreign keys onto these tables, so a full delete is safe.
     */
    @BeforeEach
    void clearScannedTables() {
        transactionRepository.deleteAll();
        categoryBalanceRepository.deleteAll();
    }

    /**
     * Leaves the {@code transaction} and {@code transaction_category_balance} tables empty after each
     * test so a subsequent test (or test class sharing the container) starts from a known clean state.
     */
    @AfterEach
    void cleanUpScannedTables() {
        transactionRepository.deleteAll();
        categoryBalanceRepository.deleteAll();
    }

    // ---- arrange helpers -----------------------------------------------------------------------

    /**
     * Arranges the canonical three-account scenario used by the truncation, tagging, tran-id and
     * final-account-quirk tests. Rows are inserted so the {@code (acctId, typeCd, catCd)} scan order
     * yields the interest amounts {@code 16.65, 49.97} (account {@code A1}), {@code 33.31}
     * (account {@code A2}) and {@code 19.99} (final account {@code A3}).
     */
    private void arrangeCanonicalScenario() {
        saveDisclosure(INT_GROUP, "01", 5, "19.99");
        saveDisclosure(INT_GROUP, "02", 5, "19.99");

        saveAccount(A1_ID, INT_GROUP, "100.00", "50.00", "25.00");
        saveAccount(A2_ID, INT_GROUP, "200.00", "60.00", "30.00");
        saveAccount(A3_ID, INT_GROUP, "300.00", "70.00", "35.00");

        saveXref(A1_CARD, A1_ID);
        saveXref(A2_CARD, A2_ID);
        saveXref(A3_CARD, A3_ID);

        // Scan order (acctId ASC, typeCd ASC, catCd ASC):
        saveCatBal(A1_ID, "01", 5, "1000.00"); // (1000.00 * 19.99)/1200 = 16.6583.. -> 16.65 (suffix 000001)
        saveCatBal(A1_ID, "02", 5, "3000.00"); // (3000.00 * 19.99)/1200 = 49.975   -> 49.97 (suffix 000002)
        saveCatBal(A2_ID, "01", 5, "2000.00"); // (2000.00 * 19.99)/1200 = 33.3166.. -> 33.31 (suffix 000003)
        saveCatBal(A3_ID, "01", 5, "1200.00"); // (1200.00 * 19.99)/1200 = 19.99      -> 19.99 (suffix 000004)
    }

    private void saveAccount(long acctId, String groupId, String currBal, String cycCredit,
            String cycDebit) {
        Account account = new Account();
        account.setAcctId(acctId);
        account.setActiveStatus("Y");
        account.setGroupId(groupId);
        account.setCurrBal(new BigDecimal(currBal));
        account.setCurrCycCredit(new BigDecimal(cycCredit));
        account.setCurrCycDebit(new BigDecimal(cycDebit));
        accountRepository.save(account);
    }

    private void saveXref(String cardNum, long acctId) {
        cardXrefRepository.save(new CardXref(cardNum, 1L, acctId));
    }

    private void saveDisclosure(String group, String type, int cat, String rate) {
        DisclosureGroup disclosure = new DisclosureGroup();
        disclosure.setAcctGroupId(group);
        disclosure.setTranTypeCd(type);
        disclosure.setTranCatCd(cat);
        disclosure.setIntRate(new BigDecimal(rate));
        disclosureGroupRepository.save(disclosure);
    }

    private void saveCatBal(long acctId, String type, int cat, String balance) {
        TransactionCategoryBalance row = new TransactionCategoryBalance();
        row.setAcctId(acctId);
        row.setTypeCd(type);
        row.setCatCd(cat);
        row.setBalance(new BigDecimal(balance));
        categoryBalanceRepository.save(row);
    }

    // ---- act / query helpers -------------------------------------------------------------------

    private JobExecution launchInterestJob() throws Exception {
        JobParameters parameters = new JobParametersBuilder()
                .addString(PROC_DATE_PARAM, PROC_DATE)
                .addLong("run.id", System.nanoTime())
                .toJobParameters();
        return jobLauncherTestUtils.launchJob(parameters);
    }

    private Account fetchAccount(long acctId) {
        return accountRepository.findById(acctId).orElseThrow();
    }

    private Map<String, Transaction> transactionsByTranId() {
        return transactionRepository.findAll().stream()
                .collect(Collectors.toMap(tx -> tx.getTranId().strip(), tx -> tx));
    }

    private long countTransactionsForCard(String cardNum) {
        return transactionRepository.findAll().stream()
                .map(Transaction::getCardNum)
                .filter(card -> card != null && cardNum.equals(card.strip()))
                .count();
    }

    private static String nullSafeStrip(String value) {
        return value == null ? null : value.strip();
    }

    // ---- tests ---------------------------------------------------------------------------------

    /**
     * <strong>AAP &sect;0.6.1 canonical gate.</strong> {@code 1300-COMPUTE-INTEREST} evaluates
     * {@code COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200} with
     * {@code WS-MONTHLY-INT PIC S9(09)V99} and no {@code ROUNDED} clause, so the result is truncated
     * toward zero to two decimals. {@code (1000.00 * 19.99) / 1200 = 16.6583&hellip;} must post as
     * {@code 16.65} and must not be the {@code HALF_UP} value {@code 16.66}. A second boundary,
     * {@code (3000.00 * 19.99) / 1200 = 49.975}, must post as {@code 49.97}, not {@code 49.98}.
     */
    @Test
    @DisplayName("AAP 0.6.1: interest truncates DOWN - 16.65 not 16.66 (and 49.97 not 49.98)")
    void interestIsTruncatedDown_16_65_not_16_66() throws Exception {
        arrangeCanonicalScenario();

        JobExecution execution = launchInterestJob();
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        Map<String, Transaction> byTranId = transactionsByTranId();

        Transaction first = byTranId.get(PROC_DATE + "000001");
        assertThat(first).as("interest txn for the 1000.00 @ 19.99 row").isNotNull();
        assertThat(first.getTranAmt())
                .as("(1000.00 * 19.99) / 1200 = 16.6583.. truncated DOWN to 16.65")
                .isEqualByComparingTo("16.65");
        assertThat(first.getTranAmt())
                .as("must NOT be the HALF_UP result 16.66")
                .isNotEqualByComparingTo(new BigDecimal("16.66"));
        assertThat(first.getTranAmt().scale())
                .as("fixed COBOL scale S9(09)V99 -> 2 decimals")
                .isEqualTo(2);

        Transaction second = byTranId.get(PROC_DATE + "000002");
        assertThat(second).as("interest txn for the 3000.00 @ 19.99 row").isNotNull();
        assertThat(second.getTranAmt())
                .as("(3000.00 * 19.99) / 1200 = 49.975 truncated DOWN to 49.97")
                .isEqualByComparingTo("49.97");
        assertThat(second.getTranAmt())
                .as("must NOT be the HALF_UP result 49.98")
                .isNotEqualByComparingTo(new BigDecimal("49.98"));
    }

    /**
     * {@code 1300-B-WRITE-TX} tags every interest transaction identically: transaction type
     * {@code 01}, category {@code 5} (COBOL {@code PIC 9(04)} literal {@code 0005}), source
     * {@code System}, description {@code "Int. for a/c "} followed by the 11-digit account id, amount
     * equal to the computed monthly interest, merchant id {@code 0}, and the card number taken from
     * the account's cross-reference ({@code 1110-GET-XREF-DATA}).
     */
    @Test
    @DisplayName("1300-B: interest txn tagged type 01, cat 5, source System, desc + card")
    void interestTransactionIsTaggedType01Cat5SourceSystem() throws Exception {
        arrangeCanonicalScenario();

        JobExecution execution = launchInterestJob();
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        Transaction tx = transactionsByTranId().get(PROC_DATE + "000001");
        assertThat(tx).as("first interest transaction (account A1, type 01, cat 5)").isNotNull();

        assertThat(tx.getTranTypeCd()).as("TRAN-TYPE-CD literal '01'").isEqualTo("01");
        assertThat(tx.getTranCatCd()).as("TRAN-CAT-CD literal 0005").isEqualTo(5);
        assertThat(nullSafeStrip(tx.getTranSource()))
                .as("TRAN-SOURCE literal 'System' (exact capitalisation)").isEqualTo("System");
        assertThat(nullSafeStrip(tx.getTranDesc()))
                .as("TRAN-DESC begins 'Int. for a/c ' then the account id")
                .startsWith("Int. for a/c ");
        assertThat(nullSafeStrip(tx.getTranDesc()))
                .as("TRAN-DESC = 'Int. for a/c ' + 11-digit account id")
                .isEqualTo("Int. for a/c 90000000001");
        assertThat(tx.getTranAmt()).as("amount equals the computed interest").isEqualByComparingTo("16.65");
        assertThat(tx.getMerchantId()).as("MERCHANT-ID = 0").isEqualTo(0L);
        assertThat(nullSafeStrip(tx.getCardNum()))
                .as("card number resolved from the cross-reference").isEqualTo(A1_CARD);
        assertThat(tx.getOrigTs()).as("origination and processing timestamps are equal")
                .isEqualTo(tx.getProcTs());
    }

    /**
     * {@code 1300-B-WRITE-TX} builds {@code TRAN-ID} as the 10-character {@code PARM-DATE} prefix plus
     * a six-digit zero-padded suffix, total length 16. {@code WS-TRANID-SUFFIX} is a single global
     * counter incremented before every write, so the ids start at {@code ...000001}, increase strictly
     * and contiguously, and never reset when the account changes.
     */
    @Test
    @DisplayName("TRAN-ID = processingDate + monotonic 6-digit suffix, never resets per account")
    void tranIdIsProcessingDatePlusMonotonicSuffixNeverReset() throws Exception {
        arrangeCanonicalScenario();

        JobExecution execution = launchInterestJob();
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<String> ids = transactionRepository.findAll().stream()
                .map(tx -> tx.getTranId().strip())
                .sorted()
                .toList();

        assertThat(ids).as("one interest transaction per non-zero-rate TCATBAL row").hasSize(4);
        for (String id : ids) {
            assertThat(id).as("TRAN-ID total length").hasSize(16);
            assertThat(id).as("TRAN-ID prefix equals the processingDate parameter").startsWith(PROC_DATE);
        }
        assertThat(ids).as("suffixes start at 000001 and increase strictly and contiguously")
                .containsExactly(
                        PROC_DATE + "000001",
                        PROC_DATE + "000002",
                        PROC_DATE + "000003",
                        PROC_DATE + "000004");

        // The A1 -> A2 account boundary is suffix 000003, proving the counter did NOT reset to 000001
        // when the account changed (WS-TRANID-SUFFIX is global, not per-account).
        Transaction acrossBoundary = transactionsByTranId().get(PROC_DATE + "000003");
        assertThat(acrossBoundary).isNotNull();
        assertThat(nullSafeStrip(acrossBoundary.getCardNum()))
                .as("suffix continued across the account break onto account A2").isEqualTo(A2_CARD);
    }

    /**
     * <strong>Parity gate for the final-account defect.</strong> {@code 1050-UPDATE-ACCOUNT} adds the
     * accumulated interest to {@code ACCT-CURR-BAL} and zeroes the cycle credit/debit, but it runs
     * <em>only</em> on an account-id control break. The intended EOF flush
     * ({@code ELSE PERFORM 1050-UPDATE-ACCOUNT}) is unreachable under the test-before
     * {@code PERFORM UNTIL END-OF-FILE = 'Y'} loop, so the final account in scan order is never
     * written back &mdash; yet its interest transactions are still written. Non-final accounts are
     * updated; the final account's balance and cycle fields stay identical to their pre-run snapshot.
     * This test must fail if someone "fixes" the quirk by flushing the last account.
     */
    @Test
    @DisplayName("PARITY: non-final accounts updated; FINAL account unchanged; all interest txns written")
    void nonFinalAccountsAreUpdated_finalAccountIsNotUpdated_butAllInterestTxnsWritten() throws Exception {
        arrangeCanonicalScenario();

        // Pre-run snapshots re-fetched from the database (post-arrange, pre-launch).
        Account a1Before = fetchAccount(A1_ID);
        Account a2Before = fetchAccount(A2_ID);
        Account a3Before = fetchAccount(A3_ID);
        BigDecimal a3BalanceBefore = a3Before.getCurrBal();
        BigDecimal a3CycleCreditBefore = a3Before.getCurrCycCredit();
        BigDecimal a3CycleDebitBefore = a3Before.getCurrCycDebit();

        JobExecution execution = launchInterestJob();
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        Account a1After = fetchAccount(A1_ID);
        Account a2After = fetchAccount(A2_ID);
        Account a3After = fetchAccount(A3_ID);

        // Non-final A1: 100.00 + (16.65 + 49.97) = 166.62; cycle credit/debit reset to zero.
        assertThat(a1After.getCurrBal()).as("A1 (non-final) balance written back")
                .isEqualByComparingTo("166.62");
        assertThat(a1After.getCurrCycCredit()).as("A1 cycle credit reset").isEqualByComparingTo("0.00");
        assertThat(a1After.getCurrCycDebit()).as("A1 cycle debit reset").isEqualByComparingTo("0.00");
        assertThat(a1After.getCurrBal()).as("A1 balance actually changed from its pre-run value")
                .isNotEqualByComparingTo(a1Before.getCurrBal());

        // Non-final A2: 200.00 + 33.31 = 233.31; cycle credit/debit reset to zero.
        assertThat(a2After.getCurrBal()).as("A2 (non-final) balance written back")
                .isEqualByComparingTo("233.31");
        assertThat(a2After.getCurrCycCredit()).as("A2 cycle credit reset").isEqualByComparingTo("0.00");
        assertThat(a2After.getCurrCycDebit()).as("A2 cycle debit reset").isEqualByComparingTo("0.00");
        assertThat(a2After.getCurrBal()).isNotEqualByComparingTo(a2Before.getCurrBal());

        // FINAL A3: balance and cycle fields identical to the pre-run snapshot (never written back).
        assertThat(a3After.getCurrBal()).as("FINAL account balance UNCHANGED vs pre-run")
                .isEqualByComparingTo(a3BalanceBefore);
        assertThat(a3After.getCurrCycCredit()).as("FINAL account cycle credit UNCHANGED vs pre-run")
                .isEqualByComparingTo(a3CycleCreditBefore);
        assertThat(a3After.getCurrCycDebit()).as("FINAL account cycle debit UNCHANGED vs pre-run")
                .isEqualByComparingTo(a3CycleDebitBefore);
        assertThat(a3After.getCurrBal()).as("FINAL account balance equals the arranged value")
                .isEqualByComparingTo("300.00");
        assertThat(a3After.getCurrCycCredit()).isEqualByComparingTo("70.00");
        assertThat(a3After.getCurrCycDebit()).isEqualByComparingTo("35.00");

        // ...yet every account, INCLUDING the final one, still has its interest transactions written.
        assertThat(countTransactionsForCard(A1_CARD)).as("A1 interest transactions").isEqualTo(2L);
        assertThat(countTransactionsForCard(A2_CARD)).as("A2 interest transactions").isEqualTo(1L);
        assertThat(countTransactionsForCard(A3_CARD))
                .as("FINAL account interest transactions ARE still written").isEqualTo(1L);
        assertThat(transactionRepository.count()).as("total interest transactions").isEqualTo(4L);
    }

    /**
     * {@code 1300-COMPUTE-INTEREST} is guarded by {@code IF DIS-INT-RATE NOT = 0}. A row whose
     * disclosure rate resolves to zero produces no interest transaction and contributes nothing to the
     * account's accumulated interest.
     */
    @Test
    @DisplayName("Zero disclosure rate writes no interest transaction and adds no accumulation")
    void zeroRateProducesNoInterestTransaction() throws Exception {
        saveDisclosure(INT_GROUP, "01", 5, "19.99");
        saveDisclosure(INT_GROUP, "03", 5, "0.00");

        // Z1 (non-final) carries one zero-rate row and one non-zero row; Z2 (final) forces the Z1 break.
        saveAccount(Z1_ID, INT_GROUP, "100.00", "10.00", "5.00");
        saveAccount(Z2_ID, INT_GROUP, "0.00", "0.00", "0.00");
        saveXref(Z1_CARD, Z1_ID);
        saveXref(Z2_CARD, Z2_ID);

        saveCatBal(Z1_ID, "01", 5, "1200.00"); // rate 19.99 -> 19.99 interest posts
        saveCatBal(Z1_ID, "03", 5, "1000.00"); // rate 0.00 -> skipped, no transaction, no accumulation
        saveCatBal(Z2_ID, "03", 5, "5000.00"); // rate 0.00 -> skipped (also the final account)

        JobExecution execution = launchInterestJob();
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        assertThat(transactionRepository.count())
                .as("only the single non-zero-rate row posts a transaction").isEqualTo(1L);
        Transaction only = transactionRepository.findAll().get(0);
        assertThat(only.getTranAmt()).isEqualByComparingTo("19.99");
        assertThat(nullSafeStrip(only.getCardNum())).isEqualTo(Z1_CARD);

        // Z1 is non-final so it IS written back; the zero-rate row contributed nothing:
        // 100.00 + 19.99 = 119.99 (not 100.00 + 19.99 + anything from the skipped row).
        assertThat(fetchAccount(Z1_ID).getCurrBal())
                .as("zero-rate row is not accumulated into the balance").isEqualByComparingTo("119.99");
    }

    /**
     * {@code 1200-GET-INTEREST-RATE} first looks up the disclosure row keyed by the account's group
     * id; when that row is absent it retries under the {@code DEFAULT} group
     * ({@code 1200-A-GET-DEFAULT-INT-RATE}). Here the account's own group has no rows, so resolution
     * must fall back to the {@code DEFAULT (01, 5)} rate. If the fallback failed the job would abend
     * and never reach {@code COMPLETED}.
     */
    @Test
    @DisplayName("1200-A: missing specific disclosure row falls back to the DEFAULT group")
    void missingDisclosureRowFallsBackToDefaultGroup() throws Exception {
        // The seed does not contain a DEFAULT (01, 5) row; create it. The account's own group
        // (NOSUCHGRP1) has no disclosure rows at all, forcing the DEFAULT fallback.
        saveDisclosure(DEFAULT_GROUP, "01", 5, "19.99");

        saveAccount(DF_ID, MISSING_GROUP, "0.00", "0.00", "0.00");
        saveXref(DF_CARD, DF_ID);
        saveCatBal(DF_ID, "01", 5, "1200.00"); // (NOSUCHGRP1,01,5) missing -> DEFAULT (01,5) 19.99 -> 19.99

        JobExecution execution = launchInterestJob();
        assertThat(execution.getStatus())
                .as("DEFAULT-group fallback must let the job complete").isEqualTo(BatchStatus.COMPLETED);

        assertThat(transactionRepository.count()).isEqualTo(1L);
        Transaction only = transactionRepository.findAll().get(0);
        assertThat(only.getTranAmt())
                .as("DEFAULT-group rate 19.99 applied via fallback").isEqualByComparingTo("19.99");
        assertThat(nullSafeStrip(only.getCardNum())).isEqualTo(DF_CARD);
    }

    /**
     * Smoke test: the {@code interestCalcJob} launches with the {@code processingDate} parameter and
     * runs to a successful completion, writing the expected number of interest transactions for the
     * canonical scenario.
     */
    @Test
    @DisplayName("Interest job completes successfully end-to-end")
    void jobCompletesSuccessfully() throws Exception {
        arrangeCanonicalScenario();

        JobExecution execution = launchInterestJob();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode())
                .isEqualTo(ExitStatus.COMPLETED.getExitCode());
        assertThat(transactionRepository.count()).isEqualTo(4L);
    }
}
