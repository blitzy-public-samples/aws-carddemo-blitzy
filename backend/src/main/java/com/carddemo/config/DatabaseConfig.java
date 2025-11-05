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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;

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
 * 5. Flyway migrations run before EntityManagerFactory initialization (via Spring Boot auto-configuration)
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
     * JPA EntityManagerFactory Configuration
     * 
     * NOTE: EntityManagerFactory bean is NOT manually defined to avoid circular dependency issues.
     * Spring Boot's auto-configuration handles JPA/Hibernate initialization based on properties
     * in application.yml (spring.jpa.* and spring.jpa.properties.hibernate.*).
     * 
     * Entity Package Scanning: Configured via @EnableJpaRepositories annotation above
     * - com.carddemo.entity package contains all JPA entities
     * 
     * Entity Scanning (COBOL Copybook equivalents):
     * - COPY CVCUS01Y → Customer.java entity
     * - COPY CVACT01Y → Account.java entity
     * - COPY CVACT03Y → Card.java entity
     * - COPY CVTRA01Y → Transaction.java entity
     * - COPY CSUSR01Y → UserSecurity.java entity
     * 
     * Hibernate Configuration (in application.yml):
     * - spring.jpa.properties.hibernate.dialect: PostgreSQLDialect
     * - spring.jpa.hibernate.ddl-auto: validate (Flyway manages schema)
     * - spring.jpa.properties.hibernate.jdbc.batch_size: 50
     * - spring.jpa.properties.hibernate.jdbc.fetch_size: 100
     * - spring.jpa.properties.hibernate.connection.isolation: 2 (READ_COMMITTED)
     * 
     * This approach eliminates circular dependencies between Flyway and EntityManagerFactory
     * by allowing Spring Boot to manage the initialization order automatically.
     */

    /**
     * Hibernate JPA Properties Configuration
     * 
     * NOTE: Hibernate properties are configured via application.yml (spring.jpa.properties.hibernate.*)
     * rather than programmatically. This allows Spring Boot's auto-configuration to manage
     * EntityManagerFactory initialization properly and avoid circular dependencies with Flyway.
     * 
     * Key Hibernate Properties (in application.yml):
     * - hibernate.dialect: PostgreSQLDialect
     * - hibernate.connection.isolation: 2 (READ_COMMITTED, matches CICS)
     * - hibernate.jdbc.batch_size: 50 (bulk insert optimization)
     * - hibernate.jdbc.fetch_size: 100 (read-ahead optimization)
     * - hibernate.order_inserts: true (minimizes deadlocks)
     * - hibernate.order_updates: true (consistent update ordering)
     * - hibernate.cache.use_second_level_cache: false (stateless like CICS)
     * 
     * VSAM File-Status to Exception Mapping:
     * - File-Status '00' (successful) → No exception
     * - File-Status '10' (end of file) → EmptyResultDataAccessException
     * - File-Status '23' (record not found) → EmptyResultDataAccessException
     * - File-Status '24' (boundary violation) → DataIntegrityViolationException
     * - Other file-status codes → DataAccessException hierarchy
     */

    /**
     * Transaction Manager Configuration
     * 
     * NOTE: Transaction manager is NOT manually defined here to avoid circular dependency issues.
     * Spring Boot's auto-configuration handles JpaTransactionManager initialization automatically
     * when JPA is enabled.
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
     * Transaction Configuration (configured via @Transactional annotations):
     * - Isolation: READ_COMMITTED (per Section 0.9, matches CICS default)
     * - Propagation: REQUIRED (new transaction or join existing)
     * - Timeout: 30 seconds (configurable per method)
     * - Rollback: On RuntimeException and Error
     * 
     * Service Layer Usage:
     * <pre>
     * {@code
     * @Transactional(
     *     isolation = Isolation.READ_COMMITTED,
     *     propagation = Propagation.REQUIRED,
     *     timeout = 30,
     *     rollbackFor = Exception.class
     * )
     * public void updateAccount(AccountUpdateRequest request) {
     *     // All database operations atomic, replaces CICS SYNCPOINT
     * }
     * }
     * </pre>
     * 
     * Spring Boot auto-configures JpaTransactionManager with proper initialization order:
     * 1. DataSource is created first
     * 2. Flyway runs migrations on DataSource
     * 3. EntityManagerFactory is created after Flyway completes
     * 4. JpaTransactionManager is created last, using EntityManagerFactory
     * 
     * This approach eliminates circular dependencies by delegating initialization to Spring Boot.
     */

    /**
     * Flyway Database Migration Configuration
     * 
     * NOTE: Flyway bean is NOT manually defined here to avoid circular dependency issues.
     * Spring Boot's auto-configuration handles Flyway initialization based on properties
     * in application.yml (spring.flyway.*).
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
     * Flyway Configuration (in application.yml):
     * - spring.flyway.enabled: true
     * - spring.flyway.baseline-on-migrate: true (supports existing databases)
     * - spring.flyway.baseline-version: 0 (starting point for version tracking)
     * - spring.flyway.locations: classpath:db/migration (SQL scripts location)
     * - spring.flyway.out-of-order: false (enforces sequential migration)
     * - spring.flyway.validate-on-migrate: true (ensures migration integrity)
     * 
     * This approach eliminates circular dependencies between Flyway and EntityManagerFactory
     * by allowing Spring Boot to manage the bean initialization order automatically.
     */
}
