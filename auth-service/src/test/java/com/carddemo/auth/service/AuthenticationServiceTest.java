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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.auth.dto.SignonRequestDto;
import com.carddemo.auth.dto.SignonResponseDto;
import com.carddemo.auth.mapper.SignonMapper;
import com.carddemo.auth.repository.SecurityUserRepository;
import com.carddemo.common.domain.SecurityUser;
import com.carddemo.auth.security.LoginAttemptService;
import com.carddemo.common.dto.SessionContext;
import com.carddemo.common.exception.CardDemoException;
import com.carddemo.common.security.SessionPrincipalIndex;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

/**
 * :purpose: Pure Mockito unit tests for {@link AuthenticationService}, the sign-on
 *  business-logic service that re-platforms the ``PROCESS-ENTER-KEY`` /
 *  ``READ-USER-SEC-FILE`` paragraphs of legacy COBOL program ``COSGN00C`` (CICS
 *  transaction ``CC00``). All collaborators are Mockito mocks; the test loads no
 *  Spring application context and no container-backed integration harness.
 *  Verifies successful authentication with session-context
 *  publication, user-id upper-casing before lookup, the three verbatim
 *  ``401 UNAUTHORIZED`` failure reasons, user-type/redirect mapping, and the
 *  case-sensitivity deviation of the encoder path.
 */
@ExtendWith(MockitoExtension.class)
class AuthenticationServiceTest {

    /**
     * :purpose: Verbatim sign-on failure reason for an unknown user id
     *  (empty ``Optional``; legacy ``COSGN00C`` L249 / ``WS-RESP-CD = 13``).
     */
    private static final String MSG_USER_NOT_FOUND = "User not found. Try again ...";

    /**
     * :purpose: Verbatim sign-on failure reason for a password mismatch
     *  (encoder ``matches`` returns false; legacy ``COSGN00C`` L242).
     */
    private static final String MSG_WRONG_PASSWORD = "Wrong Password. Try again ...";

    /**
     * :purpose: Verbatim sign-on failure reason for a credential-store read
     *  failure (``DataAccessException``; legacy ``COSGN00C`` L254 / ``WHEN OTHER``).
     */
    private static final String MSG_UNABLE_TO_VERIFY = "Unable to verify the User ...";

    /**
     * :purpose: Expected {@code HttpSession} attribute key under which the
     *  externalized session context is stored; pinned against
     *  {@link AuthenticationService#SESSION_CONTEXT_ATTRIBUTE}.
     */
    private static final String SESSION_KEY = "carddemoSessionContext";

    /**
     * :purpose: Opaque stub credential hash returned by the mocked user; never a
     *  real secret and never compared for content by the service under test.
     */
    private static final String STORED_HASH = "$2a$10$abcdefghijklmnopqrstuv";

    @Mock
    private SecurityUserRepository securityUserRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private SignonMapper signonMapper;

    @Mock
    private HttpSession session;

    @Mock
    private HttpServletRequest httpRequest;

    @Mock
    private SessionPrincipalIndex sessionPrincipalIndex;

    /**
     * :purpose: Real lockout counter (not a mock) so the sign-on path exercises the
     *  RACF-equivalent revoke-after-N control together with the credential check.
     */
    private final LoginAttemptService loginAttemptService =
            new LoginAttemptService(5, java.time.Duration.ofMinutes(15));

    @InjectMocks
    private AuthenticationService authenticationService;

    /**
     * :purpose: Build the service under test with its collaborators, and make the
     *  mocked request return the mocked session for both the rotation lookup and the
     *  post-rotation fetch.
     */
    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        authenticationService = new AuthenticationService(
                securityUserRepository, passwordEncoder, signonMapper,
                loginAttemptService, sessionPrincipalIndex);
    }

    /**
     * :purpose: Pin the session-context attribute-key contract so downstream
     *  components and this suite bind to the exact same key.
     */
    @Test
    @DisplayName("SESSION_CONTEXT_ATTRIBUTE is the frozen key 'carddemoSessionContext'")
    void sessionContextAttribute_isCarddemoSessionContext() {
        assertThat(AuthenticationService.SESSION_CONTEXT_ATTRIBUTE).isEqualTo(SESSION_KEY);
    }

    /**
     * :purpose: On valid credentials the service returns the mapper's response
     *  unchanged, publishes the session context under the frozen key, and consults
     *  both mapper methods.
     */
    @Test
    @DisplayName("Successful sign-on returns the mapped DTO and stores the session context")
    void signon_success_returnsDtoAndStoresSessionContext() {
        SecurityUser user = mock(SecurityUser.class);
        when(securityUserRepository.findBySecUsrId("ADMIN001")).thenReturn(Optional.of(user));
        when(user.getSecUsrPwd()).thenReturn(STORED_HASH);
        when(passwordEncoder.matches("password", STORED_HASH)).thenReturn(true);
        SignonResponseDto expected =
                new SignonResponseDto("ADMIN001", SessionContext.UserType.CDEMO_USRTYP_ADMIN, "CA00");
        SessionContext ctx = new SessionContext();
        when(signonMapper.toSignonResponse(user)).thenReturn(expected);
        when(signonMapper.toSessionContext(user)).thenReturn(ctx);
        when(httpRequest.getSession(false)).thenReturn(null);
        when(httpRequest.getSession(true)).thenReturn(session);

        when(httpRequest.getSession(true)).thenReturn(session);

        SignonResponseDto result =
                authenticationService.signon(new SignonRequestDto("ADMIN001", "password"), httpRequest);

        assertThat(result).isSameAs(expected);
        verify(session).setAttribute(SESSION_KEY, ctx);
        verify(signonMapper).toSignonResponse(user);
        verify(signonMapper).toSessionContext(user);
    }

    /**
     * :purpose: A lower-case user id is upper-cased before the keyed lookup,
     *  reproducing the ``COSGN00C`` id normalization.
     */
    @Test
    @DisplayName("Lower-case user id is upper-cased before the repository lookup")
    void signon_lowerCaseUserId_looksUpUpperCased() {
        SecurityUser user = mock(SecurityUser.class);
        when(securityUserRepository.findBySecUsrId("ADMIN001")).thenReturn(Optional.of(user));
        when(user.getSecUsrPwd()).thenReturn(STORED_HASH);
        when(passwordEncoder.matches(anyString(), eq(STORED_HASH))).thenReturn(true);
        when(signonMapper.toSignonResponse(user))
                .thenReturn(new SignonResponseDto("ADMIN001", SessionContext.UserType.CDEMO_USRTYP_ADMIN, "CA00"));
        when(signonMapper.toSessionContext(user)).thenReturn(new SessionContext());
        when(httpRequest.getSession(false)).thenReturn(null);
        when(httpRequest.getSession(true)).thenReturn(session);

        when(httpRequest.getSession(true)).thenReturn(session);

        authenticationService.signon(new SignonRequestDto("admin001", "password"), httpRequest);

        ArgumentCaptor<String> idCaptor = ArgumentCaptor.forClass(String.class);
        verify(securityUserRepository).findBySecUsrId(idCaptor.capture());
        assertThat(idCaptor.getValue()).isEqualTo("ADMIN001");
    }

    /**
     * :purpose: An empty repository result maps to ``401 UNAUTHORIZED`` with the
     *  verbatim "User not found" reason and no further collaboration.
     */
    @Test
    @DisplayName("Unknown user yields 401 with the verbatim 'User not found' reason")
    void signon_userNotFound_throws401() {
        when(securityUserRepository.findBySecUsrId("USER0001")).thenReturn(Optional.empty());

        Throwable thrown = catchThrowable(() ->
                authenticationService.signon(new SignonRequestDto("user0001", "whatever"), httpRequest));

        assertThat(thrown).isInstanceOf(ResponseStatusException.class);
        ResponseStatusException ex = (ResponseStatusException) thrown;
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ex.getReason()).isEqualTo(MSG_USER_NOT_FOUND);
        // The unknown-user path deliberately performs the same one-way hashing work
        // as a real verification so response time does not reveal whether the id
        // exists (CWE-204); only the mapper must stay untouched.
        verify(passwordEncoder).matches(eq("whatever"), any());
        verifyNoInteractions(signonMapper);
        verify(session, never()).setAttribute(any(), any());
        // Issue 9: a rejected sign-on must not create a session at all. Reading the
        // existing session with getSession(false) creates nothing and is how a request
        // arriving over a live session is recognised, so only creation is forbidden.
        verify(httpRequest, never()).getSession(true);
    }

    /**
     * :purpose: A password that the encoder rejects maps to ``401 UNAUTHORIZED``
     *  with the verbatim "Wrong Password" reason and no session publication.
     */
    @Test
    @DisplayName("Wrong password yields 401 with the verbatim 'Wrong Password' reason")
    void signon_wrongPassword_throws401() {
        SecurityUser user = mock(SecurityUser.class);
        when(securityUserRepository.findBySecUsrId("ADMIN001")).thenReturn(Optional.of(user));
        when(user.getSecUsrPwd()).thenReturn(STORED_HASH);
        when(passwordEncoder.matches("wrongpass", STORED_HASH)).thenReturn(false);

        Throwable thrown = catchThrowable(() ->
                authenticationService.signon(new SignonRequestDto("admin001", "wrongpass"), httpRequest));

        assertThat(thrown).isInstanceOf(ResponseStatusException.class);
        ResponseStatusException ex = (ResponseStatusException) thrown;
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ex.getReason()).isEqualTo(MSG_WRONG_PASSWORD);
        verifyNoInteractions(signonMapper);
        verify(session, never()).setAttribute(any(), any());
        // Issue 9: a rejected sign-on must not create a session at all. Reading the
        // existing session with getSession(false) creates nothing and is how a request
        // arriving over a live session is recognised, so only creation is forbidden.
        verify(httpRequest, never()).getSession(true);
    }

    /**
     * :purpose: A ``DataAccessException`` from the credential store maps to
     *  ``401 UNAUTHORIZED`` with the verbatim "Unable to verify" reason; only the
     *  repository call is exercised.
     */
    @Test
    @DisplayName("Repository DataAccessException yields 401 'Unable to verify the User'")
    void signon_repositoryDataAccessException_throws401() {
        when(securityUserRepository.findBySecUsrId("ADMIN001"))
                .thenThrow(new DataAccessResourceFailureException("boom"));

        Throwable thrown = catchThrowable(() ->
                authenticationService.signon(new SignonRequestDto("admin001", "password"), httpRequest));

        assertThat(thrown).isInstanceOf(ResponseStatusException.class);
        ResponseStatusException ex = (ResponseStatusException) thrown;
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ex.getReason()).isEqualTo(MSG_UNABLE_TO_VERIFY);
        verify(passwordEncoder, never()).matches(anyString(), eq(STORED_HASH));
        verifyNoInteractions(signonMapper);
        verify(session, never()).setAttribute(any(), any());
        // Issue 9: a rejected sign-on must not create a session at all. Reading the
        // existing session with getSession(false) creates nothing and is how a request
        // arriving over a live session is recognised, so only creation is forbidden.
        verify(httpRequest, never()).getSession(true);
    }

    /**
     * :purpose: An administrator user resolves to the ``'A'`` user type and the
     *  ``CA00`` administrator-menu redirect target, returned unchanged from the mapper.
     */
    @Test
    @DisplayName("Admin user returns the 'A' user type and the CA00 redirect target")
    void signon_adminUser_returnsAdminUserTypeAndRedirect() {
        SecurityUser user = mock(SecurityUser.class);
        when(securityUserRepository.findBySecUsrId("ADMIN001")).thenReturn(Optional.of(user));
        when(user.getSecUsrPwd()).thenReturn(STORED_HASH);
        when(passwordEncoder.matches("password", STORED_HASH)).thenReturn(true);
        when(signonMapper.toSignonResponse(user))
                .thenReturn(new SignonResponseDto("ADMIN001", SessionContext.UserType.CDEMO_USRTYP_ADMIN, "CA00"));
        when(signonMapper.toSessionContext(user)).thenReturn(new SessionContext());
        when(httpRequest.getSession(false)).thenReturn(null);
        when(httpRequest.getSession(true)).thenReturn(session);

        when(httpRequest.getSession(true)).thenReturn(session);

        SignonResponseDto result =
                authenticationService.signon(new SignonRequestDto("admin001", "password"), httpRequest);

        assertThat(result.getUserType()).isEqualTo(SessionContext.UserType.CDEMO_USRTYP_ADMIN);
        assertThat(result.getRedirectTarget()).isEqualTo("CA00");
    }

    /**
     * :purpose: A regular user resolves to the ``'U'`` user type and the ``CM00``
     *  regular-user-menu redirect target, returned unchanged from the mapper.
     */
    @Test
    @DisplayName("Regular user returns the 'U' user type and the CM00 redirect target")
    void signon_regularUser_returnsUserTypeAndRedirect() {
        SecurityUser user = mock(SecurityUser.class);
        when(securityUserRepository.findBySecUsrId("USER0001")).thenReturn(Optional.of(user));
        when(user.getSecUsrPwd()).thenReturn(STORED_HASH);
        when(passwordEncoder.matches("password", STORED_HASH)).thenReturn(true);
        when(signonMapper.toSignonResponse(user))
                .thenReturn(new SignonResponseDto("USER0001", SessionContext.UserType.CDEMO_USRTYP_USER, "CM00"));
        when(signonMapper.toSessionContext(user)).thenReturn(new SessionContext());
        when(httpRequest.getSession(false)).thenReturn(null);
        when(httpRequest.getSession(true)).thenReturn(session);

        when(httpRequest.getSession(true)).thenReturn(session);

        SignonResponseDto result =
                authenticationService.signon(new SignonRequestDto("user0001", "password"), httpRequest);

        assertThat(result.getUserType()).isEqualTo(SessionContext.UserType.CDEMO_USRTYP_USER);
        assertThat(result.getRedirectTarget()).isEqualTo("CM00");
    }

    /**
     * :purpose: The raw password is forwarded to the encoder byte-for-byte, proving
     *  the service does not upper-case or otherwise alter it (unlike the legacy
     *  COBOL sign-on, which upper-cased the entered password).
     */
    @Test
    @DisplayName("Raw password is passed to the encoder unchanged (no upper-casing)")
    void signon_passwordCaseSensitivity_rawPasswordPassedUnchanged() {
        SecurityUser user = mock(SecurityUser.class);
        when(securityUserRepository.findBySecUsrId("ADMIN001")).thenReturn(Optional.of(user));
        when(user.getSecUsrPwd()).thenReturn(STORED_HASH);
        when(passwordEncoder.matches(anyString(), eq(STORED_HASH))).thenReturn(true);
        when(signonMapper.toSignonResponse(user))
                .thenReturn(new SignonResponseDto("ADMIN001", SessionContext.UserType.CDEMO_USRTYP_ADMIN, "CA00"));
        when(signonMapper.toSessionContext(user)).thenReturn(new SessionContext());
        when(httpRequest.getSession(false)).thenReturn(null);
        when(httpRequest.getSession(true)).thenReturn(session);

        when(httpRequest.getSession(true)).thenReturn(session);

        authenticationService.signon(new SignonRequestDto("admin001", "PaSsWoRd"), httpRequest);

        ArgumentCaptor<String> pwdCaptor = ArgumentCaptor.forClass(String.class);
        verify(passwordEncoder).matches(pwdCaptor.capture(), eq(STORED_HASH));
        assertThat(pwdCaptor.getValue()).isEqualTo("PaSsWoRd");
    }

    /**
     * :purpose: A password differing only by letter case is rejected by the encoder
     *  path, yielding ``401 UNAUTHORIZED`` with the verbatim "Wrong Password" reason
     *  and documenting the case-sensitivity deviation from the legacy behavior.
     */
    @Test
    @DisplayName("Case-only password difference is rejected as a wrong password")
    void signon_passwordCaseOnlyDifference_throwsWrongPassword() {
        SecurityUser user = mock(SecurityUser.class);
        when(securityUserRepository.findBySecUsrId("ADMIN001")).thenReturn(Optional.of(user));
        when(user.getSecUsrPwd()).thenReturn(STORED_HASH);
        when(passwordEncoder.matches("password", STORED_HASH)).thenReturn(false);

        Throwable thrown = catchThrowable(() ->
                authenticationService.signon(new SignonRequestDto("admin001", "password"), httpRequest));

        assertThat(thrown).isInstanceOf(ResponseStatusException.class);
        ResponseStatusException ex = (ResponseStatusException) thrown;
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ex.getReason()).isEqualTo(MSG_WRONG_PASSWORD);
        verify(session, never()).setAttribute(any(), any());
        // Issue 9: a rejected sign-on must not create a session at all. Reading the
        // existing session with getSession(false) creates nothing and is how a request
        // arriving over a live session is recognised, so only creation is forbidden.
        verify(httpRequest, never()).getSession(true);
    }

    /**
     * :purpose: A sign-on that presents NO session is given a brand-new one, so an id that
     *  existed before this sign-on can never become the authenticated one (session
     *  fixation, CWE-384).
     */
    @Test
    @DisplayName("Sign-on with no session presented creates a fresh session")
    void signon_success_createsFreshSessionWhenNonePresented() {
        SecurityUser user = mock(SecurityUser.class);
        when(session.getId()).thenReturn("fresh-id");
        when(httpRequest.getSession(false)).thenReturn(null);
        when(httpRequest.getSession(true)).thenReturn(session);
        when(securityUserRepository.findBySecUsrId("ADMIN001")).thenReturn(Optional.of(user));
        when(user.getSecUsrPwd()).thenReturn(STORED_HASH);
        when(passwordEncoder.matches("password", STORED_HASH)).thenReturn(true);
        SessionContext ctx = new SessionContext();
        ctx.setUserId("ADMIN001");
        when(signonMapper.toSignonResponse(user))
                .thenReturn(new SignonResponseDto("ADMIN001", SessionContext.UserType.CDEMO_USRTYP_ADMIN, "CA00"));
        when(signonMapper.toSessionContext(user)).thenReturn(ctx);

        authenticationService.signon(new SignonRequestDto("admin001", "password"), httpRequest);

        verify(httpRequest).getSession(true);
        verify(session).setAttribute(SESSION_KEY, ctx);
        verify(sessionPrincipalIndex).register("ADMIN001", "fresh-id");
    }

    /**
     * :purpose: A sign-on presenting a session that carries no sign-on context -- one this
     *  service cannot recognise as signed on, because it expired or ``UserService`` revoked
     *  it in place -- REUSES that record, emptied of every attribute, and neither rotates
     *  nor invalidates it. Rotation deletes the store entry, and the gateway that proxied
     *  the call still holds its own handle on it: its save then failed outside any
     *  exception handler, the caller was answered ``500``, and because the replacement id
     *  was never persisted every later request on it was refused, so the operator could
     *  never sign on again. Emptying the record keeps the pre-authentication state from
     *  surviving without breaking the other participant.
     */
    @Test
    @DisplayName("Sign-on over a context-less session reuses it, emptied, without rotating")
    void signon_success_reusesContextLessSessionWithoutRotating() {
        SecurityUser user = mock(SecurityUser.class);
        when(session.getId()).thenReturn("reused-id");
        when(session.getAttributeNames())
                .thenReturn(Collections.enumeration(List.of("stale.attribute")));
        when(httpRequest.getSession(false)).thenReturn(session);
        when(securityUserRepository.findBySecUsrId("ADMIN001")).thenReturn(Optional.of(user));
        when(user.getSecUsrPwd()).thenReturn(STORED_HASH);
        when(passwordEncoder.matches("password", STORED_HASH)).thenReturn(true);
        SessionContext ctx = new SessionContext();
        ctx.setUserId("ADMIN001");
        when(signonMapper.toSignonResponse(user))
                .thenReturn(new SignonResponseDto("ADMIN001", SessionContext.UserType.CDEMO_USRTYP_ADMIN, "CA00"));
        when(signonMapper.toSessionContext(user)).thenReturn(ctx);

        authenticationService.signon(new SignonRequestDto("admin001", "password"), httpRequest);

        // The shared record is neither rotated nor destroyed, so no other participant of
        // this request loses the entry it is holding.
        verify(httpRequest, never()).changeSessionId();
        verify(httpRequest, never()).getSession(true);
        verify(session, never()).invalidate();
        // Nothing written before sign-on survives into the authenticated session.
        verify(session).removeAttribute("stale.attribute");
        verify(session).setAttribute(SESSION_KEY, ctx);
        verify(sessionPrincipalIndex).register("ADMIN001", "reused-id");
    }

    /**
     * :purpose: After the configured number of consecutive failures the user id is
     *  locked and further attempts are refused with ``429 TOO_MANY_REQUESTS``, even
     *  when the submitted password is correct (CWE-307).
     */
    @Test
    @DisplayName("Repeated failures lock the user id and yield 429 until the window elapses")
    void signon_repeatedFailures_locksUserId() {
        when(securityUserRepository.findBySecUsrId("ADMIN001")).thenReturn(Optional.empty());

        for (int attempt = 0; attempt < 5; attempt++) {
            Throwable failure = catchThrowable(() ->
                    authenticationService.signon(new SignonRequestDto("admin001", "guess"), httpRequest));
            assertThat(((ResponseStatusException) failure).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        Throwable locked = catchThrowable(() ->
                authenticationService.signon(new SignonRequestDto("admin001", "password"), httpRequest));

        assertThat(locked).isInstanceOf(ResponseStatusException.class);
        ResponseStatusException ex = (ResponseStatusException) locked;
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(ex.getReason()).isEqualTo(MSG_UNABLE_TO_VERIFY);
        assertThat(loginAttemptService.isLocked("ADMIN001")).isTrue();
        verify(session, never()).setAttribute(any(), any());
    }

    /**
     * :purpose: A successful sign-on clears the failure history so a legitimate user is
     *  not locked out by earlier mistyped attempts.
     */
    @Test
    @DisplayName("Successful sign-on clears the failed-attempt history")
    void signon_success_clearsFailureHistory() {
        SecurityUser user = mock(SecurityUser.class);
        when(securityUserRepository.findBySecUsrId("ADMIN001"))
                .thenReturn(Optional.empty(), Optional.of(user));
        when(user.getSecUsrPwd()).thenReturn(STORED_HASH);
        when(passwordEncoder.matches("password", STORED_HASH)).thenReturn(true);
        when(signonMapper.toSignonResponse(user))
                .thenReturn(new SignonResponseDto("ADMIN001", SessionContext.UserType.CDEMO_USRTYP_ADMIN, "CA00"));
        when(signonMapper.toSessionContext(user)).thenReturn(new SessionContext());
        when(httpRequest.getSession(false)).thenReturn(null);
        when(httpRequest.getSession(true)).thenReturn(session);

        catchThrowable(() ->
                authenticationService.signon(new SignonRequestDto("admin001", "guess"), httpRequest));
        authenticationService.signon(new SignonRequestDto("admin001", "password"), httpRequest);

        assertThat(loginAttemptService.isLocked("ADMIN001")).isFalse();
    }

    /**
     * :purpose: A sign-on arriving over a session already signed on as the SAME user is
     *  answered idempotently from the session it already holds. Nothing about the
     *  session is changed: the api-gateway is a second Spring Session participant
     *  holding the same record, so rotating or invalidating it here made the gateway's
     *  own write-back fail and destroyed a session that was valid.
     */
    @Test
    @DisplayName("Re-sign-on by the same principal returns the live session unchanged")
    void signon_alreadySignedOnSameUser_isIdempotent() {
        SessionContext live = new SessionContext();
        live.setUserId("ADMIN001");
        live.setUserType(SessionContext.UserType.CDEMO_USRTYP_ADMIN);
        when(httpRequest.getSession(false)).thenReturn(session);
        when(session.getAttribute(SESSION_KEY)).thenReturn(live);
        when(signonMapper.toSignonResponse(live))
                .thenReturn(new SignonResponseDto("ADMIN001", SessionContext.UserType.CDEMO_USRTYP_ADMIN, "CA00"));

        SignonResponseDto response =
                authenticationService.signon(new SignonRequestDto("admin001", "password"), httpRequest);

        assertThat(response.getUserId()).isEqualTo("ADMIN001");
        assertThat(response.getRedirectTarget()).isEqualTo("CA00");
        verify(httpRequest, never()).changeSessionId();
        verify(httpRequest, never()).getSession(true);
        verify(session, never()).invalidate();
        verify(session, never()).setAttribute(any(), any());
        verifyNoInteractions(securityUserRepository);
        // The credential is not re-verified: the session already carries the identity.
        // The encoder was used once at construction to build the timing-equalization
        // hash, so only the verification call itself is asserted absent.
        verify(passwordEncoder, never()).matches(any(), any());
    }

    /**
     * :purpose: A sign-on arriving over a session already signed on as a DIFFERENT user
     *  is refused, and the live session survives the refusal. The legacy sign-on screen
     *  could only be reached by ending the current session first, so the sequence is
     *  invalid rather than a re-authentication.
     */
    @Test
    @DisplayName("Sign-on as another user over a live session is refused, session intact")
    void signon_alreadySignedOnDifferentUser_isRefused() {
        SessionContext live = new SessionContext();
        live.setUserId("ADMIN001");
        live.setUserType(SessionContext.UserType.CDEMO_USRTYP_ADMIN);
        when(httpRequest.getSession(false)).thenReturn(session);
        when(session.getAttribute(SESSION_KEY)).thenReturn(live);

        Throwable thrown = catchThrowable(() ->
                authenticationService.signon(new SignonRequestDto("user0001", "password"), httpRequest));

        assertThat(thrown).isInstanceOf(CardDemoException.class);
        assertThat(thrown).hasMessage(
                "Already signed on. Sign off before signing on as another user.");
        verify(httpRequest, never()).changeSessionId();
        verify(httpRequest, never()).getSession(true);
        verify(session, never()).invalidate();
        verify(session, never()).setAttribute(any(), any());
        verifyNoInteractions(securityUserRepository);
    }
}
