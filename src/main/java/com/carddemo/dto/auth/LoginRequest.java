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
package com.carddemo.dto.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Login request payload carrying user credentials for {@code POST /api/auth/login}.
 *
 * <p>Consumed by {@code AuthController.login()} and validated by Jakarta Bean
 * Validation 3.0 before reaching {@code AuthService}. This DTO is the stateless
 * REST replacement for the CICS sign-on input captured by
 * {@code EXEC CICS RECEIVE MAP('COSGN0A') MAPSET('COSGN00')}
 * ({@code app/cbl/COSGN00C.cbl:L110-L115}); the BMS symbolic input fields
 * {@code USERIDI}/{@code PASSWDI} ({@code app/cpy-bms/COSGN00.CPY} lines 72 and 78)
 * are now Jackson-deserialized JSON properties.</p>
 *
 * <p><b>COBOL source mapping</b> ({@code app/cpy-bms/COSGN00.CPY}):</p>
 * <table border="1">
 *   <caption>BMS field to Java field mapping</caption>
 *   <tr><th>COBOL field</th><th>Java field</th><th>Notes</th></tr>
 *   <tr><td>{@code USERIDI PIC X(8)} (L72)</td><td>{@code userId}</td>
 *       <td>Uppercase alphanumeric, max 8 chars.</td></tr>
 *   <tr><td>{@code PASSWDI PIC X(8)} (L78)</td><td>{@code password}</td>
 *       <td>Raw input; max length widened to 72 for BCrypt input safety.</td></tr>
 * </table>
 *
 * <p><b>Validation parity</b> with {@code COSGN00C.cbl PROCESS-ENTER-KEY}:</p>
 * <ul>
 *   <li>{@code @NotBlank} on {@code userId} mirrors the COBOL check
 *       {@code WHEN USERIDI = SPACES OR LOW-VALUES} ({@code COSGN00C.cbl:L118-L122}).</li>
 *   <li>{@code @NotBlank} on {@code password} mirrors
 *       {@code WHEN PASSWDI = SPACES OR LOW-VALUES} ({@code COSGN00C.cbl:L123-L127}).</li>
 *   <li>{@code @Pattern("[A-Z0-9]+")} on {@code userId} encodes the
 *       {@code MOVE FUNCTION UPPER-CASE(USERIDI)} convention
 *       ({@code COSGN00C.cbl:L132-L134}); lowercase is rejected with HTTP 400 at
 *       the validation boundary before reaching {@code AuthService}.</li>
 * </ul>
 *
 * <p><b>Security notes:</b></p>
 * <ul>
 *   <li><b>PR-17</b> &mdash; the {@code password} field carries only the RAW
 *       (cleartext over HTTPS) credential in transit; {@code AuthService}
 *       BCrypt-matches it against the stored 60-character hash via
 *       {@code BCryptPasswordEncoder.matches(rawPassword, storedHash)}. No
 *       plaintext password is ever persisted.</li>
 *   <li>{@code @ToString(exclude = "password")} overrides the {@code @Data}-generated
 *       {@code toString()} so the password value can never leak into logs or
 *       exception messages (e.g. {@code log.info("Received {}", loginRequest)}).</li>
 *   <li>The case of {@code password} is deliberately preserved &mdash; unlike the
 *       COBOL {@code MOVE FUNCTION UPPER-CASE(PASSWDI)} ({@code COSGN00C.cbl:L135-L136}),
 *       the Java migration does NOT uppercase the password (no {@code @Pattern}
 *       constraint). This is an intentional security improvement per AAP &sect;0.6.8
 *       that restores full password entropy.</li>
 * </ul>
 *
 * <p><b>Password length (8 &rarr; 72):</b> the original COBOL {@code PIC X(8)}
 * storage limit is intentionally lifted because Java stores a 60-character BCrypt
 * hash rather than the raw value. The DTO accepts up to 72 characters, which is
 * BCrypt's raw-input limit (input beyond 72 bytes is silently truncated, so
 * accepting more would lose entropy). See AAP &sect;0.4.1.7.</p>
 *
 * @see com.carddemo.dto.auth.LoginResponse
 */
@Schema(
    description = "Login request payload carrying user credentials for POST /api/auth/login. "
        + "Replaces CICS COSGN00 BMS map input (USERIDI/PASSWDI)."
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = "password")
public class LoginRequest {

    /**
     * User ID &mdash; uppercase alphanumeric, maximum 8 characters.
     *
     * <p>Mirrors COBOL {@code USERIDI PIC X(8)} ({@code app/cpy-bms/COSGN00.CPY:L72}).
     * The {@code [A-Z0-9]+} pattern enforces the {@code FUNCTION UPPER-CASE}
     * convention from {@code COSGN00C.cbl:L132-L134} at the API boundary, providing
     * a fail-fast HTTP 400 for lowercase input even though {@code AuthService}
     * defensively normalizes with {@code .toUpperCase()}.</p>
     */
    @NotBlank(message = "User ID is required")
    @Size(min = 1, max = 8, message = "User ID must be between 1 and 8 characters")
    @Pattern(regexp = "[A-Z0-9]+", message = "User ID must contain only uppercase letters and digits")
    @Schema(
        description = "User ID — uppercase alphanumeric, max 8 characters. "
            + "Defaults from COBOL: ADMIN001-ADMIN005, USER0001-USER0005.",
        example = "ADMIN001",
        maxLength = 8,
        requiredMode = Schema.RequiredMode.REQUIRED
    )
    private String userId;

    /**
     * Raw password (cleartext over HTTPS), maximum 72 characters.
     *
     * <p>Mirrors COBOL {@code PASSWDI PIC X(8)} ({@code app/cpy-bms/COSGN00.CPY:L78}),
     * with the length extended to the 72-byte BCrypt input limit. The value is
     * BCrypt-matched in {@code AuthService} and is NEVER logged (excluded from
     * {@link #toString()}) or echoed back to clients. No {@code @Pattern} is applied
     * so case and special characters are preserved, maximizing password entropy
     * (AAP &sect;0.6.8).</p>
     */
    @NotBlank(message = "Password is required")
    @Size(min = 1, max = 72, message = "Password must be between 1 and 72 characters")
    @Schema(
        description = "Raw password (cleartext over HTTPS); will be BCrypt-matched in AuthService via "
            + "BCryptPasswordEncoder.matches(). NEVER logged or echoed. COBOL stored 8 chars plaintext; "
            + "Java accepts up to 72 chars and stores a 60-char BCrypt hash.",
        example = "PASSWORD",
        maxLength = 72,
        requiredMode = Schema.RequiredMode.REQUIRED,
        format = "password"
    )
    private String password;
}
