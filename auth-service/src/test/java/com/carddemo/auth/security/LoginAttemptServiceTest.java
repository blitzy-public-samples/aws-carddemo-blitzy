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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * :purpose: Unit tests for {@link LoginAttemptService}, the control that replaces the
 *     RACF revoke-after-N-failures protection of the legacy ``USRSEC`` sign-on: the
 *     failure threshold, the expiring lock window, the reset on success, the case
 *     handling inherited from ``COSGN00C``, the disable switch, and the bound that keeps
 *     the counter from becoming a memory-exhaustion vector. Time is supplied by a
 *     movable clock so the window is deterministic and no test sleeps.
 */
@DisplayName("LoginAttemptService — brute-force lockout (RACF revoke-after-N equivalent)")
class LoginAttemptServiceTest {

    private static final Duration LOCK = Duration.ofMinutes(15);

    /**
     * :purpose: Clock whose instant can be advanced, so the lock window is exercised
     *     without waiting for real time to pass.
     */
    private static final class MovableClock extends Clock {

        private Instant now = Instant.parse("2026-07-01T10:00:00Z");

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration amount) {
            now = now.plus(amount);
        }
    }

    @Test
    @DisplayName("an id is not locked until the configured number of failures is reached")
    void locksOnlyOnceThresholdIsReached() {
        LoginAttemptService service = new LoginAttemptService(5, LOCK, new MovableClock());

        for (int attempt = 1; attempt <= 4; attempt++) {
            service.recordFailure("USER0001");
            assertThat(service.isLocked("USER0001"))
                    .as("locked after %d failure(s)", attempt)
                    .isFalse();
        }

        service.recordFailure("USER0001");
        assertThat(service.isLocked("USER0001")).isTrue();
    }

    @Test
    @DisplayName("the lock is released once the lock window has elapsed")
    void releasesTheLockWhenTheWindowElapses() {
        MovableClock clock = new MovableClock();
        LoginAttemptService service = new LoginAttemptService(3, LOCK, clock);

        service.recordFailure("USER0001");
        service.recordFailure("USER0001");
        service.recordFailure("USER0001");
        assertThat(service.isLocked("USER0001")).isTrue();

        clock.advance(LOCK.minusSeconds(1));
        assertThat(service.isLocked("USER0001")).as("still locked one second early").isTrue();

        clock.advance(Duration.ofSeconds(1));
        assertThat(service.isLocked("USER0001")).as("released at the window boundary").isFalse();
    }

    @Test
    @DisplayName("a failure after the window has elapsed starts a fresh count")
    void restartsTheCountAfterTheWindowElapses() {
        MovableClock clock = new MovableClock();
        LoginAttemptService service = new LoginAttemptService(2, LOCK, clock);

        service.recordFailure("USER0001");
        clock.advance(LOCK);
        service.recordFailure("USER0001");

        assertThat(service.isLocked("USER0001"))
                .as("the aged-out failure must not count towards the new window")
                .isFalse();

        service.recordFailure("USER0001");
        assertThat(service.isLocked("USER0001")).isTrue();
    }

    @Test
    @DisplayName("a successful sign-on clears the failure history")
    void successClearsTheFailureHistory() {
        LoginAttemptService service = new LoginAttemptService(3, LOCK, new MovableClock());

        service.recordFailure("USER0001");
        service.recordFailure("USER0001");
        service.recordSuccess("USER0001");
        service.recordFailure("USER0001");
        service.recordFailure("USER0001");

        assertThat(service.isLocked("USER0001")).isFalse();
    }

    @Test
    @DisplayName("failures are counted per user id, so one locked id does not lock another")
    void countsFailuresPerUserId() {
        LoginAttemptService service = new LoginAttemptService(2, LOCK, new MovableClock());

        service.recordFailure("USER0001");
        service.recordFailure("USER0001");

        assertThat(service.isLocked("USER0001")).isTrue();
        assertThat(service.isLocked("USER0002")).isFalse();
    }

    @Test
    @DisplayName("the id is normalized the way COSGN00C upper-cases SEC-USR-ID")
    void normalizesTheUserIdLikeCosgn00c() {
        LoginAttemptService service = new LoginAttemptService(2, LOCK, new MovableClock());

        service.recordFailure(" user0001 ");
        service.recordFailure("User0001");

        assertThat(service.isLocked("USER0001")).isTrue();
        assertThat(service.isLocked(" uSeR0001 ")).isTrue();
    }

    @Test
    @DisplayName("a threshold below one disables the control")
    void thresholdBelowOneDisablesTheControl() {
        LoginAttemptService service = new LoginAttemptService(0, LOCK, new MovableClock());

        for (int attempt = 0; attempt < 50; attempt++) {
            service.recordFailure("USER0001");
        }

        assertThat(service.isLocked("USER0001")).isFalse();
    }

    @Test
    @DisplayName("null and blank ids are ignored and never reported as locked")
    void ignoresNullAndBlankIds() {
        LoginAttemptService service = new LoginAttemptService(1, LOCK, new MovableClock());

        service.recordFailure(null);
        service.recordFailure("   ");
        service.recordSuccess(null);

        assertThat(service.isLocked(null)).isFalse();
        assertThat(service.isLocked("   ")).isFalse();
    }

    @Test
    @DisplayName("a non-positive lock duration falls back to the fifteen-minute default")
    void fallsBackToTheDefaultLockDuration() {
        MovableClock clock = new MovableClock();
        LoginAttemptService service = new LoginAttemptService(1, Duration.ofMinutes(-5), clock);

        service.recordFailure("USER0001");
        assertThat(service.isLocked("USER0001")).isTrue();

        clock.advance(Duration.ofMinutes(14));
        assertThat(service.isLocked("USER0001")).isTrue();

        clock.advance(Duration.ofMinutes(1));
        assertThat(service.isLocked("USER0001")).isFalse();
    }

    @Test
    @DisplayName("the tracked-id map is bounded so the counter cannot exhaust memory")
    void boundsTheNumberOfTrackedIds() {
        LoginAttemptService service = new LoginAttemptService(1, LOCK, new MovableClock());

        for (int index = 0; index <= LoginAttemptService.MAX_TRACKED_USERS; index++) {
            service.recordFailure("USER" + index);
        }

        // The bound was reached, the window was reset, and the control still functions:
        // the id recorded after the reset is locked as configured.
        service.recordFailure("USERLAST");
        assertThat(service.isLocked("USERLAST")).isTrue();
    }
}
