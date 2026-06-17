package com.carddemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Objects;

/**
 * JPA entity mapping the legacy VSAM {@code USRSEC} KSDS dataset onto the
 * relational table {@code users}.
 *
 * <p>This entity is the Java translation of the COBOL copybook
 * {@code app/cpy/CSUSR01Y.cpy} (record {@code SEC-USER-DATA}, 80 bytes,
 * VSAM key length 8 per {@code app/catlg/LISTCAT.txt}). It is a pure
 * persistence entity: it backs application authentication by being loaded
 * through {@code security/CustomUserDetailsService}, whose adapter turns this
 * record into a Spring Security {@code UserDetails}. The {@code userType}
 * discriminator drives role authority ({@code 'A'} &rarr; {@code ADMIN},
 * {@code 'U'} &rarr; {@code USER}), reproducing the user-type routing of the
 * legacy sign-on program {@code COSGN00C}.</p>
 *
 * <h2>COBOL &rarr; Java / column mapping (CSUSR01Y)</h2>
 * <table border="1">
 *   <caption>Field mapping</caption>
 *   <tr><th>COBOL field</th><th>PIC</th><th>Java field</th><th>Column</th></tr>
 *   <tr><td>SEC-USR-ID</td><td>X(08)</td><td>{@link #userId}</td><td>user_id VARCHAR(8) (PK)</td></tr>
 *   <tr><td>SEC-USR-FNAME</td><td>X(20)</td><td>{@link #firstName}</td><td>first_name VARCHAR(20)</td></tr>
 *   <tr><td>SEC-USR-LNAME</td><td>X(20)</td><td>{@link #lastName}</td><td>last_name VARCHAR(20)</td></tr>
 *   <tr><td>SEC-USR-PWD</td><td>X(08)</td><td>{@link #password}</td><td>password VARCHAR(100) NOT NULL</td></tr>
 *   <tr><td>SEC-USR-TYPE</td><td>X(01)</td><td>{@link #userType}</td><td>user_type CHAR(1)</td></tr>
 *   <tr><td>SEC-USR-FILLER</td><td>X(23)</td><td>&mdash; (not persisted)</td><td>&mdash;</td></tr>
 * </table>
 *
 * <h2>Migration notes</h2>
 * <ul>
 *   <li>The original 8-byte plaintext password ({@code SEC-USR-PWD}) is replaced
 *       by a BCrypt hash (strength 12); the {@code password} column is therefore
 *       <strong>widened to {@code VARCHAR(100)}</strong> to hold the 60-character
 *       BCrypt digest. This entity stores the hash only &mdash; password encoding
 *       is the responsibility of the authentication service, never this class.</li>
 *   <li>The COBOL {@code SEC-USR-FILLER} reserve bytes carry no business data and
 *       are intentionally omitted from the mapping.</li>
 *   <li>The schema is owned by Flyway migration {@code V1__schema.sql} and
 *       verified at startup via Hibernate {@code ddl-auto=validate}; the column
 *       names, lengths, nullability and JDBC type of every field below must
 *       therefore match that DDL exactly.</li>
 * </ul>
 *
 * @see <a href="file:app/cpy/CSUSR01Y.cpy">app/cpy/CSUSR01Y.cpy (source-of-truth)</a>
 */
@Entity
@Table(name = "users")
public class User {

    /**
     * Primary key &mdash; the 8-character user identifier (COBOL {@code SEC-USR-ID},
     * e.g. {@code "ADMIN001"}). This is an application-assigned natural key, so it
     * carries no {@code @GeneratedValue}; callers must set it explicitly.
     */
    @Id
    @Column(name = "user_id", length = 8)
    private String userId;

    /** User's first name (COBOL {@code SEC-USR-FNAME}, {@code X(20)}). */
    @Column(name = "first_name", length = 20)
    private String firstName;

    /** User's last name (COBOL {@code SEC-USR-LNAME}, {@code X(20)}). */
    @Column(name = "last_name", length = 20)
    private String lastName;

    /**
     * BCrypt password hash (strength 12). Widened from the legacy 8-byte plaintext
     * field to {@code VARCHAR(100)} to accommodate the 60-character BCrypt digest.
     * Mandatory ({@code NOT NULL}). Never stored or accepted as plaintext.
     */
    @Column(name = "password", length = 100, nullable = false)
    private String password;

    /**
     * User type discriminator: {@code 'A'} = administrator, {@code 'U'} = regular
     * user (COBOL {@code SEC-USR-TYPE}, {@code X(01)}).
     *
     * <p>The backing column is {@code CHAR(1)} (JDBC type code 1). The
     * {@link JdbcTypeCode} below pins Hibernate to {@link SqlTypes#CHAR} so that
     * {@code ddl-auto=validate} succeeds &mdash; a plain {@code String} would
     * otherwise default to {@code VARCHAR} (JDBC type code 12) and fail validation
     * against the {@code CHAR(1)} column on Hibernate 6.4.</p>
     */
    @Column(name = "user_type", length = 1)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String userType;

    /**
     * No-argument constructor required by the JPA specification for entity
     * instantiation and proxying.
     */
    public User() {
    }

    /**
     * Convenience constructor that populates all mapped fields. Useful for seed
     * data, the authentication service, and tests.
     *
     * @param userId    8-character assigned primary key
     * @param firstName user's first name
     * @param lastName  user's last name
     * @param password  BCrypt password hash (never plaintext)
     * @param userType  {@code "A"} for admin or {@code "U"} for regular user
     */
    public User(String userId, String firstName, String lastName, String password, String userType) {
        this.userId = userId;
        this.firstName = firstName;
        this.lastName = lastName;
        this.password = password;
        this.userType = userType;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getUserType() {
        return userType;
    }

    public void setUserType(String userType) {
        this.userType = userType;
    }

    /**
     * Entity equality is based solely on the {@link #userId} primary key, per the
     * persistence-identity contract for entities with an application-assigned key.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        User user = (User) o;
        return Objects.equals(userId, user.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId);
    }

    /**
     * Returns a diagnostic representation of this user.
     *
     * <p><strong>Security:</strong> the {@link #password} hash is deliberately
     * excluded so credentials are never written to logs or diagnostics.</p>
     */
    @Override
    public String toString() {
        return "User{" +
                "userId='" + userId + '\'' +
                ", firstName='" + firstName + '\'' +
                ", lastName='" + lastName + '\'' +
                ", userType='" + userType + '\'' +
                '}';
    }
}
