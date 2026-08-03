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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * :purpose: Unit tests for the eager PII-key validation that a service performs at startup. Each case
 *     asserts one of the misconfigurations QA reported as silently accepted: a missing key, a blank
 *     key, key material that is not Base64, and a key of the wrong length.
 * :note: Every assertion also checks that the failure message names the configuration keys to set and
 *     never echoes the offending value, so key material cannot reach a log.
 */
@DisplayName("PiiEncryptionKey")
class PiiEncryptionKeyTest {

    /** Base64 of the 32-byte non-production fixture "carddemo-test-pii-key-32bytes!!!". */
    private static final String VALID_KEY = "Y2FyZGRlbW8tdGVzdC1waWkta2V5LTMyYnl0ZXMhISE=";

    /**
     * :purpose: Leave no installed key behind for other tests.
     */
    @AfterEach
    void tearDown() {
        PiiEncryptionKey.clear();
    }

    /**
     * :purpose: A well-formed Base64 256-bit key is accepted and installed.
     */
    @Test
    @DisplayName("a Base64 256-bit key is accepted")
    void validKeyAccepted() {
        PiiEncryptionKey.configure(VALID_KEY);

        assertNotNull(PiiEncryptionKey.require(), "the installed key must be returned");
        assertEquals("AES", PiiEncryptionKey.require().getAlgorithm());
        assertTrue(PiiEncryptionKey.isAvailable());
    }

    /**
     * :purpose: A missing key is rejected with a message naming the environment variable and the
     *     property, and no value.
     */
    @Test
    @DisplayName("a missing key is rejected")
    void missingKeyRejected() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> PiiEncryptionKey.parse(null));

        assertTrue(ex.getMessage().contains(PiiEncryptionKey.KEY_ENV));
        assertTrue(ex.getMessage().contains(PiiEncryptionKey.KEY_PROPERTY));
    }

    /**
     * :purpose: A blank key is rejected exactly like a missing one.
     */
    @Test
    @DisplayName("a blank key is rejected")
    void blankKeyRejected() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> PiiEncryptionKey.parse("   "));

        assertTrue(ex.getMessage().contains("not configured"));
    }

    /**
     * :purpose: Key material that is not valid Base64 is rejected, and the offending value is not
     *     echoed in the message.
     */
    @Test
    @DisplayName("non-Base64 key material is rejected without echoing it")
    void nonBase64KeyRejected() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> PiiEncryptionKey.parse("not-base64!!"));

        assertTrue(ex.getMessage().contains("Base64"));
        assertFalse(ex.getMessage().contains("not-base64!!"),
                "key material must never appear in the message");
    }

    /**
     * :purpose: A structurally valid but wrong-length key is rejected with the expected length.
     */
    @Test
    @DisplayName("a key of the wrong length is rejected")
    void wrongLengthKeyRejected() {
        String sixteenBytes = Base64.getEncoder()
                .encodeToString("0123456789abcdef".getBytes(StandardCharsets.UTF_8));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> PiiEncryptionKey.parse(sixteenBytes));

        assertTrue(ex.getMessage().contains(String.valueOf(PiiEncryptionKey.AES_KEY_BYTES)));
        assertTrue(ex.getMessage().contains("16 bytes"), "the actual length helps the operator");
    }
}
