package com.carddemo.dto;

/**
 * Immutable JSON response body returned by a successful {@code POST /auth/signon}.
 *
 * <p>This DTO is the stateless-authentication handshake result for the CardDemo
 * application. It is the Spring Boot replacement for the legacy CICS signon flow
 * implemented by {@code COSGN00C}, where a successful credential check transferred
 * control (via {@code XCTL}) to either the administrator menu ({@code COADM01C}) or
 * the regular-user menu ({@code COMEN01C}) while carrying session state in the
 * {@code CARDDEMO-COMMAREA} communication area.</p>
 *
 * <p>In the migrated, stateless architecture there is no server-side session and no
 * COMMAREA hand-off. Instead, the identity fields that the COMMAREA used to carry
 * ({@code CDEMO-USER-ID} and {@code CDEMO-USER-TYPE} from {@code COCOM01Y}) are
 * externalized to the client: the signed JWT embeds them as claims, and this response
 * surfaces the user identity plus a client-friendly role string so the caller can route
 * itself and attach the token to subsequent requests.</p>
 *
 * <h2>Field lineage (COBOL source-of-truth)</h2>
 * <ul>
 *   <li>{@code userId} &larr; {@code SEC-USR-ID} ({@code CSUSR01Y}) /
 *       {@code CDEMO-USER-ID} ({@code COCOM01Y}); upper-cased 8-character user id.</li>
 *   <li>{@code userType} &larr; {@code SEC-USR-TYPE} ({@code CSUSR01Y}) /
 *       {@code CDEMO-USER-TYPE} ({@code COCOM01Y}); the single-character legacy code,
 *       constrained by the 88-levels {@code CDEMO-USRTYP-ADMIN} ({@code 'A'}) and
 *       {@code CDEMO-USRTYP-USER} ({@code 'U'}).</li>
 *   <li>{@code role} &mdash; the Spring Security role name derived from {@code userType}
 *       ({@code 'A'} &rarr; {@code "ADMIN"}, {@code 'U'} &rarr; {@code "USER"}), mirroring
 *       the {@code COSGN00C} admin/user routing decision.</li>
 *   <li>{@code token} / {@code tokenType} &mdash; net-new stateless-auth artifacts; the
 *       HS256-signed JWT (1&nbsp;hour expiry) minted by {@code JwtTokenProvider} and the
 *       HTTP authorization scheme ({@code "Bearer"}).</li>
 * </ul>
 *
 * <h2>Security &amp; PII</h2>
 * <p>This record is intentionally a pure data carrier and deliberately exposes
 * <strong>no</strong> credential material. There is no password field (the legacy
 * {@code SEC-USR-PWD} plaintext compare is replaced by a BCrypt verification performed
 * in {@code AuthService} and is never echoed back), and no other sensitive customer
 * data is included. Only the bearer token and non-sensitive identity/role data leave
 * the service boundary.</p>
 *
 * <h2>Construction</h2>
 * <p>{@code AuthService} constructs this response after a successful BCrypt password
 * verification: it asks {@code JwtTokenProvider} to mint {@code token}, sets
 * {@code tokenType} to {@code "Bearer"}, and maps the legacy {@code SEC-USR-TYPE} code
 * to the Spring Security {@code role}. No business logic is performed here.</p>
 *
 * <h2>JSON contract</h2>
 * <p>Serialized by Jackson using the record's canonical component order, producing, for
 * example:</p>
 * <pre>{@code
 * {
 *   "token": "eyJhbGciOiJIUzI1Ni'...'",
 *   "tokenType": "Bearer",
 *   "userId": "ADMIN001",
 *   "userType": "A",
 *   "role": "ADMIN"
 * }
 * }</pre>
 *
 * @param token     the HS256-signed JWT (1&nbsp;hour expiry) issued by
 *                  {@code JwtTokenProvider}; the client attaches this as the bearer
 *                  credential on subsequent requests
 * @param tokenType the HTTP authorization scheme for {@link #token()}; always
 *                  {@code "Bearer"}
 * @param userId    the authenticated user's identifier ({@code SEC-USR-ID} /
 *                  {@code CDEMO-USER-ID}), an upper-cased value of up to 8 characters
 * @param userType  the legacy single-character user-type code preserved for parity:
 *                  {@code "A"} for an administrator or {@code "U"} for a regular user
 *                  ({@code SEC-USR-TYPE} / {@code CDEMO-USER-TYPE})
 * @param role      the Spring Security role derived from {@code userType}:
 *                  {@code "ADMIN"} when {@code userType} is {@code "A"}, otherwise
 *                  {@code "USER"}; provided for client convenience
 */
public record SignonResponse(
        String token,
        String tokenType,
        String userId,
        String userType,
        String role
) {

    /**
     * Fixed mask substituted for the bearer token in every textual rendering of this record.
     * A constant mask is used so that neither the token value nor its length is ever disclosed.
     */
    private static final String TOKEN_MASK = "****";

    /**
     * Returns a diagnostic-safe string representation of this response.
     *
     * <p>A {@code record}'s compiler-generated {@code toString()} renders every component, which
     * would include the live bearer JWT in {@link #token()} and could leak a usable credential into
     * application logs (for example via {@code log.debug("resp={}", response)}). This override
     * always replaces the token with a fixed mask, honoring the rule that the token must never be
     * logged or echoed (AAP &sect;0.6.8, &sect;0.7.1); the non-sensitive identity/role fields are
     * rendered as-is to retain their diagnostic value.</p>
     *
     * @return a string representation in which {@code token} is replaced by a fixed mask
     */
    @Override
    public String toString() {
        return "SignonResponse[token=" + TOKEN_MASK
                + ", tokenType=" + tokenType
                + ", userId=" + userId
                + ", userType=" + userType
                + ", role=" + role
                + "]";
    }
}
