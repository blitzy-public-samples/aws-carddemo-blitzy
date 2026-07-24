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
import com.carddemo.common.domain.SecurityUser;
import com.carddemo.common.dto.SessionContext;
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
 *  credentials against the security-user store and, on success, produces the
 *  sign-on response together with the externalized session context that replaces
 *  the CICS COMMAREA.
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
     *  failure (legacy ``READ-USER-SEC-FILE`` ``WHEN OTHER``).
     */
    private static final String MSG_UNABLE_TO_VERIFY = "Unable to verify the User ...";

    /**
     * :purpose: Session attribute key under which the externalized
     *  {@link SessionContext} (the CICS COMMAREA replacement) is stored on the
     *  current {@link HttpSession}; exposed so collaborating components read the
     *  same key.
     */
    public static final String SESSION_CONTEXT_ATTRIBUTE = "carddemoSessionContext";

    private final SecurityUserRepository securityUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final SignonMapper signonMapper;

    /**
     * :purpose: Construct the sign-on service with its collaborators.
     * :param securityUserRepository: repository for the keyed security-user lookup.
     * :param passwordEncoder: one-way encoder used to verify the submitted password.
     * :param signonMapper: builds the sign-on response and the session context.
     */
    public AuthenticationService(SecurityUserRepository securityUserRepository,
                                 PasswordEncoder passwordEncoder,
                                 SignonMapper signonMapper) {
        this.securityUserRepository = securityUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.signonMapper = signonMapper;
    }

    /**
     * :purpose: Authenticate a sign-on request and, on success, publish the
     *  externalized session context, reproducing the ``READ-USER-SEC-FILE``
     *  ``EVALUATE WS-RESP-CD`` control flow of ``COSGN00C``.
     * :param request: sign-on request carrying the entered user id and password.
     * :param session: current HTTP session that receives the COMMAREA-replacement
     *  session context on successful authentication.
     * :returns: the sign-on response (user id, user type, redirect target).
     * :raises ResponseStatusException: ``401 UNAUTHORIZED`` when the user is not
     *  found, the password does not match, or the credential store cannot be read.
     */
    @Transactional(readOnly = true)
    public SignonResponseDto signon(SignonRequestDto request, HttpSession session) {
        String enteredUserId = request.getUserId();
        String userId = enteredUserId == null ? "" : enteredUserId.trim().toUpperCase(Locale.ROOT);
        String rawPassword = request.getPassword();

        Optional<SecurityUser> found;
        try {
            found = securityUserRepository.findBySecUsrId(userId);
        } catch (DataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, MSG_UNABLE_TO_VERIFY);
        }

        SecurityUser user = found.orElseThrow(
                () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, MSG_USER_NOT_FOUND));

        if (!passwordEncoder.matches(rawPassword, user.getSecUsrPwd())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, MSG_WRONG_PASSWORD);
        }

        SignonResponseDto response = signonMapper.toSignonResponse(user);
        SessionContext context = signonMapper.toSessionContext(user);
        session.setAttribute(SESSION_CONTEXT_ATTRIBUTE, context);
        return response;
    }
}
