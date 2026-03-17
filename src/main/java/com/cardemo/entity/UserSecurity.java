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
package com.cardemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.cardemo.common.enums.UserType;

import java.util.Objects;

/**
 * JPA entity representing a user security record in the CardDemo application.
 *
 * <p>Maps the VSAM USRSEC KSDS dataset (80-byte records) defined in COBOL
 * copybook {@code CSUSR01Y.cpy}. This entity stores user credentials and role
 * assignments for the CardDemo application's authentication and authorization
 * system.</p>
 *
 * <h3>COBOL Record Layout (CSUSR01Y.cpy SEC-USER-DATA, 80 bytes):</h3>
 * <pre>
 *   01  SEC-USER-DATA.
 *       05  SEC-USR-ID      PIC X(08).  → userId    (String, 8 chars, PK)
 *       05  SEC-USR-FNAME   PIC X(20).  → firstName (String, 20 chars)
 *       05  SEC-USR-LNAME   PIC X(20).  → lastName  (String, 20 chars)
 *       05  SEC-USR-PWD     PIC X(08).  → password  (String, 72 chars — BCrypt)
 *       05  SEC-USR-TYPE    PIC X(01).  → userType  (UserType enum)
 *       05  SEC-USR-FILLER  PIC X(23).  → not mapped (padding)
 *   Total: 8 + 20 + 20 + 8 + 1 + 23 = 80 bytes
 * </pre>
 *
 * <h3>Security Migration Notes:</h3>
 * <ul>
 *   <li><strong>Password hashing:</strong> In the original COBOL application, passwords
 *       were stored as 8-character plaintext in {@code SEC-USR-PWD}. In this Java
 *       migration, passwords are BCrypt-hashed (60-character output, stored in a
 *       72-character column for future algorithm flexibility). The seed data loader
 *       pre-hashes the original plaintext passwords during database initialization.</li>
 *   <li><strong>User type mapping:</strong> The {@code SEC-USR-TYPE PIC X(01)} field
 *       maps to the {@link UserType} enum via a JPA {@link UserTypeConverter}
 *       that persists the single-character codes ({@code 'A'} and {@code 'U'})
 *       matching the original COBOL storage format. Valid values correspond to
 *       the COBOL 88-level conditions defined in {@code COCOM01Y.cpy}:
 *       {@link UserType#ADMIN ADMIN} ({@code 'A'}) and
 *       {@link UserType#USER USER} ({@code 'U'}).</li>
 * </ul>
 *
 * <h3>Referenced by COBOL programs:</h3>
 * <ul>
 *   <li>{@code COSGN00C.cbl} — Sign-on authentication (READ by SEC-USR-ID)</li>
 *   <li>{@code COUSR00C.cbl} — User list (STARTBR/READNEXT for paginated browse)</li>
 *   <li>{@code COUSR01C.cbl} — User add (WRITE new record)</li>
 *   <li>{@code COUSR02C.cbl} — User update (READ UPDATE/REWRITE)</li>
 *   <li>{@code COUSR03C.cbl} — User delete (READ/DELETE)</li>
 * </ul>
 *
 * @see UserType
 * @see com.cardemo.common.dto.UserSecurityRecord
 */
@Entity
@Table(name = "user_security")
public class UserSecurity {

    /**
     * User identifier — primary key.
     *
     * <p>Maps to COBOL field {@code SEC-USR-ID PIC X(08)} from
     * {@code CSUSR01Y.cpy}. This is the VSAM KSDS primary key used
     * for keyed access in all user security operations (sign-on via
     * {@code COSGN00C}, admin CRUD via {@code COUSR00C–COUSR03C}).</p>
     */
    @Id
    @Column(name = "sec_usr_id", length = 8, nullable = false)
    private String userId;

    /**
     * User first name.
     *
     * <p>Maps to COBOL field {@code SEC-USR-FNAME PIC X(20)} from
     * {@code CSUSR01Y.cpy}. Displayed in user management screens
     * ({@code COUSR00C} user list, {@code COUSR02C} user update).</p>
     */
    @Column(name = "sec_usr_fname", length = 20)
    private String firstName;

    /**
     * User last name.
     *
     * <p>Maps to COBOL field {@code SEC-USR-LNAME PIC X(20)} from
     * {@code CSUSR01Y.cpy}. Displayed in user management screens
     * ({@code COUSR00C} user list, {@code COUSR02C} user update).</p>
     */
    @Column(name = "sec_usr_lname", length = 20)
    private String lastName;

    /**
     * BCrypt-hashed password.
     *
     * <p>Maps to COBOL field {@code SEC-USR-PWD PIC X(08)} from
     * {@code CSUSR01Y.cpy}, but expanded from 8 characters (plaintext)
     * to 72 characters to accommodate BCrypt hash output (typically 60
     * characters, e.g., {@code $2a$10$...}). The additional buffer
     * allows for future hashing algorithm changes without schema
     * migration.</p>
     *
     * <p><strong>Security note:</strong> In the COBOL source,
     * {@code COSGN00C.cbl} compared passwords via
     * {@code IF SEC-USR-PWD = WS-USER-PWD} (plaintext comparison).
     * In Java, comparison uses
     * {@code BCryptPasswordEncoder.matches(rawPassword, encodedPassword)}.
     * This field must never be logged, serialized to REST responses,
     * or included in {@link #toString()} output.</p>
     */
    @Column(name = "sec_usr_pwd", length = 72, nullable = false)
    private String password;

    /**
     * User type indicating the user's role in the application.
     *
     * <p>Maps to COBOL field {@code SEC-USR-TYPE PIC X(01)} from
     * {@code CSUSR01Y.cpy}. The original single-character codes correspond
     * to the 88-level conditions in {@code COCOM01Y.cpy}:</p>
     * <ul>
     *   <li>{@link UserType#ADMIN} — {@code CDEMO-USRTYP-ADMIN VALUE 'A'}:
     *       administrators with access to user management functions</li>
     *   <li>{@link UserType#USER} — {@code CDEMO-USRTYP-USER VALUE 'U'}:
     *       regular users with access to standard card operations</li>
     * </ul>
     *
     * <p>Persisted as a single-character code ({@code 'A'} for ADMIN,
     * {@code 'U'} for USER) via a JPA {@link UserTypeConverter}, matching
     * the original COBOL {@code PIC X(01)} storage format.</p>
     */
    @Convert(converter = UserTypeConverter.class)
    @Column(name = "sec_usr_type", length = 1, nullable = false)
    private UserType userType;

    /**
     * Default no-argument constructor required by JPA specification.
     *
     * <p>Access level is {@code protected} to discourage direct
     * instantiation by application code while satisfying the JPA
     * provider's proxy/reflection requirements.</p>
     */
    protected UserSecurity() {
        // Required by JPA — no initialization needed
    }

    /**
     * Constructs a new {@code UserSecurity} entity with all business fields.
     *
     * @param userId    the user identifier (max 8 characters, primary key);
     *                  corresponds to {@code SEC-USR-ID PIC X(08)}
     * @param firstName the user's first name (max 20 characters);
     *                  corresponds to {@code SEC-USR-FNAME PIC X(20)}
     * @param lastName  the user's last name (max 20 characters);
     *                  corresponds to {@code SEC-USR-LNAME PIC X(20)}
     * @param password  the BCrypt-hashed password (max 72 characters);
     *                  corresponds to {@code SEC-USR-PWD PIC X(08)} expanded
     *                  for BCrypt storage
     * @param userType  the user role; must be {@link UserType#ADMIN} or
     *                  {@link UserType#USER}
     */
    public UserSecurity(String userId, String firstName, String lastName,
                        String password, UserType userType) {
        this.userId = userId;
        this.firstName = firstName;
        this.lastName = lastName;
        this.password = password;
        this.userType = userType;
    }

    /**
     * Returns the user identifier (primary key).
     *
     * @return the user ID, up to 8 characters
     */
    public String getUserId() {
        return userId;
    }

    /**
     * Sets the user identifier.
     *
     * @param userId the user ID to set (max 8 characters)
     */
    public void setUserId(String userId) {
        this.userId = userId;
    }

    /**
     * Returns the user's first name.
     *
     * @return the first name, up to 20 characters
     */
    public String getFirstName() {
        return firstName;
    }

    /**
     * Sets the user's first name.
     *
     * @param firstName the first name to set (max 20 characters)
     */
    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    /**
     * Returns the user's last name.
     *
     * @return the last name, up to 20 characters
     */
    public String getLastName() {
        return lastName;
    }

    /**
     * Sets the user's last name.
     *
     * @param lastName the last name to set (max 20 characters)
     */
    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    /**
     * Returns the BCrypt-hashed password.
     *
     * <p><strong>Security warning:</strong> The returned value is the
     * BCrypt hash, not the plaintext password. It should never be
     * logged or exposed in API responses.</p>
     *
     * @return the BCrypt-hashed password string
     */
    public String getPassword() {
        return password;
    }

    /**
     * Sets the BCrypt-hashed password.
     *
     * <p>Callers must ensure the value is a properly encoded BCrypt
     * hash (e.g., via {@code BCryptPasswordEncoder.encode()}) before
     * calling this method. Plaintext passwords must never be stored.</p>
     *
     * @param password the BCrypt-hashed password to set
     */
    public void setPassword(String password) {
        this.password = password;
    }

    /**
     * Returns the user type (role).
     *
     * @return the {@link UserType} enum value indicating the user's role
     */
    public UserType getUserType() {
        return userType;
    }

    /**
     * Sets the user type (role).
     *
     * @param userType the {@link UserType} to assign
     */
    public void setUserType(UserType userType) {
        this.userType = userType;
    }

    /**
     * Checks equality based on the user ID primary key ({@code userId}).
     *
     * <p>Follows JPA entity best practices: identity is determined by the
     * business key (VSAM KSDS primary key {@code SEC-USR-ID}), not by
     * object reference or all-field comparison. This ensures correct
     * behavior in JPA collections and caches.</p>
     *
     * @param obj the object to compare with
     * @return {@code true} if the other object is a {@code UserSecurity}
     *         with the same {@code userId}
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        UserSecurity that = (UserSecurity) obj;
        return Objects.equals(userId, that.userId);
    }

    /**
     * Computes hash code based on the user ID primary key.
     *
     * <p>Consistent with {@link #equals(Object)}: uses only the
     * {@code userId} field to ensure the hash code / equals contract
     * is maintained across JPA entity lifecycle states.</p>
     *
     * @return hash code derived from the user ID
     */
    @Override
    public int hashCode() {
        return Objects.hash(userId);
    }

    /**
     * Returns a string representation of this user security record.
     *
     * <p><strong>Security:</strong> The password field is deliberately
     * excluded from this output to prevent credential leakage in logs,
     * debug output, or exception messages. Only the user ID, name
     * fields, and user type are included.</p>
     *
     * @return a string containing all non-sensitive fields
     */
    @Override
    public String toString() {
        return "UserSecurity{"
                + "userId='" + userId + '\''
                + ", firstName='" + firstName + '\''
                + ", lastName='" + lastName + '\''
                + ", userType=" + userType
                + '}';
    }
}
