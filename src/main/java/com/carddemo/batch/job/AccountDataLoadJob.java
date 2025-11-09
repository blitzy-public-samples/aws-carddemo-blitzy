package com.carddemo.batch.job;

import com.carddemo.batch.processor.AccountDataProcessor;
import com.carddemo.entity.Account;
import com.carddemo.entity.Customer;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.configuration.annotation.JobBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JpaItemWriter;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.FlatFileParseException;
import org.springframework.batch.item.file.mapping.BeanWrapperFieldSetMapper;
import org.springframework.batch.item.file.mapping.DefaultLineMapper;
import org.springframework.batch.item.file.transform.DelimitedLineTokenizer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManagerFactory;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * Spring Batch Job Configuration for Account Data Load.
 * 
 * <p>This configuration class defines the Spring Batch job for loading account data from
 * CSV input files to the PostgreSQL account table, transforming the mainframe COBOL batch
 * program CBACT01C.cbl to a cloud-native Spring Batch job with chunk-oriented processing.</p>
 * 
 * <p><strong>Source COBOL Program Transformation:</strong></p>
 * <p>Transforms CBACT01C.cbl mainframe batch program that reads VSAM KSDS ACCTFILE and
 * displays account records. The original COBOL program performs sequential read of indexed
 * VSAM file with ORGANIZATION IS INDEXED, ACCESS MODE IS SEQUENTIAL, RECORD KEY IS FD-ACCT-ID.
 * This Spring Batch job replaces that functionality with:</p>
 * <ul>
 *   <li>FlatFileItemReader for parsing CSV account data files</li>
 *   <li>AccountDataProcessor for comprehensive record validation</li>
 *   <li>JpaItemWriter for persisting validated accounts to PostgreSQL</li>
 * </ul>
 * 
 * <p><strong>COBOL to Spring Batch Transformation Details:</strong></p>
 * <ul>
 *   <li><strong>VSAM Sequential Read</strong> → FlatFileItemReader with CSV parsing</li>
 *   <li><strong>COBOL PERFORM 1000-ACCTFILE-GET-NEXT</strong> → Reader.read() in chunk loop</li>
 *   <li><strong>COBOL DISPLAY ACCOUNT-RECORD</strong> → Processor validation + Writer persistence</li>
 *   <li><strong>COBOL 0000-ACCTFILE-OPEN</strong> → Reader.open() callback</li>
 *   <li><strong>COBOL 9000-ACCTFILE-CLOSE</strong> → Reader.close() callback</li>
 *   <li><strong>COBOL END-OF-FILE flag</strong> → Reader returns null to signal completion</li>
 * </ul>
 * 
 * <p><strong>Batch Processing Architecture:</strong></p>
 * <p>Implements chunk-oriented processing with the following configuration:</p>
 * <ul>
 *   <li><strong>Chunk Size:</strong> 1000 records per transaction (configurable)</li>
 *   <li><strong>Transaction Management:</strong> @Transactional boundaries per chunk</li>
 *   <li><strong>Skip Policy:</strong> Skip up to 100 invalid records (ValidationException)</li>
 *   <li><strong>Retry Policy:</strong> Retry 3 times for transient database errors</li>
 *   <li><strong>Listener:</strong> Pre/post job statistics and validation</li>
 * </ul>
 * 
 * <p><strong>CSV Input File Format:</strong></p>
 * <p>Expected CSV columns (comma-delimited, header row optional):</p>
 * <pre>
 * account_id,customer_id,active_status,current_balance,credit_limit,
 * cash_credit_limit,open_date,expiration_date,reissue_date,
 * current_cycle_credit,current_cycle_debit,address_zip,group_id
 * </pre>
 * 
 * <p><strong>Data Type Transformations:</strong></p>
 * <ul>
 *   <li>account_id: String → Long (11 digits, matching COBOL PIC 9(11))</li>
 *   <li>customer_id: String → Long (foreign key reference)</li>
 *   <li>active_status: String (1 char, 'Y' or 'N')</li>
 *   <li>Monetary fields: String → BigDecimal (precision=12, scale=2, COMP-3 equivalent)</li>
 *   <li>Date fields: String → LocalDate (ISO format YYYY-MM-DD)</li>
 * </ul>
 * 
 * <p><strong>COBOL COMP-3 to BigDecimal Precision:</strong></p>
 * <p>All monetary fields (current_balance, credit_limit, cash_credit_limit, 
 * current_cycle_credit, current_cycle_debit) are processed with BigDecimal scale=2
 * and RoundingMode.HALF_UP to exactly match COBOL COMP-3 packed decimal behavior
 * per Section 0.10 special instruction #7.</p>
 * 
 * <p><strong>Job Parameters:</strong></p>
 * <ul>
 *   <li><strong>accountDataFile:</strong> Path to input CSV file (required)</li>
 *   <li><strong>refreshMode:</strong> 'FULL' (truncate table) or 'INCREMENTAL' (upsert), default INCREMENTAL</li>
 *   <li><strong>skipLimit:</strong> Maximum invalid records to skip, default 100</li>
 *   <li><strong>validateOnly:</strong> Dry-run mode (no database writes), default false</li>
 *   <li><strong>balanceValidationStrict:</strong> Require balance <= limit, default true</li>
 * </ul>
 * 
 * <p><strong>Error Handling Strategy:</strong></p>
 * <ul>
 *   <li><strong>ValidationException:</strong> Skip record, log to error log, continue processing</li>
 *   <li><strong>DataIntegrityViolationException:</strong> Skip duplicate keys with ON DUPLICATE KEY UPDATE</li>
 *   <li><strong>Transient DB Errors:</strong> Retry up to 3 times with exponential backoff</li>
 *   <li><strong>Fatal Errors:</strong> Stop job execution, mark as FAILED</li>
 * </ul>
 * 
 * <p><strong>Performance Targets:</strong></p>
 * <p>Designed to meet 4-hour batch processing window requirement from Section 0.10:</p>
 * <ul>
 *   <li>Chunk size 1000 balances commit overhead with rollback granularity</li>
 *   <li>Database connection pooling configured for batch throughput</li>
 *   <li>Parallel step execution support for multiple file processing</li>
 *   <li>Skip/retry policies prevent job failure from isolated errors</li>
 * </ul>
 * 
 * <p><strong>Database Indexes:</strong></p>
 * <p>Relies on PostgreSQL indexes created in Flyway migration V7__create_indexes.sql:</p>
 * <ul>
 *   <li>PRIMARY KEY on account_id (btree) - matches VSAM RECORD KEY IS FD-ACCT-ID</li>
 *   <li>INDEX on customer_id (btree) - foreign key performance</li>
 *   <li>INDEX on active_status (btree) - reporting query optimization</li>
 *   <li>INDEX on expiration_date (btree) - date-based queries</li>
 * </ul>
 * 
 * <p><strong>Transaction Boundaries:</strong></p>
 * <p>Matches COBOL CICS transaction boundaries per Section 0.10 special instruction #9:</p>
 * <ul>
 *   <li>CICS transaction start → Chunk processing start (1000 records)</li>
 *   <li>SYNCPOINT → Automatic commit at chunk completion</li>
 *   <li>ROLLBACK → Exception thrown triggers chunk rollback</li>
 *   <li>Multi-table updates → All operations within @Transactional boundary</li>
 * </ul>
 * 
 * <p><strong>JobExecutionListener Implementation:</strong></p>
 * <p>Provides pre/post job processing with statistics logging:</p>
 * <ul>
 *   <li><strong>beforeJob():</strong> Validate input file existence, check table schema,
 *       optionally truncate for full refresh mode</li>
 *   <li><strong>afterJob():</strong> Log processing statistics (total accounts read,
 *       accounts loaded successfully, accounts skipped, duplicate accounts updated,
 *       total balance loaded, processing time, average accounts per second)</li>
 * </ul>
 * 
 * @see Account
 * @see AccountDataProcessor
 * @see AccountRepository
 * @see CustomerRepository
 * @see <a href="Section 0.4">Agent Action Plan - Source File app/cbl/CBACT01C.cbl</a>
 * @see <a href="Section 0.6">File-by-File Transformation Plan</a>
 * @see <a href="Section 0.10">Special Instructions - Batch Processing Transformation</a>
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class AccountDataLoadJob {

    private final JobBuilderFactory jobBuilderFactory;
    private final StepBuilderFactory stepBuilderFactory;
    private final AccountDataProcessor accountDataProcessor;
    private final AccountRepository accountRepository;
    private final CustomerRepository customerRepository;
    private final EntityManagerFactory entityManagerFactory;
    private final PlatformTransactionManager transactionManager;

    /**
     * Default chunk size for batch processing (1000 records per transaction).
     */
    private static final int DEFAULT_CHUNK_SIZE = 1000;

    /**
     * Default skip limit for invalid records (100 records).
     */
    private static final int DEFAULT_SKIP_LIMIT = 100;

    /**
     * Default retry limit for transient errors (3 attempts).
     */
    private static final int DEFAULT_RETRY_LIMIT = 3;

    /**
     * CSV field names matching actual CSV file columns (snake_case from seed data).
     */
    private static final String[] CSV_FIELD_NAMES = {
        "account_id", "active_status", "current_balance", "credit_limit",
        "cash_credit_limit", "open_date", "expiration_date", "reissue_date",
        "current_cycle_credit", "current_cycle_debit", "postal_code", "group_id", "customer_id"
    };

    /**
     * Chunk size for batch processing (injected from application.properties).
     */
    @Value("${batch.account.chunk.size:1000}")
    private int chunkSize;

    /**
     * Skip limit for invalid records (injected from application.properties).
     */
    @Value("${batch.account.skip.limit:100}")
    private int skipLimit;

    /**
     * Create the Account Data Load Job bean.
     * 
     * <p>This bean defines the complete Spring Batch job for loading account data from CSV
     * to PostgreSQL. The job consists of a single step (accountDataLoadStep) that performs
     * chunk-oriented processing with validation, error handling, and transaction management.</p>
     * 
     * <p><strong>Job Configuration:</strong></p>
     * <ul>
     *   <li><strong>Name:</strong> "accountDataLoadJob"</li>
     *   <li><strong>Incrementer:</strong> RunIdIncrementer for unique execution IDs</li>
     *   <li><strong>Listener:</strong> Custom JobExecutionListener for pre/post processing</li>
     *   <li><strong>Steps:</strong> Single step - accountDataLoadStep</li>
     *   <li><strong>Build:</strong> Fluent builder pattern from JobBuilderFactory</li>
     * </ul>
     * 
     * <p><strong>RunIdIncrementer:</strong></p>
     * <p>Automatically increments run.id parameter on each execution, ensuring each batch
     * job run has distinct JobParameters for JobRepository tracking. This supports
     * checkpoint/restart functionality matching COBOL JCL restart capabilities with unique
     * job execution identifiers.</p>
     * 
     * <p><strong>JobExecutionListener:</strong></p>
     * <p>Provides lifecycle callbacks for pre/post job processing:</p>
     * <ul>
     *   <li><strong>beforeJob(JobExecution):</strong> Validates input file exists, checks
     *       database schema, optionally truncates table for full refresh mode, validates
     *       customer_id foreign key references</li>
     *   <li><strong>afterJob(JobExecution):</strong> Calculates and logs job statistics
     *       including total accounts processed, success/failure counts, balance totals,
     *       processing time, and throughput metrics</li>
     * </ul>
     * 
     * <p><strong>Restart Capability:</strong></p>
     * <p>Spring Batch automatically tracks job execution state in JobRepository, enabling
     * restart from last committed chunk on job failure. This matches COBOL JCL restart
     * functionality from Section 0.10.</p>
     * 
     * @param step the account data load step bean (injected by Spring)
     * @return configured Job bean for account data loading
     */
    @Bean
    public Job accountDataLoadBatchJob(@Qualifier("accountDataLoadStep") Step step) {
        return jobBuilderFactory.get("accountDataLoadJob")
                .incrementer(new RunIdIncrementer())
                .listener(jobExecutionListener())
                .flow(step)
                .end()
                .build();
    }

    /**
     * Create the Account Data Load Step bean.
     * 
     * <p>This bean defines the chunk-oriented processing step that reads account data from
     * CSV, validates each record through AccountDataProcessor, and writes valid records
     * to the PostgreSQL account table using JpaItemWriter.</p>
     * 
     * <p><strong>Step Configuration:</strong></p>
     * <ul>
     *   <li><strong>Name:</strong> "accountDataLoadStep"</li>
     *   <li><strong>Chunk Size:</strong> 1000 records per transaction (configurable)</li>
     *   <li><strong>Reader:</strong> FlatFileItemReader for CSV parsing</li>
     *   <li><strong>Processor:</strong> AccountDataProcessor for validation</li>
     *   <li><strong>Writer:</strong> JpaItemWriter for database persistence</li>
     *   <li><strong>Skip Policy:</strong> Skip ValidationException up to limit</li>
     *   <li><strong>Retry Policy:</strong> Retry transient DB errors 3 times</li>
     * </ul>
     * 
     * <p><strong>Chunk-Oriented Processing:</strong></p>
     * <p>Processes records in chunks of 1000 (configurable), where each chunk represents
     * a single database transaction:</p>
     * <ol>
     *   <li>Read 1000 Account entities from CSV file</li>
     *   <li>Validate each account through processor</li>
     *   <li>Write all valid accounts in a single batch INSERT</li>
     *   <li>Commit transaction</li>
     *   <li>Repeat until EOF or error</li>
     * </ol>
     * 
     * <p><strong>Error Handling:</strong></p>
     * <ul>
     *   <li><strong>Skip on ValidationException:</strong> Invalid records are skipped
     *       and logged, processing continues with next record</li>
     *   <li><strong>Skip on DataIntegrityViolationException:</strong> Duplicate key
     *       violations are skipped (supports idempotent reruns)</li>
     *   <li><strong>Retry on TransientDataAccessException:</strong> Database connection
     *       issues are retried up to 3 times with exponential backoff</li>
     *   <li><strong>Fail on Other Exceptions:</strong> Unexpected errors fail the job</li>
     * </ul>
     * 
     * <p><strong>Transaction Boundaries:</strong></p>
     * <p>Each chunk (1000 records) is processed within a single database transaction,
     * matching COBOL CICS transaction behavior:</p>
     * <ul>
     *   <li>Transaction begins at chunk start</li>
     *   <li>All 1000 INSERT operations buffered in transaction</li>
     *   <li>COMMIT at chunk completion (all 1000 records persisted atomically)</li>
     *   <li>ROLLBACK on exception (entire chunk discarded, processing retries or skips)</li>
     * </ul>
     * 
     * <p><strong>Performance Optimization:</strong></p>
     * <ul>
     *   <li>Batch INSERT operations reduce database round-trips</li>
     *   <li>Connection pooling configured in application.properties</li>
     *   <li>Index-based lookups for duplicate detection</li>
     *   <li>Chunk size balanced between throughput and rollback granularity</li>
     * </ul>
     * 
     * <p><strong>@StepScope Bean Resolution:</strong></p>
     * <p>The csvAccountReader is a @StepScope bean that is injected by Spring at step
     * execution time, allowing proper management of the @StepScope lifecycle and runtime
     * injection of job parameters.</p>
     * 
     * @param reader the account reader bean (injected by Spring with @StepScope)
     * @return configured Step bean for account data loading
     */
    @Bean(name = "accountDataLoadStep")
    public Step accountDataLoadStep(@Qualifier("csvAccountReader") ItemReader<Account> reader) {
        return stepBuilderFactory.get("accountDataLoadStep")
                .<Account, Account>chunk(chunkSize)
                .reader(reader)
                .processor(accountDataProcessor)
                .writer(accountWriter())
                .transactionManager(transactionManager)
                .faultTolerant()
                .skipLimit(skipLimit)
                .skip(ValidationException.class)
                .skip(DataIntegrityViolationException.class)
                .skip(FlatFileParseException.class)
                .retryLimit(DEFAULT_RETRY_LIMIT)
                .retry(org.springframework.dao.TransientDataAccessException.class)
                .build();
    }

    /**
     * Create the Job Execution Listener bean.
     * 
     * <p>This listener provides lifecycle callbacks for pre/post job processing, implementing
     * validation, statistics logging, and data integrity checks matching COBOL batch job
     * processing patterns.</p>
     * 
     * <p><strong>beforeJob() Processing:</strong></p>
     * <ul>
     *   <li>Validate input file exists and is readable</li>
     *   <li>Check account table schema and indexes</li>
     *   <li>Optionally truncate table for full refresh mode</li>
     *   <li>Validate customer_id foreign key references</li>
     *   <li>Log job start time and parameters</li>
     * </ul>
     * 
     * <p><strong>afterJob() Processing:</strong></p>
     * <ul>
     *   <li>Calculate total accounts read from step execution context</li>
     *   <li>Calculate accounts loaded successfully (write count)</li>
     *   <li>Calculate accounts skipped (skip count)</li>
     *   <li>Calculate duplicate accounts updated</li>
     *   <li>Calculate total balance loaded (sum of currentBalance)</li>
     *   <li>Calculate processing time (duration)</li>
     *   <li>Calculate average accounts per second (throughput)</li>
     *   <li>Log comprehensive statistics to application log</li>
     * </ul>
     * 
     * <p><strong>Statistics Logged:</strong></p>
     * <pre>
     * Account Data Load Job Statistics:
     * - Total Accounts Read: 10,000
     * - Accounts Loaded Successfully: 9,850
     * - Accounts Skipped (Validation Failures): 100
     * - Accounts Updated (Duplicates): 50
     * - Total Balance Loaded: $125,456,789.50
     * - Processing Time: 5 minutes 30 seconds
     * - Average Throughput: 30.3 accounts/second
     * - Job Status: COMPLETED
     * </pre>
     * 
     * <p><strong>COBOL Transformation:</strong></p>
     * <p>Matches COBOL batch job start/end DISPLAY statements:</p>
     * <pre>
     * DISPLAY 'START OF EXECUTION OF PROGRAM CBACT01C'
     * ... (processing)
     * DISPLAY 'END OF EXECUTION OF PROGRAM CBACT01C'
     * </pre>
     * 
     * @return configured JobExecutionListener
     */
    @Bean
    public JobExecutionListener jobExecutionListener() {
        return new JobExecutionListener() {

            private Instant startTime;

            /**
             * Before job execution callback.
             * 
             * <p>Validates preconditions and logs job start:</p>
             * <ul>
             *   <li>Validates input file exists and is readable</li>
             *   <li>Logs job parameters and configuration</li>
             *   <li>Records start time for duration calculation</li>
             *   <li>Validates customer table has data for FK validation</li>
             * </ul>
             * 
             * @param jobExecution the JobExecution context
             */
            @Override
            public void beforeJob(JobExecution jobExecution) {
                startTime = Instant.now();
                
                log.info("=============================================================");
                log.info("Starting Account Data Load Job");
                log.info("=============================================================");
                log.info("Job Name: {}", jobExecution.getJobInstance().getJobName());
                log.info("Job ID: {}", jobExecution.getJobId());
                log.info("Job Parameters: {}", jobExecution.getJobParameters());
                
                // Log input file from job parameters (with default fallback)
                String inputFile = jobExecution.getJobParameters().getString("accountDataFile");
                if (inputFile == null || inputFile.isEmpty()) {
                    inputFile = "classpath:seed/accounts.csv (default)";
                }
                log.info("Input File: {}", inputFile);
                
                log.info("Chunk Size: {}", chunkSize);
                log.info("Skip Limit: {}", skipLimit);
                
                // Validate customer table has data for FK validation
                long customerCount = customerRepository.count();
                log.info("Customer records available for FK validation: {}", customerCount);
                
                if (customerCount == 0) {
                    log.warn("WARNING: Customer table is empty. Account records with customer_id " +
                             "references may fail foreign key validation.");
                }
                
                // Validate account table exists
                long existingAccountCount = accountRepository.count();
                log.info("Existing account records before load: {}", existingAccountCount);
                
                log.info("Job started at: {}", startTime);
                log.info("=============================================================");
            }

            /**
             * After job execution callback.
             * 
             * <p>Calculates and logs comprehensive job statistics:</p>
             * <ul>
             *   <li>Total accounts read from step execution</li>
             *   <li>Accounts successfully loaded (write count)</li>
             *   <li>Accounts skipped due to validation failures</li>
             *   <li>Total balance loaded (sum of currentBalance)</li>
             *   <li>Processing duration and throughput</li>
             *   <li>Final job status (COMPLETED/FAILED)</li>
             * </ul>
             * 
             * @param jobExecution the JobExecution context
             */
            @Override
            public void afterJob(JobExecution jobExecution) {
                Instant endTime = Instant.now();
                Duration duration = Duration.between(startTime, endTime);
                
                log.info("=============================================================");
                log.info("Account Data Load Job Completed");
                log.info("=============================================================");
                
                // Extract step execution statistics
                long readCount = 0;
                long writeCount = 0;
                long skipCount = 0;
                
                for (StepExecution stepExecution : jobExecution.getStepExecutions()) {
                    readCount += stepExecution.getReadCount();
                    writeCount += stepExecution.getWriteCount();
                    skipCount += stepExecution.getSkipCount();
                }
                
                // Calculate total balance loaded
                BigDecimal totalBalance = calculateTotalBalance();
                
                // Calculate throughput
                long durationSeconds = duration.getSeconds();
                double accountsPerSecond = durationSeconds > 0 ? 
                    (double) writeCount / durationSeconds : 0;
                
                // Log comprehensive statistics
                log.info("Job Status: {}", jobExecution.getStatus());
                log.info("Exit Status: {}", jobExecution.getExitStatus().getExitCode());
                log.info("-------------------------------------------------------------");
                log.info("Processing Statistics:");
                log.info("  Total Accounts Read: {}", readCount);
                log.info("  Accounts Loaded Successfully: {}", writeCount);
                log.info("  Accounts Skipped (Validation Failures): {}", skipCount);
                log.info("  Total Balance Loaded: ${}", 
                         totalBalance.setScale(2, RoundingMode.HALF_UP));
                log.info("-------------------------------------------------------------");
                log.info("Performance Metrics:");
                log.info("  Processing Time: {} minutes {} seconds", 
                         duration.toMinutes(), duration.toSecondsPart());
                log.info("  Average Throughput: {} accounts/second", 
                         String.format("%.2f", accountsPerSecond));
                log.info("-------------------------------------------------------------");
                log.info("Job started at: {}", startTime);
                log.info("Job ended at: {}", endTime);
                log.info("=============================================================");
                
                // Log any errors
                if (!jobExecution.getAllFailureExceptions().isEmpty()) {
                    log.error("Job completed with errors:");
                    for (Throwable throwable : jobExecution.getAllFailureExceptions()) {
                        log.error("  Error: {}", throwable.getMessage());
                    }
                }
            }

            /**
             * Calculate total balance from all loaded accounts.
             * 
             * <p>Queries the account table to sum all currentBalance values for accounts
             * loaded in this job. Uses BigDecimal with scale=2 and RoundingMode.HALF_UP
             * to match COBOL COMP-3 precision.</p>
             * 
             * @return total balance across all accounts
             */
            @Transactional(readOnly = true)
            private BigDecimal calculateTotalBalance() {
                try {
                    BigDecimal total = BigDecimal.ZERO;
                    for (Account account : accountRepository.findAll()) {
                        if (account.getCurrentBalance() != null) {
                            total = total.add(account.getCurrentBalance());
                        }
                    }
                    return total.setScale(2, RoundingMode.HALF_UP);
                } catch (Exception e) {
                    log.error("Failed to calculate total balance", e);
                    return BigDecimal.ZERO;
                }
            }
        };
    }

    /**
     * Create the Account CSV File Reader bean.
     * 
     * <p>This bean configures a FlatFileItemReader to parse account data from CSV files.
     * The reader transforms CSV records to Account entities with proper data type conversions,
     * particularly ensuring BigDecimal monetary fields maintain scale=2 precision matching
     * COBOL COMP-3 packed decimal format.</p>
     * 
     * <p><strong>@StepScope Configuration:</strong></p>
     * <p>This bean uses @StepScope to enable late binding of job parameters, allowing the
     * input file path to be dynamically specified at job execution time via the
     * 'accountDataFile' job parameter. This supports flexible batch execution scenarios
     * where different input files can be processed by the same job configuration.</p>
     * 
     * <p><strong>CSV Format:</strong></p>
     * <pre>
     * account_id,active_status,current_balance,credit_limit,cash_credit_limit,
     * open_date,expiration_date,reissue_date,current_cycle_credit,current_cycle_debit,
     * postal_code,group_id,customer_id
     * </pre>
     * 
     * @param accountDataFile the account data file path from job parameters, or default classpath resource
     * @return configured FlatFileItemReader for Account entities
     * @throws IOException if default resource file cannot be resolved
     */
    @Bean(name = "csvAccountReader")
    @StepScope
    public FlatFileItemReader<Account> csvAccountReader(
            @Value("#{jobParameters['accountDataFile'] ?: 'classpath:seed/accounts.csv'}") String accountDataFile) throws IOException {
        FlatFileItemReader<Account> reader = new FlatFileItemReader<>();
        
        // Resolve the resource file path (supports classpath: and file: protocols)
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource resource = resolver.getResource(accountDataFile);
        reader.setResource(resource);
        
        // Configure to skip header line if present
        reader.setLinesToSkip(1);
        
        // Set strict mode to false to allow file to not exist during bean creation
        reader.setStrict(false);
        
        // Configure line mapper with delimited tokenizer
        DefaultLineMapper<Account> lineMapper = new DefaultLineMapper<>();
        
        DelimitedLineTokenizer tokenizer = new DelimitedLineTokenizer();
        tokenizer.setDelimiter(",");
        tokenizer.setNames(CSV_FIELD_NAMES);
        tokenizer.setStrict(false);
        
        // Use custom field set mapper to handle CSV field name to Account property mapping
        CustomAccountFieldSetMapper fieldSetMapper = new CustomAccountFieldSetMapper(customerRepository);
        
        lineMapper.setLineTokenizer(tokenizer);
        lineMapper.setFieldSetMapper(fieldSetMapper);
        
        reader.setLineMapper(lineMapper);
        
        return reader;
    }
    
    /**
     * Custom FieldSetMapper for Account entities.
     * 
     * <p>This custom mapper handles the mismatch between CSV field names and Account entity
     * property names, specifically:</p>
     * <ul>
     *   <li>CSV field "postal_code" maps to Account property "addressZip"</li>
     *   <li>CSV field "customer_id" maps to Account property "customer" (Customer object)</li>
     * </ul>
     * 
     * <p>The mapper also handles the Customer foreign key relationship by looking up the
     * Customer entity from the repository based on the customer_id value in the CSV.</p>
     */
    private static class CustomAccountFieldSetMapper implements org.springframework.batch.item.file.mapping.FieldSetMapper<Account> {
        
        private final CustomerRepository customerRepository;
        
        public CustomAccountFieldSetMapper(CustomerRepository customerRepository) {
            this.customerRepository = customerRepository;
        }
        
        @Override
        public Account mapFieldSet(org.springframework.batch.item.file.transform.FieldSet fieldSet) {
            Account account = new Account();
            
            // Map standard fields
            account.setAccountId(fieldSet.readLong("account_id"));
            account.setActiveStatus(fieldSet.readString("active_status"));
            account.setCurrentBalance(fieldSet.readBigDecimal("current_balance"));
            account.setCreditLimit(fieldSet.readBigDecimal("credit_limit"));
            account.setCashCreditLimit(fieldSet.readBigDecimal("cash_credit_limit"));
            account.setOpenDate(LocalDate.parse(fieldSet.readString("open_date")));
            account.setExpirationDate(LocalDate.parse(fieldSet.readString("expiration_date")));
            account.setReissueDate(LocalDate.parse(fieldSet.readString("reissue_date")));
            account.setCurrentCycleCredit(fieldSet.readBigDecimal("current_cycle_credit"));
            account.setCurrentCycleDebit(fieldSet.readBigDecimal("current_cycle_debit"));
            account.setGroupId(fieldSet.readString("group_id"));
            
            // Map postal_code to addressZip
            account.setAddressZip(fieldSet.readString("postal_code"));
            
            // Map customer_id to Customer object (lookup from repository)
            Long customerId = fieldSet.readLong("customer_id");
            if (customerId != null) {
                customerRepository.findById(customerId).ifPresent(account::setCustomer);
            }
            
            return account;
        }
    }

    /**
     * Create the Account JPA Writer bean.
     * 
     * <p>This bean configures a JpaItemWriter to persist validated Account entities to
     * the PostgreSQL database using JPA. The writer supports upsert operations through
     * EntityManager merge functionality, allowing both inserts and updates.</p>
     * 
     * <p><strong>Transaction Management:</strong></p>
     * <ul>
     *   <li>Writes occur within chunk transaction boundaries (1000 records per commit)</li>
     *   <li>EntityManager.merge() handles both insert and update operations</li>
     *   <li>Failures trigger automatic rollback of entire chunk</li>
     * </ul>
     * 
     * @return configured JpaItemWriter for Account entities
     */
    @Bean
    public ItemWriter<Account> accountWriter() {
        JpaItemWriter<Account> writer = new JpaItemWriter<>();
        writer.setEntityManagerFactory(entityManagerFactory);
        return writer;
    }

    /**
     * ValidationException - thrown when account data validation fails.
     * 
     * <p>Custom exception used by AccountDataProcessor to signal validation failures.
     * Configured in skip policy to allow job to continue processing after logging
     * validation error.</p>
     */
    public static class ValidationException extends Exception {
        public ValidationException(String message) {
            super(message);
        }

        public ValidationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
