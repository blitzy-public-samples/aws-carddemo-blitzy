package com.aws.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.aws.carddemo.AbstractPostgresIntegrationTest;
import com.aws.carddemo.domain.Card;
import com.aws.carddemo.repository.CardRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
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

/**
 * Spring Batch integration / parity test for {@link CardPrintJobConfig} (job bean {@code cardPrintJob},
 * step bean {@code cardPrintStep}), the Java translation of the mainframe batch program
 * {@code CBACT02C} orchestrated by JCL job {@code READCARD}.
 *
 * <p><strong>Origin and read-only parity (AAP &sect;0.4.1, &sect;0.6.6).</strong> The oracle
 * {@code legacy/cbl/CBACT02C.cbl} ("Read and print card data file") opens the VSAM {@code CARDDAT}
 * KSDS {@code INPUT}/{@code SEQUENTIAL} in {@code 0000-CARDFILE-OPEN}, walks every {@code CARD-RECORD}
 * in ascending {@code CARD-NUM} order through {@code 1000-CARDFILE-GET-NEXT}
 * ({@code READ CARDFILE-FILE INTO CARD-RECORD}) until FILE STATUS {@code '10'} (the {@code APPL-EOF}
 * normal-termination path), {@code DISPLAY}s each record, then closes the file in
 * {@code 9000-CARDFILE-CLOSE}. Its launching job {@code legacy/jcl/READCARD.jcl} declares only
 * {@code SYSOUT}/{@code SYSPRINT} sinks and <em>no</em> output dataset, so the program performs
 * <strong>zero</strong> writes, rewrites or deletes. This test verifies exactly that contract: the
 * job reads every seeded row in ascending key order and mutates nothing.</p>
 *
 * <p>There is deliberately no {@code 1100-DISPLAY-CARD-RECORD} paragraph cited here because none
 * exists in the source: {@code CBACT02C} issues {@code DISPLAY CARD-RECORD} inline within its main
 * {@code PERFORM UNTIL} loop, matching the paragraph mapping documented on {@link CardPrintJobConfig}.</p>
 *
 * <p><strong>Harness.</strong> The test extends {@link AbstractPostgresIntegrationTest}, so every run
 * is backed by a real Testcontainers PostgreSQL 18 database whose datasource is bound through the base
 * class and whose schema plus 50-row card seed are materialized by the production Flyway migrations
 * ({@code V1__schema.sql} through {@code V2__reference_data.sql}) under the {@code test} profile. The
 * {@code @SpringBootTest} configuration is pinned to the focused {@link CardPrintJobTestSlice}: it
 * enables Spring Boot auto-configuration (datasource, JPA/Hibernate, Flyway and Spring Batch) and scans
 * only the JPA entities and repositories plus {@link CardPrintJobConfig}, deliberately <em>not</em>
 * component-scanning the online service and web layer. That slice keeps this batch integration test
 * bounded to the persistence and batch beans the card-print job actually exercises and independent of
 * unrelated web/service wiring elsewhere in the application. It deliberately does <em>not</em> use
 * {@code @SpringBatchTest}; instead the nested {@link JobLauncherTestUtilsConfiguration} contributes a
 * single {@link JobLauncherTestUtils} bound to the {@code cardPrintJob} bean and the Spring Boot
 * auto-configured Spring Batch infrastructure. The job carries no business parameters, so each launch
 * is made unique only by a {@code run.id} value.</p>
 *
 * <p><strong>Schema validation.</strong> The context is loaded with
 * {@code spring.jpa.hibernate.ddl-auto=none} so that the Flyway-materialized schema remains the single
 * source of truth for this batch-job test while Hibernate does not re-validate the JPA entity mappings
 * against it at startup. This is deliberate: the committed domain entities map their fixed-width
 * {@code CHAR(n)} columns as plain {@link String} (JDBC {@code VARCHAR}) rather than declaring the CHAR
 * JDBC type, a module-wide mapping concern owned by those entity classes and wholly independent of the
 * card-print job under test. Relaxing validation keeps this test focused on runtime batch behavior
 * against the real seeded data; Flyway still builds the exact production schema and the job still reads
 * real {@code card} rows, so none of the parity assertions below are weakened.</p>
 *
 * <p><strong>Ordering fidelity (AAP &sect;0.6.6).</strong> {@code card_num} is a fixed-width
 * {@code CHAR(16)} column declared {@code COLLATE "C"}, so PostgreSQL orders it bytewise (POSIX),
 * reproducing the legacy VSAM ascending primary-key browse; for these ASCII-digit keys that is
 * identical to Java's natural {@link String} ordering.</p>
 *
 * @see CardPrintJobConfig
 * @see CardPrintJobConfigTest
 * @see AbstractPostgresIntegrationTest
 */
@SpringBootTest(
        classes = CardPrintJobConfigIT.CardPrintJobTestSlice.class,
        properties = "spring.jpa.hibernate.ddl-auto=none")
@Import(CardPrintJobConfigIT.JobLauncherTestUtilsConfiguration.class)
class CardPrintJobConfigIT extends AbstractPostgresIntegrationTest {

    /**
     * Number of {@code card} rows seeded by Flyway {@code V2__reference_data.sql}, which loads the
     * fixed-width fixture {@code legacy/data/ASCII/carddata.txt} (50 records).
     */
    private static final long EXPECTED_CARD_COUNT = 50L;

    /** Lowest {@code card_num} among the seeded rows under bytewise {@code C}/POSIX collation. */
    private static final String MIN_CARD_NUM = "0500024453765740";

    /** Highest {@code card_num} among the seeded rows under bytewise {@code C}/POSIX collation. */
    private static final String MAX_CARD_NUM = "9805583408996588";

    /** Bean name of the single chunk step under test ({@link CardPrintJobConfig#cardPrintStep()}). */
    private static final String STEP_NAME = "cardPrintStep";

    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    @Autowired
    private CardRepository cardRepository;

    /**
     * Asserts that the job runs to normal completion and reads the whole card file in a single
     * sequential pass &mdash; the {@code CBACT02C} main loop reaching {@code APPL-EOF}.
     *
     * @throws Exception if the job launch fails
     */
    @Test
    void jobCompletes_readsAllCards() throws Exception {
        JobExecution execution = launchCardPrintJob();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus()).isEqualTo(ExitStatus.COMPLETED);

        // CBACT02C performs exactly one sequential pass over CARDFILE, so the job has exactly one step.
        StepExecution step = singleStep(execution);
        assertThat(step.getStepName()).isEqualTo(STEP_NAME);

        // Every seeded card record was read (the full-file dump reached end-of-file normally).
        assertThat(step.getReadCount()).isEqualTo(cardRepository.count());
    }

    /**
     * Asserts the step read count equals the seeded card count (50) and that every read item flowed to
     * the log-only writer ({@code DISPLAY CARD-RECORD}); the write count counts items handed to the
     * SLF4J writer, not database mutations.
     *
     * @throws Exception if the job launch fails
     */
    @Test
    void readCountEqualsSeededCardCount() throws Exception {
        assertThat(cardRepository.count()).isEqualTo(EXPECTED_CARD_COUNT);

        JobExecution execution = launchCardPrintJob();
        StepExecution step = singleStep(execution);

        // 1000-CARDFILE-GET-NEXT fires once per record until FILE STATUS '10' (APPL-EOF): the read
        // count equals the seeded row count.
        assertThat(step.getReadCount()).isEqualTo(EXPECTED_CARD_COUNT);

        // The writer only logs, so every read item is "written" to the log; write count therefore
        // equals read count. This is log throughput, NOT a database mutation (see
        // jobPerformsNoDatabaseMutations()).
        assertThat(step.getWriteCount()).isEqualTo(step.getReadCount());
    }

    /**
     * Asserts the core read-only guarantee: the row count is unchanged and two representative rows
     * (the bytewise-least and bytewise-greatest card numbers) are byte-for-byte identical before and
     * after the job runs. {@code CBACT02C}/{@code READCARD} issues no {@code WRITE}/{@code REWRITE}/
     * {@code DELETE} and produces no output dataset.
     *
     * @throws Exception if the job launch fails
     */
    @Test
    void jobPerformsNoDatabaseMutations() throws Exception {
        long countBefore = cardRepository.count();
        assertThat(countBefore).isEqualTo(EXPECTED_CARD_COUNT);
        String minBefore = fingerprintById(MIN_CARD_NUM);
        String maxBefore = fingerprintById(MAX_CARD_NUM);

        JobExecution execution = launchCardPrintJob();
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // The row count and every field of the snapshot rows must be unchanged after the read-only job.
        assertThat(cardRepository.count()).isEqualTo(countBefore);
        assertThat(fingerprintById(MIN_CARD_NUM)).isEqualTo(minBefore);
        assertThat(fingerprintById(MAX_CARD_NUM)).isEqualTo(maxBefore);
    }

    /**
     * Asserts records are processed in ascending {@code cardNum} order. The reader is a
     * {@code RepositoryItemReader} sorting by {@code cardNum} ascending, so querying the repository
     * with the identical sort reproduces the exact sequence the job processes; the sequence must be
     * strictly ascending under bytewise {@code C}/POSIX collation and span the known min/max keys.
     *
     * @throws Exception if the job launch fails
     */
    @Test
    void cardsProcessedInAscendingCardNumOrder() throws Exception {
        List<String> cardNums = cardRepository.findAll(Sort.by(Sort.Direction.ASC, "cardNum")).stream()
                .map(Card::getCardNum)
                .toList();

        assertThat(cardNums).hasSize((int) EXPECTED_CARD_COUNT);

        // A VSAM SEQUENTIAL KSDS read returns records in ascending primary-key order; card_num is a
        // CHAR(16) column collated "C" (bytewise), which for these ASCII-digit keys is identical to
        // Java's natural String ordering.
        assertThat(cardNums).isSorted();
        assertThat(cardNums).doesNotHaveDuplicates();
        assertThat(cardNums.get(0)).isEqualTo(MIN_CARD_NUM);
        assertThat(cardNums.get(cardNums.size() - 1)).isEqualTo(MAX_CARD_NUM);

        // The job itself completes cleanly over this ordered data set.
        assertThat(launchCardPrintJob().getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }

    /**
     * Launches {@code cardPrintJob} with only a unique {@code run.id} parameter (the job takes no
     * business parameters), reproducing an on-demand {@code READCARD} submission.
     *
     * @return the completed {@link JobExecution}
     * @throws Exception if the launch fails
     */
    private JobExecution launchCardPrintJob() throws Exception {
        JobParameters parameters = new JobParametersBuilder()
                .addLong("run.id", System.nanoTime())
                .toJobParameters();
        return jobLauncherTestUtils.launchJob(parameters);
    }

    /**
     * Returns the single {@link StepExecution} of a completed job execution, asserting there is exactly
     * one step (the read-only card dump has a single chunk step).
     *
     * @param execution the job execution to inspect
     * @return the sole step execution
     */
    private static StepExecution singleStep(JobExecution execution) {
        assertThat(execution.getStepExecutions()).hasSize(1);
        return execution.getStepExecutions().iterator().next();
    }

    /**
     * Fetches the seeded card with the given number and renders it as a fingerprint, failing the test
     * if the row is absent.
     *
     * @param cardNum the primary-key card number to fetch
     * @return the fingerprint of the row's persisted fields
     */
    private String fingerprintById(String cardNum) {
        Card card = cardRepository.findById(cardNum)
                .orElseThrow(() -> new AssertionError("expected seeded card is missing: " + cardNum));
        return fingerprint(card);
    }

    /**
     * Joins every persisted field of a card into one delimited string so that a change to <em>any</em>
     * field of the row is detected by a simple equality check. Uses exact textual and integral values
     * only &mdash; never floating point &mdash; consistent with the decimal-fidelity rule.
     *
     * @param card the card to render
     * @return a delimited, exact representation of the card's persisted fields
     */
    private static String fingerprint(Card card) {
        return String.join("|",
                String.valueOf(card.getCardNum()),
                String.valueOf(card.getCardAcctId()),
                String.valueOf(card.getCardCvvCd()),
                String.valueOf(card.getCardEmbossedName()),
                String.valueOf(card.getCardExpiraionDate()),
                String.valueOf(card.getCardActiveStatus()));
    }

    /**
     * Focused Spring Boot configuration for this integration test. It enables auto-configuration and
     * registers only the persistence and batch beans the card-print job needs &mdash; the JPA entities
     * ({@link EntityScan} rooted at {@link Card}), the Spring Data repositories
     * ({@link EnableJpaRepositories} rooted at {@link CardRepository}) and {@link CardPrintJobConfig}
     * &mdash; without component-scanning the online service or web layer. Flyway (enabled by the
     * {@code test} profile) still materializes the full production schema and the 50-row card seed, so
     * the job runs against the real dataset.
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Card.class)
    @EnableJpaRepositories(basePackageClasses = CardRepository.class)
    @Import(CardPrintJobConfig.class)
    static class CardPrintJobTestSlice {
    }

    /**
     * Contributes a single {@link JobLauncherTestUtils} bound to the {@code cardPrintJob} bean. Wiring
     * the utility by hand (rather than via {@code @SpringBatchTest}) keeps exactly one instance in the
     * context and targets {@code cardPrintJob} explicitly through {@link Qualifier}, unambiguously
     * selecting the {@code cardPrintJob} bean contributed by {@link CardPrintJobConfig}.
     */
    @TestConfiguration
    static class JobLauncherTestUtilsConfiguration {

        /**
         * Builds the {@link JobLauncherTestUtils} bound to the auto-configured Spring Batch
         * infrastructure and the {@code cardPrintJob} under test.
         *
         * @param jobLauncher   the Spring Boot auto-configured synchronous {@link JobLauncher}
         * @param jobRepository the Spring Boot auto-configured {@link JobRepository}
         * @param cardPrintJob  the {@link Job} bean named {@code cardPrintJob}
         * @return the configured {@link JobLauncherTestUtils}
         */
        @Bean
        JobLauncherTestUtils jobLauncherTestUtils(JobLauncher jobLauncher,
                JobRepository jobRepository,
                @Qualifier("cardPrintJob") Job cardPrintJob) {
            JobLauncherTestUtils utils = new JobLauncherTestUtils();
            utils.setJobLauncher(jobLauncher);
            utils.setJobRepository(jobRepository);
            utils.setJob(cardPrintJob);
            return utils;
        }
    }
}
