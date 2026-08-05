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
package com.carddemo.common.dto;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

/**
 * :purpose: Unit tests for the one shared session-context reader/writer every CardDemo
 *     controller delegates to. Pins the fail-closed contract: no session, no context, or a
 *     wrong-typed attribute is REFUSED rather than answered with a fabricated blank context
 *     whose absent ``CDEMO-USER-TYPE`` would gate nothing.
 */
@DisplayName("SessionContextSupport")
class SessionContextSupportTest {

    @Test
    @DisplayName("require returns the context the session carries")
    void requireReturnsStoredContext() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        SessionContext stored = new SessionContext();
        stored.setUserId("ADMIN001");
        stored.setUserType(SessionContext.UserType.CDEMO_USRTYP_ADMIN);
        session.setAttribute(SessionAttributes.SESSION_CONTEXT, stored);
        request.setSession(session);

        assertSame(stored, SessionContextSupport.require(request));
    }

    @Test
    @DisplayName("require refuses a caller with no session")
    void requireRefusesWithoutSession() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> SessionContextSupport.require(new MockHttpServletRequest()));
        assertSame(SessionContextSupport.NO_SESSION_CONTEXT_MESSAGE, thrown.getMessage());
    }

    @Test
    @DisplayName("require refuses a session that carries no context")
    void requireRefusesWithoutAttribute() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(new MockHttpSession());

        assertThrows(IllegalStateException.class, () -> SessionContextSupport.require(request));
    }

    @Test
    @DisplayName("require refuses a wrong-typed session attribute")
    void requireRefusesWrongType() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionAttributes.SESSION_CONTEXT, "not-a-context");
        request.setSession(session);

        assertThrows(IllegalStateException.class, () -> SessionContextSupport.require(request));
    }

    @Test
    @DisplayName("store writes back to an existing session")
    void storeWritesToExistingSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);
        SessionContext context = new SessionContext();
        context.setUserId("USER0001");

        SessionContextSupport.store(request, context);

        assertSame(context, session.getAttribute(SessionAttributes.SESSION_CONTEXT));
    }

    @Test
    @DisplayName("store creates no session for a caller that has none")
    void storeCreatesNoSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        SessionContextSupport.store(request, new SessionContext());

        assertNull(request.getSession(false));
    }
}
