/**
 * :module: ``frontend/src/testing/sessionHarness.ts``
 * :purpose: Place the SPA session store in a signed-on or a server-confirmed
 *     signed-out state for a test, by driving the SAME path the application drives
 *     — the ``GET /session`` identity probe the session store exposes as
 *     ``useSession().refresh`` — so a suite never forges an identity and the
 *     shipped session module carries no seam that could.
 * :output: The named ``resolveSessionFromServer``, ``seedSignedOnSession`` and
 *     ``seedSignedOutSession`` helpers.
 * :note: Test-only. Nothing in ``frontend/src`` outside a ``*.test.ts(x)`` file
 *     imports this module, so it reaches no production bundle. Because it binds to
 *     the ``../api`` barrel the calling suite has registered, the identity it
 *     publishes is whatever that suite's ``getSessionIdentity`` double answers
 *     with — the store is still only ever written by the production probe.
 * :note: Both helpers leave the probe answering "no session". The identity is
 *     published through the production call and then the server goes back to
 *     reporting no session, so a LATER probe — after a sign-out, or after a
 *     revoked session drops the local authority — gets the server's real answer
 *     instead of a standing identity that would silently sign the caller back in.
 */
import { act, renderHook } from '@testing-library/react';
import { getSessionIdentity } from '../api';
import { useSession } from '../hooks';
import type { Role, SessionIdentityDto } from '../types';

/**
 * :purpose: Message the probe rejects with to report that the server holds no
 *     session, matching how ``getSessionIdentity`` surfaces a ``401``.
 */
const NO_SESSION_MESSAGE = 'No session';

/**
 * :purpose: The two mock controls this harness needs from the suite's
 *     ``getSessionIdentity`` double, declared structurally so the harness couples
 *     to no jest typing.
 */
interface IdentityProbeStub {
  mockResolvedValue: (identity: SessionIdentityDto) => unknown;
  mockRejectedValue: (reason: unknown) => unknown;
}

/**
 * :purpose: Resolve the ``GET /session`` double the calling suite registered.
 * :returns: the double, ready to be given a resolution or a rejection.
 */
function identityProbeStub(): IdentityProbeStub {
  return getSessionIdentity as unknown as IdentityProbeStub;
}

/**
 * :purpose: Issue the production identity probe once and settle every state update
 *     it causes, so the store publishes exactly what ``GET /session`` answered.
 *     The probe is reached through the hook the application uses, mounted only for
 *     the duration of the call, and this module never writes the store itself.
 *     A suite that stubs the transport rather than the ``../api`` barrel calls this
 *     directly, once its stub is serving the identity it wants published.
 * :returns: a promise that resolves once the store reflects the server's answer.
 */
export async function resolveSessionFromServer(): Promise<void> {
  const probe = renderHook(() => useSession());
  await act(async () => {
    await probe.result.current.refresh();
  });
  probe.unmount();
}

/**
 * :purpose: Sign a user on for the duration of a test by having the server report
 *     that identity to the production ``GET /session`` probe.
 * :param userId: the user id the server reports (``CDEMO-USER-ID``).
 * :param role: the role the server reports (``CDEMO-USER-TYPE`` — ``'A'`` / ``'U'``).
 * :returns: a promise that resolves once the store has published the identity.
 */
export async function seedSignedOnSession(
  userId: string,
  role: Role,
): Promise<void> {
  const stub = identityProbeStub();
  stub.mockResolvedValue({ userId, userType: role });
  await resolveSessionFromServer();
  stub.mockRejectedValue(new Error(NO_SESSION_MESSAGE));
}

/**
 * :purpose: Put the store in the server-confirmed signed-out state — the probe has
 *     answered, and there is no session — so a route guard decides instead of
 *     waiting.
 * :returns: a promise that resolves once the store has published the signed-out
 *     state.
 */
export async function seedSignedOutSession(): Promise<void> {
  identityProbeStub().mockRejectedValue(new Error(NO_SESSION_MESSAGE));
  await resolveSessionFromServer();
}
