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
package com.carddemo.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.carddemo.common.config.CorrelationIdContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.MissingCsrfTokenException;

/**
 * :purpose: Verifies that the shared access-denied handler answers ``403`` only for a
 *     caller who really is authenticated, and hands a denial raised for an
 *     unauthenticated caller to the ``401`` entry point instead. The distinction matters
 *     because ``CsrfFilter`` runs before ``AnonymousAuthenticationFilter``, so a
 *     missing-token denial on a write arrives here with an empty ``SecurityContext``:
 *     without the delegation an unauthenticated ``POST`` answered ``403`` while an
 *     unauthenticated ``GET`` answered ``401``, and the SPA (which redirects to sign-on on
 *     ``401`` only) left the caller silently refused.
 */
@DisplayName("AuditingAccessDeniedHandler — 403 for a principal, 401 for an unauthenticated caller")
class AuditingAccessDeniedHandlerTest {

    private final AuditingAccessDeniedHandler handler = new AuditingAccessDeniedHandler();

    private Logger auditLogger;

    private ListAppender<ILoggingEvent> appender;

    /**
     * :purpose: Attach a capturing appender to the audit logger.
     */
    @BeforeEach
    void captureAuditRecords() {
        auditLogger = (Logger) LoggerFactory.getLogger(SecurityAuditLogger.AUDIT_LOGGER_NAME);
        appender = new ListAppender<>();
        appender.start();
        auditLogger.addAppender(appender);
        auditLogger.setLevel(Level.DEBUG);
    }

    /**
     * :purpose: Detach the appender and clear the security context and correlation id.
     */
    @AfterEach
    void releaseAuditRecords() {
        auditLogger.detachAppender(appender);
        appender.stop();
        SecurityContextHolder.clearContext();
        CorrelationIdContext.clear();
    }

    private List<String> records() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static MockHttpServletRequest post() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/billpay");
        request.setRequestURI("/billpay");
        request.setRemoteAddr("10.1.2.3");
        return request;
    }

    @Test
    @DisplayName("an authenticated principal denied a route gets 403 and one AUTHORIZATION_DENIED record")
    void authenticatedPrincipalGets403() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("USER0001", "n/a", "ROLE_USER"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(post(), response, new AccessDeniedException("Access Denied"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(records()).hasSize(1);
        assertThat(records().get(0)).contains("event=AUTHORIZATION_DENIED", "USER0001", "10.1.2.3", "/billpay");
    }

    @Test
    @DisplayName("an empty security context is answered 401 with one AUTHENTICATION_REQUIRED record")
    void emptyContextGets401() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(post(), response, new MissingCsrfTokenException("token"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(records()).hasSize(1);
        assertThat(records().get(0)).contains("event=AUTHENTICATION_REQUIRED", "10.1.2.3", "/billpay");
        assertThat(records().get(0)).doesNotContain("AUTHORIZATION_DENIED");
    }

    @Test
    @DisplayName("an anonymous token is answered 401, exactly as ExceptionTranslationFilter would")
    void anonymousTokenGets401() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(post(), response, new AccessDeniedException("Access Denied"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(records()).hasSize(1);
        assertThat(records().get(0)).contains("event=AUTHENTICATION_REQUIRED");
    }

    @Test
    @DisplayName("a committed response is never re-written")
    void committedResponseIsLeftAlone() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);
        response.flushBuffer();

        handler.handle(post(), response, new MissingCsrfTokenException("token"));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(records()).hasSize(1);
    }

    @Test
    @DisplayName("a null denial reason still produces a record and 403 for a principal")
    void nullDenialReasonIsTolerated() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("ADMIN001", "n/a", "ROLE_ADMIN"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(post(), response, null);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(records()).hasSize(1);
        assertThat(records().get(0)).contains("event=AUTHORIZATION_DENIED", "ADMIN001");
    }
}
