package com.carddemo.security;

import com.carddemo.entity.User;
import com.carddemo.repository.UserRepository;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Spring Security adapter that loads an application user from the relational
 * {@code users} table and exposes it as a framework {@link UserDetails}.
 *
 * <h2>Legacy provenance &mdash; replaces {@code COSGN00C} {@code READ-USER-SEC-FILE}</h2>
 * <p>In the mainframe CardDemo application, the sign-on program
 * {@code app/cbl/COSGN00C.cbl} authenticated a user by reading the VSAM
 * {@code USRSEC} KSDS keyed on the (upper-cased) user id and then comparing the
 * stored plaintext password to the entered one:</p>
 * <pre>
 *     EXEC CICS READ DATASET('USRSEC') INTO(SEC-USER-DATA)
 *          RIDFLD(WS-USER-ID) RESP(WS-RESP-CD) END-EXEC
 *     EVALUATE WS-RESP-CD
 *         WHEN 0   ... IF SEC-USR-PWD = WS-USER-PWD ... (route by user type)
 *         WHEN 13  "User not found"
 *         WHEN OTHER "Unable to verify the User"
 *     END-EVALUATE
 * </pre>
 * <p>Under Spring Security that single COBOL responsibility is deliberately
 * <strong>split in two</strong>:</p>
 * <ol>
 *   <li><strong>This class</strong> reproduces only the keyed <em>read</em> of
 *       the security file ({@code WS-RESP-CD} {@code WHEN 0} &rarr; found;
 *       {@code WHEN 13} &rarr; not found). It loads the user row and surfaces the
 *       stored <em>BCrypt hash</em> plus the resolved authority. It performs
 *       <strong>no</strong> credential comparison.</li>
 *   <li>The framework's {@code DaoAuthenticationProvider} (wired in
 *       {@code SecurityConfig}) performs the password match by invoking
 *       {@code BCryptPasswordEncoder.matches(rawPassword, storedHash)}. This
 *       replaces the legacy plaintext {@code SEC-USR-PWD = WS-USER-PWD}
 *       comparison with a hardened BCrypt verification while preserving the same
 *       login semantics (AAP &sect;0.6.7).</li>
 * </ol>
 *
 * <h2>User-id normalization (parity)</h2>
 * <p>{@code COSGN00C} upper-cases the entered id with
 * {@code FUNCTION UPPER-CASE(USERIDI)} before the keyed read (source lines
 * 132-136). The seed ids ({@code ADMIN001}, {@code USER0001}) are stored
 * upper-case, so {@link #loadUserByUsername(String)} upper-cases (and trims) the
 * incoming username before {@link UserRepository#findById(Object) findById} to
 * keep authentication case-insensitive exactly as the mainframe behaved.</p>
 *
 * <h2>User type &rarr; authority mapping (parity)</h2>
 * <p>{@code COSGN00C} routed the session by user type
 * ({@code IF CDEMO-USRTYP-ADMIN} &rarr; {@code XCTL 'COADM01C'} else
 * {@code XCTL 'COMEN01C'}). That branch becomes a single Spring authority:
 * user type {@code 'A'} &rarr; {@link #ROLE_ADMIN}, every other (or absent)
 * value &rarr; {@link #ROLE_USER}. The {@code ROLE_} prefix is mandatory so that
 * {@code @PreAuthorize("hasRole('ADMIN')")} and {@code SecurityConfig}
 * {@code hasRole("ADMIN")} rules resolve against {@code ROLE_ADMIN}. These
 * values intentionally match the constants published by {@code JwtTokenProvider}
 * and {@code JwtAuthenticationFilter} so the authority is identical whether a
 * principal is established via initial sign-on (this class) or via a bearer
 * token (the filter).</p>
 *
 * <h2>Statelessness</h2>
 * <p>The COBOL {@code COMMAREA} session hand-off is replaced by a stateless JWT
 * (AAP &sect;0.6.7). This service therefore holds no conversational state; it is
 * a singleton, thread-safe (its only collaborator is the thread-safe
 * {@link UserRepository}), and is consulted only during the authentication
 * exchange.</p>
 *
 * @see <a href="file:app/cbl/COSGN00C.cbl">app/cbl/COSGN00C.cbl (source-of-truth)</a>
 * @see <a href="file:app/cpy/CSUSR01Y.cpy">app/cpy/CSUSR01Y.cpy (record layout)</a>
 * @see UserDetailsService
 * @see User
 * @see UserRepository
 */
@Service
public class CustomUserDetailsService implements UserDetailsService {

    /**
     * Legacy {@code SEC-USR-TYPE} value for an administrator (88-level
     * {@code CDEMO-USRTYP-ADMIN}). Matches {@code JwtTokenProvider.USER_TYPE_ADMIN}.
     */
    static final String USER_TYPE_ADMIN = "A";

    /**
     * Spring Security authority granted to administrators (user type {@code 'A'}).
     * The {@code ROLE_} prefix is required by {@code hasRole(...)} semantics and
     * matches {@code JwtTokenProvider.ROLE_ADMIN}.
     */
    static final String ROLE_ADMIN = "ROLE_ADMIN";

    /**
     * Spring Security authority granted to regular users (any non-admin type).
     * Matches {@code JwtTokenProvider.ROLE_USER}.
     */
    static final String ROLE_USER = "ROLE_USER";

    /**
     * Data-access collaborator for the {@code users} table. Replaces the COBOL
     * keyed read of the VSAM {@code USRSEC} dataset.
     */
    private final UserRepository userRepository;

    /**
     * Constructor injection of the user repository.
     *
     * <p>Per AAP &sect;0.3.2 the migration uses constructor injection throughout,
     * replacing the COBOL static {@code CALL}/{@code XCTL} linkage with explicit,
     * immutable dependencies. A single constructor is auto-detected by Spring, so
     * no {@code @Autowired} annotation is necessary. The field is {@code final},
     * making the service immutable and trivially thread-safe.</p>
     *
     * @param userRepository Spring Data repository for {@link User}; never
     *                       {@code null}
     */
    public CustomUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Loads the user identified by {@code username} and adapts it into a Spring
     * Security {@link UserDetails}.
     *
     * <p>This is the {@code WHEN 0}/{@code WHEN 13} half of the legacy
     * {@code READ-USER-SEC-FILE} paragraph. The supplied username is first
     * <strong>trimmed and upper-cased</strong> (mirroring
     * {@code FUNCTION UPPER-CASE} in {@code COSGN00C} lines 132-136) and then
     * looked up by primary key via {@link UserRepository#findById(Object)
     * findById(String)}. A {@code null} username degrades to the empty string so
     * the lookup misses cleanly rather than throwing.</p>
     *
     * <p>If the user is absent, a {@link UsernameNotFoundException} is thrown
     * &mdash; the analogue of {@code WS-RESP-CD = 13} ("User not found"). The
     * message deliberately reveals only that the id was not found; whether a
     * <em>password</em> was wrong is never decided here (that is the
     * authentication provider's concern), so no extra information is leaked.</p>
     *
     * <p>On success, the returned principal carries:</p>
     * <ul>
     *   <li><strong>username</strong> = {@link User#getUserId()} (the stored,
     *       upper-case id);</li>
     *   <li><strong>password</strong> = {@link User#getPassword()}, i.e. the
     *       stored <em>BCrypt hash</em> &mdash; never plaintext. The
     *       {@code DaoAuthenticationProvider} compares the raw login password to
     *       this hash with {@code BCryptPasswordEncoder.matches(...)};</li>
     *   <li>a single {@link GrantedAuthority} resolved from the user type
     *       (see {@link #mapUserTypeToAuthority(String)});</li>
     *   <li>all account status flags set to {@code true}
     *       (enabled / account-non-expired / credentials-non-expired /
     *       account-non-locked) &mdash; the legacy security file modelled no
     *       lock, expiry, or disabled state.</li>
     * </ul>
     *
     * @param username the login user id as entered (case-insensitive); may be
     *                 {@code null}
     * @return a fully populated {@link UserDetails} for the located user
     * @throws UsernameNotFoundException if no {@code users} row exists for the
     *                                   normalized id (legacy {@code WS-RESP-CD = 13})
     */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        // Parity with COSGN00C lines 132-136: MOVE FUNCTION UPPER-CASE(USERIDI) TO WS-USER-ID.
        // Trim guards against trailing spaces from fixed-width clients; ROOT locale avoids
        // locale-sensitive casing surprises (e.g. the Turkish dotless-i).
        final String userId = (username == null)
                ? ""
                : username.trim().toUpperCase(Locale.ROOT);

        // Reproduces the keyed VSAM READ on USRSEC. UserRepository has no findByUserId
        // because userId IS the @Id, so the inherited findById(String) is the correct path.
        final User user = userRepository.findById(userId)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + userId));

        // Map SEC-USR-TYPE -> Spring authority (COSGN00C admin/user XCTL routing).
        final GrantedAuthority authority = mapUserTypeToAuthority(user.getUserType());

        // Build a framework principal. The Spring concrete type is fully qualified to
        // avoid clashing with the imported com.carddemo.entity.User. The password is the
        // stored BCrypt hash; this class never verifies it (delegated to the provider).
        // The four boolean flags (enabled, accountNonExpired, credentialsNonExpired,
        // accountNonLocked) are all true: the legacy USRSEC record had no such states.
        return new org.springframework.security.core.userdetails.User(
                user.getUserId(),
                user.getPassword(),
                true,
                true,
                true,
                true,
                List.of(authority));
    }

    /**
     * Maps a legacy {@code SEC-USR-TYPE} code to a single Spring Security
     * authority, reproducing the {@code COSGN00C} routing
     * ({@code IF CDEMO-USRTYP-ADMIN ... ELSE ...}).
     *
     * <p>The backing column is {@code CHAR(1)} and may therefore arrive
     * space-padded or {@code null}; the value is trimmed and upper-cased
     * (ROOT locale) before comparison. Type {@code 'A'} yields
     * {@link #ROLE_ADMIN}; every other value &mdash; including {@code 'U'},
     * blanks, and {@code null} &mdash; yields {@link #ROLE_USER}, matching the
     * defensive default used by {@code JwtTokenProvider}/{@code JwtAuthenticationFilter}.</p>
     *
     * @param userType the raw {@code user_type} value from the entity; may be
     *                 {@code null}
     * @return a {@link SimpleGrantedAuthority} of {@link #ROLE_ADMIN} or
     *         {@link #ROLE_USER}
     */
    private GrantedAuthority mapUserTypeToAuthority(String userType) {
        final String normalized = (userType == null)
                ? ""
                : userType.trim().toUpperCase(Locale.ROOT);
        if (USER_TYPE_ADMIN.equals(normalized)) {
            return new SimpleGrantedAuthority(ROLE_ADMIN);
        }
        return new SimpleGrantedAuthority(ROLE_USER);
    }
}
