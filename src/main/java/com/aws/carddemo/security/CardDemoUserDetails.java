package com.aws.carddemo.security;

import java.io.Serial;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Spring Security principal representing an authenticated AWS CardDemo user.
 *
 * <p>This class is framework glue with no direct one-for-one COBOL counterpart: it is the Java
 * realization of the legacy {@code SEC-USER-DATA} record as consumed by the signon flow. It carries
 * the user id, the (cleartext, for parity) password, the raw user type ({@code "A"} / {@code "U"}),
 * the first and last name, and the single Spring Security authority that was mapped from the user
 * type by the {@code CardDemoUserDetailsService}. It is instantiated directly by that service (it is
 * intentionally not a Spring-managed bean) and returned to Spring Security's authentication
 * machinery.</p>
 *
 * <p>The principal is deliberately decoupled from the JPA entity
 * {@code com.aws.carddemo.domain.UserSecurity}: it neither imports nor holds that entity, so the
 * security principal is a clean, self-contained value object rather than a detached persistence
 * object.</p>
 *
 * <p><strong>Origin (traceability, AAP &sect;0.6.7 / &sect;0.6.10):</strong></p>
 * <ul>
 *   <li>Origin: {@code legacy/cbl/COSGN00C.cbl} (signon: after a successful {@code USRSEC} read and
 *       cleartext password match, {@code MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE} and
 *       {@code IF CDEMO-USRTYP-ADMIN} route to the admin menu, else the user menu).</li>
 *   <li>Origin: {@code legacy/cpy/CSUSR01Y.cpy} ({@code SEC-USER-DATA} record layout: {@code SEC-USR-ID},
 *       {@code SEC-USR-FNAME}, {@code SEC-USR-LNAME}, {@code SEC-USR-PWD}, {@code SEC-USR-TYPE}).</li>
 * </ul>
 *
 * <p><strong>Cleartext password parity:</strong> the password is retained and returned as cleartext to
 * preserve the legacy signon's direct {@code IF SEC-USR-PWD = WS-USER-PWD} comparison behavior; the
 * rationale is recorded in {@code docs/decision-log.md}. As a hygiene safeguard the class implements
 * {@link CredentialsContainer} so Spring can erase the credential after authentication, and
 * {@link #toString()} never exposes the password.</p>
 */
public class CardDemoUserDetails implements UserDetails, CredentialsContainer {

    /** Serialization version identifier; {@link UserDetails} extends {@link java.io.Serializable}. */
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * The user id ({@code SEC-USR-ID}, uppercase, up to 8 characters); this is the Spring Security
     * {@code username} and the identity used for {@link #equals(Object)} / {@link #hashCode()}.
     */
    private final String username;

    /**
     * The cleartext password ({@code SEC-USR-PWD}). Non-final so that {@link #eraseCredentials()} can
     * null it after authentication.
     */
    private String password;

    /**
     * The raw user type flag ({@code SEC-USR-TYPE}): {@code "A"} for admin or {@code "U"} for user.
     * Retained verbatim so downstream controllers can populate the {@code CardDemoContext}
     * {@code CDEMO-USER-TYPE} field exactly and perform the post-authentication menu redirect.
     */
    private final String userType;

    /**
     * The user first name ({@code SEC-USR-FNAME}). Carried for display and completeness; it is not part
     * of the COMMAREA parity contract.
     */
    private final String firstName;

    /** The user last name ({@code SEC-USR-LNAME}). Carried for display and completeness. */
    private final String lastName;

    /**
     * The mapped Spring Security authorities. Stored as an unmodifiable list so {@link #getAuthorities()}
     * can return it directly. {@code List.copyOf(...)} yields a serializable immutable list; the field's
     * declared {@link List} type is not itself {@code Serializable}, hence the targeted suppression.
     */
    @SuppressWarnings("serial")
    private final List<GrantedAuthority> authorities;

    /**
     * Creates a principal for an authenticated CardDemo user. Values are stored verbatim; any
     * uppercasing or normalization is performed upstream by the service and authentication provider,
     * faithfully mirroring where the COBOL applies {@code FUNCTION UPPER-CASE}.
     *
     * @param username    the user id ({@code SEC-USR-ID})
     * @param password    the cleartext password ({@code SEC-USR-PWD})
     * @param userType    the raw user type flag ({@code SEC-USR-TYPE}): {@code "A"} or {@code "U"}
     * @param firstName   the user first name ({@code SEC-USR-FNAME})
     * @param lastName    the user last name ({@code SEC-USR-LNAME})
     * @param authorities the mapped granted authorities; defensively copied into an immutable list
     * @throws NullPointerException if {@code authorities} is {@code null} or contains a {@code null}
     */
    public CardDemoUserDetails(String username, String password, String userType,
                               String firstName, String lastName,
                               Collection<? extends GrantedAuthority> authorities) {
        this.username = username;
        this.password = password;
        this.userType = userType;
        this.firstName = firstName;
        this.lastName = lastName;
        this.authorities = List.copyOf(authorities);
    }

    /**
     * Returns the authorities granted to this user (the single role mapped from the user type).
     *
     * @return an unmodifiable collection of the granted authorities
     */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    /**
     * Returns the cleartext password, or {@code null} once {@link #eraseCredentials()} has run. The
     * value is returned unmodified for parity with the legacy cleartext comparison.
     *
     * @return the cleartext password, or {@code null} after erasure
     */
    @Override
    public String getPassword() {
        return password;
    }

    /**
     * Returns the user id ({@code SEC-USR-ID}) used as the Spring Security username.
     *
     * @return the username
     */
    @Override
    public String getUsername() {
        return username;
    }

    // COBOL USRSEC has no lock/expiry/disable flags; all users are active.

    /**
     * {@inheritDoc}
     *
     * @return always {@code true}; the legacy {@code USRSEC} store has no account-expiry concept
     */
    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * @return always {@code true}; the legacy {@code USRSEC} store has no account-lock concept
     */
    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * @return always {@code true}; the legacy {@code USRSEC} store has no credentials-expiry concept
     */
    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * @return always {@code true}; the legacy {@code USRSEC} store has no user-disable concept
     */
    @Override
    public boolean isEnabled() {
        return true;
    }

    /**
     * Erases the credential by nulling the stored password. Invoked by Spring's {@code ProviderManager}
     * after successful authentication so the cleartext credential is not retained in the session-stored
     * principal.
     */
    @Override
    public void eraseCredentials() {
        this.password = null;
    }

    /**
     * Returns the raw user type flag ({@code SEC-USR-TYPE}): {@code "A"} or {@code "U"}. Used by
     * controllers to populate {@code CardDemoContext.CDEMO-USER-TYPE} and drive the post-authentication
     * admin / main-menu redirect.
     *
     * @return the raw user type, {@code "A"} or {@code "U"}
     */
    public String getUserType() {
        return userType;
    }

    /**
     * Returns the user first name ({@code SEC-USR-FNAME}).
     *
     * @return the first name
     */
    public String getFirstName() {
        return firstName;
    }

    /**
     * Returns the user last name ({@code SEC-USR-LNAME}).
     *
     * @return the last name
     */
    public String getLastName() {
        return lastName;
    }

    /**
     * Convenience check mirroring the COBOL {@code CDEMO-USRTYP-ADMIN} 88-level ({@code VALUE 'A'}).
     * Downstream code may prefer authority-based checks; this accessor aids controller routing
     * readability.
     *
     * @return {@code true} if the raw user type is {@code "A"} (administrator)
     */
    public boolean isAdmin() {
        return "A".equals(userType);
    }

    /**
     * Compares two principals by user id ({@link #getUsername() username}) only, mirroring identity by
     * {@code SEC-USR-ID} and consistent with the {@code UserSecurity} entity's key-based equality.
     *
     * @param o the object to compare with
     * @return {@code true} if {@code o} is a {@code CardDemoUserDetails} with an equal username
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CardDemoUserDetails other)) {
            return false;
        }
        return Objects.equals(username, other.username);
    }

    /**
     * Returns a hash code derived from the user id ({@link #getUsername() username}), consistent with
     * {@link #equals(Object)}.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(username);
    }

    /**
     * Returns a diagnostic string for this principal. The password is intentionally masked and never
     * included, as a security-hygiene safeguard.
     *
     * @return a string representation that excludes the cleartext password
     */
    @Override
    public String toString() {
        return "CardDemoUserDetails{"
                + "username='" + username + '\''
                + ", userType='" + userType + '\''
                + ", firstName='" + firstName + '\''
                + ", lastName='" + lastName + '\''
                + ", authorities=" + authorities
                + ", password=[PROTECTED]"
                + '}';
    }
}
