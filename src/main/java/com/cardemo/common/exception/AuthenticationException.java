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
 * Exception indicating an authentication failure in the CardDemo application.
 *
 * <p>This exception maps to the sign-on error handling logic in the
 * {@code READ-USER-SEC-FILE} paragraph of COSGN00C.cbl (lines 209–257), which
 * authenticates users against the USRSEC VSAM dataset. The COBOL program's
 * three authentication failure paths all translate to this exception with
 * specific messages preserved from the original {@code WS-MESSAGE} values:</p>
 *
 * <h2>COBOL Sign-On Error Paths (COSGN00C.cbl)</h2>
 * <ul>
 *   <li><strong>Password mismatch</strong> — {@code WHEN 0} (normal read)
 *       followed by {@code IF SEC-USR-PWD = WS-USER-PWD} evaluating to false
 *       (line 223). Message: {@code "Wrong Password. Try again ..."}</li>
 *   <li><strong>User not found</strong> — {@code WHEN 13} (DFHRESP NOTFND,
 *       lines 247–251). The user ID does not exist in the USRSEC dataset.
 *       Message: {@code "User not found. Try again ..."}</li>
 *   <li><strong>Unexpected I/O error</strong> — {@code WHEN OTHER}
 *       (lines 252–256). An unexpected error occurred during the CICS READ
 *       operation. Message: {@code "Unable to verify the User ..."}</li>
 * </ul>
 *
 * <p>The CSUSR01Y.cpy copybook defines the {@code SEC-USER-DATA} record used
 * for the security file lookup, with {@code SEC-USR-ID} (8 bytes) as the
 * primary key and {@code SEC-USR-PWD} (8 bytes) as the plaintext password
 * field. In the Java migration, password comparison changes from plaintext
 * ({@code IF SEC-USR-PWD = WS-USER-PWD}) to BCrypt hash verification, but
 * the same three error paths are preserved.</p>
 *
 * <p>No additional custom fields are needed — error details are conveyed
 * via the message string, matching the COBOL pattern where
 * {@code WS-MESSAGE PIC X(80)} carries the error text.</p>
 *
 * <h2>Exception Hierarchy Position</h2>
 * <pre>
 * RuntimeException
 * └── CardDemoException
 *     └── AuthenticationException   (this class)
 * </pre>
 *
 * <p><strong>Important:</strong> This is a CardDemo application-level exception
 * in the {@code com.cardemo.common.exception} package, distinct from
 * {@code org.springframework.security.core.AuthenticationException} (Spring
 * Security). The controller or security layer is responsible for mapping
 * between the two as needed. Java imports will disambiguate by fully
 * qualified class name.</p>
 *
 * @see CardDemoException
 */
public class AuthenticationException extends CardDemoException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructs a new {@code AuthenticationException} with the specified
     * detail message.
     *
     * <p>This constructor is used for the primary authentication failure paths
     * originating from the {@code READ-USER-SEC-FILE} paragraph in COSGN00C.cbl.
     * The message should preserve the original COBOL error text semantics:</p>
     * <ul>
     *   <li>{@code "Wrong Password. Try again ..."} — password mismatch
     *       (COBOL line 242–243)</li>
     *   <li>{@code "User not found. Try again ..."} — user ID not in USRSEC
     *       (COBOL line 249)</li>
     *   <li>{@code "Unable to verify the User ..."} — unexpected I/O error
     *       (COBOL line 254)</li>
     * </ul>
     *
     * @param message the detail message describing the authentication failure;
     *                may be retrieved later by {@link #getMessage()}
     */
    public AuthenticationException(String message) {
        super(message);
    }

    /**
     * Constructs a new {@code AuthenticationException} with the specified
     * detail message and underlying cause.
     *
     * <p>This constructor is used when wrapping lower-level exceptions (e.g.,
     * database access errors, JPA exceptions) that occur during the
     * authentication flow. It corresponds to the {@code WHEN OTHER} path in
     * COSGN00C.cbl where an unexpected error during CICS READ needs to be
     * propagated with the original exception preserved as the cause.</p>
     *
     * @param message the detail message describing the authentication failure;
     *                may be retrieved later by {@link #getMessage()}
     * @param cause   the underlying cause of the authentication failure
     *                (e.g., a database connection error during user lookup);
     *                may be retrieved later by {@link #getCause()}
     */
    public AuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }
}
