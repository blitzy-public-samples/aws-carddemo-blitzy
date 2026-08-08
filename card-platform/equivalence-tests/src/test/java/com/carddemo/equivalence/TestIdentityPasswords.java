package com.carddemo.equivalence;

/**
 * The four identity passwords every test in this module configures, each with the encoded form it
 * is configured as.
 *
 * <p>A service refuses to start on an identity password whose encoding this platform does not
 * approve, and {@code noop} - the encoding that stores a password in plain text - is one of the
 * refused ones. A test therefore configures a real hash, which has the further benefit that it
 * exercises the verification path a deployment exercises rather than a shortcut around it.
 *
 * <p>Each hash below is bcrypt at cost ten, the floor {@code SecurityConfig.BCRYPT_MINIMUM_COST}
 * sets. The plaintext beside it is what a test presents in an {@code Authorization: Basic} header.
 * The two are only useful as a pair, so {@code SecurityConfigTest} asserts that each hash still
 * verifies its plaintext: a hash regenerated on its own then fails one test instead of every
 * authentication in the module.
 *
 * <p>None of these values is a credential. Each plaintext says as much in its own text, and none
 * authenticates anything outside this build.
 */
public final class TestIdentityPasswords {

    /** Plaintext of the administrator identity, presented by a test that authenticates. */
    public static final String ADMIN_PASSWORD = "not-a-real-admin-password";

    /**
     * Encoded form of {@link #ADMIN_PASSWORD}, configured as the
     * {@code ADMIN_PASSWORD_HASH} variable.
     */
    public static final String ADMIN_PASSWORD_HASH =
            "{bcrypt}$2a$10$rpp6tMOhEDokcz/6ZF2yeeC/Fvu7TElSFayJGFxxIYBLNZ8OzkjPe";

    /** Plaintext of the acquirer workload identity, presented by a test that authenticates. */
    public static final String ACQUIRER_PASSWORD = "not-a-real-acquirer-password";

    /**
     * Encoded form of {@link #ACQUIRER_PASSWORD}, configured as the
     * {@code ACQUIRER_PASSWORD_HASH} variable.
     */
    public static final String ACQUIRER_PASSWORD_HASH =
            "{bcrypt}$2a$10$WlwyMW2ysi2uW01Tcuyx8egKFqWwpbBEvQXJoCxrFoUj4Ox8U273u";

    /** Plaintext of the ordinary identity, presented by a test that authenticates. */
    public static final String USER_PASSWORD = "not-a-real-user-password";

    /**
     * Encoded form of {@link #USER_PASSWORD}, configured as the
     * {@code USER_PASSWORD_HASH} variable.
     */
    public static final String USER_PASSWORD_HASH =
            "{bcrypt}$2a$10$KC6OMqYbnujYlEArxy3Ci.5aSUb7AyqA6pdoo7GfL1A4QVIuxro6G";

    /** Plaintext of the monitoring identity, presented by a test that authenticates. */
    public static final String MONITORING_PASSWORD = "not-a-real-monitoring-password";

    /**
     * Encoded form of {@link #MONITORING_PASSWORD}, configured as the
     * {@code MONITORING_PASSWORD_HASH} variable.
     */
    public static final String MONITORING_PASSWORD_HASH =
            "{bcrypt}$2a$10$mmAfX7gKiGBexXIsYhIiBO6frikRgirA.I3L5J2A9cdpBdM/U.zNG";

    /** Holds constants only. */
    private TestIdentityPasswords() {
    }
}
