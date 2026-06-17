package com.carddemo.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-request authentication filter that makes the CardDemo REST API
 * <strong>stateless</strong>.
 *
 * <p><strong>Migration role.</strong> In the legacy mainframe design the
 * signed-on user's identity flowed from program to program inside the CICS
 * communication area ({@code CARDDEMO-COMMAREA}, copybook
 * {@code app/cpy/COCOM01Y.cpy}). Every pseudo-conversational {@code XCTL}
 * transfer re-threaded {@code CDEMO-USER-ID} and {@code CDEMO-USER-TYPE}
 * (88-levels {@code 'A'}=admin, {@code 'U'}=user) so the next program knew who
 * was acting. There is no equivalent server-side hand-off here: identity is
 * carried by a bearer JWT presented on <em>every</em> HTTP request, and this
 * filter re-establishes the {@link org.springframework.security.core.Authentication}
 * for the duration of that single request. No HTTP session is created and no
 * state is retained between requests. See AAP &sect;0.3.2, &sect;0.4.1.5 and
 * &sect;0.6.7.</p>
 *
 * <p><strong>Behavior.</strong> On each request the filter:</p>
 * <ol>
 *   <li>extracts the compact token from the {@code Authorization: Bearer
 *       &lt;token&gt;} header (returning {@code null} when the header is absent
 *       or not a bearer header);</li>
 *   <li>verifies the token via {@link JwtTokenProvider#validateToken(String)}
 *       (which already swallows {@code JwtException}/{@code
 *       IllegalArgumentException} and returns {@code false} for any malformed,
 *       expired, badly-signed, or {@code null} token);</li>
 *   <li>only when the token is valid <em>and</em> the {@link
 *       SecurityContextHolder} is currently empty, derives the user id and user
 *       type, maps the user type to a Spring Security authority, and installs a
 *       {@link UsernamePasswordAuthenticationToken} into the security
 *       context.</li>
 * </ol>
 *
 * <p><strong>Authority mapping.</strong> Reproducing the {@code COSGN00C}
 * post-signon routing — {@code IF CDEMO-USRTYP-ADMIN XCTL 'COADM01C' ELSE XCTL
 * 'COMEN01C'} (COSGN00C lines 227-240) — user type {@code 'A'} grants
 * {@link JwtTokenProvider#ROLE_ADMIN} and every other (or absent) value grants
 * {@link JwtTokenProvider#ROLE_USER}. The {@code ROLE_} prefix is required so
 * that {@code hasRole(...)} and {@code @PreAuthorize("hasRole('ADMIN')")}
 * authorization checks on the controllers work as intended, and the mapping is
 * kept consistent with {@code JwtTokenProvider} and {@code
 * CustomUserDetailsService}.</p>
 *
 * <p><strong>Never short-circuits.</strong> The filter always continues the
 * chain via {@link FilterChain#doFilter(jakarta.servlet.ServletRequest,
 * jakarta.servlet.ServletResponse)} for both the authenticated and the
 * anonymous paths; it never writes a response or throws on a missing or invalid
 * token. Rejection of unauthenticated access to protected resources is the
 * responsibility of the {@code AuthenticationEntryPoint} (HTTP 401) and the
 * authorization rules configured in {@code SecurityConfig}, which also wires
 * this filter <em>before</em> {@code UsernamePasswordAuthenticationFilter}.</p>
 *
 * <p><strong>PII suppression.</strong> Per AAP &sect;0.6.8 this class never logs
 * the raw token, passwords, the card CVV, or a full SSN. The only DEBUG-level
 * diagnostic emitted is the (non-sensitive) user id and the resolved authority
 * name.</p>
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** Logger; used at DEBUG only and never emits the token or any PII. */
    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    /** Standard HTTP header that conveys the bearer credential. */
    private static final String AUTH_HEADER = "Authorization";

    /** RFC 6750 bearer scheme prefix (note the single trailing space). */
    private static final String BEARER_PREFIX = "Bearer ";

    /**
     * Token provider used to verify signatures/expiry and to read identity
     * claims. Injected by constructor (immutable) so the filter is trivially
     * unit-testable with a stub or mock provider.
     */
    private final JwtTokenProvider jwtTokenProvider;

    /**
     * Creates the filter with its collaborating {@link JwtTokenProvider}.
     *
     * @param jwtTokenProvider the provider that validates tokens and exposes the
     *                         identity claims; must not be {@code null}
     */
    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    /**
     * Establishes the per-request {@link org.springframework.security.core.Authentication}
     * from a valid bearer JWT, then unconditionally continues the filter chain.
     *
     * <p>A missing, malformed, or expired token leaves the {@link
     * SecurityContextHolder} untouched (anonymous) and the request proceeds; the
     * downstream authorization layer decides whether anonymous access is
     * permitted. The token is read only when none is already authenticated, so
     * this filter never overrides an authentication established earlier in the
     * chain.</p>
     *
     * @param request     the current HTTP request (Jakarta servlet API)
     * @param response    the current HTTP response (Jakarta servlet API)
     * @param filterChain the remaining filter chain; always invoked
     * @throws ServletException if a downstream filter/servlet raises it
     * @throws IOException      if a downstream filter/servlet raises it
     */
    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        final String token = resolveToken(request);

        // Authenticate only when a token is present, verifies cleanly, and no
        // authentication has already been established for this request. Because
        // validateToken() returns true only for a fully verified token, the
        // subsequent claim reads (getUserId/getUserType) cannot throw — the
        // parse is guarded by this gate (per the assigned-file contract:
        // "rely on validateToken; do not re-parse unguarded").
        if (token != null
                && jwtTokenProvider.validateToken(token)
                && SecurityContextHolder.getContext().getAuthentication() == null) {

            final String userId = jwtTokenProvider.getUserId(token);
            final String userType = jwtTokenProvider.getUserType(token);
            final GrantedAuthority authority = resolveAuthority(userType);

            // Principal = the user id (String); credentials = null because the
            // token signature has already been verified. A single authority is
            // granted, mirroring the legacy admin/user split.
            final UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(userId, null, List.of(authority));
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);

            if (log.isDebugEnabled()) {
                // Non-sensitive only: never the token, password, CVV, or SSN.
                log.debug("Authenticated request for user id '{}' with authority {}",
                        userId, authority.getAuthority());
            }
        }

        // ALWAYS continue the chain — both authenticated and anonymous paths.
        filterChain.doFilter(request, response);
    }

    /**
     * Extracts the compact JWT from the {@code Authorization} header.
     *
     * @param request the current HTTP request
     * @return the token string with the {@code "Bearer "} scheme stripped, or
     *         {@code null} when the header is absent or is not a bearer header
     */
    private String resolveToken(HttpServletRequest request) {
        final String header = request.getHeader(AUTH_HEADER);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length());
        }
        return null;
    }

    /**
     * Maps a COMMAREA-style user type code to a Spring Security authority,
     * reproducing the {@code COSGN00C} admin/user routing. The code is trimmed
     * and upper-cased (locale-independent) before comparison so stray padding —
     * common when a value originates from a fixed-width COBOL field — does not
     * change the outcome.
     *
     * @param userType the user type claim ({@code 'A'}/{@code 'U'}), possibly
     *                 {@code null} or whitespace-padded
     * @return {@link JwtTokenProvider#ROLE_ADMIN} for type {@code 'A'}, otherwise
     *         {@link JwtTokenProvider#ROLE_USER}
     */
    private GrantedAuthority resolveAuthority(String userType) {
        final String normalized = (userType == null) ? null : userType.trim().toUpperCase(Locale.ROOT);
        if (JwtTokenProvider.USER_TYPE_ADMIN.equals(normalized)) {
            return new SimpleGrantedAuthority(JwtTokenProvider.ROLE_ADMIN);
        }
        return new SimpleGrantedAuthority(JwtTokenProvider.ROLE_USER);
    }
}
