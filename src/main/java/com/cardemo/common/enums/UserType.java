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
package com.cardemo.common.enums;

/**
 * Enumerates the two user types defined in the CardDemo application.
 *
 * <p>This enum is the Java translation of the COBOL 88-level conditions on
 * {@code CDEMO-USER-TYPE PIC X(01)} found in copybook {@code COCOM01Y.cpy}
 * (lines 26–28):</p>
 *
 * <pre>
 *   10 CDEMO-USER-TYPE               PIC X(01).
 *      88 CDEMO-USRTYP-ADMIN         VALUE 'A'.
 *      88 CDEMO-USRTYP-USER          VALUE 'U'.
 * </pre>
 *
 * <p>The single-character {@code code} field preserves the original COBOL
 * {@code PIC X(01)} semantics. This enum is the foundational authorization
 * type used throughout the application:</p>
 * <ul>
 *   <li>{@code CardDemoContext.userType} — carries the logged-in user's role</li>
 *   <li>{@code UserSecurity.secUsrType} — persists each user's role in the database</li>
 *   <li>{@code SecurityConfig} — maps {@code ADMIN} to {@code ROLE_ADMIN} for Spring Security</li>
 *   <li>All admin-only services gate access on {@code UserType.ADMIN}</li>
 *   <li>{@code SignonService} sets the user type during authentication</li>
 * </ul>
 *
 * @see com.cardemo.common.context.CardDemoContext
 * @see com.cardemo.entity.UserSecurity
 */
public enum UserType {

    /**
     * Administrator user type.
     * <p>Maps to COBOL 88-level condition {@code CDEMO-USRTYP-ADMIN VALUE 'A'}.
     * Administrators have access to the admin menu and user management functions
     * (list, add, update, delete users).</p>
     */
    ADMIN('A'),

    /**
     * Regular (non-admin) user type.
     * <p>Maps to COBOL 88-level condition {@code CDEMO-USRTYP-USER VALUE 'U'}.
     * Regular users can access account views, card operations, transactions,
     * reports, and bill payment — but not user administration.</p>
     */
    USER('U');

    /**
     * The single-character code corresponding to the COBOL {@code PIC X(01)} value.
     * <ul>
     *   <li>{@code 'A'} — Administrator</li>
     *   <li>{@code 'U'} — Regular user</li>
     * </ul>
     */
    private final char code;

    /**
     * Constructs a {@code UserType} constant with its single-character COBOL code.
     *
     * @param code the single-character value from {@code CDEMO-USER-TYPE PIC X(01)}
     */
    UserType(char code) {
        this.code = code;
    }

    /**
     * Returns the single-character COBOL code for this user type.
     *
     * <p>The returned value matches the original COBOL {@code PIC X(01)} field
     * exactly: {@code 'A'} for {@link #ADMIN}, {@code 'U'} for {@link #USER}.</p>
     *
     * @return the single-character code ({@code 'A'} or {@code 'U'})
     */
    public char getCode() {
        return code;
    }

    /**
     * Resolves a {@code UserType} from the given single-character COBOL code.
     *
     * <p>This is the reverse-lookup counterpart of {@link #getCode()}. It iterates
     * through the enum constants and returns the one whose {@code code} matches.
     * The comparison uses primitive {@code char} equality ({@code ==}), consistent
     * with the COBOL {@code PIC X(01)} single-character semantics.</p>
     *
     * @param code the single-character code to look up ({@code 'A'} or {@code 'U'})
     * @return the matching {@code UserType} constant
     * @throws IllegalArgumentException if no constant matches the given code
     */
    public static UserType fromCode(char code) {
        for (UserType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown user type code: " + code);
    }

    /**
     * Returns a human-readable representation including the enum name and its
     * single-character COBOL code.
     *
     * @return string in the format {@code "NAME(code)"}, e.g. {@code "ADMIN(A)"}
     */
    @Override
    public String toString() {
        return name() + "(" + code + ")";
    }
}
