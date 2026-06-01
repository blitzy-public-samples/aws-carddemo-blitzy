package com.carddemo.controller;

import com.carddemo.controller.advice.GlobalExceptionHandler;
import com.carddemo.security.MethodSecurityConfig;
import com.carddemo.security.UserDetailsServiceImpl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.configuration.JobRegistry;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.NoSuchJobException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc slice test that proves the operational batch-launch endpoint
 * {@code POST /api/admin/jobs/{jobName}/launch} enforces
 * {@code @PreAuthorize("hasRole('ADMIN')")} (refactoring rule <b>PR-18</b>).
 *
 * <p>This is the <strong>second cornerstone PR-18 test</strong> (alongside
 * {@code UserControllerTest}). Together the two close the documented programmatic
 * authorization gap that existed in the legacy {@code COUSR00C}&ndash;{@code COUSR03C}
 * user-administration programs (Tech Spec &sect;6.4 / AAP &sect;0.6.9). Where the legacy
 * mainframe relied solely on menu routing in {@code COADM01C} to keep non-administrators
 * out of privileged functions, the stateless REST API must enforce authorization
 * programmatically because any client (curl, Postman, a hostile script) can target a URL
 * directly. {@link com.carddemo.controller.BatchAdminController} is especially high-risk:
 * it can launch <em>any</em> registered Spring Batch job by name &mdash; including
 * {@code userSeedingJob} (which re-BCrypts the default users' passwords back to
 * {@code "PASSWORD"}) and {@code transactionBackupJob} (which shells out to {@code pg_dump}).
 *
 * <h2>Why this slice keeps Spring Security active (no {@code addFilters = false})</h2>
 * <p>The point of these tests is authorization enforcement, so the security filter chain and
 * method-security advisor MUST be live:</p>
 * <ul>
 *   <li>{@link WebMvcTest} loads only the {@link BatchAdminController} web layer.</li>
 *   <li>{@link Import @Import(MethodSecurityConfig.class)} activates
 *       {@code @EnableMethodSecurity(prePostEnabled = true)} so the class-level
 *       {@code @PreAuthorize("hasRole('ADMIN')")} on the controller is actually applied
 *       &mdash; without it the annotation is silently ignored. An explicit {@code @Import}
 *       is honored even though the security package is otherwise excluded from the slice's
 *       component scan (see below).</li>
 *   <li>{@link Import @Import(GlobalExceptionHandler.class)} provides the
 *       {@code @RestControllerAdvice} that maps an {@code AccessDeniedException} raised by
 *       method security to {@code 403 Forbidden}, enabling the {@code USER}&rarr;403
 *       assertions.</li>
 * </ul>
 *
 * <h2>Why {@code excludeFilters} drops the {@code com.carddemo.security} package</h2>
 * <p>A {@code @WebMvcTest} slice always registers application {@code jakarta.servlet.Filter}
 * beans. The production {@code JwtAuthenticationFilter} is a {@code @Component} that extends
 * {@code OncePerRequestFilter}; left untouched it would be instantiated by this slice and,
 * because its collaborator {@code CustomAuthorityMapper} (a plain {@code @Component}, not an
 * MVC stereotype) is not loaded by the slice, the application context would fail to start
 * with an {@code UnsatisfiedDependencyException}. Excluding the whole
 * {@code com.carddemo.security} package from the component scan via a
 * {@link FilterType#REGEX REGEX} {@link ComponentScan.Filter} keeps the context minimal. The
 * {@code MethodSecurityConfig} and {@code UserDetailsServiceImpl} beans the tests genuinely
 * need are supplied explicitly &mdash; the former via {@code @Import}, the latter via
 * {@code @MockBean} &mdash; so the exclusion does not remove them. With no custom
 * {@code SecurityFilterChain} bean present, Spring Boot's default chain applies
 * ({@code anyRequest().authenticated()}), so an authenticated {@code USER} passes the filter
 * and is then denied by method security (403), while an anonymous caller is rejected at the
 * filter chain itself (4xx) before the handler method ever runs.</p>
 *
 * <h2>Mocked collaborators</h2>
 * <ul>
 *   <li>{@link JobLauncher} &mdash; the strongest PR-18 proof in every non-admin test is
 *       {@code verify(jobLauncher, never()).run(any(), any())}: even if some HTTP-layer
 *       bypass existed, the launcher must never have been invoked for a non-admin caller.</li>
 *   <li>{@link JobRegistry} &mdash; stubbed to return a mock {@link Job} for the happy path
 *       and to throw {@link NoSuchJobException} for the unknown-job case.</li>
 *   <li>{@link UserDetailsServiceImpl} &mdash; mocked purely to satisfy the security
 *       infrastructure's {@code UserDetailsService} requirement so the context loads.</li>
 * </ul>
 *
 * <h2>Note on {@link NoSuchJobException}</h2>
 * <p>The exception thrown by {@code JobRegistry.getJob(String)} (inherited from
 * {@code JobLocator}) is {@code org.springframework.batch.core.launch.NoSuchJobException}.
 * That is the type imported here and declared by {@link BatchAdminController}; it is mapped
 * to {@code 404 Not Found} both by the controller's local {@code @ExceptionHandler} and by
 * {@link GlobalExceptionHandler}.</p>
 *
 * @see BatchAdminController
 * @see MethodSecurityConfig
 * @see GlobalExceptionHandler
 */
@WebMvcTest(
        controllers = BatchAdminController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "com\\.carddemo\\.security\\..*"))
@Import({GlobalExceptionHandler.class, MethodSecurityConfig.class})
class BatchAdminControllerTest {

    /** Auto-configured MockMvc with the Spring Security filter chain active. */
    @Autowired
    private MockMvc mockMvc;

    /** Mocked synchronous Spring Batch launcher; never invoked for non-admin callers. */
    @MockBean
    private JobLauncher jobLauncher;

    /** Mocked name-based job locator; stubbed per test for happy-path / unknown-job cases. */
    @MockBean
    private JobRegistry jobRegistry;

    /**
     * Mocked {@code UserDetailsService} bean required by the security infrastructure so the
     * {@code @WebMvcTest} application context loads successfully with method security active.
     */
    @MockBean
    private UserDetailsServiceImpl userDetailsService;

    /** A registered job stand-in returned by {@link #jobRegistry} on the happy path. */
    private Job mockJob;

    /** A completed-launch result returned by {@link #jobLauncher} on the happy path. */
    private JobExecution mockExecution;

    @BeforeEach
    void setUp() {
        mockJob = mock(Job.class);
        when(mockJob.getName()).thenReturn("transactionPostingJob");

        // Spring Batch 5.x constructor: JobExecution(JobInstance, Long executionId, JobParameters).
        // A freshly constructed execution has a non-null BatchStatus (STARTING) and a null
        // startTime; the controller null-guards startTime when assembling its response body.
        mockExecution = new JobExecution(
                new JobInstance(1L, "transactionPostingJob"),
                42L,
                new JobParameters());
    }

    /**
     * Core PR-18 enforcement matrix for {@code POST /api/admin/jobs/{jobName}/launch}:
     * anonymous and {@code USER} callers are rejected (and never reach the launcher), while an
     * {@code ADMIN} caller can launch a registered job (202) or receives 404 for an unknown one.
     */
    @Nested
    @DisplayName("POST /api/admin/jobs/{jobName}/launch — PR-18 enforcement")
    class LaunchJobAuthorization {

        @Test
        @DisplayName("PR-18: Anonymous user → 4xx (CRITICAL — unauthenticated MUST NEVER launch jobs)")
        @WithAnonymousUser
        void shouldRejectAnonymous() throws Exception {
            mockMvc.perform(post("/api/admin/jobs/{jobName}/launch", "transactionPostingJob")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().is4xxClientError());

            // CRITICAL: the launcher MUST NOT have been invoked for an unauthenticated caller.
            verify(jobLauncher, never()).run(any(), any());
        }

        @Test
        @DisplayName("PR-18: USER role → 403 Forbidden (CRITICAL — non-admin MUST NEVER launch jobs)")
        @WithMockUser(username = "USER0001", roles = {"USER"})
        void shouldReject403ForNonAdmin() throws Exception {
            mockMvc.perform(post("/api/admin/jobs/{jobName}/launch", "transactionPostingJob")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isForbidden());

            // CRITICAL: neither the registry lookup nor the launcher may run for a USER caller —
            // method security denies BEFORE the handler body executes.
            verify(jobLauncher, never()).run(any(), any());
            verify(jobRegistry, never()).getJob(any());
        }

        @Test
        @DisplayName("PR-18: ADMIN role + valid job name → 202 Accepted (job launched)")
        @WithMockUser(username = "ADMIN001", roles = {"ADMIN"})
        void shouldAllowAdminLaunch() throws Exception {
            when(jobRegistry.getJob("transactionPostingJob")).thenReturn(mockJob);
            when(jobLauncher.run(eq(mockJob), any(JobParameters.class))).thenReturn(mockExecution);

            mockMvc.perform(post("/api/admin/jobs/{jobName}/launch", "transactionPostingJob")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.jobName").value("transactionPostingJob"));

            verify(jobRegistry).getJob("transactionPostingJob");
            verify(jobLauncher).run(eq(mockJob), any(JobParameters.class));
        }

        @Test
        @DisplayName("ADMIN role + invalid job name → 404 Not Found (NoSuchJobException)")
        @WithMockUser(username = "ADMIN001", roles = {"ADMIN"})
        void shouldReturn404ForUnknownJob() throws Exception {
            when(jobRegistry.getJob("unknownJob")).thenThrow(new NoSuchJobException("unknownJob"));

            mockMvc.perform(post("/api/admin/jobs/{jobName}/launch", "unknownJob")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isNotFound());

            // An unknown job name must short-circuit before any launch attempt.
            verify(jobLauncher, never()).run(any(), any());
        }
    }

    /**
     * Defense-in-depth: enumerate each operationally dangerous job and prove a {@code USER}
     * caller is denied (403) for <em>every</em> one of them, with the launcher never invoked.
     *
     * <p>Because the class-level {@code @PreAuthorize("hasRole('ADMIN')")} guards the single
     * launch endpoint regardless of the {@code jobName} path value, the protection is uniform;
     * these per-job cases would nonetheless catch any regression that weakened enforcement for
     * a particular job. The two most sensitive jobs are called out explicitly:
     * {@code userSeedingJob} (re-BCrypts default passwords) and {@code transactionBackupJob}
     * (shells out to {@code pg_dump}).</p>
     */
    @Nested
    @DisplayName("Per-job PR-18 enforcement — USER role denied for ALL job names")
    class PerJobAuthorization {

        @Test
        @DisplayName("USER cannot launch transactionPostingJob")
        @WithMockUser(roles = {"USER"})
        void shouldDenyTransactionPostingJob() throws Exception {
            mockMvc.perform(post("/api/admin/jobs/{jobName}/launch", "transactionPostingJob")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isForbidden());
            verify(jobLauncher, never()).run(any(), any());
        }

        @Test
        @DisplayName("USER cannot launch interestCalculationJob")
        @WithMockUser(roles = {"USER"})
        void shouldDenyInterestCalculationJob() throws Exception {
            mockMvc.perform(post("/api/admin/jobs/{jobName}/launch", "interestCalculationJob")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isForbidden());
            verify(jobLauncher, never()).run(any(), any());
        }

        @Test
        @DisplayName("USER cannot launch statementGenerationJob")
        @WithMockUser(roles = {"USER"})
        void shouldDenyStatementGenerationJob() throws Exception {
            mockMvc.perform(post("/api/admin/jobs/{jobName}/launch", "statementGenerationJob")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isForbidden());
            verify(jobLauncher, never()).run(any(), any());
        }

        @Test
        @DisplayName("USER cannot launch userSeedingJob (which would reset passwords)")
        @WithMockUser(roles = {"USER"})
        void shouldDenyUserSeedingJob() throws Exception {
            // CRITICAL: userSeedingJob can re-BCrypt the default users' passwords back to
            // "PASSWORD". A non-admin caller MUST NEVER be able to launch it.
            mockMvc.perform(post("/api/admin/jobs/{jobName}/launch", "userSeedingJob")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isForbidden());
            verify(jobLauncher, never()).run(any(), any());
        }

        @Test
        @DisplayName("USER cannot launch transactionBackupJob (which executes pg_dump)")
        @WithMockUser(roles = {"USER"})
        void shouldDenyTransactionBackupJob() throws Exception {
            // CRITICAL: transactionBackupJob shells out to pg_dump. A non-admin caller MUST
            // NEVER be able to launch it.
            mockMvc.perform(post("/api/admin/jobs/{jobName}/launch", "transactionBackupJob")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isForbidden());
            verify(jobLauncher, never()).run(any(), any());
        }
    }

    /**
     * Functional check (ADMIN role) that the JSON request body is transformed into Spring Batch
     * {@link JobParameters} and forwarded to the {@link JobLauncher}.
     *
     * <p>The nested class is named {@code JobParameterPassing} (rather than
     * {@code JobParameters}) so the simple name {@code JobParameters} continues to resolve to
     * the imported {@code org.springframework.batch.core.JobParameters} type throughout this
     * file; a nested type sharing that name would shadow the single-type import. The assertion
     * matches {@code any(JobParameters.class)} rather than asserting on specific parameter
     * values, since exact parameter construction is the controller/service concern, not this
     * authorization-focused slice.</p>
     */
    @Nested
    @DisplayName("Job parameter passing (ADMIN role)")
    class JobParameterPassing {

        @Test
        @DisplayName("ADMIN with job parameters in body → params passed to JobLauncher")
        @WithMockUser(roles = {"ADMIN"})
        void shouldPassJobParameters() throws Exception {
            when(jobRegistry.getJob("interestCalculationJob")).thenReturn(mockJob);
            when(jobLauncher.run(eq(mockJob), any(JobParameters.class))).thenReturn(mockExecution);

            // PARM='2022071800' equivalent — supplied as a JSON body parameter.
            String paramsJson = "{\"tranDate\":\"2022071800\"}";

            mockMvc.perform(post("/api/admin/jobs/{jobName}/launch", "interestCalculationJob")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(paramsJson))
                    .andExpect(status().isAccepted());

            verify(jobLauncher).run(eq(mockJob), any(JobParameters.class));
        }
    }
}
