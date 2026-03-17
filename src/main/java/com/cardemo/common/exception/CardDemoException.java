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
package com.cardemo.common.exception;

/**
 * Base exception class for the entire CardDemo application.
 *
 * <p>All application-specific exceptions in the {@code com.cardemo.common.exception}
 * package extend this class. It extends {@link RuntimeException} (unchecked)
 * following Spring Framework conventions for data-access and business-logic exceptions.</p>
 *
 * <h2>COBOL Origin</h2>
 * <p>In the original COBOL CardDemo programs, unrecoverable errors are handled by:</p>
 * <ul>
 *   <li>{@code CALL CEE3ABD} — Language Environment abnormal termination used in batch
 *       programs (e.g., CBTRN02C paragraph {@code 9999-ABEND-PROGRAM})</li>
 *   <li>{@code EXEC CICS ABEND ABCODE(...)} — used in CICS online programs for
 *       unrecoverable transaction errors</li>
 *   <li>{@code 9999-ABEND-PROGRAM} paragraphs — common abend routine invoked when
 *       {@code IO-STATUS} or {@code APPL-RESULT} indicates a fatal error</li>
 *   <li>{@code 9910-DISPLAY-IO-STATUS} paragraphs — formatting and displaying
 *       diagnostic error information (two-byte VSAM file status codes) before abend</li>
 * </ul>
 *
 * <p>This base exception class serves as the Java equivalent of these error-handling
 * patterns, providing a single exception hierarchy root for all CardDemo error
 * conditions. The two-byte VSAM file status codes ({@code 00}, {@code 22},
 * {@code 23}, {@code 35}, etc.) are mapped to specific subclasses of this exception.</p>
 *
 * <h2>Exception Hierarchy</h2>
 * <pre>
 * RuntimeException
 * └── CardDemoException              (this class)
 *     ├── FileStatusException        (VSAM file status errors)
 *     │   ├── RecordNotFoundException    (STATUS '23')
 *     │   └── DuplicateRecordException   (STATUS '22')
 *     ├── AuthenticationException    (Sign-on failures)
 *     └── ValidationException        (Field validation errors)
 * </pre>
 *
 * <h2>Design Rationale</h2>
 * <p>Extending {@link RuntimeException} (unchecked) rather than {@link Exception}
 * (checked) matches the COBOL pattern where errors are handled at the transaction
 * level rather than at every individual call site. This aligns with Spring Framework
 * conventions where repository and service layer exceptions are unchecked, allowing
 * them to propagate to centralized error handlers.</p>
 *
 * @see RuntimeException
 */
public class CardDemoException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructs a new {@code CardDemoException} with the specified detail message.
     *
     * <p>This is the primary constructor for creating exceptions with descriptive
     * error messages. It corresponds to the COBOL pattern of displaying an error
     * description (e.g., {@code 'ERROR OPENING DALYTRAN'}) before invoking the
     * {@code 9999-ABEND-PROGRAM} paragraph.</p>
     *
     * @param message the detail message describing the error condition;
     *                may be retrieved later by {@link #getMessage()}
     */
    public CardDemoException(String message) {
        super(message);
    }

    /**
     * Constructs a new {@code CardDemoException} with the specified detail message
     * and cause.
     *
     * <p>Use this constructor when wrapping lower-level exceptions (e.g., JPA
     * exceptions, JDBC exceptions, I/O exceptions) with CardDemo-specific context.
     * This corresponds to the COBOL pattern where a file status code is captured
     * via {@code 9910-DISPLAY-IO-STATUS} before the program abends — the cause
     * preserves the original error while the message adds application context.</p>
     *
     * @param message the detail message describing the error condition;
     *                may be retrieved later by {@link #getMessage()}
     * @param cause   the underlying cause of this exception;
     *                may be retrieved later by {@link #getCause()}
     */
    public CardDemoException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Constructs a new {@code CardDemoException} with the specified cause.
     *
     * <p>Use this constructor for re-wrapping exceptions when the original
     * exception's message is sufficient and no additional context is needed.
     * The detail message of this exception will be
     * {@code (cause == null ? null : cause.toString())}.</p>
     *
     * @param cause the underlying cause of this exception;
     *              may be retrieved later by {@link #getCause()}
     */
    public CardDemoException(Throwable cause) {
        super(cause);
    }
}
