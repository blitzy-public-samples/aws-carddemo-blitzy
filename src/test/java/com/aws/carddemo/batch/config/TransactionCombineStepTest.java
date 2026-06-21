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
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.repository.TransactionRepository;
import java.io.InputStream;
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
 * Spring Batch integration test for the production {@link TransactionCombineStep} configuration —
 * the Java port of the legacy combine-transactions job {@code legacy/app/jcl/COMBTRAN.jcl} (with
 * IDCAMS control {@code legacy/app/ctl/REPROCT.ctl}, invoked via {@code
 * legacy/app/proc/REPROC.prc}). There is intentionally <strong>no</strong> service collaborator:
 * the legacy job is pure DFSORT/IDCAMS infrastructure, so {@link TransactionCombineStep}
 * orchestrates the file work directly and this test exercises it end to end.
 *
 * <p>The job under test ({@code transactionCombineJob}) has two steps run in sequence:
 *
 * <ol>
 *   <li>{@code transactionCombineSortStep} &mdash; reads every transaction ascending by {@code
 *       TRAN-ID} via {@link TransactionRepository#findAllByOrderByTranIdAsc()} and writes each one
 *       as a 350-byte fixed-width {@code CVTRA05Y} record (newline-terminated) into {@code
 *       TRANSACT.COMBINED}, reproducing {@code STEP05R} ({@code SORT FIELDS=(TRAN-ID,A)});
 *   <li>{@code transactionCombineReproStep} &mdash; copies {@code TRANSACT.COMBINED} verbatim onto
 *       {@code TRANSACT.VSAM.KSDS}, reproducing the {@code STEP10} IDCAMS {@code REPRO} load of the
 *       combined file into the transaction master.
 * </ol>
 *
 * <p><strong>Parity points pinned by this test.</strong>
 *
 * <ul>
 *   <li><em>Sort-order parity</em> (AAP &sect;0.6.3) — the combine output is strictly ascending by
 *       {@code TRAN-ID}. Three transactions are seeded in deliberately non-ascending id order so a
 *       passing assertion can only succeed if the step truly sorts. Because {@code TRAN-ID} is the
 *       primary key there are no ties (the legacy {@code SORT} specified no {@code EQUALS}), so the
 *       order is total and deterministic.
 *   <li><em>Fixed-width layout</em> — every emitted record is exactly 350 characters, matching the
 *       {@code CVTRA05Y} record length.
 *   <li><em>Decimal / overpunch parity</em> (AAP &sect;0.6.1) — the signed {@code TRAN-AMT PIC
 *       S9(09)V99} occupies columns 133-143 and carries its sign in the trailing overpunch byte
 *       (positive {@code {ABCDEFGHI}, negative {@code }JKLMNOPQR}), truncated (never rounded) at
 *       scale 2.
 *   <li><em>Two-step sequence parity</em> — both steps complete and the sort step starts no later
 *       than the repro step, proving the {@code .start(sort).next(repro)} ordering.
 *   <li><em>IDCAMS REPRO parity</em> — the {@code TRANSACT.VSAM.KSDS} load target is byte-identical
 *       to the combined sort output, proving the repro step copied verbatim after the sort.
 * </ul>
 *
 * <p><strong>Wiring.</strong> This is the canonical {@code JobLauncherTestUtils} pattern with no
 * {@code @SpringBatchTest}: the full application context is loaded ({@link SpringBootTest}), the
 * {@code transactionCombineJob} is selected with {@link Qualifier} (the context defines many {@code
 * Job} beans), and the Boot-provided {@link JobLauncher} / {@link JobRepository} drive the launch.
 * A dedicated, JUnit-managed {@link PostgreSQLContainer} bound through {@link ServiceConnection}
 * supplies a real PostgreSQL 16 engine (Flyway applies the VSAM-faithful schema and reference
 * seed), so the test is local-only with no running mainframe (AAP &sect;0.6.7). Each output file is
 * written under a fresh {@link TempDir} via the {@code outputDir} job parameter.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class TransactionCombineStepTest {

  /** Bean name of the sort step under test (legacy {@code COMBTRAN} {@code STEP05R}). */
  private static final String SORT_STEP = "transactionCombineSortStep";

  /**
   * Bean name of the repro step under test (legacy {@code COMBTRAN} {@code STEP10} IDCAMS REPRO).
   */
  private static final String REPRO_STEP = "transactionCombineReproStep";

  /** Combine output file name (legacy {@code TRANSACT.COMBINED}). */
  private static final String COMBINED_FILE = "TRANSACT.COMBINED";

  /** Transaction-master load target file name (legacy {@code TRANSACT.VSAM.KSDS}). */
  private static final String KSDS_FILE = "TRANSACT.VSAM.KSDS";

  /** Exact fixed-width length, in characters, of a {@code CVTRA05Y} transaction record. */
  private static final int RECORD_LENGTH = 350;

  /**
   * Zero-based start index of the 11-character {@code TRAN-AMT} field in the 350-byte record (COBOL
   * columns 133-143).
   */
  private static final int AMOUNT_BEGIN = 132;

  /** Zero-based end index (exclusive) of the {@code TRAN-AMT} field. */
  private static final int AMOUNT_END = 143;

  /** Smallest transaction id; inserted last but must sort first. */
  private static final String TRAN_ID_1 = "0000000000000001";

  /** Middle transaction id; inserted second and must sort second. */
  private static final String TRAN_ID_2 = "0000000000000002";

  /** Largest transaction id; inserted first but must sort last. */
  private static final String TRAN_ID_3 = "0000000000000003";

  /**
   * Scenario-specific golden file for this exact three-row case, shipped at {@code
   * src/test/resources/golden/sorted/transaction-combine-three-row.dat}. It holds the byte-exact
   * combine output for the three seeded transactions — 350 bytes per record, newline-terminated, in
   * ascending {@code TRAN-ID} order with the signed-overpunch {@code TRAN-AMT}s — and is compared
   * byte-for-byte against the live job output below (a hard {@link
   * org.junit.jupiter.api.Assertions#assertNotNull} guards it so a deleted/renamed fixture fails
   * loudly rather than silently eroding parity coverage). This three-row case is distinct from the
   * committed full 300-row {@code golden/sorted/dailytran.combined-sorted.dat} scenario, which is
   * exercised separately by {@link TransactionCombineSortParityTest}.
   */
  private static final String GOLDEN_RESOURCE = "/golden/sorted/transaction-combine-three-row.dat";

  /**
   * Dedicated PostgreSQL 16 container, JUnit-managed by {@link Testcontainers} and bound to the
   * Spring data source through {@link ServiceConnection} (supersedes the {@code
   * application-test.yml} datasource settings). Started once for this class and reaped at the end
   * of the run.
   */
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  /** Boot-provided Spring Batch job launcher. */
  @Autowired private JobLauncher jobLauncher;

  /** Boot-provided Spring Batch job repository (also used to reset batch metadata per test). */
  @Autowired private JobRepository jobRepository;

  /** The combine job under test, disambiguated from the other {@code Job} beans by name. */
  @Autowired
  @Qualifier("transactionCombineJob")
  private Job transactionCombineJob;

  /** Repository used to seed and clear the {@code transaction} table that the job reads. */
  @Autowired private TransactionRepository transactionRepository;

  /** Fresh per-test temporary directory; passed to the job as the {@code outputDir} parameter. */
  @TempDir Path tempDir;

  /** Manually wired launcher helper (no {@code @SpringBatchTest}); rebuilt before each test. */
  private JobLauncherTestUtils jobLauncherTestUtils;

  /**
   * Resets Spring Batch metadata, wires {@link JobLauncherTestUtils} manually, and seeds three
   * transactions in deliberately non-ascending id order (3, 2, 1 &mdash; saved as A, C, B) so the
   * ascending-order assertion is meaningful.
   */
  @BeforeEach
  void setUp() {
    new JobRepositoryTestUtils(jobRepository).removeJobExecutions();

    jobLauncherTestUtils = new JobLauncherTestUtils();
    jobLauncherTestUtils.setJobLauncher(jobLauncher);
    jobLauncherTestUtils.setJobRepository(jobRepository);
    jobLauncherTestUtils.setJob(transactionCombineJob);

    transactionRepository.deleteAll();
    Transaction txA = newTransaction(TRAN_ID_3, new BigDecimal("504.77"));
    Transaction txC = newTransaction(TRAN_ID_2, new BigDecimal("67.88"));
    Transaction txB = newTransaction(TRAN_ID_1, new BigDecimal("-919.00"));
    transactionRepository.save(txA);
    transactionRepository.save(txC);
    transactionRepository.save(txB);
  }

  /**
   * Launches the combine job and verifies sort-order, fixed-width layout, signed-amount overpunch,
   * two-step sequencing, and the byte-identical IDCAMS REPRO copy.
   *
   * @throws Exception if launching the job or reading an output file fails
   */
  @Test
  void combineJob_sorts_ascending_then_repros_byte_identical_copy() throws Exception {
    JobExecution execution =
        jobLauncherTestUtils.launchJob(
            new JobParametersBuilder()
                .addString("outputDir", tempDir.toString())
                .toJobParameters());

    // (1) The whole job completed successfully.
    assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

    // (2) Two-step sequence parity: both steps present and COMPLETED, and the sort step started no
    // later than the repro step (proves the .start(sort).next(repro) ordering).
    assertThat(execution.getStepExecutions()).hasSize(2);
    StepExecution sortStep = stepNamed(execution, SORT_STEP);
    StepExecution reproStep = stepNamed(execution, REPRO_STEP);
    assertThat(sortStep.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    assertThat(reproStep.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    assertThat(sortStep.getStartTime()).isBeforeOrEqualTo(reproStep.getStartTime());

    // Both output files were produced.
    Path combined = tempDir.resolve(COMBINED_FILE);
    Path ksds = tempDir.resolve(KSDS_FILE);
    assertThat(combined).exists();
    assertThat(ksds).exists();

    // (3) Fixed-width layout: exactly three records, each exactly 350 characters.
    List<String> lines = Files.readAllLines(combined, StandardCharsets.UTF_8);
    assertThat(lines).hasSize(3);
    for (String line : lines) {
      assertThat(line).hasSize(RECORD_LENGTH);
    }

    // (4) Sort-order parity (AAP 0.6.3): TRAN-ID (columns 1-16) strictly ascending. TRAN-ID is the
    // primary key, so there are no ties (the legacy SORT specified no EQUALS) and the order is
    // deterministic regardless of the deliberately scrambled insertion order.
    List<String> sortedIds = lines.stream().map(line -> line.substring(0, 16)).toList();
    assertThat(sortedIds).containsExactly(TRAN_ID_1, TRAN_ID_2, TRAN_ID_3);

    // (5) Signed-amount overpunch parity (AAP 0.6.1): TRAN-AMT occupies columns 133-143 (11 chars),
    // truncated at scale 2 with the sign carried by the trailing overpunch byte.
    //   id ...0001 -> -919.00 -> magnitude 91900, last digit 0 negative -> '}' -> "0000009190}"
    //   id ...0002 ->   67.88 -> magnitude  6788, last digit 8 positive -> 'H' -> "0000000678H"
    //   id ...0003 ->  504.77 -> magnitude 50477, last digit 7 positive -> 'G' -> "0000005047G"
    assertThat(lines.get(0).substring(AMOUNT_BEGIN, AMOUNT_END)).isEqualTo("0000009190}");
    assertThat(lines.get(1).substring(AMOUNT_BEGIN, AMOUNT_END)).isEqualTo("0000000678H");
    assertThat(lines.get(2).substring(AMOUNT_BEGIN, AMOUNT_END)).isEqualTo("0000005047G");

    // (6) IDCAMS REPRO parity: the repro step copies the combined file verbatim, so the KSDS load
    // target is byte-identical to the sorted combine output (and is produced after the sort).
    assertThat(Files.readAllBytes(ksds)).isEqualTo(Files.readAllBytes(combined));

    // (7) Golden-file parity: the scenario-specific golden for this exact three-row case is shipped
    // at GOLDEN_RESOURCE and is compared byte-for-byte against the live combine output. The fixture
    // is hard-asserted present (assertNotNull) so a deleted or renamed golden fails the test loudly
    // and can never silently erode parity coverage (matching the StatementFileWriterTest
    // hard-assert
    // convention rather than a silent skip).
    try (InputStream golden = getClass().getResourceAsStream(GOLDEN_RESOURCE)) {
      assertNotNull(golden, "golden fixture must ship on the classpath: " + GOLDEN_RESOURCE);
      assertThat(Files.readAllBytes(combined)).isEqualTo(golden.readAllBytes());
    }
  }

  /**
   * Builds a fully-populated {@link Transaction} for the {@code transaction} table (every column is
   * {@code NOT NULL} in {@code V1__schema.sql}). The transaction type {@code "01"} and category
   * {@code "0001"} are reference values seeded by {@code V2__seed_reference_data.sql}.
   *
   * @param tranId the 16-character transaction id (primary key)
   * @param tranAmt the signed transaction amount
   * @return a transaction populated for persistence
   */
  private static Transaction newTransaction(String tranId, BigDecimal tranAmt) {
    Transaction tran = new Transaction();
    tran.setTranId(tranId);
    tran.setTranTypeCd("01");
    tran.setTranCatCd("0001");
    tran.setTranSource("POS");
    tran.setTranDesc("Combine job parity fixture transaction record");
    tran.setTranAmt(tranAmt);
    tran.setTranMerchantId(123456789L);
    tran.setTranMerchantName("ACME MERCHANT");
    tran.setTranMerchantCity("SEATTLE");
    tran.setTranMerchantZip("98101");
    tran.setTranCardNum("4859452612877065");
    tran.setTranOrigTs("2022-07-18-12.34.56.123456");
    tran.setTranProcTs("2022-07-18-12.34.56.654321");
    return tran;
  }

  /**
   * Returns the single {@link StepExecution} with the given step name, failing the test if it is
   * absent.
   *
   * @param jobExecution the completed job execution
   * @param stepName the bean/step name to locate
   * @return the matching step execution
   */
  private static StepExecution stepNamed(JobExecution jobExecution, String stepName) {
    return jobExecution.getStepExecutions().stream()
        .filter(step -> stepName.equals(step.getStepName()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Expected step execution not found: " + stepName));
  }
}
