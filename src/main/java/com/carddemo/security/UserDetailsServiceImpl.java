package com.carddemo.security;

import com.carddemo.entity.User;
import com.carddemo.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spring Security {@link UserDetailsService} implementation that loads user
 * credentials from the {@link UserRepository}, replacing the COBOL VSAM keyed
 * read on the {@code USRSEC} dataset performed by the legacy signon program.
 *
 * <h2>Origin &mdash; what this replaces</h2>
 *
 * <p>In the legacy mainframe system, the signon program
 * {@code app/cbl/COSGN00C.cbl} authenticated a user by performing a keyed VSAM
 * read against the {@code USRSEC} security file in paragraph
 * {@code READ-USER-SEC-FILE} (lines 209-219):</p>
 *
 * <pre>
 *     READ-USER-SEC-FILE.
 *         EXEC CICS READ
 *              DATASET   (WS-USRSEC-FILE)
 *              INTO      (SEC-USER-DATA)
 *              LENGTH    (LENGTH OF SEC-USER-DATA)
 *              RIDFLD    (WS-USER-ID)
 *              KEYLENGTH (LENGTH OF WS-USER-ID)
 *              RESP      (WS-RESP-CD)
 *              RESP2     (WS-REAS-CD)
 *         END-EXEC.
 * </pre>
 *
 * <p>The COBOL {@code RIDFLD(WS-USER-ID)} keyed read maps directly to
 * {@code UserRepository.findById(username)}. The subsequent
 * {@code EVALUATE WS-RESP-CD} block (COSGN00C lines 221-257) drove the legacy
 * error handling, and its response codes map onto this method's behaviour:</p>
 *
 * <table border="1">
 *   <caption>COSGN00C {@code WS-RESP-CD} &rarr; Java mapping</caption>
 *   <tr><th>CICS {@code WS-RESP-CD}</th><th>COBOL meaning</th><th>Java behaviour</th></tr>
 *   <tr>
 *     <td>{@code 0} (DFHRESP NORMAL)</td>
 *     <td>Record found; COBOL then compares {@code SEC-USR-PWD = WS-USER-PWD}</td>
 *     <td>Present {@code Optional}; the {@link User} entity is returned. Password
 *         verification is <em>not</em> done here &mdash; see PR-17 below.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code 13} (DFHRESP NOTFND)</td>
 *     <td>"User not found. Try again ..." (COSGN00C line 249)</td>
 *     <td>{@code Optional.empty()} &rarr; {@link UsernameNotFoundException}, the
 *         standard Spring Security exception for an absent principal.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code OTHER}</td>
 *     <td>"Unable to verify the User ..." (COSGN00C line 254)</td>
 *     <td>Any genuine data-access failure propagates as the provider-specific
 *         {@code DataAccessException} from the repository layer; an absent record
 *         is normalized to {@link UsernameNotFoundException} per the Spring
 *         Security contract.</td>
 *   </tr>
 * </table>
 *
 * <h2>Design notes and preservation rules</h2>
 *
 * <p><strong>Returns the {@link User} entity directly.</strong> The
 * {@code User} entity itself implements
 * {@link org.springframework.security.core.userdetails.UserDetails}, so no DTO
 * mapping or custom {@code UserDetails} wrapper is required &mdash; the entity is
 * returned straight to the caller (typically Spring Security's
 * {@code DaoAuthenticationProvider}).</p>
 *
 * <p><strong>PR-17 &mdash; no password handling here.</strong> The user's stored
 * password is a 60-character BCrypt hash ({@code User.secUsrPwd}). This service
 * performs <em>no</em> password comparison; the legacy plaintext check
 * {@code IF SEC-USR-PWD = WS-USER-PWD} (COSGN00C line 223) is replaced by
 * {@code BCryptPasswordEncoder.matches(rawPassword, storedHash)} executed inside
 * Spring Security's {@code DaoAuthenticationProvider}. No password or other
 * sensitive value is ever logged.</p>
 *
 * <p><strong>PR-19 &mdash; role mapping owned by the entity.</strong> The
 * {@code userType} &rarr; authority mapping ({@code 'A'} &rarr; {@code ROLE_ADMIN},
 * {@code 'U'} &rarr; {@code ROLE_USER}) is implemented in
 * {@code User.getAuthorities()} and is therefore <em>not</em> duplicated here.</p>
 *
 * <p><strong>Case sensitivity (AAP &sect;0.6.8).</strong> The legacy program
 * normalized the entered user ID to uppercase
 * ({@code MOVE FUNCTION UPPER-CASE(USERIDI) TO WS-USER-ID}, COSGN00C line 132).
 * This service deliberately does <em>not</em> perform that conversion: the caller
 * (typically {@code AuthService}, or Spring Security's
 * {@code DaoAuthenticationProvider}) is responsible for normalizing the username
 * before invoking {@link #loadUserByUsername(String)}. Keeping the conversion out
 * of this method preserves a simple, single-responsibility lookup contract that
 * aligns with the {@code DaoAuthenticationProvider} expectations.</p>
 *
 * <p><strong>PR-28 &mdash; Jakarta EE baseline.</strong> No {@code javax.*}
 * imports are used. The transactional boundary uses Spring's
 * {@link org.springframework.transaction.annotation.Transactional}.</p>
 *
 * <p><strong>PR-29 &mdash; constructor injection.</strong> The sole collaborator,
 * {@link UserRepository}, is a {@code final} field injected via the constructor
 * generated by Lombok {@link lombok.RequiredArgsConstructor &#64;RequiredArgsConstructor};
 * no field injection ({@code @Autowired}) is used.</p>
 *
 * <p>The class is annotated {@link org.springframework.transaction.annotation.Transactional &#64;Transactional}{@code (readOnly = true)}
 * to bracket the lookup in a read-only transaction, mirroring the read-only
 * semantics of the original {@code READ-USER-SEC-FILE} (a CICS {@code READ} with
 * no {@code UPDATE}/{@code REWRITE}).</p>
 *
 * @see org.springframework.security.core.userdetails.UserDetailsService
 * @see com.carddemo.entity.User
 * @see com.carddemo.repository.UserRepository
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class UserDetailsServiceImpl implements UserDetailsService {

    /**
     * Spring Data JPA repository used to perform the keyed lookup
     * {@code findById(username)}, replacing the
     * {@code EXEC CICS READ DATASET(WS-USRSEC-FILE) RIDFLD(WS-USER-ID)} keyed VSAM
     * read in {@code COSGN00C} {@code READ-USER-SEC-FILE}. Injected via the
     * Lombok-generated constructor (PR-29).
     */
    private final UserRepository userRepository;

    /**
     * Loads a user by user ID, replacing the COBOL {@code READ-USER-SEC-FILE}
     * keyed VSAM read ({@code app/cbl/COSGN00C.cbl} lines 209-219).
     *
     * <p>The lookup is delegated to {@link UserRepository#findById(Object)} keyed
     * by the user ID (the {@code String} primary key, matching COBOL
     * {@code SEC-USR-ID PIC X(08)}). The returned {@link User} entity already
     * implements {@link UserDetails}, so it is returned directly. An absent record
     * &mdash; equivalent to CICS {@code WS-RESP-CD = 13} (NOTFND) &mdash; is
     * translated to {@link UsernameNotFoundException}.</p>
     *
     * <p>This method does not normalize the case of {@code username} (see the
     * class JavaDoc, AAP &sect;0.6.8) and does not perform any password comparison
     * (PR-17). Successful loads are logged at {@code DEBUG}; a {@code null}/blank
     * argument and not-found lookups are logged at {@code WARN}. No password or
     * other sensitive data is ever logged.</p>
     *
     * @param username the user ID to load (expected to already be normalized to
     *                 uppercase by the caller); must not be {@code null} or blank
     * @return the {@link UserDetails} for the user &mdash; the {@link User} entity
     *         itself, which implements {@link UserDetails}
     * @throws UsernameNotFoundException if {@code username} is {@code null}/blank,
     *                                   or no user exists with the given ID
     *                                   (equivalent to CICS {@code RESP = 13})
     */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        log.debug("Loading UserDetails for username: {}", username);

        if (username == null || username.isBlank()) {
            log.warn("loadUserByUsername invoked with null/blank username");
            throw new UsernameNotFoundException("Username must not be null or blank");
        }

        return userRepository.findById(username)
                .map(user -> (UserDetails) user)
                .orElseThrow(() -> {
                    log.warn("User not found: {}", username);
                    return new UsernameNotFoundException("User not found: " + username);
                });
    }
}
