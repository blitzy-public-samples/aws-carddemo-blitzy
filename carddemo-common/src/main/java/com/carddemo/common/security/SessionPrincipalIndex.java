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

import java.time.Duration;
import java.util.Collections;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;

/**
 * :purpose: Maintain the principal-to-session index that makes session revocation
 *     possible across the stateless CardDemo services. Sign-on records the session it
 *     created for a security-user id; administrator user maintenance uses the index to
 *     invalidate every live session of a user whose role changed or who was deleted, so
 *     a stale authorization cannot survive for the remainder of the session timeout.
 * :output: A Redis set per user id (``<namespace>:principal:<userId>``) whose members
 *     are live session ids, expiring with the session timeout.
 * :note: The index is intentionally explicit rather than relying on Spring Session's
 *     indexed repository: the indexed repository requires Redis keyspace-notification
 *     configuration (``CONFIG SET``) that a hardened, managed Redis often refuses,
 *     which would make startup fail. This implementation needs no server configuration.
 * :note: All operations fail soft: a Redis error is logged and treated as "no sessions
 *     known" so an index problem can never block sign-on or user maintenance. Deleting
 *     the session itself always goes through the session repository, so the correct
 *     namespace and serializer are used. When no Redis template or session repository is
 *     available (a web-layer slice test, for example) the index is inert and every
 *     operation is a no-op.
 */
public class SessionPrincipalIndex {

    /**
     * :purpose: Key infix separating the session namespace from the indexed user id.
     */
    public static final String PRINCIPAL_KEY_INFIX = ":principal:";

    private static final Logger LOG = LoggerFactory.getLogger(SessionPrincipalIndex.class);

    private final StringRedisTemplate redisTemplate;
    private final SessionRepository<? extends Session> sessionRepository;
    private final String namespace;
    private final Duration indexTtl;

    /**
     * :purpose: Construct the index.
     * :param redisTemplate: template used for the index set operations.
     * :param sessionRepository: repository used to delete revoked sessions.
     * :param namespace: Spring Session Redis namespace the application is configured
     *     with, so index keys live beside the session keys.
     * :param indexTtl: time to live applied to an index key, normally the session
     *     timeout.
     */
    public SessionPrincipalIndex(StringRedisTemplate redisTemplate,
                                 SessionRepository<? extends Session> sessionRepository,
                                 String namespace,
                                 Duration indexTtl) {
        this.redisTemplate = redisTemplate;
        this.sessionRepository = sessionRepository;
        this.namespace = namespace == null || namespace.isBlank() ? "carddemo:session" : namespace;
        this.indexTtl = indexTtl == null || indexTtl.isZero() || indexTtl.isNegative()
                ? Duration.ofMinutes(30)
                : indexTtl;
    }

    /**
     * :purpose: Record that a session belongs to a security-user id.
     * :param userId: the signed-on user id.
     * :param sessionId: the id of the session created for that user.
     */
    public void register(String userId, String sessionId) {
        if (!isEnabled() || isBlank(userId) || isBlank(sessionId)) {
            return;
        }
        String key = principalKey(userId);
        try {
            redisTemplate.opsForSet().add(key, sessionId);
            redisTemplate.expire(key, indexTtl);
        } catch (DataAccessException ex) {
            LOG.warn("Unable to index session for user {}: {}", userId, ex.getMessage());
        }
    }

    /**
     * :purpose: Remove a single session from a user's index (used on sign-out and on
     *     session-id rotation).
     * :param userId: the owning user id.
     * :param sessionId: the session id to forget.
     */
    public void deregister(String userId, String sessionId) {
        if (!isEnabled() || isBlank(userId) || isBlank(sessionId)) {
            return;
        }
        try {
            redisTemplate.opsForSet().remove(principalKey(userId), sessionId);
        } catch (DataAccessException ex) {
            LOG.warn("Unable to de-index session for user {}: {}", userId, ex.getMessage());
        }
    }

    /**
     * :purpose: Invalidate every live session held by a user and clear the index.
     * :param userId: the user whose sessions must stop authorizing.
     * :param reason: short machine-readable reason recorded in the audit trail, for
     *     example ``USER_DELETED`` or ``ROLE_CHANGED``.
     * :returns: the number of sessions that were deleted.
     */
    public int revokeSessions(String userId, String reason) {
        if (!isEnabled() || isBlank(userId)) {
            return 0;
        }
        String key = principalKey(userId);
        Set<String> sessionIds;
        try {
            sessionIds = redisTemplate.opsForSet().members(key);
        } catch (DataAccessException ex) {
            LOG.warn("Unable to read the session index for user {}: {}", userId, ex.getMessage());
            return 0;
        }
        if (sessionIds == null) {
            sessionIds = Collections.emptySet();
        }
        int revoked = 0;
        for (String sessionId : sessionIds) {
            try {
                sessionRepository.deleteById(sessionId);
                revoked++;
            } catch (RuntimeException ex) {
                LOG.warn("Unable to delete session {}: {}", sessionId, ex.getMessage());
            }
        }
        try {
            redisTemplate.delete(key);
        } catch (DataAccessException ex) {
            LOG.warn("Unable to clear the session index for user {}: {}", userId, ex.getMessage());
        }
        if (revoked > 0) {
            SecurityAuditLogger.sessionsRevoked(userId, reason, revoked);
        }
        return revoked;
    }

    /**
     * :purpose: Report whether the index has the collaborators it needs to operate.
     * :returns: ``true`` when both the Redis template and the session repository are
     *     available.
     */
    public boolean isEnabled() {
        return redisTemplate != null && sessionRepository != null;
    }

    /**
     * :purpose: Build the index key for a user id.
     * :param userId: the user id.
     * :returns: the fully qualified Redis key.
     */
    private String principalKey(String userId) {
        return namespace + PRINCIPAL_KEY_INFIX + userId.trim().toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * :purpose: Null-and-blank guard used by the public operations.
     * :param value: the value to test.
     * :returns: ``true`` when the value is ``null`` or blank.
     */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
