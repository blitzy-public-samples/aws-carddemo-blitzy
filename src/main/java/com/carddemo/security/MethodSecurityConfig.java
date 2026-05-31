package com.carddemo.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * Activates Spring Security 6.x method-level authorization annotations
 * ({@code @PreAuthorize}, {@code @PostAuthorize}, {@code @PreFilter},
 * {@code @PostFilter}) across the entire CardDemo application context.
 *
 * <p><b>This configuration is the linchpin that closes the documented
 * programmatic authorization gap (Tech Spec &sect;6.4 / refactoring rule PR-18).</b>
 * PR-18 mandates: <i>"Method-level {@code @PreAuthorize} enforcement enabled via
 * {@code MethodSecurityConfig}."</i> Without this class on the classpath and in
 * the application context, the {@code @PreAuthorize("hasRole('ADMIN')")}
 * annotations placed on {@code UserController} and {@code BatchAdminController}
 * methods would be silently ignored — the security advisor that intercepts
 * annotated invocations is only registered when method security is explicitly
 * enabled here.
 *
 * <h2>The original gap (closed by this configuration)</h2>
 * In the legacy mainframe system, the user-administration programs
 * {@code COUSR00C.cbl} (list users), {@code COUSR01C.cbl} (add user),
 * {@code COUSR02C.cbl} (update user), and {@code COUSR03C.cbl} (delete user)
 * contained <b>zero programmatic authorization checks</b>. The only thing
 * preventing a non-administrator from reaching them was menu routing in
 * {@code COADM01C.cbl} — the admin-only menu, which dispatched to those programs
 * via {@code EXEC CICS XCTL PROGRAM(...)}. Because authorization lived solely in
 * the menu and never inside the target programs themselves, a malicious actor
 * who invoked the user-admin CICS transaction IDs (e.g. {@code CU00}) directly —
 * bypassing the {@code COADM01C} menu — gained unauthorized access to the
 * user-administration functions.
 *
 * <p>In a stateless REST environment the exposure is even greater: clients
 * (curl, Postman, scripts, or hostile callers) can target any endpoint URL
 * directly, so authorization <b>must</b> be enforced programmatically at the
 * method boundary rather than relying on navigation flow. Annotating the
 * administrative controllers with {@code @PreAuthorize("hasRole('ADMIN')")} and
 * activating that enforcement via this class replaces the lost menu-routing
 * protection with an explicit, non-bypassable check.
 *
 * <h2>Beneficiary endpoints</h2>
 * <ul>
 *   <li>{@code UserController} — every endpoint is annotated
 *       {@code @PreAuthorize("hasRole('ADMIN')")}, replacing the unauthenticated
 *       {@code COUSR00C}&ndash;{@code COUSR03C} routing so that only callers with
 *       {@code ROLE_ADMIN} may list, create, update, or delete users.</li>
 *   <li>{@code BatchAdminController} — its batch-launch endpoints
 *       (e.g. {@code POST /api/admin/jobs/{jobName}/launch}) are annotated
 *       {@code @PreAuthorize("hasRole('ADMIN')")} so that only administrators may
 *       trigger Spring Batch jobs.</li>
 * </ul>
 *
 * <h2>Spring Security 6.x note</h2>
 * This class uses {@code @EnableMethodSecurity(prePostEnabled = true)}, the
 * Spring Security 6.x annotation that <b>replaces the deprecated Spring Security
 * 5.x {@code @EnableGlobalMethodSecurity(prePostEnabled = true)}</b>. The
 * attribute settings are:
 * <ul>
 *   <li>{@code prePostEnabled = true} — activates {@code @PreAuthorize} and
 *       {@code @PostAuthorize} (and the {@code @PreFilter}/{@code @PostFilter}
 *       expression filters). This is the only behaviour CardDemo relies on.</li>
 *   <li>{@code securedEnabled} — left at its default of {@code false}; the legacy
 *       {@code @Secured} annotation is not used anywhere in this project.</li>
 *   <li>{@code jsr250Enabled} — left at its default of {@code false}; the JSR-250
 *       {@code @RolesAllowed} annotation is not used anywhere in this project.</li>
 * </ul>
 *
 * <p>This class carries no fields, dependencies, or methods. A configuration
 * class whose sole purpose is to host {@code @EnableMethodSecurity} is the
 * idiomatic marker pattern; the annotation alone wires the method-security
 * interceptor into the application context. It is discovered automatically by
 * the {@code @ComponentScan} performed by {@code CardDemoApplication}
 * (package {@code com.carddemo}), so no manual registration is required.
 *
 * @see org.springframework.security.access.prepost.PreAuthorize
 * @see org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
 */
@Configuration
@EnableMethodSecurity(prePostEnabled = true)
public class MethodSecurityConfig {
    // Marker configuration — no body required.
    // The @EnableMethodSecurity(prePostEnabled = true) annotation alone registers
    // the authorization advisor that enforces @PreAuthorize / @PostAuthorize
    // application-wide (closing the COUSR00C-COUSR03C programmatic-auth gap).
}
