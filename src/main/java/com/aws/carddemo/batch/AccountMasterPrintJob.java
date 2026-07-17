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
package com.aws.carddemo.batch;

import java.util.Map;

import com.aws.carddemo.domain.Account;
import com.aws.carddemo.repository.AccountRepository;
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

/**
 * Spring Batch job configuration that re-platforms the legacy COBOL batch program
 * {@code CBACT01C} ("Read and print account data file", source
 * {@code legacy/cbl/CBACT01C.cbl}) onto Spring Batch (AAP sections 0.4.4 and 0.5.4).
 *
 * <h2>Legacy behavior reproduced (CBACT01C)</h2>
 * The mainframe program opens the indexed {@code ACCTFILE} (VSAM KSDS
 * {@code ACCTDATA.VSAM.KSDS}, copybook {@code CVACT01Y.cpy}) as {@code INPUT}
 * ({@code 0000-ACCTFILE-OPEN}), reads it sequentially in ascending record-key
 * ({@code FD-ACCT-ID}) order ({@code 1000-ACCTFILE-GET-NEXT}) until end-of-file, and
 * for every record prints each field followed by a dashed separator
 * ({@code 1100-DISPLAY-ACCT-RECORD}, CBACT01C L118-131). It performs <strong>no
 * mutation</strong>: the file is opened read-only and no record is ever written or
 * rewritten. Clean completion is COBOL return code {@code 0}; an I/O failure abends via
 * {@code CEE3ABD} (return code {@code 8}).
 *
 * <h2>Target design</h2>
 * <ul>
 *   <li><strong>Set-based sequential read.</strong> The VSAM ascending-key sequential
 *       browse becomes a chunk-oriented {@link RepositoryItemReader} over
 *       {@link AccountRepository}, paged and ordered by the primary-key property
 *       {@code acctId} ascending &mdash; the exact analog of the KSDS
 *       {@code RECORD KEY IS FD-ACCT-ID} sequential read (see
 *       {@link #accountMasterPrintReader(AccountRepository)}).</li>
 *   <li><strong>Print step.</strong> An {@link ItemWriter} logs every field of each
 *       {@link Account} in the exact order and with the exact labels emitted by the
 *       COBOL {@code 1100-DISPLAY-ACCT-RECORD} paragraph, followed by the 49-character
 *       dashed separator. Nothing is persisted (see
 *       {@link #accountMasterPrintWriter()}).</li>
 *   <li><strong>I/O-failure semantics.</strong> A read failure surfaces as an exception
 *       that fails the step/job (a {@code FAILED} {@code BatchStatus}), the Spring Batch
 *       equivalent of the legacy {@code CEE3ABD} abend and its non-zero return code; a
 *       clean pass completes {@code COMPLETED}, the analog of return code {@code 0}.</li>
 * </ul>
 *
 * <h2>Infrastructure contract</h2>
 * <ul>
 *   <li>This class carries only {@link Configuration @Configuration}; it deliberately
 *       does <strong>not</strong> declare {@code @EnableBatchProcessing}. Declaring it
 *       anywhere would make Spring Boot's batch auto-configuration back off. The
 *       {@link JobRepository} and the batch {@link PlatformTransactionManager} injected
 *       into the bean methods below are the auto-configured infrastructure beans; none
 *       are redeclared here (that responsibility, and its rationale, lives in
 *       {@code com.aws.carddemo.config.BatchConfig}).</li>
 *   <li>The job never runs at application startup because
 *       {@code spring.batch.job.enabled=false} in {@code application.yml}; it is launched
 *       explicitly (via {@code JobLauncher}/{@code JobOperator} or the CI/CD workflow, the
 *       modern equivalent of the JCL scheduler per AAP 0.4.4).</li>
 *   <li>The {@link CorrelationIdJobListener} (same package) is registered on the job so
 *       every batch log line carries a correlation id across the job/step boundary
 *       (Observability rule, AAP 0.9.5).</li>
 * </ul>
 *
 * <p>This is one of four structurally identical read-only master-print jobs
 * (account / card / cross-reference / customer). It is intentionally a trivial
 * read&rarr;log pass ordered by primary key, adding no business logic beyond faithful
 * reproduction of the COBOL display.</p>
 *
 * @see AccountRepository
 * @see Account
 * @see CorrelationIdJobListener
 */
@Configuration("accountMasterPrintJobConfig")
public class AccountMasterPrintJob {

    /** SLF4J logger used by the print writer to reproduce the COBOL {@code DISPLAY} output. */
    private static final Logger LOGGER = LoggerFactory.getLogger(AccountMasterPrintJob.class);

    /**
     * Job bean name. Exactly {@code "accountMasterPrintJob"} so it can be launched by name
     * through {@code JobOperator}/{@code JobRegistry} (the JCL-scheduling equivalent) and
     * matches the AAP-specified public API.
     */
    private static final String JOB_NAME = "accountMasterPrintJob";

    /** Step bean name for the single chunk-oriented read&rarr;print step. */
    private static final String STEP_NAME = "accountMasterPrintStep";

    /**
     * Reader name. Used by {@link RepositoryItemReader} (via
     * {@link org.springframework.batch.item.support.AbstractItemCountingItemStreamItemReader})
     * as the {@code ExecutionContext} key prefix under which paging save-state is persisted,
     * so it must be stable across restarts.
     */
    private static final String READER_NAME = "accountMasterPrintReader";

    /**
     * JPA entity property that maps to the COBOL {@code ACCT-ID} primary key
     * ({@code PIC 9(11)}, VSAM {@code RECORD KEY IS FD-ACCT-ID}). Sorting the reader by this
     * property ascending reproduces the KSDS ascending-key sequential read of CBACT01C.
     */
    private static final String ACCT_ID_PROPERTY = "acctId";

    /**
     * Repository method the reader invokes for each page. {@code "findAll"} resolves to the
     * inherited {@code PagingAndSortingRepository.findAll(Pageable)}; {@link RepositoryItemReader}
     * supplies a {@code PageRequest} built from {@link #ACCT_ID_PROPERTY} + {@link #READER_PAGE_SIZE},
     * yielding accounts in ascending primary-key order.
     */
    private static final String FIND_ALL_METHOD = "findAll";

    /**
     * Chunk (commit-interval) size for the print step. The step performs no writes, so this
     * governs only how often Spring Batch checkpoints its metadata while streaming; a page of
     * 100 accounts per commit keeps memory bounded for an arbitrarily large account master.
     */
    private static final int CHUNK_SIZE = 100;

    /** JPA paging size for the reader; kept equal to {@link #CHUNK_SIZE} so one page fills one chunk. */
    private static final int READER_PAGE_SIZE = 100;

    /**
     * The dashed record separator emitted after every account, byte-for-byte identical to the
     * 49-dash literal displayed by CBACT01C {@code 1100-DISPLAY-ACCT-RECORD} (L130).
     */
    private static final String RECORD_SEPARATOR = "-------------------------------------------------";

    /**
     * Defines the {@code accountMasterPrintJob} batch job: a single-step job that reads the
     * account master in ascending primary-key order and prints every record, reproducing
     * CBACT01C.
     *
     * <p>The {@link CorrelationIdJobListener} is registered so the job (and its step) log lines
     * carry a correlation id end-to-end.</p>
     *
     * @param jobRepository             the auto-configured Spring Batch {@link JobRepository};
     *                                  never {@code null}
     * @param accountMasterPrintStep    the single read&rarr;print {@link Step} defined by
     *                                  {@link #accountMasterPrintStep(JobRepository, PlatformTransactionManager, AccountRepository)}
     * @param correlationIdJobListener  the cross-cutting correlation-id listener (same package);
     *                                  never {@code null}
     * @return the fully built, read-only account master-print {@link Job}
     */
    @Bean
    public Job accountMasterPrintJob(JobRepository jobRepository,
                                     Step accountMasterPrintStep,
                                     CorrelationIdJobListener correlationIdJobListener) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .listener(correlationIdJobListener)
                .start(accountMasterPrintStep)
                .build();
    }

    /**
     * Defines the chunk-oriented read&rarr;print step. Each chunk of {@link #CHUNK_SIZE}
     * {@link Account} rows is read in ascending primary-key order and printed; nothing is
     * written back, preserving the read-only nature of CBACT01C.
     *
     * @param jobRepository       the auto-configured Spring Batch {@link JobRepository};
     *                            never {@code null}
     * @param transactionManager  the auto-configured batch {@link PlatformTransactionManager}
     *                            that bounds each chunk; never {@code null}
     * @param accountRepository   the account repository backing the reader; never {@code null}
     * @return the configured {@link Step}
     */
    @Bean
    public Step accountMasterPrintStep(JobRepository jobRepository,
                                       PlatformTransactionManager transactionManager,
                                       AccountRepository accountRepository) {
        return new StepBuilder(STEP_NAME, jobRepository)
                .<Account, Account>chunk(CHUNK_SIZE, transactionManager)
                .reader(accountMasterPrintReader(accountRepository))
                .writer(accountMasterPrintWriter())
                .build();
    }

    /**
     * Builds the inline {@link RepositoryItemReader} that streams every {@link Account} in
     * ascending primary-key ({@code acctId}) order, reproducing the CBACT01C VSAM
     * ascending-key sequential read.
     *
     * <p>The reader is backed by {@link AccountRepository}; it invokes
     * {@code findAll(Pageable)} with a {@code PageRequest} sorted by {@link #ACCT_ID_PROPERTY}
     * ascending and sized at {@link #READER_PAGE_SIZE}. Because {@link RepositoryItemReader} is
     * an {@code ItemStream}, Spring Batch manages its {@code open}/{@code update}/{@code close}
     * lifecycle per step execution, so this single instance is safe to reuse across restarts and
     * re-executions. A derived query such as {@code findAllByOrderByAcctIdAsc()} is intentionally
     * not used here: it accepts no {@code Pageable} and so cannot drive the reader's paging.</p>
     *
     * @param accountRepository the account repository to page over; never {@code null}
     * @return a configured, ascending-key {@link RepositoryItemReader} of {@link Account}
     */
    private RepositoryItemReader<Account> accountMasterPrintReader(AccountRepository accountRepository) {
        return new RepositoryItemReaderBuilder<Account>()
                .name(READER_NAME)
                .repository(accountRepository)
                .methodName(FIND_ALL_METHOD)
                .sorts(Map.of(ACCT_ID_PROPERTY, Sort.Direction.ASC))
                .pageSize(READER_PAGE_SIZE)
                .build();
    }

    /**
     * Builds the inline logging {@link ItemWriter} that prints each {@link Account}, reproducing
     * the CBACT01C {@code 1100-DISPLAY-ACCT-RECORD} paragraph: one line per field, in the exact
     * COBOL field order and with the exact field labels, followed by the dashed separator.
     * Nothing is persisted &mdash; this is a pure read&rarr;log pass.
     *
     * <p>The monetary fields ({@code ACCT-CURR-BAL}, the two credit limits, and the two cycle
     * totals) are {@link java.math.BigDecimal} on {@link Account}; they are logged directly, never
     * via {@code double}/{@code float}. {@code null} field values render as {@code "null"} through
     * SLF4J's parameterized formatting without raising an error.</p>
     *
     * @return an {@link ItemWriter} that logs {@link Account} records and persists nothing
     */
    private ItemWriter<Account> accountMasterPrintWriter() {
        return chunk -> {
            for (Account account : chunk) {
                LOGGER.info("ACCT-ID                 :{}", account.getAcctId());
                LOGGER.info("ACCT-ACTIVE-STATUS      :{}", account.getAcctActiveStatus());
                LOGGER.info("ACCT-CURR-BAL           :{}", account.getCurrBal());
                LOGGER.info("ACCT-CREDIT-LIMIT       :{}", account.getCreditLimit());
                LOGGER.info("ACCT-CASH-CREDIT-LIMIT  :{}", account.getCashCreditLimit());
                LOGGER.info("ACCT-OPEN-DATE          :{}", account.getAcctOpenDate());
                LOGGER.info("ACCT-EXPIRAION-DATE     :{}", account.getAcctExpirationDate());
                LOGGER.info("ACCT-REISSUE-DATE       :{}", account.getAcctReissueDate());
                LOGGER.info("ACCT-CURR-CYC-CREDIT    :{}", account.getCurrCycCredit());
                LOGGER.info("ACCT-CURR-CYC-DEBIT     :{}", account.getCurrCycDebit());
                LOGGER.info("ACCT-GROUP-ID           :{}", account.getGroupId());
                LOGGER.info(RECORD_SEPARATOR);
            }
        };
    }
}
