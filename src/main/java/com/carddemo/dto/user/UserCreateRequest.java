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
package com.carddemo.dto.user;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonProperty.Access;
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
 * User Administration Request DTO for create and update operations.
 *
 * <p>Consumed by {@code UserController} for the following ADMIN-only endpoints
 * (all protected by {@code @PreAuthorize("hasRole('ADMIN')")} per PR-18):</p>
 * <ul>
 *   <li>{@code POST /api/admin/users}              — create new user
 *       (all fields required including {@code password})</li>
 *   <li>{@code PUT  /api/admin/users/{userId}}     — update existing user
 *       ({@code password} may be {@code null} to preserve existing BCrypt hash;
 *       other fields, when provided, replace existing values)</li>
 * </ul>
 *
 * <p><b>SECURITY — RAW PASSWORD HANDLING (PR-17):</b></p>
 * <ul>
 *   <li>The {@code password} field carries the <b>RAW</b> (plaintext) password
 *       from the HTTP request body.</li>
 *   <li>{@code UserService} <b>MUST</b> call
 *       {@code BCryptPasswordEncoder.encode(rawPassword)} before persisting.</li>
 *   <li>The {@code secUsrPwd} column on the {@code User} entity stores only the
 *       60-character BCrypt hash — never plaintext.</li>
 *   <li>{@code @ToString.Exclude} suppresses {@code password} from Lombok's
 *       generated {@code toString()} (prevents leakage to logs).</li>
 *   <li>{@code @JsonProperty(access = Access.WRITE_ONLY)} accepts {@code password}
 *       on deserialization but Jackson never serializes it (defense in depth).</li>
 *   <li>Allowed password length is up to 72 chars (BCrypt safety buffer per
 *       AAP §0.4.1.7); the original COBOL {@code SEC-USR-PWD PIC X(08)} 8-char
 *       limit is intentionally widened for the modern stack.</li>
 * </ul>
 *
 * <p><b>ROLE MAPPING (PR-19):</b> {@code userType} accepts only:</p>
 * <ul>
 *   <li>{@code 'A'} → {@code ROLE_ADMIN}</li>
 *   <li>{@code 'U'} → {@code ROLE_USER}</li>
 * </ul>
 *
 * <p>Field-length constraints mirror COBOL {@code PIC} clauses per PR-13 (from
 * {@code app/cpy/CSUSR01Y.cpy} and {@code app/cpy-bms/COUSR01.CPY}).</p>
 *
 * @see com.carddemo.dto.user.UserDto
 * @see com.carddemo.entity.User
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "User create/update request (password is RAW input and is BCrypt-hashed before storage; null password on update preserves existing hash)")
public class UserCreateRequest {

    /**
     * User ID — primary key for the {@code User} entity / {@code SEC-USR-ID}.
     *
     * <p>Mirrors COBOL {@code USERIDI PIC X(8)} (app/cpy-bms/COUSR01.CPY line 72)
     * and {@code SEC-USR-ID PIC X(08)} (app/cpy/CSUSR01Y.cpy). Uppercase
     * normalization follows COSGN00C {@code FUNCTION UPPER-CASE} convention; the
     * {@code [A-Z0-9]+} pattern enforces uppercase alphanumeric at the API
     * boundary. On {@code PUT} this value should match the {@code {userId}} path
     * parameter.</p>
     */
    @NotBlank
    @Size(min = 1, max = 8)
    @Pattern(regexp = "[A-Z0-9]+", message = "userId must be uppercase alphanumeric")
    @Schema(description = "User ID (uppercase alphanumeric, max 8 chars). On PUT this should match the path parameter.",
            example = "ADMIN001", maxLength = 8, requiredMode = Schema.RequiredMode.REQUIRED)
    private String userId;

    /**
     * First name. Mirrors COBOL {@code FNAMEI PIC X(20)}
     * (app/cpy-bms/COUSR01.CPY line 60) / {@code SEC-USR-FNAME PIC X(20)}.
     */
    @NotBlank
    @Size(max = 20)
    @Schema(description = "First name (max 20 chars)", example = "John",
            maxLength = 20, requiredMode = Schema.RequiredMode.REQUIRED)
    private String firstName;

    /**
     * Last name. Mirrors COBOL {@code LNAMEI PIC X(20)}
     * (app/cpy-bms/COUSR01.CPY line 66) / {@code SEC-USR-LNAME PIC X(20)}.
     */
    @NotBlank
    @Size(max = 20)
    @Schema(description = "Last name (max 20 chars)", example = "Doe",
            maxLength = 20, requiredMode = Schema.RequiredMode.REQUIRED)
    private String lastName;

    /**
     * RAW (plaintext) password as supplied in the HTTP request body.
     *
     * <p><b>PR-17:</b> This value is NEVER stored or returned as-is.
     * {@code UserService} BCrypt-encodes it before persistence; the
     * {@code User} entity holds only the 60-character hash.</p>
     *
     * <p><b>Defense in depth:</b> {@code @ToString.Exclude} keeps the value out
     * of Lombok's generated {@code toString()} (no accidental log leakage), and
     * {@code @JsonProperty(access = Access.WRITE_ONLY)} lets Jackson read it from
     * the request but never write it to any response.</p>
     *
     * <p><b>Nullability:</b> {@code @NotBlank} is intentionally omitted so this
     * single DTO can be reused for {@code PUT} (update) where a {@code null}
     * password means "preserve the existing BCrypt hash". {@code UserService}
     * enforces non-blank on {@code POST} (create). When supplied, the value must
     * be 1–72 characters (the 72-byte BCrypt input limit).</p>
     */
    @Size(min = 1, max = 72)
    @ToString.Exclude
    @JsonProperty(access = Access.WRITE_ONLY)
    @Schema(description = "Raw password (1-72 chars). Required on POST (create); may be null on PUT (update) to preserve existing BCrypt hash. Service BCrypt-encodes before storage.",
            example = "PASSWORD", maxLength = 72,
            accessMode = Schema.AccessMode.WRITE_ONLY)
    private String password;

    /**
     * User type / role discriminator. Mirrors COBOL {@code USRTYPEI PIC X(1)}
     * (app/cpy-bms/COUSR01.CPY line 84) / {@code SEC-USR-TYPE PIC X(01)}.
     *
     * <p><b>PR-19:</b> only {@code 'A'} (→ {@code ROLE_ADMIN}) or {@code 'U'}
     * (→ {@code ROLE_USER}) are accepted; the mapping to a Spring Security
     * {@code GrantedAuthority} happens in {@code CustomAuthorityMapper} /
     * {@code UserDetailsServiceImpl}.</p>
     */
    @NotBlank
    @Size(min = 1, max = 1)
    @Pattern(regexp = "[AU]", message = "userType must be 'A' (ADMIN) or 'U' (USER)")
    @Schema(description = "User type: 'A' for ADMIN (ROLE_ADMIN), 'U' for USER (ROLE_USER)",
            example = "A", allowableValues = {"A", "U"}, maxLength = 1,
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String userType;
}
