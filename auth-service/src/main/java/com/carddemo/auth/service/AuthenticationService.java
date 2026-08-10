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
package com.carddemo.auth.service;

import com.carddemo.auth.dto.SignonRequestDto;
import com.carddemo.auth.dto.SignonResponseDto;
import com.carddemo.auth.mapper.SignonMapper;
import com.carddemo.auth.repository.SecurityUserRepository;
import com.carddemo.auth.security.LoginAttemptService;
import com.carddemo.common.domain.SecurityUser;
import com.carddemo.common.dto.SessionAttributes;
import com.carddemo.common.dto.SessionContext;
import com.carddemo.common.security.SecurityAuditLogger;
import com.carddemo.common.security.SessionPrincipalIndex;
import com.carddemo.common.security.UserIdNormalizer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * :purpose: Sign-on business logic for ``POST /auth/signon`` (CICS transaction
 *  ``CC00``), re-platforming the ``PROCESS-ENTER-KEY`` and ``READ-USER-SEC-FILE``
 *  paragraphs of legacy COBOL program ``COSGN00C``. Verifies the submitted
 *  credentials against the security-user store and, on success, rotates the session
 *  id and produces the sign-on response together with the externalized session
 *  context that replaces the CICS COMMAREA.
 */
@Service
public class AuthenticationService {

    /**
     * :purpose: Sign-on failure message for a user id with no matching
     *  security-user record (legacy ``READ-USER-SEC-FILE`` ``WHEN 13``).
     */
    private static final String MSG_USER_NOT_FOUND = "User not found. Try again ...";

    /**
     * :purpose: Sign-on failure message for a password that does not match the
     *  stored credential (legacy ``READ-USER-SEC-FILE`` success-branch ``ELSE``).
     */
    private static final String MSG_WRONG_PASSWORD = "Wrong Password. Try again ...";

    /**
     * :purpose: Sign-on failure message for any other credential-store read
     *  failure (legacy ``READ-USER-SEC-FILE`` ``WHEN OTHER``); also returned, with
     *  status ``429``, while a user id is locked out after repeated failures, so no
     *  new user-facing string is introduced.
     */
    private static final String MSG_UNABLE_TO_VERIFY = "Unable to verify the User ...";

    /**
     * :purpose: Session attribute key under which the externalized
     *  {@link SessionContext} (the CICS COMMAREA replacement) is stored on the
     *  current {@link HttpSession}. Delegates to
     *  {@link SessionAttributes#SESSION_CONTEXT}, the single key shared by the API
     *  gateway's menu navigation and every business service, so the migrated
     *  COMMAREA is one object rather than several divergent copies.
     */
    public static final String SESSION_CONTEXT_ATTRIBUTE = SessionAttributes.SESSION_CONTEXT;

    /**
     * :purpose: Logger for the one operational event this service reports that is not part
     *  of the security audit trail: a container declining to rotate the session id in place,
     *  after which sign-on falls back to invalidate-and-create.
     */
    private static final Logger log = LoggerFactory.getLogger(AuthenticationService.class);

    private final SecurityUserRepository securityUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final SignonMapper signonMapper;
    private final LoginAttemptService loginAttemptService;
    private final SessionPrincipalIndex sessionPrincipalIndex;

    /**
     * :purpose: Storage-format hash of a random value, used to perform the same one-way
     *  hashing work for an unknown user id as for a known one so response time does not
     *  reveal whether an id exists. It can never match a submitted password because the
     *  encoded value is discarded.
     */
    private final String timingEqualizationHash;

    /**
     * :purpose: Construct the sign-on service with its collaborators.
     * :param securityUserRepository: repository for the keyed security-user lookup.
     * :param passwordEncoder: one-way encoder used to verify the submitted password.
     * :param signonMapper: builds the sign-on response and the session context.
     * :param loginAttemptService: per-user-id failed-attempt counter and lockout.
     * :param sessionPrincipalIndex: records the session created for the user so it can
     *  later be revoked.
     */
    public AuthenticationService(SecurityUserRepository securityUserRepository,
                                 PasswordEncoder passwordEncoder,
                                 SignonMapper signonMapper,
                                 LoginAttemptService loginAttemptService,
                                 SessionPrincipalIndex sessionPrincipalIndex) {
        this.securityUserRepository = securityUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.signonMapper = signonMapper;
        this.loginAttemptService = loginAttemptService;
        this.sessionPrincipalIndex = sessionPrincipalIndex;
        this.timingEqualizationHash = passwordEncoder.encode(java.util.UUID.randomUUID().toString());
    }

    /**
     * :purpose: Authenticate a sign-on request and, on success, publish the
     *  externalized session context, reproducing the ``READ-USER-SEC-FILE``
     *  ``EVALUATE WS-RESP-CD`` control flow of ``COSGN00C``.
     * :param request: sign-on request carrying the entered user id and password.
     * :param httpRequest: current HTTP request; supplies the session that receives the
     *  COMMAREA-replacement context (with a rotated id) and the source address for the
     *  audit trail.
     * :returns: the sign-on response (user id, user type, redirect target).
     * :raises ResponseStatusException: ``401 UNAUTHORIZED`` when the user is not
     *  found, the password does not match, or the credential store cannot be read;
     *  ``429 TOO_MANY_REQUESTS`` while the user id is locked out after repeated
     *  failures.
     * :note: Every request is answered by verifying the submitted credential, whether or
     *  not it arrives over a live session. ``COSGN00C`` has exactly one entry path --
     *  ``PROCESS-ENTER-KEY`` performs ``READ-USER-SEC-FILE`` and compares the password on
     *  every ENTER -- so no state of the terminal makes the program skip that comparison or
     *  answer with anything other than its five literals. A live session is therefore
     *  neither a reason to refuse the request nor a reason to grant it: a verified sign-on
     *  takes over that session record, and a REFUSED one leaves it exactly as it was, so a
     *  typo can never cost the operator the session they are still signed on to.
     * :note: A presented session is ROTATED, not reused: the identifier an attacker knows
     *  can never become the authenticated one (session fixation, CWE-384). Rotation deletes
     *  the previous store entry, and the api-gateway is a second Spring Session participant
     *  holding the same record for the same request, so its own write-back finds the entry
     *  gone; that refusal is absorbed by
     *  {@link com.carddemo.common.config.RemovedSessionTolerantSessionRepository}, which is
     *  what makes rotating a shared record safe here. Stripping the attributes alone is not
     *  sufficient — it leaves the attacker holding a live, now-authenticated identifier.
     */
    @Transactional(readOnly = true)
    public SignonResponseDto signon(SignonRequestDto request, HttpServletRequest httpRequest) {
        String enteredUserId = request.getUserId();
        String userId = UserIdNormalizer.normalizeToKey(enteredUserId);
        String rawPassword = request.getPassword();

        // The principal currently holding this session, read BEFORE the credential is
        // verified only so that a successful take-over can drop that principal's index
        // entry. It never short-circuits the verification: answering a sign-on from the
        // session instead of the credential store would grant access on the strength of a
        // cookie alone, and would let a second sign-on for the same id succeed with any
        // password at all.
        SessionContext live = liveSessionContext(httpRequest);

        if (loginAttemptService.isLocked(userId)) {
            SecurityAuditLogger.authenticationLocked(userId, httpRequest);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, MSG_UNABLE_TO_VERIFY);
        }

        Optional<SecurityUser> found;
        try {
            found = securityUserRepository.findBySecUsrId(userId);
        } catch (DataAccessException ex) {
            SecurityAuditLogger.authenticationFailure(userId, "CREDENTIAL_STORE_UNAVAILABLE", httpRequest);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, MSG_UNABLE_TO_VERIFY);
        }

        if (found.isEmpty()) {
            // Perform the same one-way hashing work as a real verification so an
            // unknown id cannot be distinguished from a wrong password by timing.
            passwordEncoder.matches(rawPassword, timingEqualizationHash);
            loginAttemptService.recordFailure(userId);
            SecurityAuditLogger.authenticationFailure(userId, "USER_NOT_FOUND", httpRequest);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, MSG_USER_NOT_FOUND);
        }
        SecurityUser user = found.get();

        if (!passwordEncoder.matches(rawPassword, user.getSecUsrPwd())) {
            loginAttemptService.recordFailure(userId);
            SecurityAuditLogger.authenticationFailure(userId, "BAD_CREDENTIALS", httpRequest);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, MSG_WRONG_PASSWORD);
        }

        loginAttemptService.recordSuccess(userId);
        SignonResponseDto response = signonMapper.toSignonResponse(user);
        SessionContext context = signonMapper.toSessionContext(user);
        // The id the caller PRESENTED, captured before rotation replaces it. The principal
        // index is keyed by the id a session was registered under, so an outgoing principal
        // can only be dropped from it by that id, not by the post-rotation one.
        String presentedSessionId = presentedSessionId(httpRequest);
        // Credentials verified: only now is a session worth creating, and its id is
        // rotated first so a session id obtained before sign-on can never become an
        // authenticated one.
        HttpSession session = rotateSession(httpRequest);
        // Rotation removed the presented record from the store, so the index entry that
        // named it is now stale and is dropped -- whether the principal changed or not.
        // Leaving it behind would make a later revocation of the OUTGOING user address an
        // id that no longer exists, and would let a user's index grow one dead entry per
        // sign-on.
        if (live != null && presentedSessionId != null) {
            sessionPrincipalIndex.deregister(live.getUserId(), presentedSessionId);
        }
        session.setAttribute(SESSION_CONTEXT_ATTRIBUTE, context);
        sessionPrincipalIndex.register(context.getUserId(), session.getId());
        SecurityAuditLogger.authenticationSuccess(userId, httpRequest);
        return response;
    }

    /**
     * :purpose: Read the sign-on context of a session that is already authenticated.
     * :param httpRequest: the current HTTP request.
     * :returns: the live {@link SessionContext}, or ``null`` when the request carries
     *  no session or one that has not been signed on.
     */
    private SessionContext liveSessionContext(HttpServletRequest httpRequest) {
        HttpSession existing = httpRequest.getSession(false);
        if (existing == null) {
            return null;
        }
        Object attribute = existing.getAttribute(SESSION_CONTEXT_ATTRIBUTE);
        return attribute instanceof SessionContext context ? context : null;
    }

    /**
     * :purpose: Read the id of the session the caller PRESENTED, without creating one.
     * :param httpRequest: the current HTTP request.
     * :returns: the presented session id, or ``null`` when the request carried no session.
     */
    private String presentedSessionId(HttpServletRequest httpRequest) {
        HttpSession existing = httpRequest.getSession(false);
        return existing == null ? null : existing.getId();
    }

    /**
     * :purpose: Issue a fresh session id for the authenticated caller before any
     *  authenticated state is written to it, so a session id obtained before sign-on
     *  can never become an authenticated one (session fixation, CWE-384).
     * :param httpRequest: the current HTTP request.
     * :returns: the session that must carry the sign-on context, always identified by an
     *  id minted during this sign-on.
     * :note: Rotation is performed with ``HttpServletRequest.changeSessionId()`` and falls
     *  back to invalidate-and-create if the container declines to rotate, so the id changes
     *  on both paths. Emptying the record without rotating it -- the behaviour this
     *  replaces -- leaves the caller holding an identifier they knew BEFORE they were
     *  authenticated, which is precisely the fixation the rotation exists to defeat.
     */
    private HttpSession rotateSession(HttpServletRequest httpRequest) {
        HttpSession existing = httpRequest.getSession(false);
        if (existing == null) {
            // No session was presented, so sign-on mints a brand-new one with a fresh id.
            return httpRequest.getSession(true);
        }
        // Strip every attribute first so nothing written before sign-on survives into the
        // authenticated session, then replace the identifier itself.
        clearAttributes(existing);
        try {
            httpRequest.changeSessionId();
            HttpSession rotated = httpRequest.getSession(false);
            if (rotated != null) {
                // Rotation DELETES the previous store entry, and every other hop of this
                // request -- the api-gateway that proxied it -- still holds its own handle
                // on that entry and writes it back when the response commits. That
                // write-back is absorbed by RemovedSessionTolerantSessionRepository, so the
                // removal stands and the already-successful sign-on is not turned into a
                // 500 as it once was.
                return rotated;
            }
        } catch (IllegalStateException | UnsupportedOperationException ex) {
            // The container refused to rotate in place (no session under its own view of
            // the request, or an implementation that does not support it). Fall through to
            // invalidate-and-create, which changes the id just as effectively.
            log.debug("changeSessionId() declined by the container; invalidating and re-creating instead");
        }
        existing.invalidate();
        return httpRequest.getSession(true);
    }

    /**
     * :purpose: Remove every attribute from a session that is about to carry a new sign-on,
     *  so no state written before the caller was authenticated survives into the
     *  authenticated session.
     * :param session: the session being rotated.
     * :returns: nothing; the session is emptied in place.
     */
    private void clearAttributes(HttpSession session) {
        List<String> names = Collections.list(session.getAttributeNames());
        for (String name : names) {
            session.removeAttribute(name);
        }
    }
}
