package com.aws.carddemo.batch;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.batch.item.data.builder.RepositoryItemReaderBuilder;
import org.springframework.batch.item.file.FlatFileItemWriter;
import org.springframework.batch.item.file.builder.FlatFileItemWriterBuilder;
import org.springframework.batch.item.file.transform.PassThroughLineAggregator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;

import com.aws.carddemo.domain.TransactionCategoryBalance;
import com.aws.carddemo.repository.TransactionCategoryBalanceRepository;
import com.aws.carddemo.util.CobolDecimal;
import com.aws.carddemo.util.FixedWidthRecordMapper;
import com.aws.carddemo.util.FixedWidthRecordMapper.FieldDef;
import com.aws.carddemo.util.batch.AtomicFileStepPublisher;
import com.aws.carddemo.util.batch.FixedBlockLineAggregator;

/**
 * Spring Batch job configuration translating the mainframe transaction-category-balance report job
 * {@code PRTCATBL} (with its report-file generation-data-group defined by {@code REPTFILE}).
 *
 * <p>Origin: {@code legacy/jcl/PRTCATBL.jcl} and {@code legacy/jcl/REPTFILE.jcl} (source branch
 * {@code app/jcl/PRTCATBL.jcl}, {@code app/jcl/REPTFILE.jcl}). Implements AAP &sect;0.4.1 (JCL job
 * &rarr; Spring Batch {@link Job}), &sect;0.6.1 (COBOL {@code COMP-3}/{@code S9(n)V99} &rarr;
 * {@link BigDecimal} with statement-level rounding), &sect;0.6.3 (JCL/JES2 &rarr; chunk-oriented
 * Spring Batch), and &sect;0.6.6 (deterministic bytewise/C-collation ordering).</p>
 *
 * <h2>What the legacy job did</h2>
 * <p>{@code PRTCATBL.jcl} produced a printed transaction-category-balance report in three JCL steps:</p>
 * <ol>
 *   <li><strong>{@code DELDEF} ({@code PGM=IEFBR14})</strong> &mdash; deleted any pre-existing
 *       {@code AWS.M2.CARDDEMO.TCATBALF.REPT} dataset. Pure dataset housekeeping with no business
 *       logic; not applicable under Spring (the writer creates/overwrites the target file).</li>
 *   <li><strong>{@code STEP05R} ({@code PROC=REPROC})</strong> &mdash; unloaded the VSAM KSDS
 *       {@code AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS} to a sequential backup
 *       {@code TCATBALF.BKUP(+1)} ({@code LRECL=50,RECFM=FB}) purely so the sequential {@code SORT}
 *       utility in the next step had a flat input. Under Spring this unload has no equivalent: the
 *       reader streams the migrated {@code TCATBALF} table directly from PostgreSQL and the database
 *       performs the ordering, so no intermediate backup dataset is materialized (the same rationale
 *       by which {@code OPENFIL}/{@code CLOSEFIL} are not applicable under a managed connection
 *       pool).</li>
 *   <li><strong>{@code STEP10R} ({@code PGM=SORT})</strong> &mdash; the business step. It sorted the
 *       records ascending by {@code (TRANCAT-ACCT-ID, TRANCAT-TYPE-CD, TRANCAT-CD)} and reformatted
 *       each into a 40-byte report line ({@code SORTOUT LRECL=40, RECFM=FB}) via the {@code OUTREC}
 *       specification below. This is the behavior reproduced by the single chunk-oriented step of
 *       this configuration.</li>
 * </ol>
 *
 * <p>{@code REPTFILE.jcl} ran {@code IDCAMS DEFINE GENERATIONDATAGROUP (NAME(AWS.M2.CARDDEMO.TRANREPT)
 * LIMIT(10))} to define the report generation-data-group. There is no GDG concept under Spring, so
 * the {@code DEFINE GDG} maps to job-instance versioning: each run writes to the {@code outputPath}
 * job parameter, or, when it is absent, to a timestamped file so that "a new generation per run" is
 * preserved without a GDG base (see {@link #resolveReportFile(String)}).</p>
 *
 * <h2>The {@code OUTREC} / {@code EDIT} contract reproduced exactly (40 bytes)</h2>
 * <p>The legacy {@code SORT} card is:</p>
 * <pre>
 *   SORT FIELDS=(TRANCAT-ACCT-ID,A,TRANCAT-TYPE-CD,A,TRANCAT-CD,A)
 *   OUTREC FIELDS=(TRANCAT-ACCT-ID,X, TRANCAT-TYPE-CD,X, TRANCAT-CD,X,
 *                  TRAN-CAT-BAL,EDIT=(TTTTTTTTT.TT),9X)
 * </pre>
 * <p>with {@code SYMNAMES}: {@code TRANCAT-ACCT-ID,1,11,ZD}; {@code TRANCAT-TYPE-CD,12,2,CH};
 * {@code TRANCAT-CD,14,4,ZD}; {@code TRAN-CAT-BAL,18,11,ZD}. Each field is copied unedited except the
 * balance, which is edited. The resulting fixed layout is:</p>
 * <table border="1">
 *   <caption>PRTCATBL report record layout (40 bytes)</caption>
 *   <tr><th>Bytes</th><th>Field</th><th>Rendering</th></tr>
 *   <tr><td>1-11</td><td>{@code TRANCAT-ACCT-ID}</td><td>11 digits, zero-padded (unsigned
 *       {@code PIC 9(11)} zoned decimal copied verbatim)</td></tr>
 *   <tr><td>12</td><td>{@code X}</td><td>1 space separator</td></tr>
 *   <tr><td>13-14</td><td>{@code TRANCAT-TYPE-CD}</td><td>2 characters ({@code PIC X(02)},
 *       left-justified)</td></tr>
 *   <tr><td>15</td><td>{@code X}</td><td>1 space separator</td></tr>
 *   <tr><td>16-19</td><td>{@code TRANCAT-CD}</td><td>4 digits, zero-padded (unsigned
 *       {@code PIC 9(04)} zoned decimal copied verbatim)</td></tr>
 *   <tr><td>20</td><td>{@code X}</td><td>1 space separator</td></tr>
 *   <tr><td>21-32</td><td>{@code TRAN-CAT-BAL}</td><td>edited {@code TTTTTTTTT.TT} (12 characters)</td></tr>
 *   <tr><td>33-40</td><td>{@code 9X}</td><td>trailing spaces</td></tr>
 * </table>
 * <p>The unedited fields plus the 12-byte edited balance occupy 32 bytes; the {@code OUTREC}
 * requests {@code 9X} trailing blanks, which would make 41 bytes, but the {@code SORTOUT}
 * {@code LRECL=40} caps the record at 40 &mdash; DFSORT keeps the leading 40 bytes, so exactly 8 of
 * the trailing pad blanks survive. This class therefore emits {@value #TRAILING_PAD_WIDTH} trailing
 * spaces so every record is exactly {@value #REPORT_RECORD_LENGTH} bytes.</p>
 *
 * <h2>The {@code EDIT=(TTTTTTTTT.TT)} balance mask reproduced exactly</h2>
 * <p>In a DFSORT edit pattern, {@code T} is a <em>significant</em> digit selector: the digit is
 * <strong>always</strong> shown (0-9), so leading zeros are <strong>kept</strong> (unlike the
 * {@code I} selector, which suppresses leading zeros to blanks). The {@code .} is a literal. There is
 * <strong>no {@code S} sign selector</strong> and no {@code SIGNS=} operand, so no sign character is
 * emitted &mdash; the magnitude (absolute value) is printed. Because {@code TRAN-CAT-BAL} is
 * {@code PIC S9(09)V99}, the mask has 9 integer plus 2 fractional {@code T}s, rendering as
 * {@code DDDDDDDDD.DD} (12 characters). Examples: {@code 504.77 -> "000000504.77"};
 * {@code 0.00 -> "000000000.00"}; {@code -504.77 -> "000000504.77"} (sign dropped). See
 * {@link #formatEditedBalance(BigDecimal)}.</p>
 *
 * <h2>Decimal fidelity (AAP &sect;0.6.1)</h2>
 * <p>The balance is a fixed-scale packed decimal handled exclusively with {@link BigDecimal}; no
 * {@code float}/{@code double} is used anywhere. COBOL applied statement-level truncation (no
 * {@code ROUNDED}), reproduced by {@link CobolDecimal#money(BigDecimal)}
 * ({@link java.math.RoundingMode#DOWN}) before rendering.</p>
 *
 * <h2>Ordering (AAP &sect;0.6.6)</h2>
 * <p>The reader requests {@code ORDER BY acctId, typeCd, catCd} through Spring Data, matching the
 * legacy {@code SORT FIELDS}. Deterministic bytewise ordering relies on the PostgreSQL {@code C}
 * (POSIX) collation provisioned by Flyway for the {@code CHAR} key column; account id and category
 * code are numeric and order identically. The sort-field map is a {@link LinkedHashMap} so the field
 * priority (account id, then type code, then category code) is preserved.</p>
 *
 * <h2>Design notes</h2>
 * <ul>
 *   <li><strong>No {@code @EnableBatchProcessing} (AAP binding constraint).</strong> This class
 *       relies on Spring Boot's Batch auto-configuration, which supplies the persistent, restartable
 *       {@link JobRepository} and the {@link PlatformTransactionManager} injected through the
 *       constructor &mdash; the same convention as the sibling batch configurations. It does not
 *       import or depend on {@code config/BatchConfig}.</li>
 *   <li><strong>{@link FixedWidthRecordMapper} is not constructor-injected.</strong> It is an
 *       immutable, layout-specific value object (not a Spring bean and with no no-arg form), so the
 *       40-byte report layout is built here as {@link #REPORT_MAPPER} rather than injected. A
 *       startup assertion guarantees the layout is exactly {@value #REPORT_RECORD_LENGTH} bytes.</li>
 *   <li><strong>{@link CobolDecimal} is not constructor-injected.</strong> It is a stateless static
 *       utility (its constructor is private), so it is used via its static methods.</li>
 * </ul>
 * <p>The migration rationale and any deviation from a literal reading of the requirements are
 * recorded in {@code docs/decision-log.md} rather than in code comments (Explainability rule).</p>
 *
 * <p>This configuration is stateless and thread-safe: it holds only immutable collaborators, the
 * report mapper is immutable, and the {@code @StepScope} reader and writer read their per-execution
 * inputs (paging cursor, output path) exclusively from the step/job scope.</p>
 */
@Configuration
public class CategoryBalancePrintJobConfig {

    /** Logger used to record the resolved report output location. */
    private static final Logger LOGGER = LoggerFactory.getLogger(CategoryBalancePrintJobConfig.class);

    /** Bean/registry name of the Spring Batch {@link Job} translating {@code PRTCATBL}. */
    static final String JOB_NAME = "categoryBalancePrintJob";

    /** Name of the single chunk-oriented step within {@link #JOB_NAME}. */
    static final String STEP_NAME = "categoryBalancePrintStep";

    /** Name of the {@link RepositoryItemReader} bean (also its {@code ExecutionContext} key prefix). */
    static final String READER_NAME = "categoryBalanceReportReader";

    /** Name of the {@link FlatFileItemWriter} bean (also its {@code ExecutionContext} key prefix). */
    static final String WRITER_NAME = "categoryBalanceReportWriter";

    /** Job-parameter key supplying the report output path (the {@code REPT} dataset equivalent). */
    static final String OUTPUT_PATH_PARAMETER = "outputPath";

    /** Base filename used for the timestamped default output when {@link #OUTPUT_PATH_PARAMETER} is absent. */
    static final String DEFAULT_REPORT_BASENAME = "TCATBALF.REPT";

    /** Total report record length in bytes ({@code SORTOUT LRECL=40}). */
    static final int REPORT_RECORD_LENGTH = 40;

    /** Chunk size and reader page size; a report is read-mostly so a moderate page keeps memory flat. */
    private static final int CHUNK_SIZE = 100;

    /**
     * Report-mapper field label for the account id; also the JPA property name used for ordering
     * (must equal {@link TransactionCategoryBalance}'s {@code acctId} property).
     */
    private static final String FIELD_ACCT_ID = "acctId";

    /**
     * Report-mapper field label for the transaction type code; also the JPA property name used for
     * ordering (must equal {@link TransactionCategoryBalance}'s {@code typeCd} property).
     */
    private static final String FIELD_TYPE_CD = "typeCd";

    /**
     * Report-mapper field label for the transaction category code; also the JPA property name used
     * for ordering (must equal {@link TransactionCategoryBalance}'s {@code catCd} property).
     */
    private static final String FIELD_CAT_CD = "catCd";

    /** Report-mapper field label for the edited balance column (a display label only, not a JPA property). */
    private static final String FIELD_BALANCE = "balanceEdited";

    /** {@code TRANCAT-ACCT-ID} width ({@code PIC 9(11)}, copied unedited). */
    private static final int ACCT_ID_WIDTH = 11;

    /** {@code TRANCAT-TYPE-CD} width ({@code PIC X(02)}, copied unedited). */
    private static final int TYPE_CD_WIDTH = 2;

    /** {@code TRANCAT-CD} width ({@code PIC 9(04)}, copied unedited). */
    private static final int CAT_CD_WIDTH = 4;

    /** Width of each {@code X} separator inserted by the {@code OUTREC}. */
    private static final int SEPARATOR_WIDTH = 1;

    /** Number of integer digit selectors in {@code EDIT=(TTTTTTTTT.TT)}. */
    private static final int BALANCE_INTEGER_DIGITS = 9;

    /** Number of fractional digit selectors in {@code EDIT=(TTTTTTTTT.TT)}. */
    private static final int BALANCE_FRACTION_DIGITS = 2;

    /** Edited-balance field width: 9 integer + 1 literal {@code .} + 2 fractional = 12 bytes. */
    private static final int BALANCE_EDIT_WIDTH = BALANCE_INTEGER_DIGITS + 1 + BALANCE_FRACTION_DIGITS;

    /**
     * Exclusive upper bound on the integer part ({@code 10^9}); a magnitude at or above this cannot
     * fit the 9 integer digit selectors of {@code EDIT=(TTTTTTTTT.TT)} and indicates data outside the
     * {@code S9(9)V99} domain.
     */
    private static final long BALANCE_INTEGER_LIMIT = 1_000_000_000L;

    /**
     * {@link String#format} pattern reproducing {@code EDIT=(TTTTTTTTT.TT)}: the integer part is
     * zero-padded to {@value #BALANCE_INTEGER_DIGITS} digits (all-{@code T} keeps leading zeros) and
     * the fractional part to {@value #BALANCE_FRACTION_DIGITS} digits, separated by the literal
     * {@code .}. Derived from the digit-count constants so the two stay in lock-step.
     */
    private static final String BALANCE_EDIT_FORMAT =
            "%0" + BALANCE_INTEGER_DIGITS + "d." + "%0" + BALANCE_FRACTION_DIGITS + "d";

    /**
     * Trailing pad width. The {@code OUTREC} requests {@code 9X} but {@code SORTOUT LRECL=40} caps the
     * 41-byte reformatted record at 40, so exactly 8 pad blanks survive
     * ({@value #REPORT_RECORD_LENGTH} total &minus; 32 content bytes).
     */
    private static final int TRAILING_PAD_WIDTH =
            REPORT_RECORD_LENGTH
                    - (ACCT_ID_WIDTH + SEPARATOR_WIDTH + TYPE_CD_WIDTH + SEPARATOR_WIDTH
                            + CAT_CD_WIDTH + SEPARATOR_WIDTH + BALANCE_EDIT_WIDTH);

    /**
     * Immutable, byte-exact serializer for the 40-byte {@code PRTCATBL} report record. Built once from
     * the fixed {@code OUTREC} layout; {@link FixedWidthRecordMapper} is thread-safe.
     */
    private static final FixedWidthRecordMapper REPORT_MAPPER = buildReportMapper();

    static {
        if (REPORT_MAPPER.getRecordLength() != REPORT_RECORD_LENGTH) {
            throw new IllegalStateException(
                    "PRTCATBL report layout must be exactly " + REPORT_RECORD_LENGTH
                            + " bytes (SORTOUT LRECL=40) but was " + REPORT_MAPPER.getRecordLength());
        }
    }

    /** Auto-configured Spring Batch job repository used to build the step and the job. */
    private final JobRepository jobRepository;

    /** Auto-configured transaction manager bounding the chunk commit interval. */
    private final PlatformTransactionManager transactionManager;

    /** Read-only access to the migrated {@code TCATBALF} table (source of the report rows). */
    private final TransactionCategoryBalanceRepository categoryBalanceRepository;

    /**
     * Shared listener publishing the report file atomically from a deterministic in-progress temp file
     * on {@code COMPLETED} (review findings&nbsp;#18/#19).
     */
    private final AtomicFileStepPublisher atomicFileStepPublisher;

    /**
     * Creates the configuration with the collaborators supplied by Spring Boot's Batch
     * auto-configuration and component scanning.
     *
     * @param jobRepository             the auto-configured Spring Batch {@link JobRepository}
     * @param transactionManager        the auto-configured {@link PlatformTransactionManager}
     * @param categoryBalanceRepository  the repository over the migrated {@code TCATBALF} table
     * @param atomicFileStepPublisher   shared safe-path atomic-publication step listener
     */
    public CategoryBalancePrintJobConfig(JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            TransactionCategoryBalanceRepository categoryBalanceRepository,
            AtomicFileStepPublisher atomicFileStepPublisher) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.categoryBalanceRepository = categoryBalanceRepository;
        this.atomicFileStepPublisher = atomicFileStepPublisher;
    }

    /**
     * Builds the fixed-width mapper for the 40-byte report record, mirroring the {@code PRTCATBL}
     * {@code OUTREC} layout field-for-field. The single-byte {@code FILLER} separators and the
     * trailing pad default to spaces, reproducing the {@code X} / {@code 9X} blanks.
     *
     * @return the immutable report-record mapper
     */
    private static FixedWidthRecordMapper buildReportMapper() {
        return new FixedWidthRecordMapper(List.of(
                FieldDef.numeric(FIELD_ACCT_ID, ACCT_ID_WIDTH),
                FieldDef.filler(SEPARATOR_WIDTH),
                FieldDef.text(FIELD_TYPE_CD, TYPE_CD_WIDTH),
                FieldDef.filler(SEPARATOR_WIDTH),
                FieldDef.numeric(FIELD_CAT_CD, CAT_CD_WIDTH),
                FieldDef.filler(SEPARATOR_WIDTH),
                FieldDef.text(FIELD_BALANCE, BALANCE_EDIT_WIDTH),
                FieldDef.filler(TRAILING_PAD_WIDTH)));
    }

    /**
     * The {@code @StepScope} reader that streams the migrated {@code TCATBALF} table ordered by
     * {@code (acctId, typeCd, catCd)} &mdash; the Spring Data equivalent of the legacy
     * {@code SORT FIELDS=(TRANCAT-ACCT-ID,A,TRANCAT-TYPE-CD,A,TRANCAT-CD,A)}. Paging with the sort
     * calls {@link TransactionCategoryBalanceRepository}'s inherited {@code findAll(Pageable)}; a
     * reader read that returns {@code null} signals end of file (normal completion). The sort-field
     * map is a {@link LinkedHashMap} so the field priority is preserved (unordered maps such as
     * {@code Map.of} would randomize it).
     *
     * @return the {@code categoryBalanceReportReader}
     */
    @Bean
    @StepScope
    public RepositoryItemReader<TransactionCategoryBalance> categoryBalanceReportReader() {
        Map<String, Sort.Direction> sorts = new LinkedHashMap<>();
        sorts.put(FIELD_ACCT_ID, Sort.Direction.ASC);
        sorts.put(FIELD_TYPE_CD, Sort.Direction.ASC);
        sorts.put(FIELD_CAT_CD, Sort.Direction.ASC);
        return new RepositoryItemReaderBuilder<TransactionCategoryBalance>()
                .name(READER_NAME)
                .repository(categoryBalanceRepository)
                .methodName("findAll")
                .sorts(sorts)
                .pageSize(CHUNK_SIZE)
                .build();
    }

    /**
     * The stateless processor that reformats one {@link TransactionCategoryBalance} row into a
     * 40-byte report line matching the {@code PRTCATBL} {@code OUTREC} layout and the
     * {@code EDIT=(TTTTTTTTT.TT)} balance mask.
     *
     * @return the {@code categoryBalanceReportProcessor}
     */
    @Bean
    public ItemProcessor<TransactionCategoryBalance, String> categoryBalanceReportProcessor() {
        return CategoryBalancePrintJobConfig::toReportLine;
    }

    /**
     * The {@code @StepScope} writer that appends each 40-byte report line to the report file.
     *
     * <p>The output path is bound from the {@code outputPath} job parameter (the {@code REPT} dataset
     * equivalent). When it is absent or blank, a timestamped file is used so each run yields a fresh
     * "generation", reproducing the {@code REPTFILE.jcl} generation-data-group without a GDG base.
     * The parent directory is created if necessary, and an existing target is overwritten so a run
     * always produces a complete report.</p>
     *
     * <p>The report is written in <em>undelimited</em> {@code RECFM=FB} framing (review
     * finding&nbsp;#17): {@code lineSeparator("")} emits the 40-byte records back-to-back and a
     * {@link FixedBlockLineAggregator} rejects any line that is not exactly
     * {@value #REPORT_RECORD_LENGTH} bytes, matching the {@code SORTOUT LRECL=40} dataset byte-for-byte.
     * The path is validated through the safe-root resolver and the writer streams into a deterministic
     * in-progress temp file that {@link #atomicFileStepPublisher} atomically publishes on
     * {@code COMPLETED} (findings&nbsp;#18/#19).</p>
     *
     * <p>Because the bean is {@code @StepScope}, the {@code @Value} expression and the
     * {@code stepExecution} are resolved lazily per step execution; {@link #categoryBalancePrintStep()}
     * passes {@code null} placeholders that the scoped proxy replaces with the real values at run
     * time.</p>
     *
     * @param outputPath    the report output path, bound from job parameter {@code outputPath};
     *                      {@code null}/blank selects the timestamped default
     * @param stepExecution the running step execution (source of the atomic-publication temp/target)
     * @return the {@code categoryBalanceReportWriter}
     */
    @Bean
    @StepScope
    public FlatFileItemWriter<String> categoryBalanceReportWriter(
            @Value("#{jobParameters['outputPath']}") String outputPath,
            @Value("#{stepExecution}") StepExecution stepExecution) {
        Path reportFile = resolveReportFile(outputPath);
        boolean usingDefault = outputPath == null || outputPath.isBlank();
        LOGGER.info("PRTCATBL category-balance report (REPT, LRECL={}) will be written to [{}] "
                        + "(job parameter '{}' {})",
                REPORT_RECORD_LENGTH, reportFile, OUTPUT_PATH_PARAMETER,
                usingDefault ? "absent; using timestamped default" : "supplied");
        Path temp = atomicFileStepPublisher.prepare(reportFile.toString(), stepExecution);
        return new FlatFileItemWriterBuilder<String>()
                .name(WRITER_NAME)
                .resource(new FileSystemResource(temp.toFile()))
                .lineAggregator(new FixedBlockLineAggregator<>(
                        new PassThroughLineAggregator<>(), REPORT_RECORD_LENGTH,
                        StandardCharsets.ISO_8859_1))
                .encoding(StandardCharsets.ISO_8859_1.name())
                .lineSeparator("")
                .shouldDeleteIfExists(true)
                .build();
    }

    /**
     * The single chunk-oriented step reproducing {@code PRTCATBL} {@code STEP10R}: read the ordered
     * category-balance rows, reformat each into a 40-byte report line, and write them to the report
     * file, all within the auto-configured transaction boundary. The {@code null} argument is a
     * placeholder that the {@code @StepScope} writer proxy resolves from the {@code outputPath} job
     * parameter at run time.
     *
     * @return the {@code categoryBalancePrintStep} {@link Step}
     */
    @Bean
    public Step categoryBalancePrintStep() {
        return new StepBuilder(STEP_NAME, jobRepository)
                .<TransactionCategoryBalance, String>chunk(CHUNK_SIZE, transactionManager)
                .reader(categoryBalanceReportReader())
                .processor(categoryBalanceReportProcessor())
                .writer(categoryBalanceReportWriter(null, null))
                .listener(atomicFileStepPublisher)
                .build();
    }

    /**
     * The Spring Batch {@link Job} corresponding to legacy JCL job {@code PRTCATBL}. It consists of
     * the single {@link #categoryBalancePrintStep()} and completes with batch status
     * {@code COMPLETED} once the whole {@code TCATBALF} table has been reported.
     *
     * @return the {@code categoryBalancePrintJob} {@link Job}
     */
    @Bean
    public Job categoryBalancePrintJob() {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(categoryBalancePrintStep())
                .build();
    }

    /**
     * Reformats one category-balance row into its 40-byte report line, reproducing the
     * {@code PRTCATBL} {@code OUTREC} layout: account id (11), space, type code (2), space, category
     * code (4), space, edited balance (12), then trailing spaces. The bytes are produced through
     * {@link #REPORT_MAPPER} and decoded with the mapper's single-byte charset so the returned
     * {@link String} is exactly {@value #REPORT_RECORD_LENGTH} characters.
     *
     * @param row the source row (never {@code null} in a chunk pipeline)
     * @return the 40-character report line
     */
    static String toReportLine(TransactionCategoryBalance row) {
        long acctId = requireKeyComponent(row.getAcctId(), FIELD_ACCT_ID);
        long catCd = requireKeyComponent(row.getCatCd(), FIELD_CAT_CD);
        String typeCd = row.getTypeCd() == null ? "" : row.getTypeCd();
        String editedBalance = formatEditedBalance(row.getBalance());
        byte[] record = REPORT_MAPPER.newRecord()
                .setNumeric(FIELD_ACCT_ID, acctId)
                .setText(FIELD_TYPE_CD, typeCd)
                .setNumeric(FIELD_CAT_CD, catCd)
                .setText(FIELD_BALANCE, editedBalance)
                .build();
        return new String(record, REPORT_MAPPER.getCharset());
    }

    /**
     * Formats {@code TRAN-CAT-BAL} ({@code PIC S9(09)V99}) exactly as DFSORT's
     * {@code EDIT=(TTTTTTTTT.TT)} does: every digit selector is {@code T}, so all 11 digits appear
     * with leading zeros kept; the {@code .} is a literal; and the absence of an {@code S} selector
     * (and of {@code SIGNS=}) means no sign is emitted, so the magnitude is printed. COBOL truncation
     * (no {@code ROUNDED}) is applied via {@link CobolDecimal#money(BigDecimal)} before rendering, and
     * a {@code null} balance is treated as zero. All arithmetic uses {@link BigDecimal}; no
     * {@code float}/{@code double} is involved.
     *
     * @param rawBalance the stored balance (may be {@code null})
     * @return the 12-character edited balance, e.g. {@code "000000504.77"}
     * @throws IllegalStateException if the magnitude's integer part exceeds
     *         {@value #BALANCE_INTEGER_DIGITS} digits (outside the {@code S9(9)V99} domain)
     */
    static String formatEditedBalance(BigDecimal rawBalance) {
        BigDecimal magnitude = CobolDecimal.money(CobolDecimal.nullToZero(rawBalance)).abs();
        long totalCents = magnitude.movePointRight(BALANCE_FRACTION_DIGITS).longValueExact();
        long integerPart = totalCents / 100L;
        long fractionPart = totalCents % 100L;
        if (integerPart >= BALANCE_INTEGER_LIMIT) {
            throw new IllegalStateException(
                    "TRAN-CAT-BAL integer part exceeds " + BALANCE_INTEGER_DIGITS
                            + " digits (COBOL S9(9)V99); cannot render in the " + BALANCE_EDIT_WIDTH
                            + "-byte EDIT=(TTTTTTTTT.TT) field: " + magnitude.toPlainString());
        }
        return String.format(Locale.ROOT, BALANCE_EDIT_FORMAT, integerPart, fractionPart);
    }

    /**
     * Returns the {@code long} value of a mandatory composite-key component, failing fast if it is
     * {@code null}. A persisted {@code TCATBALF} row always has a complete key
     * {@code (acctId, typeCd, catCd)}; a {@code null} component indicates corrupt data rather than a
     * normal condition.
     *
     * @param value the key component (account id or category code)
     * @param field the field name, for the diagnostic message
     * @return the component's {@code long} value
     * @throws IllegalStateException if {@code value} is {@code null}
     */
    private static long requireKeyComponent(Number value, String field) {
        if (value == null) {
            throw new IllegalStateException(
                    "TCATBAL row has a null key component [" + field
                            + "]; a persisted transaction-category-balance row must have a complete key");
        }
        return value.longValue();
    }

    /**
     * Resolves the report output file. When {@code outputPath} is supplied it is used verbatim
     * (trimmed); otherwise a timestamped file under the system temporary directory is returned so each
     * run produces a fresh "generation", reproducing the {@code REPTFILE.jcl} generation-data-group
     * behavior without a GDG base.
     *
     * @param outputPath the job-parameter output path, or {@code null}/blank for the default
     * @return the resolved report file path
     */
    private static Path resolveReportFile(String outputPath) {
        if (outputPath != null && !outputPath.isBlank()) {
            return Path.of(outputPath.trim());
        }
        String generation = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS", Locale.ROOT)
                .format(LocalDateTime.now());
        return Path.of(System.getProperty("java.io.tmpdir"), DEFAULT_REPORT_BASENAME + "." + generation);
    }
}
