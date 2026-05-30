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
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Single menu option (entry) returned by {@code GET /api/menu}.
 *
 * <p>Sourced from the original CardDemo COBOL menu copybooks:
 * <ul>
 *   <li>{@code app/cpy/COMEN02Y.cpy} &mdash; 10 user-menu options
 *       ({@code CDEMO-MENU-OPT} OCCURS structure, with {@code OPTN-USR-TYPE = 'U'} for all entries).</li>
 *   <li>{@code app/cpy/COADM02Y.cpy} &mdash; 4 admin-menu options
 *       ({@code CDEMO-ADMIN-OPT} OCCURS structure, no {@code OPTN-USR-TYPE} field; implicitly {@code 'A'}).</li>
 * </ul>
 *
 * <p>This DTO is the transport container only. Concrete option values (e.g.
 * {@code "Account View" -> COACTVWC -> 'U'}) are loaded by {@code MenuController}
 * from static configuration and emitted as the {@code options} list of the
 * {@code MenuResponse} payload. It replaces the legacy COBOL menu programs
 * {@code COMEN01C} (user menu) and {@code COADM01C} (admin menu).
 *
 * <p>Field mapping:
 * <pre>
 *   COBOL                                            Java
 *   -----                                            ----
 *   CDEMO-MENU-OPT-NUM     PIC 9(02)        |-&gt;  optNum       (Integer, &#64;Min 1, &#64;Max 99)
 *   CDEMO-MENU-OPT-NAME    PIC X(35)        |-&gt;  optName      (String, &#64;Size max 35)
 *   CDEMO-MENU-OPT-PGMNAME PIC X(08)        |-&gt;  pgmName      (String, &#64;Size 8..8)
 *   CDEMO-MENU-OPT-USRTYPE PIC X(01)        |-&gt;  requiredRole (String, &#64;Pattern "[AU]")
 *   (no COBOL equivalent)                   |-&gt;  route        (String, REST URI hint)
 * </pre>
 *
 * <p><strong>PR-13 compliance:</strong> Field lengths match COBOL PIC clauses exactly
 * ({@code PIC 9(02)} -&gt; 1..99, {@code PIC X(35)} -&gt; max 35, {@code PIC X(08)} -&gt; exactly 8,
 * {@code PIC X(01)} -&gt; exactly 1).
 * <p><strong>PR-19 compliance:</strong> {@code requiredRole} carries the COBOL {@code 'A'}/{@code 'U'} value
 * verbatim; mapping to Spring Security authorities ({@code ROLE_ADMIN}/{@code ROLE_USER}) and
 * role-based filtering happens at the {@code MenuController}/{@code MenuService} layer, NOT in this DTO.
 *
 * <p>The {@code route} field is NEW (no COBOL equivalent); {@code MenuController} populates it to map
 * the legacy COBOL {@code pgmName} to a modern REST endpoint, easing frontend navigation. It may be null.
 *
 * <p>This is an immutable Java 17 record &mdash; equality, hashing, and string representation are
 * auto-generated. Records are implicitly final.
 *
 * <p>Version reference: CardDemo_v1.0-15-g27d6c6f-68 (COMEN02Y), CardDemo_v1.0-26-g42273c1-79 (COADM02Y).
 */
@Schema(description = "A single menu option/entry exposed by GET /api/menu. " +
        "Derived from CardDemo COBOL menu copybooks COMEN02Y (user menu, 10 options) " +
        "and COADM02Y (admin menu, 4 options).")
public record MenuOption(

        // 1. optNum - COBOL: CDEMO-MENU-OPT-NUM / CDEMO-ADMIN-OPT-NUM PIC 9(02)
        @Schema(description = "Sequential option number (1-10 for user menu, 1-4 for admin menu). " +
                "Mirrors COBOL OPTN-NUM PIC 9(02).", example = "1", minimum = "1", maximum = "99")
        @Min(value = 1, message = "optNum must be at least 1")
        @Max(value = 99, message = "optNum must be at most 99")
        Integer optNum,

        // 2. optName - COBOL: CDEMO-MENU-OPT-NAME / CDEMO-ADMIN-OPT-NAME PIC X(35)
        @Schema(description = "Display label for the menu option. Max 35 characters mirroring COBOL OPTN-NAME PIC X(35).",
                example = "Account View", maxLength = 35)
        @NotBlank(message = "optName must not be blank")
        @Size(max = 35, message = "optName must be at most 35 characters")
        String optName,

        // 3. pgmName - COBOL: CDEMO-MENU-OPT-PGMNAME / CDEMO-ADMIN-OPT-PGMNAME PIC X(08)
        @Schema(description = "Legacy COBOL program name preserved for traceability/audit (e.g., 'COACTVWC', 'COUSR00C'). " +
                "Mirrors COBOL OPTN-PGM-NAME PIC X(08). Not used directly for REST routing.",
                example = "COACTVWC", minLength = 8, maxLength = 8)
        @NotBlank(message = "pgmName must not be blank")
        @Size(min = 8, max = 8, message = "pgmName must be exactly 8 characters")
        String pgmName,

        // 4. requiredRole - COBOL: CDEMO-MENU-OPT-USRTYPE PIC X(01) (COMEN02Y all 'U'; COADM02Y implicitly 'A')
        @Schema(description = "Required role to view this menu option. 'A' = admin-only (maps to ROLE_ADMIN), " +
                "'U' = available to all authenticated users (ROLE_USER and ROLE_ADMIN). " +
                "Mirrors COBOL OPTN-USR-TYPE PIC X(01). Filtering enforced at MenuController layer per PR-19.",
                example = "U", allowableValues = {"A", "U"}, minLength = 1, maxLength = 1)
        @Size(min = 1, max = 1, message = "requiredRole must be exactly 1 character")
        @Pattern(regexp = "[AU]", message = "requiredRole must be 'A' (admin) or 'U' (user)")
        String requiredRole,

        // 5. route - OPTIONAL, no COBOL equivalent
        @Schema(description = "Modern REST URI hint to ease frontend navigation (e.g., '/api/accounts/{acctId}'). " +
                "Not present in the original COBOL copybooks; populated by MenuController to map " +
                "the legacy COBOL pgmName to a modern REST endpoint. May be null.",
                example = "/api/accounts/{acctId}", nullable = true)
        String route

) {
}
