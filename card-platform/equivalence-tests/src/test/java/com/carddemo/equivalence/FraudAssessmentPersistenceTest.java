package com.carddemo.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.carddemo.events.FraudFlagged;
import com.carddemo.fraud.entity.FraudAssessmentEntity;
import jakarta.persistence.EntityManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Checks that a fraud assessment written through the entity satisfies the constraints its own
 * migration declares, and that those constraints still hold against something that bypasses the
 * entity.
 *
 * <p>Two layers guard this row and they have to agree. The entity refuses an out-of-range score, an
 * unknown rule and a repeated rule. The migration repeats the score and rule-shape checks. The
 * threshold-based verdict is deliberately independent of the rule list: one rule may contribute
 * points without the total reaching the configured threshold.
 *
 * <p>Three tests insert through Hibernate. Three more insert through native statements that skip
 * the entity, which is how a repair script or a hand-written migration would reach the table. Each
 * of those asserts that the database refuses the malformed shape on its own.
 *
 * <p>No COBOL ancestor: no COBOL (Common Business Oriented Language) program scores risk, so
 * nothing here cites a source paragraph. The bounds and the rule identifiers come from {@link
 * FraudFlagged}, which the schema document {@code fraud-flagged-v1.json} enumerates.
 */
@Testcontainers
class FraudAssessmentPersistenceTest {

    /** Image tag of the database container, matching the {@code postgres} service in compose. */
    private static final String POSTGRES_IMAGE = "postgres:18.4";

    /** One instance for this class. */
    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(POSTGRES_IMAGE)
                    .withDatabaseName("carddemo")
                    .withUsername("carddemo")
                    .withPassword("carddemo-fraud-persistence");

    /** The physical naming strategy Spring Boot 4.1.0 installs by default. */
    private static final String PHYSICAL_NAMING_STRATEGY =
            "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl";

    /** The implicit naming strategy Spring Boot 4.1.0 installs by default. */
    private static final String IMPLICIT_NAMING_STRATEGY =
            "org.springframework.boot.hibernate.SpringImplicitNamingStrategy";

    /** Schema the fraud service migrates into, from its {@code application.yml}. */
    private static final String SCHEMA = "fraud_service";

    /**
     * The assessment table, qualified by its schema.
     *
     * <p>{@code hibernate.default_schema} qualifies a mapped entity and leaves native statements
     * alone, so every native statement below names the schema itself. An unqualified name resolves
     * against the connection search path, which does not hold this schema.
     */
    private static final String QUALIFIED_TABLE = SCHEMA + ".fraud_assessment";

    /** A fixed moment, so no test depends on the clock. */
    private static final Instant ASSESSED_AT = Instant.parse("2026-01-02T03:04:05.060Z");

    /** Every rule the published contract permits, in the order the schema enumerates them. */
    private static final List<String> ALL_RULES = List.of(
            FraudFlagged.VELOCITY_RULE,
            FraudFlagged.AMOUNT_ANOMALY_RULE,
            FraudFlagged.MERCHANT_CATEGORY_RULE);

    private static SessionFactory factory;
    private static EntityManager entityManager;

    @BeforeAll
    static void migrateAndOpen() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:" + migrationDirectory("fraud-detection-service"))
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .createSchemas(true)
                .load()
                .migrate();

        Map<String, Object> settings = new HashMap<>();
        settings.put("hibernate.connection.driver_class", "org.postgresql.Driver");
        settings.put("hibernate.connection.url", POSTGRES.getJdbcUrl());
        settings.put("hibernate.connection.username", POSTGRES.getUsername());
        settings.put("hibernate.connection.password", POSTGRES.getPassword());
        settings.put("hibernate.default_schema", SCHEMA);
        settings.put("hibernate.hbm2ddl.auto", "validate");
        settings.put("hibernate.physical_naming_strategy", PHYSICAL_NAMING_STRATEGY);
        settings.put("hibernate.implicit_naming_strategy", IMPLICIT_NAMING_STRATEGY);

        MetadataSources sources = new MetadataSources(
                new StandardServiceRegistryBuilder().applySettings(settings).build());
        sources.addAnnotatedClass(FraudAssessmentEntity.class);
        factory = sources.buildMetadata().buildSessionFactory();
        assertNotNull(factory, "the fraud service built no session factory");
        entityManager = factory.createEntityManager();
    }

    @AfterAll
    static void closeResources() {
        if (entityManager != null && entityManager.isOpen()) {
            entityManager.close();
        }
        if (factory != null) {
            factory.close();
        }
    }

    @Test
    @DisplayName("A flagged assessment naming every rule persists and reads back unchanged")
    void flaggedAssessmentPersists() {
        String transactionId = "PERSISTFLAGGED01";
        persist(new FraudAssessmentEntity(transactionId, "00000000001", 87, true, ALL_RULES,
                ASSESSED_AT));
        entityManager.clear();

        FraudAssessmentEntity read = entityManager.find(FraudAssessmentEntity.class, transactionId);
        assertNotNull(read, "the row Hibernate wrote is not readable");
        assertEquals(87, read.getRiskScore(), "the score did not survive");
        assertTrue(read.isFlagged(), "the verdict did not survive");
        assertEquals(ALL_RULES, read.getTriggeredRules(),
                "the rule list did not survive the converter round trip through the database");
    }

    @Test
    @DisplayName("A cleared assessment persists as the empty JSON array")
    void clearedAssessmentPersists() {
        String transactionId = "PERSISTCLEARED01";
        persist(new FraudAssessmentEntity(transactionId, "00000000002", 0, false, List.of(),
                ASSESSED_AT));
        entityManager.clear();

        FraudAssessmentEntity read = entityManager.find(FraudAssessmentEntity.class, transactionId);
        assertNotNull(read, "the row Hibernate wrote is not readable");
        assertFalse(read.isFlagged(), "a cleared assessment is not flagged");
        assertTrue(read.getTriggeredRules().isEmpty(), "a cleared assessment names no rule");
        assertEquals("[]", storedRuleText(transactionId),
                "a cleared assessment stores the empty JSON array");
    }

    @Test
    @DisplayName("The converter stores the JSON array shape the CHECK constraint requires")
    void converterOutputSatisfiesTheCheckConstraint() {
        String transactionId = "PERSISTSHAPE0001";
        persist(new FraudAssessmentEntity(transactionId, "00000000003", 55, true,
                List.of(FraudFlagged.VELOCITY_RULE, FraudFlagged.MERCHANT_CATEGORY_RULE),
                ASSESSED_AT));

        assertEquals("[\"VELOCITY\",\"MERCHANT_CATEGORY\"]", storedRuleText(transactionId),
                "the stored form is a JSON array, which is what ck_fraud_assessment_triggered_rules"
                        + " accepts and what a comma-joined value would fail");
    }

    @Test
    @DisplayName("The database refuses a comma-joined rule list written past the entity")
    void databaseRefusesCommaJoinedRuleList() {
        assertRefused("PERSISTBADFORM01", "00000000004", 10, true, "VELOCITY,AMOUNT_ANOMALY",
                "ck_fraud_assessment_triggered_rules");
    }

    @Test
    @DisplayName("The database refuses a score outside the published bounds written past the entity")
    void databaseRefusesOutOfRangeScore() {
        assertRefused("PERSISTBADSCORE1", "00000000005", 101, false, "[]",
                "ck_fraud_assessment_risk_score");
    }

    @Test
    @DisplayName("A cleared verdict may retain the rule that contributed below threshold")
    void clearedVerdictRetainsContributingRule() {
        String transactionId = "PERSISTBELOWTHR1";
        persist(new FraudAssessmentEntity(transactionId, "00000000006", 30, false,
                List.of(FraudFlagged.AMOUNT_ANOMALY_RULE), ASSESSED_AT));
        entityManager.clear();

        FraudAssessmentEntity read = entityManager.find(FraudAssessmentEntity.class, transactionId);
        assertNotNull(read, "the threshold-based row was not persisted");
        assertFalse(read.isFlagged(), "the verdict changed while persisting");
        assertEquals(List.of(FraudFlagged.AMOUNT_ANOMALY_RULE), read.getTriggeredRules(),
                "the contributing rule was lost");
    }

    @Test
    @DisplayName("The database refuses an unknown rule written past the entity")
    void databaseRefusesUnknownRule() {
        assertRefused("PERSISTBADRULE01", "00000000007", 10, true, "[\"NOT_A_RULE\"]",
                "ck_fraud_assessment_triggered_rules");
    }

    /**
     * Inserts one row with a native statement, and asserts the database refuses it.
     *
     * <p>A native statement skips the entity, which is how a repair script or a hand-written
     * migration would reach this table. The assertion names the constraint that has to refuse it, so
     * a row refused for some other reason fails the test.
     *
     * @param transactionId  the key to insert
     * @param accountId      the account to insert
     * @param riskScore      the score to insert
     * @param flagged        the verdict to insert
     * @param triggeredRules the stored rule text to insert
     * @param constraint     the constraint expected to refuse the row
     */
    private static void assertRefused(String transactionId, String accountId, int riskScore,
            boolean flagged, String triggeredRules, String constraint) {
        RuntimeException refused = assertThrows(RuntimeException.class, () -> {
            entityManager.getTransaction().begin();
            try {
                entityManager.createNativeQuery("INSERT INTO " + QUALIFIED_TABLE + " (transaction_id,"
                                + " account_id, risk_score, flagged, triggered_rules, assessed_at)"
                                + " VALUES (:id, :account, :score, :flagged, :rules, :at)")
                        .setParameter("id", transactionId)
                        .setParameter("account", accountId)
                        .setParameter("score", riskScore)
                        .setParameter("flagged", flagged)
                        .setParameter("rules", triggeredRules)
                        .setParameter("at", ASSESSED_AT)
                        .executeUpdate();
                entityManager.getTransaction().commit();
            } finally {
                if (entityManager.getTransaction().isActive()) {
                    entityManager.getTransaction().rollback();
                }
            }
        }, "the database accepted a row its constraints forbid");

        assertTrue(rootMessage(refused).contains(constraint),
                "the row was refused by something other than " + constraint + ": "
                        + rootMessage(refused));
    }

    /**
     * Persists one assessment in its own transaction.
     *
     * @param assessment the row to persist
     */
    private static void persist(FraudAssessmentEntity assessment) {
        entityManager.getTransaction().begin();
        try {
            entityManager.persist(assessment);
            entityManager.getTransaction().commit();
        } catch (RuntimeException failure) {
            if (entityManager.getTransaction().isActive()) {
                entityManager.getTransaction().rollback();
            }
            throw failure;
        }
    }

    /**
     * Reads the stored text of {@code triggered_rules} for one row, past the converter.
     *
     * @param transactionId the key to read
     * @return the column value exactly as the database holds it
     */
    private static String storedRuleText(String transactionId) {
        Object stored = entityManager
                .createNativeQuery("SELECT triggered_rules FROM " + QUALIFIED_TABLE
                        + " WHERE transaction_id = :id")
                .setParameter("id", transactionId)
                .getSingleResult();
        if (stored == null) {
            return fail("no row holds transaction " + transactionId);
        }
        return stored.toString();
    }

    /**
     * Returns the message of the deepest cause of a failure.
     *
     * @param failure the failure to unwrap
     * @return the deepest message, or the failure's own description when none carries one
     */
    private static String rootMessage(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.toString() : root.getMessage();
    }

    /**
     * Locates one service's migration directory by walking up from the working directory.
     *
     * <p>Reading from the filesystem is necessary because all six services place their migrations at
     * the same classpath location, {@code db/migration}, where only the first would be visible.
     *
     * @param module directory name of the service
     * @return the migration directory
     * @throws IllegalStateException when no ancestor of the working directory holds the directory
     */
    private static Path migrationDirectory(String module) {
        Path relative = Path.of("card-platform", "services", module, "src", "main", "resources",
                "db", "migration");
        for (Path candidate = Path.of("").toAbsolutePath().normalize(); candidate != null;
                candidate = candidate.getParent()) {
            Path migrations = candidate.resolve(relative);
            if (Files.isDirectory(migrations)) {
                return migrations;
            }
        }
        throw new IllegalStateException(
                "no ancestor of '" + Path.of("").toAbsolutePath() + "' holds '" + relative + "'");
    }
}
