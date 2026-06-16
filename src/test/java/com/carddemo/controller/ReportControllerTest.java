package com.carddemo.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc REST-contract test for {@link ReportController}, the Spring Boot re-expression of the
 * legacy CICS online report-request program {@code app/cbl/CORPT00C.cbl} ("Print Transaction
 * reports by submitting batch job from online", transaction {@code CR00}).
 *
 * <h2>What this test pins (parity with CORPT00C, AAP &sect;0.3.2)</h2>
 * <p>On the mainframe, {@code CORPT00C} offered three mutually-exclusive report selections on its
 * BMS screen &mdash; <strong>Monthly</strong>, <strong>Yearly</strong>, and a keyed
 * <strong>Custom</strong> start/end range &mdash; then assembled a JCL job stream and handed it to
 * the CICS internal reader for <em>asynchronous</em> execution (an {@code EXEC CICS WRITEQ TD} to an
 * extra-partition transient-data queue, {@code SUBMIT-JOB-TO-INTRDR}). The transaction returned
 * immediately, confirming the job had been <em>submitted</em> rather than rendering the report
 * inline. The migrated REST surface preserves that fire-and-forget contract exactly:
 * {@code POST /reports} validates the request, submits the Spring Batch {@code transactionReportJob}
 * through {@code JobLauncher}, and returns <strong>HTTP&nbsp;202&nbsp;Accepted</strong> carrying a
 * {@code ReportResponse} whose {@code jobExecutionId} is the asynchronous report reference.</p>
 *
 * <p>The cases below verify, against the <strong>real production stack</strong> (controller,
 * service, validation, batch job, and the security filter chain), only the observable
 * <strong>HTTP contract</strong>:</p>
 * <ul>
 *   <li><strong>Happy paths</strong> (MONTHLY / YEARLY / CUSTOM) &rarr; {@code 202 Accepted} with a
 *       numeric {@code jobExecutionId}, the echoed {@code reportType}, and a present {@code status};</li>
 *   <li><strong>Validation failures</strong> &rarr; {@code 400 Bad Request} for an unrecognized or
 *       missing {@code reportType} (Bean Validation {@code @Pattern}/{@code @NotNull}) and for a
 *       CUSTOM request that omits the dates (service-level {@code ValidationException});</li>
 *   <li><strong>Authentication</strong> &rarr; {@code 401 Unauthorized} when the caller is anonymous
 *       (the security filter chain rejects the request before the controller runs).</li>
 * </ul>
 *
 * <h2>Why a full-context {@code @SpringBootTest} (not a sliced {@code @WebMvcTest})</h2>
 * <p>{@code ReportService} launches a <em>real</em> Spring Batch job via the auto-configured
 * {@code JobLauncher}. The {@code test} profile ({@code src/test/resources/application-test.yml})
 * deliberately enables the batch metadata schema ({@code spring.batch.jdbc.initialize-schema=always},
 * creating the {@code BATCH_*} tables on H2) while disabling auto-run
 * ({@code spring.batch.job.enabled=false}). That is exactly what lets {@code ReportService} launch the
 * job on demand inside the test. A sliced web test would mock the service and prove nothing about the
 * end-to-end submission, so the full application context is loaded and the endpoint is exercised over
 * HTTP/JSON.</p>
 *
 * <h2>Synchronous launcher &rArr; assert presence, never the terminal status value</h2>
 * <p>Spring Boot's auto-configured {@code JobLauncher} runs synchronously (a {@code SyncTaskExecutor}),
 * so {@code jobLauncher.run(...)} returns a finished {@code JobExecution} &mdash; {@code COMPLETED} or,
 * over the minimal test dataset, possibly {@code FAILED} &mdash; <em>without throwing</em>. The
 * controller therefore returns {@code 202 + jobExecutionId} regardless of the job's outcome. These
 * tests assert that {@code $.status} is <em>present</em> but never assert its <em>value</em>: the
 * terminal status is environment-dependent and is explicitly out of contract.</p>
 *
 * <h2>Test-class conventions (folder requirements)</h2>
 * <ul>
 *   <li>{@link SpringBootTest @SpringBootTest} + {@link AutoConfigureMockMvc @AutoConfigureMockMvc} +
 *       {@link ActiveProfiles @ActiveProfiles}{@code ("test")} &mdash; full context against seeded H2.</li>
 *   <li>Class-level {@link WithMockUser @WithMockUser}{@code (username = "USER0001", roles = {"USER"})}
 *       &mdash; {@code /reports} carries no {@code @PreAuthorize}, so any authenticated user suffices;
 *       a single anonymous test overrides this with {@link WithAnonymousUser @WithAnonymousUser}.</li>
 *   <li><strong>NOT</strong> {@code @Transactional} &mdash; Spring Batch writes its {@code BATCH_*}
 *       metadata in its own {@code REQUIRES_NEW} transaction (a test-managed rollback would not reach
 *       it), and the read-only report job leaves no business-table state to clean up.</li>
 *   <li>CSRF is disabled in {@code SecurityConfig} for this stateless JSON API, so requests carry
 *       <strong>no</strong> {@code .with(csrf())}.</li>
 *   <li>No wildcard imports; static imports only for {@code post}, {@code status}, {@code jsonPath}.</li>
 * </ul>
 *
 * <p>No personally identifiable information (PII) appears in any test data &mdash; no CVV, SSN, card
 * number, or password values are used (AAP &sect;0.6.8, &sect;0.7.1).</p>
 *
 * @see ReportController
 * @see com.carddemo.service.ReportService
 * @see com.carddemo.dto.ReportRequest
 * @see com.carddemo.dto.ReportResponse
 * @see <a href="file:app/cbl/CORPT00C.cbl">CORPT00C.cbl</a>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(username = "USER0001", roles = {"USER"})
class ReportControllerTest {

    /** Absolute path of the single report-submission endpoint (no {@code /api} prefix). */
    private static final String REPORTS_ENDPOINT = "/reports";

    /**
     * The MockMvc entry point, auto-configured with the full Spring Security filter chain (because
     * {@code spring-security-test} is on the classpath). Injected by the container.
     */
    @Autowired
    private MockMvc mockMvc;

    // =============================================================================================
    // Phase 1 — happy paths: POST /reports -> 202 Accepted + jobExecutionId
    // =============================================================================================

    /**
     * MONTHLY submission &rarr; {@code 202 Accepted} with an asynchronous job reference.
     *
     * <p>The legacy program derived the MONTHLY window automatically from the current date, so the
     * client supplies only {@code reportType} (no dates). The service resolves the effective range,
     * launches {@code transactionReportJob}, and returns the {@code JobExecution} reference. We assert
     * the HTTP contract: {@code 202}, a numeric {@code jobExecutionId}, the echoed {@code reportType},
     * and the presence (only) of {@code status}.</p>
     */
    @Test
    @DisplayName("POST /reports MONTHLY -> 202 Accepted with numeric jobExecutionId and echoed reportType")
    void submitMonthlyReport_returns202WithJobReference() throws Exception {
        mockMvc.perform(post(REPORTS_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reportType\":\"MONTHLY\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobExecutionId").exists())
                .andExpect(jsonPath("$.jobExecutionId").isNumber())
                .andExpect(jsonPath("$.reportType").value("MONTHLY"))
                .andExpect(jsonPath("$.status").exists());
    }

    /**
     * YEARLY submission &rarr; {@code 202 Accepted} with an asynchronous job reference.
     *
     * <p>Same shape as the MONTHLY case; the service derives the Jan&nbsp;1&ndash;Dec&nbsp;31 window of
     * the current year and echoes {@code reportType = "YEARLY"}.</p>
     */
    @Test
    @DisplayName("POST /reports YEARLY -> 202 Accepted with numeric jobExecutionId and echoed reportType")
    void submitYearlyReport_returns202WithJobReference() throws Exception {
        mockMvc.perform(post(REPORTS_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reportType\":\"YEARLY\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobExecutionId").exists())
                .andExpect(jsonPath("$.jobExecutionId").isNumber())
                .andExpect(jsonPath("$.reportType").value("YEARLY"))
                .andExpect(jsonPath("$.status").exists());
    }

    /**
     * CUSTOM submission with a valid, correctly-ordered range &rarr; {@code 202 Accepted}.
     *
     * <p>For CUSTOM the operator-supplied {@code startDate}/{@code endDate} are used verbatim (after
     * {@code CSUTLDTC}-equivalent validation), so beyond the standard 202 + {@code jobExecutionId}
     * checks we also assert the response echoes the requested {@code reportType} and the effective
     * {@code startDate}/{@code endDate} unchanged.</p>
     */
    @Test
    @DisplayName("POST /reports CUSTOM with valid dates -> 202 Accepted, echoes the supplied range")
    void submitCustomReport_withValidDates_returns202WithJobReference() throws Exception {
        mockMvc.perform(post(REPORTS_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reportType\":\"CUSTOM\",\"startDate\":\"2024-01-01\",\"endDate\":\"2024-01-31\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobExecutionId").exists())
                .andExpect(jsonPath("$.jobExecutionId").isNumber())
                .andExpect(jsonPath("$.reportType").value("CUSTOM"))
                .andExpect(jsonPath("$.startDate").value("2024-01-01"))
                .andExpect(jsonPath("$.endDate").value("2024-01-31"))
                .andExpect(jsonPath("$.status").exists());
    }

    // =============================================================================================
    // Phase 2 — validation failures: POST /reports -> 400 Bad Request
    // =============================================================================================

    /**
     * Unrecognized {@code reportType} &rarr; {@code 400 Bad Request}.
     *
     * <p>{@code "WEEKLY"} is not one of {@code MONTHLY|YEARLY|CUSTOM}, so the
     * {@code @Pattern} constraint on {@code ReportRequest.reportType} fails during {@code @Valid}
     * binding. Spring raises {@code MethodArgumentNotValidException}, which {@code GlobalExceptionHandler}
     * maps to {@code 400} before the service or any batch launch is reached.</p>
     */
    @Test
    @DisplayName("POST /reports with unrecognized reportType (WEEKLY) -> 400 Bad Request (@Pattern)")
    void submitReport_withInvalidReportType_returns400() throws Exception {
        mockMvc.perform(post(REPORTS_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reportType\":\"WEEKLY\"}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * Missing {@code reportType} &rarr; {@code 400 Bad Request}.
     *
     * <p>An empty JSON object supplies no {@code reportType}; the {@code @NotNull} constraint fails
     * (note {@code @Pattern} treats {@code null} as valid per the Bean Validation spec, which is why
     * {@code @NotNull} is also present on the field). The resulting
     * {@code MethodArgumentNotValidException} maps to {@code 400}.</p>
     */
    @Test
    @DisplayName("POST /reports with missing reportType ({}) -> 400 Bad Request (@NotNull)")
    void submitReport_withMissingReportType_returns400() throws Exception {
        mockMvc.perform(post(REPORTS_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * CUSTOM with no dates &rarr; {@code 400 Bad Request}.
     *
     * <p>The {@code reportType} itself is valid ({@code CUSTOM} passes {@code @Pattern}), so binding
     * succeeds and the request reaches {@code ReportService}. There, the cross-field rule &mdash;
     * "CUSTOM requires both {@code startDate} and {@code endDate}" &mdash; which cannot be expressed as
     * a static field annotation, raises a {@code ValidationException} that maps to {@code 400}. This
     * verifies the service-layer half of the validation contract (distinct from the Bean Validation
     * cases above).</p>
     */
    @Test
    @DisplayName("POST /reports CUSTOM with missing dates -> 400 Bad Request (service ValidationException)")
    void submitCustomReport_withMissingDates_returns400() throws Exception {
        mockMvc.perform(post(REPORTS_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reportType\":\"CUSTOM\"}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * CUSTOM with a reversed range ({@code startDate} after {@code endDate}) &rarr; {@code 202 Accepted}.
     *
     * <p>Both dates are present and individually valid, so the request passes Bean Validation and the
     * per-date {@code CSUTLDTC}-equivalent checks. {@code CORPT00C} (the source of truth, AAP
     * &sect;0.7.3 "actual COBOL governs") validated the Start Date and End Date <em>independently</em>
     * and imposed <strong>no</strong> {@code start <= end} ordering rule, so the migrated service
     * submits the job and the endpoint returns {@code 202 Accepted}, echoing the requested range
     * verbatim (reversed). There is no non-COBOL ordering guard to reproduce.</p>
     */
    @Test
    @DisplayName("POST /reports CUSTOM with startDate after endDate -> 202 Accepted (no COBOL ordering rule)")
    void submitCustomReport_withReversedDateRange_returns202EchoingRange() throws Exception {
        mockMvc.perform(post(REPORTS_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reportType\":\"CUSTOM\",\"startDate\":\"2024-02-01\",\"endDate\":\"2024-01-01\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobExecutionId").exists())
                .andExpect(jsonPath("$.jobExecutionId").isNumber())
                .andExpect(jsonPath("$.reportType").value("CUSTOM"))
                .andExpect(jsonPath("$.startDate").value("2024-02-01"))
                .andExpect(jsonPath("$.endDate").value("2024-01-01"))
                .andExpect(jsonPath("$.status").exists());
    }

    // =============================================================================================
    // Phase 3 — authentication: POST /reports while anonymous -> 401 Unauthorized
    // =============================================================================================

    /**
     * Anonymous caller &rarr; {@code 401 Unauthorized}.
     *
     * <p>{@link WithAnonymousUser @WithAnonymousUser} overrides the class-level
     * {@link WithMockUser @WithMockUser} for this method. With no authenticated principal, the security
     * filter chain's {@code anyRequest().authenticated()} rule denies the request and the inline
     * {@code AuthenticationEntryPoint} in {@code SecurityConfig} responds {@code 401} &mdash; before the
     * controller (or any validation/batch launch) executes. The request body is a valid MONTHLY payload
     * precisely to prove the rejection is driven by authentication, not by request content.</p>
     */
    @Test
    @WithAnonymousUser
    @DisplayName("POST /reports while anonymous -> 401 Unauthorized (rejected before controller)")
    void submitReport_whenAnonymous_returns401() throws Exception {
        mockMvc.perform(post(REPORTS_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reportType\":\"MONTHLY\"}"))
                .andExpect(status().isUnauthorized());
    }
}
