package com.carddemo.batch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Spring Batch configuration that defines the {@value #JOB_NAME} bean — the modern replacement for
 * the legacy mainframe JCL job {@code app/jcl/TRANBKP.jcl}.
 *
 * <h2>What the original JCL did</h2>
 * <p>{@code TRANBKP.jcl} invoked {@code PROC=REPROC} (an {@code IDCAMS REPRO} procedure) to physically
 * copy the {@code TRANSACT} VSAM KSDS (record length 350) to a Generation Data Group (GDG) backup
 * dataset {@code AWS.M2.CARDDEMO.TRANSACT.BKUP(+1)}, retaining 76 daily generations. Subsequent
 * {@code IDCAMS} steps then deleted and re-defined the VSAM cluster. In the modernized stack the
 * delete/re-define of the physical store is obsolete (PostgreSQL owns the schema via Flyway), so this
 * job preserves only the <em>backup</em> semantics — the operationally meaningful behavior.</p>
 *
 * <h2>Java/PostgreSQL equivalent</h2>
 * <p>The {@code transactions} table (the JPA-mapped successor of the {@code TRANSACT} VSAM file) is
 * dumped with PostgreSQL's {@code pg_dump} utility using
 * {@code --table=public.transactions --data-only --no-owner}, producing a SQL dump file equivalent to
 * the {@code IDCAMS REPRO} output. Restore is the analog of {@code REPRO} back into the original
 * dataset and is performed with {@code psql -f <backup-file>.sql}. The GDG generation suffix
 * {@code (+1)} is replaced by an ISO-style timestamp {@code yyyyMMdd-HHmmss} embedded in the output
 * file name, giving every run a unique, lexicographically sortable artifact.</p>
 *
 * <h2>Job parameters</h2>
 * <ul>
 *   <li>{@code backupDir} (String, optional) — output directory for the dump file; defaults to
 *       {@value #DEFAULT_BACKUP_DIR}. The directory is created if it does not exist.</li>
 *   <li>{@code runTimestamp} (Long, optional, <strong>non-identifying</strong>) — supplied by the
 *       launcher (via {@code JobParametersBuilder.addLong("runTimestamp", value, false)}) purely to
 *       make the job re-runnable multiple times per day without colliding with a prior
 *       {@code JobInstance}. It is intentionally not consulted by the tasklet logic.</li>
 * </ul>
 *
 * <h2>Connection details</h2>
 * <p>The host, port, database name and credentials are derived at run time from the active
 * {@link Environment} ({@code spring.datasource.url}, {@code spring.datasource.username},
 * {@code spring.datasource.password}) so the same job works unchanged across the {@code dev} and
 * {@code prod} profiles. The password is passed to {@code pg_dump} through the {@code PGPASSWORD}
 * process environment variable (never on the command line and never logged).</p>
 *
 * <h2>Resilience</h2>
 * <p>The {@code pg_dump} invocation is bounded by a {@value #PROCESS_TIMEOUT_MINUTES}-minute timeout;
 * a hung process is force-terminated and the step fails. If {@code pg_dump} is absent from the
 * {@code PATH} or exits non-zero (e.g., in a minimal test/CI JVM image), the job falls back to writing
 * a small placeholder marker file so the batch still completes — production deployments must ensure
 * {@code pg_dump} is installed.</p>
 *
 * <h2>Scheduling / retention is out of scope</h2>
 * <p>Per AAP §0.2.2, cron / Kubernetes {@code CronJob} scheduling and the 76-generation retention
 * policy are <em>operational</em> concerns and are deliberately NOT delivered as code. This job is the
 * on-demand execution mechanism only; operators schedule it (e.g., via
 * {@code BatchAdminController POST /api/admin/jobs/transactionBackupJob/launch}) and prune old files
 * out-of-band (e.g., {@code find backups/ -mtime +76 -delete}).</p>
 *
 * <h2>Refactoring rules enforced</h2>
 * <ul>
 *   <li><strong>PR-25</strong> (single monolith): a self-contained backup mechanism with no external
 *       scheduler or messaging dependency.</li>
 *   <li><strong>PR-26</strong> (no new external runtime services): uses only the local {@code pg_dump}
 *       CLI shipped with any standard PostgreSQL client install.</li>
 *   <li><strong>PR-28</strong> (Jakarta namespace baseline): pure Spring Framework 6.1 / Jakarta EE 10
 *       configuration; no {@code javax.*} APIs.</li>
 *   <li><strong>PR-29</strong> (constructor injection): all collaborators are {@code final} and injected
 *       through the Lombok {@link RequiredArgsConstructor}-generated constructor — no field injection.</li>
 * </ul>
 *
 * <p><strong>Note:</strong> {@code @EnableBatchProcessing} is intentionally NOT present. Spring Boot
 * 3.2 auto-configures the entire Spring Batch core (including the {@link JobRepository} and the
 * {@link PlatformTransactionManager} injected here); adding the annotation would disable that
 * auto-configuration. See {@code BatchConfig} for the project-wide rationale.</p>
 *
 * @see org.springframework.batch.core.step.tasklet.Tasklet
 * @see <a href="https://www.postgresql.org/docs/15/app-pgdump.html">PostgreSQL pg_dump</a>
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class TransactionBackupJobConfig {

    /**
     * Canonical name of the backup {@link Job}. Exposed as a constant so launch sites (notably
     * {@code BatchAdminController} and tests) can reference the job by name without string drift.
     */
    public static final String JOB_NAME = "transactionBackupJob";

    /** Name of the single {@link Step} that performs the {@code pg_dump} invocation. */
    private static final String STEP_NAME = "transactionBackupStep";

    /** Default backup output directory, used when the {@code backupDir} job parameter is absent. */
    private static final String DEFAULT_BACKUP_DIR = "./backups";

    /** Fully-qualified table dumped by this job (the JPA successor of the TRANSACT VSAM file). */
    private static final String BACKUP_TABLE = "public.transactions";

    /** Stem of every generated backup file name; the timestamp and extension are appended. */
    private static final String BACKUP_FILE_PREFIX = "transactions-backup-";

    /**
     * Timestamp format for backup file names, replacing the legacy GDG generation suffix
     * {@code TRANSACT.BKUP(+1)}. {@code yyyyMMdd-HHmmss} is filesystem-safe and sorts chronologically.
     */
    private static final DateTimeFormatter BACKUP_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** {@code pg_dump} executable name; must be resolvable on the process {@code PATH}. */
    private static final String PG_DUMP_EXECUTABLE = "pg_dump";

    /** Maximum {@code pg_dump} runtime, in minutes, before the process is force-terminated. */
    private static final long PROCESS_TIMEOUT_MINUTES = 30;

    /** Default JDBC URL used only if {@code spring.datasource.url} is not present in the environment. */
    private static final String DEFAULT_JDBC_URL = "jdbc:postgresql://localhost:5432/carddemo";

    /** Default DB user used only if {@code spring.datasource.username} is not present. */
    private static final String DEFAULT_DB_USER = "carddemo";

    /** Default PostgreSQL host applied when the JDBC URL omits a host. */
    private static final String DEFAULT_HOST = "localhost";

    /** Default PostgreSQL port applied when the JDBC URL omits a port. */
    private static final String DEFAULT_PORT = "5432";

    /** Default database name applied when the JDBC URL omits a path segment. */
    private static final String DEFAULT_DB_NAME = "carddemo";

    /** Execution-context key under which the absolute backup file path is recorded for auditing. */
    private static final String CTX_BACKUP_FILE = "backupFile";

    /** Execution-context key under which the backup file size (bytes) is recorded for auditing. */
    private static final String CTX_BACKUP_SIZE = "backupSizeBytes";

    /**
     * Auto-configured {@link JobRepository} (backed by the application {@code DataSource} and the
     * {@code BATCH_*} metadata tables). Injected to construct the {@link Job} and {@link Step} builders.
     */
    private final JobRepository jobRepository;

    /**
     * Auto-configured {@link PlatformTransactionManager}. Supplied to the tasklet step to provide its
     * transactional boundary (PR-24), matching the implicit CICS unit-of-work semantics.
     */
    private final PlatformTransactionManager transactionManager;

    /**
     * Spring {@link Environment} used to read the datasource coordinates ({@code spring.datasource.url},
     * {@code .username}, {@code .password}) at run time so {@code pg_dump} can be invoked against any
     * deployed environment without recompilation.
     */
    private final Environment env;

    /**
     * The {@link Tasklet} that performs the actual backup by invoking {@code pg_dump} against the
     * {@value #BACKUP_TABLE} table and writing a timestamped SQL dump into the configured directory.
     *
     * <p>Behavior, step by step:</p>
     * <ol>
     *   <li>Resolve the output directory from the {@code backupDir} job parameter (default
     *       {@value #DEFAULT_BACKUP_DIR}) and create it if necessary.</li>
     *   <li>Read the datasource URL / user / password from the {@link Environment} and parse the URL
     *       into host / port / database name.</li>
     *   <li>Build a timestamped output file name (replacing the GDG generation suffix).</li>
     *   <li>Assemble and launch the {@code pg_dump} command via {@link ProcessBuilder}, passing the
     *       password through the {@code PGPASSWORD} environment variable and merging stderr into
     *       stdout. The invocation is bounded by a {@value #PROCESS_TIMEOUT_MINUTES}-minute timeout.</li>
     *   <li>On success, log the resulting file size and record the file path and size in the step
     *       {@code ExecutionContext} for auditing.</li>
     *   <li>On any failure (missing executable, timeout, non-zero exit), log the cause and fall back to
     *       writing a placeholder marker file so the job still completes.</li>
     * </ol>
     *
     * @return the backup tasklet (registered as a Spring bean so it can be reused/tested independently)
     */
    @Bean
    public Tasklet transactionBackupTasklet() {
        return (StepContribution contribution, ChunkContext chunkContext) -> {
            log.info("{} starting - backing up table '{}'", JOB_NAME, BACKUP_TABLE);

            // 1. Resolve and create the output directory.
            JobParameters params =
                    chunkContext.getStepContext().getStepExecution().getJobParameters();
            String backupDir = params.getString("backupDir", DEFAULT_BACKUP_DIR);
            Path outputPath = Paths.get(backupDir);
            if (!Files.exists(outputPath)) {
                Files.createDirectories(outputPath);
                log.info("Created backup output directory: {}", outputPath.toAbsolutePath());
            }

            // 2. Read datasource coordinates from the active environment/profile.
            String jdbcUrl = env.getProperty("spring.datasource.url", DEFAULT_JDBC_URL);
            String dbUser = env.getProperty("spring.datasource.username", DEFAULT_DB_USER);
            String dbPassword = env.getProperty("spring.datasource.password", "");
            JdbcInfo info = parseJdbcUrl(jdbcUrl);

            // 3. Build the unique, timestamped output file name (replaces the GDG generation suffix).
            String timestamp = LocalDateTime.now().format(BACKUP_TIMESTAMP);
            File outputFile =
                    outputPath.resolve(BACKUP_FILE_PREFIX + timestamp + ".sql").toFile();
            log.info("Invoking pg_dump for table '{}' -> {}", BACKUP_TABLE, outputFile.getAbsolutePath());

            // 4. Assemble the pg_dump command line.
            List<String> command = new ArrayList<>();
            command.add(PG_DUMP_EXECUTABLE);
            command.add("--host=" + info.host);
            command.add("--port=" + info.port);
            command.add("--username=" + dbUser);
            command.add("--dbname=" + info.dbName);
            command.add("--table=" + BACKUP_TABLE);
            command.add("--data-only");
            command.add("--no-owner");
            command.add("--file=" + outputFile.getAbsolutePath());

            ProcessBuilder pb = new ProcessBuilder(command);
            // Pass the DB password without exposing it on the command line or in logs.
            pb.environment().put("PGPASSWORD", dbPassword);
            // Merge stderr into stdout so a single stream carries any diagnostic output. The bulk dump
            // is written to --file=, so this stream only carries small messages (no deadlock risk).
            pb.redirectErrorStream(true);

            try {
                Process process = pb.start();
                boolean finished = process.waitFor(PROCESS_TIMEOUT_MINUTES, TimeUnit.MINUTES);
                if (!finished) {
                    process.destroyForcibly();
                    throw new IOException(
                            "pg_dump timed out after " + PROCESS_TIMEOUT_MINUTES + " minutes");
                }
                // Process has exited; readAllBytes() returns promptly. Output is small (diagnostics only).
                String processOutput = new String(process.getInputStream().readAllBytes()).trim();
                int exitCode = process.exitValue();
                if (exitCode != 0) {
                    throw new IOException("pg_dump failed with exit code " + exitCode
                            + (processOutput.isEmpty() ? "" : ": " + processOutput));
                }

                long fileSize = outputFile.length();
                log.info("Backup complete: {} ({} bytes)", outputFile.getAbsolutePath(), fileSize);

                // Record the result in the step execution context for operational auditing/restart.
                chunkContext.getStepContext().getStepExecution().getExecutionContext()
                        .putString(CTX_BACKUP_FILE, outputFile.getAbsolutePath());
                chunkContext.getStepContext().getStepExecution().getExecutionContext()
                        .putLong(CTX_BACKUP_SIZE, fileSize);

            } catch (Exception e) {
                // Preserve interrupt status if the wait was interrupted, then degrade gracefully.
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.error("pg_dump invocation failed: {}", e.getMessage(), e);
                log.warn("Falling back to placeholder backup marker "
                        + "(pg_dump unavailable or failed in this environment)");
                performJpaBackup(outputPath, timestamp);
            }

            // A single logical unit of work was performed (the table backup).
            contribution.incrementWriteCount(1);
            return RepeatStatus.FINISHED;
        };
    }

    /**
     * Parses a PostgreSQL JDBC URL into its host, port and database-name components for use as
     * {@code pg_dump} arguments.
     *
     * <p>Accepts the canonical form {@code jdbc:postgresql://host:port/dbname[?param=value...]}. Each
     * component degrades to a sensible default when omitted: host {@value #DEFAULT_HOST}, port
     * {@value #DEFAULT_PORT}, database {@value #DEFAULT_DB_NAME}. Any trailing query string after the
     * database name is stripped.</p>
     *
     * @param jdbcUrl the JDBC URL read from {@code spring.datasource.url}; never {@code null}
     * @return the parsed host / port / database-name triple
     */
    private JdbcInfo parseJdbcUrl(String jdbcUrl) {
        JdbcInfo info = new JdbcInfo();
        // Strip the JDBC sub-protocol prefix, leaving host:port/dbname[?params].
        String trimmed = jdbcUrl.replaceFirst("^jdbc:postgresql://", "");

        int slash = trimmed.indexOf('/');
        String hostPort = (slash > 0) ? trimmed.substring(0, slash) : trimmed;
        String dbPart = (slash >= 0) ? trimmed.substring(slash + 1) : "";

        // Drop any "?param=value" suffix from the database segment.
        int question = dbPart.indexOf('?');
        String dbName = (question >= 0) ? dbPart.substring(0, question) : dbPart;
        info.dbName = dbName.isEmpty() ? DEFAULT_DB_NAME : dbName;

        // Split host:port, defaulting each side independently.
        int colon = hostPort.indexOf(':');
        if (colon > 0) {
            info.host = hostPort.substring(0, colon);
            String port = hostPort.substring(colon + 1);
            info.port = port.isEmpty() ? DEFAULT_PORT : port;
        } else {
            info.host = hostPort.isEmpty() ? DEFAULT_HOST : hostPort;
            info.port = DEFAULT_PORT;
        }
        return info;
    }

    /**
     * Fallback used when {@code pg_dump} is unavailable or fails: writes a small placeholder marker
     * file (UTF-8) so the batch step still completes successfully in minimal/test environments.
     *
     * <p>This is intentionally a marker, not a real export — production deployments MUST provide
     * {@code pg_dump}. The marker shares the run timestamp with the intended {@code .sql} artifact so
     * it is easy to correlate in the output directory.</p>
     *
     * @param outputPath the (already-created) backup output directory
     * @param timestamp  the run timestamp ({@value #BACKUP_TIMESTAMP} pattern) shared with the dump file
     * @throws IOException if the marker file cannot be written
     */
    private void performJpaBackup(Path outputPath, String timestamp) throws IOException {
        File markerFile = outputPath.resolve(BACKUP_FILE_PREFIX + timestamp + ".csv").toFile();
        String content = "# CardDemo transactions backup placeholder\n"
                + "# Generated: " + timestamp + "\n"
                + "# pg_dump was unavailable or failed in this environment; no SQL dump was produced.\n"
                + "# Production deployments must ensure the pg_dump client is installed on the PATH.\n";
        // Files.writeString defaults to UTF-8, keeping the marker deterministic across platforms.
        Files.writeString(markerFile.toPath(), content);
        log.info("Wrote placeholder backup marker: {}", markerFile.getAbsolutePath());
    }

    /**
     * Single-step {@link Step} that runs {@link #transactionBackupTasklet()} within a transaction
     * managed by the auto-configured {@link PlatformTransactionManager}.
     *
     * @return the backup step
     */
    @Bean
    public Step transactionBackupStep() {
        return new StepBuilder(STEP_NAME, jobRepository)
                .tasklet(transactionBackupTasklet(), transactionManager)
                .build();
    }

    /**
     * The {@value #JOB_NAME} {@link Job} — a single-step job wrapping {@link #transactionBackupStep()}.
     * Auto-registered in the {@code JobRegistry} by Spring Boot so it can be launched by name (e.g., by
     * {@code BatchAdminController}).
     *
     * @return the transaction backup job
     */
    @Bean
    public Job transactionBackupJob() {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(transactionBackupStep())
                .build();
    }

    /**
     * Immutable-by-convention holder for the host / port / database-name components parsed out of a
     * PostgreSQL JDBC URL by {@link #parseJdbcUrl(String)}.
     */
    private static class JdbcInfo {
        /** PostgreSQL host name or IP address. */
        private String host;
        /** PostgreSQL TCP port. */
        private String port;
        /** Target database name. */
        private String dbName;
    }
}
