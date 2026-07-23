package com.aws.carddemo.security;

import java.io.Serial;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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
 * <p><strong>Construction invariants (review finding F24, CWE-269):</strong> the constructor is the
 * single, centralized place that builds a principal. It requires a non-null identity
 * ({@code username}), validates that {@code userType} is exactly {@code "A"} or {@code "U"}, and
 * <em>derives</em> the Spring Security authority from that validated type ({@code "A"} &rarr;
 * {@code ROLE_ADMIN}, {@code "U"} &rarr; {@code ROLE_USER}). Callers cannot supply an authority that
 * is inconsistent with the stored type, so a non-admin principal can never hold {@code ROLE_ADMIN}.
 * An unknown or {@code null} user type is rejected with an {@link IllegalArgumentException}; the
 * decision to fail closed on an invalid type is recorded in {@code docs/decision-log.md}.</p>
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
 * <p><strong>Cleartext password parity and credential hygiene (review finding F23, CWE-522):</strong>
 * the password is retained and returned as cleartext to preserve the legacy signon's direct
 * {@code IF SEC-USR-PWD = WS-USER-PWD} comparison behavior; the rationale is recorded in
 * {@code docs/decision-log.md}. Three layered safeguards limit its exposure: (1) the credential field
 * is declared {@code transient}, so it is never written out if the principal is ever serialized — for
 * example into a distributed or persisted {@code HttpSession} — even before erasure; (2) the class
 * implements {@link CredentialsContainer} so Spring's {@code ProviderManager} erases the credential
 * immediately after successful authentication; and (3) {@link #toString()} emits no field at all.
 * Callers must not cache or serialize a not-yet-authenticated principal in a way that would attempt to
 * persist its credential, and must rely on the framework erasure rather than reading the password after
 * authentication.</p>
 */
public class CardDemoUserDetails implements UserDetails, CredentialsContainer {

    /** Serialization version identifier; {@link UserDetails} extends {@link java.io.Serializable}. */
    @Serial
    private static final long serialVersionUID = 1L;

    /** Admin user-type flag ({@code SEC-USR-TYPE} {@code VALUE 'A'}, COBOL {@code CDEMO-USRTYP-ADMIN}). */
    public static final String USER_TYPE_ADMIN = "A";

    /** Standard user-type flag ({@code SEC-USR-TYPE} {@code VALUE 'U'}). */
    public static final String USER_TYPE_USER = "U";

    /** Spring Security role granted to an administrator ({@link #USER_TYPE_ADMIN}). */
    private static final String ROLE_ADMIN = "ROLE_ADMIN";

    /** Spring Security role granted to a standard user ({@link #USER_TYPE_USER}). */
    private static final String ROLE_USER = "ROLE_USER";

    /**
     * The user id ({@code SEC-USR-ID}, uppercase, up to 8 characters); this is the Spring Security
     * {@code username} and the identity used for {@link #equals(Object)} / {@link #hashCode()}.
     */
    private final String username;

    /**
     * The cleartext password ({@code SEC-USR-PWD}). Declared {@code transient} so the credential is
     * never emitted by Java serialization (review finding F23, CWE-522): even if a not-yet-erased
     * principal is serialized into a distributed or persisted {@code HttpSession}, the password is not
     * written out. It is also non-final so {@link #eraseCredentials()} can null it in memory after
     * authentication. In-memory reads by the authentication provider are unaffected by
     * {@code transient}.
     */
    private transient String password;

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
     * The single Spring Security authority, <em>derived</em> from the validated {@link #userType} at
     * construction ({@code "A"} &rarr; {@code ROLE_ADMIN}, {@code "U"} &rarr; {@code ROLE_USER}) rather
     * than supplied independently, so it can never be inconsistent with the stored type (review finding
     * F24). Stored as an unmodifiable list so {@link #getAuthorities()} can return it directly. The
     * {@code List.of(new SimpleGrantedAuthority(...))} is a serializable immutable list; the field's
     * declared {@link List} type is not itself {@code Serializable}, hence the targeted suppression.
     */
    @SuppressWarnings("serial")
    private final List<GrantedAuthority> authorities;

    /**
     * Creates a principal for an authenticated CardDemo user. The identity and role are validated and
     * the authority is derived here (review finding F24, CWE-269); other values are stored verbatim,
     * since any uppercasing or normalization is performed upstream by the service and authentication
     * provider, faithfully mirroring where the COBOL applies {@code FUNCTION UPPER-CASE}.
     *
     * @param username  the user id ({@code SEC-USR-ID}); must not be {@code null}
     * @param password  the cleartext password ({@code SEC-USR-PWD}); may be {@code null}
     * @param userType  the raw user type flag ({@code SEC-USR-TYPE}); must be {@code "A"} or {@code "U"}
     * @param firstName the user first name ({@code SEC-USR-FNAME})
     * @param lastName  the user last name ({@code SEC-USR-LNAME})
     * @throws NullPointerException     if {@code username} is {@code null}
     * @throws IllegalArgumentException if {@code userType} is not {@code "A"} or {@code "U"}
     */
    public CardDemoUserDetails(String username, String password, String userType,
                               String firstName, String lastName) {
        this.username = Objects.requireNonNull(username, "username (SEC-USR-ID) must not be null");
        this.userType = requireValidUserType(userType);
        this.password = password;
        this.firstName = firstName;
        this.lastName = lastName;
        this.authorities = deriveAuthorities(this.userType);
    }

    /**
     * Validates the raw user-type flag and returns it unchanged. Failing closed on an unknown or
     * {@code null} type prevents an authenticated principal from being built with no role or a role
     * inconsistent with its type (review finding F24). The invalid value is intentionally not echoed
     * into the exception message.
     *
     * @param userType the raw user type flag ({@code SEC-USR-TYPE})
     * @return {@code userType} when it is {@code "A"} or {@code "U"}
     * @throws IllegalArgumentException if {@code userType} is not {@code "A"} or {@code "U"}
     */
    private static String requireValidUserType(String userType) {
        if (!USER_TYPE_ADMIN.equals(userType) && !USER_TYPE_USER.equals(userType)) {
            throw new IllegalArgumentException(
                    "userType (SEC-USR-TYPE) must be \"A\" (admin) or \"U\" (user)");
        }
        return userType;
    }

    /**
     * Derives the single Spring Security authority from the already-validated user type, so the
     * authority can never be inconsistent with the stored type (review finding F24): {@code "A"} maps to
     * {@code ROLE_ADMIN} and {@code "U"} maps to {@code ROLE_USER}.
     *
     * @param userType the validated user type flag ({@code "A"} or {@code "U"})
     * @return an immutable single-element authority list
     */
    private static List<GrantedAuthority> deriveAuthorities(String userType) {
        String role = USER_TYPE_ADMIN.equals(userType) ? ROLE_ADMIN : ROLE_USER;
        return List.of(new SimpleGrantedAuthority(role));
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
        return USER_TYPE_ADMIN.equals(userType);
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
     * Returns a non-sensitive diagnostic representation containing only the class name and an opaque
     * per-instance identity token. The user id, user type, first/last names, authorities and
     * (cleartext) password are deliberately never emitted &mdash; not even partially masked &mdash; so
     * that neither credentials nor user identity can leak into logs or error messages (CWE-532; review
     * finding F9).
     *
     * @return a non-sensitive string representation
     */
    @Override
    public String toString() {
        return "CardDemoUserDetails@" + Integer.toHexString(System.identityHashCode(this));
    }
}
