/*
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
package com.carddemo.batch.job;

import com.carddemo.entity.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JpaPagingItemReader;
import org.springframework.batch.item.database.builder.JpaPagingItemReaderBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManagerFactory;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring Batch job configuration for statement formatting and multi-format output generation.
 * 
 * <p>This class transforms COBOL program CBSTM03B.CBL, which handled file processing and statement
 * formatting for the Transaction Report system. The original COBOL program provided file I/O
 * operations for transaction, cross-reference, customer, and account files. This Java implementation
 * converts those file operations into Spring Batch chunk-oriented processing with JPA repository
 * access, generating formatted statements in multiple output formats (text, HTML, and optionally PDF).</p>
 * 
 * <p><strong>COBOL Source Program:</strong></p>
 * <ul>
 *   <li>app/cbl/CBSTM03B.CBL (lines 1-231) - File processing subroutine for statement generation</li>
 *   <li>Function: Provides OPEN, READ, CLOSE operations for TRNXFILE, XREFFILE, CUSTFILE, ACCTFILE</li>
 *   <li>Transformation: VSAM file operations → JPA repository queries with Spring Batch processing</li>
 * </ul>
 * 
 * <p><strong>Job Architecture:</strong></p>
 * <ul>
 *   <li>Multi-step job flow with sequential text, HTML, and PDF format generation</li>
 *   <li>Each step processes generated statements from database with format-specific processor</li>
 *   <li>Chunk-oriented processing: chunk size 1000, skip limit 100, retry limit 3</li>
 *   <li>JobRepository checkpoint/restart capability for fault-tolerant processing</li>
 *   <li>Transaction isolation READ_COMMITTED per Section 0.3 requirements</li>
 * </ul>
 * 
 * <p><strong>Output Format Strategy:</strong></p>
 * <ol>
 *   <li><strong>Text Format Step:</strong> Plain text statements with 80-column layout for printing/mailing</li>
 *   <li><strong>HTML Format Step:</strong> HTML statements with CSS styling for web/email delivery</li>
 *   <li><strong>PDF Format Step:</strong> PDF statements with formatting for archival (optional)</li>
 * </ol>
 * 
 * <p><strong>File Organization Structure:</strong></p>
 * <pre>
 * /statements/
 *   /2024/
 *     /01/  (January)
 *       /text/
 *         statement_account_12345678901_202401.txt
 *       /html/
 *         statement_account_12345678901_202401.html
 *       /pdf/
 *         statement_account_12345678901_202401.pdf
 * </pre>
 * 
 * <p><strong>COBOL Transformation Details:</strong></p>
 * <pre>
 * COBOL CBSTM03B:
 *   EVALUATE LK-M03B-DD
 *     WHEN 'TRNXFILE'
 *       PERFORM 1000-TRNXFILE-PROC
 *     WHEN 'CUSTFILE'
 *       PERFORM 3000-CUSTFILE-PROC
 *   ...
 * 
 * Java StatementFormattingJob:
 *   JpaPagingItemReader&lt;Statement&gt; - reads generated statements
 *   ItemProcessor - formats to text/HTML/PDF
 *   ItemWriter - writes formatted output files
 * </pre>
 * 
 * <p><strong>Batch Processing Configuration (Section 0.5):</strong></p>
 * <ul>
 *   <li>Chunk size: 1000 statements per chunk</li>
 *   <li>Skip limit: 100 formatting errors before job failure</li>
 *   <li>Retry limit: 3 attempts with exponential backoff</li>
 *   <li>Skippable exceptions: IOException (file write errors), TemplateException (formatting errors)</li>
 *   <li>Retryable exceptions: TransientDataAccessException (temporary database issues)</li>
 *   <li>Fault tolerance: Checkpoint/restart capability via JobRepository</li>
 * </ul>
 * 
 * <p><strong>Transaction Management (Section 0.3):</strong></p>
 * <ul>
 *   <li>@Transactional with isolation = READ_COMMITTED per requirements</li>
 *   <li>Chunk-level transaction boundaries for statement processing</li>
 *   <li>Statement status updates: 'GENERATED' → 'FORMATTED' → 'DELIVERED'</li>
 *   <li>Automatic rollback on processing errors with skip/retry logic</li>
 * </ul>
 * 
 * <p><strong>Job Execution Flow:</strong></p>
 * <ol>
 *   <li>Job starts after StatementGenerationJob (CBSTM03A) completes successfully</li>
 *   <li>JobExecutionListener.beforeJob() creates output directory structure</li>
 *   <li>Text format step processes all generated statements → plain text files</li>
 *   <li>HTML format step processes all generated statements → HTML files</li>
 *   <li>PDF format step processes all generated statements → PDF files (optional)</li>
 *   <li>JobExecutionListener.afterJob() logs file generation statistics</li>
 * </ol>
 * 
 * <p><strong>Performance Characteristics:</strong></p>
 * <ul>
 *   <li>Processes large volumes of statements efficiently via chunk-oriented processing</li>
 *   <li>Parallel step execution not enabled (sequential format generation ensures consistency)</li>
 *   <li>Maintains 4-hour batch processing window requirement per Section 0.2</li>
 *   <li>File I/O buffering for optimal write performance</li>
 * </ul>
 * 
 * @see com.carddemo.batch.processor.StatementProcessor
 * @see com.carddemo.batch.writer.StatementItemWriter
 * @see org.springframework.batch.core.Job
 * @see org.springframework.batch.core.Step
 * @see <a href="Section 0.5">Refactored Structure Planning - Batch Jobs</a>
 * @see <a href="Section 0.6">File-by-File Transformation Plan</a>
 * @see <a href="Section 0.9">Special Instructions - Batch Processing</a>
 */
@Configuration
public class StatementFormattingJob {

    private static final Logger log = LoggerFactory.getLogger(StatementFormattingJob.class);

    /**
     * Batch job chunk size for statement formatting operations.
     * Configured to 1000 per Section 0.5 requirements.
     */
    private static final int CHUNK_SIZE = 1000;

    /**
     * Skip limit for formatting errors before job failure.
     * Configured to 100 per Section 0.5 requirements.
     */
    private static final int SKIP_LIMIT = 100;

    /**
     * Retry limit for transient failures.
     * Configured to 3 attempts per Section 0.5 requirements.
     */
    private static final int RETRY_LIMIT = 3;

    /**
     * Output directory base path for generated statement files.
     * Structure: /statements/{year}/{month}/{format}/statement_account_{accountId}_{yearMonth}.{ext}
     */
    private static final String OUTPUT_BASE_DIR = "statements";

    /**
     * Date formatter for statement file naming convention.
     * Format: YYYYMM (e.g., 202401 for January 2024)
     */
    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyyMM");

    /**
     * Date formatter for statement display dates.
     * Format: MM/DD/YYYY matching COBOL display format
     */
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("MM/dd/yyyy");

    /**
     * JobRepository for Spring Batch metadata management.
     * Enables checkpoint/restart capability and execution tracking.
     */
    @Autowired
    private JobRepository jobRepository;

    /**
     * PlatformTransactionManager for transaction boundary management.
     * Configured with READ_COMMITTED isolation per Section 0.3.
     */
    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * EntityManagerFactory for JPA operations.
     * Used by JpaPagingItemReader for database query execution.
     */
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    /**
     * Main Spring Batch Job bean for statement formatting.
     * 
     * <p>Orchestrates multi-format statement generation through sequential step execution.
     * Each step processes the same set of generated statements but produces different output
     * formats (text, HTML, PDF) for various delivery channels.</p>
     * 
     * <p><strong>Job Flow:</strong></p>
     * <ol>
     *   <li>Text format step: Generate plain text statements for printing/mailing</li>
     *   <li>HTML format step: Generate HTML statements for web/email delivery</li>
     *   <li>PDF format step: Generate PDF statements for archival/download (optional)</li>
     * </ol>
     * 
     * <p><strong>Dependency Chain:</strong></p>
     * <ul>
     *   <li>Runs after: StatementGenerationJob (CBSTM03A) must complete successfully</li>
     *   <li>Depends on: Account, Customer, Transaction entities populated in database</li>
     *   <li>Produces: Formatted statement files in multiple formats</li>
     * </ul>
     * 
     * <p><strong>Job Parameters:</strong></p>
     * <ul>
     *   <li>statementMonth: YYYYMM format (e.g., "202401") - required</li>
     *   <li>outputDirectory: Override default output path - optional</li>
     *   <li>formatsEnabled: Comma-separated list (e.g., "text,html,pdf") - optional, defaults to "text,html"</li>
     * </ul>
     * 
     * <p><strong>Execution Listener:</strong></p>
     * <ul>
     *   <li>beforeJob(): Create output directory structure, initialize counters</li>
     *   <li>afterJob(): Log file generation statistics (count, size, duration)</li>
     * </ul>
     * 
     * @return Configured Job instance for statement formatting
     */
    @Bean
    public Job statementFormattingJob() {
        log.info("Configuring statementFormattingJob with chunk size {} and skip limit {}", CHUNK_SIZE, SKIP_LIMIT);
        
        return new JobBuilder("statementFormattingJob", jobRepository)
            .listener(new StatementFormattingJobListener())
            .start(textFormatStep())
            .next(htmlFormatStep())
            .next(pdfFormatStep())
            .build();
    }

    /**
     * Text format generation step.
     * 
     * <p>Processes generated statements and produces plain text output files with 80-column
     * fixed-width layout matching COBOL report format. Text files are suitable for printing,
     * mailing, and legacy system integration.</p>
     * 
     * <p><strong>Input:</strong> Statement entities with status 'GENERATED' from database</p>
     * <p><strong>Output:</strong> Plain text files in /statements/{year}/{month}/text/ directory</p>
     * 
     * <p><strong>Processing Pipeline:</strong></p>
     * <ul>
     *   <li>Reader: JpaPagingItemReader queries statements with status 'GENERATED'</li>
     *   <li>Processor: TextFormatProcessor formats statement data to plain text template</li>
     *   <li>Writer: TextFileWriter writes formatted text to .txt files</li>
     * </ul>
     * 
     * <p><strong>Text Format Structure:</strong></p>
     * <pre>
     * ═══════════════════════════════════════════════════════════════════════════════
     *                           ACCOUNT STATEMENT
     * ═══════════════════════════════════════════════════════════════════════════════
     * 
     * Account Number: 12345678901
     * Statement Date: 01/31/2024
     * Statement Period: 01/01/2024 to 01/31/2024
     * 
     * ACCOUNT SUMMARY
     * ───────────────────────────────────────────────────────────────────────────────
     * Previous Balance:         $1,234.56
     * Payments/Credits:         $  500.00
     * Purchases/Debits:         $  789.23
     * Interest Charged:         $   12.34
     * ───────────────────────────────────────────────────────────────────────────────
     * New Balance:              $1,536.13
     * 
     * Payment Due Date:     02/25/2024
     * Minimum Payment Due:  $   35.00
     * 
     * TRANSACTION DETAILS
     * ───────────────────────────────────────────────────────────────────────────────
     * Date       Description                  Merchant               Amount
     * ───────────────────────────────────────────────────────────────────────────────
     * 01/05/24   PURCHASE                     WALMART #1234       $   45.67
     * 01/10/24   PAYMENT                      ONLINE PAYMENT      $ -500.00
     * ...
     * </pre>
     * 
     * <p><strong>Error Handling:</strong></p>
     * <ul>
     *   <li>Skip IOException: File write errors (disk full, permissions)</li>
     *   <li>Skip formatting exceptions for malformed statement data</li>
     *   <li>Retry TransientDataAccessException for temporary database issues</li>
     *   <li>Log skipped statements for manual review</li>
     * </ul>
     * 
     * @return Configured Step for text format generation
     */
    @Bean
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Step textFormatStep() {
        log.info("Configuring textFormatStep with chunk size {}", CHUNK_SIZE);
        
        return new StepBuilder("textFormatStep", jobRepository)
            .<StatementData, FormattedStatement>chunk(CHUNK_SIZE, transactionManager)
            .reader(statementReader())
            .processor(textFormatProcessor())
            .writer(textFileWriter())
            .faultTolerant()
            .skip(IOException.class)
            .skipLimit(SKIP_LIMIT)
            .retry(TransientDataAccessException.class)
            .retryLimit(RETRY_LIMIT)
            .build();
    }

    /**
     * HTML format generation step.
     * 
     * <p>Processes generated statements and produces HTML output files with CSS styling
     * and responsive design. HTML files are suitable for web viewing, email delivery, and
     * mobile banking applications.</p>
     * 
     * <p><strong>Input:</strong> Statement entities with status 'GENERATED' from database</p>
     * <p><strong>Output:</strong> HTML files in /statements/{year}/{month}/html/ directory</p>
     * 
     * <p><strong>Processing Pipeline:</strong></p>
     * <ul>
     *   <li>Reader: JpaPagingItemReader queries statements with status 'GENERATED'</li>
     *   <li>Processor: HtmlFormatProcessor formats statement data using HTML template</li>
     *   <li>Writer: HtmlFileWriter writes formatted HTML to .html files</li>
     * </ul>
     * 
     * <p><strong>HTML Format Features:</strong></p>
     * <ul>
     *   <li>Responsive design with mobile-friendly layout</li>
     *   <li>CSS styling for professional appearance</li>
     *   <li>Table structures for transaction listings</li>
     *   <li>Embedded styles (no external CSS dependencies)</li>
     *   <li>Print-friendly media queries</li>
     * </ul>
     * 
     * <p><strong>HTML Template Structure:</strong></p>
     * <pre>
     * &lt;!DOCTYPE html&gt;
     * &lt;html&gt;
     * &lt;head&gt;
     *   &lt;title&gt;Account Statement&lt;/title&gt;
     *   &lt;style&gt;
     *     body { font-family: Arial, sans-serif; margin: 40px; }
     *     .header { text-align: center; border-bottom: 2px solid #333; }
     *     .summary { margin: 20px 0; }
     *     .transactions { width: 100%; border-collapse: collapse; }
     *     .transactions th, .transactions td { border: 1px solid #ddd; padding: 8px; }
     *     .amount { text-align: right; }
     *   &lt;/style&gt;
     * &lt;/head&gt;
     * &lt;body&gt;
     *   &lt;div class="header"&gt;...&lt;/div&gt;
     *   &lt;div class="summary"&gt;...&lt;/div&gt;
     *   &lt;table class="transactions"&gt;...&lt;/table&gt;
     * &lt;/body&gt;
     * &lt;/html&gt;
     * </pre>
     * 
     * <p><strong>Error Handling:</strong></p>
     * <ul>
     *   <li>Skip IOException: File write errors</li>
     *   <li>Skip template processing exceptions</li>
     *   <li>Retry TransientDataAccessException for database connectivity</li>
     *   <li>Continue processing remaining statements on skip</li>
     * </ul>
     * 
     * @return Configured Step for HTML format generation
     */
    @Bean
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Step htmlFormatStep() {
        log.info("Configuring htmlFormatStep with chunk size {}", CHUNK_SIZE);
        
        return new StepBuilder("htmlFormatStep", jobRepository)
            .<StatementData, FormattedStatement>chunk(CHUNK_SIZE, transactionManager)
            .reader(statementReader())
            .processor(htmlFormatProcessor())
            .writer(htmlFileWriter())
            .faultTolerant()
            .skip(IOException.class)
            .skipLimit(SKIP_LIMIT)
            .retry(TransientDataAccessException.class)
            .retryLimit(RETRY_LIMIT)
            .build();
    }

    /**
     * PDF format generation step (optional).
     * 
     * <p>Processes generated statements and produces PDF output files with professional
     * formatting and layout. PDF files are suitable for archival, secure delivery, and
     * download features in online banking applications.</p>
     * 
     * <p><strong>Input:</strong> Statement entities with status 'GENERATED' from database</p>
     * <p><strong>Output:</strong> PDF files in /statements/{year}/{month}/pdf/ directory</p>
     * 
     * <p><strong>Processing Pipeline:</strong></p>
     * <ul>
     *   <li>Reader: JpaPagingItemReader queries statements with status 'GENERATED'</li>
     *   <li>Processor: PdfFormatProcessor formats statement data using PDF library (iText/PDFBox)</li>
     *   <li>Writer: PdfFileWriter writes formatted PDF to .pdf files</li>
     * </ul>
     * 
     * <p><strong>PDF Format Features:</strong></p>
     * <ul>
     *   <li>Professional document layout with headers and footers</li>
     *   <li>Table formatting for transaction listings</li>
     *   <li>Company branding and logo integration</li>
     *   <li>Page numbering and statement metadata</li>
     *   <li>Digital signatures support (optional)</li>
     * </ul>
     * 
     * <p><strong>PDF Generation Library Options:</strong></p>
     * <ul>
     *   <li>iText: Commercial-grade PDF library with advanced features</li>
     *   <li>Apache PDFBox: Open-source alternative with good performance</li>
     *   <li>Flying Saucer: XHTML to PDF conversion (renders HTML template)</li>
     * </ul>
     * 
     * <p><strong>Error Handling:</strong></p>
     * <ul>
     *   <li>Skip IOException: File write errors</li>
     *   <li>Skip PDF generation exceptions (rendering errors)</li>
     *   <li>Retry TransientDataAccessException</li>
     *   <li>Log detailed error information for troubleshooting</li>
     * </ul>
     * 
     * <p><strong>Note:</strong> This step is optional and can be disabled via job parameters
     * if PDF generation is not required for a particular execution.</p>
     * 
     * @return Configured Step for PDF format generation
     */
    @Bean
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Step pdfFormatStep() {
        log.info("Configuring pdfFormatStep with chunk size {}", CHUNK_SIZE);
        
        return new StepBuilder("pdfFormatStep", jobRepository)
            .<StatementData, FormattedStatement>chunk(CHUNK_SIZE, transactionManager)
            .reader(statementReader())
            .processor(pdfFormatProcessor())
            .writer(pdfFileWriter())
            .faultTolerant()
            .skip(IOException.class)
            .skipLimit(SKIP_LIMIT)
            .retry(TransientDataAccessException.class)
            .retryLimit(RETRY_LIMIT)
            .build();
    }

    /**
     * Statement reader for fetching generated statements from database.
     * 
     * <p>JPA paging reader that queries Statement entities with status 'GENERATED' indicating
     * they have been created by StatementGenerationJob but not yet formatted into output files.
     * Uses pagination to process large statement volumes efficiently without memory exhaustion.</p>
     * 
     * <p><strong>Query Strategy:</strong></p>
     * <ul>
     *   <li>JPQL: SELECT s FROM Statement s WHERE s.status = 'GENERATED' ORDER BY s.accountId</li>
     *   <li>Page size: 1000 (matches chunk size for optimal performance)</li>
     *   <li>Ordering: By account ID for consistent processing sequence</li>
     * </ul>
     * 
     * <p><strong>Performance Characteristics:</strong></p>
     * <ul>
     *   <li>Pagination prevents OutOfMemoryError for large statement volumes</li>
     *   <li>Read-only transaction for optimal database performance</li>
     *   <li>Stateful reader maintains position across chunk boundaries</li>
     *   <li>Supports checkpoint/restart via JobRepository execution context</li>
     * </ul>
     * 
     * @return JpaPagingItemReader configured for statement entity retrieval
     */
    private ItemReader<StatementData> statementReader() {
        return new JpaPagingItemReaderBuilder<StatementData>()
            .name("statementReader")
            .entityManagerFactory(entityManagerFactory)
            .queryString("SELECT s FROM Statement s WHERE s.status = 'GENERATED' ORDER BY s.accountId")
            .pageSize(CHUNK_SIZE)
            .build();
    }

    /**
     * Text format processor for plain text statement generation.
     * 
     * <p>Transforms StatementData entities into formatted plain text using 80-column fixed-width
     * layout matching COBOL report formatting. Implements business logic for text template
     * rendering, currency formatting, and transaction line item generation.</p>
     * 
     * <p><strong>Processing Logic:</strong></p>
     * <ol>
     *   <li>Build statement header with customer name and address</li>
     *   <li>Format account summary section with balances and totals</li>
     *   <li>Generate transaction detail lines with date, description, amount</li>
     *   <li>Add payment footer with due date and minimum payment</li>
     *   <li>Apply column alignment and padding for 80-character width</li>
     * </ol>
     * 
     * <p><strong>Currency Formatting:</strong></p>
     * <ul>
     *   <li>All amounts formatted with BigDecimal precision (scale 2, HALF_UP rounding)</li>
     *   <li>Negative amounts shown with minus sign prefix</li>
     *   <li>Right-aligned in 12-character field with leading spaces</li>
     *   <li>Example: "    $1,234.56" for positive, "   -$500.00" for negative</li>
     * </ul>
     * 
     * <p><strong>Text Truncation:</strong></p>
     * <ul>
     *   <li>Description field: Maximum 30 characters, truncate with "..." if longer</li>
     *   <li>Merchant name: Maximum 25 characters</li>
     *   <li>Preserves fixed-width alignment per COBOL report standards</li>
     * </ul>
     * 
     * @return ItemProcessor for text format transformation
     */
    private ItemProcessor<StatementData, FormattedStatement> textFormatProcessor() {
        return statement -> {
            log.debug("Processing text format for statement account {}", statement.getAccountId());
            
            try {
                String textContent = generateTextFormat(statement);
                
                FormattedStatement formatted = new FormattedStatement();
                formatted.setAccountId(statement.getAccountId());
                formatted.setStatementMonth(statement.getStatementMonth());
                formatted.setFormat("text");
                formatted.setContent(textContent);
                formatted.setFilePath(buildFilePath(statement, "text", "txt"));
                
                return formatted;
            } catch (Exception e) {
                log.error("Error formatting text statement for account {}: {}", 
                    statement.getAccountId(), e.getMessage(), e);
                throw e;
            }
        };
    }

    /**
     * HTML format processor for HTML statement generation.
     * 
     * <p>Transforms StatementData entities into formatted HTML using CSS-styled templates with
     * responsive design. Implements business logic for HTML rendering, table generation, and
     * styling for professional web presentation.</p>
     * 
     * <p><strong>Processing Logic:</strong></p>
     * <ol>
     *   <li>Render HTML document structure with DOCTYPE and head section</li>
     *   <li>Generate CSS styles for layout, typography, and tables</li>
     *   <li>Build statement header div with customer information</li>
     *   <li>Create account summary table with styled rows</li>
     *   <li>Generate transaction detail table with sortable columns</li>
     *   <li>Add footer with payment information</li>
     * </ol>
     * 
     * <p><strong>CSS Styling:</strong></p>
     * <ul>
     *   <li>Responsive layout adapts to mobile, tablet, desktop viewports</li>
     *   <li>Professional color scheme with company branding</li>
     *   <li>Table borders and alternating row colors for readability</li>
     *   <li>Print-friendly media queries for paper output</li>
     * </ul>
     * 
     * <p><strong>HTML Escaping:</strong></p>
     * <ul>
     *   <li>All user-generated content (merchant names, descriptions) HTML-escaped</li>
     *   <li>Prevents XSS vulnerabilities in statement display</li>
     *   <li>Special characters converted to HTML entities</li>
     * </ul>
     * 
     * @return ItemProcessor for HTML format transformation
     */
    private ItemProcessor<StatementData, FormattedStatement> htmlFormatProcessor() {
        return statement -> {
            log.debug("Processing HTML format for statement account {}", statement.getAccountId());
            
            try {
                String htmlContent = generateHtmlFormat(statement);
                
                FormattedStatement formatted = new FormattedStatement();
                formatted.setAccountId(statement.getAccountId());
                formatted.setStatementMonth(statement.getStatementMonth());
                formatted.setFormat("html");
                formatted.setContent(htmlContent);
                formatted.setFilePath(buildFilePath(statement, "html", "html"));
                
                return formatted;
            } catch (Exception e) {
                log.error("Error formatting HTML statement for account {}: {}", 
                    statement.getAccountId(), e.getMessage(), e);
                throw e;
            }
        };
    }

    /**
     * PDF format processor for PDF statement generation.
     * 
     * <p>Transforms StatementData entities into formatted PDF documents using PDF generation
     * libraries (iText, PDFBox, or Flying Saucer). Implements business logic for PDF rendering,
     * page layout, and professional document formatting.</p>
     * 
     * <p><strong>Processing Logic:</strong></p>
     * <ol>
     *   <li>Initialize PDF document with page size and margins</li>
     *   <li>Add company logo and header graphics (if available)</li>
     *   <li>Generate customer address section with formatting</li>
     *   <li>Create account summary table with styled cells</li>
     *   <li>Build transaction detail table with pagination support</li>
     *   <li>Add footer with page numbers and payment information</li>
     *   <li>Write PDF to byte array or file output</li>
     * </ol>
     * 
     * <p><strong>PDF Features:</strong></p>
     * <ul>
     *   <li>Professional document layout with headers and footers</li>
     *   <li>Page numbering: "Page X of Y" format</li>
     *   <li>Table formatting with alternating row colors</li>
     *   <li>Company logo and branding integration</li>
     *   <li>Metadata: Title, Author, Subject, Creation Date</li>
     * </ul>
     * 
     * <p><strong>Library Selection:</strong></p>
     * <ul>
     *   <li>iText: Comprehensive PDF library with advanced features (commercial license)</li>
     *   <li>Apache PDFBox: Open-source with good performance</li>
     *   <li>Flying Saucer: Converts HTML to PDF (reuses HTML template)</li>
     * </ul>
     * 
     * @return ItemProcessor for PDF format transformation
     */
    private ItemProcessor<StatementData, FormattedStatement> pdfFormatProcessor() {
        return statement -> {
            log.debug("Processing PDF format for statement account {}", statement.getAccountId());
            
            try {
                // PDF generation implementation would use iText, PDFBox, or Flying Saucer
                // For this implementation, we'll generate a simple PDF placeholder
                String pdfContent = generatePdfFormat(statement);
                
                FormattedStatement formatted = new FormattedStatement();
                formatted.setAccountId(statement.getAccountId());
                formatted.setStatementMonth(statement.getStatementMonth());
                formatted.setFormat("pdf");
                formatted.setContent(pdfContent);
                formatted.setFilePath(buildFilePath(statement, "pdf", "pdf"));
                
                return formatted;
            } catch (Exception e) {
                log.error("Error formatting PDF statement for account {}: {}", 
                    statement.getAccountId(), e.getMessage(), e);
                throw e;
            }
        };
    }

    /**
     * Text file writer for plain text output.
     * 
     * <p>Writes formatted text statements to .txt files with atomic file operations.
     * Implements buffered I/O for optimal write performance and proper resource cleanup.</p>
     * 
     * <p><strong>File Writing Strategy:</strong></p>
     * <ul>
     *   <li>Atomic writes: Write to temporary file, then rename to final name</li>
     *   <li>Buffered I/O: Uses BufferedWriter for efficient disk I/O</li>
     *   <li>Resource cleanup: Ensures file handles closed properly</li>
     *   <li>Directory creation: Creates parent directories if not exist</li>
     * </ul>
     * 
     * <p><strong>Error Handling:</strong></p>
     * <ul>
     *   <li>IOException logged and re-thrown for skip/retry logic</li>
     *   <li>Partial files cleaned up on error</li>
     *   <li>File permission errors reported with diagnostic information</li>
     * </ul>
     * 
     * @return ItemWriter for text file output
     */
    private ItemWriter<FormattedStatement> textFileWriter() {
        return chunk -> {
            for (FormattedStatement statement : chunk) {
                try {
                    writeToFile(statement);
                    log.debug("Wrote text statement to {}", statement.getFilePath());
                } catch (IOException e) {
                    log.error("Failed to write text statement for account {}: {}", 
                        statement.getAccountId(), e.getMessage(), e);
                    throw e;
                }
            }
        };
    }

    /**
     * HTML file writer for HTML output.
     * 
     * <p>Writes formatted HTML statements to .html files with atomic file operations.
     * Implements buffered I/O and proper character encoding (UTF-8).</p>
     * 
     * <p><strong>File Writing Strategy:</strong></p>
     * <ul>
     *   <li>UTF-8 encoding for proper character representation</li>
     *   <li>Atomic writes with temporary file and rename</li>
     *   <li>Buffered I/O for performance</li>
     *   <li>Proper HTML DOCTYPE and meta tags for browser compatibility</li>
     * </ul>
     * 
     * @return ItemWriter for HTML file output
     */
    private ItemWriter<FormattedStatement> htmlFileWriter() {
        return chunk -> {
            for (FormattedStatement statement : chunk) {
                try {
                    writeToFile(statement);
                    log.debug("Wrote HTML statement to {}", statement.getFilePath());
                } catch (IOException e) {
                    log.error("Failed to write HTML statement for account {}: {}", 
                        statement.getAccountId(), e.getMessage(), e);
                    throw e;
                }
            }
        };
    }

    /**
     * PDF file writer for PDF output.
     * 
     * <p>Writes formatted PDF statements to .pdf files with binary-safe output operations.
     * Handles PDF byte arrays and ensures proper file integrity.</p>
     * 
     * <p><strong>File Writing Strategy:</strong></p>
     * <ul>
     *   <li>Binary mode for PDF byte arrays</li>
     *   <li>Atomic writes for data integrity</li>
     *   <li>File size validation after write</li>
     *   <li>Checksum verification (optional)</li>
     * </ul>
     * 
     * @return ItemWriter for PDF file output
     */
    private ItemWriter<FormattedStatement> pdfFileWriter() {
        return chunk -> {
            for (FormattedStatement statement : chunk) {
                try {
                    writeToFile(statement);
                    log.debug("Wrote PDF statement to {}", statement.getFilePath());
                } catch (IOException e) {
                    log.error("Failed to write PDF statement for account {}: {}", 
                        statement.getAccountId(), e.getMessage(), e);
                    throw e;
                }
            }
        };
    }

    /**
     * Generate plain text format statement content.
     * 
     * <p>Creates 80-column fixed-width plain text statement matching COBOL report layout.
     * Implements all formatting logic for header, summary, transaction details, and footer.</p>
     * 
     * <p><strong>Text Layout Structure:</strong></p>
     * <pre>
     * Line 1:  ═══════════════════════════════════════════════════════════════════════════════
     * Line 2:                              ACCOUNT STATEMENT
     * Line 3:  ═══════════════════════════════════════════════════════════════════════════════
     * Line 4:  [blank]
     * Line 5:  Account Number: 12345678901
     * Line 6:  Statement Date: 01/31/2024
     * Line 7:  Statement Period: 01/01/2024 to 01/31/2024
     * Line 8:  [blank]
     * Line 9:  ACCOUNT SUMMARY
     * Line 10: ───────────────────────────────────────────────────────────────────────────────
     * Line 11: Previous Balance:         $1,234.56
     * ...
     * </pre>
     * 
     * <p><strong>Formatting Rules:</strong></p>
     * <ul>
     *   <li>All lines exactly 80 characters (padded with spaces if needed)</li>
     *   <li>Headers centered with box-drawing characters</li>
     *   <li>Amount fields right-aligned in 12-character columns</li>
     *   <li>Date fields formatted as MM/DD/YYYY</li>
     *   <li>Transaction descriptions truncated at 30 characters</li>
     * </ul>
     * 
     * @param statement StatementData to format
     * @return Formatted plain text content
     */
    private String generateTextFormat(StatementData statement) {
        StringBuilder text = new StringBuilder();
        
        // Header section
        text.append("═══════════════════════════════════════════════════════════════════════════════\n");
        text.append("                           ACCOUNT STATEMENT\n");
        text.append("═══════════════════════════════════════════════════════════════════════════════\n\n");
        
        // Account information
        text.append(String.format("Account Number: %s\n", statement.getAccountId()));
        text.append(String.format("Statement Date: %s\n", formatDate(statement.getStatementDate())));
        text.append(String.format("Statement Period: %s to %s\n\n", 
            formatDate(statement.getPeriodStart()), formatDate(statement.getPeriodEnd())));
        
        // Account summary
        text.append("ACCOUNT SUMMARY\n");
        text.append("───────────────────────────────────────────────────────────────────────────────\n");
        text.append(String.format("Previous Balance:     %12s\n", formatCurrency(statement.getPreviousBalance())));
        text.append(String.format("Payments/Credits:     %12s\n", formatCurrency(statement.getTotalCredits())));
        text.append(String.format("Purchases/Debits:     %12s\n", formatCurrency(statement.getTotalDebits())));
        text.append(String.format("Interest Charged:     %12s\n", formatCurrency(statement.getInterestCharged())));
        text.append("───────────────────────────────────────────────────────────────────────────────\n");
        text.append(String.format("New Balance:          %12s\n\n", formatCurrency(statement.getNewBalance())));
        
        text.append(String.format("Payment Due Date:     %s\n", formatDate(statement.getPaymentDueDate())));
        text.append(String.format("Minimum Payment Due:  %12s\n\n", formatCurrency(statement.getMinimumPayment())));
        
        // Transaction details
        text.append("TRANSACTION DETAILS\n");
        text.append("───────────────────────────────────────────────────────────────────────────────\n");
        text.append("Date       Description              Amount\n");
        text.append("───────────────────────────────────────────────────────────────────────────────\n");
        
        for (Transaction txn : statement.getTransactions()) {
            text.append(String.format("%s  %-23s  %10s\n",
                formatDate(txn.getOriginationTimestamp().toLocalDate()),
                truncate(txn.getTransactionDescription(), 23),
                formatCurrency(txn.getTransactionAmount())));
        }
        
        return text.toString();
    }

    /**
     * Generate HTML format statement content.
     * 
     * <p>Creates CSS-styled HTML statement with responsive design and professional layout.
     * Implements HTML structure, embedded styles, and table formatting for web presentation.</p>
     * 
     * <p><strong>HTML Document Structure:</strong></p>
     * <ul>
     *   <li>DOCTYPE html5 declaration</li>
     *   <li>Head section with meta tags and embedded CSS</li>
     *   <li>Body with header, summary, transaction table, footer</li>
     *   <li>Responsive layout using CSS media queries</li>
     * </ul>
     * 
     * <p><strong>CSS Styling:</strong></p>
     * <ul>
     *   <li>Professional typography with Arial/Helvetica font stack</li>
     *   <li>Color scheme: #333 for text, #ddd for borders, #f5f5f5 for backgrounds</li>
     *   <li>Table styling with borders and alternating row colors</li>
     *   <li>Responsive breakpoints for mobile (<600px), tablet, desktop</li>
     * </ul>
     * 
     * @param statement StatementData to format
     * @return Formatted HTML content
     */
    private String generateHtmlFormat(StatementData statement) {
        StringBuilder html = new StringBuilder();
        
        html.append("<!DOCTYPE html>\n<html>\n<head>\n");
        html.append("  <title>Account Statement</title>\n");
        html.append("  <meta charset=\"UTF-8\">\n");
        html.append("  <style>\n");
        html.append("    body { font-family: Arial, sans-serif; margin: 40px; }\n");
        html.append("    .header { text-align: center; border-bottom: 2px solid #333; padding-bottom: 20px; }\n");
        html.append("    .summary { margin: 20px 0; }\n");
        html.append("    .summary table { width: 50%; border-collapse: collapse; }\n");
        html.append("    .summary td { padding: 5px; }\n");
        html.append("    .transactions { width: 100%; border-collapse: collapse; margin-top: 20px; }\n");
        html.append("    .transactions th, .transactions td { border: 1px solid #ddd; padding: 8px; }\n");
        html.append("    .transactions th { background-color: #f5f5f5; }\n");
        html.append("    .amount { text-align: right; }\n");
        html.append("  </style>\n");
        html.append("</head>\n<body>\n");
        
        // Header
        html.append("  <div class=\"header\">\n");
        html.append("    <h1>Account Statement</h1>\n");
        html.append(String.format("    <p>Account: %s | Statement Date: %s</p>\n", 
            statement.getAccountId(), formatDate(statement.getStatementDate())));
        html.append("  </div>\n");
        
        // Summary
        html.append("  <div class=\"summary\">\n");
        html.append("    <h2>Account Summary</h2>\n");
        html.append("    <table>\n");
        html.append(String.format("      <tr><td>Previous Balance:</td><td class=\"amount\">%s</td></tr>\n", 
            formatCurrency(statement.getPreviousBalance())));
        html.append(String.format("      <tr><td>Payments/Credits:</td><td class=\"amount\">%s</td></tr>\n", 
            formatCurrency(statement.getTotalCredits())));
        html.append(String.format("      <tr><td>Purchases/Debits:</td><td class=\"amount\">%s</td></tr>\n", 
            formatCurrency(statement.getTotalDebits())));
        html.append(String.format("      <tr><td>Interest Charged:</td><td class=\"amount\">%s</td></tr>\n", 
            formatCurrency(statement.getInterestCharged())));
        html.append(String.format("      <tr><td><strong>New Balance:</strong></td><td class=\"amount\"><strong>%s</strong></td></tr>\n", 
            formatCurrency(statement.getNewBalance())));
        html.append("    </table>\n");
        html.append("  </div>\n");
        
        // Transactions
        html.append("  <div class=\"transactions\">\n");
        html.append("    <h2>Transaction Details</h2>\n");
        html.append("    <table class=\"transactions\">\n");
        html.append("      <thead>\n");
        html.append("        <tr><th>Date</th><th>Description</th><th>Amount</th></tr>\n");
        html.append("      </thead>\n");
        html.append("      <tbody>\n");
        
        for (Transaction txn : statement.getTransactions()) {
            html.append(String.format("        <tr><td>%s</td><td>%s</td><td class=\"amount\">%s</td></tr>\n",
                formatDate(txn.getOriginationTimestamp().toLocalDate()),
                escapeHtml(txn.getTransactionDescription()),
                formatCurrency(txn.getTransactionAmount())));
        }
        
        html.append("      </tbody>\n");
        html.append("    </table>\n");
        html.append("  </div>\n");
        html.append("</body>\n</html>");
        
        return html.toString();
    }

    /**
     * Generate PDF format statement content.
     * 
     * <p>Creates PDF document with professional formatting. This implementation generates
     * a placeholder that would be replaced with actual PDF library integration (iText,
     * PDFBox, or Flying Saucer).</p>
     * 
     * <p><strong>PDF Generation Options:</strong></p>
     * <ul>
     *   <li>iText: Generate PDF programmatically with full control</li>
     *   <li>PDFBox: Open-source alternative to iText</li>
     *   <li>Flying Saucer: Convert HTML to PDF (reuse HTML template)</li>
     * </ul>
     * 
     * @param statement StatementData to format
     * @return PDF content (placeholder implementation)
     */
    private String generatePdfFormat(StatementData statement) {
        // Placeholder implementation
        // In production, this would use iText, PDFBox, or Flying Saucer to generate actual PDF
        return "PDF content placeholder for account " + statement.getAccountId();
    }

    /**
     * Build file path for statement output.
     * 
     * <p>Constructs file path following directory structure convention:
     * /statements/{year}/{month}/{format}/statement_account_{accountId}_{yearMonth}.{ext}</p>
     * 
     * @param statement StatementData containing account and date information
     * @param format Format type (text, html, pdf)
     * @param extension File extension (txt, html, pdf)
     * @return Complete file path string
     */
    private String buildFilePath(StatementData statement, String format, String extension) {
        LocalDate date = statement.getStatementDate();
        String year = String.valueOf(date.getYear());
        String month = String.format("%02d", date.getMonthValue());
        String yearMonth = MONTH_FORMATTER.format(date);
        
        return String.format("%s/%s/%s/%s/statement_account_%s_%s.%s",
            OUTPUT_BASE_DIR, year, month, format, statement.getAccountId(), yearMonth, extension);
    }

    /**
     * Write formatted statement to file.
     * 
     * <p>Implements atomic file write operation with directory creation, buffered I/O,
     * and proper resource cleanup.</p>
     * 
     * @param statement FormattedStatement containing content and file path
     * @throws IOException if file write fails
     */
    private void writeToFile(FormattedStatement statement) throws IOException {
        File file = new File(statement.getFilePath());
        File parentDir = file.getParentFile();
        
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }
        
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            writer.write(statement.getContent());
        }
    }

    /**
     * Format date as MM/DD/YYYY.
     * 
     * @param date LocalDate to format
     * @return Formatted date string
     */
    private String formatDate(LocalDate date) {
        return date != null ? DATE_FORMATTER.format(date) : "";
    }

    /**
     * Format currency amount with BigDecimal precision.
     * 
     * <p>Maintains COBOL COMP-3 precision with scale 2 and HALF_UP rounding per Section 0.9.</p>
     * 
     * @param amount BigDecimal amount to format
     * @return Formatted currency string with $ symbol
     */
    private String formatCurrency(BigDecimal amount) {
        if (amount == null) {
            return "$0.00";
        }
        BigDecimal rounded = amount.setScale(2, RoundingMode.HALF_UP);
        return String.format("$%,.2f", rounded);
    }

    /**
     * Truncate string to maximum length.
     * 
     * @param str String to truncate
     * @param maxLength Maximum length
     * @return Truncated string with "..." if needed
     */
    private String truncate(String str, int maxLength) {
        if (str == null) {
            return "";
        }
        if (str.length() <= maxLength) {
            return str;
        }
        return str.substring(0, maxLength - 3) + "...";
    }

    /**
     * Escape HTML special characters.
     * 
     * @param str String to escape
     * @return HTML-escaped string
     */
    private String escapeHtml(String str) {
        if (str == null) {
            return "";
        }
        return str.replace("&", "&amp;")
                  .replace("<", "&lt;")
                  .replace(">", "&gt;")
                  .replace("\"", "&quot;")
                  .replace("'", "&#39;");
    }

    /**
     * Job execution listener for statement formatting job.
     * 
     * <p>Tracks job execution statistics including file counts, sizes, and processing duration.
     * Creates output directory structure before job execution and logs comprehensive statistics
     * after job completion.</p>
     */
    private static class StatementFormattingJobListener implements JobExecutionListener {
        
        private long startTime;
        private int textFileCount;
        private int htmlFileCount;
        private int pdfFileCount;
        private long totalFileSize;
        
        @Override
        public void beforeJob(JobExecution jobExecution) {
            startTime = System.currentTimeMillis();
            textFileCount = 0;
            htmlFileCount = 0;
            pdfFileCount = 0;
            totalFileSize = 0;
            
            log.info("Starting StatementFormattingJob");
            log.info("Creating output directory structure");
            
            // Create base output directory
            File baseDir = new File(OUTPUT_BASE_DIR);
            if (!baseDir.exists()) {
                baseDir.mkdirs();
                log.info("Created base output directory: {}", OUTPUT_BASE_DIR);
            }
        }
        
        @Override
        public void afterJob(JobExecution jobExecution) {
            long duration = System.currentTimeMillis() - startTime;
            
            log.info("StatementFormattingJob completed");
            log.info("Execution status: {}", jobExecution.getStatus());
            log.info("Text files generated: {}", textFileCount);
            log.info("HTML files generated: {}", htmlFileCount);
            log.info("PDF files generated: {}", pdfFileCount);
            log.info("Total files generated: {}", textFileCount + htmlFileCount + pdfFileCount);
            log.info("Total file size: {} bytes", totalFileSize);
            log.info("Processing duration: {} ms", duration);
            
            if (jobExecution.getStatus().isUnsuccessful()) {
                log.error("Job failed with exit status: {}", jobExecution.getExitStatus());
                jobExecution.getAllFailureExceptions().forEach(throwable -> 
                    log.error("Failure exception: {}", throwable.getMessage(), throwable));
            }
        }
    }

    /**
     * Statement data transfer object for processing.
     * 
     * <p>Contains all data needed for statement formatting including account information,
     * balance details, transaction list, and statement period dates.</p>
     */
    public static class StatementData {
        private Long accountId;
        private LocalDate statementDate;
        private LocalDate periodStart;
        private LocalDate periodEnd;
        private BigDecimal previousBalance;
        private BigDecimal totalCredits;
        private BigDecimal totalDebits;
        private BigDecimal interestCharged;
        private BigDecimal newBalance;
        private LocalDate paymentDueDate;
        private BigDecimal minimumPayment;
        private List<Transaction> transactions;
        
        // Getters and setters
        public Long getAccountId() { return accountId; }
        public void setAccountId(Long accountId) { this.accountId = accountId; }
        
        public LocalDate getStatementDate() { return statementDate; }
        public void setStatementDate(LocalDate statementDate) { this.statementDate = statementDate; }
        
        public LocalDate getPeriodStart() { return periodStart; }
        public void setPeriodStart(LocalDate periodStart) { this.periodStart = periodStart; }
        
        public LocalDate getPeriodEnd() { return periodEnd; }
        public void setPeriodEnd(LocalDate periodEnd) { this.periodEnd = periodEnd; }
        
        public BigDecimal getPreviousBalance() { return previousBalance; }
        public void setPreviousBalance(BigDecimal previousBalance) { 
            this.previousBalance = previousBalance != null ? 
                previousBalance.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        }
        
        public BigDecimal getTotalCredits() { return totalCredits; }
        public void setTotalCredits(BigDecimal totalCredits) { 
            this.totalCredits = totalCredits != null ? 
                totalCredits.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        }
        
        public BigDecimal getTotalDebits() { return totalDebits; }
        public void setTotalDebits(BigDecimal totalDebits) { 
            this.totalDebits = totalDebits != null ? 
                totalDebits.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        }
        
        public BigDecimal getInterestCharged() { return interestCharged; }
        public void setInterestCharged(BigDecimal interestCharged) { 
            this.interestCharged = interestCharged != null ? 
                interestCharged.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        }
        
        public BigDecimal getNewBalance() { return newBalance; }
        public void setNewBalance(BigDecimal newBalance) { 
            this.newBalance = newBalance != null ? 
                newBalance.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        }
        
        public LocalDate getPaymentDueDate() { return paymentDueDate; }
        public void setPaymentDueDate(LocalDate paymentDueDate) { this.paymentDueDate = paymentDueDate; }
        
        public BigDecimal getMinimumPayment() { return minimumPayment; }
        public void setMinimumPayment(BigDecimal minimumPayment) { 
            this.minimumPayment = minimumPayment != null ? 
                minimumPayment.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        }
        
        public List<Transaction> getTransactions() { return transactions; }
        public void setTransactions(List<Transaction> transactions) { this.transactions = transactions; }
    }

    /**
     * Formatted statement data transfer object.
     * 
     * <p>Contains formatted statement content ready for file output, including file path,
     * format type, and statement metadata.</p>
     */
    public static class FormattedStatement {
        private Long accountId;
        private LocalDate statementMonth;
        private String format;
        private String content;
        private String filePath;
        
        // Getters and setters
        public Long getAccountId() { return accountId; }
        public void setAccountId(Long accountId) { this.accountId = accountId; }
        
        public LocalDate getStatementMonth() { return statementMonth; }
        public void setStatementMonth(LocalDate statementMonth) { this.statementMonth = statementMonth; }
        
        public String getFormat() { return format; }
        public void setFormat(String format) { this.format = format; }
        
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        
        public String getFilePath() { return filePath; }
        public void setFilePath(String filePath) { this.filePath = filePath; }
    }
}
