package com.aws.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.aws.carddemo.domain.TransactionCategoryBalance;
import com.aws.carddemo.repository.TransactionCategoryBalanceRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end integration test for {@link CategoryBalancePrintJobConfig}, reproducing the
 * {@code PRTCATBL.jcl} {@code STEP10R} {@code SORT}/{@code OUTREC}/{@code EDIT} behavior against a
 * real PostgreSQL database: it seeds {@code TCATBAL} rows deliberately out of key order, launches the
 * {@code categoryBalancePrintJob}, and compares the produced report against a byte-exact 40-byte
 * oracle — including negative and zero balances and the {@code (acctId, typeCd, catCd)} ordering.
 *
 * <p><strong>Database provisioning.</strong> The test follows the repository convention of a
 * Testcontainers PostgreSQL 18 instance (matching {@code application-test.yml}). Because
 * {@code spring-boot-testcontainers} (and therefore {@code @ServiceConnection}) is not on the test
 * classpath, the container's JDBC coordinates are bound with {@link DynamicPropertySource}. The
 * schema is materialized by Hibernate ({@code ddl-auto=create-drop}) with Flyway disabled, and the
 * Spring Batch metadata tables by {@code spring.batch.jdbc.initialize-schema=always}. This keeps the
 * test self-contained — it does <em>not</em> depend on the {@code V1__schema.sql} Flyway migration
 * (authored elsewhere in the migration) — while still exercising the job against a genuine
 * PostgreSQL engine rather than an in-memory substitute.</p>
 *
 * <p><strong>Runtime prerequisite.</strong> Execution requires a Testcontainers-capable environment
 * (a reachable {@code postgres:18-alpine} image via a running Docker daemon). Where that image is not
 * available offline, the parity contract (the exact 40-byte layout, the {@code EDIT=(TTTTTTTTT.TT)}
 * mask, and the field byte positions) is additionally covered — fully in-process — by
 * {@link CategoryBalancePrintJobConfigTest}.</p>
 *
 * <p>Origin: {@code legacy/jcl/PRTCATBL.jcl}, {@code legacy/jcl/REPTFILE.jcl}. See AAP
 * &sect;0.4.1, &sect;0.6.1, &sect;0.6.3, &sect;0.6.6.</p>
 *
 * @see CategoryBalancePrintJobConfig
 * @see CategoryBalancePrintJobConfigTest
 */
@Testcontainers
@SpringBatchTest
@SpringBootTest(
        classes = CategoryBalancePrintJobConfigIT.BatchIntegrationTestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.open-in-view=false",
            "spring.batch.jdbc.initialize-schema=always",
            "spring.batch.job.enabled=false"
        })
class CategoryBalancePrintJobConfigIT {

    /** Report record width — {@code PRTCATBL.jcl} {@code SORTOUT LRECL=40}. */
    private static final int REPORT_WIDTH = 40;

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:18-alpine"))
            .withDatabaseName("carddemo")
            .withUsername("carddemo")
            .withPassword("carddemo");

    /**
     * Binds the container's JDBC coordinates onto the Spring datasource properties. Used instead of
     * {@code @ServiceConnection} because {@code spring-boot-testcontainers} is absent from the test
     * classpath.
     *
     * @param registry the dynamic property registry supplied by the Spring TestContext framework
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /**
     * Minimal Spring Boot slice: auto-configuration for the datasource, JPA and Spring Batch, plus the
     * single domain entity and repository this job needs and the job configuration under test. It
     * deliberately avoids the web and security layers, which are irrelevant to a batch report job.
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = TransactionCategoryBalance.class)
    @EnableJpaRepositories(basePackageClasses = TransactionCategoryBalanceRepository.class)
    @Import(CategoryBalancePrintJobConfig.class)
    static class BatchIntegrationTestConfig {
    }

    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    @Autowired
    private TransactionCategoryBalanceRepository repository;

    @Autowired
    private Job categoryBalancePrintJob;

    @Test
    void producesOrderedFortyByteReport(@TempDir Path tempDir) throws Exception {
        // Seed four rows deliberately OUT of (acctId, typeCd, catCd) order, spanning a maximum
        // magnitude, a zero balance and a negative balance so the EDIT mask and the sort are both
        // exercised.
        repository.saveAll(List.of(
                balance(200L, "01", 10, "504.77"),
                balance(100L, "02", 5, "-12.34"),
                balance(100L, "01", 5, "0.00"),
                balance(100L, "01", 3, "999999999.99")));

        Path reportFile = tempDir.resolve("PRTCATBL.REPT");
        JobParameters parameters = new JobParametersBuilder()
                .addString("outputPath", reportFile.toString())
                .addLong("run.id", System.nanoTime())
                .toJobParameters();

        jobLauncherTestUtils.setJob(categoryBalancePrintJob);
        JobExecution execution = jobLauncherTestUtils.launchJob(parameters);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus()).isEqualTo(ExitStatus.COMPLETED);

        // FixedWidthRecordMapper emits a single-byte ISO-8859-1 record; the content is pure ASCII.
        List<String> lines = Files.readAllLines(reportFile, StandardCharsets.ISO_8859_1);

        // Every report record is exactly 40 bytes wide.
        assertThat(lines).hasSize(4);
        for (String line : lines) {
            assertThat(line).as("report line width").hasSize(REPORT_WIDTH);
            assertThat(line.getBytes(StandardCharsets.ISO_8859_1)).hasSize(REPORT_WIDTH);
        }

        // Rows are ordered by (acctId ASC, typeCd ASC, catCd ASC); balances are rendered with the
        // EDIT=(TTTTTTTTT.TT) mask (leading zeros retained, sign dropped, truncated toward zero).
        List<String> expected = List.of(
                "00000000100 01 0003 999999999.99        ",
                "00000000100 01 0005 000000000.00        ",
                "00000000100 02 0005 000000012.34        ",
                "00000000200 01 0010 000000504.77        ");
        assertThat(lines).containsExactlyElementsOf(expected);
    }

    private static TransactionCategoryBalance balance(long acctId, String typeCd, int catCd,
            String amount) {
        TransactionCategoryBalance record = new TransactionCategoryBalance();
        record.setAcctId(acctId);
        record.setTypeCd(typeCd);
        record.setCatCd(catCd);
        record.setBalance(new BigDecimal(amount));
        return record;
    }
}
