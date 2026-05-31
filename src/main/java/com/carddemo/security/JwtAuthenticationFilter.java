package com.carddemo.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;

/**
 * JWT bearer-token authentication filter for the stateless CardDemo REST API.
 *
 * <p><b>Origin &mdash; what this replaces.</b> In the legacy mainframe system the
 * signon program {@code app/cbl/COSGN00C.cbl} authenticated a user against the
 * VSAM {@code USRSEC} file and, on success, populated the 1024-byte CICS
 * {@code COMMAREA} (copybook {@code app/cpy/COCOM01Y.cpy} L19-L44) with the
 * caller's identity and role:
 * <pre>
 *   MOVE WS-USER-ID   TO CDEMO-USER-ID     ({@code COSGN00C} L226 -&gt; COCOM01Y L25, PIC X(08))
 *   MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE   ({@code COSGN00C} L227 -&gt; COCOM01Y L26, PIC X(01))
 * </pre>
 * That {@code COMMAREA} was then carried from program to program with
 * {@code EXEC CICS XCTL ... COMMAREA(CARDDEMO-COMMAREA)} ({@code COSGN00C}
 * L231-L239), so every downstream pseudo-conversational program implicitly knew
 * <i>who</i> the user was and <i>what</i> they were allowed to do
 * ({@code CDEMO-USRTYP-ADMIN VALUE 'A'} / {@code CDEMO-USRTYP-USER VALUE 'U'},
 * COCOM01Y L27-L28).
 *
 * <p>A stateless REST API has no {@code COMMAREA} and no server-side conversation
 * to thread it through. Instead, every request carries its own self-describing,
 * cryptographically signed JSON Web Token. This filter is the per-request
 * equivalent of the {@code COMMAREA} hand-off: it reconstructs the
 * {@link org.springframework.security.core.Authentication Authentication} from
 * the token's claims so that the rest of the request-handling pipeline &mdash; in
 * particular the {@code @PreAuthorize("hasRole('ADMIN')")} checks activated by
 * {@code MethodSecurityConfig} &mdash; can make exactly the authorization
 * decisions the original {@code CDEMO-USER-TYPE} byte drove.
 *
 * <p>The token itself is issued by {@code AuthService} after a successful
 * BCrypt-based signon (the modern replacement for the plaintext password compare
 * {@code IF SEC-USR-PWD = WS-USER-PWD} at {@code COSGN00C} L223). This filter
 * never sees or handles a password (refactoring rule <b>PR-17</b>); it validates
 * only the token signature and expiry.
 *
 * <h2>Validation chain (per request)</h2>
 * <ol>
 *   <li>Extract the token from the {@code Authorization: Bearer &lt;jwt&gt;} header
 *       ({@link #extractToken(HttpServletRequest)}); requests without it pass
 *       straight through unauthenticated.</li>
 *   <li>Skip work if the {@link SecurityContextHolder} already holds an
 *       {@link Authentication} (idempotency &mdash; never overwrite a principal a
 *       prior filter established).</li>
 *   <li>Verify the HS256 signature with the {@code jwt.secret} key and check the
 *       {@code exp} claim ({@link #parseToken(String)}).</li>
 *   <li>Read {@code sub} (the user id, formerly {@code CDEMO-USER-ID}) and the
 *       custom {@code userType} claim (formerly {@code CDEMO-USER-TYPE}).</li>
 *   <li>Translate {@code userType} to Spring Security authorities via
 *       {@link CustomAuthorityMapper#mapAuthorities(String)} &mdash;
 *       {@code 'A' -> ROLE_ADMIN}, {@code 'U' -> ROLE_USER} (rule <b>PR-19</b>).</li>
 *   <li>Store a {@link UsernamePasswordAuthenticationToken} in the
 *       {@link SecurityContextHolder} for the remainder of the request.</li>
 * </ol>
 *
 * <h2>Failure handling</h2>
 * An expired, tampered, malformed, or otherwise unparseable token never raises an
 * exception out of this filter. The {@link SecurityContextHolder} is simply left
 * unpopulated and the chain proceeds; a downstream protected endpoint then yields
 * {@code 401 Unauthorized} via the {@code AuthenticationEntryPoint} configured in
 * {@code SecurityConfig}. This keeps the filter chain simple and uniform and
 * avoids leaking <i>why</i> a token was rejected to the caller.
 *
 * <h2>Trust model</h2>
 * The filter trusts the (signature-verified) claims without re-loading the
 * {@code User} entity from the database on every request &mdash; the standard JWT
 * pattern. Because the claims are signed, altering {@code sub} or {@code userType}
 * invalidates the signature and the token is rejected. Immediate revocation (e.g.
 * a disabled user) is therefore bounded by the token's expiry; a short
 * {@code exp} or an external blacklist would be the lever for tighter control, but
 * that is intentionally out of scope here.
 *
 * <h2>Refactoring rules honoured</h2>
 * <ul>
 *   <li><b>PR-17</b> &mdash; no password handling; validation is signature-based
 *       only.</li>
 *   <li><b>PR-19</b> &mdash; authority mapping delegated to
 *       {@link CustomAuthorityMapper} ({@code 'A' -> ROLE_ADMIN},
 *       {@code 'U' -> ROLE_USER}).</li>
 *   <li><b>PR-28</b> &mdash; the servlet API is imported from
 *       {@code jakarta.servlet.*} (Jakarta EE 10 baseline for Spring Boot 3.x),
 *       never {@code javax.servlet.*}.</li>
 *   <li><b>PR-29</b> &mdash; the sole collaborator ({@link CustomAuthorityMapper})
 *       is injected through the constructor generated by Lombok
 *       {@link RequiredArgsConstructor @RequiredArgsConstructor} over a
 *       {@code final} field; no field injection.</li>
 * </ul>
 *
 * <h2>Expected token shape (emitted by {@code AuthService})</h2>
 * <ul>
 *   <li><b>alg</b>: {@code HS256} (HMAC-SHA256).</li>
 *   <li><b>sub</b>: the user id, e.g. {@code "ADMIN001"} (was {@code CDEMO-USER-ID}).</li>
 *   <li><b>userType</b>: {@code "A"} or {@code "U"} (was {@code CDEMO-USER-TYPE}).</li>
 *   <li><b>iat</b> / <b>exp</b>: issued-at and expiry UNIX timestamps.</li>
 * </ul>
 *
 * <p>Registered in the {@code SecurityFilterChain} (by {@code SecurityConfig})
 * ahead of {@code UsernamePasswordAuthenticationFilter}. Extending
 * {@link OncePerRequestFilter} guarantees a single execution per request even
 * across internal dispatcher forwards.
 *
 * @see CustomAuthorityMapper
 * @see org.springframework.web.filter.OncePerRequestFilter
 * @see org.springframework.security.core.context.SecurityContextHolder
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /**
     * HTTP request header that conveys the bearer token.
     * The expected value format is {@value #BEARER_PREFIX}{@code <jwt>}.
     */
    private static final String AUTHORIZATION_HEADER = "Authorization";

    /**
     * Scheme prefix (note the trailing space) that precedes the token in the
     * {@link #AUTHORIZATION_HEADER} value. Requests whose header does not start
     * with this exact prefix (e.g. {@code Basic ...}) are treated as carrying no
     * JWT.
     */
    private static final String BEARER_PREFIX = "Bearer ";

    /**
     * Name of the custom JWT claim holding the single-character CardDemo role
     * flag. Its values mirror the COBOL {@code CDEMO-USER-TYPE} 88-levels
     * ({@code app/cpy/COCOM01Y.cpy} L26-L28): {@code 'A'} (admin) / {@code 'U'}
     * (user). Resolved to authorities by {@link CustomAuthorityMapper}.
     */
    private static final String USER_TYPE_CLAIM = "userType";

    /**
     * Translates the JWT {@code userType} claim into Spring Security
     * {@link GrantedAuthority} instances ({@code 'A' -> ROLE_ADMIN},
     * {@code 'U' -> ROLE_USER}; anything else -> no authorities). Same-package
     * collaborator injected via the Lombok-generated constructor (PR-29).
     */
    private final CustomAuthorityMapper customAuthorityMapper;

    /**
     * Minimum acceptable length, in bytes, of the UTF-8 encoding of the
     * configured {@code jwt.secret}. HMAC-SHA256 (HS256) requires a key of at
     * least 256 bits; {@code 256 / 8 = 32} bytes. A shorter key is rejected at
     * startup by {@link #initSigningKey()} rather than silently weakening the
     * signature, and jjwt itself would reject one at signing time via
     * {@link Keys#hmacShaKeyFor(byte[])}.
     */
    private static final int MIN_SECRET_BYTES = 32;

    /**
     * Shared secret used to verify the HS256 token signature, injected from the
     * {@code jwt.secret} configuration property. The <b>same</b> value must be
     * used by {@code AuthService} when signing tokens.
     *
     * <p><b>No source-code default is provided (CWE-798).</b> Earlier revisions
     * supplied an in-source fallback secret; that allowed a deployment that
     * forgot to set {@code jwt.secret} to silently run on a publicly known key,
     * letting anyone with repository access forge valid tokens. The property is
     * now mandatory: if {@code jwt.secret} is absent, Spring fails to resolve the
     * {@code ${jwt.secret}} placeholder and the context fails to start. Each
     * profile supplies it explicitly &mdash; {@code application-dev.yml} and the
     * test profile carry non-production development secrets, while
     * {@code application-prod.yml} binds it to the {@code JWT_SECRET} environment
     * variable with no default so a missing production secret is a hard,
     * fail-fast startup error.
     *
     * <p>Validated for non-blankness and minimum entropy/length by
     * {@link #initSigningKey()} immediately after injection.
     */
    @Value("${jwt.secret}")
    private String jwtSecret;

    /**
     * The HS256 verification key, derived once from {@link #jwtSecret} at startup
     * by {@link #initSigningKey()} and reused for every token verification.
     *
     * <p>Precomputing the {@link SecretKey} (rather than rebuilding it from the
     * raw bytes on each {@link #parseToken(String)} call) both removes per-request
     * key-derivation overhead and guarantees that the key-length validation in
     * {@link #initSigningKey()} has succeeded before any request is served.
     */
    private SecretKey signingKey;

    /**
     * Validates the injected {@link #jwtSecret} and precomputes the HS256
     * {@link #signingKey}, failing application startup if the secret is unusable.
     *
     * <p>Runs once, immediately after dependency injection (Jakarta
     * {@link PostConstruct}; PR-28). Two conditions are enforced:
     * <ol>
     *   <li>The secret must be present and non-blank &mdash; guards against an
     *       empty {@code jwt.secret:} entry or a blank environment variable that
     *       would otherwise resolve the placeholder yet yield no key material.</li>
     *   <li>Its UTF-8 encoding must be at least {@link #MIN_SECRET_BYTES} bytes
     *       (256 bits) so the HS256 key meets the algorithm's minimum strength.</li>
     * </ol>
     * On either violation an {@link IllegalStateException} is thrown, which
     * propagates as a {@code BeanInitializationException} and aborts startup
     * &mdash; the application never serves traffic with a weak or missing signing
     * key. The exception message never echoes the secret value.
     *
     * @throws IllegalStateException if {@code jwt.secret} is blank or its UTF-8
     *                               encoding is shorter than {@link #MIN_SECRET_BYTES} bytes
     */
    @PostConstruct
    void initSigningKey() {
        if (!StringUtils.hasText(jwtSecret)) {
            throw new IllegalStateException(
                    "Required configuration property 'jwt.secret' is missing or blank. "
                            + "Set it via application-<profile>.yml or the JWT_SECRET environment "
                            + "variable; it must be at least " + MIN_SECRET_BYTES
                            + " bytes (256 bits) for HS256.");
        }
        final byte[] keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "Configured 'jwt.secret' is too short for HS256: " + keyBytes.length
                            + " bytes; a minimum of " + MIN_SECRET_BYTES
                            + " bytes (256 bits) is required.");
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        log.info("JWT signing key initialized for HS256 ({} key bytes).", keyBytes.length);
    }

    /**
     * Core filter logic: validates the bearer token (if any) and, on success,
     * populates the {@link SecurityContextHolder} with the authenticated
     * principal for the duration of the request. Always delegates to the rest of
     * the chain &mdash; token problems are logged and swallowed so that
     * authorization (the {@code 401}/{@code 403} decision) is made downstream.
     *
     * @param request     the inbound HTTP request whose {@code Authorization}
     *                    header is inspected for a bearer token
     * @param response    the HTTP response, passed through to the chain
     * @param filterChain the remaining filter chain, always invoked exactly once
     * @throws ServletException if a downstream filter raises it
     * @throws IOException      if a downstream filter raises it
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        final String token = extractToken(request);

        // Idempotency: never overwrite an Authentication a prior filter set. This
        // mirrors the COMMAREA invariant that a single signon established identity
        // for the whole conversation rather than re-deriving it at every hop.
        final Authentication existingAuthentication =
                SecurityContextHolder.getContext().getAuthentication();

        if (StringUtils.hasText(token) && existingAuthentication == null) {
            try {
                final Claims claims = parseToken(token);

                // sub -> CDEMO-USER-ID (COCOM01Y L25); userType -> CDEMO-USER-TYPE (L26).
                final String userId = claims.getSubject();
                final String userType = claims.get(USER_TYPE_CLAIM, String.class);

                if (StringUtils.hasText(userId)) {
                    // PR-19: 'A' -> ROLE_ADMIN, 'U' -> ROLE_USER (fail-closed otherwise).
                    final Collection<? extends GrantedAuthority> authorities =
                            customAuthorityMapper.mapAuthorities(userType);

                    // Credentials are null: the token (not a password) is the proof
                    // of identity, and it has already been cryptographically verified
                    // (PR-17 — no password ever flows through this filter).
                    final UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(userId, null, authorities);
                    authentication.setDetails(
                            new WebAuthenticationDetailsSource().buildDetails(request));

                    SecurityContextHolder.getContext().setAuthentication(authentication);
                    log.debug("Authenticated user '{}' with authorities {}", userId, authorities);
                } else {
                    // A signature-valid token with no subject cannot identify a
                    // principal; leave the context unauthenticated.
                    log.warn("JWT is valid but carries no subject (sub) claim; request left unauthenticated");
                }
            } catch (ExpiredJwtException ex) {
                // Expected, benign condition: the token simply timed out. Do NOT
                // throw — the downstream entry point returns 401 if the target
                // resource is protected. Never log the token string itself.
                log.warn("JWT expired: {}", ex.getMessage());
            } catch (SignatureException | MalformedJwtException | UnsupportedJwtException ex) {
                // Bad signature / structurally invalid / unsupported token. A
                // tampered token lands here because the HMAC no longer matches.
                log.warn("Invalid JWT: {}", ex.getMessage());
            } catch (JwtException | IllegalArgumentException ex) {
                // Catch-all for any other jjwt failure plus the IllegalArgumentException
                // jjwt raises for a null/blank token, so no token problem can escape
                // this filter and disrupt the chain.
                log.warn("JWT processing error: {}", ex.getMessage());
            }
        }

        // Always continue the chain — authenticated, unauthenticated, or rejected.
        filterChain.doFilter(request, response);
    }

    /**
     * Extracts the raw JWT from the {@code Authorization} header.
     *
     * <p>Returns the token only when the header is present and begins with the
     * exact {@value #BEARER_PREFIX} scheme prefix; the prefix is stripped from the
     * returned value. A missing header, a blank header, or any other scheme (for
     * example {@code Authorization: Basic ...}) yields {@code null}, signalling
     * "no bearer token on this request".
     *
     * @param request the inbound HTTP request
     * @return the bare token string without the {@value #BEARER_PREFIX} prefix, or
     *         {@code null} if no bearer token is present
     */
    private String extractToken(HttpServletRequest request) {
        final String bearerToken = request.getHeader(AUTHORIZATION_HEADER);
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith(BEARER_PREFIX)) {
            return bearerToken.substring(BEARER_PREFIX.length());
        }
        return null;
    }

    /**
     * Parses and verifies a JWT, returning its claim set.
     *
     * <p>Verifies the token with the precomputed HS256 {@link #signingKey}
     * (derived and validated once at startup by {@link #initSigningKey()}) and
     * calls {@code parseSignedClaims}, which both checks the HMAC signature and
     * enforces the {@code exp} expiry. Any verification failure surfaces as a
     * {@link JwtException} subclass (or {@link IllegalArgumentException} for a
     * null/blank token), all of which the caller handles by leaving the security
     * context unpopulated.
     *
     * @param token the bare JWT (no {@value #BEARER_PREFIX} prefix)
     * @return the verified {@link Claims} payload
     * @throws ExpiredJwtException      if the token's {@code exp} is in the past
     * @throws SignatureException       if the HMAC signature does not match
     * @throws MalformedJwtException    if the token is not a well-formed JWT
     * @throws UnsupportedJwtException  if the token format is unsupported
     * @throws IllegalArgumentException if the token is {@code null} or blank
     */
    private Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
