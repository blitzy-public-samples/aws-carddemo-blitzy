package com.aws.carddemo.batch;

import java.util.Map;

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

import com.aws.carddemo.domain.Account;
import com.aws.carddemo.repository.AccountRepository;

/**
 * Spring Batch job configuration translating the mainframe batch program {@code CBACT01C}
 * ("Read and print account data file"), orchestrated by the {@code READACCT} JCL job.
 *
 * <p>Origin: {@code legacy/cbl/CBACT01C.cbl} (source branch {@code app/cbl/CBACT01C.cbl}) and
 * {@code legacy/jcl/READACCT.jcl} (source branch {@code app/jcl/READACCT.jcl}). Implements AAP
 * &sect;0.4.1 (batch COBOL program + JCL job &rarr; Spring Batch {@link Job}), &sect;0.6.3
 * (JCL/JES2 &rarr; chunk-oriented Spring Batch topology) and the Observability rule (COBOL
 * {@code DISPLAY} &rarr; structured SLF4J logging).</p>
 *
 * <p><strong>Legacy behavior reproduced.</strong> {@code CBACT01C} opens the VSAM {@code ACCTDAT}
 * KSDS ({@code ACCOUNT-RECORD}, copybook {@code CVACT01Y}, record length 300) for {@code INPUT} and
 * loops reading the next record. Its {@code 1000-ACCTFILE-GET-NEXT} paragraph maps {@code FILE
 * STATUS} to control flow: {@code '00'} &rarr; process the record (paragraph
 * {@code 1100-DISPLAY-ACCT-RECORD} prints its fields); {@code '10'} &rarr; end-of-file and normal
 * termination; any other status &rarr; {@code 9910-DISPLAY-IO-STATUS} then
 * {@code 9999-ABEND-PROGRAM}. The job mutates nothing &mdash; the account master is read-only &mdash;
 * and {@code READACCT.jcl} declares only {@code SYSOUT}/{@code SYSPRINT} (no output data set), so the
 * {@code DISPLAY} output is reproduced here as log records, not as a written file.</p>
 *
 * <p><strong>Target design (chunk-oriented print pattern).</strong> A {@link RepositoryItemReader}
 * streams every {@link Account} through {@link AccountRepository} in ascending {@code acctId} order,
 * reproducing the KSDS primary-key sequential read order (AAP &sect;0.6.6 deterministic ordering).
 * The reader returning {@code null} when the data set is exhausted is the idiomatic Spring Batch
 * equivalent of COBOL {@code FILE STATUS '10'} (normal end-of-file), so no
 * {@code exception.EndOfFileException} is raised on this happy path. A logging {@link ItemWriter}
 * emits one structured line per account (the {@code 1100-DISPLAY-ACCT-RECORD} field print). A single
 * {@link Step} wires the reader and writer with a commit interval, and the {@link Job} runs that one
 * step.</p>
 *
 * <p><strong>Wiring note (AAP binding constraint).</strong> This class deliberately does
 * <strong>not</strong> use {@code @EnableBatchProcessing} and does not depend on
 * {@code config/BatchConfig}. It relies on Spring Boot's Batch auto-configuration, which supplies
 * the {@link JobRepository} and {@link PlatformTransactionManager} injected through the constructor;
 * adding {@code @EnableBatchProcessing} would make Boot back off to a non-persistent, in-memory job
 * repository and break restartability. The rationale is recorded in {@code docs/decision-log.md}.</p>
 *
 * <p>The bean methods are stateless factories; the {@link RepositoryItemReader} they build resets
 * its paging cursor in {@code open(..)} on each step execution, so the singleton reader bean is safe
 * to re-run across multiple job instances.</p>
 */
@Configuration
public class AccountPrintJobConfig {

    /**
     * Logger used by the print writer to reproduce the COBOL {@code DISPLAY ACCOUNT-RECORD} /
     * {@code 1100-DISPLAY-ACCT-RECORD} output as structured, single-line log records.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(AccountPrintJobConfig.class);

    /**
     * Commit interval (chunk size) for the read-only print step, matching the shared read-only
     * "print" convention for the account, card, xref and customer scan jobs. Because each account
     * is printed independently and no running total is mutated, this value affects only per-chunk
     * transaction boundaries and DB fetch paging, never the observable output.
     */
    private static final int CHUNK_SIZE = 100;

    /** Auto-configured Spring Batch job repository used to build the step and the job. */
    private final JobRepository jobRepository;

    /** Auto-configured transaction manager bounding the read-only chunk step. */
    private final PlatformTransactionManager transactionManager;

    /**
     * Repository over the account master ({@code ACCTDAT} KSDS), the sole data source read by this
     * job. It is read only; no {@code save}/mutation is performed.
     */
    private final AccountRepository accountRepository;

    /**
     * Creates the configuration with the collaborators supplied by Spring Boot's Batch
     * auto-configuration and component scanning.
     *
     * @param jobRepository      the auto-configured Spring Batch {@link JobRepository}
     * @param transactionManager the auto-configured {@link PlatformTransactionManager}
     * @param accountRepository  the {@link AccountRepository} the reader scans by primary key
     */
    public AccountPrintJobConfig(JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            AccountRepository accountRepository) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.accountRepository = accountRepository;
    }

    /**
     * Reader that streams every {@link Account} in ascending {@code acctId} order, reproducing the
     * sequential primary-key scan of the VSAM {@code ACCTDAT} KSDS performed by
     * {@code CBACT01C}'s {@code 1000-ACCTFILE-GET-NEXT} paragraph.
     *
     * <p>Backed by {@link AccountRepository#findAll(org.springframework.data.domain.Pageable)}
     * (inherited from {@code PagingAndSortingRepository}) invoked page by page. When the last page
     * is exhausted the reader yields {@code null}, which is the idiomatic Spring Batch equivalent of
     * COBOL {@code FILE STATUS '10'} (normal end-of-file) &mdash; it is not an error condition.</p>
     *
     * @return a paging {@link RepositoryItemReader} ordered by {@code acctId} ascending
     */
    @Bean
    public RepositoryItemReader<Account> accountItemReader() {
        return new RepositoryItemReaderBuilder<Account>()
                .name("accountItemReader")
                .repository(accountRepository)
                .methodName("findAll")
                .sorts(Map.of("acctId", Sort.Direction.ASC))
                .pageSize(CHUNK_SIZE)
                .build();
    }

    /**
     * Writer that reproduces the COBOL {@code 1100-DISPLAY-ACCT-RECORD} field print.
     *
     * <p><strong>PII / financial-data protection (review finding #24).</strong> The mainframe program
     * {@code DISPLAY}ed the full account record to {@code SYSOUT}, a RACF-protected spool data set. An
     * application {@code INFO} log has a broader, less-restricted audience (it may be shipped to
     * centralized log aggregation), so emitting full account identifiers, balances, credit limits, cycle
     * values, dates and pricing-group ids at {@code INFO} would be a data-exposure regression relative to
     * the protected mainframe spool. Therefore this writer:</p>
     * <ul>
     *   <li>at {@code INFO} (the production default) emits only a <em>masked</em> summary &mdash; the
     *       account id reduced to its last four digits plus the active-status flag; every financial,
     *       date and pricing-group field is redacted from the default log stream;</li>
     *   <li>emits the full {@code 1100-DISPLAY-ACCT-RECORD} field dump only at {@code DEBUG}, which is off
     *       by default and is a deliberate, authorized opt-in that reproduces the mainframe {@code SYSOUT}
     *       diagnostic print for troubleshooting.</li>
     * </ul>
     * <p>The rendered fields at {@code DEBUG}, in the exact order and with the exact labels of the COBOL
     * paragraph, are {@code ACCT-ID}, {@code ACCT-ACTIVE-STATUS}, {@code ACCT-CURR-BAL},
     * {@code ACCT-CREDIT-LIMIT}, {@code ACCT-CASH-CREDIT-LIMIT}, {@code ACCT-OPEN-DATE},
     * {@code ACCT-EXPIRAION-DATE} (the COBOL source misspelling is preserved verbatim for traceability),
     * {@code ACCT-REISSUE-DATE}, {@code ACCT-CURR-CYC-CREDIT}, {@code ACCT-CURR-CYC-DEBIT} and
     * {@code ACCT-GROUP-ID}, rendered explicitly through the entity's accessors. No data set is written,
     * matching {@code READACCT.jcl}, which has no output DD.</p>
     *
     * @return an {@link ItemWriter} that logs one masked summary per {@link Account} at {@code INFO} and
     *         the full field detail at {@code DEBUG}
     */
    @Bean
    public ItemWriter<Account> accountPrintWriter() {
        return chunk -> {
            for (Account account : chunk) {
                // INFO (production default): masked, non-sensitive summary only (finding #24). The
                // account id is reduced to its last four digits and all financial, date and pricing-group
                // fields are withheld from the default log stream.
                LOGGER.info("ACCT-ID={} ACCT-ACTIVE-STATUS={} (financial/date/group fields redacted at "
                                + "INFO; enable DEBUG for the full diagnostic dump)",
                        maskAcctId(account.getAcctId()),
                        account.getActiveStatus());
                // DEBUG (deliberate opt-in): the full CBACT01C 1100-DISPLAY-ACCT-RECORD field print,
                // reproducing the RACF-protected mainframe SYSOUT diagnostic dump for authorized
                // troubleshooting. Off by default in production.
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug(
                            "ACCT-ID={} ACCT-ACTIVE-STATUS={} ACCT-CURR-BAL={} ACCT-CREDIT-LIMIT={} "
                                    + "ACCT-CASH-CREDIT-LIMIT={} ACCT-OPEN-DATE={} ACCT-EXPIRAION-DATE={} "
                                    + "ACCT-REISSUE-DATE={} ACCT-CURR-CYC-CREDIT={} ACCT-CURR-CYC-DEBIT={} "
                                    + "ACCT-GROUP-ID={}",
                            account.getAcctId(),
                            account.getActiveStatus(),
                            account.getCurrBal(),
                            account.getCreditLimit(),
                            account.getCashCreditLimit(),
                            account.getOpenDate(),
                            account.getExpiraionDate(),
                            account.getReissueDate(),
                            account.getCurrCycCredit(),
                            account.getCurrCycDebit(),
                            account.getGroupId());
                }
            }
        };
    }

    /**
     * Masks an account id for the default {@code INFO} log so the operational log never carries a full
     * account identifier (review finding #24). Only the last four digits are revealed; the remaining
     * leading digits are replaced with asterisks. A {@code null} id renders as {@code "null"}, and an id
     * of four or fewer digits is fully masked.
     *
     * @param acctId the account id (may be {@code null})
     * @return the masked account id, e.g. {@code "*******8901"}
     */
    private static String maskAcctId(Long acctId) {
        if (acctId == null) {
            return "null";
        }
        String digits = Long.toString(acctId);
        int visible = 4;
        if (digits.length() <= visible) {
            return "*".repeat(digits.length());
        }
        int maskedLen = digits.length() - visible;
        return "*".repeat(maskedLen) + digits.substring(maskedLen);
    }

    /**
     * The single chunk-oriented step of {@link #accountPrintJob()}. It reads accounts through
     * {@link #accountItemReader()} and prints them through {@link #accountPrintWriter()} within the
     * auto-configured transaction boundary, committing every {@link #CHUNK_SIZE} records. There is
     * no processor because the print job neither transforms nor filters records.
     *
     * @return the {@code accountPrintStep} {@link Step}
     */
    @Bean
    public Step accountPrintStep() {
        return new StepBuilder("accountPrintStep", jobRepository)
                .<Account, Account>chunk(CHUNK_SIZE, transactionManager)
                .reader(accountItemReader())
                .writer(accountPrintWriter())
                .build();
    }

    /**
     * The Spring Batch {@link Job} corresponding to legacy program {@code CBACT01C} and JCL job
     * {@code READACCT}. It consists of the single {@link #accountPrintStep()} and completes with
     * batch status {@code COMPLETED} on both empty and non-empty account data sets, mirroring the
     * COBOL program's normal end-of-file termination.
     *
     * @return the {@code accountPrintJob} {@link Job}
     */
    @Bean
    public Job accountPrintJob() {
        return new JobBuilder("accountPrintJob", jobRepository)
                .start(accountPrintStep())
                .build();
    }
}
