package com.aws.carddemo;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Shared base for all CardDemo integration / parity tests ({@code *IT}).
 *
 * <p>Starts a single, real PostgreSQL 18 database via Testcontainers and wires its runtime
 * connection coordinates into the Spring {@code Environment} so that every concrete
 * integration/parity test in the suite &mdash; repository persistence tests, {@code @SpringBootTest}
 * web/MockMvc controller tests, Spring Batch job tests, security/config authorization tests, and
 * domain persistence tests &mdash; runs against the <em>same</em> Flyway-migrated schema and seed
 * data used in production, with Hibernate {@code ddl-auto=validate}.</p>
 *
 * <p>The {@code test} profile (see {@code src/main/resources/application-test.yml}) keeps Hibernate
 * at {@code ddl-auto=validate} and lets Flyway apply the versioned migrations in order into the
 * container ({@code V0__spring_batch_metadata.sql} &rarr; {@code V1__schema.sql} &rarr;
 * {@code V2__reference_data.sql} &rarr; {@code V3__indexes.sql}); Hibernate then validates the JPA
 * entity mappings against that container-materialised schema. That profile deliberately declares no
 * {@code spring.datasource.*} block so that the dynamic, container-supplied coordinates take
 * precedence &mdash; there are no hardcoded datasource credentials anywhere (AAP &sect;0.7.1).</p>
 *
 * <p><strong>Docker is required</strong> on the machine running these tests: Testcontainers needs a
 * reachable Docker daemon to pull/start the {@code postgres:18-alpine} image. This is the expected,
 * documented local-validation constraint for the migration; when Docker is unavailable the
 * behavioural parity contracts are additionally covered in-process by the sibling {@code *Test}
 * unit tests.</p>
 *
 * <p><strong>Connection mechanism.</strong> Spring Boot's {@code @ServiceConnection} is the
 * preferred way to bind a Testcontainers database to the context, but it requires the
 * {@code spring-boot-testcontainers} module, which is not on this project's test classpath.
 * This base therefore uses the sanctioned alternative &mdash; a single
 * {@link DynamicPropertySource @DynamicPropertySource} method that maps
 * {@code spring.datasource.url/username/password} to the container's runtime-generated values via
 * method references ({@link PostgreSQLContainer#getJdbcUrl()},
 * {@link PostgreSQLContainer#getUsername()}, {@link PostgreSQLContainer#getPassword()}). Exactly one
 * connection mechanism is active; the property values are supplied dynamically by Testcontainers and
 * are never literals.</p>
 *
 * <p><strong>Collation (AAP &sect;0.6.6).</strong> Legacy EBCDIC bytewise ordering parity is
 * guaranteed by the per-column {@code COLLATE "C"} declarations in {@code V1__schema.sql}, so the
 * container's default {@code initdb} locale is irrelevant and no locale arguments are configured
 * here.</p>
 *
 * <p><strong>Design contract.</strong> This class is intentionally minimal: it only starts the
 * shared container and activates the {@code test} profile. It declares no {@code @Test} methods (it
 * is an abstract base) and no {@code @Transactional} boundary (batch and controller tests must not
 * be wrapped in a rollback transaction). Concrete subclasses live in other packages
 * ({@code com.aws.carddemo.repository}, {@code .web.controller}, {@code .batch}, {@code .config},
 * {@code .security}, {@code .domain}) and add their own slice annotations as needed &mdash; for
 * example {@code @AutoConfigureMockMvc} on web-controller tests or {@code @SpringBatchTest} on batch
 * tests. The {@code POSTGRES} field is {@code static} so the container is started once and reused
 * across the whole {@code *IT} suite, keeping runs fast and the Spring context cached.</p>
 *
 * <p><strong>Origin:</strong> net-new test infrastructure with no COBOL ancestor; it supports the
 * Testcontainers PostgreSQL integration suite mandated by the AWS CardDemo migration
 * (COBOL/CICS/VSAM/JCL/BMS &rarr; Java 25 + Spring Boot), whose legacy sources are retained
 * read-only under {@code legacy/**}.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
public abstract class AbstractPostgresIntegrationTest {

    /**
     * The single, shared PostgreSQL 18 test database. Declared {@code static} so that
     * {@code @Testcontainers} starts it once and reuses it across the whole {@code *IT} suite
     * (one cached Spring context). The image tag {@code postgres:18-alpine} matches the production
     * PostgreSQL 18.x target.
     */
    @Container
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18-alpine");

    /**
     * Binds the running container's JDBC coordinates onto the Spring datasource properties.
     *
     * <p>Used instead of {@code @ServiceConnection} because the {@code spring-boot-testcontainers}
     * module is not on the test classpath. The values are resolved lazily from the started container
     * (they are method references, not literals), so no datasource credentials are hardcoded.</p>
     *
     * @param registry the dynamic property registry supplied by the Spring TestContext framework
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
