package com.aws.carddemo.batch;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.file.FlatFileItemWriter;
import org.springframework.batch.item.file.builder.FlatFileItemWriterBuilder;
import org.springframework.batch.item.file.transform.PassThroughLineAggregator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.transaction.PlatformTransactionManager;

import com.aws.carddemo.domain.Account;
import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.domain.TransactionCategoryBalance;
import com.aws.carddemo.domain.TransactionCategoryBalance.TransactionCategoryBalanceId;
import com.aws.carddemo.dto.DailyTransaction;
import com.aws.carddemo.exception.DuplicateKeyException;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.TransactionCategoryBalanceRepository;
import com.aws.carddemo.repository.TransactionRepository;
import com.aws.carddemo.util.CobolDecimal;
import com.aws.carddemo.util.FixedWidthRecordMapper;
import com.aws.carddemo.util.FixedWidthRecordMapper.FieldDef;
import com.aws.carddemo.util.batch.AtomicFileStepPublisher;
import com.aws.carddemo.util.batch.BatchFilePathResolver;
import com.aws.carddemo.util.batch.FixedBlockLineAggregator;
import com.aws.carddemo.util.batch.FixedLengthItemReader;

/**
 * Spring Batch job configuration for daily transaction posting.
 *
 * <p><strong>Origin (traceability, AAP &sect;0.6.10):</strong> faithful translation of COBOL batch
 * program {@code legacy/cbl/CBTRN02C.cbl} ("Post the records from daily transaction file"),
 * orchestrated by JCL {@code legacy/jcl/POSTTRAN.jcl} step {@code STEP15}, with the reject-file and
 * generation-data-group semantics of {@code legacy/jcl/DALYREJS.jcl} and
 * {@code legacy/jcl/DEFGDGB.jcl} folded in. This is the canonical chunk job whose reader/processor/
 * writer split is the reference pattern for the other {@code batch} package jobs.</p>
 *
 * <p><strong>COBOL paragraph map:</strong> the driving read loop (CBTRN02C L194-234) becomes the
 * chunk {@code Step}; {@code 1500-VALIDATE-TRAN} (L370) becomes {@link PostTransactionProcessor};
 * {@code 2000-POST-TRANSACTION} (L424) plus {@code 2700-UPDATE-TCATBAL} (L467),
 * {@code 2800-UPDATE-ACCOUNT-REC} (L545) and {@code 2900-WRITE-TRANSACTION-FILE} (L562) become the
 * processor's build-and-compute step and {@link PostTransactionWriter}'s persistence; and
 * {@code 2500-WRITE-REJECT-REC} (L446) becomes the 430-byte reject line written to a
 * {@link FlatFileItemWriter}.</p>
 *
 * <p><strong>Auto-configuration contract:</strong> this class is a plain {@link Configuration} and
 * deliberately does <em>not</em> use {@code @EnableBatchProcessing} / {@code DefaultBatchConfiguration};
 * Spring Boot's auto-configured, persistent, restartable JDBC {@link JobRepository} and
 * {@link PlatformTransactionManager} are constructor-injected. Money is exclusively
 * {@link BigDecimal} via {@link CobolDecimal} (scale 2); CBTRN02C has no {@code ROUNDED} clause so the
 * additive posting arithmetic is exact. Both the 350-byte {@code DALYTRAN} input and the 430-byte
 * {@code DALYREJS} reject output preserve byte-for-byte fixed-width parity via
 * {@link FixedWidthRecordMapper}.</p>
 */
@Configuration
public class PostTransactionJobConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(PostTransactionJobConfig.class);

    /** Spring Batch job name (mirrors the POSTTRAN STEP15 posting job). */
    static final String JOB_NAME = "postTransactionJob";

    /** Spring Batch step name for the chunk-oriented posting step. */
    static final String STEP_NAME = "postTransactionStep";

    /**
     * Commit interval. Fixed at 1 for exact record-at-a-time parity with the COBOL per-record
     * rewrite: the account and per-category balance read-modify-write accumulates across records for
     * the same key, so each record commits before the next is read (CBTRN02C L194-234).
     */
    static final int CHUNK_SIZE = 1;

    // Validation failure reason codes and their COBOL description literals (CBTRN02C 1500-*).
    static final int REASON_INVALID_CARD = 100;
    static final int REASON_ACCT_NOT_FOUND = 101;
    static final int REASON_OVERLIMIT = 102;
    static final int REASON_EXPIRED = 103;
    static final String DESC_INVALID_CARD = "INVALID CARD NUMBER FOUND";
    static final String DESC_ACCT_NOT_FOUND = "ACCOUNT RECORD NOT FOUND";
    static final String DESC_OVERLIMIT = "OVERLIMIT TRANSACTION";
    static final String DESC_EXPIRED = "TRANSACTION RECEIVED AFTER ACCT EXPIRATION";

    /**
     * Custom step/job exit status raised when any record was rejected. This is the
     * launcher-boundary contract consumed by {@code CardDemoApplication}'s RETURN-CODE exit-code
     * generator, which maps it to process exit code 4 (COBOL RETURN-CODE 4). Public so the
     * bootstrap class (a different package) can reference it as the single source of truth instead
     * of duplicating the literal.
     */
    public static final String EXIT_STATUS_WITH_REJECTS = "COMPLETED_WITH_REJECTS";

    /** Execution-context key under which the running reject count is accumulated. */
    static final String REJECT_COUNT_KEY = "postTransaction.rejectCount";

    // ----- DALYTRAN (CVTRA06Y) 350-byte field names + 80-byte reject trailer (WS-VALIDATION-TRAILER) -----
    private static final String F_ID = "id";
    private static final String F_TYPE_CD = "typeCd";
    private static final String F_CAT_CD = "catCd";
    private static final String F_SOURCE = "source";
    private static final String F_DESC = "description";
    private static final String F_AMOUNT = "amount";
    private static final String F_MERCHANT_ID = "merchantId";
    private static final String F_MERCHANT_NAME = "merchantName";
    private static final String F_MERCHANT_CITY = "merchantCity";
    private static final String F_MERCHANT_ZIP = "merchantZip";
    private static final String F_CARD_NUM = "cardNum";
    private static final String F_ORIG_TS = "origTs";
    private static final String F_PROC_TS = "procTs";
    private static final String F_FAIL_REASON = "failReason";
    private static final String F_FAIL_REASON_DESC = "failReasonDesc";

    /** Blank COBOL {@code PIC X(10)} date (10 spaces) used when an account expiration date is absent. */
    private static final String BLANK_DATE = " ".repeat(10);

    /**
     * Byte-exact 350-byte {@code DALYTRAN-RECORD} layout (CVTRA06Y): 16 + 2 + 4 + 10 + 100 + 11 + 9 +
     * 50 + 50 + 10 + 16 + 26 + 26 + 20 (filler) = 350.
     */
    static final FixedWidthRecordMapper DALYTRAN_MAPPER = FixedWidthRecordMapper.of(
            FieldDef.text(F_ID, 16),
            FieldDef.text(F_TYPE_CD, 2),
            FieldDef.numeric(F_CAT_CD, 4),
            FieldDef.text(F_SOURCE, 10),
            FieldDef.text(F_DESC, 100),
            FieldDef.signedDecimal(F_AMOUNT, 11, 2),
            FieldDef.numeric(F_MERCHANT_ID, 9),
            FieldDef.text(F_MERCHANT_NAME, 50),
            FieldDef.text(F_MERCHANT_CITY, 50),
            FieldDef.text(F_MERCHANT_ZIP, 10),
            FieldDef.text(F_CARD_NUM, 16),
            FieldDef.text(F_ORIG_TS, 26),
            FieldDef.text(F_PROC_TS, 26),
            FieldDef.filler(20));

    /**
     * 430-byte {@code DALYREJS} reject layout (CBTRN02C 2500 / DALYREJS.jcl {@code LRECL=430}): the
     * verbatim 350-byte {@code DALYTRAN-RECORD} prefix + an 80-byte trailer of a 4-digit numeric
     * reason ({@code WS-VALIDATION-FAIL-REASON PIC 9(04)}) and a 76-char description
     * ({@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)}).
     */
    static final FixedWidthRecordMapper REJECT_MAPPER = DALYTRAN_MAPPER.concat(
            FixedWidthRecordMapper.of(
                    FieldDef.numeric(F_FAIL_REASON, 4),
                    FieldDef.text(F_FAIL_REASON_DESC, 76)));

    /**
     * Ordered candidate patterns for {@code DALYTRAN-ORIG-TS}: the space/colon form used by the
     * ASCII fixtures (matching the {@link Transaction} entity contract) first, then the DB2 dash/dot
     * form. Blank or unparseable values fall back to the date-only prefix and finally to {@code null}.
     */
    private static final List<DateTimeFormatter> ORIG_TS_FORMATTERS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss.SSSSSS"));

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final CardXrefRepository cardXrefRepository;
    private final AccountRepository accountRepository;
    private final TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;
    private final TransactionRepository transactionRepository;

    /**
     * Safe-root resolver validating the {@code inputPath} DALYTRAN feed (containment + symlink
     * rejection, review finding&nbsp;#18) before the fixed-block reader opens it.
     */
    private final BatchFilePathResolver batchFilePathResolver;

    /**
     * Shared listener that targets the reject writer at a deterministic in-progress temp file and
     * atomically publishes it to the final {@code DALYREJS} path on {@code COMPLETED}
     * (findings&nbsp;#18/#19).
     */
    private final AtomicFileStepPublisher atomicFileStepPublisher;

    /**
     * Constructs the posting job configuration with the Spring-Boot-auto-configured batch
     * infrastructure and the persistence repositories bound to the POSTTRAN STEP15 DD statements.
     *
     * @param jobRepository                        auto-configured persistent {@link JobRepository}
     * @param transactionManager                   auto-configured {@link PlatformTransactionManager}
     * @param cardXrefRepository                   XREFFILE (CVACT03Y) access for {@code 1500-A-LOOKUP-XREF}
     * @param accountRepository                    ACCTFILE (CVACT01Y) access for {@code 1500-B-LOOKUP-ACCT} / {@code 2800}
     * @param transactionCategoryBalanceRepository TCATBALF (CVTRA01Y) access for {@code 2700-UPDATE-TCATBAL}
     * @param transactionRepository                TRANFILE (CVTRA05Y) master for {@code 2900-WRITE-TRANSACTION-FILE}
     * @param batchFilePathResolver                shared safe-root resolver for the DALYTRAN input feed
     * @param atomicFileStepPublisher              shared safe-path atomic-publication step listener for
     *                                             the DALYREJS reject output
     */
    public PostTransactionJobConfig(JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            CardXrefRepository cardXrefRepository,
            AccountRepository accountRepository,
            TransactionCategoryBalanceRepository transactionCategoryBalanceRepository,
            TransactionRepository transactionRepository,
            BatchFilePathResolver batchFilePathResolver,
            AtomicFileStepPublisher atomicFileStepPublisher) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.cardXrefRepository = cardXrefRepository;
        this.accountRepository = accountRepository;
        this.transactionCategoryBalanceRepository = transactionCategoryBalanceRepository;
        this.transactionRepository = transactionRepository;
        this.batchFilePathResolver = batchFilePathResolver;
        this.atomicFileStepPublisher = atomicFileStepPublisher;
    }

    /**
     * Reader for the sequential {@code DALYTRAN} feed (POSTTRAN {@code DALYTRAN=DALYTRAN.PS}) in
     * <em>undelimited</em> {@code RECFM=FB} framing (review finding&nbsp;#17).
     *
     * <p>The native z/OS {@code DALYTRAN} dataset is a stream of fixed 350-byte
     * {@code DALYTRAN-RECORD}s with <strong>no</strong> line delimiter (the LF-terminated ASCII
     * fixture is a convenience form only; AAP&nbsp;&sect;0.6.6). This reader therefore uses a
     * {@link FixedLengthItemReader} that slices the input into exact 350-byte blocks &mdash; it does
     * not depend on any newline &mdash; and maps each block through the byte-oriented
     * {@link #mapDailyTransaction(byte[])}. ISO-8859-1 inside the mapper guarantees every byte
     * round-trips exactly onto the reject path.</p>
     *
     * <p>The {@code inputPath} job parameter is validated through the {@link #batchFilePathResolver}
     * (safe-root containment + symlink rejection, finding&nbsp;#18) before the reader opens it. The
     * reader is strict, so a missing feed fails the step rather than posting zero transactions
     * silently, and its inherited item-count restart makes a re-launch resume at the exact record
     * boundary (finding&nbsp;#19).</p>
     *
     * <p><b>Return type (review finding&nbsp;#35).</b> The bean returns the concrete
     * {@link FixedLengthItemReader}. {@code @StepScope} uses {@code ScopedProxyMode.TARGET_CLASS}, so
     * Spring builds a CGLIB subclass proxy of the declared bean type; that requires the target class
     * to be non-{@code final} (it is &mdash; the reader's {@code final} applies only to its fields, and
     * Spring instantiates the proxy via Objenesis, so no no-arg constructor is needed). Returning the
     * concrete type also lets Spring Batch inspect the reader for listener annotations, which avoids
     * the startup warning an interface-typed {@code @StepScope} bean would otherwise emit
     * (&quot;{@code org.springframework.batch.item.ItemStreamReader is an interface. The implementing
     * class will not be queried for annotation based listener configurations...}&quot;). The proxy
     * still implements {@link org.springframework.batch.item.ItemStream}, so the step registers the
     * reader for open/close/update and item-count restart bookkeeping (finding&nbsp;#19).</p>
     *
     * @param inputPath job-parameter path to the DALYTRAN feed
     * @return a step-scoped fixed-block reader producing {@link DailyTransaction} items
     */
    @Bean
    @StepScope
    public FixedLengthItemReader<DailyTransaction> dailyTransactionReader(
            @Value("#{jobParameters['inputPath']}") String inputPath) {
        if (inputPath == null || inputPath.isBlank()) {
            throw new IllegalStateException(
                    "Required job parameter 'inputPath' (DALYTRAN feed) was not supplied.");
        }
        Path source = batchFilePathResolver.resolveInputFile(inputPath);
        FixedLengthItemReader<DailyTransaction> reader = new FixedLengthItemReader<>(
                "dalyTranItemReader", new FileSystemResource(source.toFile()),
                DALYTRAN_MAPPER.getRecordLength(), PostTransactionJobConfig::mapDailyTransaction);
        reader.setStrict(true);
        return reader;
    }

    /**
     * Reject writer for the {@code DALYREJS} generation-data-group output (POSTTRAN
     * {@code DALYREJS=DALYREJS(+1)}, {@code RECFM=F LRECL=430}). Each rejected record is written as a
     * 430-byte line. The GDG {@code (+1)} new-generation semantics are approximated by a per-job-
     * execution output resource path (documented substitution &mdash; not a real GDG): the
     * {@code rejectPath} job parameter when supplied, otherwise a GDG-style name under the temp
     * directory keyed by the job <em>instance</em> id (stable across restarts of the same instance, so
     * a restart resumes the same generation &mdash; a more faithful GDG mapping than an
     * execution-scoped name, finding&nbsp;#19).
     *
     * <p>Each 430-byte record is emitted in <em>undelimited</em> {@code RECFM=FB} framing (empty line
     * separator, finding&nbsp;#17): the {@link PassThroughLineAggregator} passes the pre-built 430-byte
     * string through and the {@link FixedBlockLineAggregator} guard fails fast unless it is exactly
     * {@code REJECT_MAPPER.getRecordLength()} bytes, so record boundaries can never drift. The output
     * path is validated through the safe-root resolver and the writer streams into a deterministic
     * in-progress temp file that {@link #atomicFileStepPublisher} atomically renames onto the final
     * {@code DALYREJS} path on {@code COMPLETED} (findings&nbsp;#18/#19); {@code shouldDeleteIfEmpty}
     * stays {@code false} so an empty reject file is still published, matching the GDG {@code (+1)}
     * always-create-a-generation semantics.</p>
     *
     * @param rejectPath    optional job-parameter path for the reject file
     * @param stepExecution the running step execution, used to derive a unique default path and the
     *                      atomic-publication temp/target
     * @return a step-scoped flat-file writer of 430-byte reject lines
     */
    @Bean
    @StepScope
    public FlatFileItemWriter<String> rejectItemWriter(
            @Value("#{jobParameters['rejectPath']}") String rejectPath,
            @Value("#{stepExecution}") StepExecution stepExecution) {
        String resolvedPath = (rejectPath != null && !rejectPath.isBlank())
                ? rejectPath
                : defaultRejectPath(stepExecution);
        Path temp = atomicFileStepPublisher.prepare(resolvedPath, stepExecution);
        return new FlatFileItemWriterBuilder<String>()
                .name("dalyRejsItemWriter")
                .resource(new FileSystemResource(temp.toFile()))
                .encoding(StandardCharsets.ISO_8859_1.name())
                .lineSeparator("")
                .lineAggregator(new FixedBlockLineAggregator<>(
                        new PassThroughLineAggregator<>(), REJECT_MAPPER.getRecordLength(),
                        StandardCharsets.ISO_8859_1))
                .shouldDeleteIfEmpty(false)
                .build();
    }

    /**
     * Processor implementing {@code 1500-VALIDATE-TRAN} (CBTRN02C L370) and the build-and-compute
     * portion of {@code 2000-POST-TRANSACTION}. Stateless singleton reading the XREF, account and
     * category-balance stores; it performs no persistence side effects.
     *
     * @return the posting processor
     */
    @Bean
    public PostTransactionProcessor postTransactionProcessor() {
        return new PostTransactionProcessor(cardXrefRepository, accountRepository,
                transactionCategoryBalanceRepository);
    }

    /**
     * Writer applying the persistence side of {@code 2000-POST-TRANSACTION}: {@code 2700} category
     * balance, {@code 2800} account and {@code 2900} transaction-master saves for posted items, and
     * the {@code 2500} 430-byte reject line for rejected items. Shares the step-scoped reject
     * {@link FlatFileItemWriter} proxy that is also registered as a step stream so the file opens and
     * closes correctly within the same step execution.
     *
     * @param stepExecution the running step execution, used to accumulate the reject count
     * @return the step-scoped posting writer
     */
    @Bean
    @StepScope
    public PostTransactionWriter postTransactionWriter(
            @Value("#{stepExecution}") StepExecution stepExecution) {
        return new PostTransactionWriter(transactionRepository, accountRepository,
                transactionCategoryBalanceRepository, rejectItemWriter(null, null), stepExecution);
    }

    /**
     * Step listener translating {@code IF WS-REJECT-COUNT > 0 MOVE 4 TO RETURN-CODE} (CBTRN02C
     * L229-231): when the accumulated reject count is positive it returns a custom
     * {@link ExitStatus} ({@value #EXIT_STATUS_WITH_REJECTS}) whose operational exit code maps to 4 at
     * the launcher boundary; otherwise it leaves the default {@code COMPLETED} status unchanged.
     *
     * @return a {@link StepExecutionListener} that surfaces the reject-driven exit status
     */
    @Bean
    public StepExecutionListener rejectExitStatusListener() {
        return new StepExecutionListener() {
            @Override
            public ExitStatus afterStep(StepExecution stepExecution) {
                int rejects = stepExecution.getExecutionContext().getInt(REJECT_COUNT_KEY, 0);
                if (rejects > 0) {
                    return new ExitStatus(EXIT_STATUS_WITH_REJECTS,
                            "Reject count " + rejects + " > 0; maps to RETURN-CODE 4 (CBTRN02C L229-231).");
                }
                return null;
            }
        };
    }

    /**
     * The chunk-oriented posting step (CBTRN02C main loop L194-234). Reads {@link DailyTransaction}
     * items, validates and builds {@link PostingOutcome}s, and writes posted rows / reject lines. The
     * reject writer is registered as a stream so it participates in the step lifecycle.
     *
     * @return the posting step
     */
    @Bean
    public Step postTransactionStep() {
        return new StepBuilder(STEP_NAME, jobRepository)
                .<DailyTransaction, PostingOutcome>chunk(CHUNK_SIZE, transactionManager)
                .reader(dailyTransactionReader(null))
                .processor(postTransactionProcessor())
                .writer(postTransactionWriter(null))
                .stream(rejectItemWriter(null, null))
                .listener(rejectExitStatusListener())
                .listener(atomicFileStepPublisher)
                .build();
    }

    /**
     * The daily transaction posting job (POSTTRAN STEP15), composed of the single posting step. The
     * job exit status derives from the step, so a reject-driven {@link #EXIT_STATUS_WITH_REJECTS}
     * propagates to the job for the launcher-boundary exit-code-4 mapping.
     *
     * @return the posting job
     */
    @Bean
    public Job postTransactionJob() {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(postTransactionStep())
                .build();
    }

    /**
     * Parses a raw 350-byte {@code DALYTRAN-RECORD} line into a {@link DailyTransaction} using the
     * fixed-width {@link #DALYTRAN_MAPPER}. Text fields retain their raw fixed-width content (trailing
     * spaces included) so the record can be reproduced byte-for-byte on the reject path.
     *
     * @param line one 350-character DALYTRAN record
     * @return the mapped daily transaction
     */
    static DailyTransaction mapDailyTransaction(String line) {
        return mapDailyTransaction(line.getBytes(StandardCharsets.ISO_8859_1));
    }

    /**
     * Parses a raw 350-byte {@code DALYTRAN-RECORD} block into a {@link DailyTransaction} using the
     * fixed-width {@link #DALYTRAN_MAPPER}. This byte-oriented overload is the {@code recordMapper}
     * used by the undelimited {@link FixedLengthItemReader} (review finding&nbsp;#17): it receives the
     * exact 350 bytes sliced from the fixed-block stream, so no charset decode/re-encode round-trip
     * (and no newline dependency) sits between the file and the record boundary. Text fields retain
     * their raw fixed-width content (trailing spaces included) so the record can be reproduced
     * byte-for-byte on the reject path.
     *
     * @param raw the exact 350 bytes of one {@code DALYTRAN-RECORD}
     * @return the mapped daily transaction
     */
    static DailyTransaction mapDailyTransaction(byte[] raw) {
        FixedWidthRecordMapper.ParsedRecord record = DALYTRAN_MAPPER.parse(raw);
        DailyTransaction daily = new DailyTransaction();
        daily.setId(record.getText(F_ID));
        daily.setTypeCd(record.getText(F_TYPE_CD));
        daily.setCatCd((int) record.getNumeric(F_CAT_CD));
        daily.setSource(record.getText(F_SOURCE));
        daily.setDescription(record.getText(F_DESC));
        daily.setAmount(record.getSignedDecimal(F_AMOUNT));
        daily.setMerchantId(record.getNumeric(F_MERCHANT_ID));
        daily.setMerchantName(record.getText(F_MERCHANT_NAME));
        daily.setMerchantCity(record.getText(F_MERCHANT_CITY));
        daily.setMerchantZip(record.getText(F_MERCHANT_ZIP));
        daily.setCardNum(record.getText(F_CARD_NUM));
        daily.setOrigTs(record.getText(F_ORIG_TS));
        daily.setProcTs(record.getText(F_PROC_TS));
        return daily;
    }

    /**
     * Builds the 430-byte {@code DALYREJS} reject line (CBTRN02C {@code 2500-WRITE-REJECT-REC}): the
     * verbatim 350-byte {@code DALYTRAN-RECORD} prefix followed by the 4-digit zero-padded reason code
     * and the 76-char space-padded description trailer.
     *
     * @param daily      the rejected daily transaction (its original fixed-width content is preserved)
     * @param reasonCode the {@code WS-VALIDATION-FAIL-REASON} code
     * @param reasonDesc the {@code WS-VALIDATION-FAIL-REASON-DESC} text
     * @return a 430-character reject line encoded in ISO-8859-1
     */
    static String buildRejectLine(DailyTransaction daily, int reasonCode, String reasonDesc) {
        FixedWidthRecordMapper.RecordBuilder builder = REJECT_MAPPER.newRecord();
        builder.setText(F_ID, safe(daily.getId()));
        builder.setText(F_TYPE_CD, safe(daily.getTypeCd()));
        builder.setNumeric(F_CAT_CD, daily.getCatCd() == null ? 0L : daily.getCatCd().longValue());
        builder.setText(F_SOURCE, safe(daily.getSource()));
        builder.setText(F_DESC, safe(daily.getDescription()));
        builder.setSignedDecimal(F_AMOUNT, daily.getAmount() == null ? BigDecimal.ZERO : daily.getAmount());
        builder.setNumeric(F_MERCHANT_ID, daily.getMerchantId() == null ? 0L : daily.getMerchantId());
        builder.setText(F_MERCHANT_NAME, safe(daily.getMerchantName()));
        builder.setText(F_MERCHANT_CITY, safe(daily.getMerchantCity()));
        builder.setText(F_MERCHANT_ZIP, safe(daily.getMerchantZip()));
        builder.setText(F_CARD_NUM, safe(daily.getCardNum()));
        builder.setText(F_ORIG_TS, safe(daily.getOrigTs()));
        builder.setText(F_PROC_TS, safe(daily.getProcTs()));
        builder.setNumeric(F_FAIL_REASON, reasonCode);
        builder.setText(F_FAIL_REASON_DESC, safe(reasonDesc));
        return new String(builder.build(), StandardCharsets.ISO_8859_1);
    }

    /**
     * Parses {@code DALYTRAN-ORIG-TS} into a {@link LocalDateTime} for {@code Transaction.origTs}
     * (CBTRN02C 2000). Tries the known timestamp patterns, then salvages the date-only prefix, then
     * returns {@code null} when the value is blank or unusable (documented deterministic fallback).
     *
     * @param raw the raw 26-character origin timestamp field
     * @return the parsed timestamp, or {@code null} when it cannot be interpreted
     */
    static LocalDateTime parseOrigTimestamp(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        for (DateTimeFormatter formatter : ORIG_TS_FORMATTERS) {
            try {
                return LocalDateTime.parse(trimmed, formatter);
            } catch (DateTimeParseException ignored) {
                // Try the next known DALYTRAN timestamp layout before falling back.
            }
        }
        if (trimmed.length() >= 10) {
            try {
                return LocalDate.parse(trimmed.substring(0, 10)).atStartOfDay();
            } catch (DateTimeParseException ignored) {
                // Even the date prefix is unusable; fall through to the null fallback.
            }
        }
        LOGGER.debug("DALYTRAN origin timestamp could not be parsed; storing null origin timestamp.");
        return null;
    }

    /**
     * Returns the first ten characters of {@code DALYTRAN-ORIG-TS} (COBOL {@code (1:10)} reference
     * modification), i.e. the {@code yyyy-MM-dd} date used in the expiration comparison.
     *
     * @param origTs the raw origin timestamp field
     * @return the 10-character date prefix (or the whole value when shorter, empty when {@code null})
     */
    private static String origDatePrefix(String origTs) {
        if (origTs == null) {
            return "";
        }
        return origTs.length() >= 10 ? origTs.substring(0, 10) : origTs;
    }

    /** Builds a unique reject-file path when none is supplied, mirroring a fresh GDG generation. */
    private static String defaultRejectPath(StepExecution stepExecution) {
        long instanceId = stepExecution.getJobExecution().getJobInstance().getInstanceId();
        String tempDir = System.getProperty("java.io.tmpdir");
        return tempDir + "/DALYREJS.G" + String.format("%04d", instanceId % 10000L) + "V00";
    }

    /** Null-safe helper: never emit a {@code null} into a fixed-width text field. */
    private static String safe(String value) {
        return value == null ? "" : value;
    }

    /**
     * Immutable result of processing one {@link DailyTransaction}: either a posted outcome carrying
     * the built {@link Transaction} and the mutated {@link Account} / {@link TransactionCategoryBalance}
     * to persist, or a rejected outcome carrying the reason code and description.
     */
    public static final class PostingOutcome {

        private final boolean posted;
        private final DailyTransaction source;
        private final int reasonCode;
        private final String reasonDesc;
        private final Transaction transaction;
        private final Account account;
        private final TransactionCategoryBalance categoryBalance;

        private PostingOutcome(boolean posted, DailyTransaction source, int reasonCode,
                String reasonDesc, Transaction transaction, Account account,
                TransactionCategoryBalance categoryBalance) {
            this.posted = posted;
            this.source = source;
            this.reasonCode = reasonCode;
            this.reasonDesc = reasonDesc;
            this.transaction = transaction;
            this.account = account;
            this.categoryBalance = categoryBalance;
        }

        /**
         * Creates a posted outcome (CBTRN02C 2000 valid path).
         *
         * @param source          the originating daily transaction
         * @param transaction     the built transaction-master row (2900)
         * @param account         the mutated account row (2800)
         * @param categoryBalance the found-or-created category balance row (2700)
         * @return a posted outcome
         */
        static PostingOutcome posted(DailyTransaction source, Transaction transaction, Account account,
                TransactionCategoryBalance categoryBalance) {
            return new PostingOutcome(true, source, 0, "", transaction, account, categoryBalance);
        }

        /**
         * Creates a rejected outcome (CBTRN02C 1500 failure path).
         *
         * @param source     the originating daily transaction
         * @param reasonCode the validation failure reason code
         * @param reasonDesc the validation failure description
         * @return a rejected outcome
         */
        static PostingOutcome rejected(DailyTransaction source, int reasonCode, String reasonDesc) {
            return new PostingOutcome(false, source, reasonCode, reasonDesc, null, null, null);
        }

        /** @return {@code true} when the item validated and should be posted. */
        public boolean isPosted() {
            return posted;
        }

        /** @return the originating daily transaction. */
        public DailyTransaction getSource() {
            return source;
        }

        /** @return the validation failure reason code (0 when posted). */
        public int getReasonCode() {
            return reasonCode;
        }

        /** @return the validation failure description (empty when posted). */
        public String getReasonDesc() {
            return reasonDesc;
        }

        /** @return the built transaction-master row, or {@code null} when rejected. */
        public Transaction getTransaction() {
            return transaction;
        }

        /** @return the mutated account row, or {@code null} when rejected. */
        public Account getAccount() {
            return account;
        }

        /** @return the found-or-created category balance row, or {@code null} when rejected. */
        public TransactionCategoryBalance getCategoryBalance() {
            return categoryBalance;
        }
    }

    /**
     * Validation and build processor: implements {@code 1500-VALIDATE-TRAN} (CBTRN02C L370) and the
     * in-memory build/compute portion of {@code 2000-POST-TRANSACTION}. Performs only repository reads
     * (no persistence side effects); mutations are applied to detached-from-write in-memory instances
     * that {@link PostTransactionWriter} persists inside the chunk transaction.
     */
    public static final class PostTransactionProcessor
            implements ItemProcessor<DailyTransaction, PostingOutcome> {

        private final CardXrefRepository cardXrefRepository;
        private final AccountRepository accountRepository;
        private final TransactionCategoryBalanceRepository categoryBalanceRepository;

        /**
         * Creates the processor.
         *
         * @param cardXrefRepository        XREF lookup ({@code 1500-A})
         * @param accountRepository         account lookup ({@code 1500-B})
         * @param categoryBalanceRepository category-balance lookup ({@code 2700})
         */
        public PostTransactionProcessor(CardXrefRepository cardXrefRepository,
                AccountRepository accountRepository,
                TransactionCategoryBalanceRepository categoryBalanceRepository) {
            this.cardXrefRepository = cardXrefRepository;
            this.accountRepository = accountRepository;
            this.categoryBalanceRepository = categoryBalanceRepository;
        }

        /**
         * Validates and (when valid) builds the posting outcome for one daily transaction.
         *
         * @param item the daily transaction to process
         * @return a posted or rejected {@link PostingOutcome} (never {@code null})
         */
        @Override
        public PostingOutcome process(DailyTransaction item) {
            // 1500-A-LOOKUP-XREF (CBTRN02C L380): unknown card -> reason 100, stop validating.
            Optional<CardXref> xref = cardXrefRepository.findByXrefCardNum(item.getCardNum());
            if (xref.isEmpty()) {
                return PostingOutcome.rejected(item, REASON_INVALID_CARD, DESC_INVALID_CARD);
            }
            Long acctId = xref.get().getXrefAcctId();

            // 1500-B-LOOKUP-ACCT (CBTRN02C L393): unknown account -> reason 101.
            // Read the account under a pessimistic WRITE lock (SELECT ... FOR UPDATE) rather than a
            // plain findById. This lock is acquired inside the Spring Batch chunk transaction and is
            // held until the chunk commits (after the writer's 2800 account save), serializing
            // concurrent POSTTRAN launches on the same account so the read-modify-write of the
            // balance and cycle totals cannot lose updates. This reproduces the legacy dataset-level
            // exclusivity (POSTTRAN allocates ACCTDAT with DISP=OLD); the account lock also covers
            // the dependent 2700-UPDATE-TCATBAL row because a category-balance key contains the
            // account id, so a single lock suffices with no deadlock risk. See
            // AccountRepository#findByIdForUpdate and docs/decision-log.md.
            Optional<Account> accountOpt = accountRepository.findByIdForUpdate(acctId);
            if (accountOpt.isEmpty()) {
                return PostingOutcome.rejected(item, REASON_ACCT_NOT_FOUND, DESC_ACCT_NOT_FOUND);
            }
            Account account = accountOpt.get();

            BigDecimal amount = CobolDecimal.money(item.getAmount());
            int failReason = 0;
            String failDesc = "";

            // Credit-limit test (CBTRN02C L403-413): WS-TEMP-BAL = CYC-CREDIT - CYC-DEBIT + amount
            // (cycle deltas, NOT the current balance). creditLimit >= tempBal is OK, else reason 102.
            BigDecimal tempBal = CobolDecimal.money(
                    CobolDecimal.nullToZero(account.getCurrCycCredit())
                            .subtract(CobolDecimal.nullToZero(account.getCurrCycDebit()))
                            .add(amount));
            if (CobolDecimal.nullToZero(account.getCreditLimit()).compareTo(tempBal) < 0) {
                failReason = REASON_OVERLIMIT;
                failDesc = DESC_OVERLIMIT;
            }

            // Expiration test (CBTRN02C L414-420): a SEPARATE sequential IF that overwrites the reason.
            // QUIRK PRESERVED: when both the credit-limit and expiration checks fail, reason 103 wins
            // because it is assigned last. Compared as fixed-width strings exactly like the COBOL
            // (ISO yyyy-MM-dd makes lexical and chronological order equivalent); a blank/absent
            // expiration date sorts below any real date and therefore rejects as expired.
            String expirationDate = account.getExpiraionDate() == null
                    ? BLANK_DATE
                    : account.getExpiraionDate().toString();
            String originDate = origDatePrefix(item.getOrigTs());
            if (expirationDate.compareTo(originDate) < 0) {
                failReason = REASON_EXPIRED;
                failDesc = DESC_EXPIRED;
            }

            if (failReason != 0) {
                return PostingOutcome.rejected(item, failReason, failDesc);
            }

            // 2000-POST-TRANSACTION valid path: build the transaction, then compute the 2800 account
            // and 2700 category-balance mutations (persistence deferred to the writer).
            Transaction transaction = buildTransaction(item, amount);
            applyAccountMutation(account, amount);
            TransactionCategoryBalance categoryBalance = resolveCategoryBalance(acctId, item, amount);
            return PostingOutcome.posted(item, transaction, account, categoryBalance);
        }

        /** Maps {@code DALYTRAN-*} into a new {@link Transaction} (CBTRN02C 2000 L425-438). */
        private static Transaction buildTransaction(DailyTransaction item, BigDecimal amount) {
            Transaction transaction = new Transaction();
            transaction.setTranId(item.getId());
            transaction.setTranTypeCd(item.getTypeCd());
            transaction.setTranCatCd(item.getCatCd());
            transaction.setTranSource(item.getSource());
            transaction.setTranDesc(item.getDescription());
            transaction.setTranAmt(amount);
            transaction.setMerchantId(item.getMerchantId());
            transaction.setMerchantName(item.getMerchantName());
            transaction.setMerchantCity(item.getMerchantCity());
            transaction.setMerchantZip(item.getMerchantZip());
            transaction.setCardNum(item.getCardNum());
            transaction.setOrigTs(parseOrigTimestamp(item.getOrigTs()));
            transaction.setProcTs(LocalDateTime.now());
            return transaction;
        }

        /**
         * Applies {@code 2800-UPDATE-ACCOUNT-REC} (CBTRN02C L545-552): add the amount to the current
         * balance, then route it to the cycle credit ({@code amount >= 0}) or cycle debit bucket.
         */
        private static void applyAccountMutation(Account account, BigDecimal amount) {
            account.setCurrBal(CobolDecimal.money(
                    CobolDecimal.nullToZero(account.getCurrBal()).add(amount)));
            if (amount.signum() >= 0) {
                account.setCurrCycCredit(CobolDecimal.money(
                        CobolDecimal.nullToZero(account.getCurrCycCredit()).add(amount)));
            } else {
                account.setCurrCycDebit(CobolDecimal.money(
                        CobolDecimal.nullToZero(account.getCurrCycDebit()).add(amount)));
            }
        }

        /**
         * Applies {@code 2700-UPDATE-TCATBAL} (CBTRN02C L467-528). The composite key is
         * account + type + category; both found (status {@code 00}) and not-found (status {@code 23})
         * are acceptable. Found rows accumulate the amount ({@code 2700-B}); missing rows are created
         * initialized to the amount ({@code 2700-A}: INITIALIZE to zero then ADD the amount).
         */
        private TransactionCategoryBalance resolveCategoryBalance(Long acctId, DailyTransaction item,
                BigDecimal amount) {
            TransactionCategoryBalanceId key =
                    new TransactionCategoryBalanceId(acctId, item.getTypeCd(), item.getCatCd());
            Optional<TransactionCategoryBalance> existing = categoryBalanceRepository.findById(key);
            if (existing.isPresent()) {
                TransactionCategoryBalance categoryBalance = existing.get();
                categoryBalance.setBalance(CobolDecimal.money(
                        CobolDecimal.nullToZero(categoryBalance.getBalance()).add(amount)));
                return categoryBalance;
            }
            TransactionCategoryBalance categoryBalance = new TransactionCategoryBalance();
            categoryBalance.setAcctId(acctId);
            categoryBalance.setTypeCd(item.getTypeCd());
            categoryBalance.setCatCd(item.getCatCd());
            categoryBalance.setBalance(CobolDecimal.money(amount));
            return categoryBalance;
        }
    }

    /**
     * Persistence writer: applies the posted rows ({@code 2700} category balance, {@code 2800}
     * account, {@code 2900} transaction master, in that order) within the chunk transaction, and
     * writes rejected records ({@code 2500}) as 430-byte lines to the reject stream while accumulating
     * the reject count in the step execution context for the exit-status listener.
     *
     * <p>Not declared {@code final}: this writer is a {@code @StepScope} bean (it injects the
     * runtime {@code StepExecution}), so Spring supplies it through a CGLIB scoped proxy, which
     * must be able to subclass this type.</p>
     */
    public static class PostTransactionWriter implements ItemWriter<PostingOutcome> {

        private final TransactionRepository transactionRepository;
        private final AccountRepository accountRepository;
        private final TransactionCategoryBalanceRepository categoryBalanceRepository;
        private final FlatFileItemWriter<String> rejectDelegate;
        private final StepExecution stepExecution;

        /**
         * Creates the writer.
         *
         * @param transactionRepository     transaction master saves ({@code 2900})
         * @param accountRepository         account saves ({@code 2800})
         * @param categoryBalanceRepository category-balance saves ({@code 2700})
         * @param rejectDelegate            step-scoped reject file writer ({@code 2500})
         * @param stepExecution             the running step execution for reject counting
         */
        public PostTransactionWriter(TransactionRepository transactionRepository,
                AccountRepository accountRepository,
                TransactionCategoryBalanceRepository categoryBalanceRepository,
                FlatFileItemWriter<String> rejectDelegate,
                StepExecution stepExecution) {
            this.transactionRepository = transactionRepository;
            this.accountRepository = accountRepository;
            this.categoryBalanceRepository = categoryBalanceRepository;
            this.rejectDelegate = rejectDelegate;
            this.stepExecution = stepExecution;
        }

        /**
         * Persists posted items and emits reject lines for rejected items.
         *
         * @param chunk the chunk of posting outcomes
         * @throws Exception if the reject stream write fails
         */
        @Override
        public void write(Chunk<? extends PostingOutcome> chunk) throws Exception {
            List<String> rejects = new ArrayList<>();
            for (PostingOutcome outcome : chunk) {
                if (outcome.isPosted()) {
                    Transaction transaction = outcome.getTransaction();
                    // 2900-WRITE-TRANSACTION-FILE duplicate-key parity (CBTRN02C L562-577;
                    // AAP 0.6.5). The COBOL WRITE to the TRANSACT master is an insert: a key that
                    // already exists returns FILE STATUS '22' (a non-'00' status), which sets
                    // APPL-RESULT 12 and routes to 9999-ABEND-PROGRAM (CALL 'CEE3ABD', ABCODE 999 —
                    // a hard abend that yields a non-zero RETURN-CODE). Spring Data
                    // JpaRepository.save() would instead MERGE onto the existing primary key
                    // (a silent UPDATE), re-applying the already-computed 2800 account and 2700
                    // category-balance mutations on top of the prior posting while leaving a single
                    // transaction row — an unreconcilable ledger (balance moved twice, one tx row).
                    // Detect the collision BEFORE any save and raise the typed DuplicateKeyException
                    // (FILE STATUS "22"). Because the posting step is not fault-tolerant (no skip),
                    // the throw rolls back the whole chunk transaction (category balance + account +
                    // transaction), leaving nothing partially posted, and fails the job so the
                    // launcher surfaces a non-zero RETURN-CODE to the operator — behaviourally
                    // identical to the COBOL abend.
                    if (transactionRepository.existsById(transaction.getTranId())) {
                        throw new DuplicateKeyException(
                                "Duplicate transaction id on WRITE to the transaction master "
                                        + "(COBOL 2900-WRITE-TRANSACTION-FILE FILE STATUS '22'): "
                                        + transaction.getTranId().trim());
                    }
                    categoryBalanceRepository.save(outcome.getCategoryBalance());
                    accountRepository.save(outcome.getAccount());
                    transactionRepository.save(transaction);
                } else {
                    rejects.add(buildRejectLine(outcome.getSource(), outcome.getReasonCode(),
                            outcome.getReasonDesc()));
                }
            }
            if (!rejects.isEmpty()) {
                rejectDelegate.write(new Chunk<>(rejects));
                int current = stepExecution.getExecutionContext().getInt(REJECT_COUNT_KEY, 0);
                stepExecution.getExecutionContext().putInt(REJECT_COUNT_KEY, current + rejects.size());
                LOGGER.debug("Wrote {} DALYTRAN reject record(s) to the reject stream.", rejects.size());
            }
        }
    }
}
