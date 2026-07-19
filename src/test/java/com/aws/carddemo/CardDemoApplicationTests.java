package com.aws.carddemo;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * Smoke test: verifies the full Spring application context loads under the
 * {@code test} profile against a real Testcontainers PostgreSQL, that the
 * Flyway migrations {@code V1__schema.sql}, {@code V2__reference_data.sql} and
 * {@code V3__indexes.sql} applied, and that Hibernate schema validation
 * ({@code ddl-auto=validate}) succeeded during startup.
 *
 * <p>Inherits {@code @SpringBootTest}, {@code @ActiveProfiles("test")} and the
 * shared PostgreSQL container from {@link AbstractPostgresIntegrationTest}.
 *
 * <p>Origin: net-new (no COBOL ancestor). This is the sole test covering
 * {@code CardDemoApplication}, which is excluded from the JaCoCo coverage numerator.
 */
class CardDemoApplicationTests extends AbstractPostgresIntegrationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private Flyway flyway;

    @Test
    void contextLoads() {
        assertThat(applicationContext).isNotNull();
    }

    @Test
    void flywayMigrationsApplied() {
        // V1 schema + V2 reference data + V3 indexes must all be applied.
        long appliedCount = java.util.Arrays.stream(flyway.info().applied())
                .filter(m -> m.getVersion() != null)
                .count();
        assertThat(appliedCount).isGreaterThanOrEqualTo(3);
    }
}
