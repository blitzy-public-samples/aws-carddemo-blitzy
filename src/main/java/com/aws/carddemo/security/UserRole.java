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
package com.aws.carddemo.security;

import java.util.Locale;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Application-level role model for CardDemo, migrated one-for-one from the legacy
 * COBOL COMMAREA condition names.
 *
 * <p><strong>Provenance.</strong> The two roles reproduce the single-character
 * {@code CDEMO-USER-TYPE} field and its {@code 88}-level condition names declared
 * in the shared communication-area copybook {@code app/cpy/COCOM01Y.cpy}
 * (relocated to {@code legacy/**}):</p>
 * <pre>
 * 10 CDEMO-USER-TYPE               PIC X(01).
 *    88 CDEMO-USRTYP-ADMIN         VALUE 'A'.
 *    88 CDEMO-USRTYP-USER          VALUE 'U'.
 * </pre>
 *
 * <p><strong>Authorization contract.</strong> Each constant binds the legacy
 * single-character code to a Spring Security authority string. The authority is
 * stored as the fully qualified {@code "ROLE_*"} value because Spring Security's
 * {@code hasRole("ADMIN")} / {@code hasRole("USER")} checks (used by
 * {@code config/SecurityConfig}) implicitly prepend the {@code ROLE_} prefix. The
 * strings held here are therefore exactly {@code "ROLE_ADMIN"} and
 * {@code "ROLE_USER"} so that both ends of that contract agree.</p>
 *
 * <p><strong>Behavioral parity.</strong> The sign-on program
 * {@code app/cbl/COSGN00C.cbl} moves {@code SEC-USR-TYPE} into
 * {@code CDEMO-USER-TYPE} after a successful credential match and branches with
 * {@code IF CDEMO-USRTYP-ADMIN ... ELSE ...}: only the value {@code 'A'} routes to
 * the Admin Menu program ({@code COADM01C}); every other value falls through the
 * {@code ELSE} to the ordinary Main Menu program ({@code COMEN01C}).
 * {@link #fromUserType(String)} reproduces exactly this {@code 'A'}-only-is-admin
 * semantics.</p>
 *
 * <p>This type has no dependencies on any other application class; it is the
 * foundational type of the {@code com.aws.carddemo.security} package.</p>
 */
public enum UserRole {

    /**
     * Administrator role. Corresponds to the COBOL condition name
     * {@code CDEMO-USRTYP-ADMIN VALUE 'A'} in {@code app/cpy/COCOM01Y.cpy} and is
     * granted the {@code "ROLE_ADMIN"} authority.
     */
    ADMIN("A", "ROLE_ADMIN"),

    /**
     * Standard user role. Corresponds to the COBOL condition name
     * {@code CDEMO-USRTYP-USER VALUE 'U'} in {@code app/cpy/COCOM01Y.cpy} and is
     * granted the {@code "ROLE_USER"} authority. This is also the safe, non-admin
     * default returned by {@link #fromUserType(String)} for any value other than
     * {@code 'A'}.
     */
    USER("U", "ROLE_USER");

    /**
     * The legacy single-character user-type code carried in the COBOL
     * {@code CDEMO-USER-TYPE} field ({@code 'A'} for admin, {@code 'U'} for user)
     * and persisted in the {@code user_security.sec_usr_type} column.
     */
    private final String code;

    /**
     * The Spring Security authority string granted to this role
     * ({@code "ROLE_ADMIN"} or {@code "ROLE_USER"}).
     */
    private final String authority;

    /**
     * Binds a role to its legacy code and its Spring Security authority string.
     * Enum constructors are implicitly private; instances are limited to the
     * declared constants.
     *
     * @param code      the legacy single-character user-type code
     *                  ({@code "A"} or {@code "U"})
     * @param authority the Spring Security authority string
     *                  ({@code "ROLE_ADMIN"} or {@code "ROLE_USER"})
     */
    UserRole(String code, String authority) {
        this.code = code;
        this.authority = authority;
    }

    /**
     * Returns the legacy single-character user-type code for this role.
     *
     * @return {@code "A"} for {@link #ADMIN} or {@code "U"} for {@link #USER}
     */
    public String getCode() {
        return code;
    }

    /**
     * Returns the Spring Security authority string for this role.
     *
     * @return {@code "ROLE_ADMIN"} for {@link #ADMIN} or {@code "ROLE_USER"} for
     *         {@link #USER}
     */
    public String getAuthority() {
        return authority;
    }

    /**
     * Adapts this role to a Spring Security {@link GrantedAuthority} carrying the
     * role's {@link #getAuthority() authority string}. A fresh instance is
     * returned on each invocation so callers may freely aggregate the result into
     * their own collections.
     *
     * @return a {@link SimpleGrantedAuthority} for this role's authority string
     */
    public GrantedAuthority toGrantedAuthority() {
        return new SimpleGrantedAuthority(authority);
    }

    /**
     * Maps a raw {@code sec_usr_type} value to a {@link UserRole}, reproducing the
     * COBOL sign-on role branch in {@code app/cbl/COSGN00C.cbl}
     * ({@code IF CDEMO-USRTYP-ADMIN ... ELSE ...}).
     *
     * <p>Only {@code 'A'} maps to {@link #ADMIN}; every other value &mdash;
     * including {@code 'U'}, blanks, {@code null}, or any unknown code &mdash; maps
     * to {@link #USER}, reproducing the legacy semantics in which only the explicit
     * admin code is privileged and all other values fall through the {@code ELSE}
     * branch to the ordinary user path. The comparison trims surrounding
     * whitespace and is case-insensitive, mirroring COSGN00C's use of
     * {@code FUNCTION UPPER-CASE} on the operator's sign-on input.</p>
     *
     * @param userType the raw user-type value (may be {@code null}, blank, or
     *                  padded); typically the {@code user_security.sec_usr_type}
     *                  column value
     * @return {@link #ADMIN} when {@code userType} normalizes to {@code "A"};
     *         {@link #USER} otherwise (never {@code null})
     */
    public static UserRole fromUserType(String userType) {
        if (userType == null) {
            // Safe non-admin default; matches the ELSE fall-through in COSGN00C.
            return USER;
        }
        String normalized = userType.trim().toUpperCase(Locale.ROOT);
        // ADMIN.code is the single source of truth for the admin indicator ('A').
        if (ADMIN.code.equals(normalized)) {
            return ADMIN;
        }
        return USER;
    }
}
