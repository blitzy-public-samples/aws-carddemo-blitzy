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
package com.aws.carddemo.security;

import java.io.Serial;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Immutable Spring Security {@link UserDetails} principal for CardDemo, adapting a
 * single authenticated {@code USRSEC} record to the contract Spring Security's
 * authentication machinery expects.
 *
 * <p><strong>Provenance.</strong> The three fields carried here map one-for-one to
 * fields of the legacy {@code SEC-USER-DATA} record declared in
 * {@code app/cpy/CSUSR01Y.cpy} (relocated to {@code legacy/**}):</p>
 * <pre>
 * 05 SEC-USR-ID    PIC X(08).   -&gt; {@link #getUsername() username}  (login id / primary key)
 * 05 SEC-USR-PWD   PIC X(08).   -&gt; {@link #getPassword() password}  (stored credential; see below)
 * 05 SEC-USR-TYPE  PIC X(01).   -&gt; {@link #getRole() role}          (via {@link UserRole})
 * </pre>
 * The remaining record fields ({@code SEC-USR-FNAME}, {@code SEC-USR-LNAME},
 * {@code SEC-USR-FILLER}) are not part of the authentication contract and are
 * deliberately not held on the principal.
 *
 * <p><strong>Credential storage.</strong> The legacy {@code SEC-USR-PWD} field held
 * an 8-character plaintext password. In the Java target the corresponding column is
 * widened to hold a BCrypt hash, and this class treats {@link #getPassword()} as an
 * opaque encoded credential. The auto-configured {@code DaoAuthenticationProvider}
 * compares the submitted password against this value using the {@code PasswordEncoder}
 * bean declared in {@code config/SecurityConfig}; this principal never encodes,
 * decodes, or otherwise interprets the credential.</p>
 *
 * <p><strong>Account-status flags.</strong> The legacy {@code USRSEC} security model
 * ({@code app/cpy/CSUSR01Y.cpy}) has no concept of account expiry, locking,
 * credential expiry, or a disabled flag. To preserve behavioral parity &mdash;
 * introducing such gating would be a feature change forbidden by AAP &sect;0.3.3 &mdash;
 * all four status predicates ({@link #isAccountNonExpired()},
 * {@link #isAccountNonLocked()}, {@link #isCredentialsNonExpired()},
 * {@link #isEnabled()}) always return {@code true}.</p>
 *
 * <p><strong>Authority.</strong> Exactly one {@link GrantedAuthority} is exposed,
 * derived from the {@link UserRole}, which itself maps the legacy {@code 'A'}/{@code 'U'}
 * {@code SEC-USR-TYPE} code to {@code "ROLE_ADMIN"}/{@code "ROLE_USER"} per the
 * COMMAREA condition names in {@code app/cpy/COCOM01Y.cpy} (L25-28).</p>
 *
 * <p><strong>Confidentiality.</strong> The stored credential is exposed only through
 * {@link #getPassword()}; it is intentionally excluded from {@link #toString()} and
 * from every other representation so it can never be written to a log or an error
 * message (AAP &sect;0.7.3 / &sect;0.9.3). The credential is additionally declared
 * {@code transient} so it is never written into the serialized form of the principal
 * (Spring Security may serialize the authenticated principal into the
 * {@code SecurityContext}), and this class implements {@link CredentialsContainer} so
 * the authentication manager erases the credential (sets it to {@code null})
 * immediately after a successful authentication.</p>
 *
 * <p><strong>Immutability &amp; identity.</strong> The {@code username} and
 * {@code role} are {@code final} and validated non-null at construction. The
 * credential is {@code transient} and clearable in one direction only: the
 * {@link #eraseCredentials()} contract may reset it to {@code null} after
 * authentication (there is no other setter). Principal identity
 * ({@link #equals(Object)} / {@link #hashCode()}) is defined solely by the
 * {@code username}, which is the stable {@code sec_usr_id} primary key, so erasing
 * the credential never changes a principal's identity.</p>
 *
 * <p>This class is produced by
 * {@code CardDemoUserDetailsService.loadUserByUsername(String)} and consumed by the
 * Spring Security authentication provider. It depends only on {@link UserRole}
 * within this package.</p>
 *
 * @see UserRole
 * @see org.springframework.security.core.userdetails.UserDetails
 */
public class CardDemoUserDetails implements UserDetails, CredentialsContainer {

    /**
     * Serialization version identifier. {@link UserDetails} extends
     * {@link java.io.Serializable}, so an explicit {@code serialVersionUID} is
     * required both to keep the zero-warning ({@code -Xlint:all}) build free of the
     * {@code serial} lint warning and to give the principal a stable serialized
     * form (Spring Security may serialize the authenticated principal into the
     * {@code SecurityContext}). The BCrypt {@code password} is {@code transient} and
     * therefore is deliberately excluded from that serialized form.
     */
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * The login identity of the authenticated user &mdash; the legacy
     * {@code SEC-USR-ID} ({@code PIC X(08)}) / {@code user_security.sec_usr_id}
     * primary key. Never {@code null}.
     */
    private final String username;

    /**
     * The stored credential for the user: a BCrypt hash derived from the legacy
     * {@code SEC-USR-PWD} column. Treated as an opaque encoded value and exposed
     * only through {@link #getPassword()}.
     *
     * <p>Declared {@code transient} so the BCrypt hash is never written into the
     * principal's serialized form, and non-{@code final} so {@link #eraseCredentials()}
     * can reset it to {@code null} once authentication has completed. It is
     * non-{@code null} from construction until it is either erased in memory or
     * dropped by deserialization; a deserialized principal therefore carries a
     * {@code null} credential, which is acceptable because the credential is required
     * only during the initial authentication and never afterwards.</p>
     */
    private transient String password;

    /**
     * The user's application role ({@link UserRole#ADMIN} or {@link UserRole#USER}),
     * mapped from the legacy {@code SEC-USR-TYPE} code. Never {@code null}.
     */
    private final UserRole role;

    /**
     * Constructs an immutable principal from an already-resolved {@code USRSEC}
     * record. All three arguments are mandatory: a principal with a {@code null}
     * identity, credential, or role is a programming error rather than a
     * recoverable authentication outcome, so each is validated eagerly.
     *
     * @param username the login id ({@code SEC-USR-ID} / {@code sec_usr_id}); must not be {@code null}
     * @param password the stored (BCrypt-hashed) credential; must not be {@code null}
     * @param role     the application role mapped from {@code SEC-USR-TYPE}; must not be {@code null}
     * @throws NullPointerException if {@code username}, {@code password}, or {@code role} is {@code null}
     */
    public CardDemoUserDetails(String username, String password, UserRole role) {
        this.username = Objects.requireNonNull(username, "username");
        this.password = Objects.requireNonNull(password, "password");
        this.role = Objects.requireNonNull(role, "role");
    }

    /**
     * Returns the single authority granted to this principal, derived from its
     * {@link UserRole}. The returned collection is immutable and always contains
     * exactly one {@link GrantedAuthority} whose value is {@code "ROLE_ADMIN"} or
     * {@code "ROLE_USER"}.
     *
     * @return an immutable, single-element collection of authorities (never {@code null} or empty)
     */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(role.toGrantedAuthority());
    }

    /**
     * Returns the stored (BCrypt-hashed) credential. This is the only accessor that
     * exposes the credential; it exists solely for the Spring Security
     * {@code PasswordEncoder} / {@code DaoAuthenticationProvider} comparison, which
     * runs while the freshly loaded principal still holds the credential.
     *
     * @return the encoded credential; non-{@code null} until {@link #eraseCredentials()}
     *         is invoked (or the principal is deserialized), after which it is
     *         {@code null}
     */
    @Override
    public String getPassword() {
        return password;
    }

    /**
     * Erases the stored credential by resetting it to {@code null}, satisfying the
     * {@link CredentialsContainer} contract. Spring Security's authentication
     * manager invokes this on the authenticated principal immediately after a
     * successful authentication (its {@code eraseCredentialsAfterAuthentication}
     * behavior is enabled by default), so the BCrypt hash does not linger in the
     * {@code SecurityContext} for the remainder of the request.
     *
     * <p>Erasing the credential is safe: it is consumed only during the initial
     * {@code DaoAuthenticationProvider} password comparison, never afterwards, and it
     * is deliberately excluded from {@link #equals(Object)} / {@link #hashCode()} so
     * clearing it cannot change this principal's identity. The operation is
     * idempotent &mdash; invoking it when the credential is already {@code null} is a
     * no-op.</p>
     */
    @Override
    public void eraseCredentials() {
        this.password = null;
    }

    /**
     * Returns the login identity &mdash; the legacy {@code SEC-USR-ID} /
     * {@code user_security.sec_usr_id} primary key.
     *
     * @return the username (never {@code null})
     */
    @Override
    public String getUsername() {
        return username;
    }

    /**
     * Indicates whether the account has not expired. The legacy {@code USRSEC}
     * model ({@code app/cpy/CSUSR01Y.cpy}) has no expiry flag, so this always
     * returns {@code true} to preserve parity.
     *
     * @return {@code true} always
     */
    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    /**
     * Indicates whether the account is not locked. The legacy {@code USRSEC} model
     * ({@code app/cpy/CSUSR01Y.cpy}) has no lock flag, so this always returns
     * {@code true} to preserve parity.
     *
     * @return {@code true} always
     */
    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    /**
     * Indicates whether the credentials have not expired. The legacy {@code USRSEC}
     * model ({@code app/cpy/CSUSR01Y.cpy}) has no credential-expiry flag, so this
     * always returns {@code true} to preserve parity.
     *
     * @return {@code true} always
     */
    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    /**
     * Indicates whether the user is enabled. The legacy {@code USRSEC} model
     * ({@code app/cpy/CSUSR01Y.cpy}) has no enabled/disabled flag, so this always
     * returns {@code true} to preserve parity.
     *
     * @return {@code true} always
     */
    @Override
    public boolean isEnabled() {
        return true;
    }

    /**
     * Returns the application role of this principal. Provided as a typed
     * convenience for downstream admin-vs-user navigation (reproducing the
     * {@code COSGN00C} {@code XCTL} branch to the Admin Menu versus the Main Menu)
     * without re-parsing the granted authorities.
     *
     * @return the {@link UserRole} (never {@code null})
     */
    public UserRole getRole() {
        return role;
    }

    /**
     * Two principals are equal when they share the same {@code username}, which is
     * the stable {@code sec_usr_id} primary key. The credential and role are
     * intentionally excluded so that identity is anchored to the account rather
     * than to a particular snapshot of its attributes.
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
     * Returns a hash code consistent with {@link #equals(Object)}, derived solely
     * from the {@code username}.
     *
     * @return the username-based hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(username);
    }

    /**
     * Returns a diagnostic representation containing the {@code username} and
     * {@code role}. The stored credential is deliberately omitted so it can never
     * leak into logs or error messages (AAP &sect;0.7.3 / &sect;0.9.3).
     *
     * @return a password-free description of this principal
     */
    @Override
    public String toString() {
        return "CardDemoUserDetails{username='" + username + "', role=" + role + "}";
    }
}
