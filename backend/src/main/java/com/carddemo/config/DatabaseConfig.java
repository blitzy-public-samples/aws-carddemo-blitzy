package com.carddemo.config;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Isolation;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * Spring configuration class for PostgreSQL database connectivity replacing VSAM file access.
 * 
 * <p>This configuration class establishes database infrastructure for the CardDemo application,
 * migrating from IBM z/OS mainframe VSAM (Virtual Storage Access Method) Key-Sequenced Data Sets
 * to PostgreSQL 16.x relational database management system.</p>
 * 
 * <h2>VSAM to PostgreSQL Migration</h2>
 * <p>The following 11 VSAM KSDS (Key-Sequenced Data Set) datasets have been converted to 
 * PostgreSQL tables with B-tree indexes matching VSAM key access paths:</p>
 * <ul>
 *   <li><strong>ACCTFILE</strong> → account table (Account master records)</li>
 *   <li><strong>CARDFILE</strong> → card table (Card master records)</li>
 *   <li><strong>CUSTFILE</strong> → customer table (Customer master records)</li>
 *   <li><strong>XREFFILE</strong> → card_account_xref table (Card-Account cross-reference)</li>
 *   <li><strong>TRANSACT</strong> → transaction table (Online transaction records)</li>
 *   <li><strong>DALYTRAN</strong> → daily_transaction table (Daily transaction file)</li>
 *   <li><strong>TCATBAL</strong> → transaction_category_balance table (Category balances)</li>
 *   <li><strong>DISCGRP</strong> → disclosure_group table (Disclosure group data)</li>
 *   <li><strong>TRANCATG</strong> → transaction_category table (Transaction categories)</li>
 *   <li><strong>TRANTYPE</strong> → transaction_type table (Transaction types)</li>
 *   <li><strong>USRSEC</strong> → user_security table (User security records)</li>
 * </ul>
 * 
 * <h2>Performance Requirements</h2>
 * <p>Configuration is optimized to meet the following non-negotiable performance SLAs:</p>
 * <ul>
 *   <li><strong>Transaction Response Time:</strong> Sub-200ms for card authorization requests (95th percentile)</li>
 *   <li><strong>Throughput:</strong> 10,000 transactions per second (TPS) peak load</li>
 *   <li><strong>Batch Processing:</strong> Complete within 4-hour overnight cycles (02:00-06:00)</li>
 *   <li><strong>Query Performance:</strong> Sub-10ms for primary key lookups (matching VSAM key access)</li>
 * </ul>
 * 
 * <h2>Connection Pool Configuration</h2>
 * <p>HikariCP connection pool is sized for high-throughput requirements:</p>
 * <ul>
 *   <li><strong>Maximum Pool Size:</strong> 20 connections (supports 10K TPS requirement)</li>
 *   <li><strong>Minimum Idle:</strong> 5 connections (baseline capacity for immediate requests)</li>
 *   <li><strong>Connection Timeout:</strong> 30 seconds (matches CICS response time requirements)</li>
 *   <li><strong>Idle Timeout:</strong> 10 minutes (600 seconds)</li>
 *   <li><strong>Max Lifetime:</strong> 30 minutes (1800 seconds)</li>
 * </ul>
 * 
 * <h2>Transaction Management</h2>
 * <p>Transaction isolation and boundaries replicate CICS transaction processing:</p>
 * <ul>
 *   <li><strong>Isolation Level:</strong> READ_COMMITTED (equivalent to VSAM RLS record-level sharing)</li>
 *   <li><strong>Default Timeout:</strong> 30 seconds (matches CICS SYNCPOINT patterns)</li>
 *   <li><strong>Transaction Control:</strong> Spring @Transactional replaces EXEC CICS SYNCPOINT/ROLLBACK</li>
 * </ul>
 * 
 * <h2>COBOL Program Integration</h2>
 * <p>This configuration supports COBOL-to-Java transformations for the following programs:</p>
 * <ul>
 *   <li><strong>COACTUPC.cbl:</strong> Account update operations (VSAM READ/REWRITE → JPA save)</li>
 *   <li><strong>COCRDUPC.cbl:</strong> Card update operations (VSAM READ/REWRITE → JPA save)</li>
 *   <li><strong>COTRN02C.cbl:</strong> Transaction posting (VSAM WRITE → JPA save)</li>
 * </ul>
 * 
 * <h2>MINIMAL CHANGE CLAUSE</h2>
 * <p>This configuration contains NO business logic per the minimal change directive.
 * It provides ONLY the infrastructure necessary to replace VSAM file I/O with PostgreSQL
 * database access while maintaining identical functional behavior.</p>
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 2024
 * @see com.carddemo.repository Package containing JPA repositories
 * @see com.carddemo.model.entity Package containing JPA entities converted from COBOL copybooks
 */
@Configuration
@EnableJpaRepositories(basePackages = "com.carddemo.repository")
@EnableTransactionManagement
public class DatabaseConfig {

    /**
     * Database JDBC URL injected from application.yml.
     * Development: jdbc:postgresql://localhost:5432/carddemo
     * Production: RDS endpoint URL
     */
    @Value("${spring.datasource.url}")
    private String databaseUrl;

    /**
     * Database username injected from application.yml or environment variables.
     */
    @Value("${spring.datasource.username}")
    private String databaseUsername;

    /**
     * Database password injected from application.yml or environment variables.
     * Should be encrypted in production using Spring Cloud Config or AWS Secrets Manager.
     */
    @Value("${spring.datasource.password}")
    private String databasePassword;

    /**
     * Database driver class name injected from application.yml.
     * Production: org.postgresql.Driver
     * Test: org.h2.Driver (for H2 in-memory database)
     */
    @Value("${spring.datasource.driver-class-name:org.postgresql.Driver}")
    private String driverClassName;

    /**
     * Flag to enable SQL logging in development profile.
     * Set to true in application-dev.yml, false in application-prod.yml.
     */
    @Value("${spring.jpa.show-sql:false}")
    private boolean showSql;

    /**
     * Hibernate DDL auto mode for schema management.
     * Default: validate (Flyway manages DDL, Hibernate validates only)
     * Test profiles may override to create-drop for integration tests with Testcontainers.
     */
    @Value("${spring.jpa.hibernate.ddl-auto:validate}")
    private String ddlAuto;

    /**
     * Creates and configures the HikariCP DataSource connection pool for PostgreSQL.
     * 
     * <p>This bean replaces VSAM file handles with PostgreSQL database connections.
     * Connection pool settings are optimized for the 10,000 TPS performance requirement
     * while maintaining sub-200ms response times matching CICS transaction processing.</p>
     * 
     * <h3>Connection Pool Sizing Strategy</h3>
     * <p>Maximum pool size of 20 connections is calculated based on:</p>
     * <ul>
     *   <li>Peak load: 10,000 TPS</li>
     *   <li>Average transaction time: 50ms (sub-200ms requirement with safety margin)</li>
     *   <li>Concurrent transactions: 10,000 TPS * 0.05s = 500 concurrent transactions</li>
     *   <li>Connection efficiency: 500 / 20 = 25 transactions per connection per second</li>
     *   <li>With connection pooling and statement caching, 20 connections adequate for load</li>
     * </ul>
     * 
     * <h3>Timeout Configuration</h3>
     * <p>Connection timeout of 30 seconds provides graceful degradation:</p>
     * <ul>
     *   <li>Prevents request queue buildup during database slowdowns</li>
     *   <li>Allows health checks to detect connectivity issues</li>
     *   <li>Matches CICS transaction timeout patterns</li>
     * </ul>
     * 
     * @return Configured HikariDataSource ready for JPA entity manager factory
     * @throws IllegalStateException if database connection cannot be established
     */
    @Bean
    public DataSource dataSource() {
        HikariDataSource dataSource = new HikariDataSource();
        
        // Database connection settings
        dataSource.setJdbcUrl(databaseUrl);
        dataSource.setUsername(databaseUsername);
        dataSource.setPassword(databasePassword);
        dataSource.setDriverClassName(driverClassName);
        
        // Connection pool sizing (optimized for 10K TPS requirement)
        dataSource.setMaximumPoolSize(20);
        dataSource.setMinimumIdle(5);
        
        // Timeout settings (aligned with CICS response time requirements)
        dataSource.setConnectionTimeout(30000);  // 30 seconds - matches CICS timeout
        dataSource.setIdleTimeout(600000);       // 10 minutes - connection idle timeout
        dataSource.setMaxLifetime(1800000);      // 30 minutes - maximum connection lifetime
        
        // Health check query for connection validation
        dataSource.setConnectionTestQuery("SELECT 1");
        
        // Pool name for monitoring and logging
        dataSource.setPoolName("CardDemoHikariCP");
        
        // Connection leak detection (enabled in development, helps identify unclosed connections)
        dataSource.setLeakDetectionThreshold(60000); // 60 seconds
        
        return dataSource;
    }

    /**
     * Creates and configures the JPA EntityManagerFactory with Hibernate ORM provider.
     * 
     * <p>This factory manages the lifecycle of JPA entities converted from COBOL copybooks,
     * providing object-relational mapping that replaces COBOL data structures with Java entities.</p>
     * 
     * <h3>Entity Package Scanning</h3>
     * <p>Scans <code>com.carddemo.model.entity</code> package for the following entities:</p>
     * <ul>
     *   <li><strong>Account.java</strong> - Converted from CVACT01Y.cpy (ACCTFILE record)</li>
     *   <li><strong>Card.java</strong> - Converted from CVACT02Y.cpy (CARDFILE record)</li>
     *   <li><strong>Customer.java</strong> - Converted from CVCUS01Y.cpy (CUSTFILE record)</li>
     *   <li><strong>Transaction.java</strong> - Converted from CVTRA05Y.cpy (TRANSACT record)</li>
     *   <li><strong>DailyTransaction.java</strong> - Converted from CVTRA06Y.cpy (DALYTRAN record)</li>
     *   <li><strong>CardAccountXref.java</strong> - Converted from CVACT03Y.cpy (XREFFILE record)</li>
     *   <li><strong>TransactionCategory.java</strong> - Converted from CVTRA04Y.cpy (TRANCATG record)</li>
     *   <li><strong>TransactionType.java</strong> - Converted from CVTRA03Y.cpy (TRANTYPE record)</li>
     *   <li><strong>DisclosureGroup.java</strong> - Converted from CVTRA02Y.cpy (DISCGRP record)</li>
     *   <li><strong>TransactionCategoryBalance.java</strong> - Converted from CVTRA01Y.cpy (TCATBAL record)</li>
     *   <li><strong>UserSecurity.java</strong> - Converted from CSUSR01Y.cpy (USRSEC record)</li>
     * </ul>
     * 
     * <h3>Hibernate Configuration</h3>
     * <p>Hibernate properties are tuned for PostgreSQL and batch processing efficiency:</p>
     * <ul>
     *   <li><strong>DDL Auto:</strong> validate (Flyway manages schema, Hibernate validates only)</li>
     *   <li><strong>SQL Dialect:</strong> PostgreSQLDialect (optimizes SQL generation for PostgreSQL)</li>
     *   <li><strong>Batch Size:</strong> 20 (optimizes batch insert/update for Spring Batch jobs)</li>
     *   <li><strong>Fetch Size:</strong> 50 (optimizes result set retrieval)</li>
     *   <li><strong>Order Operations:</strong> Enabled (optimizes batch insert/update ordering)</li>
     * </ul>
     * 
     * @param dataSource HikariCP DataSource providing database connections
     * @return Configured LocalContainerEntityManagerFactoryBean for JPA entity management
     */
    @Bean
    public LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
        LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
        
        // Set the HikariCP DataSource
        factory.setDataSource(dataSource);
        
        // Scan packages for JPA entity classes converted from COBOL copybooks
        factory.setPackagesToScan("com.carddemo.model.entity");
        
        // Use Hibernate as the JPA vendor implementation
        HibernateJpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
        factory.setJpaVendorAdapter(vendorAdapter);
        
        // Configure Hibernate-specific properties
        Properties jpaProperties = new Properties();
        
        // Schema management: Read from configuration (validate in prod, create-drop in tests)
        // Use canonical Hibernate property name for direct properties configuration
        jpaProperties.setProperty("hibernate.hbm2ddl.auto", ddlAuto);
        
        // SQL logging (enabled in dev profile only via showSql property)
        jpaProperties.setProperty("hibernate.show-sql", String.valueOf(showSql));
        jpaProperties.setProperty("hibernate.format-sql", "true");
        
        // PostgreSQL dialect for SQL generation optimization
        jpaProperties.setProperty("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
        
        // Batch processing optimization for Spring Batch jobs (CBACT*, CBTRN* programs)
        jpaProperties.setProperty("hibernate.jdbc.batch_size", "20");
        jpaProperties.setProperty("hibernate.order_inserts", "true");
        jpaProperties.setProperty("hibernate.order_updates", "true");
        
        // Fetch size optimization for large result sets
        jpaProperties.setProperty("hibernate.jdbc.fetch_size", "50");
        
        // Statistics for performance monitoring (disabled in production)
        jpaProperties.setProperty("hibernate.generate_statistics", "false");
        
        factory.setJpaProperties(jpaProperties);
        
        return factory;
    }

    /**
     * Creates and configures the JPA transaction manager with proper isolation level.
     * 
     * <p>This transaction manager replaces CICS transaction control (EXEC CICS SYNCPOINT/ROLLBACK)
     * with Spring's declarative transaction management using @Transactional annotations.</p>
     * 
     * <h3>Transaction Isolation Mapping</h3>
     * <p>ISOLATION_READ_COMMITTED is equivalent to:</p>
     * <ul>
     *   <li><strong>VSAM RLS:</strong> Record-Level Sharing with exclusive update locks</li>
     *   <li><strong>CICS Default:</strong> Default transaction isolation in CICS TS</li>
     *   <li><strong>Behavior:</strong> Prevents dirty reads, allows non-repeatable reads and phantom reads</li>
     * </ul>
     * 
     * <h3>Transaction Boundaries</h3>
     * <p>Service layer methods with @Transactional replace COBOL transaction boundaries:</p>
     * <ul>
     *   <li><strong>EXEC CICS SYNCPOINT</strong> → @Transactional method completion (commit)</li>
     *   <li><strong>EXEC CICS ROLLBACK</strong> → Exception thrown from @Transactional method (rollback)</li>
     *   <li><strong>EXEC CICS READ UPDATE</strong> → JPA pessimistic locking (@Lock annotation)</li>
     * </ul>
     * 
     * <h3>Transaction Timeout</h3>
     * <p>Default timeout of 30 seconds matches CICS transaction timeout patterns:</p>
     * <ul>
     *   <li>Prevents long-running transactions from holding database locks</li>
     *   <li>Aligns with sub-200ms response time requirement (ample safety margin)</li>
     *   <li>Allows batch jobs to override timeout for longer-running operations</li>
     * </ul>
     * 
     * @param entityManagerFactory JPA EntityManagerFactory managing entity lifecycle
     * @return Configured PlatformTransactionManager for Spring transaction management
     */
    @Bean
    public PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
        JpaTransactionManager transactionManager = new JpaTransactionManager();
        
        // Set the entity manager factory
        transactionManager.setEntityManagerFactory(entityManagerFactory);
        
        // Set default transaction timeout (30 seconds - matches CICS SYNCPOINT patterns)
        transactionManager.setDefaultTimeout(30);
        
        // Note: Default isolation level READ_COMMITTED (VSAM RLS equivalent) is set at
        // the transaction level using @Transactional(isolation = Isolation.READ_COMMITTED)
        // Spring's JpaTransactionManager does not support setting a default isolation level
        // at the manager level; it must be specified per transaction.
        
        return transactionManager;
    }

    /**
     * Creates a custom health indicator for database connectivity monitoring.
     * 
     * <p>This health indicator exposes database connectivity status at the
     * <code>/actuator/health/db</code> endpoint for operational monitoring and Kubernetes
     * liveness/readiness probes.</p>
     * 
     * <h3>Health Check Strategy</h3>
     * <p>Executes a simple SELECT 1 query to verify:</p>
     * <ul>
     *   <li><strong>Connection Availability:</strong> Connection can be obtained from pool</li>
     *   <li><strong>Database Responsiveness:</strong> PostgreSQL server is responding to queries</li>
     *   <li><strong>Network Connectivity:</strong> Network path to database is functional</li>
     * </ul>
     * 
     * <h3>Health Response Format</h3>
     * <p>Returns health status with details:</p>
     * <pre>
     * {
     *   "status": "UP",
     *   "details": {
     *     "database": "PostgreSQL",
     *     "validationQuery": "SELECT 1"
     *   }
     * }
     * </pre>
     * 
     * <h3>Kubernetes Integration</h3>
     * <p>Health endpoint is used for:</p>
     * <ul>
     *   <li><strong>Liveness Probe:</strong> Restart pod if database connectivity lost</li>
     *   <li><strong>Readiness Probe:</strong> Remove pod from load balancer if unhealthy</li>
     *   <li><strong>Startup Probe:</strong> Delay traffic until database connection established</li>
     * </ul>
     * 
     * @param dataSource HikariCP DataSource to validate connectivity
     * @return HealthIndicator bean for Spring Boot Actuator health checks
     */
    @Bean
    public HealthIndicator databaseHealthIndicator(DataSource dataSource) {
        return () -> {
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("SELECT 1")) {
                
                if (resultSet.next() && resultSet.getInt(1) == 1) {
                    return Health.up()
                            .withDetail("database", "PostgreSQL")
                            .withDetail("validationQuery", "SELECT 1")
                            .withDetail("status", "Connection validated successfully")
                            .build();
                } else {
                    return Health.down()
                            .withDetail("database", "PostgreSQL")
                            .withDetail("error", "Validation query did not return expected result")
                            .build();
                }
            } catch (SQLException e) {
                return Health.down()
                        .withDetail("database", "PostgreSQL")
                        .withDetail("error", e.getMessage())
                        .withDetail("errorCode", e.getErrorCode())
                        .withDetail("sqlState", e.getSQLState())
                        .build();
            }
        };
    }
}
