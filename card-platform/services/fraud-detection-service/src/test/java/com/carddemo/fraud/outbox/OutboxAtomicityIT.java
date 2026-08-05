package com.carddemo.fraud.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.events.EventEnvelope;
import com.carddemo.events.FraudCleared;
import com.carddemo.fraud.entity.FraudAssessmentEntity;
import com.carddemo.fraud.repository.FraudAssessmentRepository;
import com.carddemo.fraud.repository.OutboxEventRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proves the assessment row and its outbox row commit or roll back together in PostgreSQL.
 * ADDITIVE IN FULL: net new; no COBOL ancestor.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.kafka.listener.auto-startup=false",
        "carddemo.outbox.relay.fixed-delay-ms=3600000",
        "KAFKA_SASL_PASSWORD=not-a-real-broker-password",
        "ADMIN_PASSWORD_HASH={noop}not-a-real-admin-password",
        "USER_PASSWORD_HASH={noop}not-a-real-user-password",
        "MONITORING_PASSWORD_HASH={noop}not-a-real-monitoring-password"
})
@DisplayName("Fraud assessment and outbox atomicity")
class OutboxAtomicityIT {

    private static final String DATABASE = "carddemo";
    private static final String SERVICE_SCHEMA = "fraud_service";
    private static final String TRANSACTION_ID = "ATOMICITY-CASE01";
    private static final String ACCOUNT_ID = "00000000007";
    private static final Instant ASSESSED_AT = Instant.parse("2026-08-04T13:00:00Z");
    private static final UUID EVENT_ID =
            UUID.fromString("e12b48da-73a4-4cc0-9a46-2c27d781a64e");

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18.4")
                    .withDatabaseName(DATABASE)
                    .withUsername(DATABASE)
                    .withPassword(DATABASE);

    static {
        POSTGRES.start();
    }

    private final OutboxWriter writer;
    private final FraudAssessmentRepository assessments;
    private final OutboxEventRepository outboxEvents;
    private final TransactionTemplate transactions;

    OutboxAtomicityIT(ApplicationContext context) {
        this.writer = context.getBean(OutboxWriter.class);
        this.assessments = context.getBean(FraudAssessmentRepository.class);
        this.outboxEvents = context.getBean(OutboxEventRepository.class);
        this.transactions = new TransactionTemplate(
                context.getBean(PlatformTransactionManager.class));
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", OutboxAtomicityIT::jdbcUrlOnServiceSchema);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static String jdbcUrlOnServiceSchema() {
        String url = POSTGRES.getJdbcUrl();
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SERVICE_SCHEMA;
    }

    @AfterEach
    void removeRows() {
        outboxEvents.deleteAll();
        assessments.deleteAll();
    }

    @Test
    @DisplayName("a caller rollback leaves neither the assessment nor the outbox row")
    void rollbackLeavesNeitherRow() {
        assertThrows(ExpectedRollback.class, () -> transactions.executeWithoutResult(status -> {
            assessments.save(assessment());
            writer.write(clearedEvent());
            throw new ExpectedRollback();
        }));

        assertFalse(assessments.existsById(TRANSACTION_ID), "the assessment survived the rollback");
        assertFalse(outboxEvents.existsById(EVENT_ID), "the outbox row survived the rollback");
    }

    @Test
    @DisplayName("one commit leaves one assessment and one unpublished outbox row")
    void commitLeavesBothRows() {
        transactions.executeWithoutResult(status -> {
            assessments.save(assessment());
            writer.write(clearedEvent());
        });

        assertTrue(assessments.existsById(TRANSACTION_ID), "the assessment did not commit");
        assertTrue(outboxEvents.existsById(EVENT_ID), "the outbox row did not commit");
        assertEquals(1L, assessments.count(), "the transaction wrote another assessment");
        assertEquals(1L, outboxEvents.count(), "the transaction wrote another outbox row");
        assertFalse(outboxEvents.findById(EVENT_ID).orElseThrow().isPublished(),
                "the writer marked the row published");
    }

    private static FraudAssessmentEntity assessment() {
        return new FraudAssessmentEntity(
                TRANSACTION_ID, ACCOUNT_ID, 0, false, List.of(), ASSESSED_AT);
    }

    private static FraudCleared clearedEvent() {
        return new FraudCleared(
                EVENT_ID,
                FraudCleared.EVENT_TYPE,
                EventEnvelope.SCHEMA_VERSION,
                ASSESSED_AT,
                ACCOUNT_ID,
                TRANSACTION_ID,
                ACCOUNT_ID,
                ASSESSED_AT);
    }

    private static final class ExpectedRollback extends RuntimeException {

        private static final long serialVersionUID = 1L;
    }
}