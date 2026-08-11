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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;

/**
 * :purpose: Let a request whose session was removed from the shared store by ANOTHER
 *  service complete normally instead of failing. Every CardDemo service reads one Redis
 *  session namespace, so three ordinary operations delete the session a peer is holding
 *  mid-request: sign-on (``auth-service`` rotates the id, which deletes the previous
 *  key), sign-out, and an administrator revoking a user's sessions. Spring Session's
 *  Redis repository refuses to write a session whose key is gone -- correctly, since
 *  writing it would RESURRECT a session that was deliberately removed -- by raising
 *  ``IllegalStateException("Session was invalidated")``. That refusal surfaced from the
 *  write-back Spring Session performs as the response commits, which turned a
 *  SUCCESSFUL re-sign-on at the api-gateway into ``500`` while the new session it had
 *  just created was perfectly valid.
 * :output: A {@link SessionRepository} that delegates every operation and, on
 *  ``save``, treats that one refusal as the no-op it is: there is no session left to
 *  write. Every other failure propagates unchanged.
 * :note: This does NOT weaken session invalidation. The delegate raises the refusal
 *  BEFORE writing anything, so the removal stands and the next request presenting the
 *  removed id is unauthenticated, exactly as intended. Only the caller's view of the
 *  ALREADY-COMPLETED request changes.
 */
public final class RemovedSessionTolerantSessionRepository<S extends Session>
        implements SessionRepository<S> {

    /** :purpose: Logger for absorbed write-backs of a removed session. */
    private static final Logger log =
            LoggerFactory.getLogger(RemovedSessionTolerantSessionRepository.class);

    /**
     * :purpose: The message Spring Session's Redis repository raises when asked to write a
     *  session that no longer exists in the store.
     */
    static final String INVALIDATED_MESSAGE = "Session was invalidated";

    /** :purpose: The repository every operation is delegated to. */
    private final SessionRepository<S> delegate;

    /**
     * :purpose: Wrap a repository.
     * :param delegate: the repository to delegate to; must not be ``null``.
     */
    public RemovedSessionTolerantSessionRepository(SessionRepository<S> delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        this.delegate = delegate;
    }

    /**
     * :purpose: Wrap a repository whose session type is not statically known, as it is not
     *  at a bean-post-processing site (Spring Session's Redis session type is not public).
     * :param delegate: the repository to wrap.
     * :returns: the wrapping repository.
     */
    @SuppressWarnings("unchecked")
    public static <S extends Session> SessionRepository<S> wrap(SessionRepository<?> delegate) {
        return new RemovedSessionTolerantSessionRepository<>((SessionRepository<S>) delegate);
    }

    /**
     * :purpose: The repository every operation is delegated to, so a wiring test can assert
     *  WHICH store is behind the tolerance rather than only that the tolerance is installed.
     *  The post-processor replaces the container's repository bean with this wrapper, so the
     *  bean's own type no longer names the store.
     * :returns: the delegate repository; never ``null``.
     */
    public SessionRepository<S> delegate() {
        return this.delegate;
    }

    /**
     * :purpose: Create a new session.
     * :returns: the new session produced by the delegate.
     */
    @Override
    public S createSession() {
        return this.delegate.createSession();
    }

    /**
     * :purpose: Write the session, absorbing the delegate's refusal to write a session that
     *  has already been removed from the store.
     * :param session: the session to write.
     */
    @Override
    public void save(S session) {
        try {
            this.delegate.save(session);
        } catch (IllegalStateException ex) {
            if (!INVALIDATED_MESSAGE.equals(ex.getMessage())) {
                throw ex;
            }
            log.info("Session {} was removed from the shared store before this request wrote "
                    + "it back; nothing was written and the removal stands",
                    session == null ? "(unknown)" : session.getId());
        }
    }

    /**
     * :purpose: Read a session by id.
     * :param id: the session id.
     * :returns: the session, or ``null`` when it does not exist.
     */
    @Override
    public S findById(String id) {
        return this.delegate.findById(id);
    }

    /**
     * :purpose: Delete a session by id.
     * :param id: the session id.
     */
    @Override
    public void deleteById(String id) {
        this.delegate.deleteById(id);
    }
}
