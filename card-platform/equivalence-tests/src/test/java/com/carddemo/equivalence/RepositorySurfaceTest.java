package com.carddemo.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.carddemo.account.entity.DisclosureGroupEntity;
import com.carddemo.account.repository.DisclosureGroupRepository;
import com.carddemo.ledger.entity.AccountBalanceProjectionEntity;
import com.carddemo.ledger.entity.RejectedTransactionEntity;
import com.carddemo.ledger.entity.TransactionCategoryBalanceEntity;
import com.carddemo.ledger.entity.TransactionEntity;
import com.carddemo.ledger.repository.AccountBalanceProjectionRepository;
import com.carddemo.ledger.repository.RejectedTransactionRepository;
import com.carddemo.ledger.repository.TransactionCategoryBalanceRepository;
import com.carddemo.ledger.repository.TransactionRepository;
import jakarta.persistence.EntityManager;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.data.repository.Repository;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Checks that every repository interface offers exactly the operations its aggregate supports, and
 * that Spring Data can still build each one.
 *
 * <p>An interface extending {@code ListCrudRepository} inherits {@code save}, {@code delete},
 * {@code deleteById} and {@code deleteAll} whether or not the aggregate behind it supports them.
 * Reference data seeded by a migration then carries a writable surface, an append-only table carries
 * a delete, and nothing in the type system says otherwise. Extending the bare {@link Repository}
 * marker and declaring each supported operation states the surface where a caller reads it.
 *
 * <p>Narrowing carries one risk this class exists to remove. Spring Data resolves a declared method
 * against its base implementation by signature, so a method that does not match is not a compile
 * error. It is a failure raised while the repository is being built, which without a test appears
 * first when a service starts. {@code getRepository} here does that build against a real
 * PostgreSQL instance carrying the real migrations.
 *
 * <p>The class also proves the seeded state the ledger depends on.
 * {@code account_balance_projection} declares every business column {@code NOT NULL}, and
 * {@code 2800-UPDATE-ACCOUNT-REC} at {@code app/cbl/CBTRN02C.cbl:L545-L560} adds an amount to a
 * record it has already read. A missing row therefore has no balance to add to, so the seed has to
 * cover every fixture account.
 */
@Testcontainers
class RepositorySurfaceTest {

    /** Image tag of the database container, matching the {@code postgres} service in compose. */
    private static final String POSTGRES_IMAGE = "postgres:18.4";

    /** One instance for both services under test. */
    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(POSTGRES_IMAGE)
                    .withDatabaseName("carddemo")
                    .withUsername("carddemo")
                    .withPassword("carddemo-repository-surface");

    /** The physical naming strategy Spring Boot 4.1.0 installs by default. */
    private static final String PHYSICAL_NAMING_STRATEGY =
            "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl";

    /** The implicit naming strategy Spring Boot 4.1.0 installs by default. */
    private static final String IMPLICIT_NAMING_STRATEGY =
            "org.springframework.boot.hibernate.SpringImplicitNamingStrategy";

    /** Accounts in {@code app/data/ASCII/acctdata.txt}, and so rows the projection needs. */
    private static final int FIXTURE_ACCOUNT_COUNT = 50;

    /** Rows in {@code app/data/ASCII/discgrp.txt}. */
    private static final int FIXTURE_DISCLOSURE_GROUP_COUNT = 51;

    /** Rows in {@code app/data/ASCII/tcatbal.txt}. */
    private static final int FIXTURE_CATEGORY_BALANCE_COUNT = 50;

    private static SessionFactory ledgerFactory;
    private static SessionFactory accountFactory;
    private static EntityManager ledgerEntityManager;
    private static EntityManager accountEntityManager;

    private static AccountBalanceProjectionRepository projections;
    private static TransactionRepository transactions;
    private static RejectedTransactionRepository rejects;
    private static TransactionCategoryBalanceRepository categoryBalances;
    private static DisclosureGroupRepository disclosureGroups;

    @BeforeAll
    static void migrateAndBuildRepositories() {
        ledgerFactory = build("ledger-posting-service", "ledger_service",
                TransactionEntity.class,
                TransactionCategoryBalanceEntity.class,
                AccountBalanceProjectionEntity.class,
                RejectedTransactionEntity.class);
        accountFactory = build("account-service", "account_service",
                DisclosureGroupEntity.class);

        ledgerEntityManager = ledgerFactory.createEntityManager();
        accountEntityManager = accountFactory.createEntityManager();

        JpaRepositoryFactory ledger = new JpaRepositoryFactory(ledgerEntityManager);
        JpaRepositoryFactory account = new JpaRepositoryFactory(accountEntityManager);

        projections = ledger.getRepository(AccountBalanceProjectionRepository.class);
        transactions = ledger.getRepository(TransactionRepository.class);
        rejects = ledger.getRepository(RejectedTransactionRepository.class);
        categoryBalances = ledger.getRepository(TransactionCategoryBalanceRepository.class);
        disclosureGroups = account.getRepository(DisclosureGroupRepository.class);
    }

    @AfterAll
    static void closeResources() {
        close(ledgerEntityManager);
        close(accountEntityManager);
        if (ledgerFactory != null) {
            ledgerFactory.close();
        }
        if (accountFactory != null) {
            accountFactory.close();
        }
    }

    /**
     * Returns the narrowed repository interfaces and the operations each one has to offer.
     *
     * @return one entry per repository
     */
    private static List<Surface> surfaces() {
        return List.of(
                new Surface(AccountBalanceProjectionRepository.class,
                        Set.of("findById", "findForUpdateById", "save", "count",
                                "applyStateChange")),
                new Surface(TransactionRepository.class,
                        Set.of("save", "findById", "existsById", "count")),
                new Surface(RejectedTransactionRepository.class,
                        Set.of("save", "count")),
                new Surface(TransactionCategoryBalanceRepository.class,
                        Set.of("findById", "save", "addToCategoryBalance", "count")),
                new Surface(DisclosureGroupRepository.class,
                        Set.of("findById", "findAll")));
    }

    /**
     * One repository interface and the method names it declares.
     *
     * @param repository the interface
     * @param operations the method names it has to offer, and only those
     */
    private record Surface(Class<?> repository, Set<String> operations) {

        @Override
        public String toString() {
            return repository.getSimpleName();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("surfaces")
    @DisplayName("Each repository offers exactly the operations its aggregate supports")
    void repositoryOffersExactlyItsSupportedOperations(Surface surface) {
        Set<String> offered = Arrays.stream(surface.repository().getMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());
        assertEquals(surface.operations(), offered,
                surface + " offers a different set of operations than its aggregate supports");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("surfaces")
    @DisplayName("No business aggregate exposes a delete; no source paragraph removes such a row")
    void noRepositoryExposesDelete(Surface surface) {
        List<String> deletes = Arrays.stream(surface.repository().getMethods())
                .map(Method::getName)
                .filter(name -> name.startsWith("delete") || name.startsWith("remove"))
                .toList();
        assertTrue(deletes.isEmpty(), surface + " exposes " + deletes
                + ", and no paragraph in app/cbl/ deletes a row of this aggregate. A bounded "
                + "retention delete belongs on the additive infrastructure tables only: "
                + "outbox_event, processed_event, velocity_window, statement_transaction, "
                + "notification_log and authorization_decision");
    }

    @Test
    @DisplayName("Reference data exposes no write, because only a migration writes it")
    void referenceDataExposesNoWrite() {
        Set<String> offered = Arrays.stream(DisclosureGroupRepository.class.getMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());
        assertFalse(offered.contains("save"),
                "DisclosureGroupRepository exposes save, yet OPEN INPUT DISCGRP-FILE at "
                        + "app/cbl/CBACT04C.cbl:L272 is the only access the source makes");
        assertFalse(offered.contains("saveAll"), "DisclosureGroupRepository exposes saveAll");
    }

    @Test
    @DisplayName("Every fixture account carries a seeded balance projection row")
    void everyFixtureAccountCarriesAProjectionRow() {
        assertEquals(FIXTURE_ACCOUNT_COUNT, projections.count(),
                "account_balance_projection has to hold one row per account in "
                        + "app/data/ASCII/acctdata.txt");
        for (int account = 1; account <= FIXTURE_ACCOUNT_COUNT; account++) {
            String accountId = String.format("%011d", account);
            assertTrue(projections.findById(accountId).isPresent(),
                    "account " + accountId + " carries no projection row, so 2800-UPDATE-ACCOUNT-REC"
                            + " at app/cbl/CBTRN02C.cbl:L545-L560 would have no balance to add to");
        }
    }

    @Test
    @DisplayName("The seeded projection carries the balances of the account fixture")
    void seededProjectionCarriesFixtureBalances() {
        AccountBalanceProjectionEntity first = projections.findById("00000000001")
                .orElseGet(() -> fail("account 00000000001 carries no projection row"));
        assertEquals(0, new BigDecimal("194.00").compareTo(first.getCurrentBalance()),
                "ACCT-CURR-BAL of account 1 in app/data/ASCII/acctdata.txt is 194.00");
        assertEquals(0, BigDecimal.ZERO.compareTo(first.getCycleCredit()),
                "ACCT-CURR-CYC-CREDIT of account 1 is zero");
        assertEquals(0, BigDecimal.ZERO.compareTo(first.getCycleDebit()),
                "ACCT-CURR-CYC-DEBIT of account 1 is zero");
    }

    @Test
    @DisplayName("Saving a projection row updates the account it already holds")
    void projectionSaveUpdatesAnExistingAccount() {
        AccountBalanceProjectionEntity row = projections.findById("00000000002")
                .orElseGet(() -> fail("account 00000000002 carries no projection row"));
        BigDecimal raised = row.getCurrentBalance().add(new BigDecimal("10.00"));
        AccountBalanceProjectionEntity updated = new AccountBalanceProjectionEntity(
                row.getAccountId(), raised, row.getCycleCredit(), row.getCycleDebit());

        inLedgerTransaction(() -> projections.save(updated));
        ledgerEntityManager.clear();

        assertEquals(0, raised.compareTo(projections.findById("00000000002")
                        .orElseGet(() -> fail("the row vanished")).getCurrentBalance()),
                "the saved balance did not survive a reload");
        assertEquals(FIXTURE_ACCOUNT_COUNT, projections.count(),
                "saving an account that already has a row added a second one");
    }

    @Test
    @DisplayName("The append-only transaction repository inserts and finds one row")
    void transactionRepositoryInsertsAndFinds() {
        long before = transactions.count();
        String transactionId = "SURFACEPROBE0001";
        TransactionEntity posted = new TransactionEntity(
                transactionId, "01", "0001", "SURF", "repository surface probe",
                new BigDecimal("12.34"), "000000001", "probe merchant", "probe city", "00000",
                "************1234", "2024-01-01 00:00:00.000000",
                "2024-01-02 00:00:00.000000");
        inLedgerTransaction(() -> transactions.save(posted));
        assertEquals(before + 1, transactions.count(), "the insert did not add a row");
        assertTrue(transactions.existsById(transactionId),
                "existsById did not see the row just inserted");
        Optional<TransactionEntity> found = transactions.findById(transactionId);
        assertTrue(found.isPresent(), "findById did not see the row just inserted");
        assertEquals(0, new BigDecimal("12.34").compareTo(found.get().getAmount()),
                "the stored amount lost its value");
    }

    @Test
    @DisplayName("The insert-only reject repository inserts one row")
    void rejectRepositoryInserts() {
        long before = rejects.count();
        RejectedTransactionEntity rejected = new RejectedTransactionEntity(
                UUID.randomUUID(), "SURFACEREJECT001", "0102", "OVERLIMIT TRANSACTION",
                "4859452612877065", new java.math.BigDecimal("1250.75"), "01", "5411", "123456789",
                "2024-01-03 00:00:00.000000", Instant.parse("2024-01-03T00:00:00Z"));
        inLedgerTransaction(() -> rejects.save(rejected));
        assertEquals(before + 1, rejects.count(), "the insert did not add a row");
    }

    @Test
    @DisplayName("The category balance repository reads a seeded composite key")
    void categoryBalanceRepositoryReadsSeededKey() {
        assertEquals(FIXTURE_CATEGORY_BALANCE_COUNT, categoryBalances.count(),
                "transaction_category_balance has to hold the rows of "
                        + "app/data/ASCII/tcatbal.txt");
        assertTrue(categoryBalances.findById(
                        new TransactionCategoryBalanceEntity.TransactionCategoryBalanceId(
                                "00000000001", "01", "0001")).isPresent(),
                "the first seeded category balance key is not readable");
    }

    @Test
    @DisplayName("The reference data repository reads every seeded disclosure group")
    void disclosureGroupRepositoryReadsEverySeededRow() {
        assertEquals(FIXTURE_DISCLOSURE_GROUP_COUNT, disclosureGroups.findAll().size(),
                "disclosure_group has to hold the rows of app/data/ASCII/discgrp.txt");
    }

    /**
     * Runs one unit of work against the ledger entity manager.
     *
     * @param work the work to run inside the transaction
     */
    private static void inLedgerTransaction(Runnable work) {
        ledgerEntityManager.getTransaction().begin();
        try {
            work.run();
            ledgerEntityManager.getTransaction().commit();
        } catch (RuntimeException failure) {
            if (ledgerEntityManager.getTransaction().isActive()) {
                ledgerEntityManager.getTransaction().rollback();
            }
            throw failure;
        }
    }

    /**
     * Applies one service's migrations, then builds a session factory over its entities.
     *
     * @param module   directory name of the service
     * @param schema   schema the service migrates into
     * @param entities the entity classes to map
     * @return the session factory
     */
    private static SessionFactory build(String module, String schema, Class<?>... entities) {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:" + migrationDirectory(module))
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(true)
                .load()
                .migrate();

        Map<String, Object> settings = new HashMap<>();
        settings.put("hibernate.connection.driver_class", "org.postgresql.Driver");
        settings.put("hibernate.connection.url", POSTGRES.getJdbcUrl());
        settings.put("hibernate.connection.username", POSTGRES.getUsername());
        settings.put("hibernate.connection.password", POSTGRES.getPassword());
        settings.put("hibernate.default_schema", schema);
        settings.put("hibernate.hbm2ddl.auto", "validate");
        settings.put("hibernate.physical_naming_strategy", PHYSICAL_NAMING_STRATEGY);
        settings.put("hibernate.implicit_naming_strategy", IMPLICIT_NAMING_STRATEGY);

        MetadataSources sources = new MetadataSources(
                new StandardServiceRegistryBuilder().applySettings(settings).build());
        Arrays.stream(entities).forEach(sources::addAnnotatedClass);
        SessionFactory factory = sources.buildMetadata().buildSessionFactory();
        assertNotNull(factory, module + " built no session factory");
        return factory;
    }

    /**
     * Closes one entity manager when it was opened.
     *
     * @param entityManager the entity manager, which may be {@code null}
     */
    private static void close(EntityManager entityManager) {
        if (entityManager != null && entityManager.isOpen()) {
            entityManager.close();
        }
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
