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
package com.carddemo.common.exception;

/**
 * :purpose: Signal that a sensitive customer or card attribute could not be encrypted for
 *     persistence or decrypted after being read — for example because the stored value is not
 *     the ``Base64(IV || AES-GCM ciphertext)`` the ``CryptoConverter`` writes, or because no
 *     encryption key is configured. Raising a dedicated domain type lets the shared exception
 *     handler answer with the standard error envelope instead of letting an ORM-wrapped
 *     ``IllegalStateException`` escape as an opaque framework error page.
 * :note: This type has no legacy analogue: at-rest field encryption is a target-only
 *     control introduced for AAP 0.6.7, so it is recorded as rule-mandated infrastructure in
 *     ``docs/traceability-matrix.md``. The message deliberately discloses no column name, key
 *     material or cryptographic detail.
 * :note: It extends {@link IllegalStateException} because an unreadable protected column
 *     IS an illegal state, and because the converter's own legacy-plaintext fallthrough — and
 *     the seeded-PII migration's ciphertext probe — decide by catching that type. Narrowing
 *     the thrown type without widening those catches would turn a token-shaped foreign value
 *     into a hard failure instead of the legacy value it is.
 */
public class PiiEncryptionException extends IllegalStateException {

    /** :purpose: Serialization identity for this exception type. */
    private static final long serialVersionUID = 1L;

    /**
     * :purpose: Message rendered to the caller. It names no column, key or algorithm,
     *     so a failure cannot be used to probe the encryption configuration.
     */
    public static final String MESSAGE = "Unable to process protected customer data";

    /**
     * :purpose: Build the exception with the frozen non-disclosing message.
     * :param cause: the underlying cryptographic or encoding failure, retained for
     *     server-side diagnosis only.
     */
    public PiiEncryptionException(Throwable cause) {
        super(MESSAGE, cause);
    }

    /**
     * :purpose: Build the exception with the frozen non-disclosing message and a
     *     server-side detail that is logged but never returned to the caller.
     * :param detail: operator-facing detail describing the misconfiguration.
     * :param cause: the underlying failure, or ``null``.
     */
    public PiiEncryptionException(String detail, Throwable cause) {
        super(detail, cause);
    }
}
