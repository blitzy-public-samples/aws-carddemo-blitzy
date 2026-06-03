package com.carddemo.security;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

/**
 * Maps the legacy CardDemo single-character {@code userType} flag to Spring
 * Security {@link GrantedAuthority} instances, preserving the original COBOL
 * two-tier role model exactly (refactoring rule <b>PR-19 — role mapping
 * fidelity</b>).
 *
 * <h2>Origin of the {@code userType} flag</h2>
 * In the mainframe system a user's role was a single EBCDIC character. The
 * authoritative definition lives in two copybooks (version
 * {@code CardDemo_v1.0-15-g27d6c6f-68}):
 *
 * <ul>
 *   <li><b>{@code app/cpy/CSUSR01Y.cpy} L22</b> — the persisted security record
 *       field {@code 05 SEC-USR-TYPE PIC X(01)} (one byte) within the 80-byte
 *       {@code SEC-USER-DATA} layout. This is the value physically stored for
 *       each user (now column {@code users.sec_usr_type CHAR(1)} and Java field
 *       {@code com.carddemo.entity.User.userType}).</li>
 *   <li><b>{@code app/cpy/COCOM01Y.cpy} L26-L31</b> — the {@code COMMAREA}
 *       condition names that gave the byte its meaning:
 *       <pre>
 *       10 CDEMO-USER-TYPE               PIC X(01).
 *          88 CDEMO-USRTYP-ADMIN         VALUE 'A'.
 *          88 CDEMO-USRTYP-USER          VALUE 'U'.
 *       </pre>
 *       </li>
 * </ul>
 *
 * <h2>Mapping rule (PR-19)</h2>
 * <table border="1">
 *   <caption>{@code SEC-USR-TYPE} &rarr; Spring Security authority</caption>
 *   <tr><th>{@code userType}</th><th>Authority</th><th>COBOL 88-level</th></tr>
 *   <tr><td>{@code "A"}</td><td>{@code ROLE_ADMIN}</td><td>{@code CDEMO-USRTYP-ADMIN}</td></tr>
 *   <tr><td>{@code "U"}</td><td>{@code ROLE_USER}</td><td>{@code CDEMO-USRTYP-USER}</td></tr>
 *   <tr><td>anything else / {@code null}</td><td>(none)</td><td>&mdash;</td></tr>
 * </table>
 *
 * <p>The {@code "ROLE_"} prefix is mandatory: Spring Security's
 * {@code hasRole("ADMIN")} expression (used by the
 * {@code @PreAuthorize("hasRole('ADMIN')")} annotations on {@code UserController}
 * and {@code BatchAdminController}, activated by {@code MethodSecurityConfig})
 * resolves to a check for the authority literal {@code "ROLE_ADMIN"}. Emitting
 * the prefix here keeps authority strings consistent across the whole security
 * layer.</p>
 *
 * <h2>Defensive handling of unknown values</h2>
 * The match is <b>case-sensitive</b> — the source format guarantees an uppercase
 * {@code 'A'} or {@code 'U'} ({@code app/cpy/CSUSR01Y.cpy}). Any other value,
 * including {@code null}, the empty string, lowercase variants, or a corrupted
 * byte, maps to an <b>empty, immutable authority collection</b> rather than
 * defaulting to a privileged role. This fail-closed behaviour means an
 * unrecognised principal carries no authorities and Spring Security denies every
 * {@code .authenticated()} / {@code hasRole(...)} protected resource — the safe
 * outcome for a security component.
 *
 * <h2>Relationship to {@code User.getAuthorities()}</h2>
 * The {@code com.carddemo.entity.User} entity (which implements
 * {@code UserDetails}) performs the same {@code 'A' → ROLE_ADMIN} /
 * {@code 'U' → ROLE_USER} translation inline. For the two valid, documented
 * values this mapper produces identical authorities. This class additionally
 * applies the stricter fail-closed policy for unknown values (empty rather than
 * a fallback role) and centralises the rule as a reusable {@link Component}, so
 * collaborators that only possess the raw {@code userType} string — for example
 * a {@code JwtAuthenticationFilter} extracting a {@code userType} claim from a
 * bearer token, or {@code AuthService} building an {@code Authentication} — can
 * translate it without depending on a fully materialised {@code User} entity.
 *
 * <h2>Design notes</h2>
 * <ul>
 *   <li><b>PR-29 (constructor injection):</b> this component is stateless and has
 *       no collaborators, so no constructor is declared; {@link Component} alone
 *       makes it a singleton bean discovered by the {@code com.carddemo}
 *       component scan.</li>
 *   <li><b>PR-28 (Jakarta EE namespace):</b> this class uses no persistence or
 *       validation APIs, so the {@code javax.*}/{@code jakarta.*} distinction does
 *       not arise; the only third-party imports are Spring Security and the Spring
 *       {@code @Component} stereotype.</li>
 *   <li>Thread-safe: holds no mutable state and returns only immutable
 *       collections.</li>
 * </ul>
 *
 * @see org.springframework.security.core.GrantedAuthority
 * @see org.springframework.security.core.authority.SimpleGrantedAuthority
 * @see org.springframework.security.access.prepost.PreAuthorize
 */
@Component
public class CustomAuthorityMapper {

    /**
     * Spring Security authority granted to administrators. The {@code "ROLE_"}
     * prefix is required so that {@code hasRole("ADMIN")} matches this authority.
     * Corresponds to COBOL 88-level {@code CDEMO-USRTYP-ADMIN}
     * ({@code app/cpy/COCOM01Y.cpy} L27).
     */
    public static final String ROLE_ADMIN = "ROLE_ADMIN";

    /**
     * Spring Security authority granted to regular users. The {@code "ROLE_"}
     * prefix is required so that {@code hasRole("USER")} matches this authority.
     * Corresponds to COBOL 88-level {@code CDEMO-USRTYP-USER}
     * ({@code app/cpy/COCOM01Y.cpy} L28).
     */
    public static final String ROLE_USER = "ROLE_USER";

    /**
     * Raw {@code SEC-USR-TYPE} value identifying an administrator — the literal
     * {@code 'A'} from {@code CDEMO-USRTYP-ADMIN VALUE 'A'}
     * ({@code app/cpy/COCOM01Y.cpy} L27, {@code app/cpy/CSUSR01Y.cpy} L22).
     */
    public static final String USER_TYPE_ADMIN = "A";

    /**
     * Raw {@code SEC-USR-TYPE} value identifying a regular user — the literal
     * {@code 'U'} from {@code CDEMO-USRTYP-USER VALUE 'U'}
     * ({@code app/cpy/COCOM01Y.cpy} L28, {@code app/cpy/CSUSR01Y.cpy} L22).
     */
    public static final String USER_TYPE_USER = "U";

    /**
     * Translates a raw CardDemo {@code userType} flag into the corresponding
     * Spring Security authorities, implementing the PR-19 role-mapping rule.
     *
     * <p>The lookup is case-sensitive and exact: only the uppercase literals
     * {@link #USER_TYPE_ADMIN "A"} and {@link #USER_TYPE_USER "U"} produce a
     * role. Every other input — including {@code null}, the empty string,
     * lowercase {@code "a"}/{@code "u"}, or any unexpected byte — fails closed to
     * an empty authority collection so that an unrecognised principal is granted
     * no privileges (Spring Security then denies access on any
     * {@code .authenticated()} / {@code hasRole(...)} protected endpoint).</p>
     *
     * <p>Examples:</p>
     * <pre>
     * mapAuthorities("A")  &rarr; [ROLE_ADMIN]   // administrator
     * mapAuthorities("U")  &rarr; [ROLE_USER]    // regular user
     * mapAuthorities("X")  &rarr; []             // unknown type — defensive
     * mapAuthorities("a")  &rarr; []             // wrong case   — defensive
     * mapAuthorities("")   &rarr; []             // empty        — defensive
     * mapAuthorities(null) &rarr; []             // null         — defensive
     * </pre>
     *
     * @param userType the raw single-character {@code SEC-USR-TYPE} value
     *                 (typically {@code com.carddemo.entity.User#getUserType()}
     *                 or a JWT {@code userType} claim); {@code 'A'} for admins,
     *                 {@code 'U'} for users; may be {@code null}
     * @return an immutable, single-element collection holding the matching
     *         {@link GrantedAuthority} ({@link #ROLE_ADMIN} or
     *         {@link #ROLE_USER}); or an immutable empty collection when
     *         {@code userType} is {@code null} or not a recognised role code.
     *         Never {@code null}.
     */
    public Collection<? extends GrantedAuthority> mapAuthorities(String userType) {
        if (userType == null) {
            // Fail closed: a principal with no type carries no authorities.
            return Collections.emptyList();
        }
        // Case-sensitive match against the uppercase 'A'/'U' codes defined by
        // CSUSR01Y.cpy / COCOM01Y.cpy. USER_TYPE_ADMIN and USER_TYPE_USER are
        // compile-time String constants, which makes them valid switch labels.
        return switch (userType) {
            case USER_TYPE_ADMIN -> List.of(new SimpleGrantedAuthority(ROLE_ADMIN));
            case USER_TYPE_USER -> List.of(new SimpleGrantedAuthority(ROLE_USER));
            default -> Collections.emptyList();
        };
    }
}
