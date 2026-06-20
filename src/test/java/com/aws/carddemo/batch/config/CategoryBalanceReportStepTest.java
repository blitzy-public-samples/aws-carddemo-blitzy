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

import com.aws.carddemo.domain.TransactionCategoryBalance;
import com.aws.carddemo.repository.TransactionCategoryBalanceRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
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
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Spring Batch integration test for the production {@link CategoryBalanceReportStep} configuration,
 * the Java reproduction of the legacy print-transaction-category-balance job {@code
 * legacy/app/jcl/PRTCATBL.jcl} (which drove an IDCAMS unload via {@code legacy/app/proc/REPROC.prc}
 * and then a DFSORT step). There is intentionally <strong>no</strong> {@code service.batch}
 * counterpart for this job — it is pure reporting infrastructure — so this test exercises the
 * {@code @Configuration} directly through the launched job.
 *
 * <p><strong>What is under test.</strong> {@link CategoryBalanceReportStep} defines the single
 * tasklet job {@code categoryBalanceReportJob} (whose only step is {@code
 * categoryBalanceReportStep}). The tasklet reads every row from {@link
 * TransactionCategoryBalanceRepository} ordered ascending by the composite key {@code
 * Sort.by("id.trancatAcctId", "id.trancatTypeCd", "id.trancatCd")} and writes a fixed 40-character
 * report file {@code TCATBALF.REPT} under the {@code outputDir} job parameter.
 *
 * <p><strong>Parity anchors asserted (Agent Action Plan).</strong>
 *
 * <ul>
 *   <li><b>Fixed record width (&sect;0.6.3, {@code SORTOUT LRECL=40}).</b> Every emitted line is
 *       exactly 40 characters. The production line is assembled at 41 characters (the DFSORT {@code
 *       OUTREC} fields plus a trailing {@code 9X}) and clamped to 40, dropping the 41st trailing
 *       space — so the contract is {@code length()==40}, not 41.
 *   <li><b>Amount rendering (&sect;0.6.1, DFSORT {@code EDIT=(TTTTTTTTT.TT)}).</b> The balance is
 *       rendered as the absolute magnitude with nine zero-padded integer digits, a literal {@code
 *       '.'} and two fraction digits — <em>no sign and no grouping commas</em> (so {@code 0.00}
 *       becomes {@code 000000000.00}). This mask is composed inline in production (it deliberately
 *       does not reuse the signed/comma-bearing report number formatter), so {@link
 *       #formatCategoryAmount(BigDecimal)} replicates it byte-for-byte here.
 *   <li><b>Ascending key order (&sect;0.6.3, {@code SORT
 *       FIELDS=(TRANCAT-ACCT-ID,A,TRANCAT-TYPE-CD,A, TRANCAT-CD,A)}).</b> The report rows appear in
 *       ascending composite-key order, verified both structurally (zero-padded fixed-width prefixes
 *       are non-decreasing) and against the repository's own ordered read.
 * </ul>
 *
 * <p><strong>Data source.</strong> The {@code tran_cat_balance} table is seeded by the Flyway
 * migration {@code V2__seed_reference_data.sql} (50 rows decoded from {@code
 * legacy/app/data/ASCII/tcatbal.txt}; account ids {@code 1..50}, type {@code '01'}, category {@code
 * '0001'}, balance {@code 0.00}). This test performs <strong>no</strong> additional seeding of that
 * table — it asserts the V2 baseline and reads it back through the repository.
 *
 * <p><strong>Wiring.</strong> A full Spring Boot context ({@link SpringBootTest}) runs against an
 * owned Testcontainers PostgreSQL 16 instance bound through {@link ServiceConnection} (which
 * supplies a {@code JdbcConnectionDetails} bean that supersedes the {@code application-test.yml}
 * datasource settings). The {@code test} profile keeps Hibernate {@code ddl-auto=validate} and
 * disables Spring Batch auto-launch, so the job is launched explicitly via {@link
 * JobLauncherTestUtils}. {@code @EnableBatchProcessing} is deliberately absent from production, so
 * the Boot-provided {@link JobLauncher}/{@link JobRepository} are injected directly.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class CategoryBalanceReportStepTest {

  /**
   * Expected number of {@code tran_cat_balance} rows seeded by Flyway {@code
   * V2__seed_reference_data.sql} (one per account id {@code 1..50}). Used as a sanity baseline;
   * this test never mutates that table.
   */
  private static final long EXPECTED_SEED_ROW_COUNT = 50L;

  /** Logical report file name produced by the job (legacy {@code SORTOUT = TCATBALF.REPT}). */
  private static final String REPORT_FILE_NAME = "TCATBALF.REPT";

  /** Exact fixed width, in characters, of every report line (legacy {@code SORTOUT LRECL=40}). */
  private static final int REPORT_LINE_LENGTH = 40;

  /** Width of the zero-padded {@code TRANCAT-ACCT-ID} field at the start of each line. */
  private static final int ACCT_ID_WIDTH = 11;

  /** Width of the {@code TRANCAT-TYPE-CD} field. */
  private static final int TYPE_CD_WIDTH = 2;

  /** Width of the {@code TRANCAT-CD} field. */
  private static final int CAT_CD_WIDTH = 4;

  /** Start offset of the {@code TRANCAT-TYPE-CD} field (after acct-id and its space separator). */
  private static final int TYPE_CD_OFFSET = ACCT_ID_WIDTH + 1;

  /** Start offset of the {@code TRANCAT-CD} field (after the type code and its space separator). */
  private static final int CAT_CD_OFFSET = TYPE_CD_OFFSET + TYPE_CD_WIDTH + 1;

  /** Number of decimal places (cents) in the edited amount mask {@code TTTTTTTTT.TT}. */
  private static final int AMOUNT_SCALE = 2;

  /**
   * Matches the edited-amount region {@code TTTTTTTTT.TT} (nine digits, a dot, two digits) of a
   * report data line. Header/trailer lines, if a future production ever emitted them, would not
   * match, so the matched count equals the number of data rows.
   */
  private static final Pattern AMOUNT_PATTERN = Pattern.compile("\\d{9}\\.\\d{2}");

  /**
   * Owned, JUnit-managed PostgreSQL 16 container. {@link ServiceConnection} reads it and registers
   * the {@code JdbcConnectionDetails} bean that overrides the profile datasource, so the full
   * Spring context (Flyway V1 + V2, JPA {@code validate}) runs against this throwaway database.
   */
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  /** Boot-provided Spring Batch launcher used to run the job under test. */
  @Autowired JobLauncher jobLauncher;

  /** Boot-provided Spring Batch job repository (metadata store) used to wire the test utilities. */
  @Autowired JobRepository jobRepository;

  /** The single-step category-balance-report job defined by {@link CategoryBalanceReportStep}. */
  @Autowired
  @Qualifier("categoryBalanceReportJob")
  Job categoryBalanceReportJob;

  /**
   * Repository over the seeded {@code tran_cat_balance} table; read back to verify report parity.
   */
  @Autowired TransactionCategoryBalanceRepository tcatBalRepository;

  /** Per-test temporary directory supplied to the job as the {@code outputDir} job parameter. */
  @TempDir Path tempDir;

  /**
   * Manually assembled launcher utilities (canonical wiring; {@code @SpringBatchTest} not used).
   */
  private JobLauncherTestUtils jobLauncherTestUtils;

  /**
   * Prepares a clean batch-metadata state and the launch utilities before each test, and asserts
   * the {@code tran_cat_balance} table is at its Flyway V2 baseline.
   *
   * <p>The batch metadata is cleared with {@link JobRepositoryTestUtils#removeJobExecutions()} so a
   * re-run of the same job parameters is not rejected as already complete. {@link
   * JobLauncherTestUtils} is then assembled by hand from the injected {@link JobLauncher}, {@link
   * JobRepository} and the {@code categoryBalanceReportJob}. The reference table is deliberately
   * not seeded or deleted here — the test relies on the Flyway V2 seed — so its row count is
   * asserted as a sanity baseline only.
   */
  @BeforeEach
  void setUp() {
    new JobRepositoryTestUtils(jobRepository).removeJobExecutions();

    jobLauncherTestUtils = new JobLauncherTestUtils();
    jobLauncherTestUtils.setJobLauncher(jobLauncher);
    jobLauncherTestUtils.setJobRepository(jobRepository);
    jobLauncherTestUtils.setJob(categoryBalanceReportJob);

    assertThat(tcatBalRepository.count()).isEqualTo(EXPECTED_SEED_ROW_COUNT);
  }

  /**
   * Launches {@code categoryBalanceReportJob} and verifies that {@code TCATBALF.REPT} is written as
   * fixed 40-character lines, one per {@code tran_cat_balance} row, in ascending composite-key
   * order with COBOL-faithful {@code TTTTTTTTT.TT} amount rendering.
   *
   * @throws Exception if the job launch or report read fails (propagated to fail the test)
   */
  @Test
  void categoryBalanceReport_writes_40_char_lines_in_ascending_key_order() throws Exception {
    // --- launch the job with the temp output directory ----------------------------------------
    JobExecution jobExecution =
        jobLauncherTestUtils.launchJob(
            new JobParametersBuilder()
                .addString("outputDir", tempDir.toString())
                .toJobParameters());

    // --- job + step completed -----------------------------------------------------------------
    assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

    StepExecution stepExecution =
        jobExecution.getStepExecutions().stream()
            .filter(execution -> "categoryBalanceReportStep".equals(execution.getStepName()))
            .findFirst()
            .orElseThrow();
    assertThat(stepExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

    // --- report file exists and is non-empty --------------------------------------------------
    Path rept = tempDir.resolve(REPORT_FILE_NAME);
    assertThat(rept).exists();
    assertThat(Files.size(rept)).isGreaterThan(0L);

    // --- 40-byte fixed width (PRIMARY contract): every line is exactly 40 chars ----------------
    List<String> lines = Files.readAllLines(rept, StandardCharsets.UTF_8);
    assertThat(lines).isNotEmpty();
    assertThat(lines).allSatisfy(line -> assertThat(line).hasSize(REPORT_LINE_LENGTH));

    // --- row coverage: one data line per seeded row -------------------------------------------
    List<String> dataLines =
        lines.stream().filter(line -> AMOUNT_PATTERN.matcher(line).find()).toList();
    assertThat(dataLines).hasSize((int) tcatBalRepository.count());

    // --- amount-format parity (TTTTTTTTT.TT) for every row ------------------------------------
    List<TransactionCategoryBalance> rows =
        tcatBalRepository.findAll(Sort.by("id.trancatAcctId", "id.trancatTypeCd", "id.trancatCd"));
    String fullText = String.join("\n", lines);
    for (TransactionCategoryBalance row : rows) {
      assertThat(fullText).contains(formatCategoryAmount(row.getTranCatBal()));
    }

    // --- ascending composite-key order --------------------------------------------------------
    // Expected order comes from the repository read with the same ascending Sort the production
    // uses; each key is rebuilt in the report's fixed-width shape (acct-id zero-padded to 11, type
    // code to 2, category code to 4) so it can be compared to the parsed report key.
    List<String> expectedKeys =
        rows.stream()
            .map(
                row ->
                    String.format("%0" + ACCT_ID_WIDTH + "d", row.getId().getTrancatAcctId())
                        + padRightField(row.getId().getTrancatTypeCd(), TYPE_CD_WIDTH)
                        + padRightField(row.getId().getTrancatCd(), CAT_CD_WIDTH))
            .toList();

    // The production line interleaves single-space separators, so the key fields are parsed at
    // their fixed offsets (acct-id [0,11), type code [12,14), category code [15,19)) and
    // concatenated into the same 17-character clean key.
    List<String> reportKeys =
        dataLines.stream()
            .map(
                line ->
                    line.substring(0, ACCT_ID_WIDTH)
                        + line.substring(TYPE_CD_OFFSET, TYPE_CD_OFFSET + TYPE_CD_WIDTH)
                        + line.substring(CAT_CD_OFFSET, CAT_CD_OFFSET + CAT_CD_WIDTH))
            .toList();

    // Same rows, same ascending order as the repository read: proves count, content and order.
    assertThat(reportKeys).containsExactlyElementsOf(expectedKeys);
    // All key fields are fixed-width and zero-padded, so lexicographic order == numeric key order.
    assertThat(reportKeys).isSorted();
    // The leading 11-char account-id prefixes are non-decreasing down the file.
    List<String> acctIdPrefixes =
        dataLines.stream().map(line -> line.substring(0, ACCT_ID_WIDTH)).toList();
    assertThat(acctIdPrefixes).isSorted();
  }

  /**
   * Replicates the production inline DFSORT mask {@code EDIT=(TTTTTTTTT.TT)}: nine zero-padded
   * integer digits, a literal {@code '.'}, and two fraction digits, rendering the absolute
   * magnitude with no sign and no grouping commas (for example {@code 0.00} renders as {@code
   * 000000000.00}).
   *
   * <p>The value is truncated (never rounded up) to two decimals with {@link RoundingMode#DOWN},
   * matching COBOL fixed-point assignment, before its whole-unit and cents parts are zero-padded.
   *
   * @param bal the category balance to format
   * @return the 12-character edited amount string
   */
  private static String formatCategoryAmount(BigDecimal bal) {
    BigDecimal abs = bal.abs().setScale(AMOUNT_SCALE, RoundingMode.DOWN);
    String plain = abs.toPlainString();
    int dot = plain.indexOf('.');
    String intPadded = String.format("%09d", Long.parseLong(plain.substring(0, dot)));
    return intPadded + "." + plain.substring(dot + 1);
  }

  /**
   * Reproduces a COBOL {@code PIC X(width)} {@code MOVE} for a key field: left-justify and
   * right-pad with spaces, truncating on the right when the value is longer than {@code width}.
   * This matches the production {@code CobolStringUtils.fixedWidth} used to assemble the type-code
   * and category-code fields, so a rebuilt expected key lines up with the parsed report key.
   *
   * @param value the source text (treated as empty when {@code null})
   * @param width the fixed field width
   * @return a string of exactly {@code width} characters
   */
  private static String padRightField(String value, int width) {
    String source = (value == null) ? "" : value;
    if (source.length() >= width) {
      return source.substring(0, width);
    }
    return source + " ".repeat(width - source.length());
  }
}
