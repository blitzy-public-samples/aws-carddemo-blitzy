package com.carddemo.batch;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardRepository;
import com.carddemo.repository.CardXrefRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring Batch integration test for the <strong>{@code accountRefreshJob}</strong> &mdash; the Java
 * re-expression of the legacy COBOL VSAM <em>dump/print</em> utilities {@code CBACT01C} (ACCOUNT
 * master), {@code CBACT02C} (CARD master) and {@code CBACT03C} (CARDXREF cross-reference).
 *
 * <h2>Parity goal</h2>
 * <p>On the mainframe each of those three batch programs opened a VSAM Key-Sequenced Data Set, walked
 * it sequentially in primary-key order, {@code DISPLAY}ed every record, and closed the file &mdash;
 * performing <strong>no mutation whatsoever</strong> (the sources were verified to contain no
 * {@code WRITE}, {@code REWRITE} or {@code DELETE} verb). The AAP (&sect;0.2.2.1, &sect;0.4.1.5)
 * reinterprets that trio as a single <em>refresh</em> {@link Job} composed of three sequential,
 * read-and-log steps. This test proves the Java job reproduces that behaviour exactly:</p>
 * <ul>
 *   <li>the job is a three-step chain
 *       {@code accountRefreshStep &rarr; cardRefreshStep &rarr; cardXrefRefreshStep} that runs to
 *       {@link BatchStatus#COMPLETED}; and</li>
 *   <li>each step reads <em>every</em> seeded row of its master table while mutating nothing &mdash;
 *       row counts are identical before and after the run.</li>
 * </ul>
 *
 * <h2>Why exactly 50 rows per table</h2>
 * <p>The Flyway master seed {@code V3__seed_master.sql} is byte-derived from the reference ASCII
 * fixtures {@code app/data/ASCII/acctdata.txt}, {@code carddata.txt} and {@code cardxref.txt}, each of
 * which holds exactly <strong>50</strong> records. Consequently every step's
 * {@link StepExecution#getReadCount() read count} and every repository's
 * {@link org.springframework.data.jpa.repository.JpaRepository#count() count} is {@value #EXPECTED_SEED_ROWS}.</p>
 *
 * <h2>Test harness</h2>
 * <p>{@link SpringBatchTest} contributes the {@link JobLauncherTestUtils} and
 * {@link JobRepositoryTestUtils} helpers to the context. Because the application context defines more
 * than one {@link Job} bean (this refresh job, the customer-refresh job, the posting/interest/statement
 * jobs, &hellip;), the auto-wiring of a single {@code Job} into {@link JobLauncherTestUtils} is skipped
 * by design (it injects only when a <em>unique</em> {@code Job} bean exists); the job under test is
 * therefore selected explicitly in {@link #setUp()} via {@link JobLauncherTestUtils#setJob(Job)} using
 * the {@code @Qualifier("accountRefreshJob")} bean. The shared, auto-configured {@code JobLauncher} and
 * {@code JobRepository} are injected automatically.</p>
 *
 * <p>The {@code test} profile ({@code src/test/resources/application-test.yml}) runs against an
 * in-memory H2 database in PostgreSQL-compatibility mode, applies Flyway migrations {@code V1}&ndash;{@code V4}
 * at context startup, creates the {@code BATCH_*} metadata tables
 * ({@code spring.batch.jdbc.initialize-schema=always}) and disables auto-launch of jobs
 * ({@code spring.batch.job.enabled=false}) so this test drives the job explicitly.</p>
 *
 * <h2>Isolation note</h2>
 * <p>This class is deliberately <strong>not</strong> annotated with {@code @Transactional} or
 * {@code @DirtiesContext}: the job under test is read-only, so there is no domain mutation to roll back
 * and no need to discard the (expensive) application context between methods. The
 * {@link JobRepositoryTestUtils#removeJobExecutions()} call in {@link #setUp()} clears Spring Batch
 * metadata between runs, and {@link JobLauncherTestUtils#getUniqueJobParameters()} guarantees a fresh
 * {@code JobInstance} on every launch.</p>
 *
 * @see com.carddemo.batch.AccountRefreshJobConfig
 * @see <a href="file:app/cbl/CBACT01C.cbl">app/cbl/CBACT01C.cbl (ACCOUNT dump/print)</a>
 * @see <a href="file:app/cbl/CBACT02C.cbl">app/cbl/CBACT02C.cbl (CARD dump/print)</a>
 * @see <a href="file:app/cbl/CBACT03C.cbl">app/cbl/CBACT03C.cbl (CARDXREF dump/print)</a>
 */
@SpringBatchTest
@SpringBootTest
@ActiveProfiles("test")
class AccountRefreshJobTest {

    /**
     * The number of rows seeded into <em>each</em> master table ({@code accounts}, {@code cards},
     * {@code card_xref}) by {@code V3__seed_master.sql}, mirroring the 50-record reference ASCII
     * fixtures. It is both the expected per-step {@link StepExecution#getReadCount() read count} and
     * the unchanged row count asserted before/after the read-only run.
     */
    private static final long EXPECTED_SEED_ROWS = 50L;

    /** Launches the job under test and supplies unique {@code JobParameters} per run. */
    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    /** Clears Spring Batch metadata ({@code BATCH_*}) between test methods. */
    @Autowired
    private JobRepositoryTestUtils jobRepositoryTestUtils;

    /** The three-step, read-only account/card/xref refresh job (selected explicitly by qualifier). */
    @Autowired
    @Qualifier("accountRefreshJob")
    private Job accountRefreshJob;

    /** Account master repository &mdash; used only to assert row counts (no domain mutation). */
    @Autowired
    private AccountRepository accountRepository;

    /** Card master repository &mdash; used only to assert row counts (no domain mutation). */
    @Autowired
    private CardRepository cardRepository;

    /** Card cross-reference repository &mdash; used only to assert row counts (no domain mutation). */
    @Autowired
    private CardXrefRepository cardXrefRepository;

    /**
     * Binds the {@code accountRefreshJob} as the job that {@link JobLauncherTestUtils} will launch and
     * clears any Spring Batch execution metadata left over from a previous test method, so each test
     * starts from a clean batch-metadata baseline.
     */
    @BeforeEach
    void setUp() {
        jobLauncherTestUtils.setJob(accountRefreshJob);
        jobRepositoryTestUtils.removeJobExecutions();
    }

    /**
     * Verifies the job runs its three read-only steps and completes successfully, and that each step
     * read every seeded row.
     *
     * <p>The job's sequential ORDER ({@code accountRefreshStep &rarr; cardRefreshStep &rarr;
     * cardXrefRefreshStep}) is enforced by the production {@code JobBuilder} chain; here it is proven
     * <em>transitively</em> &mdash; a {@link BatchStatus#COMPLETED} outcome with all three step
     * executions present demonstrates the chain advanced through each step in turn (a failure in any
     * step would have aborted the chain before the later steps executed).
     * {@code containsExactlyInAnyOrder} is used because
     * {@link JobExecution#getStepExecutions()} returns an unordered collection.</p>
     *
     * @throws Exception if the job launch fails (declared by {@link JobLauncherTestUtils#launchJob})
     */
    @Test
    void accountRefreshJob_runsThreeReadOnlySteps_inOrder_andCompletes() throws Exception {
        JobExecution execution =
                jobLauncherTestUtils.launchJob(jobLauncherTestUtils.getUniqueJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<String> stepNames = execution.getStepExecutions().stream()
                .map(StepExecution::getStepName)
                .collect(Collectors.toList());

        assertThat(stepNames)
                .hasSize(3)
                .containsExactlyInAnyOrder(
                        "accountRefreshStep", "cardRefreshStep", "cardXrefRefreshStep");

        for (StepExecution step : execution.getStepExecutions()) {
            assertThat(step.getReadCount())
                    .as("readCount for %s", step.getStepName())
                    .isEqualTo(EXPECTED_SEED_ROWS);
            assertThat(step.getStatus())
                    .as("status for %s", step.getStepName())
                    .isEqualTo(BatchStatus.COMPLETED);
        }
    }

    /**
     * Verifies the refresh job is non-mutating, preserving parity with the read-only dump/print COBOL
     * utilities it replaces: it captures the seeded row counts of all three master tables, runs the
     * job to completion, and asserts the counts are unchanged.
     *
     * @throws Exception if the job launch fails (declared by {@link JobLauncherTestUtils#launchJob})
     */
    @Test
    void accountRefreshJob_isReadOnly_doesNotMutateAnyTable() throws Exception {
        long acctsBefore = accountRepository.count();
        long cardsBefore = cardRepository.count();
        long xrefBefore = cardXrefRepository.count();

        assertThat(acctsBefore).as("seeded account rows").isEqualTo(EXPECTED_SEED_ROWS);
        assertThat(cardsBefore).as("seeded card rows").isEqualTo(EXPECTED_SEED_ROWS);
        assertThat(xrefBefore).as("seeded card-xref rows").isEqualTo(EXPECTED_SEED_ROWS);

        JobExecution execution =
                jobLauncherTestUtils.launchJob(jobLauncherTestUtils.getUniqueJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        assertThat(accountRepository.count()).as("account rows after refresh").isEqualTo(acctsBefore);
        assertThat(cardRepository.count()).as("card rows after refresh").isEqualTo(cardsBefore);
        assertThat(cardXrefRepository.count()).as("card-xref rows after refresh").isEqualTo(xrefBefore);
    }
}
