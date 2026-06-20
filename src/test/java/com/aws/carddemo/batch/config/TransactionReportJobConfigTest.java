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

import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.TransactionRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
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
 * Spring Batch integration test for {@link TransactionReportJobConfig} — the configuration that
 * reproduces the legacy transaction-detail-report job {@code legacy/app/jcl/TRANREPT.jcl} together
 * with its PROC {@code legacy/app/proc/TRANREPT.prc} and the report driver {@code STEP10R EXEC
 * PGM=CBTRN03C} (behavioral spec {@code legacy/app/cbl/CBTRN03C.cbl}; report layout copybook {@code
 * legacy/app/cpy/CVTRA07Y.cpy}).
 *
 * <p>Unlike the pure-Mockito {@code TransactionReportServiceTest}, this test launches the
 * <em>assembled</em> job ({@code transactionReportJob}) through a full Spring context against a
 * real Testcontainers PostgreSQL 16 instance seeded by the production Flyway migrations ({@code
 * V1__schema.sql} + {@code V2__seed_reference_data.sql}), exactly as mandated by the Agent Action
 * Plan local-only validation strategy (AAP &sect;0.6.7). It therefore verifies the
 * integration-level contract that the unit test cannot: the {@code @StepScope} tasklet's late-bound
 * {@code JobParameters} ({@code startDate} / {@code endDate} / {@code outputDir}), the legacy
 * default date window applied when those parameters are absent, and the byte-faithful {@code
 * LRECL=133} fixed-width record that {@link com.aws.carddemo.batch.writer.ReportFileWriter} writes
 * for the {@code TRANREPT} DD.
 *
 * <p><strong>Container wiring.</strong> A JUnit-managed {@link Testcontainers}/{@link Container}
 * {@link PostgreSQLContainer} is bound to the Spring data source with {@link ServiceConnection},
 * which registers a {@code JdbcConnectionDetails} bean that supersedes the {@code
 * spring.datasource.*} settings of {@code application-test.yml}. Because
 * {@code @ActiveProfiles("test")} keeps Flyway authoritative ({@code ddl-auto=validate}) and
 * disables batch auto-launch ({@code spring.batch.job.enabled=false}), each run starts from the
 * exact VSAM-faithful schema and the jobs are launched explicitly here via {@link
 * JobLauncherTestUtils}.
 *
 * <p><strong>Deterministic fixture.</strong> The {@code transaction} master (the {@code TRANSACT}
 * working set) is seeded directly with a controlled, single-card set: two transactions whose {@code
 * tranProcTs} falls <em>inside</em> the report window and one whose processing date falls
 * <em>outside</em> it, proving the inclusive date-range filter (the Java counterpart of the JCL
 * {@code SORT INCLUDE COND}). The card cross-reference required by the {@code 1500-A-LOOKUP-XREF}
 * lookup is loaded from the legacy fixture via {@link
 * FixtureSeeder#seedCardXrefs(CardXrefRepository)} (which lives in this package), and the
 * transaction-type / transaction-category descriptions are supplied by the Flyway reference seed,
 * so all three FATAL {@code CBTRN03C} lookups resolve and the step completes normally.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class TransactionReportJobConfigTest {

  /**
   * Own ephemeral PostgreSQL 16 container, bound to the Spring data source by {@link
   * ServiceConnection}. JUnit's {@link Testcontainers} extension starts this {@code static}
   * container once before the class and stops it after, which is correct for this single
   * self-contained {@code @SpringBootTest}.
   */
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  /** The fixed report record width ({@code FD-REPTFILE-REC PIC X(133)}, DD {@code LRECL=133}). */
  private static final int REPORT_LINE_WIDTH = 133;

  /** Logical output file name resolved by the tasklet; mirrors the legacy {@code TRANREPT} DD. */
  private static final String REPORT_FILE_NAME = "TRANREPT";

  /**
   * Card number used by every seeded transaction. It is the first record of the legacy card-xref
   * fixture ({@code src/test/resources/fixtures/ascii/cardxref.txt}), so {@link
   * FixtureSeeder#seedCardXrefs(CardXrefRepository)} guarantees a matching {@code card_xref} row
   * for the {@code 1500-A-LOOKUP-XREF} lookup.
   */
  private static final String CARD_NUM = "0500024453765740";

  /**
   * {@code TRAN-TYPE-CD} seeded by {@code V2__seed_reference_data.sql} ({@code '01' = Purchase}).
   */
  private static final String TYPE_CD = "01";

  /**
   * {@code TRAN-CAT-CD} seeded by {@code V2__seed_reference_data.sql} ({@code ('01','0001') =
   * Regular Sales Draft}).
   */
  private static final String CAT_CD = "0001";

  /** First in-range transaction id; its detail line must appear in the report. */
  private static final String IN_RANGE_TXN_1 = "TXNRPT0000000001";

  /** Second in-range transaction id; its detail line must appear in the report. */
  private static final String IN_RANGE_TXN_2 = "TXNRPT0000000002";

  /** Out-of-range transaction id; filtered out, so it must NOT appear in any report line. */
  private static final String OUT_OF_RANGE_TXN = "TXNRPT0000000999";

  /** {@code REPT-SHORT-NAME 'DALYREPT'} — uniquely marks the report name-header line. */
  private static final String NAME_HEADER_MARKER = "DALYREPT";

  /** {@code REPT-LONG-NAME 'Daily Transaction Report'} — the report title literal. */
  private static final String REPORT_TITLE = "Daily Transaction Report";

  /** {@code REPORT-PAGE-TOTALS} label emitted by {@code 1110-WRITE-PAGE-TOTALS}. */
  private static final String PAGE_TOTAL_LABEL = "Page Total";

  /** {@code REPORT-GRAND-TOTALS} label emitted by {@code 1110-WRITE-GRAND-TOTALS}. */
  private static final String GRAND_TOTAL_LABEL = "Grand Total";

  /** Known transaction amount ({@code S9(09)V99}, scale 2) so totals are deterministic. */
  private static final BigDecimal TXN_AMT = new BigDecimal("125.50");

  /**
   * Boot-provided Spring Batch job launcher (auto-configured; no {@code @EnableBatchProcessing}).
   */
  @Autowired private JobLauncher jobLauncher;

  /** Boot-provided Spring Batch job repository. */
  @Autowired private JobRepository jobRepository;

  /** The job under test, selected by bean name to avoid ambiguity with the sibling batch jobs. */
  @Autowired
  @Qualifier("transactionReportJob")
  private Job transactionReportJob;

  /** Transaction master ({@code TRANSACT}); seeded with the controlled working set. */
  @Autowired private TransactionRepository transactionRepository;

  /** Card cross-reference store ({@code CARDXREF}); seeded from the legacy fixture. */
  @Autowired private CardXrefRepository cardXrefRepository;

  /** Per-test temporary directory; the {@code outputDir} job parameter and report destination. */
  @TempDir Path tempDir;

  /** Manually assembled launcher utility (canonical wiring; no {@code @SpringBatchTest}). */
  private JobLauncherTestUtils jobLauncherTestUtils;

  @BeforeEach
  void setUp() {
    // Clear any prior batch metadata so the job can be relaunched deterministically per test.
    new JobRepositoryTestUtils(jobRepository).removeJobExecutions();

    // Canonical manual wiring of JobLauncherTestUtils (the project avoids @SpringBatchTest).
    jobLauncherTestUtils = new JobLauncherTestUtils();
    jobLauncherTestUtils.setJobLauncher(jobLauncher);
    jobLauncherTestUtils.setJobRepository(jobRepository);
    jobLauncherTestUtils.setJob(transactionReportJob);

    // Seed the card cross-reference required by the (FATAL) 1500-A-LOOKUP-XREF lookup. Cleared
    // first so the per-test @BeforeEach is idempotent (the full-context test is not transactional).
    cardXrefRepository.deleteAll();
    FixtureSeeder.seedCardXrefs(cardXrefRepository);

    // Seed a controlled, single-card transaction working set: two in-range rows and one
    // out-of-range
    // row. tran_type '01' and (type '01', cat '0001') already exist via the Flyway reference seed,
    // so
    // the 1500-B / 1500-C lookups resolve. tranProcTs(1:10) drives the inclusive date-range filter.
    transactionRepository.deleteAll();
    transactionRepository.save(newTransaction(IN_RANGE_TXN_1, "2022-06-10 19:27:53.000000"));
    transactionRepository.save(newTransaction(IN_RANGE_TXN_2, "2022-06-11 08:15:00.000000"));
    transactionRepository.save(newTransaction(OUT_OF_RANGE_TXN, "2022-12-01 00:00:00.000000"));
  }

  @Test
  void transactionReport_with_explicit_dates_writes_133_byte_records() throws Exception {
    JobExecution jobExecution =
        jobLauncherTestUtils.launchJob(
            new JobParametersBuilder()
                .addString("startDate", "2022-01-01")
                .addString("endDate", "2022-07-06")
                .addString("outputDir", tempDir.toString())
                .toJobParameters());

    // The job and its single tasklet step complete normally (no FATAL lookup abend).
    assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    assertThat(jobExecution.getStepExecutions())
        .filteredOn(step -> "transactionReportStep".equals(step.getStepName()))
        .singleElement()
        .extracting(StepExecution::getStatus)
        .isEqualTo(BatchStatus.COMPLETED);

    // The report is written to the fixed TRANREPT file under the outputDir job parameter.
    Path report = tempDir.resolve(REPORT_FILE_NAME);
    assertThat(report).exists();
    List<String> lines = Files.readAllLines(report, StandardCharsets.UTF_8);
    assertThat(lines).isNotEmpty();

    // LRECL parity (PRIMARY): every emitted record is exactly 133 characters wide.
    assertThat(lines).allSatisfy(line -> assertThat(line).hasSize(REPORT_LINE_WIDTH));

    // Structural spot-checks tied to CBTRN03C / CVTRA07Y literals.
    assertThat(lines).anyMatch(line -> line.contains(NAME_HEADER_MARKER));
    assertThat(lines).anyMatch(line -> line.contains(REPORT_TITLE));
    assertThat(lines).anyMatch(line -> line.contains(PAGE_TOTAL_LABEL));
    assertThat(lines).anyMatch(line -> line.contains(GRAND_TOTAL_LABEL));

    // Date-range filter parity: the two in-range transactions are reported, the out-of-range one is
    // not (its processing date 2022-12-01 is after the inclusive upper bound).
    assertThat(lines).anyMatch(line -> line.contains(IN_RANGE_TXN_1));
    assertThat(lines).anyMatch(line -> line.contains(IN_RANGE_TXN_2));
    assertThat(lines).noneMatch(line -> line.contains(OUT_OF_RANGE_TXN));
  }

  @Test
  void transactionReport_uses_default_date_range_when_parameters_absent() throws Exception {
    // Launch with ONLY outputDir: the config must apply its legacy default window (2022-01-01 ..
    // 2022-07-06) rather than failing on the missing startDate/endDate parameters.
    JobExecution jobExecution =
        jobLauncherTestUtils.launchJob(
            new JobParametersBuilder()
                .addString("outputDir", tempDir.toString())
                .toJobParameters());

    assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    assertThat(jobExecution.getStepExecutions())
        .filteredOn(step -> "transactionReportStep".equals(step.getStepName()))
        .singleElement()
        .extracting(StepExecution::getStatus)
        .isEqualTo(BatchStatus.COMPLETED);

    Path report = tempDir.resolve(REPORT_FILE_NAME);
    assertThat(report).exists();
    List<String> lines = Files.readAllLines(report, StandardCharsets.UTF_8);
    assertThat(lines).isNotEmpty();

    // The defaults are applied: 133-byte records are produced and the in-range transactions (which
    // also fall inside the default window) appear, proving the report ran against the default
    // range.
    assertThat(lines).allSatisfy(line -> assertThat(line).hasSize(REPORT_LINE_WIDTH));
    assertThat(lines).anyMatch(line -> line.contains(NAME_HEADER_MARKER));
    assertThat(lines).anyMatch(line -> line.contains(IN_RANGE_TXN_1));
    assertThat(lines).noneMatch(line -> line.contains(OUT_OF_RANGE_TXN));
  }

  /**
   * Builds a fully-populated {@link Transaction} for the controlled working set. Every column is
   * {@code NOT NULL} in {@code V1__schema.sql}, so all fields are assigned non-null values; only
   * the id and processing timestamp vary between rows. The amount is a scale-2 {@link BigDecimal}
   * (never {@code float}/{@code double}, AAP &sect;0.6.1).
   *
   * @param tranId the 16-character transaction id (primary key)
   * @param procTs the 26-character processing timestamp whose first 10 characters drive the
   *     inclusive date-range filter
   * @return a transaction on {@link #CARD_NUM} with type {@link #TYPE_CD} and category {@link
   *     #CAT_CD}
   */
  private Transaction newTransaction(String tranId, String procTs) {
    Transaction tx = new Transaction();
    tx.setTranId(tranId);
    tx.setTranTypeCd(TYPE_CD);
    tx.setTranCatCd(CAT_CD);
    tx.setTranSource("POS");
    tx.setTranDesc("TEST PURCHASE");
    tx.setTranAmt(TXN_AMT);
    tx.setTranMerchantId(123456789L);
    tx.setTranMerchantName("TEST MERCHANT");
    tx.setTranMerchantCity("SEATTLE");
    tx.setTranMerchantZip("98101");
    tx.setTranCardNum(CARD_NUM);
    tx.setTranOrigTs(procTs);
    tx.setTranProcTs(procTs);
    return tx;
  }
}
