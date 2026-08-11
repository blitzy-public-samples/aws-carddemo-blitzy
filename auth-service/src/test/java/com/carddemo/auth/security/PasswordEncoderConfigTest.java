/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.carddemo.auth.security;

import com.carddemo.common.security.PasswordEncoderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * :purpose: Verification of {@link PasswordEncoderConfig}, the auth-service bean definition
 *   that supplies the single credential-verification policy replacing the legacy
 *   RACF/``USRSEC`` plaintext comparison (``COSGN00C`` L223, AAP 0.6.7). It asserts that the
 *   registered bean is the shared delegating encoder and — critically — that it verifies the
 *   credential form the committed ``security_users`` seed actually stores: a BARE BCrypt hash
 *   with no ``{id}`` prefix. The sign-on path is unusable if it does not.
 * :output: JUnit 5 assertions over a real (minimal) Spring context built with
 *   {@link ApplicationContextRunner}, so the bean under test is obtained exactly as the
 *   running service obtains it; no web layer and no database are involved.
 */
class PasswordEncoderConfigTest {

    /**
     * :purpose: The exact bare BCrypt hash ``V3__seed_test_data.sql`` stores for all ten
     *   migrated users; produced at BCrypt cost 10 - the tuned
     *   {@link PasswordEncoderFactory#BCRYPT_STRENGTH} - and carrying no algorithm prefix.
     */
    private static final String SEEDED_BARE_BCRYPT_HASH =
            "$2a$10$ucIRth.iIafhA4MgE1RXZ.0whYamgfRIpJebWmPswpnxmLKA/peYm";

    /** :purpose: Plaintext the seeded hash was produced from (``BCrypt('PASSWORD')``). */
    private static final String SEEDED_PLAINTEXT = "PASSWORD";

    /** :purpose: Context runner loading only the configuration under test. */
    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(PasswordEncoderConfig.class);

    /**
     * :purpose: The configuration registers exactly one {@link PasswordEncoder} bean and it is
     *   the shared {@link DelegatingPasswordEncoder}, so credentials carry their algorithm
     *   identifier and can be rotated without a mass reset.
     */
    @Test
    @DisplayName("registers a single DelegatingPasswordEncoder bean")
    void registersDelegatingPasswordEncoderBean() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(PasswordEncoder.class);
            assertThat(context.getBean(PasswordEncoder.class))
                    .isInstanceOf(DelegatingPasswordEncoder.class);
        });
    }

    /**
     * :purpose: The registered bean encodes new credentials under the ``{bcrypt}`` identifier
     *   at the tuned cost factor and never echoes the plaintext.
     */
    @Test
    @DisplayName("the registered bean encodes with the {bcrypt} prefix at the tuned strength")
    void registeredBeanEncodesPrefixedBcrypt() {
        contextRunner.run(context -> {
            PasswordEncoder encoder = encoderOf(context);

            String encoded = encoder.encode(SEEDED_PLAINTEXT);

            assertThat(encoded).startsWith(
                    "{bcrypt}$2a$" + PasswordEncoderFactory.BCRYPT_STRENGTH + "$");
            assertThat(encoded).doesNotContain(SEEDED_PLAINTEXT);
            assertThat(encoder.matches(SEEDED_PLAINTEXT, encoded)).isTrue();
        });
    }

    /**
     * :purpose: The registered bean verifies the BARE, unprefixed BCrypt hash the committed
     *   seed stores. This is the sign-on path's hard requirement: a delegating encoder without
     *   a match-only fallback raises {@link IllegalArgumentException} on an unprefixed stored
     *   value, so ``POST /auth/signon`` fails for every migrated user.
     */
    @Test
    @DisplayName("the registered bean verifies the committed BARE seed hash")
    void registeredBeanVerifiesBareSeedHash() {
        contextRunner.run(context -> {
            PasswordEncoder encoder = encoderOf(context);

            assertThat(encoder.matches(SEEDED_PLAINTEXT, SEEDED_BARE_BCRYPT_HASH)).isTrue();
            assertThat(encoder.matches("WRONGPWD", SEEDED_BARE_BCRYPT_HASH)).isFalse();
            // Verification is case sensitive: the decision-logged deviation from the legacy
            // upper-casing compare in COSGN00C.
            assertThat(encoder.matches("password", SEEDED_BARE_BCRYPT_HASH)).isFalse();
        });
    }

    /**
     * :purpose: Resolve the encoder bean from the assertable context.
     * :param context: the loaded application context.
     * :returns: the registered {@link PasswordEncoder}.
     */
    private static PasswordEncoder encoderOf(AssertableApplicationContext context) {
        return context.getBean(PasswordEncoder.class);
    }
}
