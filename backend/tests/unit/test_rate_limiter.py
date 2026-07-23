# Unit tests for app.core.rate_limiter.LoginRateLimiter (QA finding M-01).
# There is no legacy COBOL source: the mainframe COSGN00C applied no attempt
# limit. These tests pin the throttle's state machine in isolation (no DB, no
# HTTP) using a fresh limiter per test and an injected fake clock.
"""Unit tests for the in-memory login rate limiter (:mod:`app.core.rate_limiter`).

The limiter is exercised end to end through the sign-on endpoint in
``tests/api/test_auth_lifecycle.py``; this suite complements that by pinning the
limiter's own state machine directly: key normalization, the failure -> lockout
transition at the configured threshold, lockout expiry, success/reset clearing,
independence of distinct keys, and that a lockout window is not extended by
further failures while already locked.

Each test constructs its OWN :class:`LoginRateLimiter` with a controllable clock
so it depends on neither the process-wide singleton nor real time. The threshold
and window are read from :data:`app.core.config.settings` (the same values the
production limiter honors), so the assertions track configuration rather than a
hardcoded duplicate.

Ochs naming (0.8.2 / 0.8.3): snake_case test names (pytest contract), PascalCase
helpers, camelCase locals, ALL_UPPERCASE constants.
"""

from __future__ import annotations

from app.core.config import settings
from app.core.rate_limiter import UNKNOWN_CLIENT_HOST, LoginRateLimiter

# ---------------------------------------------------------------------------
# Module constants (ALL_UPPERCASE per the Ochs Rule 0.8.2).
# ---------------------------------------------------------------------------
USER_ID = "ADMIN001"
OTHER_USER_ID = "USER0001"
CLIENT_HOST = "203.0.113.7"
OTHER_CLIENT_HOST = "198.51.100.4"
# Starting value for the fake clock; any finite float works.
START_TIME = 1000.0


class FakeClock:
    """A manually advanced monotonic clock for deterministic lockout timing.

    Attributes:
        now: The current fake time in seconds; advance it with :meth:`Advance`.
    """

    def __init__(self, start: float) -> None:
        """Initialize the clock at ``start`` seconds.

        Args:
            start: The initial time value the clock reports.
        """
        self.now = start

    def __call__(self) -> float:
        """Return the current fake time (limiter clock protocol).

        Returns:
            The current time in seconds.
        """
        return self.now

    def Advance(self, seconds: float) -> None:
        """Advance the clock forward.

        Args:
            seconds: The number of seconds to move the clock forward by.
        """
        self.now = self.now + seconds


def BuildLimiter() -> tuple[LoginRateLimiter, FakeClock]:
    """Construct a fresh limiter wired to a fresh fake clock.

    Returns:
        A ``(limiter, clock)`` pair; the clock is already installed on the
        limiter and can be advanced by the test.
    """
    clock = FakeClock(START_TIME)
    limiter = LoginRateLimiter(clock=clock)
    return limiter, clock


def test_build_key_normalizes_user_id_case_and_whitespace():
    """BuildKey uppercases and trims the user id, and includes the client host."""
    limiter, _ = BuildLimiter()

    keyLower = limiter.BuildKey("  admin001  ", CLIENT_HOST)
    keyUpper = limiter.BuildKey("ADMIN001", CLIENT_HOST)

    assert keyLower == keyUpper
    assert CLIENT_HOST in keyUpper


def test_distinct_hosts_produce_distinct_keys():
    """The same user id from different hosts yields independent keys."""
    limiter, _ = BuildLimiter()

    assert limiter.BuildKey(USER_ID, CLIENT_HOST) != limiter.BuildKey(
        USER_ID, OTHER_CLIENT_HOST
    )


def test_fresh_key_is_not_locked():
    """A key with no recorded failures is not locked."""
    limiter, _ = BuildLimiter()

    assert limiter.IsLocked(limiter.BuildKey(USER_ID, CLIENT_HOST)) is False


def test_failures_below_threshold_do_not_lock():
    """Fewer than the threshold number of failures leave the key unlocked."""
    limiter, _ = BuildLimiter()
    key = limiter.BuildKey(USER_ID, CLIENT_HOST)

    for _ in range(settings.LOGIN_MAX_ATTEMPTS - 1):
        limiter.RegisterFailure(key)

    assert limiter.IsLocked(key) is False


def test_failures_at_threshold_lock():
    """Reaching the threshold number of failures locks the key."""
    limiter, _ = BuildLimiter()
    key = limiter.BuildKey(USER_ID, CLIENT_HOST)

    for _ in range(settings.LOGIN_MAX_ATTEMPTS):
        limiter.RegisterFailure(key)

    assert limiter.IsLocked(key) is True


def test_lockout_expires_after_window():
    """After the lockout window elapses, the key is no longer locked."""
    limiter, clock = BuildLimiter()
    key = limiter.BuildKey(USER_ID, CLIENT_HOST)
    for _ in range(settings.LOGIN_MAX_ATTEMPTS):
        limiter.RegisterFailure(key)
    assert limiter.IsLocked(key) is True

    clock.Advance(settings.LOGIN_LOCKOUT_SECONDS + 1)

    assert limiter.IsLocked(key) is False


def test_lockout_holds_until_window_elapses():
    """The key stays locked right up to (but not past) the window boundary."""
    limiter, clock = BuildLimiter()
    key = limiter.BuildKey(USER_ID, CLIENT_HOST)
    for _ in range(settings.LOGIN_MAX_ATTEMPTS):
        limiter.RegisterFailure(key)

    # One second before expiry the key is still locked.
    clock.Advance(settings.LOGIN_LOCKOUT_SECONDS - 1)
    assert limiter.IsLocked(key) is True


def test_success_clears_counter():
    """RegisterSuccess resets accumulated failures below the threshold."""
    limiter, _ = BuildLimiter()
    key = limiter.BuildKey(USER_ID, CLIENT_HOST)
    for _ in range(settings.LOGIN_MAX_ATTEMPTS - 1):
        limiter.RegisterFailure(key)

    limiter.RegisterSuccess(key)

    # A full fresh run of failures is needed to lock again (counter was cleared).
    for _ in range(settings.LOGIN_MAX_ATTEMPTS - 1):
        limiter.RegisterFailure(key)
    assert limiter.IsLocked(key) is False


def test_reset_clears_all_state():
    """Reset discards every key's state."""
    limiter, _ = BuildLimiter()
    key = limiter.BuildKey(USER_ID, CLIENT_HOST)
    for _ in range(settings.LOGIN_MAX_ATTEMPTS):
        limiter.RegisterFailure(key)
    assert limiter.IsLocked(key) is True

    limiter.Reset()

    assert limiter.IsLocked(key) is False


def test_distinct_keys_are_independent():
    """Locking one key does not lock another."""
    limiter, _ = BuildLimiter()
    lockedKey = limiter.BuildKey(USER_ID, CLIENT_HOST)
    otherKey = limiter.BuildKey(OTHER_USER_ID, CLIENT_HOST)
    for _ in range(settings.LOGIN_MAX_ATTEMPTS):
        limiter.RegisterFailure(lockedKey)

    assert limiter.IsLocked(lockedKey) is True
    assert limiter.IsLocked(otherKey) is False


def test_failures_while_locked_do_not_extend_window():
    """Extra failures during a lockout do not push the expiry further out."""
    limiter, clock = BuildLimiter()
    key = limiter.BuildKey(USER_ID, CLIENT_HOST)
    for _ in range(settings.LOGIN_MAX_ATTEMPTS):
        limiter.RegisterFailure(key)

    # Halfway through the window, more failures arrive.
    clock.Advance(settings.LOGIN_LOCKOUT_SECONDS / 2)
    limiter.RegisterFailure(key)
    limiter.RegisterFailure(key)

    # The window still expires at the ORIGINAL lockout time, not extended.
    clock.Advance(settings.LOGIN_LOCKOUT_SECONDS / 2 + 1)
    assert limiter.IsLocked(key) is False


def test_unknown_host_sentinel_builds_valid_key():
    """The unknown-host sentinel composes a usable, independent key."""
    limiter, _ = BuildLimiter()

    sentinelKey = limiter.BuildKey(USER_ID, UNKNOWN_CLIENT_HOST)

    assert UNKNOWN_CLIENT_HOST in sentinelKey
    assert limiter.IsLocked(sentinelKey) is False
