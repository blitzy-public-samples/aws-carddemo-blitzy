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
package com.aws.carddemo.exception;

/**
 * Thrown when a keyed lookup finds no matching record — the Java equivalent of
 * the COBOL/VSAM {@code FILE STATUS '23'} and the CICS
 * {@code RESP = DFHRESP(NOTFND)} condition.
 *
 * <p>In the legacy CardDemo programs a "record not found" outcome is signalled
 * in one of two ways, depending on the execution surface. The batch programs
 * test the two-character file status after a keyed read — for example
 * {@code IF DISCGRP-STATUS = '23'} in {@code legacy/cbl/CBACT04C.cbl}. The online
 * programs test the CICS response code — for example
 * {@code EVALUATE WS-RESP-CD WHEN DFHRESP(NOTFND)} in
 * {@code legacy/cbl/COTRN01C.cbl}, which surfaces "Transaction ID NOT found..."
 * to the screen. This single exception unifies both signals so that a not-found
 * read the COBOL code treated as a specific status/response surfaces as a
 * specific typed exception rather than a generic failure.</p>
 *
 * <p>The file status carried by this exception is fixed to
 * {@link FileStatusException#STATUS_RECORD_NOT_FOUND} ({@code "23"}): every
 * constructor forwards that constant to the superclass, so
 * {@link #getFileStatus()} always returns {@code "23"}, giving callers and
 * handlers exactly the outcome the legacy programs observed. On the online
 * surface {@code GlobalExceptionHandler} maps this exception to HTTP
 * {@code 404 Not Found}, and {@code CicsRespMapper} throws it in response to a
 * CICS {@code NOTFND} condition.</p>
 *
 * <p><strong>Do not place a card CVV or a password value in the message.</strong>
 * Messages supplied to this exception may be logged or surfaced to callers, so
 * only non-sensitive identifiers (such as an account or transaction id) may
 * appear in them.</p>
 *
 * <p>The rationale for this typed-exception design is recorded in
 * {@code docs/decision-log.md}; this class intentionally keeps only factual,
 * concise commentary.</p>
 *
 * @see FileStatusException
 */
public class RecordNotFoundException extends FileStatusException {

    /**
     * Serialization version identifier. Declared explicitly because this
     * exception is {@link java.io.Serializable} through {@link RuntimeException};
     * its presence keeps the warning-free build ({@code -Xlint:all} with
     * {@code failOnWarning}) satisfied.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Creates a new {@code RecordNotFoundException} whose file status is fixed to
     * {@link FileStatusException#STATUS_RECORD_NOT_FOUND} ({@code "23"}).
     *
     * @param message a human-readable description of the missing record; must
     *                contain only non-sensitive identifiers — never a card CVV or
     *                a password
     */
    public RecordNotFoundException(String message) {
        super(FileStatusException.STATUS_RECORD_NOT_FOUND, message);
    }

    /**
     * Creates a new {@code RecordNotFoundException} that wraps an underlying
     * cause (such as a JPA/JDBC empty-result data-access failure) without
     * discarding the root cause. The file status is fixed to
     * {@link FileStatusException#STATUS_RECORD_NOT_FOUND} ({@code "23"}).
     *
     * @param message a human-readable description of the missing record; must
     *                contain only non-sensitive identifiers — never a card CVV or
     *                a password
     * @param cause   the underlying cause; a {@code null} value is permitted and
     *                indicates that the cause is unknown
     */
    public RecordNotFoundException(String message, Throwable cause) {
        super(FileStatusException.STATUS_RECORD_NOT_FOUND, message, cause);
    }

    /**
     * Builds a {@code RecordNotFoundException} with a consistent, safe message of
     * the form {@code "<entityName> not found: <key>"}.
     *
     * <p>Callers must pass only a non-sensitive lookup key (for example an
     * account id, card number, or transaction id) — never a card CVV or a
     * password — because the resulting message may be logged or returned to a
     * caller.</p>
     *
     * @param entityName the human-readable name of the entity that was searched,
     *                   for example {@code "Account"} or {@code "Transaction"}
     * @param key        the non-sensitive lookup key that produced no match; its
     *                   {@link Object#toString()} representation is appended to
     *                   the message
     * @return a new {@code RecordNotFoundException} carrying file status
     *         {@code "23"} and the composed message
     */
    public static RecordNotFoundException of(String entityName, Object key) {
        return new RecordNotFoundException(entityName + " not found: " + key);
    }
}
