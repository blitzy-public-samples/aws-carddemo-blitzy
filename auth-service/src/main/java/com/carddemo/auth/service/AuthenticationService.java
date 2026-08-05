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
import com.carddemo.common.dto.SessionContext;
import com.carddemo.common.security.SecurityAuditLogger;
import com.carddemo.common.security.SessionPrincipalIndex;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Locale;
import java.util.Optional;
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
     *  current {@link HttpSession}. It delegates to the canonical constant on
     *  {@link SessionContext} so every service reads and writes one key.
     */
    public static final String SESSION_CONTEXT_ATTRIBUTE = SessionContext.SESSION_ATTRIBUTE_NAME;

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
     */
    @Transactional(readOnly = true)
    public SignonResponseDto signon(SignonRequestDto request, HttpServletRequest httpRequest) {
        String enteredUserId = request.getUserId();
        String userId = enteredUserId == null ? "" : enteredUserId.trim().toUpperCase(Locale.ROOT);
        String rawPassword = request.getPassword();

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
        HttpSession session = rotateSession(httpRequest);
        session.setAttribute(SESSION_CONTEXT_ATTRIBUTE, context);
        sessionPrincipalIndex.register(context.getUserId(), session.getId());
        SecurityAuditLogger.authenticationSuccess(userId, httpRequest);
        return response;
    }

    /**
     * :purpose: Issue a fresh session id for the authenticated caller before any
     *  authenticated state is written to it, so a session id obtained before sign-on
     *  can never become an authenticated one.
     * :param httpRequest: the current HTTP request.
     * :returns: the session that must carry the sign-on context.
     */
    private HttpSession rotateSession(HttpServletRequest httpRequest) {
        HttpSession existing = httpRequest.getSession(false);
        if (existing != null) {
            String previousId = existing.getId();
            httpRequest.changeSessionId();
            HttpSession rotated = httpRequest.getSession(false);
            if (rotated != null && !previousId.equals(rotated.getId())) {
                return rotated;
            }
            if (rotated != null) {
                // The container did not rotate the id (for example a wrapper without
                // rotation support): invalidate and create a new session instead, so a
                // pre-authentication id is never reused.
                rotated.invalidate();
            }
        }
        return httpRequest.getSession(true);
    }
}
