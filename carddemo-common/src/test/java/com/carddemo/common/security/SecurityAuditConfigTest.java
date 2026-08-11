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
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Supplier;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.event.AuthorizationDeniedEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.servletapi.SecurityContextHolderAwareRequestWrapper;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * :purpose: Verifies the security-event audit listeners. A denial of an HTTP request must
 *     produce no record here, because the request-aware handlers already audit it with the
 *     real source address, method, and path; auditing it twice yielded a duplicate whose
 *     address, method, and path were all ``unknown``. A request-less denial (method
 *     security) must be named by the protected operation rather than by an internal
 *     object dump, and an authentication failure must pick up the request in progress so
 *     the record carries a source address.
 */
@DisplayName("SecurityAuditConfig — security-event audit listeners")
class SecurityAuditConfigTest {

    private final SecurityAuditConfig config = new SecurityAuditConfig();

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
     * :purpose: Detach the appender and clear any bound request so nothing leaks.
     */
    @AfterEach
    void releaseAuditRecords() {
        auditLogger.detachAppender(appender);
        appender.stop();
        RequestContextHolder.resetRequestAttributes();
        CorrelationIdContext.clear();
    }

    private List<String> records() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static Supplier<Authentication> principal(String name) {
        return () -> new TestingAuthenticationToken(name, "n/a", "ROLE_USER");
    }

    @Test
    @DisplayName("a denied HTTP request produces no record here (the request-aware handlers own it)")
    void deniedHttpRequestIsNotAuditedTwice() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/users");
        request.setRequestURI("/users");
        SecurityContextHolderAwareRequestWrapper wrapper =
                new SecurityContextHolderAwareRequestWrapper(request, "ROLE_");

        config.onAuthorizationDenied(new AuthorizationDeniedEvent<>(
                principal("USER0001"), wrapper, new AuthorizationDecision(false)));

        assertThat(records()).isEmpty();
    }

    @Test
    @DisplayName("a method-security denial is named by the protected operation")
    void methodSecurityDenialNamesTheOperation() throws Exception {
        Method method = SampleSecured.class.getMethod("deleteUser");
        MethodInvocation invocation = new StubMethodInvocation(method);

        config.onAuthorizationDenied(new AuthorizationDeniedEvent<>(
                principal("USER0001"), invocation, new AuthorizationDecision(false)));

        assertThat(records()).hasSize(1);
        assertThat(records().get(0))
                .contains("event=AUTHORIZATION_DENIED")
                .contains("principal=USER0001")
                .contains("detail=SampleSecured#deleteUser");
    }

    @Test
    @DisplayName("any other denied object is named by its type, never by its toString")
    void otherDeniedObjectIsNamedByType() {
        config.onAuthorizationDenied(new AuthorizationDeniedEvent<>(
                principal("USER0001"), new NoisyToString(), new AuthorizationDecision(false)));

        assertThat(records()).hasSize(1);
        assertThat(records().get(0)).contains("detail=NoisyToString");
        assertThat(records().get(0)).doesNotContain("INTERNAL DUMP");
    }

    @Test
    @DisplayName("a denial with no authentication is recorded without a principal name")
    void denialWithoutAuthenticationIsStillRecorded() {
        config.onAuthorizationDenied(new AuthorizationDeniedEvent<>(
                () -> null, new NoisyToString(), new AuthorizationDecision(false)));

        assertThat(records()).hasSize(1);
        assertThat(records().get(0)).contains("principal=unknown");
    }

    @Test
    @DisplayName("an authentication failure records the source address of the request in progress")
    void authenticationFailureRecordsTheSourceAddress() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/prometheus");
        request.setRequestURI("/actuator/prometheus");
        request.setRemoteAddr("10.11.12.13");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        config.onAuthenticationFailure(new AuthenticationFailureBadCredentialsEvent(
                new TestingAuthenticationToken("monitoring", "wrong"),
                new BadCredentialsException("Bad credentials")));

        assertThat(records()).hasSize(1);
        assertThat(records().get(0))
                .contains("event=AUTHENTICATION_FAILURE")
                .contains("userId=monitoring")
                .contains("reason=BadCredentialsException")
                .contains("sourceIp=10.11.12.13")
                .contains("path=/actuator/prometheus");
    }

    @Test
    @DisplayName("an authentication failure outside a request still records, with unknown address")
    void authenticationFailureOutsideARequestStillRecords() {
        config.onAuthenticationFailure(new AuthenticationFailureBadCredentialsEvent(
                new TestingAuthenticationToken("monitoring", "wrong"),
                new BadCredentialsException("Bad credentials")));

        assertThat(records()).hasSize(1);
        assertThat(records().get(0)).contains("sourceIp=unknown");
    }

    /** :purpose: Target of the method-security denial fixture. */
    static final class SampleSecured {

        /** :purpose: Stand-in for an administrator-only operation. */
        public void deleteUser() {
            // Intentionally empty: only the method signature is under test.
        }
    }

    /** :purpose: Object whose toString would pollute the audit stream if it were used. */
    private static final class NoisyToString {

        @Override
        public String toString() {
            return "INTERNAL DUMP org.springframework.security.internal@1a2b3c[a=1,b=2,c=3]";
        }
    }

    /** :purpose: Minimal {@link MethodInvocation} carrying only the method under test. */
    private record StubMethodInvocation(Method method) implements MethodInvocation {

        @Override
        public Method getMethod() {
            return method;
        }

        @Override
        public Object[] getArguments() {
            return new Object[0];
        }

        @Override
        public Object proceed() {
            return null;
        }

        @Override
        public Object getThis() {
            return null;
        }

        @Override
        public java.lang.reflect.AccessibleObject getStaticPart() {
            return method;
        }
    }
}
