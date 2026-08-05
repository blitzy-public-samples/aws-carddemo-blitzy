/**
 * :module: Layout.test
 * :purpose: Verify the shared 24x80 screen shell: the fixed BMS frame regions, the
 *     unspaced caption spelling the sixteen non-sign-on mapsets use, a page publishing
 *     its chrome through ``useScreenChrome``, that navigating discards the previous
 *     screen's chrome so two screens can never overlap, that a page can clear the
 *     frame from its own cleanup, that the busy state marks the body region and is
 *     announced, and that the frame reports the authentication state the SERVER
 *     published rather than a locally seeded one.
 * :note: ``../api`` is mocked via ``jest.unstable_mockModule`` so authentication is
 *     established the way production establishes it — through the server identity probe
 *     — and no real network, axios, or Vite ``import.meta`` is touched.
 */

import { jest } from '@jest/globals';
import { act, render, screen, waitFor } from '@testing-library/react';
import { useEffect } from 'react';
import type { ReactElement } from 'react';
import { Link, MemoryRouter, Route, Routes } from 'react-router';
import type {
  SessionIdentityDto,
  SignonRequestDto,
  SignonResponseDto,
} from '../types';
import { PfKeyAction } from '../types';

/** Server identity probe the session store issues once per page load. */
const getSessionIdentityMock = jest.fn<() => Promise<SessionIdentityDto>>();

/**
 * Captures the centralized session-expiry callback the session store registers, so each
 * test can return the shared store to signed-out through the same path production uses
 * when the server reports the session is gone.
 */
let expireSession: (() => void) | undefined;

jest.unstable_mockModule('../api', () => ({
  __esModule: true,
  signon: jest.fn<(request: SignonRequestDto) => Promise<SignonResponseDto>>(),
  getSessionIdentity: getSessionIdentityMock,
  logout: jest.fn<() => Promise<void>>(),
  registerSessionExpiryHandler: (handler: () => void) => {
    expireSession = handler;
    return () => {
      expireSession = undefined;
    };
  },
}));

type LayoutModule = typeof import('./Layout');

let Layout: LayoutModule['default'];
let useScreenChrome: LayoutModule['useScreenChrome'];

beforeAll(async () => {
  ({ default: Layout, useScreenChrome } = await import('./Layout'));
});

beforeEach(() => {
  getSessionIdentityMock.mockReset();
  getSessionIdentityMock.mockRejectedValue(new Error('no session'));
  // The session store is module-level and shared by every test in this file; expiring it
  // returns it to signed-out and re-arms the one-per-session server probe.
  act(() => {
    expireSession?.();
  });
});

/**
 * :purpose: A minimal page that publishes screen chrome through the Layout context,
 *     exercising the same flow real page components use.
 * :returns: the rendered page body.
 */
function ChromePublisher(): ReactElement {
  const { setChrome } = useScreenChrome();
  useEffect(() => {
    setChrome({
      transactionId: 'CAUP',
      programName: 'COACTUPC',
      title01: 'Account Update',
      errorMessage: 'Record changed by some one else. Please review',
      pfKeys: [{ action: PfKeyAction.PF3, label: 'F3=Exit', onActivate: jest.fn() }],
    });
  }, [setChrome]);
  return <div data-testid="page-body">page content</div>;
}

/**
 * :purpose: A page that publishes chrome and clears it again in its own cleanup, the
 *     way a screen returns the frame to empty when it is left.
 * :returns: the rendered page body.
 */
function SelfClearingPublisher(): ReactElement {
  const { setChrome, resetChrome } = useScreenChrome();
  useEffect(() => {
    setChrome({ transactionId: 'CT00', title01: 'List Transactions' });
    return () => {
      resetChrome();
    };
  }, [setChrome, resetChrome]);
  return <div data-testid="page-body">first screen</div>;
}

/**
 * :purpose: A page that publishes only the busy state.
 * :returns: the rendered page body.
 */
function BusyPublisher(): ReactElement {
  const { setChrome } = useScreenChrome();
  useEffect(() => {
    setChrome({ transactionId: 'CAVW', busy: true });
  }, [setChrome]);
  return <div data-testid="page-body">loading screen</div>;
}

/**
 * :purpose: A second screen that publishes nothing at all, so whatever the frame shows
 *     after navigating to it is what survived the transfer.
 * :returns: the rendered page body.
 */
function SilentScreen(): ReactElement {
  return <div data-testid="page-body">second screen</div>;
}

/**
 * :purpose: A screen that has ended its transaction with ``SEND TEXT ... ERASE``,
 *     publishing one line of plain text alongside chrome the erased frame must drop.
 * :returns: the rendered page body, which the erased frame does not show.
 */
function PlainTextPublisher(): ReactElement {
  const { setChrome } = useScreenChrome();
  useEffect(() => {
    setChrome({
      transactionId: 'CC00',
      programName: 'COSGN00C',
      errorMessage: 'should not be rendered',
      pfKeys: [
        { action: PfKeyAction.PF3, label: 'F3=Exit', onActivate: () => undefined },
      ],
      plainText: 'Thank you for using CardDemo application...',
    });
  }, [setChrome]);
  return <div data-testid="page-body">screen body</div>;
}

describe('Layout', () => {
  it('renders the header, routed content, error region, and key bar', () => {
    render(
      <MemoryRouter>
        <Layout>
          <div data-testid="page-body">hello</div>
        </Layout>
      </MemoryRouter>,
    );
    expect(screen.getByTestId('tran-id')).toBeInTheDocument();
    expect(screen.getByTestId('page-body')).toHaveTextContent('hello');
    expect(screen.getByRole('toolbar', { name: 'Function keys' })).toBeInTheDocument();
  });

  it('lets a page publish chrome (title, tran/prog, message, keys) via useScreenChrome', () => {
    render(
      <MemoryRouter>
        <Layout>
          <ChromePublisher />
        </Layout>
      </MemoryRouter>,
    );
    expect(screen.getByTestId('tran-id')).toHaveTextContent('CAUP');
    expect(screen.getByTestId('pgm-name')).toHaveTextContent('COACTUPC');
    expect(screen.getByTestId('title01')).toHaveTextContent('Account Update');
    expect(screen.getByRole('alert')).toHaveTextContent(
      'Record changed by some one else. Please review',
    );
    expect(screen.getByRole('button', { name: 'F3=Exit' })).toBeInTheDocument();
  });

  it('reports the authentication state the server published', async () => {
    getSessionIdentityMock.mockResolvedValue({ userId: 'USER0001', userType: 'U' });

    const { container } = render(
      <MemoryRouter>
        <Layout>
          <div>content</div>
        </Layout>
      </MemoryRouter>,
    );

    await waitFor(() => {
      expect(container.querySelector('.screen')).toHaveAttribute(
        'data-authenticated',
        'true',
      );
    });
  });

  it('renders the unspaced caption spelling the sixteen sibling mapsets use', () => {
    render(
      <MemoryRouter>
        <Layout>
          <div data-testid="page-body">hello</div>
        </Layout>
      </MemoryRouter>,
    );
    // Only COSGN00.bms spells them 'Tran :'; the frame's default is the other sixteen.
    expect(screen.getByText('Tran:')).toBeInTheDocument();
    expect(screen.getByText('Prog:')).toBeInTheDocument();
    expect(screen.queryByText('Tran :')).not.toBeInTheDocument();
    expect(screen.queryByTestId('app-sys-row')).not.toBeInTheDocument();
  });

  it('discards the previous screen\'s chrome when the route changes', () => {
    render(
      <MemoryRouter initialEntries={['/first']}>
        <Layout>
          <Routes>
            <Route
              path="/first"
              element={
                <>
                  <ChromePublisher />
                  <Link to="/second">go</Link>
                </>
              }
            />
            <Route path="/second" element={<SilentScreen />} />
          </Routes>
        </Layout>
      </MemoryRouter>,
    );

    expect(screen.getByTestId('tran-id')).toHaveTextContent('CAUP');
    expect(screen.getByTestId('title01')).toHaveTextContent('Account Update');
    expect(screen.getByRole('alert')).toHaveTextContent(
      'Record changed by some one else. Please review',
    );
    expect(screen.getByRole('button', { name: 'F3=Exit' })).toBeInTheDocument();

    act(() => {
      screen.getByRole('link', { name: 'go' }).click();
    });

    // A CICS XCTL sent a fresh map: no title, legend, or message survives it.
    expect(screen.getByTestId('page-body')).toHaveTextContent('second screen');
    expect(screen.getByTestId('tran-id')).toHaveTextContent('');
    expect(screen.getByTestId('title01')).toHaveTextContent('');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'F3=Exit' })).not.toBeInTheDocument();
  });

  it('lets a page clear the frame from its own cleanup', () => {
    const { unmount } = render(
      <MemoryRouter>
        <Layout>
          <SelfClearingPublisher />
        </Layout>
      </MemoryRouter>,
    );
    expect(screen.getByTestId('tran-id')).toHaveTextContent('CT00');
    expect(screen.getByTestId('title01')).toHaveTextContent('List Transactions');

    // The cleanup runs on unmount; the frame must not keep the departed screen's
    // fields for whatever is drawn next.
    act(() => {
      unmount();
    });
    expect(screen.queryByTestId('tran-id')).not.toBeInTheDocument();
  });

  it('marks the body region busy and announces the wait', () => {
    const { container } = render(
      <MemoryRouter>
        <Layout>
          <BusyPublisher />
        </Layout>
      </MemoryRouter>,
    );
    expect(container.querySelector('main')).toHaveAttribute('aria-busy', 'true');
    expect(screen.getByTestId('screen-busy')).toHaveTextContent('Working');
  });

  it('erases the frame to one plain-text line when a transaction ends', () => {
    render(
      <MemoryRouter>
        <Layout>
          <PlainTextPublisher />
        </Layout>
      </MemoryRouter>,
    );

    // SEND TEXT ... ERASE clears the whole device: only the text remains.
    expect(screen.getByTestId('screen-plain-text')).toHaveTextContent(
      'Thank you for using CardDemo application...',
    );
    expect(screen.queryByTestId('tran-id')).not.toBeInTheDocument();
    expect(screen.queryByTestId('page-body')).not.toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(
      screen.queryByRole('toolbar', { name: 'Function keys' }),
    ).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'F3=Exit' })).not.toBeInTheDocument();
  });

  it('reports signed out when the server holds no session', async () => {
    const { container } = render(
      <MemoryRouter>
        <Layout>
          <div>content</div>
        </Layout>
      </MemoryRouter>,
    );

    await waitFor(() => {
      expect(getSessionIdentityMock).toHaveBeenCalled();
    });
    expect(container.querySelector('.screen')).toHaveAttribute(
      'data-authenticated',
      'false',
    );
  });

});
