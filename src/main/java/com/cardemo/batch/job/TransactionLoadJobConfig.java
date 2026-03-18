/*
 * TransactionLoadJobConfig.java — Spring Batch Job Configuration for Transaction Seed Data Loading
 *
 * Source COBOL Program: CBTRN01C.cbl
 *   Function: End-to-end posting and verification of daily transaction records /
 *             Transaction file utility — opens 6 datasets (DALYTRAN, CUSTOMER,
 *             XREF, CARD, ACCOUNT, TRANSACT), iterates daily transactions,
 *             validates card numbers through cross-reference lookup, and retrieves
 *             account records by keyed reads.
 *
 * Source Copybook: CVTRA05Y.cpy (TRAN-RECORD, 350 bytes)
 *   Fields: TRAN-ID PIC X(16), TRAN-TYPE-CD PIC X(02), TRAN-CAT-CD PIC 9(04),
 *           TRAN-SOURCE PIC X(10), TRAN-DESC PIC X(100),
 *           TRAN-AMT PIC S9(09)V99, TRAN-MERCHANT-ID PIC 9(09),
 *           TRAN-MERCHANT-NAME PIC X(50), TRAN-MERCHANT-CITY PIC X(50),
 *           TRAN-MERCHANT-ZIP PIC X(10), TRAN-CARD-NUM PIC X(16),
 *           TRAN-ORIG-TS PIC X(26), TRAN-PROC-TS PIC X(26), FILLER PIC X(20)
 *
 * Source Data: app/data/ASCII/dailytran.txt (daily transaction seed records)
 *
 * COBOL Paragraph-to-Java Method Mapping:
 *   MAIN-PARA                          → transactionLoadJob() + transactionLoadStep()
 *   0000-0500 OPEN (6 files)           → Spring Batch lifecycle open()
 *   1000-DALYTRAN-GET-NEXT             → transactionFileReader() → read()
 *   2000-LOOKUP-XREF                   → transactionWriter() →
 *                                        TransactionUtilService.lookupXref()
 *   3000-READ-ACCOUNT                  → transactionWriter() →
 *                                        TransactionUtilService.readAccount()
 *   9000-9500 CLOSE (6 files)          → Spring Batch lifecycle close()
 *   Z-DISPLAY-IO-STATUS                → SLF4J Logger.warn()
 *   Z-ABEND-PROGRAM (CALL 'CEE3ABD')   → Exception handling — throw RuntimeException
 *
 * Ver: CardDemo_v1.0 — Migrated from COBOL to Java 25 + Spring Boot 3.5.x
 */
package com.cardemo.batch.job;

import com.cardemo.batch.reader.FixedWidthFileReader;
import com.cardemo.entity.Transaction;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.service.batch.TransactionUtilService;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.transform.FieldSet;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Spring Batch job configuration for loading transaction seed data from the
 * fixed-width {@code dailytran.txt} file into the PostgreSQL {@code transactions}
 * table.
 *
 * <p>Translates the JCL TRANFILE batch job (backed by COBOL program CBTRN01C.cbl)
 * into a Spring Batch {@link Job} with a single chunk-oriented {@link Step}.
 * The original COBOL program opens 6 datasets, iterates daily transaction records,
 * validates card numbers through cross-reference lookup (2000-LOOKUP-XREF), and
 * retrieves account records by keyed reads (3000-READ-ACCOUNT). In the Java
 * migration, this becomes a seed data loader that parses fixed-width records
 * and persists them as JPA {@link Transaction} entities, with post-insert
 * XREF/Account validation matching the COBOL behavior.</p>
 *
 * <h3>Processing Flow (mirrors CBTRN01C.cbl)</h3>
 * <ol>
 *   <li>Log start banner (replaces COBOL
 *       {@code DISPLAY 'START OF EXECUTION OF PROGRAM CBTRN01C'})</li>
 *   <li>Open reader for dailytran.txt
 *       (replaces paragraphs {@code 0000-DALYTRAN-OPEN} through
 *       {@code 0500-TRANFILE-OPEN})</li>
 *   <li>Read each 350-byte fixed-width record
 *       (replaces {@code 1000-DALYTRAN-GET-NEXT} paragraph)</li>
 *   <li>Parse fields per CVTRA05Y.cpy layout and persist to database</li>
 *   <li>Validate cross-references and accounts (replaces
 *       {@code 2000-LOOKUP-XREF} and {@code 3000-READ-ACCOUNT} paragraphs)
 *       — INVALID KEY → log warning, skip (continue to next record)</li>
 *   <li>Close reader (replaces paragraphs {@code 9000-DALYTRAN-CLOSE}
 *       through {@code 9500-TRANFILE-CLOSE})</li>
 *   <li>Log completion banner (replaces COBOL
 *       {@code DISPLAY 'END OF EXECUTION OF PROGRAM CBTRN01C'})</li>
 * </ol>
 *
 * <h3>Error Handling (CRITICAL — matches CBTRN01C.cbl exactly)</h3>
 * <ul>
 *   <li><strong>INVALID KEY on XREF/Account lookup</strong>: Log warning via
 *       SLF4J and continue to next record — DO NOT abort. This matches the
 *       COBOL pattern where {@code INVALID KEY} sets
 *       {@code WS-XREF-READ-STATUS} or {@code WS-ACCT-READ-STATUS} to '4'
 *       and proceeds to the next record.</li>
 *   <li><strong>I/O errors</strong>: Throw RuntimeException to abort the step
 *       (matches COBOL {@code Z-ABEND-PROGRAM → CALL 'CEE3ABD'}).</li>
 * </ul>
 *
 * <h3>Data Format: dailytran.txt (CVTRA05Y.cpy TRAN-RECORD, RECLN 350)</h3>
 * <table>
 *   <caption>Fixed-width field layout</caption>
 *   <tr><th>Field</th><th>COBOL PIC</th><th>Positions</th><th>Java Type</th></tr>
 *   <tr><td>TRAN-ID</td><td>X(16)</td><td>1-16</td><td>String</td></tr>
 *   <tr><td>TRAN-TYPE-CD</td><td>X(02)</td><td>17-18</td><td>String</td></tr>
 *   <tr><td>TRAN-CAT-CD</td><td>9(04)</td><td>19-22</td><td>Integer</td></tr>
 *   <tr><td>TRAN-SOURCE</td><td>X(10)</td><td>23-32</td><td>String</td></tr>
 *   <tr><td>TRAN-DESC</td><td>X(100)</td><td>33-132</td><td>String</td></tr>
 *   <tr><td>TRAN-AMT</td><td>S9(09)V99</td><td>133-143</td><td>BigDecimal</td></tr>
 *   <tr><td>TRAN-MERCHANT-ID</td><td>9(09)</td><td>144-152</td><td>String</td></tr>
 *   <tr><td>TRAN-MERCHANT-NAME</td><td>X(50)</td><td>153-202</td><td>String</td></tr>
 *   <tr><td>TRAN-MERCHANT-CITY</td><td>X(50)</td><td>203-252</td><td>String</td></tr>
 *   <tr><td>TRAN-MERCHANT-ZIP</td><td>X(10)</td><td>253-262</td><td>String</td></tr>
 *   <tr><td>TRAN-CARD-NUM</td><td>X(16)</td><td>263-278</td><td>String</td></tr>
 *   <tr><td>TRAN-ORIG-TS</td><td>X(26)</td><td>279-304</td><td>String</td></tr>
 *   <tr><td>TRAN-PROC-TS</td><td>X(26)</td><td>305-330</td><td>String</td></tr>
 *   <tr><td>FILLER</td><td>X(20)</td><td>331-350</td><td>not mapped</td></tr>
 * </table>
 *
 * @see FixedWidthFileReader
 * @see Transaction
 * @see TransactionRepository
 * @see TransactionUtilService
 */
@Configuration
public class TransactionLoadJobConfig {

    /**
     * SLF4J logger replacing COBOL DISPLAY statements from CBTRN01C.cbl.
     * Provides info-level for job start/end banners, debug-level for
     * record field display (replaces COBOL DISPLAY in 1000-DALYTRAN-GET-NEXT),
     * warn-level for INVALID KEY diagnostics (replaces Z-DISPLAY-IO-STATUS),
     * and error-level for I/O failure diagnostics.
     */
    private static final Logger LOG = LoggerFactory.getLogger(TransactionLoadJobConfig.class);

    /**
     * Chunk size for batch processing — 10 records per transaction commit.
     * Each chunk is committed atomically, matching COBOL batch transaction
     * semantics from CBTRN01C.cbl's sequential processing loop.
     */
    private static final int CHUNK_SIZE = 10;

    /**
     * Classpath location of the fixed-width transaction seed data file.
     * Points to the test fixtures directory where dailytran.txt is stored.
     * The file contains daily transaction records matching the CVTRA05Y.cpy
     * TRAN-RECORD layout (350 bytes per record).
     */
    private static final String TRANSACTION_DATA_RESOURCE = "fixtures/dailytran.txt";

    /** Spring Batch job repository for batch metadata persistence. */
    private final JobRepository jobRepository;

    /** Spring transaction manager for chunk-oriented transaction control. */
    private final PlatformTransactionManager transactionManager;

    /** Fixed-width file reader factory component for parsing COBOL record layouts. */
    private final FixedWidthFileReader fixedWidthFileReader;

    /** JPA repository for persisting Transaction entities to PostgreSQL. */
    private final TransactionRepository transactionRepository;

    /**
     * Service translating CBTRN01C.cbl batch utility logic for keyed lookup
     * validation: lookupXref() maps to paragraph 2000-LOOKUP-XREF, and
     * readAccount() maps to paragraph 3000-READ-ACCOUNT.
     */
    private final TransactionUtilService transactionUtilService;

    /**
     * Constructs the transaction load job configuration with all required
     * dependencies. Uses constructor injection (preferred over field injection
     * in Spring Boot 3.x) for testability and clear dependency declaration.
     *
     * @param jobRepository          Spring Batch job repository for metadata
     * @param transactionManager     transaction manager for chunk commits
     * @param fixedWidthFileReader   factory for creating fixed-width file readers
     * @param transactionRepository  JPA repository for Transaction persistence
     * @param transactionUtilService service for XREF/Account lookup validation
     */
    public TransactionLoadJobConfig(JobRepository jobRepository,
                                    PlatformTransactionManager transactionManager,
                                    FixedWidthFileReader fixedWidthFileReader,
                                    TransactionRepository transactionRepository,
                                    TransactionUtilService transactionUtilService) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.fixedWidthFileReader = fixedWidthFileReader;
        this.transactionRepository = transactionRepository;
        this.transactionUtilService = transactionUtilService;
    }

    // =========================================================================
    // Job Definition — Translates JCL TRANFILE job (CBTRN01C.cbl MAIN-PARA)
    // COBOL: MAIN-PARA (open files → loop DALYTRAN → close files)
    // =========================================================================

    /**
     * Defines the transaction load batch job.
     *
     * <p>Translates the JCL TRANFILE job into a Spring Batch {@link Job} with
     * a single step. Mirrors CBTRN01C.cbl's MAIN-PARA which opens 6 files,
     * drives the read loop, validates references, and closes all files.</p>
     *
     * <p>COBOL paragraph mapping:</p>
     * <ul>
     *   <li>{@code DISPLAY 'START OF EXECUTION OF PROGRAM CBTRN01C'}
     *       → SLF4J info log at job configuration</li>
     *   <li>{@code PERFORM 0000-DALYTRAN-OPEN THRU 0500-TRANFILE-OPEN}
     *       → Spring Batch lifecycle ItemStream.open()</li>
     *   <li>{@code PERFORM UNTIL END-OF-FILE = 'Y'}
     *       → chunk-oriented step processing via transactionLoadStep()</li>
     *   <li>{@code PERFORM 9000-DALYTRAN-CLOSE THRU 9500-TRANFILE-CLOSE}
     *       → Spring Batch lifecycle ItemStream.close()</li>
     *   <li>{@code DISPLAY 'END OF EXECUTION OF PROGRAM CBTRN01C'}
     *       → SLF4J info log after step completion</li>
     * </ul>
     *
     * @return configured Spring Batch {@link Job} for transaction seed data loading
     */
    @Bean
    public Job transactionLoadJob() {
        LOG.info("START OF EXECUTION OF PROGRAM CBTRN01C — Configuring transactionLoadJob");
        return new JobBuilder("transactionLoadJob", jobRepository)
                .start(transactionLoadStep())
                .build();
    }

    // =========================================================================
    // Step Definition — Chunk-oriented processing
    // COBOL: PERFORM UNTIL END-OF-FILE = 'Y' loop with XREF/Account lookup
    // =========================================================================

    /**
     * Defines the single step for the transaction load job.
     *
     * <p>Configures chunk-oriented processing with:</p>
     * <ul>
     *   <li><strong>Reader:</strong> {@link #transactionFileReader()} — parses
     *       350-byte fixed-width records from dailytran.txt
     *       (replaces {@code 1000-DALYTRAN-GET-NEXT})</li>
     *   <li><strong>Writer:</strong> {@link #transactionWriter()} — persists
     *       Transaction entities to PostgreSQL via JPA, then validates
     *       cross-references and accounts
     *       (replaces {@code 2000-LOOKUP-XREF} and {@code 3000-READ-ACCOUNT})</li>
     *   <li><strong>Chunk size:</strong> {@value #CHUNK_SIZE} records per
     *       transaction commit</li>
     * </ul>
     *
     * <p>The chunk-oriented model processes records in groups of
     * {@value #CHUNK_SIZE}, committing each chunk atomically. This matches
     * the COBOL batch transaction semantics from CBTRN01C.cbl.</p>
     *
     * @return configured Spring Batch {@link Step} for transaction record processing
     */
    @Bean
    public Step transactionLoadStep() {
        return new StepBuilder("transactionLoadStep", jobRepository)
                .<Transaction, Transaction>chunk(CHUNK_SIZE, transactionManager)
                .reader(transactionFileReader())
                .writer(transactionWriter())
                .build();
    }

    // =========================================================================
    // Reader Bean — Fixed-width file parser for dailytran.txt
    // COBOL: 0000-DALYTRAN-OPEN + 1000-DALYTRAN-GET-NEXT paragraphs
    // =========================================================================

    /**
     * Creates the fixed-width file reader for dailytran.txt.
     *
     * <p>Translates CBTRN01C.cbl's file I/O pattern:</p>
     * <ul>
     *   <li>{@code SELECT DALYTRAN-FILE ASSIGN TO DALYTRAN}
     *       → ClassPathResource("fixtures/dailytran.txt")</li>
     *   <li>{@code ORGANIZATION IS SEQUENTIAL, ACCESS MODE IS SEQUENTIAL}
     *       → FlatFileItemReader sequential read</li>
     *   <li>{@code READ DALYTRAN-FILE INTO DALYTRAN-RECORD}
     *       → FieldSetMapper populates Transaction entity from
     *       CVTRA05Y.cpy positions</li>
     * </ul>
     *
     * <p>Field parsing uses CVTRA05Y.cpy column positions exactly:</p>
     * <ul>
     *   <li>String fields (TRAN-ID, types, dates, identifiers): read and trimmed</li>
     *   <li>Category code (TRAN-CAT-CD PIC 9(04)): parsed from String to Integer</li>
     *   <li>Monetary field (TRAN-AMT PIC S9(09)V99): parsed via
     *       {@link FixedWidthFileReader#parseZonedDecimal} with scale=2,
     *       producing {@code BigDecimal} values with exact precision</li>
     * </ul>
     *
     * <p>The {@code @StepScope} annotation ensures a fresh reader instance
     * is created for each step execution, supporting job restartability.</p>
     *
     * @return step-scoped FlatFileItemReader that produces Transaction entities
     *         from 350-byte fixed-width records
     */
    @Bean
    @StepScope
    public FlatFileItemReader<Transaction> transactionFileReader() {
        // Retrieve column specifications matching CVTRA05Y.cpy TRAN-RECORD layout
        // 13 mapped fields (positions 1-330) from 350-byte records
        var columnSpecs = FixedWidthFileReader.getTransactionColumnSpecs();

        return fixedWidthFileReader.createReader(
                "transactionFileReader",
                new ClassPathResource(TRANSACTION_DATA_RESOURCE),
                columnSpecs,
                fieldSet -> mapFieldSetToTransaction(fieldSet)
        );
    }

    /**
     * Maps a parsed FieldSet from the fixed-width reader to a Transaction entity.
     *
     * <p>Implements the field-by-field extraction matching CVTRA05Y.cpy layout,
     * translating the COBOL {@code READ DALYTRAN-FILE INTO DALYTRAN-RECORD}
     * statement into Java entity construction. Each field is extracted by name
     * (matching the column specifications from
     * {@link FixedWidthFileReader#getTransactionColumnSpecs()}).</p>
     *
     * <p>The monetary field TRAN-AMT is parsed as zoned decimal using
     * {@link FixedWidthFileReader#parseZonedDecimal} to produce a
     * {@code BigDecimal} with scale=2, matching the COBOL PIC S9(09)V99
     * specification exactly. Float/double is never used per AAP mandate.</p>
     *
     * <p>TRAN-CAT-CD (PIC 9(04)) is parsed from the fixed-width String
     * representation to an {@code Integer}, matching the Transaction entity's
     * {@code categoryCode} field type.</p>
     *
     * @param fieldSet the parsed field set containing named fields from one
     *                 350-byte fixed-width record
     * @return a fully populated Transaction entity ready for persistence
     */
    private Transaction mapFieldSetToTransaction(FieldSet fieldSet) {
        // String field: TRAN-ID PIC X(16) — transaction identifier (primary key)
        String tranId = fieldSet.readString("tranId").trim();

        // String field: TRAN-TYPE-CD PIC X(02) — transaction type code
        String typeCode = fieldSet.readString("tranTypeCd").trim();

        // Numeric field: TRAN-CAT-CD PIC 9(04) — category code → Integer
        // COBOL PIC 9(04) is always 4 numeric digits; parse to Integer
        String catCdStr = fieldSet.readString("tranCatCd").trim();
        Integer categoryCode = catCdStr.isEmpty()
                ? Integer.valueOf(0)
                : Integer.valueOf(catCdStr);

        // String field: TRAN-SOURCE PIC X(10) — transaction source
        String source = fieldSet.readString("tranSource").trim();

        // String field: TRAN-DESC PIC X(100) — transaction description
        String description = fieldSet.readString("tranDesc").trim();

        // Monetary field: TRAN-AMT PIC S9(09)V99 — zoned decimal, scale=2
        // Uses parseZonedDecimal to handle EBCDIC-to-ASCII overpunch encoding
        // CRITICAL: BigDecimal only — never float/double for monetary fields
        var amount = FixedWidthFileReader.parseZonedDecimal(
                fieldSet.readString("tranAmt"), 2);

        // String field: TRAN-MERCHANT-ID PIC 9(09) — merchant identifier
        // Stored as String to preserve leading zeros from COBOL numeric display
        String merchantId = fieldSet.readString("merchantId").trim();

        // String field: TRAN-MERCHANT-NAME PIC X(50)
        String merchantName = fieldSet.readString("merchantName").trim();

        // String field: TRAN-MERCHANT-CITY PIC X(50)
        String merchantCity = fieldSet.readString("merchantCity").trim();

        // String field: TRAN-MERCHANT-ZIP PIC X(10)
        String merchantZip = fieldSet.readString("merchantZip").trim();

        // String field: TRAN-CARD-NUM PIC X(16) — card number
        String cardNum = fieldSet.readString("cardNum").trim();

        // Timestamp field: TRAN-ORIG-TS PIC X(26) — ISO-8601 extended format
        // Format: YYYY-MM-DD-HH.MM.SS.mmmmmm (26 chars with microseconds)
        String origTimestamp = fieldSet.readString("origTimestamp").trim();

        // Timestamp field: TRAN-PROC-TS PIC X(26) — processing timestamp
        String procTimestamp = fieldSet.readString("procTimestamp").trim();

        // Construct Transaction entity using the all-fields constructor
        // This matches the COBOL READ INTO TRAN-RECORD pattern
        return new Transaction(
                tranId, typeCode, categoryCode, source, description, amount,
                merchantId, merchantName, merchantCity, merchantZip,
                cardNum, origTimestamp, procTimestamp
        );
    }

    // =========================================================================
    // Writer Bean — Persistence + XREF/Account Validation + Debug Logging
    // COBOL: 2000-LOOKUP-XREF + 3000-READ-ACCOUNT + Z-DISPLAY-IO-STATUS
    // =========================================================================

    /**
     * Creates the writer that persists Transaction entities to PostgreSQL and
     * validates cross-references and account records.
     *
     * <p>The original CBTRN01C.cbl program iterates daily transaction records
     * and for each record: (1) DISPLAYs the record fields, (2) looks up the
     * card cross-reference (paragraph 2000-LOOKUP-XREF), and (3) reads the
     * account record (paragraph 3000-READ-ACCOUNT). In the Java migration,
     * this seed data loader persists the records AND validates references.</p>
     *
     * <h3>INVALID KEY Handling (CRITICAL)</h3>
     * <p>COBOL CBTRN01C handles {@code INVALID KEY} by logging a warning and
     * continuing to the next record — it does NOT abort. This behavior is
     * preserved exactly in Java:</p>
     * <ul>
     *   <li>If {@link TransactionUtilService#lookupXref} returns empty
     *       (XREF not found), a warning is logged and processing continues</li>
     *   <li>If {@link TransactionUtilService#readAccount} returns empty
     *       (Account not found), a warning is logged and processing continues</li>
     *   <li>If a database I/O error occurs during saveAll(), the exception is
     *       logged and re-thrown (matching Z-ABEND-PROGRAM → CALL 'CEE3ABD')</li>
     * </ul>
     *
     * <p>Debug log output reproduces the COBOL DISPLAY pattern from
     * paragraph 1000-DALYTRAN-GET-NEXT, showing key transaction fields.</p>
     *
     * @return ItemWriter that saves Transaction chunks via JPA repository,
     *         validates XREF/Account references, and provides debug-level
     *         field logging
     */
    @Bean
    public ItemWriter<Transaction> transactionWriter() {
        return chunk -> {
            // Build a properly-typed list for JPA batch persistence
            // Translates CBTRN01C.cbl's sequential VSAM WRITE pattern into
            // JPA saveAll() for efficient batch INSERT operations
            List<Transaction> transactions = new ArrayList<>(chunk.size());
            for (Transaction transaction : chunk) {
                transactions.add(transaction);
            }

            // Persist all transaction records to PostgreSQL
            // Equivalent to COBOL paragraph 0500-TRANFILE-OPEN + sequential writes
            try {
                transactionRepository.saveAll(transactions);
            } catch (RuntimeException e) {
                // Replaces Z-DISPLAY-IO-STATUS paragraph:
                // DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04
                LOG.error("ERROR WRITING TRANSACTION RECORDS — "
                        + "equivalent to Z-DISPLAY-IO-STATUS: {}", e.getMessage());
                // Replaces Z-ABEND-PROGRAM paragraph:
                // CALL 'CEE3ABD' (abnormal termination)
                throw e;
            }

            // Post-save validation: lookup XREF and Account for each transaction
            // Mirrors CBTRN01C.cbl processing loop after 1000-DALYTRAN-GET-NEXT:
            //   PERFORM 2000-LOOKUP-XREF
            //   PERFORM 3000-READ-ACCOUNT
            // INVALID KEY → log warning, set status, continue (DO NOT ABORT)
            for (Transaction transaction : transactions) {
                // Replaces COBOL DISPLAY statements in 1000-DALYTRAN-GET-NEXT
                // showing key fields of each daily transaction record
                LOG.debug("TRAN-ID: {} | TRAN-DESC: {} | TRAN-AMT: {} | TRAN-CARD-NUM: {}",
                        transaction.getTranId(),
                        transaction.getDescription(),
                        transaction.getAmount(),
                        transaction.getCardNum());

                // 2000-LOOKUP-XREF: READ XREF-FILE KEY IS FD-XREF-CARD-NUM
                // COBOL: INVALID KEY SET WS-XREF-READ-STATUS TO 4
                //        DISPLAY 'INVALID CARD NUMBER FOR XREF'
                //        GO TO 1000-DALYTRAN-GET-NEXT (skip to next record)
                var xrefResult = transactionUtilService.lookupXref(
                        transaction.getCardNum());
                if (xrefResult.isEmpty()) {
                    LOG.warn("2000-LOOKUP-XREF INVALID KEY — "
                            + "XREF not found for card number: {} (transaction: {})",
                            transaction.getCardNum(), transaction.getTranId());
                    // Skip account lookup — mirrors COBOL GO TO 1000-DALYTRAN-GET-NEXT
                    continue;
                }

                // 3000-READ-ACCOUNT: READ ACCOUNT-FILE KEY IS FD-ACCT-ID
                // COBOL: INVALID KEY SET WS-ACCT-READ-STATUS TO 4
                //        DISPLAY 'INVALID ACCOUNT NUMBER FOUND'
                //        GO TO 1000-DALYTRAN-GET-NEXT (skip to next record)
                String accountId = xrefResult.get().getAccountId();
                var accountResult = transactionUtilService.readAccount(accountId);
                if (accountResult.isEmpty()) {
                    LOG.warn("3000-READ-ACCOUNT INVALID KEY — "
                            + "Account not found for ID: {} (transaction: {})",
                            accountId, transaction.getTranId());
                }
            }

            LOG.info("Transaction load step: persisted {} transaction records",
                    transactions.size());
        };
    }
}
