package com.aws.carddemo;

/**
 * Shared test-scope access to the externalized seed-principal password (review finding #5,
 * AAP &sect;0.7.1 "no hardcoded credentials").
 *
 * <p>The {@code USRSEC} seed principals are no longer seeded with a committed cleartext literal.
 * {@code V2__reference_data.sql} seeds them via the Flyway placeholder
 * {@code ${carddemo_seed_password}}, which {@code application.yml} binds to the
 * {@code CARDDEMO_SEED_PASSWORD} environment variable with <em>no</em> committed default
 * (fail-closed, mirroring {@code SPRING_DATASOURCE_PASSWORD}). A clean checkout therefore contains
 * no reusable secret; the value is provisioned on the host (see {@code docs/onboarding.md}).</p>
 *
 * <p>This helper draws a deliberate line between two distinct concerns:</p>
 * <ul>
 *   <li>{@link #seedPassword()} &mdash; used only by the <em>integration</em> tests that authenticate
 *       against the real Flyway-seeded database. It reads the same environment variable that seeds the
 *       rows, so the entered and stored values always agree, and it fails loudly (rather than falling
 *       back to a literal) when the variable is absent, preserving the fail-closed contract.</li>
 *   <li>{@link #UNIT_FIXTURE_PASSWORD} &mdash; an obvious, non-secret placeholder for pure
 *       <em>unit</em> tests that construct in-memory {@code CardDemoUserDetails}/mock records. Those
 *       tests never touch the database, so the exact string is immaterial; using a clearly fake,
 *       self-describing value keeps a real credential out of the source tree while keeping the unit
 *       tests independent of the environment.</li>
 * </ul>
 *
 * <p>Net-new, framework-mandated test utility for the COBOL &rarr; Java/Spring Boot migration; there
 * is no COBOL source equivalent.</p>
 */
public final class TestCredentials {

    /**
     * Name of the environment variable that supplies the externalized seed-principal password.
     * Provisioned on the host (never committed); consumed by the Flyway placeholder in
     * {@code V2__reference_data.sql} and by {@link #seedPassword()}.
     */
    public static final String SEED_PASSWORD_ENV = "CARDDEMO_SEED_PASSWORD";

    /**
     * Non-secret, self-describing password used by pure unit tests that build in-memory principals.
     *
     * <p>This is <em>not</em> a credential for any account: the real seed principals' password comes
     * from {@link #seedPassword()} (environment-provided). Unit tests only need a stable, non-blank
     * string to exercise getters/branches, so this obvious fake keeps them independent of the host
     * environment while ensuring no reusable secret is committed. It is all-uppercase and 8 characters
     * so that any unit test also touching the fixed-width / uppercasing behavior stays representative.</p>
     */
    public static final String UNIT_FIXTURE_PASSWORD = "UNITPWD1";

    private TestCredentials() {
        // Utility holder; not instantiable.
    }

    /**
     * Returns the externalized seed-principal password for integration tests that authenticate against
     * the real Flyway-seeded {@code USRSEC} rows.
     *
     * @return the value of the {@link #SEED_PASSWORD_ENV} environment variable
     * @throws IllegalStateException if the environment variable is unset or blank &mdash; the same
     *                               fail-closed condition under which the Flyway migration itself would
     *                               fail, with an actionable message pointing at the provisioning docs
     */
    public static String seedPassword() {
        String value = System.getenv(SEED_PASSWORD_ENV);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    SEED_PASSWORD_ENV + " must be set for the integration suite (it seeds the local/test "
                            + "USRSEC principals via the Flyway placeholder ${carddemo_seed_password} and "
                            + "is read here to authenticate against them). Provision it on the host as "
                            + "described in docs/onboarding.md; there is no committed default by design "
                            + "(fail-closed, AAP 0.7.1 no-hardcoded-credentials).");
        }
        return value;
    }
}
