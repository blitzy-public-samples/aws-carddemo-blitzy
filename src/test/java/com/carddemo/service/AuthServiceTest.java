package com.carddemo.service;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;

import com.carddemo.dto.SignonRequest;
import com.carddemo.dto.SignonResponse;
import com.carddemo.entity.User;
import com.carddemo.repository.UserRepository;
import com.carddemo.security.JwtTokenProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure <strong>Mockito</strong> unit test for {@link AuthService} &mdash; the Java/Spring
 * re-expression of the legacy CICS COBOL sign-on program {@code app/cbl/COSGN00C.cbl}.
 *
 * <h2>What this test pins (sign-on parity + security hardening &mdash; AAP &sect;0.6.7, &sect;0.7.2)</h2>
 * <p>{@code COSGN00C} drove the 3270 sign-on panel: it upper-cased <em>both</em> the entered user id
 * and password ({@code FUNCTION UPPER-CASE(USERIDI)} / {@code UPPER-CASE(PASSWDI)},
 * {@code COSGN00C} lines&nbsp;132&ndash;136), performed a keyed {@code READ} of the {@code USRSEC}
 * security file, compared the stored {@code SEC-USR-PWD} against the input, and on a match routed by
 * {@code CDEMO-USER-TYPE} &mdash; {@code 'A'} {@code XCTL}-ed to the administrator menu
 * ({@code COADM01C}, lines&nbsp;230&ndash;234) and any other type to the regular menu
 * ({@code COMEN01C}, lines&nbsp;235&ndash;239). A wrong password ({@code "Wrong Password. Try again ..."})
 * or an unknown user ({@code "User not found. Try again ..."}) re-displayed the panel.</p>
 *
 * <p>The migration preserves that behavior while hardening it: the plaintext {@code SEC-USR-PWD}
 * comparison becomes a BCrypt (strength&nbsp;12) verification performed by Spring Security's
 * {@link AuthenticationManager}, and the COMMAREA hand-off becomes a stateless HS256 JWT minted by
 * {@link JwtTokenProvider}. Because the manager is mocked here, this test focuses precisely on the
 * <strong>service's own responsibilities</strong>:</p>
 * <ol>
 *   <li><strong>Credential normalization</strong> &mdash; BOTH the user id and the password are
 *       upper-cased before they reach the {@link AuthenticationManager}, reproducing the COBOL
 *       {@code FUNCTION UPPER-CASE} and making the seeded {@code PASSWORD} credential match its
 *       stored BCrypt hash even when the caller typed {@code "password"}.</li>
 *   <li><strong>Role derivation</strong> &mdash; user type {@code 'A'} &rarr; {@code "ADMIN"},
 *       everything else (here {@code 'U'}) &rarr; {@code "USER"}, mirroring the
 *       {@code COADM01C}/{@code COMEN01C} routing decision.</li>
 *   <li><strong>JWT issuance</strong> &mdash; on success the (mocked) token is returned verbatim with
 *       the {@code "Bearer"} scheme and the upper-cased identity, via the two-argument
 *       {@link JwtTokenProvider#generateToken(String, String)} overload (only the user id and type are
 *       known at sign-on).</li>
 *   <li><strong>401 propagation</strong> &mdash; the service never catches authentication failures; a
 *       Spring {@link AuthenticationException} (e.g. {@link BadCredentialsException}) propagates
 *       untouched so {@code GlobalExceptionHandler} can answer HTTP&nbsp;401, and <em>no</em> token is
 *       minted and the security row is <em>never</em> read on that path.</li>
 * </ol>
 *
 * <h2>Test character</h2>
 * <p>This is a <em>pure</em> unit test: {@code @ExtendWith(MockitoExtension.class)} with constructor
 * injection of mocks &mdash; <strong>no</strong> Spring context, <strong>no</strong> database, and no
 * I/O. The three collaborators declared by the production constructor
 * ({@link AuthenticationManager}, {@link JwtTokenProvider}, {@link UserRepository}) are all mocked and
 * {@code @InjectMocks} wires them in. Stubbing is kept minimal per test so that Mockito's default
 * {@code STRICT_STUBS} policy passes with no {@code UnnecessaryStubbingException} &mdash; in
 * particular the failure-path test stubs only the manager and asserts the other collaborators are
 * never touched.</p>
 *
 * <h2>PII &amp; secret discipline (AAP &sect;0.6.8, &sect;0.7.1)</h2>
 * <p>No real credential, BCrypt hash, CVV, or SSN appears in any fixture. The only token value
 * asserted is the deliberately fake stub {@value #STUB_TOKEN}; passwords are never logged or printed,
 * and the BCrypt hash is intentionally <em>not</em> asserted (verification happens inside the mocked
 * {@link AuthenticationManager}).</p>
 *
 * @see AuthService
 * @see <a href="file:app/cbl/COSGN00C.cbl">app/cbl/COSGN00C.cbl (sign-on, source-of-truth)</a>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService — COSGN00C sign-on parity: uppercasing, role derivation, JWT issuance, 401")
class AuthServiceTest {

    /** Deliberately fake, non-decodable token returned by the mocked {@link JwtTokenProvider}. */
    private static final String STUB_TOKEN = "test.jwt.token";

    /** The fixed HTTP authorization scheme the service must place in {@link SignonResponse#tokenType()}. */
    private static final String BEARER = "Bearer";

    /** Spring Security entry point that performs the BCrypt credential check (mocked). */
    @Mock
    private AuthenticationManager authenticationManager;

    /** Mints the stateless HS256 sign-on token (mocked). */
    @Mock
    private JwtTokenProvider jwtTokenProvider;

    /** Reads the authenticated {@code users} row to obtain the legacy {@code SEC-USR-TYPE} (mocked). */
    @Mock
    private UserRepository userRepository;

    /** Class under test; constructor-injected with the three mocks above. */
    @InjectMocks
    private AuthService authService;

    /**
     * Builds a {@link User} carrying the given id and legacy user-type code.
     *
     * <p>The persisted password column holds a BCrypt hash in production, but this unit test never
     * inspects it (BCrypt verification is delegated to the mocked {@link AuthenticationManager}), so a
     * clearly non-secret placeholder is supplied. No real credential or hash appears in this test.</p>
     *
     * @param userId   the 8-character user id (already upper-cased, as the service looks it up)
     * @param userType the legacy {@code SEC-USR-TYPE} code ({@code "A"} admin / {@code "U"} user)
     * @return a populated {@link User} suitable for stubbing {@code userRepository.findById(...)}
     */
    private static User userWithType(String userId, String userType) {
        return new User(userId, "Test", "User", "stored-hash-not-evaluated", userType);
    }

    /**
     * Phase 2 &mdash; a valid administrator sign-on returns the bearer JWT and derives the
     * {@code ADMIN} role, reproducing the {@code COSGN00C} {@code 'A'} &rarr; {@code COADM01C}
     * routing. Also pins the Phase&nbsp;5 interaction discipline: exactly one authentication and one
     * token mint (via the two-argument overload).
     */
    @Test
    @DisplayName("Valid admin credentials → issues bearer JWT and derives ADMIN role ('A' → COADM01C)")
    void signon_withValidAdminCredentials_issuesTokenAndDerivesAdminRole() {
        // Arrange — the manager authenticates successfully (the returned Authentication is irrelevant
        // to the service); the security row reports user-type 'A'; the provider mints the stub token.
        when(authenticationManager.authenticate(any())).thenReturn(mock(Authentication.class));
        when(userRepository.findById("ADMIN001")).thenReturn(Optional.of(userWithType("ADMIN001", "A")));
        when(jwtTokenProvider.generateToken("ADMIN001", "A")).thenReturn(STUB_TOKEN);

        // Act
        SignonResponse resp = authService.signon(new SignonRequest("ADMIN001", "PASSWORD"));

        // Assert — every field of the stateless-auth handshake is wired correctly.
        assertThat(resp.token()).isEqualTo(STUB_TOKEN);
        assertThat(resp.tokenType()).isEqualTo(BEARER);
        assertThat(resp.userId()).isEqualTo("ADMIN001");
        assertThat(resp.userType()).isEqualTo("A");
        assertThat(resp.role()).isEqualTo("ADMIN");

        // Assert — interaction discipline: one credential check and exactly one token mint, using the
        // two-argument generateToken(userId, userType) overload (custId/acctId/cardNum unknown at signon).
        verify(authenticationManager).authenticate(any());
        verify(jwtTokenProvider).generateToken("ADMIN001", "A");
    }

    /**
     * Phase 3 (critical parity) &mdash; lowercase input is upper-cased on BOTH the user id and the
     * password before authentication, exactly as {@code COSGN00C} applied {@code FUNCTION UPPER-CASE}
     * to {@code USERIDI} and {@code PASSWDI}. Also confirms the post-auth security read uses the
     * upper-cased key and that user-type {@code 'U'} derives the {@code USER} role
     * ({@code COSGN00C} else &rarr; {@code COMEN01C}).
     */
    @Test
    @DisplayName("Lowercase input is upper-cased (userId AND password) before auth; 'U' → USER role")
    void signon_uppercasesBothUserIdAndPassword_beforeAuthentication() {
        // Arrange — caller supplies LOWERCASE "user0001"/"password"; the service must upper-case both.
        when(authenticationManager.authenticate(any())).thenReturn(mock(Authentication.class));
        when(userRepository.findById("USER0001")).thenReturn(Optional.of(userWithType("USER0001", "U")));
        when(jwtTokenProvider.generateToken("USER0001", "U")).thenReturn(STUB_TOKEN);

        // Act
        SignonResponse resp = authService.signon(new SignonRequest("user0001", "password"));

        // Assert — capture the credential token actually handed to the AuthenticationManager.
        ArgumentCaptor<Authentication> authCaptor = ArgumentCaptor.forClass(Authentication.class);
        verify(authenticationManager).authenticate(authCaptor.capture());
        Authentication submitted = authCaptor.getValue();

        // BOTH the user id (principal / name) AND the password (credentials) must be upper-cased —
        // this is the key COSGN00C parity point (UPPER-CASE of USERIDI *and* PASSWDI).
        assertThat(submitted.getPrincipal()).isEqualTo("USER0001");
        assertThat(submitted.getName()).isEqualTo("USER0001");
        assertThat(submitted.getCredentials()).isEqualTo("PASSWORD");

        // The post-authentication security-file read must use the upper-cased key (keyed READ USRSEC).
        verify(userRepository).findById("USER0001");

        // Role derivation: 'U' (and any non-'A' type) → USER, mirroring the COMEN01C branch.
        assertThat(resp.userId()).isEqualTo("USER0001");
        assertThat(resp.userType()).isEqualTo("U");
        assertThat(resp.role()).isEqualTo("USER");
    }

    /**
     * Phase 4 &mdash; when the {@link AuthenticationManager} rejects the credential (the migration's
     * uniform replacement for {@code COSGN00C}'s distinct {@code "Wrong Password ..."} /
     * {@code "User not found ..."} outcomes), the resulting {@link AuthenticationException} propagates
     * untouched (mapped to HTTP&nbsp;401 by {@code GlobalExceptionHandler}); no JWT is minted and the
     * security row is never read.
     */
    @Test
    @DisplayName("Bad credentials → AuthenticationException propagates (401); no token minted, no DB read")
    void signon_withBadCredentials_propagatesAuthenticationExceptionAndMintsNoToken() {
        // Arrange — the manager throws BadCredentialsException (a subclass of AuthenticationException).
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        // Act + Assert — the service must NOT swallow it; it surfaces as a Spring AuthenticationException.
        assertThatThrownBy(() -> authService.signon(new SignonRequest("ADMIN001", "WRONG")))
                .isInstanceOf(AuthenticationException.class);

        // No JWT may be issued on the failure path (target the two-argument overload used at signon),
        // and the security-file read must never run when authentication fails.
        verify(jwtTokenProvider, never()).generateToken(any(), any());
        verifyNoInteractions(userRepository);
    }

    /**
     * Phase 4 (defensive edge case) &mdash; a {@code null} credential must surface as a Spring
     * {@link AuthenticationException} (HTTP&nbsp;401), <em>not</em> a {@link NullPointerException}.
     *
     * <p>Bean Validation ({@code @NotBlank}) already rejects blank input at the controller boundary,
     * so the service's null-guards (the {@code request.userId() == null ? null : ...} and
     * {@code request.password() == null ? null : ...} ternaries) are purely defensive. This test
     * exercises those guards directly: a {@code SignonRequest(null, null)} is normalized to a
     * {@code UsernamePasswordAuthenticationToken(null, null)} and handed to the manager, which
     * (mirroring a real {@code DaoAuthenticationProvider}) rejects it &mdash; confirming the service
     * delegates cleanly and propagates the failure untouched rather than throwing an NPE. As on every
     * failure path, no token is minted and the security row is never read.</p>
     */
    @Test
    @DisplayName("Null credentials → AuthenticationException (401), never NullPointerException; no token, no DB read")
    void signon_withNullCredentials_propagatesAuthenticationExceptionWithoutNpe() {
        // Arrange — a null principal/credential cannot authenticate; the manager rejects it.
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        // Act + Assert — the service must reach the manager (no NPE in its null-guards) and let the
        // resulting AuthenticationException propagate to the 401 mapping.
        assertThatThrownBy(() -> authService.signon(new SignonRequest(null, null)))
                .isInstanceOf(AuthenticationException.class);

        // No JWT and no security-file read on the failure path.
        verify(jwtTokenProvider, never()).generateToken(any(), any());
        verifyNoInteractions(userRepository);
    }
}
