package com.carddemo.config;

import org.springframework.context.annotation.Configuration;

/**
 * DataSource configuration marker for the CardDemo Spring Boot application.
 *
 * <p>This class is <b>intentionally minimal</b>. It contains no {@code @Bean}
 * method declarations because the application defers entirely to Spring Boot 3.2's
 * {@code DataSourceAutoConfiguration} (HikariCP-backed) configured via YAML:
 * <ul>
 *   <li>{@code application.yml} (base — no datasource URL; profile required)</li>
 *   <li>{@code application-dev.yml} (local PostgreSQL 15, pool size 10)</li>
 *   <li>{@code application-prod.yml} (env-var-driven URL/credentials, pool size 20)</li>
 *   <li>{@code application-test.yml} (Testcontainers PostgreSQL for integration tests)</li>
 * </ul>
 *
 * <h2>VSAM-to-PostgreSQL Migration Context (AAP §0.6.2)</h2>
 * <p>The original CardDemo application used 8 VSAM KSDS files defined in
 * {@code app/csd/CARDDEMO.CSD}:
 * <table border="1">
 *   <caption>VSAM-to-PostgreSQL table mapping</caption>
 *   <tr><th>VSAM File</th><th>PostgreSQL Table</th><th>CICS Properties</th></tr>
 *   <tr><td>{@code ACCTDAT}</td><td>{@code accounts}</td><td>KSDS, READ/UPDATE/ADD/DELETE/BROWSE</td></tr>
 *   <tr><td>{@code CARDDAT}</td><td>{@code cards}</td><td>KSDS with CARDAIX alternate index</td></tr>
 *   <tr><td>{@code CARDAIX}</td><td>{@code idx_card_account_id} (B-tree index)</td><td>AIX PATH on CARD-ACCT-ID</td></tr>
 *   <tr><td>{@code CCXREF}</td><td>{@code card_xref}</td><td>KSDS — CARD TO ACCOUNT XREF</td></tr>
 *   <tr><td>{@code CXACAIX}</td><td>{@code idx_xref_account_id} (B-tree index)</td><td>AIX PATH on XREF-ACCT-ID</td></tr>
 *   <tr><td>{@code CUSTDAT}</td><td>{@code customers}</td><td>KSDS — CARDDEMO CUSTOMER DATA</td></tr>
 *   <tr><td>{@code TRANSACT}</td><td>{@code transactions}</td><td>KSDS with TRANSACT.AIX on TRAN-ORIG-TS</td></tr>
 *   <tr><td>{@code USRSEC}</td><td>{@code users}</td><td>KSDS — replaces RACF/plaintext store</td></tr>
 * </table>
 *
 * <p>VSAM CICS file properties from {@code CARDDEMO.CSD}:
 * <ul>
 *   <li>{@code READINTEG(UNCOMMITTED)} → PostgreSQL READ_COMMITTED isolation (Hibernate default for PostgreSQL dialect)</li>
 *   <li>{@code DISPOSITION(SHARE)} → HikariCP connection pool (shared connections, configurable maximum)</li>
 *   <li>{@code RECORDFORMAT(V)} → PostgreSQL row-based storage (Hibernate maps via {@code @Entity})</li>
 *   <li>{@code JOURNAL(NO)} → Application-level JPA auditing via {@code @CreatedDate/@LastModifiedDate} (closes Tech Spec §6.4 audit gap)</li>
 *   <li>{@code RECOVERY(NONE)} → No transactional recovery in CICS; replaced by JPA {@code @Transactional} + PostgreSQL WAL</li>
 *   <li>{@code BACKUPTYPE(STATIC)} → Operational {@code pg_dump} via {@code TransactionBackupJobConfig} (AAP §0.6.14)</li>
 * </ul>
 *
 * <h2>HikariCP Configuration Strategy</h2>
 * <p>HikariCP (bundled with Spring Boot starter-data-jpa) is the connection pool.
 * Configuration is driven by {@code spring.datasource.hikari.*} properties in the
 * active profile YAML:
 * <ul>
 *   <li>{@code maximum-pool-size}: 10 (dev) / 20 (prod)</li>
 *   <li>{@code minimum-idle}: 2 (dev) / 5 (prod)</li>
 *   <li>{@code connection-timeout}: 30000 ms</li>
 *   <li>{@code idle-timeout}: 600000 ms (10 minutes)</li>
 *   <li>{@code max-lifetime}: 1800000 ms (30 minutes)</li>
 *   <li>{@code auto-commit}: false (transactional integrity preserved)</li>
 * </ul>
 *
 * <p>These defaults are appropriate for the demonstration-grade workload
 * (50 accounts, ~311 transactions, 100 TCATBAL records) per AAP §0.7.2.
 *
 * <h2>Why No Explicit {@code @Bean DataSource}?</h2>
 * <p>Spring Boot's {@code DataSourceAutoConfiguration} reads
 * {@code spring.datasource.*} properties and produces a {@code HikariDataSource}
 * automatically. Declaring an explicit {@code @Bean DataSource} here would
 * <i>override</i> the auto-configuration, requiring this class to duplicate all
 * the property binding logic. Keeping this class minimal preserves the
 * separation of concerns: <b>YAML owns configuration values; Spring Boot owns
 * bean construction</b>.
 *
 * <p>If a future requirement demands a custom DataSource (e.g., a separate
 * batch DataSource with a different pool size, or a read-replica DataSource for
 * report queries), it can be added here as a {@code @Bean @Primary} (or
 * {@code @Qualifier}-discriminated) DataSource definition without affecting any
 * other configuration class.
 *
 * <h2>Critical Rules (AAP §0.7.1)</h2>
 * <ul>
 *   <li><b>PR-25</b>: Single monolith — no read-replicas, sharding, or message-queue DataSources</li>
 *   <li><b>PR-26</b>: PostgreSQL + filesystem ONLY — no Aurora-specific or cloud-DB-specific configuration</li>
 *   <li><b>PR-28</b>: Jakarta EE namespace (implicit for Spring Boot 3.x)</li>
 *   <li><b>PR-30</b>: Single-phase delivery — file is self-contained</li>
 * </ul>
 *
 * @see com.carddemo.config.JpaConfig — enables JPA repositories and JPA auditing on this DataSource
 * @see com.carddemo.config.WebConfig — orthogonal web-layer configuration (CORS, Jackson, @EnableAsync)
 * @see com.carddemo.config.OpenApiConfig — orthogonal API documentation configuration
 */
@Configuration
public class DataSourceConfig {
    // Intentionally empty — defers DataSource creation to Spring Boot
    // 3.2's DataSourceAutoConfiguration based on application-{profile}.yml.
}
