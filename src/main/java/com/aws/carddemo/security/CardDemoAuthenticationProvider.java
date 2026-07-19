package com.aws.carddemo.security;

import java.util.Locale;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;

/**
 * Custom Spring Security {@link AuthenticationProvider} for AWS CardDemo that preserves the legacy
 * COBOL signon program's <em>cleartext password comparison</em> for 100% functional parity.
 *
 * <p>This provider is the Java realization of the {@code READ-USER-SEC-FILE} decision in the signon
 * program {@code COSGN00C}: after the user record is read from {@code USRSEC}, the program compares
 * the stored password to the entered password with {@code IF SEC-USR-PWD = WS-USER-PWD}
 * ({@code legacy/cbl/COSGN00C.cbl} L223); a mismatch drives the
 * {@code ELSE 'Wrong Password. Try again ...'} branch (L241-242). The entered password is uppercased
 * before the comparison ({@code MOVE FUNCTION UPPER-CASE(PASSWDI ...) TO WS-USER-PWD}, L135-136),
 * while the stored value {@code SEC-USR-PWD} is never uppercased.</p>
 *
 * <p><strong>Why a custom provider (rationale in {@code docs/decision-log.md}).</strong> The seed
 * passwords are stored cleartext in {@code USRSEC} and the COBOL compares them literally. The
 * idiomatic Spring alternatives are deliberately rejected: {@code NoOpPasswordEncoder} is deprecated
 * (its use would break the zero-warning build), and {@code DaoAuthenticationProvider} combined with a
 * {@code DelegatingPasswordEncoder} expects a {@code {id}} prefix on the stored password, which would
 * break cleartext parity. This class is the authentication-provider glue that realizes the
 * cleartext-comparison behavior sanctioned by the security package requirements. The parity concern is
 * strictly the <em>comparison behavior</em>; hardcoded credentials are not introduced anywhere
 * (AAP &sect;0.7.1), and introducing password hashing is recorded as a suggested next task rather than
 * a silent behavior change (AAP &sect;0.6.7).</p>
 *
 * <p><strong>Collaboration.</strong> User lookup and user-type-to-authority mapping are delegated to
 * {@link CardDemoUserDetailsService} (same package): it uppercases the id, reads the user store, and
 * returns a {@link CardDemoUserDetails} principal, throwing
 * {@link org.springframework.security.core.userdetails.UsernameNotFoundException} when the id is
 * absent. This provider only performs the password comparison and, on success, produces an
 * authenticated {@link UsernamePasswordAuthenticationToken}.</p>
 *
 * <p><strong>Exception parity (AAP &sect;0.6.5 / &sect;0.6.7).</strong> The COBOL split between
 * {@code WHEN 13 'User not found. Try again ...'} (L247-249) and the wrong-password branch (L242) is
 * behaviorally observable and must be preserved. Accordingly this provider lets the service's
 * {@code UsernameNotFoundException} propagate unchanged (it is never caught and re-thrown as a
 * bad-credentials error), and throws {@link BadCredentialsException} only for a wrong or absent
 * password. Message rendering ("User not found ..." vs "Wrong Password ...") is the web tier's
 * responsibility; this provider only chooses the correct exception <em>type</em>.</p>
 *
 * <p><strong>Downstream wiring (informational; implemented elsewhere).</strong>
 * {@code com.aws.carddemo.config.SecurityConfig} (a sibling {@code config} package, created later)
 * registers this provider on the {@code HttpSecurity} via
 * {@code http.authenticationProvider(cardDemoAuthenticationProvider)} (injected by type) and must
 * <em>not</em> also configure a {@code PasswordEncoder} / {@code DaoAuthenticationProvider}, which
 * would conflict with this cleartext provider and risk deprecation warnings. Form login surfaces the
 * signon screen ({@code COSGN00} / transaction {@code CC00}); the security configuration or controller
 * may distinguish the two exception types to reproduce the exact COBOL messages.</p>
 *
 * <p><strong>Component scanning.</strong> Annotated {@link Component}; because
 * {@code CardDemoApplication} is {@code @SpringBootApplication} at the base package
 * {@code com.aws.carddemo}, this bean is discovered automatically and made available to the security
 * configuration.</p>
 */
@Component
public class CardDemoAuthenticationProvider implements AuthenticationProvider {

    /**
     * Generic authentication-failure message used for both a wrong password and absent credentials. It
     * is intentionally non-specific and never echoes the attempted password, so no secret can leak into
     * logs or error responses; the COBOL-specific "Wrong Password. Try again ..." wording is applied by
     * the web tier based on the {@link BadCredentialsException} type.
     */
    private static final String INVALID_CREDENTIALS_MESSAGE = "Invalid credentials";

    /**
     * The user store and authority producer that loads the {@link CardDemoUserDetails} principal. This
     * is the Java stand-in for the COBOL {@code EXEC CICS READ DATASET('USRSEC')} performed by
     * {@code READ-USER-SEC-FILE}; it always returns a {@link CardDemoUserDetails} (or throws
     * {@code UsernameNotFoundException}), which is what makes the narrowing cast in
     * {@link #authenticate(Authentication)} safe.
     */
    private final CardDemoUserDetailsService userDetailsService;

    /**
     * Creates the provider with its user-details service collaborator. A single constructor is used, so
     * Spring performs constructor injection without an explicit {@code @Autowired} annotation.
     *
     * @param userDetailsService the CardDemo user-details service (must not be {@code null}); injected
     *                           by Spring
     */
    public CardDemoAuthenticationProvider(CardDemoUserDetailsService userDetailsService) {
        this.userDetailsService = userDetailsService;
    }

    /**
     * Authenticates the presented credentials by reproducing the COBOL signon comparison
     * {@code IF SEC-USR-PWD = WS-USER-PWD} ({@code legacy/cbl/COSGN00C.cbl} L223).
     *
     * <p>Behavioral parity with {@code COSGN00C}:</p>
     * <ol>
     *   <li>absent credentials are treated as a failed password check and raise
     *       {@link BadCredentialsException} (the web tier separately renders the
     *       "Please enter Password ..." prompt for an empty field);</li>
     *   <li>the principal is loaded via {@link CardDemoUserDetailsService#loadUserByUsername(String)};
     *       when the id is unknown its {@code UsernameNotFoundException} propagates unchanged, keeping
     *       the COBOL {@code WHEN 13} ("User not found ...") path distinct from the wrong-password
     *       branch;</li>
     *   <li>the presented password is uppercased with {@link Locale#ROOT} (mirroring
     *       {@code MOVE FUNCTION UPPER-CASE(PASSWDI ...) TO WS-USER-PWD}, L135-136) while the stored
     *       value is compared as-is, because COBOL never uppercases {@code SEC-USR-PWD} and the seed
     *       values are already uppercase;</li>
     *   <li>both operands are compared with trailing whitespace stripped, emulating the fixed-width
     *       {@code PIC X(08)} space-padded equality COBOL performs, so a {@code CHAR(8)}-padded stored
     *       value matches a shorter presented value regardless of whether the JDBC/Hibernate layer pads
     *       or trims;</li>
     *   <li>on success an authenticated token is returned with {@code null} credentials so the cleartext
     *       password is not retained in the security context.</li>
     * </ol>
     *
     * @param authentication the authentication request token ({@link UsernamePasswordAuthenticationToken})
     *                       carrying the entered id and password
     * @return a fully authenticated {@link UsernamePasswordAuthenticationToken} whose principal is the
     *         loaded {@link CardDemoUserDetails} and whose authorities are copied from that principal
     * @throws BadCredentialsException if the credentials are absent or the password does not match the
     *                                 stored value (COBOL {@code ELSE 'Wrong Password. Try again ...'})
     * @throws org.springframework.security.core.userdetails.UsernameNotFoundException propagated
     *                                 unchanged from the user-details service when the id is unknown
     *                                 (COBOL {@code WHEN 13 'User not found. Try again ...'})
     * @throws AuthenticationException if authentication fails for any other reason
     */
    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        // The entered user id (COBOL USERIDI). Uppercasing of the id is performed by the service
        // (MOVE FUNCTION UPPER-CASE(USERIDI ...) TO WS-USER-ID), matching where COBOL folds the id.
        String username = authentication.getName();

        // The entered password (COBOL PASSWDI), supplied as the token credentials.
        Object credentials = authentication.getCredentials();

        // Absent credentials map to an empty/missing password: fail as bad credentials rather than
        // dereferencing null. This mirrors the wrong-password branch, not the "User not found" branch.
        if (credentials == null) {
            throw new BadCredentialsException(INVALID_CREDENTIALS_MESSAGE);
        }
        String presentedPassword = credentials.toString();

        // COBOL READ-USER-SEC-FILE: EXEC CICS READ USRSEC RIDFLD(WS-USER-ID). The service always returns
        // a CardDemoUserDetails, so this narrowing cast is safe; an unknown id throws
        // UsernameNotFoundException, which is deliberately NOT caught here so the COBOL "User not found"
        // (WHEN 13) path stays distinct from the wrong-password branch.
        CardDemoUserDetails userDetails =
                (CardDemoUserDetails) userDetailsService.loadUserByUsername(username);

        // COBOL: IF SEC-USR-PWD = WS-USER-PWD. Uppercase the PRESENTED password only (COBOL uppercases
        // the input, not the stored value); stripTrailing() on BOTH operands emulates the fixed-width
        // PIC X(08) space-padded comparison so trailing spaces are not significant.
        String storedPassword = userDetails.getPassword();
        String normalizedPresented = presentedPassword.toUpperCase(Locale.ROOT);
        boolean matches = storedPassword != null
                && storedPassword.stripTrailing().equals(normalizedPresented.stripTrailing());
        if (!matches) {
            // COBOL ELSE branch: 'Wrong Password. Try again ...'. Generic message, no password echoed.
            throw new BadCredentialsException(INVALID_CREDENTIALS_MESSAGE);
        }

        // Success. Build the authenticated token with null credentials (the 3-arg constructor marks the
        // token authenticated); passing null avoids retaining the cleartext password in the security
        // context. The principal's own password is separately erased by ProviderManager via
        // CredentialsContainer.eraseCredentials().
        return new UsernamePasswordAuthenticationToken(
                userDetails, null, userDetails.getAuthorities());
    }

    /**
     * Indicates that this provider handles {@link UsernamePasswordAuthenticationToken} authentication
     * requests, which is the token produced by Spring Security form login (the signon screen
     * {@code COSGN00} / transaction {@code CC00}).
     *
     * @param authentication the authentication class offered by the {@code ProviderManager}
     * @return {@code true} if {@code authentication} is (a subtype of)
     *         {@link UsernamePasswordAuthenticationToken}; {@code false} otherwise
     */
    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
