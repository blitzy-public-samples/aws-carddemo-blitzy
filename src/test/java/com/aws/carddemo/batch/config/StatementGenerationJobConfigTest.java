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

import com.aws.carddemo.batch.writer.HtmlStatementWriter;
import com.aws.carddemo.batch.writer.StatementFileWriter;
import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.CardRepository;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.CustomerRepository;
import com.aws.carddemo.repository.TransactionRepository;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
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
 * Spring Batch integration test for the production {@code
 * com.aws.carddemo.batch.config.StatementGenerationJobConfig}, the Java/Spring translation of the
 * legacy statement-generation JCL {@code legacy/app/jcl/CREASTMT.JCL} STEP040 ({@code EXEC
 * PGM=CBSTM03A}; record I/O delegated to the subroutine {@code CBSTM03B}). The job under test
 * ({@code statementGenerationJob}) runs a single {@code @StepScope} tasklet ({@code
 * statementGenerationStep}) that resolves the {@code outputDir} job parameter, opens a {@link
 * StatementFileWriter} (DD {@code STMTFILE}, {@code LRECL=80}) and a {@link HtmlStatementWriter}
 * (DD {@code HTMLFILE}, {@code LRECL=100}), and invokes {@code
 * StatementGenerationService.run(stmtSink, htmlSink)}.
 *
 * <p>This is the full-context, end-to-end counterpart to the pure-Mockito {@code
 * StatementGenerationServiceTest}: it boots the application with {@link SpringBootTest}, persists
 * real rows into a real PostgreSQL engine through Spring Data JPA, launches the actual Spring Batch
 * job, and asserts the two physical files the job emits. It exists to prove the wiring +
 * persistence + file-production path as a whole, and to pin the fixed-width record contract that
 * golden-file parity depends on (Agent Action Plan &sect;0.6.7).
 *
 * <h2>Database wiring (canonical; no {@code @SpringBatchTest})</h2>
 *
 * <p>A {@link Testcontainers}-managed {@link PostgreSQLContainer} is bound to the application data
 * source by {@link ServiceConnection}, which registers a {@code JdbcConnectionDetails} bean that
 * <em>supersedes</em> the {@code spring.datasource.*} settings of {@code application-test.yml}
 * (matching the established repository-slice convention). The {@code test} profile keeps Flyway
 * authoritative ({@code V1__schema.sql} + {@code V2__seed_reference_data.sql}) with Hibernate
 * {@code ddl-auto=validate}, disables batch auto-launch ({@code spring.batch.job.enabled=false}),
 * and creates the Spring Batch metadata tables ({@code
 * spring.batch.jdbc.initialize-schema=always}). The job is launched explicitly through a manually
 * built {@link JobLauncherTestUtils}. The test is intentionally <strong>not</strong>
 * {@code @Transactional}: the batch step runs in its own transaction(s) and must observe committed
 * input data, so the seeded rows are committed and each method starts by clearing them for
 * idempotency.
 *
 * <h2>Input scenario (golden-backed, deterministic)</h2>
 *
 * <p>The five master / transaction stores are seeded from the byte-exact legacy ASCII fixtures via
 * {@link FixtureSeeder}. Because there is <strong>no</strong> legacy {@code TRANSACT} fixture in
 * the repository, the statement line items are produced from a <em>controlled</em> set of three
 * {@link Transaction} rows seeded directly (the deterministic option called out in the file plan).
 * These three transactions are the exact set documented by {@code
 * src/test/resources/golden/statements/README.md} for card {@code 0500024453765740} (customer
 * {@code 000000050}, account {@code 00000000050}): {@code 0010203040506070} {@code "POS PURCHASE -
 * GROCERY MART"} {@code +123.45}, {@code 0010203040506071} {@code "ONLINE PURCHASE - BOOKSTORE"}
 * {@code +67.89}, and {@code 0010203040506072} {@code "PAYMENT - THANK YOU"} {@code -50.00} (total
 * expense {@code +141.34}). Seeding exactly that set makes account {@code 00000000050}'s rendered
 * statement block reproduce the committed golden files byte-for-byte, enabling the (secondary,
 * guarded) golden parity assertion below.
 *
 * <h2>What is asserted</h2>
 *
 * <ul>
 *   <li><b>Job + step success</b> &mdash; the job and {@code statementGenerationStep} both reach
 *       {@link BatchStatus#COMPLETED}. (A missing customer/account on any cross-reference read, or
 *       an empty transaction table, would abend into {@code FAILED}, per AAP &sect;0.6.6.)
 *   <li><b>Fixed-width parity (PRIMARY)</b> &mdash; every {@code STMTFILE} record is exactly
 *       {@value StatementFileWriter#LRECL} characters and every {@code HTMLFILE} record is exactly
 *       {@value HtmlStatementWriter#LRECL} characters ({@code RECFM=FB}; trailing spaces are
 *       significant).
 *   <li><b>Statement structure + seeded line items</b> &mdash; the start/end banners, the seeded
 *       account, the three transaction descriptions, the expense total, and the HTML document
 *       envelope are all present.
 *   <li><b>Golden parity (SECONDARY, guarded)</b> &mdash; when the classpath golden block exists,
 *       the produced output is asserted to contain the byte-exact golden statement block for
 *       account {@code 00000000050}; if the golden resource is absent the structural assertions
 *       stand alone (the test never fails merely because a golden file has not been authored).
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class StatementGenerationJobConfigTest {

  /**
   * Throwaway PostgreSQL 16 container started by the {@link Testcontainers} JUnit extension and
   * bound to the application data source by {@link ServiceConnection}; its {@code
   * JdbcConnectionDetails} supersede the {@code application-test.yml} datasource keys so no second
   * (JDBC-URL) container is created.
   */
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  /**
   * Card number of the golden scenario; all seeded transactions key to it (account 00000000050).
   */
  private static final String GOLDEN_CARD_NUM = "0500024453765740";

  /** Eleven-digit account id of the golden scenario (the self-consistent fixture triple). */
  private static final String GOLDEN_ACCOUNT_ID = "00000000050";

  /** Classpath location of the expected plain-text statement block (LRECL 80). */
  private static final String GOLDEN_TXT = "/golden/statements/acct-00000000050.statement.txt";

  /** Classpath location of the expected HTML statement block (LRECL 100). */
  private static final String GOLDEN_HTML = "/golden/statements/acct-00000000050.statement.html";

  /** Twenty-six-character ISO-style timestamp filler for the non-rendered timestamp fields. */
  private static final String TS_FILLER = "2024-01-15-10.30.00.000000";

  /** The three controlled transaction descriptions rendered on account 00000000050's statement. */
  private static final List<String> SEEDED_TRAN_DESCS =
      List.of("POS PURCHASE - GROCERY MART", "ONLINE PURCHASE - BOOKSTORE", "PAYMENT - THANK YOU");

  @Autowired private JobLauncher jobLauncher;
  @Autowired private JobRepository jobRepository;

  @Autowired
  @Qualifier("statementGenerationJob")
  private Job statementGenerationJob;

  @Autowired private CustomerRepository customerRepository;
  @Autowired private AccountRepository accountRepository;
  @Autowired private CardRepository cardRepository;
  @Autowired private CardXrefRepository cardXrefRepository;
  @Autowired private TransactionRepository transactionRepository;

  /** Per-method output directory passed to the job as the {@code outputDir} job parameter. */
  @TempDir Path tempDir;

  /** Manually wired Spring Batch launch helper (no {@code @SpringBatchTest}). */
  private JobLauncherTestUtils jobLauncherTestUtils;

  /**
   * Prepares a clean, deterministic database state and a launch helper before each test. Prior
   * batch executions are removed so a re-launch is never rejected as already complete, the five
   * master / transaction tables are emptied for idempotency and re-seeded from the legacy fixtures,
   * and the three controlled golden transactions are inserted for the golden scenario card.
   */
  @BeforeEach
  void setUp() {
    new JobRepositoryTestUtils(jobRepository).removeJobExecutions();

    jobLauncherTestUtils = new JobLauncherTestUtils();
    jobLauncherTestUtils.setJobLauncher(jobLauncher);
    jobLauncherTestUtils.setJobRepository(jobRepository);
    jobLauncherTestUtils.setJob(statementGenerationJob);

    // Idempotency: clear any committed rows from a previous run (this test is not @Transactional).
    transactionRepository.deleteAll();
    cardXrefRepository.deleteAll();
    cardRepository.deleteAll();
    accountRepository.deleteAll();
    customerRepository.deleteAll();

    // Seed the master graph in foreign-key-safe order from the byte-exact legacy ASCII fixtures.
    FixtureSeeder.seedCustomers(customerRepository);
    FixtureSeeder.seedAccounts(accountRepository);
    FixtureSeeder.seedCards(cardRepository);
    FixtureSeeder.seedCardXrefs(cardXrefRepository);

    // Controlled transactions: the statement renders line items per card, and there is no legacy
    // TRANSACT fixture, so seed the exact three rows documented by golden/statements/README.md for
    // card 0500024453765740 (account 00000000050). This is the deterministic option preferred for a
    // stable golden comparison over chaining the posting job.
    transactionRepository.save(
        newTransaction(
            "0010203040506070", "POS PURCHASE - GROCERY MART", new BigDecimal("123.45")));
    transactionRepository.save(
        newTransaction("0010203040506071", "ONLINE PURCHASE - BOOKSTORE", new BigDecimal("67.89")));
    transactionRepository.save(
        newTransaction("0010203040506072", "PAYMENT - THANK YOU", new BigDecimal("-50.00")));
  }

  /**
   * Launches {@code statementGenerationJob} against the seeded scenario and verifies that it
   * produces both statement files with exact fixed-width records, the expected statement structure
   * and seeded line items, and (when present) byte-exact golden parity for account {@code
   * 00000000050}.
   *
   * @throws Exception if the job launch fails (declared by {@link
   *     JobLauncherTestUtils#launchJob(org.springframework.batch.core.JobParameters)})
   */
  @Test
  void statementGenerationJob_writes_fixed_width_text_and_html_statements() throws Exception {
    JobExecution execution =
        jobLauncherTestUtils.launchJob(
            new JobParametersBuilder()
                .addString("outputDir", tempDir.toString())
                .toJobParameters());

    // The job and its single step both complete (no abend on cross-reference reads / empty input).
    assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    StepExecution stepExecution =
        execution.getStepExecutions().stream()
            .filter(step -> "statementGenerationStep".equals(step.getStepName()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("statementGenerationStep did not execute"));
    assertThat(stepExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

    // The tasklet resolves outputDir and writes the two physical files under it by DD name.
    Path stmtPath = tempDir.resolve(StatementFileWriter.DD_NAME);
    Path htmlPath = tempDir.resolve(HtmlStatementWriter.DD_NAME);
    assertThat(stmtPath).exists();
    assertThat(htmlPath).exists();

    List<String> stmtLines = Files.readAllLines(stmtPath, StandardCharsets.UTF_8);
    List<String> htmlLines = Files.readAllLines(htmlPath, StandardCharsets.UTF_8);

    // PRIMARY: fixed-width parity — STMTFILE LRECL 80, HTMLFILE LRECL 100 (distinct; CREASTMT
    // STEP040). Pin the production constants to the JCL contract, then assert every record width.
    assertThat(StatementFileWriter.LRECL).isEqualTo(80);
    assertThat(HtmlStatementWriter.LRECL).isEqualTo(100);
    assertThat(stmtLines).isNotEmpty();
    assertThat(stmtLines).allSatisfy(line -> assertThat(line).hasSize(StatementFileWriter.LRECL));
    assertThat(htmlLines).isNotEmpty();
    assertThat(htmlLines).allSatisfy(line -> assertThat(line).hasSize(HtmlStatementWriter.LRECL));

    // Statement structure + the seeded account-50 line items are rendered.
    assertThat(stmtLines).anyMatch(line -> line.startsWith("*".repeat(31) + "START OF STATEMENT"));
    assertThat(stmtLines).anyMatch(line -> line.startsWith("*".repeat(32) + "END OF STATEMENT"));
    assertThat(stmtLines)
        .anyMatch(line -> line.startsWith("Account ID") && line.contains(GOLDEN_ACCOUNT_ID));
    assertThat(stmtLines).anyMatch(line -> line.startsWith("Total EXP:"));
    for (String description : SEEDED_TRAN_DESCS) {
      assertThat(stmtLines).anyMatch(line -> line.contains(description));
    }
    assertThat(htmlLines).anyMatch(line -> line.startsWith("<!DOCTYPE html>"));
    assertThat(htmlLines).anyMatch(line -> line.startsWith("</html>"));

    // SECONDARY (guarded): byte-exact golden parity for the account-00000000050 statement block.
    assertContainsGoldenBlock(stmtLines, GOLDEN_TXT, StatementFileWriter.LRECL);
    assertContainsGoldenBlock(htmlLines, GOLDEN_HTML, HtmlStatementWriter.LRECL);
  }

  /**
   * Builds a fully populated {@link Transaction} keyed to the golden scenario card. Only {@code
   * tranId}, {@code tranDesc} and {@code tranAmt} are rendered on the statement; every other column
   * is {@code NOT NULL} in the schema, so each is given a valid fixed-width filler value (the
   * {@code transaction} table carries no foreign keys, so the type/category codes need not exist in
   * the reference tables).
   *
   * @param tranId the 16-character transaction id (primary key)
   * @param description the transaction description rendered on the statement detail line
   * @param amount the signed transaction amount ({@link BigDecimal}, scale 2)
   * @return a persistable transaction for card {@link #GOLDEN_CARD_NUM}
   */
  private static Transaction newTransaction(String tranId, String description, BigDecimal amount) {
    Transaction transaction = new Transaction();
    transaction.setTranId(tranId);
    transaction.setTranTypeCd("01");
    transaction.setTranCatCd("0001");
    transaction.setTranSource("POS");
    transaction.setTranDesc(description);
    transaction.setTranAmt(amount);
    transaction.setTranMerchantId(999999999L);
    transaction.setTranMerchantName("GOLDEN MERCHANT");
    transaction.setTranMerchantCity("SEATTLE");
    transaction.setTranMerchantZip("98101");
    transaction.setTranCardNum(GOLDEN_CARD_NUM);
    transaction.setTranOrigTs(TS_FILLER);
    transaction.setTranProcTs(TS_FILLER);
    return transaction;
  }

  /**
   * Asserts that the produced output contains the byte-exact golden statement block, guarded by the
   * golden resource's existence. Each golden line is right-padded to the record length before the
   * comparison (the parity-preserving normalization recommended by the golden README) so the
   * assertion is robust even if a checkout stripped the significant trailing spaces; the produced
   * lines are already exactly {@code lrecl} characters. When the resource is absent the method
   * returns without asserting, so a not-yet-authored golden never fails the test.
   *
   * @param producedLines the records read back from the produced file (each exactly {@code lrecl})
   * @param goldenResource the absolute classpath location of the golden block
   * @param lrecl the fixed record length used to normalize the golden lines
   */
  private static void assertContainsGoldenBlock(
      List<String> producedLines, String goldenResource, int lrecl) {
    List<String> goldenLines = readClasspathLines(goldenResource);
    if (goldenLines == null || goldenLines.isEmpty()) {
      return;
    }
    String goldenBlock =
        goldenLines.stream().map(line -> padTo(line, lrecl)).collect(Collectors.joining("\n"));
    String producedText = String.join("\n", producedLines);
    assertThat(producedText)
        .as("produced output must contain the byte-exact golden block %s", goldenResource)
        .contains(goldenBlock);
  }

  /**
   * Reads every record line of a classpath resource, preserving fixed-width interior content, or
   * returns {@code null} when the resource is absent. Lines are split on {@code '\n'}; a trailing
   * empty element from a file-final newline is dropped and a single trailing carriage return is
   * stripped so a CRLF checkout still yields the exact record content.
   *
   * @param resource the absolute classpath resource path
   * @return the ordered record lines, or {@code null} if the resource is not on the classpath
   */
  private static List<String> readClasspathLines(String resource) {
    try (InputStream in = StatementGenerationJobConfigTest.class.getResourceAsStream(resource)) {
      if (in == null) {
        return null;
      }
      String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      String[] rawLines = content.split("\n", -1);
      List<String> lines = new ArrayList<>(rawLines.length);
      for (int i = 0; i < rawLines.length; i++) {
        String line = rawLines[i];
        if (i == rawLines.length - 1 && line.isEmpty()) {
          continue;
        }
        if (!line.isEmpty() && line.charAt(line.length() - 1) == '\r') {
          line = line.substring(0, line.length() - 1);
        }
        lines.add(line);
      }
      return lines;
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read golden resource: " + resource, e);
    }
  }

  /**
   * Right-pads {@code value} with spaces to {@code width}, leaving it unchanged when already at
   * least that long (COBOL fixed-width right-padding).
   *
   * @param value the value to pad
   * @param width the target record length
   * @return {@code value} padded with trailing spaces to {@code width}
   */
  private static String padTo(String value, int width) {
    return value.length() >= width ? value : value + " ".repeat(width - value.length());
  }
}
