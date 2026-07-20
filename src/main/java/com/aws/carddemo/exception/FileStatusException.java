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
 * <p><strong>Status contract.</strong> The two-character code is validated at
 * construction: it must be exactly two ASCII-alphanumeric characters
 * ({@code [0-9A-Za-z]}), reflecting the COBOL {@code PIC X(2)} declaration. A
 * {@code null}, empty, single-character, overlength, or control-character value is
 * a programming error at the call site rather than a recoverable I/O outcome, and
 * is rejected with an {@link IllegalArgumentException}.</p>
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
     * COBOL {@code FILE STATUS} '99' — an implementor-defined "other/general" I/O
     * error. Used as the canonical two-character status for a non-normal outcome
     * that does not map to one of the specific {@code STATUS_*} codes above (for
     * example a CICS {@code RESP} that is neither {@code NOTFND} nor a duplicate).
     * When the originating code is a numeric CICS {@code RESP}, that precise value
     * is preserved in the exception message rather than in this two-character field.
     */
    public static final String STATUS_GENERAL_ERROR = "99";

    /**
     * The original two-character COBOL {@code FILE STATUS} code (for example
     * {@code "23"}, {@code "22"}, {@code "10"}, or a {@code "9x"} implementor
     * code). Preserved verbatim for caller-visible parity with the legacy
     * programs, and always exactly two ASCII-alphanumeric characters because every
     * construction path validates it (see {@link #requireValidFileStatus(String)}).
     */
    private final String fileStatus;

    /**
     * Creates a new {@code FileStatusException}.
     *
     * @param fileStatus the original two-character COBOL {@code FILE STATUS}
     *                    code; must be exactly two ASCII-alphanumeric characters
     *                    ({@code [0-9A-Za-z]}); preserved verbatim and retrievable
     *                    through {@link #getFileStatus()}
     * @param message     a human-readable description of the failure; must not
     *                    contain a card CVV or a password
     * @throws IllegalArgumentException if {@code fileStatus} is not exactly two
     *                                  ASCII-alphanumeric characters
     */
    public FileStatusException(String fileStatus, String message) {
        super(message);
        this.fileStatus = requireValidFileStatus(fileStatus);
    }

    /**
     * Creates a new {@code FileStatusException} that wraps an underlying cause,
     * such as a JDBC/JPA data-access failure, without discarding the root cause.
     *
     * @param fileStatus the original two-character COBOL {@code FILE STATUS}
     *                    code; must be exactly two ASCII-alphanumeric characters
     *                    ({@code [0-9A-Za-z]}); preserved verbatim and retrievable
     *                    through {@link #getFileStatus()}
     * @param message     a human-readable description of the failure; must not
     *                    contain a card CVV or a password
     * @param cause       the underlying cause; a {@code null} value is permitted
     *                    and indicates that the cause is unknown
     * @throws IllegalArgumentException if {@code fileStatus} is not exactly two
     *                                  ASCII-alphanumeric characters
     */
    public FileStatusException(String fileStatus, String message, Throwable cause) {
        super(message, cause);
        this.fileStatus = requireValidFileStatus(fileStatus);
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

    /**
     * Validates that a candidate status honors the two-character COBOL
     * {@code FILE STATUS} contract: it must be non-{@code null}, exactly two
     * characters long, and composed solely of ASCII-alphanumeric characters
     * ({@code 0-9}, {@code A-Z}, {@code a-z}). COBOL declares {@code FILE STATUS} as
     * {@code PIC X(2)}, and every status this system produces (the {@code STATUS_*}
     * constants, the {@code "9x"} implementor codes, and the fixed subclass
     * statuses) satisfies that shape, so a value that does not is a programming error
     * at the call site rather than a recoverable I/O outcome.
     *
     * @param fileStatus the candidate status code
     * @return {@code fileStatus} unchanged when it is valid
     * @throws IllegalArgumentException if {@code fileStatus} is {@code null}, is not
     *                                  exactly two characters, or contains a
     *                                  non-ASCII-alphanumeric character
     */
    private static String requireValidFileStatus(String fileStatus) {
        if (fileStatus == null) {
            throw new IllegalArgumentException("fileStatus must not be null");
        }
        if (fileStatus.length() != 2) {
            throw new IllegalArgumentException(
                    "fileStatus must be exactly two characters (COBOL FILE STATUS PIC X(2)), but was "
                            + render(fileStatus));
        }
        for (int i = 0; i < 2; i++) {
            char c = fileStatus.charAt(i);
            boolean alphanumeric =
                    (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
            if (!alphanumeric) {
                throw new IllegalArgumentException(
                        "fileStatus must contain only ASCII-alphanumeric characters, but was "
                                + render(fileStatus));
            }
        }
        return fileStatus;
    }

    /**
     * Renders a rejected {@code fileStatus} for an exception message with every
     * non-printable character escaped as {@code \\uXXXX}. A control character in an
     * invalid value therefore cannot corrupt a log line or inject content into it.
     *
     * @param value the value to render; already known to be non-{@code null}
     * @return a quoted, control-character-safe representation of {@code value}
     */
    private static String render(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= 0x20 && c < 0x7F) {
                sb.append(c);
            } else {
                sb.append(String.format("\\u%04X", (int) c));
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
