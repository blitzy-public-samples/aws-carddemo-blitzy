/*
 * StatementGenJobConfig.java — Spring Batch Job for Statement Generation
 *
 * Source COBOL Programs: CBSTM03A.CBL (main engine) + CBSTM03B.CBL (I/O subroutine)
 * Source JCL Job: CREASTMT
 *
 * Migration Strategy:
 *   The JCL CREASTMT job orchestrated CBSTM03A (statement engine) which called
 *   CBSTM03B (I/O subroutine) to read XREF → Customer → Account → Transaction
 *   files and produce STMT-FILE (PIC X(80) plain text) and HTML-FILE (PIC X(100))
 *   statement outputs.
 *
 *   In Spring Batch, this becomes a chunk-oriented job:
 *     1. ItemReader: Reads CardXref records from PostgreSQL (← STARTBR/READNEXT CARDXREF)
 *     2. ItemProcessor: StatementProcessor builds statement content (← CBSTM03A paragraphs)
 *     3. ItemWriter: StatementFileWriter writes text + HTML files (← CBSTM03B WRITE operations)
 *
 * COBOL/JCL → Spring Batch Traceability:
 *   JCL CREASTMT            → @Bean Job statementGenJob
 *   JCL STEP1 EXEC PGM      → @Bean Step statementGenStep
 *   CBSTM03A 1000-OPEN-FILES → ItemReader initialization (CardXref findAll)
 *   CBSTM03A 3000-XREFFILE-GET → ItemReader.read()
 *   CBSTM03A 4000-TRNXFILE-GET → StatementProcessor.process() (transaction lookup)
 *   CBSTM03A 5000-CREATE-STATEMENT → StatementProcessor + StatementFileWriter
 *   CBSTM03B WRITE STMT-FILE → StatementFileWriter.write() (plain text)
 *   CBSTM03B WRITE HTML-FILE → StatementFileWriter.write() (HTML)
 *   CBSTM03A 9000-CLOSE-FILES → Spring Batch lifecycle (auto-close)
 *
 * Ver: CardDemo_v1.0 — Migrated from COBOL to Java 25 + Spring Boot 3.5.x
 */
package com.cardemo.batch.job;

import com.cardemo.batch.processor.StatementProcessor;
import com.cardemo.batch.writer.StatementFileWriter;
import com.cardemo.batch.writer.StatementFileWriter.StatementData;
import com.cardemo.entity.CardXref;
import com.cardemo.repository.CardXrefRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.support.ListItemReader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

/**
 * Spring Batch {@link Configuration} defining the statement generation job.
 *
 * <p>This configuration translates the JCL {@code CREASTMT} job which invoked
 * CBSTM03A (statement engine) and CBSTM03B (I/O subroutine). The job processes
 * all card cross-reference records, generating both plain-text and HTML
 * statements for each card holder.</p>
 *
 * <h3>Job Pipeline:</h3>
 * <pre>
 * CardXref (Reader) → StatementData (Processor) → Files (Writer)
 *   ↑ DB read          ↑ Lookup + format          ↑ .txt + .html output
 * </pre>
 *
 * <h3>Chunk Size:</h3>
 * <p>The chunk size of 10 balances memory usage against database round-trips.
 * Each chunk processes up to 10 card cross-reference records, generating up to
 * 10 pairs of statement files (some may be filtered by the processor if
 * the customer or account is missing).</p>
 *
 * <h3>Job Parameters:</h3>
 * <ul>
 *   <li>{@code outputDir} — Directory path for statement output files
 *       (required; the writer defaults to system temp dir if omitted)</li>
 * </ul>
 *
 * @see StatementProcessor transforms CardXref → StatementData
 * @see StatementFileWriter writes StatementData → .txt + .html files
 * @see CardXrefRepository provides CardXref records for reading
 */
@Configuration
public class StatementGenJobConfig {

    private static final Logger log = LoggerFactory.getLogger(StatementGenJobConfig.class);

    /**
     * Chunk size for statement generation. Each chunk processes this many
     * CardXref records, generating up to this many statement file pairs.
     * Matches the COBOL per-record processing pattern (no batched I/O).
     */
    private static final int CHUNK_SIZE = 10;

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final CardXrefRepository cardXrefRepository;
    private final StatementProcessor statementProcessor;
    private final StatementFileWriter statementFileWriter;

    /**
     * Constructor injection for all dependencies.
     *
     * @param jobRepository       Spring Batch job repository for metadata
     * @param transactionManager  transaction manager for chunk boundaries
     * @param cardXrefRepository  JPA repository for CardXref entity reads
     * @param statementProcessor  processor for CardXref → StatementData transformation
     * @param statementFileWriter writer for plain-text + HTML statement output
     */
    public StatementGenJobConfig(JobRepository jobRepository,
                                 PlatformTransactionManager transactionManager,
                                 CardXrefRepository cardXrefRepository,
                                 StatementProcessor statementProcessor,
                                 StatementFileWriter statementFileWriter) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.cardXrefRepository = cardXrefRepository;
        this.statementProcessor = statementProcessor;
        this.statementFileWriter = statementFileWriter;
    }

    /**
     * Defines the statement generation Spring Batch job.
     *
     * <p>Translates JCL {@code CREASTMT} job. The job has a single step that
     * reads all CardXref records, processes each into statement data (looking
     * up customer, account, and transaction information), and writes both
     * plain-text and HTML statement files.</p>
     *
     * <p><strong>COBOL Traceability:</strong></p>
     * <ul>
     *   <li>JCL: {@code //CREASTMT JOB} → this bean</li>
     *   <li>JCL: {@code //STEP1 EXEC PGM=CBSTM03A} → {@link #statementGenStep()}</li>
     * </ul>
     *
     * @return the configured statement generation job
     */
    @Bean
    public Job statementGenJob() {
        log.info("Configuring statementGenJob (← JCL CREASTMT)");
        return new JobBuilder("statementGenJob", jobRepository)
                .start(statementGenStep())
                .build();
    }

    /**
     * Defines the single step for statement generation using chunk-oriented
     * processing.
     *
     * <p>The step pipeline:</p>
     * <ol>
     *   <li><strong>Reader:</strong> {@link #cardXrefReader()} reads CardXref
     *       records from the database (← CBSTM03A 3000-XREFFILE-GET)</li>
     *   <li><strong>Processor:</strong> {@link StatementProcessor} looks up
     *       customer, account, and transactions, then builds statement content
     *       (← CBSTM03A 4000-TRNXFILE-GET + 5000-CREATE-STATEMENT).
     *       Returns {@code null} for missing customer/account, causing
     *       Spring Batch to filter the item.</li>
     *   <li><strong>Writer:</strong> {@link #statementFileWriter()} writes
     *       the formatted statements to .txt and .html files
     *       (← CBSTM03B WRITE STMT-FILE / HTML-FILE)</li>
     * </ol>
     *
     * @return the configured statement generation step
     */
    @Bean
    public Step statementGenStep() {
        return new StepBuilder("statementGenStep", jobRepository)
                .<CardXref, StatementData>chunk(CHUNK_SIZE, transactionManager)
                .reader(cardXrefReader())
                .processor(statementProcessor)
                .writer(statementFileWriter)
                .stream(statementFileWriter)
                .build();
    }

    /**
     * Creates a {@link ListItemReader} that reads all CardXref records from the
     * database. This translates the COBOL STARTBR/READNEXT pattern on
     * the CARDXREF VSAM file in CBSTM03A 3000-XREFFILE-GET.
     *
     * <p>The reader fetches all records at once and provides them one-by-one
     * via its internal list iterator. This is appropriate for the statement
     * generation use case where all active cards need statements.
     * Returning the concrete {@link ListItemReader} type (instead of the
     * {@code ItemReader} interface) ensures Spring Batch can detect
     * annotation-based listeners on the bean without proxy warnings.</p>
     *
     * <p><strong>COBOL Traceability:</strong></p>
     * <ul>
     *   <li>{@code EXEC CICS STARTBR FILE('CARDXREF')} → findAll()</li>
     *   <li>{@code EXEC CICS READNEXT} in 3000-XREFFILE-GET → ListItemReader.read()</li>
     *   <li>End of file → reader returns null (Spring Batch end signal)</li>
     * </ul>
     *
     * @return ListItemReader producing CardXref entities sequentially
     */
    @Bean
    @StepScope
    public ListItemReader<CardXref> cardXrefReader() {
        log.info("Opening CARDXREF reader (← 1000-OPEN-FILES / 3000-XREFFILE-GET)");
        List<CardXref> xrefs = cardXrefRepository.findAll();
        log.info("CARDXREF reader loaded {} records for statement generation", xrefs.size());
        return new ListItemReader<>(xrefs);
    }

}
