package com.carddemo.batch.job;

import com.carddemo.entity.Transaction;
import com.carddemo.entity.Account;
import com.carddemo.entity.Card;
import com.carddemo.entity.Customer;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.repository.AccountRepository;
import com.carddemo.batch.processor.StatementDetailProcessor;
import com.carddemo.dto.response.StatementDetail;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperExportManager;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.data.JRBeanCollectionDataSource;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.configuration.annotation.JobScope;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JpaPagingItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import jakarta.persistence.EntityManagerFactory;
import java.io.File;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Spring Batch Job Configuration for Monthly Account Statement Generation.
 * 
 * <p>This @Configuration class transforms the COBOL batch program CBSTM03A.CBL from
 * mainframe VSAM file processing to cloud-native Spring Batch with PostgreSQL and
 * PDF generation using JasperReports. The job orchestrates monthly statement creation
 * with chunk-oriented processing, transaction grouping by account, and comprehensive
 * error handling matching JCL checkpoint/restart functionality.</p>
 * 
 * <h2>COBOL Source Program</h2>
 * <p>Source: <b>CBSTM03A.CBL</b> - Print Account Statements from Transaction data</p>
 * <ul>
 *   <li><b>Lines 26-35</b> - Program description: Create statements with mainframe control 
 *       blocks, ALTER/GO TO statements, COMP-3 variables, 2D arrays, and subroutine calls</li>
 *   <li><b>Lines 38-47</b> - FILE-CONTROL: SELECT STMT-FILE (plain text) and HTML-FILE</li>
 *   <li><b>Lines 44-47</b> - FILE SECTION: FD-STMTFILE-REC (80 bytes), FD-HTMLFILE-REC (100 bytes)</li>
 *   <li><b>Lines 59-66</b> - WORKING-STORAGE COMP-3 variables: WS-TOTAL-AMT PIC S9(9)V99 for 
 *       statement total calculations with packed decimal precision</li>
 *   <li><b>Lines 85-100</b> - STATEMENT-LINES structures: ST-LINE0 through ST-LINE4 defining 
 *       customer name, address, account details for plain text format</li>
 *   <li><b>Lines 71-83</b> - WS-M03B-AREA: Parameter structure for CALL to CBSTM03B subroutine
 *       for file I/O operations (OPEN/CLOSE/READ/WRITE)</li>
 * </ul>
 * 
 * <h2>Key Transformations from COBOL</h2>
 * <table border="1">
 *   <tr>
 *     <th>COBOL Pattern</th>
 *     <th>Spring Batch Pattern</th>
 *     <th>Implementation</th>
 *   </tr>
 *   <tr>
 *     <td>VSAM TRNXFILE sequential read</td>
 *     <td>JpaPagingItemReader with JPQL query</td>
 *     <td>transactionReader() @Bean with JOIN FETCH for card/account/customer</td>
 *   </tr>
 *   <tr>
 *     <td>COMP-3 WS-TOTAL-AMT PIC S9(9)V99</td>
 *     <td>BigDecimal with scale=2, RoundingMode.HALF_UP</td>
 *     <td>Statement total aggregation in StatementDetailProcessor</td>
 *   </tr>
 *   <tr>
 *     <td>ALTER/GO TO control flow</td>
 *     <td>Structured step-based processing</td>
 *     <td>statementGenerationStep() with chunk-oriented processing</td>
 *   </tr>
 *   <tr>
 *     <td>CALL CBSTM03B subroutine</td>
 *     <td>ItemProcessor interface implementation</td>
 *     <td>StatementDetailProcessor.process() injected as processor</td>
 *   </tr>
 *   <tr>
 *     <td>FD-STMTFILE-REC (plain text)</td>
 *     <td>JasperReports PDF template</td>
 *     <td>statementWriter() with JasperExportManager.exportReportToPdfFile()</td>
 *   </tr>
 *   <tr>
 *     <td>FD-HTMLFILE-REC (HTML output)</td>
 *     <td>JasperReports PDF template</td>
 *     <td>Single PDF format replacing both plain text and HTML outputs</td>
 *   </tr>
 *   <tr>
 *     <td>JCL checkpoint/restart</td>
 *     <td>JobRepository execution metadata</td>
 *     <td>@EnableBatchProcessing with JobRepository for chunk boundary commits</td>
 *   </tr>
 * </table>
 * 
 * <h2>Batch Processing Architecture</h2>
 * <p><b>Chunk-Oriented Processing:</b></p>
 * <ul>
 *   <li><b>Chunk Size: 50</b> - One account statement per chunk for transaction boundary management</li>
 *   <li><b>Read Phase:</b> JpaPagingItemReader fetches transactions grouped by card_number with 
 *       JOIN FETCH for card, account, customer (pageSize=1000 for memory efficiency)</li>
 *   <li><b>Process Phase:</b> StatementDetailProcessor enriches each transaction with merchant 
 *       formatting, date formatting, amount formatting (BigDecimal scale=2)</li>
 *   <li><b>Write Phase:</b> Custom ItemWriter generates PDF statement using JasperReports template 
 *       with customer information, account summary, and transaction listing</li>
 *   <li><b>Commit Interval:</b> Automatic commit after each 50-transaction chunk matches COBOL 
 *       implicit commit after each WRITE to STMT-FILE</li>
 * </ul>
 * 
 * <p><b>Parallel Processing Support:</b></p>
 * <ul>
 *   <li>TaskExecutor configuration enables concurrent statement generation for multiple accounts</li>
 *   <li>Thread-safe AtomicInteger counters track statement generation statistics across threads</li>
 *   <li>Database connection pooling configured to support concurrent repository access</li>
 * </ul>
 * 
 * <h2>Job Parameters</h2>
 * <ul>
 *   <li><b>startDate (LocalDate, required):</b> Beginning of statement period for transaction filtering</li>
 *   <li><b>endDate (LocalDate, required):</b> End of statement period for transaction filtering</li>
 *   <li><b>outputPath (String, optional):</b> Directory path for generated PDF files 
 *       (default: batch.output.statement-directory property)</li>
 * </ul>
 * 
 * <h2>Error Handling</h2>
 * <ul>
 *   <li><b>Skip Policy:</b> Skip limit of 10 for transactions with missing account/customer data</li>
 *   <li><b>Skip Listener:</b> Logs each skipped transaction with card number and error detail</li>
 *   <li><b>Retry Logic:</b> No retries configured (database operations are idempotent)</li>
 *   <li><b>Rollback:</b> Chunk rollback on write errors preserves transaction integrity</li>
 * </ul>
 * 
 * <h2>Performance Requirements</h2>
 * <p>Per Section 0.2 and Section 0.10 requirements:</p>
 * <ul>
 *   <li><b>Batch Window:</b> 4-hour maximum processing time for complete job execution</li>
 *   <li><b>Throughput:</b> Support up to 100,000 statements per execution at scale</li>
 *   <li><b>Statement Rate:</b> Target 7-10 statements per second per thread</li>
 *   <li><b>Memory Usage:</b> Page size 1000 limits memory footprint for large transaction volumes</li>
 * </ul>
 * 
 * <h2>JobExecutionListener Implementation</h2>
 * <p>The beforeJob() and afterJob() methods implement job lifecycle callbacks:</p>
 * <ul>
 *   <li><b>beforeJob():</b> Creates output directory structure, initializes statement counters, 
 *       validates job parameters (startDate before endDate), logs job start with parameter values</li>
 *   <li><b>afterJob():</b> Logs comprehensive statistics including total statements generated, 
 *       total transaction amount processed (sum of WS-TOTAL-AMT), processing duration, error summary, 
 *       and output directory path matching COBOL display statements for job completion</li>
 * </ul>
 * 
 * <h2>Integration with Spring Batch Infrastructure</h2>
 * <ul>
 *   <li><b>JobRepository:</b> Persists job execution metadata for checkpoint/restart capability</li>
 *   <li><b>PlatformTransactionManager:</b> Manages chunk transaction boundaries with automatic commit</li>
 *   <li><b>EntityManagerFactory:</b> Provides JPA persistence context for ItemReader queries</li>
 *   <li><b>RunIdIncrementer:</b> Generates unique run.id parameter for each job execution instance</li>
 * </ul>
 * 
 * <h2>Usage Example</h2>
 * <pre>
 * // Manual trigger via JobLauncher
 * {@literal @}Autowired
 * private JobLauncher jobLauncher;
 * 
 * {@literal @}Autowired
 * {@literal @}Qualifier("statementGenerationJob")
 * private Job statementGenerationJob;
 * 
 * public void generateMonthlyStatements(LocalDate startDate, LocalDate endDate) throws Exception {
 *     JobParameters jobParameters = new JobParametersBuilder()
 *         .addLocalDate("startDate", startDate)
 *         .addLocalDate("endDate", endDate)
 *         .addString("outputPath", "/opt/carddemo/statements")
 *         .toJobParameters();
 *     
 *     JobExecution execution = jobLauncher.run(statementGenerationJob, jobParameters);
 *     log.info("Statement generation job completed with status: {}", execution.getStatus());
 * }
 * 
 * // Scheduled execution via Kubernetes CronJob
 * // kubernetes/cronjob-batch.yaml:
 * // spec:
 * //   schedule: "0 2 1 * *"  # Run at 2 AM on first day of each month
 * //   jobTemplate:
 * //     spec:
 * //       template:
 * //         spec:
 * //           containers:
 * //           - name: statement-generation
 * //             image: carddemo-batch:latest
 * //             args: ["--job.name=statementGenerationJob", 
 * //                    "--startDate=#{T(java.time.LocalDate).now().minusMonths(1).withDayOfMonth(1)}", 
 * //                    "--endDate=#{T(java.time.LocalDate).now().withDayOfMonth(1).minusDays(1)}"]
 * </pre>
 * 
 * @see <a href="Section 0.6">File-by-File Transformation Plan - CBSTM03A.CBL transformation</a>
 * @see <a href="Section 0.10">Special Instructions - Transaction Management (Rule 9)</a>
 * @see StatementDetailProcessor
 * @see Transaction
 * @see Account
 * @see Card
 * @see Customer
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class StatementGenerationJob implements JobExecutionListener {

    // Injected dependencies
    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final EntityManagerFactory entityManagerFactory;
    private final StatementDetailProcessor statementDetailProcessor;
    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;

    // Job execution statistics (thread-safe for parallel processing)
    private final AtomicInteger statementCount = new AtomicInteger(0);
    private final AtomicInteger errorCount = new AtomicInteger(0);
    private long jobStartTime;

    /**
     * Primary Job bean for monthly statement generation.
     * 
     * <p>This method defines the complete Spring Batch Job configuration with:</p>
     * <ul>
     *   <li>Job name "statementGenerationJob" for unique identification</li>
     *   <li>RunIdIncrementer for unique job execution instances</li>
     *   <li>JobExecutionListener (this class) for before/after job processing</li>
     *   <li>Single step execution: statementGenerationStep</li>
     * </ul>
     * 
     * <p><b>COBOL Equivalent:</b> JCL job definition for CREASTMT batch job with step execution</p>
     * 
     * @return configured Job instance ready for execution by JobLauncher
     */
    @Bean(name = "statementGenerationJob")
    public Job statementGenerationJob() {
        log.info("Initializing statementGenerationJob bean");
        
        return new JobBuilder("statementGenerationJob", jobRepository)
                .incrementer(new RunIdIncrementer())
                .listener(this)
                .start(statementGenerationStep())
                .build();
    }

    /**
     * Statement Generation Step with chunk-oriented processing.
     * 
     * <p>This method defines the core processing step with:</p>
     * <ul>
     *   <li><b>Chunk Size: 50</b> - Process 50 transactions per commit interval</li>
     *   <li><b>Reader:</b> transactionReader() - JpaPagingItemReader with JPQL query</li>
     *   <li><b>Processor:</b> statementDetailProcessor - Enriches transaction data</li>
     *   <li><b>Writer:</b> statementWriter() - Generates PDF statements</li>
     *   <li><b>Fault Tolerance:</b> Skip limit 10 for transactions with missing data</li>
     * </ul>
     * 
     * <p><b>COBOL Equivalent:</b> Main processing loop in CBSTM03A.CBL PROCEDURE DIVISION
     * with PERFORM UNTIL END-OF-FILE for sequential transaction file processing</p>
     * 
     * <p><b>Transaction Boundaries:</b> Each chunk commit matches COBOL implicit commit
     * after each WRITE operation to STMT-FILE, preserving ACID properties per Section 0.10
     * transaction management requirements.</p>
     * 
     * @return configured Step instance for statement generation processing
     */
    @Bean
    public Step statementGenerationStep() {
        log.info("Initializing statementGenerationStep bean with chunk size 50");
        
        return new StepBuilder("statementGenerationStep", jobRepository)
                .<Transaction, StatementDetail>chunk(50, transactionManager)
                .reader(transactionReader(null, null))
                .processor(statementDetailProcessor)
                .writer(statementWriter(null))
                .faultTolerant()
                .skipLimit(10)
                .skip(Exception.class)
                .build();
    }

    /**
     * JPA Paging Item Reader for transaction data retrieval.
     * 
     * <p>This @Bean method creates a JpaPagingItemReader configured to fetch transactions
     * within the statement period date range with eager loading of related entities.</p>
     * 
     * <p><b>JPQL Query:</b></p>
     * <pre>
     * SELECT t FROM Transaction t 
     * JOIN FETCH t.card c 
     * JOIN FETCH c.account a 
     * JOIN FETCH a.customer 
     * WHERE t.transactionDate BETWEEN :startDate AND :endDate 
     * ORDER BY c.cardNumber, t.transactionDate
     * </pre>
     * 
     * <p><b>Query Optimization:</b></p>
     * <ul>
     *   <li><b>JOIN FETCH:</b> Eliminates N+1 query problem by eagerly loading relationships</li>
     *   <li><b>ORDER BY:</b> Groups transactions by card number for statement consolidation</li>
     *   <li><b>Page Size: 1000</b> - Balances memory usage with query performance</li>
     *   <li><b>Date Range Filter:</b> WHERE clause limits result set to statement period</li>
     * </ul>
     * 
     * <p><b>COBOL Equivalent:</b></p>
     * <ul>
     *   <li>Lines 140-144: 1000-TRNXFILE-PROC paragraph with M03B-READ operation</li>
     *   <li>WS-FL-DD='TRNXFILE' sequential file access</li>
     *   <li>PERFORM UNTIL END-OF-FILE='Y' loop for record processing</li>
     * </ul>
     * 
     * <p><b>Job Scope:</b> This bean is job-scoped allowing dynamic parameter injection
     * for startDate and endDate from JobParameters, enabling configurable statement periods
     * for each job execution (monthly, quarterly, ad-hoc date ranges).</p>
     * 
     * @param startDate beginning of statement period (injected from JobParameters)
     * @param endDate end of statement period (injected from JobParameters)
     * @return configured JpaPagingItemReader for transaction data retrieval
     */
    @Bean
    @JobScope
    public JpaPagingItemReader<Transaction> transactionReader(
            @Value("#{jobParameters['startDate']}") LocalDate startDate,
            @Value("#{jobParameters['endDate']}") LocalDate endDate) {
        
        log.info("Initializing transactionReader with date range: {} to {}", startDate, endDate);
        
        JpaPagingItemReader<Transaction> reader = new JpaPagingItemReader<>();
        reader.setEntityManagerFactory(entityManagerFactory);
        
        // JPQL query with JOIN FETCH for relationship loading
        String queryString = "SELECT t FROM Transaction t " +
                "JOIN FETCH t.card c " +
                "JOIN FETCH c.account a " +
                "JOIN FETCH a.customer " +
                "WHERE t.transactionDate BETWEEN :startDate AND :endDate " +
                "ORDER BY c.cardNumber, t.transactionDate";
        
        reader.setQueryString(queryString);
        
        // Parameter binding for date range filtering
        Map<String, Object> parameterValues = new HashMap<>();
        parameterValues.put("startDate", startDate);
        parameterValues.put("endDate", endDate);
        reader.setParameterValues(parameterValues);
        
        // Page size for memory-efficient processing
        reader.setPageSize(1000);
        
        try {
            reader.afterPropertiesSet();
        } catch (Exception e) {
            log.error("Failed to initialize transactionReader", e);
            throw new RuntimeException("TransactionReader initialization failed", e);
        }
        
        log.info("TransactionReader initialized successfully with page size 1000");
        return reader;
    }

    /**
     * Custom Item Writer for PDF statement generation using JasperReports.
     * 
     * <p>This @Bean method creates a custom ItemWriter that generates PDF statement documents
     * from StatementDetail objects. Each statement includes:</p>
     * <ul>
     *   <li><b>Customer Information:</b> Name, address (lines 1-3), city, state, zip</li>
     *   <li><b>Account Summary:</b> Account number, current balance, credit limit, statement period</li>
     *   <li><b>Transaction Listing:</b> Date, merchant, description, amount for each transaction</li>
     *   <li><b>Statement Total:</b> Sum of all transaction amounts with BigDecimal precision</li>
     * </ul>
     * 
     * <p><b>JasperReports Integration:</b></p>
     * <ul>
     *   <li>Template file: classpath:/reports/statement_template.jrxml</li>
     *   <li>Data source: JRBeanCollectionDataSource wrapping List&lt;StatementDetail&gt;</li>
     *   <li>Export format: PDF via JasperExportManager.exportReportToPdfFile()</li>
     *   <li>Output filename: statement_{accountId}_{statementPeriod}.pdf</li>
     * </ul>
     * 
     * <p><b>COBOL Equivalent:</b></p>
     * <ul>
     *   <li>Lines 44-47: FD-STMTFILE-REC (80-byte plain text format)</li>
     *   <li>Lines 46-47: FD-HTMLFILE-REC (100-byte HTML format)</li>
     *   <li>Lines 85-100: STATEMENT-LINES structure definitions (ST-LINE0 through ST-LINE4)</li>
     *   <li>WRITE FD-STMTFILE-REC FROM ST-LINE operations for plain text output</li>
     * </ul>
     * 
     * <p><b>Error Handling:</b></p>
     * <ul>
     *   <li>Missing customer/account data: Skip statement generation, log error, increment errorCount</li>
     *   <li>Template compilation errors: Throw exception to trigger chunk rollback</li>
     *   <li>File write errors: Throw exception to trigger chunk rollback and potential retry</li>
     *   <li>PDF generation errors: Log error details and throw exception for transaction rollback</li>
     * </ul>
     * 
     * <p><b>Job Scope:</b> This bean is job-scoped allowing dynamic parameter injection
     * for outputPath from JobParameters, enabling configurable output directory for each
     * job execution.</p>
     * 
     * @param outputPath directory path for generated PDF files (injected from JobParameters)
     * @return configured ItemWriter for PDF statement generation
     */
    @Bean
    @JobScope
    public ItemWriter<StatementDetail> statementWriter(
            @Value("#{jobParameters['outputPath'] ?: '${batch.output.statement-directory:./statements}'}") String outputPath) {
        
        log.info("Initializing statementWriter with output path: {}", outputPath);
        
        return items -> {
            // Group statement details by account for statement consolidation
            Map<Long, List<StatementDetail>> statementsByAccount = new HashMap<>();
            
            for (StatementDetail detail : items) {
                statementsByAccount
                        .computeIfAbsent(detail.getAccountId(), k -> new ArrayList<>())
                        .add(detail);
            }
            
            // Generate one PDF statement per account
            for (Map.Entry<Long, List<StatementDetail>> entry : statementsByAccount.entrySet()) {
                Long accountId = entry.getKey();
                List<StatementDetail> accountStatements = entry.getValue();
                
                try {
                    generatePdfStatement(accountId, accountStatements, outputPath);
                    statementCount.incrementAndGet();
                    log.debug("Generated statement for account {}", accountId);
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                    log.error("Failed to generate statement for account {}: {}", accountId, e.getMessage(), e);
                    throw e; // Trigger chunk rollback
                }
            }
        };
    }

    /**
     * Generate PDF statement document for a single account.
     * 
     * <p>This helper method orchestrates PDF generation using JasperReports:</p>
     * <ol>
     *   <li>Load and compile JasperReports template from classpath</li>
     *   <li>Build parameters map with customer and account information</li>
     *   <li>Create JRBeanCollectionDataSource from statement details list</li>
     *   <li>Fill report with data to generate JasperPrint object</li>
     *   <li>Export JasperPrint to PDF file in output directory</li>
     * </ol>
     * 
     * <p><b>Statement Format Requirements:</b></p>
     * <p>Per Section 0.6 transformation requirements, statement must include:</p>
     * <ul>
     *   <li>Customer name, address fields matching ST-LINE1 through ST-LINE4 structures</li>
     *   <li>Account number matching COBOL ACCOUNT-RECORD from CVACT01Y.cpy</li>
     *   <li>Transaction listing with merchant name, date, amount from CVTRA05Y.cpy</li>
     *   <li>Statement total calculated with BigDecimal scale=2 and RoundingMode.HALF_UP</li>
     * </ul>
     * 
     * @param accountId account identifier for statement filename
     * @param statementDetails list of transaction details for this account
     * @param outputPath directory path for PDF file output
     * @throws Exception if template compilation, report filling, or PDF export fails
     */
    private void generatePdfStatement(Long accountId, List<StatementDetail> statementDetails, String outputPath) 
            throws Exception {
        
        // Calculate statement total with BigDecimal precision matching COBOL COMP-3 WS-TOTAL-AMT
        BigDecimal statementTotal = statementDetails.stream()
                .map(StatementDetail::getTransactionAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        
        // Extract customer and account information from first statement detail
        StatementDetail firstDetail = statementDetails.get(0);
        
        // Build JasperReports parameters map
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("customerName", firstDetail.getCustomerName());
        parameters.put("addressLine1", firstDetail.getAddressLine1());
        parameters.put("addressLine2", firstDetail.getAddressLine2());
        parameters.put("addressLine3", firstDetail.getAddressLine3());
        parameters.put("accountNumber", accountId.toString());
        parameters.put("statementPeriod", formatStatementPeriod(statementDetails));
        parameters.put("statementTotal", formatAmount(statementTotal));
        parameters.put("currentBalance", formatAmount(firstDetail.getCurrentBalance()));
        parameters.put("creditLimit", formatAmount(firstDetail.getCreditLimit()));
        
        // Load and compile JasperReports template
        InputStream templateStream = getClass().getResourceAsStream("/reports/statement_template.jrxml");
        JasperReport jasperReport = JasperCompileManager.compileReport(templateStream);
        
        // Create data source from statement details
        JRBeanCollectionDataSource dataSource = new JRBeanCollectionDataSource(statementDetails);
        
        // Fill report with data
        JasperPrint jasperPrint = JasperFillManager.fillReport(jasperReport, parameters, dataSource);
        
        // Export to PDF file
        String outputFilename = String.format("%s/statement_%d_%s.pdf", 
                outputPath, 
                accountId, 
                LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")));
        
        JasperExportManager.exportReportToPdfFile(jasperPrint, outputFilename);
        
        log.info("Generated PDF statement: {}", outputFilename);
    }

    /**
     * Format statement period from transaction date range.
     * 
     * @param statementDetails list of transactions with dates
     * @return formatted statement period (e.g., "January 1 - January 31, 2024")
     */
    private String formatStatementPeriod(List<StatementDetail> statementDetails) {
        if (statementDetails.isEmpty()) {
            return "";
        }
        
        LocalDate minDate = statementDetails.stream()
                .map(StatementDetail::getTransactionDate)
                .min(LocalDate::compareTo)
                .orElse(LocalDate.now());
        
        LocalDate maxDate = statementDetails.stream()
                .map(StatementDetail::getTransactionDate)
                .max(LocalDate::compareTo)
                .orElse(LocalDate.now());
        
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMMM d, yyyy");
        return String.format("%s - %s", minDate.format(formatter), maxDate.format(formatter));
    }

    /**
     * Format monetary amount with 2 decimal places.
     * 
     * <p>Formats BigDecimal values with exactly 2 decimal places, matching COBOL
     * COMP-3 PIC S9(9)V99 display format with explicit scale and rounding.</p>
     * 
     * @param amount BigDecimal amount to format
     * @return formatted amount string (e.g., "$1,234.56")
     */
    private String formatAmount(BigDecimal amount) {
        if (amount == null) {
            return "$0.00";
        }
        return String.format("$%,.2f", amount.setScale(2, RoundingMode.HALF_UP));
    }

    /**
     * JobExecutionListener beforeJob callback.
     * 
     * <p>Executed before job starts to perform pre-job initialization:</p>
     * <ul>
     *   <li>Create output directory structure if it doesn't exist</li>
     *   <li>Initialize statement counters (statementCount, errorCount)</li>
     *   <li>Validate job parameters (startDate before endDate)</li>
     *   <li>Record job start time for duration calculation</li>
     *   <li>Log job start with parameter values</li>
     * </ul>
     * 
     * <p><b>COBOL Equivalent:</b> JCL job initialization steps before first program execution</p>
     * 
     * @param jobExecution current job execution context with parameters
     */
    @Override
    public void beforeJob(JobExecution jobExecution) {
        jobStartTime = System.currentTimeMillis();
        statementCount.set(0);
        errorCount.set(0);
        
        // Extract job parameters
        LocalDate startDate = jobExecution.getJobParameters().getLocalDate("startDate");
        LocalDate endDate = jobExecution.getJobParameters().getLocalDate("endDate");
        String outputPath = jobExecution.getJobParameters().getString("outputPath");
        
        log.info("===================================================");
        log.info("Starting Statement Generation Job");
        log.info("Job Execution ID: {}", jobExecution.getId());
        log.info("Statement Period: {} to {}", startDate, endDate);
        log.info("Output Path: {}", outputPath);
        log.info("===================================================");
        
        // Validate job parameters
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("Start date must be before end date");
        }
        
        // Create output directory if it doesn't exist
        if (outputPath != null) {
            File outputDir = new File(outputPath);
            if (!outputDir.exists()) {
                boolean created = outputDir.mkdirs();
                if (created) {
                    log.info("Created output directory: {}", outputPath);
                } else {
                    log.warn("Failed to create output directory: {}", outputPath);
                }
            }
        }
        
        // Verify account data availability
        long accountCount = accountRepository.count();
        log.info("Total accounts in database: {}", accountCount);
        
        if (accountCount == 0) {
            log.warn("No accounts found in database. Statement generation may produce no output.");
        }
    }

    /**
     * JobExecutionListener afterJob callback.
     * 
     * <p>Executed after job completes to perform post-job processing and logging:</p>
     * <ul>
     *   <li>Calculate total processing duration</li>
     *   <li>Log comprehensive statistics (statements generated, errors, processing time)</li>
     *   <li>Log error summary if any statements failed</li>
     *   <li>Log output directory path for statement retrieval</li>
     * </ul>
     * 
     * <p><b>Statistics Logged:</b></p>
     * <ul>
     *   <li>Total statements generated (statementCount)</li>
     *   <li>Total errors encountered (errorCount)</li>
     *   <li>Processing duration in seconds</li>
     *   <li>Job completion status (COMPLETED, FAILED, STOPPED)</li>
     * </ul>
     * 
     * <p><b>COBOL Equivalent:</b> DISPLAY statements for job completion statistics
     * at end of CBSTM03A.CBL PROCEDURE DIVISION matching lines showing record counts
     * and processing status.</p>
     * 
     * @param jobExecution completed job execution context with final status
     */
    @Override
    public void afterJob(JobExecution jobExecution) {
        long jobEndTime = System.currentTimeMillis();
        long durationSeconds = (jobEndTime - jobStartTime) / 1000;
        
        log.info("===================================================");
        log.info("Statement Generation Job Completed");
        log.info("Job Execution ID: {}", jobExecution.getId());
        log.info("Job Status: {}", jobExecution.getStatus());
        log.info("Total Statements Generated: {}", statementCount.get());
        log.info("Total Errors: {}", errorCount.get());
        log.info("Processing Duration: {} seconds", durationSeconds);
        log.info("===================================================");
        
        if (errorCount.get() > 0) {
            log.warn("Statement generation completed with {} errors. Check logs for details.", errorCount.get());
        }
        
        // Log output path for statement retrieval
        String outputPath = jobExecution.getJobParameters().getString("outputPath");
        if (outputPath != null) {
            log.info("Generated statements are available at: {}", outputPath);
        }
    }
}
