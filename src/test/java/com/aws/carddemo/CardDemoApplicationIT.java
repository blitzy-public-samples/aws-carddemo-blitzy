package com.aws.carddemo;

import static org.assertj.core.api.Assertions.assertThat;

import com.aws.carddemo.domain.Account;
import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.domain.UserSecurity;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.service.online.SignonService;
import com.aws.carddemo.web.controller.SignonController;
import jakarta.persistence.EntityManagerFactory;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Application boot / smoke integration test for the AWS CardDemo migration
 * (COBOL/CICS/VSAM/JCL/BMS &rarr; Java 25 LTS + Spring Boot 3.5.16).
 *
 * <p>This is the highest-signal "is the whole thing wired correctly?" check in the suite: it starts
 * the <em>entire</em> Spring application context under the {@code test} profile against a real
 * Testcontainers PostgreSQL 18 (inherited from {@link AbstractPostgresIntegrationTest}) and proves
 * four independent, production-parity facts:</p>
 * <ol>
 *   <li><strong>Context boot</strong> &mdash; the full {@link ApplicationContext} refreshes without
 *       error ({@link #contextLoads()}).</li>
 *   <li><strong>Exact Flyway migration set</strong> &mdash; precisely {@code V0}, {@code V1},
 *       {@code V2} and {@code V3} are applied and every one is in
 *       {@link MigrationState#SUCCESS} state ({@link #flywayMigrationsAppliedExactlyV0ThroughV3()}).</li>
 *   <li><strong>No pending or failed migrations</strong> &mdash; nothing is left un-applied and no
 *       migration is in a failed state ({@link #noPendingOrFailedMigrations()}).</li>
 *   <li><strong>Hibernate schema validation</strong> &mdash; because the {@code test} profile keeps
 *       {@code spring.jpa.hibernate.ddl-auto=validate}, a booted context with a populated JPA
 *       metamodel proves every entity mapping matches the Flyway-created schema
 *       ({@link #hibernateSchemaValidationSucceeded()}). This is the subtle but powerful
 *       CHAR/NUMERIC alignment parity guarantee for the whole V1 schema (AAP &sect;0.6.2).</li>
 *   <li><strong>Core bean wiring</strong> &mdash; representative beans from every layer
 *       (persistence, JPA, repository, service, web, security, batch) are present
 *       ({@link #coreBeansWired()}).</li>
 * </ol>
 *
 * <p><strong>Naming / discovery (review finding MJ-27).</strong> This class ends in {@code IT}, not
 * {@code Tests}. It requires a Testcontainers PostgreSQL and therefore a reachable Docker daemon, so
 * it is a genuine <em>integration</em> test and belongs to the Maven Failsafe {@code **}{@code /*IT.java}
 * set that runs in the {@code integration-test}/{@code verify} phases &mdash; disjoint from the
 * Surefire {@code **}{@code /*Test.java} unit set (see the plugin contract in the root {@code pom.xml}).
 * The previous name {@code CardDemoApplicationTests} matched neither the narrowed Surefire include
 * ({@code **}{@code /*Test.java}) nor the Failsafe include ({@code **}{@code /*IT.java}) and was
 * therefore never executed; renaming to {@code *IT} restores discovery at its root cause without
 * altering the build configuration.</p>
 *
 * <p><strong>Origin:</strong> net-new (no COBOL ancestor); a framework-mandated deliverable per AAP
 * &sect;0.2.1. It is the sole test that exercises {@code CardDemoApplication}, which is excluded from
 * the JaCoCo coverage numerator in the root {@code pom.xml}; no additional {@code CardDemoApplication}
 * tests are added beyond this boot check.</p>
 *
 * <p>Inherits {@code @SpringBootTest}, {@code @ActiveProfiles("test")}, {@code @Testcontainers} and
 * the single shared PostgreSQL container from {@link AbstractPostgresIntegrationTest}; it deliberately
 * re-declares none of them and starts no second container.</p>
 */
class CardDemoApplicationIT extends AbstractPostgresIntegrationTest {

    /** The fully-refreshed application context under test (net-new; no COBOL ancestor). */
    @Autowired
    private ApplicationContext applicationContext;

    /** The Flyway bean, present because Flyway migrations are enabled on the {@code test} profile. */
    @Autowired
    private Flyway flyway;

    /** The JPA {@link EntityManagerFactory}; its metamodel proves {@code ddl-auto=validate} succeeded. */
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    /**
     * The context refreshes end-to-end. A non-null context is only possible if every autoconfiguration,
     * Flyway migration and Hibernate {@code validate} pass performed during startup succeeded.
     */
    @Test
    void contextLoads() {
        assertThat(applicationContext).isNotNull();
    }

    /**
     * Exactly the versioned migrations {@code V0}, {@code V1}, {@code V2} and {@code V3} are applied,
     * and every one is in {@link MigrationState#SUCCESS} state. Asserting the exact set (rather than a
     * weak {@code >= 3} lower bound) guarantees the full {@code V0} (Spring Batch metadata) &rarr;
     * {@code V1} (schema) &rarr; {@code V2} (reference data) &rarr; {@code V3} (indexes) chain ran and
     * that no unexpected extra migration slipped in.
     */
    @Test
    void flywayMigrationsAppliedExactlyV0ThroughV3() {
        MigrationInfo[] applied = flyway.info().applied();

        Set<String> appliedVersions = Arrays.stream(applied)
                .filter(m -> m.getVersion() != null)
                .map(m -> m.getVersion().getVersion())
                .collect(Collectors.toSet());
        assertThat(appliedVersions)
                .as("exactly the versioned Flyway migrations V0..V3 must be applied")
                .containsExactlyInAnyOrder("0", "1", "2", "3");

        Arrays.stream(applied)
                .filter(m -> m.getVersion() != null)
                .forEach(m -> assertThat(m.getState())
                        .as("migration V%s (%s) must have applied successfully",
                                m.getVersion().getVersion(), m.getDescription())
                        .isEqualTo(MigrationState.SUCCESS));
    }

    /**
     * No migration is left pending after startup and none is in a failed state. Combined with
     * {@link #flywayMigrationsAppliedExactlyV0ThroughV3()} this proves the schema is fully and cleanly
     * materialised before Hibernate validation and before any test touches the database.
     */
    @Test
    void noPendingOrFailedMigrations() {
        assertThat(flyway.info().pending())
                .as("no Flyway migration may remain pending after context startup")
                .isEmpty();

        Arrays.stream(flyway.info().all())
                .forEach(m -> assertThat(m.getState().isFailed())
                        .as("migration V%s (%s) must not be in a failed state (was %s)",
                                m.getVersion() != null ? m.getVersion().getVersion() : "repeatable",
                                m.getDescription(), m.getState())
                        .isFalse());
    }

    /**
     * Hibernate ran with {@code ddl-auto=validate} during startup, so a populated JPA metamodel proves
     * that every entity mapping matches the Flyway-created schema (the CHAR/NUMERIC alignment parity
     * guarantee, AAP &sect;0.6.2). Verifies all 10 CardDemo entities are mapped, spot-checking the
     * account, user-security and transaction records.
     */
    @Test
    void hibernateSchemaValidationSucceeded() {
        assertThat(entityManagerFactory).isNotNull();

        Set<Class<?>> mappedEntities = entityManagerFactory.getMetamodel().getEntities().stream()
                .map(e -> e.getJavaType())
                .collect(Collectors.toSet());

        assertThat(mappedEntities)
                .as("all 10 CardDemo JPA entities must be mapped and validated against the Flyway schema")
                .hasSizeGreaterThanOrEqualTo(10)
                .contains(Account.class, UserSecurity.class, Transaction.class);
    }

    /**
     * A representative bean from each architectural layer is wired into the context: the JDBC
     * {@link DataSource} and {@link Flyway} (persistence infrastructure), the
     * {@link EntityManagerFactory} (JPA), a Spring Data repository, an online service, an MVC
     * controller, the Spring Security {@link SecurityFilterChain}, and the Spring Batch
     * {@link JobRepository} plus at least one {@link Job}. Together these prove the full
     * web &rarr; service &rarr; repository &rarr; database stack, the security chain and the batch tier
     * are all constructed successfully.
     */
    @Test
    void coreBeansWired() {
        // Persistence + migration infrastructure.
        assertBeanWired(DataSource.class);
        assertBeanWired(Flyway.class);
        // JPA.
        assertBeanWired(EntityManagerFactory.class);
        // Repository -> service -> web layers.
        assertBeanWired(AccountRepository.class);
        assertBeanWired(SignonService.class);
        assertBeanWired(SignonController.class);
        // Security chain (replaces app signon + RACF).
        assertBeanWired(SecurityFilterChain.class);
        // Batch tier (replaces JCL/JES2): metadata repository plus the migrated business jobs.
        assertBeanWired(JobRepository.class);
        assertThat(applicationContext.getBeanNamesForType(Job.class))
                .as("at least one migrated Spring Batch Job must be wired")
                .isNotEmpty();
    }

    /**
     * Asserts that at least one Spring bean of the given type is wired into the context. Uses
     * {@link ApplicationContext#getBeanNamesForType(Class)} so the check remains valid even if a type
     * later has more than one bean (for example an additional {@link SecurityFilterChain}).
     *
     * @param beanType the required bean type
     */
    private void assertBeanWired(Class<?> beanType) {
        assertThat(applicationContext.getBeanNamesForType(beanType))
                .as("expected at least one Spring bean of type %s to be wired", beanType.getName())
                .isNotEmpty();
    }
}
