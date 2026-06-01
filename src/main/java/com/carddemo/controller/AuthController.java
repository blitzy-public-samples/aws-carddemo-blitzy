package com.carddemo.controller;

import com.carddemo.dto.auth.LoginRequest;
import com.carddemo.dto.auth.LoginResponse;
import com.carddemo.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication REST endpoint &mdash; the stateless replacement for the legacy CICS
 * sign-on program {@code app/cbl/COSGN00C.cbl} (TRANID {@code CC00}).
 *
 * <p>This controller is the application's <strong>critical security boundary</strong>.
 * It exposes the two authentication operations of the migrated CardDemo system:</p>
 * <ul>
 *   <li>{@code POST /api/auth/login} &mdash; authenticates a user and returns a signed
 *       JWT bearer token plus non-sensitive identity claims;</li>
 *   <li>{@code POST /api/auth/logout} &mdash; defensively clears the current request's
 *       security context and signals the client to discard its token.</li>
 * </ul>
 *
 * <h2>COBOL parity (replaces {@code COSGN00C})</h2>
 * <p>The original program performed a VSAM keyed {@code READ} of the {@code USRSEC}
 * file and then compared the password in clear text
 * ({@code app/cbl/COSGN00C.cbl:L211-L257}, specifically L223
 * {@code IF SEC-USR-PWD = WS-USER-PWD}). That plaintext comparison &mdash; a documented
 * vulnerability (Tech&nbsp;Spec&nbsp;&sect;6.4) &mdash; is eliminated here: the entire
 * authentication flow is delegated to {@link AuthService}, which drives the Spring
 * Security {@code AuthenticationManager} and {@code BCryptPasswordEncoder.matches(...)}
 * (PR-17). No password is ever compared, logged, or echoed by this class.</p>
 *
 * <p>The COBOL terminal outcomes are mapped onto HTTP semantics as follows:</p>
 * <table border="1">
 *   <caption>COSGN00C outcome &rarr; HTTP response</caption>
 *   <tr><th>COBOL behaviour</th><th>HTTP result</th></tr>
 *   <tr><td>{@code USERIDI = SPACES} &rarr; "Please enter User ID ..."
 *       ({@code COSGN00C.cbl:L118-L122})</td><td>400 (Bean Validation {@code @NotBlank})</td></tr>
 *   <tr><td>{@code PASSWDI = SPACES} &rarr; "Please enter Password ..."
 *       ({@code COSGN00C.cbl:L123-L127})</td><td>400 (Bean Validation {@code @NotBlank})</td></tr>
 *   <tr><td>password mismatch &rarr; "Wrong Password. Try again ..."
 *       ({@code COSGN00C.cbl:L242})</td><td>401 (genericized &mdash; no enumeration)</td></tr>
 *   <tr><td>{@code WHEN 13} &rarr; "User not found. Try again ..."
 *       ({@code COSGN00C.cbl:L249})</td><td>401 (genericized &mdash; no enumeration)</td></tr>
 *   <tr><td>{@code WHEN OTHER} &rarr; "Unable to verify the User ..."
 *       ({@code COSGN00C.cbl:L254})</td><td>401</td></tr>
 *   <tr><td>{@code EXEC CICS XCTL COADM01C}/{@code COMEN01C}
 *       ({@code COSGN00C.cbl:L230-L240})</td><td>200 + {@code userType} ('A'/'U');
 *       the client drives navigation (AAP &sect;0.6.1)</td></tr>
 * </table>
 *
 * <p>Per Spring Security best practice, the three credential-failure cases are
 * deliberately <em>consolidated</em> into a single, indistinguishable 401 response so
 * that an attacker cannot determine whether the user id or the password was wrong
 * (user-enumeration defence). The specific COBOL message strings are preserved
 * verbatim inside {@link AuthService} for server-side logging/diagnostics only.
 * Field-level input failures (missing/blank/malformed credentials), by contrast, are
 * returned as specific 400 responses because they leak no account information.</p>
 *
 * <h2>Statelessness (COMMAREA decomposition, AAP &sect;0.6.1)</h2>
 * <p>The 1024-byte {@code COCOM01Y} COMMAREA that the legacy program populated on
 * success ({@code CDEMO-USER-ID}, {@code CDEMO-USER-TYPE}) and handed to the next
 * program via {@code EXEC CICS XCTL} is replaced by a stateless JWT: {@link AuthService}
 * encodes the user id ({@code sub}) and the {@code userType} claim into the token, which
 * {@code JwtAuthenticationFilter} verifies on every subsequent request. There is no
 * server-side session and no server-driven program chaining &mdash; the client inspects
 * the returned {@code userType} ('A' &rarr; ADMIN, 'U' &rarr; USER, PR-19) and chooses
 * the next screen itself.</p>
 *
 * <h2>Authorization</h2>
 * <p>{@code /api/auth/login} is intentionally public because it <em>is</em> the authentication
 * step, so requiring authentication to reach it would be circular. {@code /api/auth/logout},
 * however, is an authenticated operation: {@code com.carddemo.security.SecurityConfig} deliberately
 * omits it from the {@code permitAll()} set, and this controller also performs a defense-in-depth
 * principal check before returning {@code 204}. Anonymous logout attempts receive {@code 401},
 * matching the documented API contract.</p>
 *
 * <h2>Error-status ownership (separation of concerns)</h2>
 * <ul>
 *   <li><strong>200</strong> &mdash; produced here on a successful
 *       {@link AuthService#authenticate(LoginRequest)};</li>
 *   <li><strong>400</strong> &mdash; produced by
 *       {@code com.carddemo.controller.advice.GlobalExceptionHandler} when Bean
 *       Validation on the {@link LoginRequest} body fails
 *       ({@code MethodArgumentNotValidException});</li>
 *   <li><strong>401</strong> &mdash; produced for any authentication failure
 *       ({@code AuthenticationException}) raised by {@link AuthService}; the controller
 *       does not catch it, keeping the credential-failure policy centralized;</li>
 *   <li><strong>204</strong> &mdash; produced here by {@link #logout()}.</li>
 * </ul>
 *
 * <h2>Design notes</h2>
 * <ul>
 *   <li><strong>Thin controller:</strong> the {@code AuthenticationManager} and JWT
 *       provider are <em>not</em> wired here. Encapsulating the whole flow in
 *       {@link AuthService} keeps this class a pure HTTP adapter and lets tests mock the
 *       service in isolation from the Spring Security machinery (Key Insight&nbsp;6).</li>
 *   <li><strong>Never returns the entity:</strong> the response body is always the
 *       {@link LoginResponse} DTO, never the {@code User} entity, so the BCrypt hash and
 *       other sensitive columns can never leak (PR-20).</li>
 *   <li><strong>No password in logs:</strong> only the {@code userId} is ever logged
 *       (the raw password never leaves the request object, which itself excludes the
 *       password from {@code toString()}).</li>
 * </ul>
 *
 * <h2>Refactoring rules enforced</h2>
 * <ul>
 *   <li><strong>PR-17</strong> &mdash; BCrypt only; never a plaintext comparison
 *       (delegated to {@link AuthService} + Spring Security).</li>
 *   <li><strong>PR-19</strong> &mdash; the {@code userType} 'A'/'U' (returned in the
 *       {@link LoginResponse} and embedded in the JWT) is the basis for the
 *       {@code ROLE_ADMIN}/{@code ROLE_USER} mapping performed downstream.</li>
 *   <li><strong>PR-28</strong> &mdash; Jakarta EE 10 namespace ({@code jakarta.validation.Valid});
 *       no {@code javax.*} imports.</li>
 *   <li><strong>PR-29</strong> &mdash; constructor injection only, via Lombok
 *       {@link RequiredArgsConstructor} over the {@code final} {@link #authService}
 *       field; no {@code @Autowired} field injection.</li>
 * </ul>
 *
 * @see com.carddemo.service.AuthService
 * @see com.carddemo.dto.auth.LoginRequest
 * @see com.carddemo.dto.auth.LoginResponse
 * @see com.carddemo.security.SecurityConfig
 * @see com.carddemo.security.JwtAuthenticationFilter
 * @see com.carddemo.controller.advice.GlobalExceptionHandler
 * @since 1.0
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Authentication", description = "Login and logout endpoints (replaces COSGN00C CICS sign-on)")
public class AuthController {

    /**
     * Service that encapsulates the full sign-on flow (the service-tier port of
     * {@code COSGN00C} paragraph {@code 0500-VALIDATE-USERID-PASSWORD}). It validates
     * credentials through the Spring Security {@code AuthenticationManager} + BCrypt
     * (PR-17), loads the authenticated {@code User}, and assembles a {@link LoginResponse}
     * carrying a signed JWT. Injected by type via the Lombok-generated constructor
     * (PR-29); keeping the controller free of any direct {@code AuthenticationManager}
     * or JWT-provider wiring.
     */
    private final AuthService authService;

    /**
     * Authenticates a user and, on success, returns a signed JWT bearer token.
     *
     * <p>This is the REST replacement for the {@code COSGN00C} sign-on path
     * ({@code app/cbl/COSGN00C.cbl:L211-L257}). The submitted body is first validated by
     * Jakarta Bean Validation 3.0 ({@code @Valid}); a missing, blank, or malformed
     * {@code userId}/{@code password} short-circuits with HTTP 400 (via
     * {@code GlobalExceptionHandler}) &mdash; the analogue of the COBOL
     * "Please enter User ID/Password" checks ({@code COSGN00C.cbl:L118-L127}) &mdash;
     * before this method body runs.</p>
     *
     * <p>The request is then delegated to {@link AuthService#authenticate(LoginRequest)},
     * which performs the BCrypt credential check (never a plaintext comparison, PR-17). A
     * successful authentication yields a {@link LoginResponse} (signed JWT + echoed
     * {@code userId}, {@code userType}, names, and token expiry) returned with HTTP 200.
     * Any authentication failure surfaces as an {@code AuthenticationException} that this
     * method intentionally does <em>not</em> catch, so the credential-failure policy
     * (single, genericized 401 &mdash; no user enumeration) is applied uniformly upstream.</p>
     *
     * <p>Only the (non-sensitive) {@code userId} is logged; the raw password is never
     * logged, echoed, or otherwise exposed.</p>
     *
     * @param request the inbound credentials DTO; bean-validated before this method runs,
     *                then BCrypt-matched downstream by {@link AuthService}
     * @return {@code 200 OK} with the {@link LoginResponse} (signed JWT + identity claims)
     */
    @PostMapping("/login")
    @Operation(
        summary = "Authenticate a user and return a JWT token",
        description = "Replaces COSGN00C plaintext sign-on with BCrypt-based authentication. "
                    + "Returns JWT bearer token with user identity and role claims. "
                    + "User type 'A' maps to ROLE_ADMIN; 'U' maps to ROLE_USER."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Authentication successful; JWT token returned"),
        @ApiResponse(responseCode = "400", description = "Validation error: missing or malformed userId/password"),
        @ApiResponse(responseCode = "401", description = "Invalid credentials")
    })
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        log.info("Login attempt for userId={}", request.getUserId());

        LoginResponse response = authService.authenticate(request);

        log.info("Login successful for userId={} userType={}", response.userId(), response.userType());
        return ResponseEntity.ok(response);
    }

    /**
     * Logs out the current user by clearing the request-scoped security context.
     *
     * <p>JWT bearer tokens are stateless and self-contained, so &mdash; absent a
     * server-side blacklist, which is out of scope per AAP &sect;0.7.2 &mdash; a token
     * cannot truly be "invalidated" server-side. This endpoint therefore exists for API
     * symmetry and future token-revocation extensions: it is a hint to the client to
     * discard its token. The legacy {@code COSGN00C} sign-on had no explicit sign-off
     * transaction (the CICS task simply ended); this endpoint is the modern, idempotent
     * equivalent.</p>
     *
     * <p><strong>Authentication is required (CP4).</strong> Logout invalidates an
     * <em>established</em> identity, so it is not a public route: {@code SecurityConfig}
     * omits it from the {@code permitAll} set, and the filter chain rejects an anonymous
     * caller with a 401 before this method runs. This method additionally performs a
     * defence-in-depth guard &mdash; if the {@link SecurityContextHolder} carries no
     * {@link Authentication}, an unauthenticated one, or an
     * {@link AnonymousAuthenticationToken}, it returns {@code 401 Unauthorized} rather
     * than acknowledging a no-op logout. This keeps the implementation faithful to the
     * documented OpenAPI 401 response even if the URL-level rule is ever relaxed.</p>
     *
     * <p>For an authenticated caller the current thread's {@link SecurityContextHolder}
     * context is cleared. Because the API is stateless this has minimal practical effect
     * (the next request starts from a fresh context), but it guarantees no authenticated
     * principal lingers on the thread after this call. The authenticated {@code userId} is
     * logged for audit purposes; no token contents are ever logged.</p>
     *
     * @return {@code 204 No Content} when an authenticated principal is logged out, or
     *         {@code 401 Unauthorized} when no authenticated principal is present
     */
    @PostMapping("/logout")
    @Operation(
        summary = "Logout the current user",
        description = "Clears server-side authentication context. Client must discard the JWT token. "
                    + "Stateless JWT tokens cannot be 'invalidated' server-side without a blacklist; "
                    + "this endpoint exists for symmetry and future token-blacklist extensions."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Logout acknowledged"),
        @ApiResponse(responseCode = "401", description = "User not authenticated")
    })
    public ResponseEntity<Void> logout() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        // SECURITY (CP4): logout requires an authenticated, non-anonymous principal.
        // SecurityConfig already rejects anonymous callers with a 401 before this method is
        // reached (logout is no longer in the permitAll set); this guard is defence-in-depth
        // so the documented 401 contract holds regardless of the URL-rule configuration.
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            log.warn("Logout rejected: no authenticated principal");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        log.info("Logout for userId={}", authentication.getName());

        // Clear server-side security context (for the current request only).
        SecurityContextHolder.clearContext();

        // Return 204 No Content.
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
