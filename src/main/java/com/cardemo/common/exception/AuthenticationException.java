/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
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
 * <p>This exception maps to the sign-on error handling logic in COSGN00C.cbl,
 * which authenticates users against the USRSEC VSAM dataset. The COBOL program
 * handles several authentication failure scenarios that this exception covers:</p>
 *
 * <h2>COBOL Sign-On Error Handling (COSGN00C.cbl)</h2>
 * <ul>
 *   <li><strong>User not found</strong> — EXEC CICS READ returns
 *       {@code DFHRESP(NOTFND)} (RESP code 13, paragraphs 247–251).
 *       The user ID does not exist in the USRSEC dataset.</li>
 *   <li><strong>Password mismatch</strong> — The READ succeeds but
 *       {@code IF SEC-USR-PWD = WS-USER-PWD} evaluates to false (line 223).
 *       The password entered does not match the stored password.</li>
 *   <li><strong>User ID blank/empty</strong> — The BMS map field USERIDI
 *       is empty or contains only spaces (paragraphs 115–120).</li>
 *   <li><strong>Password blank/empty</strong> — The BMS map field PASSWDI
 *       is empty or contains only spaces (paragraphs 121–125).</li>
 * </ul>
 *
 * <p>In the Java migration, password comparison changes from plaintext
 * ({@code IF SEC-USR-PWD = WS-USER-PWD}) to BCrypt hash verification
 * ({@code BCryptPasswordEncoder.matches(rawPassword, encodedPassword)}).
 * This exception is thrown regardless of the comparison mechanism.</p>
 *
 * <h2>Exception Hierarchy Position</h2>
 * <pre>
 * RuntimeException
 * └── CardDemoException
 *     └── AuthenticationException   (this class)
 * </pre>
 *
 * <p><strong>Note:</strong> This is a CardDemo application-level exception,
 * distinct from {@code org.springframework.security.core.AuthenticationException}
 * (Spring Security). The service layer may translate between the two as needed.</p>
 *
 * @see com.cardemo.common.exception.CardDemoException
 */
public class AuthenticationException extends CardDemoException {

    private static final long serialVersionUID = 1L;

    /**
     * The user ID associated with the failed authentication attempt.
     * Corresponds to the COBOL field {@code CDEMO-USER-ID PIC X(08)}
     * from COCOM01Y.cpy, or the BMS map field {@code USERIDI} from COSGN0A.
     */
    private final String userId;

    /**
     * Constructs a new {@code AuthenticationException} with the specified
     * user ID and detail message.
     *
     * @param userId  the user ID that failed authentication (may be null if not provided)
     * @param message a detail message describing the authentication failure
     */
    public AuthenticationException(String userId, String message) {
        super(message);
        this.userId = userId;
    }

    /**
     * Constructs a new {@code AuthenticationException} with the specified
     * user ID, detail message, and root cause.
     *
     * @param userId  the user ID that failed authentication
     * @param message a detail message describing the authentication failure
     * @param cause   the underlying cause of the authentication failure
     */
    public AuthenticationException(String userId, String message, Throwable cause) {
        super(message, cause);
        this.userId = userId;
    }

    /**
     * Constructs a new {@code AuthenticationException} with only a detail message
     * (no specific user ID).
     *
     * @param message a detail message describing the authentication failure
     */
    public AuthenticationException(String message) {
        super(message);
        this.userId = null;
    }

    /**
     * Returns the user ID associated with the failed authentication attempt.
     *
     * @return the user ID, or {@code null} if not available
     */
    public String getUserId() {
        return userId;
    }
}
