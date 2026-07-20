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
package com.aws.carddemo.service;

import com.aws.carddemo.domain.UserSecurity;
import com.aws.carddemo.repository.UserSecurityRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;

/**
 * Sign-on / credential-validation service &mdash; the Java re-platform of the
 * CardDemo COBOL sign-on program {@code COSGN00C} (source
 * {@code legacy/cbl/COSGN00C.cbl}, formerly {@code app/cbl/COSGN00C.cbl};
 * CICS transaction {@code CC00}). It reproduces, with no feature expansion, the
 * caller-visible outcome of the legacy sign-on flow: read the entered user id
 * and password, validate them, look the user up in the {@code USRSEC} security
 * file, and either grant access (routing to the Admin or Main menu) or return
 * one of the program's five exact error messages.
 *
 * <h2>Legacy contract reproduced (exact, in order)</h2>
 * The order and short-circuit semantics of the COBOL {@code PROCESS-ENTER-KEY}
 * ({@code legacy/cbl/COSGN00C.cbl:L108-L140}) and {@code READ-USER-SEC-FILE}
 * ({@code legacy/cbl/COSGN00C.cbl:L209-L257}) paragraphs are preserved
 * one-for-one:
 * <ol>
 *   <li><strong>Blank user id</strong> &mdash; the COBOL
 *       {@code WHEN USERIDI = SPACES OR LOW-VALUES}
 *       ({@code legacy/cbl/COSGN00C.cbl:L118-L120}) yields the message
 *       {@value #MSG_ENTER_USER_ID}. Processing stops before the file read.</li>
 *   <li><strong>Blank password</strong> &mdash; the COBOL
 *       {@code WHEN PASSWDI = SPACES OR LOW-VALUES}
 *       ({@code legacy/cbl/COSGN00C.cbl:L123-L125}) yields the message
 *       {@value #MSG_ENTER_PASSWORD}. Processing stops before the file read.</li>
 *   <li><strong>Upper-casing</strong> &mdash; both fields are folded to upper
 *       case with {@code MOVE FUNCTION UPPER-CASE}
 *       ({@code legacy/cbl/COSGN00C.cbl:L132-L136}) before use.</li>
 *   <li><strong>Keyed read of {@code USRSEC}</strong> by user id
 *       ({@code legacy/cbl/COSGN00C.cbl:L211-L219}) and its
 *       {@code EVALUATE WS-RESP-CD}
 *       ({@code legacy/cbl/COSGN00C.cbl:L221-L257}):
 *       <ul>
 *         <li><em>Found &amp; password matches</em> ({@code WHEN 0} with
 *             {@code SEC-USR-PWD = WS-USER-PWD}) &mdash; success. The COBOL sets
 *             the COMMAREA ({@code CDEMO-USER-ID}, {@code CDEMO-USER-TYPE},
 *             {@code CDEMO-PGM-CONTEXT = 0}) and {@code XCTL}s to the admin menu
 *             {@code COADM01C} when {@code CDEMO-USRTYP-ADMIN}
 *             ({@code SEC-USR-TYPE = 'A'}) or otherwise to the main menu
 *             {@code COMEN01C} ({@code legacy/cbl/COSGN00C.cbl:L222-L246}).</li>
 *         <li><em>Found but password does not match</em> &mdash; the message
 *             {@value #MSG_WRONG_PASSWORD}
 *             ({@code legacy/cbl/COSGN00C.cbl:L242}).</li>
 *         <li><em>Not found</em> ({@code WHEN 13} / CICS {@code NOTFND}) &mdash;
 *             the message {@value #MSG_USER_NOT_FOUND}
 *             ({@code legacy/cbl/COSGN00C.cbl:L247-L249}).</li>
 *         <li><em>Any other file error</em> ({@code WHEN OTHER}) &mdash; the
 *             message {@value #MSG_UNABLE_TO_VERIFY}
 *             ({@code legacy/cbl/COSGN00C.cbl:L252-L254}).</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <h2>Pseudo-conversational translation</h2>
 * The legacy program is pseudo-conversational: it terminates with
 * {@code EXEC CICS RETURN TRANSID('CC00') COMMAREA(...)} and, on success,
 * transfers control with {@code EXEC CICS XCTL}. Per AAP &sect;0.7.1 H1 there is
 * no direct HTTP analogue, so this service returns an explicit
 * {@link SignonResult} value object instead of mutating a COMMAREA or issuing an
 * {@code XCTL}: on success the result carries the authenticated user's id, role,
 * and the <em>target program name</em> ({@code COADM01C} or {@code COMEN01C})
 * that the calling controller uses to navigate; on failure it carries the exact
 * error message the 3270 screen would have shown. The service itself is
 * stateless.
 *
 * <h2>Security hardening (documented deviation)</h2>
 * The legacy {@code USRSEC} record stored an 8-character <em>plaintext</em>
 * password (an intentional demonstration anti-pattern, AAP &sect;0.7.3 L1). The
 * Java target stores a one-way <strong>BCrypt</strong> hash in
 * {@code user_security.sec_usr_pwd}, so this service never performs a plaintext
 * equality check. Instead it delegates to an injected
 * {@link PasswordEncoder#matches(CharSequence, String)} (the BCrypt bean defined
 * in {@code config/SecurityConfig}). The COBOL folded the entered password to
 * upper case before the comparison
 * ({@code legacy/cbl/COSGN00C.cbl:L135-L136}); to preserve parity against seed
 * data whose hashes were derived from the upper-cased legacy passwords, the raw
 * password is upper-cased ({@link Locale#ROOT}) before being passed to
 * {@code matches(...)}. This hardening is recorded in the decision log and is an
 * intentional improvement, not a behavioral regression.
 *
 * <h2>Confidentiality</h2>
 * The password &mdash; raw or hashed &mdash; is <strong>never</strong> written to
 * a log, an exception, or a {@link SignonResult} message (AAP &sect;0.9.3).
 * Diagnostic logging is limited to the (non-sensitive) user id and the outcome.
 *
 * <h2>Dependencies</h2>
 * Collaborators are supplied by constructor injection (no field injection, no
 * Lombok): the {@link UserSecurityRepository} that replaces the legacy CICS file
 * control over {@code USRSEC}, and the Spring Security {@link PasswordEncoder}
 * runtime bean. The single {@link #signon(String, String)} operation reads only,
 * so it runs in a read-only transaction.
 *
 * @see UserSecurityRepository#findBySecUsrId(String)
 * @see PasswordEncoder#matches(CharSequence, String)
 */
@Service
public class SignonService {

    /**
     * Logger for sign-on diagnostics. Only the non-sensitive user id and the
     * authentication outcome are ever logged; the password (raw or hashed) is
     * never passed to this logger.
     */
    private static final Logger LOG = LoggerFactory.getLogger(SignonService.class);

    /**
     * Error shown when the user id is blank, reproducing the COBOL literal at
     * {@code legacy/cbl/COSGN00C.cbl:L120}. The trailing {@code " ..."} is part
     * of the observable contract and is preserved verbatim.
     */
    public static final String MSG_ENTER_USER_ID = "Please enter User ID ...";

    /**
     * Error shown when the password is blank, reproducing the COBOL literal at
     * {@code legacy/cbl/COSGN00C.cbl:L125}.
     */
    public static final String MSG_ENTER_PASSWORD = "Please enter Password ...";

    /**
     * Error shown when no {@code USRSEC} record exists for the entered user id
     * (CICS {@code NOTFND} / {@code WHEN 13}), reproducing the COBOL literal at
     * {@code legacy/cbl/COSGN00C.cbl:L249}.
     */
    public static final String MSG_USER_NOT_FOUND = "User not found. Try again ...";

    /**
     * Error shown when the user exists but the password does not match,
     * reproducing the COBOL literal at {@code legacy/cbl/COSGN00C.cbl:L242}.
     */
    public static final String MSG_WRONG_PASSWORD = "Wrong Password. Try again ...";

    /**
     * Error shown for any other file error while reading {@code USRSEC}
     * ({@code WHEN OTHER}), reproducing the COBOL literal at
     * {@code legacy/cbl/COSGN00C.cbl:L254}.
     */
    public static final String MSG_UNABLE_TO_VERIFY = "Unable to verify the User ...";

    /**
     * Target program navigated to for an administrator ({@code SEC-USR-TYPE =
     * 'A'}), reproducing the COBOL {@code XCTL PROGRAM('COADM01C')} at
     * {@code legacy/cbl/COSGN00C.cbl:L231-L233}.
     */
    public static final String PROGRAM_ADMIN_MENU = "COADM01C";

    /**
     * Target program navigated to for a standard user, reproducing the COBOL
     * {@code XCTL PROGRAM('COMEN01C')} at
     * {@code legacy/cbl/COSGN00C.cbl:L236-L238}.
     */
    public static final String PROGRAM_MAIN_MENU = "COMEN01C";

    /**
     * The administrator role code, reproducing the COBOL {@code CDEMO-USRTYP-ADMIN}
     * condition name ({@code SEC-USR-TYPE = 'A'}, {@code app/cpy/COCOM01Y.cpy}
     * L25-L28). Any other value is treated as a standard user.
     */
    public static final char USER_TYPE_ADMIN = 'A';

    /**
     * Sentinel {@code userType} carried by a {@link SignonResult#failure(String)}
     * result, denoting "no user type" (the NUL character). A failure never
     * exposes a meaningful role.
     */
    private static final char NO_USER_TYPE = '\u0000';

    /**
     * Repository over the {@code user_security} table; the Java replacement for
     * the legacy keyed CICS {@code READ} of the {@code USRSEC} KSDS.
     */
    private final UserSecurityRepository userSecurityRepository;

    /**
     * BCrypt password encoder used to compare the submitted credential against
     * the stored hash. Supplied as a Spring Security runtime bean (declared in
     * {@code config/SecurityConfig}); this service does not create it.
     */
    private final PasswordEncoder passwordEncoder;

    /**
     * Creates the sign-on service with its collaborators. Constructor injection
     * is used exclusively (no field {@code @Autowired}, no Lombok) so the
     * dependencies are explicit, final, and testable.
     *
     * @param userSecurityRepository repository over {@code user_security};
     *                               must not be {@code null}
     * @param passwordEncoder        the BCrypt {@link PasswordEncoder} bean;
     *                               must not be {@code null}
     */
    public SignonService(UserSecurityRepository userSecurityRepository,
                         PasswordEncoder passwordEncoder) {
        this.userSecurityRepository = userSecurityRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Validates a sign-on attempt, reproducing the COBOL {@code COSGN00C}
     * {@code PROCESS-ENTER-KEY} and {@code READ-USER-SEC-FILE} paragraphs exactly
     * and in order.
     *
     * <p>Evaluation order (short-circuiting, matching the legacy program):</p>
     * <ol>
     *   <li>If {@code userId} is blank &rarr;
     *       {@link SignonResult#failure(String) failure}({@value #MSG_ENTER_USER_ID}).</li>
     *   <li>Else if {@code password} is blank &rarr;
     *       {@link SignonResult#failure(String) failure}({@value #MSG_ENTER_PASSWORD}).</li>
     *   <li>Both inputs are folded to upper case ({@link Locale#ROOT}); the user
     *       id is looked up as the {@code sec_usr_id} primary key via
     *       {@link UserSecurityRepository#findBySecUsrId(String)}.</li>
     *   <li>If no record is found &rarr;
     *       {@link SignonResult#failure(String) failure}({@value #MSG_USER_NOT_FOUND}).</li>
     *   <li>Else if {@link PasswordEncoder#matches(CharSequence, String)} accepts
     *       the upper-cased password against the stored hash &rarr;
     *       {@link SignonResult#success(String, char, String) success} carrying
     *       the (upper-cased) user id &mdash; mirroring the COBOL
     *       {@code MOVE WS-USER-ID TO CDEMO-USER-ID} &mdash; the role, and the
     *       target program ({@value #PROGRAM_ADMIN_MENU} for role
     *       {@code '}{@value #USER_TYPE_ADMIN}{@code '}, otherwise
     *       {@value #PROGRAM_MAIN_MENU}).</li>
     *   <li>Else (password mismatch) &rarr;
     *       {@link SignonResult#failure(String) failure}({@value #MSG_WRONG_PASSWORD}).</li>
     * </ol>
     *
     * <p>If the repository read fails with a {@link DataAccessException} &mdash;
     * the relational analogue of the COBOL {@code WHEN OTHER} file-error branch
     * &mdash; the exception is logged (without the password) and the method
     * returns {@link SignonResult#failure(String) failure}({@value
     * #MSG_UNABLE_TO_VERIFY}); no stack detail is leaked into the message.</p>
     *
     * <p>The method is transactional and read-only: it performs a single keyed
     * lookup and never mutates state.</p>
     *
     * @param userId   the entered user id; {@code null}, empty, or all-whitespace
     *                 is treated as blank (the COBOL {@code SPACES OR LOW-VALUES})
     * @param password the entered password; treated as blank under the same rule.
     *                 It is never logged and never placed in the returned message.
     * @return a {@link SignonResult} describing success (with navigation target)
     *         or the exact failure message, never {@code null}
     */
    @Transactional(readOnly = true)
    public SignonResult signon(String userId, String password) {
        // COSGN00C PROCESS-ENTER-KEY: blank user id guard (L118-L120).
        if (isBlank(userId)) {
            return SignonResult.failure(MSG_ENTER_USER_ID);
        }
        // COSGN00C PROCESS-ENTER-KEY: blank password guard (L123-L125).
        if (isBlank(password)) {
            return SignonResult.failure(MSG_ENTER_PASSWORD);
        }

        // COSGN00C: MOVE FUNCTION UPPER-CASE of both fields (L132-L136).
        // Locale.ROOT keeps the fold locale-independent (no Turkish-i surprises).
        final String upperId = userId.toUpperCase(Locale.ROOT);
        final String upperPassword = password.toUpperCase(Locale.ROOT);

        // COSGN00C READ-USER-SEC-FILE: keyed READ of USRSEC (L211-L219). A
        // DataAccessException is the relational analogue of the WHEN OTHER
        // file-error branch (L252-L254).
        final Optional<UserSecurity> found;
        try {
            found = userSecurityRepository.findBySecUsrId(upperId);
        } catch (DataAccessException ex) {
            LOG.error("Sign-on could not verify user id '{}' due to a data-access failure", upperId, ex);
            return SignonResult.failure(MSG_UNABLE_TO_VERIFY);
        }

        // EVALUATE WS-RESP-CD WHEN 13 (NOTFND): user not found (L247-L249).
        if (found.isEmpty()) {
            LOG.debug("Sign-on denied: no USRSEC record for user id '{}'", upperId);
            return SignonResult.failure(MSG_USER_NOT_FOUND);
        }

        // EVALUATE WS-RESP-CD WHEN 0 (found): compare credentials (L221-L246).
        final UserSecurity user = found.get();
        if (passwordEncoder.matches(upperPassword, user.getSecUsrPwd())) {
            final char userType = normalizeUserType(user.getSecUsrType());
            final String targetProgram =
                    (userType == USER_TYPE_ADMIN) ? PROGRAM_ADMIN_MENU : PROGRAM_MAIN_MENU;
            LOG.debug("Sign-on succeeded for user id '{}' (type '{}'); target program {}",
                    upperId, userType, targetProgram);
            return SignonResult.success(upperId, userType, targetProgram);
        }

        // WHEN 0 but SEC-USR-PWD != WS-USER-PWD: wrong password (L241-L242).
        LOG.debug("Sign-on denied: password mismatch for user id '{}'", upperId);
        return SignonResult.failure(MSG_WRONG_PASSWORD);
    }

    /**
     * Blank test reproducing the COBOL {@code = SPACES OR LOW-VALUES} check: a
     * value is blank when it is {@code null}, empty, or entirely whitespace.
     *
     * @param value the value to test (may be {@code null})
     * @return {@code true} if the value is {@code null}, empty, or all whitespace
     */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Normalizes the entity's {@code sec_usr_type} string ({@code PIC X(01)} in
     * the legacy copybook) to a single {@code char}. A {@code null} or empty
     * value maps to {@link #NO_USER_TYPE}, which is not equal to
     * {@link #USER_TYPE_ADMIN} and therefore routes to the main menu &mdash; the
     * same default the COBOL {@code IF CDEMO-USRTYP-ADMIN ... ELSE} takes for any
     * non-{@code 'A'} value. The stored value is compared as-is (not folded),
     * matching the COBOL, which never upper-cases {@code SEC-USR-TYPE}.
     *
     * @param secUsrType the stored user-type value (may be {@code null} or empty)
     * @return the first character of the type, or {@link #NO_USER_TYPE} if absent
     */
    private static char normalizeUserType(String secUsrType) {
        return (secUsrType == null || secUsrType.isEmpty()) ? NO_USER_TYPE : secUsrType.charAt(0);
    }

    /**
     * Immutable outcome of a {@link SignonService#signon(String, String)} call.
     *
     * <p>This value object is the stateless replacement for the legacy CICS
     * COMMAREA-plus-{@code XCTL} navigation model (AAP &sect;0.7.1 H1). It never
     * carries the password. Exactly one of the two shapes is produced:</p>
     * <ul>
     *   <li><strong>Success</strong> ({@link #success(String, char, String)}):
     *       {@code success == true}; {@link #userId()}, {@link #userType()}, and
     *       {@link #targetProgram()} are populated and {@link #message()} is
     *       {@code null}.</li>
     *   <li><strong>Failure</strong> ({@link #failure(String)}):
     *       {@code success == false}; {@link #message()} holds the exact
     *       screen message, {@link #userId()} and {@link #targetProgram()} are
     *       {@code null}, and {@link #userType()} is the NUL sentinel.</li>
     * </ul>
     *
     * @param success       {@code true} for a successful sign-on
     * @param userId        the authenticated (upper-cased) user id, or {@code null} on failure
     * @param userType      the role code ({@code 'A'} admin / {@code 'U'} user), or NUL on failure
     * @param targetProgram the navigation target ({@code COADM01C}/{@code COMEN01C}), or {@code null} on failure
     * @param message       the exact error message on failure, or {@code null} on success
     */
    public record SignonResult(boolean success,
                               String userId,
                               char userType,
                               String targetProgram,
                               String message) {

        /**
         * Builds a successful result. Mirrors the COBOL success branch that sets
         * the COMMAREA and {@code XCTL}s to the resolved menu program.
         *
         * @param userId        the authenticated (upper-cased) user id
         * @param userType      the role code ({@code 'A'} or {@code 'U'})
         * @param targetProgram the navigation target program name
         * @return a success {@link SignonResult} with a {@code null} message
         */
        public static SignonResult success(String userId, char userType, String targetProgram) {
            return new SignonResult(true, userId, userType, targetProgram, null);
        }

        /**
         * Builds a failure result carrying the exact screen message. Mirrors the
         * COBOL branches that set {@code WS-MESSAGE} and re-send the sign-on
         * screen.
         *
         * @param message the exact error message to surface to the caller
         * @return a failure {@link SignonResult} with no user id, no target
         *         program, and the NUL user-type sentinel
         */
        public static SignonResult failure(String message) {
            return new SignonResult(false, null, NO_USER_TYPE, null, message);
        }
    }
}
