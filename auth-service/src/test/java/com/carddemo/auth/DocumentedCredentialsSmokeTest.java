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
import com.carddemo.common.dto.SessionContext;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * :purpose: Smoke-test the credentials README-target.md publishes under "Default
 *     Login Credentials" against the Flyway-seeded database, so the documented
 *     primary entry path can never silently stop working (every documented
 *     credential once returned 403 because the seeded hash carried no
 *     ``{bcrypt}`` prefix). Each documented user must authenticate with the
 *     documented password through the wired ``PasswordEncoder``, resolve to the
 *     documented user type, and route to the documented post-login menu
 *     transaction (``CA00`` for an administrator, ``CM00`` otherwise).
 * :note: The README also documents that authentication is case-SENSITIVE, unlike
 *     the legacy ``COSGN00C`` compare which upper-cased the entered password
 *     [app/cbl/COSGN00C.cbl:L135-136]; that documented behavior change is
 *     asserted here too. Runs against real PostgreSQL and Redis containers with
 *     Flyway enabled, so the assertions exercise the seed migration itself.
 */
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=none")
class DocumentedCredentialsSmokeTest extends AbstractIntegrationTest {

    /** :purpose: Password published for every seeded user in README-target.md. */
    private static final String DOCUMENTED_PASSWORD = "PASSWORD";

    /** :purpose: Administrator ids seeded by ``V2__seed_security_users.sql``. */
    private static final List<String> ADMIN_IDS =
            List.of("ADMIN001", "ADMIN002", "ADMIN003", "ADMIN004", "ADMIN005");

    /** :purpose: Regular-user ids seeded by ``V2__seed_security_users.sql``. */
    private static final List<String> USER_IDS =
            List.of("USER0001", "USER0002", "USER0003", "USER0004", "USER0005");

    @Autowired
    private AuthenticationService authenticationService;

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
    @DisplayName("Every documented administrator credential signs on as an admin and routes to CA00")
    void documentedAdminCredentialsSignOn() {
        for (String userId : ADMIN_IDS) {
            SignonResponseDto response = signon(userId, DOCUMENTED_PASSWORD);

            assertThat(response.getUserId()).as("userId echoed for %s", userId).isEqualTo(userId);
            assertThat(response.getUserType()).as("user type for %s", userId)
                    .isEqualTo(SessionContext.UserType.CDEMO_USRTYP_ADMIN);
            assertThat(response.getRedirectTarget()).as("menu transaction for %s", userId)
                    .isEqualTo("CA00");
        }
    }

    @Test
    @DisplayName("Every documented regular-user credential signs on as a user and routes to CM00")
    void documentedUserCredentialsSignOn() {
        for (String userId : USER_IDS) {
            SignonResponseDto response = signon(userId, DOCUMENTED_PASSWORD);

            assertThat(response.getUserId()).as("userId echoed for %s", userId).isEqualTo(userId);
            assertThat(response.getUserType()).as("user type for %s", userId)
                    .isEqualTo(SessionContext.UserType.CDEMO_USRTYP_USER);
            assertThat(response.getRedirectTarget()).as("menu transaction for %s", userId)
                    .isEqualTo("CM00");
        }
    }

    @Test
    @DisplayName("The documented lower-cased user id is accepted (COSGN00C upper-cases the id)")
    void documentedUserIdIsUpperCasedLikeTheLegacyProgram() {
        SignonResponseDto response = signon("admin001", DOCUMENTED_PASSWORD);

        assertThat(response.getUserId()).isEqualTo("ADMIN001");
        assertThat(response.getUserType()).isEqualTo(SessionContext.UserType.CDEMO_USRTYP_ADMIN);
    }

    @Test
    @DisplayName("The documented case-sensitive password behavior holds: 'password' is rejected")
    void documentedPasswordCaseSensitivityHolds() {
        assertThatExceptionOfType(ResponseStatusException.class)
                .isThrownBy(() -> signon("ADMIN001", "password"))
                .withMessageContaining("Wrong Password. Try again ...");
    }

    @Test
    @DisplayName("An id outside the documented set is rejected with the COBOL not-found message")
    void undocumentedUserIdIsRejected() {
        assertThatExceptionOfType(ResponseStatusException.class)
                .isThrownBy(() -> signon("NOPE9999", DOCUMENTED_PASSWORD))
                .withMessageContaining("User not found. Try again ...");
    }
}
