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

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.batch.repository.AccountRepository;
import com.carddemo.batch.repository.CardRepository;
import com.carddemo.batch.repository.CardXrefRepository;
import com.carddemo.batch.repository.CustomerRepository;
import com.carddemo.batch.repository.TranCatBalRepository;
import com.carddemo.batch.repository.TransactionRepository;
import com.carddemo.batch.testsupport.AsciiFixtures;
import com.carddemo.common.domain.Account;
import com.carddemo.common.domain.Card;
import com.carddemo.common.domain.CardXref;
import com.carddemo.common.domain.Customer;
import com.carddemo.common.domain.TranCatBal;
import com.carddemo.common.domain.Transaction;
import com.carddemo.common.testsupport.MigratedSchemaContainer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.configuration.support.MapJobRegistry;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.support.TaskExecutorJobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * End-to-end integration test for the data-management jobs.
 *
 * :purpose: Launch each ``DataManagementJobConfig`` job against a real PostgreSQL
 *     carrying the schema the committed Flyway migrations produce, over a small
 *     deterministic data set this class seeds itself in ``@BeforeEach`` (so the
 *     scenarios are independent of both the migration seed and of sibling tests
 *     that clear whole tables), and assert the artifacts each one writes: the ``CBACT01C`` labelled account
 *     dump, the ``CBACT02C`` 150-byte card records, the ``CBACT03C`` 50-byte
 *     cross-reference records, the ``CBCUS01C`` 500-byte customer records, the
 *     ``CBTRN01C`` daily-transaction validation pass over the committed feed
 *     fixture, the ``PRTCATBL`` category-balance report and the ``COMBTRAN``
 *     combined transaction file. Every job is asserted to reach
 *     ``BatchStatus.COMPLETED`` with a non-zero read count, so a job that silently
 *     processes nothing fails.
 * :output: A Failsafe (``*IT``) test writing into a per-run temporary directory
 *     that is removed afterwards; the database is only read.
 */
@SpringBootTest(classes = BatchServiceApplication.class)
class DataManagementJobIT {

    /**
     * :purpose: Per-run batch working directory. Created in a static initializer
     *     because ``carddemo.batch.output-dir`` / ``input-dir`` are configuration
     *     properties read while the application context is built.
     */
    private static final Path WORK_DIR = createWorkDirectory();

    /** :purpose: Output root every job writes into. */
    private static final Path OUTPUT_ROOT = WORK_DIR.resolve("output");

    /** :purpose: Input root the daily-transaction feed is read from. */
    private static final Path INPUT_ROOT = WORK_DIR.resolve("input");

    /** :purpose: Name of the feed file copied from the committed ASCII fixture. */
    private static final String FEED_FILE = "dailytran.txt";

    /** :purpose: First deterministic customer / account identifier this test owns. */
    private static final long CUST_ONE = 100000001L;

    /** :purpose: Second deterministic customer / account identifier this test owns. */
    private static final long CUST_TWO = 100000002L;

    /** :purpose: Card number linked to {@link #CUST_ONE}. */
    private static final String CARD_ONE = "4111111111110001";

    /** :purpose: Card number linked to {@link #CUST_TWO}. */
    private static final String CARD_TWO = "4111111111110002";

    /** Context batch job repository backing this test's synchronous launcher. */
    @Autowired
    private JobRepository jobRepository;

    /** Raw JDBC access used only to size the expected artifacts from the seeded data. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Customer repository used to own the ``customers`` content for each scenario. */
    @Autowired
    private CustomerRepository customerRepository;

    /** Account repository used to own the ``accounts`` content for each scenario. */
    @Autowired
    private AccountRepository accountRepository;

    /** Card repository used to own the ``cards`` content for each scenario. */
    @Autowired
    private CardRepository cardRepository;

    /** Cross-reference repository used to own the ``card_xref`` content for each scenario. */
    @Autowired
    private CardXrefRepository cardXrefRepository;

    /** Category-balance repository backing the ``PRTCATBL`` report scenario. */
    @Autowired
    private TranCatBalRepository tranCatBalRepository;

    /** Transaction repository backing the ``COMBTRAN`` combined-file scenario. */
    @Autowired
    private TransactionRepository transactionRepository;

    // Each job bean is qualified by name because the context holds nine Job beans.

    @Autowired
    @Qualifier("accountReadJob")
    private Job accountReadJob;

    @Autowired
    @Qualifier("cardReadJob")
    private Job cardReadJob;

    @Autowired
    @Qualifier("cardXrefReadJob")
    private Job cardXrefReadJob;

    @Autowired
    @Qualifier("customerReadJob")
    private Job customerReadJob;

    @Autowired
    @Qualifier("dailyTransactionValidationJob")
    private Job dailyTransactionValidationJob;

    @Autowired
    @Qualifier("categoryBalanceReportJob")
    private Job categoryBalanceReportJob;

    @Autowired
    @Qualifier("combineTransactionsJob")
    private Job combineTransactionsJob;

    /**
     * :purpose: Bind the datasource to the shared, already-migrated ``postgres:18``
     *     container and point the batch roots at this run's working directory.
     * :param registry: the dynamic property registry supplied by the test context.
     */
    @DynamicPropertySource
    static void provisionSchemaAndDirectories(DynamicPropertyRegistry registry) {
        MigratedSchemaContainer.registerDataSource(registry);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("carddemo.batch.output-dir", OUTPUT_ROOT::toString);
        registry.add("carddemo.batch.input-dir", INPUT_ROOT::toString);
    }

    /**
     * Creates the per-run working directory and stages the committed feed fixture.
     *
     * :output: the created working directory.
     * :raises UncheckedIOException: when the directories cannot be prepared.
     */
    private static Path createWorkDirectory() {
        try {
            Path work = Files.createTempDirectory("carddemo-datamgmt-");
            Files.createDirectories(work.resolve("output"));
            Path input = Files.createDirectories(work.resolve("input"));
            Files.write(input.resolve(FEED_FILE),
                    AsciiFixtures.lines(FEED_FILE), StandardCharsets.ISO_8859_1);
            return work;
        } catch (IOException ex) {
            throw new UncheckedIOException("Unable to prepare the batch working directory", ex);
        }
    }

    /**
     * :purpose: Remove the per-run working directory and every artifact in it.
     * :raises IOException: when the directory cannot be removed.
     */
    @AfterAll
    static void removeWorkDirectory() throws IOException {
        if (!Files.exists(WORK_DIR)) {
            return;
        }
        try (var paths = Files.walk(WORK_DIR)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ex) {
                    throw new UncheckedIOException("Unable to delete " + path, ex);
                }
            });
        }
    }

    /**
     * :purpose: Own the business content every scenario reads, so this class is INDEPENDENT of
     *     both the migration seed and of any sibling test that mutates it. ``InterestCalculationJobIT``
     *     legitimately clears every business table in its own ``@BeforeEach`` (its jobs consume
     *     whole tables), so relying on the Flyway seed surviving would make these scenarios pass
     *     only under alphabetical test order. Each table is cleared child-before-parent to respect
     *     the ``cards`` / ``card_xref`` foreign keys and then re-seeded with a small, exact data
     *     set; batch metadata is intentionally left in place because every launch uses a unique
     *     ``run.id``.
     */
    @BeforeEach
    void seedDeterministicBusinessState() {
        transactionRepository.deleteAll();
        tranCatBalRepository.deleteAll();
        cardXrefRepository.deleteAll();
        cardRepository.deleteAll();
        accountRepository.deleteAll();
        customerRepository.deleteAll();

        customerRepository.saveAll(List.of(
                customer(CUST_ONE, "JOHN", "A", "DOE"),
                customer(CUST_TWO, "JANE", "B", "ROE")));
        accountRepository.saveAll(List.of(
                account(CUST_ONE, "1234.56"),
                account(CUST_TWO, "7890.12")));
        cardRepository.saveAll(List.of(
                card(CARD_ONE, CUST_ONE, "JOHN A DOE"),
                card(CARD_TWO, CUST_TWO, "JANE B ROE")));
        cardXrefRepository.saveAll(List.of(
                new CardXref(CARD_ONE, CUST_ONE, CUST_ONE),
                new CardXref(CARD_TWO, CUST_TWO, CUST_TWO)));
        // Balances stay non-negative and below 1e9 so the PRTCATBL layout renders them as the
        // fixed nine-digit-plus-two-decimal field the report scenario asserts.
        tranCatBalRepository.saveAll(List.of(
                new TranCatBal(CUST_ONE, "01", 1, new BigDecimal("1000.00")),
                new TranCatBal(CUST_ONE, "02", 2, new BigDecimal("2500.50")),
                new TranCatBal(CUST_TWO, "01", 1, new BigDecimal("99.99"))));
        transactionRepository.saveAll(List.of(
                transaction("0000000000000001", CARD_ONE, "100.50", "2024-01-15-10.30.00.123456"),
                transaction("0000000000000002", CARD_TWO, "200.25", "2024-01-16-11.45.00.654321")));
    }

    /**
     * Builds a customer with every NOT NULL column populated.
     *
     * :param custId: the customer identifier.
     * :param first: the first name.
     * :param middle: the middle name.
     * :param last: the last name.
     * :output: the unsaved customer.
     */
    private static Customer customer(long custId, String first, String middle, String last) {
        Customer customer = new Customer();
        customer.setCustId(custId);
        customer.setCustFirstName(first);
        customer.setCustMiddleName(middle);
        customer.setCustLastName(last);
        customer.setCustAddrLine1("123 MAIN ST");
        customer.setCustAddrLine2("SUITE 100");
        customer.setCustAddrLine3("ANYTOWN");
        customer.setCustAddrStateCd("NC");
        customer.setCustAddrCountryCd("USA");
        customer.setCustAddrZip("99999");
        customer.setCustPhoneNum1("(000)000-0000");
        customer.setCustPhoneNum2("(000)000-0000");
        customer.setCustDobYyyyMmDd("1970-01-01");
        customer.setCustPriCardHolderInd("Y");
        customer.setCustFicoCreditScore(750);
        // The migrated NOT NULL PII columns carry blank values: no PII is seeded (AAP 0.6.7)
        // and the CryptoConverter passes empty strings through unchanged.
        customer.setCustSsn("");
        customer.setCustGovtIssuedId("");
        customer.setCustEftAccountId("");
        return customer;
    }

    /**
     * Builds an account with every NOT NULL column populated and valid lifecycle dates.
     *
     * :param acctId: the account identifier.
     * :param currentBalance: the current balance, at scale 2.
     * :output: the unsaved account.
     */
    private static Account account(long acctId, String currentBalance) {
        Account account = new Account();
        account.setAcctId(acctId);
        account.setAcctActiveStatus("Y");
        account.setAcctCurrBal(new BigDecimal(currentBalance));
        account.setAcctCreditLimit(new BigDecimal("5000.00"));
        account.setAcctCashCreditLimit(new BigDecimal("1000.00"));
        account.setAcctOpenDate("2020-01-01");
        // Preserves the legacy copybook misspelling ``ACCT-EXPIRAION-DATE`` verbatim.
        account.setAcctExpiraionDate("2099-12-31");
        account.setAcctReissueDate("2020-01-01");
        account.setAcctCurrCycCredit(new BigDecimal("0.00"));
        account.setAcctCurrCycDebit(new BigDecimal("0.00"));
        account.setAcctAddrZip("99999");
        account.setAcctGroupId("GRP0000001");
        return account;
    }

    /**
     * Builds a card owned by the supplied account.
     *
     * :param cardNum: the 16-digit card number.
     * :param acctId: the owning account identifier.
     * :param embossedName: the embossed name.
     * :output: the unsaved card.
     */
    private static Card card(String cardNum, long acctId, String embossedName) {
        Card card = new Card();
        card.setCardNum(cardNum);
        card.setCardAcctId(acctId);
        card.setCardEmbossedName(embossedName);
        // The legacy copybook misspelling ``CARD-EXPIRAION-DATE`` is preserved verbatim.
        card.setCardExpiraionDate("2099-12-31");
        card.setCardActiveStatus("Y");
        // No CVV is seeded: the column is encrypted at rest and carries no test value.
        card.setCardCvvCd("");
        return card;
    }

    /**
     * Builds a transaction in the ``CVTRA05Y`` shape.
     *
     * :param tranId: the 16-digit zero-padded transaction id.
     * :param cardNum: the card the transaction belongs to.
     * :param amount: the amount, at scale 2.
     * :param timestamp: the 26-character DB2 timestamp used for both origin and processing.
     * :output: the unsaved transaction.
     */
    private static Transaction transaction(String tranId, String cardNum, String amount,
                                          String timestamp) {
        Transaction transaction = new Transaction();
        transaction.setTranId(tranId);
        transaction.setTranCardNum(cardNum);
        transaction.setTranAmt(new BigDecimal(amount));
        transaction.setTranDesc("PURCHASE " + tranId);
        transaction.setTranTypeCd("01");
        transaction.setTranCatCd(1);
        transaction.setTranSource("POS");
        transaction.setTranMerchantId(123456789L);
        transaction.setTranMerchantName("TEST MERCHANT");
        transaction.setTranMerchantCity("ANYTOWN");
        transaction.setTranMerchantZip("99999");
        transaction.setTranOrigTs(timestamp);
        transaction.setTranProcTs(timestamp);
        return transaction;
    }

    /**
     * Launches a job with the supplied parameters and asserts it completed.
     *
     * :param job: the job under test.
     * :param parameterName: the single job-parameter name, or ``null`` for none.
     * :param parameterValue: the job-parameter value.
     * :output: the completed job execution.
     */
    private JobExecution launch(Job job, String parameterName, String parameterValue)
            throws Exception {
        JobParametersBuilder builder = new JobParametersBuilder()
                .addLong("run.id", System.nanoTime());
        if (parameterName != null) {
            builder.addString(parameterName, parameterValue);
        }
        JobParameters parameters = builder.toJobParameters();
        JobExecution execution = synchronousJobOperator(job).start(job, parameters);
        assertThat(execution.getStatus())
                .as("%s exit description: %s", job.getName(),
                        execution.getExitStatus().getExitDescription())
                .isEqualTo(BatchStatus.COMPLETED);
        return execution;
    }

    /**
     * Builds a synchronous operator so each launch blocks until the job finishes.
     *
     * :param job: the job registered with the operator's registry.
     * :output: a {@link JobOperator} over the context job repository.
     */
    private JobOperator synchronousJobOperator(Job job) throws Exception {
        TaskExecutorJobOperator operator = new TaskExecutorJobOperator();
        operator.setJobRepository(jobRepository);
        operator.setTaskExecutor(new SyncTaskExecutor());
        MapJobRegistry jobRegistry = new MapJobRegistry();
        jobRegistry.register(job);
        operator.setJobRegistry(jobRegistry);
        operator.afterPropertiesSet();
        return operator;
    }

    /**
     * Reads an artifact the job wrote into the output root.
     *
     * :param fileName: the artifact file name.
     * :output: the artifact lines.
     */
    private static List<String> artifact(String fileName) throws IOException {
        return Files.readAllLines(OUTPUT_ROOT.resolve(fileName), StandardCharsets.ISO_8859_1);
    }

    /**
     * Counts the rows of a table.
     *
     * :param table: the table name.
     * :output: the row count.
     */
    private long rowCount(String table) {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return count == null ? 0L : count;
    }

    @Test
    @DisplayName("accountReadJob dumps every seeded account in the labelled CBACT01C format")
    void accountReadJobDumpsEveryAccount() throws Exception {
        long accounts = rowCount("accounts");
        assertThat(accounts).isPositive();

        JobExecution execution = launch(accountReadJob, "outputFile", "acctdump.txt");

        assertThat(execution.getStepExecutions().iterator().next().getReadCount())
                .isEqualTo(accounts);
        List<String> lines = artifact("acctdump.txt");
        assertThat(lines.get(0)).isEqualTo("START OF EXECUTION OF PROGRAM CBACT01C");
        assertThat(lines.get(lines.size() - 1)).isEqualTo("END OF EXECUTION OF PROGRAM CBACT01C");
        // Eleven labelled lines plus the separator per record, between the two banners.
        assertThat(lines).hasSize((int) (accounts * 12) + 2);
        assertThat(lines).filteredOn(line -> line.startsWith("ACCT-EXPIRAION-DATE"))
                .hasSize((int) accounts);
        assertThat(lines).filteredOn(line -> line.equals("-".repeat(49)))
                .hasSize((int) accounts);
    }

    @Test
    @DisplayName("cardReadJob dumps every seeded card as a 150-byte CVACT02Y record")
    void cardReadJobDumpsFixedWidthCardRecords() throws Exception {
        long cards = rowCount("cards");
        assertThat(cards).isPositive();

        launch(cardReadJob, "outputFile", "carddump.txt");

        List<String> lines = artifact("carddump.txt");
        assertThat(lines.get(0)).isEqualTo("START OF EXECUTION OF PROGRAM CBACT02C");
        assertThat(lines.get(lines.size() - 1)).isEqualTo("END OF EXECUTION OF PROGRAM CBACT02C");
        List<String> records = lines.subList(1, lines.size() - 1);
        assertThat(records).hasSize((int) cards)
                .allSatisfy(record -> assertThat(record).hasSize(150));
    }

    @Test
    @DisplayName("cardXrefReadJob dumps every cross-reference as a 50-byte CVACT03Y record")
    void cardXrefReadJobDumpsFixedWidthXrefRecords() throws Exception {
        long xrefs = rowCount("card_xref");
        assertThat(xrefs).isPositive();

        launch(cardXrefReadJob, "outputFile", "xrefdump.txt");

        List<String> lines = artifact("xrefdump.txt");
        assertThat(lines.get(0)).isEqualTo("START OF EXECUTION OF PROGRAM CBACT03C");
        assertThat(lines.get(lines.size() - 1)).isEqualTo("END OF EXECUTION OF PROGRAM CBACT03C");
        assertThat(lines.subList(1, lines.size() - 1)).hasSize((int) xrefs)
                .allSatisfy(record -> assertThat(record).hasSize(50));
    }

    @Test
    @DisplayName("customerReadJob dumps every customer as a 500-byte CVCUS01Y record")
    void customerReadJobDumpsFixedWidthCustomerRecords() throws Exception {
        long customers = rowCount("customers");
        assertThat(customers).isPositive();

        launch(customerReadJob, "outputFile", "custdump.txt");

        List<String> lines = artifact("custdump.txt");
        assertThat(lines.get(0)).isEqualTo("START OF EXECUTION OF PROGRAM CBCUS01C");
        assertThat(lines.get(lines.size() - 1)).isEqualTo("END OF EXECUTION OF PROGRAM CBCUS01C");
        assertThat(lines.subList(1, lines.size() - 1)).hasSize((int) customers)
                .allSatisfy(record -> assertThat(record).hasSize(500));
    }

    @Test
    @DisplayName("dailyTransactionValidationJob reads every record of the committed feed fixture")
    void dailyTransactionValidationJobReadsTheWholeFeed() throws Exception {
        int feedRecords = AsciiFixtures.lines(FEED_FILE).size();
        assertThat(feedRecords).isEqualTo(300);

        JobExecution execution = launch(dailyTransactionValidationJob, "inputFile", FEED_FILE);

        // CBTRN01C reports lookup misses but never filters, so every record is read
        // and handed to the count-only writer.
        assertThat(execution.getStepExecutions().iterator().next().getReadCount())
                .isEqualTo(feedRecords);
        assertThat(execution.getStepExecutions().iterator().next().getWriteCount())
                .isEqualTo(feedRecords);
    }

    @Test
    @DisplayName("categoryBalanceReportJob prints every category balance in the PRTCATBL layout")
    void categoryBalanceReportJobPrintsEveryBalance() throws Exception {
        long balances = rowCount("tran_cat_bal");
        assertThat(balances).isPositive();

        launch(categoryBalanceReportJob, "outputFile", "tcatbal.rpt");

        List<String> lines = artifact("tcatbal.rpt");
        assertThat(lines).hasSize((int) balances);
        assertThat(lines).allSatisfy(line -> {
            assertThat(line).hasSize(41);
            assertThat(line.substring(0, 11)).containsOnlyDigits();
            assertThat(line.substring(15, 19)).containsOnlyDigits();
            assertThat(line.substring(20, 32)).matches("\\d{9}\\.\\d{2}");
        });
    }

    @Test
    @DisplayName("combineTransactionsJob writes the COMBTRAN file as 350-byte records in TRAN-ID order")
    void combineTransactionsJobWritesTheCombinedFile() throws Exception {
        long transactions = rowCount("transactions");

        launch(combineTransactionsJob, "outputFile", "combtran.txt");

        List<String> lines = artifact("combtran.txt");
        assertThat(lines).hasSize((int) transactions);
        assertThat(lines).allSatisfy(line -> assertThat(line).hasSize(350));
        assertThat(lines).extracting(line -> line.substring(0, 16)).isSorted();
    }
}
