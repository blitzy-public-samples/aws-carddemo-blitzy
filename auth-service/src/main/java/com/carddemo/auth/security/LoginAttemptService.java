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
package com.carddemo.auth.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import com.carddemo.common.security.UserIdNormalizer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * :purpose: Reproduce the RACF revoke-after-N-failures control that protected the
 *     legacy ``USRSEC`` sign-on: after a configured number of consecutive failed
 *     attempts for one ``SEC-USR-ID``, further attempts for that id are refused for a
 *     configured period, so unlimited credential guessing against a known account is
 *     no longer possible (CWE-307).
 * :output: A per-user-id failure counter with an expiring lock window.
 * :note: Keyed on the upper-cased user id, matching the case handling of
 *     ``COSGN00C``, and held in memory: it adds no infrastructure dependency and
 *     cannot fail open when an external store is unavailable. The per-source-address
 *     request budget in ``RateLimitFilter`` bounds distributed guessing across many
 *     accounts, which a per-account counter cannot see.
 * :note: The tracked-id map is bounded by {@link #MAX_TRACKED_USERS}; expired entries
 *     are purged as they are touched and the map is cleared if the bound is reached,
 *     so the counter cannot become a memory-exhaustion vector.
 * :note: A locked attempt is answered ``429`` with the legacy "Unable to verify the
 *     User ..." text and deliberately carries no ``Retry-After``: like the RACF revoke
 *     it replaces, the control does not advertise when guessing may resume.
 */
@Service
public class LoginAttemptService {

    /**
     * :purpose: Upper bound on distinct user ids tracked at once.
     */
    public static final int MAX_TRACKED_USERS = 10_000;

    private final int maxFailedAttempts;
    private final Duration lockDuration;
    private final Clock clock;
    private final Map<String, FailureWindow> failures = new ConcurrentHashMap<>();

    /**
     * :purpose: Construct the service from configuration.
     * :param maxFailedAttempts: consecutive failures tolerated before the id is locked;
     *     values below one disable the control.
     * :param lockDuration: how long an id stays locked after the threshold is reached.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public LoginAttemptService(
            @Value("${carddemo.security.lockout.max-failed-attempts:5}") int maxFailedAttempts,
            @Value("${carddemo.security.lockout.duration:15m}") Duration lockDuration) {
        this(maxFailedAttempts, lockDuration, Clock.systemUTC());
    }

    /**
     * :purpose: Construct the service with an explicit time source.
     * :param maxFailedAttempts: consecutive failures tolerated before the id is locked.
     * :param lockDuration: how long an id stays locked after the threshold is reached.
     * :param clock: time source, injectable so the window is deterministic in tests.
     */
    public LoginAttemptService(int maxFailedAttempts, Duration lockDuration, Clock clock) {
        this.maxFailedAttempts = maxFailedAttempts;
        this.lockDuration = lockDuration == null || lockDuration.isNegative() ? Duration.ofMinutes(15) : lockDuration;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * :purpose: Report whether sign-on for a user id is currently refused.
     * :param userId: the submitted user id (case-insensitive).
     * :returns: ``true`` when the id has reached the failure threshold and the lock
     *     window has not yet elapsed.
     */
    public boolean isLocked(String userId) {
        if (maxFailedAttempts < 1 || userId == null || userId.isBlank()) {
            return false;
        }
        FailureWindow window = failures.get(key(userId));
        if (window == null) {
            return false;
        }
        if (isExpired(window)) {
            failures.remove(key(userId));
            return false;
        }
        return window.count() >= maxFailedAttempts;
    }

    /**
     * :purpose: Count one failed sign-on attempt for a user id.
     * :param userId: the submitted user id (case-insensitive).
     */
    public void recordFailure(String userId) {
        if (maxFailedAttempts < 1 || userId == null || userId.isBlank()) {
            return;
        }
        if (failures.size() >= MAX_TRACKED_USERS) {
            failures.clear();
        }
        String key = key(userId);
        Instant now = clock.instant();
        failures.compute(key, (ignored, window) -> {
            if (window == null || isExpired(window)) {
                return new FailureWindow(1, now);
            }
            return new FailureWindow(window.count() + 1, now);
        });
    }

    /**
     * :purpose: Clear the failure history for a user id after a successful sign-on.
     * :param userId: the authenticated user id (case-insensitive).
     */
    public void recordSuccess(String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        failures.remove(key(userId));
    }

    /**
     * :purpose: Normalize a user id to the upper-cased form used by ``COSGN00C``.
     * :param userId: the raw user id.
     * :returns: the trimmed, upper-cased key.
     */
    private String key(String userId) {
        return UserIdNormalizer.normalizeToKey(userId);
    }

    /**
     * :purpose: Decide whether a failure window has aged out.
     * :param window: the tracked window.
     * :returns: ``true`` when the lock duration has elapsed since the last failure.
     */
    private boolean isExpired(FailureWindow window) {
        return Duration.between(window.lastFailureAt(), clock.instant()).compareTo(lockDuration) >= 0;
    }

    /**
     * :purpose: Immutable failure tally for one user id.
     * :param count: consecutive failures observed.
     * :param lastFailureAt: instant of the most recent failure.
     */
    private record FailureWindow(int count, Instant lastFailureAt) {
    }
}
