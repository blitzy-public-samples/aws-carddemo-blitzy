package com.carddemo.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Focused unit test for the production secret-hardening guard added to
 * {@link JwtTokenProvider#init()} to close <strong>QA CKPT-5 finding S-4</strong>
 * ("Committed default JWT signing secret is the fallback for the prod profile with no
 * fail-fast guard", MAJOR).
 *
 * <p>The defect: the publicly-known committed dev default
 * ({@link JwtTokenProvider#DEV_DEFAULT_SECRET}) is 52 chars (&ge; 256 bits), so it sails
 * past the JJWT {@code WeakKeyException} length check &mdash; a production instance that
 * fell back to it could be impersonated (admin-token forgery). The guard refuses to
 * start under the {@value JwtTokenProvider#PROD_PROFILE} profile when the resolved
 * {@code jwt.secret} is a known committed default (AAP &sect;0.6.7: "production MUST
 * supply a strong JWT_SECRET").</p>
 *
 * <p>The test exercises {@link JwtTokenProvider#init()} directly (it is package-private,
 * and this test shares the {@code com.carddemo.security} package) with a
 * {@link MockEnvironment} pinning the active profile and {@link ReflectionTestUtils}
 * supplying the {@code @Value}-bound secret &mdash; no Spring context, for fast,
 * deterministic assertions. It proves the guard fires <em>only</em> in production on a
 * known default, and never burdens dev/test or a strong production secret.</p>
 */
class JwtTokenProviderSecretGuardTest {

    /** A strong, unique secret representative of a correct production {@code JWT_SECRET} (&ge; 32 chars). */
    private static final String STRONG_SECRET =
            "a-strong-unique-production-jwt-secret-key-0123456789-ABCDEFGHIJ";

    /**
     * Builds a {@link JwtTokenProvider} wired to a {@link MockEnvironment} with the given active
     * profile and the given {@code jwt.secret} value, mirroring how Spring would construct and
     * field-inject it before invoking {@code @PostConstruct init()}.
     *
     * @param activeProfile the single active profile to set, or {@code null} for none
     * @param secret        the value to inject into the {@code jwtSecret} field
     * @return a ready-to-{@code init()} provider
     */
    private JwtTokenProvider newProvider(String activeProfile, String secret) {
        MockEnvironment env = new MockEnvironment();
        if (activeProfile != null) {
            env.setActiveProfiles(activeProfile);
        }
        JwtTokenProvider provider = new JwtTokenProvider(env);
        ReflectionTestUtils.setField(provider, "jwtSecret", secret);
        ReflectionTestUtils.setField(provider, "jwtExpirationMs", 3_600_000L);
        return provider;
    }

    @Test
    @DisplayName("prod + committed DEV default secret -> init() aborts (IllegalStateException), never echoing the secret")
    void prodWithDevDefault_failsFast() {
        JwtTokenProvider provider = newProvider(JwtTokenProvider.PROD_PROFILE,
                JwtTokenProvider.DEV_DEFAULT_SECRET);

        assertThatThrownBy(provider::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET")
                .hasMessageContaining(JwtTokenProvider.PROD_PROFILE)
                // The secret value itself must never be leaked into the failure message.
                .hasMessageNotContaining(JwtTokenProvider.DEV_DEFAULT_SECRET);
    }

    @Test
    @DisplayName("prod + committed TEST default secret -> init() aborts (IllegalStateException)")
    void prodWithTestDefault_failsFast() {
        JwtTokenProvider provider = newProvider(JwtTokenProvider.PROD_PROFILE,
                JwtTokenProvider.TEST_DEFAULT_SECRET);

        assertThatThrownBy(provider::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(JwtTokenProvider.TEST_DEFAULT_SECRET);
    }

    @Test
    @DisplayName("prod + strong unique secret -> init() succeeds and the provider can mint+verify a token")
    void prodWithStrongSecret_startsAndWorks() {
        JwtTokenProvider provider = newProvider(JwtTokenProvider.PROD_PROFILE, STRONG_SECRET);

        assertThatCode(provider::init).doesNotThrowAnyException();

        // Prove the guard does not break normal production operation: a token minted with the
        // strong secret round-trips through the same provider's verification.
        String token = provider.generateToken("ADMIN001", "A");
        assertThat(provider.validateToken(token)).isTrue();
        assertThat(provider.getUserId(token)).isEqualTo("ADMIN001");
        assertThat(provider.getUserType(token)).isEqualTo("A");
    }

    @Test
    @DisplayName("dev + committed DEV default secret -> init() succeeds (guard is prod-only)")
    void devWithDevDefault_isAllowed() {
        JwtTokenProvider provider = newProvider("dev", JwtTokenProvider.DEV_DEFAULT_SECRET);
        assertThatCode(provider::init).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("test + committed TEST default secret -> init() succeeds (guard is prod-only)")
    void testProfileWithTestDefault_isAllowed() {
        JwtTokenProvider provider = newProvider("test", JwtTokenProvider.TEST_DEFAULT_SECRET);
        assertThatCode(provider::init).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("no active profile + committed DEV default secret -> init() succeeds (only prod is guarded)")
    void noProfileWithDevDefault_isAllowed() {
        JwtTokenProvider provider = newProvider(null, JwtTokenProvider.DEV_DEFAULT_SECRET);
        assertThatCode(provider::init).doesNotThrowAnyException();
    }
}
