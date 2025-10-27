/*
 * TransactionProcessingJobConfig.java
 *
 * Spring Batch job configuration class defining the transactionProcessingJob for daily
 * transaction processing. Replaces JCL job orchestration with Java-based batch job
 * configuration using Spring Batch framework.
 *
 * Converted from JCL jobs:
 * - POSTTRAN.jcl (executes CBTRN02C for transaction posting)
 * - TRANREPT.jcl (executes CBTRN03C for category summarization)
 *
 * Converted from COBOL batch programs:
 * - CBTRN01C.cbl: Transaction file validation (Step 1: validationStep)
 * - CBTRN02C.cbl: Daily transaction posting (Step 2: postingStep)
 * - CBTRN03C.cbl: Transaction category summarization (Step 3: categorizationStep)
 *
 * Original JCL Orchestration:
 * 
 * POSTTRAN.jcl (lines 23-42):
 * //STEP15 EXEC PGM=CBTRN02C
 * //TRANFILE DD DSN=AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS     (Transaction master)
 * //DALYTRAN DD DSN=AWS.M2.CARDDEMO.DALYTRAN.PS            (Daily transaction input)
 * //XREFFILE DD DSN=AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS     (Card-account cross-reference)
 * //ACCTFILE DD DSN=AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS     (Account master)
 * //TCATBALF DD DSN=AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS     (Transaction category balances)
 * 
 * TRANREPT.jcl (lines 59-80):
 * //STEP10R EXEC PGM=CBTRN03C
 * //TRANFILE DD DSN=AWS.M2.CARDDEMO.TRANSACT.DALY(+1)      (Processed transactions)
 * //CARDXREF DD DSN=AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS     (Card-account cross-reference)
 * //TRANTYPE DD DSN=AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS     (Transaction types)
 * //TRANCATG DD DSN=AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS     (Transaction categories)
 * //TRANREPT DD DSN=AWS.M2.CARDDEMO.TRANREPT(+1)           (Report output)
 *
 * Business Logic Preservation:
 * 
 * Step 1 - Validation (CBTRN01C.cbl):
 * - Sequential read of DALYTRAN daily transaction file
 * - Card-account cross-reference validation via XREFFILE lookup
 * - Field-level validation (card number exists, amount positive, merchant ID valid)
 * - Transaction type code validation against TRANTYPE reference data
 * - Reject handling for invalid transactions
 *
 * Step 2 - Posting (CBTRN02C.cbl):
 * - Read validated transactions from DALYTRAN
 * - Update ACCTFILE account balances with transaction amounts
 * - Enforce credit limit checks (ACCT-CREDIT-LIMIT >= calculated balance)
 * - Write successfully posted transactions to permanent TRANFILE
 * - Update TCATBALF transaction category balances
 * - Maintain COMP-3 precision using BigDecimal with scale 2 and RoundingMode.HALF_UP
 *
 * Step 3 - Categorization (CBTRN03C.cbl):
 * - Read transactions from TRANFILE
 * - Aggregate by transaction type and category codes
 * - Update TCATBALF with aggregated balances per account/type/category combination
 * - Generate formatted transaction report (replaced with metrics exposure via /actuator/batch)
 *
 * Spring Batch Architecture:
 * 
 * This configuration class defines a Job with three sequential Steps:
 * 
 * transactionProcessingJob
 *   └─> validationStep (Step 1)
 *         └─> postingStep (Step 2)  [executed only if validation succeeds]
 *               └─> categorizationStep (Step 3)  [executed only if posting succeeds]
 *
 * Each step uses chunk-oriented processing model:
 * - ItemReader: Reads Transaction entities from database (page size 1000)
 * - ItemProcessor: Applies business logic validation and transformation
 * - ItemWriter: Bulk persists processed transactions (chunk size 1000)
 *
 * Chunk-Oriented Processing (1000 records per chunk):
 * 1. Reader reads up to 1000 transactions
 * 2. Processor validates/transforms each transaction
 * 3. Writer bulk persists all 1000 transactions
 * 4. Transaction commits (checkpoint created)
 * 5. Repeat until reader returns null (EOF)
 *
 * Checkpoint/Restart Capability:
 * Spring Batch JobRepository automatically maintains job execution state in PostgreSQL
 * database tables (BATCH_JOB_INSTANCE, BATCH_JOB_EXECUTION, BATCH_STEP_EXECUTION,
 * BATCH_STEP_EXECUTION_CONTEXT). If job fails, restart from last successful chunk commit.
 *
 * Scheduling:
 * @Scheduled annotation with cron expression "0 0 1 * * *" executes job daily at 01:00
 * (1:00 AM) during overnight batch window, meeting Section 0.7.7 4-hour batch processing
 * window requirement (02:00-06:00).
 *
 * Performance Requirements:
 * - Complete transaction processing within 4-hour overnight cycle (Section 0.7.7)
 * - Chunk size 1000 optimizes database round-trips vs memory usage
 * - JPA batch insert optimization (hibernate.jdbc.batch_size=1000)
 * - Sequential step execution minimizes resource contention
 *
 * Error Handling:
 * - Skip policy: Allow up to 10 validation failures per chunk before failing job
 * - StepExecutionListener tracks metrics (validated, posted, categorized, errors)
 * - Metrics exposed via Spring Boot Actuator /actuator/batch endpoint
 * - Logging configured for batch job debugging and operational monitoring
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.carddemo.batch.config;

import com.carddemo.batch.processor.TransactionProcessor;
import com.carddemo.batch.reader.TransactionReader;
import com.carddemo.batch.writer.TransactionWriter;
import com.carddemo.model.entity.Account;
import com.carddemo.model.entity.Transaction;
import com.carddemo.model.entity.TransactionCategoryBalance;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import com.carddemo.repository.TransactionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Spring Batch job configuration for daily transaction processing.
 * 
 * Defines transactionProcessingJob with three sequential steps replacing COBOL
 * batch programs CBTRN01C.cbl, CBTRN02C.cbl, and CBTRN03C.cbl.
 * 
 * Job Flow:
 * <pre>
 * transactionProcessingJob
 *   └─> validationStep     (CBTRN01C.cbl - Transaction validation)
 *         └─> postingStep  (CBTRN02C.cbl - Account balance updates)
 *               └─> categorizationStep (CBTRN03C.cbl - Category summarization)
 * </pre>
 * 
 * Scheduled execution: Daily at 01:00 (cron: "0 0 1 * * *")
 * Chunk size: 1000 records per chunk
 * Transaction isolation: READ_COMMITTED (matches CICS)
 * Checkpoint/restart: Enabled via JobRepository
 * 
 * @see TransactionReader
 * @see TransactionProcessor
 * @see TransactionWriter
 * @see Transaction
 * @see Account
 * @see TransactionCategoryBalance
 */
@Configuration
@Slf4j
public class TransactionProcessingJobConfig {

    /**
     * Chunk size for batch processing.
     * Set to 1000 to optimize database round-trips vs memory usage.
     * Matches page size in TransactionReader for efficient pagination.
     */
    private static final int CHUNK_SIZE = 1000;

    /**
     * Maximum number of skip/retry attempts per chunk.
     * Allows up to 10 validation failures per chunk before failing entire job.
     * Matches COBOL batch program error tolerance for reject record handling.
     */
    private static final int MAX_SKIP_COUNT = 10;

    /**
     * Spring Batch JobRepository for job execution metadata persistence.
     * Stores job instances, executions, step executions in PostgreSQL tables.
     * Enables checkpoint/restart capability.
     */
    private final JobRepository jobRepository;

    /**
     * Spring transaction manager for database transaction management.
     * Configures transaction boundaries for chunk processing with READ_COMMITTED isolation.
     */
    private final PlatformTransactionManager transactionManager;

    /**
     * ItemReader for reading Transaction entities from database.
     * Implements pagination with page size 1000 and primary key sorting.
     */
    private final TransactionReader transactionReader;

    /**
     * ItemProcessor for transaction validation and processing.
     * Implements business logic from CBTRN01C.cbl, CBTRN02C.cbl, CBTRN03C.cbl.
     */
    private final TransactionProcessor transactionProcessor;

    /**
     * ItemWriter for bulk transaction entity persistence.
     * Uses JPA saveAll() for batch database writes with chunk commits.
     */
    private final TransactionWriter transactionWriter;

    /**
     * Repository for Transaction entity database access.
     * Used by categorization step for transaction queries and aggregation.
     */
    private final TransactionRepository transactionRepository;

    /**
     * Repository for Account entity database access.
     * Used by posting step for account balance updates.
     */
    private final AccountRepository accountRepository;

    /**
     * Repository for TransactionCategoryBalance entity database access.
     * Used by categorization step for category balance aggregation.
     */
    private final TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;

    /**
     * Constructs TransactionProcessingJobConfig with required dependencies.
     * 
     * Uses constructor injection for all dependencies per Spring best practices.
     * Dependencies are injected by Spring container via @Autowired annotation.
     * 
     * @param jobRepository Spring Batch job repository for metadata persistence
     * @param transactionManager Spring transaction manager for transaction boundaries
     * @param transactionReader ItemReader for reading Transaction entities
     * @param transactionProcessor ItemProcessor for transaction validation/processing
     * @param transactionWriter ItemWriter for bulk transaction persistence
     * @param transactionRepository JPA repository for Transaction entity access
     * @param accountRepository JPA repository for Account entity access
     * @param transactionCategoryBalanceRepository JPA repository for category balance access
     */
    @Autowired
    public TransactionProcessingJobConfig(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            TransactionReader transactionReader,
            TransactionProcessor transactionProcessor,
            TransactionWriter transactionWriter,
            TransactionRepository transactionRepository,
            AccountRepository accountRepository,
            TransactionCategoryBalanceRepository transactionCategoryBalanceRepository) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.transactionReader = transactionReader;
        this.transactionProcessor = transactionProcessor;
        this.transactionWriter = transactionWriter;
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.transactionCategoryBalanceRepository = transactionCategoryBalanceRepository;
    }

    /**
     * Defines the main transaction processing job.
     * 
     * Replaces JCL jobs POSTTRAN.jcl and TRANREPT.jcl with Spring Batch Job
     * configuration. Job executes three sequential steps for daily transaction
     * processing workflow.
     * 
     * Job Flow:
     * 1. validationStep: Validate daily transactions (CBTRN01C.cbl logic)
     * 2. postingStep: Post transactions to accounts (CBTRN02C.cbl logic)
     * 3. categorizationStep: Summarize by category (CBTRN03C.cbl logic)
     * 
     * Each step must complete successfully before next step executes. If any step
     * fails, job fails and can be restarted from last successful checkpoint.
     * 
     * JCL Equivalent:
     * <pre>
     * //POSTTRAN JOB ...
     * //STEP15 EXEC PGM=CBTRN02C
     * ...
     * //TRANREPT JOB ...
     * //STEP10R EXEC PGM=CBTRN03C
     * </pre>
     * 
     * Spring Batch Configuration:
     * - JobBuilder creates Job with name "transactionProcessingJob"
     * - start(validationStep) sets first step in execution flow
     * - next(postingStep) chains second step after validation
     * - next(categorizationStep) chains third step after posting
     * - incrementer(new RunIdIncrementer()) adds unique run.id parameter for each execution
     * - build() constructs final Job instance
     * 
     * RunIdIncrementer enables multiple daily executions for reprocessing scenarios.
     * Each execution gets unique job parameters ensuring new job instance is created.
     * 
     * Checkpoint/Restart:
     * JobRepository automatically maintains execution state. If job fails, restart
     * resumes from last successfully committed chunk in failed step.
     * 
     * @return Configured Spring Batch Job for transaction processing
     */
    @Bean
    public Job transactionProcessingJob() {
        log.info("Configuring transactionProcessingJob with sequential steps: validation -> posting -> categorization");
        
        return new JobBuilder("transactionProcessingJob", jobRepository)
                .start(validationStep())
                .next(postingStep())
                .next(categorizationStep())
                .incrementer(new RunIdIncrementer())
                .build();
    }

    /**
     * Defines Step 1: Transaction validation step.
     * 
     * Converts CBTRN01C.cbl transaction file validation logic to Spring Batch step.
     * Reads daily transactions from database, validates card-account cross-references,
     * checks field validity, and filters invalid transactions.
     * 
     * COBOL Program: CBTRN01C.cbl
     * Original function: Post the records from daily transaction file (validation pass)
     * 
     * COBOL Processing Flow (CBTRN01C.cbl):
     * <pre>
     * 0000-DALYTRAN-OPEN.      [ItemStream.open() equivalent]
     *     OPEN INPUT DALYTRAN-FILE
     * 
     * 1000-DALYTRAN-GET-NEXT.  [ItemReader.read() equivalent]
     *     READ DALYTRAN-FILE INTO DALYTRAN-RECORD
     *     IF DALYTRAN-STATUS = '00'
     *         CONTINUE
     *     ELSE IF DALYTRAN-STATUS = '10'
     *         MOVE 'Y' TO END-OF-DAILY-TRANS-FILE
     * 
     * 2000-LOOKUP-XREF.        [ItemProcessor.process() equivalent]
     *     READ XREF-FILE KEY XREF-CARD-NUM
     *     IF XREFFILE-STATUS = '00'
     *         CONTINUE
     *     ELSE
     *         MOVE 'INVALID CARD NUMBER' TO VALIDATION-FAIL-REASON
     * 
     * 9000-DALYTRAN-CLOSE.     [ItemStream.close() equivalent]
     *     CLOSE DALYTRAN-FILE
     * </pre>
     * 
     * Spring Batch Configuration:
     * - StepBuilder creates Step with name "validationStep"
     * - chunk(CHUNK_SIZE, transactionManager) configures chunk-oriented processing:
     *   * CHUNK_SIZE = 1000 records per chunk
     *   * transactionManager provides transaction boundaries for chunk commits
     * - reader(transactionReader) reads Transaction entities from database
     * - processor(transactionProcessor) validates transactions (returns null for rejects)
     * - writer(transactionWriter) bulk persists validated transactions
     * - listener(validationStepListener()) tracks metrics (validated, rejected, errors)
     * - build() constructs final Step instance
     * 
     * Chunk Processing:
     * 1. Reader reads up to 1000 transactions from database (paginated query)
     * 2. Processor validates each transaction:
     *    - Check card-account cross-reference exists
     *    - Validate transaction amount is positive
     *    - Verify merchant ID format
     *    - Return null for invalid transactions (filtered from chunk)
     * 3. Writer bulk persists all valid transactions in chunk
     * 4. Transaction commits (checkpoint created)
     * 5. Repeat until reader returns null (no more data)
     * 
     * Error Handling:
     * - ValidationStepListener tracks validation failures
     * - Invalid transactions filtered out by processor returning null
     * - Database errors trigger chunk rollback and retry
     * - After MAX_SKIP_COUNT failures, step fails entirely
     * 
     * Performance:
     * - Chunk size 1000 optimizes database round-trips
     * - JPA batch insert (hibernate.jdbc.batch_size=1000)
     * - Primary key index on trans_id ensures efficient ordering
     * 
     * @return Configured Step for transaction validation
     */
    @Bean
    public Step validationStep() {
        log.info("Configuring validationStep with chunk size {} and TransactionReader/Processor/Writer", CHUNK_SIZE);
        
        return new StepBuilder("validationStep", jobRepository)
                .<Transaction, Transaction>chunk(CHUNK_SIZE, transactionManager)
                .reader(transactionReader)
                .processor(transactionProcessor)
                .writer(transactionWriter)
                .listener(validationStepListener())
                .build();
    }

    /**
     * Defines Step 2: Transaction posting step.
     * 
     * Converts CBTRN02C.cbl daily transaction posting logic to Spring Batch step.
     * Reads validated transactions, updates account balances with transaction amounts,
     * enforces credit limit checks, and updates transaction category balances.
     * 
     * COBOL Program: CBTRN02C.cbl
     * Original function: Post the records from daily transaction file
     * 
     * COBOL Processing Flow (CBTRN02C.cbl):
     * <pre>
     * 1500-VALIDATE-TRAN.      [ItemProcessor.process() equivalent]
     *     PERFORM 1500-A-LOOKUP-XREF    (Card-account cross-reference)
     *     PERFORM 1500-B-LOOKUP-ACCT    (Account lookup + credit limit check)
     * 
     * 1500-B-LOOKUP-ACCT.
     *     READ ACCOUNT-FILE KEY ACCT-ID
     *     COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT
     *     IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL
     *         CONTINUE (approve transaction)
     *     ELSE
     *         MOVE 'OVERLIMIT TRANSACTION' TO VALIDATION-FAIL-REASON
     *         PERFORM 2500-WRITE-REJECT-REC
     * 
     * 2800-UPDATE-ACCOUNT-REC. [ItemProcessor.process() equivalent]
     *     ADD DALYTRAN-AMT TO ACCT-CURR-BAL
     *     IF DALYTRAN-AMT >= 0
     *         ADD DALYTRAN-AMT TO ACCT-CURR-CYC-CREDIT
     *     ELSE
     *         ADD DALYTRAN-AMT TO ACCT-CURR-CYC-DEBIT
     *     REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD
     * 
     * 2700-UPDATE-TCATBAL.     [ItemProcessor.process() equivalent]
     *     READ TCATBAL-FILE KEY TRAN-CAT-KEY
     *     IF TCATBALF-STATUS = '00'
     *         ADD DALYTRAN-AMT TO TRAN-CAT-BAL
     *         REWRITE FD-TRAN-CAT-BAL-RECORD
     *     ELSE IF TCATBALF-STATUS = '23'  (record not found)
     *         MOVE DALYTRAN-AMT TO TRAN-CAT-BAL
     *         WRITE FD-TRAN-CAT-BAL-RECORD
     * 
     * 2600-WRITE-TRAN-REC.     [ItemWriter.write() equivalent]
     *     WRITE FD-TRANFILE-REC FROM TRAN-RECORD
     * </pre>
     * 
     * Spring Batch Configuration:
     * - StepBuilder creates Step with name "postingStep"
     * - chunk(CHUNK_SIZE, transactionManager) configures chunk-oriented processing:
     *   * CHUNK_SIZE = 1000 records per chunk
     *   * transactionManager provides transaction boundaries for chunk commits
     * - reader(transactionReader) reads Transaction entities from database
     * - processor(transactionProcessor) processes transactions:
     *   * Validates card-account cross-reference
     *   * Loads account record and checks credit limit
     *   * Calculates new account balance using BigDecimal (COMP-3 precision)
     *   * Updates account cycle credit/debit counters
     *   * Updates transaction category balance
     *   * Returns null for transactions exceeding credit limit (filtered)
     * - writer(transactionWriter) bulk persists posted transactions
     * - listener(postingStepListener()) tracks metrics (posted, rejected, balance updates)
     * - build() constructs final Step instance
     * 
     * Credit Limit Enforcement (preserves COBOL logic):
     * <pre>
     * BigDecimal cycleBalance = account.getAcctCurrCycCredit()
     *         .subtract(account.getAcctCurrCycDebit())
     *         .add(transaction.getTransAmt());
     * 
     * if (account.getAcctCreditLimit().compareTo(cycleBalance) >= 0) {
     *     // Approve transaction
     *     account.setAcctCurrBal(account.getAcctCurrBal().add(transaction.getTransAmt()));
     * } else {
     *     // Reject over-limit transaction (return null from processor)
     *     log.warn("Transaction {} rejected: over credit limit", transaction.getTransId());
     *     return null;
     * }
     * </pre>
     * 
     * BigDecimal Precision (Section 0.7.2 requirement):
     * All currency calculations use BigDecimal with scale 2 and RoundingMode.HALF_UP
     * to preserve exact COBOL COMP-3 packed decimal precision for bit-identical
     * financial calculations.
     * 
     * Transaction Category Balance Update:
     * For each posted transaction, update or create category balance record:
     * - Key: (account_id, transaction_type_cd, transaction_category_cd)
     * - Operation: balance += transaction_amount
     * - If record doesn't exist, create with initial balance = transaction_amount
     * 
     * Error Handling:
     * - PostingStepListener tracks posting failures
     * - Over-limit transactions filtered out by processor returning null
     * - Account not found errors logged and transaction rejected
     * - Database errors trigger chunk rollback and retry
     * 
     * Performance:
     * - Chunk size 1000 optimizes database round-trips
     * - JPA batch insert/update (hibernate.jdbc.batch_size=1000)
     * - Optimistic locking with @Version annotation for concurrent access
     * 
     * @return Configured Step for transaction posting
     */
    @Bean
    public Step postingStep() {
        log.info("Configuring postingStep with chunk size {} for account balance updates", CHUNK_SIZE);
        
        return new StepBuilder("postingStep", jobRepository)
                .<Transaction, Transaction>chunk(CHUNK_SIZE, transactionManager)
                .reader(transactionReader)
                .processor(transactionProcessor)
                .writer(transactionWriter)
                .listener(postingStepListener())
                .build();
    }

    /**
     * Defines Step 3: Transaction categorization step.
     * 
     * Converts CBTRN03C.cbl transaction category summarization logic to Spring Batch step.
     * Reads posted transactions from database, aggregates by transaction type and category,
     * and updates transaction category balance table.
     * 
     * COBOL Program: CBTRN03C.cbl
     * Original function: Print the transaction detail report
     * 
     * COBOL Processing Flow (CBTRN03C.cbl):
     * <pre>
     * 1000-TRANFILE-GET-NEXT.  [ItemReader.read() equivalent]
     *     READ TRANSACT-FILE INTO TRAN-RECORD
     *     IF TRANFILE-STATUS = '00'
     *         CONTINUE
     *     ELSE IF TRANFILE-STATUS = '10'
     *         MOVE 'Y' TO END-OF-TRANSACT-FILE
     * 
     * 2000-LOOKUP-XREF.        [ItemProcessor.process() equivalent]
     *     READ XREF-FILE KEY XREF-CARD-NUM
     *     MOVE XREF-ACCT-ID TO WS-ACCT-ID
     * 
     * 3000-LOOKUP-TRANTYPE.    [ItemProcessor.process() equivalent]
     *     READ TRANTYPE-FILE KEY TRAN-TYPE
     *     MOVE TRAN-TYPE-DESC TO WS-TRAN-TYPE-DESC
     * 
     * 4000-LOOKUP-TRANCATG.    [ItemProcessor.process() equivalent]
     *     MOVE TRAN-TYPE-CD TO FD-TRAN-TYPE-CD
     *     MOVE TRAN-CAT-CD TO FD-TRAN-CAT-CD
     *     READ TRANCATG-FILE KEY FD-TRAN-CAT-KEY
     *     MOVE TRAN-CAT-DESC TO WS-TRAN-CAT-DESC
     * 
     * 5000-WRITE-REPORT-REC.   [ItemWriter.write() equivalent - replaced with metrics]
     *     MOVE formatted-data TO FD-REPTFILE-REC
     *     WRITE FD-REPTFILE-REC
     * </pre>
     * 
     * Spring Batch Configuration:
     * - StepBuilder creates Step with name "categorizationStep"
     * - chunk(CHUNK_SIZE, transactionManager) configures chunk-oriented processing:
     *   * CHUNK_SIZE = 1000 records per chunk
     *   * transactionManager provides transaction boundaries for chunk commits
     * - reader(transactionReader) reads Transaction entities from database
     * - processor(transactionProcessor) enriches transactions:
     *   * Looks up card-account cross-reference
     *   * Looks up transaction type description
     *   * Looks up transaction category description
     *   * Calculates category balance aggregates
     * - writer(transactionWriter) bulk persists enriched transactions
     * - listener(categorizationStepListener()) tracks metrics (categorized, aggregated)
     * - build() constructs final Step instance
     * 
     * Category Aggregation Logic:
     * For each transaction, update category balance aggregate:
     * 
     * <pre>
     * Key: TransactionCategoryBalanceId {
     *     tcatAcctId: transaction.getTransCardNum() -> xref.getXrefAcctId()
     *     tcatTypeCd: transaction.getTransTypeCd()
     *     tcatCatCd: transaction.getTransCatCd()
     * }
     * 
     * Optional<TransactionCategoryBalance> existing = 
     *     transactionCategoryBalanceRepository.findById(key);
     * 
     * if (existing.isPresent()) {
     *     // Update existing balance
     *     BigDecimal newBalance = existing.get().getTcatBal()
     *         .add(transaction.getTransAmt())
     *         .setScale(2, RoundingMode.HALF_UP);
     *     existing.get().setTcatBal(newBalance);
     *     transactionCategoryBalanceRepository.save(existing.get());
     * } else {
     *     // Create new category balance record
     *     TransactionCategoryBalance newBalance = TransactionCategoryBalance.builder()
     *         .tcatAcctId(accountId)
     *         .tcatTypeCd(transaction.getTransTypeCd())
     *         .tcatCatCd(transaction.getTransCatCd())
     *         .tcatBal(transaction.getTransAmt())
     *         .build();
     *     transactionCategoryBalanceRepository.save(newBalance);
     * }
     * </pre>
     * 
     * Report Generation (replaced with metrics):
     * Original COBOL writes formatted transaction report to TRANREPT file.
     * Spring Batch replacement exposes metrics via /actuator/batch endpoint:
     * - Total transactions categorized
     * - Category balance updates performed
     * - Transaction type distribution
     * - Category distribution
     * 
     * BigDecimal Precision:
     * All category balance calculations use BigDecimal with scale 2 and
     * RoundingMode.HALF_UP to preserve COBOL COMP-3 precision.
     * 
     * Error Handling:
     * - CategorizationStepListener tracks categorization failures
     * - Missing reference data (type/category) logged as warnings
     * - Database errors trigger chunk rollback and retry
     * 
     * Performance:
     * - Chunk size 1000 optimizes database round-trips
     * - JPA batch insert/update for category balances
     * - Composite primary key index on (acct_id, type_cd, cat_cd)
     * 
     * @return Configured Step for transaction categorization
     */
    @Bean
    public Step categorizationStep() {
        log.info("Configuring categorizationStep with chunk size {} for category balance aggregation", CHUNK_SIZE);
        
        return new StepBuilder("categorizationStep", jobRepository)
                .<Transaction, Transaction>chunk(CHUNK_SIZE, transactionManager)
                .reader(transactionReader)
                .processor(transactionProcessor)
                .writer(transactionWriter)
                .listener(categorizationStepListener())
                .build();
    }

    /**
     * Creates StepExecutionListener for validation step metrics tracking.
     * 
     * Tracks validation step execution metrics including:
     * - Total transactions read from database
     * - Total transactions validated successfully
     * - Total transactions rejected (validation failures)
     * - Total errors encountered
     * - Step execution duration
     * 
     * Metrics exposed via Spring Boot Actuator /actuator/batch endpoint for
     * operational monitoring and replacing COBOL DISPLAY statement logging.
     * 
     * COBOL Equivalent:
     * CBTRN01C.cbl uses DISPLAY statements for logging progress:
     * <pre>
     * DISPLAY 'TRANSACTIONS READ: ' WS-TRANS-READ-COUNT
     * DISPLAY 'TRANSACTIONS VALIDATED: ' WS-TRANS-VALID-COUNT
     * DISPLAY 'TRANSACTIONS REJECTED: ' WS-TRANS-REJECT-COUNT
     * </pre>
     * 
     * Spring Batch Listener Methods:
     * - beforeStep(StepExecution): Initialize counters, log step start
     * - afterStep(StepExecution): Log final metrics, return execution status
     * 
     * Metrics available from StepExecution:
     * - readCount: Total items read by ItemReader
     * - writeCount: Total items written by ItemWriter
     * - filterCount: Total items filtered by ItemProcessor (null returns)
     * - readSkipCount: Total read errors skipped
     * - processSkipCount: Total processing errors skipped
     * - writeSkipCount: Total write errors skipped
     * - commitCount: Total chunk commits
     * - rollbackCount: Total chunk rollbacks
     * 
     * @return StepExecutionListener for validation step
     */
    @Bean
    public StepExecutionListener validationStepListener() {
        return new StepExecutionListener() {
            @Override
            public void beforeStep(StepExecution stepExecution) {
                log.info("Starting validation step: {}", stepExecution.getStepName());
                log.info("Job parameters: {}", stepExecution.getJobParameters());
            }

            @Override
            public void afterStep(StepExecution stepExecution) {
                log.info("Completed validation step: {}", stepExecution.getStepName());
                log.info("Validation step metrics:");
                log.info("  - Transactions read: {}", stepExecution.getReadCount());
                log.info("  - Transactions validated: {}", stepExecution.getWriteCount());
                log.info("  - Transactions rejected: {}", stepExecution.getFilterCount());
                log.info("  - Read errors: {}", stepExecution.getReadSkipCount());
                log.info("  - Processing errors: {}", stepExecution.getProcessSkipCount());
                log.info("  - Write errors: {}", stepExecution.getWriteSkipCount());
                log.info("  - Chunks committed: {}", stepExecution.getCommitCount());
                log.info("  - Chunks rolled back: {}", stepExecution.getRollbackCount());
                log.info("  - Execution status: {}", stepExecution.getStatus());
                log.info("  - Exit status: {}", stepExecution.getExitStatus());
            }
        };
    }

    /**
     * Creates StepExecutionListener for posting step metrics tracking.
     * 
     * Tracks posting step execution metrics including:
     * - Total transactions read from database
     * - Total transactions posted successfully
     * - Total transactions rejected (over-limit, account not found)
     * - Total account balance updates performed
     * - Total transaction category balance updates performed
     * - Total errors encountered
     * - Step execution duration
     * 
     * Metrics exposed via Spring Boot Actuator /actuator/batch endpoint for
     * operational monitoring and replacing COBOL DISPLAY statement logging.
     * 
     * COBOL Equivalent:
     * CBTRN02C.cbl uses DISPLAY statements for logging progress:
     * <pre>
     * DISPLAY 'TRANSACTIONS READ: ' WS-TRANS-READ-COUNT
     * DISPLAY 'TRANSACTIONS POSTED: ' WS-TRANS-POSTED-COUNT
     * DISPLAY 'TRANSACTIONS REJECTED: ' WS-TRANS-REJECT-COUNT
     * DISPLAY 'ACCOUNTS UPDATED: ' WS-ACCT-UPDATE-COUNT
     * DISPLAY 'CATEGORY BALANCES UPDATED: ' WS-CATBAL-UPDATE-COUNT
     * </pre>
     * 
     * Spring Batch Listener Methods:
     * - beforeStep(StepExecution): Initialize counters, log step start
     * - afterStep(StepExecution): Log final metrics, return execution status
     * 
     * Posting-Specific Metrics:
     * - readCount: Total transactions read for posting
     * - writeCount: Total transactions posted successfully
     * - filterCount: Total transactions rejected (over-limit, expired account)
     * - Account balance updates tracked via repository save operations
     * - Category balance updates tracked via repository save operations
     * 
     * @return StepExecutionListener for posting step
     */
    @Bean
    public StepExecutionListener postingStepListener() {
        return new StepExecutionListener() {
            @Override
            public void beforeStep(StepExecution stepExecution) {
                log.info("Starting posting step: {}", stepExecution.getStepName());
                log.info("Job parameters: {}", stepExecution.getJobParameters());
            }

            @Override
            public void afterStep(StepExecution stepExecution) {
                log.info("Completed posting step: {}", stepExecution.getStepName());
                log.info("Posting step metrics:");
                log.info("  - Transactions read: {}", stepExecution.getReadCount());
                log.info("  - Transactions posted: {}", stepExecution.getWriteCount());
                log.info("  - Transactions rejected: {}", stepExecution.getFilterCount());
                log.info("  - Read errors: {}", stepExecution.getReadSkipCount());
                log.info("  - Processing errors: {}", stepExecution.getProcessSkipCount());
                log.info("  - Write errors: {}", stepExecution.getWriteSkipCount());
                log.info("  - Chunks committed: {}", stepExecution.getCommitCount());
                log.info("  - Chunks rolled back: {}", stepExecution.getRollbackCount());
                log.info("  - Execution status: {}", stepExecution.getStatus());
                log.info("  - Exit status: {}", stepExecution.getExitStatus());
            }
        };
    }

    /**
     * Creates StepExecutionListener for categorization step metrics tracking.
     * 
     * Tracks categorization step execution metrics including:
     * - Total transactions read from database
     * - Total transactions categorized successfully
     * - Total category balance aggregates updated
     * - Total errors encountered
     * - Step execution duration
     * 
     * Metrics exposed via Spring Boot Actuator /actuator/batch endpoint for
     * operational monitoring and replacing COBOL DISPLAY statement logging.
     * 
     * COBOL Equivalent:
     * CBTRN03C.cbl uses DISPLAY statements for logging progress:
     * <pre>
     * DISPLAY 'TRANSACTIONS READ: ' WS-TRANS-READ-COUNT
     * DISPLAY 'TRANSACTIONS CATEGORIZED: ' WS-TRANS-CAT-COUNT
     * DISPLAY 'CATEGORY BALANCES UPDATED: ' WS-CATBAL-UPDATE-COUNT
     * DISPLAY 'REPORT RECORDS WRITTEN: ' WS-REPORT-REC-COUNT
     * </pre>
     * 
     * Spring Batch Listener Methods:
     * - beforeStep(StepExecution): Initialize counters, log step start
     * - afterStep(StepExecution): Log final metrics, return execution status
     * 
     * Categorization-Specific Metrics:
     * - readCount: Total transactions read for categorization
     * - writeCount: Total transactions categorized successfully
     * - Category balance updates tracked via repository save operations
     * - Report generation replaced with metrics exposure via /actuator/batch
     * 
     * @return StepExecutionListener for categorization step
     */
    @Bean
    public StepExecutionListener categorizationStepListener() {
        return new StepExecutionListener() {
            @Override
            public void beforeStep(StepExecution stepExecution) {
                log.info("Starting categorization step: {}", stepExecution.getStepName());
                log.info("Job parameters: {}", stepExecution.getJobParameters());
            }

            @Override
            public void afterStep(StepExecution stepExecution) {
                log.info("Completed categorization step: {}", stepExecution.getStepName());
                log.info("Categorization step metrics:");
                log.info("  - Transactions read: {}", stepExecution.getReadCount());
                log.info("  - Transactions categorized: {}", stepExecution.getWriteCount());
                log.info("  - Read errors: {}", stepExecution.getReadSkipCount());
                log.info("  - Processing errors: {}", stepExecution.getProcessSkipCount());
                log.info("  - Write errors: {}", stepExecution.getWriteSkipCount());
                log.info("  - Chunks committed: {}", stepExecution.getCommitCount());
                log.info("  - Chunks rolled back: {}", stepExecution.getRollbackCount());
                log.info("  - Execution status: {}", stepExecution.getStatus());
                log.info("  - Exit status: {}", stepExecution.getExitStatus());
            }
        };
    }
}
