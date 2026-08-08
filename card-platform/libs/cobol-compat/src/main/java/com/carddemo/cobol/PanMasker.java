package com.carddemo.cobol;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Masks a Primary Account Number (PAN) for a published payload or a log line, and derives the
 * card token that identifies a card where a card number must not travel.
 *
 * <p>This class has no COBOL ancestor. No masking exists in the CardDemo source, so the locators
 * below are references rather than ancestors.
 *
 * <p>The card record stores the card number as {@code CARD-NUM PIC X(16)} at
 * {@code app/cpy/CVACT02Y.cpy:L5}. The same record stores the card verification value in the
 * clear as {@code CARD-CVV-CD PIC 9(03)} at {@code app/cpy/CVACT02Y.cpy:L7}. The card detail
 * screen shows all sixteen characters unprotected: {@code CARDSID DFHMDF} carries
 * {@code ATTRB=(FSET,NORM,UNPROT)} and {@code LENGTH=16} at
 * {@code app/bms/COCRDSL.bms:L96-100}.
 *
 * <p>Two obligations fall on the caller, because a helper class cannot enforce either one.
 * Use {@link #maskCardNumber(String)} before emitting a card number to an event, a log or an
 * application programming interface response, and never emit a card verification value.
 * Resolve the authorization decision on the full card number first: the card cross-reference
 * lookup keys on all sixteen characters.
 *
 * <p>A masked card number identifies nothing. Twelve of its sixteen characters are the mask, so
 * two cards ending in the same four digits produce one masked value. {@link #cardToken(String)}
 * supplies the identity instead: one card yields one token, and the derivation is
 * collision-resistant rather than collision-free, since a fixed-length code over a larger input
 * space cannot rule a collision out. A route key, a storage key and an ownership authority take
 * the token, and the masked form stays display data.
 *
 * <p>A card token is a <strong>keyed</strong> value. The card-number space is finite and its
 * shape is public, so an unkeyed digest of a card number can be recomputed for every candidate
 * card number by anyone holding a token. {@link #cardToken(String)} therefore takes a keyed
 * message authentication code under a deployment-supplied key, which a holder of the token does
 * not have. No key is compiled in and no key has a default: a deployment that configures none
 * derives no token at all, because a token derived under a key everybody knows is not a token.
 * {@link #CARD_TOKEN_SECRET_PROPERTY} and {@link #CARD_TOKEN_SECRET_VARIABLE} name the two places
 * the key is read from.
 *
 * <p>A token carries a version, from {@link #CARD_TOKEN_VERSION_PROPERTY} or
 * {@link #CARD_TOKEN_VERSION_VARIABLE}, and the version is part of the message the code covers.
 * Raising the version therefore rolls every token over under the same key, and turning the key
 * over rolls every token over under the same version. Either is a deliberate act with one
 * consequence: a stored token, a granted ownership authority and a cursor already issued name the
 * card they named under the previous key and version, so both are migrated together.
 * {@code card-platform/docs/suggested-next-tasks.md} carries the procedure.
 *
 * <p>Card number validation in the source is a numeric class test only, at
 * {@code app/cbl/COCRDUPC.cbl:L782-784}. This class adds no further card-number validation.
 */
public final class PanMasker {

    /** The character that replaces a hidden card-number digit. */
    public static final char MASK_CHARACTER = '*';

    /** The count of trailing card-number characters left visible. */
    public static final int VISIBLE_DIGIT_COUNT = 4;

    /** The width of {@code CARD-NUM PIC X(16)} at {@code app/cpy/CVACT02Y.cpy:L5}. */
    public static final int CARD_NUMBER_LENGTH = 16;

    /** The width of {@code CARD-CVV-CD PIC 9(03)} at {@code app/cpy/CVACT02Y.cpy:L7}. */
    public static final int CARD_VERIFICATION_VALUE_LENGTH = 3;

    /**
     * The fixed result of every card verification value redaction. Three mask characters,
     * matching the width of {@code CARD-CVV-CD PIC 9(03)}.
     */
    public static final String REDACTED_CARD_VERIFICATION_VALUE =
            String.valueOf(MASK_CHARACTER).repeat(CARD_VERIFICATION_VALUE_LENGTH);

    /**
     * The width of a card token: the sixty-four hexadecimal characters a 256-bit message
     * authentication code renders as.
     */
    public static final int CARD_TOKEN_LENGTH = 64;

    /**
     * The shape of a card token: exactly {@value #CARD_TOKEN_LENGTH} lower-case hexadecimal
     * characters.
     *
     * <p>A schema document, a check constraint and a route parameter each declare this shape, so
     * a value that is not a token is refused before it reaches a query.
     */
    public static final String CARD_TOKEN_PATTERN = "^[0-9a-f]{" + CARD_TOKEN_LENGTH + "}$";

    /** The token a caller receives for an absent card number: every character a zero. */
    public static final String ABSENT_CARD_TOKEN = "0".repeat(CARD_TOKEN_LENGTH);

    /**
     * System property that supplies the card-token key. Read before
     * {@link #CARD_TOKEN_SECRET_VARIABLE}, so a test or a single process overrides a deployment
     * without editing its environment.
     */
    public static final String CARD_TOKEN_SECRET_PROPERTY = "carddemo.card-token.secret";

    /** Environment variable that supplies the card-token key where the property is unset. */
    public static final String CARD_TOKEN_SECRET_VARIABLE = "CARD_TOKEN_SECRET";

    /** System property that supplies the card-token version. */
    public static final String CARD_TOKEN_VERSION_PROPERTY = "carddemo.card-token.version";

    /** Environment variable that supplies the card-token version where the property is unset. */
    public static final String CARD_TOKEN_VERSION_VARIABLE = "CARD_TOKEN_VERSION";

    /** The version every token carries where a deployment names none. */
    public static final String DEFAULT_CARD_TOKEN_VERSION = "1";

    /**
     * The card-token key this repository publishes for its demonstration stack.
     *
     * <p>It is written down in {@code card-platform/.env.example} and in
     * {@code card-platform/deploy/k8s/31-secret.example.yaml}, and the fifty {@code card_token}
     * literals the card service seeds and the {@code SCOPE_CARD} authority the demonstration
     * grants were all derived under it. That is why it is a working value rather than a
     * {@code REPLACE} placeholder: a placeholder here would leave every seeded row keyed to
     * nothing and every card request answering 403.
     *
     * <p>A published key is a key every reader of this repository holds, so a token taken under it
     * is recomputable for any candidate card number, which is the one property the key exists to
     * supply. {@link #requireCardTokenSecretFitForUse()} is what stops a deployment carrying this
     * value by accident.
     */
    public static final String PUBLISHED_DEMO_CARD_TOKEN_SECRET =
            "carddemo-demo-card-token-key-not-for-production";

    /**
     * System property by which a deployment states that it is the demonstration and means to run
     * under {@link #PUBLISHED_DEMO_CARD_TOKEN_SECRET}.
     *
     * <p>Read the same way the key and the version are read, rather than through a framework, so
     * one class owns the resolution of all three.
     */
    public static final String CARD_TOKEN_ALLOW_PUBLISHED_KEY_PROPERTY =
            "carddemo.card-token.allow-published-key";

    /** Environment variable that carries the same statement where the property is unset. */
    public static final String CARD_TOKEN_ALLOW_PUBLISHED_KEY_VARIABLE =
            "CARD_TOKEN_ALLOW_PUBLISHED_KEY";

    /**
     * Characters a configured key must hold at least.
     *
     * <p>Thirty-two bytes is the digest length of the underlying hash, whose block is 64 bytes. A
     * key shorter than the digest length adds less key material than the code it produces carries,
     * so it is refused at the point of use instead of silently weakening every token.
     */
    public static final int CARD_TOKEN_SECRET_MIN_LENGTH = 32;

    /**
     * The label the token code covers ahead of the version and the card number.
     *
     * <p>The label separates this code from every other code taken under the same key. A code
     * taken elsewhere over the bare card number does not equal a token, so one cannot be mistaken
     * for the other.
     */
    private static final String CARD_TOKEN_LABEL_PREFIX = "CardDemo/card-token/v";

    /** Separates the version marker from the card number inside the covered message. */
    private static final char CARD_TOKEN_LABEL_TERMINATOR = ':';

    /** The keyed algorithm {@link #cardToken(String)} applies. */
    private static final String CARD_TOKEN_ALGORITHM = "HmacSHA256";

    /** What a rendered key holder shows in place of key material. */
    private static final String REDACTED_KEY = "[redacted]";

    /** The shape a configured version must take: one to three digits, the first of them non-zero. */
    private static final Pattern CARD_TOKEN_VERSION = Pattern.compile("^[1-9][0-9]{0,2}$");

    /**
     * The key last resolved, held so that a repeated derivation does not rebuild it.
     *
     * <p>The entry is replaced whenever the configured version or key changes, which is what lets
     * a rollover take effect inside a running process and lets a test exercise two versions in
     * one run. The reference holds the key material rather than a hash of it, so nothing here is
     * written to a log or carried into an exception message.
     */
    private static final AtomicReference<TokenKey> RESOLVED_KEY = new AtomicReference<>();

    /** The twelve mask characters that precede the four visible characters. */
    private static final String MASK_PREFIX =
            String.valueOf(MASK_CHARACTER).repeat(CARD_NUMBER_LENGTH - VISIBLE_DIGIT_COUNT);

    /**
     * A card number with all sixteen characters hidden, which
     * {@link #maskCardNumber(String)} returns for an argument that names no card number.
     */
    public static final String FULLY_MASKED_CARD_NUMBER =
            String.valueOf(MASK_CHARACTER).repeat(CARD_NUMBER_LENGTH);

    private static final HexFormat HEX = HexFormat.of();

    private PanMasker() {
    }

    /**
     * Replaces every character of a card number except the last four with the mask character.
     *
     * <p>The result is always exactly {@value #CARD_NUMBER_LENGTH} characters wide, matching
     * {@code CARD-NUM PIC X(16)}. Leading and trailing whitespace is discarded before the last
     * four characters are read.
     *
     * <p>An argument wider than {@value #CARD_NUMBER_LENGTH} characters normalizes to the stored
     * width. The result keeps the last {@value #VISIBLE_DIGIT_COUNT} characters of the stripped
     * argument and no other character of it. The mask-character count therefore stays at
     * {@value #CARD_NUMBER_LENGTH} minus {@value #VISIBLE_DIGIT_COUNT}, whatever the argument
     * width.
     *
     * <p>A {@code null} argument, a blank argument, and an argument of
     * {@value #VISIBLE_DIGIT_COUNT} characters or fewer each yield a fully masked result. No
     * argument value passes through unmasked. No method on this class reverses the result.
     *
     * @param cardNumber the full card number, as stored in {@code CARD-NUM}; may be
     *                   {@code null}
     * @return sixteen characters, ending in at most the last four characters of the argument
     */
    public static String maskCardNumber(String cardNumber) {
        if (cardNumber == null) {
            return FULLY_MASKED_CARD_NUMBER;
        }

        String value = cardNumber.strip();
        if (value.length() <= VISIBLE_DIGIT_COUNT) {
            return FULLY_MASKED_CARD_NUMBER;
        }

        String visible = value.substring(value.length() - VISIBLE_DIGIT_COUNT);
        return MASK_PREFIX + visible;
    }

    /**
     * Replaces a card verification value with a fixed redaction of
     * {@value #CARD_VERIFICATION_VALUE_LENGTH} mask characters.
     *
     * <p>Every argument yields the same result, including {@code null}. No digit of the
     * argument reaches the result. The card record stores this field as
     * {@code CARD-CVV-CD PIC 9(03)} at {@code app/cpy/CVACT02Y.cpy:L7}; a caller must not
     * emit it in any form other than this redaction.
     *
     * @param cardVerificationValue the stored card verification value; may be {@code null}
     * @return three mask characters
     */
    public static String redactCardVerificationValue(String cardVerificationValue) {
        return REDACTED_CARD_VERIFICATION_VALUE;
    }

    /**
     * Returns the token of one card number, or {@link #ABSENT_CARD_TOKEN} when none is supplied.
     *
     * <p>The same value {@link #cardToken(String)} returns. Two names exist because callers on both
     * sides of the platform were written against different ones, and a token has to be one value
     * everywhere or a read model keyed on it splits.
     *
     * @param cardNumber the full card number, or {@code null} or blank when the caller holds none
     * @return {@value #CARD_TOKEN_LENGTH} lower-case hexadecimal characters
     * @throws IllegalStateException if a card number is supplied and no card-token key is
     *                               configured
     */
    public static String tokenOf(String cardNumber) {
        if (cardNumber == null || cardNumber.isBlank()) {
            return ABSENT_CARD_TOKEN;
        }
        return cardToken(cardNumber);
    }

    /**
     * Refuses the published demonstration key unless the deployment says it means to use it.
     *
     * <p>Call this once while a service starts. Every derivation site already refuses an absent or
     * too-short key; what it cannot see is that a configured key is one this repository states in
     * plain text. Without this check a deployment that copied the value out of
     * {@code .env.example} keeps working and says nothing, and its tokens are recomputable by
     * anyone holding the repository.
     *
     * <p>No shipped path carries the published key. {@code .env.example} and
     * {@code deploy/k8s/31-secret.example.yaml} both carry a placeholder,
     * {@code card-platform/scripts/generate-env.sh} fills it with a key generated for the install,
     * and {@code com.carddemo.card.domain.CardTokenReconciler} re-derives the seeded literals under
     * whatever key arrives. {@value #CARD_TOKEN_ALLOW_PUBLISHED_KEY_VARIABLE} therefore appears in
     * exactly one place, {@code card-platform/pom.xml}, which sets the property for the test run
     * because the build supplies a key of its own. A deployment that deliberately means to run on
     * the published key sets the variable itself; anything else rotates the key, and
     * {@code card-platform/docs/suggested-next-tasks.md} carries that procedure with the three
     * artifacts a rotation re-derives.
     *
     * <p>An absent key is not refused here. A service that never derives a token needs none, and
     * the one that does refuses it at the derivation.
     *
     * @return {@code true} where the published key is configured and the deployment stated it
     *         deliberately, so a caller may report that it is running on a published key;
     *         {@code false} where the configured key is the deployment's own or none is configured
     * @throws IllegalStateException where the published key is configured and no statement
     *                               accompanies it
     */
    public static boolean requireCardTokenSecretFitForUse() {
        String configured = configured(CARD_TOKEN_SECRET_PROPERTY, CARD_TOKEN_SECRET_VARIABLE);
        if (!PUBLISHED_DEMO_CARD_TOKEN_SECRET.equals(configured)) {
            return false;
        }
        String stated = configured(CARD_TOKEN_ALLOW_PUBLISHED_KEY_PROPERTY,
                CARD_TOKEN_ALLOW_PUBLISHED_KEY_VARIABLE);
        if (!Boolean.parseBoolean(stated)) {
            throw new IllegalStateException("the configured card-token key is the one this"
                    + " repository publishes for its demonstration stack, so every token it"
                    + " derives is recomputable by anyone holding this repository. Set "
                    + CARD_TOKEN_SECRET_PROPERTY + " or "
                    + CARD_TOKEN_SECRET_VARIABLE + " to a key of your own, at least "
                    + CARD_TOKEN_SECRET_MIN_LENGTH + " characters, and re-derive the three"
                    + " artifacts card-platform/docs/suggested-next-tasks.md lists. To run the"
                    + " demonstration on the published key deliberately, set "
                    + CARD_TOKEN_ALLOW_PUBLISHED_KEY_PROPERTY + " or "
                    + CARD_TOKEN_ALLOW_PUBLISHED_KEY_VARIABLE + " to true.");
        }
        return true;
    }

    /**
     * Derives the card token of one card number.
     *
     * <p>The token is the {@value #CARD_TOKEN_ALGORITHM} code, taken under the configured key,
     * over the label, the configured version and the stripped card number, rendered as
     * {@value #CARD_TOKEN_LENGTH} lower-case hexadecimal characters. It always matches
     * {@value #CARD_TOKEN_PATTERN}.
     *
     * <p>Four properties are what callers rely on. Under one key and one version the same card
     * number always yields the same token, so a service that never meets the card number twice
     * still keys its rows on one value. Two different card numbers are overwhelmingly likely to
     * yield different tokens, which is the identity a masked card number cannot supply. No character of the card number
     * survives into the token, so a token is safe in a route, an access log, a trace and a stored
     * key. And a holder of the token cannot recompute it for a candidate card number, because
     * recomputing it needs the key.
     *
     * <p>The key is read at each derivation from {@link #CARD_TOKEN_SECRET_PROPERTY} and then
     * {@link #CARD_TOKEN_SECRET_VARIABLE}, and the version from
     * {@link #CARD_TOKEN_VERSION_PROPERTY} and then {@link #CARD_TOKEN_VERSION_VARIABLE}. Reading
     * per derivation rather than once at class initialization is what makes a rollover take effect
     * without a restart. The resolved key is held between derivations, so the cost of a repeat is
     * one comparison.
     *
     * <p>Leading and trailing whitespace is discarded, so a value read from a fixed-width
     * {@code CARD-NUM PIC X(16)} field and the same value trimmed produce one token. Nothing else
     * is normalized: a value of another width is a different card number and yields a different
     * token.
     *
     * <p>No exception message here quotes the card number, the key or any part of either. A
     * message about the key reports its width and nothing else.
     *
     * @param cardNumber the full card number, as stored in {@code CARD-NUM}; must not be
     *                   {@code null} and must hold at least one non-whitespace character
     * @return {@value #CARD_TOKEN_LENGTH} lower-case hexadecimal characters
     * @throws NullPointerException     if {@code cardNumber} is {@code null}
     * @throws IllegalArgumentException if {@code cardNumber} holds no non-whitespace character,
     *                                  because a blank value names no card and every blank value
     *                                  would collapse onto one token
     * @throws IllegalStateException    if no key is configured, if the configured key is under
     *                                  {@value #CARD_TOKEN_SECRET_MIN_LENGTH} characters, or if
     *                                  the configured version is not one to three digits
     */
    public static String cardToken(String cardNumber) {
        if (cardNumber == null) {
            throw new NullPointerException("cardNumber is required to derive a card token");
        }

        String value = cardNumber.strip();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(
                    "cardNumber holds no character and names no card to tokenize");
        }

        TokenKey key = resolvedKey();
        byte[] covered = (CARD_TOKEN_LABEL_PREFIX + key.version() + CARD_TOKEN_LABEL_TERMINATOR
                + value).getBytes(StandardCharsets.UTF_8);
        return HEX.formatHex(key.code(covered));
    }

    /**
     * Returns the version every token derived now carries.
     *
     * <p>A caller records it beside a token it stores, so a later rollover can tell a token of the
     * previous version from one of the current version without re-deriving either.
     *
     * @return the configured version, or {@value #DEFAULT_CARD_TOKEN_VERSION} where none is set
     * @throws IllegalStateException if the configured version is not one to three digits
     */
    public static String cardTokenVersion() {
        String configured = configured(CARD_TOKEN_VERSION_PROPERTY, CARD_TOKEN_VERSION_VARIABLE);
        String version = configured == null ? DEFAULT_CARD_TOKEN_VERSION : configured;
        if (!CARD_TOKEN_VERSION.matcher(version).matches()) {
            throw new IllegalStateException("the configured card-token version must be one to"
                    + " three digits opening with a non-zero digit, and the configured value holds"
                    + " " + version.length() + " characters. Set " + CARD_TOKEN_VERSION_PROPERTY
                    + " or " + CARD_TOKEN_VERSION_VARIABLE + " to a value of that shape.");
        }
        return version;
    }

    /**
     * @return the key material and the version tokens are derived under
     * @throws IllegalStateException if no key is configured or the configured key is too short
     */
    private static TokenKey resolvedKey() {
        String version = cardTokenVersion();
        String secret = configuredSecret();

        TokenKey held = RESOLVED_KEY.get();
        if (held != null && held.matches(version, secret)) {
            return held;
        }

        TokenKey rebuilt = new TokenKey(version, secret,
                new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), CARD_TOKEN_ALGORITHM));
        RESOLVED_KEY.set(rebuilt);
        return rebuilt;
    }

    /**
     * Reads the configured key, and refuses to derive a token where none is configured.
     *
     * <p>No default exists on purpose. A key compiled into this repository is a key every reader
     * of it holds, and a token derived under such a key is recomputable for every candidate card
     * number, which is the whole property the key exists to supply.
     *
     * @return the configured key, stripped of surrounding whitespace
     * @throws IllegalStateException if no key is configured or the configured key is under
     *                               {@value #CARD_TOKEN_SECRET_MIN_LENGTH} characters
     */
    private static String configuredSecret() {
        String configured = configured(CARD_TOKEN_SECRET_PROPERTY, CARD_TOKEN_SECRET_VARIABLE);
        if (configured == null) {
            throw new IllegalStateException("no card-token key is configured, so no card token can"
                    + " be derived. Set the system property " + CARD_TOKEN_SECRET_PROPERTY
                    + " or the environment variable " + CARD_TOKEN_SECRET_VARIABLE
                    + ". card-platform/.env.example documents the value a demo deployment uses.");
        }
        if (configured.length() < CARD_TOKEN_SECRET_MIN_LENGTH) {
            throw new IllegalStateException("the configured card-token key holds "
                    + configured.length() + " characters and at least "
                    + CARD_TOKEN_SECRET_MIN_LENGTH + " are required, because a shorter key adds"
                    + " less key material than the code it produces carries.");
        }
        return configured;
    }

    /**
     * Reads one setting from a system property, then from an environment variable.
     *
     * @param property the system property name, read first
     * @param variable the environment variable name, read where the property carries no value
     * @return the value with surrounding whitespace removed, or {@code null} where neither carries
     *         one
     */
    private static String configured(String property, String variable) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            value = System.getenv(variable);
        }
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * One resolved card-token key: the version it derives under, the key text it was built from,
     * and the key itself.
     *
     * <p>The key text is retained only so that {@link #matches(String, String)} can tell a
     * configuration change from a repeat. Neither {@link #toString()} nor any message in this
     * class renders it.
     *
     * @param version the version marker the covered message carries
     * @param secret  the configured key text this entry was built from
     * @param key     the key the code is taken under
     */
    private record TokenKey(String version, String secret, SecretKeySpec key) {

        /**
         * Reports whether this entry was built from the supplied version and key.
         *
         * @param currentVersion the version now configured
         * @param currentSecret  the key now configured
         * @return {@code true} when neither has changed
         */
        private boolean matches(String currentVersion, String currentSecret) {
            return version.equals(currentVersion) && secret.equals(currentSecret);
        }

        /**
         * Takes the code of one covered message under this key.
         *
         * <p>A {@link Mac} holds the state of one computation, so one is built per call rather
         * than shared. {@value #CARD_TOKEN_ALGORITHM} is required of every Java platform, so
         * neither checked exception the two calls declare can arise from a valid key.
         *
         * @param covered the label, the version and the card number, as bytes
         * @return the code, thirty-two octets wide
         * @throws IllegalStateException if the runtime supplies no
         *                               {@value #CARD_TOKEN_ALGORITHM} implementation
         */
        private byte[] code(byte[] covered) {
            try {
                Mac mac = Mac.getInstance(CARD_TOKEN_ALGORITHM);
                mac.init(key);
                return mac.doFinal(covered);
            } catch (NoSuchAlgorithmException | InvalidKeyException unusable) {
                throw new IllegalStateException("this runtime supplies no usable "
                        + CARD_TOKEN_ALGORITHM + " implementation, so no card token can be"
                        + " derived", unusable);
            }
        }

        @Override
        public String toString() {
            return "TokenKey[version=" + version + ", secret=" + REDACTED_KEY + "]";
        }
    }
}
