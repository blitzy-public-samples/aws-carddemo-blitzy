package com.aws.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.aws.carddemo.AbstractPostgresIntegrationTest;
import com.aws.carddemo.CardDemoApplication;
import com.aws.carddemo.domain.Account;
import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.Customer;
import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.CustomerRepository;
import com.aws.carddemo.repository.TransactionRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ContextConfiguration;

/**
 * Failsafe integration (parity oracle) test for {@link StatementJobConfig} &mdash; the Spring Batch
 * customer-statement job (job bean {@code statementJob}, step {@code statementStep}).
 *
 * <p><strong>Origin / oracle (AAP &sect;0.4.1, &sect;0.6.4).</strong> Verifies behavioural parity
 * with the mainframe statement generator {@code legacy/cbl/CBSTM03A.CBL} (paragraphs
 * {@code 1000-MAINLINE}, {@code 1000-XREFFILE-GET-NEXT}, {@code 2000-CUSTFILE-GET},
 * {@code 3000-ACCTFILE-GET}, {@code 4000-TRNXFILE-GET}, {@code 5000-CREATE-STATEMENT},
 * {@code 6000-WRITE-TRANS}), its generic VSAM I/O subprogram {@code legacy/cbl/CBSTM03B.CBL}, and the
 * orchestrating job {@code legacy/jcl/CREASTMT.JCL}. On z/OS {@code CBSTM03A} performed all file
 * access by calling {@code CBSTM03B} (an operation-code-dispatched open/close/read/keyed-read/write
 * subroutine); {@code CBSTM03B} is pure plumbing and has <em>no</em> standalone Java class &mdash;
 * its behaviour is absorbed into typed Spring Data repository calls (keyed {@code findById(...)} and
 * the ordered {@code findByCardNumOrderByTranIdAsc(...)}). That 1:1 absorption is recorded here so
 * the traceability matrix stays at 100% with no silent drops (AAP &sect;0.6.10).</p>
 *
 * <p><strong>What is asserted.</strong> For every card cross-reference the job emits one statement to
 * <em>two</em> fixed-width outputs &mdash; a plain-text file ({@code RECFM=FB,LRECL=80}) and an HTML
 * file ({@code RECFM=FB,LRECL=100}), matching the datasets in {@code CREASTMT.JCL} {@code STEP040}
 * &mdash; with the card's transactions ordered by {@code (cardNum, tranId)} (the {@code CREASTMT}
 * {@code STEP010 SORT FIELDS=(263,16,CH,A,1,16,CH,A)}) and an accumulated total. This test proves:
 * the job reaches {@link BatchStatus#COMPLETED}; both files are produced and non-empty; every text
 * record is exactly 80 bytes and every HTML record exactly 100 bytes; one statement is produced per
 * seeded card; a card's transactions are rendered ascending by {@code tranId} even when persisted out
 * of order; and the statement total equals the {@link BigDecimal} sum of the card's transaction
 * amounts.</p>
 *
 * <p><strong>Harness.</strong> Extends {@link AbstractPostgresIntegrationTest} so the whole Spring
 * Boot context runs against a real Testcontainers PostgreSQL 18 database with the production Flyway
 * schema (V0&ndash;V3) and reference data. It deliberately does <em>not</em> use
 * {@code @SpringBatchTest}: the full application context contains many {@link Job} beans, so a single
 * {@link JobLauncherTestUtils} is built in a nested {@link TestConfiguration} and bound to the
 * statement job through {@code @Qualifier("statementJob")}. Each test supplies the two output paths
 * ({@code textOutputPath}, {@code htmlOutputPath}) via a {@link TempDir} plus a unique {@code run.id}
 * so every launch is a fresh {@code JobInstance}.</p>
 *
 * <p>The context configuration is pinned explicitly with
 * {@code @ContextConfiguration(classes = {}{@link CardDemoApplication}{@code .class, StatementJobTestConfig.class})}.
 * Two things make this list necessary. First, without the application class Spring Boot's
 * {@code @SpringBootConfiguration} finder scans this test's {@code com.aws.carddemo.batch} package
 * first and can latch onto a sibling test's package-visible {@code @SpringBootConfiguration} (rather
 * than walking up to the real application class), yielding a context that lacks the
 * {@code statementJob} bean. Second, declaring an explicit {@code classes} attribute suppresses the
 * automatic discovery of nested {@link TestConfiguration} classes, so {@link StatementJobTestConfig}
 * &mdash; the sole source of the {@link JobLauncherTestUtils} bean &mdash; must be listed alongside
 * the application class. The result is a deterministic merged configuration of
 * {@code [CardDemoApplication, StatementJobTestConfig]} regardless of what other tests in the package
 * declare.</p>
 *
 * <p><strong>Data hygiene.</strong> The base container is shared and non-transactional, so the
 * arrange step is purely additive: it adds one controlled card cross-reference (reusing an existing,
 * consistent customer/account so the fail-fast processor succeeds) plus three transactions, and
 * {@link #cleanupControlledData()} removes exactly those rows after each test to leave the seed
 * pristine for sibling integration tests. All monetary values are {@link BigDecimal}; {@code float}
 * and {@code double} are never used.</p>
 *
 * @see StatementJobConfig
 * @see AbstractPostgresIntegrationTest
 */
@ContextConfiguration(classes = {CardDemoApplication.class, StatementJobConfigIT.StatementJobTestConfig.class})
class StatementJobConfigIT extends AbstractPostgresIntegrationTest {

    /**
     * Fixed single-byte charset used both to read the generated files and to measure record widths.
     * The rendered statement content is pure ASCII, so decoding with ISO-8859-1 makes the character
     * count equal the byte count, letting the tests assert the mainframe {@code LRECL} byte widths.
     */
    private static final Charset RECORD_CHARSET = StandardCharsets.ISO_8859_1;

    /** Plain-text statement record width ({@code FD-STMTFILE-REC PIC X(80)}; {@code CREASTMT} LRECL 80). */
    private static final int TEXT_RECORD_WIDTH = 80;

    /** HTML statement record width ({@code FD-HTMLFILE-REC PIC X(100)}; {@code CREASTMT} LRECL 100). */
    private static final int HTML_RECORD_WIDTH = 100;

    /** Marker contained in the {@code ST-LINE0} opening banner of every statement. */
    private static final String START_BANNER = "START OF STATEMENT";

    /** Marker contained in the {@code ST-LINE15} closing banner of every statement. */
    private static final String END_BANNER = "END OF STATEMENT";

    /** Leading label of the accumulated-total line ({@code ST-LINE14A}). */
    private static final String TOTAL_LABEL = "Total EXP:";

    /** Verbatim account-id header label ({@code ST-LINE7}). */
    private static final String ACCOUNT_ID_LABEL = "Account ID         :";

    /** Verbatim current-balance header label ({@code ST-LINE8}). */
    private static final String CURRENT_BALANCE_LABEL = "Current Balance    :";

    /** Verbatim FICO-score header label ({@code ST-LINE9}). */
    private static final String FICO_SCORE_LABEL = "FICO Score         :";

    /**
     * Test-only configuration contributing the single {@link JobLauncherTestUtils} used to launch the
     * statement job. Because the full Spring Boot context exposes many {@link Job} beans, the job is
     * selected explicitly with {@code @Qualifier("statementJob")}; the launcher and repository are the
     * Spring Boot Batch auto-configured beans. Because the class-level {@code @ContextConfiguration}
     * declares an explicit {@code classes} attribute (which suppresses automatic nested-configuration
     * discovery), this {@link TestConfiguration} is registered by listing it there alongside
     * {@link CardDemoApplication}, layering it on top of the application's primary configuration.
     */
    @TestConfiguration
    static class StatementJobTestConfig {

        /**
         * Builds the one {@link JobLauncherTestUtils} bound to the customer-statement job.
         *
         * @param jobLauncher   the auto-configured Spring Batch {@link JobLauncher}
         * @param jobRepository the auto-configured Spring Batch {@link JobRepository}
         * @param statementJob  the statement job resolved by bean name {@code statementJob}
         * @return a {@link JobLauncherTestUtils} wired to launch {@code statementJob}
         */
        @Bean
        JobLauncherTestUtils statementJobLauncherTestUtils(JobLauncher jobLauncher,
                JobRepository jobRepository,
                @Qualifier("statementJob") Job statementJob) {
            JobLauncherTestUtils utils = new JobLauncherTestUtils();
            utils.setJobLauncher(jobLauncher);
            utils.setJobRepository(jobRepository);
            utils.setJob(statementJob);
            return utils;
        }
    }

    /** The single launcher utility, contributed by {@link StatementJobTestConfig}. */
    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    /** Customer access for arrange (reuse an existing customer) and content assertions. */
    @Autowired
    private CustomerRepository customerRepository;

    /** Account access for arrange (reuse an existing account) and content assertions. */
    @Autowired
    private AccountRepository accountRepository;

    /** Card cross-reference access &mdash; the job's driving table; used to arrange and count cards. */
    @Autowired
    private CardXrefRepository cardXrefRepository;

    /** Transaction access &mdash; used to seed the controlled card's transactions and total oracle. */
    @Autowired
    private TransactionRepository transactionRepository;

    /** Per-test temporary directory holding the two generated statement files. */
    @TempDir
    private Path tempDir;

    /** The controlled card cross-reference inserted by {@link #arrangeControlledCard()} (for cleanup). */
    private CardXref controlledCard;

    /** 16-character card number of the controlled card; also the key for its transactions. */
    private String controlledCardNum;

    /** Customer id reused from an existing consistent seed card so the fail-fast processor succeeds. */
    private Long reusedCustId;

    /** Account id reused from an existing consistent seed card so the fail-fast processor succeeds. */
    private Long reusedAcctId;

    /** The controlled card's transaction ids in ascending (expected statement) order. */
    private List<String> ascendingTranIds;

    /** Primary-key ids of every transaction inserted by the arrange step (for cleanup). */
    private final List<String> insertedTranIds = new ArrayList<>();

    /** The expected accumulated statement total for the controlled card (scale 2, truncated). */
    private BigDecimal expectedTotal;

    /**
     * Arranges a single controlled card cross-reference with three transactions persisted deliberately
     * out of transaction-id order, so the {@code (cardNum, tranId)} ordering and total accumulation can
     * be asserted end-to-end. The card reuses an existing seed customer/account so the statement
     * processor (which fail-fasts on a missing customer or account, mirroring {@code CBSTM03A}'s
     * {@code 9999-ABEND-PROGRAM}) completes for every card the driving reader visits.
     */
    @BeforeEach
    void arrangeControlledCard() {
        List<CardXref> seededCards = cardXrefRepository.findAll();
        assertThat(seededCards)
                .as("Flyway V2 reference data must seed card cross-references")
                .isNotEmpty();

        // Reuse a consistent (customer, account) pair from an existing seed card.
        CardXref sampleCard = seededCards.get(0);
        reusedCustId = sampleCard.getXrefCustId();
        reusedAcctId = sampleCard.getXrefAcctId();

        // Unique, non-colliding keys derived from a single run token. Seed card numbers are numeric
        // strings whose maximum begins "98", so a "99"-prefixed number sorts last and never collides.
        long token = Math.floorMod(System.nanoTime(), 100_000_000L);
        controlledCardNum = "99" + String.format("%014d", token); // exactly 16 characters
        String tranIdBase = "STMTIT" + String.format("%08d", token); // 14 characters; + 2-digit suffix

        controlledCard = cardXrefRepository.save(
                new CardXref(controlledCardNum, reusedCustId, reusedAcctId));

        String tranId1 = tranIdBase + "01";
        String tranId2 = tranIdBase + "02";
        String tranId3 = tranIdBase + "03";
        ascendingTranIds = List.of(tranId1, tranId2, tranId3);

        // Persist OUT of natural key order (3, 1, 2) with distinct amounts to prove the sort and total.
        saveTransaction(tranId3, "STMT-IT-TXN-03", new BigDecimal("25.25"));
        saveTransaction(tranId1, "STMT-IT-TXN-01", new BigDecimal("100.00"));
        saveTransaction(tranId2, "STMT-IT-TXN-02", new BigDecimal("250.50"));

        // Oracle for the accumulated total: COBOL "ADD TRNX-AMT TO WS-TOTAL-AMT" carries no ROUNDED,
        // so accumulate at full scale and truncate toward zero to scale 2 (RoundingMode.DOWN).
        BigDecimal sum = BigDecimal.ZERO;
        for (Transaction transaction : transactionRepository.findByCardNumOrderByTranIdAsc(controlledCardNum)) {
            sum = sum.add(transaction.getTranAmt());
        }
        expectedTotal = sum.setScale(2, RoundingMode.DOWN);
    }

    /**
     * Removes every row inserted by {@link #arrangeControlledCard()} so the shared, non-transactional
     * database is left in its pristine Flyway-seeded state for sibling integration tests.
     */
    @AfterEach
    void cleanupControlledData() {
        for (String tranId : insertedTranIds) {
            transactionRepository.deleteById(tranId);
        }
        insertedTranIds.clear();
        if (controlledCard != null) {
            cardXrefRepository.delete(controlledCard);
            controlledCard = null;
        }
    }

    /**
     * The job produces both statement outputs and completes successfully.
     *
     * @throws Exception if the job launch fails
     */
    @Test
    void jobProducesTextAndHtmlStatements() throws Exception {
        Path textOut = tempDir.resolve("stmt.txt");
        Path htmlOut = tempDir.resolve("stmt.html");

        JobExecution execution = launchStatementJob(textOut, htmlOut);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(Files.exists(textOut)).as("plain-text statement file exists").isTrue();
        assertThat(Files.exists(htmlOut)).as("HTML statement file exists").isTrue();
        assertThat(Files.size(textOut)).as("plain-text statement file is non-empty").isPositive();
        assertThat(Files.size(htmlOut)).as("HTML statement file is non-empty").isPositive();
    }

    /**
     * Every plain-text record is exactly 80 bytes and every HTML record exactly 100 bytes, preserving
     * the {@code CREASTMT} {@code RECFM=FB} record lengths byte-for-byte.
     *
     * @throws Exception if the job launch fails
     */
    @Test
    void textRecordsAre80Bytes_htmlRecordsAre100Bytes() throws Exception {
        Path textOut = tempDir.resolve("stmt.txt");
        Path htmlOut = tempDir.resolve("stmt.html");

        JobExecution execution = launchStatementJob(textOut, htmlOut);
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<String> textLines = Files.readAllLines(textOut, RECORD_CHARSET);
        List<String> htmlLines = Files.readAllLines(htmlOut, RECORD_CHARSET);

        assertThat(textLines).as("plain-text statement records").isNotEmpty();
        assertThat(htmlLines).as("HTML statement records").isNotEmpty();

        for (String line : textLines) {
            assertThat(line.length())
                    .as("plain-text record character width")
                    .isEqualTo(TEXT_RECORD_WIDTH);
            assertThat(line.getBytes(RECORD_CHARSET).length)
                    .as("plain-text record byte width")
                    .isEqualTo(TEXT_RECORD_WIDTH);
        }
        for (String line : htmlLines) {
            assertThat(line.length())
                    .as("HTML record character width")
                    .isEqualTo(HTML_RECORD_WIDTH);
            assertThat(line.getBytes(RECORD_CHARSET).length)
                    .as("HTML record byte width")
                    .isEqualTo(HTML_RECORD_WIDTH);
        }
    }

    /**
     * Within a card's statement the transactions appear ascending by {@code tranId}, reproducing the
     * {@code CREASTMT} {@code STEP010 SORT FIELDS=(263,16,CH,A,1,16,CH,A)} even though the rows were
     * persisted out of order. Proven end-to-end via the derived query
     * {@code findByCardNumOrderByTranIdAsc}.
     *
     * @throws Exception if the job launch fails
     */
    @Test
    void transactionsWithinCardAreOrderedByTranId() throws Exception {
        Path textOut = tempDir.resolve("stmt.txt");
        Path htmlOut = tempDir.resolve("stmt.html");

        JobExecution execution = launchStatementJob(textOut, htmlOut);
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<String> statementBlock =
                statementBlockContaining(Files.readAllLines(textOut, RECORD_CHARSET), ascendingTranIds.get(0));

        List<String> appearanceOrder = new ArrayList<>();
        for (String line : statementBlock) {
            for (String tranId : ascendingTranIds) {
                if (line.startsWith(tranId)) {
                    appearanceOrder.add(tranId);
                }
            }
        }

        assertThat(appearanceOrder)
                .as("controlled card's transactions rendered ascending by tranId")
                .containsExactlyElementsOf(ascendingTranIds);
    }

    /**
     * The statement header carries the customer name, account id and the balance/FICO labels, the
     * per-transaction lines carry the id/description/amount, and the accumulated total equals the
     * {@link BigDecimal} sum of the card's transaction amounts (compared by value, never as
     * {@code double}).
     *
     * @throws Exception if the job launch fails
     */
    @Test
    void statementTotalEqualsSumOfCardTransactionAmounts() throws Exception {
        Path textOut = tempDir.resolve("stmt.txt");
        Path htmlOut = tempDir.resolve("stmt.html");

        JobExecution execution = launchStatementJob(textOut, htmlOut);
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<String> statementBlock =
                statementBlockContaining(Files.readAllLines(textOut, RECORD_CHARSET), ascendingTranIds.get(0));
        String blockText = String.join("\n", statementBlock);

        Customer customer = customerRepository.findById(reusedCustId)
                .orElseThrow(() -> new AssertionError("seed customer " + reusedCustId + " must exist"));
        Account account = accountRepository.findById(reusedAcctId)
                .orElseThrow(() -> new AssertionError("seed account " + reusedAcctId + " must exist"));

        // Header: customer name (first-name token), account id (11-digit zero-padded), and the labels.
        assertThat(blockText).as("statement header shows the customer name")
                .contains(firstToken(customer.getFirstName()));
        assertThat(blockText).as("statement header shows the account id")
                .contains(String.format("%011d", account.getAcctId()));
        assertThat(blockText).as("statement header labels present")
                .contains(ACCOUNT_ID_LABEL)
                .contains(CURRENT_BALANCE_LABEL)
                .contains(FICO_SCORE_LABEL);

        // Per-transaction lines carry each id, description and amount.
        for (String tranId : ascendingTranIds) {
            assertThat(blockText).as("transaction line for " + tranId).contains(tranId);
        }
        assertThat(blockText)
                .contains("STMT-IT-TXN-01")
                .contains("STMT-IT-TXN-02")
                .contains("STMT-IT-TXN-03");

        // Accumulated total, parsed from the "Total EXP:" line and compared by value.
        BigDecimal renderedTotal = parseTotalAmount(statementBlock);
        assertThat(renderedTotal)
                .as("accumulated statement total equals the BigDecimal sum of the card's amounts")
                .isEqualByComparingTo(expectedTotal);
    }

    /**
     * Exactly one statement is produced per card cross-reference: the number of opening (and closing)
     * banners equals the number of {@link CardXref} rows the job drives over.
     *
     * @throws Exception if the job launch fails
     */
    @Test
    void oneStatementPerCard() throws Exception {
        long expectedStatements = cardXrefRepository.count();
        Path textOut = tempDir.resolve("stmt.txt");
        Path htmlOut = tempDir.resolve("stmt.html");

        JobExecution execution = launchStatementJob(textOut, htmlOut);
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<String> textLines = Files.readAllLines(textOut, RECORD_CHARSET);
        long startBanners = textLines.stream().filter(line -> line.contains(START_BANNER)).count();
        long endBanners = textLines.stream().filter(line -> line.contains(END_BANNER)).count();

        assertThat(startBanners)
                .as("one opening banner per seeded card")
                .isEqualTo(expectedStatements);
        assertThat(endBanners)
                .as("one closing banner per seeded card")
                .isEqualTo(expectedStatements);
    }

    /**
     * Persists a transaction for the controlled card and records its id for cleanup. Only the fields
     * the statement renderer reads (id, card number, description, amount) are populated; all remaining
     * {@code transaction} columns are nullable in the schema.
     *
     * @param tranId      the 16-character transaction id (primary key)
     * @param description the transaction description
     * @param amount      the transaction amount (scale 2)
     */
    private void saveTransaction(String tranId, String description, BigDecimal amount) {
        Transaction transaction = new Transaction();
        transaction.setTranId(tranId);
        transaction.setCardNum(controlledCardNum);
        transaction.setTranDesc(description);
        transaction.setTranAmt(amount);
        transactionRepository.save(transaction);
        insertedTranIds.add(tranId);
    }

    /**
     * Launches {@code statementJob} with the two output-path parameters and a unique {@code run.id} so
     * each invocation is a distinct {@code JobInstance}.
     *
     * @param textOut the plain-text output file path (job parameter {@code textOutputPath})
     * @param htmlOut the HTML output file path (job parameter {@code htmlOutputPath})
     * @return the completed {@link JobExecution}
     * @throws Exception if the launch fails
     */
    private JobExecution launchStatementJob(Path textOut, Path htmlOut) throws Exception {
        JobParameters parameters = new JobParametersBuilder()
                .addString("textOutputPath", textOut.toString())
                .addString("htmlOutputPath", htmlOut.toString())
                .addLong("run.id", System.nanoTime())
                .toJobParameters();
        return jobLauncherTestUtils.launchJob(parameters);
    }

    /**
     * Splits the rendered lines into per-card statement blocks (each from an opening banner through the
     * next closing banner, inclusive) and returns the block whose text contains the given marker.
     *
     * @param lines  every rendered statement line
     * @param marker a token unique to the target card (e.g. one of its transaction ids)
     * @return the matching statement block
     */
    private static List<String> statementBlockContaining(List<String> lines, String marker) {
        List<String> current = null;
        for (String line : lines) {
            if (line.contains(START_BANNER)) {
                current = new ArrayList<>();
                current.add(line);
            } else if (current != null) {
                current.add(line);
                if (line.contains(END_BANNER)) {
                    if (String.join("\n", current).contains(marker)) {
                        return current;
                    }
                    current = null;
                }
            }
        }
        throw new AssertionError("No statement block contained marker: " + marker);
    }

    /**
     * Parses the accumulated total from a statement block's {@code "Total EXP:"} line. The COBOL edit
     * mask ({@code ST-TOTAL-TRAMT PIC Z(9).99-}) renders nine leading-blank-suppressed integer
     * positions, a decimal point, two fraction digits and a trailing sign position ({@code '-'} when
     * negative, blank otherwise).
     *
     * @param statementBlock the lines of one card's statement
     * @return the rendered total as a {@link BigDecimal}
     */
    private static BigDecimal parseTotalAmount(List<String> statementBlock) {
        for (String line : statementBlock) {
            if (line.startsWith(TOTAL_LABEL)) {
                String amountField = line.substring(line.indexOf('$') + 1);
                boolean negative = amountField.contains("-");
                String digits = amountField.replace("-", " ").trim();
                BigDecimal value = new BigDecimal(digits);
                return negative ? value.negate() : value;
            }
        }
        throw new AssertionError("No '" + TOTAL_LABEL + "' line found in statement block");
    }

    /**
     * Returns the first whitespace-delimited token of a (possibly space-padded) field, mirroring the
     * COBOL {@code STRING ... DELIMITED BY ' '} tokenization used to build {@code ST-NAME}.
     *
     * @param value the source value, may be {@code null}
     * @return the token preceding the first space, or the empty string when {@code value} is blank
     */
    private static String firstToken(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        int spaceIndex = trimmed.indexOf(' ');
        return spaceIndex < 0 ? trimmed : trimmed.substring(0, spaceIndex);
    }
}
