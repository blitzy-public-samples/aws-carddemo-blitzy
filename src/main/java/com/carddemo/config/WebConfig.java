package com.carddemo.config;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;
import java.util.TimeZone;

/**
 * Web layer configuration for the CardDemo Spring Boot application.
 *
 * <p>This {@code @Configuration} class declares three orthogonal beans/behaviors
 * required by the application that are NOT provided by Spring Boot
 * auto-configuration alone:
 * <ol>
 *   <li><b>{@link CorsConfigurationSource} bean</b> &mdash; automatically consumed by
 *       {@code com.carddemo.security.SecurityConfig} via
 *       {@code http.cors(Customizer.withDefaults())}. Without this bean,
 *       browser CORS preflight ({@code OPTIONS}) requests fail and the
 *       Spring Security filter chain rejects all cross-origin requests.</li>
 *   <li><b>{@link Jackson2ObjectMapperBuilderCustomizer} bean</b> &mdash;
 *       defense-in-depth for AAP rule PR-16 ({@code BigDecimal} everywhere for
 *       money). Reinforces {@code WRITE_BIGDECIMAL_AS_PLAIN} so monetary values
 *       serialize as plain decimal ({@code "100.00"}) and NEVER scientific
 *       notation ({@code "1.0E2"}). Also explicitly registers
 *       {@link JavaTimeModule} for ISO-8601 {@link java.time.LocalDate} /
 *       {@link java.time.LocalDateTime} serialization with disabled timestamp
 *       output.</li>
 *   <li><b>{@code @EnableAsync}</b> &mdash; activates {@code @Async} processing for
 *       {@code com.carddemo.service.ReportService.submitReportJob()} (replaces
 *       {@code EXEC CICS WRITEQ TD QUEUE('JOBS')} from {@code CORPT00C}).</li>
 * </ol>
 *
 * <h2>CORS Strategy</h2>
 * <p>For the demonstration-grade deployment, all origins are allowed and
 * standard HTTP methods are accepted. Production deployments should override
 * {@link #corsConfigurationSource()} or replace it with a property-driven
 * implementation that reads allowed origins from
 * {@code application-prod.yml}. The current bean configures:
 * <ul>
 *   <li>{@code allowedOriginPatterns}: {@code ["*"]} (uses patterns to permit
 *       credentials; cannot use literal {@code "*"} with {@code allowCredentials})</li>
 *   <li>{@code allowedMethods}: {@code GET, POST, PUT, DELETE, OPTIONS, PATCH}</li>
 *   <li>{@code allowedHeaders}: {@code ["*"]} (includes {@code Authorization}
 *       and {@code Content-Type})</li>
 *   <li>{@code exposedHeaders}: {@code Authorization}, {@code Content-Disposition}
 *       (for file downloads such as statement PDFs/HTML)</li>
 *   <li>{@code allowCredentials}: {@code true}</li>
 *   <li>{@code maxAge}: 3600 seconds (1 hour preflight cache)</li>
 * </ul>
 *
 * <h2>Jackson Strategy (PR-16 &mdash; CRITICAL)</h2>
 * <p>Even though {@code application.yml} already configures
 * {@code spring.jackson.generator.write-bigdecimal-as-plain: true}, the
 * customizer here reinforces the setting programmatically. This defense-in-
 * depth ensures that:
 * <ul>
 *   <li>A future profile override cannot silently disable plain-decimal output</li>
 *   <li>Custom code that constructs an {@link com.fasterxml.jackson.databind.ObjectMapper}
 *       manually still benefits if it uses Spring's builder</li>
 *   <li>The {@link JavaTimeModule} is explicitly registered (Spring Boot does
 *       auto-register it, but explicitness aids debugging)</li>
 *   <li>{@code WRITE_DATES_AS_TIMESTAMPS} is disabled (dates serialize as
 *       ISO-8601 strings, NOT epoch milliseconds)</li>
 *   <li>The default timezone is UTC (matches database storage)</li>
 * </ul>
 *
 * <h2>Async Strategy</h2>
 * <p>{@code @EnableAsync} activates Spring's proxy-based {@code @Async}
 * annotation processing. The single async use case is
 * {@code ReportService.submitReportJob()} which launches Spring Batch jobs in
 * a background thread so the HTTP request returns 202 Accepted immediately.
 * The default {@code SimpleAsyncTaskExecutor} is acceptable for the demo-grade
 * workload; production hardening would replace it with a bounded
 * {@code ThreadPoolTaskExecutor} via a dedicated {@code AsyncConfigurer}
 * implementation.
 *
 * <h2>Critical Rules (AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li><b>PR-16</b>: {@code BigDecimal} JSON via {@code WRITE_BIGDECIMAL_AS_PLAIN=true}</li>
 *   <li><b>PR-25</b>: Single monolith &mdash; single web stack</li>
 *   <li><b>PR-26</b>: No cloud-native services (no S3 origins in CORS)</li>
 *   <li><b>PR-28</b>: Jakarta EE namespace (consistent with Spring Boot 3.x)</li>
 *   <li><b>PR-29</b>: Constructor injection (N/A &mdash; no fields)</li>
 *   <li><b>PR-30</b>: Single-phase delivery</li>
 * </ul>
 *
 * @see com.carddemo.security.SecurityConfig consumes
 *      {@link #corsConfigurationSource()} via {@code http.cors(Customizer.withDefaults())}
 * @see com.carddemo.service.ReportService uses {@code @Async} activated by
 *      {@code @EnableAsync} declared on this class
 * @see com.carddemo.config.OpenApiConfig orthogonal API documentation
 * @see com.carddemo.config.JpaConfig orthogonal persistence configuration
 * @see com.carddemo.config.DataSourceConfig orthogonal DataSource marker
 */
@Configuration
@EnableAsync
public class WebConfig implements WebMvcConfigurer {

    /**
     * Provides the {@link CorsConfigurationSource} bean consumed automatically by
     * Spring Security's {@code http.cors(Customizer.withDefaults())} (declared in
     * {@code com.carddemo.security.SecurityConfig}).
     *
     * <p><b>CRITICAL</b>: This bean MUST exist. If it is missing, Spring Security's
     * {@code cors(Customizer.withDefaults())} cannot locate a CORS configuration
     * source, causing all browser-originated requests with cross-origin headers
     * (including preflight {@code OPTIONS}) to fail.
     *
     * <p>Configuration for demo-grade deployment:
     * <ul>
     *   <li>All origin patterns allowed ({@code "*"}) &mdash; restrict per environment in
     *       production via property-driven overrides</li>
     *   <li>Standard REST methods: {@code GET, POST, PUT, DELETE, OPTIONS, PATCH}</li>
     *   <li>All request headers accepted (includes {@code Authorization} for JWT
     *       and {@code Content-Type} for JSON)</li>
     *   <li>Response headers exposed: {@code Authorization} (for refresh tokens),
     *       {@code Content-Disposition} (for statement file downloads)</li>
     *   <li>Credentials allowed ({@code allowCredentials=true}) &mdash; uses
     *       {@code allowedOriginPatterns} (not literal {@code "*"} which is
     *       incompatible with credentials per CORS spec)</li>
     *   <li>Preflight cache: 3600 seconds</li>
     * </ul>
     *
     * @return the CORS configuration source registered under URL pattern {@code /**}
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        // allowedOriginPatterns (NOT allowedOrigins) is mandatory when
        // allowCredentials=true: the CORS spec forbids a literal "*" origin
        // alongside credentials, and Spring's pattern API is the supported
        // workaround that still echoes back the caller's Origin header.
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of(
                "GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        config.setAllowedHeaders(List.of("*"));
        // Expose Authorization (token refresh) and Content-Disposition (so that
        // CORS-restricted browsers can read the filename of downloaded statement
        // files emitted by StatementGenerationJobConfig).
        config.setExposedHeaders(List.of("Authorization", "Content-Disposition"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    /**
     * Customizes the Spring-managed Jackson {@code ObjectMapper} to enforce
     * AAP rule PR-16 (BigDecimal for money) and ISO-8601 date/time output.
     *
     * <p>This is defense-in-depth on top of {@code application.yml} settings:
     * <ul>
     *   <li>{@link SerializationFeature#WRITE_BIGDECIMAL_AS_PLAIN} enabled &mdash; money
     *       values like {@code 100.00} are serialized as plain decimal, NEVER
     *       scientific notation ({@code 1.0E2}). This is CRITICAL because COBOL
     *       PIC S9(10)V99 COMP-3 fields preserve exact cents and any rounding via
     *       float would corrupt the value.</li>
     *   <li>{@link SerializationFeature#WRITE_DATES_AS_TIMESTAMPS} disabled &mdash; dates
     *       serialize as ISO-8601 strings ({@code "2024-01-15T10:30:00.000Z"}) NOT
     *       epoch milliseconds.</li>
     *   <li>{@link JavaTimeModule} registered explicitly &mdash; provides serializers for
     *       {@link java.time.LocalDate}, {@link java.time.LocalDateTime},
     *       {@link java.time.OffsetDateTime}, {@link java.time.Instant}.</li>
     *   <li>Default {@link TimeZone} set to {@code UTC} &mdash; matches database storage
     *       and prevents server-locale-based formatting drift.</li>
     * </ul>
     *
     * <p><b>Note on {@code @SuppressWarnings("deprecation")}:</b> Jackson 2.15
     * marks {@link SerializationFeature#WRITE_BIGDECIMAL_AS_PLAIN} as deprecated,
     * yet it remains the effective, fully-functional mechanism for plain
     * {@code BigDecimal} serialization on the standard {@code ObjectMapper} and
     * is the exact feature mapped by the
     * {@code spring.jackson.serialization.write-bigdecimal-as-plain} property.
     * Because AAP rule PR-16 mandates this behavior and no non-deprecated
     * equivalent exists for value (non-tree) serialization, the deprecation is
     * suppressed at the narrowest possible (method) scope rather than altered.
     *
     * @return a Jackson builder customizer applied to the auto-configured ObjectMapper
     */
    @Bean
    @SuppressWarnings("deprecation") // PR-16: WRITE_BIGDECIMAL_AS_PLAIN is spec-mandated; deprecated in Jackson 2.15 but still the effective plain-decimal mechanism
    public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
        return builder -> builder
                .featuresToEnable(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN)
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .modules(new JavaTimeModule())
                .timeZone(TimeZone.getTimeZone("UTC"));
    }
}
