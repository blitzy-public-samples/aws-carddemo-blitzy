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
package com.carddemo.dto.menu;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Outbound REST response payload for {@code GET /api/menu}.
 *
 * <p>Combines two CardDemo COBOL menu copybooks into a single role-filtered response:
 * <ul>
 *   <li>{@code app/cpy/COMEN02Y.cpy} &mdash; 10 user-menu options
 *       (visible to {@code ROLE_USER} and {@code ROLE_ADMIN}); every entry carries
 *       {@code CDEMO-MENU-OPT-USRTYPE = 'U'}.</li>
 *   <li>{@code app/cpy/COADM02Y.cpy} &mdash; 4 admin-menu options
 *       (visible to {@code ROLE_ADMIN} only); the copybook has no usr-type field, so
 *       these entries are implicitly {@code 'A'}.</li>
 * </ul>
 *
 * <p>Replaces the legacy COBOL menu programs:
 * <ul>
 *   <li>{@code COMEN01C} (user menu screen)</li>
 *   <li>{@code COADM01C} (admin menu screen)</li>
 * </ul>
 *
 * <p>Field mapping:
 * <pre>
 *   COBOL (COCOM01Y.cpy / menu copybooks)            Java
 *   -------------------------------------            ----
 *   CDEMO-USER-ID    PIC X(08)              |-&gt;  userId         (String, &#64;Size max 8)
 *   CDEMO-USER-TYPE  PIC X(01)              |-&gt;  userType       (String, &#64;Size 1..1)
 *   CDEMO-MENU-OPT / CDEMO-ADMIN-OPT array  |-&gt;  options        (List&lt;MenuOption&gt;)
 *   (no COBOL equivalent)                   |-&gt;  welcomeMessage (String, optional)
 * </pre>
 *
 * <p><strong>PR-13 compliance:</strong> {@code userId} mirrors COBOL
 * {@code CDEMO-USER-ID PIC X(08)} (max 8 characters) and {@code userType} mirrors COBOL
 * {@code CDEMO-USER-TYPE PIC X(01)} (exactly 1 character), both from
 * {@code app/cpy/COCOM01Y.cpy}.
 *
 * <p><strong>PR-19 compliance:</strong> {@code MenuController} reads
 * {@code SecurityContextHolder.getContext().getAuthentication()} for the current user's role,
 * then assembles the appropriate role-filtered list of {@link MenuOption} instances. The
 * {@code 'A'} role (ROLE_ADMIN) sees the admin menu; the {@code 'U'} role (ROLE_USER) sees the
 * user menu. The filtering happens at the controller layer, NOT in this DTO &mdash; this record
 * is purely the transport container for whatever list the controller has already filtered.
 *
 * <p>The {@code welcomeMessage} field is NEW (no COBOL equivalent); {@code MenuController}
 * constructs it from the authenticated customer's first/last name for frontend convenience.
 * It may be {@code null}.
 *
 * <p>This is an immutable Java 17 record &mdash; equality, hashing, and string representation are
 * auto-generated. Records are implicitly final.
 *
 * <p>{@link MenuOption} resides in the same package ({@code com.carddemo.dto.menu}) and
 * therefore needs no import.
 *
 * <p>Version reference: CardDemo_v1.0-15-g27d6c6f-68 (COMEN02Y), CardDemo_v1.0-26-g42273c1-79 (COADM02Y).
 *
 * @param options        the role-filtered list of menu options assembled by {@code MenuController}
 * @param userType       the authenticated user's type code ('A' for ADMIN, 'U' for USER)
 * @param userId         the authenticated user identifier (max 8 characters)
 * @param welcomeMessage an optional, client-friendly greeting (may be {@code null})
 */
@Schema(description = "Outbound response from GET /api/menu. Contains a role-filtered list of " +
        "menu options along with the current user's identity for client convenience. " +
        "The options list is filtered by MenuController based on the authenticated user's role " +
        "(ROLE_ADMIN receives admin menu, ROLE_USER receives user menu) per PR-19.")
public record MenuResponse(

        // 1. options - role-filtered list of menu options (element type MenuOption, same package).
        @Schema(description = "Role-filtered list of menu options. " +
                "For ROLE_USER: 10 entries from COMEN02Y (Account View, Account Update, Credit Card List, etc.). " +
                "For ROLE_ADMIN: 4 entries from COADM02Y (User List, User Add, User Update, User Delete) " +
                "OR a combined list depending on MenuController policy. " +
                "Filtering enforced at MenuController layer; this DTO is just the transport container.")
        List<MenuOption> options,

        // 2. userType - COBOL: CDEMO-USER-TYPE PIC X(01) from COCOM01Y.cpy.
        @Schema(description = "Echoes the authenticated user's type for client convenience. " +
                "'A' = ADMIN, 'U' = USER. Mirrors COBOL CDEMO-USER-TYPE PIC X(01) from COCOM01Y.cpy.",
                example = "U", allowableValues = {"A", "U"}, minLength = 1, maxLength = 1)
        @Size(min = 1, max = 1, message = "userType must be exactly 1 character")
        String userType,

        // 3. userId - COBOL: CDEMO-USER-ID PIC X(08) from COCOM01Y.cpy.
        @Schema(description = "Authenticated user identifier (8 characters). " +
                "Mirrors COBOL CDEMO-USER-ID PIC X(08) from COCOM01Y.cpy.",
                example = "ADMIN001", minLength = 1, maxLength = 8)
        @Size(max = 8, message = "userId must be at most 8 characters")
        String userId,

        // 4. welcomeMessage - OPTIONAL, no COBOL equivalent.
        @Schema(description = "Optional welcome message (e.g., 'Welcome, John Doe'). " +
                "Constructed by MenuController from the customer's first/last name. May be null.",
                example = "Welcome, John Doe", nullable = true)
        String welcomeMessage

) {
}
