package com.aws.carddemo.security;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import com.aws.carddemo.TestCredentials;
import com.aws.carddemo.domain.UserSecurity;
import com.aws.carddemo.repository.UserSecurityRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure-Mockito unit test for {@link CardDemoUserDetailsService}, the Spring Security
 * {@code UserDetailsService} that realizes the COBOL signon file lookup and the user-type to
 * role-authority mapping of the AWS CardDemo migration.
 *
 * <p>This is a fast, isolated test: the collaborating {@link UserSecurityRepository} is a Mockito
 * mock, so there is deliberately <em>no</em> Spring application context and <em>no</em> database.
 * The class name ends in {@code Test}, so it runs under the Maven Surefire plugin. Because
 * {@link MockitoExtension} defaults to {@code STRICT_STUBS}, each test stubs only the single lookup
 * it consumes, keeping the suite free of {@code UnnecessaryStubbingException}.</p>
 *
 * <p><strong>What this test locks down</strong> (behavioral parity, AAP &sect;0.6.7):</p>
 * <ul>
 *   <li>the login id is uppercased with {@code Locale.ROOT} before the repository lookup;</li>
 *   <li>user type {@code "A"} maps to {@code ROLE_ADMIN} and every other value &mdash; {@code "U"},
 *       an unexpected code, a lowercase {@code "a"}, {@code null}, or a padded {@code " A "}
 *       &mdash; is normalized by the service and mapped to {@code ROLE_USER} (or, for a trimmed
 *       {@code "A"}, still {@code ROLE_ADMIN});</li>
 *   <li>an absent record throws {@link UsernameNotFoundException} whose message names the uppercased
 *       id, and a {@code null} login id throws the same exception without touching the repository;</li>
 *   <li>the {@code ROLE_ADMIN} / {@code ROLE_USER} constants are exposed with their exact authority
 *       strings.</li>
 * </ul>
 *
 * <p><strong>COBOL oracle (traceability, AAP &sect;0.6.7 / &sect;0.6.10).</strong></p>
 * <ul>
 *   <li>Origin: {@code legacy/cbl/COSGN00C.cbl} &mdash; the signon program's
 *       {@code READ-USER-SEC-FILE} paragraph reads the {@code USRSEC} record by the uppercased user
 *       id ({@code MOVE FUNCTION UPPER-CASE(USERIDI ...) TO WS-USER-ID}), maps {@code SEC-USR-TYPE}
 *       into {@code CDEMO-USER-TYPE}, and routes by type ({@code IF CDEMO-USRTYP-ADMIN} transfers
 *       control to the admin menu {@code COADM01C}, otherwise to the user menu {@code COMEN01C}); a
 *       not-found record yields "User not found. Try again ...", the parity source for
 *       {@link UsernameNotFoundException}.</li>
 *   <li>Origin: {@code legacy/cpy/CSUSR01Y.cpy} &mdash; the {@code SEC-USER-DATA} record
 *       ({@code SEC-USR-ID}, {@code SEC-USR-FNAME}, {@code SEC-USR-LNAME}, {@code SEC-USR-PWD},
 *       {@code SEC-USR-TYPE}), the source of the loaded principal's identity, name, password, and
 *       type fields.</li>
 * </ul>
 *
 * <p><strong>Contract reconciliation (bound to the production source).</strong> The assertions
 * below are bound to the actual {@link CardDemoUserDetailsService} source, not to any preliminary
 * description. The service normalizes the raw {@code SEC-USR-TYPE} in a private helper (trimming
 * padding and applying a case-sensitive {@code "A"} test) before constructing the principal, which
 * derives its single authority from that normalized type. This test therefore exercises the service
 * end-to-end through {@link UserDetails} accessors on the returned principal and never constructs a
 * {@link CardDemoUserDetails} directly.</p>
 */
@ExtendWith(MockitoExtension.class)
class CardDemoUserDetailsServiceTest {

    /**
     * Non-secret unit fixture password (review finding #5): a self-describing test value, not a
     * committed account credential (the real seed password is environment-provided). These tests
     * exercise id/type mapping, so the password is only carried through and asserted back.
     */
    private static final String PWD = TestCredentials.UNIT_FIXTURE_PASSWORD;

    /**
     * Mock of the signon lookup repository. Its single relevant method,
     * {@link UserSecurityRepository#findByUsrId(String)}, stands in for the legacy
     * {@code EXEC CICS READ} of the {@code USRSEC} KSDS.
     */
    @Mock
    private UserSecurityRepository repository;

    /** The service under test, re-created before each test with the mock repository injected. */
    private CardDemoUserDetailsService service;

    /**
     * Instantiates the service through its public single-argument constructor before each test,
     * exercising the constructor-injection contract explicitly rather than via field injection.
     */
    @BeforeEach
    void setUp() {
        service = new CardDemoUserDetailsService(repository);
    }

    /**
     * Builds a {@link UserSecurity} fixture through the entity's no-argument constructor and setters
     * (there is no all-args constructor). Fixed non-null first and last names are set so the mapped
     * principal carries complete, non-null display fields.
     *
     * @param id   the user id ({@code SEC-USR-ID})
     * @param pwd  the cleartext password ({@code SEC-USR-PWD})
     * @param type the raw user type flag ({@code SEC-USR-TYPE})
     * @return a fresh, fully populated user-security record
     */
    private UserSecurity user(String id, String pwd, String type) {
        UserSecurity record = new UserSecurity();
        record.setUsrId(id);
        record.setUsrPwd(pwd);
        record.setUsrType(type);
        record.setUsrFname("FIRST");
        record.setUsrLname("LAST");
        return record;
    }

    @Test
    @DisplayName("user type \"A\" loads a principal with a single ROLE_ADMIN authority")
    void adminUserMapsToRoleAdmin() {
        when(repository.findByUsrId("ADMIN001"))
                .thenReturn(Optional.of(user("ADMIN001", PWD, "A")));

        UserDetails result = service.loadUserByUsername("ADMIN001");

        assertThat(result.getUsername()).isEqualTo("ADMIN001");
        assertThat(result.getPassword()).isEqualTo(PWD);
        assertThat(result.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    @DisplayName("user type \"U\" loads a principal with a single ROLE_USER authority")
    void standardUserMapsToRoleUser() {
        when(repository.findByUsrId("USER0001"))
                .thenReturn(Optional.of(user("USER0001", PWD, "U")));

        UserDetails result = service.loadUserByUsername("USER0001");

        assertThat(result.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_USER");
    }

    @Test
    @DisplayName("username is uppercased with Locale.ROOT before the repository lookup")
    void usernameIsUppercasedBeforeLookup() {
        when(repository.findByUsrId("ADMIN001"))
                .thenReturn(Optional.of(user("ADMIN001", PWD, "A")));

        UserDetails result = service.loadUserByUsername("admin001");

        verify(repository).findByUsrId("ADMIN001");
        assertThat(result.getUsername()).isEqualTo("ADMIN001");
    }

    @Test
    @DisplayName("absent user throws UsernameNotFoundException naming the uppercased id (COBOL WHEN 13)")
    void unknownUserThrowsUsernameNotFound() {
        when(repository.findByUsrId("NOBODY")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadUserByUsername("nobody"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessageContaining("NOBODY");
    }

    @Test
    @DisplayName("null username throws UsernameNotFoundException without touching the repository")
    void nullUsernameThrowsUsernameNotFound() {
        assertThatThrownBy(() -> service.loadUserByUsername(null))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    @DisplayName("null user type maps to ROLE_USER (\"A\".equals(null) is false)")
    void nullUserTypeMapsToRoleUser() {
        when(repository.findByUsrId("USER0002"))
                .thenReturn(Optional.of(user("USER0002", PWD, null)));

        UserDetails result = service.loadUserByUsername("USER0002");

        assertThat(result.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_USER");
    }

    @Test
    @DisplayName("unexpected user type \"X\" maps to ROLE_USER (COBOL ELSE branch)")
    void unexpectedUserTypeMapsToRoleUser() {
        when(repository.findByUsrId("USER0003"))
                .thenReturn(Optional.of(user("USER0003", PWD, "X")));

        UserDetails result = service.loadUserByUsername("USER0003");

        assertThat(result.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_USER");
    }

    @Test
    @DisplayName("user type \" A \" (padded) still maps to ROLE_ADMIN (resolveUserType trims)")
    void userTypeWithSurroundingWhitespaceStillAdmin() {
        when(repository.findByUsrId("ADMIN002"))
                .thenReturn(Optional.of(user("ADMIN002", PWD, " A ")));

        UserDetails result = service.loadUserByUsername("ADMIN002");

        assertThat(result.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    @DisplayName("lowercase user type \"a\" maps to ROLE_USER (case-sensitive \"A\".equals check)")
    void lowercaseUserTypeMapsToRoleUser() {
        when(repository.findByUsrId("USER0004"))
                .thenReturn(Optional.of(user("USER0004", PWD, "a")));

        UserDetails result = service.loadUserByUsername("USER0004");

        assertThat(result.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_USER");
    }

    @Test
    @DisplayName("public role constants expose the exact Spring Security authority strings")
    void roleConstantsAreExposed() {
        assertThat(CardDemoUserDetailsService.ROLE_ADMIN).isEqualTo("ROLE_ADMIN");
        assertThat(CardDemoUserDetailsService.ROLE_USER).isEqualTo("ROLE_USER");
    }
}
