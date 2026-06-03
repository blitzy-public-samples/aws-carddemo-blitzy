package com.carddemo.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Minimal, dependency-free encoder/decoder for compact HS256 (HMAC-SHA256) JSON Web
 * Tokens, built entirely on the JDK (<code>javax.crypto.Mac</code>, {@link Base64} URL
 * codec, {@link MessageDigest}) plus the Jackson {@link ObjectMapper} already on the
 * classpath via {@code spring-boot-starter-web}.
 *
 * <h2>Why this class exists</h2>
 * <p>The final-checkpoint dependency policy (and AAP &sect;0.5.1, which did not list
 * JJWT) forbids a standalone JWT library on the production dependency graph. This codec
 * replaces the previously-used {@code io.jsonwebtoken:jjwt-*} artifacts with a small,
 * auditable, BOM-free implementation. Token creation moved here from {@code AuthService}
 * and verification moved here from {@code JwtAuthenticationFilter}; both collaborators
 * call this single codec with the shared {@code jwt.secret} bytes, so every issued token
 * verifies and the on-the-wire token shape is unchanged
 * ({@code alg=HS256}; claims {@code sub}, {@code userType}, {@code iat}, {@code exp}).</p>
 *
 * <h2>Security properties (parity with the removed JJWT behaviour)</h2>
 * <ul>
 *   <li><b>Constant-time signature comparison</b> via {@link MessageDigest#isEqual(byte[], byte[])}
 *       &mdash; avoids timing side-channels when checking the HMAC.</li>
 *   <li><b>Algorithm pinning</b> &mdash; verification recomputes an HS256 MAC and
 *       additionally asserts the decoded header advertises {@code "alg":"HS256"},
 *       rejecting the {@code "none"} algorithm and any {@code alg}-confusion attempt.</li>
 *   <li><b>Expiry enforcement</b> &mdash; a present numeric {@code exp} claim is checked
 *       against the current epoch second; {@code now >= exp} is treated as expired,
 *       matching the JJWT clock semantics previously relied upon.</li>
 *   <li><b>Structural validation</b> &mdash; a token that is not exactly three
 *       base64url segments, or whose segments are not valid base64url / JSON, is
 *       rejected as malformed.</li>
 * </ul>
 *
 * <p>The HS256 key strength requirement (&ge; 256-bit secret) is enforced by the
 * caller ({@code JwtAuthenticationFilter#initSigningKey()} validates the secret length
 * at startup); this codec performs the cryptographic primitive only.</p>
 *
 * @since 1.0
 */
public final class JwtCodec {

    /** JCA algorithm name for the HMAC primitive. */
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /** JWS {@code alg} header value this codec produces and is willing to verify. */
    private static final String JWT_ALG = "HS256";

    /** JWT header field name for the signing algorithm. */
    private static final String HEADER_ALG = "alg";

    /** JWT header field name for the token type. */
    private static final String HEADER_TYP = "typ";

    /** Registered claim name for the subject (the user id; was {@code CDEMO-USER-ID}). */
    public static final String CLAIM_SUBJECT = "sub";

    /** Registered claim name for the issued-at timestamp (epoch seconds). */
    private static final String CLAIM_ISSUED_AT = "iat";

    /** Registered claim name for the expiry timestamp (epoch seconds). */
    private static final String CLAIM_EXPIRATION = "exp";

    /** Shared Jackson mapper; thread-safe once configured and used read-only here. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Base64url encoder without padding, per the JWS compact serialization rules. */
    private static final Base64.Encoder B64URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    /** Base64url decoder (tolerates the unpadded segments produced above). */
    private static final Base64.Decoder B64URL_DECODER = Base64.getUrlDecoder();

    /** Reusable type token for deserializing a JSON object into a {@link Map}. */
    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<>() { };

    private JwtCodec() {
        // Utility class: not instantiable. Intentionally empty (throws nothing) per the
        // final-checkpoint rule that utility constructors must not throw.
    }

    /**
     * Builds a compact, signed HS256 JWT.
     *
     * <p>Produces {@code base64url(header).base64url(payload).base64url(HMAC-SHA256)}
     * where the header is {@code {"alg":"HS256","typ":"JWT"}} and the payload carries
     * the subject, any additional claims, and (when supplied) the {@code iat}/{@code exp}
     * timestamps as epoch seconds.</p>
     *
     * @param keyBytes         the raw HS256 secret key bytes (UTF-8 of {@code jwt.secret});
     *                         must be non-null
     * @param subject          the {@code sub} claim (the user id); written only when non-null
     * @param additionalClaims extra claims to embed (e.g. {@code userType}); may be null/empty
     * @param issuedAt         the {@code iat} instant; written (as epoch seconds) when non-null
     * @param expiresAt        the {@code exp} instant; written (as epoch seconds) when non-null
     * @return the compact, signed JWT string
     * @throws IllegalStateException if the claims cannot be serialized or the HMAC fails
     */
    public static String createHs256(byte[] keyBytes,
                                     String subject,
                                     Map<String, ?> additionalClaims,
                                     Instant issuedAt,
                                     Instant expiresAt) {
        try {
            Map<String, Object> header = new LinkedHashMap<>();
            header.put(HEADER_ALG, JWT_ALG);
            header.put(HEADER_TYP, "JWT");

            Map<String, Object> payload = new LinkedHashMap<>();
            if (subject != null) {
                payload.put(CLAIM_SUBJECT, subject);
            }
            if (additionalClaims != null) {
                payload.putAll(additionalClaims);
            }
            if (issuedAt != null) {
                payload.put(CLAIM_ISSUED_AT, issuedAt.getEpochSecond());
            }
            if (expiresAt != null) {
                payload.put(CLAIM_EXPIRATION, expiresAt.getEpochSecond());
            }

            String encodedHeader = B64URL_ENCODER.encodeToString(MAPPER.writeValueAsBytes(header));
            String encodedPayload = B64URL_ENCODER.encodeToString(MAPPER.writeValueAsBytes(payload));
            String signingInput = encodedHeader + "." + encodedPayload;

            byte[] signature = hmacSha256(keyBytes, signingInput.getBytes(StandardCharsets.US_ASCII));
            String encodedSignature = B64URL_ENCODER.encodeToString(signature);

            return signingInput + "." + encodedSignature;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize JWT claims", e);
        }
    }

    /**
     * Verifies a compact HS256 JWT and returns its decoded claim set.
     *
     * <p>Verification steps, in order: split into exactly three base64url segments;
     * recompute the HS256 MAC over {@code header.payload} and compare it to the supplied
     * signature in constant time; assert the header {@code alg} is {@code HS256}
     * (blocking {@code none}/algorithm-confusion); then, if a numeric {@code exp} is
     * present, reject the token when {@code now >= exp}.</p>
     *
     * @param keyBytes the raw HS256 secret key bytes (UTF-8 of {@code jwt.secret})
     * @param token    the bare JWT (no {@code Bearer } prefix)
     * @return the decoded payload claims as a {@link Map}
     * @throws JwtVerificationException if the token is null/blank, malformed, has a bad
     *                                  signature, uses an unsupported algorithm, or is
     *                                  expired ({@link JwtVerificationException#isExpired()}
     *                                  distinguishes the expiry case)
     */
    public static Map<String, Object> verifyHs256(byte[] keyBytes, String token) {
        if (token == null || token.isBlank()) {
            throw new JwtVerificationException("Token is null or blank", false);
        }

        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new JwtVerificationException(
                    "Malformed JWT: expected 3 segments but found " + parts.length, false);
        }

        // Recompute and compare the signature before trusting any header/payload bytes.
        String signingInput = parts[0] + "." + parts[1];
        byte[] expectedSignature =
                hmacSha256(keyBytes, signingInput.getBytes(StandardCharsets.US_ASCII));
        byte[] actualSignature;
        try {
            actualSignature = B64URL_DECODER.decode(parts[2]);
        } catch (IllegalArgumentException e) {
            throw new JwtVerificationException("Malformed JWT: signature is not valid base64url", false);
        }
        if (!MessageDigest.isEqual(expectedSignature, actualSignature)) {
            throw new JwtVerificationException("JWT signature does not match", false);
        }

        // Pin the algorithm: reject 'none' and any alg other than HS256.
        Map<String, Object> header = decodeSegment(parts[0], "header");
        Object alg = header.get(HEADER_ALG);
        if (!JWT_ALG.equals(alg)) {
            throw new JwtVerificationException("Unsupported JWT alg: " + alg, false);
        }

        Map<String, Object> claims = decodeSegment(parts[1], "payload");

        Object exp = claims.get(CLAIM_EXPIRATION);
        if (exp instanceof Number) {
            long expEpochSecond = ((Number) exp).longValue();
            // now >= exp -> expired (matches the JJWT clock semantics this replaced).
            if (Instant.now().getEpochSecond() >= expEpochSecond) {
                throw new JwtVerificationException("JWT expired at epoch second " + expEpochSecond, true);
            }
        }

        return claims;
    }

    /**
     * Decodes a single base64url JWT segment into a JSON object map.
     *
     * @param segment the base64url-encoded JSON segment
     * @param what    a short label ({@code "header"}/{@code "payload"}) for error messages
     * @return the decoded JSON object as a {@link Map}
     * @throws JwtVerificationException if the segment is not valid base64url or not a JSON object
     */
    private static Map<String, Object> decodeSegment(String segment, String what) {
        try {
            byte[] json = B64URL_DECODER.decode(segment);
            return MAPPER.readValue(json, MAP_TYPE);
        } catch (IllegalArgumentException | IOException e) {
            throw new JwtVerificationException("Malformed JWT " + what, false);
        }
    }

    /**
     * Computes an HMAC-SHA256 over {@code data} with {@code keyBytes}.
     *
     * @param keyBytes the secret key bytes
     * @param data     the bytes to authenticate (the ASCII {@code header.payload})
     * @return the raw MAC bytes
     * @throws IllegalStateException if the JCA provider lacks HmacSHA256 or the key is invalid
     */
    private static byte[] hmacSha256(byte[] keyBytes, byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(keyBytes, HMAC_ALGORITHM));
            return mac.doFinal(data);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable or the key is invalid", e);
        }
    }

    /**
     * Thrown when a token fails verification. {@link #isExpired()} distinguishes the
     * benign "token timed out" case from a structural/signature/algorithm failure, so
     * callers can preserve the expired-vs-invalid logging distinction the JJWT
     * {@code ExpiredJwtException} vs {@code SignatureException} split previously gave.
     */
    public static final class JwtVerificationException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /** {@code true} when the failure is solely an expired {@code exp} claim. */
        private final transient boolean expired;

        /**
         * @param message human-readable reason (never contains the token or secret)
         * @param expired whether the failure is an expiry (vs. a structural/signature error)
         */
        public JwtVerificationException(String message, boolean expired) {
            super(message);
            this.expired = expired;
        }

        /**
         * @return {@code true} if the token failed only because it had expired
         */
        public boolean isExpired() {
            return expired;
        }
    }
}
