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
package com.carddemo.common.config;

import com.carddemo.common.dto.SessionContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.session.MapSession;
import org.springframework.session.SessionRepository;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * :purpose: Guard the session-serializer contract asserted by
 *     ``docs/decision-log.md``: session values must be written as allowlisted JSON
 *     by a {@link GenericJacksonJsonRedisSerializer}, never as a JDK native
 *     serialization stream (CWE-502). The bean name
 *     ``springSessionDefaultRedisSerializer`` is the exact name Spring Session
 *     looks up, so a rename would silently reinstate JDK serialization.
 */
class SessionRedisConfigTest {

    /** :purpose: Bean name Spring Session resolves for the session value serializer. */
    private static final String SPRING_SESSION_SERIALIZER_BEAN = "springSessionDefaultRedisSerializer";

    @Test
    @DisplayName("The exposed session serializer is the allowlisted JSON serializer, not JDK")
    void serializerIsJsonNotJdk() {
        RedisSerializer<Object> serializer = new SessionRedisConfig().springSessionDefaultRedisSerializer();

        assertThat(serializer).isInstanceOf(GenericJacksonJsonRedisSerializer.class);
        assertThat(serializer).isNotInstanceOf(JdkSerializationRedisSerializer.class);
    }

    @Test
    @DisplayName("The serializer bean is exposed under the name Spring Session looks up")
    void serializerBeanNameIsTheOneSpringSessionResolves() throws Exception {
        assertThat(SessionRedisConfig.class.getDeclaredMethod(SPRING_SESSION_SERIALIZER_BEAN))
                .isNotNull();
    }

    @Test
    @DisplayName("A SessionContext round-trips as JSON with no JDK serialization marker")
    void sessionContextRoundTripsAsJson() {
        RedisSerializer<Object> serializer = new SessionRedisConfig().springSessionDefaultRedisSerializer();

        SessionContext context = new SessionContext();
        context.setUserId("ADMIN001");
        context.setUserType(SessionContext.UserType.CDEMO_USRTYP_ADMIN);
        context.setProgramContext(SessionContext.ProgramContext.CDEMO_PGM_ENTER);
        context.setFromProgram("COSGN00C");
        context.setFromTranid("CC00");
        context.setAcctId(90000000001L);

        byte[] payload = serializer.serialize(context);
        assertThat(payload).isNotNull();
        String wire = new String(payload, StandardCharsets.UTF_8);

        // JDK serialization streams start with 0xAC 0xED; JSON starts with '{'.
        assertThat(payload[0]).isEqualTo((byte) '{');
        assertThat(wire).contains("com.carddemo.common.dto.SessionContext");
        assertThat(wire).contains("ADMIN001");

        Object restored = serializer.deserialize(payload);
        assertThat(restored).isInstanceOf(SessionContext.class);
        SessionContext round = (SessionContext) restored;
        assertThat(round.getUserId()).isEqualTo("ADMIN001");
        assertThat(round.getUserType()).isEqualTo(SessionContext.UserType.CDEMO_USRTYP_ADMIN);
        assertThat(round.getProgramContext()).isEqualTo(SessionContext.ProgramContext.CDEMO_PGM_ENTER);
        assertThat(round.getFromProgram()).isEqualTo("COSGN00C");
        assertThat(round.getAcctId()).isEqualTo(90000000001L);
    }

    /**
     * :purpose: The configuration wraps the auto-configured session repository so a session
     *     removed from the shared store by a peer service cannot fail the request that was
     *     holding it. The post-processor is asserted directly because the wrapping is what
     *     keeps a re-sign-on from reporting ``500``.
     */
    @Test
    @DisplayName("the session repository is wrapped for tolerance of a removed session")
    void sessionRepositoryIsWrapped() {
        BeanPostProcessor processor = SessionRedisConfig.removedSessionTolerantSessionRepository();
        SessionRepository<MapSession> underlying = new SessionRepository<>() {
            @Override
            public MapSession createSession() {
                return new MapSession();
            }

            @Override
            public void save(MapSession session) {
                throw new IllegalStateException(
                        RemovedSessionTolerantSessionRepository.INVALIDATED_MESSAGE);
            }

            @Override
            public MapSession findById(String id) {
                return null;
            }

            @Override
            public void deleteById(String id) {
                // no-op
            }
        };

        Object wrapped = processor.postProcessAfterInitialization(underlying, "sessionRepository");

        assertThat(wrapped).isInstanceOf(RemovedSessionTolerantSessionRepository.class);
        assertThat(processor.postProcessAfterInitialization(wrapped, "sessionRepository"))
                .as("an already-wrapped repository must not be wrapped twice")
                .isSameAs(wrapped);
        assertThat(processor.postProcessAfterInitialization("not a repository", "other"))
                .isEqualTo("not a repository");
    }

    @Test
    @DisplayName("a session replaced mid-request is not written back and does not fail the request")
    void saveToleratesASessionDeletedDuringTheRequest() {
        RecordingSessionRepository underlying = new RecordingSessionRepository();
        underlying.saveFailure = new IllegalStateException(
                RemovedSessionTolerantSessionRepository.INVALIDATED_MESSAGE);
        SessionRepository<MapSession> repository = wrap(underlying);

        repository.save(new MapSession("rotated-id"));

        assertThat(underlying.saveAttempts)
                .as("the write must still be attempted; only its failure is accepted")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a store failure that is not a replaced session still propagates")
    void saveStillPropagatesRealFailures() {
        RecordingSessionRepository underlying = new RecordingSessionRepository();
        underlying.saveFailure = new QueryTimeoutException("Redis command timed out");
        SessionRepository<MapSession> repository = wrap(underlying);

        assertThatThrownBy(() -> repository.save(new MapSession("id")))
                .isInstanceOf(QueryTimeoutException.class);
    }

    @Test
    @DisplayName("lookup, creation and deletion are pass-throughs, so a deleted session stays gone")
    void everythingOtherThanSaveDelegatesUnchanged() {
        RecordingSessionRepository underlying = new RecordingSessionRepository();
        SessionRepository<MapSession> repository = wrap(underlying);

        assertThat(repository.createSession()).isNotNull();
        assertThat(repository.findById("absent"))
                .as("a deleted session must still be reported absent, never resurrected")
                .isNull();
        repository.deleteById("gone");
        assertThat(underlying.deleted).containsExactly("gone");
    }

    /**
     * :purpose: Apply the post-processor and return the wrapped repository.
     * :param underlying: the repository to wrap.
     * :returns: the wrapped repository.
     */
    @SuppressWarnings("unchecked")
    private static SessionRepository<MapSession> wrap(RecordingSessionRepository underlying) {
        return (SessionRepository<MapSession>) SessionRedisConfig
                .removedSessionTolerantSessionRepository()
                .postProcessAfterInitialization(underlying, "sessionRepository");
    }

    /**
     * :purpose: Minimal repository that records what it was asked to do and can be told to
     *  fail a save, standing in for the Redis-backed repository.
     */
    private static final class RecordingSessionRepository implements SessionRepository<MapSession> {

        /** :purpose: Failure the next save raises, or ``null`` to succeed. */
        private RuntimeException saveFailure;

        /** :purpose: Number of times a save reached this repository. */
        private int saveAttempts;

        /** :purpose: Ids this repository was asked to delete. */
        private final java.util.List<String> deleted = new java.util.ArrayList<>();

        @Override
        public MapSession createSession() {
            return new MapSession();
        }

        @Override
        public void save(MapSession session) {
            this.saveAttempts++;
            if (this.saveFailure != null) {
                throw this.saveFailure;
            }
        }

        @Override
        public MapSession findById(String id) {
            return null;
        }

        @Override
        public void deleteById(String id) {
            this.deleted.add(id);
        }
    }
}
