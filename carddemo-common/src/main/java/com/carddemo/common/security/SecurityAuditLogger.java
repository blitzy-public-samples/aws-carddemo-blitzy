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

import com.carddemo.common.config.CorrelationIdContext;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * :purpose: Emit the security-event audit trail required by the CardDemo
 *     observability rule: every authentication failure, authorization denial, and
 *     lockout decision produces one structured, correlation-aware ``WARN`` record, so
 *     credential stuffing, brute force, and boundary probing are detectable in the
 *     log stream (the modern equivalent of RACF's SMF security records).
 * :output: ``WARN`` records on the dedicated ``com.carddemo.security.audit`` logger,
 *     carrying the event name, the subject, the source address, the request method
 *     and path, and the request correlation id.
 * :note: Values are sanitized before they are written: user-supplied text is
 *     truncated and stripped of CR/LF by {@link CorrelationIdContext#sanitize} style
 *     handling here, and PAN-shaped digit runs are masked by
 *     {@link SensitiveDataMasker}. Passwords and password hashes are never logged.
 */
public final class SecurityAuditLogger {

    /**
     * :purpose: Dedicated audit logger name so deployments can route security events
     *     to their own appender or SIEM pipeline.
     */
    public static final String AUDIT_LOGGER_NAME = "com.carddemo.security.audit";

    /**
     * :purpose: Maximum number of characters retained from any single audited value,
     *     bounding log growth from oversized adversarial input.
     */
    private static final int MAX_VALUE_LENGTH = 64;

    /**
     * :purpose: Placeholder written when a value is not available on the audited path.
     */
    private static final String UNKNOWN = "unknown";

    private static final Logger AUDIT = LoggerFactory.getLogger(AUDIT_LOGGER_NAME);

    /**
     * :purpose: Prevent instantiation of this stateless helper.
     */
    private SecurityAuditLogger() {
    }

    /**
     * :purpose: Record a failed authentication attempt (unknown user id, wrong
     *     password, or an unverifiable credential store).
     * :param userId: the submitted user id, sanitized before logging.
     * :param reason: short machine-readable reason, for example ``USER_NOT_FOUND``.
     * :param request: the current request, used for the source address and path.
     */
    public static void authenticationFailure(String userId, String reason, HttpServletRequest request) {
        AUDIT.warn("event=AUTHENTICATION_FAILURE reason={} userId={} sourceIp={} method={} path={} correlationId={}",
                sanitize(reason), sanitize(userId), sourceIp(request), method(request), path(request), correlationId());
    }

    /**
     * :purpose: Record a successful authentication so a failure burst can be tied to
     *     the account that eventually succeeded.
     * :param userId: the authenticated user id.
     * :param request: the current request, used for the source address and path.
     */
    public static void authenticationSuccess(String userId, HttpServletRequest request) {
        AUDIT.info("event=AUTHENTICATION_SUCCESS userId={} sourceIp={} method={} path={} correlationId={}",
                sanitize(userId), sourceIp(request), method(request), path(request), correlationId());
    }

    /**
     * :purpose: Record a sign-on attempt refused because the account is temporarily
     *     locked after repeated failures.
     * :param userId: the locked user id.
     * :param request: the current request, used for the source address and path.
     */
    public static void authenticationLocked(String userId, HttpServletRequest request) {
        AUDIT.warn("event=AUTHENTICATION_LOCKED userId={} sourceIp={} method={} path={} correlationId={}",
                sanitize(userId), sourceIp(request), method(request), path(request), correlationId());
    }

    /**
     * :purpose: Record a request rejected because the caller exceeded the configured
     *     request rate.
     * :param clientKey: the throttled client key (normally the source address).
     * :param request: the current request, used for the method and path.
     */
    public static void rateLimited(String clientKey, HttpServletRequest request) {
        AUDIT.warn("event=RATE_LIMITED client={} sourceIp={} method={} path={} correlationId={}",
                sanitize(clientKey), sourceIp(request), method(request), path(request), correlationId());
    }

    /**
     * :purpose: Record an unauthenticated request to a protected resource (HTTP 401).
     * :param request: the current request.
     * :param detail: short description of the triggering condition.
     */
    public static void authenticationRequired(HttpServletRequest request, String detail) {
        AUDIT.warn("event=AUTHENTICATION_REQUIRED detail={} sourceIp={} method={} path={} correlationId={}",
                sanitize(detail), sourceIp(request), method(request), path(request), correlationId());
    }

    /**
     * :purpose: Record an authenticated request denied by an authorization rule
     *     (HTTP 403).
     * :param principal: the denied principal name, or ``null`` when anonymous.
     * :param request: the current request.
     * :param detail: short description of the triggering condition.
     */
    public static void authorizationDenied(String principal, HttpServletRequest request, String detail) {
        AUDIT.warn("event=AUTHORIZATION_DENIED principal={} detail={} sourceIp={} method={} path={} correlationId={}",
                sanitize(principal), sanitize(detail), sourceIp(request), method(request), path(request), correlationId());
    }

    /**
     * :purpose: Record an authorization denial observed without an available request
     *     (for example a method-security denial published as an application event).
     * :param principal: the denied principal name, or ``null`` when anonymous.
     * :param detail: short description of the denied operation.
     */
    public static void authorizationDenied(String principal, String detail) {
        AUDIT.warn("event=AUTHORIZATION_DENIED principal={} detail={} sourceIp={} method={} path={} correlationId={}",
                sanitize(principal), sanitize(detail), UNKNOWN, UNKNOWN, UNKNOWN, correlationId());
    }

    /**
     * :purpose: Record the revocation of every live session held by a principal after
     *     a privilege change or a delete.
     * :param userId: the affected user id.
     * :param reason: short machine-readable reason, for example ``USER_DELETED``.
     * :param revokedCount: number of sessions invalidated.
     */
    public static void sessionsRevoked(String userId, String reason, int revokedCount) {
        AUDIT.warn("event=SESSIONS_REVOKED userId={} reason={} revokedCount={} correlationId={}",
                sanitize(userId), sanitize(reason), revokedCount, correlationId());
    }

    /**
     * :purpose: Resolve the current request correlation id for the audit record.
     * :returns: the MDC correlation id, or ``unknown`` when none is established.
     */
    private static String correlationId() {
        String correlationId = CorrelationIdContext.getCorrelationId();
        return correlationId == null || correlationId.isBlank() ? UNKNOWN : correlationId;
    }

    /**
     * :purpose: Resolve the client address of a request.
     * :param request: the current request, possibly ``null``.
     * :returns: the remote address, or ``unknown``.
     */
    private static String sourceIp(HttpServletRequest request) {
        if (request == null) {
            return UNKNOWN;
        }
        String remoteAddr = request.getRemoteAddr();
        return remoteAddr == null || remoteAddr.isBlank() ? UNKNOWN : remoteAddr;
    }

    /**
     * :purpose: Resolve the HTTP method of a request.
     * :param request: the current request, possibly ``null``.
     * :returns: the method, or ``unknown``.
     */
    private static String method(HttpServletRequest request) {
        if (request == null || request.getMethod() == null) {
            return UNKNOWN;
        }
        return sanitize(request.getMethod());
    }

    /**
     * :purpose: Resolve the request path, masking any PAN it carries.
     * :param request: the current request, possibly ``null``.
     * :returns: the masked request URI, or ``unknown``.
     */
    private static String path(HttpServletRequest request) {
        if (request == null || request.getRequestURI() == null) {
            return UNKNOWN;
        }
        return sanitize(SensitiveDataMasker.maskPath(request.getRequestURI()));
    }

    /**
     * :purpose: Bound and neutralize a value before it is written to the log stream.
     * :param value: the raw value, possibly ``null``.
     * :returns: a CR/LF-free value truncated to {@link #MAX_VALUE_LENGTH}, or
     *     ``unknown`` when the input is ``null`` or blank.
     */
    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        String cleaned = SensitiveDataMasker.maskPan(value)
                .replace('\r', '_')
                .replace('\n', '_')
                .replace('\t', '_');
        return cleaned.length() <= MAX_VALUE_LENGTH ? cleaned : cleaned.substring(0, MAX_VALUE_LENGTH);
    }
}
