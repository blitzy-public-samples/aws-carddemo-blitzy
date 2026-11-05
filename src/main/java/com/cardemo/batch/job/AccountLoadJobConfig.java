/*
 * AccountLoadJobConfig.java — Spring Batch Job Configuration for Account Seed Data Loading
 *
 * Source COBOL Program: CBACT01C.cbl
 *   Function: Read and print account data file (batch utility)
 *   Dataset: ACCTFILE-FILE (ORGANIZATION IS INDEXED, ACCESS MODE IS SEQUENTIAL,
 *            RECORD KEY IS FD-ACCT-ID, FILE STATUS IS ACCTFILE-STATUS)
 *   Record: FD-ACCTFILE-REC — FD-ACCT-ID PIC 9(11), FD-ACCT-DATA PIC X(289) = 300 bytes
 *
 * Source Copybook: CVACT01Y.cpy
 *   Record: ACCOUNT-RECORD (300 bytes, 12 mapped fields + 178-byte FILLER)
 *
 * Source Data: app/data/ASCII/acctdata.txt
 *   50 account records × 300 characters each (fixed-width, zoned decimal monetary fields)
 *
 * Migration Strategy:
 *   The COBOL program reads ACCTFILE sequentially and DISPLAYs each record's fields.
 *   In the Java migration, this becomes a Spring Batch seed data loader that parses
 *   the fixed-width acctdata.txt file and INSERTs Account entities into PostgreSQL.
 *   The COBOL DISPLAY statements are replaced by SLF4J debug-level logging.
 *
 * COBOL Paragraph-to-Java Method Mapping:
 *   MAIN (display banner, drive loop)    → accountLoadJob() + accountLoadStep()
 *   0000-ACCTFILE-OPEN                   → Spring Batch lifecycle ItemStream.open()
 *   1000-ACCTFILE-GET-NEXT               → accountFileReader() → read()
 *   1100-DISPLAY-ACCT-RECORD             → accountWriter() + SLF4J debug logging
 *   9000-ACCTFILE-CLOSE                  → Spring Batch lifecycle ItemStream.close()
 *   9910-DISPLAY-IO-STATUS               → Exception handling with logged file status
 *   9999-ABEND-PROGRAM                   → Step failure — RuntimeException propagation
 *
 * Ver: CardDemo_v1.0 — Migrated from COBOL to Java 25 + Spring Boot 3.5.x
 */
package com.cardemo.batch.job;

import com.cardemo.batch.reader.FixedWidthFileReader;
import com.cardemo.entity.Account;
import com.cardemo.repository.AccountRepository;

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
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.file.transform.FieldSet;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Spring Batch job configuration for loading account seed data from the
 * fixed-width {@code acctdata.txt} file into the PostgreSQL {@code accounts} table.
 *
 * <p>Translates the JCL ACCTFILE batch job (backed by COBOL program CBACT01C.cbl)
 * into a Spring Batch {@link Job} with a single chunk-oriented {@link Step}.
 * The original COBOL program reads the indexed account dataset sequentially
 * and displays each record's fields. The Java equivalent reads the fixed-width
 * file and persists each record as a JPA {@link Account} entity.</p>
 *
 * <h3>Processing Flow (mirrors CBACT01C.cbl)</h3>
 * <ol>
 *   <li>Log start banner (replaces COBOL
 *       {@code DISPLAY 'START OF EXECUTION OF PROGRAM CBACT01C'})</li>
 *   <li>Open reader for acctdata.txt
 *       (replaces {@code 0000-ACCTFILE-OPEN} paragraph)</li>
 *   <li>Read each 300-byte fixed-width record
 *       (replaces {@code 1000-ACCTFILE-GET-NEXT} paragraph)</li>
 *   <li>Parse fields per CVACT01Y.cpy layout and persist to database</li>
 *   <li>Log each record's fields at debug level
 *       (replaces {@code 1100-DISPLAY-ACCT-RECORD} paragraph)</li>
 *   <li>Close reader (replaces {@code 9000-ACCTFILE-CLOSE} paragraph)</li>
 *   <li>Log completion banner (replaces COBOL
 *       {@code DISPLAY 'END OF EXECUTION OF PROGRAM CBACT01C'})</li>
 * </ol>
 *
 * <h3>Data Format: acctdata.txt (CVACT01Y.cpy ACCOUNT-RECORD, RECLN 300)</h3>
 * <table>
 *   <caption>Fixed-width field layout</caption>
 *   <tr><th>Field</th><th>COBOL PIC</th><th>Positions</th><th>Java Type</th></tr>
 *   <tr><td>ACCT-ID</td><td>9(11)</td><td>1-11</td><td>String</td></tr>
 *   <tr><td>ACCT-ACTIVE-STATUS</td><td>X(01)</td><td>12</td><td>String</td></tr>
 *   <tr><td>ACCT-CURR-BAL</td><td>S9(10)V99</td><td>13-24</td><td>BigDecimal</td></tr>
 *   <tr><td>ACCT-CREDIT-LIMIT</td><td>S9(10)V99</td><td>25-36</td><td>BigDecimal</td></tr>
 *   <tr><td>ACCT-CASH-CREDIT-LIMIT</td><td>S9(10)V99</td><td>37-48</td><td>BigDecimal</td></tr>
 *   <tr><td>ACCT-OPEN-DATE</td><td>X(10)</td><td>49-58</td><td>String</td></tr>
 *   <tr><td>ACCT-EXPIRAION-DATE</td><td>X(10)</td><td>59-68</td><td>String</td></tr>
 *   <tr><td>ACCT-REISSUE-DATE</td><td>X(10)</td><td>69-78</td><td>String</td></tr>
 *   <tr><td>ACCT-CURR-CYC-CREDIT</td><td>S9(10)V99</td><td>79-90</td><td>BigDecimal</td></tr>
 *   <tr><td>ACCT-CURR-CYC-DEBIT</td><td>S9(10)V99</td><td>91-102</td><td>BigDecimal</td></tr>
 *   <tr><td>ACCT-ADDR-ZIP</td><td>X(10)</td><td>103-112</td><td>String</td></tr>
 *   <tr><td>ACCT-GROUP-ID</td><td>X(10)</td><td>113-122</td><td>String</td></tr>
 *   <tr><td>FILLER</td><td>X(178)</td><td>123-300</td><td>not mapped</td></tr>
 * </table>
 *
 * @see FixedWidthFileReader
 * @see Account
 * @see AccountRepository
 */
@Configuration
public class AccountLoadJobConfig {

    /**
     * SLF4J logger replacing COBOL DISPLAY statements from CBACT01C.cbl.
     * Provides info-level for job start/end banners, debug-level for
     * field-by-field record display (1100-DISPLAY-ACCT-RECORD), and
     * error-level for file status diagnostics (9910-DISPLAY-IO-STATUS).
     */
    private static final Logger LOG = LoggerFactory.getLogger(AccountLoadJobConfig.class);

    /**
     * Chunk size for batch processing — 10 records per transaction commit.
     * Appropriate for seed data loading of 50 account records from acctdata.txt.
     * Each chunk is committed atomically, matching COBOL batch transaction semantics.
     */
    private static final int CHUNK_SIZE = 10;

    /**
     * Classpath location of the fixed-width account seed data file.
     * Points to the test fixtures directory where acctdata.txt is stored.
     * The file contains 50 account records at 300 characters each,
     * matching the CVACT01Y.cpy ACCOUNT-RECORD layout.
     */
    private static final String ACCOUNT_DATA_RESOURCE = "fixtures/acctdata.txt";

    /** Spring Batch job repository for batch metadata persistence. */
    private final JobRepository jobRepository;

    /** Spring transaction manager for chunk-oriented transaction control. */
    private final PlatformTransactionManager transactionManager;

    /** Fixed-width file reader factory component for parsing COBOL record layouts. */
    private final FixedWidthFileReader fixedWidthFileReader;

    /** JPA repository for persisting Account entities to PostgreSQL. */
    private final AccountRepository accountRepository;

    /**
     * Constructs the account load job configuration with all required dependencies.
     * Uses constructor injection (preferred over field injection in Spring Boot 3.x)
     * for testability and clear dependency declaration.
     *
     * @param jobRepository        Spring Batch job repository for metadata
     * @param transactionManager   transaction manager for chunk commits
     * @param fixedWidthFileReader factory for creating fixed-width file readers
     * @param accountRepository    JPA repository for Account entity persistence
     */
    public AccountLoadJobConfig(JobRepository jobRepository,
                                PlatformTransactionManager transactionManager,
                                FixedWidthFileReader fixedWidthFileReader,
                                AccountRepository accountRepository) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.fixedWidthFileReader = fixedWidthFileReader;
        this.accountRepository = accountRepository;
    }

    // =========================================================================
    // Job Definition — Translates JCL ACCTFILE job
    // COBOL: PROCEDURE DIVISION main section (display banner, drive loop)
    // =========================================================================

    /**
     * Defines the account load batch job.
     *
     * <p>Translates the JCL ACCTFILE job into a Spring Batch {@link Job} with
     * a single step. Mirrors CBACT01C.cbl's PROCEDURE DIVISION main section:
     * display start banner, drive read loop, display end banner.</p>
     *
     * <p>COBOL paragraph mapping:</p>
     * <ul>
     *   <li>{@code DISPLAY 'START OF EXECUTION OF PROGRAM CBACT01C'}
     *       → SLF4J info log at job configuration</li>
     *   <li>{@code PERFORM UNTIL END-OF-FILE = 'Y'}
     *       → chunk-oriented step processing via accountLoadStep()</li>
     *   <li>{@code DISPLAY 'END OF EXECUTION OF PROGRAM CBACT01C'}
     *       → SLF4J info log after step completion</li>
     * </ul>
     *
     * @return configured Spring Batch {@link Job} for account seed data loading
     */
    @Bean
    public Job accountLoadJob() {
        LOG.info("START OF EXECUTION OF PROGRAM CBACT01C — Configuring accountLoadJob");
        return new JobBuilder("accountLoadJob", jobRepository)
                .start(accountLoadStep())
                .build();
    }

    // =========================================================================
    // Step Definition — Chunk-oriented processing
    // COBOL: PERFORM UNTIL END-OF-FILE = 'Y' loop
    // =========================================================================

    /**
     * Defines the single step for the account load job.
     *
     * <p>Configures chunk-oriented processing with:</p>
     * <ul>
     *   <li><strong>Reader:</strong> {@link #accountFileReader()} — parses 300-byte
     *       fixed-width records from acctdata.txt
     *       (replaces {@code 1000-ACCTFILE-GET-NEXT})</li>
     *   <li><strong>Writer:</strong> {@link #accountWriter()} — persists Account
     *       entities to PostgreSQL via JPA
     *       (extends COBOL's sequential DISPLAY with persistent INSERT)</li>
     *   <li><strong>Chunk size:</strong> 10 records per transaction commit</li>
     * </ul>
     *
     * <p>The chunk-oriented model processes records in groups of {@value #CHUNK_SIZE},
     * committing each chunk atomically. This matches the COBOL batch transaction
     * semantics from CBACT01C.cbl's sequential file processing.</p>
     *
     * @return configured Spring Batch {@link Step} for account record processing
     */
    @Bean
    public Step accountLoadStep() {
        return new StepBuilder("accountLoadStep", jobRepository)
                .<Account, Account>chunk(CHUNK_SIZE, transactionManager)
                .reader(accountFileReader())
                .writer(accountWriter())
                .build();
    }

    // =========================================================================
    // Reader Bean — Fixed-width file parser for acctdata.txt
    // COBOL: 0000-ACCTFILE-OPEN + 1000-ACCTFILE-GET-NEXT paragraphs
    // =========================================================================

    /**
     * Creates the fixed-width file reader for acctdata.txt.
     *
     * <p>Translates CBACT01C.cbl's file I/O pattern:</p>
     * <ul>
     *   <li>{@code SELECT ACCTFILE-FILE ASSIGN TO ACCTFILE}
     *       → ClassPathResource("fixtures/acctdata.txt")</li>
     *   <li>{@code ORGANIZATION IS INDEXED, ACCESS MODE IS SEQUENTIAL}
     *       → FlatFileItemReader sequential read</li>
     *   <li>{@code READ ACCTFILE-FILE INTO ACCOUNT-RECORD}
     *       → FieldSetMapper populates Account entity from CVACT01Y.cpy positions</li>
     * </ul>
     *
     * <p>Field parsing uses CVACT01Y.cpy column positions exactly:</p>
     * <ul>
     *   <li>String fields (ACCT-ID, dates, ZIP, group): read and trimmed</li>
     *   <li>Monetary fields (PIC S9(10)V99): parsed via
     *       {@link FixedWidthFileReader#parseZonedDecimal} with scale=2,
     *       producing {@code BigDecimal} values with exact precision</li>
     * </ul>
     *
     * <p>The {@code @StepScope} annotation ensures a fresh reader instance
     * is created for each step execution, supporting job restartability.</p>
     *
     * @return step-scoped FlatFileItemReader that produces Account entities
     *         from 300-byte fixed-width records
     */
    @Bean
    @StepScope
    public FlatFileItemReader<Account> accountFileReader() {
        // Retrieve column specifications matching CVACT01Y.cpy ACCOUNT-RECORD layout
        // 12 mapped fields (positions 1-122) from 300-byte records
        var columnSpecs = FixedWidthFileReader.getAccountColumnSpecs();

        return fixedWidthFileReader.createReader(
                "accountFileReader",
                new ClassPathResource(ACCOUNT_DATA_RESOURCE),
                columnSpecs,
                fieldSet -> mapFieldSetToAccount(fieldSet)
        );
    }

    /**
     * Maps a parsed FieldSet from the fixed-width reader to an Account entity.
     *
     * <p>Implements the field-by-field extraction matching CVACT01Y.cpy layout,
     * translating the COBOL {@code READ ACCTFILE-FILE INTO ACCOUNT-RECORD}
     * statement into Java entity construction. Each field is extracted by name
     * (matching the column specifications from
     * {@link FixedWidthFileReader#getAccountColumnSpecs()}).</p>
     *
     * <p>All five monetary fields are parsed as zoned decimal using
     * {@link FixedWidthFileReader#parseZonedDecimal} to produce {@code BigDecimal}
     * values with scale=2, matching the COBOL PIC S9(10)V99 specification exactly.
     * Float/double is never used for monetary fields per AAP mandate.</p>
     *
     * @param fieldSet the parsed field set containing named fields from one
     *                 300-byte fixed-width record
     * @return a fully populated Account entity ready for persistence
     */
    private Account mapFieldSetToAccount(FieldSet fieldSet) {
        // String fields: ACCT-ID PIC 9(11) — 11-char numeric display, trimmed
        String acctId = fieldSet.readString("acctId").trim();

        // String field: ACCT-ACTIVE-STATUS PIC X(01) — 'Y'/'N' flag
        String activeStatus = fieldSet.readString("activeStatus").trim();

        // Monetary field: ACCT-CURR-BAL PIC S9(10)V99 — zoned decimal, scale=2
        // Uses parseZonedDecimal to handle EBCDIC-to-ASCII overpunch encoding
        var currBal = FixedWidthFileReader.parseZonedDecimal(
                fieldSet.readString("currBal"), 2);

        // Monetary field: ACCT-CREDIT-LIMIT PIC S9(10)V99
        var creditLimit = FixedWidthFileReader.parseZonedDecimal(
                fieldSet.readString("creditLimit"), 2);

        // Monetary field: ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99
        var cashCreditLimit = FixedWidthFileReader.parseZonedDecimal(
                fieldSet.readString("cashCreditLimit"), 2);

        // Date field: ACCT-OPEN-DATE PIC X(10) — YYYY-MM-DD format
        String openDate = fieldSet.readString("openDate").trim();

        // Date field: ACCT-EXPIRAION-DATE PIC X(10) — COBOL typo preserved in data
        String expirationDate = fieldSet.readString("expirationDate").trim();

        // Date field: ACCT-REISSUE-DATE PIC X(10)
        String reissueDate = fieldSet.readString("reissueDate").trim();

        // Monetary field: ACCT-CURR-CYC-CREDIT PIC S9(10)V99
        var currCycCredit = FixedWidthFileReader.parseZonedDecimal(
                fieldSet.readString("currCycCredit"), 2);

        // Monetary field: ACCT-CURR-CYC-DEBIT PIC S9(10)V99
        var currCycDebit = FixedWidthFileReader.parseZonedDecimal(
                fieldSet.readString("currCycDebit"), 2);

        // String field: ACCT-ADDR-ZIP PIC X(10)
        String addrZip = fieldSet.readString("addrZip").trim();

        // String field: ACCT-GROUP-ID PIC X(10)
        String groupId = fieldSet.readString("groupId").trim();

        // Construct Account entity using the public parameterized constructor
        // This matches the COBOL READ INTO ACCOUNT-RECORD pattern
        return new Account(
                acctId, activeStatus, currBal, creditLimit, cashCreditLimit,
                openDate, expirationDate, reissueDate,
                currCycCredit, currCycDebit, addrZip, groupId
        );
    }

    // =========================================================================
    // Writer Bean — Persistence + Debug Logging
    // COBOL: 1100-DISPLAY-ACCT-RECORD paragraph + VSAM WRITE semantics
    // =========================================================================

    /**
     * Creates the writer that persists Account entities to PostgreSQL.
     *
     * <p>The original CBACT01C.cbl program (1100-DISPLAY-ACCT-RECORD paragraph)
     * only DISPLAYs record fields to the console. The Java equivalent persists
     * records to the database AND logs the field values at debug level,
     * reproducing the COBOL DISPLAY output pattern faithfully.</p>
     *
     * <p>Debug log output mimics the exact COBOL DISPLAY statements:</p>
     * <pre>
     * ACCT-ID                 :00000000001
     * ACCT-ACTIVE-STATUS      :Y
     * ACCT-CURR-BAL           :194.00
     * ACCT-CREDIT-LIMIT       :2020.00
     * ACCT-CASH-CREDIT-LIMIT  :1020.00
     * ACCT-OPEN-DATE          :2014-11-20
     * ACCT-EXPIRAION-DATE     :2025-05-20
     * ACCT-REISSUE-DATE       :2025-05-20
     * ACCT-CURR-CYC-CREDIT    :0.00
     * ACCT-CURR-CYC-DEBIT     :0.00
     * ACCT-GROUP-ID           :A000000000
     * -------------------------------------------------
     * </pre>
     *
     * <p>Error handling maps to COBOL paragraphs:</p>
     * <ul>
     *   <li>{@code 9910-DISPLAY-IO-STATUS} → LOG.error() with exception message</li>
     *   <li>{@code 9999-ABEND-PROGRAM} → re-throw RuntimeException (step failure)</li>
     * </ul>
     *
     * @return ItemWriter that saves Account chunks via JPA repository with
     *         debug-level field logging
     */
    @Bean
    public ItemWriter<Account> accountWriter() {
        return chunk -> {
            // Build a properly-typed list for JPA batch persistence
            // Translates CBACT01C.cbl's sequential VSAM WRITE pattern into
            // JPA saveAll() for efficient batch INSERT operations
            List<Account> accounts = new ArrayList<>(chunk.size());
            for (Account account : chunk) {
                accounts.add(account);
            }

            try {
                accountRepository.saveAll(accounts);
            } catch (RuntimeException e) {
                // Replaces 9910-DISPLAY-IO-STATUS paragraph:
                // DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04
                LOG.error("ERROR WRITING ACCOUNT RECORDS — "
                        + "equivalent to 9910-DISPLAY-IO-STATUS: {}", e.getMessage());
                // Replaces 9999-ABEND-PROGRAM paragraph:
                // CALL 'CEE3ABD' (abnormal termination)
                throw e;
            }

            // Replaces 1100-DISPLAY-ACCT-RECORD paragraph from CBACT01C.cbl
            // Each COBOL DISPLAY statement becomes a debug-level log entry
            // matching the exact field labels from the source program
            for (Account account : accounts) {
                LOG.debug("ACCT-ID                 :{}", account.getAcctId());
                LOG.debug("ACCT-ACTIVE-STATUS      :{}", account.getActiveStatus());
                LOG.debug("ACCT-CURR-BAL           :{}", account.getCurrBal());
                LOG.debug("ACCT-CREDIT-LIMIT       :{}", account.getCreditLimit());
                LOG.debug("ACCT-CASH-CREDIT-LIMIT  :{}", account.getCashCreditLimit());
                LOG.debug("ACCT-OPEN-DATE          :{}", account.getOpenDate());
                LOG.debug("ACCT-EXPIRAION-DATE     :{}", account.getExpirationDate());
                LOG.debug("ACCT-REISSUE-DATE       :{}", account.getReissueDate());
                LOG.debug("ACCT-CURR-CYC-CREDIT    :{}", account.getCurrCycCredit());
                LOG.debug("ACCT-CURR-CYC-DEBIT     :{}", account.getCurrCycDebit());
                LOG.debug("ACCT-GROUP-ID           :{}", account.getGroupId());
                LOG.debug("-------------------------------------------------");
            }

            LOG.info("Account load step: persisted {} account records",
                    accounts.size());
        };
    }
}
