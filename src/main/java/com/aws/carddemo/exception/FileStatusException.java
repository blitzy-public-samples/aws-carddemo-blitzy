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
 * Base unchecked exception representing a COBOL/VSAM {@code FILE STATUS} (also
 * referred to in the legacy programs as {@code IO-STATUS}) outcome.
 *
 * <p>The legacy CardDemo batch programs declare {@code FILE STATUS IS
 * &lt;name&gt;-STATUS} on every {@code SELECT} and, after each I/O verb, inspect
 * the resulting two-character status code (for example in
 * {@code legacy/cbl/CBTRN02C.cbl} and {@code legacy/cbl/CBACT04C.cbl}). This
 * exception re-expresses that model in Java: it carries the original
 * two-character status verbatim so that callers and handlers observe exactly the
 * same outcome the COBOL code observed when it examined {@code FILE STATUS}. The
 * raw code is retrievable through {@link #getFileStatus()}.</p>
 *
 * <p>The exception is <em>unchecked</em> (it extends {@link RuntimeException})
 * for two reasons: the COBOL abend path ({@code 9999-ABEND-PROGRAM}) is a
 * non-recoverable control-flow escape, and Spring's {@code @Transactional}
 * rollback semantics key off unchecked exceptions.</p>
 *
 * <p>Specific statuses are modelled by subclasses:</p>
 * <ul>
 *   <li>{@code RecordNotFoundException} for status {@value #STATUS_RECORD_NOT_FOUND}
 *       (record not found; e.g. COBOL {@code DISCGRP-STATUS = '23'}).</li>
 *   <li>{@code DuplicateKeyException} for status {@value #STATUS_DUPLICATE_KEY}
 *       (an attempt to write a record whose key already exists).</li>
 * </ul>
 *
 * <p><strong>End-of-file is not an error.</strong> Status
 * {@value #STATUS_EOF} ('10') is a normal terminal condition in the COBOL read
 * loops and must <em>not</em> be thrown as a {@code FileStatusException}. Readers
 * and repositories signal end-of-file by returning {@code null} or an empty
 * {@link java.util.Optional} instead.</p>
 *
 * <p><strong>Do not place a card CVV or a password value in the message.</strong>
 * Messages supplied to this exception may be logged or surfaced to callers, so
 * sensitive values must never appear in them.</p>
 *
 * <p>The rationale for this typed-exception design is recorded in
 * {@code docs/decision-log.md} (decision D15); this class intentionally keeps
 * only factual, concise commentary.</p>
 */
public class FileStatusException extends RuntimeException {

    /**
     * Serialization version identifier. Declared explicitly because
     * {@link RuntimeException} (through {@link Throwable}) is
     * {@link java.io.Serializable}; its presence keeps the warning-free build
     * ({@code -Xlint:all} with {@code failOnWarning}) satisfied.
     */
    private static final long serialVersionUID = 1L;

    /** COBOL {@code FILE STATUS} '00' — the I/O operation completed successfully. */
    public static final String STATUS_OK = "00";

    /**
     * COBOL {@code FILE STATUS} '10' — end-of-file. This is a normal terminal
     * condition, never an error, and must not be thrown as a
     * {@code FileStatusException}.
     */
    public static final String STATUS_EOF = "10";

    /**
     * COBOL {@code FILE STATUS} '22' — an attempt to write a record whose key
     * already exists (duplicate key).
     */
    public static final String STATUS_DUPLICATE_KEY = "22";

    /** COBOL {@code FILE STATUS} '23' — the requested record was not found. */
    public static final String STATUS_RECORD_NOT_FOUND = "23";

    /**
     * The original two-character COBOL {@code FILE STATUS} code (for example
     * {@code "23"}, {@code "22"}, {@code "10"}, or a {@code "9x"} implementor
     * code). Preserved verbatim for caller-visible parity with the legacy
     * programs.
     */
    private final String fileStatus;

    /**
     * Creates a new {@code FileStatusException}.
     *
     * @param fileStatus the original two-character COBOL {@code FILE STATUS}
     *                    code; preserved verbatim and retrievable through
     *                    {@link #getFileStatus()}
     * @param message     a human-readable description of the failure; must not
     *                    contain a card CVV or a password
     */
    public FileStatusException(String fileStatus, String message) {
        super(message);
        this.fileStatus = fileStatus;
    }

    /**
     * Creates a new {@code FileStatusException} that wraps an underlying cause,
     * such as a JDBC/JPA data-access failure, without discarding the root cause.
     *
     * @param fileStatus the original two-character COBOL {@code FILE STATUS}
     *                    code; preserved verbatim and retrievable through
     *                    {@link #getFileStatus()}
     * @param message     a human-readable description of the failure; must not
     *                    contain a card CVV or a password
     * @param cause       the underlying cause; a {@code null} value is permitted
     *                    and indicates that the cause is unknown
     */
    public FileStatusException(String fileStatus, String message, Throwable cause) {
        super(message, cause);
        this.fileStatus = fileStatus;
    }

    /**
     * Returns the original two-character COBOL {@code FILE STATUS} code carried
     * by this exception.
     *
     * @return the raw two-character status code, exactly as it was supplied
     */
    public String getFileStatus() {
        return fileStatus;
    }

    /**
     * Tests whether this exception carries the given {@code FILE STATUS} code.
     * Provided so callers can branch on a status symbolically without repeating
     * null-safe string comparisons.
     *
     * @param code the two-character status code to compare against, typically
     *             one of the {@code STATUS_*} constants
     * @return {@code true} if {@code code} is non-{@code null} and equal to this
     *         exception's status code; {@code false} otherwise
     */
    public boolean isFileStatus(String code) {
        return code != null && code.equals(this.fileStatus);
    }
}
