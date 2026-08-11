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
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { useEffect, useLayoutEffect, useState } from 'react';
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
  // The request-cancellation contract ``useApi`` binds to: the real scope hands the
  // caller's AbortSignal to axios, and the double simply invokes the call.
  runWithRequestSignal: (_signal: AbortSignal, call: () => unknown): unknown => call(),
  isCancelledRequest: (): boolean => false,
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
let usePublishedChrome: LayoutModule['usePublishedChrome'];

beforeAll(async () => {
  ({ default: Layout, useScreenChrome, usePublishedChrome } = await import('./Layout'));
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
      title02: 'Update Account',
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
 * :purpose: A page shaped exactly like the seventeen real screens: it holds the value
 *     of its one entry field in state, publishes an ENTER handler that reads that
 *     state, and publishes from a LAYOUT effect. It is the harness for the one property
 *     that matters most about the shell — that a key struck immediately behind a
 *     keystroke acts on the value the operator can see.
 * :returns: the rendered entry field.
 */
function EntryFieldPublisher({
  onSubmit,
}: {
  onSubmit: (submitted: string) => void;
}): ReactElement {
  const { setChrome } = useScreenChrome();
  const [value, setValue] = useState('');
  useLayoutEffect(() => {
    setChrome({
      transactionId: 'CT01',
      pfKeys: [
        {
          action: PfKeyAction.Enter,
          label: 'ENTER=Fetch',
          onActivate: () => {
            onSubmit(value);
          },
        },
      ],
    });
  }, [setChrome, onSubmit, value]);
  return (
    <input
      data-testid="entry"
      value={value}
      onChange={(event) => {
        setValue(event.target.value);
      }}
    />
  );
}

/** Every chrome value the frame has published, newest last. */
const publishedChrome: unknown[] = [];

/**
 * :purpose: Record the chrome the frame currently holds, so a test can tell a
 *     publication that replaced it from one the frame declined.
 * :returns: nothing rendered.
 */
function ChromeObserver(): ReactElement {
  publishedChrome.push(usePublishedChrome());
  return <span data-testid="chrome-observer" />;
}

/**
 * :purpose: A page that republishes on every render with a FRESH handler closure, the
 *     way a real screen does — its ``onActivate`` identity turns over whenever any of
 *     its own state changes, so the layout effect re-runs and publishes again even when
 *     nothing on the screen has changed.
 * :param generation: bumped by the test to force a republication.
 * :param label: the ENTER legend text, so a test can make one republication carry a
 *     genuine change.
 * :param onSubmit: notified with the generation that was current when ENTER was struck.
 * :returns: the rendered page body.
 */
function RepublishingPage({
  generation,
  label,
  onSubmit,
}: {
  generation: number;
  label: string;
  onSubmit?: (generation: number) => void;
}): ReactElement {
  const { setChrome } = useScreenChrome();
  useLayoutEffect(() => {
    setChrome({
      transactionId: 'CAUP',
      programName: 'COACTUPC',
      pfKeys: [
        {
          action: PfKeyAction.Enter,
          label,
          onActivate: () => {
            onSubmit?.(generation);
          },
        },
      ],
    });
  }, [setChrome, generation, label, onSubmit]);
  return <ChromeObserver />;
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
    expect(screen.getByRole('group', { name: 'Function keys' })).toBeInTheDocument();
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

  it('marks the body region busy and shows the input-inhibited indicator', () => {
    const { container } = render(
      <MemoryRouter>
        <Layout>
          <BusyPublisher />
        </Layout>
      </MemoryRouter>,
    );
    expect(container.querySelector('main')).toHaveAttribute('aria-busy', 'true');
    // The Operator Information Area is the terminal's own status line below the 24
    // application rows, and `X SYSTEM` is its input-inhibited indicator: the screen
    // shows it, and announces it politely, for exactly as long as the keyboard is
    // locked. It replaces no BMS field and consumes none of the 24 rows.
    const oia = screen.getByTestId('screen-busy');
    expect(oia).toHaveTextContent('X SYSTEM');
    expect(oia).toHaveAttribute('aria-live', 'polite');
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
      screen.queryByRole('group', { name: 'Function keys' }),
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

describe('Layout — a key acts on the state the operator can see', () => {
  it('submits the value the field holds when ENTER arrives in the same task as the keystroke', () => {
    const submitted: string[] = [];
    render(
      <MemoryRouter>
        <Layout>
          <EntryFieldPublisher
            onSubmit={(value) => {
              submitted.push(value);
            }}
          />
        </Layout>
      </MemoryRouter>,
    );

    const entry = screen.getByTestId('entry');
    // One task: the last keystroke and the ENTER that follows it, with nothing in
    // between. This is the window in which the shell used to submit the value the
    // field held BEFORE the keystroke.
    act(() => {
      fireEvent.change(entry, { target: { value: '000000100068358' } });
      fireEvent.change(entry, { target: { value: '0000001000683580' } });
      fireEvent.keyDown(document, { key: 'Enter' });
    });

    expect(submitted).toEqual(['0000001000683580']);
  });

  it('submits the value the field holds when ENTER is struck through the legend button', () => {
    const submitted: string[] = [];
    render(
      <MemoryRouter>
        <Layout>
          <EntryFieldPublisher
            onSubmit={(value) => {
              submitted.push(value);
            }}
          />
        </Layout>
      </MemoryRouter>,
    );

    act(() => {
      fireEvent.change(screen.getByTestId('entry'), { target: { value: 'USER0005' } });
      fireEvent.click(screen.getByRole('button', { name: 'ENTER=Fetch' }));
    });

    expect(submitted).toEqual(['USER0005']);
  });
});

describe('Layout — the keyboard is locked for the whole transaction', () => {
  it('accepts no attention identifier and offers no enabled key while the screen is busy', () => {
    const activate = jest.fn();
    /**
     * :purpose: A screen whose read is outstanding and which still declares a key.
     * :returns: the rendered page body.
     */
    function BusyWithKeys(): ReactElement {
      const { setChrome } = useScreenChrome();
      useLayoutEffect(() => {
        setChrome({
          transactionId: 'CT00',
          busy: true,
          pfKeys: [
            { action: PfKeyAction.PF8, label: 'F8=Forward', onActivate: activate },
          ],
        });
      }, [setChrome]);
      return <div data-testid="page-body">browsing</div>;
    }

    render(
      <MemoryRouter>
        <Layout>
          <BusyWithKeys />
        </Layout>
      </MemoryRouter>,
    );

    // Five presses of a paging key while the reply is outstanding: a 3270 discards
    // every one of them rather than queueing five requests that all carry the cursor
    // of the page still on screen.
    act(() => {
      for (let press = 0; press < 5; press += 1) {
        fireEvent.keyDown(document, { key: 'F8' });
      }
    });

    expect(activate).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: 'F8=Forward' })).toBeDisabled();
  });

  it('sends one attention identifier per send even when five keys arrive in one task', () => {
    const activate = jest.fn();
    /**
     * :purpose: An idle screen declaring a paging key, which becomes busy only once its
     *     handler has run — the shape every list screen has.
     * :returns: the rendered page body.
     */
    function IdleWithPagingKey(): ReactElement {
      const { setChrome } = useScreenChrome();
      const [busy, setBusy] = useState(false);
      useLayoutEffect(() => {
        setChrome({
          transactionId: 'CT00',
          busy,
          pfKeys: [
            {
              action: PfKeyAction.PF8,
              label: 'F8=Forward',
              onActivate: () => {
                activate();
                setBusy(true);
              },
            },
          ],
        });
      }, [setChrome, busy]);
      return <div data-testid="page-body">browsing</div>;
    }

    render(
      <MemoryRouter>
        <Layout>
          <IdleWithPagingKey />
        </Layout>
      </MemoryRouter>,
    );

    // One task, no macrotask boundary: the rendered lock cannot have been committed
    // between the presses, so only the synchronous latch can refuse them.
    act(() => {
      const bar = document.querySelector('.pfKeyBar');
      for (let press = 0; press < 5; press += 1) {
        bar?.ownerDocument.dispatchEvent(
          new KeyboardEvent('keydown', { key: 'F8', bubbles: true }),
        );
      }
    });

    expect(activate).toHaveBeenCalledTimes(1);
  });
});

describe('Layout — the frame is navigable and identifiable', () => {
  it('holds rows 23 and 24 in a contentinfo landmark a skip link reaches', () => {
    const { container } = render(
      <MemoryRouter>
        <Layout>
          <ChromePublisher />
        </Layout>
      </MemoryRouter>,
    );

    const status = screen.getByRole('contentinfo');
    expect(status).toContainElement(screen.getByRole('alert'));
    expect(status).toContainElement(screen.getByRole('group', { name: 'Function keys' }));
    const skip = container.querySelector('.screen__skipLink');
    expect(skip).toHaveAttribute('href', `#${status.id}`);
  });

  it('titles the document with the screen the frame is showing', async () => {
    render(
      <MemoryRouter>
        <Layout>
          <ChromePublisher />
        </Layout>
      </MemoryRouter>,
    );

    await waitFor(() => {
      expect(document.title).toBe('CAUP COACTUPC - CardDemo');
    });
  });
});

/* ------------------------------------------------------------------ */
/* A publication that would paint the same screen is declined         */
/* ------------------------------------------------------------------ */

describe('Layout — a publication that paints nothing new is declined', () => {
  beforeEach(() => {
    publishedChrome.length = 0;
  });

  it('keeps the chrome it holds when a republication carries the same values', () => {
    const { rerender } = render(
      <MemoryRouter>
        <Layout>
          <RepublishingPage generation={1} label="ENTER=Process" />
        </Layout>
      </MemoryRouter>,
    );
    const first = publishedChrome[publishedChrome.length - 1];

    // A new generation gives the page a new handler closure, so its layout effect
    // re-runs and publishes again — carrying exactly the same values.
    rerender(
      <MemoryRouter>
        <Layout>
          <RepublishingPage generation={2} label="ENTER=Process" />
        </Layout>
      </MemoryRouter>,
    );

    expect(publishedChrome[publishedChrome.length - 1]).toBe(first);
  });

  it('replaces the chrome as soon as a republication changes the legend', () => {
    const { rerender } = render(
      <MemoryRouter>
        <Layout>
          <RepublishingPage generation={1} label="ENTER=Process" />
        </Layout>
      </MemoryRouter>,
    );
    const first = publishedChrome[publishedChrome.length - 1];

    rerender(
      <MemoryRouter>
        <Layout>
          <RepublishingPage generation={2} label="ENTER=Fetch" />
        </Layout>
      </MemoryRouter>,
    );

    expect(publishedChrome[publishedChrome.length - 1]).not.toBe(first);
    expect(screen.getByRole('button', { name: 'ENTER=Fetch' })).toBeInTheDocument();
  });

  it('dispatches the newest handler even when the publication was declined', () => {
    const submitted: number[] = [];
    const onSubmit = (generation: number): void => {
      submitted.push(generation);
    };
    const { rerender } = render(
      <MemoryRouter>
        <Layout>
          <RepublishingPage generation={1} label="ENTER=Process" onSubmit={onSubmit} />
        </Layout>
      </MemoryRouter>,
    );
    const first = publishedChrome[publishedChrome.length - 1];

    rerender(
      <MemoryRouter>
        <Layout>
          <RepublishingPage generation={2} label="ENTER=Process" onSubmit={onSubmit} />
        </Layout>
      </MemoryRouter>,
    );
    // The frame declined the publication, and must still act on the handler it carried.
    expect(publishedChrome[publishedChrome.length - 1]).toBe(first);

    act(() => {
      fireEvent.keyDown(document, { key: 'Enter' });
    });

    expect(submitted).toEqual([2]);
  });
});
