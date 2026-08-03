/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.common.crypto;

import java.util.Base64;
import javax.crypto.spec.SecretKeySpec;

/**
 * :purpose: Single resolution and validation point for the CardDemo PII encryption key, shared by
 *           the JPA {@link CryptoConverter} (which Hibernate instantiates outside the Spring
 *           container) and by the startup validation that fails a service fast when the key is
 *           missing or unusable.
 * :output: A validated AES-256 {@link SecretKeySpec}, or an {@link IllegalStateException} whose
 *          message names the environment variable and property to set and NEVER echoes key
 *          material.
 * :note: Resolution order is: the value installed by {@link #configure(String)} (the Spring
 *        Environment value, applied at startup, which already covers the environment variable, the
 *        system property and any external configuration source); then the ``CARDDEMO_PII_KEY``
 *        environment variable; then the ``carddemo.pii.key`` system property. The last two keep the
 *        converter usable in plain unit tests that run without a Spring context.
 * :note: Extracting this out of the converter is what makes eager validation possible. Previously
 *        the key was resolved lazily inside each conversion, so a service with a missing, blank or
 *        structurally invalid key started, reported UP and then failed EVERY request that touched an
 *        encrypted column.
 */
public final class PiiEncryptionKey {

    /**
     * :purpose: Environment variable carrying the Base64-encoded 256-bit PII key.
     */
    public static final String KEY_ENV = "CARDDEMO_PII_KEY";

    /**
     * :purpose: Property (and system-property) name carrying the Base64-encoded 256-bit PII key.
     */
    public static final String KEY_PROPERTY = "carddemo.pii.key";

    /**
     * :purpose: Required decoded key length: AES-256 uses a 32-byte key.
     */
    public static final int AES_KEY_BYTES = 32;

    /**
     * :purpose: JCE algorithm name of the derived key.
     */
    private static final String ALGORITHM = "AES";

    /**
     * :purpose: Key installed at startup from the Spring Environment; ``null`` until validated.
     * :note: ``volatile`` because Hibernate may invoke the converter from any thread.
     */
    private static volatile SecretKeySpec configuredKey;

    /**
     * :purpose: Prevent instantiation of this stateless holder.
     */
    private PiiEncryptionKey() {
    }

    /**
     * :purpose: Validate an encoded key and install it as the process-wide PII key.
     * :param encoded: the Base64-encoded 256-bit key, as resolved from configuration.
     * :raises IllegalStateException: when the value is absent, blank, not valid Base64, or does not
     *     decode to exactly {@link #AES_KEY_BYTES} bytes.
     */
    public static void configure(String encoded) {
        configuredKey = parse(encoded);
    }

    /**
     * :purpose: Forget any installed key so a subsequent resolution falls back to the environment.
     * :note: Used by tests that assert the unconfigured behaviour; production code never calls it.
     */
    public static void clear() {
        configuredKey = null;
    }

    /**
     * :purpose: Return the key to use for the current conversion.
     * :returns: the validated AES-256 key.
     * :raises IllegalStateException: when no key is configured anywhere, or the configured value is
     *     unusable.
     */
    public static SecretKeySpec require() {
        SecretKeySpec installed = configuredKey;
        if (installed != null) {
            return installed;
        }
        String encoded = System.getenv(KEY_ENV);
        if (encoded == null || encoded.isBlank()) {
            encoded = System.getProperty(KEY_PROPERTY);
        }
        return parse(encoded);
    }

    /**
     * :purpose: Report whether a usable key can be resolved right now, without throwing.
     * :returns: ``true`` when {@link #require()} would succeed.
     */
    public static boolean isAvailable() {
        try {
            require();
            return true;
        } catch (IllegalStateException ex) {
            return false;
        }
    }

    /**
     * :purpose: Validate an encoded key and turn it into an AES key specification.
     * :param encoded: the Base64-encoded 256-bit key.
     * :returns: the validated key specification.
     * :raises IllegalStateException: when the value is absent, blank, not valid Base64, or does not
     *     decode to exactly {@link #AES_KEY_BYTES} bytes. The message names the configuration keys
     *     to set and deliberately excludes the offending value so key material never reaches a log.
     */
    public static SecretKeySpec parse(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalStateException(
                    "PII encryption key is not configured; set the " + KEY_ENV
                            + " environment variable or the " + KEY_PROPERTY
                            + " property to a Base64-encoded 256-bit key");
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(encoded.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "PII encryption key (" + KEY_ENV + " / " + KEY_PROPERTY
                            + ") is not valid Base64; supply a Base64-encoded 256-bit key");
        }
        if (keyBytes.length != AES_KEY_BYTES) {
            throw new IllegalStateException(
                    "PII encryption key (" + KEY_ENV + " / " + KEY_PROPERTY + ") must decode to "
                            + AES_KEY_BYTES + " bytes (256 bits) but decoded to " + keyBytes.length
                            + " bytes");
        }
        return new SecretKeySpec(keyBytes, ALGORITHM);
    }
}
