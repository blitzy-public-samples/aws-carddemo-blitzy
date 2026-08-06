package com.carddemo.fraud.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.events.TransactionAuthorized;
import com.carddemo.fraud.entity.VelocityWindowEntity;
import com.carddemo.fraud.repository.VelocityWindowRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proves simultaneous updates of one velocity bucket keep both authorizations and one row.
 * ADDITIVE IN FULL: net new; no COBOL ancestor.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.kafka.listener.auto-startup=false",
        "KAFKA_SASL_PASSWORD=not-a-real-broker-password",
        "ADMIN_PASSWORD_HASH={noop}not-a-real-admin-password",
        "USER_PASSWORD_HASH={noop}not-a-real-user-password",
        "MONITORING_PASSWORD_HASH={noop}not-a-real-monitoring-password"
})
@DisplayName("Concurrent velocity-window updates")
class VelocityWindowConcurrencyIT {

    private static final String DATABASE = "carddemo";
    private static final String SERVICE_SCHEMA = "fraud_service";
    private static final String ACCOUNT_ID = "00000000007";
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-04T12:15:00Z");
    private static final BigDecimal AMOUNT = new BigDecimal("10.00");

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18.4")
                    .withDatabaseName(DATABASE)
                    .withUsername(DATABASE)
                    .withPassword(DATABASE);

    static {
        POSTGRES.start();
    }

    private final RiskScoringService scorer;
    private final VelocityWindowRepository windows;
    private final PlatformTransactionManager transactionManager;
    private final JdbcTemplate jdbc;

    VelocityWindowConcurrencyIT(ApplicationContext context) {
        this.scorer = context.getBean(RiskScoringService.class);
        this.windows = context.getBean(VelocityWindowRepository.class);
        this.transactionManager = context.getBean(PlatformTransactionManager.class);
        this.jdbc = context.getBean(JdbcTemplate.class);
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", VelocityWindowConcurrencyIT::jdbcUrlOnServiceSchema);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static String jdbcUrlOnServiceSchema() {
        String url = POSTGRES.getJdbcUrl();
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SERVICE_SCHEMA;
    }

    @AfterEach
    void removeWindows() {
        jdbc.update("DELETE FROM " + SERVICE_SCHEMA + ".velocity_window");
    }

    @Test
    @DisplayName("two transactions updating one absent bucket leave one row with both increments")
    void simultaneousUpdatesKeepBothIncrementsAndOneWindow() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(
                    () -> scoreInsideTransaction(authorized("VELOCITY-CASE001", 1), ready, start));
            Future<?> second = executor.submit(
                    () -> scoreInsideTransaction(authorized("VELOCITY-CASE002", 2), ready, start));

            assertTrue(ready.await(10, TimeUnit.SECONDS),
                    "both transactions did not reach the gate");
            start.countDown();
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        }

        Instant bucketStart = OCCURRED_AT.truncatedTo(ChronoUnit.HOURS);
        VelocityWindowEntity stored = windows
                .findById(new VelocityWindowEntity.VelocityWindowId(ACCOUNT_ID, bucketStart))
                .orElseThrow();
        Integer rowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + SERVICE_SCHEMA
                        + ".velocity_window WHERE account_id = ?",
                Integer.class, ACCOUNT_ID);

        assertEquals(1, rowCount, "the bucket was duplicated");
        assertEquals(2, stored.getAuthorizationCount(), "one authorization increment was lost");
        assertEquals(0, new BigDecimal("20.00").compareTo(stored.getTotalAmount()),
                "one amount increment was lost");
    }

    @Test
    @DisplayName("one bucket accumulates two maximum-magnitude amounts without refusing the second")
    void oneBucketAccumulatesTwoMaximumMagnitudeAmounts() {
        BigDecimal maximum = new BigDecimal("999999999.99");
        TransactionTemplate boundary = new TransactionTemplate(transactionManager);

        boundary.executeWithoutResult(status ->
                scorer.assess(authorized("MAXMAGNITUDE0001", 11L, maximum)));
        boundary.executeWithoutResult(status ->
                scorer.assess(authorized("MAXMAGNITUDE0002", 12L, maximum.negate())));

        Instant bucketStart = OCCURRED_AT.truncatedTo(VelocityWindowEntity.WINDOW_BUCKET);
        VelocityWindowEntity stored = windows
                .findById(new VelocityWindowEntity.VelocityWindowId(ACCOUNT_ID, bucketStart))
                .orElseThrow();

        assertEquals(2, stored.getAuthorizationCount(), "one authorization increment was lost");
        assertEquals(0, new BigDecimal("1999999999.98").compareTo(stored.getTotalAmount()),
                "the accumulated magnitude of two maximum amounts");
        assertEquals(VelocityWindowEntity.TOTAL_AMOUNT_PRECISION,
                jdbc.queryForObject("SELECT numeric_precision FROM information_schema.columns"
                        + " WHERE table_schema = ? AND table_name = 'velocity_window'"
                        + " AND column_name = 'total_amount'", Integer.class, SERVICE_SCHEMA),
                "the migrated column width and the mapped width disagree");
    }

    private void scoreInsideTransaction(TransactionAuthorized event, CountDownLatch ready,
            CountDownLatch start) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            ready.countDown();
            await(start);
            scorer.assess(event);
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the concurrent update gate did not open");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("the concurrent update was interrupted", interrupted);
        }
    }

    private static TransactionAuthorized authorized(String transactionId, long eventNumber) {
        return authorized(transactionId, eventNumber, AMOUNT);
    }

    private static TransactionAuthorized authorized(String transactionId, long eventNumber,
            BigDecimal amount) {
        return new TransactionAuthorized(
                new UUID(0L, eventNumber),
                TransactionAuthorized.EVENT_TYPE,
                TransactionAuthorized.CARD_TOKEN_SCHEMA_VERSION,
                OCCURRED_AT,
                ACCOUNT_ID,
                transactionId,
                "01",
                "0003",
                "POS TERM",
                "Velocity concurrency",
                amount,
                "800000000",
                "Demo Merchant",
                "Demo City",
                "72112",
                "************0001",
                "2bf90b0da1627234a5d993f0fcaab0f2a3640f8a033bf69969de2fb60b83fa8d",
                "2026-08-04 12:15:00.000000",
                ACCOUNT_ID,
                TransactionAuthorized.CURRENCY);
    }
}