package com.aws.carddemo.security;

import java.util.Collection;

import com.aws.carddemo.TestCredentials;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit test for the Spring Security principal {@link CardDemoUserDetails}.
 *
 * <p>This is a fast, isolated POJO test: there is deliberately no Spring application context, no
 * Mockito, and no database. The class name ends in {@code Test}, so it runs under the Maven
 * Surefire plugin. Every test builds a fresh principal, so the tests are independent and
 * side-effect free.</p>
 *
 * <p><strong>What this test locks down.</strong> It verifies the Spring Security
 * {@code UserDetails} plus {@code CredentialsContainer} contract implemented by
 * {@link CardDemoUserDetails}: the constructor and accessor round trip, the single role authority
 * derived from the raw user type, the always-active account-status flags, credential erasure, the
 * username-only identity ({@code equals}/{@code hashCode}), the immutability of the authorities
 * collection, and the non-sensitive {@code toString()}. It also guards the fail-closed constructor
 * validation of the user type and the required username.</p>
 *
 * <p><strong>COBOL oracle (traceability, AAP 0.6.7 / 0.6.10).</strong></p>
 * <ul>
 *   <li>Origin: {@code legacy/cbl/COSGN00C.cbl} - the signon program's {@code READ-USER-SEC-FILE}
 *       paragraph reads the {@code USRSEC} record, compares the cleartext password directly
 *       ({@code IF SEC-USR-PWD = WS-USER-PWD}), moves {@code SEC-USR-TYPE} into
 *       {@code CDEMO-USER-TYPE}, and routes by user type ({@code IF CDEMO-USRTYP-ADMIN} transfers
 *       to the admin menu {@code COADM01C}, otherwise to the user menu {@code COMEN01C}).</li>
 *   <li>Origin: {@code legacy/cpy/CSUSR01Y.cpy} - the {@code SEC-USER-DATA} record (80 bytes:
 *       {@code SEC-USR-ID X(08)}, {@code SEC-USR-FNAME X(20)}, {@code SEC-USR-LNAME X(20)},
 *       {@code SEC-USR-PWD X(08)}, {@code SEC-USR-TYPE X(01)}, {@code FILLER X(23)}) - the source
 *       of the principal's identity, name, password, and type fields.</li>
 * </ul>
 *
 * <p><strong>Cleartext-password parity (AAP 0.6.7, intentional).</strong> The legacy signon
 * compared the password as cleartext, so {@link CardDemoUserDetails} stores and returns it
 * unmodified with no {@code PasswordEncoder} and no hashing; that decision is recorded in
 * {@code docs/decision-log.md}. This test therefore asserts the password is returned verbatim from
 * {@code getPassword()}, while the non-negotiable invariant of
 * {@link #toStringMasksCleartextPassword()} guarantees the cleartext value never leaks into a
 * diagnostic string.</p>
 *
 * <p><strong>Contract reconciliation (bound to the production source).</strong> The assertions
 * below are bound to the actual {@link CardDemoUserDetails} source, not to any preliminary
 * description: (1) the constructor takes five arguments and the single authority is
 * <em>derived</em> from the validated user type, so the test never supplies an authority and never
 * builds a {@code SimpleGrantedAuthority}; and (2) the production {@code toString()} emits only the
 * class name plus an opaque identity hash (CWE-532, review finding F9), so this test asserts the
 * absence of the cleartext password and of the user identity rather than the presence of a mask
 * token.</p>
 */
class CardDemoUserDetailsTest {

    /**
     * Non-secret unit fixture password (review finding #5): a self-describing test value, not a
     * committed account credential (the real seed password is environment-provided). The exact
     * string is immaterial here &mdash; it is only carried through the constructor and asserted back.
     */
    private static final String PWD = TestCredentials.UNIT_FIXTURE_PASSWORD;

    /**
     * Builds a {@link CardDemoUserDetails} through its production five-argument constructor with
     * fixed placeholder names. The single Spring Security authority is derived inside the
     * constructor from {@code userType} ({@code "A"} maps to {@code ROLE_ADMIN}, {@code "U"} maps
     * to {@code ROLE_USER}), so no authority argument is passed (nor accepted by the class).
     *
     * @param username the user id (SEC-USR-ID)
     * @param password the cleartext password (SEC-USR-PWD)
     * @param userType the raw user type flag (SEC-USR-TYPE); must be {@code "A"} or {@code "U"}
     * @return a fresh principal
     */
    private static CardDemoUserDetails newDetails(String username, String password, String userType) {
        return new CardDemoUserDetails(username, password, userType, "FIRST", "LAST");
    }

    @Test
    @DisplayName("constructor exposes username, password, userType, firstName and lastName via getters")
    void constructorAndGettersExposeAllFields() {
        CardDemoUserDetails details =
                new CardDemoUserDetails("ADMIN001", PWD, "A", "ADMIN", "USER");

        assertThat(details.getUsername()).isEqualTo("ADMIN001");
        assertThat(details.getPassword()).isEqualTo(PWD);
        assertThat(details.getUserType()).isEqualTo("A");
        assertThat(details.getFirstName()).isEqualTo("ADMIN");
        assertThat(details.getLastName()).isEqualTo("USER");
    }

    @Test
    @DisplayName("user type \"A\" derives a single ROLE_ADMIN authority")
    void authoritiesReflectAdminRole() {
        CardDemoUserDetails details = newDetails("ADMIN001", PWD, "A");

        assertThat(details.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    @DisplayName("user type \"U\" derives a single ROLE_USER authority")
    void authoritiesReflectUserRole() {
        CardDemoUserDetails details = newDetails("USER0001", PWD, "U");

        assertThat(details.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_USER");
    }

    @Test
    @DisplayName("all account-status flags return true (USRSEC has no lock, expiry or disable concept)")
    void allAccountStatusFlagsReturnTrue() {
        CardDemoUserDetails details = newDetails("ADMIN001", PWD, "A");

        assertThat(details.isAccountNonExpired()).isTrue();
        assertThat(details.isAccountNonLocked()).isTrue();
        assertThat(details.isCredentialsNonExpired()).isTrue();
        assertThat(details.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("eraseCredentials() nulls the password but preserves username and userType")
    void eraseCredentialsNullsPasswordButPreservesIdentity() {
        CardDemoUserDetails details = newDetails("ADMIN001", PWD, "A");

        details.eraseCredentials();

        assertThat(details.getPassword()).isNull();
        assertThat(details.getUsername()).isEqualTo("ADMIN001");
        assertThat(details.getUserType()).isEqualTo("A");
    }

    @Test
    @DisplayName("getAuthorities() returns an immutable collection")
    void getAuthoritiesIsImmutable() {
        CardDemoUserDetails details = newDetails("ADMIN001", PWD, "A");

        Collection<? extends GrantedAuthority> authorities = details.getAuthorities();

        // The production authority list is built with List.of(...), which is immutable. A wildcard
        // Collection cannot accept a type-checked add(...), so the argument-free clear() is used to
        // prove the collection rejects mutation with UnsupportedOperationException.
        assertThatThrownBy(authorities::clear)
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("toString() never leaks the cleartext password or the user identity")
    void toStringMasksCleartextPassword() {
        CardDemoUserDetails details = newDetails("ADMIN001", "SUPERSECRETPWD", "A");

        String text = details.toString();

        // Non-negotiable invariant: the cleartext password must never appear in toString().
        assertThat(text).doesNotContain("SUPERSECRETPWD");
        // The production toString() emits only the class name plus an opaque identity hash
        // (CWE-532, review finding F9); it deliberately leaks neither the credential nor the
        // user identity, so the username must be absent as well.
        assertThat(text).contains("CardDemoUserDetails");
        assertThat(text).doesNotContain("ADMIN001");
    }

    @Test
    @DisplayName("equals()/hashCode() are keyed on username only")
    void equalsAndHashCodeKeyedOnUsernameOnly() {
        CardDemoUserDetails a = newDetails("ADMIN001", PWD, "A");
        CardDemoUserDetails b = newDetails("ADMIN001", "DIFFERENT", "U");
        CardDemoUserDetails c = newDetails("USER0001", PWD, "U");

        // Same username, but different password, user type and derived authority => still equal.
        assertThat(a).isEqualTo(b);
        assertThat(a).hasSameHashCodeAs(b);

        // Different username => not equal.
        assertThat(a).isNotEqualTo(c);

        // A null and a foreign type exercise the instanceof guard (must not throw ClassCastException).
        assertThat(a).isNotEqualTo(null);
        assertThat(a).isNotEqualTo("ADMIN001");
    }

    @Test
    @DisplayName("constructor rejects a user type other than \"A\" or \"U\" (fail-closed, F24/CWE-269)")
    void constructorRejectsInvalidUserType() {
        assertThatThrownBy(
                () -> new CardDemoUserDetails("ADMIN001", PWD, "Z", "ADMIN", "USER"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("constructor rejects a null username (SEC-USR-ID is the required identity)")
    void constructorRejectsNullUsername() {
        assertThatThrownBy(
                () -> new CardDemoUserDetails(null, PWD, "A", "ADMIN", "USER"))
                .isInstanceOf(NullPointerException.class);
    }
}
