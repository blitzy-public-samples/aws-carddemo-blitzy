package com.carddemo.batch;

import java.util.Collections;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
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

import com.carddemo.entity.Account;
import com.carddemo.entity.Card;
import com.carddemo.entity.CardXref;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardRepository;
import com.carddemo.repository.CardXrefRepository;

/**
 * Spring Batch&nbsp;5 configuration for the <strong>account/card master refresh</strong> job &mdash;
 * the Java re-expression of the legacy COBOL VSAM <em>dump/print</em> utilities
 * {@code CBACT01C} (ACCOUNT), {@code CBACT02C} (CARD) and {@code CBACT03C} (CARDXREF).
 *
 * <h2>Legacy lineage and the read-only contract</h2>
 * <p>On the mainframe these three batch programs each opened a VSAM Key-Sequenced Data Set
 * {@code INPUT}, walked it sequentially in primary-key order, {@code DISPLAY}ed every record, and
 * closed the file &mdash; performing <strong>no mutation whatsoever</strong> (no {@code WRITE},
 * {@code REWRITE} or {@code DELETE}). Their stated functions are literally "Read and print account
 * data file" ({@code CBACT01C}), "Read and print card data file" ({@code CBACT02C}) and "Read and
 * print account cross reference data file" ({@code CBACT03C}). The AAP (&sect;0.2.2.1, &sect;0.4.1.5)
 * reinterprets that trio as a single <em>refresh</em> {@link Job} composed of three sequential,
 * read-and-log steps.</p>
 *
 * <p><strong>CRITICAL &mdash; this job is read-only / side-effect-free.</strong> It reads each master
 * table in key order and logs/counts the records it sees. It MUST NOT insert, update or delete any
 * row, staying faithful to the dump/print utilities it replaces. The chunk writer below is
 * deliberately a non-persisting logger, and no service or persistence-mutating collaborator is
 * injected (AAP read-only mandate; the source programs were verified to perform no writes).</p>
 *
 * <h2>Step order mirrors the legacy program order</h2>
 * <p>The job chains its steps {@code ACCOUNT &rarr; CARD &rarr; CARDXREF}, mirroring the legacy
 * execution order {@code CBACT01C &rarr; CBACT02C &rarr; CBACT03C}. Each step pages its master table
 * to exhaustion (it is <em>count-agnostic</em>: no record total is hard-coded) and emits a single
 * INFO line reporting how many records were read, via the {@link #readCountListener(String)}.</p>
 *
 * <h2>Batch-enablement model &mdash; OPTION&nbsp;A (Spring Boot auto-configuration)</h2>
 * <p>This class carries no {@code @EnableBatchProcessing}. Under Spring Boot&nbsp;3.2 /
 * Spring Batch&nbsp;5 the shared infrastructure ({@code JobRepository},
 * {@link PlatformTransactionManager}, {@code JobLauncher}) is supplied by Boot's
 * {@code BatchAutoConfiguration}; adding {@code @EnableBatchProcessing} would cause that
 * auto-configuration to back off and break the {@code BATCH_*} schema initialization and on-demand
 * launch model documented on {@code com.carddemo.config.BatchConfig}. The auto-configured
 * {@link JobRepository} and {@link PlatformTransactionManager} are constructor-injected and handed
 * directly to the {@link JobBuilder} / {@link StepBuilder}. Jobs run on demand
 * ({@code spring.batch.job.enabled=false}); nothing launches at startup.</p>
 *
 * <h2>Data access</h2>
 * <p>Reading is performed through Spring Batch {@link RepositoryItemReader}s built over the Spring
 * Data {@code JpaRepository} beans for each master entity. Each reader invokes the repository's
 * inherited {@code findAll(Pageable)} ({@code methodName = "findAll"}) and supplies a mandatory
 * non-empty {@code sorts} map so records are returned in deterministic primary-key order
 * &mdash; {@code acctId} for {@link Account}, {@code cardNum} for {@link Card} and
 * {@code xrefCardNum} for {@link CardXref} &mdash; exactly reproducing the VSAM key-sequenced read
 * order of the source programs.</p>
 *
 * <h2>Layering</h2>
 * <p>This configuration imports only {@code repository} and {@code entity} types from
 * {@code com.carddemo}; it deliberately references no {@code service} class and no
 * {@code TranIdGenerator}, keeping the refresh job a pure, read-only data scan within the
 * strict Controller&nbsp;&rarr;&nbsp;Service&nbsp;&rarr;&nbsp;Repository layering (AAP&nbsp;&sect;0.3.2).</p>
 *
 * @see <a href="file:app/cbl/CBACT01C.cbl">app/cbl/CBACT01C.cbl (ACCOUNT dump/print)</a>
 * @see <a href="file:app/cbl/CBACT02C.cbl">app/cbl/CBACT02C.cbl (CARD dump/print)</a>
 * @see <a href="file:app/cbl/CBACT03C.cbl">app/cbl/CBACT03C.cbl (CARDXREF dump/print)</a>
 * @see com.carddemo.config.BatchConfig
 */
@Configuration
public class AccountRefreshJobConfig {

    /**
     * Page / commit-interval size used both as the {@link RepositoryItemReader} page size and the
     * chunk commit interval. This is a pure read/commit-throughput tuning value; the refresh job
     * carries no domain constants (it neither validates business rules nor writes data), so the size
     * is defined locally rather than sourced from {@code CardDemoConstants}.
     */
    private static final int CHUNK_SIZE = 100;

    /** SLF4J logger; the read-count listener logs at INFO and the chunk writer at TRACE. */
    private static final Logger log = LoggerFactory.getLogger(AccountRefreshJobConfig.class);

    /** Auto-configured Spring Batch job repository (Boot {@code BatchAutoConfiguration}, OPTION A). */
    private final JobRepository jobRepository;

    /** Auto-configured transaction manager bound to the chunk-oriented steps. */
    private final PlatformTransactionManager transactionManager;

    /** Spring Data repository for the {@link Account} master (read-only access here). */
    private final AccountRepository accountRepository;

    /** Spring Data repository for the {@link Card} master (read-only access here). */
    private final CardRepository cardRepository;

    /** Spring Data repository for the {@link CardXref} cross-reference master (read-only access here). */
    private final CardXrefRepository cardXrefRepository;

    /**
     * Single all-arguments constructor &mdash; Spring performs constructor injection automatically for
     * a sole constructor, so no {@code @Autowired} annotation is required. All collaborators are
     * stored in {@code final} fields.
     *
     * @param jobRepository      the auto-configured Spring Batch {@link JobRepository}
     * @param transactionManager the auto-configured {@link PlatformTransactionManager} for chunk steps
     * @param accountRepository  repository for the account master (read-only)
     * @param cardRepository     repository for the card master (read-only)
     * @param cardXrefRepository repository for the card cross-reference master (read-only)
     */
    public AccountRefreshJobConfig(JobRepository jobRepository,
                                   PlatformTransactionManager transactionManager,
                                   AccountRepository accountRepository,
                                   CardRepository cardRepository,
                                   CardXrefRepository cardXrefRepository) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.accountRepository = accountRepository;
        this.cardRepository = cardRepository;
        this.cardXrefRepository = cardXrefRepository;
    }

    // ------------------------------------------------------------------------
    // Shared helpers (logging writer + read-count listener)
    // ------------------------------------------------------------------------

    /**
     * Builds a generic, <strong>side-effect-free</strong> {@link ItemWriter} that merely logs each
     * item at TRACE level. {@link ItemWriter} is a functional interface
     * ({@code write(Chunk<? extends T>)}), so the writer is expressed as a lambda over the supplied
     * chunk.
     *
     * <p>This writer performs <strong>no persistence</strong> &mdash; it is the read-only counterpart
     * to the legacy {@code DISPLAY} verb of the dump/print utilities. Logging is guarded by
     * {@link Logger#isTraceEnabled()} and emitted only at TRACE so that a full master scan does not
     * flood the logs in normal operation while remaining available for diagnostic deep-dives.</p>
     *
     * @param <T>         the item type handled by the enclosing step (an entity type)
     * @param entityLabel a short, human-readable label for the entity being refreshed (for log lines)
     * @return a non-persisting {@link ItemWriter} that logs each read item at TRACE level
     */
    private <T> ItemWriter<T> loggingWriter(String entityLabel) {
        return chunk -> {
            if (log.isTraceEnabled()) {
                for (T item : chunk) {
                    log.trace("Refresh read {}: {}", entityLabel, item);
                }
            }
        };
    }

    /**
     * Builds a {@link StepExecutionListener} that, after a step completes, logs the number of records
     * read during that step and returns the step's existing {@link ExitStatus} unchanged.
     *
     * <p>In Spring Batch&nbsp;5 {@link StepExecutionListener} declares only {@code default} methods, so
     * it is <em>not</em> a single-method functional interface; the listener is therefore created as an
     * anonymous class overriding {@link StepExecutionListener#afterStep(StepExecution)}. The returned
     * exit status is left as-is so this purely observational listener never alters the step outcome.</p>
     *
     * @param entityLabel a short, human-readable label for the entity being refreshed (for log lines)
     * @return a {@link StepExecutionListener} that reports the per-step read count at INFO level
     */
    private StepExecutionListener readCountListener(String entityLabel) {
        return new StepExecutionListener() {
            @Override
            public ExitStatus afterStep(StepExecution stepExecution) {
                log.info("{} refresh complete: {} record(s) read",
                        entityLabel, stepExecution.getReadCount());
                return stepExecution.getExitStatus();
            }
        };
    }

    // ------------------------------------------------------------------------
    // Readers (one per master table; plain singleton beans, no @StepScope)
    // ------------------------------------------------------------------------

    /**
     * Reader over the {@code accounts} master, replacing the sequential VSAM read of {@code CBACT01C}.
     *
     * <p>Invokes {@link AccountRepository}'s inherited {@code findAll(Pageable)} and sorts by the
     * entity key field {@code acctId} ascending, reproducing the {@code ACCT-ID} key-sequenced read
     * order. The {@code sorts} map is non-empty as required by {@link RepositoryItemReader}, and the
     * page size matches {@link #CHUNK_SIZE}.</p>
     *
     * @return a configured {@link RepositoryItemReader} streaming {@link Account} rows in key order
     */
    @Bean
    public RepositoryItemReader<Account> accountRefreshReader() {
        return new RepositoryItemReaderBuilder<Account>()
                .name("accountRefreshReader")
                .repository(accountRepository)
                .methodName("findAll")
                .arguments(Collections.emptyList())
                .sorts(Map.of("acctId", Sort.Direction.ASC))
                .pageSize(CHUNK_SIZE)
                .build();
    }

    /**
     * Reader over the {@code cards} master, replacing the sequential VSAM read of {@code CBACT02C}.
     *
     * <p>Invokes {@link CardRepository}'s inherited {@code findAll(Pageable)} and sorts by the entity
     * key field {@code cardNum} ascending, reproducing the {@code CARD-NUM} key-sequenced read order.</p>
     *
     * @return a configured {@link RepositoryItemReader} streaming {@link Card} rows in key order
     */
    @Bean
    public RepositoryItemReader<Card> cardRefreshReader() {
        return new RepositoryItemReaderBuilder<Card>()
                .name("cardRefreshReader")
                .repository(cardRepository)
                .methodName("findAll")
                .arguments(Collections.emptyList())
                .sorts(Map.of("cardNum", Sort.Direction.ASC))
                .pageSize(CHUNK_SIZE)
                .build();
    }

    /**
     * Reader over the {@code card_xref} master, replacing the sequential VSAM read of {@code CBACT03C}.
     *
     * <p>Invokes {@link CardXrefRepository}'s inherited {@code findAll(Pageable)} and sorts by the
     * entity key field {@code xrefCardNum} ascending, reproducing the {@code XREF-CARD-NUM}
     * key-sequenced read order.</p>
     *
     * @return a configured {@link RepositoryItemReader} streaming {@link CardXref} rows in key order
     */
    @Bean
    public RepositoryItemReader<CardXref> cardXrefRefreshReader() {
        return new RepositoryItemReaderBuilder<CardXref>()
                .name("cardXrefRefreshReader")
                .repository(cardXrefRepository)
                .methodName("findAll")
                .arguments(Collections.emptyList())
                .sorts(Map.of("xrefCardNum", Sort.Direction.ASC))
                .pageSize(CHUNK_SIZE)
                .build();
    }

    // ------------------------------------------------------------------------
    // Steps (chunk-oriented; read -> non-persisting log; read-count listener)
    // ------------------------------------------------------------------------

    /**
     * Chunk-oriented step that scans the account master. The reader bean is injected by name (the
     * parameter name {@code accountRefreshReader} matches the reader bean), the writer is the
     * non-persisting {@link #loggingWriter(String)}, and the {@link #readCountListener(String)}
     * reports the records read. Input and output item types are identical ({@code <Account, Account>})
     * because no transformation occurs &mdash; this is a read-only refresh.
     *
     * @param accountRefreshReader the account reader bean (injected by name)
     * @return the {@code accountRefreshStep} {@link Step}
     */
    @Bean
    public Step accountRefreshStep(RepositoryItemReader<Account> accountRefreshReader) {
        return new StepBuilder("accountRefreshStep", jobRepository)
                .<Account, Account>chunk(CHUNK_SIZE, transactionManager)
                .reader(accountRefreshReader)
                .writer(loggingWriter("ACCOUNT"))
                .listener(readCountListener("ACCOUNT"))
                .build();
    }

    /**
     * Chunk-oriented step that scans the card master. Mirrors {@link #accountRefreshStep} with
     * {@code <Card, Card>} item types, the {@code "CARD"} logging writer and read-count listener.
     *
     * @param cardRefreshReader the card reader bean (injected by name)
     * @return the {@code cardRefreshStep} {@link Step}
     */
    @Bean
    public Step cardRefreshStep(RepositoryItemReader<Card> cardRefreshReader) {
        return new StepBuilder("cardRefreshStep", jobRepository)
                .<Card, Card>chunk(CHUNK_SIZE, transactionManager)
                .reader(cardRefreshReader)
                .writer(loggingWriter("CARD"))
                .listener(readCountListener("CARD"))
                .build();
    }

    /**
     * Chunk-oriented step that scans the card cross-reference master. Mirrors
     * {@link #accountRefreshStep} with {@code <CardXref, CardXref>} item types, the {@code "CARDXREF"}
     * logging writer and read-count listener.
     *
     * @param cardXrefRefreshReader the card cross-reference reader bean (injected by name)
     * @return the {@code cardXrefRefreshStep} {@link Step}
     */
    @Bean
    public Step cardXrefRefreshStep(RepositoryItemReader<CardXref> cardXrefRefreshReader) {
        return new StepBuilder("cardXrefRefreshStep", jobRepository)
                .<CardXref, CardXref>chunk(CHUNK_SIZE, transactionManager)
                .reader(cardXrefRefreshReader)
                .writer(loggingWriter("CARDXREF"))
                .listener(readCountListener("CARDXREF"))
                .build();
    }

    // ------------------------------------------------------------------------
    // Job (chains the three steps in legacy program order)
    // ------------------------------------------------------------------------

    /**
     * The {@code accountRefreshJob} chaining the three refresh steps in the order
     * {@code accountRefreshStep &rarr; cardRefreshStep &rarr; cardXrefRefreshStep}, mirroring the
     * legacy program sequence {@code CBACT01C &rarr; CBACT02C &rarr; CBACT03C}.
     *
     * <p>The step beans are injected by name (parameter names match the step bean names). The job is
     * launched on demand through the auto-configured {@code JobLauncher}; it does not run at startup
     * ({@code spring.batch.job.enabled=false}).</p>
     *
     * @param accountRefreshStep   the account-scan step (injected by name)
     * @param cardRefreshStep      the card-scan step (injected by name)
     * @param cardXrefRefreshStep  the card cross-reference-scan step (injected by name)
     * @return the {@code accountRefreshJob} {@link Job}
     */
    @Bean
    public Job accountRefreshJob(Step accountRefreshStep,
                                 Step cardRefreshStep,
                                 Step cardXrefRefreshStep) {
        return new JobBuilder("accountRefreshJob", jobRepository)
                .start(accountRefreshStep)
                .next(cardRefreshStep)
                .next(cardXrefRefreshStep)
                .build();
    }
}
