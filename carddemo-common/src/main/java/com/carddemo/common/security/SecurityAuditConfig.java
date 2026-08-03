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

import jakarta.servlet.http.HttpServletRequest;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authorization.AuthorizationEventPublisher;
import org.springframework.security.authorization.SpringAuthorizationEventPublisher;
import org.springframework.security.authorization.event.AuthorizationDeniedEvent;
import org.springframework.security.core.Authentication;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * :purpose: Turn Spring Security's authentication-failure and authorization-denial
 *     events into the structured audit trail required by the observability rule, so a
 *     brute-force run, credential stuffing, or a role-boundary probe leaves a record
 *     even when it is stopped by a mechanism that does not itself log (for example a
 *     method-security check).
 * :output: An {@link AuthorizationEventPublisher} bean (which makes Spring Security
 *     publish {@link AuthorizationDeniedEvent}) plus listeners that forward both event
 *     families to {@link SecurityAuditLogger}.
 * :note: A service activates this configuration with
 *     ``@Import(SecurityAuditConfig.class)``. The HTTP-layer denials are already
 *     audited by {@link AuditingAuthenticationEntryPoint} and
 *     {@link AuditingAccessDeniedHandler}, which have the request available; these
 *     listeners cover the remaining, request-less paths.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "org.springframework.security.authorization.SpringAuthorizationEventPublisher")
public class SecurityAuditConfig {

    /**
     * :purpose: Enable publication of authorization decisions as application events.
     * :param applicationEventPublisher: the context event publisher.
     * :returns: the Spring Security authorization event publisher.
     */
    @Bean
    AuthorizationEventPublisher authorizationEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        return new SpringAuthorizationEventPublisher(applicationEventPublisher);
    }

    /**
     * :purpose: Audit an authorization denial published by Spring Security that no
     *     request-aware handler can see.
     * :param event: the denial event carrying the authentication supplier and the
     *     denied object.
     * :note: A denial of an HTTP request is deliberately ignored here. That path is
     *     already audited by {@link AuditingAccessDeniedHandler} (or, for an
     *     unauthenticated caller, by {@link AuditingAuthenticationEntryPoint}) with the
     *     real source address, method, and path. Auditing it a second time from this
     *     listener produced a duplicate record whose address, method, and path were all
     *     ``unknown``, and which reported an anonymous caller as an authorization denial
     *     rather than a missing authentication.
     */
    @EventListener
    public void onAuthorizationDenied(AuthorizationDeniedEvent<?> event) {
        Object deniedObject = event.getObject();
        if (deniedObject instanceof HttpServletRequest) {
            return;
        }
        Authentication authentication = event.getAuthentication() == null ? null : event.getAuthentication().get();
        SecurityAuditLogger.authorizationDenied(
                authentication == null ? null : authentication.getName(),
                describe(deniedObject));
    }

    /**
     * :purpose: Audit an authentication failure published by an
     *     ``AuthenticationProvider`` (for example a failed Prometheus scrape
     *     credential).
     * :param event: the failure event carrying the attempted authentication.
     */
    @EventListener
    public void onAuthenticationFailure(AbstractAuthenticationFailureEvent event) {
        Authentication authentication = event.getAuthentication();
        SecurityAuditLogger.authenticationFailure(
                authentication == null ? null : authentication.getName(),
                event.getException() == null ? "authentication failed" : event.getException().getClass().getSimpleName(),
                currentRequest());
    }

    /**
     * :purpose: Name the protected operation a request-less denial refers to.
     * :param deniedObject: the object Spring Security refused access to.
     * :returns: ``<DeclaringClass>#<method>`` for a method-security denial, the object's
     *     simple type name otherwise, and ``unknown`` when there is no object. The
     *     object's own ``toString`` is never used: for a secured request wrapper it is a
     *     long internal dump that carries no diagnostic value.
     */
    private String describe(Object deniedObject) {
        if (deniedObject == null) {
            return "unknown";
        }
        if (deniedObject instanceof MethodInvocation invocation && invocation.getMethod() != null) {
            return invocation.getMethod().getDeclaringClass().getSimpleName()
                    + "#" + invocation.getMethod().getName();
        }
        return deniedObject.getClass().getSimpleName();
    }

    /**
     * :purpose: Recover the request in progress so a security-event record carries the
     *     source address, method, and path even though the event itself does not.
     * :returns: the current request, or ``null`` outside a request (a batch step, for
     *     example).
     */
    private HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return servletAttributes.getRequest();
        }
        return null;
    }
}
