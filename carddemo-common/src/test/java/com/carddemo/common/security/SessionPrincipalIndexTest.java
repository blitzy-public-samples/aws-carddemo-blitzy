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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.session.MapSession;
import org.springframework.session.SessionRepository;

/**
 * :purpose: Unit tests for {@link SessionPrincipalIndex}, the principal-to-session index
 *     that makes session revocation possible: the key shape and namespace, the index TTL,
 *     registration and de-registration, revocation through the session repository, the
 *     fail-soft behaviour on a Redis error, and the inert mode used where no session
 *     store is configured.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SessionPrincipalIndex — principal-to-session index and revocation")
class SessionPrincipalIndexTest {

    private static final String NAMESPACE = "carddemo:session";
    private static final String KEY = NAMESPACE + ":principal:USER0001";
    private static final Duration TTL = Duration.ofMinutes(30);

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private SetOperations<String, String> setOperations;

    @Mock
    private SessionRepository<MapSession> sessionRepository;

    private SessionPrincipalIndex index;

    /**
     * :purpose: Wire the template to its set operations and build the index under test.
     */
    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForSet()).thenReturn(setOperations);
        index = new SessionPrincipalIndex(redisTemplate, sessionRepository, NAMESPACE, TTL);
    }

    @Test
    @DisplayName("register adds the session id under the namespaced key and applies the TTL")
    void registerAddsTheSessionIdAndAppliesTheTtl() {
        index.register("USER0001", "session-1");

        verify(setOperations).add(KEY, "session-1");
        verify(redisTemplate).expire(KEY, TTL);
    }

    @Test
    @DisplayName("the key is normalized the way COSGN00C upper-cases SEC-USR-ID")
    void normalizesTheUserIdIntoTheKey() {
        index.register(" user0001 ", "session-1");

        verify(setOperations).add(KEY, "session-1");
    }

    @Test
    @DisplayName("register ignores a blank user id or session id")
    void registerIgnoresBlankInput() {
        index.register(null, "session-1");
        index.register("USER0001", null);
        index.register("  ", "  ");

        verifyNoInteractions(setOperations);
    }

    @Test
    @DisplayName("deregister removes only the named session from the index")
    void deregisterRemovesOnlyThatSession() {
        index.deregister("USER0001", "session-1");

        verify(setOperations).remove(KEY, "session-1");
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    @DisplayName("revokeSessions deletes every indexed session and clears the index")
    void revokeSessionsDeletesEverySessionAndClearsTheIndex() {
        Set<String> members = new LinkedHashSet<>(Set.of("session-1", "session-2"));
        when(setOperations.members(KEY)).thenReturn(members);

        int revoked = index.revokeSessions("USER0001", "ROLE_CHANGED");

        assertThat(revoked).isEqualTo(2);
        verify(sessionRepository).deleteById("session-1");
        verify(sessionRepository).deleteById("session-2");
        verify(redisTemplate).delete(KEY);
    }

    @Test
    @DisplayName("revokeSessions reports zero and clears the index when the user has none")
    void revokeSessionsWithNoLiveSessions() {
        when(setOperations.members(KEY)).thenReturn(Set.of());

        assertThat(index.revokeSessions("USER0001", "USER_DELETED")).isZero();

        verify(sessionRepository, never()).deleteById(anyString());
        verify(redisTemplate).delete(KEY);
    }

    @Test
    @DisplayName("revokeSessions tolerates a null members reply")
    void revokeSessionsToleratesANullMembersReply() {
        when(setOperations.members(KEY)).thenReturn(null);

        assertThat(index.revokeSessions("USER0001", "USER_DELETED")).isZero();

        verify(sessionRepository, never()).deleteById(anyString());
    }

    @Test
    @DisplayName("revokeSessions continues after one session fails to delete")
    void revokeSessionsContinuesAfterASingleFailure() {
        when(setOperations.members(KEY))
                .thenReturn(new LinkedHashSet<>(java.util.List.of("session-1", "session-2")));
        doThrow(new IllegalStateException("gone")).when(sessionRepository).deleteById("session-1");

        assertThat(index.revokeSessions("USER0001", "ROLE_CHANGED")).isEqualTo(1);

        verify(sessionRepository).deleteById("session-2");
        verify(redisTemplate).delete(KEY);
    }

    @Test
    @DisplayName("a Redis read failure fails soft: zero revoked, no session deleted, no exception")
    void failsSoftOnARedisReadFailure() {
        when(setOperations.members(KEY)).thenThrow(new DataAccessResourceFailureException("redis down"));

        assertThat(index.revokeSessions("USER0001", "USER_DELETED")).isZero();

        verify(sessionRepository, never()).deleteById(anyString());
    }

    @Test
    @DisplayName("a Redis write failure during register fails soft and never breaks sign-on")
    void failsSoftOnARedisWriteFailure() {
        when(setOperations.add(eq(KEY), any(String[].class)))
                .thenThrow(new DataAccessResourceFailureException("redis down"));

        index.register("USER0001", "session-1");

        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("the index is inert when no Redis template or session repository is available")
    void isInertWithoutCollaborators() {
        SessionPrincipalIndex inert = new SessionPrincipalIndex(null, null, NAMESPACE, TTL);

        assertThat(inert.isEnabled()).isFalse();
        assertThat(inert.revokeSessions("USER0001", "USER_DELETED")).isZero();
        inert.register("USER0001", "session-1");
        inert.deregister("USER0001", "session-1");
    }

    @Test
    @DisplayName("a blank namespace and a non-positive TTL fall back to safe defaults")
    void fallsBackToSafeDefaults() {
        SessionPrincipalIndex defaulted =
                new SessionPrincipalIndex(redisTemplate, sessionRepository, "  ", Duration.ZERO);

        defaulted.register("USER0001", "session-1");

        verify(setOperations).add("carddemo:session:principal:USER0001", "session-1");
        verify(redisTemplate).expire("carddemo:session:principal:USER0001", Duration.ofMinutes(30));
    }

    @Test
    @DisplayName("revokeSessions ignores a blank user id")
    void revokeSessionsIgnoresABlankUserId() {
        assertThat(index.revokeSessions(null, "USER_DELETED")).isZero();
        assertThat(index.revokeSessions("   ", "USER_DELETED")).isZero();

        verifyNoInteractions(sessionRepository);
    }
}
