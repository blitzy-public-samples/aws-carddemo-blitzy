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
package com.aws.carddemo.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.util.Objects;

/**
 * JPA entity for an application user / security record.
 *
 * <p>Migrated from the legacy COBOL copybook {@code CSUSR01Y.cpy} group item
 * {@code SEC-USER-DATA} (record length 80, VSAM {@code USRSEC.VSAM.KSDS},
 * {@code KEYLEN=8}) to the relational table {@code user_security}. Field order
 * and semantics are preserved one-for-one from the copybook so the behavioral
 * contract of the online sign-on flow (program {@code COSGN00C}) and the user
 * administration screens ({@code COUSR00C}–{@code COUSR03C}) is unchanged.</p>
 *
 * <p>Copybook-to-column mapping (the {@code SEC-USR-FILLER PIC X(23)} padding
 * field carries no data and is intentionally not mapped):</p>
 * <table>
 *   <caption>SEC-USER-DATA to user_security mapping</caption>
 *   <tr><th>COBOL field</th><th>PIC</th><th>Column</th><th>Type</th></tr>
 *   <tr><td>SEC-USR-ID</td><td>X(08)</td><td>sec_usr_id</td><td>VARCHAR(8) PK</td></tr>
 *   <tr><td>SEC-USR-FNAME</td><td>X(20)</td><td>sec_usr_fname</td><td>VARCHAR(20)</td></tr>
 *   <tr><td>SEC-USR-LNAME</td><td>X(20)</td><td>sec_usr_lname</td><td>VARCHAR(20)</td></tr>
 *   <tr><td>SEC-USR-PWD</td><td>X(08)</td><td>sec_usr_pwd</td><td>VARCHAR(255)</td></tr>
 *   <tr><td>SEC-USR-TYPE</td><td>X(01)</td><td>sec_usr_type</td><td>VARCHAR(1)</td></tr>
 *   <tr><td>SEC-USR-FILLER</td><td>X(23)</td><td>(not mapped)</td><td>&mdash;</td></tr>
 * </table>
 *
 * <p><strong>Role semantics.</strong> {@code sec_usr_type} reproduces the
 * COBOL role condition names: {@code 'A'} designates an administrator and
 * {@code 'U'} designates a standard user. This value drives authentication and
 * authorization through the security layer's {@code UserDetailsService}.</p>
 *
 * <p><strong>Security hardening (documented deviation).</strong> The legacy
 * record stored an 8-character plaintext password. The migrated column
 * {@code sec_usr_pwd} is widened to {@code VARCHAR(255)} so it can hold a
 * modern one-way password hash (for example, BCrypt) instead of plaintext.
 * This is an intentional improvement recorded in the decision log, not a
 * behavioral regression. The password is <em>sensitive</em>: it is deliberately
 * excluded from {@link #toString()} and must never be written to logs.</p>
 *
 * <p><strong>Concurrency.</strong> User records are updated by the online
 * administration screens. A JPA {@link Version optimistic-lock} column
 * reproduces the integrity of the legacy READ&#45;UPDATE&#45;REWRITE cycle and
 * is likewise recorded as an intentional improvement in the decision log.</p>
 *
 * <p><strong>Schema ownership.</strong> The physical schema is owned by the
 * Flyway migration {@code V1__schema.sql}; Hibernate is configured with
 * {@code ddl-auto: validate} and only verifies this mapping against the table.
 * The column names, lengths, and nullability declared here must therefore match
 * that migration exactly.</p>
 */
@Entity
@Table(name = "user_security")
public class UserSecurity {

    /**
     * Primary key. Maps {@code SEC-USR-ID PIC X(08)} to {@code sec_usr_id}.
     * This is a natural (application-assigned) key; no {@code @GeneratedValue}
     * strategy is applied because the identifier is supplied by the caller,
     * matching the legacy VSAM key ({@code KEYLEN=8}).
     */
    @Id
    @Column(name = "sec_usr_id", length = 8, nullable = false)
    private String secUsrId;

    /**
     * User first name. Maps {@code SEC-USR-FNAME PIC X(20)} to
     * {@code sec_usr_fname}.
     */
    @Column(name = "sec_usr_fname", length = 20)
    private String secUsrFname;

    /**
     * User last name. Maps {@code SEC-USR-LNAME PIC X(20)} to
     * {@code sec_usr_lname}.
     */
    @Column(name = "sec_usr_lname", length = 20)
    private String secUsrLname;

    /**
     * User password. Maps {@code SEC-USR-PWD PIC X(08)} to {@code sec_usr_pwd},
     * widened to {@code VARCHAR(255)} to store a hashed credential.
     *
     * <p><strong>Sensitive:</strong> never log this value and never include it
     * in {@link #toString()}. The getter exists solely so the security layer
     * can compare the stored hash during authentication.</p>
     */
    @Column(name = "sec_usr_pwd", length = 255)
    private String secUsrPwd;

    /**
     * User role/type. Maps {@code SEC-USR-TYPE PIC X(01)} to
     * {@code sec_usr_type}. Legal values are {@code 'A'} (administrator) and
     * {@code 'U'} (standard user).
     */
    @Column(name = "sec_usr_type", length = 1)
    private String secUsrType;

    /**
     * Optimistic-locking version counter, managed by the persistence provider.
     * Reproduces the integrity of the legacy READ&#45;UPDATE&#45;REWRITE cycle
     * for online updates. Not derived from any copybook field.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /**
     * Protected no-argument constructor required by the JPA specification.
     * Application code should prefer {@link #UserSecurity(String, String, String, String, String)}.
     */
    protected UserSecurity() {
        // Required by JPA; intentionally empty.
    }

    /**
     * Convenience constructor for the business fields of a user record. The
     * optimistic-lock {@code version} is intentionally omitted because it is
     * assigned and maintained by the persistence provider.
     *
     * <p>Fields are assigned directly (setters are not invoked) so the
     * constructor does not expose a partially-initialized instance.</p>
     *
     * @param secUsrId    the primary-key user id (up to 8 characters)
     * @param secUsrFname the user first name (up to 20 characters)
     * @param secUsrLname the user last name (up to 20 characters)
     * @param secUsrPwd   the (hashed) password; sensitive, never logged
     * @param secUsrType  the role indicator: {@code 'A'} admin or {@code 'U'} user
     */
    public UserSecurity(String secUsrId,
                        String secUsrFname,
                        String secUsrLname,
                        String secUsrPwd,
                        String secUsrType) {
        this.secUsrId = secUsrId;
        this.secUsrFname = secUsrFname;
        this.secUsrLname = secUsrLname;
        this.secUsrPwd = secUsrPwd;
        this.secUsrType = secUsrType;
    }

    /**
     * Returns the primary-key user id.
     *
     * @return the user id
     */
    public String getSecUsrId() {
        return secUsrId;
    }

    /**
     * Sets the primary-key user id.
     *
     * @param secUsrId the user id to set
     */
    public void setSecUsrId(String secUsrId) {
        this.secUsrId = secUsrId;
    }

    /**
     * Returns the user first name.
     *
     * @return the first name
     */
    public String getSecUsrFname() {
        return secUsrFname;
    }

    /**
     * Sets the user first name.
     *
     * @param secUsrFname the first name to set
     */
    public void setSecUsrFname(String secUsrFname) {
        this.secUsrFname = secUsrFname;
    }

    /**
     * Returns the user last name.
     *
     * @return the last name
     */
    public String getSecUsrLname() {
        return secUsrLname;
    }

    /**
     * Sets the user last name.
     *
     * @param secUsrLname the last name to set
     */
    public void setSecUsrLname(String secUsrLname) {
        this.secUsrLname = secUsrLname;
    }

    /**
     * Returns the (hashed) password.
     *
     * <p><strong>Sensitive:</strong> the returned value must never be logged.
     * It is exposed only so the security layer can verify credentials.</p>
     *
     * @return the stored password hash
     */
    public String getSecUsrPwd() {
        return secUsrPwd;
    }

    /**
     * Sets the (hashed) password. Callers are responsible for supplying an
     * already-hashed value; the raw credential must never be persisted.
     *
     * @param secUsrPwd the password hash to set
     */
    public void setSecUsrPwd(String secUsrPwd) {
        this.secUsrPwd = secUsrPwd;
    }

    /**
     * Returns the user role/type ({@code 'A'} admin or {@code 'U'} user).
     *
     * @return the role indicator
     */
    public String getSecUsrType() {
        return secUsrType;
    }

    /**
     * Sets the user role/type ({@code 'A'} admin or {@code 'U'} user).
     *
     * @param secUsrType the role indicator to set
     */
    public void setSecUsrType(String secUsrType) {
        this.secUsrType = secUsrType;
    }

    /**
     * Returns the optimistic-locking version counter.
     *
     * @return the version, or {@code null} for a never-persisted instance
     */
    public Long getVersion() {
        return version;
    }

    /**
     * Sets the optimistic-locking version counter. Normally managed by the
     * persistence provider; exposed for testing and detached-entity merges.
     *
     * @param version the version to set
     */
    public void setVersion(Long version) {
        this.version = version;
    }

    /**
     * Entity equality is based on the primary key {@code secUsrId}, matching
     * the legacy VSAM unique-key identity of a user record.
     *
     * @param other the object to compare with
     * @return {@code true} if {@code other} is a {@code UserSecurity} with the
     *         same {@code secUsrId}
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || getClass() != other.getClass()) {
            return false;
        }
        UserSecurity that = (UserSecurity) other;
        return Objects.equals(secUsrId, that.secUsrId);
    }

    /**
     * Hash code derived from the primary key {@code secUsrId}, consistent with
     * {@link #equals(Object)}.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(secUsrId);
    }

    /**
     * Returns a diagnostic representation of this record.
     *
     * <p>The sensitive {@code secUsrPwd} field is deliberately excluded so the
     * password is never leaked through logs or diagnostics.</p>
     *
     * @return a string representation without the password
     */
    @Override
    public String toString() {
        return "UserSecurity{"
                + "secUsrId='" + secUsrId + '\''
                + ", secUsrFname='" + secUsrFname + '\''
                + ", secUsrLname='" + secUsrLname + '\''
                + ", secUsrType='" + secUsrType + '\''
                + ", version=" + version
                + '}';
    }
}
