package com.carddemo.controller;

import com.carddemo.dto.SignonRequest;
import com.carddemo.dto.SignonResponse;
import com.carddemo.service.AuthService;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing the CardDemo <strong>sign-on</strong> operation.
 *
 * <p>This controller is the Spring Boot re-expression of the legacy CICS sign-on program
 * {@code COSGN00C} (transaction {@code CC00}), whose 3270 panel collected an 8-character user id and
 * an 8-character password and, on a successful credential check, transferred control via
 * {@code EXEC CICS XCTL} to either the administrator menu ({@code COADM01C}) or the regular-user menu
 * ({@code COMEN01C}). Honoring the migration's "one operation per original transaction id" rule, the
 * single source program maps to exactly one HTTP endpoint:</p>
 * <ul>
 *   <li>{@code POST /auth/signon} &mdash; authenticate a user and, on success, return a signed JWT
 *       plus the identity/role hint the client uses to route itself (replacing the legacy
 *       {@code XCTL} to {@code COADM01C}/{@code COMEN01C}).</li>
 * </ul>
 *
 * <h2>The single public entry point</h2>
 * <p>This is the <strong>only</strong> public (unauthenticated) controller in the application. Its
 * endpoint is {@code permitAll} in {@code SecurityConfig}; it is the door through which a caller
 * obtains the bearer token that every other endpoint requires. The path is exactly
 * {@code /auth/signon} (no {@code /api} prefix) so it matches the security filter chain's
 * {@code permitAll} matcher; all other routes remain {@code authenticated()} and reject a missing or
 * invalid token with HTTP&nbsp;401.</p>
 *
 * <h2>Source-of-truth mapping (REFERENCE &mdash; not reimplemented here)</h2>
 * <p>The BMS screen map {@code COSGN00} that rendered the sign-on panel is <strong>retired</strong>;
 * its two operator-entered fields ({@code USERID} and {@code PASSWD}, each {@code LENGTH=8}) informed
 * the shape of {@link SignonRequest} ({@code @NotBlank @Size(max = 8)} on both components) rather than
 * any rendered UI. The behaviour of {@code COSGN00C} &mdash; rejecting an empty user id
 * ({@code "Please enter User ID ..."}) or empty password ({@code "Please enter Password ..."}),
 * upper-casing both inputs, reading the {@code USRSEC} security file, comparing the password
 * (plaintext in the legacy program, hardened to a BCrypt&nbsp;(strength&nbsp;12) verification in the
 * migration), and routing by {@code SEC-USR-TYPE} &mdash; lives entirely in {@link AuthService} and
 * the security package, <em>not</em> in this controller.</p>
 *
 * <h2>Thin-controller contract</h2>
 * <p>This class holds <strong>no business logic</strong>. It performs no credential handling, no
 * upper-casing, no BCrypt comparison, and no JWT construction. The endpoint simply binds the JSON
 * request body, delegates to {@link AuthService#signon(SignonRequest)}, and wraps the result in an
 * HTTP&nbsp;200 response. The following are deliberately <strong>service-owned</strong> and absent
 * from this controller:</p>
 * <ul>
 *   <li>upper-casing of the user id and password (the legacy {@code FUNCTION UPPER-CASE} moves that
 *       made credentials case-insensitive);</li>
 *   <li>the credential verification itself, delegated by the service to the Spring Security
 *       {@code AuthenticationManager} (BCrypt strength&nbsp;12), replacing the
 *       {@code SEC-USR-PWD = WS-USER-PWD} plaintext compare;</li>
 *   <li>minting the HS256 JWT that carries the COMMAREA-equivalent identity claims
 *       ({@code CDEMO-USER-ID}/{@code CDEMO-USER-TYPE}).</li>
 * </ul>
 *
 * <h2>Error mapping (centralized; no per-endpoint handling)</h2>
 * <p>This controller catches nothing; error translation is centralized in the application's
 * {@code @RestControllerAdvice} ({@code GlobalExceptionHandler}):</p>
 * <ul>
 *   <li>invalid credentials or an unknown user surface as a Spring Security
 *       {@code AuthenticationException} thrown from {@link AuthService} /
 *       {@code AuthenticationManager} &rarr; HTTP&nbsp;401 with a uniform message (the legacy program's
 *       distinct {@code "Wrong Password ..."} vs {@code "User not found ..."} texts are intentionally
 *       collapsed to one response to prevent user/credential enumeration);</li>
 *   <li>a blank or oversized {@code userId}/{@code password} violates the {@link SignonRequest} Bean
 *       Validation constraints, raising {@code MethodArgumentNotValidException} &rarr; HTTP&nbsp;400
 *       with field errors carrying the legacy "Please enter ..." messages.</li>
 * </ul>
 *
 * <h2>Security &amp; PII</h2>
 * <p>No {@code @PreAuthorize} guard is declared &mdash; the endpoint is public by design. The
 * request body, the password, and the issued token are <strong>never</strong> logged here; this
 * controller intentionally adds no logging that touches credentials, consistent with the PII
 * suppression rules. {@link SignonResponse} carries no password field by design.</p>
 *
 * @see AuthService
 * @see SignonRequest
 * @see SignonResponse
 * @see <a href="file:app/cbl/COSGN00C.cbl">app/cbl/COSGN00C.cbl (source-of-truth)</a>
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    /**
     * The sign-on service that owns all credential verification, input upper-casing, BCrypt
     * comparison (via the Spring Security {@code AuthenticationManager}), and JWT issuance. This
     * controller delegates the entire sign-on flow to it and contributes no business logic of its
     * own.
     */
    private final AuthService authService;

    /**
     * Creates the controller with its single collaborator.
     *
     * <p>Constructor injection is used (no field injection and no {@code @Autowired}, which is
     * unnecessary for a single constructor), replacing the COBOL static {@code CALL}/{@code XCTL}
     * linkage and keeping the controller trivially testable with a mocked {@link AuthService}.</p>
     *
     * @param authService the sign-on service to delegate authentication and token issuance to
     */
    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * Authenticates a user and issues a stateless JWT &mdash; the REST re-expression of
     * {@code COSGN00C} (transaction {@code CC00}).
     *
     * <p>The request body is validated against the {@link SignonRequest} Bean Validation constraints
     * ({@code @Valid} triggers the {@code @NotBlank}/{@code @Size(max = 8)} checks that mirror the
     * legacy empty-field rejects and the {@code PIC X(8)} field widths); a constraint violation is
     * translated to HTTP&nbsp;400 by the global exception handler. On a valid body, the call is
     * delegated to {@link AuthService#signon(SignonRequest)}, which performs the credential check and
     * mints the token. A failed authentication propagates as an {@code AuthenticationException}
     * mapped to HTTP&nbsp;401; this method never catches it.</p>
     *
     * @param request the sign-on request carrying the {@code userId} and {@code password}; validated
     *                via {@code @Valid}
     * @return HTTP&nbsp;200 with the {@link SignonResponse} (bearer token, {@code "Bearer"} scheme,
     *         upper-cased user id, legacy single-character user type, and derived role hint)
     */
    @PostMapping("/signon")
    public ResponseEntity<SignonResponse> signon(@Valid @RequestBody SignonRequest request) {
        return ResponseEntity.ok(authService.signon(request));
    }
}
