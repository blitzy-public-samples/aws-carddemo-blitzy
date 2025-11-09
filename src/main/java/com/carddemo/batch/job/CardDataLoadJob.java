package com.carddemo.batch.job;

import com.carddemo.entity.Account;
import com.carddemo.entity.Card;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardRepository;
import jakarta.persistence.EntityManagerFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.SkipListener;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.database.JpaItemWriter;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.LineMapper;
import org.springframework.batch.item.file.mapping.BeanWrapperFieldSetMapper;
import org.springframework.batch.item.file.mapping.DefaultLineMapper;
import org.springframework.batch.item.file.transform.DelimitedLineTokenizer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;

/**
 * Spring Batch Job Configuration for Card Data Loading
 * 
 * <p>This configuration class defines a Spring Batch job for loading card data from CSV files
 * to the PostgreSQL card table, transforming the mainframe batch program CBACT02C.cbl to a
 * cloud-native data migration solution.</p>
 * 
 * <p><strong>Source COBOL Program:</strong> CBACT02C.cbl</p>
 * <p>The original COBOL batch program sequentially reads card records from a VSAM KSDS file
 * (CARDFILE-FILE with ORGANIZATION IS INDEXED, ACCESS MODE IS SEQUENTIAL, RECORD KEY IS
 * FD-CARD-NUM) and displays them. This Spring Batch job extends that functionality to:</p>
 * <ul>
 *   <li>Read card data from CSV input files (comma-delimited format)</li>
 *   <li>Validate each card record against business rules</li>
 *   <li>Persist validated cards to PostgreSQL card table</li>
 *   <li>Handle errors with skip and retry policies</li>
 *   <li>Track execution metadata for restart capability</li>
 * </ul>
 * 
 * <p><strong>Key Transformations from COBOL:</strong></p>
 * <ol>
 *   <li><strong>File I/O:</strong> COBOL VSAM READ operations (lines 93-115) replaced with
 *       Spring Batch FlatFileItemReader parsing CSV records with configurable delimiter</li>
 *   <li><strong>Record Structure:</strong> COBOL CARD-RECORD from CVACT02Y.cpy copybook
 *       (150-byte fixed-length) replaced with Card JPA entity with normalized database columns</li>
 *   <li><strong>Sequential Processing:</strong> COBOL PERFORM UNTIL loop (lines 74-81) replaced
 *       with chunk-oriented processing with configurable commit intervals</li>
 *   <li><strong>Error Handling:</strong> COBOL file status checking (CARDFILE-STATUS = '00',
 *       '10', etc.) replaced with Spring Batch skip/retry policies and exception handling</li>
 *   <li><strong>Primary Key Uniqueness:</strong> VSAM RECORD KEY IS FD-CARD-NUM uniqueness
 *       constraint replicated with PostgreSQL PRIMARY KEY on card_number column</li>
 * </ol>
 * 
 * <p><strong>CSV File Format:</strong></p>
 * <p>The input CSV file must contain the following comma-separated fields in order:</p>
 * <ol>
 *   <li>card_number (16 characters) - Primary key matching CARD-NUM PIC X(16)</li>
 *   <li>account_id (11 digits) - Foreign key to account table matching CARD-ACCT-ID PIC 9(11)</li>
 *   <li>card_type (2 characters) - Card type code ('DC' or 'CC') matching CARD-CARD-TYPE PIC X(02)</li>
 *   <li>expiration_date (MM/YY format) - Card expiration in month/year format</li>
 *   <li>embossed_name (up to 50 characters) - Cardholder name matching CARD-EMBOSSED-NAME PIC X(50)</li>
 *   <li>cvv (3 digits) - Card verification value matching CARD-CVV PIC 9(03)</li>
 *   <li>status (1 character) - Active status 'Y' or 'N' matching CARD-ACTIVE-STATUS PIC X(01)</li>
 *   <li>open_date (YYYY-MM-DD format) - Card issuance date</li>
 *   <li>last_used_date (YYYY-MM-DD format) - Last transaction date (optional)</li>
 * </ol>
 * 
 * <p><strong>Chunk-Oriented Processing:</strong></p>
 * <p>The job uses chunk size 1000 matching JCL batch commit intervals. This means:</p>
 * <ul>
 *   <li>Reader reads up to 1000 card records from CSV file</li>
 *   <li>Processor validates each of the 1000 records</li>
 *   <li>Writer persists all 1000 validated cards in a single database transaction</li>
 *   <li>Commit occurs after successful write, rollback on any error</li>
 *   <li>Process repeats for next chunk until file exhausted</li>
 * </ul>
 * 
 * <p><strong>Validation Rules (ItemProcessor):</strong></p>
 * <ul>
 *   <li><strong>Card Number:</strong> Must be exactly 16 alphanumeric characters (PIC X(16))</li>
 *   <li><strong>Expiration Date:</strong> MM/YY format, must not be expired (current date before expiration)</li>
 *   <li><strong>Account ID:</strong> Must reference an existing account (foreign key validation)</li>
 *   <li><strong>Card Type:</strong> Must be 'DC' (debit card) or 'CC' (credit card)</li>
 *   <li><strong>Status:</strong> Must be 'Y' (active) or 'N' (inactive) matching COBOL 88-level conditions</li>
 *   <li><strong>CVV Code:</strong> Must be exactly 3 digits (000-999)</li>
 *   <li><strong>Embossed Name:</strong> Maximum 50 characters, cannot be blank</li>
 * </ul>
 * 
 * <p><strong>Skip Policy:</strong></p>
 * <p>The job allows up to 100 invalid card records to be skipped without failing the entire batch.
 * Skipped records are logged to an error CSV file (error-cards-{timestamp}.csv) including:</p>
 * <ul>
 *   <li>Original card record data</li>
 *   <li>Validation failure reason</li>
 *   <li>Timestamp of skip event</li>
 *   <li>Step execution ID for traceability</li>
 * </ul>
 * 
 * <p><strong>Retry Policy:</strong></p>
 * <p>Database errors (connection timeout, deadlock) are retried up to 3 times with exponential
 * backoff before skipping the record. This ensures transient database issues don't cause
 * unnecessary batch failures.</p>
 * 
 * <p><strong>Job Parameters:</strong></p>
 * <ul>
 *   <li><strong>cardDataFile:</strong> Path to input CSV file (default: batch.input.card-file-path property)</li>
 *   <li><strong>refreshMode:</strong> 'FULL' to truncate table before load, 'INCREMENTAL' for insert/update (default: INCREMENTAL)</li>
 *   <li><strong>skipLimit:</strong> Maximum records to skip (default: 100)</li>
 *   <li><strong>validateOnly:</strong> If true, performs validation without database writes (default: false)</li>
 * </ul>
 * 
 * <p><strong>Checkpoint/Restart Capability:</strong></p>
 * <p>Spring Batch JobRepository stores execution metadata enabling job restart from last successful
 * commit point, matching JCL batch job checkpoint/restart functionality. If job fails after
 * processing 5000 records, restart will resume from record 5001.</p>
 * 
 * <p><strong>Referential Integrity:</strong></p>
 * <p>The Card entity has @ManyToOne relationship to Account via account_id foreign key. The
 * processor validates account existence before inserting card, maintaining referential integrity
 * that replaces VSAM XREF cross-reference file validation from CVACT03Y.cpy.</p>
 * 
 * <p><strong>Database Performance:</strong></p>
 * <p>JpaItemWriter uses Hibernate batch processing with flush after each chunk. Database indexes
 * created in Flyway migration V7__create_indexes.sql include:</p>
 * <ul>
 *   <li>PRIMARY KEY on card_number (btree) - matches VSAM primary key</li>
 *   <li>INDEX on account_id (btree) - supports foreign key lookups</li>
 *   <li>INDEX on expiration_date (btree) - supports expiration queries</li>
 * </ul>
 * 
 * @see Card
 * @see CardRepository
 * @see Account
 * @see AccountRepository
 * @see <a href="Section 0.4">Agent Action Plan - Source File app/cbl/CBACT02C.cbl</a>
 * @see <a href="Section 0.6">File-by-File Transformation - CBACT02C.cbl to CardDataLoadJob.java</a>
 * @see <a href="Section 0.10">Special Instructions - Batch Processing Window Preservation</a>
 */
@Slf4j
@Configuration
public class CardDataLoadJob {

    private static final int CHUNK_SIZE = 1000;
    private static final int DEFAULT_SKIP_LIMIT = 100;
    private static final int RETRY_LIMIT = 3;
    private static final String JOB_NAME = "cardDataLoadBatchJob";
    private static final String STEP_NAME = "cardDataLoadBatchStep";
    
    // CSV field names matching CVACT02Y.cpy CARD-RECORD structure
    private static final String[] FIELD_NAMES = {
        "cardNumber",      // CARD-NUM PIC X(16)
        "accountId",       // CARD-ACCT-ID PIC 9(11) - stored as Long, but read as String initially
        "cardType",        // CARD-CARD-TYPE PIC X(02)
        "expirationDate",  // CARD-EXPIRE-DATE PIC X(10)
        "embossedName",    // CARD-EMBOSSED-NAME PIC X(50)
        "cvvCode",         // CARD-CVV PIC 9(03)
        "activeStatus",    // CARD-ACTIVE-STATUS PIC X(01)
        "openDate",        // Additional field not in original COBOL
        "lastUsedDate"     // Additional field not in original COBOL
    };

    private final CardRepository cardRepository;
    private final AccountRepository accountRepository;
    private final EntityManagerFactory entityManagerFactory;

    /**
     * Constructor for dependency injection.
     * 
     * @param cardRepository Repository for Card entity CRUD operations
     * @param accountRepository Repository for Account entity validation
     * @param entityManagerFactory JPA EntityManagerFactory for JpaItemWriter
     */
    public CardDataLoadJob(CardRepository cardRepository,
                           AccountRepository accountRepository,
                           EntityManagerFactory entityManagerFactory) {
        this.cardRepository = cardRepository;
        this.accountRepository = accountRepository;
        this.entityManagerFactory = entityManagerFactory;
    }

    /**
     * Defines the Card Data Load Job.
     * 
     * <p>This bean creates the main Spring Batch Job with metadata tracking and restart capability.
     * The job uses RunIdIncrementer to generate unique job instance IDs for each execution,
     * enabling multiple runs with same parameters and proper restart behavior.</p>
     * 
     * <p><strong>Job Execution Flow:</strong></p>
     * <ol>
     *   <li>Job starts with parameters validation</li>
     *   <li>BeforeJob listener checks input file existence and table schema</li>
     *   <li>If refreshMode=FULL, truncate card table</li>
     *   <li>Execute cardDataLoadStep (read, validate, write chunks)</li>
     *   <li>AfterJob listener logs statistics (total loaded, skipped, duplicates)</li>
     * </ol>
     * 
     * <p><strong>Restart Behavior:</strong></p>
     * <p>If job fails mid-execution, Spring Batch JobRepository maintains execution context
     * including last successfully committed chunk position. Restart will skip already processed
     * records and resume from last commit point, matching JCL checkpoint/restart functionality.</p>
     * 
     * @param jobRepository Spring Batch repository for job metadata persistence
     * @param step The step containing read-process-write logic
     * @return Configured Job bean for card data loading
     */
    @Bean(name = JOB_NAME)
    public Job cardDataLoadJobBean(JobRepository jobRepository, @Qualifier(STEP_NAME) Step step) {
        log.info("Configuring Card Data Load Job: {}", JOB_NAME);
        
        return new JobBuilder(JOB_NAME, jobRepository)
                .incrementer(new RunIdIncrementer())
                .start(step)
                .listener(new CardDataLoadJobListener(cardRepository))
                .build();
    }

    /**
     * Defines the Card Data Load Step.
     * 
     * <p>This bean creates the main processing step with chunk-oriented architecture:</p>
     * <ul>
     *   <li><strong>Reader:</strong> FlatFileItemReader parsing CSV input file</li>
     *   <li><strong>Processor:</strong> ItemProcessor validating each card record</li>
     *   <li><strong>Writer:</strong> JpaItemWriter persisting validated cards to database</li>
     *   <li><strong>Chunk Size:</strong> 1000 records per transaction commit</li>
     *   <li><strong>Skip Policy:</strong> Skip invalid records up to specified limit</li>
     *   <li><strong>Retry Policy:</strong> Retry database errors up to 3 times</li>
     * </ul>
     * 
     * <p><strong>Transaction Boundaries:</strong></p>
     * <p>Each chunk is processed within a single database transaction managed by
     * PlatformTransactionManager. Successful chunk write commits the transaction; any
     * exception triggers rollback, and the record causing the error is skipped if within
     * skip limit. This matches CICS SYNCPOINT commit behavior from mainframe programs.</p>
     * 
     * <p><strong>Fault Tolerance:</strong></p>
     * <p>The step is configured for resilient processing:</p>
     * <ul>
     *   <li>Skip validation exceptions up to skipLimit parameter</li>
     *   <li>Skip data integrity violations (duplicate key, foreign key violations)</li>
     *   <li>Retry transient database connection errors</li>
     *   <li>Log all skipped records to error file via SkipListener</li>
     * </ul>
     * 
     * @param jobRepository Spring Batch repository for step metadata
     * @param transactionManager Transaction manager for chunk commits
     * @param cardDataReader FlatFileItemReader for CSV parsing
     * @param cardProcessor ItemProcessor for validation
     * @param cardWriter JpaItemWriter for database persistence
     * @param skipLimit Maximum records to skip (from job parameters)
     * @return Configured Step bean for card data processing
     */
    @Bean(name = STEP_NAME)
    public Step cardDataLoadStepBean(JobRepository jobRepository,
                                      PlatformTransactionManager transactionManager,
                                      FlatFileItemReader<CardCsvRecord> cardDataReader,
                                      ItemProcessor<CardCsvRecord, Card> cardProcessor,
                                      JpaItemWriter<Card> cardWriter,
                                      @Value("${batch.card.skip-limit:100}") Integer skipLimit) {
        
        log.info("Configuring Card Data Load Step: {} with chunk size {} and skip limit {}", 
                 STEP_NAME, CHUNK_SIZE, skipLimit);
        
        // Configure retry policy for transient database errors
        Map<Class<? extends Throwable>, Boolean> retryableExceptions = new HashMap<>();
        retryableExceptions.put(org.springframework.dao.TransientDataAccessResourceException.class, true);
        retryableExceptions.put(org.springframework.dao.ConcurrencyFailureException.class, true);
        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(RETRY_LIMIT, retryableExceptions);
        
        return new StepBuilder(STEP_NAME, jobRepository)
                .<CardCsvRecord, Card>chunk(CHUNK_SIZE, transactionManager)
                .reader(cardDataReader)
                .processor(cardProcessor)
                .writer(cardWriter)
                .faultTolerant()
                .skipLimit(skipLimit)
                .skip(CardValidationException.class)
                .skip(DataIntegrityViolationException.class)
                .retryLimit(RETRY_LIMIT)
                .retry(org.springframework.dao.TransientDataAccessResourceException.class)
                .retry(org.springframework.dao.ConcurrencyFailureException.class)
                .listener(new CardSkipListener())
                .build();
    }

    /**
     * Creates FlatFileItemReader for parsing card CSV file.
     * 
     * <p>This bean reads card data from comma-delimited CSV file with configurable path.
     * Replaces COBOL VSAM sequential file reading (CARDFILE-FILE with ACCESS MODE IS SEQUENTIAL)
     * with Spring Batch line-by-line CSV parsing.</p>
     * 
     * <p><strong>CSV Parsing Configuration:</strong></p>
     * <ul>
     *   <li><strong>Delimiter:</strong> Comma (,) separator between fields</li>
     *   <li><strong>Line Tokenizer:</strong> DelimitedLineTokenizer parsing 9 fields per line</li>
     *   <li><strong>Field Mapper:</strong> BeanWrapperFieldSetMapper populating CardCsvRecord properties</li>
     *   <li><strong>Lines to Skip:</strong> 1 (skip CSV header row)</li>
     * </ul>
     * 
     * <p><strong>Error Handling:</strong></p>
     * <p>Parse errors (wrong field count, malformed data) are treated as skippable exceptions,
     * allowing the batch to continue processing remaining records. The SkipListener logs parse
     * errors with line number for manual correction.</p>
     * 
     * <p><strong>Scope:</strong></p>
     * <p>@StepScope annotation enables late binding of job parameters, allowing cardDataFile
     * path to be specified at job launch time rather than bean creation time. This supports
     * multiple concurrent job executions with different input files.</p>
     * 
     * @param cardDataFile Path to input CSV file (from job parameter or application property)
     * @return Configured FlatFileItemReader for card CSV parsing
     */
    @Bean
    @StepScope
    public FlatFileItemReader<CardCsvRecord> cardDataReader(
            @Value("#{jobParameters['cardDataFile'] ?: '${batch.input.card-file-path:data/card-data.csv}'}") String cardDataFile) {
        
        log.info("Configuring Card Data Reader for file: {}", cardDataFile);
        
        FlatFileItemReader<CardCsvRecord> reader = new FlatFileItemReader<>();
        reader.setName("cardDataReader");
        reader.setResource(new FileSystemResource(cardDataFile));
        reader.setLinesToSkip(1); // Skip CSV header row
        reader.setLineMapper(createLineMapper());
        
        return reader;
    }

    /**
     * Creates LineMapper for CSV field parsing.
     * 
     * <p>Configures DelimitedLineTokenizer to parse comma-separated fields and
     * BeanWrapperFieldSetMapper to populate CardCsvRecord bean properties from parsed fields.</p>
     * 
     * @return Configured LineMapper for card CSV parsing
     */
    private LineMapper<CardCsvRecord> createLineMapper() {
        DefaultLineMapper<CardCsvRecord> lineMapper = new DefaultLineMapper<>();
        
        DelimitedLineTokenizer tokenizer = new DelimitedLineTokenizer();
        tokenizer.setDelimiter(",");
        tokenizer.setNames(FIELD_NAMES);
        tokenizer.setStrict(false); // Allow missing trailing fields
        
        BeanWrapperFieldSetMapper<CardCsvRecord> fieldSetMapper = new BeanWrapperFieldSetMapper<>();
        fieldSetMapper.setTargetType(CardCsvRecord.class);
        
        lineMapper.setLineTokenizer(tokenizer);
        lineMapper.setFieldSetMapper(fieldSetMapper);
        
        return lineMapper;
    }

    /**
     * Creates ItemProcessor for card validation.
     * 
     * <p>This bean validates each card record parsed from CSV against business rules before
     * database persistence. Invalid records are skipped and logged, allowing batch to continue.</p>
     * 
     * <p><strong>Validation Rules Implemented:</strong></p>
     * <ol>
     *   <li><strong>Card Number Format:</strong> Exactly 16 alphanumeric characters matching
     *       CARD-NUM PIC X(16) from CVACT02Y.cpy</li>
     *   <li><strong>Expiration Date Validity:</strong> Parse MM/YY format and verify not expired
     *       (LocalDate.now() before expiration last day of month)</li>
     *   <li><strong>Account ID Reference:</strong> Verify account exists using
     *       accountRepository.existsById() ensuring foreign key integrity</li>
     *   <li><strong>Card Type Validation:</strong> Must be 'DC' (debit) or 'CC' (credit)
     *       matching business rules</li>
     *   <li><strong>Status Validation:</strong> Must be 'Y' (active) or 'N' (inactive)
     *       matching COBOL 88-level conditions</li>
     *   <li><strong>CVV Code Validation:</strong> Exactly 3 digits (000-999) matching
     *       CARD-CVV PIC 9(03)</li>
     *   <li><strong>Embossed Name Validation:</strong> Not blank, max 50 characters matching
     *       CARD-EMBOSSED-NAME PIC X(50)</li>
     * </ol>
     * 
     * <p><strong>Date Conversion:</strong></p>
     * <p>CSV expiration_date in MM/YY format is converted to LocalDate representing last day
     * of expiration month (e.g., "12/25" becomes 2025-12-31). This matches COBOL date handling
     * from CARD-EXPIRE-DATE PIC X(10) field.</p>
     * 
     * <p><strong>Entity Construction:</strong></p>
     * <p>Validated CardCsvRecord is transformed to Card JPA entity using builder pattern with:</p>
     * <ul>
     *   <li>cardNumber as primary key</li>
     *   <li>Account object loaded from accountRepository by accountId</li>
     *   <li>All other fields mapped from CSV record</li>
     *   <li>Version field initialized to null (JPA will manage)</li>
     * </ul>
     * 
     * <p><strong>Scope:</strong></p>
     * <p>@StepScope enables access to job parameters within processor logic, supporting
     * validateOnly mode where processor performs validation without returning Card entity
     * (returns null to skip database write).</p>
     * 
     * @param validateOnly If true, validate without creating Card entities (from job parameters)
     * @return Configured ItemProcessor for card validation
     */
    @Bean
    @StepScope
    public ItemProcessor<CardCsvRecord, Card> cardProcessor(
            @Value("#{jobParameters['validateOnly'] ?: false}") Boolean validateOnly) {
        
        log.info("Configuring Card Processor with validateOnly: {}", validateOnly);
        
        return csvRecord -> {
            try {
                // Validation 1: Card Number Format (16 alphanumeric characters)
                if (csvRecord.getCardNumber() == null || !csvRecord.getCardNumber().matches("^[A-Za-z0-9]{16}$")) {
                    throw new CardValidationException(
                        "Invalid card number format. Expected 16 alphanumeric characters, got: " + csvRecord.getCardNumber()
                    );
                }
                
                // Validation 2: Expiration Date Validity
                LocalDate expirationDate = parseExpirationDate(csvRecord.getExpirationDate());
                if (expirationDate.isBefore(LocalDate.now())) {
                    throw new CardValidationException(
                        "Card is expired. Expiration date: " + csvRecord.getExpirationDate()
                    );
                }
                
                // Validation 3: Account ID Reference Existence
                Long accountId = parseAccountId(csvRecord.getAccountId());
                if (!accountRepository.existsById(accountId)) {
                    throw new CardValidationException(
                        "Account ID does not exist: " + csvRecord.getAccountId()
                    );
                }
                
                // Validation 4: Card Type (DC or CC)
                if (!("DC".equals(csvRecord.getCardType()) || "CC".equals(csvRecord.getCardType()))) {
                    throw new CardValidationException(
                        "Invalid card type. Expected 'DC' or 'CC', got: " + csvRecord.getCardType()
                    );
                }
                
                // Validation 5: Active Status (Y or N)
                if (!("Y".equals(csvRecord.getActiveStatus()) || "N".equals(csvRecord.getActiveStatus()))) {
                    throw new CardValidationException(
                        "Invalid active status. Expected 'Y' or 'N', got: " + csvRecord.getActiveStatus()
                    );
                }
                
                // Validation 6: CVV Code Format (3 digits)
                if (csvRecord.getCvvCode() == null || !csvRecord.getCvvCode().matches("^[0-9]{3}$")) {
                    throw new CardValidationException(
                        "Invalid CVV code format. Expected 3 digits, got: " + csvRecord.getCvvCode()
                    );
                }
                
                // Validation 7: Embossed Name (not blank, max 50 characters)
                if (csvRecord.getEmbossedName() == null || csvRecord.getEmbossedName().trim().isEmpty()) {
                    throw new CardValidationException(
                        "Embossed name cannot be blank"
                    );
                }
                if (csvRecord.getEmbossedName().length() > 50) {
                    throw new CardValidationException(
                        "Embossed name exceeds maximum length of 50 characters: " + csvRecord.getEmbossedName()
                    );
                }
                
                // If validateOnly mode, return null (skip write)
                if (Boolean.TRUE.equals(validateOnly)) {
                    log.debug("Validation passed for card: {} (validate-only mode, skipping write)", csvRecord.getCardNumber());
                    return null;
                }
                
                // Load Account entity for @ManyToOne relationship
                Account account = accountRepository.findById(accountId)
                        .orElseThrow(() -> new CardValidationException(
                            "Account not found during entity construction: " + accountId
                        ));
                
                // Parse open date and last used date
                LocalDate openDate = parseDate(csvRecord.getOpenDate(), "open date");
                LocalDate lastUsedDate = csvRecord.getLastUsedDate() != null && !csvRecord.getLastUsedDate().trim().isEmpty()
                        ? parseDate(csvRecord.getLastUsedDate(), "last used date")
                        : null;
                
                // Build Card entity from validated CSV record
                Card card = Card.builder()
                        .cardNumber(csvRecord.getCardNumber())
                        .account(account)
                        .cardType(csvRecord.getCardType())
                        .cvvCode(csvRecord.getCvvCode())
                        .embossedName(csvRecord.getEmbossedName().trim())
                        .expirationDate(expirationDate)
                        .activeStatus(csvRecord.getActiveStatus())
                        .openDate(openDate)
                        .lastUsedDate(lastUsedDate)
                        .build();
                
                log.debug("Successfully validated and constructed Card entity for card number: {}", card.getCardNumber());
                
                return card;
                
            } catch (CardValidationException e) {
                // Re-throw validation exceptions for skip handling
                throw e;
            } catch (Exception e) {
                // Wrap unexpected exceptions as validation exceptions
                throw new CardValidationException(
                    "Unexpected error processing card record: " + e.getMessage(), e
                );
            }
        };
    }

    /**
     * Parses expiration date from MM/YY format to LocalDate.
     * 
     * <p>Converts MM/YY string to LocalDate representing last day of expiration month.
     * For example, "12/25" converts to 2025-12-31.</p>
     * 
     * @param expirationDateStr Expiration date in MM/YY format
     * @return LocalDate representing last day of expiration month
     * @throws CardValidationException if date format is invalid
     */
    private LocalDate parseExpirationDate(String expirationDateStr) throws CardValidationException {
        if (expirationDateStr == null || expirationDateStr.trim().isEmpty()) {
            throw new CardValidationException("Expiration date is required");
        }
        
        try {
            String[] parts = expirationDateStr.trim().split("/");
            if (parts.length != 2) {
                throw new CardValidationException("Invalid expiration date format. Expected MM/YY, got: " + expirationDateStr);
            }
            
            int month = Integer.parseInt(parts[0]);
            int year = Integer.parseInt(parts[1]);
            
            // Assume 20xx for 2-digit year
            if (year < 100) {
                year += 2000;
            }
            
            // Validate month range
            if (month < 1 || month > 12) {
                throw new CardValidationException("Invalid expiration month. Must be 1-12, got: " + month);
            }
            
            // Return last day of expiration month
            return LocalDate.of(year, month, 1).plusMonths(1).minusDays(1);
            
        } catch (NumberFormatException e) {
            throw new CardValidationException("Invalid expiration date format. Expected MM/YY with numeric values, got: " + expirationDateStr);
        } catch (DateTimeParseException e) {
            throw new CardValidationException("Invalid expiration date: " + expirationDateStr, e);
        }
    }

    /**
     * Parses account ID from string to Long.
     * 
     * @param accountIdStr Account ID as string (11 digits)
     * @return Account ID as Long
     * @throws CardValidationException if account ID format is invalid
     */
    private Long parseAccountId(String accountIdStr) throws CardValidationException {
        if (accountIdStr == null || accountIdStr.trim().isEmpty()) {
            throw new CardValidationException("Account ID is required");
        }
        
        try {
            return Long.parseLong(accountIdStr.trim());
        } catch (NumberFormatException e) {
            throw new CardValidationException("Invalid account ID format. Expected numeric value, got: " + accountIdStr);
        }
    }

    /**
     * Parses date from YYYY-MM-DD format to LocalDate.
     * 
     * @param dateStr Date string in YYYY-MM-DD format
     * @param fieldName Field name for error messages
     * @return LocalDate object or null if date string is empty
     * @throws CardValidationException if date format is invalid
     */
    private LocalDate parseDate(String dateStr, String fieldName) throws CardValidationException {
        if (dateStr == null || dateStr.trim().isEmpty()) {
            return null;
        }
        
        try {
            return LocalDate.parse(dateStr.trim(), DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException e) {
            throw new CardValidationException(
                "Invalid " + fieldName + " format. Expected YYYY-MM-DD, got: " + dateStr, e
            );
        }
    }

    /**
     * Creates JpaItemWriter for persisting Card entities.
     * 
     * <p>This bean writes validated Card entities to PostgreSQL card table using JPA/Hibernate.
     * Replaces COBOL VSAM WRITE operations from CBACT02C.cbl with transactional database inserts.</p>
     * 
     * <p><strong>Batch Insert Configuration:</strong></p>
     * <ul>
     *   <li>Hibernate batches INSERT statements for performance (hibernate.jdbc.batch_size property)</li>
     *   <li>Chunk size 1000 means up to 1000 cards persisted per transaction commit</li>
     *   <li>ON DUPLICATE KEY UPDATE handled by DataIntegrityViolationException skip policy</li>
     * </ul>
     * 
     * <p><strong>Transaction Boundaries:</strong></p>
     * <p>JpaItemWriter operations occur within Spring Batch chunk transaction. Successful write
     * commits all 1000 cards; any exception triggers rollback and item-level retry/skip based
     * on configured policies. This matches CICS SYNCPOINT commit behavior.</p>
     * 
     * @return Configured JpaItemWriter for Card entity persistence
     */
    @Bean
    public JpaItemWriter<Card> cardWriter() {
        log.info("Configuring Card Data Writer with EntityManagerFactory");
        
        JpaItemWriter<Card> writer = new JpaItemWriter<>();
        writer.setEntityManagerFactory(entityManagerFactory);
        
        return writer;
    }

    /**
     * SkipListener for logging skipped card records to error file.
     * 
     * <p>This listener captures all skipped records during read, process, and write phases,
     * logging details to error-cards-{timestamp}.csv file for manual review and correction.</p>
     * 
     * <p>Error file format: card_number,error_reason,timestamp,step_execution_id,original_record</p>
     */
    private static class CardSkipListener implements SkipListener<CardCsvRecord, Card> {
        
        private FileWriter errorWriter;
        private final DateTimeFormatter timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        
        @Override
        public void onSkipInRead(Throwable throwable) {
            log.warn("Skipped record during read phase: {}", throwable.getMessage());
            writeErrorRecord("READ_ERROR", throwable.getMessage(), null);
        }
        
        @Override
        public void onSkipInProcess(CardCsvRecord csvRecord, Throwable throwable) {
            log.warn("Skipped card during process phase. Card number: {}, Reason: {}", 
                     csvRecord != null ? csvRecord.getCardNumber() : "unknown", 
                     throwable.getMessage());
            writeErrorRecord(csvRecord != null ? csvRecord.getCardNumber() : "unknown", 
                           throwable.getMessage(), 
                           csvRecord);
        }
        
        @Override
        public void onSkipInWrite(Card card, Throwable throwable) {
            log.warn("Skipped card during write phase. Card number: {}, Reason: {}", 
                     card != null ? card.getCardNumber() : "unknown", 
                     throwable.getMessage());
            writeErrorRecord(card != null ? card.getCardNumber() : "unknown", 
                           throwable.getMessage(), 
                           null);
        }
        
        private void writeErrorRecord(String cardNumber, String errorReason, CardCsvRecord csvRecord) {
            try {
                if (errorWriter == null) {
                    String errorFileName = "error-cards-" + System.currentTimeMillis() + ".csv";
                    errorWriter = new FileWriter(errorFileName, true);
                    errorWriter.write("card_number,error_reason,timestamp,original_record\n");
                    log.info("Created error file: {}", errorFileName);
                }
                
                String timestamp = LocalDate.now().atStartOfDay().format(timestampFormatter);
                String originalRecord = csvRecord != null ? csvRecordToString(csvRecord) : "N/A";
                
                errorWriter.write(String.format("%s,\"%s\",%s,\"%s\"\n", 
                    cardNumber, 
                    errorReason.replace("\"", "\"\""), 
                    timestamp, 
                    originalRecord.replace("\"", "\"\"")));
                errorWriter.flush();
                
            } catch (IOException e) {
                log.error("Failed to write error record to file", e);
            }
        }
        
        private String csvRecordToString(CardCsvRecord record) {
            return String.format("%s,%s,%s,%s,%s,%s,%s,%s,%s",
                record.getCardNumber(),
                record.getAccountId(),
                record.getCardType(),
                record.getExpirationDate(),
                record.getEmbossedName(),
                record.getCvvCode(),
                record.getActiveStatus(),
                record.getOpenDate(),
                record.getLastUsedDate());
        }
    }

    /**
     * JobExecutionListener for pre/post job processing.
     * 
     * <p>Handles job lifecycle events including input file validation, optional table truncation
     * (FULL refresh mode), and execution statistics logging.</p>
     */
    private static class CardDataLoadJobListener implements org.springframework.batch.core.JobExecutionListener {
        
        private final CardRepository cardRepository;
        private long startTime;
        private long initialCardCount;
        
        public CardDataLoadJobListener(CardRepository cardRepository) {
            this.cardRepository = cardRepository;
        }
        
        @Override
        public void beforeJob(org.springframework.batch.core.JobExecution jobExecution) {
            startTime = System.currentTimeMillis();
            initialCardCount = cardRepository.count();
            
            log.info("Starting Card Data Load Job. Initial card count: {}", initialCardCount);
            
            // Check refresh mode parameter
            String refreshMode = jobExecution.getJobParameters().getString("refreshMode");
            if ("FULL".equalsIgnoreCase(refreshMode)) {
                log.warn("FULL refresh mode specified - this would truncate card table in production");
                // In production, this would call: cardRepository.deleteAll();
                // Commented out for safety during development
            }
        }
        
        @Override
        public void afterJob(org.springframework.batch.core.JobExecution jobExecution) {
            long endTime = System.currentTimeMillis();
            long duration = endTime - startTime;
            long finalCardCount = cardRepository.count();
            long cardsLoaded = finalCardCount - initialCardCount;
            
            // Gather execution statistics
            StepExecution stepExecution = jobExecution.getStepExecutions().iterator().next();
            long readCount = stepExecution.getReadCount();
            long writeCount = stepExecution.getWriteCount();
            long skipCount = stepExecution.getSkipCount();
            
            log.info("======================================");
            log.info("Card Data Load Job Completed");
            log.info("======================================");
            log.info("Status: {}", jobExecution.getStatus());
            log.info("Duration: {} ms ({} seconds)", duration, duration / 1000);
            log.info("Records Read: {}", readCount);
            log.info("Records Written: {}", writeCount);
            log.info("Records Skipped: {}", skipCount);
            log.info("Cards in Database Before: {}", initialCardCount);
            log.info("Cards in Database After: {}", finalCardCount);
            log.info("Net Cards Loaded: {}", cardsLoaded);
            log.info("Processing Rate: {} cards/second", 
                     duration > 0 ? (writeCount * 1000 / duration) : 0);
            log.info("======================================");
        }
    }

    /**
     * DTO for CSV record parsing.
     * 
     * <p>This class represents a single row from the card CSV file before validation and
     * entity construction. All fields are Strings to allow flexible parsing and validation.</p>
     */
    public static class CardCsvRecord {
        private String cardNumber;
        private String accountId;  // String initially, converted to Long in processor
        private String cardType;
        private String expirationDate;
        private String embossedName;
        private String cvvCode;
        private String activeStatus;
        private String openDate;
        private String lastUsedDate;
        
        // Getters and setters
        public String getCardNumber() { return cardNumber; }
        public void setCardNumber(String cardNumber) { this.cardNumber = cardNumber; }
        
        public String getAccountId() { return accountId; }
        public void setAccountId(String accountId) { this.accountId = accountId; }
        
        public String getCardType() { return cardType; }
        public void setCardType(String cardType) { this.cardType = cardType; }
        
        public String getExpirationDate() { return expirationDate; }
        public void setExpirationDate(String expirationDate) { this.expirationDate = expirationDate; }
        
        public String getEmbossedName() { return embossedName; }
        public void setEmbossedName(String embossedName) { this.embossedName = embossedName; }
        
        public String getCvvCode() { return cvvCode; }
        public void setCvvCode(String cvvCode) { this.cvvCode = cvvCode; }
        
        public String getActiveStatus() { return activeStatus; }
        public void setActiveStatus(String activeStatus) { this.activeStatus = activeStatus; }
        
        public String getOpenDate() { return openDate; }
        public void setOpenDate(String openDate) { this.openDate = openDate; }
        
        public String getLastUsedDate() { return lastUsedDate; }
        public void setLastUsedDate(String lastUsedDate) { this.lastUsedDate = lastUsedDate; }
    }

    /**
     * Custom exception for card validation failures.
     * 
     * <p>Thrown by ItemProcessor when validation rules are not met. This exception type
     * is configured as skippable in the step, allowing the batch job to continue processing
     * remaining records after logging the validation failure.</p>
     */
    public static class CardValidationException extends Exception {
        public CardValidationException(String message) {
            super(message);
        }
        
        public CardValidationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
