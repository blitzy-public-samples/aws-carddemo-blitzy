package com.carddemo.service;

import com.carddemo.dto.auth.LoginRequest;
import com.carddemo.dto.auth.LoginResponse;
import com.carddemo.entity.User;
import com.carddemo.repository.UserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Authentication service replacing {@code app/cbl/COSGN00C.cbl} (CICS TRANID 'CS00',
 * screen transaction 'CC00').
 *
 * <p>Replaces the COBOL plaintext password comparison
 * ({@code app/cbl/COSGN00C.cbl:L211-L257}, specifically L223
 * {@code IF SEC-USR-PWD = WS-USER-PWD}) with delegation to the Spring Security
 * {@link AuthenticationManager}, which in turn drives {@code UserDetailsServiceImpl}
 * and {@code BCryptPasswordEncoder.matches(rawPassword, storedHash)} per
 * refactoring rule <b>PR-17</b>. This closes the documented security gap from Tech
 * Spec &sect;6.4 where credentials were stored and compared in clear text.</p>
 *
 * <p>On success this service issues a signed, stateless JWT bearer token (the modern
 * replacement for the CICS {@code COMMAREA}/{@code EXEC CICS XCTL} hand-off at
 * {@code COSGN00C.cbl:L226-L240}). The token carries the {@code sub} (user id,
 * formerly {@code CDEMO-USER-ID}) and a {@code userType} claim (formerly
 * {@code CDEMO-USER-TYPE}, {@code app/cpy/COCOM01Y.cpy:L26-L28}); it is verified on
 * every subsequent request by {@code JwtAuthenticationFilter}, which derives the
 * Spring Security authorities via {@code CustomAuthorityMapper}
 * ({@code 'A' -> ROLE_ADMIN}, {@code 'U' -> ROLE_USER} per <b>PR-19</b>).</p>
 *
 * <p>The token is signed with HMAC-SHA256 (HS256) using the shared {@code jwt.secret}
 * key &mdash; the <b>same</b> property and the <b>same</b> key-derivation
 * ({@code Keys.hmacShaKeyFor(secret UTF-8 bytes)}) used by
 * {@code JwtAuthenticationFilter}, so every issued token verifies there.</p>
 *
 * <h2>COBOL parity ({@code 0500-VALIDATE-USERID-PASSWORD})</h2>
 * <ul>
 *   <li>The user id is uppercased before lookup, mirroring
 *       {@code MOVE FUNCTION UPPER-CASE(USERIDI)} ({@code COSGN00C.cbl:L132-L134}).
 *       The password is deliberately <b>NOT</b> uppercased &mdash; unlike the COBOL
 *       {@code MOVE FUNCTION UPPER-CASE(PASSWDI)} ({@code COSGN00C.cbl:L135-L136})
 *       &mdash; because BCrypt is case-sensitive and uppercasing would discard
 *       password entropy (AAP &sect;0.6.8).</li>
 *   <li>The three terminal outcomes preserve the exact COBOL message strings
 *       character-for-character:
 *       <ul>
 *         <li>record not found &rarr; {@value #MSG_USER_NOT_FOUND}
 *             ({@code COSGN00C.cbl:L249}, {@code WHEN 13});</li>
 *         <li>password mismatch &rarr; {@value #MSG_WRONG_PASSWORD}
 *             ({@code COSGN00C.cbl:L242});</li>
 *         <li>any other failure &rarr; {@value #MSG_UNABLE_TO_VERIFY}
 *             ({@code COSGN00C.cbl:L254}, {@code WHEN OTHER}).</li>
 *       </ul>
 *   </li>
 *   <li>There is no failed-attempt counter or account lockout &mdash; matching the
 *       original program, which tracks neither (deferred to operational hardening
 *       per Tech Spec &sect;6.4).</li>
 *   <li>Server-side navigation ({@code EXEC CICS XCTL COADM01C}/{@code COMEN01C})
 *       is intentionally NOT reproduced: the REST response returns {@code userType}
 *       and the client chooses the next screen (AAP &sect;0.6.1).</li>
 * </ul>
 *
 * <p>Refactoring rules honoured: <b>PR-17</b> (BCrypt, never plaintext),
 * <b>PR-19</b> (role mapping via {@code userType}), <b>PR-25</b> (single monolith;
 * the {@link AuthenticationManager} is configured in {@code SecurityConfig}),
 * <b>PR-28</b> (Jakarta EE / {@code org.springframework.security.*} namespaces),
 * <b>PR-29</b> (constructor injection of collaborator beans via Lombok
 * {@link RequiredArgsConstructor}, no {@code @Autowired} field injection).</p>
 *
 * @see com.carddemo.security.SecurityConfig
 * @see com.carddemo.security.UserDetailsServiceImpl
 * @see com.carddemo.security.JwtAuthenticationFilter
 * @see com.carddemo.security.CustomAuthorityMapper
 * @see com.carddemo.controller.AuthController
 * @since 1.0
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    /**
     * Name of the custom JWT claim that carries the single-character CardDemo role
     * flag. Must match the constant of the same value read by
     * {@code JwtAuthenticationFilter} so the issued token resolves to authorities
     * there. Mirrors the COBOL {@code CDEMO-USER-TYPE} 88-levels
     * ({@code app/cpy/COCOM01Y.cpy:L26-L28}): {@code 'A'} (admin) / {@code 'U'} (user).
     */
    private static final String USER_TYPE_CLAIM = "userType";

    /**
     * Exact COBOL "record not found" message ({@code app/cbl/COSGN00C.cbl:L249},
     * {@code WHEN 13}). Preserved character-for-character for behavioural parity.
     */
    private static final String MSG_USER_NOT_FOUND = "User not found. Try again ...";

    /**
     * Exact COBOL "password mismatch" message ({@code app/cbl/COSGN00C.cbl:L242}).
     * Preserved character-for-character for behavioural parity.
     */
    private static final String MSG_WRONG_PASSWORD = "Wrong Password. Try again ...";

    /**
     * Exact COBOL "unexpected failure" message ({@code app/cbl/COSGN00C.cbl:L254},
     * {@code WHEN OTHER}). Preserved character-for-character for behavioural parity.
     */
    private static final String MSG_UNABLE_TO_VERIFY = "Unable to verify the User ...";

    /**
     * Spring Security entry point for credential validation, configured (together
     * with {@code UserDetailsServiceImpl} and {@code BCryptPasswordEncoder}) in
     * {@code SecurityConfig}. Injected by type via the Lombok-generated constructor
     * (PR-29).
     */
    private final AuthenticationManager authenticationManager;

    /**
     * Loads the authenticated {@link User} entity to source the {@code userType},
     * first name and last name for the JWT claim and the {@link LoginResponse}.
     * Replaces the COSGN00C VSAM {@code USRSEC} keyed READ
     * ({@code app/cbl/COSGN00C.cbl:L211-L219}). Injected via the Lombok-generated
     * constructor (PR-29).
     */
    private final UserRepository userRepository;

    /**
     * HS256 signing secret, injected from the {@code jwt.secret} configuration
     * property. No source-code default is supplied (CWE-798): the same mandatory
     * property is consumed by {@code JwtAuthenticationFilter}, and the two MUST
     * agree for issued tokens to verify. Each profile supplies it explicitly
     * ({@code application-dev.yml}, the test profile, and {@code application-prod.yml}
     * binding to the {@code JWT_SECRET} environment variable).
     */
    @Value("${jwt.secret}")
    private String jwtSecret;

    /**
     * Token time-to-live in milliseconds, injected from {@code jwt.expiration-ms}.
     * Defaults to {@code 3600000} (one hour) when the property is absent so the
     * application context starts cleanly in every profile. Used to compute both the
     * JWT {@code exp} claim and the {@link LoginResponse#expiresAt()} value.
     */
    @Value("${jwt.expiration-ms:3600000}")
    private long jwtExpirationMs;

    /**
     * Authenticates a sign-on request and, on success, returns a signed JWT bearer
     * token plus user identity claims.
     *
     * <p>This is the service-tier port of {@code COSGN00C} paragraph
     * {@code 0500-VALIDATE-USERID-PASSWORD} ({@code app/cbl/COSGN00C.cbl:L211-L257}).
     * It is intentionally <b>not</b> {@code @Transactional}: it performs no entity
     * mutation, and {@code UserDetailsServiceImpl} manages its own read transaction
     * during {@link AuthenticationManager#authenticate(Authentication)}.</p>
     *
     * <p>The submitted password is never logged, never echoed, and never uppercased;
     * it flows only into the {@link UsernamePasswordAuthenticationToken} passed to
     * the {@link AuthenticationManager} for BCrypt verification (PR-17).</p>
     *
     * @param req the inbound credentials DTO (already bean-validated by the
     *            controller); its {@code userId} is uppercased before lookup and its
     *            raw {@code password} is BCrypt-matched downstream
     * @return a {@link LoginResponse} carrying the signed JWT, the echoed (uppercase)
     *         user id, the {@code userType} ('A'/'U'), the user's first and last
     *         name, and the token expiry instant
     * @throws BadCredentialsException        if the user id is unknown
     *                                        ({@value #MSG_USER_NOT_FOUND}) or the
     *                                        password does not match
     *                                        ({@value #MSG_WRONG_PASSWORD})
     * @throws AuthenticationServiceException if authentication fails for any other
     *                                        reason ({@value #MSG_UNABLE_TO_VERIFY}),
     *                                        e.g. an underlying data-access error
     */
    public LoginResponse authenticate(LoginRequest req) {
        // COSGN00C L132-L134: MOVE FUNCTION UPPER-CASE(USERIDI) TO WS-USER-ID.
        // The user id is uppercased; the password is NOT (BCrypt is case-sensitive).
        String upperUserId = req.getUserId() != null ? req.getUserId().toUpperCase() : "";

        // Build the credential token from the normalized id + RAW password. The raw
        // password is verified against the stored BCrypt hash by the configured
        // DaoAuthenticationProvider (PR-17) — no plaintext comparison occurs here.
        UsernamePasswordAuthenticationToken authRequest =
                new UsernamePasswordAuthenticationToken(upperUserId, req.getPassword());

        // COSGN00C L211-L257: VSAM READ USRSEC + plaintext compare, now delegated to
        // the AuthenticationManager. The EVALUATE WS-RESP-CD branches map to the
        // Spring Security exception types below.
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(authRequest);
        } catch (UsernameNotFoundException ex) {
            // COSGN00C L247-L251 (WHEN 13 / DFHRESP NOTFND): "User not found.".
            // Surfaced only when SecurityConfig sets hideUserNotFoundExceptions(false);
            // otherwise the provider reports a BadCredentialsException (handled below).
            log.warn("Sign-on failed - user not found: {}", upperUserId);
            throw new BadCredentialsException(MSG_USER_NOT_FOUND);
        } catch (BadCredentialsException ex) {
            // COSGN00C L241-L245 (WHEN 0 + SEC-USR-PWD != WS-USER-PWD): "Wrong Password.".
            log.warn("Sign-on failed - wrong password for user: {}", upperUserId);
            throw new BadCredentialsException(MSG_WRONG_PASSWORD);
        } catch (AuthenticationServiceException ex) {
            // COSGN00C L252-L256 (WHEN OTHER): unexpected failure -> "Unable to verify".
            // Also catches InternalAuthenticationServiceException (its subclass), which
            // wraps a data-access/UserDetailsService error -> the precise analogue of an
            // unexpected VSAM response code. The cause is preserved for diagnostics.
            log.error("Sign-on failed - unable to verify user: {}", upperUserId, ex);
            throw new AuthenticationServiceException(MSG_UNABLE_TO_VERIFY, ex);
        }

        // Authentication succeeded: getName() is the canonical authenticated user id
        // (UserDetails.getUsername() == User.userId). Use it as the authoritative key
        // for the follow-up load rather than the raw request input.
        String authenticatedUserId = authentication.getName();

        // COSGN00C L226-L227: MOVE WS-USER-ID/SEC-USR-TYPE for downstream propagation.
        // Re-load the full entity to source userType + names for the token and response.
        // A miss here is an extraordinary post-authentication inconsistency.
        User user = userRepository.findById(authenticatedUserId)
                .orElseThrow(() -> new BadCredentialsException(MSG_USER_NOT_FOUND));

        // Compute the token validity window once so the JWT exp claim and the
        // LoginResponse.expiresAt are derived from a single instant.
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plusMillis(jwtExpirationMs);

        String token = generateToken(user.getUserId(), user.getUserType(), issuedAt, expiresAt);

        log.info("Sign-on successful for user '{}' (userType '{}')",
                user.getUserId(), user.getUserType());

        // COSGN00C XCTL routing (L230-L240) becomes a stateless response: the client
        // inspects userType and drives navigation. No COMMAREA, no server-side chaining.
        return LoginResponse.builder()
                .token(token)
                .userId(user.getUserId())
                .userType(user.getUserType())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .expiresAt(expiresAt)
                .build();
    }

    /**
     * Builds and signs the stateless JWT bearer token for an authenticated user.
     *
     * <p>The claim set and signing scheme are deliberately identical to what
     * {@code JwtAuthenticationFilter} expects and verifies:</p>
     * <ul>
     *   <li>{@code sub} &mdash; the user id (was {@code CDEMO-USER-ID});</li>
     *   <li>{@value #USER_TYPE_CLAIM} &mdash; the raw user-type code 'A'/'U'
     *       (was {@code CDEMO-USER-TYPE}); the filter resolves it to authorities
     *       via {@code CustomAuthorityMapper}, so no separate role claim is needed;</li>
     *   <li>{@code iat}/{@code exp} &mdash; issued-at and expiry timestamps.</li>
     * </ul>
     *
     * <p>The HS256 key is derived from {@code jwt.secret} with
     * {@code Keys.hmacShaKeyFor(secret UTF-8 bytes)} &mdash; byte-for-byte the same
     * derivation as {@code JwtAuthenticationFilter}, guaranteeing the signature
     * verifies there. {@code Jwts.SIG.HS256} is forced explicitly so the token
     * header always advertises {@code alg=HS256} regardless of secret length (a
     * longer secret would otherwise auto-select a stronger HMAC variant).</p>
     *
     * @param userId    the JWT subject (already uppercase-normalized)
     * @param userType  the single-character role code ('A' or 'U') for the claim
     * @param issuedAt  the token issue instant (drives {@code iat})
     * @param expiresAt the token expiry instant (drives {@code exp})
     * @return the compact, signed JWT string
     */
    private String generateToken(String userId, String userType, Instant issuedAt, Instant expiresAt) {
        SecretKey signingKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(userId)
                .claim(USER_TYPE_CLAIM, userType)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }
}
