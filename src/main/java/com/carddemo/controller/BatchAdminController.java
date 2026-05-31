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
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
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
 *   <li>{@code POST /api/admin/jobs/{jobName}/launch} &mdash; launch the named job with optional
 *       {@link JobParameters}; returns {@code 202 Accepted} with execution metadata.</li>
 * </ul>
 *
 * <p>This controller intentionally exposes <em>only</em> the launch operation mandated by the AAP
 * ({@code POST /api/admin/jobs/{jobName}/launch}). No job-discovery/enumeration endpoint is provided:
 * the migration scope forbids adding endpoints beyond those required to mirror the legacy behavior
 * (PR &mdash; no feature additions), and the set of launchable job names is fixed and documented
 * rather than enumerated at runtime.</p>
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
 *   <li><strong>Idempotency / re-runs:</strong> Spring Batch identifies a {@code JobInstance} by its
 *       identifying {@link JobParameters}; launching a job twice with identical parameters would
 *       raise {@link JobInstanceAlreadyCompleteException}. To make each manual launch a distinct
 *       instance, a unique identifying {@code launchTimestamp} parameter is added via
 *       {@link JobParametersBuilder#addLong(String, Long, boolean)} with {@code identifying=true}.</li>
 *   <li><strong>Local exception handling:</strong> the Spring Batch launch exceptions are handled by
 *       the {@code @ExceptionHandler} methods on this controller (rather than the global handler)
 *       because they are framework-specific and map cleanly to {@code 404}/{@code 409}/{@code 400}
 *       outcomes for this operational surface.</li>
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
     * Bean name of the unique, identifying job parameter injected on every launch so that repeated
     * manual launches of the same job create distinct {@code JobInstance}s instead of failing with
     * {@link JobInstanceAlreadyCompleteException}.
     */
    private static final String LAUNCH_TIMESTAMP_PARAM = "launchTimestamp";

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
     * <p>A unique identifying {@code launchTimestamp} parameter is always appended so that launching
     * the same job repeatedly with otherwise-identical parameters produces distinct
     * {@code JobInstance}s rather than failing as an already-completed instance.</p>
     *
     * @param jobName    the name of a {@link Job} registered with the {@link JobRegistry}
     *                   (bound from the path)
     * @param parameters optional job parameters as string key/value pairs (request body may be empty)
     * @return {@code 202 Accepted} with a body containing {@code jobExecutionId}, {@code jobInstanceId},
     *         {@code jobName}, {@code status}, {@code startTime} and the supplied {@code parameters}
     * @throws NoSuchJobException                     if {@code jobName} is not registered
     *                                                (handled locally &rarr; {@code 404})
     * @throws JobExecutionAlreadyRunningException     if an execution for the instance is already
     *                                                running (handled locally &rarr; {@code 409})
     * @throws JobRestartException                     if the job could not be restarted
     *                                                (handled locally &rarr; {@code 409})
     * @throws JobInstanceAlreadyCompleteException     if the instance already completed successfully
     *                                                (handled locally &rarr; {@code 409})
     * @throws JobParametersInvalidException           if the parameters fail the job's validator
     *                                                (handled locally &rarr; {@code 400})
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

        log.info("Launching batch job: {} with parameters: {}", jobName, parameters);

        // Look up the registered Job bean by name (throws NoSuchJobException -> 404 if absent).
        Job job = jobRegistry.getJob(jobName);

        // Build JobParameters from the optional request body, preserving every supplied key/value.
        JobParametersBuilder paramsBuilder = new JobParametersBuilder();
        if (parameters != null) {
            parameters.forEach(paramsBuilder::addString);
        }
        // Append a unique, identifying launch timestamp so repeat launches form distinct
        // JobInstances. Without this, an identical (job + parameters) combination would raise
        // JobInstanceAlreadyCompleteException on the second invocation.
        paramsBuilder.addLong(LAUNCH_TIMESTAMP_PARAM, System.currentTimeMillis(), true);

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
     * Maps {@link NoSuchJobException} (an unknown {@code jobName}) to {@code 404 Not Found}.
     *
     * @param ex the thrown exception
     * @return a {@code 404} response with a structured error body
     */
    @ExceptionHandler(NoSuchJobException.class)
    public ResponseEntity<Map<String, Object>> handleNoSuchJob(NoSuchJobException ex) {
        log.warn("Job not found: {}", ex.getMessage());
        Map<String, Object> body = new HashMap<>();
        body.put("error", "Job not found");
        body.put("message", ex.getMessage());
        body.put("timestamp", LocalDateTime.now().toString());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    /**
     * Maps the Spring Batch launch-conflict exceptions to {@code 409 Conflict}: the targeted instance
     * is already running, has already completed, or could not be restarted.
     *
     * @param ex the thrown exception (one of {@link JobInstanceAlreadyCompleteException},
     *           {@link JobExecutionAlreadyRunningException}, {@link JobRestartException})
     * @return a {@code 409} response with a structured error body
     */
    @ExceptionHandler({
        JobInstanceAlreadyCompleteException.class,
        JobExecutionAlreadyRunningException.class,
        JobRestartException.class
    })
    public ResponseEntity<Map<String, Object>> handleJobConflict(Exception ex) {
        log.warn("Job launch conflict: {}", ex.getMessage());
        Map<String, Object> body = new HashMap<>();
        body.put("error", "Job conflict");
        body.put("message", ex.getMessage());
        body.put("timestamp", LocalDateTime.now().toString());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /**
     * Maps {@link JobParametersInvalidException} (parameters rejected by the job's validator) to
     * {@code 400 Bad Request}.
     *
     * @param ex the thrown exception
     * @return a {@code 400} response with a structured error body
     */
    @ExceptionHandler(JobParametersInvalidException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidParams(JobParametersInvalidException ex) {
        log.warn("Invalid job parameters: {}", ex.getMessage());
        Map<String, Object> body = new HashMap<>();
        body.put("error", "Invalid job parameters");
        body.put("message", ex.getMessage());
        body.put("timestamp", LocalDateTime.now().toString());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}
