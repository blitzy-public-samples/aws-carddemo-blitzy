package com.carddemo.batch;

import com.carddemo.entity.User;
import com.carddemo.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring Batch configuration for the <strong>{@code userSeedingJob}</strong> &mdash; the
 * Java/PostgreSQL replacement for the legacy JCL job {@code app/jcl/DUSRSECJ.jcl}
 * ("DEF USRSEC FILE").
 *
 * <h2>Legacy mainframe behavior (DUSRSECJ.jcl)</h2>
 * The original three-step job materialized the user-security file:
 * <ol>
 *   <li><b>PREDEL</b> ({@code PGM=IEFBR14}) &mdash; deleted any pre-existing
 *       {@code AWS.M2.CARDDEMO.USRSEC.PS} sequential dataset.</li>
 *   <li><b>STEP01</b> ({@code PGM=IEBGENER}) &mdash; copied 10 hard-coded, fixed-width
 *       80-byte records from in-stream {@code SYSUT1} into the {@code USRSEC.PS} dataset.
 *       Each record packs {@code SEC-USR-ID(8) + SEC-USR-FNAME(20) + SEC-USR-LNAME(20)
 *       + SEC-USR-PWD(8) + SEC-USR-TYPE(1) + filler} per {@code app/cpy/CSUSR01Y.cpy}.</li>
 *   <li><b>STEP02/STEP03</b> ({@code PGM=IDCAMS}) &mdash; {@code DEFINE}d the
 *       {@code USRSEC} VSAM KSDS ({@code KEYS(8,0)}, {@code RECORDSIZE(80,80)}) and
 *       {@code REPRO}'d the PS records into it.</li>
 * </ol>
 * The 10 default users are 5 administrators ({@code ADMIN001}-{@code ADMIN005}) and 5
 * standard users ({@code USER0001}-{@code USER0005}).
 *
 * <h2>Modernized PostgreSQL behavior</h2>
 * The entire IEBGENER + IDCAMS DEFINE/REPRO pipeline collapses into a single,
 * transactional Spring Batch tasklet ({@link #userSeedingTasklet()}) that constructs 10
 * {@link User} JPA entities and persists them through {@link UserRepository}. The VSAM
 * {@code DEFINE CLUSTER} has no runtime analog &mdash; the {@code users} table is created
 * by the Flyway migration {@code V1__schema.sql}, and its B-tree primary-key index on
 * {@code sec_usr_id} replaces the KSDS {@code KEYS(8,0)} prime key.
 *
 * <h2>Relationship to {@code V4__seed_users.sql} (two convergent seeding paths)</h2>
 * The same 10 users are also seeded declaratively by the Flyway migration
 * {@code V4__seed_users.sql} using pre-computed BCrypt hashes. This batch job is the
 * <em>runtime re-hash path</em> (AAP &sect;0.6.8): it can be launched on demand via
 * {@code POST /api/admin/jobs/userSeedingJob/launch} ({@code BatchAdminController}). The two
 * paths are deliberately consistent &mdash; identical user IDs, names and types &mdash; so
 * that whichever runs first wins and the other becomes a harmless idempotent no-op (PR-22).
 *
 * <h2>Password and identity data sourcing</h2>
 * The source {@code DUSRSECJ.jcl} stored two 8-byte plaintext literals ({@code "PASSWORDA"}
 * for admins, {@code "PASSWORDU"} for users). Per <strong>AAP PR-17</strong> the modernized
 * seeding uses the single literal {@code "PASSWORD"} for all 10 users (the canonical
 * demonstration credential used by the parity tests), BCrypt-hashed into the 60-character
 * {@code sec_usr_pwd} column. First/last names are taken <em>verbatim</em> (uppercase) from
 * the {@code DUSRSECJ.jcl} in-stream block (e.g. {@code MARGARET GOLD}, {@code LEE TING}),
 * matching {@code V4__seed_users.sql} exactly &mdash; the placeholder sample names in this
 * file's design note are intentionally <em>not</em> used, preserving functional/data parity
 * with the authoritative reference (goal G1).
 *
 * <h2>Refactoring rules enforced</h2>
 * <ul>
 *   <li><b>PR-17</b> (BCrypt for all passwords): every password is stored as a distinct
 *       60-char BCrypt hash produced by {@code passwordEncoder.encode("PASSWORD")} &mdash;
 *       never plaintext. BCrypt's embedded 22-char random salt guarantees that all 10 hashes
 *       differ even though the raw password is identical.</li>
 *   <li><b>PR-19</b> (role-mapping fidelity): the 5 admins are stored with
 *       {@code userType = 'A'} ({@code ROLE_ADMIN}) and the 5 standard users with
 *       {@code userType = 'U'} ({@code ROLE_USER}); the {@code GrantedAuthority} mapping
 *       itself is performed downstream by {@code User.getAuthorities()} /
 *       {@code CustomAuthorityMapper}.</li>
 *   <li><b>PR-22</b> (idempotent re-runs): {@code userRepository.existsById(userId)} skips
 *       users that already exist, so the job is safe to re-run and coexists with the Flyway
 *       seed.</li>
 *   <li><b>PR-24</b> (unit-of-work boundary): the tasklet executes inside a single
 *       transaction managed by the injected {@link PlatformTransactionManager}, mirroring the
 *       implicit CICS {@code SYNCPOINT}.</li>
 *   <li><b>PR-28</b> (Jakarta/Spring 6 namespace): only Jakarta EE 10 / Spring Framework 6.1
 *       annotations are used.</li>
 *   <li><b>PR-29</b> (constructor injection): all four collaborators are {@code final} and
 *       injected through the Lombok {@code @RequiredArgsConstructor}-generated constructor;
 *       no field injection.</li>
 * </ul>
 *
 * <p><strong>Auto-configuration boundary:</strong> {@code @EnableBatchProcessing} is
 * intentionally absent. Spring Boot 3.2 auto-configures the {@link JobRepository},
 * {@code JobLauncher}, {@code JobRegistry} (which makes this {@link Job} launchable by name)
 * and {@code PlatformTransactionManager}; adding {@code @EnableBatchProcessing} would disable
 * that auto-configuration. See {@code BatchConfig} for details.</p>
 *
 * @see com.carddemo.entity.User
 * @see com.carddemo.repository.UserRepository
 * @see org.springframework.batch.core.step.tasklet.Tasklet
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class UserSeedingJobConfig {

    /**
     * Logical name of the seeding {@link Job} bean. Registered with the Spring Batch
     * {@code JobRegistry} by Spring Boot auto-configuration so it can be launched by name
     * through the {@code BatchAdminController} ({@code POST /api/admin/jobs/userSeedingJob/launch}).
     */
    public static final String JOB_NAME = "userSeedingJob";

    /** Logical name of the single tasklet-based seeding {@link Step}. */
    private static final String STEP_NAME = "userSeedingStep";

    /**
     * Canonical raw password (PR-17) BCrypt-encoded once per user. All 10 default users share
     * this literal per the AAP demonstration semantics; each receives a distinct salted hash.
     * Operational hardening should rehash with unique, per-user secrets post-deployment.
     */
    private static final String DEFAULT_RAW_PASSWORD = "PASSWORD";

    /** User-type discriminator for administrators (PR-19: maps to {@code ROLE_ADMIN}). */
    private static final String USER_TYPE_ADMIN = "A";

    /** User-type discriminator for standard users (PR-19: maps to {@code ROLE_USER}). */
    private static final String USER_TYPE_STANDARD = "U";

    /**
     * Administrator user IDs &mdash; verbatim from the {@code DUSRSECJ.jcl} in-stream SYSIN
     * block (and identical to {@code V4__seed_users.sql}).
     */
    private static final String[] ADMIN_USER_IDS = {
            "ADMIN001", "ADMIN002", "ADMIN003", "ADMIN004", "ADMIN005"
    };

    /** Administrator first names, positionally aligned with {@link #ADMIN_USER_IDS}. */
    private static final String[] ADMIN_FIRST_NAMES = {
            "MARGARET", "RUSSELL", "RAYMOND", "EMMANUEL", "GRANVILLE"
    };

    /** Administrator last names, positionally aligned with {@link #ADMIN_USER_IDS}. */
    private static final String[] ADMIN_LAST_NAMES = {
            "GOLD", "RUSSELL", "WHITMORE", "CASGRAIN", "LACHAPELLE"
    };

    /**
     * Standard user IDs &mdash; verbatim from the {@code DUSRSECJ.jcl} in-stream SYSIN block
     * (and identical to {@code V4__seed_users.sql}).
     */
    private static final String[] STANDARD_USER_IDS = {
            "USER0001", "USER0002", "USER0003", "USER0004", "USER0005"
    };

    /** Standard-user first names, positionally aligned with {@link #STANDARD_USER_IDS}. */
    private static final String[] USER_FIRST_NAMES = {
            "LAWRENCE", "AJITH", "LAURITZ", "AVERARDO", "LEE"
    };

    /** Standard-user last names, positionally aligned with {@link #STANDARD_USER_IDS}. */
    private static final String[] USER_LAST_NAMES = {
            "THOMAS", "KUMAR", "ALME", "MAZZI", "TING"
    };

    /** Total number of default users seeded by this job (5 admins + 5 standard users). */
    private static final int DEFAULT_USER_COUNT = ADMIN_USER_IDS.length + STANDARD_USER_IDS.length;

    /** Spring Batch metadata repository (auto-configured by Spring Boot). */
    private final JobRepository jobRepository;

    /**
     * Transaction manager bracketing the tasklet's unit of work &mdash; the {@code SYNCPOINT}
     * equivalent (PR-24).
     */
    private final PlatformTransactionManager transactionManager;

    /** Spring Data JPA repository used for the idempotent {@code existsById}/{@code save} loop. */
    private final UserRepository userRepository;

    /**
     * Password encoder (a {@code BCryptPasswordEncoder} bean from {@code SecurityConfig}) used to
     * hash {@link #DEFAULT_RAW_PASSWORD} for each user (PR-17).
     */
    private final PasswordEncoder passwordEncoder;

    /**
     * Defines the {@link Tasklet} that seeds the {@value #DEFAULT_USER_COUNT} default users.
     *
     * <p>The tasklet builds all users in memory (5 admins, then 5 standard users), then
     * iterates persisting each one only when it does not already exist (PR-22). The number of
     * rows actually inserted is recorded on the {@link StepContribution} write-count so it
     * surfaces in the Spring Batch {@code BATCH_STEP_EXECUTION} metadata, giving operators the
     * same visibility the IDCAMS {@code SYSPRINT} record count provided in the original
     * {@code DUSRSECJ} REPRO step.</p>
     *
     * @return a single-shot tasklet that idempotently seeds the default users and returns
     *         {@link RepeatStatus#FINISHED}
     */
    @Bean
    public Tasklet userSeedingTasklet() {
        return (StepContribution contribution, ChunkContext chunkContext) -> {
            log.info("UserSeedingJob ({}) starting - seeding {} default users (BCrypt, PR-17)",
                    JOB_NAME, DEFAULT_USER_COUNT);

            List<User> users = new ArrayList<>(DEFAULT_USER_COUNT);

            // 5 administrator users (PR-19: userType 'A' -> ROLE_ADMIN).
            for (int i = 0; i < ADMIN_USER_IDS.length; i++) {
                users.add(buildUser(
                        ADMIN_USER_IDS[i],
                        ADMIN_FIRST_NAMES[i],
                        ADMIN_LAST_NAMES[i],
                        USER_TYPE_ADMIN));
            }

            // 5 standard users (PR-19: userType 'U' -> ROLE_USER).
            for (int i = 0; i < STANDARD_USER_IDS.length; i++) {
                users.add(buildUser(
                        STANDARD_USER_IDS[i],
                        USER_FIRST_NAMES[i],
                        USER_LAST_NAMES[i],
                        USER_TYPE_STANDARD));
            }

            int seeded = 0;
            int skipped = 0;
            for (User user : users) {
                String userId = user.getUserId();
                // PR-22: idempotent re-run - never clobber an existing user (e.g. one already
                // inserted by the V4__seed_users.sql Flyway migration).
                if (userRepository.existsById(userId)) {
                    skipped++;
                    log.debug("User {} already exists - skipping (idempotent)", userId);
                } else {
                    userRepository.save(user);
                    seeded++;
                    log.info("Seeded user: id={} type={}", userId, user.getUserType());
                }
            }

            log.info("UserSeedingJob ({}) completed - seeded={} skipped={}",
                    JOB_NAME, seeded, skipped);
            contribution.incrementWriteCount(seeded);
            return RepeatStatus.FINISHED;
        };
    }

    /**
     * Builds a single {@link User} entity with a freshly BCrypt-hashed password.
     *
     * <p>PR-17: the password is hashed via {@link #passwordEncoder} (never stored as
     * plaintext); each invocation of {@code encode(...)} yields a distinct salted 60-char hash.
     * PR-19: {@code userType} is persisted verbatim ({@code 'A'} or {@code 'U'}); the mapping to
     * a Spring Security {@code GrantedAuthority} happens downstream in {@code User.getAuthorities()}.
     * Audit columns ({@code createdAt}/{@code updatedAt}/{@code createdBy}/{@code updatedBy}) are
     * left unset so the schema-level {@code DEFAULT CURRENT_TIMESTAMP} / nullable defaults apply,
     * matching {@code V4__seed_users.sql}.</p>
     *
     * @param userId    8-character primary key (e.g. {@code "ADMIN001"})
     * @param firstName user first name (max 20 chars per {@code SEC-USR-FNAME PIC X(20)})
     * @param lastName  user last name (max 20 chars per {@code SEC-USR-LNAME PIC X(20)})
     * @param userType  {@code "A"} for admin or {@code "U"} for standard user (PR-19)
     * @return a transient {@link User} ready to persist
     */
    private User buildUser(String userId, String firstName, String lastName, String userType) {
        return User.builder()
                .userId(userId)
                .firstName(firstName)
                .lastName(lastName)
                .secUsrPwd(passwordEncoder.encode(DEFAULT_RAW_PASSWORD)) // PR-17: BCrypt hash
                .userType(userType)
                .build();
    }

    /**
     * Builds the single tasklet-based {@link Step} for the seeding job.
     *
     * <p>Binding the {@link #transactionManager} to the step runs the whole 10-user seed inside
     * one transaction boundary (PR-24).</p>
     *
     * @return the {@code userSeedingStep} bean
     */
    @Bean
    public Step userSeedingStep() {
        return new StepBuilder(STEP_NAME, jobRepository)
                .tasklet(userSeedingTasklet(), transactionManager)
                .build();
    }

    /**
     * Builds the {@link Job} bean ({@link #JOB_NAME}) consisting of the single seeding step.
     *
     * <p>Spring Boot auto-configuration registers this job with the {@code JobRegistry}, making
     * it launchable by name through the {@code BatchAdminController} or as part of an initial
     * deployment bootstrap.</p>
     *
     * @return the {@code userSeedingJob} bean
     */
    @Bean
    public Job userSeedingJob() {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(userSeedingStep())
                .build();
    }
}
