package com.carddemo.service;

import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.carddemo.dto.SignonRequest;
import com.carddemo.dto.SignonResponse;
import com.carddemo.entity.User;
import com.carddemo.exception.ResourceNotFoundException;
import com.carddemo.repository.UserRepository;
import com.carddemo.security.JwtTokenProvider;

/**
 * Application sign-on service: the layered-monolith re-expression of the legacy
 * CICS sign-on program {@code app/cbl/COSGN00C.cbl}.
 *
 * <h2>What it replaces (COBOL source-of-truth)</h2>
 * <p>{@code COSGN00C} performed a pseudo-conversational sign-on against the VSAM
 * {@code USRSEC} dataset. Its essential logic (verified against the source) is:</p>
 * <ol>
 *   <li>Reject a blank user id ({@code "Please enter User ID ..."}) or blank
 *       password ({@code "Please enter Password ..."}).</li>
 *   <li><strong>Upper-case both the entered user id and password</strong>
 *       ({@code MOVE FUNCTION UPPER-CASE(USERIDI) ...} / {@code UPPER-CASE(PASSWDI) ...},
 *       {@code COSGN00C} lines&nbsp;132&ndash;136), making legacy passwords
 *       <em>case-insensitive</em>.</li>
 *   <li>Keyed {@code READ} of {@code USRSEC}; on a hit, compare the stored
 *       {@code SEC-USR-PWD} to the upper-cased input. On match, populate the
 *       COMMAREA identity ({@code CDEMO-USER-ID}, {@code CDEMO-USER-TYPE}) and
 *       {@code XCTL} to the administrator menu ({@code COADM01C}) when
 *       {@code CDEMO-USRTYP-ADMIN} ({@code 'A'}) or the user menu
 *       ({@code COMEN01C}) otherwise. A password mismatch, a not-found record
 *       ({@code RESP=13}), or any other response yields a distinct error
 *       message.</li>
 * </ol>
 *
 * <h2>How the migration re-expresses it (AAP &sect;0.3.2, &sect;0.4.1.3, &sect;0.6.7)</h2>
 * <ul>
 *   <li><strong>Plaintext compare &rarr; BCrypt verify.</strong> The
 *       {@code SEC-USR-PWD = WS-USER-PWD} equality test is replaced by a BCrypt
 *       (strength&nbsp;12) verification performed by the Spring Security
 *       {@link AuthenticationManager} (a {@code DaoAuthenticationProvider} wired
 *       to {@code CustomUserDetailsService} and {@code BCryptPasswordEncoder(12)}
 *       in {@code SecurityConfig}). This service never hashes or compares
 *       passwords itself.</li>
 *   <li><strong>COMMAREA hand-off &rarr; stateless JWT.</strong> The COMMAREA
 *       identity fields ({@code COCOM01Y}: {@code CDEMO-USER-ID},
 *       {@code CDEMO-USER-TYPE}) become claims in an HS256-signed JWT minted by
 *       {@link JwtTokenProvider}. There is no server-side session and no
 *       {@code XCTL}; the client receives the token plus a role hint and routes
 *       itself.</li>
 *   <li><strong>Case-insensitive parity.</strong> Both the user id and the
 *       password are upper-cased before authentication, exactly as the COBOL did.
 *       This is mandatory: the generated user seed ({@code V4__seed_users.sql})
 *       stores {@code BCrypt(strength=12)} of the <em>upper-case</em>
 *       {@code "PASSWORD"}, so a client-supplied {@code "password"} only matches
 *       once upper-cased.</li>
 * </ul>
 *
 * <h2>Error handling &mdash; deliberately uniform</h2>
 * <p>When the credentials are wrong or the user does not exist, the
 * {@link AuthenticationManager} throws an
 * {@code org.springframework.security.core.AuthenticationException} (typically
 * {@code BadCredentialsException} or {@code UsernameNotFoundException}). This
 * service intentionally does <strong>not</strong> catch it: it propagates to
 * {@code GlobalExceptionHandler}, which maps it to <em>HTTP&nbsp;401</em> with a
 * single uniform message ({@code "Authentication failed."}). Unlike the legacy
 * program&rsquo;s distinct {@code "Wrong Password ..."} vs {@code "User not found ..."}
 * messages, the uniform response avoids credential/user enumeration &mdash; a
 * security hardening applied within functional parity.</p>
 *
 * <h2>Layering, transactions, and PII</h2>
 * <ul>
 *   <li><strong>Strict layering.</strong> {@code AuthController} &rarr;
 *       {@code AuthService} &rarr; ({@link AuthenticationManager},
 *       {@link JwtTokenProvider}, {@link UserRepository}). All sign-on business
 *       logic lives here; the controller is a thin HTTP adapter.</li>
 *   <li><strong>Constructor injection only.</strong> Replaces the COBOL static
 *       {@code CALL}/{@code XCTL} linkage; no field injection is used.</li>
 *   <li><strong>Read-only transaction.</strong> Sign-on only reads the
 *       {@code users} row, so {@link #signon(SignonRequest)} runs in a
 *       {@code @Transactional(readOnly = true)} boundary.</li>
 *   <li><strong>PII / secret suppression (AAP &sect;0.6.8, &sect;0.7.1).</strong>
 *       The raw or upper-cased password, the stored BCrypt hash, and the minted
 *       JWT are <em>never</em> logged or returned. Only the non-sensitive
 *       {@code userId} is logged, at {@code DEBUG}. {@link SignonResponse} has no
 *       password field by design.</li>
 * </ul>
 *
 * @see <a href="file:app/cbl/COSGN00C.cbl">app/cbl/COSGN00C.cbl (source-of-truth)</a>
 * @see JwtTokenProvider
 * @see com.carddemo.security.SecurityConfig
 * @see com.carddemo.security.CustomUserDetailsService
 */
@Service
public class AuthService {

    /**
     * SLF4J logger. Used only for non-sensitive diagnostics (the {@code userId});
     * credentials and tokens are never logged.
     */
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /**
     * Client-facing role name returned to administrators (legacy
     * {@code SEC-USR-TYPE} / {@code CDEMO-USER-TYPE} {@code 'A'}).
     *
     * <p>This is the <em>unprefixed</em> role hint carried in
     * {@link SignonResponse#role()} for client convenience. It is intentionally
     * distinct from the Spring Security authority {@code ROLE_ADMIN} (with the
     * mandatory {@code ROLE_} prefix) granted by {@code CustomUserDetailsService};
     * the prefixed authority is what {@code @PreAuthorize("hasRole('ADMIN')")}
     * resolves against, whereas this string is purely informational.</p>
     */
    private static final String ROLE_NAME_ADMIN = "ADMIN";

    /**
     * Client-facing role name returned to regular users (every {@code userType}
     * other than {@code 'A'}, mirroring the {@code COSGN00C} admin/user routing
     * where the non-admin branch transferred to {@code COMEN01C}).
     */
    private static final String ROLE_NAME_USER = "USER";

    /**
     * The HTTP authorization scheme paired with the issued bearer token; the
     * fixed value placed in {@link SignonResponse#tokenType()}.
     */
    private static final String TOKEN_TYPE_BEARER = "Bearer";

    /**
     * Spring Security entry point that performs the BCrypt credential check
     * (replacing the legacy {@code SEC-USR-PWD = WS-USER-PWD} plaintext compare).
     */
    private final AuthenticationManager authenticationManager;

    /**
     * Mints the HS256 JWT that carries the COMMAREA-equivalent identity claims
     * ({@code CDEMO-USER-ID} / {@code CDEMO-USER-TYPE}).
     */
    private final JwtTokenProvider jwtTokenProvider;

    /**
     * Reads the authenticated {@code users} row to obtain the legacy
     * {@code SEC-USR-TYPE} for token claims and role derivation (the migration
     * analogue of {@code COSGN00C}&rsquo;s {@code READ USRSEC ... } then
     * route-by-type).
     */
    private final UserRepository userRepository;

    /**
     * Creates the sign-on service with its collaborators.
     *
     * <p>All dependencies are injected through this single constructor (no field
     * injection), which replaces the COBOL static {@code CALL}/{@code XCTL}
     * linkage and keeps the service trivially unit-testable with mocks.</p>
     *
     * @param authenticationManager the Spring Security manager that verifies the
     *                              BCrypt credential (from {@code SecurityConfig})
     * @param jwtTokenProvider      the issuer of the stateless HS256 sign-on token
     * @param userRepository        the repository used to load the authenticated
     *                              user&rsquo;s persisted record
     */
    public AuthService(AuthenticationManager authenticationManager,
                       JwtTokenProvider jwtTokenProvider,
                       UserRepository userRepository) {
        this.authenticationManager = authenticationManager;
        this.jwtTokenProvider = jwtTokenProvider;
        this.userRepository = userRepository;
    }

    /**
     * Authenticates a user and issues a stateless JWT, porting the
     * {@code COSGN00C} sign-on flow.
     *
     * <p><strong>Algorithm (parity with {@code COSGN00C}):</strong></p>
     * <ol>
     *   <li>Upper-case the user id (trimmed, {@link Locale#ROOT}) so the lookup
     *       key matches {@code CustomUserDetailsService}&rsquo;s normalization and
     *       the legacy {@code UPPER-CASE(USERIDI)}.</li>
     *   <li>Upper-case the password ({@link Locale#ROOT}) to honor the legacy
     *       {@code UPPER-CASE(PASSWDI)} case-insensitive semantics; this is what
     *       makes the seeded {@code PASSWORD} credential match its BCrypt hash.</li>
     *   <li>Delegate the credential check to the {@link AuthenticationManager}.
     *       A bad password or unknown user raises an
     *       {@code AuthenticationException}, which is allowed to propagate and is
     *       translated to HTTP&nbsp;401 by {@code GlobalExceptionHandler}.</li>
     *   <li>On success, load the {@code users} row to read the legacy
     *       {@code SEC-USR-TYPE}, derive the client role hint, and mint the JWT
     *       carrying the {@code CDEMO-USER-ID}/{@code CDEMO-USER-TYPE} claims.</li>
     * </ol>
     *
     * <p>Bean Validation on {@link SignonRequest} ({@code @NotBlank}/{@code @Size})
     * already rejects blank input at the controller boundary with the legacy
     * messages; the null-guards here are purely defensive. A {@code null} credential
     * cannot authenticate, so it results in a 401 rather than a
     * {@link NullPointerException}.</p>
     *
     * @param request the sign-on request carrying the {@code userId} and
     *                {@code password}; must not be {@code null}
     * @return a {@link SignonResponse} containing the bearer token, the
     *         {@code "Bearer"} scheme, the upper-cased user id, the legacy
     *         single-character user type, and the derived role hint
     * @throws org.springframework.security.core.AuthenticationException if the
     *         credentials are invalid or the user does not exist (mapped to
     *         HTTP&nbsp;401)
     * @throws ResourceNotFoundException if, after a successful authentication, the
     *         user row cannot be re-read (a should-not-happen consistency guard)
     */
    @Transactional(readOnly = true)
    public SignonResponse signon(SignonRequest request) {
        // Step 1 - Upper-case the user id (trim + ROOT locale), mirroring
        // COSGN00C's UPPER-CASE(USERIDI) and CustomUserDetailsService's own
        // normalization so the post-authentication findById uses the identical key.
        final String userId = (request.userId() == null)
                ? null
                : request.userId().trim().toUpperCase(Locale.ROOT);

        // Step 2 - Upper-case the password (ROOT locale) to preserve the legacy
        // case-insensitive password semantics (COSGN00C UPPER-CASE(PASSWDI)). This
        // is REQUIRED: the user seed stores BCrypt(strength=12) of the upper-case
        // "PASSWORD", so a client-supplied "password" only matches once upper-cased.
        // The password value itself is never logged or stored.
        final String password = (request.password() == null)
                ? null
                : request.password().toUpperCase(Locale.ROOT);

        log.debug("Processing sign-on request for userId={}", userId);

        // Step 3 - Verify the credential via Spring Security (BCrypt strength 12),
        // replacing the legacy plaintext SEC-USR-PWD = WS-USER-PWD compare. The
        // return value is intentionally not used: the sole purpose of this call is
        // its side effect of throwing an AuthenticationException on failure, which
        // GlobalExceptionHandler maps to a uniform HTTP 401 (no user enumeration).
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(userId, password));

        // Step 4 - Reload the authenticated user to obtain the legacy
        // SEC-USR-TYPE (the COSGN00C "read USRSEC then route by type" step). The
        // record is guaranteed to exist immediately after a successful
        // authentication; the orElseThrow is a defensive consistency guard.
        final User user = userRepository.findById(userId)
                .orElseThrow(() -> ResourceNotFoundException.of("User", userId));

        final String userType = user.getUserType();

        // Step 5 - Derive the client-facing role hint from the user type, mirroring
        // the COSGN00C admin (COADM01C) vs user (COMEN01C) routing. The user type is
        // normalized defensively so a padded/lower-case value still routes correctly.
        final String role = isAdmin(userType) ? ROLE_NAME_ADMIN : ROLE_NAME_USER;

        // Step 6 - Mint the stateless HS256 token (1-hour expiry) carrying the
        // COMMAREA-equivalent CDEMO-USER-ID / CDEMO-USER-TYPE claims. At sign-on the
        // customer/account/card identifiers are unknown, so the basic two-argument
        // overload (which omits those optional claims) is the correct choice.
        final String token = jwtTokenProvider.generateToken(userId, userType);

        log.debug("Sign-on successful for userId={}, role={}", userId, role);

        // Step 7 - Return the stateless-auth handshake result. No credential
        // material (password, hash) is ever placed in the response.
        return new SignonResponse(token, TOKEN_TYPE_BEARER, userId, userType, role);
    }

    /**
     * Returns whether the supplied legacy user-type code denotes an administrator
     * ({@code 'A'}), reproducing the {@code COSGN00C} {@code CDEMO-USRTYP-ADMIN}
     * decision.
     *
     * <p>The value is trimmed and upper-cased ({@link Locale#ROOT}) before the
     * comparison so that a padded, lower-case, {@code null}, or otherwise
     * non-admin value resolves to a regular user &mdash; consistent with
     * {@code CustomUserDetailsService}, which grants {@code ROLE_ADMIN} only for an
     * exact {@code 'A'} and {@code ROLE_USER} for everything else.</p>
     *
     * @param userType the legacy {@code SEC-USR-TYPE} code (may be {@code null})
     * @return {@code true} if the normalized code equals {@code "A"} (admin),
     *         otherwise {@code false}
     */
    private boolean isAdmin(String userType) {
        if (userType == null) {
            return false;
        }
        return JwtTokenProvider.USER_TYPE_ADMIN.equals(userType.trim().toUpperCase(Locale.ROOT));
    }
}
