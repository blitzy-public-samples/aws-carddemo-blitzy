package com.aws.carddemo.security;

import java.util.Locale;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.aws.carddemo.domain.UserSecurity;
import com.aws.carddemo.repository.UserSecurityRepository;

/**
 * Spring Security {@link UserDetailsService} for AWS CardDemo: the Java realization of the legacy
 * signon file lookup and user-type-to-authority mapping.
 *
 * <p>This service replaces the {@code READ-USER-SEC-FILE} paragraph of the COBOL signon program
 * {@code COSGN00C}: it loads a user from the {@code user_security} table by (uppercased) user id and
 * maps the COBOL {@code SEC-USR-TYPE} flag ({@code "A"} / {@code "U"}) to a single Spring Security
 * role authority ({@code ROLE_ADMIN} / {@code ROLE_USER}), returning a {@link CardDemoUserDetails}
 * principal for the authentication machinery.</p>
 *
 * <p><strong>Responsibility boundary.</strong> This class is the <em>user store and authority
 * producer</em> only. It deliberately does <em>not</em>:</p>
 * <ul>
 *   <li>compare the password &mdash; the legacy {@code IF SEC-USR-PWD = WS-USER-PWD} cleartext
 *       comparison is performed by {@code CardDemoAuthenticationProvider}, which calls this service to
 *       load the principal and then compares the presented (uppercased) password against
 *       {@link CardDemoUserDetails#getPassword()};</li>
 *   <li>define the filter chain, URL rules, or the {@code ROLE_ADMIN}/{@code ROLE_USER} authorization
 *       policy &mdash; that is {@code com.aws.carddemo.config.SecurityConfig} (a sibling package,
 *       created later), which enforces the {@code CDV1}/{@code COCRDSEC} card-detail security variant
 *       (which has no {@code .cbl} program) via URL/method authorization;</li>
 *   <li>perform the post-authentication menu redirect (admin &rarr; {@code COADM01C}/{@code CA00},
 *       user &rarr; {@code COMEN01C}/{@code CM00}) &mdash; that is done by controllers / the security
 *       configuration.</li>
 * </ul>
 *
 * <p><strong>Origin (traceability, AAP &sect;0.6.7 / &sect;0.6.10):</strong></p>
 * <ul>
 *   <li>Origin: {@code legacy/cbl/COSGN00C.cbl} &mdash; paragraph {@code READ-USER-SEC-FILE}
 *       ({@code EXEC CICS READ DATASET('USRSEC') INTO(SEC-USER-DATA) RIDFLD(WS-USER-ID)} then
 *       {@code EVALUATE WS-RESP-CD}: {@code WHEN 0} proceed, {@code WHEN 13} &rarr;
 *       "User not found. Try again ...", {@code WHEN OTHER} &rarr; "Unable to verify the User ...").
 *       The user id is uppercased before the read ({@code MOVE FUNCTION UPPER-CASE(USERIDI ...) TO
 *       WS-USER-ID}), and the type routing is {@code MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE} followed by
 *       {@code IF CDEMO-USRTYP-ADMIN} (88-level {@code VALUE 'A'}) &rarr; admin, else user.</li>
 *   <li>Origin: {@code legacy/cpy/CSUSR01Y.cpy} &mdash; {@code SEC-USER-DATA} record layout
 *       ({@code SEC-USR-ID}, {@code SEC-USR-FNAME}, {@code SEC-USR-LNAME}, {@code SEC-USR-PWD},
 *       {@code SEC-USR-TYPE}).</li>
 * </ul>
 *
 * <p><strong>Component scanning.</strong> Annotated {@link Service}; because
 * {@code CardDemoApplication} is {@code @SpringBootApplication} at the base package
 * {@code com.aws.carddemo}, this bean is discovered automatically and injected wherever a
 * {@link UserDetailsService} is required.</p>
 */
@Service
public class CardDemoUserDetailsService implements UserDetailsService {

    /**
     * Spring Security role granted to an administrator, mapped from COBOL {@code SEC-USR-TYPE = 'A'}
     * (88-level {@code CDEMO-USRTYP-ADMIN}). Exposed as a constant so
     * {@code com.aws.carddemo.config.SecurityConfig}, controllers, and tests can reference the admin
     * role without duplicating the magic string.
     */
    public static final String ROLE_ADMIN = "ROLE_ADMIN";

    /**
     * Spring Security role granted to a standard user, mapped from COBOL {@code SEC-USR-TYPE = 'U'}
     * (88-level {@code CDEMO-USRTYP-USER}) and, faithful to the COBOL {@code ELSE} branch, from every
     * non-admin value. Exposed as a constant for the same no-magic-string reason as
     * {@link #ROLE_ADMIN}.
     */
    public static final String ROLE_USER = "ROLE_USER";

    /**
     * Spring Data repository over the {@code user_security} table; supplies the single signon lookup
     * {@link UserSecurityRepository#findByUsrId(String)} that replaces the legacy
     * {@code EXEC CICS READ} of the {@code USRSEC} VSAM KSDS.
     */
    private final UserSecurityRepository userSecurityRepository;

    /**
     * Creates the service with its repository collaborator. A single constructor is used, so no
     * {@code @Autowired} annotation is required for Spring to perform constructor injection.
     *
     * @param userSecurityRepository the user-security repository (must not be {@code null}); injected
     *                               by Spring
     */
    public CardDemoUserDetailsService(UserSecurityRepository userSecurityRepository) {
        this.userSecurityRepository = userSecurityRepository;
    }

    /**
     * Loads the CardDemo user for the given login id and returns the Spring Security principal,
     * reproducing the COBOL {@code READ-USER-SEC-FILE} lookup and its user-type-to-role mapping.
     *
     * <p>Behavioral parity with {@code COSGN00C}:</p>
     * <ol>
     *   <li>the id is uppercased with {@link Locale#ROOT} before the lookup, mirroring
     *       {@code MOVE FUNCTION UPPER-CASE(USERIDI ...) TO WS-USER-ID}; the seed ids are stored
     *       uppercase, so this fold is what makes the lookup succeed;</li>
     *   <li>an absent record throws {@link UsernameNotFoundException}, mapping COBOL {@code WHEN 13}
     *       ("User not found. Try again ..."); this is kept a distinct exception (not collapsed into a
     *       bad-credentials error) so downstream handling can preserve the COBOL split between
     *       "User not found" and "Wrong Password";</li>
     *   <li>the raw {@code SEC-USR-TYPE} is mapped to a single role authority
     *       ({@code 'A'} &rarr; {@link #ROLE_ADMIN}, every other value &rarr; {@link #ROLE_USER}),
     *       mirroring {@code IF CDEMO-USRTYP-ADMIN ... ELSE ...};</li>
     *   <li>the cleartext password is passed through unmodified &mdash; it is compared later by
     *       {@code CardDemoAuthenticationProvider}, never here.</li>
     * </ol>
     *
     * @param username the login id as entered ({@code USERIDI}); may be any case and is uppercased
     *                 before lookup
     * @return the {@link CardDemoUserDetails} principal for the matching user
     * @throws UsernameNotFoundException if {@code username} is {@code null} or no user record exists
     *                                   for the uppercased id (COBOL {@code WHEN 13})
     */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        // Defensive null guard: fail as "not found" without echoing a null and without risking an NPE.
        if (username == null) {
            throw new UsernameNotFoundException("User not found");
        }

        // COBOL: MOVE FUNCTION UPPER-CASE(USERIDI ...) TO WS-USER-ID (COSGN00C L132-134).
        // Locale.ROOT gives a deterministic, locale-independent uppercase. No trim is applied: COBOL
        // WS-USER-ID is fixed-width PIC X(08) and the DB column is CHAR(8), so trailing-space handling
        // is a sibling concern and is intentionally not performed here.
        String userId = username.toUpperCase(Locale.ROOT);

        // COBOL READ-USER-SEC-FILE: EXEC CICS READ USRSEC RIDFLD(WS-USER-ID). An empty Optional is the
        // Java equivalent of WS-RESP-CD = 13 ("User not found. Try again ..."). The message names the
        // id (operationally meaningful, as in COBOL) and contains no secret.
        UserSecurity user = userSecurityRepository.findByUsrId(userId)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + userId));

        // COBOL: MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE; IF CDEMO-USRTYP-ADMIN (VALUE 'A') -> admin, else
        // -> user. Normalize to a valid 'A'/'U' flag so that CardDemoUserDetails (which derives the
        // authority and fails closed on any other value) always receives an admissible type and every
        // non-admin value routes to ROLE_USER, exactly as the COBOL ELSE branch does.
        String userType = resolveUserType(user.getUsrType());

        // Build the principal. The password (SEC-USR-PWD) is passed through as cleartext for parity;
        // it is NOT compared here. CardDemoUserDetails derives the single ROLE_ADMIN/ROLE_USER
        // authority from the normalized user type.
        return new CardDemoUserDetails(
                user.getUsrId(),
                user.getUsrPwd(),
                userType,
                user.getUsrFname(),
                user.getUsrLname());
    }

    /**
     * Normalizes the raw {@code SEC-USR-TYPE} flag to the canonical {@code "A"} (admin) or {@code "U"}
     * (user) value, mirroring the COBOL {@code IF CDEMO-USRTYP-ADMIN ... ELSE ...} decision
     * (88-level {@code CDEMO-USRTYP-ADMIN VALUE 'A'} in {@code COCOM01Y}).
     *
     * <p>Only an exact {@code "A"} is treated as admin (case-sensitive, matching the COBOL literal);
     * every other value &mdash; {@code "U"}, an unexpected code, blank, or {@code null} &mdash; maps to
     * {@code "U"}, reproducing the COBOL {@code ELSE} branch that routes all non-admin users to the
     * user menu. A {@code trim()} guards against fixed-width {@code CHAR(1)} padding artifacts without
     * changing the single-character semantics. Returning a canonical flag (rather than the raw value)
     * ensures {@link CardDemoUserDetails}, which validates its user type and fails closed on any value
     * other than {@code "A"}/{@code "U"}, never rejects a persisted record.</p>
     *
     * @param rawUsrType the raw user-type flag as stored ({@code SEC-USR-TYPE}); may be {@code null}
     * @return {@link CardDemoUserDetails#USER_TYPE_ADMIN} ({@code "A"}) when the trimmed value is
     *         exactly {@code "A"}; otherwise {@link CardDemoUserDetails#USER_TYPE_USER} ({@code "U"})
     */
    private static String resolveUserType(String rawUsrType) {
        String type = (rawUsrType == null) ? null : rawUsrType.trim();
        boolean admin = CardDemoUserDetails.USER_TYPE_ADMIN.equals(type);
        return admin ? CardDemoUserDetails.USER_TYPE_ADMIN : CardDemoUserDetails.USER_TYPE_USER;
    }
}
