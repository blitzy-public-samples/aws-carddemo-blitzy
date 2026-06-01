package com.carddemo.batch;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.carddemo.entity.User;
import com.carddemo.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring Batch integration test for {@link UserSeedingJobConfig} &mdash; the
 * {@code userSeedingJob} bean that is the Java/PostgreSQL replacement for the legacy JCL
 * job {@code app/jcl/DUSRSECJ.jcl} ("DEF USRSEC FILE").
 *
 * <h2>What the original DUSRSECJ.jcl did</h2>
 * The mainframe job materialized the user-security file in three steps:
 * <ol>
 *   <li><b>PREDEL</b> ({@code PGM=IEFBR14}) deleted any pre-existing
 *       {@code AWS.M2.CARDDEMO.USRSEC.PS} sequential dataset.</li>
 *   <li><b>STEP01</b> ({@code PGM=IEBGENER}) copied 10 hard-coded, fixed-width 80-byte
 *       records from the in-stream {@code SYSUT1} block (lines 35-44) into the
 *       {@code USRSEC.PS} dataset. Each record packs
 *       {@code SEC-USR-ID(8) + SEC-USR-FNAME(20) + SEC-USR-LNAME(20) + SEC-USR-PWD(8)
 *       + SEC-USR-TYPE(1) + filler(23)} per {@code app/cpy/CSUSR01Y.cpy}. The 10 users are
 *       5 administrators ({@code ADMIN001}-{@code ADMIN005}, {@code SEC-USR-TYPE='A'}) and
 *       5 standard users ({@code USER0001}-{@code USER0005}, {@code SEC-USR-TYPE='U'}), all
 *       carrying the 8-byte plaintext password literal {@code PASSWORD}.</li>
 *   <li><b>STEP02/STEP03</b> ({@code PGM=IDCAMS}) {@code DEFINE}d the {@code USRSEC} VSAM
 *       KSDS ({@code KEYS(8,0)}, {@code RECORDSIZE(80,80)}) and {@code REPRO}'d the PS records
 *       into it.</li>
 * </ol>
 *
 * <h2>What the modernized job does (system under test)</h2>
 * The entire IEBGENER + IDCAMS DEFINE/REPRO pipeline collapses into a single transactional
 * tasklet that builds 10 {@link User} JPA entities and persists them through
 * {@link UserRepository}. Per <strong>PR-17</strong> the 8-byte plaintext password is never
 * stored: each user's password is the single canonical literal {@code "PASSWORD"} run through
 * the production {@link PasswordEncoder} (a {@code BCryptPasswordEncoder}), producing a
 * distinct 60-character BCrypt hash per user (BCrypt embeds a 22-character random salt, so the
 * 10 hashes differ even though the raw password is identical). Per <strong>PR-19</strong> the
 * persisted {@code userType} discriminator is {@code 'A'} for the administrators (mapped
 * downstream to {@code ROLE_ADMIN}) and {@code 'U'} for the standard users ({@code ROLE_USER}).
 *
 * <h2>Observable contract verified here</h2>
 * <ul>
 *   <li>the job completes with {@link ExitStatus#COMPLETED} and seeds exactly 10 users, all 10
 *       expected IDs present ({@code shouldSeedAll10DefaultUsersWithBCryptHashes});</li>
 *   <li>no plaintext password is stored &mdash; every stored value is a 60-char BCrypt hash
 *       beginning with {@code $2} ({@code shouldStoreBCryptHashesNotPlaintextPasswords},
 *       PR-17);</li>
 *   <li>all 10 hashes are distinct thanks to BCrypt's per-hash salt
 *       ({@code shouldGenerateDistinctHashesPerUser}, PR-17);</li>
 *   <li>{@code passwordEncoder.matches("PASSWORD", storedHash)} is {@code true} for every user
 *       ({@code shouldVerifyPasswordMatchesAgainstLiteralPassword}, PR-17);</li>
 *   <li>role discriminators are seeded correctly &mdash; {@code 'A'} for admins, {@code 'U'} for
 *       standard users ({@code shouldAssignAdminRoleToAdminUsers}, PR-19);</li>
 *   <li>the job is idempotent &mdash; re-running with different identifying parameters never
 *       duplicates users ({@code shouldBeIdempotentOnReRun}, PR-22).</li>
 * </ul>
 *
 * <h2>Test topology</h2>
 * <p>{@code @SpringBootTest} boots the full application context; {@code @SpringBatchTest}
 * contributes {@link JobLauncherTestUtils} / {@link JobRepositoryTestUtils}. A real PostgreSQL 15
 * database is provided by Testcontainers, Flyway applies every {@code V*.sql} migration, and
 * Hibernate validates the schema against the committed DDL. The job runs against production
 * wiring rather than mocks &mdash; in particular the <em>real</em> {@code BCryptPasswordEncoder}
 * bean from {@code SecurityConfig} both seeds the hashes and verifies them, so this test exercises
 * the genuine PR-17 hashing path end-to-end.</p>
 *
 * <p><strong>Datasource note.</strong> The {@code @DynamicPropertySource} below binds this class's
 * dedicated {@link #POSTGRES} container and explicitly overrides
 * {@code spring.datasource.driver-class-name} to {@code org.postgresql.Driver}. The override is
 * mandatory because {@code application-test.yml} configures the Testcontainers
 * {@code ContainerDatabaseDriver} (which only accepts {@code jdbc:tc:} magic URLs); the plain
 * {@code jdbc:postgresql://} URL returned by {@link PostgreSQLContainer#getJdbcUrl()} must be
 * handled by the real PostgreSQL driver. This mirrors the canonical pattern in
 * {@code com.carddemo.batch.TransactionConsolidationJobIT} and
 * {@code com.carddemo.integration.SecurityIT}.</p>
 *
 * <p><strong>JobLauncher note.</strong> The context holds two {@link JobLauncher} beans &mdash;
 * Spring Boot's auto-configured synchronous {@code jobLauncher} and {@code BatchConfig}'s
 * non-primary {@code asyncJobLauncher} &mdash; so {@code @SpringBatchTest}'s own
 * {@code @Autowired(required = false)} setter silently skips injecting one into
 * {@link JobLauncherTestUtils}. The synchronous launcher is therefore resolved by bean name into
 * the {@link #jobLauncher} field and installed explicitly in {@link #setUp()} so the job runs
 * inline and its committed results are visible to the post-run assertions.</p>
 *
 * <p><strong>Transactionality.</strong> The class is intentionally <em>not</em>
 * {@code @Transactional}: the seeding job must commit so the synchronous launcher (and the
 * post-run repository queries) observe the inserted rows. {@link #setUp()} clears Spring Batch
 * metadata and truncates the {@code users} table before every test to guarantee isolation
 * (the {@code users} table is also seeded declaratively by {@code V4__seed_users.sql} at Flyway
 * time; clearing it lets the job perform the seed afresh under test).</p>
 *
 * @see UserSeedingJobConfig
 * @see com.carddemo.entity.User
 * @see com.carddemo.repository.UserRepository
 */
@SpringBootTest
@SpringBatchTest
@Testcontainers
@ActiveProfiles("test")
@DisplayName("DUSRSECJ → UserSeedingJob integration tests")
class UserSeedingJobIT {

    // ------------------------------------------------------------------------
    // Expected seed roster (verbatim from app/jcl/DUSRSECJ.jcl SYSUT1, L35-44)
    // ------------------------------------------------------------------------

    /** The 5 administrator user IDs ({@code SEC-USR-TYPE='A'} → {@code ROLE_ADMIN}, PR-19). */
    private static final List<String> EXPECTED_ADMIN_IDS =
            List.of("ADMIN001", "ADMIN002", "ADMIN003", "ADMIN004", "ADMIN005");

    /** The 5 standard user IDs ({@code SEC-USR-TYPE='U'} → {@code ROLE_USER}, PR-19). */
    private static final List<String> EXPECTED_USER_IDS =
            List.of("USER0001", "USER0002", "USER0003", "USER0004", "USER0005");

    /** Total number of default users seeded by {@code userSeedingJob} (5 admins + 5 users). */
    private static final long EXPECTED_USER_COUNT = 10L;

    /** Canonical raw password shared by all 10 seeded users (PR-17 — BCrypt-hashed per user). */
    private static final String RAW_PASSWORD = "PASSWORD";

    /** Fixed BCrypt hash length: {@code $2x$cc$} (7) + 22-char salt + 31-char digest = 60. */
    private static final int BCRYPT_HASH_LENGTH = 60;

    /** Administrator user-type discriminator (PR-19). */
    private static final String USER_TYPE_ADMIN = "A";

    /** Standard-user user-type discriminator (PR-19). */
    private static final String USER_TYPE_STANDARD = "U";

    // ------------------------------------------------------------------------
    // Testcontainers PostgreSQL 15 (AAP §0.5.1 — PostgreSQL 15 baseline)
    // ------------------------------------------------------------------------

    /**
     * Dedicated PostgreSQL 15 container for the seeding job. {@code @SuppressWarnings("resource")}
     * is applied because the {@code @Container}/{@code @Testcontainers} lifecycle (not a
     * try-with-resources block) owns container startup and shutdown.
     */
    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:15"))
            .withDatabaseName("carddemo_userseed_test")
            .withUsername("carddemo")
            .withPassword("test_password");

    /**
     * Binds the container's JDBC coordinates onto the Spring {@code Environment} before the
     * application context starts. The {@code driver-class-name} override is mandatory (see class
     * Javadoc) so the plain {@code jdbc:postgresql://} URL is handled by the real PostgreSQL JDBC
     * driver rather than the Testcontainers magic-URL driver configured in
     * {@code application-test.yml}.
     *
     * @param registry the dynamic property registry supplied by the Spring TestContext framework
     */
    @DynamicPropertySource
    static void registerDatabaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    // ------------------------------------------------------------------------
    // Injected collaborators
    // ------------------------------------------------------------------------

    /** Spring Batch test helper used to launch the job and inspect its {@link JobExecution}. */
    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    /** Cleans Spring Batch metadata ({@code BATCH_*} tables) between tests for isolation. */
    @Autowired
    private JobRepositoryTestUtils jobRepositoryTestUtils;

    /**
     * The <em>synchronous</em> {@link JobLauncher} that drives the job under test. Two
     * {@link JobLauncher} beans exist (Spring Boot's auto-configured synchronous {@code jobLauncher}
     * and {@code BatchConfig}'s non-primary {@code asyncJobLauncher}); naming this field
     * {@code jobLauncher} resolves it by bean name to the synchronous one, which is installed onto
     * {@link JobLauncherTestUtils} in {@link #setUp()} so the job runs inline and its committed
     * results are visible to the assertions.
     */
    @Autowired
    private JobLauncher jobLauncher;

    /** The system under test &mdash; selected by bean name so the correct {@link Job} is exercised. */
    @Autowired
    @Qualifier("userSeedingJob")
    private Job userSeedingJob;

    /** Repository used to query and reset the {@code users} table during verification. */
    @Autowired
    private UserRepository userRepository;

    /**
     * The production {@link PasswordEncoder} (a {@code BCryptPasswordEncoder} from
     * {@code SecurityConfig}). The <em>same</em> bean both seeds the hashes (inside the job) and
     * verifies them here via {@code matches("PASSWORD", storedHash)} (PR-17). It is deliberately
     * the real encoder, not a mock, because BCrypt verification is the core assertion of this test.
     */
    @Autowired
    private PasswordEncoder passwordEncoder;

    // ------------------------------------------------------------------------
    // Per-test setup
    // ------------------------------------------------------------------------

    /**
     * Resets all state the job interacts with before each test:
     * <ul>
     *   <li>{@code jobRepositoryTestUtils.removeJobExecutions()} clears prior Spring Batch
     *       executions so each test launches a clean job instance;</li>
     *   <li>{@code userRepository.deleteAll()} empties the {@code users} table (which Flyway
     *       {@code V4__seed_users.sql} pre-populated), so the job performs the seed afresh and the
     *       post-run counts reflect only what the job inserted;</li>
     *   <li>{@code jobLauncherTestUtils.setJobLauncher(jobLauncher)} installs the synchronous
     *       launcher explicitly (see the {@link #jobLauncher} field Javadoc &mdash;
     *       {@code @SpringBatchTest} cannot auto-resolve it because two {@link JobLauncher} beans
     *       exist);</li>
     *   <li>{@code jobLauncherTestUtils.setJob(...)} points the launcher at the seeding job (the
     *       field is not autowired by {@code @SpringBatchTest} and must be set explicitly).</li>
     * </ul>
     */
    @BeforeEach
    void setUp() {
        jobRepositoryTestUtils.removeJobExecutions();
        userRepository.deleteAll();
        jobLauncherTestUtils.setJobLauncher(jobLauncher);
        jobLauncherTestUtils.setJob(userSeedingJob);
    }

    // ------------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------------

    /**
     * Verifies the job completes successfully and seeds exactly the 10 default users defined in
     * {@code DUSRSECJ.jcl} (5 admins + 5 standard users), with every expected ID present.
     *
     * @throws Exception if the job launch fails
     */
    @Test
    @DisplayName("Should seed all 10 default users (ADMIN001-005, USER0001-005) with BCrypt hashes")
    void shouldSeedAll10DefaultUsersWithBCryptHashes() throws Exception {
        JobExecution execution = jobLauncherTestUtils.launchJob();

        assertThat(execution.getExitStatus()).isEqualTo(ExitStatus.COMPLETED);
        assertThat(userRepository.count()).isEqualTo(EXPECTED_USER_COUNT);

        for (String id : EXPECTED_ADMIN_IDS) {
            assertThat(userRepository.findById(id))
                    .as("Admin user %s should be seeded", id)
                    .isPresent();
        }
        for (String id : EXPECTED_USER_IDS) {
            assertThat(userRepository.findById(id))
                    .as("Standard user %s should be seeded", id)
                    .isPresent();
        }
    }

    /**
     * Verifies <strong>PR-17</strong>: no plaintext password is stored. Every seeded
     * {@code secUsrPwd} must be a 60-character BCrypt hash beginning with {@code $2} (the BCrypt
     * version prefix {@code $2a}/{@code $2b}/{@code $2y}) and must never equal the raw literal
     * {@code "PASSWORD"}.
     *
     * @throws Exception if the job launch fails
     */
    @Test
    @DisplayName("Should store BCrypt hashes (60 chars starting with $2) NOT plaintext PASSWORD")
    void shouldStoreBCryptHashesNotPlaintextPasswords() throws Exception {
        jobLauncherTestUtils.launchJob();

        List<User> allUsers = userRepository.findAll();
        assertThat(allUsers).hasSize((int) EXPECTED_USER_COUNT);
        for (User user : allUsers) {
            assertThat(user.getSecUsrPwd())
                    .as("Password for user %s must NOT be plaintext", user.getUserId())
                    .isNotEqualTo(RAW_PASSWORD)
                    .hasSize(BCRYPT_HASH_LENGTH)
                    .startsWith("$2");
        }
    }

    /**
     * Verifies <strong>PR-17</strong>: BCrypt's embedded 22-character random salt yields a distinct
     * hash for every user even though all 10 share the identical raw password {@code "PASSWORD"}.
     *
     * @throws Exception if the job launch fails
     */
    @Test
    @DisplayName("Should generate distinct BCrypt hashes per user (random salt)")
    void shouldGenerateDistinctHashesPerUser() throws Exception {
        jobLauncherTestUtils.launchJob();

        List<User> allUsers = userRepository.findAll();
        long distinctHashCount = allUsers.stream()
                .map(User::getSecUsrPwd)
                .distinct()
                .count();
        assertThat(distinctHashCount)
                .as("All %d seeded BCrypt hashes should be unique", EXPECTED_USER_COUNT)
                .isEqualTo(EXPECTED_USER_COUNT);
    }

    /**
     * Verifies <strong>PR-17</strong>: the production {@link PasswordEncoder} confirms that each
     * stored hash matches the canonical raw password {@code "PASSWORD"} &mdash; i.e. the seeding
     * job hashed the correct literal and authentication via
     * {@code BCryptPasswordEncoder.matches(...)} will succeed for every default user.
     *
     * @throws Exception if the job launch fails
     */
    @Test
    @DisplayName("Should verify passwordEncoder.matches(\"PASSWORD\", storedHash) is true for every user")
    void shouldVerifyPasswordMatchesAgainstLiteralPassword() throws Exception {
        jobLauncherTestUtils.launchJob();

        List<User> allUsers = userRepository.findAll();
        assertThat(allUsers).hasSize((int) EXPECTED_USER_COUNT);
        for (User user : allUsers) {
            boolean matches = passwordEncoder.matches(RAW_PASSWORD, user.getSecUsrPwd());
            assertThat(matches)
                    .as("BCrypt verification should succeed for user %s", user.getUserId())
                    .isTrue();
        }
    }

    /**
     * Verifies <strong>PR-19</strong>: the {@code userType} role discriminator is seeded correctly
     * &mdash; {@code 'A'} for {@code ADMIN001}-{@code ADMIN005} (→ {@code ROLE_ADMIN}) and
     * {@code 'U'} for {@code USER0001}-{@code USER0005} (→ {@code ROLE_USER}).
     *
     * @throws Exception if the job launch fails
     */
    @Test
    @DisplayName("Should assign type='A' to ADMIN users and type='U' to regular users")
    void shouldAssignAdminRoleToAdminUsers() throws Exception {
        jobLauncherTestUtils.launchJob();

        for (String id : EXPECTED_ADMIN_IDS) {
            Optional<User> user = userRepository.findById(id);
            assertThat(user).as("Admin user %s should be seeded", id).isPresent();
            assertThat(user.get().getUserType())
                    .as("User %s should have userType 'A' (ROLE_ADMIN)", id)
                    .isEqualTo(USER_TYPE_ADMIN);
        }
        for (String id : EXPECTED_USER_IDS) {
            Optional<User> user = userRepository.findById(id);
            assertThat(user).as("Standard user %s should be seeded", id).isPresent();
            assertThat(user.get().getUserType())
                    .as("User %s should have userType 'U' (ROLE_USER)", id)
                    .isEqualTo(USER_TYPE_STANDARD);
        }
    }

    /**
     * Verifies <strong>PR-22</strong>: the job is idempotent. Re-running it with different
     * identifying {@link JobParameters} (which forces a fresh {@link JobExecution}) must not
     * duplicate users &mdash; {@code UserSeedingJobConfig} guards every insert with
     * {@code existsById(...)}, so the second run is a harmless no-op and the {@code users} table
     * still holds exactly 10 rows keyed by their {@code sec_usr_id} primary key.
     *
     * @throws Exception if either job launch fails
     */
    @Test
    @DisplayName("Re-running with the same job parameters should not duplicate users")
    void shouldBeIdempotentOnReRun() throws Exception {
        JobParameters params1 = new JobParametersBuilder().addLong("run", 1L).toJobParameters();
        JobExecution exec1 = jobLauncherTestUtils.launchJob(params1);
        assertThat(exec1.getExitStatus()).isEqualTo(ExitStatus.COMPLETED);
        assertThat(userRepository.count())
                .as("First run should seed all 10 default users")
                .isEqualTo(EXPECTED_USER_COUNT);

        JobParameters params2 = new JobParametersBuilder().addLong("run", 2L).toJobParameters();
        JobExecution exec2 = jobLauncherTestUtils.launchJob(params2);
        assertThat(exec2.getExitStatus()).isEqualTo(ExitStatus.COMPLETED);
        assertThat(userRepository.count())
                .as("Second run must be idempotent — still exactly 10 users, no duplicates")
                .isEqualTo(EXPECTED_USER_COUNT);
    }
}
