package com.carddemo.common.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * JPA entity mapping the legacy COBOL ``SEC-USER-DATA`` record (copybook
 * ``CSUSR01Y``, 80-byte fixed layout) to the PostgreSQL ``security_users`` table.
 *
 * :purpose: Persist application security-user records that replace the legacy
 *     RACF/USRSEC credential store. Each row carries the user identifier, first
 *     and last name, an encoded password hash, and the single-character user type.
 * :output: A persistable row in ``security_users`` keyed by ``sec_usr_id``.
 *
 * The ``secUsrPwd`` property stores only an encoded password hash and never a
 * plaintext credential; it is deliberately excluded from ``toString`` so that it
 * cannot leak into logs or diagnostics.
 */
@Entity
@Table(name = "security_users")
public class SecurityUser {

    /**
     * Eight-character user identifier and primary key.
     *
     * :purpose: Maps the legacy ``SEC-USR-ID`` ``PIC X(08)`` key field.
     */
    @Id
    @NotBlank
    @Size(max = 8)
    @Column(name = "sec_usr_id", length = 8, nullable = false)
    private String secUsrId;

    /**
     * User first name.
     *
     * :purpose: Maps the legacy ``SEC-USR-FNAME`` ``PIC X(20)`` field.
     */
    @Size(max = 20)
    @Column(name = "sec_usr_fname", length = 20, nullable = false)
    private String secUsrFname;

    /**
     * User last name.
     *
     * :purpose: Maps the legacy ``SEC-USR-LNAME`` ``PIC X(20)`` field.
     */
    @Size(max = 20)
    @Column(name = "sec_usr_lname", length = 20, nullable = false)
    private String secUsrLname;

    /**
     * Encoded password hash (BCrypt/PBKDF2 form).
     *
     * :purpose: Maps the legacy ``SEC-USR-PWD`` field, widened to 100 characters
     *     to hold an encoded hash rather than the legacy plaintext value. Stores
     *     only the encoded credential and is excluded from ``toString``.
     */
    @NotBlank
    @Column(name = "sec_usr_pwd", length = 100, nullable = false)
    private String secUsrPwd;

    /**
     * Raw single-character user type (``A`` or ``U``).
     *
     * :purpose: Maps the legacy ``SEC-USR-TYPE`` ``PIC X(01)`` field. The value is
     *     kept as the raw character; role resolution is performed by the security
     *     layer, not by this entity.
     */
    @Size(max = 1)
    @Column(name = "sec_usr_type", length = 1, nullable = false)
    private String secUsrType;

    /**
     * Creates an empty instance as required by the JPA provider.
     */
    public SecurityUser() {
    }

    /**
     * Returns the eight-character user identifier.
     *
     * :returns: the primary-key user id, or ``null`` when unset.
     */
    public String getSecUsrId() {
        return secUsrId;
    }

    /**
     * Sets the eight-character user identifier.
     *
     * :param secUsrId: the primary-key user id to assign.
     */
    public void setSecUsrId(String secUsrId) {
        this.secUsrId = secUsrId;
    }

    /**
     * Returns the user first name.
     *
     * :returns: the first name, or ``null`` when unset.
     */
    public String getSecUsrFname() {
        return secUsrFname;
    }

    /**
     * Sets the user first name.
     *
     * :param secUsrFname: the first name to assign.
     */
    public void setSecUsrFname(String secUsrFname) {
        this.secUsrFname = secUsrFname;
    }

    /**
     * Returns the user last name.
     *
     * :returns: the last name, or ``null`` when unset.
     */
    public String getSecUsrLname() {
        return secUsrLname;
    }

    /**
     * Sets the user last name.
     *
     * :param secUsrLname: the last name to assign.
     */
    public void setSecUsrLname(String secUsrLname) {
        this.secUsrLname = secUsrLname;
    }

    /**
     * Returns the encoded password hash.
     *
     * :returns: the encoded credential, or ``null`` when unset; never serialized
     *     to clients.
     */
    @JsonIgnore
    public String getSecUsrPwd() {
        return secUsrPwd;
    }

    /**
     * Sets the encoded password hash.
     *
     * :param secUsrPwd: the encoded credential to persist; never a plaintext value.
     */
    public void setSecUsrPwd(String secUsrPwd) {
        this.secUsrPwd = secUsrPwd;
    }

    /**
     * Returns the raw single-character user type.
     *
     * :returns: the user type character (``A`` or ``U``), or ``null`` when unset.
     */
    public String getSecUsrType() {
        return secUsrType;
    }

    /**
     * Sets the raw single-character user type.
     *
     * :param secUsrType: the user type character (``A`` or ``U``) to assign.
     */
    public void setSecUsrType(String secUsrType) {
        this.secUsrType = secUsrType;
    }

    /**
     * Compares two security users by primary-key identity.
     *
     * :param o: the object to compare against.
     * :returns: ``true`` when both instances share the same ``secUsrId``.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SecurityUser other)) {
            return false;
        }
        return secUsrId != null && secUsrId.equals(other.getSecUsrId());
    }

    /**
     * Computes a proxy-stable hash code consistent with {@link #equals(Object)}.
     *
     * :returns: a constant, identity-consistent hash code.
     */
    @Override
    public int hashCode() {
        return SecurityUser.class.hashCode();
    }

    /**
     * Renders a diagnostic representation that excludes the password hash.
     *
     * :returns: a string containing the id, names, and type only.
     */
    @Override
    public String toString() {
        return "SecurityUser{"
                + "secUsrId='" + secUsrId + '\''
                + ", secUsrFname='" + secUsrFname + '\''
                + ", secUsrLname='" + secUsrLname + '\''
                + ", secUsrType='" + secUsrType + '\''
                + '}';
    }
}
