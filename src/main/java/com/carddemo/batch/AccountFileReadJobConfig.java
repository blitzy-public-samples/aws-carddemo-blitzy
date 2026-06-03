package com.carddemo.batch;

import com.carddemo.entity.Account;
import com.carddemo.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.batch.item.data.builder.RepositoryItemReaderBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Spring Batch configuration for the <strong>{@code accountFileReadJob}</strong> — the
 * Java/PostgreSQL replacement for the legacy batch COBOL program {@code app/cbl/CBACT01C.cbl}
 * (invoked operationally by the JCL job {@code app/jcl/READACCT.jcl}).
 *
 * <h2>Legacy mainframe behavior (CBACT01C.cbl + READACCT.jcl)</h2>
 * {@code CBACT01C} is a pure <em>diagnostic</em> reader. Its {@code PROCEDURE DIVISION} displays a
 * {@code 'START OF EXECUTION'} banner, opens the indexed {@code ACCTFILE-FILE}
 * ({@code ORGANIZATION IS INDEXED}, {@code ACCESS MODE IS SEQUENTIAL}, {@code RECORD KEY IS
 * FD-ACCT-ID} — the ACCTDATA VSAM KSDS laid out by {@code app/cpy/CVACT01Y.cpy}
 * {@code ACCOUNT-RECORD}), then loops {@code PERFORM UNTIL END-OF-FILE = 'Y'}. Each iteration
 * performs {@code 1000-ACCTFILE-GET-NEXT} ({@code READ ACCTFILE-FILE INTO ACCOUNT-RECORD}; an
 * end-of-file file-status {@code '10'} maps to {@code APPL-EOF} and sets {@code END-OF-FILE = 'Y'})
 * and then {@code DISPLAY}s the record via {@code 1100-DISPLAY-ACCT-RECORD} (dumping
 * {@code ACCT-ID}, {@code ACCT-ACTIVE-STATUS}, {@code ACCT-CURR-BAL}, {@code ACCT-CREDIT-LIMIT},
 * {@code ACCT-CASH-CREDIT-LIMIT}, {@code ACCT-OPEN-DATE}, {@code ACCT-EXPIRAION-DATE} [sic],
 * {@code ACCT-REISSUE-DATE}, {@code ACCT-CURR-CYC-CREDIT}, {@code ACCT-CURR-CYC-DEBIT} and
 * {@code ACCT-GROUP-ID}). Finally it performs {@code 9000-ACCTFILE-CLOSE}, displays an
 * {@code 'END OF EXECUTION'} banner, and {@code GOBACK}s. The program never writes, updates, or
 * deletes — it only dumps the account master file to {@code SYSOUT} for operator verification.
 * {@code app/jcl/READACCT.jcl} is merely the JCL wrapper that executes {@code PGM=CBACT01C} (step
 * {@code STEP05}) with the {@code ACCTFILE} DD pointed at
 * {@code AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS}; the JCL specifics (DD statements, region size) carry
 * no business meaning and are not preserved.
 *
 * <h2>Modernized Spring Batch behavior — diagnostic reader</h2>
 * This job is a single chunk-oriented {@link Step} that pages over the {@code accounts} table (the
 * relational system-of-record replacing the VSAM ACCTDATA KSDS) and logs every row — the faithful
 * Spring equivalent of the COBOL {@code DISPLAY ACCOUNT-RECORD} loop. It never writes to the
 * database (read-only diagnostic, PR-25 single monolith). It is operationally useful for verifying
 * that the {@code accounts} table contains the expected data after running
 * {@code dataInitializationJob}, for producing audit logs, and for sanity-checking schema
 * migrations.
 *
 * <h2>Step shape</h2>
 * <pre>
 *   RepositoryItemReader&lt;Account&gt;  (pages the accounts table via findAll, sorted by acctId ASC)
 *        -&gt; ItemProcessor (logs each row; identity pass-through)
 *        -&gt; ItemWriter   (no-op; logs the chunk size only)
 * </pre>
 * The reader sorts by the natural primary key {@code acctId} (the {@code acct_id BIGINT} column,
 * originally COBOL {@code ACCT-ID PIC 9(11)}, which was the VSAM {@code RECORD KEY IS FD-ACCT-ID})
 * ascending. Because {@code acct_id} is a numeric {@code BIGINT} primary key, ascending numeric
 * order reproduces the sequential record-key ordering of the original VSAM read exactly (the VSAM
 * key {@code FD-ACCT-ID} is itself a {@code PIC 9(11)} numeric key). A deterministic, non-empty
 * sort is also a hard requirement of the paging {@link RepositoryItemReader}.
 *
 * <h2>Refactoring rules enforced</h2>
 * <ul>
 *   <li><strong>PR-25</strong> (single monolith): this batch job runs inside the one Spring Boot
 *       context — no microservice, no external scheduler, no message queue.</li>
 *   <li><strong>PR-28</strong> (Jakarta / Spring 6 baseline): only Spring Framework 6.1 / Spring
 *       Batch 5.1 APIs are used; no {@code javax.*} types.</li>
 *   <li><strong>PR-29</strong> (constructor injection only): the three collaborators are
 *       {@code final} and injected through the Lombok {@code @RequiredArgsConstructor}-generated
 *       constructor — no field injection, no {@code @Autowired}.</li>
 * </ul>
 *
 * <p><strong>Why {@code @EnableBatchProcessing} is intentionally absent:</strong> under Spring
 * Boot 3.x the {@code spring-boot-starter-batch} auto-configuration supplies the
 * {@link JobRepository}, {@code JobLauncher}, {@code JobRegistry} and {@code JobExplorer}; adding
 * {@code @EnableBatchProcessing} would switch off that auto-configuration (see {@code BatchConfig}).
 * Both the {@code @Bean Job} and {@code @Bean Step} defined here are auto-registered with the
 * {@code JobRegistry}, so {@code BatchAdminController} ({@code POST /api/admin/jobs/{jobName}/launch})
 * can launch this job by its {@link #JOB_NAME}.</p>
 *
 * <p><strong>Entity accessor note.</strong> The logging processor reads the committed
 * {@link Account} entity getters. Their actual Java types are intentionally heterogeneous (they
 * mirror the committed Flyway DDL and {@code ddl-auto: validate} mappings, not a naive 1:1 string
 * mapping of the COBOL {@code PIC} clauses):</p>
 * <ul>
 *   <li>{@code getAcctId()} &rarr; {@link Long} — the {@code acct_id} BIGINT primary key, COBOL
 *       {@code ACCT-ID PIC 9(11)} (an 11-digit value exceeds the range of {@code int}).</li>
 *   <li>{@code getActiveStatus()} &rarr; {@link String} — the {@code active_status} CHAR(1), COBOL
 *       {@code ACCT-ACTIVE-STATUS PIC X(01)} (e.g. {@code "Y"}/{@code "N"}).</li>
 *   <li>{@code getCurrBal()} &rarr; {@link java.math.BigDecimal} — the {@code curr_bal}
 *       NUMERIC(15,2), COBOL {@code ACCT-CURR-BAL PIC S9(10)V99} (exact packed decimal, scale 2 per
 *       PR-16; never {@code float}/{@code double}).</li>
 *   <li>{@code getCreditLimit()} &rarr; {@link java.math.BigDecimal} — the {@code credit_limit}
 *       NUMERIC(15,2), COBOL {@code ACCT-CREDIT-LIMIT PIC S9(10)V99}.</li>
 *   <li>{@code getExpirationDate()} &rarr; {@link java.time.LocalDate} — the {@code expiration_date}
 *       DATE, COBOL {@code ACCT-EXPIRAION-DATE} [sic] {@code PIC X(10)} normalized to a SQL
 *       {@code DATE} (<em>not</em> a String; PR-14 corrects the copybook misspelling to
 *       {@code expirationDate}).</li>
 * </ul>
 * <p>All five values are passed to the SLF4J {@code log.info(String, Object...)} varargs slots, so
 * the heterogeneous types ({@code Long}/{@code String}/{@code BigDecimal}/{@code LocalDate}) are
 * each rendered through their {@code toString()} at log time; the diagnostic line therefore mirrors
 * a representative subset of the COBOL {@code 1100-DISPLAY-ACCT-RECORD} field dump regardless of the
 * underlying Java type.</p>
 *
 * <p><strong>Scaling note.</strong> For very large account volumes a {@code JdbcCursorItemReader}
 * streaming a forward-only cursor would avoid materializing paged result sets; the
 * {@link RepositoryItemReader} paging approach is used here for consistency with the sibling
 * diagnostic readers ({@link CardFileReadJobConfig}, {@link XrefFileReadJobConfig},
 * {@link TransactionReadJobConfig}, {@link DailyTransactionReadJobConfig}) and is more than adequate
 * for the demonstration-grade data volume (the 50 default account rows seeded from
 * {@code app/data/ASCII/acctdata.txt}).</p>
 *
 * @see com.carddemo.entity.Account
 * @see com.carddemo.repository.AccountRepository
 * @see CardFileReadJobConfig
 * @see XrefFileReadJobConfig
 * @see org.springframework.batch.item.data.RepositoryItemReader
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class AccountFileReadJobConfig {

    /**
     * Logical name of the diagnostic {@link Job} bean. Used by the Spring Batch {@code JobRegistry}
     * and by {@code BatchAdminController} ({@code POST /api/admin/jobs/{jobName}/launch}) to launch
     * this job by name for operator verification of the {@code accounts} table — the faithful
     * equivalent of submitting {@code app/jcl/READACCT.jcl} to run {@code CBACT01C}.
     */
    public static final String JOB_NAME = "accountFileReadJob";

    /** Logical name of the single chunk-oriented diagnostic {@link Step}. */
    private static final String STEP_NAME = "accountFileReadStep";

    /**
     * {@link RepositoryItemReader} name — the key prefix under which the reader saves and restores
     * its paging state in the step {@code ExecutionContext}. Must be unique within the step.
     */
    private static final String READER_NAME = "accountFileReader";

    /**
     * Chunk size, doubling as the reader page size. A value of {@value} matches the codebase-wide
     * default chunk size (AAP &sect;0.3.3 #7) and balances log granularity against database
     * round-trips for the demonstration-grade data volume.
     */
    private static final int CHUNK_SIZE = 100;

    /**
     * Entity property the reader sorts on (mapped to the {@code acct_id} primary-key column).
     * Sorting by the numeric {@code acctId} ascending yields a stable, deterministic ordering that
     * reproduces the VSAM {@code RECORD KEY IS FD-ACCT-ID} sequential read order of {@code CBACT01C}
     * — and is also a hard requirement of the paging {@link RepositoryItemReader} (its sort map must
     * be non-empty).
     */
    private static final String SORT_PROPERTY = "acctId";

    /** Spring Batch metadata repository (auto-configured by Spring Boot 3.x). */
    private final JobRepository jobRepository;

    /**
     * Transaction manager bracketing each chunk's unit of work — the {@code SYNCPOINT}-equivalent
     * boundary for the read step (PR-24, AAP &sect;0.6.12). Even though this diagnostic step performs
     * no writes, Spring Batch requires a transaction manager to delimit chunk processing.
     */
    private final PlatformTransactionManager transactionManager;

    /**
     * Spring Data JPA repository over the {@code accounts} table. Supplied to the
     * {@link RepositoryItemReaderBuilder} to provide a paged full-table read via its inherited
     * {@code findAll(Pageable)} method ({@code AccountRepository} extends {@code JpaRepository},
     * hence {@code PagingAndSortingRepository}).
     */
    private final AccountRepository accountRepository;

    /**
     * Paging reader over the {@code accounts} table.
     *
     * <p>Built on {@link RepositoryItemReader}, which invokes
     * {@code accountRepository.findAll(Pageable)} one page at a time. The configured
     * {@code methodName} {@code "findAll"} combined with the {@code PageRequest} the reader passes
     * resolves unambiguously to {@code PagingAndSortingRepository.findAll(Pageable)} (a
     * {@code PageRequest} is a {@code Pageable}, not a {@code Sort}). A non-empty sort on
     * {@link #SORT_PROPERTY} ascending is mandatory for the paging reader and reproduces the VSAM
     * record-key dump ordering; the page size is set to {@link #CHUNK_SIZE} so each page aligns with
     * one processing chunk.</p>
     *
     * @return a configured {@link RepositoryItemReader} streaming the {@code accounts} table in
     *         ascending {@code acctId} order
     */
    @Bean
    public RepositoryItemReader<Account> accountFileReader() {
        return new RepositoryItemReaderBuilder<Account>()
                .name(READER_NAME)
                .repository(accountRepository)
                .methodName("findAll")
                .sorts(Collections.singletonMap(SORT_PROPERTY, Sort.Direction.ASC))
                .pageSize(CHUNK_SIZE)
                .build();
    }

    /**
     * Pass-through {@link ItemProcessor} that logs each account row — the Spring equivalent of the
     * COBOL {@code DISPLAY ACCOUNT-RECORD} / {@code 1100-DISPLAY-ACCT-RECORD} diagnostic in
     * {@code CBACT01C}.
     *
     * <p>A per-bean {@link AtomicLong} captured by the returned lambda emits a monotonically
     * increasing record number ({@code [#n]}) so an operator can correlate the log against the table
     * row count and mirror the ordered sequential {@code DISPLAY} output of the COBOL program. The
     * item is returned unchanged so the chunk flows on to the no-op writer. Accessor calls use the
     * actual committed {@link Account} getters — {@code getAcctId()} (Long PK),
     * {@code getActiveStatus()} (String), {@code getCurrBal()} (BigDecimal),
     * {@code getCreditLimit()} (BigDecimal) and {@code getExpirationDate()} (LocalDate) — while the
     * log labels ({@code acctId}, {@code status}, {@code currBal}, {@code creditLimit},
     * {@code expDate}) mirror the COBOL {@code ACCOUNT-RECORD} field names for traceability.</p>
     *
     * @return a logging, identity-mapping {@link ItemProcessor}
     */
    @Bean
    public ItemProcessor<Account, Account> accountFileLoggingProcessor() {
        final AtomicLong counter = new AtomicLong(0L);
        return account -> {
            long recordNumber = counter.incrementAndGet();
            log.info("[CBACT01C-DIAG] [#{}] acctId={} status={} currBal={} creditLimit={} expDate={}",
                    recordNumber,
                    account.getAcctId(),
                    account.getActiveStatus(),
                    account.getCurrBal(),
                    account.getCreditLimit(),
                    account.getExpirationDate());
            return account;
        };
    }

    /**
     * No-op {@link ItemWriter} for the diagnostic step. Because this job only verifies table
     * contents (it never persists anything — mirroring the read-only {@code CBACT01C}), the writer
     * merely records the number of items in each processed chunk at {@code DEBUG} level. The lambda
     * parameter is a Spring Batch 5 {@code Chunk<? extends Account>}, whose {@code size()} yields the
     * chunk item count.
     *
     * @return a write-nothing {@link ItemWriter} that logs the chunk size
     */
    @Bean
    public ItemWriter<Account> accountFileNoOpWriter() {
        return items -> log.debug("Chunk of {} Account record(s) read", items.size());
    }

    /**
     * Chunk-oriented diagnostic {@link Step} wiring the reader, logging processor and no-op writer.
     *
     * <p>The {@link #transactionManager} delimits each chunk's transaction boundary (PR-24) and the
     * chunk size is {@link #CHUNK_SIZE}. The step reads {@link Account} items and emits
     * {@link Account} items (identity processing), mirroring the single sequential pass of the COBOL
     * {@code PERFORM UNTIL END-OF-FILE = 'Y'} loop in {@code CBACT01C}.</p>
     *
     * @return the {@code accountFileReadStep} bean
     */
    @Bean
    public Step accountFileReadStep() {
        return new StepBuilder(STEP_NAME, jobRepository)
                .<Account, Account>chunk(CHUNK_SIZE, transactionManager)
                .reader(accountFileReader())
                .processor(accountFileLoggingProcessor())
                .writer(accountFileNoOpWriter())
                .build();
    }

    /**
     * The diagnostic {@link Job} bean ({@link #JOB_NAME}) consisting of the single
     * {@link #accountFileReadStep()}.
     *
     * <p>Auto-registered with the Spring Batch {@code JobRegistry} by Spring Boot auto-configuration,
     * so it is launchable by name through {@code BatchAdminController} — the REST-era equivalent of
     * submitting {@code app/jcl/READACCT.jcl} to run {@code CBACT01C}. The job has a single step and
     * therefore no inter-step flow; it completes as soon as the sequential account-table dump
     * finishes. Running the job with the same parameters returns the existing {@code JobExecution}
     * (idempotent); supplying a distinct parameter set creates a new execution.</p>
     *
     * @return the {@code accountFileReadJob} bean
     */
    @Bean
    public Job accountFileReadJob() {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(accountFileReadStep())
                .build();
    }
}
