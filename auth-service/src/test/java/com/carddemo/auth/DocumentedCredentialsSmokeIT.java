/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
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
package com.carddemo.auth;

import com.carddemo.auth.dto.SignonRequestDto;
import com.carddemo.auth.dto.SignonResponseDto;
import com.carddemo.auth.service.AuthenticationService;
import com.carddemo.auth.repository.SecurityUserRepository;
import com.carddemo.auth.security.SeedCredentialProvisioner;
import com.carddemo.common.domain.SecurityUser;
import com.carddemo.common.dto.SessionContext;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * :purpose: Smoke-test the seeded USRSEC entry path against the Flyway-seeded
 *     database, with a credential this deployment INJECTS rather than one the
 *     repository publishes. Each seeded user must authenticate with the injected
 *     password through the wired ``PasswordEncoder``, resolve to its documented user
 *     type, and route to the documented post-login menu transaction (``CA00`` for an
 *     administrator, ``CM00`` otherwise) - the documented primary entry path can
 *     never silently stop working (every seeded credential once returned 403 because
 *     the seeded hash carried no ``{bcrypt}`` prefix).
 * :note: The former legacy fixture password 'PASSWORD' is asserted to be REFUSED.
 *     ``V10__lock_seeded_credentials.sql`` replaced the shared hash of that string
 *     with a sentinel no input can match, because a known and documented
 *     administrator credential must not be active on any deployment of this
 *     repository; ``SeedCredentialProvisioner`` then writes the injected password
 *     over the sentinel when the deployment opts in, which is what this class
 *     configures for itself through ``@TestPropertySource``.
 * :note: The README also documents that authentication is case-SENSITIVE, unlike the
 *     legacy ``COSGN00C`` compare which upper-cased the entered password
 *     [app/cbl/COSGN00C.cbl:L135-136]; that documented behavior change is asserted
 *     here too. Runs against real PostgreSQL and Redis containers with Flyway
 *     enabled, so the assertions exercise the seed migration and the provisioner.
 */
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "carddemo.security.seed-credentials.enabled=true",
        "carddemo.security.seed-credentials.password=" + DocumentedCredentialsSmokeIT.PROVISIONED_PASSWORD
})
// PER_CLASS so the restore below can be an instance method with the repository
// injected. This class is the only one that PROVISIONS credentials, and the
// integration tests share one migrated database container, so it must hand that
// database back in the state the migration set left it in.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentedCredentialsSmokeIT extends AbstractIntegrationTest {

    /**
     * :purpose: Credential this test injects for the seeded accounts, standing in for
     *     the deployment-supplied CARDDEMO_SEED_USER_PASSWORD. Eight characters,
     *     matching the frozen ``SEC-USR-PWD PIC X(08)`` width.
     */
    static final String PROVISIONED_PASSWORD = "Tk7Qz2Rm";

    /**
     * :purpose: The legacy fixture password V3 used to seed for every account. It must
     *     no longer authenticate anything.
     */
    private static final String RETIRED_DEFAULT_PASSWORD = "PASSWORD";

    /** :purpose: Administrator ids seeded by ``V3__seed_test_data.sql``. */
    private static final List<String> ADMIN_IDS =
            List.of("ADMIN001", "ADMIN002", "ADMIN003", "ADMIN004", "ADMIN005");

    /** :purpose: Regular-user ids seeded by ``V3__seed_test_data.sql``. */
    private static final List<String> USER_IDS =
            List.of("USER0001", "USER0002", "USER0003", "USER0004", "USER0005");

    @Autowired
    private AuthenticationService authenticationService;

    @Autowired
    private SecurityUserRepository securityUserRepository;

    /**
     * :purpose: Put the locked sentinel back on every seeded account, so the shared
     *     migrated database is left exactly as the migration set produced it.
     * :note: Without this, whichever integration test class ran after this one saw
     *     provisioned credentials instead of the locked ones the schema ships, and
     *     asserted against a state no deployment starts in.
     */
    @AfterAll
    void restoreLockedSentinel() {
        List<SecurityUser> restored = new ArrayList<>();
        for (SecurityUser user : securityUserRepository.findAll()) {
            user.setSecUsrPwd(SeedCredentialProvisioner.LOCKED_SENTINEL);
            restored.add(user);
        }
        securityUserRepository.saveAll(restored);
    }

    /**
     * :purpose: Sign on through the real service against the seeded database.
     * :param userId: the user id to present.
     * :param password: the raw password to present.
     * :returns: the sign-on response.
     */
    private SignonResponseDto signon(String userId, String password) {
        SignonRequestDto request = new SignonRequestDto();
        request.setUserId(userId);
        request.setPassword(password);
        // The service now takes the request and creates the session itself only AFTER the
        // credential verifies, so a session must never be pre-attached here.
        return authenticationService.signon(request, new MockHttpServletRequest());
    }

    @Test
    @DisplayName("Every seeded administrator signs on with the INJECTED credential and routes to CA00")
    void provisionedAdminCredentialsSignOn() {
        for (String userId : ADMIN_IDS) {
            SignonResponseDto response = signon(userId, PROVISIONED_PASSWORD);

            assertThat(response.getUserId()).as("userId echoed for %s", userId).isEqualTo(userId);
            assertThat(response.getUserType()).as("user type for %s", userId)
                    .isEqualTo(SessionContext.UserType.CDEMO_USRTYP_ADMIN);
            assertThat(response.getRedirectTarget()).as("menu transaction for %s", userId)
                    .isEqualTo("CA00");
        }
    }

    @Test
    @DisplayName("Every seeded regular user signs on with the INJECTED credential and routes to CM00")
    void provisionedUserCredentialsSignOn() {
        for (String userId : USER_IDS) {
            SignonResponseDto response = signon(userId, PROVISIONED_PASSWORD);

            assertThat(response.getUserId()).as("userId echoed for %s", userId).isEqualTo(userId);
            assertThat(response.getUserType()).as("user type for %s", userId)
                    .isEqualTo(SessionContext.UserType.CDEMO_USRTYP_USER);
            assertThat(response.getRedirectTarget()).as("menu transaction for %s", userId)
                    .isEqualTo("CM00");
        }
    }

    /**
     * :purpose: The retired shared default must not authenticate ANY seeded account.
     *     This is the assertion that keeps the finding closed: if a future change
     *     re-seeds the old hash, or the lockdown migration is dropped, every id below
     *     starts authenticating again and this test fails.
     */
    @Test
    @DisplayName("The retired default password 'PASSWORD' is refused for every seeded account")
    void retiredDefaultPasswordIsRefusedEverywhere() {
        for (String userId : ADMIN_IDS) {
            assertThatExceptionOfType(ResponseStatusException.class)
                    .as("administrator %s must not accept the retired default", userId)
                    .isThrownBy(() -> signon(userId, RETIRED_DEFAULT_PASSWORD))
                    .withMessageContaining("Wrong Password. Try again ...");
        }
        for (String userId : USER_IDS) {
            assertThatExceptionOfType(ResponseStatusException.class)
                    .as("user %s must not accept the retired default", userId)
                    .isThrownBy(() -> signon(userId, RETIRED_DEFAULT_PASSWORD))
                    .withMessageContaining("Wrong Password. Try again ...");
        }
    }

    @Test
    @DisplayName("The documented lower-cased user id is accepted (COSGN00C upper-cases the id)")
    void documentedUserIdIsUpperCasedLikeTheLegacyProgram() {
        SignonResponseDto response = signon("admin001", PROVISIONED_PASSWORD);

        assertThat(response.getUserId()).isEqualTo("ADMIN001");
        assertThat(response.getUserType()).isEqualTo(SessionContext.UserType.CDEMO_USRTYP_ADMIN);
    }

    @Test
    @DisplayName("The documented case-sensitive password behavior holds: the lower-cased form is rejected")
    void documentedPasswordCaseSensitivityHolds() {
        assertThatExceptionOfType(ResponseStatusException.class)
                .isThrownBy(() -> signon("ADMIN001", PROVISIONED_PASSWORD.toLowerCase()))
                .withMessageContaining("Wrong Password. Try again ...");
    }

    @Test
    @DisplayName("An id outside the seeded set is rejected with the COBOL not-found message")
    void undocumentedUserIdIsRejected() {
        assertThatExceptionOfType(ResponseStatusException.class)
                .isThrownBy(() -> signon("NOPE9999", PROVISIONED_PASSWORD))
                .withMessageContaining("User not found. Try again ...");
    }
}
