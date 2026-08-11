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
package com.carddemo.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.session.MapSession;
import org.springframework.session.SessionRepository;

import java.util.ArrayList;
import java.util.List;

/**
 * :purpose: Verify that {@link RemovedSessionTolerantSessionRepository} absorbs ONLY the
 *  store's refusal to write a session that has already been removed -- the refusal that
 *  turned a successful re-sign-on into a ``500`` -- and that it delegates every other
 *  operation and propagates every other failure untouched.
 * :output: Exercises save-of-a-removed-session, save of a differently-messaged illegal
 *  state, an unrelated runtime failure, and the create/find/delete delegation.
 */
class RemovedSessionTolerantSessionRepositoryTest {

    /**
     * :purpose: Test double recording delegation and raising a configured failure on save.
     */
    private static final class RecordingRepository implements SessionRepository<MapSession> {

        private final RuntimeException saveFailure;

        private final List<String> calls = new ArrayList<>();

        /**
         * :purpose: Build the double.
         * :param saveFailure: failure raised by ``save``, or ``null`` to succeed.
         */
        RecordingRepository(RuntimeException saveFailure) {
            this.saveFailure = saveFailure;
        }

        @Override
        public MapSession createSession() {
            this.calls.add("createSession");
            return new MapSession();
        }

        @Override
        public void save(MapSession session) {
            this.calls.add("save:" + session.getId());
            if (this.saveFailure != null) {
                throw this.saveFailure;
            }
        }

        @Override
        public MapSession findById(String id) {
            this.calls.add("findById:" + id);
            return null;
        }

        @Override
        public void deleteById(String id) {
            this.calls.add("deleteById:" + id);
        }
    }

    /**
     * :purpose: A write-back of a session another service already removed is a no-op, so the
     *  request that was holding it completes with the response it produced.
     */
    @Test
    @DisplayName("writing back a removed session is absorbed")
    void removedSessionWriteBackIsAbsorbed() {
        RecordingRepository delegate = new RecordingRepository(new IllegalStateException(
                RemovedSessionTolerantSessionRepository.INVALIDATED_MESSAGE));
        SessionRepository<MapSession> repository =
                new RemovedSessionTolerantSessionRepository<>(delegate);
        MapSession session = new MapSession();

        assertThatCode(() -> repository.save(session)).doesNotThrowAnyException();
        assertThat(delegate.calls).containsExactly("save:" + session.getId());
    }

    /**
     * :purpose: A differently-messaged illegal state is a genuine fault and must propagate,
     *  so this wrapper cannot mask a real defect.
     */
    @Test
    @DisplayName("a differently-messaged illegal state propagates")
    void otherIllegalStatePropagates() {
        SessionRepository<MapSession> repository = new RemovedSessionTolerantSessionRepository<>(
                new RecordingRepository(new IllegalStateException("Redis is not reachable")));

        assertThatThrownBy(() -> repository.save(new MapSession()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Redis is not reachable");
    }

    /**
     * :purpose: An unrelated failure -- for example a store outage translated by Spring -- is
     *  propagated so it can be reported as the dependency failure it is.
     */
    @Test
    @DisplayName("an unrelated failure propagates")
    void unrelatedFailurePropagates() {
        SessionRepository<MapSession> repository = new RemovedSessionTolerantSessionRepository<>(
                new RecordingRepository(new org.springframework.dao.QueryTimeoutException("timeout")));

        assertThatThrownBy(() -> repository.save(new MapSession()))
                .isInstanceOf(org.springframework.dao.QueryTimeoutException.class);
    }

    /**
     * :purpose: Create, read and delete are delegated unchanged, so wrapping the repository
     *  alters nothing but the one refusal above.
     */
    @Test
    @DisplayName("create, read and delete are delegated unchanged")
    void otherOperationsAreDelegated() {
        RecordingRepository delegate = new RecordingRepository(null);
        SessionRepository<MapSession> repository =
                new RemovedSessionTolerantSessionRepository<>(delegate);

        MapSession created = repository.createSession();
        assertThat(created).isNotNull();
        assertThat(repository.findById("abc")).isNull();
        repository.deleteById("abc");

        assertThat(delegate.calls).containsExactly("createSession", "findById:abc", "deleteById:abc");
    }

    /**
     * :purpose: The static wrapper accepts a repository whose session type is not statically
     *  known, which is how the bean post-processor wraps Spring Session's package-private
     *  Redis session type.
     */
    @Test
    @DisplayName("the type-erased wrapper delegates correctly")
    void erasedWrapperDelegates() {
        RecordingRepository delegate = new RecordingRepository(null);
        SessionRepository<MapSession> repository =
                RemovedSessionTolerantSessionRepository.wrap(delegate);

        repository.deleteById("xyz");

        assertThat(delegate.calls).containsExactly("deleteById:xyz");
    }

    /**
     * :purpose: A null delegate is rejected at construction rather than at first use.
     */
    @Test
    @DisplayName("a null delegate is rejected")
    void nullDelegateRejected() {
        assertThatThrownBy(() -> new RemovedSessionTolerantSessionRepository<MapSession>(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
