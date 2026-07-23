# Login throttling for the CardDemo sign-on flow (QA finding M-01). There is no
# legacy COBOL source for this module: the mainframe COSGN00C applied NO attempt
# limit, so an attacker could try passwords without bound. AAP 0.1.1 makes the
# credential-handling security uplift mandatory, so under the D1 precedence rule
# an attempt throttle is added even though the legacy program had none.
"""In-process login rate limiter guarding the sign-on endpoint (M-01).

This module provides a small, thread-safe, in-memory throttle that the
authentication router consults around every ``POST /auth/login`` attempt. It
counts CONSECUTIVE authentication failures for each ``(user id, client IP)``
pair and, once ``settings.LOGIN_MAX_ATTEMPTS`` is reached, locks further
attempts for ``settings.LOGIN_LOCKOUT_SECONDS`` before the window resets. A
successful sign-on clears the pair's counter immediately.

Design and scope:
    * Keyed on BOTH the normalized user id and the client IP so that neither a
      single account nor a single source host can be attacked without tripping
      the limit, while a legitimate user is not locked out by an unrelated
      attacker elsewhere targeting a different account from a different host.
    * Only genuine credential failures (the router registers a failure solely on
      ``AuthenticationError``) advance the counter. A blank-field submission
      (``DomainValidationError``) is a client-side edit, not a credential guess,
      and is intentionally NOT counted.
    * The failure response is a GENERIC HTTP 429 (see the router). It does not
      reveal whether the user id exists, preserving the anti-enumeration posture
      of the sign-on flow (QA Issue C8 / M-01).
    * State is process-local and intentionally simple (a dict under a lock).
      Horizontal scaling would need a shared store (for example Redis); that is
      out of scope here, and the in-process limiter still fully closes the
      "no throttle at all" gap the finding reports for a single instance.

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
    "DefaultClock",
    "loginRateLimiter",
    "UNKNOWN_CLIENT_HOST",
]

# Sentinel client host used when the ASGI server does not populate
# ``request.client`` (for example under httpx's ASGITransport in tests). Keying
# on a stable sentinel keeps throttling functional even without a real peer IP.
UNKNOWN_CLIENT_HOST = "unknown"

# Separator joining the user id and client host into a single dictionary key.
# A control character is used so it cannot collide with any character that may
# legitimately appear inside a user id or an IP-address literal.
_KEY_SEPARATOR = "\x1f"


def DefaultClock() -> float:
    """Return a monotonically increasing time reference in seconds.

    ``time.monotonic`` is used rather than wall-clock time so lockout math is
    immune to system clock adjustments (NTP steps, DST). Tests substitute their
    own callable via :meth:`LoginRateLimiter.SetClock`.

    Returns:
        The current monotonic time, in fractional seconds.
    """
    return time.monotonic()


@dataclass
class _AttemptRecord:
    """Per-key throttle state: the consecutive-failure count and lockout expiry.

    Attributes:
        failureCount: Number of consecutive authentication failures observed for
            the key since the last success or window reset.
        lockedUntil: Monotonic time at which an active lockout expires; ``0.0``
            when the key is not currently locked.
    """

    failureCount: int = 0
    lockedUntil: float = 0.0


class LoginRateLimiter:
    """Thread-safe, in-memory consecutive-failure throttle for sign-on (M-01).

    A single process-wide instance (:data:`loginRateLimiter`) is shared by the
    auth router. All mutating operations hold an internal lock so concurrent
    requests cannot corrupt the counters. The limiter is deliberately storage-
    agnostic and holds no configuration of its own: the threshold and lockout
    duration are read live from :data:`app.core.config.settings` on each call, so
    a test that overrides those settings takes effect immediately.
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

    def BuildKey(self, userId: str, clientHost: str) -> str:
        """Compose the throttle key from a user id and client host.

        The user id is normalized to the uppercase form the sign-on flow uses as
        the USRSEC key (``COSGN00C`` FUNCTION UPPER-CASE), so ``admin001`` and
        ``ADMIN001`` share one counter and case cannot be used to sidestep the
        limit. The password is never part of the key.

        Args:
            userId: The submitted user id (any case; may be surrounded by
                whitespace).
            clientHost: The client IP/host, or :data:`UNKNOWN_CLIENT_HOST` when
                the peer address is unavailable.

        Returns:
            The composed dictionary key string.
        """
        normalizedUserId = userId.strip().upper()
        return f"{normalizedUserId}{_KEY_SEPARATOR}{clientHost}"

    def IsLocked(self, key: str) -> bool:
        """Report whether the key is currently locked out.

        A key is locked when it has an unexpired lockout. When a previously set
        lockout has elapsed, the record is reset here (a fresh attempt window)
        and the key reports unlocked, so the caller may proceed.

        Args:
            key: The throttle key from :meth:`BuildKey`.

        Returns:
            ``True`` when further attempts are currently blocked, else ``False``.
        """
        with self._lock:
            record = self._records.get(key)
            if record is None:
                return False
            if record.lockedUntil == 0.0:
                return False
            if self._clock() < record.lockedUntil:
                return True
            # Lockout has expired: clear the record so the next attempt starts a
            # fresh window rather than immediately re-locking.
            del self._records[key]
            return False

    def RegisterFailure(self, key: str) -> None:
        """Record one authentication failure, locking the key at the threshold.

        Increments the consecutive-failure counter for the key. When the count
        reaches ``settings.LOGIN_MAX_ATTEMPTS`` the key is locked for
        ``settings.LOGIN_LOCKOUT_SECONDS`` from now. Calls made while the key is
        already locked are ignored (the lockout window is not extended).

        Args:
            key: The throttle key from :meth:`BuildKey`.
        """
        with self._lock:
            now = self._clock()
            record = self._records.get(key)
            if record is None:
                record = _AttemptRecord()
                self._records[key] = record
            if record.lockedUntil != 0.0 and now < record.lockedUntil:
                return
            record.failureCount = record.failureCount + 1
            if record.failureCount >= settings.LOGIN_MAX_ATTEMPTS:
                record.lockedUntil = now + settings.LOGIN_LOCKOUT_SECONDS

    def RegisterSuccess(self, key: str) -> None:
        """Clear all throttle state for the key after a successful sign-on.

        Args:
            key: The throttle key from :meth:`BuildKey`.
        """
        with self._lock:
            self._records.pop(key, None)

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
