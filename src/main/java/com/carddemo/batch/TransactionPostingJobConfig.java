package com.carddemo.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.FlatFileItemWriter;
import org.springframework.batch.item.file.LineMapper;
import org.springframework.batch.item.file.transform.PassThroughLineAggregator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.transaction.PlatformTransactionManager;

import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import com.carddemo.repository.TransactionRepository;

/**
 * Spring Batch&nbsp;5 job/step <strong>assembly</strong> for the <strong>daily transaction
 * posting</strong> job &mdash; the Java port of the legacy COBOL batch program
 * {@code app/cbl/CBTRN02C.cbl} (the JCL-scheduled {@code POSTTRAN}/{@code TRANBKP} batch step).
 *
 * <h2>Responsibility &mdash; wiring only, no business logic</h2>
 * <p>This class is a pure {@link Configuration &#64;Configuration} class: it declares beans and
 * <em>stitches them together</em> into a chunk-oriented step and a single-step job. It contains
 * <strong>no validation, no posting, and no reject-formatting logic of any kind</strong>. The work
 * is split across collaborators authored elsewhere in this package, exactly mirroring the
 * read&nbsp;&rarr;&nbsp;validate&nbsp;&rarr;&nbsp;post/reject loop of {@code CBTRN02C}:</p>
 * <ul>
 *   <li><strong>{@link #dalyTranReader(String) dalyTranReader}</strong> &mdash; a
 *       {@link FlatFileItemReader} over the fixed-width 350-byte {@code DALYTRAN} feed
 *       (copybook {@code app/cpy/CVTRA06Y.cpy}). Each line is decoded by
 *       {@link DailyTransactionRecord#parse(String)}.</li>
 *   <li><strong>{@link TransactionPostingProcessor}</strong> &mdash; the per-record validation
 *       gauntlet ({@code CBTRN02C} {@code 1500-*} paragraphs) producing the reject-code subset
 *       100/101/102/103 or a valid {@link ProcessedTransaction}.</li>
 *   <li><strong>{@link TransactionPostingWriter}</strong> &mdash; the dual-output writer
 *       ({@code 2000-POST-TRANSACTION} + {@code 2500-WRITE-REJECT-REC}); it posts valid items and
 *       raises reject code&nbsp;109 on an account-update failure, completing the
 *       100/101/102/103/109 superset.</li>
 *   <li><strong>{@link #dalyRejectWriter(String) dalyRejectWriter}</strong> &mdash; a dedicated
 *       {@link FlatFileItemWriter} that emits the 430-byte fixed-width {@code DALYREJS} reject
 *       records (350-byte feed image + 80-byte validation trailer) the writer pre-formats.</li>
 * </ul>
 *
 * <h2>Batch-enablement model &mdash; OPTION&nbsp;A (Spring Boot auto-configuration)</h2>
 * <p>Consistent with {@code com.carddemo.CardDemoApplication}, {@code com.carddemo.config.BatchConfig},
 * and the sibling {@code *JobConfig} classes, this configuration carries <strong>no</strong>
 * {@code @EnableBatchProcessing}. Under Spring Boot&nbsp;3.2 / Spring Batch&nbsp;5 the shared batch
 * infrastructure &mdash; the {@link JobRepository}, the {@code JobLauncher}, the {@code JobExplorer},
 * the {@code jobRegistry} <em>and</em> the {@code stepScope}/{@code jobScope} post-processors &mdash;
 * is supplied by Boot's {@code BatchAutoConfiguration}, which is
 * {@code @ConditionalOnMissingBean(DefaultBatchConfiguration.class)} and backs off the moment
 * {@code @EnableBatchProcessing} appears anywhere in the context. Because the scope infrastructure is
 * present, the {@link StepScope &#64;StepScope} SpEL late-binding used by
 * {@link #dalyTranReader(String)} and {@link #dalyRejectWriter(String)} resolves correctly at
 * step-execution time. Jobs run <strong>on demand</strong> ({@code spring.batch.job.enabled=false} in
 * {@code application.yml}); nothing in this class auto-runs at startup.</p>
 *
 * <h2>Spring Batch&nbsp;5 builders</h2>
 * <p>All batch objects are assembled with the Spring Batch&nbsp;5 builders
 * {@link org.springframework.batch.core.job.builder.JobBuilder JobBuilder} and
 * {@link org.springframework.batch.core.step.builder.StepBuilder StepBuilder}, each constructed with
 * the constructor-injected {@link JobRepository}. The removed
 * {@code JobBuilderFactory}/{@code StepBuilderFactory} types are deliberately <em>not</em> used. The
 * auto-configured {@link JobRepository} and {@link PlatformTransactionManager} are stored in
 * {@code final} fields (constructor injection).</p>
 *
 * <h2>Layering &mdash; no {@code batch &harr; service} coupling</h2>
 * <p>This configuration imports only {@code com.carddemo.repository} interfaces (handed to the
 * processor/writer collaborators) and same-package {@code com.carddemo.batch} types. It imports
 * <strong>nothing</strong> from {@code com.carddemo.service}: the posting job is fully self-contained
 * within the batch and repository layers, so there is neither a {@code service &rarr; batch} nor a
 * {@code batch &rarr; service} edge for this job, and hence no cyclic dependency.</p>
 *
 * <h2>Parity guarantees (enforced by the wired collaborators)</h2>
 * <ul>
 *   <li>Reject-code superset <strong>100/101/102/103</strong> (processor) + <strong>109</strong>
 *       (writer) &mdash; AAP&nbsp;&sect;0.6.1, &sect;0.7.3 #3.</li>
 *   <li>Cycle-based overlimit and account-expiry-vs-origination-date checks &mdash; in the processor.</li>
 *   <li>430-byte fixed-width {@code DALYREJS} record &mdash; produced by the writer and emitted
 *       verbatim by {@link #dalyRejectWriter(String)} via a {@link PassThroughLineAggregator}
 *       (AAP&nbsp;&sect;0.6.2).</li>
 *   <li>The batch poster carries the incoming {@code DALYTRAN-ID} <strong>verbatim</strong> (no
 *       {@code TranIdGenerator} on the batch path) &mdash; in the processor (AAP&nbsp;&sect;0.6.5).</li>
 *   <li>{@code RETURN-CODE = 4} (rejects present, job still completes) &mdash; surfaced as a
 *       {@code WARNING} exit status by the writer's {@code StepExecutionListener} hook.</li>
 * </ul>
 *
 * @see TransactionPostingProcessor
 * @see TransactionPostingWriter
 * @see DailyTransactionRecord
 * @see ProcessedTransaction
 * @see com.carddemo.config.BatchConfig
 * @see InterestCalculationJobConfig
 * @see <a href="file:app/cbl/CBTRN02C.cbl">app/cbl/CBTRN02C.cbl (authoritative posting program)</a>
 * @see <a href="file:app/cpy/CVTRA06Y.cpy">app/cpy/CVTRA06Y.cpy (350-byte DALYTRAN record layout)</a>
 */
@Configuration
public class TransactionPostingJobConfig {

    /**
     * Chunk / commit interval for the posting step. This is a purely batch-internal tuning value
     * (how many feed records are read, validated, and committed per transaction) &mdash; <em>not</em>
     * a domain constant &mdash; so it is intentionally declared locally here rather than in
     * {@code com.carddemo.util.CardDemoConstants}. {@code CBTRN02C} ran as a single sequential program
     * with no notion of a chunk; any positive value is behavior-preserving because the per-record
     * validation/posting outcome is independent of chunk boundaries.
     */
    private static final int CHUNK_SIZE = 100;

    /**
     * SLF4J logger emitting INFO bean-creation markers as the job/step graph is assembled. It logs
     * only non-sensitive batch wiring details (bean names, the resolved input/reject paths) &mdash;
     * never PII, card numbers, or monetary amounts.
     */
    private static final Logger log = LoggerFactory.getLogger(TransactionPostingJobConfig.class);

    /**
     * Auto-configured Spring Batch metadata repository (Boot {@code BatchAutoConfiguration},
     * OPTION&nbsp;A), used to construct the {@link StepBuilder} and {@link JobBuilder}.
     */
    private final JobRepository jobRepository;

    /**
     * Auto-configured transaction manager bounding the chunk-oriented step's per-chunk transaction.
     * Each {@link TransactionPostingWriter#write(org.springframework.batch.item.Chunk) write} of a
     * chunk &mdash; the account-balance update, the per-category-balance upsert, and the posted
     * transaction insert &mdash; commits or rolls back atomically within this manager's boundary.
     */
    private final PlatformTransactionManager transactionManager;

    /**
     * Single all-arguments constructor. Because it is the sole constructor, Spring performs
     * constructor injection without an explicit {@code @Autowired} annotation, and both
     * collaborators are stored in {@code final} fields (immutable after construction).
     *
     * @param jobRepository      the auto-configured Spring Batch {@link JobRepository}
     * @param transactionManager the auto-configured {@link PlatformTransactionManager} for the step
     */
    public TransactionPostingJobConfig(JobRepository jobRepository,
                                       PlatformTransactionManager transactionManager) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
    }

    /**
     * The {@code DALYTRAN} feed reader &mdash; a {@link FlatFileItemReader} over the fixed-width
     * 350-byte daily-transaction file (copybook {@code app/cpy/CVTRA06Y.cpy}). It replaces the
     * sequential {@code READ DALYTRAN-FILE} of {@code CBTRN02C}.
     *
     * <h3>Late-bound input path</h3>
     * <p>The input file is supplied as the Spring Batch <strong>job parameter</strong> named exactly
     * {@code input.file.path} (AAP&nbsp;&sect;0.4.2), bound late through {@link StepScope &#64;StepScope}
     * SpEL: {@code @Value("#{jobParameters['input.file.path']}")}. {@code @StepScope} is mandatory
     * because the {@code jobParameters} expression can only be evaluated once a {@code StepExecution}
     * (and therefore the launch-time parameters) exists, which is precisely when a step-scoped bean is
     * instantiated.</p>
     *
     * <h3>Why a {@link LineMapper} lambda, not a {@code FixedLengthTokenizer}</h3>
     * <p>The mapper delegates each raw line to {@link DailyTransactionRecord#parse(String)} rather than
     * using a {@code FixedLengthTokenizer}. The {@code DALYTRAN-AMT} field uses COBOL trailing-overpunch
     * sign encoding that a positional tokenizer cannot decode; {@code parse(...)} owns that decoding and
     * also right-pads/truncates each line to exactly 350 characters before slicing, so a short or long
     * physical line never corrupts field boundaries. The lambda
     * {@code (line, lineNumber) -> DailyTransactionRecord.parse(line)} is a
     * {@code LineMapper<DailyTransactionRecord>}; {@code LineMapper.mapLine} declares
     * {@code throws Exception}, so any parse failure naturally propagates to Spring Batch.</p>
     *
     * <h3>Strict resource &amp; restartability</h3>
     * <p>{@code setStrict(true)} makes a missing input file fail fast at step start, matching the COBOL
     * file-open failure semantics (an absent {@code DALYTRAN} dataset is a hard error, not an empty
     * run). {@code saveState} is left at its default ({@code true}) so chunk-level restart works. The
     * reader is intentionally <strong>count-agnostic</strong>: it processes whatever number of records
     * the feed contains and never assumes a fixed count (no record total is hard-coded anywhere).</p>
     *
     * @param inputFilePath the {@code DALYTRAN} input file path, bound from the {@code input.file.path}
     *                      job parameter (late-binding via {@code @StepScope})
     * @return a configured, named, strict {@link FlatFileItemReader} of {@link DailyTransactionRecord}
     */
    @Bean
    @StepScope
    public FlatFileItemReader<DailyTransactionRecord> dalyTranReader(
            @Value("#{jobParameters['input.file.path']}") String inputFilePath) {
        log.info("Building dalyTranReader for input.file.path={}", inputFilePath);
        FlatFileItemReader<DailyTransactionRecord> reader = new FlatFileItemReader<>();
        reader.setName("dalyTranReader");
        reader.setResource(new FileSystemResource(inputFilePath));
        reader.setStrict(true);
        // LineMapper lambda: delegate the full 350-char fixed-width decode (incl. trailing-overpunch
        // amount) to DailyTransactionRecord.parse(...). Do NOT use a FixedLengthTokenizer here.
        LineMapper<DailyTransactionRecord> lineMapper =
                (line, lineNumber) -> DailyTransactionRecord.parse(line);
        reader.setLineMapper(lineMapper);
        return reader;
    }

    /**
     * The {@code DALYREJS} reject writer &mdash; a {@link FlatFileItemWriter} that emits the
     * fixed-width 430-byte reject records of {@code CBTRN02C}'s {@code 2500-WRITE-REJECT-REC}.
     *
     * <h3>Pre-formatted items, written verbatim</h3>
     * <p>Each item handed to this writer is a fully pre-formatted 430-character {@code String}
     * produced by {@code TransactionPostingWriter} (a 350-byte image of the original feed record plus
     * an 80-byte validation trailer: a 4-digit zero-padded reject reason followed by a 76-character
     * space-padded description). A {@link PassThroughLineAggregator} writes each {@code String} exactly
     * as-is and the writer appends the platform line separator per record, preserving the 430-byte
     * fixed-width record image and keeping the artifact byte-compatible with the legacy {@code DALYREJS}
     * dataset. This config performs <strong>no</strong> reject formatting; it only routes the
     * already-formatted line.</p>
     *
     * <h3>Late-bound output path with property fallback</h3>
     * <p>The path is bound via {@link StepScope &#64;StepScope} SpEL with a layered default: an explicit
     * {@code outputRejectPath} job parameter is preferred when supplied; otherwise it falls back to the
     * {@code batch.reject.output.path} property, defaulting to {@code ./output/dalyrejs.txt}. Spring
     * Batch's {@code FileUtils.setUpOutputFile} creates any missing parent directories, so
     * {@code ./output/} need not pre-exist. {@code setShouldDeleteIfExists(true)} recreates a fresh
     * reject file on every run (the legacy job recreated the dataset each run); append is left at its
     * default ({@code false}).</p>
     *
     * <h3>Lifecycle note</h3>
     * <p>Because this writer is <em>not</em> the step's primary writer, its {@code open()}/{@code close()}
     * {@code ItemStream} lifecycle is not managed automatically &mdash; it is registered on the step via
     * {@code .stream(dalyRejectWriter)} in {@link #transactionPostingStep}. {@code @StepScope} also makes
     * the output path re-resolve per step execution.</p>
     *
     * @param rejectFilePath the {@code DALYREJS} output file path, preferring the {@code outputRejectPath}
     *                       job parameter and otherwise the {@code batch.reject.output.path} property
     *                       (default {@code ./output/dalyrejs.txt})
     * @return a configured, named {@link FlatFileItemWriter} of {@code String} reject lines
     */
    @Bean
    @StepScope
    public FlatFileItemWriter<String> dalyRejectWriter(
            @Value("#{jobParameters['outputRejectPath'] ?: '${batch.reject.output.path:./output/dalyrejs.txt}'}")
            String rejectFilePath) {
        log.info("Building dalyRejectWriter for reject output path={}", rejectFilePath);
        FlatFileItemWriter<String> writer = new FlatFileItemWriter<>();
        writer.setName("dalyRejectWriter");
        writer.setResource(new FileSystemResource(rejectFilePath));
        // The item is already an exact 430-char fixed-width record; emit it as-is (no tokenizing).
        writer.setLineAggregator(new PassThroughLineAggregator<>());
        writer.setShouldDeleteIfExists(true);
        return writer;
    }

    /**
     * The per-record validation processor bean ({@code CBTRN02C} {@code 1500-*} paragraphs). Declared
     * as an explicit {@code @Bean} here &mdash; rather than via a stereotype on the class &mdash; so the
     * {@code com.carddemo.batch} package stays self-contained and the support classes carry no
     * annotations. Spring resolves the two repository collaborators by type and hands them to the
     * processor's constructor.
     *
     * @param cardXrefRepository the card&rarr;account cross-reference repository (reject 100 on miss)
     * @param accountRepository  the account-master repository (reject 101 on miss; overlimit/expiry checks)
     * @return the {@link TransactionPostingProcessor} singleton wired with its repositories
     */
    @Bean
    public TransactionPostingProcessor transactionPostingProcessor(CardXrefRepository cardXrefRepository,
                                                                   AccountRepository accountRepository) {
        return new TransactionPostingProcessor(cardXrefRepository, accountRepository);
    }

    /**
     * The dual-output posting writer bean ({@code CBTRN02C} {@code 2000}/{@code 2500}/{@code 2700}/
     * {@code 2800}/{@code 2900} paragraphs). Declared as an explicit {@code @Bean} for the same
     * self-containment reason as {@link #transactionPostingProcessor(CardXrefRepository, AccountRepository)}.
     *
     * <p>The {@code dalyRejectWriter} argument is the {@link StepScope &#64;StepScope} reject-writer bean;
     * Spring injects a scoped proxy that resolves to the step-scoped instance at write time. The writer
     * delegates all 430-byte {@code DALYREJS} output to that proxy. The writer also implements
     * {@code StepExecutionListener} &mdash; it is registered as both the item writer and a step listener
     * by {@link #transactionPostingStep}.</p>
     *
     * @param accountRepository                    repository for the account-balance update ({@code 2800};
     *                                             a failure here raises reject 109)
     * @param transactionCategoryBalanceRepository repository for the per-category balance upsert ({@code 2700})
     * @param transactionRepository                repository for the posted-transaction insert ({@code 2900})
     * @param dalyRejectWriter                     the {@code @StepScope} 430-byte {@code DALYREJS} reject
     *                                             delegate (injected as a scoped proxy)
     * @return the {@link TransactionPostingWriter} singleton wired with its collaborators
     */
    @Bean
    public TransactionPostingWriter transactionPostingWriter(
            AccountRepository accountRepository,
            TransactionCategoryBalanceRepository transactionCategoryBalanceRepository,
            TransactionRepository transactionRepository,
            FlatFileItemWriter<String> dalyRejectWriter) {
        return new TransactionPostingWriter(accountRepository, transactionCategoryBalanceRepository,
                transactionRepository, dalyRejectWriter);
    }

    /**
     * The chunk-oriented posting step: read {@code DALYTRAN} &rarr; validate &rarr; post/reject. It
     * re-expresses the {@code CBTRN02C} read-validate-write main loop with input type
     * {@link DailyTransactionRecord} and output type {@link ProcessedTransaction}, committing every
     * {@link #CHUNK_SIZE} items within the injected {@link PlatformTransactionManager}'s transaction.
     *
     * <h3>Listener and stream registration (both critical)</h3>
     * <ul>
     *   <li><strong>{@code .listener(transactionPostingWriter)}</strong> &mdash; because the parameter's
     *       static type is the concrete {@link TransactionPostingWriter}, this call resolves to the
     *       {@code listener(StepExecutionListener)} overload (the writer implements that interface). Its
     *       {@code afterStep} returns a {@code WARNING} {@code ExitStatus} when any record was rejected
     *       (the {@code RETURN-CODE = 4} parity), so the step is <strong>never</strong> marked
     *       {@code FAILED} for ordinary business rejects.</li>
     *   <li><strong>{@code .stream(dalyRejectWriter)}</strong> &mdash; the reject {@link FlatFileItemWriter}
     *       is not the step's primary writer, so its {@code open()}/{@code update()}/{@code close()}
     *       lifecycle would not be managed automatically. Registering it as a stream ensures the reject
     *       file is opened before, and flushed/closed after, the step; omitting this causes
     *       "writer must be open" failures.</li>
     * </ul>
     *
     * <p>No fault-tolerant skip policy is configured: rejects are <strong>first-class outputs</strong>
     * routed by the writer (as {@link ProcessedTransaction} items), not exceptions to be swallowed.</p>
     *
     * @param dalyTranReader               the step-scoped {@code DALYTRAN} reader (scoped proxy injected by name)
     * @param transactionPostingProcessor  the validation processor
     * @param transactionPostingWriter     the dual-output writer (also the step's {@code StepExecutionListener})
     * @param dalyRejectWriter             the step-scoped reject writer, registered as an {@code ItemStream}
     * @return the configured {@code transactionPostingStep} {@link Step}
     */
    @Bean
    public Step transactionPostingStep(FlatFileItemReader<DailyTransactionRecord> dalyTranReader,
                                       TransactionPostingProcessor transactionPostingProcessor,
                                       TransactionPostingWriter transactionPostingWriter,
                                       FlatFileItemWriter<String> dalyRejectWriter) {
        return new StepBuilder("transactionPostingStep", jobRepository)
                .<DailyTransactionRecord, ProcessedTransaction>chunk(CHUNK_SIZE, transactionManager)
                .reader(dalyTranReader)
                .processor(transactionPostingProcessor)
                .writer(transactionPostingWriter)
                .listener(transactionPostingWriter) // StepExecutionListener -> RETURN-CODE=4 => WARNING exit
                .stream(dalyRejectWriter)            // manage the @StepScope reject writer's open/update/close
                .build();
    }

    /**
     * The {@code transactionPostingJob}, composed of the single {@link #transactionPostingStep}.
     *
     * <p>The bean name is <strong>exactly</strong> {@code transactionPostingJob} (lowercase camel); it
     * is the identifier under which the job is launched on demand through the auto-configured
     * {@code JobLauncher}, with the required {@code input.file.path} job parameter (and optional
     * {@code outputRejectPath} / unique {@code run.id} parameters). The job simply starts the single
     * posting step, mirroring the linear top-to-bottom flow of {@code CBTRN02C}; callers supply a unique
     * {@code run.id} to permit re-runs of the same logical input.</p>
     *
     * @param transactionPostingStep the posting step bean (injected by name)
     * @return the configured {@link Job} named {@code transactionPostingJob}
     */
    @Bean
    public Job transactionPostingJob(Step transactionPostingStep) {
        return new JobBuilder("transactionPostingJob", jobRepository)
                .start(transactionPostingStep)
                .build();
    }
}
