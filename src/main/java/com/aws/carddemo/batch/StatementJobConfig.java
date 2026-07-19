package com.aws.carddemo.batch;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.batch.item.data.builder.RepositoryItemReaderBuilder;
import org.springframework.batch.item.file.FlatFileItemWriter;
import org.springframework.batch.item.file.builder.FlatFileItemWriterBuilder;
import org.springframework.batch.item.file.transform.PassThroughLineAggregator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;

import com.aws.carddemo.domain.Account;
import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.Customer;
import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.dto.StatementTransaction;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.CustomerRepository;
import com.aws.carddemo.repository.TransactionRepository;
import com.aws.carddemo.util.CobolDecimal;

/**
 * Spring Batch job configuration translating the mainframe customer-statement generation program
 * {@code CBSTM03A}, its generic KSDS I/O subprogram {@code CBSTM03B}, and the orchestrating JCL
 * {@code CREASTMT.JCL}.
 *
 * <p><strong>Origin:</strong> {@code legacy/cbl/CBSTM03A.CBL} (statement driver),
 * {@code legacy/cbl/CBSTM03B.CBL} (generic VSAM I/O subroutine), and
 * {@code legacy/jcl/CREASTMT.JCL} (job orchestration) &mdash; source branch
 * {@code app/cbl/CBSTM03A.CBL}, {@code app/cbl/CBSTM03B.CBL}, {@code app/jcl/CREASTMT.JCL}.
 * Implements AAP &sect;0.4.1 ({@code CBSTM03B} I/O subprogram &rarr; reader/DAO using
 * {@code COSTM01}), &sect;0.6.3 (JCL/JES2 &rarr; Spring Batch), and &sect;0.6.4 (external-interface
 * parity &mdash; the two fixed-width statement outputs are preserved byte-for-byte).</p>
 *
 * <p><strong>What it produces.</strong> For every card cross-reference (one {@code CVACT03Y} xref
 * row per card), it assembles a customer account statement &mdash; customer name and address,
 * account id, current balance, FICO score, and the card's transactions with an accumulated total
 * &mdash; and emits it to <em>two</em> outputs, exactly as {@code CBSTM03A} did:</p>
 * <ul>
 *   <li>a plain-text statement file with a fixed record length of <strong>80</strong> bytes
 *       ({@code STMT-FILE}, {@code FD-STMTFILE-REC PIC X(80)}); and</li>
 *   <li>an HTML statement file with a fixed record length of <strong>100</strong> bytes
 *       ({@code HTML-FILE}, {@code FD-HTMLFILE-REC PIC X(100)}).</li>
 * </ul>
 * Each rendered line is padded (or truncated) to the exact record length; the writers append a
 * single {@code "\n"} record separator so that every persisted record body is exactly 80 / 100
 * bytes, reproducing the mainframe {@code RECFM=FB} datasets defined in {@code CREASTMT.JCL}.
 *
 * <p><strong>{@code CBSTM03B} &rarr; repository absorption (traceability).</strong> On z/OS,
 * {@code CBSTM03A} performed all file access by calling the generic subroutine {@code CBSTM03B}
 * through the {@code LK-M03B-AREA} linkage record, dispatching on an operation code
 * ({@code O}=open, {@code C}=close, {@code R}=sequential read, {@code K}=keyed read, {@code W}=write,
 * {@code Z}=rewrite) against one of the DD names {@code TRNXFILE}/{@code XREFFILE}/{@code CUSTFILE}/
 * {@code ACCTFILE}. {@code CBSTM03B} is pure plumbing &mdash; a VSAM I/O dispatcher &mdash; and per
 * the AAP it is <strong>not</strong> reified as a standalone Java class. Its entire value is
 * replaced by typed Spring Data repository calls: keyed reads become {@code findById(...)} and the
 * sequential card scan becomes an ordered repository query. This 1:1 mapping
 * ({@code CBSTM03B} &rarr; repositories) is recorded here so the traceability matrix stays at 100%
 * with no silent drops.</p>
 *
 * <p><strong>Transaction ordering (AAP &sect;0.6.6).</strong> In the legacy job, {@code STEP010}
 * ran {@code PGM=SORT} with {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)} to build the work file
 * {@code TRXFL} ordered by <em>card number then transaction id</em>, which {@code CBSTM03A} then
 * read sequentially. The post-migration system of record is PostgreSQL, so the per-card
 * transactions are sourced from
 * {@link TransactionRepository#findByCardNumOrderByTranIdAsc(String)} &mdash; the derived query
 * reproduces the {@code (cardNum, tranId)} ascending order the SORT produced, without materialising
 * a pre-sorted flat file. (The alternative &mdash; a pre-sorted {@code FlatFileItemReader<StatementTransaction>}
 * fed through {@code FixedWidthRecordMapper} over the 350-byte {@code COSTM01} layout &mdash; would
 * yield identical ordering; the DB source is chosen because the migration makes the database the
 * authoritative store. This choice is recorded in {@code docs/decision-log.md}.)</p>
 *
 * <p><strong>Decimal fidelity (AAP &sect;0.6.1).</strong> The running transaction total
 * ({@code WS-TOTAL-AMT PIC S9(9)V99}, COMP-3) and every monetary value are {@link BigDecimal};
 * {@code float}/{@code double} are never used. The accumulation mirrors COBOL
 * {@code ADD TRNX-AMT TO WS-TOTAL-AMT}, which carries <em>no</em> {@code ROUNDED} phrase, so
 * {@link CobolDecimal} (truncation toward zero at scale 2) governs the fixed-scale arithmetic.</p>
 *
 * <p><strong>Wiring note (AAP binding constraint).</strong> This class deliberately does
 * <strong>not</strong> use {@code @EnableBatchProcessing} and does not depend on
 * {@code config/BatchConfig}. It relies on Spring Boot's Batch auto-configuration, which supplies
 * the persistent, restartable {@link JobRepository} and the {@link PlatformTransactionManager}
 * injected through the constructor &mdash; the same convention used by the sibling batch
 * configurations. {@link CobolDecimal} is a non-instantiable static-helper utility, so it is used
 * statically rather than injected; {@code FixedWidthRecordMapper} and {@code exception.EndOfFileException}
 * are context for the mapping (flat-file parsing and normal end-of-scan, respectively) but are not
 * required by the chosen DB-sourced, reader-returns-{@code null}-at-EOF design.</p>
 *
 * <p><strong>Fixed work-table note.</strong> {@code CBSTM03A} buffered transactions in an in-core
 * table capped at {@code OCCURS 51} cards by {@code OCCURS 10} transactions. That cap is a mainframe
 * storage artifact, not a business rule; the DB-driven design removes it without altering the
 * statement output for conforming data (a card with no transactions still yields a full statement
 * with a zero total, exactly as the COBOL loop did).</p>
 *
 * <p>This configuration is stateless and thread-safe: it holds only immutable collaborators, and all
 * rendering logic in {@link StatementRenderer} is pure and side-effect free.</p>
 */
@Configuration
public class StatementJobConfig {

    /** Logger for job/step diagnostics. Card numbers (PAN) are never logged (CWE-532). */
    private static final Logger LOGGER = LoggerFactory.getLogger(StatementJobConfig.class);

    /** Bean/registry name of the Spring Batch {@link Job} translating {@code CBSTM03A}/{@code CREASTMT.JCL}. */
    static final String JOB_NAME = "statementJob";

    /** Name of the single chunk-oriented step within {@link #JOB_NAME}. */
    static final String STEP_NAME = "statementStep";

    /** Fixed record length of the plain-text statement file ({@code FD-STMTFILE-REC PIC X(80)}). */
    static final int TEXT_RECORD_LENGTH = 80;

    /** Fixed record length of the HTML statement file ({@code FD-HTMLFILE-REC PIC X(100)}). */
    static final int HTML_RECORD_LENGTH = 100;

    /** Chunk size for the driving step. Ordering is preserved regardless of chunk size. */
    private static final int CHUNK_SIZE = 10;

    /** Page size for the paging {@link RepositoryItemReader} driving over {@link CardXref}. */
    private static final int READER_PAGE_SIZE = 100;

    /** System property overriding the default output directory for the generated statement files. */
    static final String OUTPUT_DIR_PROPERTY = "carddemo.statement.output.dir";

    /** Default output directory (under the JVM temp dir) when no explicit path/property is supplied. */
    private static final String DEFAULT_OUTPUT_SUBDIR = "carddemo-statements";

    /** Auto-configured Spring Batch job repository used to build the step and the job. */
    private final JobRepository jobRepository;

    /** Auto-configured transaction manager bounding the chunk step's execution. */
    private final PlatformTransactionManager transactionManager;

    /** Drives one statement per card ({@code XREFFILE}); replaces {@code CBSTM03B} sequential xref read. */
    private final CardXrefRepository cardXrefRepository;

    /** Keyed customer lookup ({@code CUSTFILE-GET} by {@code XREF-CUST-ID}); replaces {@code CBSTM03B} keyed read. */
    private final CustomerRepository customerRepository;

    /** Keyed account lookup ({@code ACCTFILE-GET} by {@code XREF-ACCT-ID}); replaces {@code CBSTM03B} keyed read. */
    private final AccountRepository accountRepository;

    /** Per-card transaction scan in {@code (cardNum, tranId)} order; replaces the pre-sorted {@code TRNXFILE}. */
    private final TransactionRepository transactionRepository;

    /**
     * Creates the configuration with the collaborators supplied by Spring Boot's Batch
     * auto-configuration and component scanning.
     *
     * @param jobRepository         the auto-configured Spring Batch {@link JobRepository}
     * @param transactionManager    the auto-configured {@link PlatformTransactionManager}
     * @param cardXrefRepository    driving repository over card cross-references ({@code XREFFILE})
     * @param customerRepository    keyed customer access ({@code CUSTFILE})
     * @param accountRepository     keyed account access ({@code ACCTFILE})
     * @param transactionRepository ordered per-card transaction access ({@code TRNXFILE})
     */
    public StatementJobConfig(JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            CardXrefRepository cardXrefRepository,
            CustomerRepository customerRepository,
            AccountRepository accountRepository,
            TransactionRepository transactionRepository) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.cardXrefRepository = cardXrefRepository;
        this.customerRepository = customerRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    /**
     * Driving reader over {@link CardXref} in ascending card-number order &mdash; the Java analogue
     * of {@code CBSTM03A}'s {@code 1000-XREFFILE-GET-NEXT} sequential read of {@code XREFFILE}.
     *
     * <p>End-of-scan (COBOL {@code FILE STATUS 10}, which set {@code END-OF-FILE = 'Y'}) is signalled
     * naturally by the reader returning {@code null}, which ends the step &mdash; the normal, non-error
     * termination modelled by {@code exception.EndOfFileException}.</p>
     *
     * @return a paging {@link RepositoryItemReader} yielding every {@link CardXref} ordered by
     *         {@code xrefCardNum} ascending
     */
    @Bean
    public RepositoryItemReader<CardXref> statementXrefReader() {
        return new RepositoryItemReaderBuilder<CardXref>()
                .name("statementXrefReader")
                .repository(cardXrefRepository)
                .methodName("findAll")
                .sorts(Map.of("xrefCardNum", Sort.Direction.ASC))
                .pageSize(READER_PAGE_SIZE)
                .build();
    }

    /**
     * Processor reproducing the per-xref body of {@code 1000-MAINLINE}: for each card cross-reference
     * it performs the keyed customer and account reads, scans the card's transactions in
     * {@code (cardNum, tranId)} order, and renders the complete statement into a
     * {@link StatementDocument} (the ordered 80-byte text lines, the ordered 100-byte HTML lines, and
     * the accumulated total).
     *
     * <p><strong>Missing-record behavior (parity).</strong> {@code CBSTM03A}'s
     * {@code 2000-CUSTFILE-GET} and {@code 3000-ACCTFILE-GET} treat any non-{@code '00'} file status
     * as fatal: they {@code DISPLAY} the error and {@code PERFORM 9999-ABEND-PROGRAM} (an LE
     * {@code CEE3ABD} abend). That fail-fast behavior is preserved by throwing an unchecked
     * exception, which fails the step/job rather than silently skipping the card.</p>
     *
     * @return an {@link ItemProcessor} mapping a {@link CardXref} to its rendered {@link StatementDocument}
     */
    @Bean
    public ItemProcessor<CardXref, StatementDocument> statementProcessor() {
        return xref -> {
            Long custId = xref.getXrefCustId();
            Long acctId = xref.getXrefAcctId();
            String cardNum = xref.getXrefCardNum();

            // 2000-CUSTFILE-GET: keyed read by XREF-CUST-ID (fail fast on miss, as COBOL abends).
            Customer customer = customerRepository.findById(custId)
                    .orElseThrow(() -> new IllegalStateException(
                            "CBSTM03A 2000-CUSTFILE-GET: no CUSTFILE record for XREF-CUST-ID=" + custId));

            // 3000-ACCTFILE-GET: keyed read by XREF-ACCT-ID (fail fast on miss, as COBOL abends).
            Account account = accountRepository.findById(acctId)
                    .orElseThrow(() -> new IllegalStateException(
                            "CBSTM03A 3000-ACCTFILE-GET: no ACCTFILE record for XREF-ACCT-ID=" + acctId));

            // 4000-TRNXFILE-GET source: the card's transactions in (cardNum, tranId) ascending order,
            // reproducing the CREASTMT STEP010 SORT. Mapped to the COSTM01 (StatementTransaction) view.
            List<Transaction> transactions = transactionRepository.findByCardNumOrderByTranIdAsc(cardNum);
            List<StatementTransaction> statementTransactions = new ArrayList<>(transactions.size());
            for (Transaction transaction : transactions) {
                statementTransactions.add(toStatementTransaction(transaction));
            }

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Rendering statement for account {} ({} transaction(s))",
                        acctId, statementTransactions.size());
            }
            return StatementRenderer.render(customer, account, statementTransactions);
        };
    }

    /**
     * Maps a persisted {@link Transaction} to the {@link StatementTransaction} ({@code COSTM01}
     * {@code TRNX-RECORD}) view consumed by the renderer. Only the fields {@code CBSTM03A} reads from
     * {@code TRNX-RECORD} are populated: the composite key ({@code TRNX-CARD-NUM}, {@code TRNX-ID}),
     * the description ({@code TRNX-DESC}), and the amount ({@code TRNX-AMT}); the amount is kept as
     * {@link BigDecimal} throughout.
     *
     * @param transaction the persisted transaction (never {@code null})
     * @return the equivalent {@link StatementTransaction} for rendering
     */
    private static StatementTransaction toStatementTransaction(Transaction transaction) {
        StatementTransaction statementTransaction = new StatementTransaction();
        statementTransaction.setCardNum(transaction.getCardNum());
        statementTransaction.setId(transaction.getTranId());
        statementTransaction.setDescription(transaction.getTranDesc());
        statementTransaction.setAmount(transaction.getTranAmt());
        return statementTransaction;
    }

    /**
     * Step-scoped writer for the plain-text statement file ({@code STMT-FILE}, {@code LRECL 80}).
     * Each line handed to it is already exactly {@value #TEXT_RECORD_LENGTH} characters; a single
     * {@code "\n"} record separator is appended so every persisted record body is 80 bytes,
     * matching the {@code RECFM=FB,LRECL=80} dataset in {@code CREASTMT.JCL}.
     *
     * @param textOutputPath explicit output path from the {@code textOutputPath} job parameter,
     *                       or blank to use a generated, generation-versioned default
     * @param jobExecutionId the current job execution id, used as the GDG-style generation suffix
     * @return the configured text {@link FlatFileItemWriter}
     */
    @Bean
    @StepScope
    public FlatFileItemWriter<String> statementTextWriter(
            @Value("#{jobParameters['textOutputPath']}") String textOutputPath,
            @Value("#{stepExecution.jobExecutionId}") Long jobExecutionId) {
        String path = resolveOutputPath(textOutputPath, "txt", jobExecutionId);
        return buildLineWriter("statementTextWriter", path);
    }

    /**
     * Step-scoped writer for the HTML statement file ({@code HTML-FILE}, {@code LRECL 100}).
     * Each line handed to it is already exactly {@value #HTML_RECORD_LENGTH} characters; a single
     * {@code "\n"} record separator is appended so every persisted record body is 100 bytes,
     * matching the {@code RECFM=FB,LRECL=100} dataset in {@code CREASTMT.JCL}.
     *
     * @param htmlOutputPath explicit output path from the {@code htmlOutputPath} job parameter,
     *                       or blank to use a generated, generation-versioned default
     * @param jobExecutionId the current job execution id, used as the GDG-style generation suffix
     * @return the configured HTML {@link FlatFileItemWriter}
     */
    @Bean
    @StepScope
    public FlatFileItemWriter<String> statementHtmlWriter(
            @Value("#{jobParameters['htmlOutputPath']}") String htmlOutputPath,
            @Value("#{stepExecution.jobExecutionId}") Long jobExecutionId) {
        String path = resolveOutputPath(htmlOutputPath, "html", jobExecutionId);
        return buildLineWriter("statementHtmlWriter", path);
    }

    /**
     * Composite writer distributing each rendered {@link StatementDocument} to the two underlying
     * {@link FlatFileItemWriter}s &mdash; the text lines to the text writer and the HTML lines to the
     * HTML writer. This mirrors {@code CBSTM03A}, which wrote every statement to both
     * {@code STMT-FILE} and {@code HTML-FILE} within the same pass.
     *
     * <p>The two delegates are injected as their step-scoped proxies, so calls transparently resolve
     * to the per-step instances; the proxies are also registered as step streams in
     * {@link #statementStep} so their open/update/close lifecycle is managed.</p>
     *
     * @param textWriter the step-scoped text writer proxy
     * @param htmlWriter the step-scoped HTML writer proxy
     * @return an {@link ItemWriter} fanning each document out to both files
     */
    @Bean
    public ItemWriter<StatementDocument> statementCompositeWriter(
            @Qualifier("statementTextWriter") FlatFileItemWriter<String> textWriter,
            @Qualifier("statementHtmlWriter") FlatFileItemWriter<String> htmlWriter) {
        return chunk -> {
            List<String> textLines = new ArrayList<>();
            List<String> htmlLines = new ArrayList<>();
            for (StatementDocument document : chunk.getItems()) {
                textLines.addAll(document.textLines());
                htmlLines.addAll(document.htmlLines());
            }
            textWriter.write(new Chunk<>(textLines));
            htmlWriter.write(new Chunk<>(htmlLines));
        };
    }

    /**
     * The single chunk-oriented step reproducing {@code CBSTM03A}'s {@code 1000-MAINLINE} loop:
     * the {@link #statementXrefReader() driving reader} yields one {@link CardXref} per card, the
     * {@link #statementProcessor() processor} renders the full statement, and the
     * {@link #statementCompositeWriter composite writer} emits it to both output files. Both
     * file writers are registered as step streams so their lifecycle is bound to step execution.
     *
     * @param statementXrefReader      the driving {@link CardXref} reader (card order)
     * @param statementProcessor       the per-card rendering processor
     * @param statementCompositeWriter the dual-file writer
     * @param textWriter               the step-scoped text writer proxy (registered as a stream)
     * @param htmlWriter               the step-scoped HTML writer proxy (registered as a stream)
     * @return the configured {@link Step}
     */
    @Bean
    public Step statementStep(
            RepositoryItemReader<CardXref> statementXrefReader,
            ItemProcessor<CardXref, StatementDocument> statementProcessor,
            ItemWriter<StatementDocument> statementCompositeWriter,
            @Qualifier("statementTextWriter") FlatFileItemWriter<String> textWriter,
            @Qualifier("statementHtmlWriter") FlatFileItemWriter<String> htmlWriter) {
        return new StepBuilder(STEP_NAME, jobRepository)
                .<CardXref, StatementDocument>chunk(CHUNK_SIZE, transactionManager)
                .reader(statementXrefReader)
                .processor(statementProcessor)
                .writer(statementCompositeWriter)
                .stream(textWriter)
                .stream(htmlWriter)
                .build();
    }

    /**
     * The customer-statement generation job &mdash; the Spring Batch equivalent of
     * {@code CREASTMT.JCL}'s {@code STEP040} execution of {@code CBSTM03A}. It consists of the single
     * {@link #statementStep} that drives over cards and writes both statement files.
     *
     * @param statementStep the statement-generation step
     * @return the configured {@link Job}
     */
    @Bean
    public Job statementJob(Step statementStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(statementStep)
                .build();
    }

    /**
     * Resolves the output path for a statement file. When {@code providedPath} is supplied (non-blank
     * job parameter), it is used verbatim; otherwise a generation-versioned default file name is built
     * under the directory named by the {@value #OUTPUT_DIR_PROPERTY} system property, or under a
     * {@code carddemo-statements} folder in the JVM temporary directory when that property is absent.
     * The generation suffix (the job execution id) reproduces the GDG generation semantics of the
     * legacy {@code SYSTRAN}/GDG outputs (AAP &sect;0.6.3).
     *
     * @param providedPath   the explicit path from a job parameter, may be {@code null}/blank
     * @param extension      the file extension ({@code "txt"} or {@code "html"})
     * @param jobExecutionId the job execution id used as the generation suffix, may be {@code null}
     * @return the resolved absolute-or-relative file path for the writer's resource
     */
    private static String resolveOutputPath(String providedPath, String extension, Long jobExecutionId) {
        if (providedPath != null && !providedPath.isBlank()) {
            return providedPath;
        }
        String configuredDir = System.getProperty(OUTPUT_DIR_PROPERTY);
        java.io.File directory = (configuredDir != null && !configuredDir.isBlank())
                ? new java.io.File(configuredDir)
                : new java.io.File(System.getProperty("java.io.tmpdir"), DEFAULT_OUTPUT_SUBDIR);
        long generation = (jobExecutionId != null) ? jobExecutionId : 0L;
        return new java.io.File(directory, "carddemo-statement-" + generation + "." + extension).getPath();
    }

    /**
     * Builds a {@link FlatFileItemWriter} that writes each pre-formatted, fixed-width line verbatim
     * (via a {@link PassThroughLineAggregator}) followed by a single {@code "\n"} separator, deleting
     * any pre-existing file first. The parent directory is created if necessary.
     *
     * @param name the writer name (used for restart execution-context keys); must be unique per step
     * @param path the target file path
     * @return the configured {@link FlatFileItemWriter}
     */
    private static FlatFileItemWriter<String> buildLineWriter(String name, String path) {
        java.io.File file = new java.io.File(path);
        java.io.File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            boolean created = parent.mkdirs();
            if (!created && !parent.isDirectory()) {
                throw new IllegalStateException("Unable to create statement output directory: " + parent);
            }
        }
        return new FlatFileItemWriterBuilder<String>()
                .name(name)
                .resource(new FileSystemResource(file))
                .lineAggregator(new PassThroughLineAggregator<>())
                .lineSeparator("\n")
                .shouldDeleteIfExists(true)
                .build();
    }

    /**
     * Immutable rendered statement for a single card: the ordered plain-text lines (each exactly
     * {@link #TEXT_RECORD_LENGTH} characters), the ordered HTML lines (each exactly
     * {@link #HTML_RECORD_LENGTH} characters), and the accumulated transaction total
     * ({@code WS-TOTAL-AMT}).
     *
     * <p>This is the {@code ItemProcessor} output and {@code ItemWriter} input; it carries the fully
     * rendered content so that writing is a pure fan-out with no further formatting.</p>
     */
    static final class StatementDocument {

        /** Ordered plain-text statement lines, each exactly {@link #TEXT_RECORD_LENGTH} characters. */
        private final List<String> textLines;

        /** Ordered HTML statement lines, each exactly {@link #HTML_RECORD_LENGTH} characters. */
        private final List<String> htmlLines;

        /** The accumulated transaction total ({@code WS-TOTAL-AMT}); always scale 2. */
        private final BigDecimal totalAmount;

        /**
         * Creates an immutable statement document.
         *
         * @param textLines   the ordered 80-character plain-text lines
         * @param htmlLines   the ordered 100-character HTML lines
         * @param totalAmount the accumulated transaction total (scale 2)
         */
        StatementDocument(List<String> textLines, List<String> htmlLines, BigDecimal totalAmount) {
            this.textLines = List.copyOf(textLines);
            this.htmlLines = List.copyOf(htmlLines);
            this.totalAmount = totalAmount;
        }

        /**
         * Returns the ordered plain-text statement lines.
         *
         * @return an immutable list of 80-character lines
         */
        List<String> textLines() {
            return textLines;
        }

        /**
         * Returns the ordered HTML statement lines.
         *
         * @return an immutable list of 100-character lines
         */
        List<String> htmlLines() {
            return htmlLines;
        }

        /**
         * Returns the accumulated transaction total for the card.
         *
         * @return the total amount (scale 2)
         */
        BigDecimal totalAmount() {
            return totalAmount;
        }
    }

    /**
     * Pure, side-effect-free renderer reproducing the exact statement layout produced by
     * {@code CBSTM03A} &mdash; paragraphs {@code 5000-CREATE-STATEMENT} (header/body),
     * {@code 5100-WRITE-HTML-HEADER}, {@code 5200-WRITE-HTML-NMADBS}, {@code 6000-WRITE-TRANS}
     * (per-transaction), and the total/footer emitted at the end of {@code 4000-TRNXFILE-GET}.
     *
     * <p>Every text line is built to exactly {@link #TEXT_RECORD_LENGTH} bytes and every HTML line to
     * exactly {@link #HTML_RECORD_LENGTH} bytes, matching the {@code FD-STMTFILE-REC PIC X(80)} and
     * {@code FD-HTMLFILE-REC PIC X(100)} records and the fixed-format {@code STATEMENT-LINES}/
     * {@code HTML-LINES} working-storage definitions. The COBOL literal HTML fragments are reproduced
     * verbatim (including the double space after {@code <table} in {@code HTML-L08}).</p>
     */
    static final class StatementRenderer {

        // ----- text field widths (STATEMENT-LINES working storage) -----
        private static final int NAME_LEN = 75;      // ST-NAME  PIC X(75)
        private static final int ADDR_LEN = 50;      // ST-ADD1/ST-ADD2 PIC X(50)
        private static final int ADDR3_LEN = 80;     // ST-ADD3  PIC X(80)
        private static final int DISPLAY_FIELD_LEN = 20; // ST-ACCT-ID / L11-ACCT / ST-FICO-SCORE PIC X(20)
        private static final int TRAN_ID_LEN = 16;   // ST-TRANID PIC X(16)
        private static final int TRAN_DESC_LEN = 49; // ST-TRANDT PIC X(49)
        private static final int NAME_HTML_LEN = 50; // L23-NAME  PIC X(50)

        // ----- text literal labels (verbatim from STATEMENT-LINES) -----
        private static final String BANNER_START = "START OF STATEMENT";   // ST-LINE0 (18)
        private static final String BANNER_END = "END OF STATEMENT";       // ST-LINE15 (16)
        private static final String LBL_BASIC_DETAILS = "Basic Details";   // ST-LINE6 literal (13, field 14)
        private static final String LBL_TRANSACTION_SUMMARY = "TRANSACTION SUMMARY "; // ST-LINE11 (20)
        private static final String LBL_ACCOUNT_ID = "Account ID         :";   // ST-LINE7 (20)
        private static final String LBL_CURRENT_BALANCE = "Current Balance    :"; // ST-LINE8 (20)
        private static final String LBL_FICO_SCORE = "FICO Score         :";     // ST-LINE9 (20)
        private static final String LBL_TRAN_ID_HDR = "Tran ID         ";        // ST-LINE13 (16)
        private static final String LBL_TRAN_DETAILS_HDR = "Tran Details    ";   // ST-LINE13 literal (16, field 51)
        private static final String LBL_TRAN_AMOUNT_HDR = "  Tran Amount";       // ST-LINE13 (13)
        private static final String LBL_TOTAL_EXP = "Total EXP:";               // ST-LINE14A (10)

        // ----- HTML fixed literals (HTML-LINES 88-level VALUEs, verbatim) -----
        private static final String HTML_L01 = "<!DOCTYPE html>";
        private static final String HTML_L02 = "<html lang=\"en\">";
        private static final String HTML_L03 = "<head>";
        private static final String HTML_L04 = "<meta charset=\"utf-8\">";
        private static final String HTML_L05 = "<title>HTML Table Layout</title>";
        private static final String HTML_L06 = "</head>";
        private static final String HTML_L07 = "<body style=\"margin:0px;\">";
        private static final String HTML_L08 =
                "<table  align=\"center\" frame=\"box\" style=\"width:70%; font:12px Segoe UI,sans-serif;\">";
        private static final String HTML_LTRS = "<tr>";
        private static final String HTML_LTRE = "</tr>";
        private static final String HTML_LTDE = "</td>";
        private static final String HTML_L10 =
                "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#1d1d96b3;\">";
        private static final String HTML_L15 =
                "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#FFAF33;\">";
        private static final String HTML_L16 = "<p style=\"font-size:16px\">Bank of XYZ</p>";
        private static final String HTML_L17 = "<p>410 Terry Ave N</p>";
        private static final String HTML_L18 = "<p>Seattle WA 99999</p>";
        private static final String HTML_L22_35 =
                "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#f2f2f2;\">";
        private static final String HTML_L30_42 =
                "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#33FFD1; text-align:center;\">";
        private static final String HTML_L31 = "<p style=\"font-size:16px\">Basic Details</p>";
        private static final String HTML_L43 = "<p style=\"font-size:16px\">Transaction Summary</p>";
        private static final String HTML_L47 =
                "<td style=\"width:25%; padding:0px 5px; background-color:#33FF5E; text-align:left;\">";
        private static final String HTML_L48 = "<p style=\"font-size:16px\">Tran ID</p>";
        private static final String HTML_L50 =
                "<td style=\"width:55%; padding:0px 5px; background-color:#33FF5E; text-align:left;\">";
        private static final String HTML_L51 = "<p style=\"font-size:16px\">Tran Details</p>";
        private static final String HTML_L53 =
                "<td style=\"width:20%; padding:0px 5px; background-color:#33FF5E; text-align:right;\">";
        private static final String HTML_L54 = "<p style=\"font-size:16px\">Amount</p>";
        private static final String HTML_L58 =
                "<td style=\"width:25%; padding:0px 5px; background-color:#f2f2f2; text-align:left;\">";
        private static final String HTML_L61 =
                "<td style=\"width:55%; padding:0px 5px; background-color:#f2f2f2; text-align:left;\">";
        private static final String HTML_L64 =
                "<td style=\"width:20%; padding:0px 5px; background-color:#f2f2f2; text-align:right;\">";
        private static final String HTML_L75 = "<h3>End of Statement</h3>";
        private static final String HTML_L78 = "</table>";
        private static final String HTML_L79 = "</body>";
        private static final String HTML_L80 = "</html>";

        // ----- HTML composed prefixes/suffixes (STRING literals, verbatim) -----
        private static final String HTML_L11_PREFIX = "<h3>Statement for Account Number: "; // FILLER X(34)
        private static final String HTML_L11_SUFFIX = "</h3>";                               // FILLER X(05)
        private static final String HTML_NAME_PREFIX = "<p style=\"font-size:16px\">";       // HTML-L23 FILLER X(26)
        private static final String HTML_PARA_OPEN = "<p>";
        private static final String HTML_PARA_CLOSE = "</p>";
        private static final String HTML_BSIC_ACCT_PREFIX = "<p>Account ID         : ";      // STRING literal (24)
        private static final String HTML_BSIC_BAL_PREFIX = "<p>Current Balance    : ";       // STRING literal (24)
        private static final String HTML_BSIC_FICO_PREFIX = "<p>FICO Score         : ";      // STRING literal (24)
        private static final String HTML_TRAILING_SPACES = "  ";                             // literal '  ' before </p>

        /** Non-instantiable helper. */
        private StatementRenderer() {
            throw new AssertionError("No instances");
        }

        /**
         * Renders the complete statement for one card into a {@link StatementDocument}: the ordered
         * 80-byte text lines, the ordered 100-byte HTML lines, and the accumulated total. Reproduces
         * the exact {@code WRITE} sequence of {@code CBSTM03A} (see class Javadoc for the paragraph
         * map).
         *
         * @param customer     the customer owning the account ({@code CUSTFILE} record)
         * @param account      the account ({@code ACCTFILE} record)
         * @param transactions the card's transactions in {@code (cardNum, tranId)} order
         * @return the fully rendered {@link StatementDocument}
         */
        static StatementDocument render(Customer customer, Account account,
                List<StatementTransaction> transactions) {
            List<String> text = new ArrayList<>();
            List<String> html = new ArrayList<>();

            // ----- 5000-CREATE-STATEMENT: derive the ST-* display fields (MOVEs at L462-L485) -----
            String stName = buildName(customer);                                  // ST-NAME  X(75)
            String stAdd1 = pad(safe(customer.getAddrLine1()), ADDR_LEN);         // ST-ADD1  X(50)
            String stAdd2 = pad(safe(customer.getAddrLine2()), ADDR_LEN);         // ST-ADD2  X(50)
            String stAdd3 = buildAddr3(customer);                                 // ST-ADD3  X(80)
            String stAcctId = editDisplayNumber(account.getAcctId(), 11);         // ST-ACCT-ID X(20)
            String stCurrBal = editLeadingZero(CobolDecimal.nullToZero(account.getCurrBal())); // ST-CURR-BAL 9(9).99-
            String stFico = editDisplayNumber(fico(customer), 3);                 // ST-FICO-SCORE X(20)

            // ----- ST-LINE0 (L460) then HTML page header 5100 (L508-L552) -----
            text.add(fixed(repeat('*', 31) + BANNER_START + repeat('*', 31), TEXT_RECORD_LENGTH)); // ST-LINE0
            appendHtmlHeader(html, stAcctId);

            // ----- HTML name/address/basic-details block 5200 (L560-L669) -----
            appendHtmlDetail(html, stName, stAdd1, stAdd2, stAdd3, stAcctId, stCurrBal, stFico);

            // ----- text header body ST-LINE1..ST-LINE13 (write order L488-L502) -----
            text.add(fixed(stName + SPACES5, TEXT_RECORD_LENGTH));                              // ST-LINE1
            text.add(fixed(stAdd1 + repeat(' ', 30), TEXT_RECORD_LENGTH));                      // ST-LINE2
            text.add(fixed(stAdd2 + repeat(' ', 30), TEXT_RECORD_LENGTH));                      // ST-LINE3
            text.add(fixed(stAdd3, TEXT_RECORD_LENGTH));                                        // ST-LINE4
            text.add(fixed(repeat('-', 80), TEXT_RECORD_LENGTH));                               // ST-LINE5
            text.add(fixed(repeat(' ', 33) + pad(LBL_BASIC_DETAILS, 14)
                    + repeat(' ', 33), TEXT_RECORD_LENGTH));                                    // ST-LINE6
            text.add(fixed(repeat('-', 80), TEXT_RECORD_LENGTH));                               // ST-LINE5 (again)
            text.add(fixed(LBL_ACCOUNT_ID + stAcctId + repeat(' ', 40), TEXT_RECORD_LENGTH));   // ST-LINE7
            text.add(fixed(LBL_CURRENT_BALANCE + stCurrBal + repeat(' ', 7)
                    + repeat(' ', 40), TEXT_RECORD_LENGTH));                                    // ST-LINE8
            text.add(fixed(LBL_FICO_SCORE + stFico + repeat(' ', 40), TEXT_RECORD_LENGTH));     // ST-LINE9
            text.add(fixed(repeat('-', 80), TEXT_RECORD_LENGTH));                               // ST-LINE10
            text.add(fixed(repeat(' ', 30) + LBL_TRANSACTION_SUMMARY
                    + repeat(' ', 30), TEXT_RECORD_LENGTH));                                    // ST-LINE11
            text.add(fixed(repeat('-', 80), TEXT_RECORD_LENGTH));                               // ST-LINE12
            text.add(fixed(LBL_TRAN_ID_HDR + pad(LBL_TRAN_DETAILS_HDR, 51)
                    + LBL_TRAN_AMOUNT_HDR, TEXT_RECORD_LENGTH));                                // ST-LINE13
            text.add(fixed(repeat('-', 80), TEXT_RECORD_LENGTH));                               // ST-LINE12 (again)

            // ----- 6000-WRITE-TRANS per transaction; accumulate WS-TOTAL-AMT (L676-L721, L429) -----
            BigDecimal total = BigDecimal.ZERO.setScale(CobolDecimal.MONEY_SCALE, RoundingMode.DOWN);
            for (StatementTransaction transaction : transactions) {
                String tranId = pad(safe(transaction.getId()), TRAN_ID_LEN);        // ST-TRANID X(16)
                String tranDesc = pad(safe(transaction.getDescription()), TRAN_DESC_LEN); // ST-TRANDT X(49)
                BigDecimal amount = CobolDecimal.nullToZero(transaction.getAmount());
                String tranAmt = editZeroSuppressed(amount);                        // ST-TRANAMT Z(9).99-

                // text ST-LINE14 (L679): ST-TRANID(16) + ' ' + ST-TRANDT(49) + '$' + ST-TRANAMT(13)
                text.add(fixed(tranId + " " + tranDesc + "$" + tranAmt, TEXT_RECORD_LENGTH));
                appendHtmlTransaction(html, tranId, tranDesc, tranAmt);

                // ADD TRNX-AMT TO WS-TOTAL-AMT (no ROUNDED) -> truncating add at scale 2
                total = CobolDecimal.money(total.add(amount));
            }

            // ----- 4000-TRNXFILE-GET tail: total + end banner (text L433-L437) -----
            String totalAmt = editZeroSuppressed(total);                            // ST-TOTAL-TRAMT Z(9).99-
            text.add(fixed(repeat('-', 80), TEXT_RECORD_LENGTH));                   // ST-LINE12
            text.add(fixed(LBL_TOTAL_EXP + repeat(' ', 56) + "$" + totalAmt,
                    TEXT_RECORD_LENGTH));                                           // ST-LINE14A
            text.add(fixed(repeat('*', 32) + BANNER_END + repeat('*', 32),
                    TEXT_RECORD_LENGTH));                                           // ST-LINE15

            // ----- HTML closing (4000 tail, L439-L454) -----
            appendHtmlFooter(html);

            return new StatementDocument(text, html, total);
        }

        /** Five-space {@code ST-LINE1} filler. */
        private static final String SPACES5 = "     ";

        /**
         * Emits the fixed HTML page header, reproducing {@code 5100-WRITE-HTML-HEADER} (L508-L552).
         *
         * @param html     the HTML line accumulator
         * @param stAcctId the 20-character account-id display field ({@code L11-ACCT})
         */
        private static void appendHtmlHeader(List<String> html, String stAcctId) {
            addHtml(html, HTML_L01);
            addHtml(html, HTML_L02);
            addHtml(html, HTML_L03);
            addHtml(html, HTML_L04);
            addHtml(html, HTML_L05);
            addHtml(html, HTML_L06);
            addHtml(html, HTML_L07);
            addHtml(html, HTML_L08);
            addHtml(html, HTML_LTRS);
            addHtml(html, HTML_L10);
            // HTML-L11: prefix(34) + L11-ACCT(20) + suffix(5); MOVE ACCT-ID TO L11-ACCT (L529-L530)
            addHtml(html, HTML_L11_PREFIX + stAcctId + HTML_L11_SUFFIX);
            addHtml(html, HTML_LTDE);
            addHtml(html, HTML_LTRE);
            addHtml(html, HTML_LTRS);
            addHtml(html, HTML_L15);
            addHtml(html, HTML_L16);
            addHtml(html, HTML_L17);
            addHtml(html, HTML_L18);
            addHtml(html, HTML_LTDE);
            addHtml(html, HTML_LTRE);
            addHtml(html, HTML_LTRS);
            addHtml(html, HTML_L22_35);
        }

        /**
         * Emits the customer name/address block and the basic-details block, reproducing
         * {@code 5200-WRITE-HTML-NMADBS} (L560-L669). The name and address lines use COBOL
         * {@code STRING ... DELIMITED BY '  '} (stop at the first double space) then append a literal
         * two spaces before {@code </p>}; the basic-detail lines use {@code DELIMITED BY '*'} (the full
         * fixed-width field including trailing spaces).
         *
         * @param html      the HTML line accumulator
         * @param stName    the 75-character {@code ST-NAME}
         * @param stAdd1    the 50-character {@code ST-ADD1}
         * @param stAdd2    the 50-character {@code ST-ADD2}
         * @param stAdd3    the 80-character {@code ST-ADD3}
         * @param stAcctId  the 20-character {@code ST-ACCT-ID}
         * @param stCurrBal the 13-character {@code ST-CURR-BAL}
         * @param stFico    the 20-character {@code ST-FICO-SCORE}
         */
        private static void appendHtmlDetail(List<String> html, String stName, String stAdd1,
                String stAdd2, String stAdd3, String stAcctId, String stCurrBal, String stFico) {
            // Name line (L560-L568): L23-NAME = first 50 chars of ST-NAME, DELIMITED BY '  '
            String l23Name = pad(stName, NAME_HTML_LEN);
            addHtml(html, HTML_NAME_PREFIX + beforeDoubleSpace(l23Name)
                    + HTML_TRAILING_SPACES + HTML_PARA_CLOSE);
            // Address lines (L569-L592): '<p>' + field DELIMITED BY '  ' + '  ' + '</p>'
            addHtml(html, HTML_PARA_OPEN + beforeDoubleSpace(stAdd1)
                    + HTML_TRAILING_SPACES + HTML_PARA_CLOSE);
            addHtml(html, HTML_PARA_OPEN + beforeDoubleSpace(stAdd2)
                    + HTML_TRAILING_SPACES + HTML_PARA_CLOSE);
            addHtml(html, HTML_PARA_OPEN + beforeDoubleSpace(stAdd3)
                    + HTML_TRAILING_SPACES + HTML_PARA_CLOSE);

            addHtml(html, HTML_LTDE);
            addHtml(html, HTML_LTRE);
            addHtml(html, HTML_LTRS);
            addHtml(html, HTML_L30_42);
            addHtml(html, HTML_L31);
            addHtml(html, HTML_LTDE);
            addHtml(html, HTML_LTRE);
            addHtml(html, HTML_LTRS);
            addHtml(html, HTML_L22_35);

            // Basic-detail lines (L613-L633): DELIMITED BY '*' -> full fixed fields incl. trailing spaces
            addHtml(html, HTML_BSIC_ACCT_PREFIX + stAcctId + HTML_PARA_CLOSE);
            addHtml(html, HTML_BSIC_BAL_PREFIX + stCurrBal + HTML_PARA_CLOSE);
            addHtml(html, HTML_BSIC_FICO_PREFIX + stFico + HTML_PARA_CLOSE);

            addHtml(html, HTML_LTDE);
            addHtml(html, HTML_LTRE);
            addHtml(html, HTML_LTRS);
            addHtml(html, HTML_L30_42);
            addHtml(html, HTML_L43);
            addHtml(html, HTML_LTDE);
            addHtml(html, HTML_LTRE);
            addHtml(html, HTML_LTRS);
            addHtml(html, HTML_L47);
            addHtml(html, HTML_L48);
            addHtml(html, HTML_LTDE);
            addHtml(html, HTML_L50);
            addHtml(html, HTML_L51);
            addHtml(html, HTML_LTDE);
            addHtml(html, HTML_L53);
            addHtml(html, HTML_L54);
            addHtml(html, HTML_LTDE);
            addHtml(html, HTML_LTRE);
        }

        /**
         * Emits the three HTML cells for one transaction, reproducing the HTML half of
         * {@code 6000-WRITE-TRANS} (L681-L721). The id/description/amount cells use
         * {@code DELIMITED BY '*'} (full fixed-width fields).
         *
         * @param html     the HTML line accumulator
         * @param tranId   the 16-character {@code ST-TRANID}
         * @param tranDesc the 49-character {@code ST-TRANDT}
         * @param tranAmt  the 13-character {@code ST-TRANAMT}
         */
        private static void appendHtmlTransaction(List<String> html, String tranId, String tranDesc,
                String tranAmt) {
            addHtml(html, HTML_LTRS);
            addHtml(html, HTML_L58);
            addHtml(html, HTML_PARA_OPEN + tranId + HTML_PARA_CLOSE);
            addHtml(html, HTML_LTDE);
            addHtml(html, HTML_L61);
            addHtml(html, HTML_PARA_OPEN + tranDesc + HTML_PARA_CLOSE);
            addHtml(html, HTML_LTDE);
            addHtml(html, HTML_L64);
            addHtml(html, HTML_PARA_OPEN + tranAmt + HTML_PARA_CLOSE);
            addHtml(html, HTML_LTDE);
            addHtml(html, HTML_LTRE);
        }

        /**
         * Emits the HTML closing block, reproducing the HTML half of the {@code 4000-TRNXFILE-GET}
         * tail (L439-L454).
         *
         * @param html the HTML line accumulator
         */
        private static void appendHtmlFooter(List<String> html) {
            addHtml(html, HTML_LTRS);
            addHtml(html, HTML_L10);
            addHtml(html, HTML_L75);
            addHtml(html, HTML_LTDE);
            addHtml(html, HTML_LTRE);
            addHtml(html, HTML_L78);
            addHtml(html, HTML_L79);
            addHtml(html, HTML_L80);
        }

        /**
         * Appends a single HTML line, padded/truncated to exactly {@link #HTML_RECORD_LENGTH} bytes.
         * COBOL {@code WRITE FD-HTMLFILE-REC FROM ...} moves the source into the 100-byte record,
         * left-justified and space-filled, so any content longer than 100 bytes is truncated.
         *
         * @param html    the HTML line accumulator
         * @param content the rendered line content (before fixed-width normalization)
         */
        private static void addHtml(List<String> html, String content) {
            html.add(fixed(content, HTML_RECORD_LENGTH));
        }

        /**
         * Builds {@code ST-NAME} exactly as {@code 5000-CREATE-STATEMENT} (L462-L469):
         * {@code STRING first DELIMITED BY ' ', ' ', middle DELIMITED BY ' ', ' ', last DELIMITED BY
         * ' ', ' '}. Each name token is the field content up to its first space; a single space
         * separates the tokens and one trailing space follows the last token. The result is
         * space-padded (or truncated) to {@link #NAME_LEN} characters.
         *
         * @param customer the customer record
         * @return the 75-character {@code ST-NAME} field
         */
        private static String buildName(Customer customer) {
            String assembled = tokenBeforeSpace(customer.getFirstName()) + " "
                    + tokenBeforeSpace(customer.getMiddleName()) + " "
                    + tokenBeforeSpace(customer.getLastName()) + " ";
            return pad(assembled, NAME_LEN);
        }

        /**
         * Builds {@code ST-ADD3} exactly as {@code 5000-CREATE-STATEMENT} (L472-L481):
         * {@code STRING addr3 ' ' state ' ' country ' ' zip ' '}, each source {@code DELIMITED BY ' '}.
         * The result is space-padded (or truncated) to {@link #ADDR3_LEN} characters.
         *
         * @param customer the customer record
         * @return the 80-character {@code ST-ADD3} field
         */
        private static String buildAddr3(Customer customer) {
            String assembled = tokenBeforeSpace(customer.getAddrLine3()) + " "
                    + tokenBeforeSpace(customer.getAddrStateCd()) + " "
                    + tokenBeforeSpace(customer.getAddrCountryCd()) + " "
                    + tokenBeforeSpace(customer.getAddrZip()) + " ";
            return pad(assembled, ADDR3_LEN);
        }

        /**
         * Returns the customer FICO score as a primitive, treating {@code null} as COBOL zero.
         *
         * @param customer the customer record
         * @return the FICO score, or {@code 0} when absent
         */
        private static int fico(Customer customer) {
            Integer score = customer.getFicoCreditScore();
            return (score == null) ? 0 : score;
        }

        /**
         * Reproduces a COBOL {@code MOVE} of an unsigned display numeric ({@code PIC 9(digits)}) into a
         * 20-character alphanumeric field: the value is zero-padded on the left to {@code digits}
         * positions (low-order {@code digits} kept if longer), then left-justified and space-filled to
         * {@link #DISPLAY_FIELD_LEN}. Used for {@code ST-ACCT-ID} ({@code 9(11)}) and
         * {@code ST-FICO-SCORE} ({@code 9(3)}).
         *
         * @param value  the numeric value, may be {@code null} (treated as zero)
         * @param digits the number of {@code PIC 9} digit positions in the source field
         * @return the 20-character display field
         */
        private static String editDisplayNumber(Long value, int digits) {
            long magnitude = (value == null) ? 0L : Math.abs(value);
            String raw = Long.toString(magnitude);
            if (raw.length() > digits) {
                raw = raw.substring(raw.length() - digits);
            } else {
                raw = "0".repeat(digits - raw.length()) + raw;
            }
            return pad(raw, DISPLAY_FIELD_LEN);
        }

        /**
         * Overload for an {@code int} display numeric (FICO score); see
         * {@link #editDisplayNumber(Long, int)}.
         *
         * @param value  the numeric value
         * @param digits the number of {@code PIC 9} digit positions
         * @return the 20-character display field
         */
        private static String editDisplayNumber(int value, int digits) {
            return editDisplayNumber((long) value, digits);
        }

        /**
         * Reproduces COBOL numeric editing for {@code PIC 9(9).99-} ({@code ST-CURR-BAL}): nine integer
         * positions with <em>leading zeros preserved</em>, a literal decimal point, two decimal digits,
         * and a trailing sign position ({@code '-'} when negative, space otherwise). The integer part
         * is truncated to its low-order nine digits if larger (COBOL {@code MOVE} truncation). Total
         * width is 13.
         *
         * @param amount the monetary value (non-{@code null}); scale is normalized to two
         * @return the 13-character edited field
         */
        private static String editLeadingZero(BigDecimal amount) {
            BigDecimal scaled = amount.setScale(CobolDecimal.MONEY_SCALE, RoundingMode.DOWN);
            boolean negative = scaled.signum() < 0;
            BigDecimal abs = scaled.abs();
            BigInteger intPart = abs.toBigInteger();
            int frac = abs.subtract(new BigDecimal(intPart)).movePointRight(2).intValueExact();
            String intDigits = intPart.toString();
            if (intDigits.length() > 9) {
                intDigits = intDigits.substring(intDigits.length() - 9);
            } else {
                intDigits = "0".repeat(9 - intDigits.length()) + intDigits;
            }
            return intDigits + "." + String.format("%02d", frac) + (negative ? "-" : " ");
        }

        /**
         * Reproduces COBOL numeric editing for {@code PIC Z(9).99-} ({@code ST-TRANAMT},
         * {@code ST-TOTAL-TRAMT}): nine integer positions with <em>leading zeros suppressed to
         * spaces</em> (a zero value yields nine blanks), a literal decimal point, two decimal digits,
         * and a trailing sign position ({@code '-'} when negative, space otherwise). The integer part
         * is truncated to its low-order nine digits if larger. Total width is 13.
         *
         * @param amount the monetary value (non-{@code null}); scale is normalized to two
         * @return the 13-character edited field
         */
        private static String editZeroSuppressed(BigDecimal amount) {
            BigDecimal scaled = amount.setScale(CobolDecimal.MONEY_SCALE, RoundingMode.DOWN);
            boolean negative = scaled.signum() < 0;
            BigDecimal abs = scaled.abs();
            BigInteger intPart = abs.toBigInteger();
            int frac = abs.subtract(new BigDecimal(intPart)).movePointRight(2).intValueExact();
            String intField;
            if (intPart.signum() == 0) {
                intField = repeat(' ', 9);
            } else {
                String intDigits = intPart.toString();
                if (intDigits.length() > 9) {
                    intDigits = intDigits.substring(intDigits.length() - 9);
                }
                intField = repeat(' ', 9 - intDigits.length()) + intDigits;
            }
            return intField + "." + String.format("%02d", frac) + (negative ? "-" : " ");
        }

        /**
         * COBOL {@code STRING ... DELIMITED BY ' '}: returns the prefix of {@code value} up to (not
         * including) the first space. A {@code null} value yields the empty string. Reproduces the
         * name/address tokenization in {@code 5000-CREATE-STATEMENT}.
         *
         * @param value the source field, may be {@code null}
         * @return the token preceding the first space
         */
        private static String tokenBeforeSpace(String value) {
            String s = safe(value);
            int idx = s.indexOf(' ');
            return (idx < 0) ? s : s.substring(0, idx);
        }

        /**
         * COBOL {@code STRING ... DELIMITED BY '  '}: returns the prefix of {@code value} up to (not
         * including) the first occurrence of two consecutive spaces. A {@code null} value yields the
         * empty string. Reproduces the name/address emission in {@code 5200-WRITE-HTML-NMADBS}.
         *
         * @param value the source field, may be {@code null}
         * @return the prefix preceding the first double space
         */
        private static String beforeDoubleSpace(String value) {
            String s = safe(value);
            int idx = s.indexOf("  ");
            return (idx < 0) ? s : s.substring(0, idx);
        }

        /**
         * Null-safe identity: returns {@code ""} for {@code null}, otherwise {@code value} unchanged.
         *
         * @param value the value that may be {@code null}
         * @return {@code value}, or {@code ""} when {@code null}
         */
        private static String safe(String value) {
            return (value == null) ? "" : value;
        }

        /**
         * Returns a string of {@code count} repetitions of {@code c}.
         *
         * @param c     the character to repeat
         * @param count the repetition count (must be {@code >= 0})
         * @return the repeated string
         */
        private static String repeat(char c, int count) {
            return String.valueOf(c).repeat(count);
        }

        /**
         * Reproduces COBOL {@code WRITE ... FROM} / {@code MOVE} into a fixed-length record: pads
         * {@code value} on the right with spaces, or truncates it, so the result is exactly
         * {@code width} characters (left-justified, space-filled).
         *
         * @param value the content to normalize
         * @param width the exact target width
         * @return a string of exactly {@code width} characters
         */
        private static String fixed(String value, int width) {
            return pad(value, width);
        }

        /**
         * Pads (right, with spaces) or truncates {@code value} to exactly {@code width} characters.
         *
         * @param value the content, may be {@code null} (treated as {@code ""})
         * @param width the exact target width
         * @return a string of exactly {@code width} characters
         */
        private static String pad(String value, int width) {
            String s = safe(value);
            if (s.length() == width) {
                return s;
            }
            if (s.length() > width) {
                return s.substring(0, width);
            }
            StringBuilder sb = new StringBuilder(width);
            sb.append(s);
            while (sb.length() < width) {
                sb.append(' ');
            }
            return sb.toString();
        }
    }
}
