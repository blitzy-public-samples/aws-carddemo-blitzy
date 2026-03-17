/*
 * UserSecurity.java — JPA Entity mapping VSAM USRSEC (80-byte KSDS record)
 *
 * Source: app/cpy/CSUSR01Y.cpy (SEC-USER-DATA)
 * VSAM Dataset: AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS
 * Record Length: 80 bytes
 * Primary Key: SEC-USR-ID PIC X(08) — 8-character user identifier
 *
 * COBOL Record Layout (CSUSR01Y.cpy):
 *   01  SEC-USER-DATA.
 *       05  SEC-USR-ID                 PIC X(08).   -> secUsrId (String, 8 chars, PK)
 *       05  SEC-USR-FNAME              PIC X(20).   -> secUsrFname (String, 20 chars)
 *       05  SEC-USR-LNAME              PIC X(20).   -> secUsrLname (String, 20 chars)
 *       05  SEC-USR-PWD                PIC X(08).   -> secUsrPwd (String, 72 chars — expanded for BCrypt)
 *       05  SEC-USR-TYPE               PIC X(01).   -> secUsrType (String, 1 char: 'A' or 'U')
 *       05  SEC-USR-FILLER             PIC X(23).   -> not mapped (padding only)
 *   Total: 8+20+20+8+1+23 = 80 bytes
 *
 * Migration Notes:
 * - SEC-USR-PWD is expanded from 8 chars (plaintext) to 72 chars (BCrypt hash)
 *   per AAP security requirements. Passwords are BCrypt-hashed, not stored in plaintext.
 * - SEC-USR-TYPE maps to the UserType enum ('A' = ADMIN, 'U' = USER), corresponding
 *   to COBOL 88-level conditions CDEMO-USRTYP-ADMIN/CDEMO-USRTYP-USER in COCOM01Y.cpy.
 * - No @Version optimistic locking — the COBOL user security file did not use
 *   READ UPDATE → REWRITE patterns for concurrent access (admin-only single-user updates).
 *
 * Referenced by:
 * - COSGN00C.cbl (Sign-on authentication — READ by SEC-USR-ID)
 * - COUSR00C.cbl (User list — STARTBR/READNEXT for paginated browse)
 * - COUSR01C.cbl (User add — WRITE new record)
 * - COUSR02C.cbl (User update — READ UPDATE/REWRITE)
 * - COUSR03C.cbl (User delete — READ/DELETE)
 *
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package com.cardemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;

/**
 * JPA entity representing a user security record.
 *
 * <p>Maps the VSAM USRSEC KSDS dataset (80-byte records) defined in COBOL
 * copybook CSUSR01Y.cpy. This entity stores user credentials and role
 * assignments for the CardDemo application's authentication and authorization
 * system.</p>
 *
 * <p>In the original COBOL application, passwords were stored as 8-character
 * plaintext in SEC-USR-PWD. In the Java migration, passwords are BCrypt-hashed
 * (60-character output, stored in a 72-character column for future algorithm
 * flexibility). The seed data loader (V100__seed_data.sql) pre-hashes the
 * original plaintext passwords.</p>
 *
 * <p>The user type field ({@code secUsrType}) carries 'A' for Admin or 'U' for
 * Regular User, matching the COBOL 88-level conditions
 * {@code CDEMO-USRTYP-ADMIN VALUE 'A'} and {@code CDEMO-USRTYP-USER VALUE 'U'}
 * defined in COCOM01Y.cpy. This maps to the {@link com.cardemo.common.enums.UserType}
 * enum in the service layer.</p>
 *
 * @see com.cardemo.common.enums.UserType
 * @see com.cardemo.common.dto.UserSecurityRecord
 */
@Entity
@Table(name = "user_security")
public class UserSecurity {

    /**
     * User identifier — primary key.
     * <p>Maps to COBOL field {@code SEC-USR-ID PIC X(08)} from CSUSR01Y.cpy.
     * This is the VSAM KSDS primary key used for keyed access in all user
     * security operations (COSGN00C sign-on, COUSR00C-03C admin CRUD).</p>
     */
    @Id
    @Column(name = "sec_usr_id", length = 8, nullable = false)
    private String secUsrId;

    /**
     * User first name.
     * <p>Maps to COBOL field {@code SEC-USR-FNAME PIC X(20)} from CSUSR01Y.cpy.
     * Displayed in user management screens (COUSR00C user list).</p>
     */
    @Column(name = "sec_usr_fname", length = 20)
    private String secUsrFname;

    /**
     * User last name.
     * <p>Maps to COBOL field {@code SEC-USR-LNAME PIC X(20)} from CSUSR01Y.cpy.
     * Displayed in user management screens (COUSR00C user list).</p>
     */
    @Column(name = "sec_usr_lname", length = 20)
    private String secUsrLname;

    /**
     * BCrypt-hashed password.
     * <p>Maps to COBOL field {@code SEC-USR-PWD PIC X(08)} from CSUSR01Y.cpy,
     * but expanded from 8 characters (plaintext) to 72 characters to accommodate
     * BCrypt hash output (typically 60 characters, e.g., {@code $2a$10$...}).
     * The additional 12-character buffer allows for future hashing algorithm
     * changes without schema migration.</p>
     *
     * <p><strong>Security note:</strong> In the COBOL source, COSGN00C.cbl line 223
     * compared passwords via {@code IF SEC-USR-PWD = WS-USER-PWD} (plaintext).
     * In Java, comparison is performed via
     * {@code BCryptPasswordEncoder.matches(rawPassword, encodedPassword)}.</p>
     */
    @Column(name = "sec_usr_pwd", length = 72, nullable = false)
    private String secUsrPwd;

    /**
     * User type code — single character indicating the user's role.
     * <p>Maps to COBOL field {@code SEC-USR-TYPE PIC X(01)} from CSUSR01Y.cpy.
     * Valid values:</p>
     * <ul>
     *   <li>{@code 'A'} — Administrator (COBOL: {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'})</li>
     *   <li>{@code 'U'} — Regular User (COBOL: {@code 88 CDEMO-USRTYP-USER VALUE 'U'})</li>
     * </ul>
     * <p>In the service layer, this value is converted to the
     * {@link com.cardemo.common.enums.UserType} enum via {@code UserType.fromCode()}.</p>
     */
    @Column(name = "sec_usr_type", length = 1)
    private String secUsrType;

    /**
     * Default no-argument constructor required by JPA specification.
     * Not intended for direct use by application code.
     */
    protected UserSecurity() {
        // Required by JPA
    }

    /**
     * Constructs a new {@code UserSecurity} entity with all fields.
     *
     * @param secUsrId    the user identifier (max 8 characters, PK)
     * @param secUsrFname the user's first name (max 20 characters)
     * @param secUsrLname the user's last name (max 20 characters)
     * @param secUsrPwd   the BCrypt-hashed password (max 72 characters)
     * @param secUsrType  the user type code ('A' for Admin, 'U' for User)
     */
    public UserSecurity(String secUsrId, String secUsrFname, String secUsrLname,
                        String secUsrPwd, String secUsrType) {
        this.secUsrId = secUsrId;
        this.secUsrFname = secUsrFname;
        this.secUsrLname = secUsrLname;
        this.secUsrPwd = secUsrPwd;
        this.secUsrType = secUsrType;
    }

    /** Returns the user identifier (primary key). */
    public String getSecUsrId() {
        return secUsrId;
    }

    /** Sets the user identifier. */
    public void setSecUsrId(String secUsrId) {
        this.secUsrId = secUsrId;
    }

    /** Returns the user's first name. */
    public String getSecUsrFname() {
        return secUsrFname;
    }

    /** Sets the user's first name. */
    public void setSecUsrFname(String secUsrFname) {
        this.secUsrFname = secUsrFname;
    }

    /** Returns the user's last name. */
    public String getSecUsrLname() {
        return secUsrLname;
    }

    /** Sets the user's last name. */
    public void setSecUsrLname(String secUsrLname) {
        this.secUsrLname = secUsrLname;
    }

    /** Returns the BCrypt-hashed password. */
    public String getSecUsrPwd() {
        return secUsrPwd;
    }

    /** Sets the BCrypt-hashed password. */
    public void setSecUsrPwd(String secUsrPwd) {
        this.secUsrPwd = secUsrPwd;
    }

    /** Returns the user type code ('A' or 'U'). */
    public String getSecUsrType() {
        return secUsrType;
    }

    /** Sets the user type code. */
    public void setSecUsrType(String secUsrType) {
        this.secUsrType = secUsrType;
    }

    /**
     * Checks equality based on the user ID primary key ({@code secUsrId}).
     * <p>Matches the COBOL VSAM keyed access pattern where SEC-USR-ID
     * uniquely identifies each user security record.</p>
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UserSecurity that = (UserSecurity) o;
        return Objects.equals(secUsrId, that.secUsrId);
    }

    /**
     * Computes hash code based on the user ID primary key.
     */
    @Override
    public int hashCode() {
        return Objects.hash(secUsrId);
    }

    /**
     * Returns a string representation with the password field masked
     * for security. Only the user ID, name, and type are visible.
     *
     * @return formatted string with masked password
     */
    @Override
    public String toString() {
        return "UserSecurity{" +
                "secUsrId='" + secUsrId + '\'' +
                ", secUsrFname='" + secUsrFname + '\'' +
                ", secUsrLname='" + secUsrLname + '\'' +
                ", secUsrPwd='********'" +
                ", secUsrType='" + secUsrType + '\'' +
                '}';
    }
}
