package com.aws.carddemo.batch;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.batch.item.data.builder.RepositoryItemReaderBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;

import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.repository.CardXrefRepository;

/**
 * Spring Batch job configuration translating the mainframe batch program {@code CBACT03C}
 * ("Read and print card cross-reference data file").
 *
 * <p>Origin: {@code legacy/cbl/CBACT03C.cbl} (source branch {@code app/cbl/CBACT03C.cbl}),
 * orchestrated by {@code legacy/jcl/READXREF.jcl} (source branch {@code app/jcl/READXREF.jcl}).
 * Implements AAP &sect;0.4.1 (batch COBOL program + JCL job &rarr; Spring Batch {@link Job}),
 * &sect;0.6.3 (JCL/JES2 &rarr; chunk-oriented Spring Batch topology), and the Observability rule
 * ({@code DISPLAY} &rarr; SYSOUT becomes structured SLF4J logging).</p>
 *
 * <p><strong>COBOL behaviour reproduced.</strong> On z/OS, {@code CBACT03C} opens the
 * {@code CCXREF}/{@code CARDXREF} VSAM KSDS ({@code XREFFILE}) for {@code INPUT} with
 * {@code ACCESS MODE IS SEQUENTIAL} on {@code RECORD KEY FD-XREF-CARD-NUM PIC X(16)}, then loops:
 * paragraph {@code 1000-XREFFILE-GET-NEXT} reads the next record and inspects the COBOL
 * {@code FILE STATUS} &mdash; {@code '00'} means process the record, {@code '10'} means end-of-file
 * (normal termination), and any other value displays the I/O status and abends
 * ({@code 9999-ABEND-PROGRAM} &rarr; {@code CALL 'CEE3ABD'}). Each successfully read
 * {@code CARD-XREF-RECORD} (copybook {@code CVACT03Y}, record length 50) is emitted with
 * {@code DISPLAY CARD-XREF-RECORD}; the file is then closed. It is a read-only diagnostic /
 * verification job: it performs no mutation and, per {@code READXREF.jcl}, writes no output dataset
 * (its only sinks are {@code SYSOUT}/{@code SYSPRINT}).</p>
 *
 * <p><strong>Target design (chunk-oriented print pattern).</strong> The sequential KSDS scan becomes
 * a single chunk-oriented {@link Step}:</p>
 * <ul>
 *   <li>a {@link RepositoryItemReader} over {@link CardXrefRepository#findAll} streams every
 *       cross-reference row <strong>ordered by {@code xrefCardNum} ascending</strong>, reproducing
 *       the KSDS card-number key order (AAP &sect;0.6.6 requires a deterministic bytewise/ASCII
 *       ordering to match the legacy collation, which the {@code C}-collated
 *       {@code card_xref.xref_card_num} column provides). The reader returning {@code null} when the
 *       result set is exhausted is the direct analogue of {@code FILE STATUS '10'} (end-of-file,
 *       normal termination);</li>
 *   <li>an {@link ItemWriter} logs one structured line per record, the analogue of
 *       {@code DISPLAY CARD-XREF-RECORD}.</li>
 * </ul>
 *
 * <p><strong>Sensitive-data handling in logs.</strong> {@code DISPLAY CARD-XREF-RECORD} would emit
 * the full 16-digit card number (a PAN) to SYSOUT. The migration's authoritative control (recorded
 * in {@code docs/decision-log.md}) is that <em>structured logging excludes PAN/CVV/SSN/password</em>
 * while file feeds and reports retain full values for interface parity. Because this job's only
 * output is the log (there is no output dataset), the card number is masked to its last four digits
 * before it is logged (CWE-532); the customer id and account id &mdash; internal surrogate keys that
 * are not part of the log-exclusion set &mdash; are logged in full so the cross-reference remains
 * verifiable. This is a non-behavioural control, not a change to any external interface contract.</p>
 *
 * <p><strong>Wiring note (AAP binding constraint).</strong> This class deliberately does
 * <strong>not</strong> use {@code @EnableBatchProcessing} and does not depend on
 * {@code config/BatchConfig}. It relies on Spring Boot's Batch auto-configuration, which supplies the
 * persistent, restartable {@link JobRepository} and the {@link PlatformTransactionManager} injected
 * through the constructor &mdash; the same convention used by the sibling batch configurations
 * ({@code AdminBatchJobConfig}, {@code DateConversionJobConfig}). The migration rationale lives in
 * {@code docs/decision-log.md} rather than in code comments (Explainability rule).</p>
 *
 * <p>This configuration is stateless and thread-safe: it holds only immutable collaborators supplied
 * at construction. The {@link RepositoryItemReader} bean is itself stateful across a step execution
 * but is opened, iterated, and closed by the step within a single sequential execution, and it is
 * configured with {@code saveState(false)} so every launch re-reads the file from the beginning,
 * matching the mainframe job which has no mid-file checkpoint.</p>
 */
@Configuration
public class XrefPrintJobConfig {

    /**
     * Logger that replaces the COBOL {@code DISPLAY} to SYSOUT. One structured, parameterized line is
     * emitted per cross-reference record (see {@link #xrefItemWriter()}), with the card number masked.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(XrefPrintJobConfig.class);

    /** Bean/registry name of the Spring Batch {@link Job} translating {@code CBACT03C}. */
    static final String JOB_NAME = "xrefPrintJob";

    /** Name of the single chunk-oriented step within {@link #JOB_NAME}. */
    static final String STEP_NAME = "xrefPrintStep";

    /**
     * {@link RepositoryItemReader} name, used as the prefix for any state this reader would store in
     * the step {@code ExecutionContext}. Supplied for clarity even though {@code saveState(false)}
     * means no state is persisted.
     */
    static final String READER_NAME = "xrefItemReader";

    /**
     * Name of the {@link CardXref} property used as the reader's ascending sort key. Ordering by the
     * card number reproduces the legacy {@code CCXREF} KSDS key order (the file is keyed on
     * {@code FD-XREF-CARD-NUM}); it must match an existing property so Spring Data can build the
     * {@code ORDER BY} clause.
     */
    private static final String SORT_PROPERTY_CARD_NUM = "xrefCardNum";

    /**
     * Chunk size (commit interval) and reader page size for this read-only print step. The value
     * {@value} matches the shared read-only print/report commit interval documented in
     * {@code config/BatchConfig} ({@code DEFAULT_CHUNK_SIZE}); it is redeclared locally because the
     * binding constraint forbids importing {@code config/BatchConfig}. Because each record is
     * independent and nothing is mutated, the commit interval has no effect on observable output.
     */
    private static final int CHUNK_SIZE = 100;

    /** Number of trailing card-number digits left visible when masking a PAN for logging (CWE-532). */
    private static final int PAN_VISIBLE_SUFFIX = 4;

    /** Single-character mask token repeated over the hidden portion of a masked card number. */
    private static final String PAN_MASK_UNIT = "*";

    /** Placeholder logged in place of a masked card number when the stored value is {@code null}. */
    private static final String CARD_NUMBER_ABSENT = "(absent)";

    /** Auto-configured Spring Batch job repository used to build the step and the job. */
    private final JobRepository jobRepository;

    /** Auto-configured transaction manager bounding each chunk's read/commit cycle. */
    private final PlatformTransactionManager transactionManager;

    /** Data access to the {@code CCXREF} cross-reference file (replaces the VSAM {@code XREFFILE}). */
    private final CardXrefRepository cardXrefRepository;

    /**
     * Creates the configuration with the collaborators supplied by Spring Boot's Batch
     * auto-configuration and component scanning.
     *
     * @param jobRepository      the auto-configured Spring Batch {@link JobRepository}
     * @param transactionManager the auto-configured {@link PlatformTransactionManager}
     * @param cardXrefRepository the repository over the {@link CardXref} cross-reference entity
     */
    public XrefPrintJobConfig(JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            CardXrefRepository cardXrefRepository) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.cardXrefRepository = cardXrefRepository;
    }

    /**
     * Reader that streams every {@link CardXref} row in ascending {@code xrefCardNum} order,
     * reproducing the sequential card-number-keyed scan of the {@code CCXREF} KSDS performed by
     * {@code CBACT03C} paragraph {@code 1000-XREFFILE-GET-NEXT}.
     *
     * <p>It delegates to {@link CardXrefRepository} (a {@code PagingAndSortingRepository}) invoking
     * {@code findAll} page by page with the sort applied. When the repository has no further rows the
     * reader returns {@code null}, which Spring Batch treats as end-of-input &mdash; the direct
     * analogue of COBOL {@code FILE STATUS '10'} (normal end-of-file). {@code saveState(false)} makes
     * each launch re-read from the first record, matching the mainframe job's checkpoint-free scan.</p>
     *
     * @return a configured {@link RepositoryItemReader} over the cross-reference file
     */
    @Bean
    public RepositoryItemReader<CardXref> xrefItemReader() {
        return new RepositoryItemReaderBuilder<CardXref>()
                .name(READER_NAME)
                .repository(cardXrefRepository)
                .methodName("findAll")
                .sorts(Map.of(SORT_PROPERTY_CARD_NUM, Sort.Direction.ASC))
                .pageSize(CHUNK_SIZE)
                .saveState(false)
                .build();
    }

    /**
     * Writer that reproduces {@code DISPLAY CARD-XREF-RECORD} by logging one structured line per
     * cross-reference record. The card number is masked to its last {@value #PAN_VISIBLE_SUFFIX}
     * digits before logging (CWE-532; see the class-level sensitive-data note); the customer id and
     * account id are logged in full so the mapping stays verifiable.
     *
     * @return an {@link ItemWriter} that logs each {@link CardXref} in the chunk
     */
    @Bean
    public ItemWriter<CardXref> xrefItemWriter() {
        return chunk -> {
            for (CardXref xref : chunk) {
                LOGGER.info("CBACT03C XREF record: cardNumber={} custId={} acctId={}",
                        maskCardNumber(xref.getXrefCardNum()),
                        xref.getXrefCustId(),
                        xref.getXrefAcctId());
            }
        };
    }

    /**
     * The single chunk-oriented step of {@link #xrefPrintJob()}: it reads every cross-reference row
     * via {@link #xrefItemReader()} and logs each via {@link #xrefItemWriter()} within the
     * auto-configured transaction boundary, committing every {@value #CHUNK_SIZE} records.
     *
     * @return the {@code xrefPrintStep} {@link Step}
     */
    @Bean
    public Step xrefPrintStep() {
        return new StepBuilder(STEP_NAME, jobRepository)
                .<CardXref, CardXref>chunk(CHUNK_SIZE, transactionManager)
                .reader(xrefItemReader())
                .writer(xrefItemWriter())
                .build();
    }

    /**
     * The Spring Batch {@link Job} corresponding to legacy JCL job {@code READXREF} running
     * {@code CBACT03C}. It consists of the single {@link #xrefPrintStep()} and completes with batch
     * status {@code COMPLETED} once the whole cross-reference file has been read and logged.
     *
     * @return the {@code xrefPrintJob} {@link Job}
     */
    @Bean
    public Job xrefPrintJob() {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(xrefPrintStep())
                .build();
    }

    /**
     * Masks a card number for logging, leaving only the last {@value #PAN_VISIBLE_SUFFIX} characters
     * visible and replacing every preceding character with {@value #PAN_MASK_UNIT} (CWE-532). Trailing
     * pad spaces from the fixed-width {@code PIC X(16)} field are removed first so the visible suffix
     * reflects real digits. A {@code null} value yields {@link #CARD_NUMBER_ABSENT}; a value no longer
     * than the visible suffix is masked in full so no meaningful digit is ever exposed.
     *
     * @param cardNumber the raw card number ({@code XREF-CARD-NUM}), possibly {@code null} or
     *                   space-padded
     * @return a masked representation safe to write to logs
     */
    private static String maskCardNumber(String cardNumber) {
        if (cardNumber == null) {
            return CARD_NUMBER_ABSENT;
        }
        String trimmed = cardNumber.stripTrailing();
        int length = trimmed.length();
        if (length <= PAN_VISIBLE_SUFFIX) {
            return PAN_MASK_UNIT.repeat(length);
        }
        return PAN_MASK_UNIT.repeat(length - PAN_VISIBLE_SUFFIX)
                + trimmed.substring(length - PAN_VISIBLE_SUFFIX);
    }
}
