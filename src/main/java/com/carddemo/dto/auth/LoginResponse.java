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
import lombok.Builder;

import java.time.Instant;

/**
 * Login response payload.
 *
 * <p>Returned by {@code AuthController.login()} after successful authentication.
 * Carries a signed JWT bearer token plus user identity claims for client display.
 *
 * <p>Migration context:
 * <ul>
 *   <li>Replaces the CICS {@code EXEC CICS XCTL} program-chaining pattern
 *       (COSGN00C.cbl:L230-L240) with a stateless REST + JWT model per AAP &sect;0.6.1.
 *       In the original program, a successful sign-on transferred control to
 *       {@code COADM01C} (admin) or {@code COMEN01C} (user) while carrying the
 *       1024-byte COMMAREA; the REST migration instead returns this response
 *       and lets the client drive subsequent navigation.</li>
 *   <li>Replaces the 1024-byte COMMAREA ({@code app/cpy/COCOM01Y.cpy})
 *       with HTTP-level statelessness &mdash; userType is encoded as a JWT claim
 *       and re-derived per request by {@code JwtAuthenticationFilter}.</li>
 *   <li>Field sources:
 *     <ul>
 *       <li>{@code userType} &mdash; 'A' (ADMIN) or 'U' (USER) from
 *           {@code app/cpy/COCOM01Y.cpy} 88-levels CDEMO-USRTYP-ADMIN/CDEMO-USRTYP-USER
 *           (lines 26-28).</li>
 *       <li>{@code firstName}/{@code lastName} &mdash; from
 *           {@code app/cpy/CSUSR01Y.cpy} SEC-USR-FNAME/SEC-USR-LNAME (PIC X(20)).</li>
 *       <li>{@code userId} &mdash; echoed from {@code LoginRequest} (uppercase, normalized),
 *           mirroring {@code MOVE WS-USER-ID TO CDEMO-USER-ID} at
 *           {@code app/cbl/COSGN00C.cbl:L226}.</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p><strong>Security:</strong> This DTO must NEVER contain a password,
 * password hash, or any other secret. The token is the only secret-equivalent
 * and is signed (not encrypted) &mdash; clients store it for short-lived sessions only.
 * This upholds PR-17 (no plaintext passwords) and PR-20 (sensitive fields never
 * returned on outbound payloads): {@code SEC-USR-PWD} from
 * {@code app/cpy/CSUSR01Y.cpy} is deliberately omitted.
 *
 * @param token      the signed JWT bearer token presented on subsequent requests
 * @param userId     the echoed, uppercase-normalized user identifier
 * @param userType   the raw user-type code ('A' for ADMIN, 'U' for USER)
 * @param firstName  the user's first name for display purposes
 * @param lastName   the user's last name for display purposes
 * @param expiresAt  the UTC instant at which the JWT token expires
 */
@Schema(
    description = "Login response with JWT bearer token and user identity claims. "
        + "Replaces the CICS COMMAREA-based session from app/cpy/COCOM01Y.cpy via "
        + "stateless JWT per AAP \u00a70.6.1."
)
@Builder
public record LoginResponse(

    @Schema(
        description = "JWT bearer token. Pass in subsequent requests as "
            + "'Authorization: Bearer <token>'. Signed by SecurityConfig (HMAC-SHA256). "
            + "Includes claims: sub (userId), userType, iat, exp.",
        example = "eyJhbGciOiJIUzI1NiJ9...",
        requiredMode = Schema.RequiredMode.REQUIRED
    )
    String token,

    @Schema(
        description = "Echoed user ID (uppercase, normalized in AuthService from request input).",
        example = "ADMIN001",
        maxLength = 8,
        requiredMode = Schema.RequiredMode.REQUIRED
    )
    String userId,

    @Schema(
        description = "User type: A = ADMIN (CustomAuthorityMapper maps to ROLE_ADMIN), "
            + "U = USER (maps to ROLE_USER). Encodes the COBOL "
            + "CDEMO-USRTYP-ADMIN/CDEMO-USRTYP-USER 88-levels from "
            + "app/cpy/COCOM01Y.cpy lines 26-28.",
        example = "A",
        allowableValues = {"A", "U"},
        maxLength = 1,
        requiredMode = Schema.RequiredMode.REQUIRED
    )
    String userType,

    @Schema(
        description = "User first name for display purposes. Sourced from "
            + "app/cpy/CSUSR01Y.cpy SEC-USR-FNAME PIC X(20).",
        example = "Alice",
        maxLength = 20
    )
    String firstName,

    @Schema(
        description = "User last name for display purposes. Sourced from "
            + "app/cpy/CSUSR01Y.cpy SEC-USR-LNAME PIC X(20).",
        example = "Admin",
        maxLength = 20
    )
    String lastName,

    @Schema(
        description = "UTC timestamp when the JWT token expires. Serialized by Jackson as "
            + "ISO-8601 string (via JavaTimeModule). Client should refresh before this time.",
        example = "2024-12-31T23:59:59Z",
        type = "string",
        format = "date-time",
        requiredMode = Schema.RequiredMode.REQUIRED
    )
    Instant expiresAt

) {
    // Record body intentionally empty: components are implicitly final, and the
    // canonical constructor, accessors, equals/hashCode/toString are generated by
    // the record contract. No compact constructor is required because response
    // DTOs carry server-emitted values that are not subject to input validation.
}
