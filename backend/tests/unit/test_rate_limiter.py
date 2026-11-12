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
from app.core.rate_limiter import (
    IP_ATTEMPT_MULTIPLIER,
    MAX_TRACKED_KEYS,
    RECORD_IDLE_TTL_SECONDS,
    UNKNOWN_CLIENT_HOST,
    LoginRateLimiter,
)

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
    assert CLIENT_HOST in keyUpper.ipKey
    assert "ADMIN001" in keyUpper.accountKey


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

    assert UNKNOWN_CLIENT_HOST in sentinelKey.ipKey
    assert limiter.IsLocked(sentinelKey) is False


# ---------------------------------------------------------------------------
# M09: independent account / IP buckets close the rotation bypass.
# ---------------------------------------------------------------------------
def test_account_bucket_locks_across_rotating_hosts():
    """QA finding M09: guessing ONE account from MANY hosts trips the account bucket.

    The old combined ``(user, host)`` key let an attacker rotate the source host
    to mint a fresh key every attempt and never lock. With an independent
    account bucket, LOGIN_MAX_ATTEMPTS failures against a single user id lock
    that account REGARDLESS of the (all-distinct) source hosts, and a further
    attempt on the same account from a brand-new host is still blocked.
    """
    limiter, _ = BuildLimiter()
    for attempt in range(settings.LOGIN_MAX_ATTEMPTS):
        rotatingHost = f"203.0.113.{attempt}"
        limiter.RegisterFailure(limiter.BuildKey(USER_ID, rotatingHost))

    # A fresh, never-seen host for the SAME account is still locked (account
    # bucket), proving host rotation cannot bypass the limit.
    assert limiter.IsLocked(limiter.BuildKey(USER_ID, "203.0.113.250")) is True


def test_ip_bucket_locks_on_id_spray_from_one_host():
    """QA finding M09: spraying MANY user ids from ONE host trips the IP bucket.

    Distinct user ids never fill any single account bucket, but they all share
    the source-host bucket, which locks at
    ``LOGIN_MAX_ATTEMPTS * IP_ATTEMPT_MULTIPLIER``. After that many single
    failures from one host, a further attempt from that host -- even for a
    brand-new user id -- is blocked.
    """
    limiter, _ = BuildLimiter()
    ipThreshold = settings.LOGIN_MAX_ATTEMPTS * IP_ATTEMPT_MULTIPLIER
    for attempt in range(ipThreshold):
        sprayedUser = f"USER{attempt:05d}"
        limiter.RegisterFailure(limiter.BuildKey(sprayedUser, CLIENT_HOST))

    # A brand-new user id from the same host is blocked by the IP bucket.
    assert limiter.IsLocked(limiter.BuildKey("BRANDNEW9", CLIENT_HOST)) is True


def test_ip_bucket_has_headroom_over_account_threshold():
    """QA finding M09: a shared host is not locked by a few distinct-account misses.

    ``LOGIN_MAX_ATTEMPTS`` failures from one host, each for a DIFFERENT account,
    leave every account bucket sub-threshold and the IP bucket well under its
    (multiplied) threshold, so a legitimate user behind the same NAT egress is
    not collaterally locked out.
    """
    limiter, _ = BuildLimiter()
    for attempt in range(settings.LOGIN_MAX_ATTEMPTS):
        distinctUser = f"NATUSER{attempt:03d}"
        limiter.RegisterFailure(limiter.BuildKey(distinctUser, CLIENT_HOST))

    # Neither an as-yet-unseen user nor the shared host is locked yet.
    assert limiter.IsLocked(limiter.BuildKey("NATFRESH1", CLIENT_HOST)) is False


# ---------------------------------------------------------------------------
# M09: bounded memory (idle TTL prune + hard size cap).
# ---------------------------------------------------------------------------
def test_idle_records_are_pruned_after_ttl():
    """QA finding M09: a non-locked record idle beyond the TTL is pruned.

    A sub-threshold failure leaves a live (unlocked) record. After the idle TTL
    elapses, the next mutating call runs the prune and drops it, bounding memory
    over time without affecting any currently-active lockout.
    """
    limiter, clock = BuildLimiter()
    staleIdentity = limiter.BuildKey("STALEUSER", "10.0.0.1")
    limiter.RegisterFailure(staleIdentity)
    assert staleIdentity.accountKey in limiter._records
    assert staleIdentity.ipKey in limiter._records

    clock.Advance(RECORD_IDLE_TTL_SECONDS + 1)
    freshIdentity = limiter.BuildKey("FRESHUSER", "10.0.0.2")
    limiter.RegisterFailure(freshIdentity)

    assert staleIdentity.accountKey not in limiter._records
    assert staleIdentity.ipKey not in limiter._records
    assert freshIdentity.accountKey in limiter._records


def test_tracked_keys_are_bounded_under_flood():
    """QA finding M09: a random-id/IP flood cannot grow the map without bound.

    Registering far more distinct identities than the cap keeps the record map
    at or below :data:`MAX_TRACKED_KEYS` via least-recently-seen eviction, so a
    spray of random user ids and source hosts can no longer exhaust memory.
    """
    limiter, _ = BuildLimiter()
    floodSize = MAX_TRACKED_KEYS + 200
    for attempt in range(floodSize):
        floodUser = f"F{attempt:07d}"
        floodHost = f"10.{(attempt // 65536) % 256}.{(attempt // 256) % 256}.{attempt % 256}"
        limiter.RegisterFailure(limiter.BuildKey(floodUser, floodHost))

    assert len(limiter._records) <= MAX_TRACKED_KEYS
