package com.carddemo.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Locale;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

/**
 * Stateless JSON Web Token (JWT) provider for the CardDemo application.
 *
 * <p><strong>Migration role.</strong> This component replaces the legacy CICS
 * {@code COMMAREA} cross-program session-handoff mechanism (copybook
 * {@code app/cpy/COCOM01Y.cpy}) with a self-contained, stateless
 * <strong>HS256</strong> JWT. In the original mainframe design, the
 * {@code CARDDEMO-COMMAREA} structure was threaded through every
 * pseudo-conversational {@code XCTL} program transfer to carry the signed-on
 * user's identity and the in-flight account/card/customer context. In the
 * Spring Boot port that COMMAREA is dissolved: the per-request state lives in
 * the bearer token presented on each call, and no server-side session is
 * retained. See AAP &sect;0.3.2, &sect;0.4.2 and &sect;0.6.7.</p>
 *
 * <p><strong>Signon issuance.</strong> The signon program
 * {@code app/cbl/COSGN00C.cbl} validated the user against the {@code USRSEC}
 * file and, on success, populated {@code CDEMO-USER-ID} and
 * {@code CDEMO-USER-TYPE} before {@code XCTL}-ing to the admin menu
 * ({@code COADM01C}) for user-type {@code 'A'} or the regular menu
 * ({@code COMEN01C}) otherwise (COSGN00C lines 226-240). That post-auth handoff
 * is reproduced here by minting a token that carries the same identity claims;
 * the menu routing becomes role-based authorization
 * ({@code ROLE_ADMIN}/{@code ROLE_USER}) downstream.</p>
 *
 * <p><strong>COMMAREA &rarr; JWT claim mapping</strong> (COCOM01Y lines 25-41):</p>
 * <ul>
 *   <li>{@code CDEMO-USER-ID  PIC X(08)} &rarr; JWT <em>subject</em> ({@code sub})
 *       and mirrored claim {@value #CLAIM_USER_ID}.</li>
 *   <li>{@code CDEMO-USER-TYPE PIC X(01)} (88-levels {@code 'A'}=admin,
 *       {@code 'U'}=user) &rarr; claim {@value #CLAIM_USER_TYPE}; drives
 *       {@code ROLE_ADMIN}/{@code ROLE_USER}.</li>
 *   <li>{@code CDEMO-CUST-ID  PIC 9(09)} &rarr; claim {@value #CLAIM_CUST_ID}
 *       (numeric; optional, absent at signon).</li>
 *   <li>{@code CDEMO-ACCT-ID  PIC 9(11)} &rarr; claim {@value #CLAIM_ACCT_ID}
 *       (numeric; optional, absent at signon).</li>
 *   <li>{@code CDEMO-CARD-NUM PIC 9(16)} &rarr; claim {@value #CLAIM_CARD_NUM}
 *       (kept as {@code String} to preserve the fixed 16-digit width and any
 *       leading zeros; optional, absent at signon).</li>
 * </ul>
 *
 * <p>At signon only the user id and user type are known, so the
 * customer/account/card claims are <em>nullable</em> and are simply omitted
 * from the token (never serialized as JSON {@code null}). As the user navigates
 * the REST API those identifiers are supplied as path/query parameters rather
 * than carried in the token; the full-claim {@link
 * #generateToken(String, String, Long, Long, String)} overload exists to honor
 * the complete COMMAREA contract should a caller wish to embed them.</p>
 *
 * <p><strong>Security properties.</strong> Tokens are signed with HS256 using a
 * key derived from the configured {@code jwt.secret} (which must be at least
 * 256 bits / 32 ASCII characters; a shorter secret causes a fail-fast
 * {@code WeakKeyException} at startup). The default lifetime is one hour,
 * configurable via {@code jwt.expiration-ms}. This class never logs or
 * serializes the raw token, passwords, card CVV, or full SSN, in keeping with
 * the PII-suppression rules of AAP &sect;0.6.8.</p>
 *
 * <p>Built with JJWT ({@code io.jsonwebtoken}) 0.12.6.</p>
 */
@Component
public class JwtTokenProvider {

    /** Logger; used at DEBUG only and never emits token contents or PII. */
    private static final Logger log = LoggerFactory.getLogger(JwtTokenProvider.class);

    // ------------------------------------------------------------------
    // Claim-name constants (identical to the COCOM01Y COMMAREA field names
    // per AAP section 0.4.2 so that JwtAuthenticationFilter and downstream
    // services reference the exact same strings and never drift on a typo).
    // ------------------------------------------------------------------

    /** Claim mirroring {@code CDEMO-USER-ID}; also stored as the JWT subject. */
    public static final String CLAIM_USER_ID = "CDEMO-USER-ID";
    /** Claim mirroring {@code CDEMO-USER-TYPE} ({@code 'A'} admin / {@code 'U'} user). */
    public static final String CLAIM_USER_TYPE = "CDEMO-USER-TYPE";
    /** Claim mirroring {@code CDEMO-CUST-ID} (optional, numeric). */
    public static final String CLAIM_CUST_ID = "CDEMO-CUST-ID";
    /** Claim mirroring {@code CDEMO-ACCT-ID} (optional, numeric). */
    public static final String CLAIM_ACCT_ID = "CDEMO-ACCT-ID";
    /** Claim mirroring {@code CDEMO-CARD-NUM} (optional, string-encoded). */
    public static final String CLAIM_CARD_NUM = "CDEMO-CARD-NUM";

    /** {@code CDEMO-USRTYP-ADMIN} 88-level value (COCOM01Y line 27). */
    public static final String USER_TYPE_ADMIN = "A";
    /** {@code CDEMO-USRTYP-USER} 88-level value (COCOM01Y line 28). */
    public static final String USER_TYPE_USER = "U";

    /** Spring Security authority granted to administrators (user-type {@code 'A'}). */
    public static final String ROLE_ADMIN = "ROLE_ADMIN";
    /** Spring Security authority granted to regular users (any non-admin type). */
    public static final String ROLE_USER = "ROLE_USER";

    // ------------------------------------------------------------------
    // Externalized configuration (bound from application*.yml; the keys are
    // contractually fixed: jwt.secret and the hyphenated jwt.expiration-ms).
    // ------------------------------------------------------------------

    /** Raw signing secret; must be &ge; 32 ASCII chars (256 bits) for HS256. */
    @Value("${jwt.secret}")
    private String jwtSecret;

    /** Token lifetime in milliseconds; defaults to 3,600,000 (one hour). */
    @Value("${jwt.expiration-ms:3600000}")
    private long jwtExpirationMs;

    /**
     * The HMAC-SHA signing key, derived once from {@link #jwtSecret}. Held as a
     * {@link SecretKey} so the same configured secret is reused for signing and
     * verification (tokens survive restarts and verify across instances).
     */
    private SecretKey key;

    /**
     * Builds the immutable signing key from the configured secret after Spring
     * has injected the {@code @Value} fields. Performed eagerly so that an
     * under-strength secret fails fast at application startup with a
     * {@code io.jsonwebtoken.security.WeakKeyException} rather than at the first
     * authentication attempt.
     */
    @PostConstruct
    void init() {
        this.key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------
    // Token generation (public API consumed by AuthService at signon).
    // ------------------------------------------------------------------

    /**
     * Mints a signon token carrying only the identity established by
     * {@code COSGN00C}: the user id (as subject and {@value #CLAIM_USER_ID})
     * and the user type ({@value #CLAIM_USER_TYPE}). The customer, account and
     * card claims are left absent, matching the COMMAREA state immediately
     * after a successful signon.
     *
     * @param userId   the authenticated user id (becomes the JWT subject); must
     *                 not be {@code null} or blank
     * @param userType the user type code ({@code 'A'} admin / {@code 'U'} user);
     *                 normalized to trimmed upper-case
     * @return a signed, compact HS256 JWT
     * @throws IllegalArgumentException if {@code userId} is {@code null}/blank
     */
    public String generateToken(String userId, String userType) {
        return generateToken(userId, userType, null, null, null);
    }

    /**
     * Mints a token carrying the full COMMAREA claim set. Optional identifiers
     * ({@code custId}, {@code acctId}, {@code cardNum}) are added only when
     * non-null; null values are omitted entirely rather than serialized as JSON
     * {@code null}, keeping the token minimal and unambiguous.
     *
     * @param userId   the authenticated user id (JWT subject); must not be
     *                 {@code null} or blank
     * @param userType the user type code ({@code 'A'}/{@code 'U'}); normalized
     *                 to trimmed upper-case, omitted if blank
     * @param custId   optional customer id ({@code CDEMO-CUST-ID}); may be
     *                 {@code null}
     * @param acctId   optional account id ({@code CDEMO-ACCT-ID}); may be
     *                 {@code null}
     * @param cardNum  optional 16-digit card number ({@code CDEMO-CARD-NUM}),
     *                 string-encoded to preserve leading zeros; may be
     *                 {@code null}
     * @return a signed, compact HS256 JWT
     * @throws IllegalArgumentException if {@code userId} is {@code null}/blank
     */
    public String generateToken(String userId, String userType,
                                Long custId, Long acctId, String cardNum) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId (JWT subject) must not be null or blank");
        }

        final String normalizedUserId = userId.trim();
        final String normalizedUserType = normalizeUserType(userType);
        final long now = System.currentTimeMillis();

        var builder = Jwts.builder()
                .subject(normalizedUserId)
                .claim(CLAIM_USER_ID, normalizedUserId)
                .issuedAt(new Date(now))
                .expiration(new Date(now + jwtExpirationMs));

        if (normalizedUserType != null) {
            builder = builder.claim(CLAIM_USER_TYPE, normalizedUserType);
        }
        if (custId != null) {
            builder = builder.claim(CLAIM_CUST_ID, custId);
        }
        if (acctId != null) {
            builder = builder.claim(CLAIM_ACCT_ID, acctId);
        }
        if (cardNum != null && !cardNum.isBlank()) {
            builder = builder.claim(CLAIM_CARD_NUM, cardNum.trim());
        }

        // Explicit HS256 selection (also implied by the 256-bit HMAC key).
        return builder.signWith(key, Jwts.SIG.HS256).compact();
    }

    // ------------------------------------------------------------------
    // Token validation & claim extraction (consumed by JwtAuthenticationFilter).
    // ------------------------------------------------------------------

    /**
     * Verifies a token's signature and temporal validity.
     *
     * @param token the compact JWT string (may be {@code null}/empty)
     * @return {@code true} if the token is well-formed, correctly signed and
     *         unexpired; {@code false} for any expired, malformed, unsupported,
     *         badly-signed, or null/empty token
     */
    public boolean validateToken(String token) {
        try {
            Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException ex) {
            // Log only the failure category at DEBUG; never the token or its
            // contents (PII suppression, AAP section 0.6.8).
            if (log.isDebugEnabled()) {
                log.debug("JWT validation failed: {}", ex.getClass().getSimpleName());
            }
            return false;
        }
    }

    /**
     * Extracts the user id (the JWT subject / {@code CDEMO-USER-ID}).
     *
     * @param token a validated JWT
     * @return the subject claim
     * @throws JwtException             if the token is invalid
     * @throws IllegalArgumentException if the token is {@code null}/empty
     */
    public String getUserId(String token) {
        return parseClaims(token).getSubject();
    }

    /**
     * Extracts the user type claim ({@code CDEMO-USER-TYPE}: {@code 'A'} or
     * {@code 'U'}).
     *
     * @param token a validated JWT
     * @return the user type, or {@code null} if the claim is absent
     * @throws JwtException             if the token is invalid
     * @throws IllegalArgumentException if the token is {@code null}/empty
     */
    public String getUserType(String token) {
        return parseClaims(token).get(CLAIM_USER_TYPE, String.class);
    }

    /**
     * Extracts the optional customer id claim ({@code CDEMO-CUST-ID}).
     *
     * @param token a validated JWT
     * @return the customer id, or {@code null} if the claim is absent
     */
    public Long getCustId(String token) {
        return getLongClaim(parseClaims(token), CLAIM_CUST_ID);
    }

    /**
     * Extracts the optional account id claim ({@code CDEMO-ACCT-ID}).
     *
     * @param token a validated JWT
     * @return the account id, or {@code null} if the claim is absent
     */
    public Long getAcctId(String token) {
        return getLongClaim(parseClaims(token), CLAIM_ACCT_ID);
    }

    /**
     * Extracts the optional card number claim ({@code CDEMO-CARD-NUM}),
     * string-encoded to preserve the fixed 16-digit width.
     *
     * @param token a validated JWT
     * @return the card number, or {@code null} if the claim is absent
     */
    public String getCardNum(String token) {
        return parseClaims(token).get(CLAIM_CARD_NUM, String.class);
    }

    /**
     * Resolves the Spring Security authority for a token, reproducing the
     * COSGN00C menu routing: user-type {@code 'A'} maps to {@link #ROLE_ADMIN},
     * every other (or absent) value maps to {@link #ROLE_USER} — mirroring the
     * COBOL {@code IF CDEMO-USRTYP-ADMIN ... ELSE ...} branch (COSGN00C lines
     * 230-240). Kept consistent with {@code CustomUserDetailsService}.
     *
     * @param token a validated JWT
     * @return a single {@link SimpleGrantedAuthority} of {@code ROLE_ADMIN} or
     *         {@code ROLE_USER}
     */
    public SimpleGrantedAuthority getAuthority(String token) {
        String userType = normalizeUserType(getUserType(token));
        if (USER_TYPE_ADMIN.equals(userType)) {
            return new SimpleGrantedAuthority(ROLE_ADMIN);
        }
        return new SimpleGrantedAuthority(ROLE_USER);
    }

    // ------------------------------------------------------------------
    // Internal helpers.
    // ------------------------------------------------------------------

    /**
     * Parses and verifies a token, returning its claim set. Centralizes the
     * JJWT 0.12.x parse idiom ({@code verifyWith(...).parseSignedClaims(...)}).
     *
     * @param token the compact JWT string
     * @return the verified {@link Claims} payload
     * @throws JwtException             if signature/structure/expiry is invalid
     * @throws IllegalArgumentException if the token is {@code null}/empty
     */
    private Claims parseClaims(String token) {
        Jws<Claims> jws = Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
        return jws.getPayload();
    }

    /**
     * Normalizes a user-type code: trims surrounding whitespace and upper-cases
     * using {@link Locale#ROOT} (locale-independent), returning {@code null} for
     * a {@code null} or blank input so the claim can be safely omitted.
     */
    private static String normalizeUserType(String userType) {
        if (userType == null) {
            return null;
        }
        String trimmed = userType.trim();
        return trimmed.isEmpty() ? null : trimmed.toUpperCase(Locale.ROOT);
    }

    /**
     * Robustly reads a numeric claim as a {@link Long}. JJWT/Jackson may
     * deserialize a JSON integer as {@link Integer} or {@link Long} depending on
     * its magnitude, so this normalizes any {@link Number} via
     * {@link Number#longValue()} and falls back to parsing a string form.
     *
     * @param claims the parsed claim set
     * @param name   the claim name
     * @return the claim as a {@code Long}, or {@code null} if absent
     */
    private static Long getLongClaim(Claims claims, String name) {
        Object value = claims.get(name);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.valueOf(value.toString().trim());
    }
}
