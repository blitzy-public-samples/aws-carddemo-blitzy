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
 * Thrown when an insert/write violates a unique key — the Java equivalent of the
 * COBOL/VSAM {@code FILE STATUS} '22' (duplicate key on {@code WRITE}) and of the
 * CICS {@code RESP} conditions {@code DFHRESP(DUPKEY)} and {@code DFHRESP(DUPREC)}.
 *
 * <p>In the legacy online programs those two CICS responses are handled
 * identically. For example {@code legacy/cbl/COUSR01C.cbl} stacks
 * {@code WHEN DFHRESP(DUPKEY)} and {@code WHEN DFHRESP(DUPREC)} after its
 * {@code EXEC CICS WRITE} and surfaces the single message
 * "User ID already exist...". A batch {@code WRITE} against a KSDS reports the
 * same situation as {@code FILE STATUS} '22'. All of these collapse to one
 * caller-visible outcome: the record already exists.</p>
 *
 * <p>The {@code FILE STATUS} carried by this exception is fixed to
 * {@link FileStatusException#STATUS_DUPLICATE_KEY} ("22"), so
 * {@link #getFileStatus()} always returns "22". {@code GlobalExceptionHandler}
 * maps this exception to HTTP 409 (Conflict), and {@code CicsRespMapper} raises
 * it for the {@code DUPKEY}/{@code DUPREC} responses.</p>
 *
 * <p><strong>Distinct from {@code org.springframework.dao.DuplicateKeyException}.</strong>
 * That is a separate Spring Data type; this class is the CardDemo domain
 * exception. Callers must import
 * {@code com.aws.carddemo.exception.DuplicateKeyException} explicitly so the two
 * are never confused.</p>
 *
 * <p><strong>Do not place a card CVV or a password value in the message.</strong>
 * Messages supplied to this exception may be logged or surfaced to callers, so
 * sensitive values must never appear in them.</p>
 *
 * <p>The rationale for this typed-exception design is recorded in
 * {@code docs/decision-log.md}; this class intentionally keeps only concise,
 * factual commentary.</p>
 */
public class DuplicateKeyException extends FileStatusException {

    /**
     * Serialization version identifier. Declared explicitly because the exception
     * hierarchy is {@link java.io.Serializable} (through {@link RuntimeException});
     * its presence keeps the warning-free build ({@code -Xlint:all} with
     * {@code failOnWarning}) satisfied.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Creates a new {@code DuplicateKeyException} with the given detail message.
     * The {@code FILE STATUS} is fixed to
     * {@link FileStatusException#STATUS_DUPLICATE_KEY} ("22").
     *
     * @param message a human-readable description of the duplicate-key failure;
     *                must not contain a card CVV or a password
     */
    public DuplicateKeyException(String message) {
        super(FileStatusException.STATUS_DUPLICATE_KEY, message);
    }

    /**
     * Creates a new {@code DuplicateKeyException} that wraps an underlying cause,
     * such as a JDBC/JPA unique-constraint violation, without discarding the root
     * cause. The {@code FILE STATUS} is fixed to
     * {@link FileStatusException#STATUS_DUPLICATE_KEY} ("22").
     *
     * @param message a human-readable description of the duplicate-key failure;
     *                must not contain a card CVV or a password
     * @param cause   the underlying cause; a {@code null} value is permitted and
     *                indicates that the cause is unknown
     */
    public DuplicateKeyException(String message, Throwable cause) {
        super(FileStatusException.STATUS_DUPLICATE_KEY, message, cause);
    }

    /**
     * Convenience factory that builds a {@code DuplicateKeyException} carrying a
     * standard "<em>entityName</em> already exists: <em>key</em>" message.
     *
     * <p>Only non-sensitive natural keys or identifiers (for example a user ID,
     * account ID, or card number) should be supplied as {@code key}; never pass a
     * card CVV or a password.</p>
     *
     * @param entityName the human-readable name of the entity whose unique key was
     *                   violated (for example {@code "User"} or {@code "Account"})
     * @param key        the offending key value; rendered through
     *                   {@link String#valueOf(Object)} during message assembly and
     *                   must be non-sensitive
     * @return a new {@code DuplicateKeyException} carrying {@code FILE STATUS} "22"
     */
    public static DuplicateKeyException of(String entityName, Object key) {
        return new DuplicateKeyException(entityName + " already exists: " + key);
    }
}
