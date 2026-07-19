/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA entity for the CardDemo signon / authorization user store.
 *
 * <p><strong>Origin (traceability, AAP &sect;0.6.10):</strong> migrated one-for-one from the
 * legacy COBOL copybook {@code legacy/cpy/CSUSR01Y.cpy} ({@code 01 SEC-USER-DATA}, fixed record
 * length {@code RECLN 80}), which described the layout of the VSAM KSDS {@code USRSEC}. Each
 * {@code 05}-level field of the copybook maps to a persisted column here, except the trailing
 * {@code SEC-USR-FILLER PIC X(23)} padding, which is intentionally not persisted. The COBOL
 * {@code SEC-} field-name prefix is dropped in the Java field and column names (AAP &sect;0.6.2),
 * so {@code SEC-USR-ID} becomes {@code usrId} / column {@code usr_id}, and so on.</p>
 *
 * <p><strong>Record layout parity (80 bytes):</strong></p>
 * <pre>
 *   COBOL field        PIC      Java field   Column      Bytes
 *   SEC-USR-ID         X(08)    usrId        usr_id       8
 *   SEC-USR-FNAME      X(20)    usrFname     usr_fname   20
 *   SEC-USR-LNAME      X(20)    usrLname     usr_lname   20
 *   SEC-USR-PWD        X(08)    usrPwd       usr_pwd      8
 *   SEC-USR-TYPE       X(01)    usrType      usr_type     1
 *   SEC-USR-FILLER     X(23)    (not persisted)          23
 *                                                       ----
 *                                            total       80
 * </pre>
 *
 * <p><strong>Persistence:</strong> mapped to table {@code user_security}. The table name uses the
 * {@code user_security} form deliberately to avoid the SQL reserved word {@code user}. The primary
 * key is {@code usr_id} ({@code SEC-USR-ID}), preserving the VSAM KSDS key semantics exactly. The
 * corresponding Flyway DDL declares the columns as fixed-width {@code CHAR(n)} to retain the
 * legacy trailing-space semantics; seed rows are loaded from the {@code USRSEC} ASCII fixture.</p>
 *
 * <p><strong>Authentication / authorization:</strong> this entity backs Spring Security. The
 * {@code CardDemoUserDetailsService} loads a row by user id and grants {@code ROLE_ADMIN} when
 * {@link #getUsrType() usrType} is {@code "A"} (administrator) or {@code ROLE_USER} when it is
 * {@code "U"} (regular user), reproducing the legacy signon program's {@code XCTL} to the admin or
 * main menu (AAP &sect;0.6.7).</p>
 *
 * <p><strong>Cleartext password parity (AAP &sect;0.6.7 &mdash; intentional):</strong> the legacy
 * {@code USRSEC} store held the password as cleartext and the COBOL signon program compared it
 * directly, so {@link #getUsrPwd() usrPwd} preserves that behavior for 100% functional parity.
 * Introducing password hashing (e.g. BCrypt/PBKDF2) is deliberately out of scope here and is
 * recorded as a suggested next task in {@code docs/decision-log.md}; it is not silently changed.
 * The "no hardcoded credentials" rule applies to source and configuration, not to this seeded data
 * column. As a security-hygiene safeguard, the password is never emitted by {@link #toString()} and must
 * never be written to logs.</p>
 */
@Entity
@Table(name = "user_security")
public class UserSecurity {

    /**
     * User id and primary key. Origin {@code SEC-USR-ID PIC X(08)} (8 bytes); the VSAM KSDS key.
     */
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "usr_id", length = 8)
    private String usrId;

    /**
     * User first name. Origin {@code SEC-USR-FNAME PIC X(20)} (20 bytes).
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "usr_fname", length = 20)
    private String usrFname;

    /**
     * User last name. Origin {@code SEC-USR-LNAME PIC X(20)} (20 bytes).
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "usr_lname", length = 20)
    private String usrLname;

    /**
     * User password, stored as cleartext for parity with the legacy {@code USRSEC} store. Origin
     * {@code SEC-USR-PWD PIC X(08)} (8 bytes). Never logged and never emitted by {@link #toString()}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "usr_pwd", length = 8)
    private String usrPwd;

    /**
     * User type / role flag. Origin {@code SEC-USR-TYPE PIC X(01)} (1 byte). {@code "A"} maps to
     * {@code ROLE_ADMIN} and {@code "U"} maps to {@code ROLE_USER} in Spring Security.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "usr_type", length = 1)
    private String usrType;

    /**
     * Creates an empty {@code UserSecurity}. Required by JPA for entity instantiation.
     */
    public UserSecurity() {
        // No-argument constructor required by the JPA specification.
    }

    /**
     * Returns the user id (primary key). Origin {@code SEC-USR-ID}.
     *
     * @return the user id, or {@code null} if unset
     */
    public String getUsrId() {
        return usrId;
    }

    /**
     * Sets the user id (primary key). Origin {@code SEC-USR-ID}.
     *
     * @param usrId the user id to set
     */
    public void setUsrId(String usrId) {
        this.usrId = usrId;
    }

    /**
     * Returns the user first name. Origin {@code SEC-USR-FNAME}.
     *
     * @return the first name, or {@code null} if unset
     */
    public String getUsrFname() {
        return usrFname;
    }

    /**
     * Sets the user first name. Origin {@code SEC-USR-FNAME}.
     *
     * @param usrFname the first name to set
     */
    public void setUsrFname(String usrFname) {
        this.usrFname = usrFname;
    }

    /**
     * Returns the user last name. Origin {@code SEC-USR-LNAME}.
     *
     * @return the last name, or {@code null} if unset
     */
    public String getUsrLname() {
        return usrLname;
    }

    /**
     * Sets the user last name. Origin {@code SEC-USR-LNAME}.
     *
     * @param usrLname the last name to set
     */
    public void setUsrLname(String usrLname) {
        this.usrLname = usrLname;
    }

    /**
     * Returns the cleartext password. Origin {@code SEC-USR-PWD}. Handle with care: this value must
     * never be logged. See the class-level note on cleartext-password parity.
     *
     * @return the cleartext password, or {@code null} if unset
     */
    public String getUsrPwd() {
        return usrPwd;
    }

    /**
     * Sets the cleartext password. Origin {@code SEC-USR-PWD}. See the class-level note on
     * cleartext-password parity.
     *
     * @param usrPwd the cleartext password to set
     */
    public void setUsrPwd(String usrPwd) {
        this.usrPwd = usrPwd;
    }

    /**
     * Returns the user type flag. Origin {@code SEC-USR-TYPE}. {@code "A"} = admin, {@code "U"} = user.
     *
     * @return the user type, or {@code null} if unset
     */
    public String getUsrType() {
        return usrType;
    }

    /**
     * Sets the user type flag. Origin {@code SEC-USR-TYPE}. {@code "A"} = admin, {@code "U"} = user.
     *
     * @param usrType the user type to set
     */
    public void setUsrType(String usrType) {
        this.usrType = usrType;
    }

    /**
     * Compares two {@code UserSecurity} instances by their non-null primary key
     * ({@link #getUsrId() usrId}), mirroring the VSAM KSDS key identity. Uses an {@code instanceof}
     * check so a Hibernate proxy compares equal to its underlying entity, and treats an instance
     * with a {@code null} id as not equal to any other instance (including other unsaved instances),
     * so distinct transient rows are never collapsed (review finding F10).
     *
     * @param o the object to compare with
     * @return {@code true} if {@code o} is a {@code UserSecurity} with an equal non-null {@code usrId}
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UserSecurity other)) {
            return false;
        }
        return usrId != null && usrId.equals(other.usrId);
    }

    /**
     * Returns a constant, identity-stable hash code. A constant (rather than one derived from
     * {@code usrId}) is used so the hash does not change when the primary key is assigned, keeping
     * instances locatable in hash-based collections and consistent with {@link #equals(Object)}
     * (review finding F10).
     *
     * @return a stable, class-level hash code
     */
    @Override
    public int hashCode() {
        return UserSecurity.class.hashCode();
    }

    /**
     * Returns a non-sensitive diagnostic representation containing only the class name and an opaque
     * per-instance identity token. The user id, first/last names, user type and (cleartext) password
     * are deliberately never emitted — not even partially masked — so that credentials and user
     * identity cannot leak into logs or error messages (CWE-532; review finding F9).
     *
     * @return a non-sensitive string representation
     */
    @Override
    public String toString() {
        return "UserSecurity@" + Integer.toHexString(System.identityHashCode(this));
    }
}
