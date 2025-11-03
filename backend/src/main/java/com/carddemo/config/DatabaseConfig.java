/*
 * DatabaseConfig.java
 *
 * CardDemo Application - Database Configuration
 * 
 * Spring Boot configuration class for database access layer providing JPA/Hibernate 
 * and HikariCP connection pool setup. Transforms VSAM KSDS file I/O patterns 
 * (READ, WRITE, REWRITE, DELETE) to JPA repository operations with equivalent semantics.
 *
 * This configuration replaces:
 * - COBOL VSAM file access (SELECT, ORGANIZATION IS INDEXED)
 * - CICS file control operations (EXEC CICS READ, WRITE, REWRITE, DELETE)
 * - CICS transaction boundaries (EXEC CICS SYNCPOINT)
 *
 * Key transformations per Section 0.1 Data Architecture Modernization:
 * - VSAM KSDS sequential reads → JPA repository findAll() with pagination
 * - VSAM keyed reads → JPA repository findById()
 * - VSAM REWRITE → JPA repository save() with existing entity
 * - VSAM WRITE → JPA repository save() with new entity
 * - VSAM DELETE → JPA repository delete()
 * - VSAM file-status codes → DataAccessException hierarchy
 *
 * Performance requirements per Section 0.9:
 * - HikariCP connection pool: 20 minimum idle, 50 maximum connections
 * - Transaction isolation: READ_COMMITTED (matches CICS default)
 * - Connection timeout: 30 seconds
 * - Query timeout: 30 seconds
 * - Supports 10,000 TPS with sub-200ms response time at 95th percentile
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.config;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
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
 * Database configuration class for CardDemo application.
 * 
 * Configures the complete data access layer including:
 * - HikariCP connection pool optimized for VSAM-equivalent performance
 * - JPA/Hibernate entity management with PostgreSQL dialect
 * - Transaction management with CICS-equivalent isolation levels
 * - Flyway database migration for schema versioning
 * 
 * This configuration ensures:
 * 1. COBOL COMP-3 packed decimal precision preservation using BigDecimal
 * 2. CICS SYNCPOINT transaction boundary equivalence via @Transactional
 * 3. VSAM concurrent access performance via optimized connection pooling
 * 4. READ_COMMITTED isolation level matching CICS default transaction semantics
 * 
 * @see org.springframework.data.jpa.repository.JpaRepository for repository pattern
 * @see org.springframework.transaction.annotation.Transactional for transaction management
 */
@Configuration
@EnableJpaRepositories(basePackages = "com.carddemo.repository")
@EnableTransactionManagement
public class DatabaseConfig {

    @Autowired
    private Environment environment;

    /**
     * Configures HikariCP DataSource with optimized connection pool settings.
     * 
     * Replaces VSAM file access with PostgreSQL JDBC connections. Pool sizing
     * is optimized to match VSAM concurrent access performance characteristics
     * and support peak transaction volumes of 10,000 TPS.
     * 
     * Connection Pool Settings (per Section 0.9 performance requirements):
     * - Minimum Idle: 20 connections (ensures quick response to traffic spikes)
     * - Maximum Pool Size: 50 connections (prevents database connection exhaustion)
     * - Connection Timeout: 30 seconds (matches COBOL file I/O timeout patterns)
     * - Idle Timeout: 10 minutes (reclaims unused connections)
     * - Max Lifetime: 30 minutes (prevents connection staleness)
     * - Leak Detection: 60 seconds (identifies connection leaks in service layer)
     * 
     * VSAM to PostgreSQL Transformation:
     * - VSAM KSDS file access → JDBC connection to PostgreSQL table
     * - COBOL FILE STATUS checking → SQLException handling
     * - VSAM buffer pool → HikariCP connection pool
     * 
     * @return configured HikariDataSource for database connectivity
     */
    @Bean
    public DataSource dataSource() {
        HikariDataSource dataSource = new HikariDataSource();
        
        // Basic JDBC connection properties from application.yml
        dataSource.setJdbcUrl(environment.getRequiredProperty("spring.datasource.url"));
        dataSource.setUsername(environment.getRequiredProperty("spring.datasource.username"));
        dataSource.setPassword(environment.getRequiredProperty("spring.datasource.password"));
        dataSource.setDriverClassName(environment.getProperty("spring.datasource.driver-class-name", "org.postgresql.Driver"));
        
        // Connection pool configuration per Section 0.9 requirements
        dataSource.setMinimumIdle(20);
        dataSource.setMaximumPoolSize(50);
        dataSource.setConnectionTimeout(30000); // 30 seconds
        dataSource.setIdleTimeout(600000);      // 10 minutes
        dataSource.setMaxLifetime(1800000);     // 30 minutes
        dataSource.setAutoCommit(true);
        dataSource.setPoolName("CardDemoHikariCP");
        dataSource.setLeakDetectionThreshold(60000); // 1 minute
        
        // Performance optimizations
        dataSource.addDataSourceProperty("cachePrepStmts", "true");
        dataSource.addDataSourceProperty("prepStmtCacheSize", "250");
        dataSource.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        dataSource.addDataSourceProperty("useServerPrepStmts", "true");
        dataSource.addDataSourceProperty("useLocalSessionState", "true");
        dataSource.addDataSourceProperty("rewriteBatchedStatements", "true");
        dataSource.addDataSourceProperty("cacheResultSetMetadata", "true");
        dataSource.addDataSourceProperty("cacheServerConfiguration", "true");
        dataSource.addDataSourceProperty("elideSetAutoCommits", "true");
        dataSource.addDataSourceProperty("maintainTimeStats", "false");
        
        return dataSource;
    }

    /**
     * Configures JPA EntityManagerFactory with Hibernate implementation.
     * 
     * Replaces COBOL COPY statement data structure definitions with JPA entity
     * mappings. Scans com.carddemo.entity package for @Entity annotated classes
     * that represent COBOL copybook record layouts transformed to Java classes.
     * 
     * Entity Scanning (COBOL Copybook equivalents):
     * - COPY CVCUS01Y → Customer.java entity
     * - COPY CVACT01Y → Account.java entity
     * - COPY CVACT03Y → Card.java entity
     * - COPY CVTRA01Y → Transaction.java entity
     * - COPY CSUSR01Y → UserSecurity.java entity
     * 
     * Hibernate Configuration:
     * - Dialect: PostgreSQL-specific SQL generation
     * - DDL Auto: validate (Flyway manages schema, Hibernate validates only)
     * - Batch Size: 50 (optimizes bulk insert/update operations)
     * - Fetch Size: 100 (matches typical COBOL file I/O block size)
     * - Isolation: READ_COMMITTED (CICS default transaction isolation level)
     * 
     * @param dataSource the HikariCP data source
     * @return configured EntityManagerFactory for JPA operations
     */
    @Bean
    public LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
        LocalContainerEntityManagerFactoryBean entityManagerFactory = 
            new LocalContainerEntityManagerFactoryBean();
        
        entityManagerFactory.setDataSource(dataSource);
        entityManagerFactory.setPackagesToScan("com.carddemo.entity");
        entityManagerFactory.setPersistenceUnitName("carddemo");
        
        // Configure Hibernate as JPA vendor
        HibernateJpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
        vendorAdapter.setDatabase(Database.POSTGRESQL);
        vendorAdapter.setShowSql(false); // Set to true in dev profile via hibernate.show_sql property
        vendorAdapter.setGenerateDdl(false); // Flyway manages DDL
        entityManagerFactory.setJpaVendorAdapter(vendorAdapter);
        
        // Set Hibernate-specific properties
        entityManagerFactory.setJpaProperties(hibernateProperties());
        
        return entityManagerFactory;
    }

    /**
     * Configures Hibernate JPA properties for PostgreSQL database.
     * 
     * These properties ensure:
     * 1. COBOL COMP-3 precision preservation via proper BigDecimal handling
     * 2. Batch processing optimization for bulk operations (batch jobs)
     * 3. Query performance tuning for VSAM-equivalent access patterns
     * 4. Transaction isolation matching CICS default behavior
     * 
     * Critical Properties:
     * - hibernate.connection.isolation=2 (READ_COMMITTED, matches CICS)
     * - hibernate.jdbc.batch_size=50 (bulk insert optimization for batch jobs)
     * - hibernate.jdbc.fetch_size=100 (read-ahead optimization)
     * - hibernate.order_inserts=true (minimizes deadlocks in concurrent updates)
     * - hibernate.order_updates=true (consistent update ordering)
     * 
     * VSAM File-Status to Exception Mapping:
     * - File-Status '00' (successful) → No exception
     * - File-Status '10' (end of file) → EmptyResultDataAccessException
     * - File-Status '23' (record not found) → EmptyResultDataAccessException
     * - File-Status '24' (boundary violation) → DataIntegrityViolationException
     * - Other file-status codes → DataAccessException hierarchy
     * 
     * @return Properties object containing Hibernate configuration
     */
    private Properties hibernateProperties() {
        Properties properties = new Properties();
        
        // PostgreSQL dialect for SQL generation
        properties.setProperty("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
        
        // SQL logging (controlled by environment)
        properties.setProperty("hibernate.show_sql", 
            environment.getProperty("spring.jpa.show-sql", "false"));
        properties.setProperty("hibernate.format_sql", "true");
        properties.setProperty("hibernate.use_sql_comments", "true");
        
        // Schema management (Flyway handles DDL in production, Hibernate validates by default)
        // For test environments, this can be overridden via spring.jpa.hibernate.ddl-auto property
        String ddlAuto = environment.getProperty("spring.jpa.hibernate.ddl-auto", "validate");
        properties.setProperty("hibernate.ddl-auto", ddlAuto);
        properties.setProperty("hibernate.hbm2ddl.auto", ddlAuto);
        
        // Batch processing optimization (critical for batch job performance)
        properties.setProperty("hibernate.jdbc.batch_size", "50");
        properties.setProperty("hibernate.order_inserts", "true");
        properties.setProperty("hibernate.order_updates", "true");
        properties.setProperty("hibernate.batch_versioned_data", "true");
        
        // Fetch optimization (matches VSAM block size patterns)
        properties.setProperty("hibernate.jdbc.fetch_size", "100");
        properties.setProperty("hibernate.default_batch_fetch_size", "10");
        
        // Transaction isolation (READ_COMMITTED = 2, matches CICS default per Section 0.9)
        properties.setProperty("hibernate.connection.isolation", "2");
        
        // Query performance optimizations
        properties.setProperty("hibernate.query.plan_cache_max_size", "2048");
        properties.setProperty("hibernate.query.plan_parameter_metadata_max_size", "128");
        properties.setProperty("hibernate.jdbc.time_zone", "UTC");
        
        // Second-level cache disabled (stateless like CICS transactions)
        properties.setProperty("hibernate.cache.use_second_level_cache", "false");
        properties.setProperty("hibernate.cache.use_query_cache", "false");
        
        // Statistics (enabled in dev/test profiles for performance analysis)
        properties.setProperty("hibernate.generate_statistics", 
            environment.getProperty("spring.jpa.properties.hibernate.generate_statistics", "false"));
        
        return properties;
    }

    /**
     * Configures JPA transaction manager for declarative transaction management.
     * 
     * Replaces CICS transaction boundaries (EXEC CICS SYNCPOINT) with Spring
     * @Transactional annotation support. Ensures transaction isolation level
     * matches CICS default behavior (READ_COMMITTED).
     * 
     * Transaction Semantics Mapping:
     * - EXEC CICS SYNCPOINT → Method with @Transactional commits
     * - EXEC CICS SYNCPOINT ROLLBACK → Exception thrown, @Transactional rolls back
     * - CICS pseudo-conversational → Stateless REST + session state in Redis
     * - CICS COMMAREA → JSON request/response DTOs
     * 
     * Transaction Configuration:
     * - Isolation: READ_COMMITTED (per Section 0.9, matches CICS default)
     * - Propagation: REQUIRED (new transaction or join existing)
     * - Timeout: 30 seconds (configurable, matches COBOL file I/O timeout)
     * - Rollback: On RuntimeException and Error
     * 
     * Service Layer Usage:
     * <pre>
     * {@code
     * @Transactional(
     *     isolation = Isolation.READ_COMMITTED,
     *     propagation = Propagation.REQUIRED,
     *     rollbackFor = Exception.class
     * )
     * public void updateAccount(AccountUpdateRequest request) {
     *     // All database operations atomic, replaces CICS SYNCPOINT
     * }
     * }
     * </pre>
     * 
     * @param entityManagerFactory the JPA entity manager factory
     * @return configured PlatformTransactionManager for transaction management
     */
    @Bean
    public PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
        JpaTransactionManager transactionManager = new JpaTransactionManager();
        transactionManager.setEntityManagerFactory(entityManagerFactory);
        
        // Set default transaction timeout (30 seconds)
        transactionManager.setDefaultTimeout(30);
        
        // Enable nested transaction support for complex scenarios
        transactionManager.setNestedTransactionAllowed(true);
        
        // Validate existing transaction before joining
        transactionManager.setValidateExistingTransaction(true);
        
        // Enable transaction synchronization for resource management
        transactionManager.setGlobalRollbackOnParticipationFailure(true);
        
        return transactionManager;
    }

    /**
     * Configures Flyway database migration for versioned schema evolution.
     * 
     * Replaces VSAM file definition (IDCAMS DEFINE CLUSTER) with PostgreSQL
     * DDL scripts managed through versioned migration files. Ensures schema
     * consistency across environments and supports rollback capabilities.
     * 
     * Migration File Mapping (per Section 0.6):
     * - V1__create_customer_table.sql → CUSTDAT VSAM KSDS
     * - V2__create_account_table.sql → ACCTDAT VSAM KSDS
     * - V3__create_card_table.sql → CARDDAT VSAM KSDS
     * - V4__create_transaction_table.sql → TRANSACT VSAM KSDS
     * - V5__create_user_security_table.sql → USRSEC VSAM KSDS
     * - V6__create_xref_tables.sql → XREF/CXACAIX cross-reference files
     * - V7__create_indexes.sql → VSAM alternate index definitions
     * - V8__create_foreign_keys.sql → Referential integrity constraints
     * - V9__load_reference_data.sql → Reference data initialization
     * 
     * Flyway Configuration:
     * - Baseline on migrate: true (supports existing databases)
     * - Baseline version: 0 (starting point for version tracking)
     * - Locations: classpath:db/migration (SQL scripts location)
     * - Out of order: false (enforces sequential migration)
     * - Validate on migrate: true (ensures migration integrity)
     * 
     * @param dataSource the HikariCP data source
     * @return configured Flyway instance for database migrations
     */
    @Bean(initMethod = "migrate")
    @ConditionalOnProperty(name = "spring.flyway.enabled", havingValue = "true", matchIfMissing = true)
    public Flyway flyway(DataSource dataSource) {
        return Flyway.configure()
            .dataSource(dataSource)
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .locations("classpath:db/migration")
            .outOfOrder(false)
            .validateOnMigrate(true)
            .cleanDisabled(true) // Prevent accidental data loss in production
            .load();
    }

    /**
     * Development profile configuration for enhanced debugging.
     * 
     * Enables Hibernate SQL logging and statistics for development and
     * testing environments. Disabled in production for performance.
     * 
     * @return DataSource configured for development use
     */
    @Bean
    @Profile("dev")
    public DataSource devDataSource() {
        DataSource dataSource = dataSource();
        
        // Development-specific logging
        System.setProperty("hibernate.show_sql", "true");
        System.setProperty("hibernate.format_sql", "true");
        System.setProperty("hibernate.generate_statistics", "true");
        
        return dataSource;
    }
}
