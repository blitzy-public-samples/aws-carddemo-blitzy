/**
 * :module: ``frontend/src/hooks/useSelfRevocationExit.test.tsx``
 * :purpose: Prove that a maintenance action which revokes the operator's OWN session
 *   reports that consequence itself, instead of leaving the operator to discover it
 *   when their next action is refused. Covers the three outcomes the hook must
 *   distinguish: somebody else's record (no probe, no exit), one's own record with the
 *   session still honoured (no exit), and one's own record with the session revoked
 *   (exit to the sign-on screen carrying the confirmation plus the consequence).
 * :note: ``../api`` and ``./useSession`` are mocked with ``jest.unstable_mockModule``
 *   (the native-ESM form the sibling suites use), so no axios instance and no network
 *   is touched; the hook is imported dynamically after the mocks are registered.
 * :note: ``react-router`` is NOT mocked. The harness mounts a real ``MemoryRouter``
 *   carrying a ``/signon`` probe route, so the assertion is that the operator actually
 *   reaches the sign-on screen with the message on it, rather than that a stub was
 *   called with particular arguments.
 * :output: Jest test suite; no exports.
 */

import { act, render, screen } from '@testing-library/react';
// Jest's native-ESM runtime does not inject ``jest`` as a global.
import { jest } from '@jest/globals';
import type { ReactElement } from 'react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';

/** Line-23 confirmation ``COUSR03C`` publishes for a completed delete. */
const CONFIRMATION = 'User ADMIN001 has been deleted ...';

/** Route the operator is returned to once their own session stops authorizing. */
const SIGNON_ROUTE = '/signon';

const mockGetSessionIdentity = jest.fn<() => Promise<unknown>>();
const mockRevalidate = jest.fn<() => Promise<boolean>>();
let mockUser: string | null = 'ADMIN001';

jest.unstable_mockModule('../api', () => ({
  getSessionIdentity: mockGetSessionIdentity,
}));

jest.unstable_mockModule('./useSession', () => ({
  useSession: () => ({ user: mockUser, revalidate: mockRevalidate }),
}));

const { useSelfRevocationExit, SESSION_REVOKED_SENTENCE } = await import(
  './useSelfRevocationExit'
);

type ExitCallback = (affectedUserId: string, confirmation: string) => Promise<boolean>;

/** Publishes the hook's callback to the test through the ``onReady`` sink. */
function Harness(props: { onReady: (run: ExitCallback) => void }): ReactElement {
  const run = useSelfRevocationExit();
  props.onReady(run);
  return <div data-testid="harness" />;
}

/** Stands in for the sign-on screen: renders whatever line-23 text reached it. */
function SignonProbe(): ReactElement {
  const location = useLocation();
  const state = location.state as { screenMessage?: string } | null;
  return <p data-testid="signon-message">{state?.screenMessage ?? ''}</p>;
}

/**
 * :purpose: Mount the harness and hand back the hook's callback.
 * :returns: the callback the hook published on its latest render.
 */
function mountHook(): ExitCallback {
  let captured: ExitCallback | null = null;
  render(
    <MemoryRouter initialEntries={['/users/delete']}>
      <Routes>
        <Route
          path="/users/delete"
          element={
            <Harness
              onReady={(run) => {
                captured = run;
              }}
            />
          }
        />
        <Route path={SIGNON_ROUTE} element={<SignonProbe />} />
      </Routes>
    </MemoryRouter>,
  );
  const resolved = captured as ExitCallback | null;
  if (resolved === null) {
    throw new Error('the hook callback was not captured');
  }
  return resolved;
}

describe('useSelfRevocationExit', () => {
  beforeEach(() => {
    mockGetSessionIdentity.mockReset();
    mockRevalidate.mockReset();
    mockRevalidate.mockResolvedValue(false);
    mockUser = 'ADMIN001';
  });

  it("neither probes nor exits when the maintained record is somebody else's", async () => {
    const run = mountHook();
    let exited = true;
    await act(async () => {
      exited = await run('USER0002', CONFIRMATION);
    });
    expect(exited).toBe(false);
    expect(mockGetSessionIdentity).not.toHaveBeenCalled();
    expect(screen.getByTestId('harness')).toBeInTheDocument();
    expect(screen.queryByTestId('signon-message')).toBeNull();
  });

  it('stays on the screen when the change to its own record left the session honoured', async () => {
    mockGetSessionIdentity.mockResolvedValue({ userId: 'ADMIN001', userType: 'A' });
    const run = mountHook();
    let exited = true;
    await act(async () => {
      // The comparison is case-insensitive, as the server's own key handling is.
      exited = await run('admin001', CONFIRMATION);
    });
    expect(exited).toBe(false);
    expect(mockGetSessionIdentity).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId('harness')).toBeInTheDocument();
    expect(screen.queryByTestId('signon-message')).toBeNull();
    expect(mockRevalidate).not.toHaveBeenCalled();
  });

  it('carries the confirmation and the consequence to sign-on when the session was revoked', async () => {
    mockGetSessionIdentity.mockRejectedValue(new Error('401'));
    const run = mountHook();
    let exited = false;
    await act(async () => {
      exited = await run('ADMIN001', CONFIRMATION);
    });
    expect(exited).toBe(true);
    // The operator is on the sign-on screen, and line 23 names both the completed
    // maintenance (verbatim legacy literal) and the consequence for their session.
    expect(screen.getByTestId('signon-message')).toHaveTextContent(
      `${CONFIRMATION} ${SESSION_REVOKED_SENTENCE}`,
    );
    expect(screen.queryByTestId('harness')).toBeNull();
    // The stale local identity is dropped only after the public screen is reached, so
    // no guard can replace the message with its own generic wording.
    expect(mockRevalidate).toHaveBeenCalledTimes(1);
  });
});
