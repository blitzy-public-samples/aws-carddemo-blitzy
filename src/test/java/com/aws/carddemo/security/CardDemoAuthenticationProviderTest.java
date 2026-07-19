package com.aws.carddemo.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Pure-Mockito unit test for {@link CardDemoAuthenticationProvider}, the custom Spring Security
 * {@link org.springframework.security.authentication.AuthenticationProvider} that reproduces the AWS
 * CardDemo signon program's <em>cleartext</em> password comparison for 100% functional parity
 * (AAP &sect;0.6.7).
 *
 * <p>This is the most behaviorally critical security test in the migration: it locks the COBOL
 * {@code IF SEC-USR-PWD = WS-USER-PWD} comparison and its <em>distinct</em> wrong-password versus
 * user-not-found outcomes, and it guards against any accidental reintroduction of a
 * {@code PasswordEncoder} or password hashing (neither of which may ever participate &mdash; the
 * comparison is a cleartext {@link String#equals(Object)} after an uppercase fold and trailing-space
 * strip).</p>
 *
 * <p>This is a fast, isolated unit test: the collaborating {@link CardDemoUserDetailsService} is a
 * Mockito mock, so there is deliberately <em>no</em> Spring application context and <em>no</em>
 * database. The class name ends in {@code Test}, so it runs under the Maven Surefire plugin. Because
 * {@link MockitoExtension} defaults to {@code STRICT_STUBS}, each test stubs only the single lookup it
 * consumes; the null-credentials and {@code supports(...)} tests stub nothing, since the provider
 * never reaches the user store on those paths, keeping the suite free of
 * {@code UnnecessaryStubbingException}.</p>
 *
 * <p><strong>What this test locks down</strong> (behavioral parity, AAP &sect;0.6.7):</p>
 * <ul>
 *   <li>a correct password authenticates and the success token carries {@code null} credentials while
 *       preserving the principal's derived authority;</li>
 *   <li>the presented password is uppercased with {@code Locale.ROOT} before the compare, so a
 *       lowercase entry still matches the (uppercase) stored value;</li>
 *   <li>both operands are compared with trailing whitespace stripped, emulating the fixed-width
 *       {@code PIC X(08)} space-padded equality;</li>
 *   <li>a wrong or absent password raises {@link BadCredentialsException};</li>
 *   <li>an unknown user's {@link UsernameNotFoundException} <em>propagates unchanged</em> (it is not
 *       masked as a bad-credentials error), keeping the COBOL "User not found" path distinct from the
 *       "Wrong Password" path;</li>
 *   <li>{@link CardDemoAuthenticationProvider#supports(Class)} accepts only
 *       {@link UsernamePasswordAuthenticationToken} (and its subtypes).</li>
 * </ul>
 *
 * <p><strong>COBOL oracle (traceability, AAP &sect;0.6.7 / &sect;0.6.10).</strong></p>
 * <ul>
 *   <li>Origin: {@code legacy/cbl/COSGN00C.cbl} (L205-245) &mdash; the {@code READ-USER-SEC-FILE}
 *       paragraph performs the cleartext compare {@code IF SEC-USR-PWD = WS-USER-PWD} (L223) after the
 *       entered password is uppercased ({@code MOVE FUNCTION UPPER-CASE(PASSWDI ...) TO WS-USER-PWD});
 *       a match routes by user type ({@code IF CDEMO-USRTYP-ADMIN} transfers control to
 *       {@code COADM01C}, otherwise to {@code COMEN01C}), a mismatch drives
 *       {@code 'Wrong Password. Try again ...'} (L242), and a missing record is the distinct
 *       {@code WHEN 13 'User not found. Try again ...'} (L247-249).</li>
 *   <li>Origin: {@code legacy/cpy/CSUSR01Y.cpy} &mdash; {@code SEC-USR-PWD PIC X(08)} is a fixed-width,
 *       space-padded 8-byte field, which is why the provider (and these tests) compare both operands
 *       with trailing spaces stripped.</li>
 * </ul>
 *
 * <p><strong>Contract reconciliation (bound to the production source).</strong> The stub principals
 * are built through the actual {@link CardDemoUserDetails} constructor, which takes
 * {@code (username, password, userType, firstName, lastName)} and <em>derives</em> the single Spring
 * Security authority from the validated user type ({@code "A"} &rarr; {@code ROLE_ADMIN},
 * {@code "U"} &rarr; {@code ROLE_USER}); the authority is not supplied independently. Assertions are
 * therefore bound to the real provider behavior verified in
 * {@code src/main/java/com/aws/carddemo/security/CardDemoAuthenticationProvider.java}, not to any
 * preliminary description.</p>
 */
@ExtendWith(MockitoExtension.class)
class CardDemoAuthenticationProviderTest {

    /**
     * Mock of the user store and authority producer. It stands in for the legacy
     * {@code READ-USER-SEC-FILE} {@code EXEC CICS READ} of the {@code USRSEC} KSDS; its single relevant
     * method, {@link CardDemoUserDetailsService#loadUserByUsername(String)}, is stubbed per test to
     * return a {@link CardDemoUserDetails} principal or to throw {@link UsernameNotFoundException}.
     */
    @Mock
    private CardDemoUserDetailsService userDetailsService;

    /** The provider under test, re-created before each test with the mock user-details service. */
    private CardDemoAuthenticationProvider provider;

    /**
     * Instantiates the provider through its public single-argument constructor before each test,
     * exercising the constructor-injection contract explicitly.
     */
    @BeforeEach
    void setUp() {
        provider = new CardDemoAuthenticationProvider(userDetailsService);
    }

    /**
     * Builds a {@link CardDemoUserDetails} stub principal through the production constructor. The
     * constructor derives the single Spring Security authority from {@code userType} ({@code "A"}
     * &rarr; {@code ROLE_ADMIN}, {@code "U"} &rarr; {@code ROLE_USER}), so no authority is passed here;
     * fixed non-null first and last names are supplied for completeness.
     *
     * @param username       the user id ({@code SEC-USR-ID}); becomes {@link CardDemoUserDetails#getUsername()}
     * @param storedPassword the stored cleartext password ({@code SEC-USR-PWD}), possibly space-padded
     * @param userType       the raw user type flag ({@code SEC-USR-TYPE}); must be {@code "A"} or {@code "U"}
     * @return a fresh principal whose authority is derived from {@code userType}
     */
    private CardDemoUserDetails principal(String username, String storedPassword, String userType) {
        return new CardDemoUserDetails(username, storedPassword, userType, "FIRST", "LAST");
    }

    @Test
    @DisplayName("correct password authenticates admin; success token has null credentials and ROLE_ADMIN")
    void correctPasswordAuthenticatesAdminWithNulledCredentials() {
        when(userDetailsService.loadUserByUsername("ADMIN001"))
                .thenReturn(principal("ADMIN001", "PASSWORD", "A"));

        Authentication req = new UsernamePasswordAuthenticationToken("ADMIN001", "PASSWORD");
        Authentication result = provider.authenticate(req);

        assertThat(result.isAuthenticated()).isTrue();
        // The provider passes null credentials into the success token so the cleartext password is not
        // retained in the security context (principal-password erasure is ProviderManager's job).
        assertThat(result.getCredentials()).isNull();
        assertThat(result.getName()).isEqualTo("ADMIN001");
        assertThat(result.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    @DisplayName("presented password is uppercased (Locale.ROOT) before compare; lowercase input still matches")
    void presentedPasswordIsUppercasedBeforeCompare() {
        when(userDetailsService.loadUserByUsername("USER0001"))
                .thenReturn(principal("USER0001", "PASSWORD", "U"));

        // Entered password is lowercase; the provider folds it to "PASSWORD" before the compare
        // (COBOL MOVE FUNCTION UPPER-CASE(PASSWDI ...) TO WS-USER-PWD).
        Authentication req = new UsernamePasswordAuthenticationToken("USER0001", "password");
        Authentication result = provider.authenticate(req);

        assertThat(result.isAuthenticated()).isTrue();
        assertThat(result.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_USER");
    }

    @Test
    @DisplayName("trailing spaces on the stored PIC X(08) password are tolerated (stripTrailing parity)")
    void trailingSpacesToleratedForFixedWidthParity() {
        // Stored password simulates a fixed-width PIC X(08) value: "SECRET" padded with two spaces.
        when(userDetailsService.loadUserByUsername("USER0002"))
                .thenReturn(principal("USER0002", "SECRET  ", "U"));

        Authentication req = new UsernamePasswordAuthenticationToken("USER0002", "SECRET");
        Authentication result = provider.authenticate(req);

        assertThat(result.isAuthenticated()).isTrue();
    }

    @Test
    @DisplayName("wrong password throws BadCredentialsException (COBOL 'Wrong Password. Try again ...')")
    void wrongPasswordThrowsBadCredentials() {
        when(userDetailsService.loadUserByUsername("ADMIN001"))
                .thenReturn(principal("ADMIN001", "PASSWORD", "A"));

        Authentication req = new UsernamePasswordAuthenticationToken("ADMIN001", "WRONGPWD");

        assertThatThrownBy(() -> provider.authenticate(req))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    @DisplayName("null credentials throw BadCredentialsException before the user store is consulted")
    void nullCredentialsThrowBadCredentials() {
        // No stubbing: the null-credentials guard precedes loadUserByUsername, so the mock is untouched.
        Authentication req = new UsernamePasswordAuthenticationToken("ADMIN001", null);

        assertThatThrownBy(() -> provider.authenticate(req))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    @DisplayName("unknown user propagates UsernameNotFoundException, not BadCredentialsException (COBOL WHEN 13)")
    void unknownUserPropagatesUsernameNotFound() {
        when(userDetailsService.loadUserByUsername("GHOST"))
                .thenThrow(new UsernameNotFoundException("User not found: GHOST"));

        // Credentials are non-null so the provider proceeds to the lookup; the service's
        // UsernameNotFoundException must propagate unchanged to keep "User not found" distinct from
        // "Wrong Password" (COBOL WHEN 13 vs the wrong-password ELSE branch).
        Authentication req = new UsernamePasswordAuthenticationToken("GHOST", "PASSWORD");

        assertThatThrownBy(() -> provider.authenticate(req))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    @DisplayName("supports() accepts only UsernamePasswordAuthenticationToken and its subtypes")
    void supportsUsernamePasswordAuthenticationToken() {
        assertThat(provider.supports(UsernamePasswordAuthenticationToken.class)).isTrue();
        assertThat(provider.supports(Authentication.class)).isFalse();
        assertThat(provider.supports(Object.class)).isFalse();
    }
}
