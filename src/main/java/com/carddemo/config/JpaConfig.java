package com.carddemo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAccessor;
import java.util.Optional;

/**
 * JPA configuration for the CardDemo Spring Boot application.
 *
 * <p>This is the MOST FOUNDATIONAL configuration class in
 * {@code com.carddemo.config}. It enables Spring Data JPA repository scanning
 * for ALL 12 repositories in {@code com.carddemo.repository} and activates
 * JPA auditing (closing the audit-log gap documented in Tech Spec &sect;6.4).
 *
 * <h2>Repository Scanning</h2>
 * <p>{@code @EnableJpaRepositories(basePackages = "com.carddemo.repository")}
 * registers all 12 Spring Data JPA repository interfaces:
 * <ul>
 *   <li>AccountRepository, CardRepository, CardXrefRepository, CustomerRepository</li>
 *   <li>TransactionRepository, DailyTransactionRepository, RejectedTransactionRepository</li>
 *   <li>TransactionCategoryBalanceRepository, DisclosureGroupRepository</li>
 *   <li>TransactionTypeRepository, TransactionCategoryRepository, UserRepository</li>
 * </ul>
 *
 * <p>The explicit {@code basePackages} attribute is preferred over Spring
 * Boot's auto-configuration default (scan from {@code @SpringBootApplication}'s
 * package) because:
 * <ul>
 *   <li>It documents the intent and scope</li>
 *   <li>It prevents accidental scanning of test repositories</li>
 *   <li>It positions the codebase for future multi-DataSource scenarios</li>
 * </ul>
 *
 * <h2>JPA Auditing (AAP &sect;0.6.12 &mdash; Audit Gap Closure)</h2>
 * <p>{@code @EnableJpaAuditing} activates the {@code AuditingEntityListener}
 * mechanism that auto-populates:
 * <ul>
 *   <li>{@code @CreatedDate} &mdash; entity creation timestamp</li>
 *   <li>{@code @LastModifiedDate} &mdash; entity last-update timestamp</li>
 *   <li>{@code @CreatedBy} &mdash; user ID who created the entity</li>
 *   <li>{@code @LastModifiedBy} &mdash; user ID who last modified the entity</li>
 * </ul>
 *
 * <p>This addresses the documented audit-log gap from Tech Spec &sect;6.4: the
 * original COBOL CardDemo had {@code JOURNAL(NO)} on every VSAM file defined in
 * {@code app/csd/CARDDEMO.CSD} (ACCTDAT, CARDDAT, CCXREF, CUSTDAT, TRANSACT,
 * USRSEC, and the cross-reference files) and no application-level audit
 * tracking. The modernized application captures audit metadata on every
 * entity via the listener registered through this class.
 *
 * <h2>Auditor Resolution Strategy</h2>
 * <p>{@link #auditorAware()} reads
 * {@code SecurityContextHolder.getContext().getAuthentication()}:
 * <ul>
 *   <li>If an authenticated user is present (online REST requests with
 *       valid JWT), returns the user's principal name (e.g., {@code ADMIN001},
 *       {@code USER0001})</li>
 *   <li>If no authentication is available (batch jobs, Flyway migrations,
 *       system-initiated operations), returns {@code "SYSTEM"}</li>
 *   <li>If authentication exists but is anonymous (Spring's default
 *       {@code AnonymousAuthenticationToken}), returns {@code "SYSTEM"}</li>
 * </ul>
 *
 * <h2>Timestamp Strategy</h2>
 * <p>{@link #auditingDateTimeProvider()} returns
 * {@code LocalDateTime.now(ZoneOffset.UTC)}. UTC matches:
 * <ul>
 *   <li>Database storage: {@code spring.jpa.properties.hibernate.jdbc.time_zone: UTC}
 *       in {@code application.yml}</li>
 *   <li>JSON serialization: Jackson configured with UTC timezone in
 *       {@link com.carddemo.config.WebConfig#jacksonCustomizer()}</li>
 * </ul>
 *
 * <p>Externalizing the provider as a bean enables deterministic testing &mdash;
 * tests can override it with a fixed-time {@link DateTimeProvider} to assert
 * exact audit timestamps.
 *
 * <h2>Transaction Management</h2>
 * <p>This class does NOT declare {@code @EnableTransactionManagement}.
 * Spring Boot's {@code JpaTransactionManagerAutoConfiguration} auto-creates a
 * {@code JpaTransactionManager} bean once the JPA classpath is detected.
 * {@code @Transactional} annotations on services and batch processors work
 * out of the box (AAP PR-24).
 *
 * <h2>Optimistic Locking (Orthogonal &mdash; PR-22)</h2>
 * <p>This class does NOT configure optimistic locking directly. Entities own
 * the {@code @Version} annotations that drive optimistic concurrency control.
 * The JPA infrastructure enabled here (Hibernate via Spring Boot
 * starter-data-jpa) is what processes those annotations at flush time.
 *
 * <h2>Critical Rules (AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li><b>PR-22</b>: Optimistic locking via {@code @Version} &mdash; entities own; this config enables</li>
 *   <li><b>PR-24</b>: {@code @Transactional} boundaries &mdash; Spring Boot auto-configures</li>
 *   <li><b>PR-25</b>: Single persistence unit, single repository scan</li>
 *   <li><b>PR-26</b>: PostgreSQL only &mdash; no NoSQL repositories</li>
 *   <li><b>PR-28</b>: Jakarta EE namespace ({@code jakarta.persistence.*} in entities; not directly referenced here)</li>
 *   <li><b>PR-29</b>: Constructor injection (N/A &mdash; no fields)</li>
 *   <li><b>PR-30</b>: Single-phase delivery</li>
 * </ul>
 *
 * @see com.carddemo.repository the package scanned by {@code @EnableJpaRepositories}
 * @see com.carddemo.entity entities annotated with audit fields and
 *      {@code @EntityListeners(AuditingEntityListener.class)}
 * @see com.carddemo.config.DataSourceConfig DataSource consumed by the JPA EntityManager
 * @see com.carddemo.config.WebConfig orthogonal web-layer configuration
 * @see com.carddemo.config.OpenApiConfig orthogonal API documentation
 * @see com.carddemo.security.SecurityConfig populates SecurityContextHolder consumed by AuditorAware
 */
@Configuration
@EnableJpaRepositories(basePackages = "com.carddemo.repository")
@EnableJpaAuditing(
    auditorAwareRef = "auditorAware",
    dateTimeProviderRef = "auditingDateTimeProvider")
public class JpaConfig {

    /**
     * Resolves the current auditor (user ID) for JPA auditing
     * ({@code @CreatedBy}, {@code @LastModifiedBy}).
     *
     * <p>The lookup strategy:
     * <ol>
     *   <li>Read {@link SecurityContextHolder#getContext()}'s {@link Authentication}</li>
     *   <li>If the authentication is null, not authenticated, or anonymous,
     *       return {@code "SYSTEM"} (covers batch jobs, Flyway migrations,
     *       and system-initiated operations)</li>
     *   <li>Otherwise return the authentication's {@code name} (the user ID
     *       like {@code ADMIN001}, {@code USER0001})</li>
     * </ol>
     *
     * <p>This bean intentionally NEVER returns {@link Optional#empty()}. Spring
     * Batch jobs run without an HTTP request, so {@code SecurityContextHolder}
     * is empty; returning empty would leave {@code @CreatedBy} /
     * {@code @LastModifiedBy} columns NULL and defeat the audit purpose.
     * {@code "SYSTEM"} is the floor. The {@code "anonymousUser"} guard rejects
     * the principal that Spring Security's anonymous filter installs for
     * unauthenticated requests, which is meaningless for audit purposes.
     *
     * <p>The constant {@code "SYSTEM"} is a 6-character string that fits within
     * the 8-character user-ID column width inherited from the COBOL
     * {@code SEC-USR-ID PIC X(08)} field &mdash; preserving record-length fidelity
     * with the original USRSEC layout (AAP PR-13).
     *
     * <p>The bean name {@code auditorAware} is referenced by string in this
     * class's {@code @EnableJpaAuditing(auditorAwareRef = "auditorAware")} and
     * therefore MUST NOT be renamed.
     *
     * @return an {@link AuditorAware} that always resolves to a non-null user ID
     */
    @Bean
    public AuditorAware<String> auditorAware() {
        return () -> {
            Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null
                || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
                return Optional.of("SYSTEM");
            }
            return Optional.of(authentication.getName());
        };
    }

    /**
     * Provides UTC {@link LocalDateTime} timestamps for JPA auditing
     * ({@code @CreatedDate}, {@code @LastModifiedDate}).
     *
     * <p>UTC matches the database storage timezone configured in
     * {@code application.yml} via
     * {@code spring.jpa.properties.hibernate.jdbc.time_zone: UTC}, ensuring
     * timestamps are consistent regardless of server locale. The same UTC
     * convention is applied at the JSON boundary by
     * {@link com.carddemo.config.WebConfig#jacksonCustomizer()}.
     *
     * <p>Externalizing the provider as a Spring bean enables deterministic
     * testing. Test configurations can supply a fixed-time
     * {@link DateTimeProvider} to assert exact audit timestamps without
     * race-condition risk.
     *
     * <p>The return type is {@code Optional<TemporalAccessor>} (rather than the
     * narrower {@code Optional<LocalDateTime>}) to match the
     * {@link DateTimeProvider#getNow()} contract exactly. The bean name
     * {@code auditingDateTimeProvider} is referenced by string in this class's
     * {@code @EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")}
     * and therefore MUST NOT be renamed.
     *
     * @return a {@link DateTimeProvider} that returns the current UTC time
     */
    @Bean
    public DateTimeProvider auditingDateTimeProvider() {
        return () -> Optional.<TemporalAccessor>of(
            LocalDateTime.now(ZoneOffset.UTC));
    }
}
