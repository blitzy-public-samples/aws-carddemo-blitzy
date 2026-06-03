package com.carddemo.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.configuration.JobRegistry;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.NoSuchJobException;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRestartException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Operational administrative REST endpoint for launching Spring Batch
 * {@link Job}s by name.
 *
 * <p><strong>No direct COBOL source program.</strong> This controller is a new modernization
 * artifact that consolidates the implicit "job submission" pattern that was scattered across the
 * legacy mainframe deployment. In the original CardDemo system, online programs submitted batch
 * work to an internal reader through a Transient Data Queue — most visibly
 * {@code app/cbl/CORPT00C.cbl}, whose {@code SUBMIT-JOB-TO-INTRDR} paragraph assembled an 80-byte
 * JCL stream and issued {@code EXEC CICS WRITEQ TD QUEUE('JOBS')} to enqueue a report job for
 * asynchronous batch execution. In the modernized stack that mechanism is replaced by a direct,
 * stateless REST API that launches any registered Spring Batch job:
 * {@code POST /api/admin/jobs/{jobName}/launch}.</p>
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code GET  /api/admin/jobs} &mdash; list the names of all jobs registered with the
 *       {@link JobRegistry} (alphabetically sorted) together with a {@code count}, so callers can
 *       discover which job names are launchable; returns {@code 200 OK}.</li>
 *   <li>{@code POST /api/admin/jobs/{jobName}/launch} &mdash; launch the named job with optional
 *       {@link JobParameters}; returns {@code 202 Accepted} with execution metadata.</li>
 * </ul>
 *
 * <p>The {@code GET} list endpoint is an operational discovery aid for the launch endpoint: it
 * reflects the live {@link JobRegistry} contents (populated by {@code BatchConfig}) and adds no
 * business behavior of its own. It is required by the downstream batch tier so that the launchable
 * job names need not be hard-coded by clients rather than discovered at runtime.</p>
 *
 * <h2>Runtime collaborators (constructor-injected by type)</h2>
 * <ul>
 *   <li>{@link JobLauncher} &mdash; the Spring Boot 3.2 auto-configured, synchronous launcher.
 *       The application context also contains a second, non-{@code @Primary} async launcher named
 *       {@code asyncJobLauncher} (declared by {@code com.carddemo.batch.BatchConfig}); by-type
 *       injection here resolves to the auto-configured {@code jobLauncher} bean because the
 *       constructor parameter name {@code jobLauncher} matches that bean's name (parameter names
 *       are retained by the {@code -parameters} compiler flag configured in {@code pom.xml}).</li>
 *   <li>{@link JobRegistry} &mdash; the Spring Boot 3.2 auto-configured, name-based job locator.
 *       {@code BatchConfig}'s {@code JobRegistryBeanPostProcessor} auto-registers every
 *       {@code @Bean Job} so they become discoverable here by name.</li>
 * </ul>
 *
 * <h2>Refactoring rules enforced</h2>
 * <ul>
 *   <li><strong>PR-18</strong> (closes the documented programmatic-auth gap): the class-level
 *       {@link PreAuthorize @PreAuthorize("hasRole('ADMIN')")} restricts every endpoint to callers
 *       holding {@code ROLE_ADMIN}. Enforcement is activated by
 *       {@code com.carddemo.security.MethodSecurityConfig} ({@code @EnableMethodSecurity}); without
 *       that configuration this annotation would be silently ignored. Because the modernized API is
 *       stateless and any client can target the URL directly, authorization must be enforced
 *       programmatically at the method boundary rather than relying on legacy menu routing.</li>
 *   <li><strong>PR-28</strong> (Jakarta EE 10 baseline): no {@code javax.*} imports are used.</li>
 *   <li><strong>PR-29</strong> (constructor injection only): dependencies are injected through the
 *       Lombok {@link RequiredArgsConstructor}-generated constructor over {@code final} fields &mdash;
 *       no {@code @Autowired} field injection.</li>
 * </ul>
 *
 * <h2>Design notes</h2>
 * <ul>
 *   <li><strong>Stateless:</strong> no {@code HttpSession} and no server-side state are held between
 *       requests; each launch is self-contained.</li>
 *   <li><strong>Idempotency / re-runs (AAP &sect;0.6.3 / PR-12):</strong> Spring Batch identifies a
 *       {@code JobInstance} by its identifying {@link JobParameters}. To honor the documented
 *       same-parameter idempotency contract, the {@code JobInstance} identity is left to the
 *       caller-supplied parameters only (e.g. {@code tranDate}); the {@code launchTimestamp} this
 *       controller appends via {@link JobParametersBuilder#addLong(String, Long, boolean)} is
 *       <strong>non-identifying</strong> ({@code identifying=false}) and serves audit/observability
 *       purposes alone. Consequently, re-launching a job with identical caller parameters resolves
 *       to the same instance and raises {@link JobInstanceAlreadyCompleteException} (mapped to
 *       {@code 409 JOB_ALREADY_COMPLETE} by the {@code GlobalExceptionHandler}) instead of creating a
 *       duplicate instance, while a changed parameter value yields a new instance/execution.</li>
 *   <li><strong>Centralized exception handling:</strong> the Spring Batch launch exceptions are
 *       mapped by the application-wide {@code com.carddemo.controller.advice.GlobalExceptionHandler}
 *       &mdash; {@link NoSuchJobException} &rarr; {@code 404}, the launch-conflict exceptions
 *       ({@link JobInstanceAlreadyCompleteException}, {@link JobExecutionAlreadyRunningException},
 *       {@link JobRestartException}) &rarr; {@code 409}, and {@link JobParametersInvalidException}
 *       &rarr; {@code 400}. Centralizing them guarantees the same structured {@code ErrorResponse}
 *       envelope ({@code status, code, message, path, timestamp}) that every other endpoint returns,
 *       instead of a controller-local ad hoc shape.</li>
 * </ul>
 *
 * @see com.carddemo.batch.BatchConfig supplies/auto-configures the {@link JobLauncher} and
 *      {@link JobRegistry} this controller depends on
 * @see com.carddemo.security.MethodSecurityConfig activates {@code @PreAuthorize} enforcement
 */
@RestController
@RequestMapping("/api/admin/jobs")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('ADMIN')")
@Tag(
    name = "Batch Administration",
    description = "Administrative endpoints for launching Spring Batch jobs (ADMIN role required)")
@SecurityRequirement(name = "bearerAuth")
public class BatchAdminController {

    /**
     * Name of the <strong>non-identifying</strong> {@code launchTimestamp} job parameter appended on
     * every launch for audit/observability. Because it is added with {@code identifying=false}, it is
     * excluded from {@code JobInstance} identity, so the documented same-parameter idempotency
     * contract (AAP &sect;0.6.3 / PR-12) is preserved: a job is identified by its caller-supplied
     * parameters only, and re-launching with identical parameters raises
     * {@link JobInstanceAlreadyCompleteException} (mapped to {@code 409 JOB_ALREADY_COMPLETE}) rather
     * than creating a duplicate instance.
     */
    private static final String LAUNCH_TIMESTAMP_PARAM = "launchTimestamp";

    /** Placeholder used in logs when a caller-supplied job parameter name suggests secret material. */
    private static final String REDACTED_LOG_VALUE = "<redacted>";

    /**
     * Spring Boot 3.2 auto-configured (synchronous, primary) {@link JobLauncher}. Resolved by type;
     * the parameter name {@code jobLauncher} disambiguates it from the additional non-primary
     * {@code asyncJobLauncher} bean declared by {@code BatchConfig}.
     */
    private final JobLauncher jobLauncher;

    /**
     * Spring Boot 3.2 auto-configured {@link JobRegistry} that maps job names to {@link Job} beans.
     * Used to look up a job for launching by name (see {@link #launchJob(String, Map)}).
     */
    private final JobRegistry jobRegistry;

    /**
     * Launches the named Spring Batch {@link Job} with optional {@link JobParameters} supplied as a
     * flat map of string key/value pairs.
     *
     * <p>This is the REST replacement for the legacy {@code EXEC CICS WRITEQ TD QUEUE('JOBS')}
     * job-submission pattern (see {@code app/cbl/CORPT00C.cbl}). On success the method returns
     * {@code 202 Accepted} together with execution metadata; the actual completion semantics depend
     * on the configured {@link JobLauncher} (the auto-configured launcher runs synchronously, so the
     * returned status reflects the terminal or in-progress state at return time).</p>
     *
     * <p>A {@code launchTimestamp} parameter is always appended for audit/observability, but it is
     * <strong>non-identifying</strong> ({@code identifying=false}), so it does not participate in
     * {@code JobInstance} identity. The instance is therefore identified solely by the
     * caller-supplied parameters, which preserves the documented same-parameter idempotency contract
     * (AAP &sect;0.6.3 / PR-12): re-launching with identical caller parameters resolves to the same
     * {@code JobInstance} and raises {@link JobInstanceAlreadyCompleteException} (mapped to
     * {@code 409 JOB_ALREADY_COMPLETE}) instead of creating a duplicate instance, whereas a different
     * parameter value (e.g. a new {@code tranDate}) yields a new instance/execution.</p>
     *
     * @param jobName    the name of a {@link Job} registered with the {@link JobRegistry}
     *                   (bound from the path)
     * @param parameters optional job parameters as string key/value pairs (request body may be empty)
     * @return {@code 202 Accepted} with a body containing {@code jobExecutionId}, {@code jobInstanceId},
     *         {@code jobName}, {@code status}, {@code startTime} and the supplied {@code parameters}
     * @throws NoSuchJobException                     if {@code jobName} is not registered
     *                                                (handled by GlobalExceptionHandler &rarr; {@code 404})
     * @throws JobExecutionAlreadyRunningException     if an execution for the instance is already
     *                                                running (handled by GlobalExceptionHandler &rarr; {@code 409})
     * @throws JobRestartException                     if the job could not be restarted
     *                                                (handled by GlobalExceptionHandler &rarr; {@code 409})
     * @throws JobInstanceAlreadyCompleteException     if the instance already completed successfully
     *                                                (handled by GlobalExceptionHandler &rarr; {@code 409})
     * @throws JobParametersInvalidException           if the parameters fail the job's validator
     *                                                (handled by GlobalExceptionHandler &rarr; {@code 400})
     */
    @PostMapping("/{jobName}/launch")
    @Operation(
        summary = "Launch a Spring Batch job by name",
        description = "Launches the named Spring Batch job with optional JobParameters. "
            + "Replaces the CICS WRITEQ TD QUEUE('JOBS') pattern. "
            + "Returns 202 Accepted with execution metadata.")
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "Job launch accepted; returns execution metadata"),
        @ApiResponse(responseCode = "400", description = "Invalid job parameters"),
        @ApiResponse(responseCode = "403", description = "User lacks ADMIN role"),
        @ApiResponse(responseCode = "404", description = "Job name not registered in JobRegistry"),
        @ApiResponse(responseCode = "409", description = "Job already running or already completed with same parameters")
    })
    public ResponseEntity<Map<String, Object>> launchJob(
            @Parameter(description = "Spring Batch Job name registered with JobRegistry", example = "InterestCalculationJob")
            @PathVariable("jobName") String jobName,
            @Parameter(description = "Optional job parameters as key-value pairs (Strings)", example = "{\"tranDate\":\"2022071800\"}")
            @RequestBody(required = false) Map<String, String> parameters)
            throws NoSuchJobException, JobExecutionAlreadyRunningException,
            JobRestartException, JobInstanceAlreadyCompleteException,
            JobParametersInvalidException {

        log.info("Launching batch job: {} with parameters: {}", jobName, redactJobParameters(parameters));

        // Look up the registered Job bean by name (throws NoSuchJobException -> 404 if absent).
        Job job = jobRegistry.getJob(jobName);

        // Build JobParameters from the optional request body, preserving every supplied key/value.
        JobParametersBuilder paramsBuilder = new JobParametersBuilder();
        if (parameters != null) {
            parameters.forEach(paramsBuilder::addString);
        }
        // Append a NON-IDENTIFYING launch timestamp for audit/observability only. Passing
        // identifying=false keeps it out of the JobInstance identity, so the JobInstance is
        // identified solely by the caller-supplied parameters (e.g. tranDate). This preserves the
        // documented same-parameter idempotency contract (AAP 0.6.3 / PR-12): re-launching a job
        // with identical caller parameters resolves to the SAME JobInstance and raises
        // JobInstanceAlreadyCompleteException (-> 409 JOB_ALREADY_COMPLETE via GlobalExceptionHandler)
        // rather than silently creating a duplicate instance. A different parameter value (e.g. a
        // new tranDate) still yields a new JobInstance/execution.
        paramsBuilder.addLong(LAUNCH_TIMESTAMP_PARAM, System.currentTimeMillis(), false);

        JobParameters jobParameters = paramsBuilder.toJobParameters();

        // Launch the job. The auto-configured (synchronous) launcher runs the job and returns the
        // resulting JobExecution; any of the declared checked exceptions are surfaced to the local
        // @ExceptionHandler methods below.
        JobExecution execution = jobLauncher.run(job, jobParameters);

        // Assemble the response payload with execution metadata for the operator/caller.
        Map<String, Object> response = new HashMap<>();
        response.put("jobExecutionId", execution.getId());
        response.put("jobInstanceId", execution.getJobInstance().getInstanceId());
        response.put("jobName", jobName);
        response.put("status", execution.getStatus().toString());
        response.put("startTime",
            execution.getStartTime() != null
                ? execution.getStartTime().toString()
                : LocalDateTime.now().toString());
        response.put("parameters", parameters != null ? parameters : Map.of());

        log.info("Job {} launched successfully with executionId={}", jobName, execution.getId());

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    /**
     * Lists the names of all Spring Batch {@link Job}s currently registered with the
     * {@link JobRegistry}, sorted alphabetically, together with the total count.
     *
     * <p><strong>No direct COBOL source program.</strong> This is an operational discovery endpoint
     * that complements {@link #launchJob(String, Map)}: it lets an authorized administrator (or a
     * downstream automation client such as the INC3 batch tier) enumerate exactly which job names are
     * launchable, rather than relying on out-of-band documentation. In the legacy mainframe deployment
     * the set of batch jobs was fixed in JCL and known only to operations staff; exposing the live
     * registry here mirrors that operational knowledge in a discoverable, stateless form. It adds no
     * business behavior &mdash; it only reflects the jobs that {@code BatchConfig} already registers.</p>
     *
     * <p>The response is a stable-ordered JSON object, for example:</p>
     * <pre>{@code { "jobs": ["DataInitializationJob", "InterestCalculationJob", ...], "count": 17 }}</pre>
     *
     * @return {@code 200 OK} with a body containing the alphabetically-sorted {@code jobs} list and
     *         the {@code count} of registered jobs
     */
    @GetMapping
    @Operation(
        summary = "List registered Spring Batch job names",
        description = "Returns the alphabetically-sorted names of every Spring Batch job registered "
            + "with the JobRegistry, together with the total count. Use a returned name with "
            + "POST /api/admin/jobs/{jobName}/launch.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Registered job names and count"),
        @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token"),
        @ApiResponse(responseCode = "403", description = "User lacks ADMIN role")
    })
    public ResponseEntity<Map<String, Object>> listJobs() {
        // JobRegistry.getJobNames() returns the live set of registered job names; sort for a stable,
        // deterministic response that is friendly to clients and to assertion-based tests.
        List<String> jobNames = jobRegistry.getJobNames().stream()
                .sorted()
                .toList();

        log.info("Listing {} registered batch job(s)", jobNames.size());

        // LinkedHashMap preserves insertion order so the JSON renders "jobs" before "count".
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jobs", jobNames);
        response.put("count", jobNames.size());

        return ResponseEntity.ok(response);
    }

    /**
     * Returns a log-safe copy of job parameters, redacting values whose keys commonly carry secret
     * material. The original parameter map is still used to launch the job; this helper only hardens
     * observability so ad hoc operational parameters cannot leak credentials into application logs.
     *
     * @param parameters caller-supplied job parameters; may be {@code null}
     * @return a non-null map safe to include in logs
     */
    private static Map<String, String> redactJobParameters(Map<String, String> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return Map.of();
        }
        Map<String, String> redacted = new HashMap<>();
        parameters.forEach((key, value) ->
                redacted.put(key, isSensitiveJobParameterKey(key) ? REDACTED_LOG_VALUE : value));
        return redacted;
    }

    /**
     * Identifies secret-like parameter keys using conservative substrings aligned with common
     * credential naming conventions.
     */
    private static boolean isSensitiveJobParameterKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toLowerCase(Locale.ROOT);
        return normalized.contains("password")
                || normalized.contains("token")
                || normalized.contains("secret")
                || normalized.contains("credential")
                || normalized.contains("key");
    }
}
