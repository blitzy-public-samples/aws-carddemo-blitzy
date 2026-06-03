package com.carddemo.controller;

import com.carddemo.controller.advice.GlobalExceptionHandler;
import com.carddemo.dto.report.ReportRequest;
import com.carddemo.dto.report.ReportType;
import com.carddemo.service.ReportService;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc web-slice tests for {@link ReportController} &mdash; the stateless REST replacement for the
 * legacy CICS report-request program {@code app/cbl/CORPT00C.cbl} (TRANID {@code 'CR00'}).
 *
 * <p>{@code CORPT00C} let an online user pick a report period (Monthly / Yearly / Custom), confirm,
 * then assembled an 80-byte JCL stream and handed it to the mainframe internal reader for
 * asynchronous batch execution by writing each line to the {@code 'JOBS'} extra-partition Transient
 * Data Queue ({@code EXEC CICS WRITEQ TD QUEUE('JOBS')}). Control returned to the user immediately;
 * the report was produced later by the {@code TRANREPT} batch job. In the modernized stack that
 * internal-reader hand-off becomes a single REST operation &mdash; {@code POST /api/reports} &mdash;
 * which delegates to {@code ReportService.submitReport(...)} (annotated {@code @Async}, internally
 * {@code JobLauncher.run(jobRegistry.getJob(...), params)}) and returns {@code 202 Accepted} carrying
 * the launched {@code jobExecutionId} (AAP &sect;0.4.1.1, &sect;0.6.1).</p>
 *
 * <h2>What this slice asserts</h2>
 * <ul>
 *   <li>All three report periods ({@link ReportType#MONTHLY}, {@link ReportType#YEARLY},
 *       {@link ReportType#CUSTOM}) are accepted with {@code 202 Accepted} and delegate to
 *       {@link ReportService#submitReport(ReportRequest)} &mdash; the REST analogue of the
 *       fire-and-forget {@code WRITEQ TD} submission.</li>
 *   <li>The {@code 202} body carries the async-submission contract
 *       ({@code jobExecutionId}, {@code status}, {@code message}, {@code reportType}).</li>
 *   <li>Bean-Validation failures (empty body) and unparseable bodies (malformed JSON) are rejected
 *       with HTTP&nbsp;4xx/400 by {@link GlobalExceptionHandler} <em>before</em> any job is launched
 *       &mdash; the modern equivalent of the CORPT00C on-screen validation messages.</li>
 *   <li>When the {@code @Async} service future completes <em>exceptionally</em>, the controller's
 *       {@code join()}/{@code CompletionException}-unwrap logic surfaces the original cause so
 *       {@link GlobalExceptionHandler} maps it ({@link IllegalArgumentException} &rarr; 400,
 *       {@link IllegalStateException} &rarr; 422).</li>
 * </ul>
 *
 * <h2>Slice configuration (matches the project's controller-test convention)</h2>
 * <ul>
 *   <li>{@link WebMvcTest @WebMvcTest(controllers = ReportController.class)} loads only the
 *       {@code ReportController} web layer; the sole collaborator {@link ReportService} is supplied
 *       as a {@link MockBean}.</li>
 *   <li>{@code excludeFilters} drops the entire {@code com.carddemo.security} package from the
 *       slice's component scan. A {@code @WebMvcTest} slice always registers application
 *       {@code jakarta.servlet.Filter} beans; the production {@code JwtAuthenticationFilter} is a
 *       {@code @Component} extending {@code OncePerRequestFilter} whose collaborator
 *       {@code CustomAuthorityMapper} is not loaded by the slice, so leaving it in scope would fail
 *       the context with an {@code UnsatisfiedDependencyException}.
 *       {@link AutoConfigureMockMvc @AutoConfigureMockMvc(addFilters = false)} only removes filters
 *       from the MockMvc dispatch &mdash; it does not stop the bean from being instantiated &mdash;
 *       so the component-scan exclusion is what actually keeps the context minimal.</li>
 *   <li>{@link Import @Import(GlobalExceptionHandler.class)} wires the {@code @RestControllerAdvice}
 *       so validation/parse failures and unwrapped async causes produce the correct 4xx/4xx
 *       statuses within the slice.</li>
 *   <li>{@link AutoConfigureMockMvc @AutoConfigureMockMvc(addFilters = false)} disables the security
 *       filter chain: {@code ReportController} carries no class-level
 *       {@code @PreAuthorize("hasRole('ADMIN')")} (per AAP &sect;0.7.2 the transaction-report feature
 *       is available to both {@code ROLE_USER} and {@code ROLE_ADMIN}), so this slice focuses on the
 *       HTTP contract rather than authorization. {@code @WithMockUser} and {@code .with(csrf())} are
 *       retained for forward compatibility.</li>
 * </ul>
 *
 * @see ReportController
 * @see ReportService
 * @see ReportRequest
 * @see ReportType
 * @see GlobalExceptionHandler
 */
@WebMvcTest(
        controllers = ReportController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "com\\.carddemo\\.security\\..*"))
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("ReportController web-slice tests (replaces COBOL CORPT00C, TRANID=CR00)")
class ReportControllerTest {

    /**
     * Deterministic job-execution id returned by the mocked {@link ReportService} on the success
     * paths. {@code ReportController} blocks on the service's {@link CompletableFuture} via
     * {@code join()} and echoes this value as {@code jobExecutionId} in the {@code 202} body, so the
     * controller MUST be given a completed future &mdash; an unstubbed mock would return {@code null}
     * and {@code null.join()} would surface as a 500, not the expected 202.
     */
    private static final long JOB_EXECUTION_ID = 1001L;

    /** Auto-configured MockMvc for the {@code ReportController} web slice (security filters disabled). */
    @Autowired
    private MockMvc mockMvc;

    /**
     * Auto-configured Jackson mapper used to serialize {@link ReportRequest} bodies to JSON. The
     * slice's mapper already has the JSR-310 {@code JavaTimeModule} registered (via
     * {@code spring-boot-starter-web} + {@code jackson-datatype-jsr310} on the classpath), so the
     * {@code @JsonFormat("yyyy-MM-dd")} {@link LocalDate} fields serialize as ISO-8601 strings.
     */
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * The sole controller collaborator, replaced by a Mockito mock in the slice context. The real
     * {@code ReportService} owns the {@code @Async} {@code JobLauncher.run(...)} invocation that
     * replaces {@code EXEC CICS WRITEQ TD QUEUE('JOBS')}; here it is stubbed so the controller's HTTP
     * behaviour can be asserted in isolation.
     */
    @MockBean
    private ReportService reportService;

    /**
     * Default success stubbing: every {@code submitReport(...)} call returns a future already
     * completed with {@link #JOB_EXECUTION_ID}. {@code @MockBean} mocks are lenient (they are reset
     * between tests and are not subject to Mockito's {@code UnnecessaryStubbingException}), so the
     * negative-path tests that never reach the service leave this stub harmlessly unused, while the
     * async-failure tests re-stub it in their own bodies.
     */
    @BeforeEach
    void setUp() {
        when(reportService.submitReport(any(ReportRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(JOB_EXECUTION_ID));
    }

    /**
     * Builds a fully valid {@link ReportRequest} fixture.
     *
     * <p>{@code confirmation} is set to the affirmative {@code "Y"} because the real DTO constrains it
     * with {@code @NotBlank @Pattern("[YyNn]")}; omitting it (as a naive fixture would) makes
     * Bean Validation fail with HTTP&nbsp;400 instead of reaching the controller body. This mirrors
     * the CORPT00C confirmation gate ({@code WHEN CONFIRMI = 'Y' OR 'y'}: proceed).
     *
     * @param type  the report period selector ({@link ReportType#MONTHLY}/{@code YEARLY}/{@code CUSTOM})
     * @param start the inclusive report-period start date
     * @param end   the inclusive report-period end date (must be on or after {@code start})
     * @return a valid, ready-to-serialize request fixture
     */
    private ReportRequest reportRequest(ReportType type, LocalDate start, LocalDate end) {
        ReportRequest req = new ReportRequest();
        req.setReportType(type);
        req.setStartDate(start);
        req.setEndDate(end);
        req.setConfirmation("Y");
        return req;
    }

    /**
     * Happy-path and request-validation cases for {@code POST /api/reports} &mdash; the three report
     * periods produce {@code 202 Accepted}, the response carries the async-submission contract, and
     * malformed input is rejected with 4xx before any job is launched.
     */
    @Nested
    @DisplayName("POST /api/reports")
    class SubmitReport {

        @Test
        @DisplayName("Monthly report \u2192 202 Accepted (async JobLauncher delegation)")
        @WithMockUser
        void shouldSubmitMonthlyReport() throws Exception {
            ReportRequest req = reportRequest(ReportType.MONTHLY,
                    LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31));

            mockMvc.perform(post("/api/reports")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    // 202 Accepted: the report runs asynchronously, not synchronously.
                    .andExpect(status().isAccepted());

            // The controller delegated to ReportService.submitReport (which internally calls
            // JobLauncher.run() — the REST replacement for EXEC CICS WRITEQ TD QUEUE('JOBS')).
            verify(reportService).submitReport(any(ReportRequest.class));
        }

        @Test
        @DisplayName("Yearly report \u2192 202 Accepted")
        @WithMockUser
        void shouldSubmitYearlyReport() throws Exception {
            ReportRequest req = reportRequest(ReportType.YEARLY,
                    LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31));

            mockMvc.perform(post("/api/reports")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isAccepted());

            verify(reportService).submitReport(any(ReportRequest.class));
        }

        @Test
        @DisplayName("Custom date range \u2192 202 Accepted")
        @WithMockUser
        void shouldSubmitCustomDateRangeReport() throws Exception {
            ReportRequest req = reportRequest(ReportType.CUSTOM,
                    LocalDate.of(2024, 3, 15), LocalDate.of(2024, 6, 30));

            mockMvc.perform(post("/api/reports")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isAccepted());

            verify(reportService).submitReport(any(ReportRequest.class));
        }

        @Test
        @DisplayName("202 body carries the async-submission contract "
                + "(jobExecutionId/status/message/reportType) \u2014 CORPT00C 'Report submitted...' parity")
        @WithMockUser
        void shouldReturnSubmissionAcknowledgement() throws Exception {
            ReportRequest req = reportRequest(ReportType.MONTHLY,
                    LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31));

            mockMvc.perform(post("/api/reports")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isAccepted())
                    // The launched jobExecutionId is echoed for operator correlation (replaces the
                    // CICS internal-reader job hand-off; CORPT00C returned control immediately).
                    .andExpect(jsonPath("$.jobExecutionId").value((int) JOB_EXECUTION_ID))
                    .andExpect(jsonPath("$.status").value("ACCEPTED"))
                    // The human-readable acknowledgment stands in for CORPT00C's
                    // "Report submitted for printing ..." confirmation message.
                    .andExpect(jsonPath("$.message").exists())
                    .andExpect(jsonPath("$.reportType").value("MONTHLY"));

            verify(reportService).submitReport(any(ReportRequest.class));
        }

        @Test
        @DisplayName("Empty request body \u2192 400 Bad Request (reportType/dates/confirmation required)")
        @WithMockUser
        void shouldReject400OnEmptyBody() throws Exception {
            // "{}" deserializes to an all-null ReportRequest; @NotNull (reportType/startDate/endDate)
            // and @NotBlank (confirmation) all fail, so @Valid raises MethodArgumentNotValidException
            // which GlobalExceptionHandler maps to 400. Per the folder convention we assert the 4xx
            // class only and do NOT assert the service was never called (validation location is an
            // implementation detail).
            mockMvc.perform(post("/api/reports")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().is4xxClientError());
        }

        @Test
        @DisplayName("Malformed JSON \u2192 400 Bad Request (HttpMessageNotReadableException)")
        @WithMockUser
        void shouldReject400OnMalformedJson() throws Exception {
            // An unparseable body raises HttpMessageNotReadableException during argument binding,
            // mapped to 400 ("MALFORMED_REQUEST") by GlobalExceptionHandler before any job launch.
            mockMvc.perform(post("/api/reports")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("not a json"))
                    .andExpect(status().isBadRequest());
        }
    }

    /**
     * Exercises {@code ReportController}'s most distinctive logic: it blocks on the {@code @Async}
     * service's {@link CompletableFuture} with {@code join()} and, on exceptional completion, unwraps
     * the {@code CompletionException} and rethrows the original cause on the request thread so
     * {@link GlobalExceptionHandler} maps it exactly as a synchronously-thrown exception would be.
     */
    @Nested
    @DisplayName("POST /api/reports \u2014 async failure propagation (join/CompletionException unwrap)")
    class AsyncFailurePropagation {

        @Test
        @DisplayName("Async IllegalArgumentException (non-affirmative confirmation) \u2192 400 Bad Request")
        @WithMockUser
        void shouldMapAsyncIllegalArgumentToBadRequest() throws Exception {
            // The service future completes exceptionally with IllegalArgumentException — the path
            // CORPT00C took when CONFIRMI was not 'Y'/'y' (submission cancelled). The controller
            // unwraps the CompletionException from join() and rethrows the cause; GlobalExceptionHandler
            // maps IllegalArgumentException -> 400.
            when(reportService.submitReport(any(ReportRequest.class)))
                    .thenReturn(CompletableFuture.failedFuture(new IllegalArgumentException(
                            "Report submission requires confirmation 'Y' or 'y'; received: 'N'")));

            // 'N' is a valid wire value (@Pattern "[YyNn]"), so the request passes Bean Validation and
            // reaches the (failing) service rather than being rejected at the binding stage.
            ReportRequest req = reportRequest(ReportType.MONTHLY,
                    LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31));
            req.setConfirmation("N");

            mockMvc.perform(post("/api/reports")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isBadRequest());

            verify(reportService).submitReport(any(ReportRequest.class));
        }

        @Test
        @DisplayName("Async IllegalStateException (job launch failure) \u2192 422 Unprocessable Entity")
        @WithMockUser
        void shouldMapAsyncIllegalStateToUnprocessableEntity() throws Exception {
            // The service future completes exceptionally with IllegalStateException — e.g. the report
            // job is not registered in the JobRegistry or the JobLauncher rejected the launch. The
            // controller unwraps and rethrows; GlobalExceptionHandler maps IllegalStateException -> 422.
            when(reportService.submitReport(any(ReportRequest.class)))
                    .thenReturn(CompletableFuture.failedFuture(new IllegalStateException(
                            "Failed to launch report job 'transactionReportJob'")));

            ReportRequest req = reportRequest(ReportType.YEARLY,
                    LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31));

            mockMvc.perform(post("/api/reports")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isUnprocessableEntity());

            verify(reportService).submitReport(any(ReportRequest.class));
        }
    }
}
