package com.aws.carddemo.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import com.aws.carddemo.AbstractPostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end integration test for {@link CardDemoUserDetailsService} against the real, Flyway-seeded
 * {@code user_security} table inside a Testcontainers PostgreSQL instance.
 *
 * <p>Unlike the fast, isolated {@link CardDemoUserDetailsServiceTest} (which mocks the repository and
 * runs under Surefire), this test exercises the <em>full</em> stack: the real Spring application
 * context, the real {@code CardDemoUserDetailsService} bean, the real
 * {@link com.aws.carddemo.repository.UserSecurityRepository} over Hibernate, and the actual
 * {@code user_security} rows materialized by the Flyway migration
 * {@code src/main/resources/db/migration/V2__reference_data.sql}. It proves that the seeded legacy
 * {@code USRSEC} users resolve, through a genuine database read, to the correct Spring Security role
 * authorities &mdash; the Java realization of the COBOL signon parity contract (AAP &sect;0.6.7).</p>
 *
 * <p><strong>Infrastructure (no duplicated config).</strong> This class only extends
 * {@link AbstractPostgresIntegrationTest}; it declares <em>no</em> {@code @SpringBootTest},
 * {@code @Testcontainers}, {@code @ActiveProfiles}, container, or datasource configuration of its
 * own. The shared base starts the single {@code postgres:18-alpine} container, activates the
 * {@code test} profile, and binds the container's JDBC coordinates onto the Spring datasource, so
 * Flyway applies {@code V1__schema.sql} &rarr; {@code V2__reference_data.sql} &rarr;
 * {@code V3__indexes.sql} and Hibernate validates the entity mappings against that schema. The
 * class name deliberately ends in {@code IT} so it runs under the Maven Failsafe plugin (the
 * integration-test/verify phase), disjoint from the {@code *Test} unit suite run by Surefire.</p>
 *
 * <p><strong>Runtime prerequisite.</strong> Execution requires a Testcontainers-capable environment
 * (a reachable Docker daemon able to start {@code postgres:18-alpine}). This is the documented
 * local-validation constraint for the migration; where Docker is unavailable, the same behavioral
 * parity contract is additionally covered fully in-process by {@link CardDemoUserDetailsServiceTest}.</p>
 *
 * <p><strong>Seed oracle.</strong> The assertions below are bound to the exact rows seeded by
 * {@code V2__reference_data.sql}: five administrators ({@code ADMIN001}&ndash;{@code ADMIN005}, user
 * type {@code "A"} &rarr; {@code ROLE_ADMIN}) and five standard users
 * ({@code USER0001}&ndash;{@code USER0005}, user type {@code "U"} &rarr; {@code ROLE_USER}), every one
 * with the cleartext password {@code PASSWORD}. These values were decoded from the native EBCDIC
 * dataset {@code legacy/data/EBCDIC/AWS.M2.CARDDEMO.USRSEC.PS} (cp037, ten 80-byte records).</p>
 *
 * <p><strong>COBOL oracle (traceability, AAP &sect;0.6.7 / &sect;0.6.10).</strong></p>
 * <ul>
 *   <li>Origin: {@code legacy/cbl/COSGN00C.cbl} &mdash; the signon program's
 *       {@code READ-USER-SEC-FILE} paragraph reads the {@code USRSEC} record by the uppercased user
 *       id ({@code MOVE FUNCTION UPPER-CASE(USERIDI ...) TO WS-USER-ID}), moves {@code SEC-USR-TYPE}
 *       into {@code CDEMO-USER-TYPE}, and routes by type ({@code IF CDEMO-USRTYP-ADMIN} transfers
 *       control to the admin menu {@code COADM01C}, otherwise to the user menu {@code COMEN01C}); a
 *       not-found record yields "User not found. Try again ...", the parity source for
 *       {@link UsernameNotFoundException}.</li>
 *   <li>Origin: {@code legacy/cpy/CSUSR01Y.cpy} &mdash; the {@code SEC-USER-DATA} record
 *       ({@code SEC-USR-ID}, {@code SEC-USR-FNAME}, {@code SEC-USR-LNAME}, {@code SEC-USR-PWD},
 *       {@code SEC-USR-TYPE}), whose columns back the {@code user_security} table.</li>
 * </ul>
 *
 * @see CardDemoUserDetailsService
 * @see CardDemoUserDetailsServiceTest
 * @see AbstractPostgresIntegrationTest
 */
class CardDemoUserDetailsServiceIT extends AbstractPostgresIntegrationTest {

    /**
     * The five seeded administrator ids ({@code SEC-USR-TYPE = 'A'} &rarr; {@link
     * CardDemoUserDetailsService#ROLE_ADMIN}), decoded from the legacy {@code USRSEC} dataset and
     * loaded by {@code V2__reference_data.sql}.
     */
    private static final String[] ADMIN_IDS = {
        "ADMIN001", "ADMIN002", "ADMIN003", "ADMIN004", "ADMIN005"
    };

    /**
     * The five seeded standard-user ids ({@code SEC-USR-TYPE = 'U'} &rarr; {@link
     * CardDemoUserDetailsService#ROLE_USER}), decoded from the legacy {@code USRSEC} dataset and
     * loaded by {@code V2__reference_data.sql}.
     */
    private static final String[] USER_IDS = {
        "USER0001", "USER0002", "USER0003", "USER0004", "USER0005"
    };

    /** The cleartext password shared by every seeded user ({@code SEC-USR-PWD}), for parity. */
    private static final String SEEDED_PASSWORD = "PASSWORD";

    /**
     * The real, Spring-managed service under test. Field injection is the standard, warning-free
     * choice for an integration test (the field is set by the Spring TestContext framework, never by
     * user code), so no constructor is declared.
     */
    @Autowired
    private CardDemoUserDetailsService service;

    /**
     * A seeded administrator ({@code ADMIN001}) loads from the real table and is granted exactly the
     * single {@code ROLE_ADMIN} authority, with its identity and cleartext password preserved.
     */
    @Test
    @DisplayName("Seeded admin ADMIN001 loads from the real DB and resolves to ROLE_ADMIN")
    void seededAdminResolvesToRoleAdmin() {
        UserDetails admin = service.loadUserByUsername("ADMIN001");

        assertThat(admin.getUsername()).isEqualTo("ADMIN001");
        assertThat(admin.getPassword()).isEqualTo(SEEDED_PASSWORD);
        assertThat(admin.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly(CardDemoUserDetailsService.ROLE_ADMIN);
    }

    /**
     * A seeded standard user ({@code USER0001}) loads from the real table and is granted exactly the
     * single {@code ROLE_USER} authority, with its identity and cleartext password preserved.
     */
    @Test
    @DisplayName("Seeded user USER0001 loads from the real DB and resolves to ROLE_USER")
    void seededStandardUserResolvesToRoleUser() {
        UserDetails user = service.loadUserByUsername("USER0001");

        assertThat(user.getUsername()).isEqualTo("USER0001");
        assertThat(user.getPassword()).isEqualTo(SEEDED_PASSWORD);
        assertThat(user.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly(CardDemoUserDetailsService.ROLE_USER);
    }

    /**
     * A lower-case login id ({@code admin001}) is uppercased ({@code Locale.ROOT}) before the lookup,
     * matching the uppercase-stored key of the real {@code ADMIN001} row and returning the stored,
     * uppercased identity. This proves the COBOL {@code FUNCTION UPPER-CASE} fold end-to-end against a
     * genuine database row rather than a mock.
     */
    @Test
    @DisplayName("Lower-case login is uppercased and matches the seeded ADMIN001 row")
    void lookupIsCaseInsensitiveViaUppercasing() {
        UserDetails admin = service.loadUserByUsername("admin001");

        assertThat(admin.getUsername()).isEqualTo("ADMIN001");
        assertThat(admin.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly(CardDemoUserDetailsService.ROLE_ADMIN);
    }

    /**
     * An id with no matching row throws {@link UsernameNotFoundException}, the Java equivalent of the
     * COBOL {@code WHEN 13} "User not found. Try again ..." path.
     */
    @Test
    @DisplayName("Unknown user id throws UsernameNotFoundException")
    void unknownUserThrowsUsernameNotFound() {
        assertThatThrownBy(() -> service.loadUserByUsername("ZZZZZZZZ"))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    /**
     * Every seeded user resolves to exactly one role authority: the five {@code ADMIN00x} ids to
     * {@code ROLE_ADMIN} and the five {@code USER000x} ids to {@code ROLE_USER}. Iterating the full
     * seed set guards against any row being mis-typed or granted more than one authority.
     */
    @Test
    @DisplayName("Every seeded user resolves to exactly one role (5 admins, 5 users)")
    void everySeededUserResolvesToExactlyOneRole() {
        for (String adminId : ADMIN_IDS) {
            UserDetails admin = service.loadUserByUsername(adminId);
            assertThat(admin.getUsername()).isEqualTo(adminId);
            assertThat(admin.getAuthorities())
                    .extracting(GrantedAuthority::getAuthority)
                    .containsExactly(CardDemoUserDetailsService.ROLE_ADMIN);
        }

        for (String userId : USER_IDS) {
            UserDetails user = service.loadUserByUsername(userId);
            assertThat(user.getUsername()).isEqualTo(userId);
            assertThat(user.getAuthorities())
                    .extracting(GrantedAuthority::getAuthority)
                    .containsExactly(CardDemoUserDetailsService.ROLE_USER);
        }
    }
}
