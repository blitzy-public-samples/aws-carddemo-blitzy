/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cardemo.repository;

import com.cardemo.entity.UserSecurity;
import com.cardemo.common.enums.UserType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JPA repository integration test for {@link UserSecurityRepository}.
 *
 * <p>Validates the data access layer for the USRSEC VSAM KSDS dataset
 * (80-byte SEC-USER-DATA from {@code CSUSR01Y.cpy}) against a real
 * PostgreSQL 16 database using Testcontainers. Tests cover all COBOL
 * CICS VSAM access patterns mapped from the original mainframe programs:</p>
 *
 * <ul>
 *   <li><b>CRUD operations</b> — {@code EXEC CICS READ / WRITE / REWRITE / DELETE}
 *       from COSGN00C.cbl (sign-on), COUSR01C.cbl (add), COUSR02C.cbl (update),
 *       COUSR03C.cbl (delete)</li>
 *   <li><b>Authentication lookup</b> — SEC-USR-ID based auth from COSGN00C.cbl</li>
 *   <li><b>BCrypt password storage</b> — plaintext SEC-USR-PWD PIC X(08) →
 *       BCrypt hashed varchar(72)</li>
 *   <li><b>UserType enum persistence</b> — 88-level conditions
 *       CDEMO-USRTYP-ADMIN/USER from COCOM01Y.cpy</li>
 *   <li><b>Paginated listing</b> — STARTBR/READNEXT browse from COUSR00C.cbl</li>
 *   <li><b>Optimistic locking</b> — READ UPDATE → REWRITE from COUSR02C.cbl
 *       mapped to JPA {@code @Version}</li>
 * </ul>
 *
 * @see UserSecurity
 * @see UserSecurityRepository
 * @see UserType
 */
@DataJpaTest
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserSecurityRepositoryTest {

    /**
     * PostgreSQL 16 container matching the production database version.
     * Managed by Testcontainers JUnit 5 lifecycle — auto-started before
     * the first test method and auto-stopped after the last.
     */
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    /**
     * Overrides datasource properties from {@code application-test.yml}
     * to point at the Testcontainers-managed PostgreSQL instance.
     * The YAML uses {@code jdbc:tc:postgresql:} JDBC URL format with
     * {@code ContainerDatabaseDriver}, but since this test manages its
     * own container via {@code @Container}, we override with standard
     * PostgreSQL JDBC driver and the container's actual JDBC URL.
     */
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name",
                () -> "org.postgresql.Driver");
    }

    @Autowired
    private UserSecurityRepository userSecurityRepository;

    /**
     * BCryptPasswordEncoder instantiated directly because {@code @DataJpaTest}
     * is a JPA slice test that does not auto-configure Spring Security beans.
     * This encoder is used to hash test passwords for UserSecurity entities
     * and to verify BCrypt password matching in authentication-related tests.
     */
    private static final BCryptPasswordEncoder PASSWORD_ENCODER =
            new BCryptPasswordEncoder();

    /** Plaintext password for the admin test user (original COBOL: SEC-USR-PWD PIC X(08)). */
    private static final String ADMIN_PLAINTEXT_PWD = "ADMIN01";

    /** Plaintext password for the regular test user. */
    private static final String USER_PLAINTEXT_PWD = "USER0001";

    /**
     * Admin user ID — 8-char, space-padded to match COBOL PIC X(08) layout.
     * SEC-USR-ID preserves trailing space for exact VSAM key fidelity.
     */
    private static final String ADMIN_USER_ID = "ADMIN01 ";

    /** Regular user ID — exactly 8 characters, no padding needed. */
    private static final String REGULAR_USER_ID = "USER0001";

    /** BCrypt hash computed fresh for each test to avoid cross-test coupling. */
    private String adminBcryptHash;

    /** BCrypt hash computed fresh for each test to avoid cross-test coupling. */
    private String userBcryptHash;

    /**
     * Test setup — clears the repository and inserts two well-known test
     * users (Admin + Regular) with BCrypt-hashed passwords.
     *
     * <p>Maps to the initial state expected by all COBOL CICS programs
     * that access the USRSEC file: at least one admin user and one
     * regular user exist for authentication and CRUD testing.</p>
     */
    @BeforeEach
    void setUp() {
        userSecurityRepository.deleteAll();

        adminBcryptHash = PASSWORD_ENCODER.encode(ADMIN_PLAINTEXT_PWD);
        userBcryptHash = PASSWORD_ENCODER.encode(USER_PLAINTEXT_PWD);

        UserSecurity adminUser = new UserSecurity(
                ADMIN_USER_ID, "System", "Administrator",
                adminBcryptHash, UserType.ADMIN);
        UserSecurity regularUser = new UserSecurity(
                REGULAR_USER_ID, "Regular", "User",
                userBcryptHash, UserType.USER);

        userSecurityRepository.saveAll(List.of(adminUser, regularUser));
        userSecurityRepository.flush();
    }

    // ---------------------------------------------------------------
    // Phase 3: CRUD Operation Tests
    // ---------------------------------------------------------------

    /**
     * Verifies {@code findById} returns the correct user by 8-char
     * SEC-USR-ID primary key.
     *
     * <p>Maps to COSGN00C.cbl:
     * {@code EXEC CICS READ DATASET(WS-USRSEC-FILE) RIDFLD(WS-USER-ID)}</p>
     */
    @Test
    @DisplayName("findById returns user by 8-char SEC-USR-ID primary key")
    void testFindByIdReturnsUser() {
        Optional<UserSecurity> result =
                userSecurityRepository.findById(ADMIN_USER_ID);

        assertThat(result).isPresent();
        UserSecurity user = result.get();
        assertThat(user.getUserId()).isEqualTo(ADMIN_USER_ID);
        assertThat(user.getFirstName()).isEqualTo("System");
        assertThat(user.getLastName()).isEqualTo("Administrator");
        assertThat(user.getUserType()).isEqualTo(UserType.ADMIN);
    }

    /**
     * Verifies {@code findById} returns {@link Optional#empty()} when
     * no user exists with the given ID.
     *
     * <p>Maps to COSGN00C.cbl response code 13 (DFHRESP(NOTFND)) —
     * user not found in USRSEC file.</p>
     */
    @Test
    @DisplayName("findById returns empty for non-existent user")
    void testFindByIdReturnsEmptyForNonExistent() {
        Optional<UserSecurity> result =
                userSecurityRepository.findById("UNKNOWN1");

        assertThat(result).isEmpty();
    }

    /**
     * Verifies {@code save} persists a new user with a BCrypt-hashed
     * password (not plaintext).
     *
     * <p>Maps to COUSR01C.cbl:
     * {@code EXEC CICS WRITE DATASET(WS-USRSEC-FILE) FROM(SEC-USER-DATA)}</p>
     */
    @Test
    @DisplayName("save persists new user with BCrypt hashed password")
    void testSavePersistsNewUserWithBCryptPassword() {
        String newUserId = "NEWUSR01";
        String rawPassword = "secret01";
        String hashedPassword = PASSWORD_ENCODER.encode(rawPassword);

        UserSecurity newUser = new UserSecurity(
                newUserId, "New", "UserRecord", hashedPassword, UserType.USER);
        userSecurityRepository.save(newUser);

        Optional<UserSecurity> result =
                userSecurityRepository.findById(newUserId);
        assertThat(result).isPresent();

        UserSecurity persisted = result.get();
        assertThat(persisted.getUserId()).isEqualTo(newUserId);
        assertThat(persisted.getFirstName()).isEqualTo("New");
        assertThat(persisted.getLastName()).isEqualTo("UserRecord");
        // Password is stored as BCrypt hash, NEVER plaintext
        assertThat(persisted.getPassword()).isNotEqualTo(rawPassword);
        assertThat(persisted.getPassword()).startsWith("$2a$");
        assertThat(PASSWORD_ENCODER.matches(rawPassword,
                persisted.getPassword())).isTrue();
    }

    /**
     * Verifies {@code save} updates an existing user's fields.
     *
     * <p>Maps to COUSR02C.cbl:
     * {@code EXEC CICS READ UPDATE ... → EXEC CICS REWRITE}</p>
     */
    @Test
    @DisplayName("save updates existing user")
    void testSaveUpdatesExistingUser() {
        Optional<UserSecurity> found =
                userSecurityRepository.findById(ADMIN_USER_ID);
        assertThat(found).isPresent();

        UserSecurity user = found.get();
        user.setLastName("UpdatedAdmin");
        userSecurityRepository.save(user);

        Optional<UserSecurity> updated =
                userSecurityRepository.findById(ADMIN_USER_ID);
        assertThat(updated).isPresent();
        assertThat(updated.get().getLastName()).isEqualTo("UpdatedAdmin");
        // First name unchanged
        assertThat(updated.get().getFirstName()).isEqualTo("System");
    }

    /**
     * Verifies {@code deleteById} removes a user from the database.
     *
     * <p>Maps to COUSR03C.cbl:
     * {@code EXEC CICS DELETE DATASET(WS-USRSEC-FILE) RIDFLD(WS-USER-ID)}</p>
     */
    @Test
    @DisplayName("deleteById removes user")
    void testDeleteByIdRemovesUser() {
        assertThat(userSecurityRepository.findById(REGULAR_USER_ID)).isPresent();

        userSecurityRepository.deleteById(REGULAR_USER_ID);
        userSecurityRepository.flush();

        assertThat(userSecurityRepository.findById(REGULAR_USER_ID)).isEmpty();
    }

    // ---------------------------------------------------------------
    // Phase 4: Authentication Lookup Tests
    // ---------------------------------------------------------------

    /**
     * Verifies {@code findByUserId} returns the user for authentication.
     *
     * <p>Maps to COSGN00C.cbl authentication flow: look up user by
     * SEC-USR-ID, then verify password externally (BCrypt match).</p>
     */
    @Test
    @DisplayName("findByUserId returns user for authentication lookup")
    void testFindByUserIdReturnsUser() {
        Optional<UserSecurity> result =
                userSecurityRepository.findByUserId(ADMIN_USER_ID);

        assertThat(result).isPresent();
        UserSecurity user = result.get();
        assertThat(user.getUserId()).isEqualTo(ADMIN_USER_ID);
        assertThat(user.getFirstName()).isEqualTo("System");
        assertThat(user.getLastName()).isEqualTo("Administrator");
        assertThat(user.getUserType()).isEqualTo(UserType.ADMIN);
        // Verify password can be matched with BCrypt
        assertThat(PASSWORD_ENCODER.matches(ADMIN_PLAINTEXT_PWD,
                user.getPassword())).isTrue();
    }

    /**
     * Verifies {@code findByUserId} returns {@link Optional#empty()}
     * for a non-existent user ID.
     *
     * <p>Maps to COSGN00C.cbl response code 13 — user not found
     * during authentication attempt.</p>
     */
    @Test
    @DisplayName("findByUserId returns empty for non-existent user ID")
    void testFindByUserIdReturnsEmptyForNonExistent() {
        Optional<UserSecurity> result =
                userSecurityRepository.findByUserId("NONEXIST");

        assertThat(result).isEmpty();
    }

    // ---------------------------------------------------------------
    // Phase 5: BCrypt Password Storage Tests
    // ---------------------------------------------------------------

    /**
     * Verifies that BCrypt-hashed passwords persist in the database
     * and are never stored as plaintext.
     *
     * <p>Per AAP Section 0.7.4: "Passwords must transition from
     * plaintext (SEC-USR-PWD) to BCrypt-hashed storage while
     * preserving the authentication flow."</p>
     */
    @Test
    @DisplayName("BCrypt hashed password persists in database — not plaintext")
    void testBCryptHashedPasswordPersists() {
        String plaintext = "TestPass";
        String hash = PASSWORD_ENCODER.encode(plaintext);

        UserSecurity user = new UserSecurity(
                "BCRYPT01", "BCrypt", "Test", hash, UserType.USER);
        userSecurityRepository.saveAndFlush(user);

        Optional<UserSecurity> result =
                userSecurityRepository.findById("BCRYPT01");
        assertThat(result).isPresent();

        String storedPassword = result.get().getPassword();
        // (1) Verify BCrypt prefix ($2a$ for standard BCrypt)
        assertThat(storedPassword).startsWith("$2a$");
        // (2) Standard BCrypt hash length is 60 characters
        assertThat(storedPassword).hasSize(60);
        // (3) Stored password is NOT the plaintext value
        assertThat(storedPassword).isNotEqualTo(plaintext);
        // (4) BCrypt match confirms correct hash stored
        assertThat(PASSWORD_ENCODER.matches(plaintext,
                storedPassword)).isTrue();
    }

    /**
     * Verifies that the {@code varchar(72)} column accommodates a
     * standard 60-character BCrypt hash and stores/retrieves it exactly.
     *
     * <p>The column width was expanded from COBOL PIC X(08) (8 chars
     * plaintext) to 72 characters to accommodate BCrypt hashes (60 chars
     * standard, with 12 chars headroom for future algorithm changes).</p>
     */
    @Test
    @DisplayName("BCrypt password field accommodates 72-char column width")
    void testBCryptPasswordFieldAccommodates72CharWidth() {
        String hash = PASSWORD_ENCODER.encode("longpass");
        // Standard BCrypt hash is always 60 characters
        assertThat(hash).hasSize(60);

        UserSecurity user = new UserSecurity(
                "WIDTH01 ", "Width", "Test", hash, UserType.USER);
        userSecurityRepository.saveAndFlush(user);

        Optional<UserSecurity> result =
                userSecurityRepository.findById("WIDTH01 ");
        assertThat(result).isPresent();
        // Hash stored and retrieved exactly — no truncation in varchar(72)
        assertThat(result.get().getPassword()).isEqualTo(hash);
        assertThat(result.get().getPassword()).hasSize(60);
    }

    // ---------------------------------------------------------------
    // Phase 6: UserType Enum Tests
    // ---------------------------------------------------------------

    /**
     * Verifies that {@link UserType#ADMIN} persists and retrieves
     * correctly through the JPA converter.
     *
     * <p>Maps COBOL 88-level condition:
     * {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'} from COCOM01Y.cpy.
     * The converter stores {@code 'A'} in the single-char
     * {@code sec_usr_type VARCHAR(1)} column and reconstructs
     * {@link UserType#ADMIN} on read.</p>
     */
    @Test
    @DisplayName("UserType.ADMIN persists and retrieves correctly "
            + "via @Enumerated(EnumType.STRING)")
    void testUserTypeAdminPersistsCorrectly() {
        UserSecurity user = new UserSecurity(
                "TYPADM01", "Admin", "TypeTest",
                PASSWORD_ENCODER.encode("test"), UserType.ADMIN);
        userSecurityRepository.saveAndFlush(user);

        Optional<UserSecurity> result =
                userSecurityRepository.findById("TYPADM01");
        assertThat(result).isPresent();
        assertThat(result.get().getUserType()).isEqualTo(UserType.ADMIN);
    }

    /**
     * Verifies that {@link UserType#USER} persists and retrieves
     * correctly through the JPA converter.
     *
     * <p>Maps COBOL 88-level condition:
     * {@code 88 CDEMO-USRTYP-USER VALUE 'U'} from COCOM01Y.cpy.</p>
     */
    @Test
    @DisplayName("UserType.USER persists and retrieves correctly")
    void testUserTypeUserPersistsCorrectly() {
        UserSecurity user = new UserSecurity(
                "TYPUSR01", "RegUser", "TypeTest",
                PASSWORD_ENCODER.encode("test"), UserType.USER);
        userSecurityRepository.saveAndFlush(user);

        Optional<UserSecurity> result =
                userSecurityRepository.findById("TYPUSR01");
        assertThat(result).isPresent();
        assertThat(result.get().getUserType()).isEqualTo(UserType.USER);
    }

    // ---------------------------------------------------------------
    // Phase 7: Paginated User Listing Test
    // ---------------------------------------------------------------

    /**
     * Verifies {@code findAll(Pageable)} supports paginated user listing
     * for the admin browse screen.
     *
     * <p>Maps to COUSR00C.cbl STARTBR/READNEXT/READPREV browse pattern:
     * the COBOL program displays 10 users per page (USER-REC OCCURS 10)
     * with PF7 (previous) and PF8 (next) page navigation.</p>
     */
    @Test
    @DisplayName("findAll with Pageable supports paginated user listing "
            + "for admin browse")
    void testFindAllWithPageableForPaginatedListing() {
        // 2 users already inserted by setUp()
        Page<UserSecurity> page =
                userSecurityRepository.findAll(PageRequest.of(0, 10));

        assertThat(page).isNotNull();
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getTotalPages()).isEqualTo(1);
        assertThat(page.getNumber()).isZero();
        assertThat(page.getSize()).isEqualTo(10);
    }

    // ---------------------------------------------------------------
    // Phase 8: Optimistic Locking (@Version) Test
    // ---------------------------------------------------------------

    /**
     * Verifies that the JPA {@code @Version} annotation enables
     * optimistic locking for user updates.
     *
     * <p>Maps to COUSR02C.cbl:
     * {@code EXEC CICS READ UPDATE ... → EXEC CICS REWRITE}
     * The COBOL pattern uses record-level locking during the update
     * window; the Java equivalent uses {@code @Version}-based
     * optimistic locking to detect concurrent modifications.</p>
     */
    @Test
    @DisplayName("@Version enables optimistic locking for user updates")
    void testVersionOptimisticLocking() {
        // Read existing user — initial version should be 0 after INSERT
        Optional<UserSecurity> found =
                userSecurityRepository.findById(ADMIN_USER_ID);
        assertThat(found).isPresent();

        UserSecurity user = found.get();
        assertThat(user.getVersion()).isEqualTo(0L);

        // Update a field and save with explicit flush to trigger
        // the UPDATE SQL that increments the version column
        user.setFirstName("Updated");
        UserSecurity saved = userSecurityRepository.saveAndFlush(user);

        // Version should be incremented by Hibernate after UPDATE
        assertThat(saved.getVersion()).isGreaterThan(0L);
    }
}
