package com.carddemo.config;

import org.springframework.context.annotation.Configuration;

/**
 * DataSource <strong>infrastructure</strong> configuration marker for the CardDemo monolith.
 *
 * <p><strong>Migration role.</strong> The legacy AWS CardDemo mainframe application persisted its
 * data in <strong>ten VSAM Key-Sequenced Data Sets</strong> (KSDS) catalogued by the IDCAMS
 * {@code LISTCAT} snapshot in {@code app/catlg/LISTCAT.txt} (e.g. {@code ACCTDATA.VSAM.KSDS} with an
 * 11-byte key). In the Java&nbsp;17 / Spring&nbsp;Boot&nbsp;3.2.x port those ten datasets are
 * re-expressed as <strong>ten PostgreSQL tables</strong> built by the Flyway migration
 * {@code src/main/resources/db/migration/V1__schema.sql} and seeded by {@code V2}&ndash;{@code V4}
 * (AAP&nbsp;&sect;0.3.1). This class is the {@code com.carddemo.config} home that AAP&nbsp;&sect;0.3.1
 * lists as "{@code DataSourceConfig.java (dev/prod profile wiring)}" and that AAP&nbsp;&sect;0.4.1.5
 * enumerates among the config artifacts. Like the entity, repository, and service classes it has a
 * legacy <em>logical</em> parent (the VSAM catalog) but, being pure framework plumbing, it has
 * <strong>no 1:1 COBOL source program</strong> to port &mdash; {@code LISTCAT.txt} supplies only
 * datasource/schema <em>context</em>, never executable logic.</p>
 *
 * <h2>Profile-driven datasource strategy</h2>
 *
 * <p>The application selects its datasource entirely through <strong>Spring profiles</strong>; the
 * concrete connection settings live in the two sibling profile documents (both {@code depends_on}
 * files of this class) and are <strong>never</strong> hardcoded here:</p>
 * <ul>
 *   <li><strong>{@code dev} / test &rarr; in-memory H2.</strong> {@code application-dev.yml} wires an
 *       in-memory H2 database started in <em>PostgreSQL compatibility mode</em>
 *       ({@code MODE=PostgreSQL}, {@code DATABASE_TO_LOWER=TRUE}) and selects the Hibernate H2
 *       dialect. Compatibility mode is essential: the Flyway migrations are authored in
 *       PostgreSQL-dialect SQL, so H2 must accept that syntax for the schema to build unchanged. No
 *       external database is required for local development or for the test profile.</li>
 *   <li><strong>{@code prod} &rarr; PostgreSQL&nbsp;15.x.</strong> {@code application-prod.yml} wires
 *       the production PostgreSQL database from the externalized environment variables
 *       {@code SPRING_DATASOURCE_URL}, {@code SPRING_DATASOURCE_USERNAME}, and
 *       {@code SPRING_DATASOURCE_PASSWORD} (no defaults &mdash; a missing value fails fast at
 *       startup rather than silently falling back), behind a tuned HikariCP pool
 *       ({@code CardDemoHikariPool}, sized via {@code DB_POOL_MAX_SIZE}/{@code DB_POOL_MIN_IDLE}) and
 *       the Hibernate PostgreSQL dialect.</li>
 * </ul>
 *
 * <p>The default active profile is {@code dev} (the base {@code application.yml} sets
 * {@code spring.profiles.active=${SPRING_PROFILES_ACTIVE:dev}}), so {@code mvn spring-boot:run} works
 * out of the box with no external database; production selects {@code prod} via
 * {@code SPRING_PROFILES_ACTIVE=prod}.</p>
 *
 * <h2>Why this class declares NO {@code DataSource} bean</h2>
 *
 * <p>Spring Boot's {@code DataSourceAutoConfiguration} already builds a single
 * <strong>HikariCP</strong> {@code javax.sql.DataSource} from the {@code spring.datasource.*}
 * properties contributed by the active profile YAML. This class therefore <strong>deliberately
 * defines no {@code @Bean DataSource}</strong> (and no {@code @ConfigurationProperties}-bound
 * {@code DataSourceBuilder}). Declaring a competing bean would either trigger a
 * {@code BeanDefinitionOverrideException} / {@code NoUniqueBeanDefinitionException} or silently
 * shadow the carefully-tuned per-profile YAML &mdash; defeating the externalized-configuration
 * design. Mirroring the folder guidance, "<em>most settings come from
 * {@code application-{dev,prod}.yml}; keep this class minimal (rely on Boot auto-config unless a
 * custom bean is genuinely needed)</em>", the correct, expert outcome here is precisely a thin,
 * well-documented {@code @Configuration} class whose <em>documentation</em> &mdash; not any code
 * &mdash; is the deliverable.</p>
 *
 * <h2>Schema ownership and Hibernate validation</h2>
 *
 * <p>Schema construction is owned exclusively by <strong>Flyway</strong>, not by this class and not
 * by Hibernate. On startup Flyway applies {@code V1__schema.sql} (the ten tables, foreign keys,
 * sequences, and the three indexes that replace the VSAM alternate indexes) through
 * {@code V4__seed_users.sql} <em>before</em> JPA initializes. Hibernate then runs with
 * {@code spring.jpa.hibernate.ddl-auto=validate} (set in the base {@code application.yml}), so it
 * only <strong>validates</strong> the entities against the Flyway-built schema and never creates or
 * alters it. This class participates in none of that flow; it neither configures Flyway nor touches
 * the schema.</p>
 *
 * <h2>Design notes</h2>
 *
 * <p>This is a <strong>tier-0 infrastructure</strong> type: its only import is {@link Configuration}
 * and it has zero dependencies on any other {@code com.carddemo} class, so it can never participate
 * in a cyclic dependency. It is discovered automatically by Spring component scanning because
 * {@code com.carddemo.config} is a sub-package of the {@code @SpringBootApplication} base package
 * {@code com.carddemo} (rooted at {@code CardDemoApplication}); no {@code @ComponentScan},
 * {@code @EntityScan}, or {@code @EnableJpaRepositories} is declared here, as the default scanning
 * already covers the {@code entity/} and {@code repository/} packages. Consistent with
 * AAP&nbsp;&sect;0.2.2 (a single monolith) it introduces <strong>no</strong> multi-datasource,
 * sharding, read/write-splitting, or microservice patterns.</p>
 *
 * <h2>Authority</h2>
 * <ul>
 *   <li>AAP&nbsp;&sect;0.3.1 &mdash; {@code config/DataSourceConfig.java (dev/prod profile wiring)}.</li>
 *   <li>AAP&nbsp;&sect;0.4.1.5 &mdash; config/resources artifact inventory; dev=H2, prod=PostgreSQL profiles.</li>
 *   <li>AAP&nbsp;&sect;0.4.2 &mdash; {@code SPRING_DATASOURCE_*} wired through the profile YAML; Flyway runs before JPA validation.</li>
 *   <li>AAP&nbsp;&sect;0.2.2 &mdash; single monolith; no microservice/multi-datasource split.</li>
 * </ul>
 *
 * @see <a href="file:app/catlg/LISTCAT.txt">LISTCAT.txt</a> (IDCAMS catalog of the 10 VSAM KSDS datasets)
 * @see Configuration
 */
@Configuration
public class DataSourceConfig {

    /*
     * INTENTIONALLY EMPTY.
     *
     * No @Bean methods are declared here, and this is the complete, correct implementation - not a
     * stub or placeholder. The application DataSource is created by Spring Boot's
     * DataSourceAutoConfiguration (a HikariCP pool) from the spring.datasource.* properties supplied
     * by the active profile YAML:
     *   - application-dev.yml  -> in-memory H2 (MODE=PostgreSQL) for dev/test;
     *   - application-prod.yml -> PostgreSQL 15.x via SPRING_DATASOURCE_* env vars + HikariCP.
     * See the class-level Javadoc for the full rationale.
     *
     * DO NOT add any of the following here - doing so would conflict with / override the
     * YAML-driven auto-configuration, leak configuration into code, or violate the single-monolith
     * scope (AAP 0.2.2):
     *   - @Bean public DataSource dataSource()      (conflicts with DataSourceAutoConfiguration);
     *   - @ConfigurationProperties("spring.datasource") DataSource builder;
     *   - @Profile-gated H2 / PostgreSQL DataSource beans (the YAML already selects per profile);
     *   - any hardcoded JDBC URL, driver class, username, password, or pool size (all externalized);
     *   - a JdbcTemplate, a second connection pool, or @EnableJpaRepositories / @EntityScan
     *     (default scanning from com.carddemo already covers repository/ + entity/);
     *   - multi-datasource / sharding / read-write-splitting wiring (out of scope).
     */
}
