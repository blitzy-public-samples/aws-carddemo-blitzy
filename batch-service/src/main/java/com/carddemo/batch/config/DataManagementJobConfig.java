/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.carddemo.batch.config;

import com.carddemo.common.batch.BatchOutputPathResolver;
import com.carddemo.common.batch.FailedOutputCleanupListener;
import com.carddemo.batch.batch.CategoryBalanceReportWriter;
import com.carddemo.batch.batch.CobolRecordFormatter;
import com.carddemo.batch.batch.CombineTransactionsTasklet;
import com.carddemo.batch.batch.DailyTransactionRecordMapper;
import com.carddemo.batch.batch.DailyTransactionValidationProcessor;
import com.carddemo.batch.batch.LoggingItemWriter;
import com.carddemo.batch.batch.RecordDumpItemWriter;
import com.carddemo.batch.batch.TransactionDetailReportWriter;
import com.carddemo.batch.batch.TransactionReportItem;
import com.carddemo.batch.batch.TransactionReportItemProcessor;
import com.carddemo.batch.repository.AccountRepository;
import com.carddemo.batch.repository.CardRepository;
import com.carddemo.batch.repository.CardXrefRepository;
import com.carddemo.batch.repository.CustomerRepository;
import com.carddemo.batch.repository.TranCatBalRepository;
import com.carddemo.common.config.CorrelationIdContext;
import com.carddemo.common.domain.Account;
import com.carddemo.common.domain.Card;
import com.carddemo.common.domain.CardXref;
import com.carddemo.common.domain.Customer;
import com.carddemo.common.domain.DailyTransaction;
import com.carddemo.common.domain.TranCatBal;
import com.carddemo.common.domain.Transaction;

import jakarta.persistence.EntityManagerFactory;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.data.RepositoryItemReader;
import org.springframework.batch.infrastructure.item.data.builder.RepositoryItemReaderBuilder;
import org.springframework.batch.infrastructure.item.database.JpaPagingItemReader;
import org.springframework.batch.infrastructure.item.database.builder.JpaPagingItemReaderBuilder;
import org.springframework.batch.infrastructure.item.file.FlatFileItemReader;
import org.springframework.batch.infrastructure.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * :purpose: Spring Batch configuration that wires the CardDemo data-management
 *  batch tier: the four sequential read-and-print "dump" jobs, the
 *  daily-transaction validation-read pass, the transaction-category-balance
 *  report, the transaction detail report, and the transaction combine job. Each
 *  job is the Java analogue of a legacy JCL job stream driving a COBOL program:
 *  ``READACCT``/``CBACT01C`` (account master), ``READCARD``/``CBACT02C`` (card
 *  master), ``READXREF``/``CBACT03C`` (card cross-reference),
 *  ``READCUST``/``CBCUS01C`` (customer master), ``CBTRN01C`` (daily-transaction
 *  validation-read), ``PRTCATBL`` (category-balance report), ``CBTRN03C`` driven
 *  by ``TRANREPT.prc`` (transaction detail report), and ``COMBTRAN`` (transaction
 *  combine). This class only assembles ``Job`` and ``Step`` beans from the
 *  readers, processors, writers, and tasklet in ``com.carddemo.batch.batch`` and
 *  the repositories in ``com.carddemo.batch.repository``; it holds no business
 *  arithmetic.
 * :output: Exactly eight ``Job`` beans and eight matching ``Step`` beans, each job
 *  carrying a correlation-id ``JobExecutionListener`` for structured, traceable
 *  logging. ``JobRepository``, the batch ``PlatformTransactionManager``, and the
 *  JPA ``EntityManagerFactory`` are supplied by Spring Boot batch
 *  auto-configuration and injected as ``@Bean`` method parameters; no batch
 *  infrastructure is self-instantiated and ``@EnableBatchProcessing`` is
 *  intentionally absent so the Boot auto-configuration stays active.
 */
@Configuration
public class DataManagementJobConfig {

    /**
     * Shared chunk commit interval and reader page size for every
     * data-management step, sized to stream the master and feed tables in
     * bounded pages while completing within the batch window.
     */
    private static final int PAGE_SIZE = 100;

    /**
     * :purpose: Build a ``JobExecutionListener`` that binds the incoming
     *  ``correlationId`` job parameter to the logging MDC for the duration of a
     *  job run, so every log line emitted by the job's steps, readers,
     *  processors, and writers is stamped with a single correlation id; a job
     *  launched without a correlation id is assigned a generated one.
     * :output: A ``JobExecutionListener`` whose ``beforeJob`` seeds the
     *  correlation id from the ``correlationId`` job parameter (or generates one
     *  when the parameter is absent or blank) and whose ``afterJob`` clears it.
     *  All access is through the static {@link CorrelationIdContext}; the returned
     *  listener is attached to every job in this configuration.
     */
    private JobExecutionListener correlationIdJobListener() {
        return new JobExecutionListener() {
            @Override
            public void beforeJob(JobExecution jobExecution) {
                String correlationId = jobExecution.getJobParameters()
                        .getString(CorrelationIdContext.CORRELATION_ID_KEY);
                if (correlationId != null && !correlationId.isBlank()) {
                    CorrelationIdContext.setCorrelationId(correlationId);
                } else {
                    CorrelationIdContext.getOrCreateCorrelationId();
                }
            }

            @Override
            public void afterJob(JobExecution jobExecution) {
                CorrelationIdContext.clear();
            }
        };
    }

    // -----------------------------------------------------------------------
    // Account read-and-print job (READACCT + CBACT01C)
    // -----------------------------------------------------------------------

    /**
     * :purpose: Remove the artifact a step was writing when that step does not complete
     *  successfully, for every job whose destination is the ``outputFile`` job parameter
     *  (the ``READACCT``/``READCARD``/``READXREF``/``READCUST`` dumps, the ``PRTCATBL``
     *  report and the ``COMBTRAN`` combined print). Without it a failed run left a file
     *  carrying only the legacy start and end banners - the exact shape of a successful
     *  run over an empty input - which no downstream reader could tell apart.
     * :param outputFile: the requested output file, bound late from the ``outputFile`` job
     *  parameter.
     * :param pathResolver: resolver confining the name to the batch output root, so the
     *  listener addresses exactly the file the writer opened.
     * :returns: the cleanup listener for one step execution.
     * :note: ``@StepScope`` is required because the destination is a job parameter: a
     *  singleton listener would be bound to whichever execution created it and could
     *  delete another run's file.
     */
    @Bean
    @StepScope
    public FailedOutputCleanupListener outputFileCleanupListener(
            @Value("#{jobParameters['outputFile']}") String outputFile,
            BatchOutputPathResolver pathResolver) {
        return new FailedOutputCleanupListener(pathResolver.resolveOutput(outputFile));
    }

    /**
     * :purpose: Remove the transaction-detail report when ``transactionDetailReportStep``
     *  does not complete successfully. Its destination is the ``reportFile`` job
     *  parameter rather than ``outputFile``, so it needs its own binding.
     * :param reportFile: the requested report file, bound late from the ``reportFile``
     *  job parameter.
     * :param pathResolver: resolver confining the name to the batch output root.
     * :returns: the cleanup listener for one report step execution.
     */
    @Bean
    @StepScope
    public FailedOutputCleanupListener reportFileCleanupListener(
            @Value("#{jobParameters['reportFile']}") String reportFile,
            BatchOutputPathResolver pathResolver) {
        return new FailedOutputCleanupListener(pathResolver.resolveOutput(reportFile));
    }

    /**
     * :purpose: Page through the account master ordered by ``acctId`` for the
     *  account read-and-print job, reproducing the sequential VSAM read of
     *  ``CBACT01C`` driven by ``READACCT``.
     * :param accountRepository: paging repository over the account master.
     * :returns: a ``RepositoryItemReader`` streaming every ``Account`` in
     *  ascending ``acctId`` order.
     * :note: ``@StepScope`` is required, not merely convenient: a
     *  ``RepositoryItemReader`` is an ``ItemStream`` that holds the page cursor of
     *  the read it is performing, so a singleton instance is ONE cursor shared by
     *  every concurrent step execution. Two runs launched together then consumed
     *  each other's pages - one dump repeated rows, another was left with nothing
     *  but its banner lines - while both executions still reported COMPLETED. A
     *  step-scoped bean gives each execution its own cursor.
     */
    @Bean
    @StepScope
    public RepositoryItemReader<Account> accountReader(AccountRepository accountRepository) {
        return new RepositoryItemReaderBuilder<Account>()
                .name("accountReader")
                .repository(accountRepository)
                .methodName("findAll")
                .sorts(Map.of("acctId", Sort.Direction.ASC))
                .pageSize(PAGE_SIZE)
                .build();
    }

    /**
     * :purpose: Build the ``@StepScope`` record-dump writer for the account
     *  read-and-print job, resolving and confining the requested ``outputFile``
     *  job parameter to the configured batch output root and reproducing the
     *  ``CBACT01C`` labelled ``DISPLAY`` dump with its start and end banners.
     * :param outputFile: the requested output file, bound late from the
     *  ``outputFile`` job parameter.
     * :param pathResolver: resolver that confines the path to the output root.
     * :returns: a ``RecordDumpItemWriter`` writing the account dump.
     */
    @Bean
    @StepScope
    public RecordDumpItemWriter<Account> accountDumpWriter(
            @Value("#{jobParameters['outputFile']}") String outputFile,
            BatchOutputPathResolver pathResolver) {
        Path resolved = pathResolver.resolveOutput(outputFile);
        return new RecordDumpItemWriter<>("accountDumpWriter", resolved,
                "START OF EXECUTION OF PROGRAM CBACT01C",
                "END OF EXECUTION OF PROGRAM CBACT01C",
                CobolRecordFormatter::accountDump);
    }

    /**
     * :purpose: Chunk-oriented step that reads every account and prints each to
     *  the account dump output file; mirrors the ``CBACT01C`` read/``DISPLAY``
     *  loop with no mutation.
     * :param jobRepository: batch job repository (Boot auto-configured).
     * :param transactionManager: batch transaction manager (Boot auto-configured).
     * :param accountReader: reader streaming accounts in ``acctId`` order.
     * :param accountDumpWriter: writer printing each account to the dump file.
     * :param outputFileCleanupListener: step-scoped listener removing the dump file
     *  when the step does not complete successfully.
     * :returns: the ``accountReadStep`` ``Step``.
     */
    @Bean
    public Step accountReadStep(JobRepository jobRepository,
                                PlatformTransactionManager transactionManager,
                                RepositoryItemReader<Account> accountReader,
                                RecordDumpItemWriter<Account> accountDumpWriter,
                                FailedOutputCleanupListener outputFileCleanupListener) {
        return new StepBuilder("accountReadStep", jobRepository)
                .<Account, Account>chunk(PAGE_SIZE).transactionManager(transactionManager)
                .reader(accountReader)
                .writer(accountDumpWriter)
                .listener((StepExecutionListener) outputFileCleanupListener)
                .build();
    }

    /**
     * :purpose: Account read-and-print job (``READACCT`` + ``CBACT01C``).
     * :param jobRepository: batch job repository (Boot auto-configured).
     * :param accountReadStep: the single step of this job.
     * :returns: the ``accountReadJob`` ``Job`` with the correlation-id listener
     *  attached.
     */
    @Bean
    public Job accountReadJob(JobRepository jobRepository, Step accountReadStep) {
        return new JobBuilder("accountReadJob", jobRepository)
                .listener(correlationIdJobListener())
                .start(accountReadStep)
                .build();
    }

    // -----------------------------------------------------------------------
    // Card read-and-print job (READCARD + CBACT02C)
    // -----------------------------------------------------------------------

    /**
     * :purpose: Page through the card master ordered by ``cardNum`` for the card
     *  read-and-print job, reproducing the sequential VSAM read of ``CBACT02C``
     *  driven by ``READCARD``.
     * :param cardRepository: paging repository over the card master.
     * :returns: a ``RepositoryItemReader`` streaming every ``Card`` in ascending
     *  ``cardNum`` order.
     * :note: ``@StepScope`` is required, not merely convenient: a
     *  ``RepositoryItemReader`` is an ``ItemStream`` that holds the page cursor of
     *  the read it is performing, so a singleton instance is ONE cursor shared by
     *  every concurrent step execution. Two runs launched together then consumed
     *  each other's pages - one dump repeated rows, another was left with nothing
     *  but its banner lines - while both executions still reported COMPLETED. A
     *  step-scoped bean gives each execution its own cursor.
     */
    @Bean
    @StepScope
    public RepositoryItemReader<Card> cardReader(CardRepository cardRepository) {
        return new RepositoryItemReaderBuilder<Card>()
                .name("cardReader")
                .repository(cardRepository)
                .methodName("findAll")
                .sorts(Map.of("cardNum", Sort.Direction.ASC))
                .pageSize(PAGE_SIZE)
                .build();
    }

    /**
     * :purpose: Build the ``@StepScope`` record-dump writer for the card
     *  read-and-print job, resolving and confining the requested ``outputFile``
     *  job parameter to the configured batch output root and reproducing the
     *  ``CBACT02C`` whole-record ``DISPLAY CARD-RECORD`` dump with its start and
     *  end banners.
     * :param outputFile: the requested output file, bound late from the
     *  ``outputFile`` job parameter.
     * :param pathResolver: resolver that confines the path to the output root.
     * :returns: a ``RecordDumpItemWriter`` writing the card dump.
     */
    @Bean
    @StepScope
    public RecordDumpItemWriter<Card> cardDumpWriter(
            @Value("#{jobParameters['outputFile']}") String outputFile,
            BatchOutputPathResolver pathResolver) {
        Path resolved = pathResolver.resolveOutput(outputFile);
        return new RecordDumpItemWriter<>("cardDumpWriter", resolved,
                "START OF EXECUTION OF PROGRAM CBACT02C",
                "END OF EXECUTION OF PROGRAM CBACT02C",
                CobolRecordFormatter::cardRecord);
    }

    /**
     * :purpose: Chunk-oriented step that reads every card and prints each to the
     *  card dump output file; mirrors the ``CBACT02C`` read/``DISPLAY`` loop with
     *  no mutation.
     * :param jobRepository: batch job repository (Boot auto-configured).
     * :param transactionManager: batch transaction manager (Boot auto-configured).
     * :param cardReader: reader streaming cards in ``cardNum`` order.
     * :param cardDumpWriter: writer printing each card to the dump file.
     * :param outputFileCleanupListener: step-scoped listener removing the dump file
     *  when the step does not complete successfully.
     * :returns: the ``cardReadStep`` ``Step``.
     */
    @Bean
    public Step cardReadStep(JobRepository jobRepository,
                             PlatformTransactionManager transactionManager,
                             RepositoryItemReader<Card> cardReader,
                             RecordDumpItemWriter<Card> cardDumpWriter,
                             FailedOutputCleanupListener outputFileCleanupListener) {
        return new StepBuilder("cardReadStep", jobRepository)
                .<Card, Card>chunk(PAGE_SIZE).transactionManager(transactionManager)
                .reader(cardReader)
                .writer(cardDumpWriter)
                .listener((StepExecutionListener) outputFileCleanupListener)
                .build();
    }

    /**
     * :purpose: Card read-and-print job (``READCARD`` + ``CBACT02C``).
     * :param jobRepository: batch job repository (Boot auto-configured).
     * :param cardReadStep: the single step of this job.
     * :returns: the ``cardReadJob`` ``Job`` with the correlation-id listener
     *  attached.
     */
    @Bean
    public Job cardReadJob(JobRepository jobRepository, Step cardReadStep) {
        return new JobBuilder("cardReadJob", jobRepository)
                .listener(correlationIdJobListener())
                .start(cardReadStep)
                .build();
    }

    // -----------------------------------------------------------------------
    // Card cross-reference read-and-print job (READXREF + CBACT03C)
    // -----------------------------------------------------------------------

    /**
     * :purpose: Page through the card cross-reference ordered by ``xrefCardNum``
     *  for the cross-reference read-and-print job, reproducing the sequential VSAM
     *  read of ``CBACT03C`` driven by ``READXREF``.
     * :param cardXrefRepository: paging repository over the card cross-reference.
     * :returns: a ``RepositoryItemReader`` streaming every ``CardXref`` in
     *  ascending ``xrefCardNum`` order.
     * :note: ``@StepScope`` is required, not merely convenient: a
     *  ``RepositoryItemReader`` is an ``ItemStream`` that holds the page cursor of
     *  the read it is performing, so a singleton instance is ONE cursor shared by
     *  every concurrent step execution. Two runs launched together then consumed
     *  each other's pages - one dump repeated rows, another was left with nothing
     *  but its banner lines - while both executions still reported COMPLETED. A
     *  step-scoped bean gives each execution its own cursor.
     */
    @Bean
    @StepScope
    public RepositoryItemReader<CardXref> cardXrefReader(CardXrefRepository cardXrefRepository) {
        return new RepositoryItemReaderBuilder<CardXref>()
                .name("cardXrefReader")
                .repository(cardXrefRepository)
                .methodName("findAll")
                .sorts(Map.of("xrefCardNum", Sort.Direction.ASC))
                .pageSize(PAGE_SIZE)
                .build();
    }

    /**
     * :purpose: Build the ``@StepScope`` record-dump writer for the card
     *  cross-reference read-and-print job, resolving and confining the requested
     *  ``outputFile`` job parameter to the configured batch output root and
     *  reproducing the ``CBACT03C`` whole-record ``DISPLAY CARD-XREF-RECORD`` dump
     *  with its start and end banners.
     * :param outputFile: the requested output file, bound late from the
     *  ``outputFile`` job parameter.
     * :param pathResolver: resolver that confines the path to the output root.
     * :returns: a ``RecordDumpItemWriter`` writing the card cross-reference dump.
     */
    @Bean
    @StepScope
    public RecordDumpItemWriter<CardXref> cardXrefDumpWriter(
            @Value("#{jobParameters['outputFile']}") String outputFile,
            BatchOutputPathResolver pathResolver) {
        Path resolved = pathResolver.resolveOutput(outputFile);
        return new RecordDumpItemWriter<>("cardXrefDumpWriter", resolved,
                "START OF EXECUTION OF PROGRAM CBACT03C",
                "END OF EXECUTION OF PROGRAM CBACT03C",
                CobolRecordFormatter::cardXrefRecord);
    }

    /**
     * :purpose: Chunk-oriented step that reads every card cross-reference record
     *  and prints each to the cross-reference dump output file; mirrors the
     *  ``CBACT03C`` read/``DISPLAY`` loop with no mutation.
     * :param jobRepository: batch job repository (Boot auto-configured).
     * :param transactionManager: batch transaction manager (Boot auto-configured).
     * :param cardXrefReader: reader streaming cross-reference records in
     *  ``xrefCardNum`` order.
     * :param cardXrefDumpWriter: writer printing each cross-reference record to
     *  the dump file.
     * :param outputFileCleanupListener: step-scoped listener removing the dump file
     *  when the step does not complete successfully.
     * :returns: the ``cardXrefReadStep`` ``Step``.
     */
    @Bean
    public Step cardXrefReadStep(JobRepository jobRepository,
                                 PlatformTransactionManager transactionManager,
                                 RepositoryItemReader<CardXref> cardXrefReader,
                                 RecordDumpItemWriter<CardXref> cardXrefDumpWriter,
                                 FailedOutputCleanupListener outputFileCleanupListener) {
        return new StepBuilder("cardXrefReadStep", jobRepository)
                .<CardXref, CardXref>chunk(PAGE_SIZE).transactionManager(transactionManager)
                .reader(cardXrefReader)
                .writer(cardXrefDumpWriter)
                .listener((StepExecutionListener) outputFileCleanupListener)
                .build();
    }

    /**
     * :purpose: Card cross-reference read-and-print job (``READXREF`` +
     *  ``CBACT03C``).
     * :param jobRepository: batch job repository (Boot auto-configured).
     * :param cardXrefReadStep: the single step of this job.
     * :returns: the ``cardXrefReadJob`` ``Job`` with the correlation-id listener
     *  attached.
     */
    @Bean
    public Job cardXrefReadJob(JobRepository jobRepository, Step cardXrefReadStep) {
        return new JobBuilder("cardXrefReadJob", jobRepository)
                .listener(correlationIdJobListener())
                .start(cardXrefReadStep)
                .build();
    }

    // -----------------------------------------------------------------------
    // Customer read-and-print job (READCUST + CBCUS01C)
    // -----------------------------------------------------------------------

    /**
     * :purpose: Page through the customer master ordered by ``custId`` for the
     *  customer read-and-print job, reproducing the sequential VSAM read of
     *  ``CBCUS01C`` driven by ``READCUST``.
     * :param customerRepository: paging repository over the customer master.
     * :returns: a ``RepositoryItemReader`` streaming every ``Customer`` in
     *  ascending ``custId`` order.
     * :note: ``@StepScope`` is required, not merely convenient: a
     *  ``RepositoryItemReader`` is an ``ItemStream`` that holds the page cursor of
     *  the read it is performing, so a singleton instance is ONE cursor shared by
     *  every concurrent step execution. Two runs launched together then consumed
     *  each other's pages - one dump repeated rows, another was left with nothing
     *  but its banner lines - while both executions still reported COMPLETED. A
     *  step-scoped bean gives each execution its own cursor.
     */
    @Bean
    @StepScope
    public RepositoryItemReader<Customer> customerReader(CustomerRepository customerRepository) {
        return new RepositoryItemReaderBuilder<Customer>()
                .name("customerReader")
                .repository(customerRepository)
                .methodName("findAll")
                .sorts(Map.of("custId", Sort.Direction.ASC))
                .pageSize(PAGE_SIZE)
                .build();
    }

    /**
     * :purpose: Build the ``@StepScope`` record-dump writer for the customer
     *  read-and-print job, resolving and confining the requested ``outputFile``
     *  job parameter to the configured batch output root and reproducing the
     *  ``CBCUS01C`` whole-record ``DISPLAY CUSTOMER-RECORD`` dump with its start
     *  and end banners.
     * :param outputFile: the requested output file, bound late from the
     *  ``outputFile`` job parameter.
     * :param pathResolver: resolver that confines the path to the output root.
     * :returns: a ``RecordDumpItemWriter`` writing the customer dump.
     */
    @Bean
    @StepScope
    public RecordDumpItemWriter<Customer> customerDumpWriter(
            @Value("#{jobParameters['outputFile']}") String outputFile,
            BatchOutputPathResolver pathResolver) {
        Path resolved = pathResolver.resolveOutput(outputFile);
        return new RecordDumpItemWriter<>("customerDumpWriter", resolved,
                "START OF EXECUTION OF PROGRAM CBCUS01C",
                "END OF EXECUTION OF PROGRAM CBCUS01C",
                CobolRecordFormatter::customerRecord);
    }

    /**
     * :purpose: Chunk-oriented step that reads every customer and prints each to
     *  the customer dump output file; mirrors the ``CBCUS01C`` read/``DISPLAY``
     *  loop with no mutation.
     * :param jobRepository: batch job repository (Boot auto-configured).
     * :param transactionManager: batch transaction manager (Boot auto-configured).
     * :param customerReader: reader streaming customers in ``custId`` order.
     * :param customerDumpWriter: writer printing each customer to the dump file.
     * :param outputFileCleanupListener: step-scoped listener removing the dump file
     *  when the step does not complete successfully.
     * :returns: the ``customerReadStep`` ``Step``.
     */
    @Bean
    public Step customerReadStep(JobRepository jobRepository,
                                 PlatformTransactionManager transactionManager,
                                 RepositoryItemReader<Customer> customerReader,
                                 RecordDumpItemWriter<Customer> customerDumpWriter,
                                 FailedOutputCleanupListener outputFileCleanupListener) {
        return new StepBuilder("customerReadStep", jobRepository)
                .<Customer, Customer>chunk(PAGE_SIZE).transactionManager(transactionManager)
                .reader(customerReader)
                .writer(customerDumpWriter)
                .listener((StepExecutionListener) outputFileCleanupListener)
                .build();
    }

    /**
     * :purpose: Customer read-and-print job (``READCUST`` + ``CBCUS01C``).
     * :param jobRepository: batch job repository (Boot auto-configured).
     * :param customerReadStep: the single step of this job.
     * :returns: the ``customerReadJob`` ``Job`` with the correlation-id listener
     *  attached.
     */
    @Bean
    public Job customerReadJob(JobRepository jobRepository, Step customerReadStep) {
        return new JobBuilder("customerReadJob", jobRepository)
                .listener(correlationIdJobListener())
                .start(customerReadStep)
                .build();
    }

    // -----------------------------------------------------------------------
    // Daily-transaction validation-read job (CBTRN01C)
    // -----------------------------------------------------------------------

    /**
     * :purpose: Read the daily-transaction feed (``DALYTRAN``, copybook
     *  ``CVTRA06Y``, RECLN 350) sequentially for the validation-read pass,
     *  reproducing the sequential ``READ DALYTRAN-FILE`` of ``CBTRN01C``. The feed
     *  is a fixed-length flat file rather than a database table, so a restartable
     *  ``FlatFileItemReader`` parses each 350-character record through
     *  ``DailyTransactionRecordMapper``; the reader records its line position in
     *  the step execution context so a restart resumes after the last committed
     *  chunk.
     * :param inputFile: feed file name bound late from the ``inputFile`` job
     *  parameter; resolved and confined to the configured input root by
     *  ``pathResolver``.
     * :param pathResolver: resolver that confines the feed file to the allowlisted
     *  batch input root, rejecting absolute, ``..`` traversal, and symlink-escape
     *  paths (CWE-22).
     * :returns: a ``FlatFileItemReader`` streaming every ``DailyTransaction`` in
     *  the physical order of the feed file.
     * :note: The feed is read in file order, matching the sequential feed read of
     *  ``CBTRN01C``; the feed is produced in ascending ``DALYTRAN-ID`` order. It is
     *  decoded with a single-byte charset so each byte maps to exactly one
     *  character and the fixed field offsets stay aligned. ``@StepScope`` is
     *  required so the ``inputFile`` job parameter binds per step execution.
     */
    @Bean
    @StepScope
    public FlatFileItemReader<DailyTransaction> dailyTransactionValidationReader(
            @Value("#{jobParameters['inputFile']}") String inputFile,
            BatchOutputPathResolver pathResolver) {
        Path resolved = pathResolver.resolveInput(inputFile);
        return new FlatFileItemReaderBuilder<DailyTransaction>()
                .name("dailyTransactionValidationReader")
                .resource(new FileSystemResource(resolved))
                .encoding(StandardCharsets.ISO_8859_1.name())
                .lineMapper(new DailyTransactionRecordMapper())
                .strict(true)
                .saveState(true)
                .build();
    }

    /**
     * :purpose: Chunk-oriented validation-read step that confirms each daily
     *  transaction's card cross-reference and account exist, logs the outcome, and
     *  passes the record through unchanged to a count-only logging writer. This is
     *  a log-only pass migrated from ``CBTRN01C``: transaction posting and
     *  reject-file writing are owned by ``transaction-service`` (``CBTRN02C``) and
     *  are out of scope here, so no reject records are written and no balances are
     *  posted.
     * :param jobRepository: batch job repository (Boot auto-configured).
     * :param transactionManager: batch transaction manager (Boot auto-configured).
     * :param dailyTransactionValidationReader: reader streaming the
     *  daily-transaction feed in file order.
     * :param dailyTransactionValidationProcessor: pass-through validator that logs
     *  cross-reference and account lookup outcomes and returns the record
     *  unchanged.
     * :param dailyTransactionValidationWriter: step-scoped count-only writer.
     * :returns: the ``dailyTransactionValidationStep`` ``Step``.
     */
    @Bean
    public Step dailyTransactionValidationStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            FlatFileItemReader<DailyTransaction> dailyTransactionValidationReader,
            DailyTransactionValidationProcessor dailyTransactionValidationProcessor,
            LoggingItemWriter<DailyTransaction> dailyTransactionValidationWriter) {
        return new StepBuilder("dailyTransactionValidationStep", jobRepository)
                .<DailyTransaction, DailyTransaction>chunk(PAGE_SIZE).transactionManager(transactionManager)
                .reader(dailyTransactionValidationReader)
                .processor(dailyTransactionValidationProcessor)
                .writer(dailyTransactionValidationWriter)
                .build();
    }

    /**
     * :purpose: Build the count-only writer of the validation-read pass, the Java
     *  analogue of the legacy ``DISPLAY`` record sink of ``CBTRN01C``.
     * :returns: a ``LoggingItemWriter`` labelled ``dailyTransactionValidation``.
     * :note: ``@StepScope`` is required, not merely convenient: the writer carries
     *  the running record count of the pass it is performing. Constructed once for
     *  a singleton step it accumulated across every execution and interleaved
     *  between concurrent ones, so the count it reported was neither the run's nor
     *  any run's. A step-scoped bean gives each execution its own counter.
     */
    @Bean
    @StepScope
    public LoggingItemWriter<DailyTransaction> dailyTransactionValidationWriter() {
        return new LoggingItemWriter<>("dailyTransactionValidation");
    }

    /**
     * :purpose: Daily-transaction validation-read job (``CBTRN01C``). This is a
     *  validation-read, log-only job: reject-file writing and transaction posting
     *  are out of scope and are owned by ``transaction-service`` (``CBTRN02C``).
     * :param jobRepository: batch job repository (Boot auto-configured).
     * :param dailyTransactionValidationStep: the single step of this job.
     * :returns: the ``dailyTransactionValidationJob`` ``Job`` with the
     *  correlation-id listener attached.
     * :note: Requires the ``inputFile`` job parameter naming the daily-transaction
     *  feed file (confined to the configured batch input root), consumed by
     *  ``dailyTransactionValidationReader``.
     */
    @Bean
    public Job dailyTransactionValidationJob(JobRepository jobRepository,
                                             Step dailyTransactionValidationStep) {
        return new JobBuilder("dailyTransactionValidationJob", jobRepository)
                .listener(correlationIdJobListener())
                .start(dailyTransactionValidationStep)
                .build();
    }

    // -----------------------------------------------------------------------
    // Transaction-category-balance report job (PRTCATBL)
    // -----------------------------------------------------------------------

    /**
     * :purpose: Page through the transaction-category-balance table ordered by
     *  ``trancatAcctId``, ``trancatTypeCd``, ``trancatCd`` (all ascending) for the
     *  category-balance report, reproducing the composite sort key of
     *  ``PRTCATBL``. A ``LinkedHashMap`` is used so the three sort keys keep their
     *  declared order (unlike ``Map.of``, whose iteration order is undefined).
     * :param tranCatBalRepository: paging repository over the category-balance
     *  table.
     * :returns: a ``RepositoryItemReader`` streaming every ``TranCatBal`` in the
     *  composite ``trancatAcctId``/``trancatTypeCd``/``trancatCd`` order.
     * :note: ``@StepScope`` is required, not merely convenient: a
     *  ``RepositoryItemReader`` is an ``ItemStream`` that holds the page cursor of
     *  the read it is performing, so a singleton instance is ONE cursor shared by
     *  every concurrent step execution. Two runs launched together then consumed
     *  each other's pages - one dump repeated rows, another was left with nothing
     *  but its banner lines - while both executions still reported COMPLETED. A
     *  step-scoped bean gives each execution its own cursor.
     */
    @Bean
    @StepScope
    public RepositoryItemReader<TranCatBal> categoryBalanceReader(
            TranCatBalRepository tranCatBalRepository) {
        LinkedHashMap<String, Sort.Direction> sorts = new LinkedHashMap<>();
        sorts.put("trancatAcctId", Sort.Direction.ASC);
        sorts.put("trancatTypeCd", Sort.Direction.ASC);
        sorts.put("trancatCd", Sort.Direction.ASC);
        return new RepositoryItemReaderBuilder<TranCatBal>()
                .name("categoryBalanceReader")
                .repository(tranCatBalRepository)
                .methodName("findAll")
                .sorts(sorts)
                .pageSize(PAGE_SIZE)
                .build();
    }

    /**
     * :purpose: Chunk-oriented step that streams category balances in composite
     *  key order to the fixed-width report writer; migrated from ``PRTCATBL``.
     * :param jobRepository: batch job repository (Boot auto-configured).
     * :param transactionManager: batch transaction manager (Boot auto-configured).
     * :param categoryBalanceReader: reader streaming category balances in
     *  composite key order.
     * :param categoryBalanceReportWriter: step-scoped stream writer that renders
     *  the report to the ``outputFile`` job parameter; supplied directly as the
     *  step writer so the chunk step auto-registers it as an ``ItemStream``.
     * :param outputFileCleanupListener: step-scoped listener removing the report file
     *  when the step does not complete successfully.
     * :returns: the ``categoryBalanceReportStep`` ``Step``.
     */
    @Bean
    public Step categoryBalanceReportStep(JobRepository jobRepository,
                                          PlatformTransactionManager transactionManager,
                                          RepositoryItemReader<TranCatBal> categoryBalanceReader,
                                          CategoryBalanceReportWriter categoryBalanceReportWriter,
                                          FailedOutputCleanupListener outputFileCleanupListener) {
        return new StepBuilder("categoryBalanceReportStep", jobRepository)
                .<TranCatBal, TranCatBal>chunk(PAGE_SIZE).transactionManager(transactionManager)
                .reader(categoryBalanceReader)
                .writer(categoryBalanceReportWriter)
                .listener((StepExecutionListener) outputFileCleanupListener)
                .build();
    }

    /**
     * :purpose: Transaction-category-balance report job (``PRTCATBL``).
     * :param jobRepository: batch job repository (Boot auto-configured).
     * :param categoryBalanceReportStep: the single step of this job.
     * :returns: the ``categoryBalanceReportJob`` ``Job`` with the correlation-id
     *  listener attached.
     * :note: Requires the ``outputFile`` job parameter (report output path),
     *  consumed by ``CategoryBalanceReportWriter``.
     */
    @Bean
    public Job categoryBalanceReportJob(JobRepository jobRepository,
                                        Step categoryBalanceReportStep) {
        return new JobBuilder("categoryBalanceReportJob", jobRepository)
                .listener(correlationIdJobListener())
                .start(categoryBalanceReportStep)
                .build();
    }

    // -----------------------------------------------------------------------
    // Transaction combine job (COMBTRAN) — tasklet
    // -----------------------------------------------------------------------

    /**
     * :purpose: Tasklet step that merges the transaction backup and
     *  system-generated transaction inputs, orders them by ``tranId`` ascending,
     *  and loads the combined set into the transaction master; migrated from
     *  ``COMBTRAN``. The merge, ordering, and idempotent load are owned by
     *  ``CombineTransactionsTasklet``.
     * :param jobRepository: batch job repository (Boot auto-configured).
     * :param transactionManager: batch transaction manager (Boot auto-configured).
     * :param combineTransactionsTasklet: tasklet performing the merge, ordering,
     *  and load.
     * :param outputFileCleanupListener: step-scoped listener removing the combined print
     *  when the step does not complete successfully.
     * :returns: the ``combineTransactionsStep`` ``Step``.
     */
    @Bean
    public Step combineTransactionsStep(JobRepository jobRepository,
                                        PlatformTransactionManager transactionManager,
                                        CombineTransactionsTasklet combineTransactionsTasklet,
                                        FailedOutputCleanupListener outputFileCleanupListener) {
        return new StepBuilder("combineTransactionsStep", jobRepository)
                .tasklet(combineTransactionsTasklet, transactionManager)
                .listener((StepExecutionListener) outputFileCleanupListener)
                .build();
    }

    /**
     * :purpose: Transaction combine job (``COMBTRAN``).
     * :param jobRepository: batch job repository (Boot auto-configured).
     * :param combineTransactionsStep: the single tasklet step of this job.
     * :returns: the ``combineTransactionsJob`` ``Job`` with the correlation-id
     *  listener attached.
     */
    @Bean
    public Job combineTransactionsJob(JobRepository jobRepository,
                                      Step combineTransactionsStep) {
        return new JobBuilder("combineTransactionsJob", jobRepository)
                .listener(correlationIdJobListener())
                .start(combineTransactionsStep)
                .build();
    }

    // -----------------------------------------------------------------------
    // Transaction detail report job (CBTRN03C via TRANREPT.prc)
    // -----------------------------------------------------------------------

    /**
     * :purpose: Page through posted transactions whose processing-timestamp date
     *  (``SUBSTRING(tranProcTs, 1, 10)``) falls inclusively within the requested
     *  ``startDate``/``endDate`` range, ordered by ``tranCardNum`` so the writer
     *  can drive its per-card control break; reproduces the date-range filter and
     *  card ordering of ``CBTRN03C`` under the ``TRANREPT.prc`` ``DATEPARM``
     *  contract. The 26-character timestamp's first ten characters are the ISO
     *  ``YYYY-MM-DD`` date, whose lexicographic comparison equals chronological
     *  comparison; JPQL ``SUBSTRING`` is 1-indexed (start ``1``, length ``10``).
     * :param entityManagerFactory: JPA entity-manager factory backing the paging
     *  query.
     * :param startDate: inclusive range start (``YYYY-MM-DD``) bound late from the
     *  ``startDate`` job parameter.
     * :param endDate: inclusive range end (``YYYY-MM-DD``) bound late from the
     *  ``endDate`` job parameter.
     * :returns: a ``JpaPagingItemReader`` streaming the in-range transactions in
     *  ascending ``tranCardNum`` order.
     * :note: State saving is disabled (``saveState(false)``) so that a restart
     *  re-reads the range from the beginning. ``TransactionDetailReportWriter``
     *  truncates and regenerates the whole report on ``open``, and its
     *  control-break, pagination, and running-total state are not persisted; a
     *  restart therefore regenerates the complete report rather than resuming
     *  mid-file, which would otherwise drop the already-read rows. This matches
     *  the wholesale full-report regeneration of the legacy ``CBTRN03C`` run.
     * :note: The primary key is the ordering tie-breaker because a paging reader
     *  issues one windowed query per page: with several transactions sharing a
     *  card number — the normal case — a sort on the card number alone leaves
     *  tied rows in an order the database may choose differently for each page,
     *  which repeats one row and drops another across a page boundary. Ordering
     *  by ``tranId`` within a card group also matches the order the legacy
     *  sequential ``TRANSACT`` KSDS read delivers records in.
     */
    @Bean
    @StepScope
    public JpaPagingItemReader<Transaction> transactionDetailReportReader(
            EntityManagerFactory entityManagerFactory,
            @Value("#{jobParameters['startDate']}") String startDate,
            @Value("#{jobParameters['endDate']}") String endDate) {
        return new JpaPagingItemReaderBuilder<Transaction>()
                .name("transactionDetailReportReader")
                .entityManagerFactory(entityManagerFactory)
                .queryString("SELECT t FROM Transaction t "
                        + "WHERE SUBSTRING(t.tranProcTs, 1, 10) >= :startDate "
                        + "AND SUBSTRING(t.tranProcTs, 1, 10) <= :endDate "
                        + "ORDER BY t.tranCardNum, t.tranId")
                .parameterValues(Map.of("startDate", startDate, "endDate", endDate))
                .pageSize(PAGE_SIZE)
                .saveState(false)
                .build();
    }

    /**
     * :purpose: Chunk-oriented step that enriches each in-range transaction with
     *  its cross-reference account id and transaction type/category descriptions,
     *  then writes the paginated detail report; migrated from ``CBTRN03C``. All
     *  page-break, page-total, per-card account-total, and grand-total logic is
     *  owned by ``TransactionDetailReportWriter``; this step performs no
     *  pagination arithmetic.
     * :param jobRepository: batch job repository (Boot auto-configured).
     * :param transactionManager: batch transaction manager (Boot auto-configured).
     * :param transactionDetailReportReader: step-scoped reader streaming in-range
     *  transactions in ``tranCardNum`` order.
     * :param transactionReportItemProcessor: three-way lookup join
     *  (cross-reference, transaction type, transaction category) producing a
     *  ``TransactionReportItem`` per transaction.
     * :param transactionDetailReportWriter: step-scoped stream writer rendering
     *  the fixed-width report; supplied directly as the step writer so the chunk
     *  step auto-registers it as an ``ItemStream``.
     * :param reportFileCleanupListener: step-scoped listener removing the report file
     *  when the step does not complete successfully.
     * :returns: the ``transactionDetailReportStep`` ``Step``.
     */
    @Bean
    public Step transactionDetailReportStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            JpaPagingItemReader<Transaction> transactionDetailReportReader,
            TransactionReportItemProcessor transactionReportItemProcessor,
            TransactionDetailReportWriter transactionDetailReportWriter,
            FailedOutputCleanupListener reportFileCleanupListener) {
        return new StepBuilder("transactionDetailReportStep", jobRepository)
                .<Transaction, TransactionReportItem>chunk(PAGE_SIZE).transactionManager(transactionManager)
                .reader(transactionDetailReportReader)
                .processor(transactionReportItemProcessor)
                .writer(transactionDetailReportWriter)
                .listener((StepExecutionListener) reportFileCleanupListener)
                .build();
    }

    /**
     * :purpose: Transaction detail report job (``CBTRN03C`` via ``TRANREPT.prc``).
     * :param jobRepository: batch job repository (Boot auto-configured).
     * :param transactionDetailReportStep: the single step of this job.
     * :returns: the ``transactionDetailReportJob`` ``Job`` with the correlation-id
     *  listener attached.
     * :note: Requires job parameters ``startDate`` and ``endDate`` (both
     *  ``YYYY-MM-DD``, from the ``DATEPARM`` contract) and ``reportFile`` (report
     *  output path consumed by the writer); ``correlationId`` is an optional
     *  non-identifying observability parameter. Parameter defaults are supplied by
     *  ``JobSchedulingConfig``, not by this configuration.
     */
    @Bean
    public Job transactionDetailReportJob(JobRepository jobRepository,
                                          Step transactionDetailReportStep) {
        return new JobBuilder("transactionDetailReportJob", jobRepository)
                .listener(correlationIdJobListener())
                .start(transactionDetailReportStep)
                .build();
    }
}
