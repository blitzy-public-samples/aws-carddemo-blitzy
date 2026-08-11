/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.common.security;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.carddemo.common.dto.SessionAttributes;
import com.carddemo.common.dto.SessionContext;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.session.MapSession;
import org.springframework.session.SessionRepository;

/**
 * :purpose: Unit tests for the logout handler that de-indexes the session being signed out,
 *     so the principal-to-session index lists only sessions that can still authorize and a
 *     later administrator revocation reports a truthful count.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SessionIndexLogoutHandler")
class SessionIndexLogoutHandlerTest {

    private static final String NAMESPACE = "carddemo:session";
    private static final String KEY = NAMESPACE + ":principal:USER0001";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private SetOperations<String, String> setOperations;

    @Mock
    private SessionRepository<MapSession> sessionRepository;

    private SessionIndexLogoutHandler handler;

    /**
     * :purpose: Build the handler over a live (non-inert) index.
     */
    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForSet()).thenReturn(setOperations);
        handler = new SessionIndexLogoutHandler(new SessionPrincipalIndex(
                redisTemplate, sessionRepository, NAMESPACE, Duration.ofMinutes(30)));
    }

    @Test
    @DisplayName("de-indexes the session using the user id carried by the session context")
    void deregistersUsingTheSessionContext() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        SessionContext context = new SessionContext();
        context.setUserId("USER0001");
        context.setUserType(SessionContext.UserType.CDEMO_USRTYP_USER);
        session.setAttribute(SessionAttributes.SESSION_CONTEXT, context);
        request.setSession(session);

        handler.logout(request, new MockHttpServletResponse(), null);

        verify(setOperations).remove(KEY, session.getId());
    }

    @Test
    @DisplayName("falls back to the authenticated principal name when the context is absent")
    void deregistersUsingThePrincipalName() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);

        handler.logout(request, new MockHttpServletResponse(),
                UsernamePasswordAuthenticationToken.unauthenticated("USER0001", null));

        verify(setOperations).remove(KEY, session.getId());
    }

    @Test
    @DisplayName("does nothing when the caller has no session")
    void ignoresASessionlessLogout() {
        handler.logout(new MockHttpServletRequest(), new MockHttpServletResponse(), null);

        verifyNoInteractions(setOperations);
    }

    @Test
    @DisplayName("does nothing when neither the context nor a principal names a user")
    void ignoresAnUnidentifiedLogout() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(new MockHttpSession());

        handler.logout(request, new MockHttpServletResponse(), null);

        verify(setOperations, never()).remove(KEY, request.getSession().getId());
    }
}
