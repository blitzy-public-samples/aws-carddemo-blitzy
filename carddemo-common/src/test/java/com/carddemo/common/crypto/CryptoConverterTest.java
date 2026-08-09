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
 * into any other test class in the fork. Every scenario runs in every environment:
 * no case is guarded by an assumption, and the build blanks ``CARDDEMO_PII_KEY`` in
 * each test fork so an ambient key cannot outrank the fixture below
 * (docs/decision-log.md, section 53.2).
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

    /**
     * :purpose: Put this suite's key in effect and guarantee it is the one the
     *     converter resolves.
     * :output: ``carddemo.pii.key`` holds {@link #TEST_KEY}, no process-wide key is
     *     installed, and {@link #converter} is ready.
     * :note: The installed key is cleared because :java:meth:`PiiEncryptionKey.require`
     *     prefers it over every configuration source; a Spring context booted earlier in
     *     the same fork installs one, which would otherwise decide the outcome of the
     *     cases below instead of the property they set.
     */
    @BeforeEach
    void installTestKey() {
        previousProperty = System.getProperty(CryptoConverter.KEY_PROPERTY);
        PiiEncryptionKey.clear();
        System.setProperty(CryptoConverter.KEY_PROPERTY, TEST_KEY);
        converter = new CryptoConverter();
    }

    /**
     * :purpose: Leave the fork exactly as it was found.
     * :output: the previous ``carddemo.pii.key`` value (or its absence) is restored and
     *     no process-wide key remains installed, so the next context installs its own.
     */
    @AfterEach
    void restorePreviousKey() {
        PiiEncryptionKey.clear();
        if (previousProperty == null) {
            System.clearProperty(CryptoConverter.KEY_PROPERTY);
        } else {
            System.setProperty(CryptoConverter.KEY_PROPERTY, previousProperty);
        }
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
            String token = converter.convertToDatabaseColumn(plaintext);

            assertThat(token).isNotEqualTo(plaintext);
            assertThat(converter.convertToEntityAttribute(token)).isEqualTo(plaintext);
        }

        @Test
        @DisplayName("the stored token is Base64 of at least 28 bytes (12-byte IV + 16-byte GCM tag)")
        void tokenCarriesIvAndAuthenticationTag() {
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
            String token = converter.convertToDatabaseColumn("020973888");

            System.setProperty(CryptoConverter.KEY_PROPERTY, OTHER_KEY);
            assertThatThrownBy(() -> new CryptoConverter().convertToEntityAttribute(token))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Unable to decrypt sensitive attribute from persistence");
        }

        @Test
        @DisplayName("a tampered token of token length raises IllegalStateException")
        void tamperedTokenIsNotTolerated() {
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
            System.clearProperty(CryptoConverter.KEY_PROPERTY);

            assertThatThrownBy(() -> new CryptoConverter().convertToDatabaseColumn("020973888"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(CryptoConverter.KEY_ENV);
        }

        @Test
        @DisplayName("a key of the wrong length is rejected rather than silently padded")
        void shortKeyIsRejected() {
            System.setProperty(CryptoConverter.KEY_PROPERTY,
                    Base64.getEncoder().encodeToString(new byte[16]));

            assertThatThrownBy(() -> new CryptoConverter().convertToDatabaseColumn("747"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must decode to 32 bytes");
        }
    }
    /**
     * :purpose: Pin the exact asymmetry that made the seeded-PII sweep report a falsehood. The
     *   sweep asked "did ``convertToEntityAttribute`` throw?" and treated silence as proof of
     *   ciphertext -- but that converter deliberately PASSES LEGACY PLAINTEXT THROUGH rather than
     *   failing on it, so it never threw, every value looked already-encrypted, and the migration
     *   rewrote nothing while recording SUCCESS and logging ``rewritten=0`` over a database full of
     *   plaintext. ``isProtected`` is the predicate that actually answers the question, and these
     *   assertions hold both halves of that distinction in place so the detector cannot regress to
     *   the try-decrypt form.
     */
    @Nested
    @DisplayName("protection detection for the seeded-PII sweep")
    class ProtectionDetection {

        /**
         * :purpose: Legacy plaintext must be reported as NOT protected, which is the whole basis of
         *   the sweep deciding to rewrite a row.
         */
        @Test
        @DisplayName("legacy plaintext is not protected, though decryption tolerates it")
        void legacyPlaintextIsNotProtected() {
            String plaintext = "020973888";

            // The trap: this does NOT throw, and returns the value unchanged.
            assertThat(converter.convertToEntityAttribute(plaintext)).isEqualTo(plaintext);
            // The correct predicate still says the value needs encrypting.
            assertThat(converter.isProtected(plaintext)).isFalse();
        }

        /**
         * :purpose: A value carrying the envelope must be reported as protected, so the sweep skips
         *   it and stays idempotent across restarts.
         */
        @Test
        @DisplayName("an enveloped token is protected")
        void envelopedTokenIsProtected() {
            String token = converter.convertToDatabaseColumn("020973888");

            assertThat(token).startsWith(CryptoConverter.ENVELOPE_PREFIX);
            assertThat(converter.isProtected(token)).isTrue();
        }

        /**
         * :purpose: Running the sweep's decision twice must reach the same answer, which is what
         *   makes a second startup rewrite nothing.
         */
        @Test
        @DisplayName("encrypting once then re-testing reports protected (idempotent)")
        void encryptingOnceThenReTestingReportsProtected() {
            String plaintext = "020973888";
            assertThat(converter.isProtected(plaintext)).isFalse();

            String encrypted = converter.convertToDatabaseColumn(plaintext);
            assertThat(converter.isProtected(encrypted)).isTrue();

            // And the value still round-trips, so the sweep did not damage it.
            assertThat(converter.convertToEntityAttribute(encrypted)).isEqualTo(plaintext);
        }

        /**
         * :purpose: An absent column needs no protection and must not be counted as a rewrite.
         */
        @Test
        @DisplayName("null and empty columns are treated as protected")
        void nullAndEmptyAreTreatedAsProtected() {
            assertThat(converter.isProtected(null)).isTrue();
            assertThat(converter.isProtected("")).isTrue();
        }
    }
}
