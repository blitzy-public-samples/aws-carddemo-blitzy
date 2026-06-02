package com.carddemo.batch;

import com.carddemo.batch.reader.AsciiFixedWidthItemReader;
import com.carddemo.entity.DailyTransaction;
import com.carddemo.repository.DailyTransactionRepository;
import com.carddemo.util.FixedWidthRecordParser;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Spring Batch configuration for the <strong>{@code dailyTransactionReadJob}</strong> — the
 * Java/PostgreSQL replacement for the legacy batch COBOL program {@code app/cbl/CBTRN01C.cbl}
 * ("Post the records from daily transaction file") in its role as the <em>daily-feed staging
 * loader</em>.
 *
 * <h2>Legacy mainframe behavior (CBTRN01C.cbl)</h2>
 * The original COBOL program opens the sequential {@code DALYTRAN-FILE} (a PS file laid out by
 * {@code app/cpy/CVTRA06Y.cpy} {@code DALYTRAN-RECORD}, RECLN 350) and loops
 * {@code PERFORM UNTIL END-OF-DAILY-TRANS-FILE = 'Y'}, reading each daily-transaction record from
 * the feed. In the mainframe pipeline this sequential PS file is the input consumed downstream by
 * the posting program {@code CBTRN02C} (the {@code POSTTRAN} job).
 *
 * <h2>Modernized Spring Batch behavior — staging loader (AAP &sect;0.6.6 step 1)</h2>
 * PostgreSQL cannot directly consume the fixed-width EBCDIC/ASCII PS feed, so this job
 * <strong>materializes</strong> the daily feed into the {@code daily_transactions} staging table —
 * the relational stand-in for the sequential {@code DALYTRAN} PS file. Per the AAP transformation
 * plan (&sect;0.4.1.2 and &sect;0.6.6 step 1):
 *
 * <blockquote>"{@code DailyTransactionReadJobConfig} … reads {@code app/data/ASCII/dailytran.txt}
 * via {@code FlatFileItemReader} + {@code FixedWidthRecordParser}; persists to
 * {@code daily_transactions} staging table … with {@code processed = false}."</blockquote>
 *
 * The chunk-oriented step therefore streams the 300 fixed-width records of
 * {@code app/data/ASCII/dailytran.txt} through {@link AsciiFixedWidthItemReader} (the AAP-planned
 * fixed-width reader, also used by {@code DataInitializationJobConfig}), maps each line to a
 * {@link DailyTransaction} entity via the {@code CVTRA06Y} {@code PIC}-clause offsets, and persists
 * the batch through {@link DailyTransactionRepository#saveAll(Iterable)}. Each staged row is written
 * with {@code processed = false} so the downstream {@code POSTTRAN} job
 * ({@code TransactionPostingJobConfig}) — whose reader selects unprocessed rows via
 * {@link DailyTransactionRepository#findByProcessedFalse(org.springframework.data.domain.Pageable)}
 * — has real input to validate and post (validation codes 100/101/102/103, the {@code TCATBAL}
 * upsert, and the sign-based account-balance bucket).
 *
 * <p>This is precisely the <em>pre-step</em> described in AAP &sect;0.6.6: in a clean environment,
 * launching this job loads the daily feed so that {@code POSTTRAN → INTCALC → COMBTRAN → CREASTMT}
 * (PR-12) can run end-to-end unaided. The substantive validation/posting business logic remains
 * owned by {@code POSTTRAN}; this job's sole responsibility is faithful ingestion of the feed.</p>
 *
 * <h2>Fixed-width record layout — {@code app/cpy/CVTRA06Y.cpy} {@code DALYTRAN-RECORD} (350 bytes)</h2>
 * <table border="1">
 *   <caption>Field offsets (zero-based) and lengths derived from the COBOL {@code PIC} clauses</caption>
 *   <tr><th>COBOL field</th><th>PIC</th><th>offset</th><th>length</th><th>Java target</th></tr>
 *   <tr><td>DALYTRAN-ID</td><td>X(16)</td><td>0</td><td>16</td><td>{@code dalytranId} (String)</td></tr>
 *   <tr><td>DALYTRAN-TYPE-CD</td><td>X(02)</td><td>16</td><td>2</td><td>{@code typeCd} (String, CHAR(2))</td></tr>
 *   <tr><td>DALYTRAN-CAT-CD</td><td>9(04)</td><td>18</td><td>4</td><td>{@code categoryCd} (String, CHAR(4); leading zeros preserved)</td></tr>
 *   <tr><td>DALYTRAN-SOURCE</td><td>X(10)</td><td>22</td><td>10</td><td>{@code source} (String)</td></tr>
 *   <tr><td>DALYTRAN-DESC</td><td>X(100)</td><td>32</td><td>100</td><td>{@code description} (String)</td></tr>
 *   <tr><td>DALYTRAN-AMT</td><td>S9(09)V99</td><td>132</td><td>11</td><td>{@code amount} (BigDecimal, scale 2; zoned-decimal sign overpunch)</td></tr>
 *   <tr><td>DALYTRAN-MERCHANT-ID</td><td>9(09)</td><td>143</td><td>9</td><td>{@code merchantId} (Long)</td></tr>
 *   <tr><td>DALYTRAN-MERCHANT-NAME</td><td>X(50)</td><td>152</td><td>50</td><td>{@code merchantName} (String)</td></tr>
 *   <tr><td>DALYTRAN-MERCHANT-CITY</td><td>X(50)</td><td>202</td><td>50</td><td>{@code merchantCity} (String)</td></tr>
 *   <tr><td>DALYTRAN-MERCHANT-ZIP</td><td>X(10)</td><td>252</td><td>10</td><td>{@code merchantZip} (String)</td></tr>
 *   <tr><td>DALYTRAN-CARD-NUM</td><td>X(16)</td><td>262</td><td>16</td><td>{@code cardNum} (String)</td></tr>
 *   <tr><td>DALYTRAN-ORIG-TS</td><td>X(26)</td><td>278</td><td>26</td><td>{@code origTimestamp} (LocalDateTime)</td></tr>
 *   <tr><td>DALYTRAN-PROC-TS</td><td>X(26)</td><td>304</td><td>26</td><td>{@code procTimestamp} (LocalDateTime; blank in feed &rarr; {@code null})</td></tr>
 *   <tr><td>FILLER</td><td>X(20)</td><td>330</td><td>20</td><td>(unmapped)</td></tr>
 * </table>
 *
 * <p>The monetary {@code DALYTRAN-AMT} field is COBOL zoned-decimal with the sign overpunched on the
 * trailing digit (e.g. {@code 'G'} &rarr; {@code +7}, {@code '}'} &rarr; {@code -0}); it is decoded
 * and scaled by {@link AsciiFixedWidthItemReader#parseZonedDecimal(String, int, int, int)} to a
 * {@link java.math.BigDecimal} of scale 2 with {@link java.math.RoundingMode#HALF_UP} (PR-16). The
 * two 26-character timestamps in {@code dailytran.txt} are stored in the form
 * {@code "yyyy-MM-dd HH:mm:ss.SSSSSS"} and are parsed by
 * {@link FixedWidthRecordParser#parseLocalDateTime(String, int, int, String)}; {@code DALYTRAN-PROC-TS}
 * is blank in the feed (the rows are unprocessed) and therefore maps to {@code null}.</p>
 *
 * <h2>Idempotent re-runs</h2>
 * <p>The streaming reader is wrapped in {@link ExistingDataSkippingReader}: if the
 * {@code daily_transactions} table is already populated, the load is skipped (returns EOF without
 * opening the fixture), so re-launching the job does not stack duplicate feed rows. When the table
 * is empty, the underlying {@link AsciiFixedWidthItemReader#open(ExecutionContext)} validates that
 * {@code dailytran.txt} exists and raises {@link ItemStreamException} otherwise. This mirrors the
 * existing-data guard used by {@code DataInitializationJobConfig} for every fixture load.</p>
 *
 * <h2>Step shape</h2>
 * <pre>
 *   AsciiFixedWidthItemReader&lt;DailyTransaction&gt; (over app/data/ASCII/dailytran.txt, CVTRA06Y layout)
 *        wrapped by ExistingDataSkippingReader (skip when daily_transactions already populated)
 *        -&gt; ItemWriter (DailyTransactionRepository.saveAll; rows persisted with processed=false)
 * </pre>
 *
 * <h2>Refactoring rules enforced</h2>
 * <ul>
 *   <li><strong>PR-11</strong> (DB2 timestamp format): {@code origTimestamp}/{@code procTimestamp}
 *       are normalized from the 26-character external timestamp at this I/O boundary.</li>
 *   <li><strong>PR-13</strong> (record-length fidelity): field offsets/lengths mirror the
 *       {@code CVTRA06Y} {@code PIC} clauses exactly.</li>
 *   <li><strong>PR-16</strong> (exact decimal): {@code amount} is a {@link java.math.BigDecimal} of
 *       scale 2 with {@link java.math.RoundingMode#HALF_UP}; {@code float}/{@code double} are never
 *       used.</li>
 *   <li><strong>PR-25</strong> (single monolith): this batch job runs inside the one Spring Boot
 *       context — no microservice or external scheduler.</li>
 *   <li><strong>PR-27</strong> (sources preserved): {@code app/data/ASCII/dailytran.txt} and the
 *       {@code CVTRA06Y} copybook are read unchanged as input/REFERENCE.</li>
 *   <li><strong>PR-28</strong> (Jakarta / Spring 6 baseline): only Spring Framework 6.1 / Spring
 *       Batch 5.1 APIs are used; no {@code javax.*} types.</li>
 *   <li><strong>PR-29</strong> (constructor injection only): the three collaborators are
 *       {@code final} and injected through the Lombok {@code @RequiredArgsConstructor}-generated
 *       constructor — no field injection.</li>
 * </ul>
 *
 * <p><strong>Why {@code @EnableBatchProcessing} is intentionally absent:</strong> under Spring
 * Boot 3.x the {@code spring-boot-starter-batch} auto-configuration supplies the
 * {@link JobRepository}, {@code JobLauncher}, {@code JobRegistry} and {@code JobExplorer}; adding
 * {@code @EnableBatchProcessing} would disable that auto-configuration (see {@code BatchConfig}).
 * Both the {@code @Bean Job} and {@code @Bean Step} defined here are auto-registered with the
 * {@code JobRegistry}, so {@code BatchAdminController} can launch this job by its
 * {@link #JOB_NAME}.</p>
 *
 * @see com.carddemo.entity.DailyTransaction
 * @see com.carddemo.repository.DailyTransactionRepository
 * @see com.carddemo.batch.reader.AsciiFixedWidthItemReader
 * @see TransactionPostingJobConfig
 * @see DataInitializationJobConfig
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class DailyTransactionReadJobConfig {

    /**
     * Logical name of the {@link Job} bean. Used by the {@code JobRegistry} and the
     * {@code BatchAdminController} ({@code POST /api/admin/jobs/{jobName}/launch}) to launch this
     * staging-loader job by name.
     */
    public static final String JOB_NAME = "dailyTransactionReadJob";

    /** Logical name of the single chunk-oriented staging {@link Step}. */
    private static final String STEP_NAME = "dailyTransactionReadStep";

    /**
     * {@link AsciiFixedWidthItemReader} name — used as the key prefix under which the reader saves
     * and restores its restart line-count in the step {@code ExecutionContext}. Distinct from the
     * {@code POSTTRAN} reader name ({@code "transactionPostingDailyTransactionReader"}) to avoid any
     * {@code ExecutionContext}/bean-name collision.
     */
    private static final String READER_NAME = "dailyTransactionStagingReader";

    /** Bare name of the daily-feed fixture under {@code app/data/ASCII/} (PR-27, read-only input). */
    private static final String FIXTURE_FILE = "dailytran.txt";

    /**
     * Chunk size, doubling as the commit granularity. A value of {@value} matches the codebase-wide
     * default chunk size (AAP &sect;0.3.3 #7) and the {@code DataInitializationJobConfig} fixture
     * loads; each committed chunk is a Spring Batch checkpoint boundary supporting restart.
     */
    private static final int CHUNK_SIZE = 100;

    /** Implied decimal scale for the COBOL {@code S9(09)V99} {@code DALYTRAN-AMT} field (PR-16). */
    private static final int MONEY_SCALE = 2;

    /**
     * Minimum line length required to safely extract every mapped field. The last mapped field,
     * {@code DALYTRAN-PROC-TS}, ends at offset {@value} (304 + 26); the trailing 20-byte
     * {@code FILLER} is intentionally not read. A shorter line indicates a truncated/corrupt record
     * and is rejected loudly (mirrors the short-record guard in {@code DataInitializationJobConfig}).
     */
    private static final int MIN_RECORD_LENGTH = 330;

    /**
     * Layout of the 26-character timestamp fields as stored in {@code dailytran.txt}
     * ({@code "2022-06-10 19:27:53.000000"} — date, space, time, six fractional-second digits). The
     * DB2 external form ({@code "yyyy-MM-dd-HH.mm.ss.SSSSSS"}) is reproduced separately at output
     * boundaries via {@code DateConversionUtil} (PR-11); here we parse the source feed layout.
     */
    private static final String FEED_TIMESTAMP_PATTERN = "yyyy-MM-dd HH:mm:ss.SSSSSS";

    // --- CVTRA06Y DALYTRAN-RECORD field offsets/lengths (verified against the fixture) ---
    private static final int ID_OFFSET = 0;
    private static final int ID_LENGTH = 16;
    private static final int TYPE_CD_OFFSET = 16;
    private static final int TYPE_CD_LENGTH = 2;
    private static final int CAT_CD_OFFSET = 18;
    private static final int CAT_CD_LENGTH = 4;
    private static final int SOURCE_OFFSET = 22;
    private static final int SOURCE_LENGTH = 10;
    private static final int DESC_OFFSET = 32;
    private static final int DESC_LENGTH = 100;
    private static final int AMT_OFFSET = 132;
    private static final int AMT_LENGTH = 11;
    private static final int MERCHANT_ID_OFFSET = 143;
    private static final int MERCHANT_ID_LENGTH = 9;
    private static final int MERCHANT_NAME_OFFSET = 152;
    private static final int MERCHANT_NAME_LENGTH = 50;
    private static final int MERCHANT_CITY_OFFSET = 202;
    private static final int MERCHANT_CITY_LENGTH = 50;
    private static final int MERCHANT_ZIP_OFFSET = 252;
    private static final int MERCHANT_ZIP_LENGTH = 10;
    private static final int CARD_NUM_OFFSET = 262;
    private static final int CARD_NUM_LENGTH = 16;
    private static final int ORIG_TS_OFFSET = 278;
    private static final int ORIG_TS_LENGTH = 26;
    private static final int PROC_TS_OFFSET = 304;
    private static final int PROC_TS_LENGTH = 26;

    /** Spring Batch metadata repository (auto-configured by Spring Boot 3.x). */
    private final JobRepository jobRepository;

    /**
     * Transaction manager bracketing each chunk's unit of work — the {@code SYNCPOINT}-equivalent
     * boundary for the staging step (PR-24). Each committed chunk persists its staged rows and
     * advances the reader's restart cursor atomically.
     */
    private final PlatformTransactionManager transactionManager;

    /**
     * Spring Data JPA repository over the {@code daily_transactions} staging table. Used both to
     * persist the parsed feed rows ({@link DailyTransactionRepository#saveAll(Iterable)}) and to
     * decide whether a prior load already populated the table ({@code count()} idempotency guard).
     */
    private final DailyTransactionRepository dailyTransactionRepository;

    /**
     * Maps a single fixed-width {@code DALYTRAN-RECORD} line to a transient {@link DailyTransaction}
     * staging entity, using the verified {@code CVTRA06Y} offsets.
     *
     * <p>Plain alphanumeric/date/numeric fields are extracted by {@link FixedWidthRecordParser}; the
     * signed {@code DALYTRAN-AMT} money field is decoded (zoned-decimal sign overpunch) and scaled by
     * {@link AsciiFixedWidthItemReader#parseZonedDecimal(String, int, int, int)} (PR-16). Every row is
     * staged with {@code processed = false} so the downstream {@code POSTTRAN} job picks it up via
     * {@code findByProcessedFalse}.</p>
     *
     * @param line a non-blank fixed-width line at least {@link #MIN_RECORD_LENGTH} characters long
     * @return the mapped, transient {@link DailyTransaction} (not yet persisted)
     */
    static DailyTransaction mapDailyTransaction(String line) {
        DailyTransaction dt = new DailyTransaction();
        dt.setDalytranId(FixedWidthRecordParser.parseString(line, ID_OFFSET, ID_LENGTH));
        dt.setTypeCd(FixedWidthRecordParser.parseString(line, TYPE_CD_OFFSET, TYPE_CD_LENGTH));
        dt.setCategoryCd(FixedWidthRecordParser.parseString(line, CAT_CD_OFFSET, CAT_CD_LENGTH));
        dt.setSource(FixedWidthRecordParser.parseString(line, SOURCE_OFFSET, SOURCE_LENGTH));
        dt.setDescription(FixedWidthRecordParser.parseString(line, DESC_OFFSET, DESC_LENGTH));
        dt.setAmount(AsciiFixedWidthItemReader.parseZonedDecimal(line, AMT_OFFSET, AMT_LENGTH, MONEY_SCALE));
        dt.setMerchantId(FixedWidthRecordParser.parseLong(line, MERCHANT_ID_OFFSET, MERCHANT_ID_LENGTH));
        dt.setMerchantName(FixedWidthRecordParser.parseString(line, MERCHANT_NAME_OFFSET, MERCHANT_NAME_LENGTH));
        dt.setMerchantCity(FixedWidthRecordParser.parseString(line, MERCHANT_CITY_OFFSET, MERCHANT_CITY_LENGTH));
        dt.setMerchantZip(FixedWidthRecordParser.parseString(line, MERCHANT_ZIP_OFFSET, MERCHANT_ZIP_LENGTH));
        dt.setCardNum(FixedWidthRecordParser.parseString(line, CARD_NUM_OFFSET, CARD_NUM_LENGTH));
        dt.setOrigTimestamp(
                FixedWidthRecordParser.parseLocalDateTime(line, ORIG_TS_OFFSET, ORIG_TS_LENGTH, FEED_TIMESTAMP_PATTERN));
        dt.setProcTimestamp(
                FixedWidthRecordParser.parseLocalDateTime(line, PROC_TS_OFFSET, PROC_TS_LENGTH, FEED_TIMESTAMP_PATTERN));
        dt.setProcessed(false);
        return dt;
    }

    /**
     * Builds the streaming reader for the daily feed: an {@link AsciiFixedWidthItemReader} over
     * {@code app/data/ASCII/dailytran.txt} (classpath {@code fixtures/} first, filesystem fallback),
     * mapping each line via {@link #mapDailyTransaction(String)} and wrapped in an
     * {@link ExistingDataSkippingReader} so an already-populated table makes the load an idempotent
     * no-op. Not exposed as a {@code @Bean} (it is reader state local to the single step), mirroring
     * the {@code DataInitializationJobConfig} fixture-reader idiom.
     *
     * @return the configured, idempotency-aware {@link ItemStreamReader}
     */
    private ItemStreamReader<DailyTransaction> dailyTransactionStagingReader() {
        AsciiFixedWidthItemReader<DailyTransaction> delegate = new AsciiFixedWidthItemReader<>(
                AsciiFixedWidthItemReader.resolveFixtureResource(FIXTURE_FILE),
                line -> {
                    if (line.length() < MIN_RECORD_LENGTH) {
                        throw new IllegalArgumentException(
                                "Short DALYTRAN record in " + FIXTURE_FILE + " at length "
                                        + line.length() + "; expected at least " + MIN_RECORD_LENGTH);
                    }
                    return mapDailyTransaction(line);
                });
        delegate.setName(READER_NAME);
        return new ExistingDataSkippingReader(delegate);
    }

    /**
     * Chunk-oriented staging {@link Step} wiring the fixed-width reader to a repository-backed writer.
     *
     * <p>The {@link #transactionManager} delimits each chunk's transaction boundary (PR-24); the
     * chunk size is {@link #CHUNK_SIZE}. The inline writer persists each chunk through
     * {@link DailyTransactionRepository#saveAll(Iterable)} (the proven {@code DataInitializationJobConfig}
     * writer idiom) so the mapped {@link DailyTransaction} rows — each with {@code processed = false}
     * — land in the {@code daily_transactions} staging table.</p>
     *
     * @return the {@code dailyTransactionReadStep} bean
     */
    @Bean
    public Step dailyTransactionReadStep() {
        return new StepBuilder(STEP_NAME, jobRepository)
                .<DailyTransaction, DailyTransaction>chunk(CHUNK_SIZE, transactionManager)
                .reader(dailyTransactionStagingReader())
                .writer(chunk -> {
                    dailyTransactionRepository.saveAll(chunk.getItems());
                    log.info("{}: staged {} DailyTransaction record(s) from {} into daily_transactions "
                                    + "(processed=false)",
                            STEP_NAME, chunk.size(), FIXTURE_FILE);
                })
                .build();
    }

    /**
     * The staging-loader {@link Job} bean ({@link #JOB_NAME}) consisting of the single
     * {@link #dailyTransactionReadStep()}.
     *
     * <p>Auto-registered with the Spring Batch {@code JobRegistry} by Spring Boot auto-configuration,
     * so it is launchable by name through {@code BatchAdminController}. In a clean environment this is
     * the pre-step that loads the daily feed into {@code daily_transactions} before the
     * {@code POSTTRAN} job posts it (AAP &sect;0.6.6).</p>
     *
     * @return the {@code dailyTransactionReadJob} bean
     */
    @Bean
    public Job dailyTransactionReadJob() {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(dailyTransactionReadStep())
                .build();
    }

    /**
     * {@link ItemStreamReader} wrapper that preserves idempotent re-runs without weakening
     * missing-fixture validation on an empty table.
     *
     * <p>On {@link #open(ExecutionContext)} it consults {@link DailyTransactionRepository#count()}:
     * when the {@code daily_transactions} table already holds rows it sets a skip flag and returns
     * EOF on every {@link #read()} (no fixture is opened), so re-launching the staging job is a safe
     * no-op rather than a source of duplicate feed rows. When the table is empty it delegates to the
     * underlying {@link AsciiFixedWidthItemReader}, whose {@code open} validates that the fixture
     * exists and raises {@link ItemStreamException} otherwise. This mirrors the
     * {@code DataInitializationJobConfig.ExistingDataSkippingReader} guard.</p>
     */
    private final class ExistingDataSkippingReader implements ItemStreamReader<DailyTransaction> {

        private final AsciiFixedWidthItemReader<DailyTransaction> delegate;
        private boolean skip;

        private ExistingDataSkippingReader(AsciiFixedWidthItemReader<DailyTransaction> delegate) {
            this.delegate = delegate;
        }

        @Override
        public void open(ExecutionContext executionContext) throws ItemStreamException {
            long count = dailyTransactionRepository.count();
            if (count > 0) {
                skip = true;
                log.info("{}: daily_transactions already populated (count={}); skipping {} staging load",
                        STEP_NAME, count, FIXTURE_FILE);
                return;
            }
            skip = false;
            delegate.open(executionContext);
        }

        @Override
        public DailyTransaction read() throws Exception {
            return skip ? null : delegate.read();
        }

        @Override
        public void update(ExecutionContext executionContext) throws ItemStreamException {
            if (!skip) {
                delegate.update(executionContext);
            }
        }

        @Override
        public void close() throws ItemStreamException {
            if (!skip) {
                delegate.close();
            }
        }
    }
}
