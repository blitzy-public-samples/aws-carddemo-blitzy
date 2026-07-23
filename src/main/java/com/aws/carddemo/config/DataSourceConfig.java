package com.aws.carddemo.config;

import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * PostgreSQL DataSource configuration for the CardDemo migration (VSAM KSDS -&gt; PostgreSQL).
 *
 * <p>This class owns the single application {@link DataSource}: one JDBC connection pool over
 * PostgreSQL that replaces the z/OS VSAM data substrate &mdash; the eight KSDS clusters originally
 * provisioned by IDCAMS {@code DEFINE CLUSTER} JCL. Every connection setting (URL, username,
 * password, driver class, and pool tuning) is externalized to configuration and bound from the
 * runtime environment; there are <strong>no hardcoded credentials</strong> anywhere in this file
 * (AAP &sect;0.7.1). The rationale for the datasource-ownership and externalization decisions is
 * recorded in {@code docs/decision-log.md}, not in code comments.</p>
 *
 * <p>Origin (lineage): {@code legacy/jcl/ACCTFILE.jcl}, {@code CARDFILE.jcl}, {@code CUSTFILE.jcl},
 * {@code XREFFILE.jcl}, {@code TRANFILE.jcl}, {@code TCATBALF.jcl}, {@code TRANCATG.jcl},
 * {@code TRANTYPE.jcl}, {@code DISCGRP.jcl}, {@code DUSRSECJ.jcl} (IDCAMS DEFINE CLUSTER / REPRO
 * data-init) &mdash; realized relationally by Flyway migrations under
 * {@code src/main/resources/db/migration}. This class is <strong>net-new infrastructure</strong>
 * with no single 1:1 COBOL source and deliberately reproduces none of the JCL logic; it only
 * provides the connection pool those relational migrations and the JPA layer run against.</p>
 *
 * <p>Target database: PostgreSQL 18.4, with a supported floor of PostgreSQL 16.</p>
 *
 * <p>Interaction with Spring Boot auto-configuration:</p>
 * <ul>
 *   <li>Boot's {@code DataSourceAutoConfiguration} creates a {@code DataSource} only when none is
 *       already defined ({@code @ConditionalOnMissingBean}). By declaring the
 *       {@link #dataSource(DataSourceProperties)} bean below, this class intentionally owns the
 *       datasource while still delegating every value to externalized properties.</li>
 *   <li>Flyway is auto-configured by Boot and runs its migrations automatically on startup from the
 *       default classpath location {@code classpath:db/migration} against this {@code DataSource};
 *       no Flyway bean or migration strategy is declared here (declaring one would risk a double
 *       migration and bean conflicts).</li>
 *   <li>JPA/Hibernate is auto-configured against this same {@code DataSource}; no
 *       {@code EntityManagerFactory}, {@code JpaVendorAdapter}, or {@code TransactionManager} bean
 *       is declared here. This class is scoped strictly to the {@code DataSource}.</li>
 * </ul>
 *
 * <p>Required externalized configuration (owned by the resources agent in
 * {@code src/main/resources/application.yml}). This bean's contract is exactly these keys; they are
 * supplied via environment variables at runtime and <strong>must not contain literal secret values
 * in committed files</strong>:</p>
 * <ul>
 *   <li>{@code spring.datasource.url} &mdash; the PostgreSQL connection URL, bound from the
 *       {@code SPRING_DATASOURCE_URL} environment variable.</li>
 *   <li>{@code spring.datasource.username} &mdash; bound from the {@code SPRING_DATASOURCE_USERNAME}
 *       environment variable.</li>
 *   <li>{@code spring.datasource.password} &mdash; bound from the {@code SPRING_DATASOURCE_PASSWORD}
 *       environment variable.</li>
 *   <li>{@code spring.datasource.driver-class-name} &mdash; {@code org.postgresql.Driver}.</li>
 * </ul>
 *
 * <p>Companion keys documented for context but <em>not</em> consumed by this bean (owned elsewhere):
 * {@code spring.jpa.hibernate.ddl-auto=validate} (Flyway owns the schema, so Hibernate only
 * validates it), {@code spring.flyway.enabled=true}, and a {@code C}/{@code POSIX}-collation
 * database so legacy EBCDIC/ASCII sort ordering is preserved (see {@code docs/decision-log.md},
 * AAP &sect;0.6.6).</p>
 */
@Configuration
public class DataSourceConfig {

    /**
     * Builds the single primary {@link DataSource} for the application.
     *
     * <p>Every value &mdash; connection URL, username, password, driver class name, and every
     * HikariCP pool setting &mdash; is bound from configuration (populated from environment
     * variables by the resources agent in {@code application.yml}); nothing is hardcoded here. Two
     * distinct property prefixes are bound, matching Spring Boot's own datasource conventions:</p>
     * <ul>
     *   <li>the connection coordinates come from {@code spring.datasource.*}
     *       ({@code url}/{@code username}/{@code password}/{@code driver-class-name}), and</li>
     *   <li>the pool tuning comes from {@code spring.datasource.hikari.*}
     *       ({@code pool-name}, {@code maximum-pool-size}, {@code minimum-idle}, &hellip;).</li>
     * </ul>
     *
     * <p>The builder is obtained from {@link DataSourceProperties#initializeDataSourceBuilder()}
     * rather than from a bare {@code DataSourceBuilder.create()}. This is deliberate and required
     * for correctness: {@code DataSourceProperties} (auto-registered by Boot's
     * {@code DataSourceAutoConfiguration}) pre-populates the builder from the documented
     * {@code spring.datasource.url}/{@code username}/{@code password}/{@code driver-class-name}
     * keys &mdash; performing the {@code url} to {@code jdbcUrl} mapping that HikariCP requires
     * (HikariCP exposes {@code jdbcUrl} but has no {@code url} setter, so binding a bare builder
     * result would silently drop the URL). The pool type is then pinned explicitly with
     * {@code .type(HikariDataSource.class)} so the concrete pool is a {@link HikariDataSource}
     * and the return value is strongly typed with no wildcard capture.</p>
     *
     * <p><strong>Pool tuning binding (review finding F5):</strong> the method-level
     * {@code @ConfigurationProperties(prefix = "spring.datasource.hikari")} binds the nested
     * {@code spring.datasource.hikari.*} keys directly onto the returned {@link HikariDataSource}
     * instance. The previous prefix {@code "spring.datasource"} did <em>not</em> reach those nested
     * keys, so {@code pool-name}, {@code maximum-pool-size} and {@code minimum-idle} were silently
     * ignored; binding at the {@code spring.datasource.hikari} prefix &mdash; the same prefix Boot's
     * own auto-configuration uses for the pool &mdash; makes every documented pool setting take
     * effect.</p>
     *
     * <p>This bean intentionally replaces Boot's {@code @ConditionalOnMissingBean} auto-configured
     * datasource. Both the auto-configured Flyway migrations and the JPA/Hibernate
     * {@code EntityManagerFactory} consume whichever {@code DataSource} bean is present, so they run
     * against this one. {@link Primary} makes that ownership explicit and avoids ambiguity should a
     * test or other configuration introduce an additional datasource.</p>
     *
     * @param properties the Boot-managed {@code spring.datasource.*} properties (auto-registered by
     *                    {@code DataSourceAutoConfiguration}); supplies the externalized connection
     *                    coordinates and never carries hardcoded values
     * @return the externally configured, HikariCP-backed PostgreSQL {@code DataSource}
     */
    @Bean
    @Primary
    @ConfigurationProperties(prefix = "spring.datasource.hikari")
    public DataSource dataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }
}
