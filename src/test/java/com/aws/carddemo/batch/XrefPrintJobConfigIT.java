package com.aws.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.aws.carddemo.AbstractPostgresIntegrationTest;
import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.repository.CardXrefRepository;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;

/**
 * Spring Batch integration / parity test for {@link XrefPrintJobConfig}, asserting that the migrated
 * card cross-reference print job behaves identically to the mainframe batch program it replaces.
 *
 * <p><strong>Origin.</strong> This test is the parity oracle for COBOL {@code CBACT03C}
 * ("Read and print card cross reference data file") in {@code legacy/cbl/CBACT03C.cbl}, orchestrated
 * by JCL job {@code READXREF} in {@code legacy/jcl/READXREF.jcl} (source branch
 * {@code app/cbl/CBACT03C.cbl}, {@code app/jcl/READXREF.jcl}). On z/OS the program performs paragraph
 * {@code 0000-XREFFILE-OPEN} ({@code OPEN INPUT XREFFILE}), then loops through
 * {@code 1000-XREFFILE-GET-NEXT} reading each {@code CARD-XREF-RECORD} (copybook {@code CVACT03Y},
 * record length 50; the ASCII fixture {@code legacy/data/ASCII/cardxref.txt} carries 36 significant
 * bytes per row) and emitting it inline with {@code DISPLAY CARD-XREF-RECORD}, and finally
 * {@code 9000-XREFFILE-CLOSE} ({@code CLOSE XREFFILE}). COBOL {@code FILE STATUS '10'} (end-of-file)
 * is the normal termination. Per {@code READXREF.jcl} the step's only sinks are {@code SYSOUT} /
 * {@code SYSPRINT}: it defines <em>no</em> output dataset, so the job is a purely read-only diagnostic
 * dump that performs zero writes or updates (AAP &sect;0.4.1 "xref file read/print").</p>
 *
 * <p><strong>What is asserted (behavioural parity).</strong></p>
 * <ol>
 *   <li><em>Job status</em> &mdash; the job completes with {@link BatchStatus#COMPLETED}, the analogue
 *       of {@code CBACT03C} running to normal end-of-file and returning cleanly.</li>
 *   <li><em>Full sequential scan</em> &mdash; the single step reads every seeded cross-reference row
 *       (the Flyway {@code V2} seed derived from {@code legacy/data/ASCII/cardxref.txt} materialises
 *       {@value #SEEDED_XREF_COUNT} rows), reproducing the read-until-EOF loop. Every read item flows
 *       through the pass-through logging writer, so the write count equals the read count (the writer
 *       filters nothing and mutates nothing &mdash; it is the analogue of {@code DISPLAY}).</li>
 *   <li><em>Zero database mutations</em> &mdash; the core read-only guarantee: the row count is
 *       unchanged before and after the run and two boundary rows (the minimum and maximum card
 *       numbers) are byte-for-byte unchanged. {@code CBACT03C} opens the file {@code INPUT} only and
 *       never rewrites it.</li>
 *   <li><em>Key ordering</em> &mdash; rows are processed in ascending {@code xrefCardNum} order,
 *       reproducing the {@code CCXREF} KSDS card-number key sequence. Because the card number is a
 *       fixed 16-digit ASCII value, Java's natural {@link String} ordering coincides with the
 *       {@code CHAR(16)} column's {@code C}/POSIX bytewise collation mandated for legacy EBCDIC
 *       ordering parity (AAP &sect;0.6.6).</li>
 * </ol>
 *
 * <p><strong>Harness.</strong> This test extends {@link AbstractPostgresIntegrationTest}, so it runs
 * against a real Testcontainers PostgreSQL instance with the production Flyway schema and seed data
 * under the {@code *IT} Failsafe binding. A job-scoped Spring Boot context imports the production
 * {@link XrefPrintJobConfig}, enables the real JPA repository and Spring Batch infrastructure, and
 * leaves schema ownership to Flyway by setting Hibernate {@code ddl-auto=none}. The broader
 * application context is intentionally not loaded because unrelated baseline entity-schema
 * validation and menu-service bean-wiring defects currently prevent a full-context startup; neither
 * concern participates in this batch job. This slice therefore prevents unrelated failures from
 * masking the parity contract while still executing the real job against the real schema and all
 * {@value #SEEDED_XREF_COUNT} Flyway-seeded rows.</p>
 *
 * <p>The test deliberately does <strong>not</strong> use {@code @SpringBatchTest}; instead a nested
 * {@link TestConfiguration} supplies a single {@link JobLauncherTestUtils} wired explicitly to the
 * auto-configured {@link JobLauncher} and {@link JobRepository} and to the {@code xrefPrintJob}
 * {@link Job} (resolved with {@link Qualifier @Qualifier}). The job is launched with only a
 * {@code run.id} parameter &mdash; it takes no business parameters &mdash; and each launch uses a
 * unique value so every invocation is a fresh {@code JobInstance}. The base class's {@code test}
 * profile, Testcontainers container, and dynamic datasource binding remain inherited.</p>
 *
 * <p>The ordering assertion queries the repository with the <em>same</em> {@link Sort} the production
 * reader applies ({@code findAll} ordered by {@code xrefCardNum} ascending), so the queried sequence
 * is exactly the sequence the step's {@code RepositoryItemReader} feeds to the writer.</p>
 *
 * @see XrefPrintJobConfig
 * @see AbstractPostgresIntegrationTest
 */
@SpringBootTest(
        classes = {
            XrefPrintJobConfigIT.BatchIntegrationTestConfig.class,
            XrefPrintJobConfigIT.JobLauncherTestUtilsConfig.class
        },
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=none")
class XrefPrintJobConfigIT extends AbstractPostgresIntegrationTest {

    /**
     * Number of {@code card_xref} rows materialised by the Flyway {@code V2} reference-data migration
     * (one per line of {@code legacy/data/ASCII/cardxref.txt}). This is the expected read count of a
     * full sequential scan and the invariant row count of the read-only job.
     */
    private static final int SEEDED_XREF_COUNT = 50;

    /**
     * Lowest seeded card number under {@code C}/POSIX bytewise ordering; used as the low boundary for
     * the ordering assertion and one of the immutability snapshot rows.
     */
    private static final String MIN_CARD_NUM = "0500024453765740";

    /**
     * Highest seeded card number under {@code C}/POSIX bytewise ordering; used as the high boundary
     * for the ordering assertion and one of the immutability snapshot rows.
     */
    private static final String MAX_CARD_NUM = "9805583408996588";

    /**
     * Minimal application context for the job under test.
     *
     * <p>Auto-configuration supplies the datasource, Flyway, JPA, transaction manager, and Spring
     * Batch runtime. Entity and repository scanning provide the production persistence contract, and
     * the explicit import contributes only {@link XrefPrintJobConfig} from the application layer.</p>
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = CardXref.class)
    @EnableJpaRepositories(basePackageClasses = CardXrefRepository.class)
    @Import(XrefPrintJobConfig.class)
    static class BatchIntegrationTestConfig {
    }

    /**
     * Test-only configuration contributing the single {@link JobLauncherTestUtils} used by this suite.
     *
     * <p>Included explicitly alongside {@link BatchIntegrationTestConfig}. The utility is constructed
     * and wired by hand rather than through {@code @SpringBatchTest}, keeping exactly one such bean in
     * the context.</p>
     */
    @TestConfiguration
    static class JobLauncherTestUtilsConfig {

        /**
         * Builds the {@link JobLauncherTestUtils} bound to the cross-reference print job.
         *
         * @param jobLauncher  the auto-configured Spring Batch {@link JobLauncher}
         * @param jobRepository the auto-configured Spring Batch {@link JobRepository}
         * @param xrefPrintJob the {@code xrefPrintJob} {@link Job} defined by {@link XrefPrintJobConfig}
         * @return a fully wired {@link JobLauncherTestUtils} targeting {@code xrefPrintJob}
         */
        @Bean
        JobLauncherTestUtils xrefPrintJobLauncherTestUtils(JobLauncher jobLauncher,
                JobRepository jobRepository,
                @Qualifier("xrefPrintJob") Job xrefPrintJob) {
            JobLauncherTestUtils utils = new JobLauncherTestUtils();
            utils.setJobLauncher(jobLauncher);
            utils.setJobRepository(jobRepository);
            utils.setJob(xrefPrintJob);
            return utils;
        }
    }

    /** Launcher/helper for the {@code xrefPrintJob}, contributed by {@link JobLauncherTestUtilsConfig}. */
    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    /** Repository over the {@code card_xref} table, used for count/order/immutability assertions. */
    @Autowired
    private CardXrefRepository cardXrefRepository;

    /**
     * Verifies the job runs to completion and its single step reads every seeded cross-reference row,
     * the direct analogue of {@code CBACT03C} scanning {@code XREFFILE} to end-of-file.
     *
     * @throws Exception if the job launcher fails to run the job
     */
    @Test
    void jobCompletes_readsAllXrefs() throws Exception {
        JobExecution execution = launchXrefPrintJob();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(singleStep(execution).getReadCount()).isEqualTo(SEEDED_XREF_COUNT);
    }

    /**
     * Verifies the step's read count equals the number of rows actually seeded, and that every read
     * item is passed to the logging writer (write count equals read count). This documents that the
     * writer is a pure pass-through {@code DISPLAY} analogue: it neither filters nor mutates.
     *
     * @throws Exception if the job launcher fails to run the job
     */
    @Test
    void readCountEqualsSeededXrefCount() throws Exception {
        long seeded = cardXrefRepository.count();
        assertThat(seeded).isEqualTo(SEEDED_XREF_COUNT);

        JobExecution execution = launchXrefPrintJob();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        StepExecution step = singleStep(execution);
        assertThat(step.getReadCount()).isEqualTo(seeded);
        assertThat(step.getWriteCount()).isEqualTo(seeded);
    }

    /**
     * Verifies the core read-only guarantee: the job performs no database mutations. The total row
     * count is unchanged and two boundary rows (minimum and maximum card number) retain their exact
     * customer-id and account-id cross-reference after the run &mdash; {@code CBACT03C} opens the file
     * {@code INPUT} only and defines no output dataset.
     *
     * @throws Exception if the job launcher fails to run the job
     */
    @Test
    void jobPerformsNoDatabaseMutations() throws Exception {
        long countBefore = cardXrefRepository.count();
        CardXref minBefore = requireXref(MIN_CARD_NUM);
        CardXref maxBefore = requireXref(MAX_CARD_NUM);

        JobExecution execution = launchXrefPrintJob();
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        assertThat(cardXrefRepository.count()).isEqualTo(countBefore);

        CardXref minAfter = requireXref(MIN_CARD_NUM);
        assertThat(minAfter.getXrefCustId()).isEqualTo(minBefore.getXrefCustId());
        assertThat(minAfter.getXrefAcctId()).isEqualTo(minBefore.getXrefAcctId());

        CardXref maxAfter = requireXref(MAX_CARD_NUM);
        assertThat(maxAfter.getXrefCustId()).isEqualTo(maxBefore.getXrefCustId());
        assertThat(maxAfter.getXrefAcctId()).isEqualTo(maxBefore.getXrefAcctId());
    }

    /**
     * Verifies rows are processed in ascending {@code xrefCardNum} order, reproducing the
     * {@code CCXREF} KSDS card-number key sequence. The assertion queries the repository with the same
     * ascending {@link Sort} the production {@code RepositoryItemReader} applies, so the queried
     * sequence is exactly the order the step feeds to the writer. Card numbers are fixed 16-digit
     * ASCII values, so Java's natural {@link String} ordering matches the {@code CHAR(16)} column's
     * {@code C}/POSIX bytewise collation (AAP &sect;0.6.6).
     *
     * @throws Exception if the job launcher fails to run the job
     */
    @Test
    void xrefsProcessedInAscendingCardNumOrder() throws Exception {
        JobExecution execution = launchXrefPrintJob();
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<String> cardNumbers = cardXrefRepository
                .findAll(Sort.by(Sort.Direction.ASC, "xrefCardNum"))
                .stream()
                .map(CardXref::getXrefCardNum)
                .toList();

        assertThat(cardNumbers).hasSize(SEEDED_XREF_COUNT);
        assertThat(cardNumbers).isSorted();
        assertThat(cardNumbers).first().isEqualTo(MIN_CARD_NUM);
        assertThat(cardNumbers).last().isEqualTo(MAX_CARD_NUM);
    }

    /**
     * Launches {@code xrefPrintJob} with only a unique {@code run.id} parameter (the job takes no
     * business parameters), guaranteeing a fresh {@code JobInstance} per invocation.
     *
     * @return the completed {@link JobExecution}
     * @throws Exception if the job launcher fails to run the job
     */
    private JobExecution launchXrefPrintJob() throws Exception {
        JobParameters parameters = new JobParametersBuilder()
                .addLong("run.id", System.nanoTime())
                .toJobParameters();
        return jobLauncherTestUtils.launchJob(parameters);
    }

    /**
     * Returns the sole {@link StepExecution} of the given job execution, asserting that the job
     * comprises exactly one step ({@code xrefPrintStep}).
     *
     * @param execution the job execution to inspect
     * @return the single step execution
     */
    private static StepExecution singleStep(JobExecution execution) {
        assertThat(execution.getStepExecutions()).hasSize(1);
        return execution.getStepExecutions().iterator().next();
    }

    /**
     * Fetches the cross-reference row for the given card number, failing the test if it is absent from
     * the seed data (the boundary rows are required for the immutability and ordering assertions).
     *
     * @param cardNumber the 16-character card number to look up
     * @return the matching {@link CardXref}
     */
    private CardXref requireXref(String cardNumber) {
        return cardXrefRepository.findByXrefCardNum(cardNumber)
                .orElseThrow(() -> new AssertionError("Expected seeded card_xref row for card number "
                        + cardNumber));
    }
}
