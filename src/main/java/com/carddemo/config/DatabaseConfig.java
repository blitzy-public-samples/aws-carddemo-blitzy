package com.carddemo.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.Database;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.Properties;

/**
 * Database Configuration for CardDemo Application
 * 
 * Configures PostgreSQL database connectivity with HikariCP connection pooling
 * to replace mainframe VSAM KSDS file access patterns. This configuration supports
 * 150+ concurrent users with optimized connection pooling and transaction management.
 * 
 * VSAM File Mappings to PostgreSQL Tables:
 * - CUSTDAT (KEYLEN=9, MAXLRECL=500) → customer table with indexed customer_id
 * - ACCTDAT (KEYLEN=11, MAXLRECL=300) → account table with indexed account_id
 * - CARDDAT (KEYLEN=16, MAXLRECL=150) → card table with indexed card_number
 * - TRANSACT (KEYLEN=16, MAXLRECL=350) → transaction table with indexed transaction_id
 * - USRSEC (KEYLEN=8, MAXLRECL=80) → user table with indexed user_id
 * 
 * All monetary fields use PostgreSQL NUMERIC type with explicit precision to
 * maintain COBOL COMP-3 decimal accuracy using Java BigDecimal.
 * 
 * Transaction management replicates CICS SYNCPOINT and ROLLBACK behavior through
 * Spring @Transactional annotations with JPA EntityManager.
 */
@Slf4j
@Configuration
@EnableJpaRepositories(basePackages = "com.carddemo.repository")
@EnableTransactionManagement
@RequiredArgsConstructor
public class DatabaseConfig {

    /**
     * PostgreSQL JDBC connection URL
     * Default: jdbc:postgresql://localhost:5432/carddemo
     */
    @Value("${spring.datasource.url:jdbc:postgresql://localhost:5432/carddemo}")
    private String jdbcUrl;

    /**
     * Database username from environment variable for security
     */
    @Value("${DB_USERNAME:carddemo}")
    private String dbUsername;

    /**
     * Database password from environment variable for security
     */
    @Value("${DB_PASSWORD:carddemo}")
    private String dbPassword;

    /**
     * Spring active profile for environment-specific configuration
     */
    @Value("${spring.profiles.active:prod}")
    private String activeProfile;

    /**
     * Minimum number of idle connections in the pool
     * Set to 10 to ensure quick response for incoming requests
     */
    @Value("${spring.datasource.hikari.minimum-idle:10}")
    private int minimumIdle;

    /**
     * Maximum number of connections in the pool
     * Set to 200 to support 150+ concurrent users plus batch job connections
     */
    @Value("${spring.datasource.hikari.maximum-pool-size:200}")
    private int maximumPoolSize;

    /**
     * Maximum time (in milliseconds) to wait for a connection from the pool
     * 30 seconds timeout aligns with CICS transaction timeout patterns
     */
    @Value("${spring.datasource.hikari.connection-timeout:30000}")
    private long connectionTimeout;

    /**
     * Maximum time (in milliseconds) a connection can remain idle in the pool
     * 10 minutes idle timeout prevents stale connections
     */
    @Value("${spring.datasource.hikari.idle-timeout:600000}")
    private long idleTimeout;

    /**
     * Maximum lifetime (in milliseconds) of a connection in the pool
     * 30 minutes max lifetime ensures connection freshness
     */
    @Value("${spring.datasource.hikari.max-lifetime:1800000}")
    private long maxLifetime;

    /**
     * Enable connection leak detection in development mode
     */
    @Value("${spring.datasource.hikari.leak-detection-threshold:0}")
    private long leakDetectionThreshold;

    /**
     * JDBC driver class name - configurable for test environments
     * Default: PostgreSQL driver for production
     * Test override: H2 driver via application-test.properties
     */
    @Value("${spring.datasource.driver-class-name:org.postgresql.Driver}")
    private String driverClassName;

    /**
     * Hibernate dialect - configurable for test environments
     * Default: PostgreSQL dialect for production
     * Test override: H2 dialect via application-test.properties
     */
    @Value("${spring.jpa.database-platform:org.hibernate.dialect.PostgreSQLDialect}")
    private String hibernateDialect;

    /**
     * Hibernate DDL auto mode - configurable for different environments
     * Default: validate (Flyway manages schema in production)
     * Test override: create-drop (automatically create/drop schema for tests)
     */
    @Value("${spring.jpa.hibernate.ddl-auto:validate}")
    private String ddlAuto;

    /**
     * Whether to generate DDL from entity annotations
     * Default: false (Flyway manages DDL in production)
     * Test override: true (generate schema from entities for in-memory H2)
     */
    @Value("${spring.jpa.generate-ddl:false}")
    private boolean generateDdl;

    /**
     * Configures HikariCP DataSource with PostgreSQL database connectivity
     * replacing VSAM KSDS file access patterns with relational database operations.
     * 
     * Pool Configuration:
     * - Minimum Idle: 10 connections always ready
     * - Maximum Pool Size: 200 connections for 150+ concurrent users
     * - Connection Timeout: 30 seconds matching CICS timeout patterns
     * - Idle Timeout: 10 minutes for connection reuse optimization
     * - Max Lifetime: 30 minutes ensuring connection freshness
     * 
     * Performance Optimizations:
     * - Prepared statement caching (250 statements per connection)
     * - Statement pooling enabled
     * - Connection validation with fast isValid() check
     * - Auto-commit disabled for explicit transaction control
     * - Read-only connections for query optimization where applicable
     * 
     * @return configured HikariDataSource with optimized connection pooling
     */
    @Bean
    public DataSource dataSource() {
        log.info("Configuring HikariCP DataSource for PostgreSQL database");
        log.info("JDBC URL: {}", jdbcUrl);
        log.info("Pool Configuration - Min Idle: {}, Max Pool Size: {}", minimumIdle, maximumPoolSize);

        HikariConfig hikariConfig = new HikariConfig();
        
        // Core JDBC connection settings
        hikariConfig.setJdbcUrl(jdbcUrl);
        hikariConfig.setUsername(dbUsername);
        hikariConfig.setPassword(dbPassword);
        hikariConfig.setDriverClassName(driverClassName);

        // Connection pool sizing for 150+ concurrent users
        hikariConfig.setMinimumIdle(minimumIdle);
        hikariConfig.setMaximumPoolSize(maximumPoolSize);

        // Timeout configuration matching CICS transaction patterns
        hikariConfig.setConnectionTimeout(connectionTimeout);
        hikariConfig.setIdleTimeout(idleTimeout);
        hikariConfig.setMaxLifetime(maxLifetime);

        // Connection validation and health checks
        hikariConfig.setConnectionTestQuery("SELECT 1");
        hikariConfig.setValidationTimeout(5000); // 5 seconds for validation

        // Pool name for monitoring and debugging
        hikariConfig.setPoolName("CardDemo-HikariCP-Pool");

        // Auto-commit disabled for explicit transaction management
        hikariConfig.setAutoCommit(false);

        // PostgreSQL-specific performance optimizations
        // Statement caching for prepared statement reuse
        hikariConfig.addDataSourceProperty("cachePrepStmts", "true");
        hikariConfig.addDataSourceProperty("prepStmtCacheSize", "250");
        hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        hikariConfig.addDataSourceProperty("useServerPrepStmts", "true");

        // Statement pooling for performance
        hikariConfig.addDataSourceProperty("useStatementPooling", "true");
        hikariConfig.addDataSourceProperty("maxStatements", "500");

        // Connection properties for reliability
        hikariConfig.addDataSourceProperty("socketTimeout", "30");
        hikariConfig.addDataSourceProperty("loginTimeout", "10");
        hikariConfig.addDataSourceProperty("connectTimeout", "10");

        // TCP keepalive for long-running connections
        hikariConfig.addDataSourceProperty("tcpKeepAlive", "true");

        // Application name for PostgreSQL connection tracking
        hikariConfig.addDataSourceProperty("ApplicationName", "CardDemo-SpringBoot");

        // Connection leak detection for development environment
        if ("dev".equalsIgnoreCase(activeProfile) || leakDetectionThreshold > 0) {
            hikariConfig.setLeakDetectionThreshold(leakDetectionThreshold > 0 ? 
                leakDetectionThreshold : 60000); // 60 seconds in dev mode
            log.info("Connection leak detection enabled with threshold: {} ms", 
                hikariConfig.getLeakDetectionThreshold());
        }

        // Register MBeans for monitoring
        hikariConfig.setRegisterMbeans(true);

        log.info("HikariCP DataSource configured successfully");
        return new HikariDataSource(hikariConfig);
    }

    /**
     * Configures JPA EntityManagerFactory with Hibernate as the persistence provider
     * and PostgreSQL-specific optimizations.
     * 
     * Entity Package Scanning:
     * - Scans com.carddemo.entity for all JPA entities (9 entities total)
     * - Customer, Account, Card, Transaction, User entities map to VSAM files
     * - TransactionCategory, TransactionType, DisclosureGroup, TransactionCategoryBalance
     * 
     * Hibernate Configuration:
     * - PostgreSQL dialect for database-specific SQL generation
     * - DDL auto set to 'validate' (Flyway manages schema migrations)
     * - Batch processing enabled (batch size 20) for insert/update performance
     * - Fetch size 50 for optimal result set retrieval
     * - Statement ordering for batching efficiency
     * 
     * Performance Tuning:
     * - show_sql disabled in production for performance
     * - format_sql disabled to reduce overhead
     * - Batch inserts and updates enabled
     * - Order inserts/updates for better batching
     * 
     * @param dataSource configured HikariCP DataSource
     * @return configured LocalContainerEntityManagerFactoryBean
     */
    @Bean
    public LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
        log.info("Configuring JPA EntityManagerFactory with Hibernate provider");

        LocalContainerEntityManagerFactoryBean entityManagerFactory = 
            new LocalContainerEntityManagerFactoryBean();
        
        // Set the DataSource
        entityManagerFactory.setDataSource(dataSource);
        
        // Scan for JPA entities in com.carddemo.entity package
        // Includes all 9 entities: Customer, Account, Card, Transaction, User,
        // TransactionCategory, TransactionType, DisclosureGroup, TransactionCategoryBalance
        entityManagerFactory.setPackagesToScan("com.carddemo.entity");

        // Configure Hibernate as JPA vendor adapter (database type determined by dialect)
        HibernateJpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
        // Database type omitted - determined by hibernate.dialect property for flexibility
        vendorAdapter.setGenerateDdl(generateDdl); // Configurable: false for prod (Flyway), true for tests
        vendorAdapter.setShowSql(false); // Disabled for production performance
        
        entityManagerFactory.setJpaVendorAdapter(vendorAdapter);

        // Set Hibernate-specific properties
        entityManagerFactory.setJpaProperties(jpaProperties());

        log.info("EntityManagerFactory configured with package scan: com.carddemo.entity");
        return entityManagerFactory;
    }

    /**
     * Configures Hibernate JPA properties for PostgreSQL database optimization
     * matching VSAM KSDS access patterns and COBOL COMP-3 precision requirements.
     * 
     * Dialect Configuration:
     * - PostgreSQLDialect for PostgreSQL-specific SQL generation
     * - Supports NUMERIC types for BigDecimal precision (COBOL COMP-3 equivalent)
     * 
     * Schema Management:
     * - hibernate.ddl-auto=validate ensures schema matches Flyway migrations
     * - No automatic schema generation (Flyway controls all DDL)
     * 
     * Performance Optimizations:
     * - Batch processing: 20 statements per batch for insert/update efficiency
     * - Order inserts/updates: Enables better batch execution
     * - Fetch size: 50 rows per database round-trip for list operations
     * - Statement caching: Handled by HikariCP configuration
     * 
     * Production Settings:
     * - show_sql=false to avoid logging overhead
     * - format_sql=false to minimize processing
     * - Statistics disabled (enable via application.properties if needed)
     * 
     * Transaction Management:
     * - JDBC timezone set to UTC for consistent date/time handling
     * - Supports CICS-style transaction boundaries via @Transactional
     * 
     * @return Properties object with all Hibernate configuration settings
     */
    private Properties jpaProperties() {
        log.debug("Configuring Hibernate JPA properties");

        Properties properties = new Properties();

        // Hibernate Dialect - configurable (PostgreSQL for prod, H2 for tests)
        properties.setProperty("hibernate.dialect", hibernateDialect);

        // Schema Management - Configurable: validate for prod (Flyway), create-drop for tests
        properties.setProperty("hibernate.ddl-auto", ddlAuto);

        // SQL Logging - Disabled for production performance
        properties.setProperty("hibernate.show_sql", "false");
        properties.setProperty("hibernate.format_sql", "false");

        // Batch Processing Configuration for Performance
        // Batch size of 20 balances memory usage and database round-trips
        properties.setProperty("hibernate.jdbc.batch_size", "20");
        properties.setProperty("hibernate.order_inserts", "true");
        properties.setProperty("hibernate.order_updates", "true");
        properties.setProperty("hibernate.jdbc.batch_versioned_data", "true");

        // Fetch Size for Optimal Result Set Retrieval
        // 50 rows per fetch matches typical pagination size (7 cards, 10 transactions)
        properties.setProperty("hibernate.jdbc.fetch_size", "50");

        // Connection Handling
        properties.setProperty("hibernate.connection.provider_disables_autocommit", "true");

        // JDBC Timezone Configuration for Consistent Date/Time Handling
        // UTC timezone ensures consistent date conversions from COBOL Lillian format
        properties.setProperty("hibernate.jdbc.time_zone", "UTC");

        // Physical Naming Strategy - Use default Spring Boot strategy
        properties.setProperty("hibernate.physical_naming_strategy",
            "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");

        // Second-Level Cache - Disabled (can be enabled via application.properties)
        properties.setProperty("hibernate.cache.use_second_level_cache", "false");
        properties.setProperty("hibernate.cache.use_query_cache", "false");

        // Statistics Collection - Disabled for production (enable for monitoring)
        properties.setProperty("hibernate.generate_statistics", "false");

        // JDBC Statement Comments - Useful for SQL tracing in development
        properties.setProperty("hibernate.use_sql_comments", 
            "dev".equalsIgnoreCase(activeProfile) ? "true" : "false");

        // Transaction Coordination
        properties.setProperty("hibernate.transaction.jta.platform",
            "org.hibernate.engine.transaction.jta.platform.internal.NoJtaPlatform");

        // Entity Enhancement - Lazy loading optimization
        properties.setProperty("hibernate.enhancer.enableLazyInitialization", "true");
        properties.setProperty("hibernate.enhancer.enableDirtyTracking", "true");

        log.debug("Hibernate JPA properties configured successfully");
        return properties;
    }

    /**
     * Configures JPA Transaction Manager for declarative transaction management
     * using @Transactional annotations.
     * 
     * Replaces CICS Transaction Management:
     * - CICS SYNCPOINT → @Transactional method completion (commit)
     * - CICS ROLLBACK → Exception thrown in @Transactional method (rollback)
     * - CICS Transaction boundaries → @Transactional method scope
     * 
     * Transaction Isolation:
     * - Default isolation level: READ_COMMITTED
     * - Matches VSAM record locking behavior
     * - Prevents dirty reads while allowing concurrent access
     * 
     * Transaction Propagation:
     * - REQUIRED (default): Join existing transaction or create new
     * - Supports nested service calls maintaining transaction atomicity
     * 
     * Rollback Behavior:
     * - Automatic rollback on RuntimeException and Error
     * - Configurable via @Transactional(rollbackFor) for checked exceptions
     * - Maintains ACID properties equivalent to CICS transaction management
     * 
     * Performance Considerations:
     * - Transaction timeout can be configured per method
     * - Read-only transactions optimize database performance
     * - Supports batch processing within transaction boundaries
     * 
     * @param entityManagerFactory configured EntityManagerFactory
     * @return configured PlatformTransactionManager for Spring transaction management
     */
    @Bean
    @Primary
    public PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
        log.info("Configuring JPA Transaction Manager");

        JpaTransactionManager transactionManager = new JpaTransactionManager();
        transactionManager.setEntityManagerFactory(entityManagerFactory);

        // Transaction validation timeout (5 seconds)
        transactionManager.setValidateExistingTransaction(true);

        // Enable nested transactions for complex service orchestration
        transactionManager.setNestedTransactionAllowed(true);

        // Fail early on transaction commit failures
        transactionManager.setFailEarlyOnGlobalRollbackOnly(true);

        log.info("Transaction Manager configured successfully");
        log.info("Transaction management replaces CICS SYNCPOINT and ROLLBACK behavior");
        log.info("@Transactional annotations define transaction boundaries");

        return transactionManager;
    }
}
