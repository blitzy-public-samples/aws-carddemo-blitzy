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
package com.carddemo.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;


import java.util.Base64;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for :class:`CryptoConverter`.
 *
 * :purpose: Verify the three distinct behaviours of the PII column converter -
 *     encrypt-then-decrypt round trip for values this application writes,
 *     verbatim pass-through of a stored value that cannot structurally be a
 *     ciphertext token (a pre-encryption value loaded by the seed migrations
 *     derived from ``app/data/ASCII``), and a loud failure when a well-formed
 *     token cannot be decrypted or no key is configured.
 * :output: JUnit 5 / AssertJ assertions only; no database, Spring context or
 *     other external resource is touched (pure JCE logic).
 *
 * The key is installed into the ``carddemo.pii.key`` system property per test and
 * the previous value is restored afterwards, so this class leaks no configuration
 * into any other test class in the fork.
 */
final class CryptoConverterTest {

    /**
     * :purpose: Deterministic, obviously-fake 256-bit key used only by this suite.
     */
    private static final String TEST_KEY =
            Base64.getEncoder().encodeToString("carddemo-unit-test-key-32bytes!!".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    /**
     * :purpose: A different 256-bit key, used to prove a token encrypted under one
     *     key is not silently accepted under another.
     */
    private static final String OTHER_KEY =
            Base64.getEncoder().encodeToString("carddemo-other-unit-key-32bytes!".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    private String previousProperty;

    private CryptoConverter converter;

    @BeforeEach
    void installTestKey() {
        previousProperty = System.getProperty(CryptoConverter.KEY_PROPERTY);
        System.setProperty(CryptoConverter.KEY_PROPERTY, TEST_KEY);
        converter = new CryptoConverter();
    }

    @AfterEach
    void restorePreviousKey() {
        if (previousProperty == null) {
            System.clearProperty(CryptoConverter.KEY_PROPERTY);
        } else {
            System.setProperty(CryptoConverter.KEY_PROPERTY, previousProperty);
        }
    }

    /**
     * :purpose: Whether the ambient environment supplies ``CARDDEMO_PII_KEY``, which
     *     :java:meth:`CryptoConverter.resolveKey` prefers over the system property.
     *     Scenarios that need a specific key in effect are skipped when it does, so
     *     the suite behaves identically inside and outside a configured container.
     */
    private static boolean environmentKeyPresent() {
        String fromEnvironment = System.getenv(CryptoConverter.KEY_ENV);
        return fromEnvironment != null && !fromEnvironment.isBlank();
    }

    /**
     * Values this application writes must survive the column round trip exactly.
     */
    @Nested
    @DisplayName("Encrypt / decrypt round trip")
    class RoundTrip {

        @ParameterizedTest
        @DisplayName("every encrypted field width round-trips byte-for-byte")
        @ValueSource(strings = {
                "747",                  // CARD-CVV-CD          PIC X(3)
                "020973888",            // CUST-SSN             PIC X(9)
                "0053581756",           // CUST-EFT-ACCOUNT-ID  leading zeros preserved
                "GOVT-ID-01234567890",  // CUST-GOVT-ISSUED-ID  PIC X(20)
                "   ",                  // blank fixed-width field
                "Ünïcödé-ßß"            // non-ASCII payload
        })
        void plaintextSurvivesTheRoundTrip(String plaintext) {
            assumeTrue(!environmentKeyPresent(), "ambient CARDDEMO_PII_KEY overrides the test key");

            String token = converter.convertToDatabaseColumn(plaintext);

            assertThat(token).isNotEqualTo(plaintext);
            assertThat(converter.convertToEntityAttribute(token)).isEqualTo(plaintext);
        }

        @Test
        @DisplayName("the stored token is Base64 of at least 28 bytes (12-byte IV + 16-byte GCM tag)")
        void tokenCarriesIvAndAuthenticationTag() {
            assumeTrue(!environmentKeyPresent(), "ambient CARDDEMO_PII_KEY overrides the test key");

            String token = converter.convertToDatabaseColumn("747");

            assertThat(token).startsWith(CryptoConverter.ENVELOPE_PREFIX);
            byte[] decoded = Base64.getDecoder().decode(payloadOf(token));
            assertThat(decoded).hasSizeGreaterThanOrEqualTo(28);
            // 12-byte IV + 3-byte ciphertext + 16-byte tag for a PIC X(3) value.
            assertThat(decoded).hasSize(31);
        }

        @Test
        @DisplayName("a random IV makes two encryptions of one value differ yet both decrypt")
        void encryptionIsNonDeterministic() {
            assumeTrue(!environmentKeyPresent(), "ambient CARDDEMO_PII_KEY overrides the test key");

            String first = converter.convertToDatabaseColumn("020973888");
            String second = converter.convertToDatabaseColumn("020973888");

            assertThat(first).isNotEqualTo(second);
            assertThat(converter.convertToEntityAttribute(first)).isEqualTo("020973888");
            assertThat(converter.convertToEntityAttribute(second)).isEqualTo("020973888");
        }

        @Test
        @DisplayName("null and empty values pass through both directions unchanged")
        void nullAndEmptyPassThrough() {
            assertThat(converter.convertToDatabaseColumn(null)).isNull();
            assertThat(converter.convertToDatabaseColumn("")).isEmpty();
            assertThat(converter.convertToEntityAttribute(null)).isNull();
            assertThat(converter.convertToEntityAttribute("")).isEmpty();
            assertThat(CryptoConverter.isEncryptedToken(null)).isFalse();
            assertThat(CryptoConverter.isEncryptedToken("")).isFalse();
        }
    }

    /**
     * A stored value that cannot structurally be a token is a pre-encryption value.
     *
     * These are the values the seed migrations derived from the legacy fixed-width
     * sequential data sets write; the read path must surface them as data instead of
     * failing the request. See ``docs/decision-log.md``.
     */
    @Nested
    @DisplayName("Pre-encryption (legacy) column values")
    class PreEncryptionValues {

        @ParameterizedTest
        @DisplayName("a seeded plaintext identifier is returned verbatim, not rejected")
        @ValueSource(strings = {
                "747",                  // valid Base64 -> 2 bytes, far below the 28-byte floor
                "0053581756",           // valid Base64 -> 7 bytes
                "020973888",            // not valid Base64 at all (length 9)
                "Etienne Bernard",      // arbitrary non-Base64 text
                "1234567890123456"      // valid Base64 -> 12 bytes
        })
        void legacyValueIsReturnedAsRead(String storedValue) {
            assertThat(converter.convertToEntityAttribute(storedValue)).isEqualTo(storedValue);
        }

        @Test
        @DisplayName("a legacy value is re-written encrypted the next time the entity is persisted")
        void legacyValueIsUpgradedOnNextWrite() {
            assumeTrue(!environmentKeyPresent(), "ambient CARDDEMO_PII_KEY overrides the test key");

            String asRead = converter.convertToEntityAttribute("020973888");
            String rewritten = converter.convertToDatabaseColumn(asRead);

            assertThat(rewritten).isNotEqualTo("020973888");
            assertThat(Base64.getDecoder().decode(payloadOf(rewritten))).hasSizeGreaterThanOrEqualTo(28);
            assertThat(converter.convertToEntityAttribute(rewritten)).isEqualTo("020973888");
        }
    }

    /**
     * :purpose: Strip the versioned envelope marker so the Base64 payload can be decoded.
     * :param token: a column value produced by the converter.
     * :returns: the Base64 payload that follows the marker.
     */
    private static String payloadOf(String token) {
        return token.startsWith(CryptoConverter.ENVELOPE_PREFIX)
                ? token.substring(CryptoConverter.ENVELOPE_PREFIX.length())
                : token;
    }

    /**
     * A well-formed token that cannot be decrypted must fail loudly.
     *
     * Tolerating it would surface ciphertext as data and hide a key
     * misconfiguration, so this boundary is asserted explicitly.
     */
    @Nested
    @DisplayName("Undecryptable tokens and key misconfiguration")
    class FailsLoudly {

        @Test
        @DisplayName("a token encrypted under a different key raises IllegalStateException")
        void wrongKeyIsNotTolerated() {
            assumeTrue(!environmentKeyPresent(), "ambient CARDDEMO_PII_KEY overrides the test key");

            String token = converter.convertToDatabaseColumn("020973888");

            System.setProperty(CryptoConverter.KEY_PROPERTY, OTHER_KEY);
            assertThatThrownBy(() -> new CryptoConverter().convertToEntityAttribute(token))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Unable to decrypt sensitive attribute from persistence");
        }

        @Test
        @DisplayName("a tampered token of token length raises IllegalStateException")
        void tamperedTokenIsNotTolerated() {
            assumeTrue(!environmentKeyPresent(), "ambient CARDDEMO_PII_KEY overrides the test key");

            byte[] decoded = Base64.getDecoder().decode(
                    payloadOf(converter.convertToDatabaseColumn("747")));
            decoded[decoded.length - 1] ^= 0x5A;
            String tampered = CryptoConverter.ENVELOPE_PREFIX
                    + Base64.getEncoder().encodeToString(decoded);

            assertThatThrownBy(() -> converter.convertToEntityAttribute(tampered))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Unable to decrypt sensitive attribute from persistence");
        }

        @Test
        @DisplayName("encrypting without a configured key fails fast instead of storing plaintext")
        void missingKeyFailsFastOnWrite() {
            assumeTrue(!environmentKeyPresent(), "ambient CARDDEMO_PII_KEY overrides the test key");

            System.clearProperty(CryptoConverter.KEY_PROPERTY);

            assertThatThrownBy(() -> new CryptoConverter().convertToDatabaseColumn("020973888"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(CryptoConverter.KEY_ENV);
        }

        @Test
        @DisplayName("a key of the wrong length is rejected rather than silently padded")
        void shortKeyIsRejected() {
            assumeTrue(!environmentKeyPresent(), "ambient CARDDEMO_PII_KEY overrides the test key");

            System.setProperty(CryptoConverter.KEY_PROPERTY,
                    Base64.getEncoder().encodeToString(new byte[16]));

            assertThatThrownBy(() -> new CryptoConverter().convertToDatabaseColumn("747"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must decode to 32 bytes");
        }
    }
}
