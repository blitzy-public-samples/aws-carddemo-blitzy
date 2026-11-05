package com.cardemo.common.dto;

import jakarta.validation.constraints.Size;
import java.util.Objects;

/**
 * User security record DTO — translated from CSUSR01Y.cpy (SEC-USER-DATA, RECLN 80).
 *
 * <p>This class faithfully maps the COBOL copybook {@code CSUSR01Y.cpy} SEC-USER-DATA
 * 01-level group (80-byte record) used by the USRSEC VSAM dataset into a Java POJO.
 *
 * <p>COBOL source structure:
 * <pre>
 * 01 SEC-USER-DATA.
 *   05 SEC-USR-ID        PIC X(08).   → secUsrId   (max 8)
 *   05 SEC-USR-FNAME     PIC X(20).   → secUsrFname (max 20)
 *   05 SEC-USR-LNAME     PIC X(20).   → secUsrLname (max 20)
 *   05 SEC-USR-PWD       PIC X(08).   → secUsrPwd   (max 8)
 *   05 SEC-USR-TYPE      PIC X(01).   → secUsrType  (max 1)
 *   05 SEC-USR-FILLER    PIC X(23).   → not mapped (padding only)
 * </pre>
 *
 * <p>In the COBOL source, passwords are stored in plaintext. In the Java migration,
 * passwords are BCrypt-hashed. The {@code secUsrPwd} field carries the hashed password
 * or is used for input only. The password is masked in {@link #toString()} output
 * for security.
 *
 * <p>{@code secUsrType} maps to 'A' (Admin) and 'U' (User) — kept as {@code String}
 * in this DTO. Typed access is available via {@code com.cardemo.common.enums.UserType}
 * in the entity and service layers.
 *
 * <p>Identity ({@link #equals(Object)} and {@link #hashCode()}) is based solely on
 * {@code secUsrId}, which corresponds to the COBOL primary key SEC-USR-ID.
 */
public class UserSecurityRecord {

    /**
     * User ID — primary key (COBOL: SEC-USR-ID, PIC X(08)).
     * Maximum 8 characters.
     */
    @Size(max = 8)
    private String secUsrId;

    /**
     * User first name (COBOL: SEC-USR-FNAME, PIC X(20)).
     * Maximum 20 characters.
     */
    @Size(max = 20)
    private String secUsrFname;

    /**
     * User last name (COBOL: SEC-USR-LNAME, PIC X(20)).
     * Maximum 20 characters.
     */
    @Size(max = 20)
    private String secUsrLname;

    /**
     * User password (COBOL: SEC-USR-PWD, PIC X(08)).
     * Sensitive field — masked in {@link #toString()} output.
     * In the Java migration, this carries the BCrypt-hashed password
     * or the plaintext input for verification.
     * Maximum 8 characters per original COBOL PIC specification.
     */
    @Size(max = 8)
    private String secUsrPwd;

    /**
     * User type (COBOL: SEC-USR-TYPE, PIC X(01)).
     * Values: 'A' = Admin, 'U' = User (maps to 88-level conditions).
     * Maximum 1 character.
     */
    @Size(max = 1)
    private String secUsrType;

    /**
     * Default no-arg constructor.
     * Required for framework compatibility (JPA, Jackson, Spring).
     */
    public UserSecurityRecord() {
        // No-arg constructor for framework use
    }

    /**
     * All-args constructor creating a fully populated user security record.
     *
     * @param secUsrId    user ID, max 8 characters (COBOL PK SEC-USR-ID)
     * @param secUsrFname first name, max 20 characters
     * @param secUsrLname last name, max 20 characters
     * @param secUsrPwd   password, max 8 characters (BCrypt-hashed in storage)
     * @param secUsrType  user type, 'A' for Admin or 'U' for User
     */
    public UserSecurityRecord(String secUsrId, String secUsrFname, String secUsrLname,
                              String secUsrPwd, String secUsrType) {
        this.secUsrId = secUsrId;
        this.secUsrFname = secUsrFname;
        this.secUsrLname = secUsrLname;
        this.secUsrPwd = secUsrPwd;
        this.secUsrType = secUsrType;
    }

    /**
     * Returns the user ID (COBOL: SEC-USR-ID).
     *
     * @return user ID, max 8 characters
     */
    public String getSecUsrId() {
        return secUsrId;
    }

    /**
     * Sets the user ID (COBOL: SEC-USR-ID).
     *
     * @param secUsrId user ID, max 8 characters
     */
    public void setSecUsrId(String secUsrId) {
        this.secUsrId = secUsrId;
    }

    /**
     * Returns the user first name (COBOL: SEC-USR-FNAME).
     *
     * @return first name, max 20 characters
     */
    public String getSecUsrFname() {
        return secUsrFname;
    }

    /**
     * Sets the user first name (COBOL: SEC-USR-FNAME).
     *
     * @param secUsrFname first name, max 20 characters
     */
    public void setSecUsrFname(String secUsrFname) {
        this.secUsrFname = secUsrFname;
    }

    /**
     * Returns the user last name (COBOL: SEC-USR-LNAME).
     *
     * @return last name, max 20 characters
     */
    public String getSecUsrLname() {
        return secUsrLname;
    }

    /**
     * Sets the user last name (COBOL: SEC-USR-LNAME).
     *
     * @param secUsrLname last name, max 20 characters
     */
    public void setSecUsrLname(String secUsrLname) {
        this.secUsrLname = secUsrLname;
    }

    /**
     * Returns the user password (COBOL: SEC-USR-PWD).
     * Warning: This field contains sensitive data. Use with care.
     *
     * @return password value (BCrypt-hashed in storage, plaintext for input)
     */
    public String getSecUsrPwd() {
        return secUsrPwd;
    }

    /**
     * Sets the user password (COBOL: SEC-USR-PWD).
     *
     * @param secUsrPwd password value, max 8 characters
     */
    public void setSecUsrPwd(String secUsrPwd) {
        this.secUsrPwd = secUsrPwd;
    }

    /**
     * Returns the user type (COBOL: SEC-USR-TYPE).
     *
     * @return user type character: 'A' for Admin, 'U' for User
     */
    public String getSecUsrType() {
        return secUsrType;
    }

    /**
     * Sets the user type (COBOL: SEC-USR-TYPE).
     *
     * @param secUsrType user type character: 'A' for Admin, 'U' for User
     */
    public void setSecUsrType(String secUsrType) {
        this.secUsrType = secUsrType;
    }

    /**
     * Returns a string representation with the password field masked for security.
     * The {@code secUsrPwd} value is replaced with "[MASKED]" to prevent
     * accidental exposure of sensitive credentials in logs or debug output.
     *
     * @return string representation with masked password
     */
    @Override
    public String toString() {
        return "UserSecurityRecord{"
                + "secUsrId='" + secUsrId + '\''
                + ", secUsrFname='" + secUsrFname + '\''
                + ", secUsrLname='" + secUsrLname + '\''
                + ", secUsrPwd='[MASKED]'"
                + ", secUsrType='" + secUsrType + '\''
                + '}';
    }

    /**
     * Equality based on {@code secUsrId} (COBOL primary key SEC-USR-ID).
     * Two {@code UserSecurityRecord} instances are equal if and only if
     * their user IDs are equal.
     *
     * @param o the object to compare
     * @return {@code true} if the user IDs are equal
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UserSecurityRecord that)) {
            return false;
        }
        return Objects.equals(secUsrId, that.secUsrId);
    }

    /**
     * Hash code based on {@code secUsrId} (COBOL primary key SEC-USR-ID).
     *
     * @return hash code derived from user ID
     */
    @Override
    public int hashCode() {
        return Objects.hash(secUsrId);
    }
}
