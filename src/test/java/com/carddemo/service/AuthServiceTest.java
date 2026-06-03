package com.carddemo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.dto.auth.LoginRequest;
import com.carddemo.dto.auth.LoginResponse;
import com.carddemo.entity.User;
import com.carddemo.repository.UserRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit test for {@link AuthService} — the Spring service that replaces the COBOL
 * {@code app/cbl/COSGN00C.cbl} sign-on program.
 *
 * <p>These are pure Mockito unit tests: no Spring context and no database are
 * involved. The two {@code final} collaborators are mocked
 * ({@link AuthenticationManager} and {@link UserRepository}) and injected via
 * constructor injection ({@code @InjectMocks} over the Lombok
 * {@code @RequiredArgsConstructor}, honouring PR-29). The two {@code @Value} fields
 * ({@code jwtSecret}, {@code jwtExpirationMs}) are not constructor parameters, so they
 * are populated reflectively in {@link #setUp()}; {@code jwtSecret} is deliberately
 * &ge; 32 bytes so the HS256 key derivation ({@code Keys.hmacShaKeyFor}) used by the
 * real token generator does not raise {@code WeakKeyException}.</p>
 *
 * <h2>What this test verifies (and why it mocks the AuthenticationManager)</h2>
 * <p>The production {@code AuthService} does <b>not</b> compare passwords itself.
 * Per <b>PR-17</b> it delegates credential verification to the Spring Security
 * {@link AuthenticationManager} (configured in {@code SecurityConfig} with
 * {@code DaoAuthenticationProvider} + {@code BCryptPasswordEncoder}); the BCrypt
 * {@code matches(rawPassword, storedHash)} call therefore happens <em>inside</em> the
 * mocked manager. Consequently the service-tier PR-17 obligation asserted here is
 * twofold: (a) the service invokes {@code authenticationManager.authenticate(...)}
 * exactly once, and (b) it hands the <em>raw, case-preserved</em> password (never a
 * plaintext {@code String.equals} against the stored hash) to that manager — proven
 * with an {@link ArgumentCaptor}. The actual BCrypt matching and the
 * {@code 'A' -> ROLE_ADMIN} / {@code 'U' -> ROLE_USER} authority mapping (PR-19) are
 * exercised by {@code UserDetailsServiceImpl} / {@code CustomAuthorityMapper} tests
 * and the security integration tests; at this layer PR-19 is verified through the raw
 * {@code userType} carried on the {@link LoginResponse} plus the {@link User} entity's
 * own authority contract.</p>
 *
 * <h2>COBOL parity ({@code COSGN00C} {@code 0500-VALIDATE-USERID-PASSWORD})</h2>
 * <ul>
 *   <li>user id uppercased before lookup ({@code COSGN00C.cbl:L132-L134}); password
 *       NOT uppercased (intentional security improvement, AAP &sect;0.6.8);</li>
 *   <li>record-not-found &rarr; {@code "User not found. Try again ..."}
 *       ({@code COSGN00C.cbl:L249});</li>
 *   <li>password mismatch &rarr; {@code "Wrong Password. Try again ..."}
 *       ({@code COSGN00C.cbl:L242});</li>
 *   <li>any other failure &rarr; {@code "Unable to verify the User ..."}
 *       ({@code COSGN00C.cbl:L254}).</li>
 * </ul>
 *
 * @see AuthService
 * @see com.carddemo.security.UserDetailsServiceImpl
 * @see com.carddemo.security.CustomAuthorityMapper
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService — replaces COBOL COSGN00C signon program (PR-17 BCrypt delegation, PR-19 role mapping)")
class AuthServiceTest {

    /** Default admin user id (COBOL convention {@code ADMIN001}-{@code ADMIN005}). */
    private static final String ADMIN_ID = "ADMIN001";

    /** Default regular user id (COBOL convention {@code USER0001}-{@code USER0005}). */
    private static final String USER_ID = "USER0001";

    /** The single shared default password from Tech Spec &sect;6.4 / AAP &sect;0.6.8. */
    private static final String RAW_PASSWORD = "PASSWORD";

    /**
     * A realistic-looking 60-character BCrypt hash ({@code $2a$} prefix) used only as a
     * fixture value for {@code User.secUsrPwd}. It is an obvious, non-secret test
     * literal — the mocked {@link AuthenticationManager} never actually matches against
     * it, so its value is immaterial beyond shape.
     */
    private static final String BCRYPT_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    /**
     * HS256 signing secret for the unit test. Deliberately &ge; 32 bytes so the real
     * {@code Keys.hmacShaKeyFor(...)} call inside {@code AuthService.generateToken}
     * succeeds. This is an obvious fake value, never a real credential.
     */
    private static final String JWT_SECRET =
            "unit-test-only-jwt-signing-secret-key-0123456789-abcdefghijklmnop";

    /** Token validity window (one hour) injected into the {@code jwt.expiration-ms} field. */
    private static final long JWT_EXPIRATION_MS = 3_600_000L;

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private AuthService authService;

    /**
     * Populates the two {@code @Value}-injected fields that Mockito's constructor
     * injection cannot supply (they are not {@code final} constructor parameters).
     * Runs after {@code @InjectMocks} has constructed {@code authService} with the
     * mocked collaborators.
     */
    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(authService, "jwtSecret", JWT_SECRET);
        ReflectionTestUtils.setField(authService, "jwtExpirationMs", JWT_EXPIRATION_MS);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Builds a {@link User} fixture using the entity's no-arg constructor and setters,
     * matching the fields {@code AuthService} reads on the success path
     * ({@code userId}, {@code userType}, {@code firstName}, {@code lastName}) plus the
     * stored BCrypt hash.
     */
    private User createUser(String userId, String firstName, String lastName,
                            String hashedPwd, String userType) {
        User u = new User();
        u.setUserId(userId);
        u.setFirstName(firstName);
        u.setLastName(lastName);
        u.setSecUsrPwd(hashedPwd);
        u.setUserType(userType);
        return u;
    }

    /**
     * Returns an authenticated {@link Authentication} whose {@link Authentication#getName()}
     * resolves to {@code userId} — mirroring what the real {@code DaoAuthenticationProvider}
     * returns after a successful BCrypt match, so {@code AuthService} re-loads the entity by
     * that canonical id.
     */
    private Authentication authenticatedAs(String userId, String role) {
        return new UsernamePasswordAuthenticationToken(
                userId, null, List.of(new SimpleGrantedAuthority(role)));
    }

    // ------------------------------------------------------------ test groups

    @Nested
    @DisplayName("Successful authentication")
    class SuccessfulAuth {

        @Test
        @DisplayName("Should authenticate ADMIN user with valid credentials and issue a signed JWT")
        void shouldAuthenticateAdminWithValidCredentials() {
            // given
            User adminUser = createUser(ADMIN_ID, "Alice", "Admin", BCRYPT_HASH, "A");
            when(authenticationManager.authenticate(any()))
                    .thenReturn(authenticatedAs(ADMIN_ID, "ROLE_ADMIN"));
            when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.of(adminUser));

            // when
            LoginResponse response = authService.authenticate(new LoginRequest(ADMIN_ID, RAW_PASSWORD));

            // then
            assertThat(response).isNotNull();
            assertThat(response.userId()).isEqualTo(ADMIN_ID);
            assertThat(response.userType()).isEqualTo("A");
            assertThat(response.firstName()).isEqualTo("Alice");
            assertThat(response.lastName()).isEqualTo("Admin");
            assertThat(response.token()).isNotBlank();
            assertThat(response.expiresAt()).isNotNull().isAfter(Instant.now());
            verify(authenticationManager).authenticate(any(Authentication.class));
        }

        @Test
        @DisplayName("Should authenticate regular USER with valid credentials")
        void shouldAuthenticateRegularUserWithValidCredentials() {
            // given
            User regularUser = createUser(USER_ID, "Bob", "User", BCRYPT_HASH, "U");
            when(authenticationManager.authenticate(any()))
                    .thenReturn(authenticatedAs(USER_ID, "ROLE_USER"));
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(regularUser));

            // when
            LoginResponse response = authService.authenticate(new LoginRequest(USER_ID, RAW_PASSWORD));

            // then
            assertThat(response).isNotNull();
            assertThat(response.userId()).isEqualTo(USER_ID);
            assertThat(response.userType()).isEqualTo("U");
            assertThat(response.token()).isNotBlank();
        }
    }

    @Nested
    @DisplayName("Failed authentication")
    class FailedAuth {

        @Test
        @DisplayName("Should reject with COBOL 'User not found' message when the user id is unknown (WS-RESP-CD=13)")
        void shouldRejectWhenUserNotFound() {
            // given — provider signals an unknown principal
            when(authenticationManager.authenticate(any()))
                    .thenThrow(new UsernameNotFoundException("no such user"));

            // when / then — re-mapped to BadCredentialsException with the exact COBOL message
            assertThatThrownBy(() -> authService.authenticate(new LoginRequest("UNKNOWN1", RAW_PASSWORD)))
                    .isInstanceOf(BadCredentialsException.class)
                    .hasMessage("User not found. Try again ...");

            // the entity load is never reached on a pre-load authentication failure
            verify(userRepository, never()).findById(anyString());
        }

        @Test
        @DisplayName("Should reject with COBOL 'Wrong Password' message when the password does not match")
        void shouldRejectWhenPasswordDoesNotMatch() {
            // given — provider rejects the BCrypt comparison
            when(authenticationManager.authenticate(any()))
                    .thenThrow(new BadCredentialsException("bad credentials"));

            // when / then
            assertThatThrownBy(() -> authService.authenticate(new LoginRequest(ADMIN_ID, "WRONGPWD")))
                    .isInstanceOf(BadCredentialsException.class)
                    .hasMessage("Wrong Password. Try again ...");

            verify(userRepository, never()).findById(anyString());
        }

        @Test
        @DisplayName("Should reject with COBOL 'Unable to verify' message on an unexpected service failure (WHEN OTHER)")
        void shouldRejectWhenAuthenticationServiceFails() {
            // given — e.g. an underlying data-access error surfaced by the provider
            when(authenticationManager.authenticate(any()))
                    .thenThrow(new AuthenticationServiceException("backend unavailable"));

            // when / then
            assertThatThrownBy(() -> authService.authenticate(new LoginRequest(ADMIN_ID, RAW_PASSWORD)))
                    .isInstanceOf(AuthenticationServiceException.class)
                    .hasMessage("Unable to verify the User ...");

            verify(userRepository, never()).findById(anyString());
        }

        @Test
        @DisplayName("Should reject when the user vanishes between authentication and entity reload (post-auth inconsistency)")
        void shouldRejectWhenUserMissingAfterSuccessfulAuthentication() {
            // given — authentication succeeds but the follow-up keyed read finds nothing
            when(authenticationManager.authenticate(any()))
                    .thenReturn(authenticatedAs(ADMIN_ID, "ROLE_ADMIN"));
            when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() -> authService.authenticate(new LoginRequest(ADMIN_ID, RAW_PASSWORD)))
                    .isInstanceOf(BadCredentialsException.class)
                    .hasMessage("User not found. Try again ...");
        }
    }

    @Nested
    @DisplayName("Role mapping (PR-19)")
    class RoleMapping {

        @Test
        @DisplayName("Should propagate userType 'A' (maps to ROLE_ADMIN downstream)")
        void shouldMapUserTypeAdminToRoleAdmin() {
            // given
            User adminUser = createUser(ADMIN_ID, "Alice", "Admin", BCRYPT_HASH, "A");
            when(authenticationManager.authenticate(any()))
                    .thenReturn(authenticatedAs(ADMIN_ID, "ROLE_ADMIN"));
            when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.of(adminUser));

            // when
            LoginResponse response = authService.authenticate(new LoginRequest(ADMIN_ID, RAW_PASSWORD));

            // then — the response carries the raw 'A' code; the entity contract resolves it to ROLE_ADMIN
            assertThat(response.userType()).isEqualTo("A");
            assertThat(adminUser.getAuthorities())
                    .extracting(GrantedAuthority::getAuthority)
                    .containsExactly("ROLE_ADMIN");
        }

        @Test
        @DisplayName("Should propagate userType 'U' (maps to ROLE_USER downstream)")
        void shouldMapUserTypeUserToRoleUser() {
            // given
            User regularUser = createUser(USER_ID, "Bob", "User", BCRYPT_HASH, "U");
            when(authenticationManager.authenticate(any()))
                    .thenReturn(authenticatedAs(USER_ID, "ROLE_USER"));
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(regularUser));

            // when
            LoginResponse response = authService.authenticate(new LoginRequest(USER_ID, RAW_PASSWORD));

            // then
            assertThat(response.userType()).isEqualTo("U");
            assertThat(regularUser.getAuthorities())
                    .extracting(GrantedAuthority::getAuthority)
                    .containsExactly("ROLE_USER");
        }
    }

    @Nested
    @DisplayName("BCrypt credential verification (PR-17)")
    class BCryptVerification {

        @Test
        @DisplayName("Should delegate credential verification to the AuthenticationManager exactly once (never a plaintext compare)")
        void shouldDelegateCredentialVerificationToAuthenticationManager() {
            // given
            User adminUser = createUser(ADMIN_ID, "Alice", "Admin", BCRYPT_HASH, "A");
            when(authenticationManager.authenticate(any()))
                    .thenReturn(authenticatedAs(ADMIN_ID, "ROLE_ADMIN"));
            when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.of(adminUser));

            // when
            authService.authenticate(new LoginRequest(ADMIN_ID, RAW_PASSWORD));

            // then — exactly one delegation to the BCrypt-backed manager
            verify(authenticationManager, times(1)).authenticate(any(Authentication.class));
        }

        @Test
        @DisplayName("Should hand the RAW password (not the stored hash) to the AuthenticationManager for BCrypt matching")
        void shouldHandRawPasswordToAuthenticationManagerNotAPlaintextComparison() {
            // given
            User adminUser = createUser(ADMIN_ID, "Alice", "Admin", BCRYPT_HASH, "A");
            when(authenticationManager.authenticate(any()))
                    .thenReturn(authenticatedAs(ADMIN_ID, "ROLE_ADMIN"));
            when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.of(adminUser));

            // when
            authService.authenticate(new LoginRequest(ADMIN_ID, RAW_PASSWORD));

            // then — capture and inspect the credential token submitted for verification
            ArgumentCaptor<Authentication> captor = ArgumentCaptor.forClass(Authentication.class);
            verify(authenticationManager).authenticate(captor.capture());
            Authentication submitted = captor.getValue();

            assertThat(submitted).isInstanceOf(UsernamePasswordAuthenticationToken.class);
            // the RAW cleartext password flows to the provider for BCrypt.matches(...)
            assertThat(submitted.getCredentials()).isEqualTo(RAW_PASSWORD);
            // the service never substitutes/compares the stored BCrypt hash itself (PR-17)
            assertThat(submitted.getCredentials()).isNotEqualTo(BCRYPT_HASH);
            assertThat(submitted.getPrincipal()).isEqualTo(ADMIN_ID);
        }
    }

    @Nested
    @DisplayName("Edge cases (COBOL FUNCTION UPPER-CASE parity)")
    class EdgeCases {

        @Test
        @DisplayName("Should normalize the user id to uppercase before authentication (COSGN00C L132-L134)")
        void shouldNormalizeUserIdToUppercaseBeforeAuthentication() {
            // given — lowercase id on input; the resolved principal must be uppercased
            User adminUser = createUser(ADMIN_ID, "Alice", "Admin", BCRYPT_HASH, "A");
            when(authenticationManager.authenticate(any()))
                    .thenReturn(authenticatedAs(ADMIN_ID, "ROLE_ADMIN"));
            when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.of(adminUser));

            // when
            authService.authenticate(new LoginRequest("admin001", RAW_PASSWORD));

            // then
            ArgumentCaptor<Authentication> captor = ArgumentCaptor.forClass(Authentication.class);
            verify(authenticationManager).authenticate(captor.capture());
            assertThat(captor.getValue().getPrincipal()).isEqualTo("ADMIN001");
        }

        @Test
        @DisplayName("Should preserve password case sensitivity (Java does NOT uppercase the password, unlike COBOL L135-L136)")
        void shouldPreservePasswordCaseSensitivity() {
            // given — a mixed-case password must reach the provider unchanged
            String mixedCasePassword = "PassWord";
            User adminUser = createUser(ADMIN_ID, "Alice", "Admin", BCRYPT_HASH, "A");
            when(authenticationManager.authenticate(any()))
                    .thenReturn(authenticatedAs(ADMIN_ID, "ROLE_ADMIN"));
            when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.of(adminUser));

            // when
            authService.authenticate(new LoginRequest(ADMIN_ID, mixedCasePassword));

            // then — credentials are byte-for-byte the submitted value (no uppercasing)
            ArgumentCaptor<Authentication> captor = ArgumentCaptor.forClass(Authentication.class);
            verify(authenticationManager).authenticate(captor.capture());
            assertThat(captor.getValue().getCredentials()).isEqualTo("PassWord");
        }
    }
}
