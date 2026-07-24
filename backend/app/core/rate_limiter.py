# Login throttling for the CardDemo sign-on flow (QA findings M-01 + M09). There
# is no legacy COBOL source for this module: the mainframe COSGN00C applied NO
# attempt limit, so an attacker could try passwords without bound. AAP 0.1.1
# makes the credential-handling security uplift mandatory, so under the D1
# precedence rule an attempt throttle is added even though the legacy program had
# none.
"""In-process login rate limiter guarding the sign-on endpoint (M-01 / M09).

This module provides a small, thread-safe, in-memory throttle that the
authentication router consults around every ``POST /auth/login`` attempt.

Independent buckets (QA finding M09):
    An earlier design keyed the counter on the COMBINED ``(user id, client IP)``
    pair, which two attacks defeat: an attacker guessing one account from many
    rotating source hosts, or spraying many random user ids from one host, mints
    a BRAND-NEW combined key every attempt and therefore never trips the limit,
    while also growing the state dictionary without bound. This limiter instead
    maintains TWO INDEPENDENT bucket namespaces and trips when EITHER fills:

    * an ACCOUNT bucket keyed on the normalized user id -- caps the total
      consecutive failures against a single account regardless of source host
      (blocks host rotation), locking at ``settings.LOGIN_MAX_ATTEMPTS``; and
    * an IP bucket keyed on the client host -- caps the total consecutive
      failures from a single source across ALL accounts (blocks id spraying),
      locking at ``settings.LOGIN_MAX_ATTEMPTS * IP_ATTEMPT_MULTIPLIER``. The IP
      bucket is given headroom over the account bucket so a shared corporate NAT
      or CGNAT egress with several legitimate users is not locked out by normal
      mistyped passwords, while a genuine spray still trips it.

Bounded memory (QA finding M09):
    Every mutating call prunes idle records (not currently locked and untouched
    for longer than :data:`RECORD_IDLE_TTL_SECONDS`) and, if the map still
    exceeds :data:`MAX_TRACKED_KEYS`, evicts the least-recently-seen records
    until it is back within the cap. This makes the memory footprint strictly
    bounded regardless of how many distinct user ids or source hosts appear, so
    random-id / random-IP floods can no longer exhaust process memory.

Other design and scope notes:
    * Only genuine credential failures (the router registers a failure solely on
      ``AuthenticationError``) advance the counters. A blank-field submission
      (``DomainValidationError``) is a client-side edit, not a credential guess,
      and is intentionally NOT counted.
    * The failure response is a GENERIC HTTP 429 (see the router). It does not
      reveal whether the user id exists, preserving the anti-enumeration posture
      of the sign-on flow (QA Issue C8 / M-01).
    * State is process-local. Horizontal scaling across replicas would need a
      shared/distributed store (for example Redis); that is beyond the AAP
      dependency set (AAP 0.6 lists no cache/broker) and is therefore out of
      scope here. The in-process limiter still fully closes the rotation-bypass
      and unbounded-growth gaps the finding reports for a single instance, and
      the bucket abstraction below is storage-agnostic so a shared backend can be
      substituted later without touching the router.

The clock is injectable (:func:`DefaultClock` by default) so tests can freeze or
advance time deterministically to exercise lockout and expiry without sleeping.

Naming follows the Ochs resolution (technical specification 0.8.3): the class and
its methods are PascalCase, local variables camelCase, and module constants
ALL_UPPERCASE; the module filename stays snake_case.
"""

import threading
import time
from collections.abc import Callable
from dataclasses import dataclass

from app.core.config import settings

__all__ = [
    "LoginRateLimiter",
    "ThrottleIdentity",
    "DefaultClock",
    "loginRateLimiter",
    "UNKNOWN_CLIENT_HOST",
    "IP_ATTEMPT_MULTIPLIER",
    "MAX_TRACKED_KEYS",
    "RECORD_IDLE_TTL_SECONDS",
]

# Sentinel client host used when the ASGI server does not populate
# ``request.client`` (for example under httpx's ASGITransport in tests). Keying
# on a stable sentinel keeps throttling functional even without a real peer IP.
UNKNOWN_CLIENT_HOST = "unknown"

# Separator joining a bucket namespace and its value into a single dictionary
# key. A control character is used so it cannot collide with any character that
# may legitimately appear inside a user id or an IP-address literal.
_KEY_SEPARATOR = "\x1f"

# Bucket-namespace prefixes: one for the per-account counter, one for the
# per-source-host counter. Kept distinct so a user id can never collide with an
# IP literal in the shared record map.
_ACCOUNT_NAMESPACE = "acct"
_IP_NAMESPACE = "host"

# The IP bucket tolerates this many times the account threshold before locking,
# giving a shared NAT/CGNAT egress headroom for several users' honest mistakes
# while still stopping a single-host credential spray. A multiplier (rather than
# a separate absolute setting) keeps the IP cap proportional to the operator's
# chosen per-account threshold without adding another environment variable.
IP_ATTEMPT_MULTIPLIER = 5

# Hard upper bound on the number of tracked records (account + IP buckets
# combined). Once exceeded, the least-recently-seen records are evicted. Chosen
# generously so legitimate traffic is never evicted mid-window, while still
# capping worst-case memory at a few hundred kilobytes under a random-id flood.
MAX_TRACKED_KEYS = 10000

# A record that is not currently locked and has not been touched for longer than
# this many seconds is treated as stale and pruned. This bounds memory over time
# and effectively resets an abandoned partial-failure window. It is comfortably
# longer than a default lockout so an active lockout is never pruned early.
RECORD_IDLE_TTL_SECONDS = 3600


def DefaultClock() -> float:
    """Return a monotonically increasing time reference in seconds.

    ``time.monotonic`` is used rather than wall-clock time so lockout math is
    immune to system clock adjustments (NTP steps, DST). Tests substitute their
    own callable via :meth:`LoginRateLimiter.SetClock`.

    Returns:
        The current monotonic time, in fractional seconds.
    """
    return time.monotonic()


@dataclass(frozen=True)
class ThrottleIdentity:
    """The pair of independent bucket keys for one sign-on attempt (M09).

    Returned by :meth:`LoginRateLimiter.BuildKey` and passed opaquely by the
    auth router to :meth:`~LoginRateLimiter.IsLocked`,
    :meth:`~LoginRateLimiter.RegisterFailure` and
    :meth:`~LoginRateLimiter.RegisterSuccess`. It carries the two namespaced
    record keys -- one for the account bucket, one for the IP bucket -- so the
    router never has to know how buckets are composed.

    Attributes:
        accountKey: The record key for the per-account bucket (namespaced by
            :data:`_ACCOUNT_NAMESPACE`).
        ipKey: The record key for the per-source-host bucket (namespaced by
            :data:`_IP_NAMESPACE`).
    """

    accountKey: str
    ipKey: str


@dataclass
class _AttemptRecord:
    """Per-key throttle state: failure count, lockout expiry and last-seen time.

    Attributes:
        failureCount: Number of consecutive authentication failures observed for
            the key since the last success or window reset.
        lockedUntil: Monotonic time at which an active lockout expires; ``0.0``
            when the key is not currently locked.
        lastSeen: Monotonic time the record was last touched, used by the
            idle-TTL prune and the least-recently-seen size-cap eviction (M09).
    """

    failureCount: int = 0
    lockedUntil: float = 0.0
    lastSeen: float = 0.0


class LoginRateLimiter:
    """Thread-safe, in-memory account+IP failure throttle for sign-on (M-01/M09).

    A single process-wide instance (:data:`loginRateLimiter`) is shared by the
    auth router. All mutating operations hold an internal lock so concurrent
    requests cannot corrupt the counters. The limiter is deliberately storage-
    agnostic and holds no configuration of its own: the account threshold and
    lockout duration are read live from :data:`app.core.config.settings` on each
    call (the IP threshold is that account threshold times
    :data:`IP_ATTEMPT_MULTIPLIER`), so a test that overrides those settings takes
    effect immediately.
    """

    def __init__(self, clock: Callable[[], float] = DefaultClock) -> None:
        """Construct an empty limiter with the given time source.

        Args:
            clock: A zero-argument callable returning the current time in
                seconds. Defaults to :func:`DefaultClock` (monotonic time).
        """
        self._lock = threading.Lock()
        self._records: dict[str, _AttemptRecord] = {}
        self._clock = clock

    def BuildKey(self, userId: str, clientHost: str) -> ThrottleIdentity:
        """Compose the independent account and IP bucket keys for an attempt.

        The user id is normalized to the uppercase form the sign-on flow uses as
        the USRSEC key (``COSGN00C`` FUNCTION UPPER-CASE), so ``admin001`` and
        ``ADMIN001`` share one account counter and case cannot be used to sidestep
        the limit. The password is never part of any key.

        Args:
            userId: The submitted user id (any case; may be surrounded by
                whitespace).
            clientHost: The client IP/host, or :data:`UNKNOWN_CLIENT_HOST` when
                the peer address is unavailable.

        Returns:
            The :class:`ThrottleIdentity` holding the account and IP record keys.
        """
        normalizedUserId = userId.strip().upper()
        accountKey = f"{_ACCOUNT_NAMESPACE}{_KEY_SEPARATOR}{normalizedUserId}"
        ipKey = f"{_IP_NAMESPACE}{_KEY_SEPARATOR}{clientHost}"
        return ThrottleIdentity(accountKey=accountKey, ipKey=ipKey)

    def _ThresholdFor(self, key: str) -> int:
        """Return the failure threshold that locks the given namespaced key.

        The account bucket locks at ``settings.LOGIN_MAX_ATTEMPTS``; the IP
        bucket locks at that value times :data:`IP_ATTEMPT_MULTIPLIER` so a
        shared source host is given proportional headroom (M09).

        Args:
            key: A namespaced record key from :meth:`BuildKey`.

        Returns:
            The consecutive-failure count at which the key locks.
        """
        if key.startswith(f"{_IP_NAMESPACE}{_KEY_SEPARATOR}"):
            return settings.LOGIN_MAX_ATTEMPTS * IP_ATTEMPT_MULTIPLIER
        return settings.LOGIN_MAX_ATTEMPTS

    def _IsKeyLocked(self, key: str) -> bool:
        """Report whether a single namespaced key is currently locked.

        Must be called while holding ``self._lock``. When a previously set
        lockout has elapsed the record is cleared here (a fresh window), so the
        key then reports unlocked; a live key has its ``lastSeen`` refreshed so
        the idle prune does not drop an account/host under active attack.

        Args:
            key: A namespaced record key from :meth:`BuildKey`.

        Returns:
            ``True`` when attempts for this key are currently blocked.
        """
        record = self._records.get(key)
        if record is None:
            return False
        if record.lockedUntil == 0.0:
            return False
        if self._clock() < record.lockedUntil:
            record.lastSeen = self._clock()
            return True
        # Lockout has expired: clear the record so the next attempt starts a
        # fresh window rather than immediately re-locking.
        del self._records[key]
        return False

    def IsLocked(self, identity: ThrottleIdentity) -> bool:
        """Report whether the account OR the source host is currently locked.

        The attempt is blocked when EITHER independent bucket is locked (M09),
        so neither host rotation (account bucket) nor id spraying (IP bucket) can
        slip past.

        Args:
            identity: The :class:`ThrottleIdentity` from :meth:`BuildKey`.

        Returns:
            ``True`` when further attempts are currently blocked, else ``False``.
        """
        with self._lock:
            return self._IsKeyLocked(identity.accountKey) or self._IsKeyLocked(identity.ipKey)

    def _RegisterKeyFailure(self, key: str, now: float) -> None:
        """Advance one namespaced key's failure count, locking it at its threshold.

        Must be called while holding ``self._lock``. Calls made while the key is
        already locked are ignored (the lockout window is not extended).

        Args:
            key: A namespaced record key from :meth:`BuildKey`.
            now: The current time, read once by the caller for consistency.
        """
        record = self._records.get(key)
        if record is None:
            record = _AttemptRecord()
            self._records[key] = record
        record.lastSeen = now
        if record.lockedUntil != 0.0 and now < record.lockedUntil:
            return
        record.failureCount = record.failureCount + 1
        if record.failureCount >= self._ThresholdFor(key):
            record.lockedUntil = now + settings.LOGIN_LOCKOUT_SECONDS

    def RegisterFailure(self, identity: ThrottleIdentity) -> None:
        """Record one authentication failure against BOTH independent buckets.

        Advances the account counter and the source-host counter (M09); each
        locks independently at its own threshold. Bounded-memory maintenance runs
        last -- after the two records are advanced -- so the postcondition
        ``len(records) <= MAX_TRACKED_KEYS`` holds after every failure and the
        record map can never grow without limit. The just-touched records carry
        the newest ``lastSeen`` and so are never the ones evicted.

        Args:
            identity: The :class:`ThrottleIdentity` from :meth:`BuildKey`.
        """
        with self._lock:
            now = self._clock()
            self._RegisterKeyFailure(identity.accountKey, now)
            self._RegisterKeyFailure(identity.ipKey, now)
            self._EnforceBounds(now)

    def RegisterSuccess(self, identity: ThrottleIdentity) -> None:
        """Clear both buckets for the identity after a successful sign-on.

        A verified sign-on proves the account holder and that this source host
        produced legitimate traffic, so both the account and IP counters for the
        attempt are cleared (preserving the "a success resets the window" UX).

        Args:
            identity: The :class:`ThrottleIdentity` from :meth:`BuildKey`.
        """
        with self._lock:
            self._records.pop(identity.accountKey, None)
            self._records.pop(identity.ipKey, None)

    def _EnforceBounds(self, now: float) -> None:
        """Prune idle records and cap the map size (bounded memory, M09).

        Must be called while holding ``self._lock``. First drops every record
        that is not currently locked and has been idle longer than
        :data:`RECORD_IDLE_TTL_SECONDS`; then, if the map still exceeds
        :data:`MAX_TRACKED_KEYS`, evicts the least-recently-seen records until it
        is back within the cap. Locked records are preferentially retained (they
        are only evicted if the map is over the cap even after every unlocked
        record is a candidate), so an active lockout is essentially never lost.

        Args:
            now: The current time, read once by the caller.
        """
        staleKeys = [
            key
            for key, record in self._records.items()
            if not (record.lockedUntil != 0.0 and now < record.lockedUntil)
            and (now - record.lastSeen) > RECORD_IDLE_TTL_SECONDS
        ]
        for key in staleKeys:
            del self._records[key]

        overflow = len(self._records) - MAX_TRACKED_KEYS
        if overflow <= 0:
            return
        # Evict the least-recently-seen records, preferring unlocked ones so a
        # live lockout survives an eviction sweep whenever possible.
        evictionOrder = sorted(
            self._records.items(),
            key=lambda item: (
                item[1].lockedUntil != 0.0 and now < item[1].lockedUntil,
                item[1].lastSeen,
            ),
        )
        for key, _record in evictionOrder[:overflow]:
            del self._records[key]

    def Reset(self) -> None:
        """Discard all throttle state (test-support / administrative reset).

        Used by the autouse test fixture to guarantee each test starts from a
        clean limiter, so counters never leak across tests.
        """
        with self._lock:
            self._records.clear()

    def SetClock(self, clock: Callable[[], float]) -> None:
        """Replace the time source (test support).

        Args:
            clock: A zero-argument callable returning the current time in
                seconds. Pass :func:`DefaultClock` to restore the default.
        """
        with self._lock:
            self._clock = clock


# Process-wide singleton shared by the authentication router. It is created once
# at import; tests reset it between cases via the autouse fixture in conftest.
loginRateLimiter = LoginRateLimiter()
