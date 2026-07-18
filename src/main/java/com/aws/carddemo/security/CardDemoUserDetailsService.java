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

import com.aws.carddemo.domain.UserSecurity;
import com.aws.carddemo.repository.UserSecurityRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * Spring Security {@link UserDetailsService} for CardDemo: it loads a single user
 * from the {@code user_security} table and adapts it to a
 * {@link CardDemoUserDetails} principal.
 *
 * <p><strong>Provenance.</strong> This service re-platforms the record-load half of
 * the legacy sign-on program {@code app/cbl/COSGN00C.cbl} (CICS transaction
 * {@code CC00}, relocated to {@code legacy/**}). The COBOL paragraph
 * {@code READ-USER-SEC-FILE}:</p>
 * <pre>
 * MOVE FUNCTION UPPER-CASE(USERIDI OF COSGN0AI) TO WS-USER-ID
 * EXEC CICS READ DATASET(WS-USRSEC-FILE) INTO(SEC-USER-DATA)
 *                RIDFLD(WS-USER-ID) KEYLENGTH(LENGTH OF WS-USER-ID)
 *                RESP(WS-RESP-CD) END-EXEC
 * EVALUATE WS-RESP-CD
 *     WHEN 0   ... (found)  -&gt; compare SEC-USR-PWD, then branch by SEC-USR-TYPE
 *     WHEN 13  ... (NOTFND) -&gt; "User not found. Try again ..."
 *     WHEN OTHER            -&gt; "Unable to verify the User ..."
 * END-EVALUATE
 * </pre>
 *
 * <p><strong>Migration split of responsibilities.</strong> The single COBOL
 * paragraph is deliberately decomposed so that each concern lives in exactly one
 * Java collaborator; this class owns only the <em>lookup&nbsp;+&nbsp;adaptation</em>
 * step:</p>
 * <ul>
 *   <li><strong>This class</strong> normalizes the submitted id exactly as
 *       {@code FUNCTION UPPER-CASE} did, performs the keyed primary-key read, and
 *       returns a {@link UserDetails}. A missing record is surfaced as a
 *       {@link UsernameNotFoundException} &mdash; the direct analog of the COBOL
 *       {@code WHEN 13} ({@code DFHRESP(NOTFND)}) branch.</li>
 *   <li><strong>Password verification is not performed here.</strong> The legacy
 *       plaintext compare {@code IF SEC-USR-PWD = WS-USER-PWD} is replaced by
 *       BCrypt verification performed by Spring Security's auto-configured
 *       {@code DaoAuthenticationProvider}, which compares the submitted password
 *       against {@link CardDemoUserDetails#getPassword()} using the
 *       {@code PasswordEncoder} bean declared in {@code config/SecurityConfig}.
 *       This is a documented security improvement (see the decision log); no
 *       credential comparison or hashing occurs in this file.</li>
 *   <li>The user-facing sign-on screen messages and the admin-vs-user navigation
 *       ({@code XCTL} to {@code COADM01C} / {@code COMEN01C}) belong to
 *       {@code service/SignonService} and the web layer, not to this service.</li>
 * </ul>
 *
 * <p><strong>Username normalization (parity-critical).</strong> COSGN00C
 * upper-cases the entered user id before the keyed read &mdash; and only that; it
 * does not trim &mdash; and the seed ids loaded by {@code app/jcl/DUSRSECJ.jcl} are
 * stored as UPPER-CASE 8-character keys. {@link #loadUserByUsername(String)}
 * therefore upper-cases (but does not trim) the incoming value with
 * {@link Locale#ROOT} (a locale-independent fold that avoids surprises such as the
 * Turkish dotless-i) before querying, so a login of {@code "admin001"} resolves the
 * same record as {@code "ADMIN001"}, matching the legacy behavior. The fold applies
 * upper-case only &mdash; identical to the CC00 sign-on service
 * ({@code service/SignonService}), which also upper-cases without trimming &mdash;
 * so the interactive sign-on path and the HTTP&nbsp;Basic gate normalize the id the
 * same way and neither accepts a space-padded id the other would reject. The
 * companion password normalization (also upper-case only, per COSGN00C) is applied
 * by the shared {@code PasswordEncoder} ({@code UpperCasePasswordEncoder} in
 * {@code config/SecurityConfig}), not here; see decision log D26.</p>
 *
 * <p><strong>Confidentiality.</strong> The stored credential is never logged. The
 * only diagnostic emitted is a {@code DEBUG}-level line carrying the
 * (non-sensitive) normalized user id; the password hash returned by
 * {@link UserSecurity#getSecUsrPwd()} is passed straight through to the principal
 * and is not read, logged, or transformed here (AAP &sect;0.7.3&nbsp;L1 /
 * &sect;0.9.3).</p>
 *
 * <p><strong>Wiring.</strong> Annotated {@link Service}, this class is discovered by
 * the root {@code @SpringBootApplication} component scan of {@code com.aws.carddemo}
 * and becomes the single {@link UserDetailsService} bean that Spring Security's
 * {@code DaoAuthenticationProvider} consumes. Its sole collaborator,
 * {@link UserSecurityRepository}, is supplied by constructor injection (AAP
 * &sect;0.6.4 &mdash; no field injection).</p>
 *
 * @see CardDemoUserDetails
 * @see UserRole
 * @see UserSecurityRepository
 * @see org.springframework.security.core.userdetails.UserDetailsService
 */
@Service
public class CardDemoUserDetailsService implements UserDetailsService {

    /**
     * Logger for this service. Used only for a {@code DEBUG}-level trace of the
     * normalized (non-sensitive) user id before the lookup; the stored password /
     * hash is never logged (AAP &sect;0.7.3&nbsp;L1 / &sect;0.9.3).
     */
    private static final Logger log = LoggerFactory.getLogger(CardDemoUserDetailsService.class);

    /**
     * Repository over the {@code user_security} table &mdash; the relational
     * successor of the legacy {@code USRSEC.VSAM.KSDS}. This is the service's only
     * dependency and is injected through the constructor.
     */
    private final UserSecurityRepository userSecurityRepository;

    /**
     * Creates the service with its repository collaborator.
     *
     * <p>Constructor injection is used exclusively (AAP &sect;0.6.4); no field-level
     * {@code @Autowired} appears in this class. Because the class declares a single
     * constructor, Spring injects the {@link UserSecurityRepository} bean
     * automatically without an explicit {@code @Autowired} annotation.</p>
     *
     * @param userSecurityRepository the repository used to look up user records by
     *                               their primary-key id; must not be {@code null}
     */
    public CardDemoUserDetailsService(UserSecurityRepository userSecurityRepository) {
        this.userSecurityRepository = userSecurityRepository;
    }

    /**
     * Loads the security record for {@code username} and adapts it to a Spring
     * Security {@link UserDetails} principal, reproducing the record-load and
     * not-found semantics of the COBOL {@code READ-USER-SEC-FILE} paragraph in
     * {@code app/cbl/COSGN00C.cbl}.
     *
     * <p>Processing order:</p>
     * <ol>
     *   <li>A {@code null} or blank username is rejected with
     *       {@link UsernameNotFoundException}. Spring Security's
     *       {@code DaoAuthenticationProvider} already short-circuits empty
     *       credentials, so this guard is defensive and keeps the method total.</li>
     *   <li>The username is upper-cased with {@link Locale#ROOT} (upper-case only;
     *       it is not trimmed), reproducing the {@code FUNCTION UPPER-CASE}
     *       normalization COSGN00C applied to the entered id and matching the CC00
     *       sign-on service so both surfaces resolve the same key.</li>
     *   <li>The {@code user_security} table is read by primary key. An empty result
     *       becomes {@link UsernameNotFoundException}, the analog of the COBOL
     *       {@code WHEN 13} ({@code DFHRESP(NOTFND)}) branch.</li>
     *   <li>The located record is adapted to a {@link CardDemoUserDetails}: the id
     *       and stored (hashed) credential are copied through verbatim and the
     *       single-character {@code SEC-USR-TYPE} is mapped to a {@link UserRole}
     *       via {@link UserRole#fromUserType(String)}.</li>
     * </ol>
     *
     * <p>The credential is returned to the caller untouched so the framework's
     * {@code PasswordEncoder} can verify it; this method never compares, encodes,
     * or logs it.</p>
     *
     * @param username the login id as submitted (any case, possibly padded); must
     *                 be non-{@code null} and non-blank
     * @return the {@link UserDetails} principal for the resolved user (never
     *         {@code null})
     * @throws UsernameNotFoundException if {@code username} is {@code null} or
     *                                   blank, or if no {@code user_security} row
     *                                   exists for the normalized id
     */
    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        if (username == null || username.isBlank()) {
            // Analogous to COSGN00C rejecting a blank USERIDI before the file read.
            throw new UsernameNotFoundException("Username must not be blank");
        }

        // Parity: COSGN00C applies FUNCTION UPPER-CASE to the entered id (and only
        // that - it does not trim), and the USRSEC keys are stored upper-cased (see
        // app/jcl/DUSRSECJ.jcl). The fold is upper-case only - matching the CC00
        // sign-on service (SignonService), which likewise does not trim - so the two
        // authentication surfaces normalize the id identically and neither accepts a
        // space-padded id the other would reject. Locale.ROOT makes the fold
        // locale-independent.
        String normalizedId = username.toUpperCase(Locale.ROOT);

        // Non-sensitive id only; never log the password / hash (AAP 0.7.3 L1 / 0.9.3).
        log.debug("Loading user details for id={}", normalizedId);

        UserSecurity user = userSecurityRepository.findBySecUsrId(normalizedId)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + normalizedId));

        // Copy the id and stored credential through verbatim; map the legacy
        // single-character role code to a UserRole for the granted authority.
        return new CardDemoUserDetails(
                user.getSecUsrId(),
                user.getSecUsrPwd(),
                UserRole.fromUserType(user.getSecUsrType()));
    }
}
