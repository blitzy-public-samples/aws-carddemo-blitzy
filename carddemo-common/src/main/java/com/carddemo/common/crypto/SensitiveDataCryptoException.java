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

/**
 * :purpose: Signals that a regulated attribute could not be protected on write or
 *     recovered on read - a misconfigured encryption key, a value that is not an
 *     AES-GCM ciphertext token (for example a column still holding unencrypted
 *     data), or a token that fails integrity verification. It exists so the
 *     failure is reported as an explicit, self-describing condition instead of a
 *     low-level ``ArrayIndexOutOfBoundsException`` or ``BadPaddingException``
 *     escaping the persistence layer.
 * :output: An unchecked exception whose message names the fault category and, at
 *     most, the length of the offending token. The protected value, the key, and
 *     any derived material are NEVER included, so neither logs nor an error
 *     response can disclose them.
 * :note: This is deliberately NOT a {@code CardDemoException}: it is a
 *     server-side data-at-rest or configuration fault rather than a legacy RESP
 *     or reject condition, and it is mapped to ``500`` (never ``400``) so a caller
 *     is never told that a request was malformed when the stored data was.
 */
public class SensitiveDataCryptoException extends RuntimeException {

    /** :purpose: Serialization version identifier for this exception type. */
    private static final long serialVersionUID = 1L;

    /**
     * :purpose: Construct the exception with a self-describing, value-free message.
     * :param message: the fault description; must not embed protected data.
     */
    public SensitiveDataCryptoException(String message) {
        super(message);
    }

    /**
     * :purpose: Construct the exception with a value-free message and the underlying
     *     cryptographic or decoding cause.
     * :param message: the fault description; must not embed protected data.
     * :param cause: the underlying throwable, retained for operator diagnostics.
     */
    public SensitiveDataCryptoException(String message, Throwable cause) {
        super(message, cause);
    }
}
