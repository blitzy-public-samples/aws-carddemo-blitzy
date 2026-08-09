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
package com.carddemo.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * :purpose: Unit test for {@link PasswordEncoderFactory}, the single password-encoding
 *   policy that replaces the legacy RACF/``USRSEC`` plaintext comparison
 *   (``COSGN00C`` L223, AAP 0.6.7). It pins the encoding contract (``{bcrypt}``
 *   prefix at the tuned strength), the verification contract for every stored form the
 *   migrated system actually holds — including the BARE, unprefixed BCrypt hash that
 *   the committed ``security_users`` seed carries — and the rehash-on-next-login
 *   signal.
 * :output: JUnit 5 assertions executed under Surefire against the real encoder; no
 *   Spring context, no database. The bare-hash scenarios are the regression guard for
 *   the delegating encoder rejecting an unprefixed stored value, which made every
 *   migrated user unable to sign on.
 */
class PasswordEncoderFactoryTest {

    /**
     * :purpose: The exact bare BCrypt hash the committed ``V3__seed_test_data.sql``
     *   stores for all ten migrated users. It has no ``{id}`` prefix and was produced at
     *   BCrypt cost 10, matching {@link PasswordEncoderFactory#BCRYPT_STRENGTH} so a verified
     *   credential is never rehashed on sign-on.
     */
    private static final String SEEDED_BARE_BCRYPT_HASH =
            "$2a$10$ucIRth.iIafhA4MgE1RXZ.0whYamgfRIpJebWmPswpnxmLKA/peYm";

    /** :purpose: Plaintext the seeded hash was produced from (``BCrypt('PASSWORD')``). */
    private static final String SEEDED_PLAINTEXT = "PASSWORD";

    /**
     * :purpose: The factory returns a {@link DelegatingPasswordEncoder} so the stored
     *   algorithm can be rotated without invalidating existing credentials.
     */
    @Test
    @DisplayName("factory returns a DelegatingPasswordEncoder")
    void factoryReturnsDelegatingEncoder() {
        assertThat(PasswordEncoderFactory.createDelegatingPasswordEncoder())
                .isInstanceOf(DelegatingPasswordEncoder.class);
    }

    /**
     * :purpose: New credentials are written with the ``{bcrypt}`` identifier prefix at
     *   the tuned cost factor, so the stored form always names its own algorithm.
     */
    @Test
    @DisplayName("encode writes the {bcrypt} prefix at the tuned strength")
    void encodeWritesPrefixedBcryptAtTunedStrength() {
        PasswordEncoder encoder = PasswordEncoderFactory.createDelegatingPasswordEncoder();

        String encoded = encoder.encode(SEEDED_PLAINTEXT);

        assertThat(encoded).startsWith("{" + PasswordEncoderFactory.DEFAULT_ENCODER_ID + "}");
        assertThat(encoded).contains("$2a$" + PasswordEncoderFactory.BCRYPT_STRENGTH + "$");
        assertThat(encoded).doesNotContain(SEEDED_PLAINTEXT);
    }

    /**
     * :purpose: BCrypt embeds a per-hash salt, so encoding the same plaintext twice must
     *   never produce the same stored value while both still verify.
     */
    @Test
    @DisplayName("encode is salted: two encodings differ yet both verify")
    void encodeIsSalted() {
        PasswordEncoder encoder = PasswordEncoderFactory.createDelegatingPasswordEncoder();

        String first = encoder.encode(SEEDED_PLAINTEXT);
        String second = encoder.encode(SEEDED_PLAINTEXT);

        assertThat(first).isNotEqualTo(second);
        assertThat(encoder.matches(SEEDED_PLAINTEXT, first)).isTrue();
        assertThat(encoder.matches(SEEDED_PLAINTEXT, second)).isTrue();
    }

    /**
     * :purpose: A freshly encoded credential verifies against its own plaintext and
     *   rejects any other, including a case variant — the decision-logged deviation from
     *   the legacy upper-casing compare is that verification is case sensitive.
     */
    @Test
    @DisplayName("matches accepts the correct plaintext and rejects a case variant")
    void matchesRoundTripIsCaseSensitive() {
        PasswordEncoder encoder = PasswordEncoderFactory.createDelegatingPasswordEncoder();
        String encoded = encoder.encode(SEEDED_PLAINTEXT);

        assertThat(encoder.matches(SEEDED_PLAINTEXT, encoded)).isTrue();
        assertThat(encoder.matches("password", encoded)).isFalse();
        assertThat(encoder.matches("WRONGPWD", encoded)).isFalse();
    }

    /**
     * :purpose: The credential form the migrated system actually stores — a BARE BCrypt
     *   hash with no ``{id}`` prefix, produced at a cost factor different from the tuned
     *   one — must verify. Without the match-only fallback encoder the delegating encoder
     *   raises {@link IllegalArgumentException} here and no migrated user can sign on.
     */
    @Test
    @DisplayName("matches verifies the committed BARE (unprefixed) BCrypt seed hash")
    void matchesVerifiesBareSeedHash() {
        PasswordEncoder encoder = PasswordEncoderFactory.createDelegatingPasswordEncoder();

        assertThat(encoder.matches(SEEDED_PLAINTEXT, SEEDED_BARE_BCRYPT_HASH)).isTrue();
    }

    /**
     * :purpose: The bare-hash fallback must not weaken verification: a wrong plaintext and
     *   a case variant are still rejected against the unprefixed stored value.
     */
    @Test
    @DisplayName("bare-hash verification still rejects a wrong or case-variant plaintext")
    void bareSeedHashRejectsWrongPlaintext() {
        PasswordEncoder encoder = PasswordEncoderFactory.createDelegatingPasswordEncoder();

        assertThat(encoder.matches("password", SEEDED_BARE_BCRYPT_HASH)).isFalse();
        assertThat(encoder.matches("WRONGPWD", SEEDED_BARE_BCRYPT_HASH)).isFalse();
        assertThat(encoder.matches("", SEEDED_BARE_BCRYPT_HASH)).isFalse();
    }

    /**
     * :purpose: A credential persisted under the registered ``{pbkdf2}`` identifier is
     *   still verifiable, so an algorithm rotation never locks anyone out.
     */
    @Test
    @DisplayName("matches verifies a {pbkdf2}-prefixed stored value")
    void matchesVerifiesPbkdf2PrefixedValue() {
        PasswordEncoder encoder = PasswordEncoderFactory.createDelegatingPasswordEncoder();
        Pbkdf2PasswordEncoder pbkdf2 = Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();
        String stored = "{" + PasswordEncoderFactory.PBKDF2_ENCODER_ID + "}"
                + pbkdf2.encode(SEEDED_PLAINTEXT);

        assertThat(encoder.matches(SEEDED_PLAINTEXT, stored)).isTrue();
        assertThat(encoder.matches("WRONGPWD", stored)).isFalse();
    }

    /**
     * :purpose: A credential already stored under the default algorithm at the tuned cost
     *   needs no rehash, while the bare seed hash and a ``{pbkdf2}`` value are reported as
     *   upgradeable so they are rewritten on the next successful sign-on.
     */
    @Test
    @DisplayName("upgradeEncoding flags the legacy forms and clears the current one")
    void upgradeEncodingFlagsLegacyForms() {
        PasswordEncoder encoder = PasswordEncoderFactory.createDelegatingPasswordEncoder();
        Pbkdf2PasswordEncoder pbkdf2 = Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();

        assertThat(encoder.upgradeEncoding(encoder.encode(SEEDED_PLAINTEXT))).isFalse();
        assertThat(encoder.upgradeEncoding(SEEDED_BARE_BCRYPT_HASH)).isTrue();
        assertThat(encoder.upgradeEncoding("{" + PasswordEncoderFactory.PBKDF2_ENCODER_ID + "}"
                + pbkdf2.encode(SEEDED_PLAINTEXT))).isTrue();
    }

    /**
     * :purpose: The tuned policy constants are frozen: BCrypt is the encoding algorithm at
     *   cost 10 - the cost the committed seed hash carries, so a successful sign-on never
     *   silently rehashes and the wrong-password path stays within the response-time budget
     *   without exposing a user-enumeration timing oracle - and PBKDF2 is the registered
     *   legacy identifier.
     */
    @Test
    @DisplayName("policy constants are frozen: bcrypt / 10 / pbkdf2")
    void policyConstantsAreFrozen() {
        assertThat(PasswordEncoderFactory.DEFAULT_ENCODER_ID).isEqualTo("bcrypt");
        assertThat(PasswordEncoderFactory.BCRYPT_STRENGTH).isEqualTo(10);
        assertThat(PasswordEncoderFactory.PBKDF2_ENCODER_ID).isEqualTo("pbkdf2");
    }
}
